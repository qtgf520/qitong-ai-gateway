package com.qtwl.gateway.utils

import android.util.Log
import com.qtwl.gateway.search.SearxngClient

/**
 * 联网搜索技能 — SearXNG 集成
 * 零成本、无限制、JSON 直接喂 LLM
 */
class SearchSkill(
    private val searxng: SearxngClient = SearxngClient()
) {
    /**
     * 判断是否需要联网搜索
     */
    fun canHandle(query: String): Boolean {
        val lower = query.lowercase()
        val realtimeKeywords = listOf(
            "最新", "今天", "现在", "当前", "2026", "新闻",
            "股价", "天气", "比分", "更新", "发布了",
            "最新AI新闻", "latest", "today", "news", "weather"
        )
        return realtimeKeywords.any { lower.contains(it) }
    }

    /**
     * 执行搜索
     */
    suspend fun execute(query: String): String {
        return try {
            val searchQuery = searxng.rewriteQuery(query)
            val results = searxng.searchForLLM(searchQuery)
            results
        } catch (e: Exception) {
            Log.e(TAG, "搜索失败: ${e.message}", e)
            "搜索失败: ${e.message}"
        }
    }

    companion object {
        private const val TAG = "SearchSkill"
    }
}