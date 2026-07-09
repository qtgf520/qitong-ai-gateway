package com.qtwl.gateway.service

import android.content.Context
import android.content.SharedPreferences
import com.qtwl.gateway.GatewayApplication

/**
 * 思考引导配置管理器
 *
 * 三种模式：
 * - OFF: 不注入思考引导
 * - LIGHT: 只加 "请先思考再回答"
 * - DEEP: 结构化思考模板
 *
 * 存储位置：SharedPreferences (gateway_config)
 */
object ThinkingConfigManager {

    private const val PREFS = "gateway_config"
    private const val KEY_THINKING_DEPTH = "thinking_depth"
    private const val KEY_THINKING_ENABLED = "thinking_enabled"

    /** 思考深度级别 */
    enum class ThinkingDepth(val value: String, val label: String) {
        OFF("off", "关闭"),
        LIGHT("light", "轻度"),
        DEEP("deep", "深度");

        companion object {
            fun fromString(s: String): ThinkingDepth =
                entries.find { it.value == s } ?: OFF
        }
    }

    private fun prefs(): SharedPreferences =
        GatewayApplication.getInstance().getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 是否开启思考引导 */
    fun isEnabled(): Boolean = prefs().getBoolean(KEY_THINKING_ENABLED, false)

    /** 设置开关 */
    fun setEnabled(enabled: Boolean) {
        prefs().edit().putBoolean(KEY_THINKING_ENABLED, enabled).apply()
    }

    /** 获取当前思考深度 */
    fun getDepth(): ThinkingDepth =
        ThinkingDepth.fromString(prefs().getString(KEY_THINKING_DEPTH, "light") ?: "light")

    /** 设置思考深度 */
    fun setDepth(depth: ThinkingDepth) {
        prefs().edit().putString(KEY_THINKING_DEPTH, depth.value).apply()
    }

    /**
     * 根据配置生成思考引导 prompt
     * 返回要追加到 system prompt 末尾的字符串，空串表示不注入
     */
    fun buildThinkingPrompt(): String {
        if (!isEnabled()) return ""

        return when (getDepth()) {
            ThinkingDepth.OFF -> ""
            ThinkingDepth.LIGHT -> "\n\n【思考要求】请先思考再回答。"
            ThinkingDepth.DEEP -> """
            
【思考要求】
在回答之前，请先在你的思考过程中分析以下要点：
1) 用户的核心需求是什么
2) 有哪些可能的解法
3) 每种解法的优缺点
4) 最终推荐及理由

请将你的思考过程放在 `thinking` 标签中，然后在标签外给出最终回答。
""".trimIndent()
        }
    }
}