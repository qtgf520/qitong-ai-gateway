package com.qtwl.gateway.agent

import com.qtwl.gateway.network.UpstreamClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Agent 执行器 —— ReAct 循环 + 上下文管理 + 流式输出
 *
 * 流程：
 *   1. 组装系统提示（软件知识 + 工具说明）
 *   2. 检查上下文用量，超阈值则压缩
 *   3. 请求模型（带 reasoning_effort）
 *   4. 有 tool_calls → 执行工具、回灌结果、进入下一轮
 *   5. 无 tool_calls → 最终回答；若被长度截断且允许，自动续跑
 */
class AgentExecutor(
    private val baseUrl: String,
    private val apiKey: String,
    private val modelId: String,
    private val chatPath: String = "/v1/chat/completions",
    private val config: AgentConfig = AgentConfig.Default,
    private val tools: List<AgentTool> = defaultTools(),
    private val extraSystemPrompt: String = "",
    /** 是否走代理：必须与模型配置一致，否则国内中转站会连不上 */
    private val useProxy: Boolean = true,
    private val onToolCall: ((ToolCallRecord) -> Unit)? = null,
    private val onRoundStart: ((Int) -> Unit)? = null,
    private val onStatus: ((String) -> Unit)? = null,
    private val onContextUsage: ((ContextUsage) -> Unit)? = null,
    private val onStreamDelta: ((String) -> Unit)? = null
) {

    companion object {
        fun defaultTools(): List<AgentTool> = BuiltinTools.all() + NetworkTools.all()
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    private val messages = mutableListOf<JsonObject>()
    private val records = mutableListOf<ToolCallRecord>()

    /** 本次运行是否已经做过「流式工具调用」探测 */
    private var streamProbeDone = false

    /** 服务商确认不支持流式下发的工具调用 → 本次运行一律走非流式 */
    private var streamToolsUnsupported = false

    suspend fun run(
        userMessage: String,
        history: List<Pair<String, String>> = emptyList()
    ): AgentRunResult = withContext(Dispatchers.IO) {

        messages.clear()
        records.clear()
        streamProbeDone = false
        streamToolsUnsupported = false

        // 1) 系统提示
        messages += buildJsonObject {
            put("role", "system")
            put("content", buildString {
                appendLine(AppKnowledge.systemPrompt())
                if (extraSystemPrompt.isNotBlank()) {
                    appendLine()
                    appendLine("## 额外指令")
                    appendLine(extraSystemPrompt)
                }
                appendLine()
                append(AppKnowledge.toolPrompt(tools))
            })
        }

        // 2) 历史
        history.forEach { (role, content) ->
            messages += buildJsonObject {
                put("role", role)
                put("content", content)
            }
        }

        // 3) 用户消息
        messages += buildJsonObject {
            put("role", "user")
            put("content", userMessage)
        }

        val toolSchemas = buildJsonArray { tools.forEach { add(it.toOpenAiSchema()) } }

        var round = 0
        var compacted = false
        var continueCount = 0

        while (round < config.maxRounds) {
            round++
            onRoundStart?.invoke(round)
            reportUsage()

            if (!compacted && shouldCompact()) {
                onStatus?.invoke("上下文接近上限，正在压缩历史...")
                if (compactHistory()) {
                    compacted = true
                    onStatus?.invoke("历史已压缩")
                    reportUsage()
                }
            }

            onStatus?.invoke("第 $round 轮推理中...")
            val response = try {
                fetchCompletion(toolSchemas)
            } catch (e: Exception) {
                return@withContext fail(describeFailure(e), round)
            }

            val choice = response["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                ?: return@withContext fail("模型返回缺少 choices", round)
            val message = choice["message"]?.jsonObject
                ?: return@withContext fail("模型返回缺少 message", round)

            val content = message["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val toolCalls = message["tool_calls"] as? JsonArray
            val finishReason = choice["finish_reason"]?.jsonPrimitive?.contentOrNull

            if (toolCalls != null && toolCalls.isNotEmpty()) {
                messages += buildJsonObject {
                    put("role", "assistant")
                    put("content", content)
                    put("tool_calls", toolCalls)
                }
                executeToolCalls(toolCalls, round)
                continue
            }

            var finalText = content
            var lastFinish = finishReason

            while (
                config.autoContinue &&
                SseParser.isTruncated(lastFinish) &&
                continueCount < config.maxAutoContinue
            ) {
                continueCount++
                onStatus?.invoke("输出被截断，自动续写（第 $continueCount 次）")

                messages += buildJsonObject {
                    put("role", "assistant")
                    put("content", finalText)
                }
                messages += buildJsonObject {
                    put("role", "user")
                    put("content", ContextManager.continuePrompt())
                }

                val cont = try {
                    fetchCompletion(toolSchemas)
                } catch (_: Exception) {
                    break
                }
                val c = cont["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                val contText = c?.get("message")?.jsonObject?.get("content")
                    ?.jsonPrimitive?.contentOrNull.orEmpty()
                lastFinish = c?.get("finish_reason")?.jsonPrimitive?.contentOrNull
                if (contText.isBlank()) break
                finalText += contText
            }

            // 逐字回调：把完整文本按小片段喂给 UI 做打字机效果
            if (config.streaming && onStreamDelta != null && finalText.isNotBlank()) {
                emitAsTyping(finalText)
            }

            reportUsage()
            return@withContext AgentRunResult(
                success = true,
                answer = finalText.ifBlank { "（模型未返回内容）" },
                toolCalls = records.toList(),
                rounds = round,
                autoContinued = continueCount
            )
        }

        AgentRunResult(
            success = true,
            answer = "（已达到最大工具调用轮次 ${config.maxRounds}）",
            toolCalls = records.toList(),
            rounds = round,
            hitLimit = true
        )
    }

    /**
     * 把文本切成小片段回调，实现逐字显示
     *
     * 按 2~4 字符一块，兼顾流畅度与性能。
     */
    private fun emitAsTyping(text: String) {
        val cb = onStreamDelta ?: return
        var i = 0
        while (i < text.length) {
            val step = if (text.length - i > 4) 3 else (text.length - i)
            cb(text.substring(i, i + step))
            i += step
        }
    }

    private fun executeToolCalls(toolCalls: JsonArray, round: Int) {
        toolCalls.forEach { element ->
            val call = element.jsonObject
            val callId = call["id"]?.jsonPrimitive?.contentOrNull ?: "call_$round"
            val fn = call["function"]?.jsonObject
            val name = fn?.get("name")?.jsonPrimitive?.contentOrNull.orEmpty()
            val argsJson = fn?.get("arguments")?.jsonPrimitive?.contentOrNull.orEmpty()

            val tool = tools.firstOrNull { it.name == name }
            val args = parseArgs(argsJson)

            onStatus?.invoke("正在执行 $name ...")

            val result: ToolResult = if (tool == null) {
                ToolResult.fail("未知工具 $name")
            } else {
                try {
                    kotlinx.coroutines.runBlocking { tool.executor(args) }
                } catch (e: Exception) {
                    ToolResult.fail("执行异常：${e.message ?: e.javaClass.simpleName}")
                }
            }

            val record = ToolCallRecord(
                id = callId,
                toolName = name,
                arguments = argsJson,
                result = result.content,
                success = result.success,
                round = round
            )
            records += record
            onToolCall?.invoke(record)

            messages += buildJsonObject {
                put("role", "tool")
                put("tool_call_id", callId)
                put("name", name)
                put("content", result.content)
            }
        }
    }

    // ==================== 上下文管理 ====================

    private fun currentTokens(): Int =
        ContextManager.estimateTokens(messages.map { it.toString() })

    private fun shouldCompact(): Boolean =
        ContextManager.needsCompact(config, currentTokens())

    private fun reportUsage() {
        onContextUsage?.invoke(
            ContextUsage(
                usedTokens = currentTokens(),
                maxTokens = config.contextWindow,
                messageCount = messages.size
            )
        )
    }

    private fun compactHistory(): Boolean {
        if (messages.size <= config.keepRecentMessages + 2) return false

        val systemMsg = messages.firstOrNull() ?: return false
        val recent = messages.takeLast(config.keepRecentMessages)
        val middle = messages.drop(1).dropLast(config.keepRecentMessages)
        if (middle.isEmpty()) return false

        val compactMessages = buildList {
            add(systemMsg)
            addAll(middle)
            add(buildJsonObject {
                put("role", "user")
                put("content", ContextManager.compactPrompt())
            })
        }

        val summary = try {
            val resp = requestCompletionWith(
                compactMessages, buildJsonArray { }, stream = false
            )
            resp["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                ?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
                .orEmpty()
        } catch (_: Exception) {
            ""
        }

        if (summary.isBlank()) return false

        messages.clear()
        messages += systemMsg
        messages += buildJsonObject {
            put("role", "user")
            put("content", "【之前对话的摘要】\n$summary")
        }
        messages += buildJsonObject {
            put("role", "assistant")
            put("content", "好的，我已了解之前的对话背景，请继续。")
        }
        messages.addAll(recent)
        return true
    }

    // ==================== 请求 ====================

    private fun buildPayload(
        msgs: List<JsonObject>,
        toolSchemas: JsonArray,
        stream: Boolean
    ): JsonObject = buildJsonObject {
        put("model", modelId)
        put("messages", JsonArray(msgs))
        if (toolSchemas.isNotEmpty()) {
            put("tools", toolSchemas)
            put("tool_choice", "auto")
        }
        put("temperature", 0.7)
        put("stream", stream)

        // 推理强度
        if (config.reasoningEffort != ReasoningEffort.Medium) {
            put("reasoning_effort", config.reasoningEffort.apiValue)
        }
    }

    private fun requestCompletion(toolSchemas: JsonArray, stream: Boolean): JsonObject =
        requestCompletionWith(messages.toList(), toolSchemas, stream)

    /**
     * 取一次模型响应：优先用配置里的流式开关。
     *
     * 两个兜底，保证"流式"这个选项永远不会把对话搞坏：
     *   ① 请求直接抛异常（有些中转站不支持 text/event-stream）→ 退回非流式
     *   ② 流式返回了空内容且没有工具调用（端点假装成功）→ 退回非流式
     */
    private fun fetchCompletion(toolSchemas: JsonArray): JsonObject {
        if (!config.streaming || streamToolsUnsupported) {
            return requestCompletion(toolSchemas, stream = false)
        }
        val streamed = try {
            requestCompletion(toolSchemas, stream = true)
        } catch (e: Exception) {
            onStatus?.invoke("流式不可用，已切回非流式")
            streamToolsUnsupported = true
            return requestCompletion(toolSchemas, stream = false)
        }
        if (isEmptyResponse(streamed)) {
            onStatus?.invoke("流式返回为空，已切回非流式")
            streamToolsUnsupported = true
            return requestCompletion(toolSchemas, stream = false)
        }
        // ★ 一次性探测：首轮流式有文字、却没有工具调用时，用非流式再问一次。
        //   目的是区分两种情况：
        //     · 模型本来就选择直接回答（正常）
        //     · 服务商在流式下根本不发 tool_calls（会把 agent 退化成纯聊天）
        //   后者一旦确认，本次运行剩下的轮次全部改走非流式。
        if (!streamProbeDone && toolSchemas.isNotEmpty() && !hasToolCalls(streamed)) {
            streamProbeDone = true
            val plain = try {
                requestCompletion(toolSchemas, stream = false)
            } catch (_: Exception) {
                null
            }
            if (plain != null && hasToolCalls(plain)) {
                streamToolsUnsupported = true
                onStatus?.invoke("该服务商不支持流式工具调用，本次已改用非流式")
                return plain
            }
        }
        return streamed
    }

    private fun hasToolCalls(resp: JsonObject): Boolean {
        val msg = resp["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("message")?.jsonObject ?: return false
        val calls = msg["tool_calls"] as? JsonArray
        return calls != null && calls.isNotEmpty()
    }

    private fun isEmptyResponse(resp: JsonObject): Boolean {
        val msg = resp["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("message")?.jsonObject ?: return true
        val text = msg["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val calls = msg["tool_calls"] as? JsonArray
        return text.isBlank() && (calls == null || calls.isEmpty())
    }

    /**
     * 发一次补全请求。
     *
     * 里面的重试是必要的：中继/代理经常把长连接掐掉，
     * 表现就是 OkHttp 抛 "Software caused connection abort"（ECONNABORTED）。
     * 第一次失败后清空连接池、加 Connection: close 再试一次，能救回大部分情况。
     */
    private fun requestCompletionWith(
        msgs: List<JsonObject>,
        toolSchemas: JsonArray,
        stream: Boolean
    ): JsonObject {
        val payload = buildPayload(msgs, toolSchemas, stream)
        val url = baseUrl.trimEnd('/') + chatPath
        var lastError: Exception? = null
        for (attempt in 1..2) {
            try {
                return doRequest(url, payload, stream, closeConnection = attempt > 1)
            } catch (e: java.io.IOException) {
                lastError = e
                // 清掉可能已经死掉的 keep-alive 连接，避免第二次又复用同一根
                try {
                    UpstreamClient.getClientForModel(useProxy).connectionPool.evictAll()
                } catch (_: Throwable) {
                }
                if (attempt == 1) {
                    try {
                        Thread.sleep(250)
                    } catch (_: InterruptedException) {
                    }
                }
            }
        }
        throw lastError ?: java.io.IOException("网络错误")
    }

    private fun doRequest(
        url: String,
        payload: JsonObject,
        stream: Boolean,
        closeConnection: Boolean
    ): JsonObject {
        // 关键：按模型配置选择走代理还是直连
        val base = UpstreamClient.getClientForModel(useProxy)
        val client = base.newBuilder()
            .readTimeout(config.requestTimeoutSeconds, TimeUnit.SECONDS)
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        val builder = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .header("Accept", if (stream) "text/event-stream" else "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
        if (closeConnection) builder.header("Connection", "close")
        val request = builder.build()

        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                val err = resp.body?.string().orEmpty()
                throw IllegalStateException("HTTP ${resp.code}: ${err.take(400)}")
            }

            if (stream) {
                val outcome = SseParser.parse(resp) { delta -> onStreamDelta?.invoke(delta) }
                return buildJsonObject {
                    put("choices", buildJsonArray {
                        add(buildJsonObject {
                            put("message", buildJsonObject {
                                put("content", outcome.content)
                                // ★ 流式下工具调用是分片下发的，SseParser 已经拼好；
                                //   这里必须原样带出去，否则 agent 永远只会聊天、不会调工具
                                outcome.toolCalls?.let { put("tool_calls", it) }
                            })
                            put(
                                "finish_reason",
                                outcome.finishReason
                                    ?: if (outcome.toolCalls != null) "tool_calls" else "stop"
                            )
                        })
                    })
                }
            }

            val bodyText = resp.body?.string().orEmpty()
            if (bodyText.isBlank()) throw IllegalStateException("响应为空")
            return json.parseToJsonElement(bodyText).jsonObject
        }
    }

    /** 把异常翻译成能直接照做的提示 */
    private fun describeFailure(e: Exception): String {
        val msg = e.message ?: e.javaClass.simpleName
        val host = try {
            java.net.URI(baseUrl.trimEnd('/') + chatPath).host ?: "未知"
        } catch (_: Throwable) {
            "未知"
        }
        val isLocal = host == "127.0.0.1" || host == "localhost" || host == "::1" || host == "0.0.0.0"
        val hint = when {
            isLocal ->
                "连的是本机引擎，但它没在监听 —— 本地模型需要补齐 llama.cpp 原生库后才能启动"
            msg.contains("abort", ignoreCase = true) ->
                "上游或代理主动断开了连接（已重试 2 次）"
            msg.contains("timeout", ignoreCase = true) ->
                "等待响应超时（可在 Agent 设置里加大超时，或换更快的模型）"
            msg.contains("HTTP 4") ->
                "服务商拒绝了请求，多半是模型不支持 tools / function calling"
            msg.contains("HTTP 5") ->
                "服务商侧故障"
            else -> "网络异常"
        }
        return buildString {
            append("请求失败：")
            append(msg)
            append("\n[").append(hint).append("]")
            append("\n地址：").append(host)
            append("　代理：").append(if (useProxy) "已开启" else "直连")
            append("　模型：").append(modelId)
            append("\n排查顺序：① 关掉该模型的「走代理」再试；② 换非流式/换个模型；")
            append("③ 有些中转站遇到 tools 参数会直接掐连接，换官方端点试试。")
        }
    }

    private fun parseArgs(argsJson: String): Map<String, String> {
        if (argsJson.isBlank()) return emptyMap()
        return try {
            json.parseToJsonElement(argsJson).jsonObject.mapNotNull { (k, v) ->
                val text = when (v) {
                    is JsonPrimitive -> v.contentOrNull
                    else -> v.toString()
                }
                if (text == null) null else k to text
            }.toMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun fail(msg: String, round: Int) = AgentRunResult(
        success = false,
        answer = msg,
        toolCalls = records.toList(),
        rounds = round
    )
}

/** Agent 运行结果 */
data class AgentRunResult(
    val success: Boolean,
    val answer: String,
    val toolCalls: List<ToolCallRecord> = emptyList(),
    val rounds: Int = 0,
    val hitLimit: Boolean = false,
    val autoContinued: Int = 0
)