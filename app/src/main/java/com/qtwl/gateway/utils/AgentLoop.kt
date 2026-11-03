package com.qtwl.gateway.utils

/**
 * Agent 循环（v3.40 重写）
 *
 * ============================================================
 * 为什么之前「Agent 一直用不了」
 * ============================================================
 * 旧实现在拿到工具结果后，是这样喂回模型的：
 *
 *   messages.add("assistant" to 模型的原输出)
 *   messages.add("user" to "工具执行结果：…请基于这个结果继续回答用户的问题。")
 *
 * 这有两个致命问题：
 *
 *  1. **角色错误**。工具结果被包装成 `user` 消息，而 buildPrompt 会在末尾
 *     再补一个 `<|im_start|>assistant`。于是模型看到的“最后一条用户消息”
 *     变成了「工具执行结果…请基于这个结果继续回答」，原始问题被顶到更上面。
 *     小模型（0.5B~1.5B）注意力很短，看到这句就开始回答「请基于这个结果…」
 *     这句话本身，表现为：复读、自问自答、答非所问。
 *
 *  2. **不收敛**。模型每一轮都可能再吐一个工具调用，旧实现只是数到 maxRounds
 *     就退出，中途不做任何检测。模型重复调用同一个工具时（小模型极常见），
 *     会原地空转到上限，用户看到的就是「一直转圈 / 没有结果」。
 *
 * ============================================================
 * 现在的做法
 * ============================================================
 *  - 工具结果以 `tool` 角色回传（buildPrompt 里映射为工具结果块），
 *    原始 user 问题保持不动，模型始终知道自己在回答什么。
 *  - 每次工具调用做「指纹去重」：同名 + 同参数连续出现 2 次，
 *    立即判定为卡死，跳出循环并把已获得的工具结果整理给用户。
 *  - 工具返回的是错误时，直接把错误说明作为最终回答，
 *    不再让模型空转（避免它反复重试同一个失败调用）。
 *  - 无论哪条路径结束，都保证 finalText 非空。
 */
object AgentLoop {

    /** 单次执行结果 */
    data class Step(
        /** assistant 文本（可能是纯文本，也可能是工具调用） */
        val text: String,
        /** 识别到的工具调用，null 表示这一轮没有调工具 */
        val toolCall: AgentTools.ToolCall? = null,
        /** 工具执行结果 */
        val toolResult: String? = null
    )

    /** 最终结果 */
    data class Result(
        val finalText: String,
        val steps: List<Step>,
        val hitLimit: Boolean = false
    )

    /** 单个工具结果最多回灌多少字符，防止把小模型的上下文一次吃光 */
    private const val MAX_TOOL_RESULT_CHARS = 2000

