package com.qtwl.gateway.utils

import android.util.Log
import com.qtwl.gateway.data.model.SpeedMetrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 模型测速器 — 三指标精准采集
 * TTFT: Time To First Token（首字延迟）
 * TPS: Tokens Per Second（首字之后每秒token数，不算TTFT）
 * totalMs: 总耗时
 */
class ModelSpeedTester(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
) {
    suspend fun measure(
        modelId: String,
        baseUrl: String,
        apiKey: String?,
        prompt: String = DEFAULT_PROMPT
    ): SpeedMetrics = withContext(Dispatchers.IO) {
        val url = baseUrl.trimEnd('/') + "/v1/chat/completions"
        val body = buildPayload(modelId, prompt)
        val request = Request.Builder()
            .url(url)
            .post(body.toRequestBody(JSON_TYPE))
            .apply { if (!apiKey.isNullOrBlank()) header("Authorization", "Bearer $apiKey") }
            .build()

        val t0 = System.currentTimeMillis()
        var tFirst: Long? = null
        var tEnd: Long = 0L
        var tokenCount = 0
        var firstContent = ""

        try {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val errBody = resp.body?.string()?.take(200) ?: "unknown"
                    Log.w(TAG, "测速失败 HTTP ${resp.code}: $errBody")
                    return@withContext SpeedMetrics(
                        ttftMs = -1, tps = 0.0, totalMs = -1, tokenCount = 0, measuredAt = System.currentTimeMillis()
                    )
                }
                val source = resp.body!!.source()
                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: continue
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data == "[DONE]" || data == "{\"done\":true}") break

                    val delta = parseDelta(data) ?: continue
                    if (delta.isNotEmpty()) {
                        if (tFirst == null) {
                            tFirst = System.currentTimeMillis()
                            firstContent = delta
                        }
                        tokenCount += estimateTokens(delta)
                        tEnd = System.currentTimeMillis()
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "测速异常: ${e.message}")
            return@withContext SpeedMetrics(
                ttftMs = -1, tps = 0.0, totalMs = -1, tokenCount = 0, measuredAt = System.currentTimeMillis()
            )
        }

        if (tEnd == 0L) tEnd = System.currentTimeMillis()

        val ttft = (tFirst ?: t0) - t0
        val decodeMs = if (tFirst != null && tEnd > tFirst!!) {
            (tEnd - tFirst!!).toDouble()
        } else 0.0
        val tps = if (decodeMs > 0 && tokenCount > 0) {
            tokenCount / (decodeMs / 1000.0)
        } else 0.0

        val metrics = SpeedMetrics(
            ttftMs = ttft,
            tps = tps,
            totalMs = tEnd - t0,
            tokenCount = tokenCount,
            measuredAt = System.currentTimeMillis()
        )
        Log.d(TAG, "测速完成 $modelId → TTFT=${ttft}ms TPS=${"%.1f".format(tps)} 总=${tEnd - t0}ms tokens=$tokenCount")
        metrics
    }

    private fun parseDelta(jsonStr: String): String? = try {
        val obj = JSONObject(jsonStr)
        val choices = obj.optJSONArray("choices") ?: return null
        val choice = choices.optJSONObject(0) ?: return null
        // 支持 delta.content (OpenAI) 和 delta.reasoning_content (Claude thinking)
        val delta = choice.optJSONObject("delta")
        delta?.optString("content", null)
    } catch (_: Exception) { null }

    /**
     * 启发式 token 估算：
     * 中文按 0.65 token/字，英文及其他按 1 token/4 字符
     */
    private fun estimateTokens(text: String): Int {
        val chinese = text.count { it in '\u4e00'..'\u9fa5' }
        val other = text.length - chinese
        return (chinese * 0.65 + other / 4.0).toInt().coerceAtLeast(1)
    }

    private fun buildPayload(modelId: String, prompt: String): String = JSONObject().apply {
        put("model", modelId)
        put("stream", true)
        put("max_tokens", 200)
        put("temperature", 0.7)
        put("messages", org.json.JSONArray().apply {
            put(JSONObject().apply {
                put("role", "user")
                put("content", prompt)
            })
        })
    }.toString()

    companion object {
        private const val TAG = "ModelSpeedTester"
        const val DEFAULT_PROMPT = "请用一句话介绍你自己。"
        private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}