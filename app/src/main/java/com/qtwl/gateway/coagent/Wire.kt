package com.qtwl.gateway.coagent

import com.qtwl.gateway.network.UpstreamClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
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

/** 思考强度。OFF 时不发送任何推理参数 —— 这是官方模型 400 的重灾区。 */
enum class ThinkingLevel(val apiValue: String, val label: String) {
    OFF("", "关闭"),
    LOW("low", "快速"),
    MEDIUM("medium", "均衡"),
    HIGH("high", "深入");

    companion object {
        fun of(key: String?): ThinkingLevel =
            entries.firstOrNull { it.name.equals(key, ignoreCase = true) } ?: OFF
    }
}

/** 上游端点信息 */
data class Endpoint(
    val baseUrl: String,
    val path: String = "/v1/chat/completions",
    val apiKey: String = "",
    val useProxy: Boolean = false,
    val timeoutSeconds: Long = 120
) {
    val url: String
        get() {
            val base = baseUrl.trim()
            // 用户可能直接粘了完整端点
            if (base.contains("/chat/completions")) return base
            val trimmed = base.trimEnd('/')
            val p = if (path.startsWith("/")) path else "/" + path
            // baseUrl 已经带 /v1，而 path 又是 /v1/... → 去掉重复的一段
            return if (trimmed.endsWith("/v1") && p.startsWith("/v1/")) {
                trimmed + p.removePrefix("/v1")
            } else {
                trimmed + p
            }
        }

    val host: String
        get() = runCatching { java.net.URI(url).host ?: baseUrl }.getOrDefault(baseUrl)
}

/** 模型要求调用某个工具 */
data class ToolInvocation(
    val id: String,
    val name: String,
    val argsJson: String
) {
    fun args(): Map<String, String> {
        if (argsJson.isBlank()) return emptyMap()
        return try {
            val obj = Wire.json.parseToJsonElement(argsJson).jsonObject
            obj.mapNotNull { (k, v) ->
                val text = when (v) {
                    is kotlinx.serialization.json.JsonPrimitive -> v.contentOrNull
                    else -> v.toString()
                }
                if (text == null) null else k to text
            }.toMap()
        } catch (_: Throwable) {
            emptyMap()
        }
    }
}

/** 一次请求的结果 */
data class ChatTurn(
    val text: String = "",
    val reasoning: String = "",
    val toolCalls: List<ToolInvocation> = emptyList(),
    val finishReason: String? = null
)

/** 把上游错误翻译成人话（顶层函数：构造器里不能调用成员函数） */
private fun describeApiError(
    code: Int,
    providerMessage: String,
    endpoint: Endpoint,
    sentParameters: List<String>
): String = buildString {
    append("HTTP ")
    append(code)
    append(" · ")
    append(
        when (code) {
            400 -> "模型不接受某个参数（常见：temperature / reasoning_effort / tool_choice）"
            401 -> "API Key 无效或已过期"
            403 -> "没有该模型的权限，或地区受限"
            404 -> "端点路径不对，检查 BaseURL 是否需要 /v1"
            413 -> "请求体过大（上下文太长）"
            429 -> "触发限流或余额不足"
            in 500..599 -> "服务商侧故障"
            else -> "请求被拒绝"
        }
    )
    if (providerMessage.isNotBlank()) {
        append("\n原文：")
        append(providerMessage.take(500))
    }
    append("\n端点：")
    append(endpoint.host)
    append(endpoint.path)
    if (sentParameters.isNotEmpty()) {
        append("\n本次发送的参数：")
        append(sentParameters.joinToString(", "))
    }
}

class ApiException(
    val code: Int,
    val providerMessage: String,
    val endpoint: Endpoint,
    val sentParameters: List<String>
) : Exception(describeApiError(code, providerMessage, endpoint, sentParameters))

/**
 * 极简 OpenAI 兼容客户端。
 *
 * 设计原则（这是重写的核心，也是 400 的根治办法）：
 *   1. 默认只发 {model, messages, stream}，能不带的一律不带
 *   2. 工具、温度、tool_choice、思考参数都是「可选开关」，任何一个被拒都能单独摘掉
 *   3. 收到 400 就解析报错文本，摘掉对应参数重试（最多 4 次），而不是直接把 400 抛给用户
 */
class Wire(private val ep: Endpoint) {

