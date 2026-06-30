package com.qtwl.gateway.gateway

import com.qtwl.gateway.data.db.AppDatabase
import com.qtwl.gateway.data.model.AiModel
import com.qtwl.gateway.data.model.Provider
import com.qtwl.gateway.data.model.TokenUsage
import com.qtwl.gateway.network.UpstreamClient
import com.qtwl.gateway.service.GatewayForegroundService
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receive
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.writeFully
import io.ktor.server.request.httpMethod
import io.ktor.util.AttributeKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.SocketTimeoutException
import java.net.ConnectException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

/**
 * 本地 AI 网关服务（Ktor Server）
 * 运行在手机本地，转发 AI 请求到上游服务商
 * v2.0 — 通用代理模式：支持任意 POST/GET 路径（图片/视频/音频/聊天），流式管道直通
 * v2.1 — 极限吞吐：超长超时 + 大缓冲区 + 无限制body大小
 */
class GatewayService(private val database: AppDatabase) {

    private var server: EmbeddedServer<*, *>? = null
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    /**
     * 启动网关服务器
     * @param port 监听端口，默认 8889
     */
    fun start(port: Int = 8889) {
        if (server != null) return

        val embedded = embeddedServer(CIO, port = port) {
            routing {
                // 健康检查
                get("/health") {
                    call.respondText("OK", ContentType.Text.Plain)
                }

                // 获取模型列表 (OpenAI Compatible)
                get("/v1/models") {
                    try {
                        val models = database.aiModelDao().getEnabledModelsList()
                        val modelList = models.map { model ->
                            val displayName = if (model.customAlias.isNotBlank()) model.customAlias else model.displayName
                            buildJsonObject {
                                put("id", JsonPrimitive(model.modelId))
                                put("object", JsonPrimitive("model"))
                                put("owned_by", JsonPrimitive("custom"))
                                put("model_id", JsonPrimitive(model.modelId))
                                put("display_name", JsonPrimitive(displayName))
                                put("custom_alias", JsonPrimitive(model.customAlias))
                            }
                        }
                        // ★★ 加入 qtai-sj 虚拟模型（第三方APP也能选）★★
                        val finalList = modelList + buildJsonObject {
                            put("id", JsonPrimitive("qtai-sj"))
                            put("object", JsonPrimitive("model"))
                            put("owned_by", JsonPrimitive("qitong"))
                            put("model_id", JsonPrimitive("qtai-sj"))
                            put("display_name", JsonPrimitive("⚡ 綦桐AI测速"))
                            put("custom_alias", JsonPrimitive(""))
                        }
                        val response = buildJsonObject {
                            put("object", JsonPrimitive("list"))
                            put("data", JsonArray(finalList))
                        }
                        call.respondText(
                            contentType = ContentType.Application.Json,
                            text = response.toString()
                        )
                    } catch (e: Exception) {
                        val (status, body) = openAIError(HttpStatusCode.InternalServerError, "Failed to fetch models: ${e.message}", "server_error")
                        call.respondText(contentType = ContentType.Application.Json, status = status, text = body)
                    }
                }

                // === 通用代理转发：拦截所有 /v1/* 请求 ===
                // ★★ 去掉了 runBlocking！Ktor 路由 handler 本身就在协程中
                post("/v1/{path...}") {
                    proxyRequest(call, database)
                }
                get("/v1/{path...}") {
                    proxyRequest(call, database)
                }
            }
        }
        server = embedded.start(wait = false)
    }

    fun stop() {
        server?.stop(1000, 2000)
        server = null
    }

    val isRunning: Boolean get() = server != null
}

// ================== 通用代理转发核心 ==================

/** 智能故障转移：模型健康状态缓存 */

/** 流水线测速结果排序（最快的在前），由 ViewModel 更新，供故障转移优先切换使用 */
@Volatile
var pipelineSortedModelIds: List<String> = emptyList()

private val proxyJson = Json { ignoreUnknownKeys = true; prettyPrint = false }
private val DEFAULT_CT = "application/json".toMediaType()
private const val MAX_RETRIES = 5
private const val HEALTH_CHECK_TIMEOUT = 5000L
private val failoverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/** 模型健康状态 */
private data class ModelHealth(
    val modelId: String,
    val providerId: Long,
    val latencyMs: Long = Long.MAX_VALUE,
    val lastCheckTime: Long = 0,
    val isHealthy: Boolean = true,
    val successCount: Int = 0      // ★ 连续成功次数
)

