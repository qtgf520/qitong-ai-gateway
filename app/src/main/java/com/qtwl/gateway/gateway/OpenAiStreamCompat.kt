package com.qtwl.gateway.gateway

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

/** OpenAI-wire compatibility helpers for upstreams that do not honour stream=true. */
internal object OpenAiStreamCompat {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    fun isEventStream(contentType: String?): Boolean =
        contentType
            ?.substringBefore(';')
            ?.trim()
            ?.equals("text/event-stream", ignoreCase = true) == true

    fun hasDataFrame(payload: String): Boolean =
        payload.lineSequence().any { line ->
            if (!line.trimStart().startsWith("data:")) return@any false
            val data = line.substringAfter("data:").trim()
            data.isNotEmpty() && data != "[DONE]"
        }

    fun hasDoneFrame(payload: String): Boolean =
        payload.lineSequence().any { line -> line.trim() == "data: [DONE]" || line.trim() == "data:[DONE]" }

    fun doneFrame(): ByteArray = "data: [DONE]\n\n".toByteArray(Charsets.UTF_8)

    fun emptyStreamErrorFrame(): ByteArray =
        "data: {\"type\":\"error\",\"error\":{\"message\":\"Upstream returned an empty SSE stream\"}}\n\n"
            .toByteArray(Charsets.UTF_8)

    /** Parse SSE stream into list of JSON objects */
    fun parseSseFrames(payload: String): List<JsonObject> {
        val frames = mutableListOf<JsonObject>()
        payload.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("data:")) {
                val data = trimmed.substringAfter("data:").trim()
                if (data.isNotEmpty() && data != "[DONE]") {
                    try {
                        val element = json.parseToJsonElement(data)
                        if (element is JsonObject) frames.add(element)
                    } catch (_: Exception) { }
                }
            }
        }
        return frames
    }

    /** Convert a regular chat.completion JSON body into a complete SSE sequence. */
    fun chatCompletionJsonToSse(body: String): ByteArray {
        val root = json.parseToJsonElement(body).jsonObject
        val choices = root["choices"]?.jsonArray
            ?: throw IllegalArgumentException("Upstream JSON response is missing choices")
        val firstChoice = choices.firstOrNull()?.jsonObject
            ?: throw IllegalArgumentException("Upstream JSON response contains no choices")
        val message = firstChoice["message"]?.jsonObject
        val content = message?.get("content").toText()
            .ifBlank { firstChoice["text"].toText() }
        // ★ Bug 修复 v3.18.23-p1：tool_calls-only 响应（content 为空）是函数调用的合法形态，
        //   旧代码直接抛异常 → 触发故障转移 → 函数调用在"上游忽略stream"场景必失败。
        val toolCalls = message?.get("tool_calls") as? JsonArray
        val hasToolCalls = toolCalls != null && toolCalls.isNotEmpty()
        if (content.isBlank() && !hasToolCalls) {
            throw IllegalArgumentException("Upstream JSON response contains no assistant text")
        }

        val id = root["id"]?.jsonPrimitive?.contentOrNull
            ?: "chatcmpl-${UUID.randomUUID().toString().take(8)}"
        val created = root["created"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
            ?: System.currentTimeMillis() / 1000
        val model = root["model"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val finishReason = if (hasToolCalls) "tool_calls" else (firstChoice["finish_reason"]?.jsonPrimitive?.contentOrNull ?: "stop")

        val contentChunk = buildJsonObject {
            put("id", JsonPrimitive(id))
            put("object", JsonPrimitive("chat.completion.chunk"))
            put("created", JsonPrimitive(created))
            put("model", JsonPrimitive(model))
            put("choices", JsonArray(listOf(buildJsonObject {
                put("index", JsonPrimitive(0))
                put("delta", buildJsonObject {
                    put("role", JsonPrimitive("assistant"))
                    if (content.isNotBlank()) put("content", JsonPrimitive(content))
                    if (hasToolCalls) put("tool_calls", toolCalls)
                })
                put("finish_reason", JsonNull)
            })))
        }
        val stopChunk = buildJsonObject {
            put("id", JsonPrimitive(id))
            put("object", JsonPrimitive("chat.completion.chunk"))
            put("created", JsonPrimitive(created))
            put("model", JsonPrimitive(model))
            put("choices", JsonArray(listOf(buildJsonObject {
                put("index", JsonPrimitive(0))
                put("delta", buildJsonObject {})
                put("finish_reason", JsonPrimitive(finishReason))
            })))
        }

        return buildString {
            append("data: ").append(json.encodeToString(contentChunk)).append("\n\n")
            append("data: ").append(json.encodeToString(stopChunk)).append("\n\n")
            append("data: [DONE]\n\n")
        }.toByteArray(Charsets.UTF_8)
    }

    private fun JsonElement?.toText(): String {
        if (this == null || this is JsonNull) return ""
        if (this is JsonPrimitive) return contentOrNull.orEmpty()
        if (this is JsonArray) {
            return mapNotNull { part ->
                val obj = part as? JsonObject ?: return@mapNotNull null
                when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                    "text", "output_text" -> obj["text"]?.jsonPrimitive?.contentOrNull
                    else -> obj["text"]?.jsonPrimitive?.contentOrNull
                }
            }.filter { it.isNotBlank() }.joinToString("\n")
        }
        return ""
    }
}
