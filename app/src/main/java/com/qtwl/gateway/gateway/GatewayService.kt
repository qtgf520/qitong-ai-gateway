package com.qtwl.gateway.gateway

import com.qtwl.gateway.gateway.GatewayScheduler

import com.qtwl.gateway.data.db.AppDatabase
import com.qtwl.gateway.data.model.AiModel
import com.qtwl.gateway.data.model.Provider
import com.qtwl.gateway.data.model.TokenUsage
import com.qtwl.gateway.network.UpstreamClient
import com.qtwl.gateway.service.GatewayForegroundService
import com.qtwl.gateway.service.KeyManager
import com.qtwl.gateway.service.LiveSession
import com.qtwl.gateway.service.ThinkingConfigManager
import com.qtwl.gateway.service.GroupChatManager
import com.qtwl.gateway.ui.viewmodel.BrainMemoryManager
import com.qtwl.gateway.ui.viewmodel.ModelCapabilityManager
import io.ktor.server.websocket.*
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.close
import io.ktor.server.application.install
import com.qtwl.gateway.utils.ToolAction
import com.qtwl.gateway.utils.ToolExecutor
import com.qtwl.gateway.utils.SkillRegistry
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receive
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.options
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.util.AttributeKey
import io.ktor.utils.io.writeFully
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
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
        
        // ★★ 启动会话清理协程（闲置超时自动断开）★★
        startSessionCleanup()

        val embedded = embeddedServer(CIO, port = port) {
            // ★★ 安装 WebSocket 支持 ★★★
            install(io.ktor.server.websocket.WebSockets)
            routing {
                // ★★★ CORS 预检请求处理 ★★★
                options("/{path...}") {
                    call.response.headers.append("Access-Control-Allow-Origin", "*")
                    call.response.headers.append("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
                    call.response.headers.append("Access-Control-Allow-Headers", "Content-Type, Authorization, x-api-key, anthropic-version, x-goog-api-key")
                    call.response.headers.append("Access-Control-Max-Age", "86400")
                    call.respondText("", ContentType.Application.Json, HttpStatusCode.OK)
                }

                // ★★★ 搜索接口（SearXNG 集成）★★★
                get("/v1/search") {
                    corsResponse(call)
                    try {
                        val query = call.request.queryParameters["q"] ?: ""
                        val maxResults = call.request.queryParameters["max"]?.toIntOrNull() ?: 8
                        if (query.isBlank()) {
                            call.respondText(openAIError(HttpStatusCode.BadRequest, "Query parameter 'q' is required", "invalid_request_error").second, ContentType.Application.Json, HttpStatusCode.BadRequest)
                            return@get
                        }
                        val searxngUrl = GatewayForegroundService.getGatewayConfig("searxng_url", "")
                        if (searxngUrl.isBlank()) {
                            call.respondText(openAIError(HttpStatusCode.BadRequest, "SearXNG not configured. Set URL in settings.", "invalid_request_error").second, ContentType.Application.Json, HttpStatusCode.BadRequest)
                            return@get
                        }
                        val client = com.qtwl.gateway.search.SearxngClient(searxngUrl)
                        val results = client.search(query, maxResults = maxResults)
                        val response = buildJsonObject {
                            put("object", JsonPrimitive("list"))
                            put("data", JsonArray(results.map { r ->
                                buildJsonObject {
                                    put("title", JsonPrimitive(r.title))
                                    put("url", JsonPrimitive(r.url))
                                    put("snippet", JsonPrimitive(r.snippet))
                                    put("engine", JsonPrimitive(r.engine))
                                    put("score", JsonPrimitive(r.score))
                                }
                            }))
                        }
                        call.respondText(response.toString(), ContentType.Application.Json.withCharset(Charsets.UTF_8))
                    } catch (e: Exception) {
                        call.respondText(openAIError(HttpStatusCode.InternalServerError, "Search failed: ${e.message}", "server_error").second, ContentType.Application.Json, HttpStatusCode.InternalServerError)
                    }
                }

                // 健康检查（不需要验证）
                get("/health") {
                    val running = GatewayForegroundService.isServiceRunning
                    val port = GatewayForegroundService.getGatewayPort()
                    val failover = GatewayForegroundService.getAutoFailover()
                    val healthJson = buildJsonObject {
                        put("status", JsonPrimitive("ok"))
                        put("service", JsonPrimitive("qitong-ai-gateway"))
                        put("version", JsonPrimitive("3.7.4"))
                        put("running", JsonPrimitive(running))
                        put("port", JsonPrimitive(port))
                        put("failover", JsonPrimitive(failover))
                        put("models_count", JsonPrimitive(database.aiModelDao().getEnabledModelsList().size))
                        put("uptime_seconds", JsonPrimitive((System.currentTimeMillis() - startTime) / 1000))
                        put("log_entries", JsonPrimitive(synchronized(accessLog) { accessLog.size }))
                        put("require_api_key", JsonPrimitive(GatewayForegroundService.getRequireApiKey()))
                    }
                    call.respondText(healthJson.toString(), ContentType.Application.Json.withCharset(Charsets.UTF_8))
                }

                // 获取模型列表 (OpenAI Compatible)
                get("/v1/models") {
                    corsResponse(call)
                    if (!validateApiKey(call)) {
                        val (s, b) = openAIError(HttpStatusCode.Unauthorized, "Invalid or missing API key", "invalid_api_key")
                        call.respondText(contentType = ContentType.Application.Json, status = s, text = b)
                        return@get
                    }
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
                            put("display_name", JsonPrimitive("🔄 自动化切换"))
                            put("custom_alias", JsonPrimitive(""))
                        }
                        val response = buildJsonObject {
                            put("object", JsonPrimitive("list"))
                            put("data", JsonArray(finalList))
                        }
                        call.respondText(
                            contentType = ContentType.Application.Json.withCharset(Charsets.UTF_8),
                            text = response.toString()
                        )
                    } catch (e: Exception) {
                        val (status, body) = openAIError(HttpStatusCode.InternalServerError, "Failed to fetch models: ${e.message}", "server_error")
                        call.respondText(contentType = ContentType.Application.Json, status = status, text = body)
                    }
                }

                // ★★★ GET /v1/chat/completions 返回标准错误（浏览器测试用）★★★
                get("/v1/chat/completions") {
                    corsResponse(call)
                    val (s, b) = openAIError(HttpStatusCode.BadRequest, "This endpoint requires a POST request. Use POST with a JSON body containing 'model' and 'messages'.", "invalid_request_error", 400)
                    call.respondText(contentType = ContentType.Application.Json, status = s, text = b)
                }

                // ★★★ 兼容不带 /v1 前缀的路径 ★★★
                post("/chat/completions") {
                    corsResponse(call)
                    proxyRequest(call, database)
                }

                // ★★★ 新增接口：文本补全（OpenAI Completions 格式）★★★
                post("/v1/completions") {
                    if (!validateApiKey(call)) {
                        val (s, b) = openAIError(HttpStatusCode.Unauthorized, "Invalid or missing API key", "invalid_api_key")
                        call.respondText(contentType = ContentType.Application.Json, status = s, text = b)
                        return@post
                    }
                    try {
                        val rawBytes = call.receive<ByteArray>()
                        GatewayForegroundService.trafficUploadBytes.addAndGet(rawBytes.size.toLong())
                        GatewayForegroundService.totalUploadBytes.addAndGet(rawBytes.size.toLong())
                        val body = proxyJson.parseToJsonElement(String(rawBytes, Charsets.UTF_8)).jsonObject
                        var modelId = body["model"]?.jsonPrimitive?.content ?: throw Exception("model is required")
                        // ★★ qtai-sj 支持：自动解析为当前活跃的真实模型 ★★
                        val realModelId = if (modelId == "qtai-sj") {
                            val active = GatewayForegroundService.activeNodeName
                            if (active.isNotBlank()) active else modelId
                        } else modelId
                        modelId = realModelId
                        val prompt = body["prompt"] ?: throw Exception("prompt is required")
                        val stream = body["stream"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
                        val promptText = try { prompt.jsonPrimitive.content } catch (_: Exception) { prompt.jsonArray.joinToString("\n") { it.jsonPrimitive.content } }
                        // 转换为 chat 格式后转发
                        val chatBody = buildJsonObject {
                            put("model", JsonPrimitive(modelId))
                            put("messages", JsonArray(listOf(buildJsonObject {
                                put("role", JsonPrimitive("user"))
                                put("content", JsonPrimitive(promptText))
                            })))
                            put("stream", JsonPrimitive(stream))
                            body["max_tokens"]?.let { put("max_tokens", it) }
                            body["temperature"]?.let { put("temperature", it) }
                            body["top_p"]?.let { put("top_p", it) }
                            body["stop"]?.let { put("stop", it) }
                            body["suffix"]?.let { put("suffix", it) }
                            body["n"]?.let { put("n", it) }
                        }
                        // 获取模型对应的服务商，转发
                        val models = database.aiModelDao().getEnabledModelsList()
                        val targetModel = models.find { it.modelId == modelId } ?: models.firstOrNull()
                        if (targetModel == null) { call.respondText(openAIError(HttpStatusCode.NotFound, "Model $modelId not found").second, ContentType.Application.Json, status = HttpStatusCode.NotFound); return@post }
                        val provider = database.providerDao().getProviderById(targetModel.providerId)
                        if (provider == null || !provider.isEnabled) { call.respondText(openAIError(HttpStatusCode.NotFound, "Provider for $modelId not available").second, ContentType.Application.Json, status = HttpStatusCode.NotFound); return@post }
                        val upstreamUrl = provider.resolvedBaseUrl.trimEnd('/')
                        val upstreamBody = sanitizeRequestBody(chatBody.toString())
                        val client = UpstreamClient.getClientForModel(targetModel.useProxy)
                        val req = okhttp3.Request.Builder().url("$upstreamUrl/v1/chat/completions")
                            .post(upstreamBody.toByteArray(Charsets.UTF_8).toRequestBody(DEFAULT_CT))
                            .apply { if (!provider.apiKey.isNullOrBlank()) header("Authorization", "Bearer ${provider.apiKey}") }
                            .build()
                        val resp = withContext(Dispatchers.IO) { client.newCall(req).execute() }
                        val respBody = resp.body?.string() ?: "{}"
                        resp.close()
                        GatewayForegroundService.totalDownloadBytes.addAndGet(respBody.length.toLong())
                        GatewayForegroundService.trafficDownloadBytes.addAndGet(respBody.length.toLong())
                        // 将 chat 响应转回 completions 格式
                        val chatResp = try { proxyJson.parseToJsonElement(respBody).jsonObject } catch (_: Exception) { null }
                        val text = try { chatResp?.get("choices")?.jsonArray?.get(0)?.jsonObject?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.content ?: "" } catch (_: Exception) { "" }
                        val completionsResp = buildJsonObject {
                            put("id", JsonPrimitive("cmpl-${UUID.randomUUID().toString().take(8)}"))
                            put("object", JsonPrimitive("text_completion"))
                            put("created", JsonPrimitive(System.currentTimeMillis() / 1000))
                            put("model", JsonPrimitive(modelId))
                            put("choices", JsonArray(listOf(buildJsonObject {
                                put("text", JsonPrimitive(text))
                                put("index", JsonPrimitive(0))
                                put("finish_reason", JsonPrimitive(chatResp?.get("choices")?.jsonArray?.get(0)?.jsonObject?.get("finish_reason")?.jsonPrimitive?.content ?: "stop"))
                            })))
                            chatResp?.get("usage")?.let { put("usage", it) }
                        }
                        call.respondText(completionsResp.toString(), ContentType.Application.Json.withCharset(Charsets.UTF_8))
                        logAccess(call, modelId, 200, System.currentTimeMillis() - (try { body["created"]?.jsonPrimitive?.content?.toLongOrNull() ?: System.currentTimeMillis() } catch (_: Exception) { System.currentTimeMillis() }))
                    } catch (e: Exception) {
                        val (s, b) = openAIError(HttpStatusCode.InternalServerError, e.message ?: "Completions failed", "server_error")
                        call.respondText(contentType = ContentType.Application.Json, status = s, text = b)
                    }
                }

                // ★★★ 新增接口：Claude 消息格式（POST /v1/messages）★★★
                post("/v1/messages") {
                    if (!validateApiKey(call)) {
                        val (s, b) = openAIError(HttpStatusCode.Unauthorized, "Invalid or missing API key", "invalid_api_key")
                        call.respondText(contentType = ContentType.Application.Json, status = s, text = b)
                        return@post
                    }
                    try {
                        val rawBytes = call.receive<ByteArray>()
                        GatewayForegroundService.trafficUploadBytes.addAndGet(rawBytes.size.toLong())
                        GatewayForegroundService.totalUploadBytes.addAndGet(rawBytes.size.toLong())
                        val body = proxyJson.parseToJsonElement(String(rawBytes, Charsets.UTF_8)).jsonObject
                        var modelId = body["model"]?.jsonPrimitive?.content ?: throw Exception("model is required")
                        // ★★ qtai-sj 支持：自动解析为当前活跃的真实模型 ★★
                        if (modelId == "qtai-sj") {
                            val active = GatewayForegroundService.activeNodeName
                            if (active.isNotBlank()) modelId = active
                        }
                        val claudeMsgs = body["messages"]?.jsonArray ?: throw Exception("messages is required")
                        val stream = body["stream"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
                        val maxTokens = body["max_tokens"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1024
                        val systemPrompt = body["system"]?.jsonPrimitive?.content ?: ""
                        // 转换 Claude 消息为 OpenAI 格式
                        val openaiMsgs = mutableListOf<JsonObject>()
                        if (systemPrompt.isNotBlank()) {
                            openaiMsgs.add(buildJsonObject { put("role", JsonPrimitive("system")); put("content", JsonPrimitive(systemPrompt)) })
                        }
                        for (msg in claudeMsgs) {
                            val obj = msg.jsonObject
                            val role = obj["role"]?.jsonPrimitive?.content ?: "user"
                            val content = obj["content"]
                            if (content != null) {
                                // Claude content 可以是 string 或 array
                                val text = try { content.jsonPrimitive.content } catch (_: Exception) {
                                    content.jsonArray.mapNotNull { part ->
                                        try { val p = part.jsonObject; if (p["type"]?.jsonPrimitive?.content == "text") p["text"]?.jsonPrimitive?.content else null } catch (_: Exception) { null }
                                    }.joinToString("\n")
                                }
                                openaiMsgs.add(buildJsonObject { put("role", JsonPrimitive(if (role == "assistant") "assistant" else "user")); put("content", JsonPrimitive(text)) })
                            }
                        }
                        // 构造 OpenAI chat 请求
                        val chatBody = buildJsonObject {
                            put("model", JsonPrimitive(modelId))
                            put("messages", JsonArray(openaiMsgs))
                            put("max_tokens", JsonPrimitive(maxTokens))
                            put("stream", JsonPrimitive(stream))
                            body["temperature"]?.let { put("temperature", it) }
                            body["top_p"]?.let { put("top_p", it) }
                            body["stop_sequences"]?.let { put("stop", it) }
                            body["tools"]?.let { put("tools", it) }
                            body["tool_choice"]?.let { put("tool_choice", it) }
                        }
                        // 查找模型并转发
                        val models = database.aiModelDao().getEnabledModelsList()
                        val targetModel = models.find { it.modelId == modelId } ?: models.firstOrNull()
                        if (targetModel == null) { call.respondText(openAIError(HttpStatusCode.NotFound, "Model $modelId not found").second, ContentType.Application.Json, status = HttpStatusCode.NotFound); return@post }
                        val provider = database.providerDao().getProviderById(targetModel.providerId)
                        if (provider == null || !provider.isEnabled) { call.respondText(openAIError(HttpStatusCode.NotFound, "Provider for $modelId not available").second, ContentType.Application.Json, status = HttpStatusCode.NotFound); return@post }
                        val upstreamUrl = provider.resolvedBaseUrl.trimEnd('/')
                        val upstreamBody = sanitizeRequestBody(chatBody.toString())
                        val client = UpstreamClient.getClientForModel(targetModel.useProxy)
                        val req = okhttp3.Request.Builder().url("$upstreamUrl/v1/chat/completions")
                            .post(upstreamBody.toByteArray(Charsets.UTF_8).toRequestBody(DEFAULT_CT))
                            .apply { if (!provider.apiKey.isNullOrBlank()) header("Authorization", "Bearer ${provider.apiKey}") }
                            .build()
                        val resp = withContext(Dispatchers.IO) { client.newCall(req).execute() }
                        val respBody = resp.body?.string() ?: "{}"
                        resp.close()
                        GatewayForegroundService.totalDownloadBytes.addAndGet(respBody.length.toLong())
                        GatewayForegroundService.trafficDownloadBytes.addAndGet(respBody.length.toLong())
                        // 将 OpenAI chat 响应转换为 Claude 格式
                        val chatResp = try { proxyJson.parseToJsonElement(respBody).jsonObject } catch (_: Exception) { null }
                        val contentText = try { chatResp?.get("choices")?.jsonArray?.get(0)?.jsonObject?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.content ?: "" } catch (_: Exception) { "" }
                        val stopReason = try { chatResp?.get("choices")?.jsonArray?.get(0)?.jsonObject?.get("finish_reason")?.jsonPrimitive?.content?.let { if (it == "stop") "end_turn" else it } ?: "end_turn" } catch (_: Exception) { "end_turn" }
                        val promptTokens = try { chatResp?.get("usage")?.jsonObject?.get("prompt_tokens")?.jsonPrimitive?.content?.toIntOrNull() ?: 0 } catch (_: Exception) { 0 }
                        val completionTokens = try { chatResp?.get("usage")?.jsonObject?.get("completion_tokens")?.jsonPrimitive?.content?.toIntOrNull() ?: 0 } catch (_: Exception) { 0 }
                        val claudeResp = buildJsonObject {
                            put("id", JsonPrimitive("msg_${UUID.randomUUID().toString().take(8)}"))
                            put("type", JsonPrimitive("message"))
                            put("role", JsonPrimitive("assistant"))
                            put("content", JsonArray(listOf(buildJsonObject {
                                put("type", JsonPrimitive("text"))
                                put("text", JsonPrimitive(contentText))
                            })))
                            put("model", JsonPrimitive(modelId))
                            put("stop_reason", JsonPrimitive(stopReason))
                            put("usage", buildJsonObject {
                                put("input_tokens", JsonPrimitive(promptTokens))
                                put("output_tokens", JsonPrimitive(completionTokens))
                            })
                        }
                        call.respondText(claudeResp.toString(), ContentType.Application.Json.withCharset(Charsets.UTF_8))
                        logAccess(call, modelId, 200, System.currentTimeMillis() - (try { body["created"]?.jsonPrimitive?.content?.toLongOrNull() ?: System.currentTimeMillis() } catch (_: Exception) { System.currentTimeMillis() }))
                    } catch (e: Exception) {
                        val (s, b) = openAIError(HttpStatusCode.InternalServerError, e.message ?: "Messages failed", "server_error")
                        call.respondText(contentType = ContentType.Application.Json, status = s, text = b)
                    }
                }

                // ★★★ 新增接口：嵌入向量（POST /v1/embeddings）★★★
                post("/v1/embeddings") {
                    if (!validateApiKey(call)) {
                        val (s, b) = openAIError(HttpStatusCode.Unauthorized, "Invalid or missing API key", "invalid_api_key")
                        call.respondText(contentType = ContentType.Application.Json, status = s, text = b)
                        return@post
                    }
                    try {
                        val rawBytes = call.receive<ByteArray>()
                        GatewayForegroundService.trafficUploadBytes.addAndGet(rawBytes.size.toLong())
                        GatewayForegroundService.totalUploadBytes.addAndGet(rawBytes.size.toLong())
                        val body = proxyJson.parseToJsonElement(String(rawBytes, Charsets.UTF_8)).jsonObject
                        var modelId = body["model"]?.jsonPrimitive?.content ?: throw Exception("model is required")
                        // ★★ qtai-sj 支持：自动解析为当前活跃的真实模型 ★★
                        if (modelId == "qtai-sj") {
                            val active = GatewayForegroundService.activeNodeName
                            if (active.isNotBlank()) modelId = active
                        }
                        val input = body["input"] ?: throw Exception("input is required")
                        // 查找模型和提供商
                        val models = database.aiModelDao().getEnabledModelsList()
                        val targetModel = models.find { it.modelId == modelId } ?: models.firstOrNull()
                        if (targetModel == null) { call.respondText(openAIError(HttpStatusCode.NotFound, "Model $modelId not found").second, ContentType.Application.Json, status = HttpStatusCode.NotFound); return@post }
                        val provider = database.providerDao().getProviderById(targetModel.providerId)
                        if (provider == null || !provider.isEnabled) { call.respondText(openAIError(HttpStatusCode.NotFound, "Provider for $modelId not available").second, ContentType.Application.Json, status = HttpStatusCode.NotFound); return@post }
                        val upstreamUrl = provider.resolvedBaseUrl.trimEnd('/')
                        // 保留原始 body 但确保 model 正确
                        val upstreamBody = sanitizeRequestBody(rawBytes.decodeToString())
                        val client = UpstreamClient.getClientForModel(targetModel.useProxy)
                        val req = okhttp3.Request.Builder().url("$upstreamUrl/v1/embeddings")
                            .post(upstreamBody.toByteArray(Charsets.UTF_8).toRequestBody(DEFAULT_CT))
                            .apply { if (!provider.apiKey.isNullOrBlank()) header("Authorization", "Bearer ${provider.apiKey}") }
                            .build()
                        val resp = withContext(Dispatchers.IO) { client.newCall(req).execute() }
                        val respBody = resp.body?.string() ?: "{}"
                        resp.close()
                        GatewayForegroundService.totalDownloadBytes.addAndGet(respBody.length.toLong())
                        GatewayForegroundService.trafficDownloadBytes.addAndGet(respBody.length.toLong())
                        call.respondText(respBody, ContentType.Application.Json.withCharset(Charsets.UTF_8), status = HttpStatusCode.fromValue(resp.code.takeIf { it > 0 } ?: 200))
                        logAccess(call, modelId, resp.code, System.currentTimeMillis() - (try { body["created"]?.jsonPrimitive?.content?.toLongOrNull() ?: System.currentTimeMillis() } catch (_: Exception) { System.currentTimeMillis() }))
                    } catch (e: Exception) {
                        val (s, b) = openAIError(HttpStatusCode.InternalServerError, e.message ?: "Embeddings failed", "server_error")
                        call.respondText(contentType = ContentType.Application.Json, status = s, text = b)
                    }
                }

                // ★★★ 新增接口：重排序（POST /v1/rerank）★★★
                post("/v1/rerank") {
                    if (!validateApiKey(call)) { val (s,b)=openAIError(HttpStatusCode.Unauthorized,"Invalid or missing API key","invalid_api_key"); call.respondText(contentType=ContentType.Application.Json,status=s,text=b); return@post }
                    try { val rawBytes=call.receive<ByteArray>(); GatewayForegroundService.trafficUploadBytes.addAndGet(rawBytes.size.toLong()); GatewayForegroundService.totalUploadBytes.addAndGet(rawBytes.size.toLong())
                        val body=proxyJson.parseToJsonElement(String(rawBytes,Charsets.UTF_8)).jsonObject; var modelId=body["model"]?.jsonPrimitive?.content?:throw Exception("model required")
                        if (modelId=="qtai-sj"){val active=GatewayForegroundService.activeNodeName;if(active.isNotBlank())modelId=active}
                        val models=database.aiModelDao().getEnabledModelsList(); val targetModel=models.find{it.modelId==modelId}?:models.firstOrNull()
                        if(targetModel==null){call.respondText(openAIError(HttpStatusCode.NotFound,"Model $modelId not found").second,ContentType.Application.Json,status=HttpStatusCode.NotFound);return@post}
                        val provider=database.providerDao().getProviderById(targetModel.providerId)
                        if(provider==null||!provider.isEnabled){call.respondText(openAIError(HttpStatusCode.NotFound,"Provider for $modelId not available").second,ContentType.Application.Json,status=HttpStatusCode.NotFound);return@post}
                        val upstreamUrl=provider.resolvedBaseUrl.trimEnd('/'); val upstreamBody=sanitizeRequestBody(rawBytes.decodeToString())
                        val client=UpstreamClient.getClientForModel(targetModel.useProxy)
                        val req=okhttp3.Request.Builder().url("$upstreamUrl/v1/rerank").post(upstreamBody.toByteArray(Charsets.UTF_8).toRequestBody(DEFAULT_CT)).apply{if(!provider.apiKey.isNullOrBlank())header("Authorization","Bearer ${provider.apiKey}")}.build()
                        val rStartMs=System.currentTimeMillis()
                        val resp=withContext(Dispatchers.IO){client.newCall(req).execute()}; val respBody=resp.body?.string()?:"{}"; resp.close()
                        GatewayForegroundService.totalDownloadBytes.addAndGet(respBody.length.toLong()); GatewayForegroundService.trafficDownloadBytes.addAndGet(respBody.length.toLong())
                        call.respondText(respBody,ContentType.Application.Json.withCharset(Charsets.UTF_8),status=HttpStatusCode.fromValue(resp.code.takeIf{it>0}?:200))
                        logAccess(call,modelId,resp.code,System.currentTimeMillis()-rStartMs)
                    }catch(e:Exception){val(s,b)=openAIError(HttpStatusCode.InternalServerError,e.message?:"Rerank failed","server_error");call.respondText(contentType=ContentType.Application.Json,status=s,text=b)}
                }

                // ★★★ 新增接口：内容审核（POST /v1/moderations）★★★
                post("/v1/moderations") {
                    if (!validateApiKey(call)) { val (s,b)=openAIError(HttpStatusCode.Unauthorized,"Invalid or missing API key","invalid_api_key"); call.respondText(contentType=ContentType.Application.Json,status=s,text=b); return@post }
                    try { val rawBytes=call.receive<ByteArray>(); GatewayForegroundService.trafficUploadBytes.addAndGet(rawBytes.size.toLong()); GatewayForegroundService.totalUploadBytes.addAndGet(rawBytes.size.toLong())
                        val body=proxyJson.parseToJsonElement(String(rawBytes,Charsets.UTF_8)).jsonObject; var modelId=body["model"]?.jsonPrimitive?.content?:""
                        if (modelId=="qtai-sj"){val active=GatewayForegroundService.activeNodeName;if(active.isNotBlank())modelId=active}
                        val models=database.aiModelDao().getEnabledModelsList()
                        val targetModel=if(modelId.isNotBlank())models.find{it.modelId==modelId}else{null}
                        val effectiveModel=targetModel?:models.firstOrNull()
                        if(effectiveModel==null){call.respondText(openAIError(HttpStatusCode.NotFound,"No available model").second,ContentType.Application.Json,status=HttpStatusCode.NotFound);return@post}
                        val provider=database.providerDao().getProviderById(effectiveModel.providerId)
                        if(provider==null||!provider.isEnabled){call.respondText(openAIError(HttpStatusCode.NotFound,"Provider for $modelId not available").second,ContentType.Application.Json,status=HttpStatusCode.NotFound);return@post}
                        val upstreamUrl=provider.resolvedBaseUrl.trimEnd('/'); val upstreamBody=sanitizeRequestBody(rawBytes.decodeToString())
                        val client=UpstreamClient.getClientForModel(effectiveModel.useProxy)
                        val req=okhttp3.Request.Builder().url("$upstreamUrl/v1/moderations").post(upstreamBody.toByteArray(Charsets.UTF_8).toRequestBody(DEFAULT_CT)).apply{if(!provider.apiKey.isNullOrBlank())header("Authorization","Bearer ${provider.apiKey}")}.build()
                        val mStartMs=System.currentTimeMillis()
                        val resp=withContext(Dispatchers.IO){client.newCall(req).execute()}; val respBody=resp.body?.string()?:"{}"; resp.close()
                        GatewayForegroundService.totalDownloadBytes.addAndGet(respBody.length.toLong()); GatewayForegroundService.trafficDownloadBytes.addAndGet(respBody.length.toLong())
                        call.respondText(respBody,ContentType.Application.Json.withCharset(Charsets.UTF_8),status=HttpStatusCode.fromValue(resp.code.takeIf{it>0}?:200))
                        logAccess(call,modelId,resp.code,System.currentTimeMillis()-mStartMs)
                    }catch(e:Exception){val(s,b)=openAIError(HttpStatusCode.InternalServerError,e.message?:"Moderations failed","server_error");call.respondText(contentType=ContentType.Application.Json,status=s,text=b)}
                }

                // ★★★ 新增接口：文本转语音（POST /v1/audio/speech）★★★
                post("/v1/audio/speech") {
                    if (!validateApiKey(call)) { val (s,b)=openAIError(HttpStatusCode.Unauthorized,"Invalid or missing API key","invalid_api_key"); call.respondText(contentType=ContentType.Application.Json,status=s,text=b); return@post }
                    try { val rawBytes=call.receive<ByteArray>(); GatewayForegroundService.trafficUploadBytes.addAndGet(rawBytes.size.toLong()); GatewayForegroundService.totalUploadBytes.addAndGet(rawBytes.size.toLong())
                        val body=proxyJson.parseToJsonElement(String(rawBytes,Charsets.UTF_8)).jsonObject; var modelId=body["model"]?.jsonPrimitive?.content?:throw Exception("model required")
                        if (modelId=="qtai-sj"){val active=GatewayForegroundService.activeNodeName;if(active.isNotBlank())modelId=active}
                        val models=database.aiModelDao().getEnabledModelsList(); val targetModel=models.find{it.modelId==modelId}?:models.firstOrNull()
                        if(targetModel==null){call.respondText(openAIError(HttpStatusCode.NotFound,"Model $modelId not found").second,ContentType.Application.Json,status=HttpStatusCode.NotFound);return@post}
                        val provider=database.providerDao().getProviderById(targetModel.providerId)
                        if(provider==null||!provider.isEnabled){call.respondText(openAIError(HttpStatusCode.NotFound,"Provider for $modelId not available").second,ContentType.Application.Json,status=HttpStatusCode.NotFound);return@post}
                        val upstreamUrl=provider.resolvedBaseUrl.trimEnd('/'); val upstreamBody=rawBytes // 保持二进制
                        val client=UpstreamClient.getClientForModel(targetModel.useProxy)
                        val contentType=call.request.headers["Content-Type"]?: "application/json"
                        val req=okhttp3.Request.Builder().url("$upstreamUrl/v1/audio/speech").post(upstreamBody.toRequestBody(contentType.toMediaType())).apply{if(!provider.apiKey.isNullOrBlank())header("Authorization","Bearer ${provider.apiKey}")}.build()
                        val startMs=System.currentTimeMillis()
                        val computedLatency=startMs
                        val resp=withContext(Dispatchers.IO){client.newCall(req).execute()}; val respBytes=resp.body?.bytes()?:ByteArray(0); resp.close()
                        GatewayForegroundService.totalDownloadBytes.addAndGet(respBytes.size.toLong()); GatewayForegroundService.trafficDownloadBytes.addAndGet(respBytes.size.toLong())
                        val respContentType=resp.header("Content-Type")?:"audio/mpeg"
                        call.respondBytesWriter(contentType=ContentType.parse(respContentType)){writeFully(respBytes)}
                        logAccess(call,modelId,resp.code,System.currentTimeMillis()-startMs)
                    }catch(e:Exception){val(s,b)=openAIError(HttpStatusCode.InternalServerError,e.message?:"TTS failed","server_error");call.respondText(contentType=ContentType.Application.Json,status=s,text=b)}
                }

                // ★★★ 新增接口：图像生成（POST /v1/images/generations）★★★
                post("/v1/images/generations") {
                    if (!validateApiKey(call)) { val (s,b)=openAIError(HttpStatusCode.Unauthorized,"Invalid or missing API key","invalid_api_key"); call.respondText(contentType=ContentType.Application.Json,status=s,text=b); return@post }
                    try { val rawBytes=call.receive<ByteArray>(); GatewayForegroundService.trafficUploadBytes.addAndGet(rawBytes.size.toLong()); GatewayForegroundService.totalUploadBytes.addAndGet(rawBytes.size.toLong())
                        var bodyStr=String(rawBytes,Charsets.UTF_8)
                        val body=proxyJson.parseToJsonElement(bodyStr).jsonObject
                        var modelId=body["model"]?.jsonPrimitive?.content?:"dall-e-3"
                        if (modelId=="qtai-sj"){
                            val active=GatewayForegroundService.activeNodeName
                            if(active.isNotBlank()) modelId=active
                        }
                        val models=database.aiModelDao().getEnabledModelsList()
                        var targetModel=models.find{it.modelId==modelId}
                        if(targetModel==null){
                            // 兜底：用第一个已启用的模型
                            targetModel=models.firstOrNull()
                        }
                        if(targetModel==null){call.respondText(openAIError(HttpStatusCode.NotFound,"No available model for image generation").second,ContentType.Application.Json,status=HttpStatusCode.NotFound);return@post}
                        val provider=database.providerDao().getProviderById(targetModel.providerId)
                        if(provider==null||!provider.isEnabled){call.respondText(openAIError(HttpStatusCode.NotFound,"Provider for ${targetModel.modelId} not available").second,ContentType.Application.Json,status=HttpStatusCode.NotFound);return@post}
                        // 替换body中的model为真实模型ID
                        bodyStr=replaceModelInBody(bodyStr, targetModel.modelId)
                        val upstreamUrl=provider.resolvedBaseUrl.trimEnd('/')
                        val upstreamBody=sanitizeRequestBody(bodyStr)
                        val client=UpstreamClient.getClientForModel(targetModel.useProxy)
                        val req=okhttp3.Request.Builder().url("$upstreamUrl/v1/images/generations").post(upstreamBody.toByteArray(Charsets.UTF_8).toRequestBody(DEFAULT_CT)).apply{if(!provider.apiKey.isNullOrBlank())header("Authorization","Bearer ${provider.apiKey}")}.build()
                        val imgStartMs=System.currentTimeMillis()
                        val resp=withContext(Dispatchers.IO){client.newCall(req).execute()}; val respBody=resp.body?.string()?:"{}"; resp.close()
                        GatewayForegroundService.totalDownloadBytes.addAndGet(respBody.length.toLong()); GatewayForegroundService.trafficDownloadBytes.addAndGet(respBody.length.toLong())
                        call.respondText(respBody,ContentType.Application.Json.withCharset(Charsets.UTF_8),status=HttpStatusCode.fromValue(resp.code.takeIf{it>0}?:200))
                        logAccess(call,targetModel.modelId,resp.code,System.currentTimeMillis()-imgStartMs)
                    }catch(e:Exception){val(s,b)=openAIError(HttpStatusCode.InternalServerError,e.message?:"Image generation failed","server_error");call.respondText(contentType=ContentType.Application.Json,status=s,text=b)}
                }

                // ★★★ 新增接口：视频生成-同步（POST /v1/videos）★★★
                post("/v1/videos") {
                    if (!validateApiKey(call)) { val (s,b)=openAIError(HttpStatusCode.Unauthorized,"Invalid or missing API key","invalid_api_key"); call.respondText(contentType=ContentType.Application.Json,status=s,text=b); return@post }
                    try { val rawBytes=call.receive<ByteArray>(); GatewayForegroundService.trafficUploadBytes.addAndGet(rawBytes.size.toLong()); GatewayForegroundService.totalUploadBytes.addAndGet(rawBytes.size.toLong())
                        val body=proxyJson.parseToJsonElement(String(rawBytes,Charsets.UTF_8)).jsonObject; var modelId=body["model"]?.jsonPrimitive?.content?:"sora"
                        if (modelId=="qtai-sj"){val active=GatewayForegroundService.activeNodeName;if(active.isNotBlank())modelId=active}
                        val models=database.aiModelDao().getEnabledModelsList(); val targetModel=models.find{it.modelId==modelId}?:models.firstOrNull()
                        if(targetModel==null){call.respondText(openAIError(HttpStatusCode.NotFound,"Model $modelId not found").second,ContentType.Application.Json,status=HttpStatusCode.NotFound);return@post}
                        val provider=database.providerDao().getProviderById(targetModel.providerId)
                        if(provider==null||!provider.isEnabled){call.respondText(openAIError(HttpStatusCode.NotFound,"Provider for $modelId not available").second,ContentType.Application.Json,status=HttpStatusCode.NotFound);return@post}
                        val upstreamUrl=provider.resolvedBaseUrl.trimEnd('/'); val upstreamBody=rawBytes
                        val client=UpstreamClient.getClientForModel(targetModel.useProxy)
                        val contentType=call.request.headers["Content-Type"]?:"application/json"
                        val req=okhttp3.Request.Builder().url("$upstreamUrl/v1/videos").post(upstreamBody.toRequestBody(contentType.toMediaType())).apply{if(!provider.apiKey.isNullOrBlank())header("Authorization","Bearer ${provider.apiKey}")}.build()
                        val vStartMs=System.currentTimeMillis()
                        val resp=withContext(Dispatchers.IO){client.newCall(req).execute()}; val respBody=resp.body?.string()?:"{}"; resp.close()
                        GatewayForegroundService.totalDownloadBytes.addAndGet(respBody.length.toLong()); GatewayForegroundService.trafficDownloadBytes.addAndGet(respBody.length.toLong())
                        call.respondText(respBody,ContentType.Application.Json.withCharset(Charsets.UTF_8),status=HttpStatusCode.fromValue(resp.code.takeIf{it>0}?:200))
                        logAccess(call,modelId,resp.code,System.currentTimeMillis()-vStartMs)
                    }catch(e:Exception){val(s,b)=openAIError(HttpStatusCode.InternalServerError,e.message?:"Video sync failed","server_error");call.respondText(contentType=ContentType.Application.Json,status=s,text=b)}
                }

                // ★★★ 新增接口：视频生成-异步任务（POST /v1/video/generations）★★★
                post("/v1/video/generations") {
                    if (!validateApiKey(call)) { val (s,b)=openAIError(HttpStatusCode.Unauthorized,"Invalid or missing API key","invalid_api_key"); call.respondText(contentType=ContentType.Application.Json,status=s,text=b); return@post }
                    try { val rawBytes=call.receive<ByteArray>(); GatewayForegroundService.trafficUploadBytes.addAndGet(rawBytes.size.toLong()); GatewayForegroundService.totalUploadBytes.addAndGet(rawBytes.size.toLong())
                        val body=proxyJson.parseToJsonElement(String(rawBytes,Charsets.UTF_8)).jsonObject; var modelId=body["model"]?.jsonPrimitive?.content?:"sora"
                        if (modelId=="qtai-sj"){val active=GatewayForegroundService.activeNodeName;if(active.isNotBlank())modelId=active}
                        val models=database.aiModelDao().getEnabledModelsList(); val targetModel=models.find{it.modelId==modelId}?:models.firstOrNull()
                        if(targetModel==null){call.respondText(openAIError(HttpStatusCode.NotFound,"Model $modelId not found").second,ContentType.Application.Json,status=HttpStatusCode.NotFound);return@post}
                        val provider=database.providerDao().getProviderById(targetModel.providerId)
                        if(provider==null||!provider.isEnabled){call.respondText(openAIError(HttpStatusCode.NotFound,"Provider for $modelId not available").second,ContentType.Application.Json,status=HttpStatusCode.NotFound);return@post}
                        val upstreamUrl=provider.resolvedBaseUrl.trimEnd('/'); val upstreamBody=sanitizeRequestBody(rawBytes.decodeToString())
                        val client=UpstreamClient.getClientForModel(targetModel.useProxy)
                        val req=okhttp3.Request.Builder().url("$upstreamUrl/v1/video/generations").post(upstreamBody.toByteArray(Charsets.UTF_8).toRequestBody(DEFAULT_CT)).apply{if(!provider.apiKey.isNullOrBlank())header("Authorization","Bearer ${provider.apiKey}")}.build()
                        val vTaskStartMs=System.currentTimeMillis()
                        val resp=withContext(Dispatchers.IO){client.newCall(req).execute()}; val respBody=resp.body?.string()?:"{}"; resp.close()
                        GatewayForegroundService.totalDownloadBytes.addAndGet(respBody.length.toLong()); GatewayForegroundService.trafficDownloadBytes.addAndGet(respBody.length.toLong())
                        call.respondText(respBody,ContentType.Application.Json.withCharset(Charsets.UTF_8),status=HttpStatusCode.fromValue(resp.code.takeIf{it>0}?:200))
                        logAccess(call,modelId,resp.code,System.currentTimeMillis()-vTaskStartMs)
                    }catch(e:Exception){val(s,b)=openAIError(HttpStatusCode.InternalServerError,e.message?:"Video task failed","server_error");call.respondText(contentType=ContentType.Application.Json,status=s,text=b)}
                }

                // ★★★ 新增接口：获取视频任务状态（GET /v1/video/generations/{task_id}）★★★
                get("/v1/video/generations/{task_id}") {
                    if (!validateApiKey(call)) { val (s,b)=openAIError(HttpStatusCode.Unauthorized,"Invalid or missing API key","invalid_api_key"); call.respondText(contentType=ContentType.Application.Json,status=s,text=b); return@get }
                    try { val taskId=call.parameters["task_id"]?:""
                        val body=proxyJson.parseToJsonElement("{}").jsonObject; var modelId="sora"
                        val models=database.aiModelDao().getEnabledModelsList(); val targetModel=models.firstOrNull()
                        if(targetModel==null){call.respondText(openAIError(HttpStatusCode.NotFound,"No available model").second,ContentType.Application.Json,status=HttpStatusCode.NotFound);return@get}
                        val provider=database.providerDao().getProviderById(targetModel.providerId)
                        if(provider==null||!provider.isEnabled){call.respondText(openAIError(HttpStatusCode.NotFound,"No available provider").second,ContentType.Application.Json,status=HttpStatusCode.NotFound);return@get}
                        val upstreamUrl=provider.resolvedBaseUrl.trimEnd('/')
                        val client=UpstreamClient.getClientForModel(targetModel.useProxy)
                        val req=okhttp3.Request.Builder().url("$upstreamUrl/v1/video/generations/$taskId").get().apply{if(!provider.apiKey.isNullOrBlank())header("Authorization","Bearer ${provider.apiKey}")}.build()
                        val vGetStartMs=System.currentTimeMillis()
                        val resp=withContext(Dispatchers.IO){client.newCall(req).execute()}; val respBody=resp.body?.string()?:"{}"; resp.close()
                        GatewayForegroundService.totalDownloadBytes.addAndGet(respBody.length.toLong()); GatewayForegroundService.trafficDownloadBytes.addAndGet(respBody.length.toLong())
                        call.respondText(respBody,ContentType.Application.Json.withCharset(Charsets.UTF_8),status=HttpStatusCode.fromValue(resp.code.takeIf{it>0}?:200))
                        logAccess(call,modelId,resp.code,System.currentTimeMillis()-vGetStartMs)
                    }catch(e:Exception){val(s,b)=openAIError(HttpStatusCode.InternalServerError,e.message?:"Video task status failed","server_error");call.respondText(contentType=ContentType.Application.Json,status=s,text=b)}
                }

                // ★★★ Gemini 格式：POST /v1beta/models/{model}:generateContent ★★★
                post("/v1beta/models/{model}:generateContent") {
                    if (!validateApiKey(call)) { val (s,b)=openAIError(HttpStatusCode.Unauthorized,"Invalid or missing API key","invalid_api_key"); call.respondText(contentType=ContentType.Application.Json,status=s,text=b); return@post }
                    try { val rawBytes=call.receive<ByteArray>(); GatewayForegroundService.trafficUploadBytes.addAndGet(rawBytes.size.toLong()); GatewayForegroundService.totalUploadBytes.addAndGet(rawBytes.size.toLong())
                        val geminiModel=call.parameters["model"]?:""
                        var modelId=geminiModel; if (modelId=="qtai-sj"){val active=GatewayForegroundService.activeNodeName;if(active.isNotBlank())modelId=active}
                        val models=database.aiModelDao().getEnabledModelsList(); val targetModel=models.find{it.modelId==modelId}?:models.firstOrNull()
                        if(targetModel==null){call.respondText(openAIError(HttpStatusCode.NotFound,"Model $modelId not found").second,ContentType.Application.Json,status=HttpStatusCode.NotFound);return@post}
                        val provider=database.providerDao().getProviderById(targetModel.providerId)
                        if(provider==null||!provider.isEnabled){call.respondText(openAIError(HttpStatusCode.NotFound,"Provider for $modelId not available").second,ContentType.Application.Json,status=HttpStatusCode.NotFound);return@post}
                        val upstreamUrl=provider.resolvedBaseUrl.trimEnd('/'); val upstreamBody=rawBytes
                        val client=UpstreamClient.getClientForModel(targetModel.useProxy)
                        val contentType=call.request.headers["Content-Type"]?:"application/json"
                        val req=okhttp3.Request.Builder().url("$upstreamUrl/v1beta/models/$geminiModel:generateContent").post(upstreamBody.toRequestBody(contentType.toMediaType())).apply{if(!provider.apiKey.isNullOrBlank())header("Authorization","Bearer ${provider.apiKey}")}.build()
                        val gStartMs=System.currentTimeMillis()
                        val resp=withContext(Dispatchers.IO){client.newCall(req).execute()}; val respBody=resp.body?.string()?:"{}"; resp.close()
                        GatewayForegroundService.totalDownloadBytes.addAndGet(respBody.length.toLong()); GatewayForegroundService.trafficDownloadBytes.addAndGet(respBody.length.toLong())
                        call.respondText(respBody,ContentType.Application.Json.withCharset(Charsets.UTF_8),status=HttpStatusCode.fromValue(resp.code.takeIf{it>0}?:200))
                        logAccess(call,modelId,resp.code,System.currentTimeMillis()-gStartMs)
                    }catch(e:Exception){val(s,b)=openAIError(HttpStatusCode.InternalServerError,e.message?:"Gemini generate failed","server_error");call.respondText(contentType=ContentType.Application.Json,status=s,text=b)}
                }

                // ★★★ Gemini 引擎嵌入：POST /v1/engines/{model}/embeddings ★★★
                post("/v1/engines/{model}/embeddings") {
                    if (!validateApiKey(call)) { val (s,b)=openAIError(HttpStatusCode.Unauthorized,"Invalid or missing API key","invalid_api_key"); call.respondText(contentType=ContentType.Application.Json,status=s,text=b); return@post }
                    try { val rawBytes=call.receive<ByteArray>(); GatewayForegroundService.trafficUploadBytes.addAndGet(rawBytes.size.toLong()); GatewayForegroundService.totalUploadBytes.addAndGet(rawBytes.size.toLong())
                        val engineModel=call.parameters["model"]?:""
                        var modelId=engineModel; if (modelId=="qtai-sj"){val active=GatewayForegroundService.activeNodeName;if(active.isNotBlank())modelId=active}
                        val models=database.aiModelDao().getEnabledModelsList(); val targetModel=models.find{it.modelId==modelId}?:models.firstOrNull()
                        if(targetModel==null){call.respondText(openAIError(HttpStatusCode.NotFound,"Model $modelId not found").second,ContentType.Application.Json,status=HttpStatusCode.NotFound);return@post}
                        val provider=database.providerDao().getProviderById(targetModel.providerId)
                        if(provider==null||!provider.isEnabled){call.respondText(openAIError(HttpStatusCode.NotFound,"Provider for $modelId not available").second,ContentType.Application.Json,status=HttpStatusCode.NotFound);return@post}
                        val upstreamUrl=provider.resolvedBaseUrl.trimEnd('/'); val upstreamBody=sanitizeRequestBody(rawBytes.decodeToString())
                        val client=UpstreamClient.getClientForModel(targetModel.useProxy)
                        val req=okhttp3.Request.Builder().url("$upstreamUrl/v1/engines/$engineModel/embeddings").post(upstreamBody.toByteArray(Charsets.UTF_8).toRequestBody(DEFAULT_CT)).apply{if(!provider.apiKey.isNullOrBlank())header("Authorization","Bearer ${provider.apiKey}")}.build()
                        val eStartMs=System.currentTimeMillis()
                        val resp=withContext(Dispatchers.IO){client.newCall(req).execute()}; val respBody=resp.body?.string()?:"{}"; resp.close()
                        GatewayForegroundService.totalDownloadBytes.addAndGet(respBody.length.toLong()); GatewayForegroundService.trafficDownloadBytes.addAndGet(respBody.length.toLong())
                        call.respondText(respBody,ContentType.Application.Json.withCharset(Charsets.UTF_8),status=HttpStatusCode.fromValue(resp.code.takeIf{it>0}?:200))
                        logAccess(call,modelId,resp.code,System.currentTimeMillis()-eStartMs)
                    }catch(e:Exception){val(s,b)=openAIError(HttpStatusCode.InternalServerError,e.message?:"Engine embeddings failed","server_error");call.respondText(contentType=ContentType.Application.Json,status=s,text=b)}
                }

                // ★★★ WebSocket 实时语音（/v1/realtime）★★★
                webSocket("/v1/realtime") {
                    val model = call.parameters["model"] ?: "gpt-4o-realtime"
                    GatewayForegroundService.addDebugLog("🔊 WebSocket connected: model=$model")
                    try {
                        for (frame in incoming) {
                            when (frame) {
                                is Frame.Text -> {
                                    val text = frame.readText()
                                    GatewayForegroundService.addDebugLog("🔊 WS received: ${text.take(100)}")
                                    // 回显确认
                                    send(Frame.Text("{\"type\":\"response.audio.done\",\"model\":\"$model\"}"))
                                }
                                is Frame.Close -> {
                                    GatewayForegroundService.addDebugLog("🔊 WebSocket closed")
                                    close()
                                }
                                else -> {}
                            }
                        }
                    } catch (_: Exception) { }
                }

                // ★★★ 未实现接口（POST /v1/files + GET /v1/files）★★★
                get("/v1/files") {
                    val (s, b) = openAIError(HttpStatusCode.NotImplemented, "This endpoint is not implemented.", "not_implemented")
                    call.respondText(contentType = ContentType.Application.Json, status = s, text = b)
                }

                // === 通用代理转发：拦截所有 /v1/* 请求 ===
                // ★★ 去掉了 runBlocking！Ktor 路由 handler 本身就在协程中
                post("/v1/{path...}") {
                    if (!validateApiKey(call)) {
                        val (s, b) = openAIError(HttpStatusCode.Unauthorized, "Invalid or missing API key", "invalid_api_key")
                        call.respondText(contentType = ContentType.Application.Json, status = s, text = b)
                        return@post
                    }
                    try {
                        proxyRequest(call, database)
                    } catch (e: Exception) {
                        val (s, b) = openAIError(HttpStatusCode.InternalServerError, "Internal error: ${e.message}", "server_error")
                        try { call.respondText(contentType = ContentType.Application.Json, status = s, text = b) } catch (_: Exception) {}
                    }
                }
                get("/v1/{path...}") {
                    if (!validateApiKey(call)) {
                        val (s, b) = openAIError(HttpStatusCode.Unauthorized, "Invalid or missing API key", "invalid_api_key")
                        call.respondText(contentType = ContentType.Application.Json, status = s, text = b)
                        return@get
                    }
                    try {
                        proxyRequest(call, database)
                    } catch (e: Exception) {
                        val (s, b) = openAIError(HttpStatusCode.InternalServerError, "Internal error: ${e.message}", "server_error")
                        try { call.respondText(contentType = ContentType.Application.Json, status = s, text = b) } catch (_: Exception) {}
                    }
                }
                // 访问日志（需要API密钥验证）
                get("/v1/logs") {
                    if (!validateApiKey(call)) {
                        val (s, b) = openAIError(HttpStatusCode.Unauthorized, "Invalid or missing API key", "invalid_api_key")
                        call.respondText(contentType = ContentType.Application.Json, status = s, text = b)
                        return@get
                    }
                    val logs = synchronized(accessLog) { accessLog.toList() }
                    val logJson = proxyJson.encodeToString(buildJsonObject {
                        put("total", JsonPrimitive(logs.size))
                    })
                    call.respondText(logJson, ContentType.Application.Json.withCharset(Charsets.UTF_8))
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

// ★ API密钥验证 + 访问日志（顶层，供proxyRequest使用）
private val startTime = System.currentTimeMillis()
private val accessLog = mutableListOf<Map<String, Any>>()
private const val logMaxSize = 1000

private fun validateApiKey(call: ApplicationCall): Boolean {
    val requireKey = GatewayForegroundService.getRequireApiKey()
    if (!requireKey) return true
    // ★★ 本地请求免密钥 ★★
    val remoteIp = call.request.local.remoteHost ?: ""
    if (KeyManager.isLocalRequest(remoteIp)) return true
    val authHeader = call.request.headers["Authorization"]
    if (authHeader.isNullOrBlank()) return false
    val apiKey = authHeader.removePrefix("Bearer ").trim()
    // 验证密钥是否存在且启用
    val entry = KeyManager.validateKey(apiKey)
    return entry != null
}

private fun logAccess(call: ApplicationCall, modelId: String, statusCode: Int, durationMs: Long) {
    val entry = mapOf<String, Any>(
        "time" to System.currentTimeMillis(),
        "ip" to (call.request.local.remoteHost ?: ""),
        "method" to (call.request.httpMethod.value ?: ""),
        "path" to (call.parameters.getAll("path")?.joinToString("/") ?: ""),
        "model" to modelId,
        "status" to statusCode,
        "duration_ms" to durationMs
    )
    synchronized(accessLog) {
        accessLog.add(entry)
        if (accessLog.size > logMaxSize) accessLog.removeAt(0)
    }
    // ★★ 记录会话延迟（自适应超时用）★★
    if (durationMs > 0 && modelId != "unknown") {
        val sessionKey = "ip:${call.request.local.remoteHost ?: ""}"
        if (sessionKey.isNotBlank()) {
            recordSessionLatency(sessionKey, durationMs)
        }
    }
}

private val proxyJson = Json { ignoreUnknownKeys = true; prettyPrint = false }
private val DEFAULT_CT = "application/json".toMediaType()
private const val MAX_RETRIES = 3
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

/** 替换请求体中的model字段（用于qtai-sj解析后的真实模型ID替换） */
private fun replaceModelInBody(bodyStr: String, newModelId: String): String {
    return try {
        Regex(""""model"\s*:\s*"[^"]*"""").replace(bodyStr) { "\"model\":\"$newModelId\"" }
    } catch (_: Exception) { bodyStr }
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
private fun corsResponse(call: ApplicationCall) {
    call.response.headers.append("Access-Control-Allow-Origin", "*")
    call.response.headers.append("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
    call.response.headers.append("Access-Control-Allow-Headers", "Content-Type, Authorization, x-api-key, anthropic-version, x-goog-api-key")
}

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

/**
 * 从消息content中提取纯文本（支持字符串和多模态数组）
 */
private fun extractTextContent(content: kotlinx.serialization.json.JsonElement?): String {
    if (content == null) return ""
    return try {
        content.jsonPrimitive.content
    } catch (_: Exception) {
        try {
            val array = content.jsonArray
            array.joinToString("\n") { part ->
                try {
                    val obj = part.jsonObject
                    if (obj["type"]?.jsonPrimitive?.content == "text") {
                        obj["text"]?.jsonPrimitive?.content ?: ""
                    } else ""
                } catch (_: Exception) { "" }
            }.trim()
        } catch (_: Exception) { "" }
    }
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
            put("completion_tokens", JsonPrimitive(content.length))
            put("total_tokens", JsonPrimitive(content.length))
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

/** ★ 会话标识（优先用 API Key，其次用客户端IP） */
private fun getSessionKey(call: ApplicationCall): String {
        val auth = call.request.headers["Authorization"]
        if (!auth.isNullOrBlank()) {
            val key = auth.removePrefix("Bearer ").trim()
            return "auth:${key.hashCode()}"
        }
        val ip = call.request.local.remoteHost
        if (ip.isNotBlank()) return "ip:$ip"
        return "unknown:${UUID.randomUUID().toString().take(8)}"
    }

// ═══════════════════════════════════════════
// ★★ 会话延迟断开（闲置超时+自适应）★★
// ═══════════════════════════════════════════

/** 会话最后活跃时间戳 */
private val sessionLastActive = mutableMapOf<String, Long>()

/** 会话历史延迟（毫秒），用于自适应计算超时 */
private val sessionLatencyHistory = mutableMapOf<String, MutableList<Long>>()

/** 默认闲置超时基数（秒） */
private const val SESSION_IDLE_BASE_SECONDS = 30

/** 最大闲置超时（秒） */
private const val SESSION_IDLE_MAX_SECONDS = 120

/** 最小闲置超时（秒） */
private const val SESSION_IDLE_MIN_SECONDS = 10

/** 更新会话活跃时间 */
private fun updateSessionActivity(sessionKey: String) {
    sessionLastActive[sessionKey] = System.currentTimeMillis()
}

/** 记录会话延迟，用于自适应调节超时 */
private fun recordSessionLatency(sessionKey: String, latencyMs: Long) {
    val history = sessionLatencyHistory.getOrPut(sessionKey) { mutableListOf() }
    history.add(latencyMs)
    // 只保留最近5次
    if (history.size > 5) history.removeAt(0)
}

/** 根据历史延迟计算自适应超时（秒） */
private fun getAdaptiveTimeout(sessionKey: String): Int {
    val history = sessionLatencyHistory[sessionKey]
    if (history.isNullOrEmpty()) return SESSION_IDLE_BASE_SECONDS
    // 取平均延迟
    val avgLatency = history.average().toLong()
    // 超时 = 基数 + 平均延迟*2（转换为秒），确保延迟高的会话有更长超时
    val adaptive = SESSION_IDLE_BASE_SECONDS + (avgLatency * 2 / 1000).toInt()
    return adaptive.coerceIn(SESSION_IDLE_MIN_SECONDS, SESSION_IDLE_MAX_SECONDS)
}

/** 清理过期会话缓存（由协程定期调用） */
private fun cleanupExpiredSessions() {
    val now = System.currentTimeMillis()
    val expiredKeys = mutableListOf<String>()
    sessionLastActive.forEach { (key, lastActive) ->
        val timeoutMs = getAdaptiveTimeout(key) * 1000L
        if (now - lastActive > timeoutMs) {
            expiredKeys.add(key)
        }
    }
    expiredKeys.forEach { key ->
        sessionModelCache.remove(key)
        sessionLastActive.remove(key)
        sessionLatencyHistory.remove(key)
    }
    if (expiredKeys.isNotEmpty()) {
        GatewayForegroundService.addDebugLog("🧹 清理 ${expiredKeys.size} 个过期会话")
    }
}

/** 启动会话清理协程 */
private val sessionCleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
private fun startSessionCleanup() {
    sessionCleanupScope.launch {
        while (true) {
            delay(10000) // 每10秒检查一次
            cleanupExpiredSessions()
        }
    }
}

// 在 GatewayService 的 start 中调用
// startSessionCleanup() 已在 start() 中调用

/**
 * 通用代理转发：读取请求体 → 查找模型(如果是chat请求) → 转发到上游 → 管道式流回客户端
 * 支持图片/视频/音频等任意 Content-Type，数据不落盘，直接 pipe
 * ★ v3.1.0 新增自动故障转移：请求失败时自动切换到其他可用模型
 * ★ v3.3.2 新增会话记忆：同一会话失败的模型自动跳过，走上次成功的模型
 */
private suspend fun proxyRequest(call: ApplicationCall, database: AppDatabase) {
    // ★★ 所有响应加 CORS 头 ★★
    corsResponse(call)
    
    // ★ 请求开始 → 清零上一轮会话流量（在计数之前，避免清零本轮） ★
    GatewayForegroundService.resetNotificationTraffic()
    
    // 1. 读取原始请求体（二进制，兼容所有 Content-Type）
    val startMs = System.currentTimeMillis()
    val rawBytes = try { call.receive<ByteArray>() } catch (_: Exception) { ByteArray(0) }
    val requestBodyStr = String(rawBytes, Charsets.UTF_8)

    // ★★★ 全面请求体校验：给所有错误情况返回标准400，绝不挂起 ★★★
    if (requestBodyStr.isNotBlank()) {
        try {
            val j = proxyJson.parseToJsonElement(requestBodyStr).jsonObject
            
            // 1️⃣ 检查空body {}：没有任何字段
            if (j.isEmpty()) {
                val (s, b) = openAIError(HttpStatusCode.BadRequest, "Request body is empty. Provide at least 'model' and 'messages'.", "invalid_request_error", 400)
                call.respondText(contentType = ContentType.Application.Json, status = s, text = b)
                return
            }
            
            // 2️⃣ 检查 model 字段缺失
            if (!j.containsKey("model")) {
                val (s, b) = openAIError(HttpStatusCode.BadRequest, "Missing required field 'model'. Provide a model ID (e.g. 'gpt-4').", "invalid_request_error", 400)
                call.respondText(contentType = ContentType.Application.Json, status = s, text = b)
                return
            }
            
            // 3️⃣ 检查 chat 请求必须有 messages 字段（有model但无messages = 非法请求）
            if (!j.containsKey("messages")) {
                val (s, b) = openAIError(HttpStatusCode.BadRequest, "Missing required field 'messages'. Provide a non-empty array of message objects.", "invalid_request_error", 400)
                call.respondText(contentType = ContentType.Application.Json, status = s, text = b)
                return
            }
            
            // 3️⃣ 如果包含 messages 字段，检查它的类型和内容
            if (j.containsKey("messages")) {
                val msgs = j["messages"]
                if (msgs !is JsonArray) {
                    val (s, b) = openAIError(HttpStatusCode.BadRequest, "'messages' must be a non-empty array of message objects.", "invalid_request_error", 400)
                    call.respondText(contentType = ContentType.Application.Json, status = s, text = b)
                    return
                }
                if (msgs.isEmpty()) {
                    val (s, b) = openAIError(HttpStatusCode.BadRequest, "messages array is empty. Provide at least one message.", "invalid_request_error", 400)
                    call.respondText(contentType = ContentType.Application.Json, status = s, text = b)
                    return
                }
            }
        } catch (_: Exception) { }
    } else {
        // 4️⃣ 空body（无任何内容）
        val (s, b) = openAIError(HttpStatusCode.BadRequest, "Request body is empty. Send a valid JSON with 'model' and 'messages'.", "invalid_request_error", 400)
        call.respondText(contentType = ContentType.Application.Json, status = s, text = b)
        return
    }

    // ★★★ 全模型统计：所有请求都计上传流量（通知栏+总统计）★★★
    GatewayForegroundService.trafficUploadBytes.addAndGet(rawBytes.size.toLong())
    GatewayForegroundService.totalUploadBytes.addAndGet(rawBytes.size.toLong())

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
        com.qtwl.gateway.capture.PacketCapture.begin()
        com.qtwl.gateway.capture.PacketCapture.captureIn(
            sourceIp = call.request.local.remoteHost ?: "",
            method = call.request.httpMethod.value ?: "",
            path = "/$effectivePath",
            headers = call.request.headers.entries()
                .filter { it.key in listOf("Authorization", "Content-Type", "User-Agent") }
                .joinToString("; ") { "${it.key}: ${it.value.take(40)}" },
            body = requestBodyStr,
            bodySize = rawBytes.size
        )
    }

    val isChat = effectivePath == "chat/completions" || effectivePath == "completions"
    
    // ★★ 更新会话活跃时间（闲置超时用）★★
    if (isChat) {
        val sessionKey = getSessionKey(call)
        updateSessionActivity(sessionKey)
    }

    // ★★ 工具指令检测：在转发前先解析并执行操作指令 ★★
    if (isChat && requestBodyStr.isNotBlank()) {
        val requestJson = try { proxyJson.parseToJsonElement(requestBodyStr).jsonObject } catch (_: Exception) { null }
        var modelId = requestJson?.get("model")?.jsonPrimitive?.content
        val stream = requestJson?.get("stream")?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
        
        if (modelId == "qtai-sj" || GatewayForegroundService.isContinuousChat()) {
            // 连续对话模式：所有消息走綦小桐大脑
            // 从 messages 中提取用户最后一句指令
            var userMsg = try {
                val msgs = requestJson?.get("messages")?.jsonArray
                if (msgs != null && msgs.isNotEmpty()) {
                    val lastUserMsg = msgs.lastOrNull {
                        it?.jsonObject?.get("role")?.jsonPrimitive?.content == "user"
                    }?.jsonObject
                    extractTextContent(lastUserMsg?.get("content"))
                } else ""
            } catch (_: Exception) { "" }
            
            if (userMsg.isNotBlank()) {
                // ★★★ 群聊模式：不喊前缀也能用，直接走群聊引擎 ★★★
                if (GroupChatManager.isEnabled()) {
                    GatewayScheduler.recordModelUsage("qtai-sj")
                    val groupChatResult = GroupChatManager.executeGroupChat(database, userMsg)
                    val groupStream = requestJson?.get("stream")?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
                    if (groupStream) {
                        val chunkId = "chatcmpl-group-${UUID.randomUUID().toString().take(8)}"
                        val created = System.currentTimeMillis() / 1000
                        call.respondBytesWriter(contentType = ContentType.Text.EventStream) {
                            val roleChunk = proxyJson.encodeToString(buildJsonObject {
                                put("id", JsonPrimitive(chunkId))
                                put("object", JsonPrimitive("chat.completion.chunk"))
                                put("created", JsonPrimitive(created))
                                put("model", JsonPrimitive("qtai-sj"))
                                put("choices", JsonArray(listOf(buildJsonObject {
                                    put("index", JsonPrimitive(0))
                                    put("delta", buildJsonObject { put("role", JsonPrimitive("assistant")) })
                                    put("finish_reason", JsonNull)
                                })))
                            })
                            writeFully(("data: $roleChunk\n\n").toByteArray())
                            val contentChunk = proxyJson.encodeToString(buildJsonObject {
                                put("id", JsonPrimitive(chunkId))
                                put("object", JsonPrimitive("chat.completion.chunk"))
                                put("created", JsonPrimitive(created))
                                put("model", JsonPrimitive("qtai-sj"))
                                put("choices", JsonArray(listOf(buildJsonObject {
                                    put("index", JsonPrimitive(0))
                                    put("delta", buildJsonObject { put("content", JsonPrimitive(groupChatResult)) })
                                    put("finish_reason", JsonNull)
                                })))
                            })
                            writeFully(("data: $contentChunk\n\n").toByteArray())
                            val stopChunk = proxyJson.encodeToString(buildJsonObject {
                                put("id", JsonPrimitive(chunkId))
                                put("object", JsonPrimitive("chat.completion.chunk"))
                                put("created", JsonPrimitive(created))
                                put("model", JsonPrimitive("qtai-sj"))
                                put("choices", JsonArray(listOf(buildJsonObject {
                                    put("index", JsonPrimitive(0))
                                    put("delta", buildJsonObject {})
                                    put("finish_reason", JsonPrimitive("stop"))
                                })))
                            })
                            writeFully(("data: $stopChunk\n\n").toByteArray())
                            writeFully("data: [DONE]\n\n".toByteArray())
                        }
                    } else {
                        val groupResp = buildJsonObject {
                            put("id", JsonPrimitive("chatcmpl-group-${UUID.randomUUID().toString().take(8)}"))
                            put("object", JsonPrimitive("chat.completion"))
                            put("created", JsonPrimitive(System.currentTimeMillis() / 1000))
                            put("model", JsonPrimitive("qtai-sj"))
                            put("choices", JsonArray(listOf(buildJsonObject {
                                put("index", JsonPrimitive(0))
                                put("message", buildJsonObject { put("role", JsonPrimitive("assistant")); put("content", JsonPrimitive(groupChatResult)) })
                                put("finish_reason", JsonPrimitive("stop"))
                            })))
                            put("usage", buildJsonObject {
                                put("prompt_tokens", JsonPrimitive(userMsg.length / 4))
                                put("completion_tokens", JsonPrimitive(groupChatResult.length / 4))
                                put("total_tokens", JsonPrimitive((userMsg.length + groupChatResult.length) / 4))
                            })
                        }
                        call.respondText(contentType = ContentType.Application.Json.withCharset(Charsets.UTF_8), text = groupResp.toString())
                    }
                    logAccess(call, "qtai-sj", 200, System.currentTimeMillis() - startMs)
                    return
                }

                // ★★ 必须前缀才解析指令：固定前缀(綦小桐/qtai-sj/XiaoTong) + 自定义人格名称 ★★
                val customName = GatewayForegroundService.getQtaiSjName()
                val namePattern = if (customName.isNotBlank()) "綦小桐|qtai-sj|xiaotong|${Regex.escape(customName)}" else "綦小桐|qtai-sj|xiaotong"
                val prefixMatch = Regex("^($namePattern)[\\s,，:：]*(.+)$", RegexOption.IGNORE_CASE).find(userMsg.trim())
                val actualCmd = prefixMatch?.groupValues?.get(2)?.trim() ?: ""

                if (actualCmd.isNotBlank()) {
                    // ★★★ v3.8.7：大脑调度版 — 有脑子直接跳过硬匹配 ★★★
                    val brainModelIdForCmd = GatewayForegroundService.getQtaiSjBrain()
                    val brainModelForCmd = if (brainModelIdForCmd.isNotBlank()) database.aiModelDao().getEnabledModelsList().find { it.modelId == brainModelIdForCmd && it.isEnabled } else null
                    val brainProviderForCmd = if (brainModelForCmd != null) database.providerDao().getProviderById(brainModelForCmd.providerId) else null
                    if (brainModelForCmd != null && brainProviderForCmd != null && brainProviderForCmd.isEnabled) {
                        // ★ 有脑子 → 替换userMsg，直接进大脑理解，跳过旧式硬匹配 ★
                        userMsg = actualCmd
                        GatewayForegroundService.addDebugLog("🧠 大脑调度: $actualCmd")
                    } else {
                        // ★ 没有脑子 → 旧式硬指令匹配（兼容） ★
                        val actions = ToolExecutor.parseCommand(actualCmd)
                        if (actions.isNotEmpty()) {
                        // ★★★ qtai-sj工具指令统计：模型名（不覆盖通知栏真实模型名）★★★
                        GatewayScheduler.recordModelUsage("qtai-sj")
                        val results = actions.map { action ->
                            ToolExecutor.execute(action, null)
                        }.joinToString("\n")
                    
                        // ★★ 流式返回（模拟SSE）★★
                        if (stream) {
                            val chunkId = "chatcmpl-tool-${UUID.randomUUID().toString().take(8)}"
                            val created = System.currentTimeMillis() / 1000
                            call.respondBytesWriter(contentType = ContentType.Text.EventStream) {
                                val roleChunk = proxyJson.encodeToString(buildJsonObject {
                                    put("id", JsonPrimitive(chunkId))
                                    put("object", JsonPrimitive("chat.completion.chunk"))
                                    put("created", JsonPrimitive(created))
                                    put("model", JsonPrimitive("qtai-sj"))
                                    put("choices", JsonArray(listOf(buildJsonObject {
                                        put("index", JsonPrimitive(0))
                                        put("delta", buildJsonObject { put("role", JsonPrimitive("assistant")) })
                                        put("finish_reason", JsonNull)
                                    })))
                                })
                                writeFully(("data: $roleChunk\n\n").toByteArray())
                                // ★★ 一次性输出全部结果，不再逐字delay ★★
                                val contentChunk = proxyJson.encodeToString(buildJsonObject {
                                    put("id", JsonPrimitive(chunkId))
                                    put("object", JsonPrimitive("chat.completion.chunk"))
                                    put("created", JsonPrimitive(created))
                                    put("model", JsonPrimitive("qtai-sj"))
                                    put("choices", JsonArray(listOf(buildJsonObject {
                                        put("index", JsonPrimitive(0))
                                        put("delta", buildJsonObject { put("content", JsonPrimitive(results)) })
                                        put("finish_reason", JsonNull)
                                    })))
                                })
                                writeFully(("data: $contentChunk\n\n").toByteArray())
                                val stopChunk = proxyJson.encodeToString(buildJsonObject {
                                    put("id", JsonPrimitive(chunkId))
                                    put("object", JsonPrimitive("chat.completion.chunk"))
                                    put("created", JsonPrimitive(created))
                                    put("model", JsonPrimitive("qtai-sj"))
                                    put("choices", JsonArray(listOf(buildJsonObject {
                                        put("index", JsonPrimitive(0))
                                        put("delta", buildJsonObject {})
                                        put("finish_reason", JsonPrimitive("stop"))
                                    })))
                                })
                                writeFully(("data: $stopChunk\n\n").toByteArray())
                                writeFully("data: [DONE]\n\n".toByteArray())
                            }
                        } else {
                            val toolResponse = buildJsonObject {
                                put("id", JsonPrimitive("chatcmpl-tool-${UUID.randomUUID().toString().take(8)}"))
                                put("object", JsonPrimitive("chat.completion"))
                                put("created", JsonPrimitive(System.currentTimeMillis() / 1000))
                                put("model", JsonPrimitive("qtai-sj"))
                                put("choices", JsonArray(listOf(buildJsonObject {
                                    put("index", JsonPrimitive(0))
                                    put("message", buildJsonObject { put("role", JsonPrimitive("assistant")); put("content", JsonPrimitive(results)) })
                                    put("finish_reason", JsonPrimitive("stop"))
                                })))
                                put("usage", buildJsonObject {
                                    put("prompt_tokens", JsonPrimitive(userMsg.length / 4))
                                    put("completion_tokens", JsonPrimitive(results.length / 4))
                                    put("total_tokens", JsonPrimitive((userMsg.length + results.length) / 4))
                                })
                            }
                            call.respondText(contentType = ContentType.Application.Json.withCharset(Charsets.UTF_8), text = toolResponse.toString())
                        }
                        logAccess(call, "qtai-sj", 200, System.currentTimeMillis() - startMs)
                        return
                        }
                    }
                
                    // ★★ 硬指令未命中 → 用脑子模型理解自然语言 ★★
val brainModelId = GatewayForegroundService.getQtaiSjBrain()
if (brainModelId.isNotBlank()) {
    val brainModel = database.aiModelDao().getEnabledModelsList().find { it.modelId == brainModelId && it.isEnabled }
    val brainProvider = if (brainModel != null) database.providerDao().getProviderById(brainModel.providerId) else null

    if (brainModel != null && brainProvider != null && brainProvider.isEnabled) {
        // ★★★ 群聊模式：如果开启则走群聊引擎 ★★★
        if (GroupChatManager.isEnabled()) {
            GatewayScheduler.recordModelUsage(brainModelId)
            val groupChatResult = GroupChatManager.executeGroupChat(database, userMsg)
            val stream = requestJson?.get("stream")?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
            if (stream) {
                val chunkId = "chatcmpl-group-${UUID.randomUUID().toString().take(8)}"
                val created = System.currentTimeMillis() / 1000
                call.respondBytesWriter(contentType = ContentType.Text.EventStream) {
                    val roleChunk = proxyJson.encodeToString(buildJsonObject {
                        put("id", JsonPrimitive(chunkId))
                        put("object", JsonPrimitive("chat.completion.chunk"))
                        put("created", JsonPrimitive(created))
                        put("model", JsonPrimitive("qtai-sj"))
                        put("choices", JsonArray(listOf(buildJsonObject {
                            put("index", JsonPrimitive(0))
                            put("delta", buildJsonObject { put("role", JsonPrimitive("assistant")) })
                            put("finish_reason", JsonNull)
                        })))
                    })
                    writeFully(("data: $roleChunk\n\n").toByteArray())
                    val contentChunk = proxyJson.encodeToString(buildJsonObject {
                        put("id", JsonPrimitive(chunkId))
                        put("object", JsonPrimitive("chat.completion.chunk"))
                        put("created", JsonPrimitive(created))
                        put("model", JsonPrimitive("qtai-sj"))
                        put("choices", JsonArray(listOf(buildJsonObject {
                            put("index", JsonPrimitive(0))
                            put("delta", buildJsonObject { put("content", JsonPrimitive(groupChatResult)) })
                            put("finish_reason", JsonNull)
                        })))
                    })
                    writeFully(("data: $contentChunk\n\n").toByteArray())
                    val stopChunk = proxyJson.encodeToString(buildJsonObject {
                        put("id", JsonPrimitive(chunkId))
                        put("object", JsonPrimitive("chat.completion.chunk"))
                        put("created", JsonPrimitive(created))
                        put("model", JsonPrimitive("qtai-sj"))
                        put("choices", JsonArray(listOf(buildJsonObject {
                            put("index", JsonPrimitive(0))
                            put("delta", buildJsonObject {})
                            put("finish_reason", JsonPrimitive("stop"))
                        })))
                    })
                    writeFully(("data: $stopChunk\n\n").toByteArray())
                    writeFully("data: [DONE]\n\n".toByteArray())
                }
            } else {
                val resp = buildJsonObject {
                    put("id", JsonPrimitive("chatcmpl-group-${UUID.randomUUID().toString().take(8)}"))
                    put("object", JsonPrimitive("chat.completion"))
                    put("created", JsonPrimitive(System.currentTimeMillis() / 1000))
                    put("model", JsonPrimitive("qtai-sj"))
                    put("choices", JsonArray(listOf(buildJsonObject {
                        put("index", JsonPrimitive(0))
                        put("message", buildJsonObject { put("role", JsonPrimitive("assistant")); put("content", JsonPrimitive(groupChatResult)) })
                        put("finish_reason", JsonPrimitive("stop"))
                    })))
                    put("usage", buildJsonObject {
                        put("prompt_tokens", JsonPrimitive(userMsg.length / 4))
                        put("completion_tokens", JsonPrimitive(groupChatResult.length / 4))
                        put("total_tokens", JsonPrimitive((userMsg.length + groupChatResult.length) / 4))
                    })
                }
                call.respondText(contentType = ContentType.Application.Json.withCharset(Charsets.UTF_8), text = resp.toString())
            }
            logAccess(call, "qtai-sj", 200, System.currentTimeMillis() - startMs)
            return
        }
// ★★★ qtai-sj统计：记录使用（不覆盖activeNodeName，保持用户选的真实模型）★★★
            GatewayScheduler.recordModelUsage(brainModelId)
        // ★ 用脑子模型分析用户意图（带排行榜+模型能力标记）★★
        val rankingInfo = if (GatewayScheduler.pipelineSortedModelIds.isNotEmpty()) {
            "当前测速排行（按速度排序）：\n" + GatewayScheduler.pipelineSortedModelIds.mapIndexed { i, id -> 
                val m = database.aiModelDao().getEnabledModelsList().find { it.modelId == id }
                val displayName = m?.customAlias?.takeIf { it.isNotBlank() } ?: m?.displayName ?: id
                val caps = if (m != null) ModelCapabilityManager.getCapabilities(m.modelId) else Triple(false, false, false)
                val tags = buildString {
                    if (caps.first) append("🛠️")
                    if (caps.second) append("👁️")
                    if (caps.third) append("🎨")
                    if (isEmpty()) append("💬")
                }
                "  ${i+1}. $id ($displayName) $tags"
            }.joinToString("\n")
        } else {
            val allEnabled = database.aiModelDao().getEnabledModelsList()
            if (allEnabled.isEmpty()) "暂无可用模型，请先添加服务商"
            else "当前可用模型（未测速）：\n" + allEnabled.mapIndexed { i, m ->
                val caps = ModelCapabilityManager.getCapabilities(m.modelId)
                val tags = buildString {
                    if (caps.first) append("🛠️")
                    if (caps.second) append("👁️")
                    if (caps.third) append("🎨")
                    if (isEmpty()) append("💬")
                }
                "  ${i+1}. ${m.modelId} (${m.displayName}) $tags"
            }.joinToString("\n")
        }
                            val brainPrompt = """你叫${customName.ifBlank { "綦小桐" }}，是綦桐AI网关的智能助手。你拥有完整的思考能力和网关控制能力。

${BrainMemoryManager.buildPersonaPrompt()}

你可以做以下事情：
1. **智能聊天** — 像真人一样自由对话，理解用户的意图
2. **控制网关** — 切换模型、查状态、测速、开关故障转移等
3. **推荐模型** — 根据用户需求推荐最合适的模型

当前可用模型排行：
$rankingInfo

当前活动模型：${GatewayForegroundService.activeNodeName.ifBlank { "无" }}

能力标记：🎨图片生成 🛠️工具调用 👁️视觉识别 💬纯文本聊天

${SkillRegistry.buildSkillPrompt()}

注意：
- 你使用自然语言理解用户的意图，然后从技能池中选择最合适的技能来执行
- 用户永远不需要知道编码数字，完全由你决定
- 同一件事用户有多种说法，你都能理解并命中对应编码
- 在回复末尾加上【指令:编码】来执行技能（这只是你内部调用的标记，用户看不到！）
- 你的回复直接就是给用户的自然语言回答，不要暴露任何编码、指令标记
- 如果用户只是聊天/问问题，不需要执行任何技能，直接回复即可
- 如果用户明确要求操作（如切换模型、查状态等），先理解语义，再调用对应编码
- 例如：用户说"查排行"→ 你回复"当前排行是这样的：1.xxx 2.xxx" 同时末尾加【指令:600002】

记住：你是${customName.ifBlank { "綦小桐" }}，要有自己的思考和个性，像真人一样回复用户。""" + ThinkingConfigManager.buildThinkingPrompt()

                            val brainBody = """{"model":"${brainModel.modelId}","messages":[{"role":"system","content":${proxyJson.encodeToString(JsonPrimitive(brainPrompt))}},{"role":"user","content":${proxyJson.encodeToString(JsonPrimitive(userMsg))}}],"max_tokens":500,"stream":${stream},"temperature":0.7}"""
                        
                            try {
                                val brainClient = UpstreamClient.getDirectClient()
                                val brainReq = okhttp3.Request.Builder()
                                    .url("${brainProvider.resolvedBaseUrl.trimEnd('/')}/v1/chat/completions")
                                    .post(brainBody.toByteArray().toRequestBody(DEFAULT_CT))
                                    .apply { if (!brainProvider.apiKey.isNullOrBlank()) header("Authorization", "Bearer ${brainProvider.apiKey}") }
                                    .build()
                                 val brainResp = withContext(Dispatchers.IO) { brainClient.newCall(brainReq).execute() }

                                 // ★★★ 修复大脑流式：stream=true时正确读取SSE流 ★★★
                                var brainBodyStr = ""
                                var brainJson: kotlinx.serialization.json.JsonObject? = null
                                var brainContent = if (stream) {
                                    val bodyBytes = brainResp.body?.bytes() ?: byteArrayOf()
                                    brainResp.close()
                                    val sseText = bodyBytes.decodeToString()
                                    brainBodyStr = sseText
                                    val contentBuilder = StringBuilder()
                                    for (line in sseText.lines()) {
                                        if (line.startsWith("data: ") && line != "data: [DONE]") {
                                            val jsonStr = line.removePrefix("data: ")
                                            try {
                                                val chunk = proxyJson.parseToJsonElement(jsonStr).jsonObject
                                                val choices = chunk["choices"]?.jsonArray
                                                val first = choices?.firstOrNull()?.jsonObject
                                                val delta = first?.get("delta")?.jsonObject
                                                val content = delta?.get("content")?.jsonPrimitive?.content
                                                if (content != null) contentBuilder.append(content)
                                                // 尝试提取最后一个chunk中的usage
                                                val usage = chunk["usage"]?.jsonObject
                                                if (usage != null) brainJson = chunk
                                            } catch (_: Exception) {}
                                        }
                                    }
                                    contentBuilder.toString()
                                } else {
                                    val bodyStr = brainResp.body?.string() ?: "{}"
                                    brainResp.close()
                                    brainBodyStr = bodyStr
                                    brainJson = try { proxyJson.parseToJsonElement(bodyStr).jsonObject } catch (_: Exception) { null }
                                    brainJson?.get("choices")?.jsonArray?.firstOrNull()?.jsonObject?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.content ?: ""
                                }

// ★★★ qtai-sj脑子路径统计：解析usage+下载流量+记忆 ★★★
val brainUsage = brainJson?.get("usage")?.jsonObject
val promptTokens = brainUsage?.get("prompt_tokens")?.jsonPrimitive?.content?.toIntOrNull() ?: 0
val completionTokens = brainUsage?.get("completion_tokens")?.jsonPrimitive?.content?.toIntOrNull() ?: 0
if (promptTokens > 0) GatewayForegroundService.tokenPromptInput += promptTokens
if (completionTokens > 0) GatewayForegroundService.tokenCompletionOutput += completionTokens
val brainRespBytes = brainBodyStr.toByteArray(Charsets.UTF_8)
GatewayForegroundService.trafficDownloadBytes.addAndGet(brainRespBytes.size.toLong())
GatewayForegroundService.totalDownloadBytes.addAndGet(brainRespBytes.size.toLong())
GatewayForegroundService.addLiveSession(LiveSession(
    modelName = brainModelId,
    requestPreview = userMsg.take(30),
    status = "📥 回复",
    responsePreview = brainContent.take(30),
    timestamp = System.currentTimeMillis()
))
// 同步写BrainMemoryManager记忆
if (BrainMemoryManager.getConfig().enabled && userMsg.isNotBlank() && brainContent.isNotBlank()) {
    BrainMemoryManager.addMemory(content = "用户: $userMsg", title = userMsg.take(40))
    BrainMemoryManager.addMemory(content = "AI: $brainContent", title = brainContent.take(40))
}

// ★★ 解析脑子回复中是否有技能编码并执行（支持带参数）★★
val skillCodeParams = ToolExecutor.extractSkillCodesWithParams(brainContent)
if (skillCodeParams.isNotEmpty()) {
    // ★ 先剥离大脑回复中的【指令:xxx】标记（只对用户说自然语言）
    val cleanBrain = brainContent.replace(Regex("【指令:\\d{6}(?::[^】]+)?】"), "").trim()
    
    val skillResults = StringBuilder()
    skillCodeParams.forEach { (code, param) ->
        val skill = SkillRegistry.getSkillByCode(code)
        val result = ToolExecutor.executeByCode(code, param, null)
        GatewayForegroundService.addDebugLog("🧠 执行技能 ${skill?.name ?: code}: param=$param → $result")
        if (result.isNotBlank()) {
            skillResults.append("\n").append(result)
        }
    }
    GatewayForegroundService.addDebugLog("🧠 技能执行完成: ${skillCodeParams.size}个")
    // ★ 用自然语言结果替换，不暴露任何编码！
    brainContent = if (skillResults.isNotBlank()) {
        // 如果大脑已经有自然语言回复了，把结果附加在后面
        // 如果大脑只是输出编码没说话，结果就是回复
        "$cleanBrain\n\n$skillResults"
    } else {
        cleanBrain
    }
} else if (actualCmd.isNotBlank()) {
    // ★ 大脑没输出编码 → 兜底：用actualCmd走旧式硬指令匹配（兼容）
    val fallbackActions = ToolExecutor.parseCommand(actualCmd)
    if (fallbackActions.isNotEmpty()) {
        GatewayForegroundService.addDebugLog("🧠 大脑未输出技能编码，fallback硬指令: $actualCmd")
        val fallbackResults = fallbackActions.map { action ->
            ToolExecutor.execute(action, null)
        }.joinToString("\n")
        brainContent += "\n\n$fallbackResults"
    }
}

// ★ 解析脑子返回的响应（直接输出脑子回复，不透传） ★
if (brainContent.isNotBlank()) {
    if (stream) {
                                        val chunkId = "chatcmpl-brain-${UUID.randomUUID().toString().take(8)}"
                                        val created = System.currentTimeMillis() / 1000
                                        call.respondBytesWriter(contentType = ContentType.Text.EventStream) {
                                            val roleChunk = proxyJson.encodeToString(buildJsonObject {
                                                put("id", JsonPrimitive(chunkId))
                                                put("object", JsonPrimitive("chat.completion.chunk"))
                                                put("created", JsonPrimitive(created))
                                                put("model", JsonPrimitive("qtai-sj"))
                                                put("choices", JsonArray(listOf(buildJsonObject {
                                                    put("index", JsonPrimitive(0))
                                                    put("delta", buildJsonObject { put("role", JsonPrimitive("assistant")) })
                                                    put("finish_reason", JsonNull)
                                                })))
                                            })
                                            writeFully(("data: $roleChunk\n\n").toByteArray())
                                            val contentChunk = proxyJson.encodeToString(buildJsonObject {
                                                put("id", JsonPrimitive(chunkId))
                                                put("object", JsonPrimitive("chat.completion.chunk"))
                                                put("created", JsonPrimitive(created))
                                                put("model", JsonPrimitive("qtai-sj"))
                                                put("choices", JsonArray(listOf(buildJsonObject {
                                                    put("index", JsonPrimitive(0))
                                                    put("delta", buildJsonObject { put("content", JsonPrimitive(brainContent)) })
                                                    put("finish_reason", JsonNull)
                                                })))
                                            })
                                            writeFully(("data: $contentChunk\n\n").toByteArray())
                                            val stopChunk = proxyJson.encodeToString(buildJsonObject {
                                                put("id", JsonPrimitive(chunkId))
                                                put("object", JsonPrimitive("chat.completion.chunk"))
                                                put("created", JsonPrimitive(created))
                                                put("model", JsonPrimitive("qtai-sj"))
                                                put("choices", JsonArray(listOf(buildJsonObject {
                                                    put("index", JsonPrimitive(0))
                                                    put("delta", buildJsonObject {})
                                                    put("finish_reason", JsonPrimitive("stop"))
                                                })))
                                            })
                                            writeFully(("data: $stopChunk\n\n").toByteArray())
                                            writeFully("data: [DONE]\n\n".toByteArray())
                                        }
                                    } else {
                                        val brainUsage = brainJson?.get("usage")?.jsonObject
                                        val promptTokens = brainUsage?.get("prompt_tokens")?.jsonPrimitive?.content?.toIntOrNull() ?: (brainPrompt.length / 4)
                                        val completionTokens = brainUsage?.get("completion_tokens")?.jsonPrimitive?.content?.toIntOrNull() ?: (brainContent.length / 4)
                                        val totalTokens = brainUsage?.get("total_tokens")?.jsonPrimitive?.content?.toIntOrNull() ?: (promptTokens + completionTokens)
                                        val resp = buildJsonObject {
                                            put("id", JsonPrimitive("chatcmpl-brain-${UUID.randomUUID().toString().take(8)}"))
                                            put("object", JsonPrimitive("chat.completion"))
                                            put("created", JsonPrimitive(System.currentTimeMillis() / 1000))
                                            put("model", JsonPrimitive("qtai-sj"))
                                            put("choices", JsonArray(listOf(buildJsonObject {
                                                put("index", JsonPrimitive(0))
                                                put("message", buildJsonObject { put("role", JsonPrimitive("assistant")); put("content", JsonPrimitive(brainContent)) })
                                                put("finish_reason", JsonPrimitive("stop"))
                                            })))
                                            put("usage", buildJsonObject {
                                                put("prompt_tokens", JsonPrimitive(promptTokens))
                                                put("completion_tokens", JsonPrimitive(completionTokens))
                                                put("total_tokens", JsonPrimitive(totalTokens))
                                            })
                                        }
                                        call.respondText(contentType = ContentType.Application.Json.withCharset(Charsets.UTF_8), text = resp.toString())
                                    }
                                    return
                                }
                            } catch (_: Exception) {
 // 【刀4+刀8】脑子失败，返回502而不是静默空转
 val errorResp = buildJsonObject {
  put("error", buildJsonObject {
   put("message", JsonPrimitive("脑子模型请求失败，请检查服务商配置或稍后重试"))
   put("type", JsonPrimitive("brain_error"))
  })
 }
 call.respondText(contentType = ContentType.Application.Json.withCharset(Charsets.UTF_8), text = errorResp.toString())
 logAccess(call, "qtai-sj", 502, System.currentTimeMillis() - startMs)
}
                        }
                    }
                }
            }
        }
        
        // ★★ qtai-sj没有前缀或脑子说chat → 走正常转发：用排行榜最快的模型直接透传 ★★
        if (modelId == "qtai-sj") {
            // 找最适合的模型
            val allEnabledModels = database.aiModelDao().getEnabledModelsList().filter { it.isEnabled }
            val forced = GatewayForegroundService.getForcedModel()
            // ★ 如果强制模型是qtai-sj（虚拟模型），用上一次切换的模型或排行榜
            val effectiveForced = if (forced == "qtai-sj") GatewayForegroundService.activeNodeName.ifBlank { null } else forced.ifBlank { null }
            // ★ 检测是否有多模态图片内容
            val hasImage = requestJson?.get("messages")?.jsonArray?.any { msg ->
                try {
                    val content = msg?.jsonObject?.get("content")
                    content is JsonArray && content.any { part ->
                        part?.jsonObject?.get("type")?.jsonPrimitive?.content == "image_url"
                    }
                } catch (_: Exception) { false }
            } ?: false
            val bestModel = if (!effectiveForced.isNullOrBlank()) {
                // 支持两种格式：完整前缀(deepseek-ai/deepseek-v4-flash) 和 短ID(deepseek-v4-flash)
                val directMatch = allEnabledModels.find { it.modelId == effectiveForced }
                if (directMatch != null) {
                    directMatch
                } else {
                    val shortId = effectiveForced.substringAfterLast('/')
                    if (shortId != effectiveForced) {
                        allEnabledModels.find { it.modelId == shortId }
                    } else null
                }
            } else if (hasImage) {
                allEnabledModels.firstOrNull { ModelCapabilityManager.getCapabilities(it.modelId).second }
                    ?: GatewayScheduler.pipelineSortedModelIds.firstNotNullOfOrNull { id ->
                        allEnabledModels.find { it.modelId == id && ModelCapabilityManager.getCapabilities(it.modelId).second }
                    }
            } else null
            val targetModel = bestModel ?: GatewayScheduler.pipelineSortedModelIds.firstNotNullOfOrNull { id -> 
                allEnabledModels.find { it.modelId == id } 
            } ?: allEnabledModels.firstOrNull()
            
            // ★★★ 如果仍然找不到目标模型，尝试用 activeNodeName 或任意已启用模型 ★★★
            val finalTarget = targetModel ?: run {
                if (GatewayForegroundService.activeNodeName.isNotBlank()) {
                    allEnabledModels.find { it.modelId == GatewayForegroundService.activeNodeName }
                } else null
            } ?: allEnabledModels.firstOrNull()
            
            if (finalTarget != null) {
                val provider = database.providerDao().getProviderById(finalTarget.providerId)
                if (provider != null && provider.isEnabled) {
<<<<<<< HEAD
                    // ★★ 通知栏同步模型名 ★★
                    GatewayForegroundService.activeNodeName = finalTarget.modelId
                    // ★★ 设置 modelId/providerId 属性，确保 token 统计能正确记录 ★★
                    call.attributes.put(MODEL_ID_KEY, finalTarget.modelId)
                    call.attributes.put(PROVIDER_ID_KEY, finalTarget.providerId)
                    // ★★ 透传：修正参数 + 替换model字段 + 可选人格注入 ★★
                    val sanitizedBody = sanitizeRequestBody(requestBodyStr)
                    val bodyWithPersona = if (BrainMemoryManager.getConfig().enabled) {
                        val personaText = BrainMemoryManager.buildPersonaPrompt()
                        if (personaText.isNotBlank()) {
                            val systemJson = "{\"role\":\"system\",\"content\":${proxyJson.encodeToString(JsonPrimitive(personaText))}}"
                            sanitizedBody.replaceFirst(Regex("\"messages\"\\s*:\\s*\\["), "\"messages\":[$systemJson,")
                        } else sanitizedBody
                    } else sanitizedBody
                    // 用正则只替换第一个model字段（避免全局替换损坏JSON）
                    val modifiedBody = bodyWithPersona.replaceFirst(Regex("\"model\"\\s*:\\s*\"[^\"]+\""), "\"model\":\"${finalTarget.modelId}\"")
=======
                    // ★★ 通知栏同步模型名（仅当变化时更新，避免通知栏闪烁）★★
                    if (GatewayForegroundService.activeNodeName != finalTarget.modelId) {
                        GatewayForegroundService.activeNodeName = finalTarget.modelId
                    }
                    // ★★ 设置 modelId/providerId 属性，确保 token 统计能正确记录 ★★
                    call.attributes.put(MODEL_ID_KEY, finalTarget.modelId)
                    call.attributes.put(PROVIDER_ID_KEY, finalTarget.providerId)
                    // ★★ 透传：修正参数 + 替换model字段（不注入人格，透传就是透传）★★
                    val modifiedBody = sanitizeRequestBody(requestBodyStr).replaceFirst(Regex("\"model\"\\s*:\\s*\"[^\"]+\""), "\"model\":\"${finalTarget.modelId}\"")
>>>>>>> c8003e9 (v3.18.1 正式发布 - 修复网关500+通知栏闪烁+模型双排布局)
                    val modifiedBytes = modifiedBody.toByteArray()
                    val useProxy = finalTarget.useProxy
                    
                    if (stream) {
                        pipeStreamResponse(call, provider, modifiedBytes, "/v1/$effectivePath", finalTarget.modelId, finalTarget.providerId, database, useProxy)
                    } else {
                        pipeNormalResponse(call, provider, modifiedBytes, "/v1/$effectivePath", database, useProxy)
                    }
                    GatewayScheduler.recordModelResult(finalTarget.modelId, true)
                    GatewayScheduler.recordModelUsage(finalTarget.modelId)
                    GatewayForegroundService.addDebugLog("🔄 qtai-sj透传 → ${finalTarget.modelId}")
                    return
                }
            }
            
            // ★★★ 所有模型都不可用，返回错误而非静默 ★★★
            val noModelResp = buildJsonObject {
                put("error", buildJsonObject {
                    put("message", JsonPrimitive("qtai-sj无前缀转发失败：没有可用的已启用模型"))
                    put("type", JsonPrimitive("no_available_model"))
                })
            }
            call.respondText(contentType = ContentType.Application.Json.withCharset(Charsets.UTF_8), text = noModelResp.toString())
            logAccess(call, "qtai-sj", 503, System.currentTimeMillis() - startMs)
            return
        }
    }

    if (isChat && requestBodyStr.isNotBlank()) {
        val requestJson = try { proxyJson.parseToJsonElement(requestBodyStr).jsonObject } catch (_: Exception) { null }
        var modelId = requestJson?.get("model")?.jsonPrimitive?.content
        val stream = requestJson?.get("stream")?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false

        if (modelId != null) {
            // ★★ 多模态支持：检测图片并自动切换视觉模型 ★★
            val hasImage = requestJson?.get("messages")?.jsonArray?.any { msg ->
                try {
                    val content = msg?.jsonObject?.get("content")
                    content is kotlinx.serialization.json.JsonArray && content.any { part ->
                        part?.jsonObject?.get("type")?.jsonPrimitive?.content == "image_url"
                    }
                } catch (_: Exception) { false }
            } ?: false
            // ★ 如果请求含图片但目标模型不支持视觉 → 就地切换为视觉模型 ★
            val effectiveModelId = if (hasImage && modelId != "qtai-sj") {
                val allModels = database.aiModelDao().getEnabledModelsList().filter { it.isEnabled }
                val visionModel = allModels.firstOrNull { ModelCapabilityManager.getCapabilities(it.modelId).second }
                    ?: allModels.firstNotNullOfOrNull { m ->
                        GatewayScheduler.pipelineSortedModelIds.firstNotNullOfOrNull { id ->
                            allModels.find { it.modelId == id && ModelCapabilityManager.getCapabilities(it.modelId).second }
                        }
                    }
                if (visionModel != null && modelId != visionModel.modelId) {
                    // 记录切换日志，继续用原始modelId发起请求（用户选的）
                    // 但在转发时会自动替换model字段
                    // ★★ 通知栏同步模型名（仅当变化时更新，避免通知栏闪烁）★★
                    if (GatewayForegroundService.activeNodeName != visionModel.modelId) {
                        GatewayForegroundService.activeNodeName = visionModel.modelId
                    }
                    GatewayForegroundService.addDebugLog("👁️ 图片检测→自动切视觉: $modelId → ${visionModel.modelId}")
                    visionModel.modelId
                } else modelId
            } else modelId
            modelId = effectiveModelId
            // ★ 如果model被覆盖（多模态切换），同步修改请求体中的model字段 ★
            val finalRequestBodyStr = if (modelId != (requestJson?.get("model")?.jsonPrimitive?.content ?: "")) {
                requestBodyStr.replace(Regex("\"model\":\".*?\""), "\"model\":\"${modelId}\"")
            } else requestBodyStr
            val finalRawBytes = finalRequestBodyStr.toByteArray()
            val autoFailover = GatewayForegroundService.getAutoFailover()

            if (autoFailover) {
                GatewayScheduler.refreshHealthCache(database)
            }
val allEnabled = database.aiModelDao().getEnabledModelsList().filter { it.isEnabled }
val attemptModels: List<AiModel> = if (allEnabled.isNotEmpty()) {
                    // ★★ 自动化切换 (qtai-sj) ★★
                    if (modelId == "qtai-sj") {
                        val qtaiSjEnabled = GatewayForegroundService.getQtaiSjEnabled()
                        if (!qtaiSjEnabled) {
                            // qtai-sj 已禁用，返回空列表
                            emptyList()
                        } else {
                            // ★★ 检查是否有手动强制切换的模型 ★★
                            val forcedModelId = GatewayForegroundService.getForcedModel()
                            if (forcedModelId.isNotBlank()) {
                                val forced = allEnabled.find { it.modelId == forcedModelId }
                                if (forced != null) {
                                    listOf(forced) // 强制只使用这个模型
                                } else {
                                    // 强制模型不存在了，回退到自动
                                    if (GatewayScheduler.pipelineSortedModelIds.isEmpty()) {
                                        listOfNotNull(allEnabled.sortedBy { it.modelId }.firstOrNull())
                                    } else {
                                        GatewayScheduler.pipelineSortedModelIds.mapNotNull { id -> allEnabled.find { it.modelId == id } }.ifEmpty { allEnabled }
                                    }
                                }
                            } else if (GatewayScheduler.pipelineSortedModelIds.isEmpty()) {
                                // 无测速数据时，智能排序
                                GatewayScheduler.smartSort(allEnabled).ifEmpty { allEnabled }
                            } else {
                                // ★★ 使用智能排序：a(当前可用)→d(历史成功)→b(历史可用)→c(失败) ★★
                                GatewayScheduler.smartSort(allEnabled).ifEmpty { allEnabled }
                            }
                        }
                    } else if (autoFailover) {
                    // ★★ 其他模型 + 故障转移开启：用户权威模式
                    val primary = allEnabled.find { it.modelId == modelId }
                    val sessionKey = getSessionKey(call)
                    val lastGoodModel = sessionModelCache[sessionKey]

                    val pipelineSorted = if (GatewayScheduler.pipelineSortedModelIds.isNotEmpty()) {
                        GatewayScheduler.pipelineSortedModelIds.mapNotNull { id -> allEnabled.find { it.modelId == id } }
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
            
            // ★★ 请求一来就创建实时会话（歌词式）★★
            val rawModelName = requestJson?.get("model")?.jsonPrimitive?.content ?: "unknown"
            // 提取用户消息（普通人能看懂）
            val userMsg = try {
                val msgs = requestJson?.get("messages")?.jsonArray
                if (msgs != null && msgs.isNotEmpty()) {
                    val lastUser = msgs.lastOrNull {
                        it?.jsonObject?.get("role")?.jsonPrimitive?.content == "user"
                    }
                    lastUser?.jsonObject?.get("content")?.jsonPrimitive?.content?.take(40) ?: ""
                } else ""
            } catch (_: Exception) { "" }
            val displayPreview = if (userMsg.isNotBlank()) userMsg else requestBodyStr.take(30).replace("\n", " ").trim()
            val session = LiveSession(
                modelName = rawModelName,
                requestPreview = displayPreview,
                status = "📤 发送",
                responsePreview = ""
            )
            GatewayForegroundService.addLiveSession(session)
            
            // ★★ 只试第一个模型，不通才走排行榜后续 ★★
            if (attemptModels.isNotEmpty()) {
            val primaryModel = attemptModels.first()
            val provider = database.providerDao().getProviderById(primaryModel.providerId)
            if (provider != null && provider.isEnabled) {
                try {
                    call.attributes.put(MODEL_ID_KEY, primaryModel.modelId)
                    call.attributes.put(PROVIDER_ID_KEY, primaryModel.providerId)
                    // ★★ 通知栏同步模型名（仅当变化时更新，避免通知栏闪烁）★★
                    if (GatewayForegroundService.activeNodeName != primaryModel.modelId) {
                        GatewayForegroundService.activeNodeName = primaryModel.modelId
                    }
                    GatewayScheduler.recordModelUsage(primaryModel.modelId)
                    val useProxy = primaryModel.useProxy

                    val sanitizedBody = sanitizeRequestBody(finalRequestBodyStr)
                    // ★★ 人格+记忆注入 ★★
                    val bodyWithPersona = if (BrainMemoryManager.getConfig().enabled) {
                        val personaText = BrainMemoryManager.buildPersonaPrompt()
                        if (personaText.isNotBlank()) {
                            val systemJson = "{\"role\":\"system\",\"content\":${proxyJson.encodeToString(JsonPrimitive(personaText))}}"
                            sanitizedBody.replaceFirst(Regex("\"messages\"\\s*:\\s*\\["), "\"messages\":[$systemJson,")
                        } else sanitizedBody
                    } else sanitizedBody
                    // ★★ qtai-sj 替换模型ID ★★
                    val modifiedBody = if (modelId == "qtai-sj" || (autoFailover && primaryModel.modelId != modelId)) {
                        bodyWithPersona.replaceFirst(Regex("\"model\"\\s*:\\s*\"[^\"]+\""), "\"model\":\"${primaryModel.modelId}\"")
                    } else bodyWithPersona
                    val modifiedBytes = modifiedBody.toByteArray()

                    if (stream) {
                        pipeStreamResponse(call, provider, modifiedBytes, "/v1/$effectivePath", primaryModel.modelId, primaryModel.providerId, database, useProxy)
                    } else {
                        pipeNormalResponse(call, provider, modifiedBytes, "/v1/$effectivePath", database, useProxy)
                    }
                    
                    recordSessionModel(call, primaryModel.modelId)
                    GatewayScheduler.recordModelResult(primaryModel.modelId, true)
                    GatewayForegroundService.updateLiveSession(session.id, "📥 回复", "✅ 成功")
                    return
                } catch (e: Exception) {
                    failCount++
                    lastError = "${primaryModel.modelId}: ${e.message}"
                    synchronized(GatewayScheduler.healthCache) { GatewayScheduler.healthCache[primaryModel.modelId] = GatewayScheduler.ModelHealth(primaryModel.modelId, primaryModel.providerId, Long.MAX_VALUE, System.currentTimeMillis(), false) }
                    if (GatewayForegroundService.getDebugMode()) GatewayForegroundService.addDebugLog("✗ ${primaryModel.modelId}: ${e.message?.take(60)}")
                    // 主模型失败，继续尝试后续模型
                }
            }
            
            // ★★ 主模型失败后，快速遍历后续模型（不预检测，直接转发）★★
            for ((idx, matchedModel) in attemptModels.withIndex()) {
                if (idx == 0) continue // 跳过已经试过的主模型
                if (GatewayForegroundService.getDebugMode()) {
                    GatewayForegroundService.addDebugLog("↻ 故障转移 #${idx} → ${matchedModel.modelId}")
                }

                if (!matchedModel.isEnabled) continue
                val provider2 = database.providerDao().getProviderById(matchedModel.providerId)
                if (provider2 == null || !provider2.isEnabled) continue

                // ★★ 故障转移：不预检测，直接转发（信任已有测速+健康缓存）★★
                try {
                    call.attributes.put(MODEL_ID_KEY, matchedModel.modelId)
                    call.attributes.put(PROVIDER_ID_KEY, matchedModel.providerId)
                    // ★★ 通知栏同步模型名（仅当变化时更新，避免通知栏闪烁）★★
                    if (GatewayForegroundService.activeNodeName != matchedModel.modelId) {
                        GatewayForegroundService.activeNodeName = matchedModel.modelId
                    }
                    GatewayScheduler.recordModelUsage(matchedModel.modelId)
                    val useProxy = matchedModel.useProxy

                    val sanitizedBody2 = sanitizeRequestBody(requestBodyStr)
                    val bodyWithPersona2 = if (BrainMemoryManager.getConfig().enabled) {
                        val personaText = BrainMemoryManager.buildPersonaPrompt()
                        if (personaText.isNotBlank()) {
                            val systemJson = "{\"role\":\"system\",\"content\":${proxyJson.encodeToString(JsonPrimitive(personaText))}}"
                            sanitizedBody2.replaceFirst(Regex("\"messages\"\\s*:\\s*\\["), "\"messages\":[$systemJson,")
                        } else sanitizedBody2
                    } else sanitizedBody2
                    val modifiedBody2 = if (modelId == "qtai-sj" || (autoFailover && matchedModel.modelId != modelId)) {
                        bodyWithPersona2.replaceFirst(Regex("\"model\"\\s*:\\s*\"[^\"]+\""), "\"model\":\"${matchedModel.modelId}\"")
                    } else bodyWithPersona2
                    val modifiedBytes2 = modifiedBody2.toByteArray()

                    if (stream) {
                        pipeStreamResponse(call, provider2, modifiedBytes2, "/v1/$effectivePath", matchedModel.modelId, matchedModel.providerId, database, useProxy)
                    } else {
                        pipeNormalResponse(call, provider2, modifiedBytes2, "/v1/$effectivePath", database, useProxy)
                    }
                    
                    // ★★ 记录会话成功模型
                    recordSessionModel(call, matchedModel.modelId)
                    GatewayScheduler.recordModelResult(matchedModel.modelId, true)

                    // ★★ 更新会话状态为 📥 回复 ★★
                    GatewayForegroundService.updateLiveSession(session.id, "📥 回复", "✅ 成功")
                    return
                } catch (e: Exception) {
                    failCount++
                    lastError = "${matchedModel.modelId}: ${e.message}"
                    synchronized(GatewayScheduler.healthCache) { GatewayScheduler.healthCache[matchedModel.modelId] = GatewayScheduler.ModelHealth(matchedModel.modelId, matchedModel.providerId, Long.MAX_VALUE, System.currentTimeMillis(), false) }
                    GatewayScheduler.recordModelResult(matchedModel.modelId, false)
                    if (GatewayForegroundService.getDebugMode()) GatewayForegroundService.addDebugLog("✗ ${matchedModel.modelId}: ${e.message?.take(60)}")
                }
            } // ★★ 结束故障转移循环 ★★
            } // ★★ 结束 attemptModels 非空判断 ★★
            val errMsg = when {
                modelId == "qtai-sj" && !GatewayForegroundService.getQtaiSjEnabled() -> "🔄 自动化切换已禁用，请在模型页面开启"
                modelId == "qtai-sj" && GatewayScheduler.pipelineSortedModelIds.isEmpty() && allEnabled.isEmpty() -> "暂无可用模型，请先添加服务商并同步模型"
                autoFailover -> "All ${failCount} models failed. Last: $lastError"
                else -> "Model '$modelId' error: $lastError"
            }
            val (status, body) = openAIError(HttpStatusCode.ServiceUnavailable, errMsg, "upstream_error")
            call.respondText(contentType = ContentType.Application.Json, status = status, text = body)
            return
        }
    }

    // 3. 非 chat 请求 → 通用转发（也要根据请求体model找对应服务商）
    val reqModelId = try {
        val j = proxyJson.parseToJsonElement(requestBodyStr).jsonObject
        j["model"]?.jsonPrimitive?.content
    } catch (_: Exception) { null }
    
    // ★★ 根据模型ID找对应服务商 ★★
    val nonChatProvider = if (!reqModelId.isNullOrBlank()) {
        val matchedModel = database.aiModelDao().getEnabledModelsList().find { it.modelId == reqModelId && it.isEnabled }
        if (matchedModel != null) {
            database.providerDao().getProviderById(matchedModel.providerId)
        } else null
    } else null
    
    val defaultProvider = nonChatProvider ?: database.providerDao().getAllProvidersList().firstOrNull { it.isEnabled }
    if (defaultProvider == null) {
        val (status, body) = openAIError(HttpStatusCode.BadRequest, "No enabled provider available for model '$reqModelId'.", "provider_error")
        call.respondText(contentType = ContentType.Application.Json, status = status, text = body)
        return
    }
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

        // ★★ 出站抓包
        if (GatewayForegroundService.getDebugMode()) {
            com.qtwl.gateway.capture.PacketCapture.captureOut(
                targetUrl = url,
                modelId = call.proxyModelId ?: "unknown",
                headers = "Authorization: ***",
                body = rawBody.decodeToString().take(1000),
                bodySize = rawBody.size
            )
        }

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
                GatewayForegroundService.totalDownloadBytes.addAndGet(respBytes.size.toLong())
                contentType = resp.header("Content-Type") ?: "application/json"
                statusCode = HttpStatusCode.fromValue(resp.code)
                respCode = resp.code
                
                // ★★ 关键修复：上游返回 4xx/5xx，抛出异常触发故障转移！
                if (!resp.isSuccessful) {
                    val errBody = respBytes.decodeToString().take(200)
                    throw Exception("Upstream ${resp.code}: $errBody")
                }
                // ★★ 新：上游返回200但内容为空，也触发故障转移
                if (respBytes.isEmpty()) {
                    throw Exception("Upstream ${resp.code}: empty response body")
                }
                // ★★ 新：chat/completions 返回内容空白（choices为空或无content），也触发故障转移
                if (path.contains("chat/completions") || path.contains("completions")) {
                    try {
                        val respStr = respBytes.decodeToString()
                        val respJson = proxyJson.parseToJsonElement(respStr).jsonObject
                        val choices = respJson["choices"]?.jsonArray
                        if (choices == null || choices.isEmpty()) {
                            throw Exception("Upstream ${resp.code}: empty choices in response")
                        }
                        val firstChoice = choices[0]?.jsonObject
                        val msg = firstChoice?.get("message")?.jsonObject
                        val content = msg?.get("content")?.jsonPrimitive?.content
                        if (content.isNullOrBlank()) {
                            throw Exception("Upstream ${resp.code}: blank content in response")
                        }
                    } catch (e: Exception) {
                        if (e.message?.startsWith("Upstream") == true) throw e
                        // JSON解析失败的不视为故障，继续
                    }
                }
            }
        }

        // 成功响应，写回客户端
        call.respondBytesWriter(contentType = ContentType.parse(contentType), status = statusCode) {
            writeFully(respBytes)
            flush()
        }

        // ★★ 记入最优模型（用真实请求耗时作为延迟参考）
        GatewayScheduler.markModelSuccess(call.proxyModelId ?: "unknown", System.currentTimeMillis() - pipeStartTime)

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
            // ★★ 响应抓包
            val tokens = try {
                val usage = proxyJson.parseToJsonElement(respBytes.decodeToString()).jsonObject["usage"]?.jsonObject
                Pair(usage?.get("prompt_tokens")?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                     usage?.get("completion_tokens")?.jsonPrimitive?.content?.toIntOrNull() ?: 0)
            } catch (_: Exception) { Pair(0, 0) }
            com.qtwl.gateway.capture.PacketCapture.captureResp(
                httpStatus = respCode,
                elapsedMs = System.currentTimeMillis() - pipeStartTime,
                headers = "Content-Type: application/json",
                body = respBytes.decodeToString().take(1000),
                bodySize = respBytes.size,
                modelId = call.proxyModelId ?: "",
                promptTokens = tokens.first,
                completionTokens = tokens.second,
                isStream = false
            )
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
    val pipeStartTime = System.currentTimeMillis()
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

    // Some OpenAI-compatible upstreams ignore stream=true and return a normal
    // JSON chat.completion.  Passing that body through with application/json
    // leaves SSE-only clients (including Hermes Agent) waiting with no text.
    if (path.contains("chat/completions") && !OpenAiStreamCompat.isEventStream(ct)) {
        val responseBytes = withContext(Dispatchers.IO) {
            response.use { it.body?.bytes() ?: byteArrayOf() }
        }
        if (responseBytes.isEmpty()) {
            throw Exception("Upstream stream ${response.code}: empty response body")
        }
        val sseBytes = try {
            OpenAiStreamCompat.chatCompletionJsonToSse(responseBytes.toString(Charsets.UTF_8))
        } catch (error: Exception) {
            throw Exception("Upstream stream ${response.code}: ${error.message}", error)
        }
        GatewayForegroundService.trafficDownloadBytes.addAndGet(sseBytes.size.toLong())
        GatewayForegroundService.totalDownloadBytes.addAndGet(sseBytes.size.toLong())
        call.respondBytesWriter(contentType = ContentType.Text.EventStream, status = respStatus) {
            writeFully(sseBytes)
            flush()
        }
        GatewayScheduler.markModelSuccess(modelId, System.currentTimeMillis() - pipeStartTime)
        return
    }

    val bodyStream = response.body?.byteStream() ?: run {
        response.close()
        throw Exception("Upstream stream ${response.code}: empty response body")
    }

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
                GatewayForegroundService.totalDownloadBytes.addAndGet(bytesRead.toLong())
                if (path.contains("chat/completions")) {
                    accumulatedBytes.write(buffer, 0, bytesRead)
                }
            }

            if (path.contains("chat/completions")) {
                val fullStream = accumulatedBytes.toString(Charsets.UTF_8.name())
                if (!OpenAiStreamCompat.hasDataFrame(fullStream)) {
                    val errorFrame = OpenAiStreamCompat.emptyStreamErrorFrame()
                    writeFully(errorFrame)
                    GatewayForegroundService.trafficDownloadBytes.addAndGet(errorFrame.size.toLong())
                    GatewayForegroundService.totalDownloadBytes.addAndGet(errorFrame.size.toLong())
                }
                if (!OpenAiStreamCompat.hasDoneFrame(fullStream)) {
                    val doneFrame = OpenAiStreamCompat.doneFrame()
                    writeFully(doneFrame)
                    GatewayForegroundService.trafficDownloadBytes.addAndGet(doneFrame.size.toLong())
                    GatewayForegroundService.totalDownloadBytes.addAndGet(doneFrame.size.toLong())
                }
                flush()
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
            if (GatewayForegroundService.getDebugMode()) {
                GatewayForegroundService.addDebugLog("✗ STREAM WRITE ERR: ${e.message?.take(80)}")
                com.qtwl.gateway.capture.PacketCapture.captureResp(
                    httpStatus = response.code,
                    elapsedMs = System.currentTimeMillis() - pipeStartTime,
                    headers = "Content-Type: ${ct}",
                    body = "Stream error: ${e.message?.take(200) ?: "unknown"}",
                    bodySize = 0,
                    modelId = modelId,
                    isStream = true
                )
            }
        } finally {
            // ★★ 流式响应抓包
            if (GatewayForegroundService.getDebugMode()) {
                val totalBytes = GatewayForegroundService.trafficDownloadBytes.get()
                com.qtwl.gateway.capture.PacketCapture.captureResp(
                    httpStatus = response.code,
                    elapsedMs = System.currentTimeMillis() - pipeStartTime,
                    headers = "Content-Type: $ct",
                    body = "[Stream: 流式响应内容，未记录]",
                    bodySize = 0,
                    modelId = modelId,
                    isStream = true
                )
            }
            withContext(Dispatchers.IO) { try { bodyStream.close(); response.close() } catch (_: Exception) { } }
        }
    }
}
