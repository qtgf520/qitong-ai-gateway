package com.qtwl.gateway.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Response

/**
 * OpenAI SSE 流式响应解析器
 *
 * 逐行读取 `data: {...}`，回调每个 delta 文本片段。
 * 同时累积完整内容与 finish_reason，供上层判断是否被截断。
 */
object SseParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    data class StreamOutcome(
        val content: String,
        val finishReason: String?,
        val promptTokens: Int,
        val completionTokens: Int
    )

    /**
     * 解析流式响应
     *
     * @param onDelta 每收到一个文本片段回调（用于逐字显示）
     * @return 累积后的完整结果
     */
    fun parse(
        response: Response,
        onDelta: ((String) -> Unit)? = null
    ): StreamOutcome {
        val sb = StringBuilder()
        var finishReason: String? = null
        var promptTokens = 0
        var completionTokens = 0

        response.body?.source()?.use { source ->
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                if (line.isEmpty()) continue
                if (!line.startsWith("data:")) continue

                val payload = line.removePrefix("data:").trim()
                if (payload == "[DONE]") break
                if (payload.isEmpty()) continue

                val obj: JsonObject = try {
                    json.parseToJsonElement(payload).jsonObject
                } catch (_: Exception) {
                    continue
                }

                // usage 可能出现在最后一条
                obj["usage"]?.let { u ->
                    try {
                        val uo = u.jsonObject
                        promptTokens = uo["prompt_tokens"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                            ?: promptTokens
                        completionTokens = uo["completion_tokens"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                            ?: completionTokens
                    } catch (_: Exception) { }
                }

                val choice = try {
                    obj["choices"]?.let { c ->
                        if (c is kotlinx.serialization.json.JsonArray) c.firstOrNull()?.jsonObject else null
                    }
                } catch (_: Exception) { null }
                    ?: continue

                choice["finish_reason"]?.jsonPrimitive?.contentOrNull?.let { fr ->
                    if (fr.isNotEmpty() && fr != "null") finishReason = fr
                }

                val delta = try {
                    choice["delta"]?.jsonObject
                } catch (_: Exception) { null }

                val text = delta?.get("content")?.jsonPrimitive?.contentOrNull
                if (!text.isNullOrEmpty()) {
                    sb.append(text)
                    onDelta?.invoke(text)
                }

                // 有些兼容实现把内容放在 message.content
                if (text.isNullOrEmpty()) {
                    val alt = try {
                        choice["message"]?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
                    } catch (_: Exception) { null }
                    if (!alt.isNullOrEmpty()) {
                        sb.append(alt)
                        onDelta?.invoke(alt)
                    }
                }
            }
        }

        return StreamOutcome(
            content = sb.toString(),
            finishReason = finishReason,
            promptTokens = promptTokens,
            completionTokens = completionTokens
        )
    }

    /** finish_reason 是否表示被长度截断 */
    fun isTruncated(finishReason: String?): Boolean =
        finishReason == "length" || finishReason == "max_tokens"
}