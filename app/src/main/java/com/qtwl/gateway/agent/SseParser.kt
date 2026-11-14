package com.qtwl.gateway.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
        val completionTokens: Int,
        /** 流式模式下累积出的工具调用（OpenAI 分片下发，需要按 index 拼接） */
        val toolCalls: JsonArray? = null
    )

    /** 流式工具调用的拼接槽位 */
    private class PendingCall {
        var id: String? = null
        val name = StringBuilder()
        val args = StringBuilder()
    }

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
        // 工具调用按 index 分片到达：id/name 只在第一片出现，arguments 要逐片累加
        val pendingCalls = sortedMapOf<Int, PendingCall>()
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

                // ★ 工具调用增量（agent 模式必须支持，否则流式下工具永远不会被触发）
                val tcDeltas = try {
                    delta?.get("tool_calls") as? JsonArray
                } catch (_: Exception) {
                    null
                }
                tcDeltas?.forEach { el ->
                    try {
                        val o = el.jsonObject
                        val idx = o["index"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                            ?: pendingCalls.size
                        val slot = pendingCalls.getOrPut(idx) { PendingCall() }
                        o["id"]?.jsonPrimitive?.contentOrNull
                            ?.takeIf { it.isNotBlank() }
                            ?.let { slot.id = it }
                        val fn = try {
                            o["function"]?.jsonObject
                        } catch (_: Exception) {
                            null
                        }
                        fn?.get("name")?.jsonPrimitive?.contentOrNull
                            ?.takeIf { it.isNotBlank() }
                            ?.let { if (slot.name.isEmpty()) slot.name.append(it) }
                        fn?.get("arguments")?.jsonPrimitive?.contentOrNull
                            ?.let { slot.args.append(it) }
                    } catch (_: Exception) {
                    }
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

        val toolCalls: JsonArray? = if (pendingCalls.isEmpty()) null else buildJsonArray {
            pendingCalls.forEach { (i, p) ->
                add(buildJsonObject {
                    put("id", p.id ?: "call_stream_$i")
                    put("type", "function")
                    put("function", buildJsonObject {
                        put("name", p.name.toString())
                        put("arguments", p.args.toString().ifBlank { "{}" })
                    })
                })
            }
        }

        return StreamOutcome(
            content = sb.toString(),
            finishReason = finishReason,
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            toolCalls = toolCalls
        )
    }

    /** finish_reason 是否表示被长度截断 */
    fun isTruncated(finishReason: String?): Boolean =
        finishReason == "length" || finishReason == "max_tokens"
}