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
        if (content.isBlank()) {
            throw IllegalArgumentException("Upstream JSON response contains no assistant text")
        }

        val id = root["id"]?.jsonPrimitive?.contentOrNull
            ?: "chatcmpl-${UUID.randomUUID().toString().take(8)}"
        val created = root["created"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
            ?: System.currentTimeMillis() / 1000
        val model = root["model"]?.jsonPrimitive?.contentOrNull.orEmpty()

        val contentChunk = buildJsonObject {
            put("id", JsonPrimitive(id))
            put("object", JsonPrimitive("chat.completion.chunk"))
            put("created", JsonPrimitive(created))
            put("model", JsonPrimitive(model))
            put("choices", JsonArray(listOf(buildJsonObject {
                put("index", JsonPrimitive(0))
                put("delta", buildJsonObject {
                    put("role", JsonPrimitive("assistant"))
                    put("content", JsonPrimitive(content))
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
                put("finish_reason", JsonPrimitive(firstChoice["finish_reason"]?.jsonPrimitive?.contentOrNull ?: "stop"))
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
