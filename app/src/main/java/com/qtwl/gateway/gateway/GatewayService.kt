package com.qtwl.gateway.gateway

import com.qtwl.gateway.data.db.AppDatabase
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
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.header
import io.ktor.server.response.header
import io.ktor.util.AttributeKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.net.SocketTimeoutException
import java.net.ConnectException
import java.util.UUID
import kotlinx.serialization.json.JsonNull
import kotlinx.coroutines.delay

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
                // 根路径/健康检查
                get("/health") {
                    call.respondText("OK", ContentType.Text.Plain)
                }
                get("/") {
                    call.respondText(
                        contentType = ContentType.Application.Json,
                        text = """{"service":"qitong-ai-gateway","version":"3.2.0","status":"running"}"""
                    )
                }

                // 获取模型列表 (OpenAI Compatible)
                get("/v1/models") {
                    try {
                        val models = database.aiModelDao().getEnabledModelsList()
                        val modelList = models.map { model ->
                            val displayName = if (model.customAlias.isNotBlank()) model.customAlias else model.displayName
                            buildJsonObject {
                                put("id", JsonPrimitive(model.modelId))           // 保持不变！客户端用 id 调 API
                                put("object", JsonPrimitive("model"))
                                put("owned_by", JsonPrimitive("custom"))
                                put("model_id", JsonPrimitive(model.modelId))     // 保留原始 modelId
                                put("display_name", JsonPrimitive(displayName))    // 展示别名
                                put("custom_alias", JsonPrimitive(model.customAlias))
                            }
                        }
                        val response = buildJsonObject {
                            put("object", JsonPrimitive("list"))
                            put("data", JsonArray(modelList))
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

                // === 通用代理：拦截 /v1/* 所有 POST/GET 请求 ===
                // 唯一入口！所有 /v1/ 开头的请求都走这里
                post("/v1/{path...}") {
                    kotlinx.coroutines.runBlocking { proxyRequest(call, database) }
                }
                get("/v1/{path...}") {
                    kotlinx.coroutines.runBlocking { proxyRequest(call, database) }
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

private val proxyJson = Json { ignoreUnknownKeys = true; prettyPrint = false }
private val DEFAULT_CT = "application/json".toMediaType()
private const val STREAM_BUF_SIZE = 32768 // 32KB 大缓冲区，减少IO次数
private const val MAX_RETRIES = 5          // ★ 最大重试次数

/** ★ 带重试的 HTTP 请求执行 */
private suspend fun executeWithRetry(client: okhttp3.OkHttpClient, request: okhttp3.Request, retries: Int = MAX_RETRIES): okhttp3.Response {
    var lastError: Exception? = null
    for (attempt in 1..retries) {
        try {
            val response = client.newCall(request).execute()
            if (response.isSuccessful || attempt == retries) {
                return response
            }
            // 非成功状态码且不是最后一次，关闭响应后重试
            response.close()
            if (attempt < retries) {
                val waitMs = (attempt * 1000L).coerceAtMost(5000L)
                delay(waitMs)
            }
        } catch (e: SocketTimeoutException) {
            lastError = e
            if (attempt < retries) {
                val waitMs = (attempt * 1000L).coerceAtMost(5000L)
                delay(waitMs)
            }
        } catch (e: ConnectException) {
            lastError = e
            if (attempt < retries) {
                val waitMs = (attempt * 1500L).coerceAtMost(5000L)
                delay(waitMs)
            }
        } catch (e: Exception) {
            lastError = e
            if (attempt < retries) {
                delay(1000)
            }
        }
    }
    // 所有重试失败，抛出最后一个错误
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

/**
 * 通用代理转发：读取请求体 → 查找模型(如果是chat请求) → 转发到上游 → 管道式流回客户端
 * 支持图片/视频/音频等任意 Content-Type，数据不落盘，直接 pipe
 * ★ v3.1.0 新增自动故障转移：请求失败时自动切换到其他可用模型
 */
private suspend fun proxyRequest(call: ApplicationCall, database: AppDatabase) {
    // 1. 读取原始请求体（二进制，兼容所有 Content-Type）
    val rawBytes = call.receive<ByteArray>()
    val requestBodyStr = String(rawBytes, Charsets.UTF_8)

    val path = call.parameters.getAll("path")?.joinToString("/") ?: ""

    if (GatewayForegroundService.getDebugMode()) {
        GatewayForegroundService.addDebugLog("→ ${call.request.httpMethod.value} /v1/$path (${rawBytes.size}B)")
    }

    val isChat = path == "chat/completions" || path == "completions"

    if (isChat && requestBodyStr.isNotBlank()) {
        val requestJson = try { proxyJson.parseToJsonElement(requestBodyStr).jsonObject } catch (_: Exception) { null }
        val modelId = requestJson?.get("model")?.jsonPrimitive?.content
        val stream = requestJson?.get("stream")?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false

        if (modelId != null) {
            // ★ 自动故障转移模式
            val autoFailover = GatewayForegroundService.getAutoFailover()
            val failoverModels = if (autoFailover) {
                // 收集所有已启用的模型（按服务商分组，优先同服务商）
                database.aiModelDao().getEnabledModelsList().filter { it.isEnabled }
            } else emptyList()

            // 尝试模型列表：首选请求的模型，故障转移时尝试其他
            val attemptModels = if (autoFailover && failoverModels.isNotEmpty()) {
                // 把请求的模型放第一位，其他模型按序排列（排除已禁用的）
                val primary = failoverModels.find { it.modelId == modelId }
                val others = failoverModels.filter { it.modelId != modelId }
                val ordered = if (primary != null) listOf(primary) + others else failoverModels
                ordered
            } else {
                // 非故障转移模式：只尝试请求的模型
                val allModels = database.aiModelDao().getAllModelsList()
                listOfNotNull(allModels.find { it.modelId == modelId })
            }

            var lastError: String? = null
            for ((idx, matchedModel) in attemptModels.withIndex()) {
                if (idx > 0) {
                    if (GatewayForegroundService.getDebugMode()) {
                        GatewayForegroundService.addDebugLog("↻ 故障转移尝试 #$idx → ${matchedModel.modelId}")
                    }
                }

                if (!matchedModel.isEnabled) continue

                val provider = database.providerDao().getProviderById(matchedModel.providerId)
                if (provider == null || !provider.isEnabled) continue

                try {
                    GatewayForegroundService.trafficUploadBytes += rawBytes.size.toLong()
                    call.attributes.put(MODEL_ID_KEY, matchedModel.modelId)
                    call.attributes.put(PROVIDER_ID_KEY, matchedModel.providerId)
                    val useProxy = matchedModel.useProxy

                    // ★ 修改请求体中的 model 为实际转发的模型
                    val modifiedBody = if (autoFailover && matchedModel.modelId != modelId) {
                        // 替换 model 字段为故障转移的模型ID
                        requestBodyStr.replaceFirst(Regex("\"model\"\\s*:\\s*\"[^\"]+\""), "\"model\":\"${matchedModel.modelId}\"")
                    } else requestBodyStr
                    val modifiedBytes = modifiedBody.toByteArray()

                    if (stream) {
                        pipeStreamResponse(call, provider, modifiedBytes, "/v1/$path", matchedModel.modelId, matchedModel.providerId, database, useProxy)
                    } else {
                        pipeNormalResponse(call, provider, modifiedBytes, "/v1/$path", database, useProxy)
                    }
                    return // 成功！
                } catch (e: Exception) {
                    lastError = "${matchedModel.modelId}: ${e.message}"
                    if (GatewayForegroundService.getDebugMode()) {
                        GatewayForegroundService.addDebugLog("✗ 模型 ${matchedModel.modelId} 失败: ${e.message?.take(60)}")
                    }
                    // 继续尝试下一个模型
                }
            }

            // 所有模型都失败
            val errMsg = if (autoFailover) "All models failed. Last error: $lastError" else "Model '$modelId' error: $lastError"
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
    GatewayForegroundService.trafficUploadBytes += rawBytes.size.toLong()
    pipeNormalResponse(call, defaultProvider, rawBytes, "/v1/$path", database)
}

/**
 * 非流式转发：读取完整上游响应 → 回写客户端
 * 适合图片/视频/音频以及非流式文本
 * 自动解析 usage 字段并记录 Token 用量到数据库
 */
private suspend fun pipeNormalResponse(
    call: ApplicationCall,
    provider: Provider,
    rawBody: ByteArray,
    path: String,
    database: AppDatabase,
    useProxy: Boolean = true  // ★ 新增：是否走代理
) {
    try {
        val resolvedUrl = provider.resolvedBaseUrl.trimEnd('/')
        val url = resolvedUrl + path

        val reqBody = rawBody.toRequestBody(DEFAULT_CT)
        val request = okhttp3.Request.Builder()
            .url(url)
            .post(reqBody)
            .apply {
                if (!provider.apiKey.isNullOrBlank()) {
                    header("Authorization", "Bearer ${provider.apiKey}")
                }
            }
            .build()

        // ★ 根据 useProxy 选择客户端
        val httpClient = if (useProxy) UpstreamClient.getOkHttpClient() else UpstreamClient.getDirectClient()
        // ★ 使用带重试的执行（非流式走 runBlocking）
        val response = kotlinx.coroutines.runBlocking { executeWithRetry(httpClient, request) }

        response.use { resp ->
            val respBytes = resp.body?.bytes() ?: byteArrayOf()
            GatewayForegroundService.trafficDownloadBytes += respBytes.size.toLong()

            val contentType = resp.header("Content-Type") ?: "application/json"
            val statusCode = HttpStatusCode.fromValue(resp.code)
            call.respondBytesWriter(contentType = ContentType.parse(contentType), status = statusCode) {
                writeFully(respBytes)
                flush()
            }

            // 解析 usage 并记录 TokenUsage
            if (path.contains("chat/completions") || path.contains("completions")) {
                kotlinx.coroutines.runBlocking {
                    try {
                        val respStr = respBytes.decodeToString()
                        val respJson = proxyJson.parseToJsonElement(respStr).jsonObject
                        val usage = respJson["usage"]?.jsonObject
                        if (usage != null && call.proxyModelId != null && call.proxyProviderId != null) {
                            val promptTokens = usage["prompt_tokens"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                            val completionTokens = usage["completion_tokens"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                            val totalTokens = usage["total_tokens"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                            if (totalTokens > 0) {
                                database.tokenUsageDao().insert(
                                    TokenUsage(
                                        providerId = call.proxyProviderId!!,
                                        modelId = call.proxyModelId!!,
                                        promptTokens = promptTokens,
                                        completionTokens = completionTokens,
                                        totalTokens = totalTokens
                                    )
                                )
                            }
                        }
                    } catch (_: Exception) { }
                } // 关闭 runBlocking
            } // 关闭 if path.contains

            // ★ Debug 日志 - 响应成功
            if (GatewayForegroundService.getDebugMode()) {
                val modelPreview = if (path.contains("chat/completions")) {
                    try {
                        val respStr = respBytes.decodeToString()
                        val m = proxyJson.parseToJsonElement(respStr).jsonObject["model"]?.jsonPrimitive?.content
                        "model=$m"
                    } catch (_: Exception) { "" }
                } else ""
                GatewayForegroundService.addDebugLog("← ${resp.code} /v1/$path (${respBytes.size}B) $modelPreview")
            }
        } // 关闭 response.use
    } catch (e: Exception) {
        if (GatewayForegroundService.getDebugMode()) {
            GatewayForegroundService.addDebugLog("✗ ERR /v1/$path: ${e.message?.take(80)}")
        }
        // ★ 对于故障转移，需要抛出异常让调用方知道失败
        throw e
    }
}

/**
 * 流式管道直通：上游响应正文逐块转发给客户端
 * ★ 修复：HTTP 请求失败时抛出异常，触发故障转移
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
    // ★★ 先在 respondBytesWriter 外执行 HTTP 请求，失败才能触发故障转移
    val resolvedUrl = provider.resolvedBaseUrl.trimEnd('/')
    val url = resolvedUrl + path

    val reqBody = rawBody.toRequestBody(DEFAULT_CT)
    val request = okhttp3.Request.Builder()
        .url(url)
        .post(reqBody)
        .apply {
            if (!provider.apiKey.isNullOrBlank()) {
                header("Authorization", "Bearer ${provider.apiKey}")
            }
        }
        .build()

    val httpClient = if (useProxy) UpstreamClient.getOkHttpClient() else UpstreamClient.getDirectClient()
    // ★ 在 writer 外执行 HTTP 请求，失败会抛出异常被 proxyRequest 捕获
    val response = executeWithRetry(httpClient, request)

    // ★ 请求成功后，再进入 respondBytesWriter 开始流式输出
    call.respondBytesWriter(
        contentType = ContentType.Text.EventStream,
        status = HttpStatusCode.OK
    ) {
        try {
            call.response.header("Content-Type", "text/event-stream")

            response.use { resp ->
                if (!resp.isSuccessful) {
                    val errBytes = resp.body?.bytes() ?: "Unknown error".toByteArray()
                    val errMsg = errBytes.decodeToString().replace("\"", "\\\"")
                    writeFully("""data: {"error":"$errMsg"}""".toByteArray())
                    writeFully("\n\n".toByteArray())
                    writeFully("data: [DONE]\n\n".toByteArray())
                    flush()
                    return@respondBytesWriter
                }

                val body = resp.body?.byteStream() ?: return@respondBytesWriter
                body.use { input ->
                    val buffer = ByteArray(STREAM_BUF_SIZE)
                    var bytesRead: Int
                    val accumulatedBytes = ByteArrayOutputStream(STREAM_BUF_SIZE)
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        writeFully(buffer, 0, bytesRead)
                        flush()
                        GatewayForegroundService.trafficDownloadBytes += bytesRead.toLong()
                        if (path.contains("chat/completions")) {
                            accumulatedBytes.write(buffer, 0, bytesRead)
                        }
                    }
                    // 流式结束后只解码一次
                    if (path.contains("chat/completions")) {
                        try {
                            val fullStr = accumulatedBytes.toString(Charsets.UTF_8.name())
                            val usageMatch = Regex(""""usage"\s*:\s*\{[^}]+\}""").find(fullStr)
                            if (usageMatch != null) {
                                val usageStr = usageMatch.value
                                val promptTokens = Regex(""""prompt_tokens"\s*:\s*(\d+)""").find(usageStr)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
                                val completionTokens = Regex(""""completion_tokens"\s*:\s*(\d+)""").find(usageStr)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
                                val totalTokens = Regex(""""total_tokens"\s*:\s*(\d+)""").find(usageStr)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
                                if (totalTokens > 0) {
                                    database.tokenUsageDao().insert(
                                        TokenUsage(
                                            providerId = providerId,
                                            modelId = modelId,
                                            promptTokens = promptTokens,
                                            completionTokens = completionTokens,
                                            totalTokens = totalTokens
                                        )
                                    )
                                }
                            }
                        } catch (_: Exception) { }
                    }
                }
            }
        } catch (e: Exception) {
            if (GatewayForegroundService.getDebugMode()) {
                GatewayForegroundService.addDebugLog("✗ STREAM ERR: ${e.message?.take(80)}")
            }
            try {
                writeFully("""data: {"error":"${e.message?.replace("\"", "\\\"") ?: "Unknown"}"}""".toByteArray())
                writeFully("\n\n".toByteArray())
                writeFully("data: [DONE]\n\n".toByteArray())
                flush()
            } catch (_: Exception) { }
        }
    }
}