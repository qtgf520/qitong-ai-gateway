package com.qtwl.gateway.agent

/**
 * Agent 运行配置
 *
 * 集中管理所有可调参数，避免构造函数膨胀。
 */
data class AgentConfig(
    /** 推理强度：low / medium / high，映射到 reasoning_effort 参数 */
    val reasoningEffort: ReasoningEffort = ReasoningEffort.Medium,

    /** 模型上下文窗口（tokens）。用于估算何时压缩 */
    val contextWindow: Int = 128_000,

    /** 触发压缩的阈值比例（0.0~1.0）。达到就压缩历史 */
    val compactThreshold: Float = 0.75f,

    /** 压缩后保留的最近消息条数 */
    val keepRecentMessages: Int = 6,

    /** 最大工具调用轮次 */
    val maxRounds: Int = 6,

    /** 是否启用流式输出最终回答 */
    val streaming: Boolean = true,

    /** 单次请求超时（秒） */
    val requestTimeoutSeconds: Long = 120,

    /** 是否允许自动续跑（token 用尽时继续） */
    val autoContinue: Boolean = true,

    /** 自动续跑最大次数 */
    val maxAutoContinue: Int = 3
) {
    companion object {
        val Default = AgentConfig()
    }
}

/**
 * 推理强度档位
 *
 * 不同厂商参数名不同：
 *  - OpenAI o系列/gpt-5: reasoning_effort = low/medium/high
 *  - DeepSeek R1: 无此参数，靠提示词
 *  - Claude: thinking.budget_tokens
 */
enum class ReasoningEffort(val apiValue: String, val label: String, val budgetTokens: Int) {
    Low("low", "快速", 1024),
    Medium("medium", "均衡", 4096),
    High("high", "深度", 16384);

    companion object {
        fun fromLabel(label: String): ReasoningEffort =
            entries.firstOrNull { it.label == label } ?: Medium
    }
}

/**
 * 上下文管理器
 *
 * 负责：
 *  1. 估算当前消息列表的 token 数
 *  2. 超过阈值时压缩历史（保留系统提示 + 最近 N 条 + 摘要）
 *  3. 生成压缩摘要提示词
 */
object ContextManager {

    /**
     * 粗估 token 数
     *
     * 经验值：
     *  - 英文约 4 字符 = 1 token
     *  - 中文约 1.5 字符 = 1 token
     * 这里用混合估算：cjk 字符按 0.7 token，其他按 0.28 token
     */
    fun estimateTokens(text: String): Int {
        if (text.isEmpty()) return 0
        var cjk = 0
        var other = 0
        text.forEach { c ->
            if (c.code in 0x4E00..0x9FFF || c.code in 0x3000..0x303F || c.code in 0xFF00..0xFFEF) {
                cjk++
            } else {
                other++
            }
        }
        return (cjk * 0.7 + other * 0.28).toInt().coerceAtLeast(1)
    }

    fun estimateTokens(messages: List<String>): Int = messages.sumOf { estimateTokens(it) }

    /** 是否需要压缩 */
    fun needsCompact(config: AgentConfig, currentTokens: Int): Boolean {
        val limit = (config.contextWindow * config.compactThreshold).toInt()
        return currentTokens >= limit
    }

    /** 生成压缩用的系统提示 */
    fun compactPrompt(): String = """
        |请把上面的对话历史压缩成一份简洁摘要，用于后续继续对话。
        |
        |要求：
        |1. 保留所有关键事实、数字、用户明确提出的要求
        |2. 保留已经完成的操作及其结果
        |3. 保留未完成的任务和待办事项
        |4. 去掉寒暄、重复内容和已经无关的中间推理过程
        |5. 用要点列表输出，不超过 400 字
        |6. 直接输出摘要内容，不要加任何前缀说明
    """.trimMargin()

    /** 生成续跑提示（token 用尽时） */
    fun continuePrompt(): String =
        "请从刚才中断的地方继续，不要重复已经说过的内容，直接接着往下写。"
}

/** 上下文使用情况，供 UI 展示 */
data class ContextUsage(
    val usedTokens: Int,
    val maxTokens: Int,
    val messageCount: Int
) {
    val percent: Float
        get() = if (maxTokens <= 0) 0f else (usedTokens.toFloat() / maxTokens).coerceIn(0f, 1f)
}