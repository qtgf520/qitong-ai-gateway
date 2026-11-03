package com.qtwl.gateway.utils

import android.util.Log
import com.qtwl.gateway.service.GatewayForegroundService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 模型最大输出长度探测器
 *
 * 原理：向上游发送带 `max_tokens` 的请求，观察 `finish_reason`：
 *   - "length" → 输出被截断，说明 max_tokens 小于模型实际上限
 *   - "stop"   → 自然结束，说明 max_tokens 已达/超过模型上限
 *
 * 使用二分法在 [minTokens, maxTokens] 区间内快速定位模型真实的最大输出 token 数。
 *
 * 结果通过 SharedPreferences 按模型ID缓存，供"输出截断自动续写"参考。
 */
class ModelMaxOutputProber(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()
) {
    companion object {
        private const val TAG = "MaxOutputProber"
        private const val KEY_PREFIX = "model_max_output_"
        private const val MIN_PROBE = 256
        private const val MAX_PROBE = 131072
        private const val DEFAULT_FALLBACK = 4096

        /** 读取缓存的探测结果（token 数），无缓存返回 null */
        fun getCached(modelId: String): Int? {
            val v = GatewayForegroundService.getGatewayConfig(KEY_PREFIX + modelId, "")
            return v.toIntOrNull()
        }

        /** 读取探测结果，无缓存时返回保守默认值 */
        fun getCachedOrDefault(modelId: String): Int = getCached(modelId) ?: DEFAULT_FALLBACK

        /** 保存探测结果 */
        fun saveCached(modelId: String, tokens: Int) {
            GatewayForegroundService.saveGatewayConfig(KEY_PREFIX + modelId, tokens.toString())
        }
    }

    /**
     * 探测单个模型的最大输出 token 数（二分法）
     *
     * @param modelId 模型ID
     * @param baseUrl 服务商 BaseURL
     * @param apiKey API Key
     * @param chatPath 自定义 chatPath
     * @return 探测到的最大输出 tokens；失败返回 DEFAULT_FALLBACK
     */
    suspend fun probe(
        modelId: String,
        baseUrl: String,
        apiKey: String?,
        chatPath: String? = null
    ): Int = withContext(Dispatchers.IO) {
        try {
            val path = chatPath?.let { if (it.startsWith("/")) it else "/$it" } ?: "/v1/chat/completions"
            val url = baseUrl.trimEnd('/') + path

            var lo = MIN_PROBE
            var hi = MAX_PROBE
            var best = DEFAULT_FALLBACK
            var iterations = 0

            // 先用一个大值探测一次，确认模型支持的上限级别
            val initial = probeOnce(url, modelId, apiKey, MAX_PROBE)
            if (initial != null) {
                if (initial) {
                    // 连 128K 都不截断，直接按上限处理
                    saveCached(modelId, MAX_PROBE)
                    return@withContext MAX_PROBE
                }
            } else {
                // 探测请求本身失败，不写缓存
                return@withContext DEFAULT_FALLBACK
            }

            // 二分定位
            while (lo <= hi && iterations < 20) {
                iterations++
                val mid = (lo + hi) / 2
                val truncated = probeOnce(url, modelId, apiKey, mid)

                if (truncated == null) {
                    // 请求异常，保守返回当前已知的最佳值
                    break
                }
                if (truncated) {
                    // 被截断 → 上限比 mid 大
                    lo = mid + 1
                } else {
                    // 未截断 → mid 已达上限
                    best = mid
                    hi = mid - 1
                }
            }

            saveCached(modelId, best)
            GatewayForegroundService.addDebugLog(" 模型 $modelId 最大输出探测完成: ${best} tokens")
            best
        } catch (e: Exception) {
            Log.w(TAG, "probe error $modelId: ${e.message}")
            DEFAULT_FALLBACK
        }
    }

    /**
     * 单次探测：发送指定 max_tokens 的请求
     * @return true=被截断(finish_reason=length)，false=自然结束，null=请求失败
     */
    private fun probeOnce(url: String, modelId: String, apiKey: String?, maxTokens: Int): Boolean? {
        return try {
            // 用"请一直输出'好'字"这类提示词诱导模型尽量写满
            val prompt = "请重复输出文字“继续”二字，不要加任何其他内容，尽可能多地输出。"
            val body = JSONObject().apply {
                put("model", modelId)
                put("stream", false)
                put("max_tokens", maxTokens)
                put("temperature", 0.1)
                put("messages", org.json.JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", prompt)
                    })
                })
            }.toString()

            val request = Request.Builder()
                .url(url)
                .post(body.toRequestBody(JSON_TYPE))
                .apply { if (!apiKey.isNullOrBlank()) header("Authorization", "Bearer $apiKey") }
                .build()

            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "probeOnce HTTP ${resp.code}: ${resp.body?.string()?.take(150)}")
                    return null
                }
                val respStr = resp.body?.string() ?: return null
                val json = try { JSONObject(respStr) } catch (_: Exception) { return null }
                val choices = json.optJSONArray("choices") ?: return null
                val first = choices.optJSONObject(0) ?: return null
                val finish = first.optString("finish_reason", "")
                finish == "length"
            }
        } catch (e: Exception) {
            Log.w(TAG, "probeOnce error: ${e.message}")
            null
        }
    }

    /**
     * 批量探测所有已启用模型的最大输出长度
     */
    suspend fun probeAll(
        models: List<ProbeTarget>
    ): Map<String, Int> = withContext(Dispatchers.IO) {
        val result = mutableMapOf<String, Int>()
        for (m in models) {
            try {
                result[m.modelId] = probe(m.modelId, m.baseUrl, m.apiKey, m.chatPath)
            } catch (e: Exception) {
                Log.w(TAG, "probeAll ${m.modelId}: ${e.message}")
            }
        }
        result
    }

    data class ProbeTarget(
        val modelId: String,
        val baseUrl: String,
        val apiKey: String?,
        val chatPath: String?
    )

    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
}