    companion object {
        val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            explicitNulls = false
        }
        private val mediaJson = "application/json".toMediaType()
    }

    /** 参数可用性记忆：这一轮对话里被服务商拒绝过的参数，后续请求不再发 */
    private var toolsEnabled = true
    private var temperatureAllowed = true
    private var toolChoiceAllowed = true
    private var reasoningAllowed = true
    private var reasoningKey = "reasoning_effort"

    private var lastParameters: List<String> = emptyList()

    suspend fun chat(
        model: String,
        messages: List<JsonObject>,
        tools: JsonArray?,
        stream: Boolean,
        thinking: ThinkingLevel,
        onDelta: ((String) -> Unit)? = null,
        onReasoning: ((String) -> Unit)? = null,
        onRetry: ((String) -> Unit)? = null
    ): ChatTurn = withContext(Dispatchers.IO) {
        chatWithHealing(model, messages, tools, stream, thinking, onDelta, onReasoning, onRetry)
    }

    private fun chatWithHealing(
        model: String,
        messages: List<JsonObject>,
        tools: JsonArray?,
        stream: Boolean,
        thinking: ThinkingLevel,
        onDelta: ((String) -> Unit)?,
        onReasoning: ((String) -> Unit)?,
        onRetry: ((String) -> Unit)?
    ): ChatTurn {
        var attempt = 0
        var useStream = stream
        while (true) {
            val payload = buildPayload(model, messages, tools, useStream, thinking)
            val result = runCatching { execute(payload, useStream, onDelta, onReasoning) }

            val failure = result.exceptionOrNull()
            if (failure == null) return result.getOrThrow()

            // 只对 400 做参数自愈；其它错误直接抛
            if (failure !is ApiException || failure.code != 400 || attempt >= 4) throw failure

            val body = failure.providerMessage.lowercase()
            val healed = when {
                useStream && (body.contains("stream") || body.contains("sse")) -> {
                    useStream = false
                    "该端点不支持流式，已改用非流式"
                }
                temperatureAllowed && body.contains("temperature") -> {
                    temperatureAllowed = false
                    "该模型不支持 temperature，已自动去掉"
                }
                toolChoiceAllowed && body.contains("tool_choice") -> {
                    toolChoiceAllowed = false
                    "该模型不支持 tool_choice，已自动去掉"
                }
                // 整个工具能力被拒：降级成纯对话，宁可少个功能也不要整轮失败
                toolsEnabled && (body.contains("tool") || body.contains("function")) -> {
                    toolsEnabled = false
                    "该模型不支持工具调用，已切到纯对话模式"
                }
                reasoningAllowed && (body.contains("reasoning") || body.contains("thinking")) -> {
                    reasoningAllowed = false
                    "该模型不支持思考参数，已自动去掉"
                }
                else -> throw failure
            }
            attempt++
            onRetry?.invoke("$healed（第 $attempt 次重试）")
        }
    }

    private fun buildPayload(
        model: String,
        messages: List<JsonObject>,
        tools: JsonArray?,
        stream: Boolean,
        thinking: ThinkingLevel
    ): JsonObject = buildJsonObject {
        val sent = mutableListOf("model", "messages", "stream")
        put("model", model)
        put("messages", JsonArray(messages))
        put("stream", stream)

        if (toolsEnabled && tools != null && tools.isNotEmpty()) {
            put("tools", tools)
            sent += "tools"
            if (toolChoiceAllowed) {
                put("tool_choice", "auto")
                sent += "tool_choice"
            }
        }

        if (thinking != ThinkingLevel.OFF && reasoningAllowed) {
            // 部分厂商用 reasoning 对象、部分用扁平字段，这里统一走最常见的扁平写法；
            // 被拒后会自动摘掉（见 chat()）
            put(reasoningKey, thinking.apiValue)
            sent += reasoningKey
        }

        lastParameters = sent
    }

    private fun execute(
        payload: JsonObject,
        stream: Boolean,
        onDelta: ((String) -> Unit)?,
        onReasoning: ((String) -> Unit)?
    ): ChatTurn {
        val client = UpstreamClient.getClientForModel(ep.useProxy)
            .newBuilder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(ep.timeoutSeconds, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        val builder = Request.Builder()
            .url(ep.url)
            .post(payload.toString().toRequestBody(mediaJson))
            .header("Content-Type", "application/json")
            .header("Accept", if (stream) "text/event-stream" else "application/json")
        if (ep.apiKey.isNotBlank()) builder.header("Authorization", "Bearer " + ep.apiKey)

        client.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) {
                val text = resp.body?.string().orEmpty()
                throw ApiException(resp.code, extractMessage(text), ep, lastParameters)
            }
            return if (stream) readStream(resp, onDelta, onReasoning) else readPlain(resp)
        }
    }

    private fun readPlain(resp: okhttp3.Response): ChatTurn {
        val body = resp.body?.string().orEmpty()
        if (body.isBlank()) throw ApiException(200, "响应体为空", ep, lastParameters)
        val root = try {
            json.parseToJsonElement(body).jsonObject
        } catch (e: Throwable) {
            throw ApiException(200, "无法解析响应：" + body.take(300), ep, lastParameters)
        }
        root["error"]?.let { throw ApiException(resp.code, it.toString(), ep, lastParameters) }
        val choice = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: throw ApiException(200, "响应缺少 choices", ep, lastParameters)
        val msg = choice["message"]?.jsonObject
            ?: throw ApiException(200, "响应缺少 message", ep, lastParameters)
        val text = msg["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val reasoning = msg["reasoning_content"]?.jsonPrimitive?.contentOrNull
            ?: msg["reasoning"]?.jsonPrimitive?.contentOrNull
            ?: ""
        val calls = msg["tool_calls"]?.jsonArray ?: JsonArray(emptyList())
        return ChatTurn(
            text = text,
            reasoning = reasoning,
            toolCalls = calls.mapNotNull { el ->
                val o = runCatching { el.jsonObject }.getOrNull() ?: return@mapNotNull null
                val fn = o["function"]?.jsonObject ?: return@mapNotNull null
                ToolInvocation(
                    id = o["id"]?.jsonPrimitive?.contentOrNull ?: ("call_" + System.nanoTime()),
                    name = fn["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    argsJson = fn["arguments"]?.jsonPrimitive?.contentOrNull.orEmpty()
                )
            },
            finishReason = choice["finish_reason"]?.jsonPrimitive?.contentOrNull
        )
    }

    private class Pending(val id: String, val name: StringBuilder, val args: StringBuilder)

    private fun readStream(
        resp: okhttp3.Response,
        onDelta: ((String) -> Unit)?,
        onReasoning: ((String) -> Unit)?
    ): ChatTurn {
        val text = StringBuilder()
        val reasoning = StringBuilder()
        val calls = LinkedHashMap<Int, Pending>()
        var finish: String? = null

        resp.body?.source()?.use { src ->
            while (!src.exhausted()) {
                val line = src.readUtf8Line() ?: break
                if (line.isBlank() || !line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data == "[DONE]") break
                val obj = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: continue
                obj["error"]?.let { throw ApiException(resp.code, it.toString(), ep, lastParameters) }
                val choice = obj["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: continue
                choice["finish_reason"]?.jsonPrimitive?.contentOrNull?.let { if (it.isNotBlank() && it != "null") finish = it }

                val delta = choice["delta"]?.jsonObject
                delta?.get("content")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }?.let {
                    text.append(it)
                    onDelta?.invoke(it)
                }
                val think = delta?.get("reasoning_content")?.jsonPrimitive?.contentOrNull
                    ?: delta?.get("reasoning")?.jsonPrimitive?.contentOrNull
                if (!think.isNullOrEmpty()) {
                    reasoning.append(think)
                    onReasoning?.invoke(think)
                }

                delta?.get("tool_calls")?.jsonArray?.forEach { el ->
                    val o = runCatching { el.jsonObject }.getOrNull() ?: return@forEach
                    val idx = o["index"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: calls.size
                    val fn = o["function"]?.jsonObject
                    val slot = calls.getOrPut(idx) {
                        Pending(
                            id = o["id"]?.jsonPrimitive?.contentOrNull ?: ("call_" + idx),
                            name = StringBuilder(),
                            args = StringBuilder()
                        )
                    }
                    fn?.get("name")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let {
                        if (slot.name.isEmpty()) slot.name.append(it)
                    }
                    fn?.get("arguments")?.jsonPrimitive?.contentOrNull?.let { slot.args.append(it) }
                }
            }
        }

        return ChatTurn(
            text = text.toString(),
            reasoning = reasoning.toString(),
            toolCalls = calls.values.filter { it.name.isNotEmpty() }.map {
                ToolInvocation(it.id, it.name.toString(), it.args.toString().ifBlank { "{}" })
            },
            finishReason = finish
        )
    }

    /** 从错误体里挖出人能看懂的那句话 */
    private fun extractMessage(raw: String): String {
        if (raw.isBlank()) return ""
        val parsed = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
        if (parsed != null) {
            parsed["error"]?.let { err ->
                val eo = runCatching { err.jsonObject }.getOrNull()
                eo?.get("message")?.jsonPrimitive?.contentOrNull?.let { return it }
                return err.toString()
            }
            parsed["message"]?.jsonPrimitive?.contentOrNull?.let { return it }
        }
        return raw.take(500)
    }
}
