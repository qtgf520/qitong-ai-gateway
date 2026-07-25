package com.qtwl.gateway.utils

import android.util.Log

/**
 * 三层技能路由器
 * Layer 1: O(1) 关键词匹配（70%命中率，<1ms）
 * Layer 2: 语义匹配（25%命中率，~5ms）
 * Layer 3: LLM 判断（5%命中率，~500ms）
 */
object SkillRouter {

    /**
     * Layer 1: 快速关键词匹配
     * 直接检查用户消息是否包含技能触发词
     */
    fun matchByKeywords(query: String): String? {
        val lower = query.lowercase()
        for (skill in SkillRegistry.allSkills) {
            if (skill.example.split("/").any { trigger ->
                lower.contains(trigger.lowercase())
            }) {
                Log.d(TAG, "Layer 1 命中: ${skill.name} (${skill.code})")
                return skill.code
            }
        }
        return null
    }

    /**
     * Layer 2: 语义匹配（基于规则）
     * 处理变体表达和同义词
     */
    fun matchBySemantics(query: String): String? {
        val lower = query.lowercase()

        // 搜索相关变体
        if (lower.contains("搜") || lower.contains("查一下") || lower.contains("找一下") ||
            lower.contains("search") || lower.contains("look up") || lower.contains("find")) {
            return "000001" // 搜索技能
        }

        // 测速相关变体
        if (lower.contains("测速") || lower.contains("跑分") || lower.contains("benchmark") ||
            lower.contains("多快") || lower.contains("速度")) {
            return "100002" // 流水线测速
        }

        // 切换模型变体
        if (lower.contains("切换") || lower.contains("换成") || lower.contains("换到") ||
            lower.contains("switch") || lower.contains("change to")) {
            return "200002" // 切换指定模型
        }

        // 备份变体
        if (lower.contains("备份") || lower.contains("导出") || lower.contains("保存配置") ||
            lower.contains("backup") || lower.contains("export")) {
            return "500001" // 数据备份
        }

        return null
    }

    /**
     * Layer 3: LLM 判断（最后兜底）
     * 把技能描述列表喂给 LLM 让它选
     */
    suspend fun matchByLLM(query: String): String? {
        // 这里可以接入 LLM 进行语义理解
        // 暂时返回 null，后续接入大脑模型
        Log.d(TAG, "Layer 3 LLM 判断暂未接入，用户消息: ${query.take(50)}")
        return null
    }

    /**
     * 三层路由主入口
     * @return 匹配到的技能编码，无匹配返回 null
     */
    suspend fun route(query: String): String? {
        // Layer 1: 快速匹配
        matchByKeywords(query)?.let { return it }

        // Layer 2: 语义匹配
        matchBySemantics(query)?.let { return it }

        // Layer 3: LLM 判断
        matchByLLM(query)?.let { return it }

        return null
    }

    private const val TAG = "SkillRouter"
}