    /**
     * 运行 Agent 循环。
     *
     * @param userMessage 用户这一句输入
     * @param history 之前的对话（role to content），不含本条 userMessage
     * @param systemPrompt 系统提示词（已包含工具说明）
     * @param generate 调用模型的函数，接收 messages 返回文本
     * @param maxRounds 最大工具调用轮数，防止死循环
     * @param onStep 每完成一步回调，用于界面实时显示
     */
    suspend fun run(
        userMessage: String,
        history: List<Pair<String, String>> = emptyList(),
        systemPrompt: String = "",
        maxRounds: Int = 3,
        /** 模型总 token 预算，用于裁剪输入、给输出留空间 */
        maxTokens: Int = 4096,
        generate: suspend (List<Pair<String, String>>) -> String,
        onStep: ((Step) -> Unit)? = null
    ): Result {
        val steps = mutableListOf<Step>()

        // 会话消息，随工具调用不断追加
        val messages = mutableListOf<Pair<String, String>>()
        if (systemPrompt.isNotBlank()) messages.add("system" to systemPrompt)
        messages.addAll(history)
        messages.add("user" to userMessage)

        // 工具调用指纹，用于检测「模型反复调同一个工具」
        var lastFingerprint = ""
        var sameCount = 0

        // 已经执行过的工具结果，用于兜底输出
        val collected = mutableListOf<String>()

        var round = 0
        while (round < maxRounds) {
            round++

            // 按 token 预算裁剪。历史增长后必须裁，否则输出空间被挤没。
            val trimmed = trimHistory(messages, maxTokens, reserveForOutput = 768)

            // 1. 让模型生成
            val output = try {
                generate(trimmed).trim()
            } catch (e: Throwable) {
                val msg = "生成失败：${e.message ?: e.javaClass.simpleName}"
                val step = Step(msg)
                steps.add(step)
                onStep?.invoke(step)
                return Result(msg, steps)
            }

            if (output.isBlank()) {
                val msg = collected.lastOrNull() ?: "（模型没有返回内容，可以换个说法再试）"
                val step = Step(msg)
                steps.add(step)
                onStep?.invoke(step)
                return Result(msg, steps)
            }

            // 2. 解析是否有工具调用
            val call = AgentTools.parse(output)

            if (call == null) {
                // 没有工具调用 → 这就是最终回答
                val step = Step(output)
                steps.add(step)
                onStep?.invoke(step)
                return Result(output, steps)
            }

            // 3. 防死循环：同名 + 同参数连续出现两次，直接判卡死
            val fp = call.name + "|" + call.args.toSortedMap().toString()
            if (fp == lastFingerprint) {
                sameCount++
                if (sameCount >= 2) {
                    val fallback = collected.lastOrNull()
                        ?: "模型反复调用同一个工具（${call.name}）却没有进展。可以换个更具体的说法，或选用更强的模型。"
                    val step = Step(fallback)
                    steps.add(step)
                    onStep?.invoke(step)
                    return Result(fallback, steps, hitLimit = true)
                }
            } else {
                lastFingerprint = fp
                sameCount = 0
            }

            // 4. 执行工具
            val result = AgentTools.execute(call).trim()
            val clipped = if (result.length > MAX_TOOL_RESULT_CHARS)
                result.take(MAX_TOOL_RESULT_CHARS) + "\n…（结果过长已截断）"
            else result

            collected.add(clipped)

            val step = Step(text = output, toolCall = call, toolResult = clipped)
            steps.add(step)
            onStep?.invoke(step)

            // 5. 工具失败 → 直接把错误告诉用户，别让模型空转重试
            if (result.startsWith("错误：")) {
                return Result(result, steps)
            }

            // 6. 把结果喂回模型。
            //    关键：用 tool 角色，而不是 user。
            //    这样原始 user 问题仍然是「最后一条用户提问」，
            //    模型不会把「请基于结果继续」当成新的问题去回答。
            messages.add("assistant" to output)
            messages.add("tool" to clipped)
        }

        // 超过轮数限制：把最后一轮拿到的工具结果还给用户，而不是只报“已达上限”
        val last = steps.lastOrNull()
        val fallback = collected.lastOrNull()
            ?: last?.text
            ?: "已达到工具调用上限（$maxRounds 轮）"
        return Result(
            finalText = fallback,
            steps = steps,
            hitLimit = true
        )
    }

    // ============ 内联工具函数（移植自 LocalLlmEngine，消除外部依赖）============

    /** 粗略估算 token 数：中文按 1 字 1 token，英文按 4 字符 1 token */
    private fun estimateTokens(text: String): Int {
        var cjk = 0
        var other = 0
        for (ch in text) {
            if (ch.code in 0x4E00..0x9FFF || ch.code in 0x3000..0x303F || ch.code in 0xFF00..0xFFEF) cjk++
            else other++
        }
        return cjk + other / 4 + 1
    }

    /** 按 token 预算裁剪历史，保留最近的消息 */
    private fun trimHistory(
        messages: List<Pair<String, String>>,
        maxTokens: Int,
        reserveForOutput: Int = 768
    ): List<Pair<String, String>> {
        val budget = (maxTokens - reserveForOutput).coerceAtLeast(256)
        val systems = messages.filter { it.first == "system" }
        val rest = messages.filter { it.first != "system" }
        var used = systems.sumOf { estimateTokens(it.second) }
        val kept = ArrayDeque<Pair<String, String>>()
        for (m in rest.asReversed()) {
            val t = estimateTokens(m.second)
            if (used + t > budget) break
            used += t
            kept.addFirst(m)
        }
        return systems + kept.toList()
    }
}
