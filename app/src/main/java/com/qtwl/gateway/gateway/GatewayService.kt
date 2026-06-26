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
import java.util.UUID
import kotlinx.serialization.json.JsonNull

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
                // 匹配 /v1/chat/completions, /v1/images/generations, /v1/audio/transcriptions, /v1/embeddings, /v1/completions 等一切路径
                post("/v1/{path...}") {
                    kotlinx.coroutines.runBlocking { proxyRequest(call, database) }
                }
                get("/v1/{path...}") {
                    kotlinx.coroutines.runBlocking { proxyRequest(call, database) }
                }
                // ★ 新增：显式支持 embeddings / completions / moderations（某些客户端直接发送这些路径）
                post("/v1/embeddings") {
                    kotlinx.coroutines.runBlocking { proxyRequest(call, database) }
                }
                post("/v1/completions") {
                    kotlinx.coroutines.runBlocking { proxyRequest(call, database) }
                }
                post("/v1/moderations") {
                    kotlinx.coroutines.runBlocking { proxyRequest(call, database) }
                }
                post("/v1/images/generations") {
                    kotlinx.coroutines.runBlocking { proxyRequest(call, database) }
                }
                post("/v1/images/edits") {
                    kotlinx.coroutines.runBlocking { proxyRequest(call, database) }
                }
                post("/v1/audio/transcriptions") {
                    kotlinx.coroutines.runBlocking { proxyRequest(call, database) }
                }
                post("/v1/audio/translations") {
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
 */
private suspend fun proxyRequest(call: ApplicationCall, database: AppDatabase) {
    // 1. 读取原始请求体（二进制，兼容所有 Content-Type）
    val rawBytes = call.receive<ByteArray>()
    val requestBodyStr = String(rawBytes, Charsets.UTF_8)

    // 2. 判断是否为 chat 请求（需要路由到特定模型对应的服务商）
    val path = call.parameters.getAll("path")?.joinToString("/") ?: ""

    // ★ Debug 日志
    if (GatewayForegroundService.getDebugMode()) {
        GatewayForegroundService.addDebugLog("→ ${call.request.httpMethod.value} /v1/$path (${rawBytes.size}B)")
    }

    val isChat = path == "chat/completions" || path == "completions"

    if (isChat && requestBodyStr.isNotBlank()) {
        // 解析 model 字段，路由到对应服务商
        val requestJson = try {
            proxyJson.parseToJsonElement(requestBodyStr).jsonObject
        } catch (_: Exception) { null }

        val modelId = requestJson?.get("model")?.jsonPrimitive?.content
        val stream = requestJson?.get("stream")?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false

        if (modelId != null) {
            val allModels = database.aiModelDao().getAllModelsList()
            val matchedModel = allModels.find { it.modelId == modelId }
            if (matchedModel == null) {
                val (status, body) = openAIError(HttpStatusCode.NotFound, "The model '$modelId' does not exist", "invalid_model_error")
                call.respondText(contentType = ContentType.Application.Json, status = status, text = body)
                return
            }
            if (!matchedModel.isEnabled) {
                val (status, body) = openAIError(HttpStatusCode.Forbidden, "Model '$modelId' is disabled", "model_disabled")
                call.respondText(contentType = ContentType.Application.Json, status = status, text = body)
                return
            }

            val provider = database.providerDao().getProviderById(matchedModel.providerId)
            if (provider == null || !provider.isEnabled) {
                val (status, body) = openAIError(HttpStatusCode.BadRequest, "Provider for model '$modelId' is disabled or not found", "provider_error")
                call.respondText(contentType = ContentType.Application.Json, status = status, text = body)
                return
            }

            // 统计上传流量
            GatewayForegroundService.trafficUploadBytes += rawBytes.size.toLong()

            // 记录 modelId / providerId 到 call attributes，供 pipe 函数记录 TokenUsage
            call.attributes.put(MODEL_ID_KEY, matchedModel.modelId)
            call.attributes.put(PROVIDER_ID_KEY, matchedModel.providerId)

            // ★ 按模型粒度决定是否走代理
            val useProxy = matchedModel.useProxy

            if (stream) {
                // 流式：管道直通（传入 useProxy 参数）
                pipeStreamResponse(call, provider, rawBytes, "/v1/$path", matchedModel.modelId, matchedModel.providerId, database, useProxy)
            } else {
                // 非流式：转发并回复（传入 useProxy 参数）
                pipeNormalResponse(call, provider, rawBytes, "/v1/$path", database, useProxy)
            }
            return
        }
    }

    // 3. 非 chat 请求 或 没有 model 字段 → 尝试通用转发到默认服务商
    val providers = database.providerDao().getAllProvidersList()
    val defaultProvider = providers.firstOrNull { it.isEnabled }
    if (defaultProvider == null) {
        val (status, body) = openAIError(HttpStatusCode.BadRequest, "No enabled provider available. Please add and enable a provider first.", "provider_error")
        call.respondText(contentType = ContentType.Application.Json, status = status, text = body)
        return
    }

    // 统计上传流量
    GatewayForegroundService.trafficUploadBytes += rawBytes.size.toLong()

    // 非流式转发（图片/音频等一律非流式）
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
        val response = httpClient.newCall(request).execute()

        response.use { resp ->
            val respBytes = resp.body?.bytes() ?: byteArrayOf()
            GatewayForegroundService.trafficDownloadBytes += respBytes.size.toLong()

            val contentType = resp.header("Content-Type") ?: "application/json"
            call.response.header("Content-Type", contentType)
            call.respondBytesWriter(status = HttpStatusCode.fromValue(resp.code)) {
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
        val (status, body) = openAIError(HttpStatusCode.BadGateway, "Upstream request failed: ${e.message}", "upstream_error")
        call.respondText(contentType = ContentType.Application.Json, status = status, text = body)
    }
}

/**
 * 流式管道直通：上游响应正文逐块转发给客户端
 * 32KB 大缓冲区 + 每块 flush，延迟降到最低
 * 自动从 data: ... 行解析 usage 并记录 Token 用量
 */
private suspend fun pipeStreamResponse(
    call: ApplicationCall,
    provider: Provider,
    rawBody: ByteArray,
    path: String,
    modelId: String,
    providerId: Long,
    database: AppDatabase,
    useProxy: Boolean = true  // ★ 新增：是否走代理
) {
    call.respondBytesWriter(
        contentType = ContentType.Text.EventStream,
        status = HttpStatusCode.OK
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
            val response = httpClient.newCall(request).execute()

            response.use { resp ->
                if (!resp.isSuccessful) {
                    val errBytes = resp.body?.bytes() ?: "Unknown error".toByteArray()
                    writeFully("""data: {"error":"${errBytes.decodeToString().replace("\"", "\\\"")}"}""".toByteArray())
                    writeFully("\n\n".toByteArray())
                    writeFully("data: [DONE]\n\n".toByteArray())
                    flush()
                    return@respondBytesWriter
                }

                val body = resp.body?.byteStream() ?: return@respondBytesWriter
                body.use { input ->
                    val buffer = ByteArray(STREAM_BUF_SIZE)
                    var bytesRead: Int
                    // 累积原始字节流，避免每块 decodeToString
                    val accumulatedBytes = ByteArrayOutputStream(STREAM_BUF_SIZE)
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        writeFully(buffer, 0, bytesRead)
                        flush()
                        GatewayForegroundService.trafficDownloadBytes += bytesRead.toLong()
                        // 只累积原始字节，不解码
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
            writeFully("""data: {"error":"${e.message?.replace("\"", "\\\"") ?: "Unknown"}"}""".toByteArray())
            writeFully("\n\n".toByteArray())
            writeFully("data: [DONE]\n\n".toByteArray())
            flush()
        }
    }
}