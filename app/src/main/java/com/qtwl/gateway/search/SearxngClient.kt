package com.qtwl.gateway.search

import android.util.Log
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * SearXNG 搜索客户端 — 自建搜索引擎集成
 * 零成本、无限制、JSON 直接喂 LLM
 */
class SearxngClient(
    private val baseUrl: String = "",
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
) {
    suspend fun search(
        query: String,
        engines: List<String>? = null,
        categories: String = "general",
        language: String = "zh-CN",
        maxResults: Int = 8
    ): List<SearchResult> = withContext(Dispatchers.IO) {
        try {
            val url = buildString {
                append(baseUrl.trimEnd('/'))
                append("/search?")
                append("q=${URLEncoder.encode(query, "UTF-8")}")
                append("&format=json")
                append("&categories=$categories")
                append("&language=$language")
                if (engines != null) {
                    append("&engines=${engines.joinToString(",")}")
                }
            }

            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "SearXNG 搜索失败: HTTP ${resp.code}")
                    return@withContext emptyList()
                }
                val json = JSONObject(resp.body!!.string())
                val results = json.optJSONArray("results") ?: return@withContext emptyList()
                val list = mutableListOf<SearchResult>()
                for (i in 0 until min(results.length(), maxResults)) {
                    val r = results.getJSONObject(i)
                    list += SearchResult(
                        title = r.optString("title", ""),
                        url = r.optString("url", ""),
                        snippet = r.optString("content", ""),
                        engine = r.optString("engine", ""),
                        score = r.optDouble("score", 0.0),
                        publishedDate = r.optString("publishedDate", null)
                    )
                }
                list.sortedByDescending { it.score }
            }
        } catch (e: Exception) {
            Log.e(TAG, "搜索异常: ${e.message}")
            emptyList()
        }
    }

    /**
     * 搜索 + 结果转 LLM 可读文本
     */
    suspend fun searchForLLM(query: String, maxResults: Int = 5): String {
        val results = search(query, maxResults = maxResults)
        if (results.isEmpty()) return "（未找到相关结果）"
        return results.mapIndexed { i, r ->
            "[${i + 1}] ${r.title}\n${r.url}\n${r.snippet}"
        }.joinToString("\n\n")
    }

    /**
     * 查询改写 — 把口语化问题转成适合搜索的关键词
     */
    fun rewriteQuery(userQuery: String): String {
        return userQuery
            .replace(Regex("""[吗？?！!。.，,\s]{2,}"""), " ")
            .trim()
            .take(100)
    }

    data class SearchResult(
        val title: String,
        val url: String,
        val snippet: String,
        val engine: String,
        val score: Double,
        val publishedDate: String?
    )

    companion object {
        private const val TAG = "SearxngClient"
    }
}