/** 模型健康缓存 */
private val healthCache = mutableMapOf<String, ModelHealth>()
private var cacheTime: Long = 0
private const val CACHE_TTL = 60_000L

/** ★ 最优模型ID（自动记住最快的模型） */
@Volatile
private var bestModelId: String? = null
@Volatile
private var bestModelLatency: Long = Long.MAX_VALUE
private var bestModelSetTime: Long = 0
private const val BEST_MODEL_TTL = 300_000L // 最优模型缓存5分钟

/** 标记模型为成功，自动更新最优模型 */
private fun markModelSuccess(modelId: String, latencyMs: Long) {
    synchronized(healthCache) {
        val existing = healthCache[modelId]
        val successCount = (existing?.successCount ?: 0) + 1
        healthCache[modelId] = ModelHealth(modelId, existing?.providerId ?: 0, latencyMs, System.currentTimeMillis(), true, successCount)
    }
    // 如果这个模型比当前最优模型快，或者最优模型未设置，更新最优模型
    if (latencyMs < bestModelLatency || bestModelId == null) {
        bestModelId = modelId
        bestModelLatency = latencyMs
        bestModelSetTime = System.currentTimeMillis()
    }
}

/** 获取最优模型ID（如果缓存未过期） */
private fun getBestModel(): String? {
    if (bestModelId != null && System.currentTimeMillis() - bestModelSetTime < BEST_MODEL_TTL) {
        return bestModelId
    }
    return null
}
/** ★ 刷新健康缓存 — 只测试已启用且最近被使用的模型 */
private suspend fun refreshHealthCache(database: AppDatabase) {
    val now = System.currentTimeMillis()
    if (now - cacheTime < CACHE_TTL) return

    val models = database.aiModelDao().getEnabledModelsList().filter { it.isEnabled }
    if (models.isEmpty()) return

    cacheTime = now

    // ★ 只测试当前网关正在使用的模型（有 activeNodeName 或最近被请求过的）
    // 如果是首次开启，测试所有启用模型
    val targetModels = if (recentlyUsedModels.isEmpty()) {
        models
    } else {
        models.filter { it.modelId in recentlyUsedModels }
    }

    if (targetModels.isEmpty()) return

    targetModels.forEach { model ->
        failoverScope.launch {
            try {
                val provider = database.providerDao().getProviderById(model.providerId) ?: return@launch
                if (!provider.isEnabled) return@launch

                val start = System.currentTimeMillis()
                val resolvedUrl = provider.resolvedBaseUrl.trimEnd('/')
                val testBody = """{"model":"${model.modelId}","messages":[{"role":"user","content":"hi"}],"max_tokens":1,"stream":false}"""
                val req = okhttp3.Request.Builder()
                    .url("$resolvedUrl/v1/chat/completions")
                    .post(testBody.toRequestBody(DEFAULT_CT))
                    .apply { if (!provider.apiKey.isNullOrBlank()) header("Authorization", "Bearer ${provider.apiKey}") }
                    .build()
                val client = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(HEALTH_CHECK_TIMEOUT, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .readTimeout(HEALTH_CHECK_TIMEOUT, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .build()
                val resp = client.newCall(req).execute()
                val latency = System.currentTimeMillis() - start
                if (resp.isSuccessful) {
                    synchronized(healthCache) {
                        healthCache[model.modelId] = ModelHealth(model.modelId, model.providerId, latency, now, true)
                    }
                    if (GatewayForegroundService.getDebugMode()) {
                        GatewayForegroundService.addDebugLog("⏱ ${model.modelId}: ${latency}ms ✅")
                    }
                } else {
                    synchronized(healthCache) { healthCache[model.modelId] = ModelHealth(model.modelId, model.providerId, Long.MAX_VALUE, now, false) }
                }
                resp.close()
            } catch (_: Exception) {
                synchronized(healthCache) { healthCache[model.modelId] = ModelHealth(model.modelId, model.providerId, Long.MAX_VALUE, System.currentTimeMillis(), false) }
            }
        }
    }
}

/** ★ 记录最近使用的模型 */
private val recentlyUsedModels = mutableSetOf<String>()

/** ★ 记录模型被使用 */
private fun recordModelUsage(modelId: String) {
    recentlyUsedModels.add(modelId)
}

/** 按健康状态排序：快的在前，不可用的在后 */
private fun getSortedModels(models: List<AiModel>, preferredModelId: String?): List<AiModel> {
    val preferred = models.find { it.modelId == preferredModelId }
    val others = models.filter { it.modelId != preferredModelId }

    val sortedOthers = others.sortedBy { model ->
        val health = synchronized(healthCache) { healthCache[model.modelId] }
        if (health != null && health.isHealthy) health.latencyMs else Long.MAX_VALUE
    }

    return if (preferred != null) listOf(preferred) + sortedOthers else sortedOthers
}

/** ★ 修正请求体中的参数，确保符合 OpenAI 标准和各模型限制 */
private fun sanitizeRequestBody(bodyStr: String): String {
    try {
        val json = proxyJson.parseToJsonElement(bodyStr).jsonObject
        val sb = StringBuilder(bodyStr)
        
        sb.replace(Regex(""""temperature"\s*:\s*([\d.]+)""")) { match ->
            val value = match.groupValues[1].toDoubleOrNull()
            if (value != null) { val clamped = value.coerceIn(0.0, 1.999); if (clamped != value) "\"temperature\":$clamped" else match.value } else match.value
        }
        sb.replace(Regex(""""top_p"\s*:\s*([\d.]+)""")) { match ->
            val value = match.groupValues[1].toDoubleOrNull()
            if (value != null) { val clamped = value.coerceIn(0.0, 1.0); if (clamped != value) "\"top_p\":$clamped" else match.value } else match.value
        }
        sb.replace(Regex(""""(presence_penalty|frequency_penalty)"\s*:\s*([-\d.]+)""")) { match ->
            val value = match.groupValues[2].toDoubleOrNull()
            if (value != null) { val clamped = value.coerceIn(-2.0, 2.0); if (clamped != value) "\"${match.groupValues[1]}\":$clamped" else match.value } else match.value
        }
        sb.replace(Regex(""""max_tokens"\s*:\s*(\d+)""")) { match ->
            val value = match.groupValues[1].toIntOrNull()
            if (value != null) { val clamped = value.coerceIn(1, 128000); if (clamped != value) "\"max_tokens\":$clamped" else match.value } else match.value
        }
        return sb.toString()
    } catch (_: Exception) { return bodyStr }
}

private suspend fun executeWithRetry(client: okhttp3.OkHttpClient, request: okhttp3.Request, retries: Int = MAX_RETRIES): okhttp3.Response {
    var lastError: Exception? = null
    for (attempt in 1..retries) {
        try {
            // ★★ OkHttp execute() 是阻塞的，用 IO 调度器避免卡死 Ktor
            val response = withContext(Dispatchers.IO) { client.newCall(request).execute() }
            if (response.isSuccessful || attempt == retries) {
                return response
            }
            response.close()
            if (attempt < retries) {
                val waitMs = (attempt * 1000L).coerceAtMost(5000L)
                delay(waitMs)
            }
        } catch (e: SocketTimeoutException) {
            lastError = e
            if (attempt < retries) { delay((attempt * 1000L).coerceAtMost(5000L)) }
        } catch (e: ConnectException) {
            lastError = e
            if (attempt < retries) { delay((attempt * 1500L).coerceAtMost(5000L)) }
        } catch (e: Exception) {
            lastError = e
            if (attempt < retries) { delay(1000) }
        }
    }
    throw lastError ?: Exception("Request failed after $retries retries")
}

/** OpenAI 标准错误响应 */
private fun openAIError(status: HttpStatusCode, message: String, type: String = "invalid_request_error", code: Int? = null): Pair<HttpStatusCode, String> {
    val errorJson = buildJsonObject {
        put("error", buildJsonObject {
            put("message", JsonPrimitive(message))
            put("type", JsonPrimitive(type))
            put("param", JsonNull)
            put("code", if (code != null) JsonPrimitive(code) else JsonNull)
        })
    }
    return status to errorJson.toString()
}

/** 生成 OpenAI 标准 chat.completion 响应（用于回退/测试） */
private fun makeChatCompletionResponse(modelId: String, content: String, stream: Boolean = false): String {
    val id = "chatcmpl-${UUID.randomUUID().toString().take(8)}"
    val created = System.currentTimeMillis() / 1000
    if (stream) {
        return buildJsonObject {
            put("id", JsonPrimitive(id))
            put("object", JsonPrimitive("chat.completion.chunk"))
            put("created", JsonPrimitive(created))
            put("model", JsonPrimitive(modelId))
            put("choices", JsonArray(listOf(buildJsonObject {
                put("index", JsonPrimitive(0))
                put("delta", buildJsonObject {
                    put("role", JsonPrimitive("assistant"))
                    put("content", JsonPrimitive(content))
                })
                put("finish_reason", JsonPrimitive("stop"))
            })))
        }.toString()
    }
    return buildJsonObject {
        put("id", JsonPrimitive(id))
        put("object", JsonPrimitive("chat.completion"))
        put("created", JsonPrimitive(created))
        put("model", JsonPrimitive(modelId))
        put("choices", JsonArray(listOf(buildJsonObject {
            put("index", JsonPrimitive(0))
            put("message", buildJsonObject {
                put("role", JsonPrimitive("assistant"))
                put("content", JsonPrimitive(content))
            })
            put("finish_reason", JsonPrimitive("stop"))
        })))
        put("usage", buildJsonObject {
            put("prompt_tokens", JsonPrimitive(0))
            put("completion_tokens", JsonPrimitive(0))
            put("total_tokens", JsonPrimitive(0))
        })
    }.toString()
}

// Attribute keys 用于在 call 中传递 modelId / providerId
private val MODEL_ID_KEY = AttributeKey<String>("proxyModelId")
private val PROVIDER_ID_KEY = AttributeKey<Long>("proxyProviderId")

private val ApplicationCall.proxyModelId: String? get() = attributes.getOrNull(MODEL_ID_KEY)
private val ApplicationCall.proxyProviderId: Long? get() = attributes.getOrNull(PROVIDER_ID_KEY)

/** ★ 会话记忆：源IP → 最后成功使用的模型ID */
private val sessionModelCache = mutableMapOf<String, String>()

/** ★ 记录会话成功使用的模型 */
private fun recordSessionModel(call: ApplicationCall, modelId: String) {
    val sessionKey = getSessionKey(call)
    if (sessionKey.isNotBlank()) {
        sessionModelCache[sessionKey] = modelId
    }
}

/** ★ 获取会话标识（优先用 API Key，其次用客户端IP） */
private fun getSessionKey(call: ApplicationCall): String {
    // 用 Authorization header 作为会话标识（同一 API Key 视为同一用户）
    val auth = call.request.headers["Authorization"]
    if (!auth.isNullOrBlank()) return auth.take(20)
    // 用客户端 IP
    val ip = call.request.local.remoteHost
    if (ip.isNotBlank()) return "ip:$ip"
    return ""
}

/**
 * 通用代理转发：读取请求体 → 查找模型(如果是chat请求) → 转发到上游 → 管道式流回客户端
 * 支持图片/视频/音频等任意 Content-Type，数据不落盘，直接 pipe
 * ★ v3.1.0 新增自动故障转移：请求失败时自动切换到其他可用模型
 * ★ v3.3.2 新增会话记忆：同一会话失败的模型自动跳过，走上次成功的模型
 */
private suspend fun proxyRequest(call: ApplicationCall, database: AppDatabase) {
    // 1. 读取原始请求体（二进制，兼容所有 Content-Type）
    val rawBytes = call.receive<ByteArray>()
    val requestBodyStr = String(rawBytes, Charsets.UTF_8)

    val path = call.parameters.getAll("path")?.joinToString("/") ?: ""

    // ★★ 如果 path 为空但 body 是 JSON 且有 model 字段 → 自动转成 /v1/chat/completions
    val effectivePath = if (path.isBlank()) {
        if (requestBodyStr.isNotBlank()) {
            try {
                val j = proxyJson.parseToJsonElement(requestBodyStr).jsonObject
                if (j.containsKey("model") || j.containsKey("messages")) "chat/completions" else path
            } catch (_: Exception) { path }
        } else path
    } else path

    if (GatewayForegroundService.getDebugMode()) {
        GatewayForegroundService.addDebugLog("→ ${call.request.httpMethod.value} /$effectivePath (${rawBytes.size}B)")
    }

    val isChat = effectivePath == "chat/completions" || effectivePath == "completions"

    if (isChat && requestBodyStr.isNotBlank()) {
        val requestJson = try { proxyJson.parseToJsonElement(requestBodyStr).jsonObject } catch (_: Exception) { null }
        val modelId = requestJson?.get("model")?.jsonPrimitive?.content
        val stream = requestJson?.get("stream")?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false

        if (modelId != null) {
            val autoFailover = GatewayForegroundService.getAutoFailover()

            if (autoFailover) {
                refreshHealthCache(database)
            }
val allEnabled = database.aiModelDao().getEnabledModelsList().filter { it.isEnabled }
            val attemptModels: List<AiModel> = if (allEnabled.isNotEmpty()) {
                // ★★ qtai-sj 模式：不管autoFailover开关，永远走排行榜 ★★
                if (modelId == "qtai-sj") {
                    val sorted = if (pipelineSortedModelIds.isNotEmpty()) {
                        pipelineSortedModelIds.mapNotNull { id -> allEnabled.find { it.modelId == id } }
                    } else {
                        allEnabled
                    }
                    sorted.ifEmpty { allEnabled }
                } else if (autoFailover) {
                    // ★★ 其他模型 + 故障转移开启：用户权威模式
                    val primary = allEnabled.find { it.modelId == modelId }
                    val sessionKey = getSessionKey(call)
                    val lastGoodModel = sessionModelCache[sessionKey]

                    val pipelineSorted = if (pipelineSortedModelIds.isNotEmpty()) {
                        pipelineSortedModelIds.mapNotNull { id -> allEnabled.find { it.modelId == id } }
                    } else {
                        allEnabled
                    }
                    val ordered = listOfNotNull(primary) + pipelineSorted.filter { it.modelId != modelId }

                    if (lastGoodModel != null && lastGoodModel != modelId && ordered.any { it.modelId == lastGoodModel }) {
                        val rest = ordered.filter { it.modelId != lastGoodModel }
                        listOfNotNull(primary) + listOfNotNull(ordered.find { it.modelId == lastGoodModel }) + rest.filter { it.modelId != modelId }
                    } else {
                        ordered
                    }
                } else {
                    listOfNotNull(allEnabled.find { it.modelId == modelId })
                }
            } else {
                emptyList()
            }

            var lastError: String? = null
            var failCount = 0
            for ((idx, matchedModel) in attemptModels.withIndex()) {
                if (idx > 0 && GatewayForegroundService.getDebugMode()) {
                    GatewayForegroundService.addDebugLog("↻ 故障转移 #$idx → ${matchedModel.modelId}")
                }

                if (!matchedModel.isEnabled) continue
                val provider = database.providerDao().getProviderById(matchedModel.providerId)
                if (provider == null || !provider.isEnabled) continue

                // ★★ 切换模型前先快速测试连通性（跟测速一样），不通就跳过
                if (idx > 0) {
                    try {
                        val testBody = """{"model":"${matchedModel.modelId}","messages":[{"role":"user","content":"hi"}],"max_tokens":1,"stream":false}"""
                        val testUrl = provider.resolvedBaseUrl.trimEnd('/') + "/v1/chat/completions"
                        val testReq = okhttp3.Request.Builder()
                            .url(testUrl)
                            .post(testBody.toRequestBody(DEFAULT_CT))
                            .apply { if (!provider.apiKey.isNullOrBlank()) header("Authorization", "Bearer ${provider.apiKey}") }
                            .build()
                        val testClient = okhttp3.OkHttpClient.Builder()
                            .connectTimeout(5, TimeUnit.SECONDS)
                            .readTimeout(5, TimeUnit.SECONDS)
                            .build()
                        val testResp = withContext(Dispatchers.IO) { testClient.newCall(testReq).execute() }
                        val testOk = testResp.isSuccessful
                        val testBodyStr = testResp.body?.string() ?: ""
                        testResp.close()
                        if (!testOk) {
                            failCount++
                            lastError = "${matchedModel.modelId}: 预检测失败"
                            synchronized(healthCache) { healthCache[matchedModel.modelId] = ModelHealth(matchedModel.modelId, matchedModel.providerId, Long.MAX_VALUE, System.currentTimeMillis(), false) }
                            if (GatewayForegroundService.getDebugMode()) GatewayForegroundService.addDebugLog("✗ 预检测 ${matchedModel.modelId}: 不通→跳过")
                            continue
                        }
                        if (GatewayForegroundService.getDebugMode()) GatewayForegroundService.addDebugLog("✓ 预检测 ${matchedModel.modelId}: 通过→转发请求")
                    } catch (e: Exception) {
                        failCount++
                        lastError = "${matchedModel.modelId}: 预检测异常 ${e.message?.take(40)}"
                        synchronized(healthCache) { healthCache[matchedModel.modelId] = ModelHealth(matchedModel.modelId, matchedModel.providerId, Long.MAX_VALUE, System.currentTimeMillis(), false) }
                        if (GatewayForegroundService.getDebugMode()) GatewayForegroundService.addDebugLog("✗ 预检测 ${matchedModel.modelId}: ${e.message?.take(40)}→跳过")
                        continue
                    }
                }

                try {
                    GatewayForegroundService.trafficUploadBytes.addAndGet(rawBytes.size.toLong())
                    call.attributes.put(MODEL_ID_KEY, matchedModel.modelId)
                    call.attributes.put(PROVIDER_ID_KEY, matchedModel.providerId)
                    GatewayForegroundService.activeNodeName = matchedModel.modelId
                    recordModelUsage(matchedModel.modelId)
                    val useProxy = matchedModel.useProxy

                    val sanitizedBody = sanitizeRequestBody(requestBodyStr)
                    // ★★ qtai-sj 或故障转移切模型时，都要替换 body 里的 model 字段 ★★
                    val needReplaceModel = modelId == "qtai-sj" || (autoFailover && matchedModel.modelId != modelId)
                    val modifiedBody = if (needReplaceModel) {
                        sanitizedBody.replaceFirst(Regex("\"model\"\\s*:\\s*\"[^\"]+\""), "\"model\":\"${matchedModel.modelId}\"")
                    } else sanitizedBody
                    val modifiedBytes = modifiedBody.toByteArray()

                    if (stream) {
                        pipeStreamResponse(call, provider, modifiedBytes, "/v1/$effectivePath", matchedModel.modelId, matchedModel.providerId, database, useProxy)
                    } else {
                        pipeNormalResponse(call, provider, modifiedBytes, "/v1/$effectivePath", database, useProxy)
                    }
                    
                    // ★★ 记录会话成功模型
                    recordSessionModel(call, matchedModel.modelId)
                    return
                } catch (e: Exception) {
                    failCount++
                    lastError = "${matchedModel.modelId}: ${e.message}"
                    synchronized(healthCache) { healthCache[matchedModel.modelId] = ModelHealth(matchedModel.modelId, matchedModel.providerId, Long.MAX_VALUE, System.currentTimeMillis(), false) }
                    if (GatewayForegroundService.getDebugMode()) GatewayForegroundService.addDebugLog("✗ ${matchedModel.modelId}: ${e.message?.take(60)}")
                }
            }

            val errMsg = if (autoFailover) "All ${failCount} models failed. Last: $lastError" else "Model '$modelId' error: $lastError"
            val (status, body) = openAIError(HttpStatusCode.ServiceUnavailable, errMsg, "upstream_error")
            call.respondText(contentType = ContentType.Application.Json, status = status, text = body)
            return
        }
    }

    // 3. 非 chat 请求 → 通用转发
    val providers = database.providerDao().getAllProvidersList()
    val defaultProvider = providers.firstOrNull { it.isEnabled }
    if (defaultProvider == null) {
        val (status, body) = openAIError(HttpStatusCode.BadRequest, "No enabled provider available.", "provider_error")
        call.respondText(contentType = ContentType.Application.Json, status = status, text = body)
        return
    }
    GatewayForegroundService.trafficUploadBytes.addAndGet(rawBytes.size.toLong())
    pipeNormalResponse(call, defaultProvider, rawBytes, "/v1/$effectivePath", database)
}

/**
 * 非流式转发：读取完整上游响应 → 回写客户端
 * ★★ 如果上游返回 4xx/5xx（非成功），抛异常触发故障转移
 */
private suspend fun pipeNormalResponse(
    call: ApplicationCall,
    provider: Provider,
    rawBody: ByteArray,
    path: String,
    database: AppDatabase,
    useProxy: Boolean = true
) {
    try {
        val resolvedUrl = provider.resolvedBaseUrl.trimEnd('/')
        val url = resolvedUrl + path
        val pipeStartTime = System.currentTimeMillis()

        val reqBody = rawBody.toRequestBody(DEFAULT_CT)
        val request = okhttp3.Request.Builder()
            .url(url)
            .post(reqBody)
            .apply {
                if (!provider.apiKey.isNullOrBlank()) header("Authorization", "Bearer ${provider.apiKey}")
            }
            .build()

        val httpClient = if (useProxy) UpstreamClient.getOkHttpClient() else UpstreamClient.getDirectClient()
        
        var respBytes: ByteArray = byteArrayOf()
        var contentType: String = "application/json"
        var statusCode: HttpStatusCode = HttpStatusCode.OK
        var respCode: Int = 200
        withContext(Dispatchers.IO) {
            val response = executeWithRetry(httpClient, request)
            response.use { resp ->
                respBytes = resp.body?.bytes() ?: byteArrayOf()
                GatewayForegroundService.trafficDownloadBytes.addAndGet(respBytes.size.toLong())
                contentType = resp.header("Content-Type") ?: "application/json"
                statusCode = HttpStatusCode.fromValue(resp.code)
                respCode = resp.code
                
                // ★★ 关键修复：上游返回 4xx/5xx，抛出异常触发故障转移！
                if (!resp.isSuccessful) {
                    val errBody = respBytes.decodeToString().take(200)
                    throw Exception("Upstream ${resp.code}: $errBody")
                }
            }
        }

        // 成功响应，写回客户端
        call.respondBytesWriter(contentType = ContentType.parse(contentType), status = statusCode) {
            writeFully(respBytes)
            flush()
        }

        // ★★ 记入最优模型（用真实请求耗时作为延迟参考）
        markModelSuccess(call.proxyModelId ?: "unknown", System.currentTimeMillis() - pipeStartTime)

        // 解析 usage
        if (path.contains("chat/completions") || path.contains("completions")) {
            withContext(Dispatchers.IO) {
                try {
                    val respStr = respBytes.decodeToString()
                    val respJson = proxyJson.parseToJsonElement(respStr).jsonObject
                    val usage = respJson["usage"]?.jsonObject
                    if (usage != null && call.proxyModelId != null && call.proxyProviderId != null) {
                        val promptTokens = usage["prompt_tokens"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                        val completionTokens = usage["completion_tokens"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                        val totalTokens = usage["total_tokens"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                        if (totalTokens > 0) {
                            database.tokenUsageDao().insert(TokenUsage(
                                providerId = call.proxyProviderId!!, modelId = call.proxyModelId!!,
                                promptTokens = promptTokens, completionTokens = completionTokens, totalTokens = totalTokens
                            ))
                        }
                    }
                } catch (_: Exception) { }
            }
        }

        if (GatewayForegroundService.getDebugMode()) {
            val modelPreview = if (path.contains("chat/completions")) {
                try { "model=${proxyJson.parseToJsonElement(respBytes.decodeToString()).jsonObject["model"]?.jsonPrimitive?.content}" } catch (_: Exception) { "" }
            } else ""
            GatewayForegroundService.addDebugLog("← $respCode /v1/$path (${respBytes.size}B) $modelPreview")
        }
    } catch (e: Exception) {
        if (GatewayForegroundService.getDebugMode()) GatewayForegroundService.addDebugLog("✗ ERR /v1/$path: ${e.message?.take(80)}")
        throw e
    }
}

/**
 * 流式管道直通：上游响应正文逐块转发给客户端
 * ★★ 核心修复：边读边写，不再全量缓冲，消除卡顿！
 * 读流在 IO 线程，写响应在 CIO 线程，互不阻塞
 */
private suspend fun pipeStreamResponse(
    call: ApplicationCall,
    provider: Provider,
    rawBody: ByteArray,
    path: String,
    modelId: String,
    providerId: Long,
    database: AppDatabase,
    useProxy: Boolean = true
) {
    // 1. 在 IO 线程执行 HTTP 请求，获取响应流
    val resolvedUrl = provider.resolvedBaseUrl.trimEnd('/')
    val url = resolvedUrl + path
    val httpClient = if (useProxy) UpstreamClient.getOkHttpClient() else UpstreamClient.getDirectClient()

    // 在 IO 线程发起请求，拿到 response 对象（不读 body）
    val response = withContext(Dispatchers.IO) {
        try {
            val reqBody = rawBody.toRequestBody(DEFAULT_CT)
            val request = okhttp3.Request.Builder()
                .url(url).post(reqBody)
                .apply { if (!provider.apiKey.isNullOrBlank()) header("Authorization", "Bearer ${provider.apiKey}") }
                .build()
            val resp = executeWithRetry(httpClient, request)
            resp
        } catch (e: Exception) {
            if (GatewayForegroundService.getDebugMode()) GatewayForegroundService.addDebugLog("✗ STREAM HTTP ERR: ${e.message?.take(80)}")
            throw e
        }
    }

    if (!response.isSuccessful) {
        val errBody = withContext(Dispatchers.IO) { response.body?.bytes()?.decodeToString()?.take(200) ?: "Unknown" }
        response.close()
        throw Exception("Upstream stream ${response.code}: $errBody")
    }

    val ct = response.header("Content-Type") ?: "text/event-stream"
    val respStatus = HttpStatusCode.fromValue(response.code)
    val bodyStream = response.body?.byteStream() ?: return

    // 2. 在 CIO 线程上启动流式写，从 IO 流读取并逐块转发
    call.respondBytesWriter(contentType = ContentType.parse(ct), status = respStatus) {
        val buffer = ByteArray(4096)  // 4KB 小缓冲区，延迟最低
        val accumulatedBytes = java.io.ByteArrayOutputStream(32768)
        var bytesRead: Int

        try {
            while (true) {
                bytesRead = withContext(Dispatchers.IO) {
                    try { bodyStream.read(buffer) } catch (_: Exception) { -1 }
                }
                if (bytesRead == -1) break
                
                writeFully(buffer, 0, bytesRead)
                flush()
                GatewayForegroundService.trafficDownloadBytes.addAndGet(bytesRead.toLong())
                if (path.contains("chat/completions")) {
                    accumulatedBytes.write(buffer, 0, bytesRead)
                }
            }

            // 流结束后解析 usage
            if (path.contains("chat/completions")) {
                withContext(Dispatchers.IO) {
                    try {
                        val fullStr = accumulatedBytes.toString(Charsets.UTF_8.name())
                        val usageMatch = Regex(""""usage"\s*:\s*\{[^{}]+\}""").find(fullStr)
                        if (usageMatch != null) {
                            val usageStr = usageMatch.value
                            val pt = Regex(""""prompt_tokens"\s*:\s*(\d+)""").find(usageStr)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
                            val ctok = Regex(""""completion_tokens"\s*:\s*(\d+)""").find(usageStr)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
                            val tt = Regex(""""total_tokens"\s*:\s*(\d+)""").find(usageStr)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
                            if (tt > 0) database.tokenUsageDao().insert(TokenUsage(providerId = providerId, modelId = modelId, promptTokens = pt, completionTokens = ctok, totalTokens = tt))
                        }
                    } catch (_: Exception) { }
                }
            }
        } catch (e: Exception) {
            if (GatewayForegroundService.getDebugMode()) GatewayForegroundService.addDebugLog("✗ STREAM WRITE ERR: ${e.message?.take(80)}")
        } finally {
            withContext(Dispatchers.IO) { try { bodyStream.close(); response.close() } catch (_: Exception) { } }
        }
    }
}