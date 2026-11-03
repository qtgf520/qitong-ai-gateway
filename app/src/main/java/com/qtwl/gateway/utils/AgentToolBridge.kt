package com.qtwl.gateway.utils

import com.qtwl.gateway.agent.AgentTool
import com.qtwl.gateway.agent.BuiltinTools
import com.qtwl.gateway.agent.NetworkTools
import kotlinx.coroutines.runBlocking

/**
 * 本地模型工具桥
 *
 * ============================================================
 * 为什么需要这一层
 * ============================================================
 * 工程里原本存在两套完全独立的 Agent 实现：
 *
 *   utils/AgentLoop   → 窄接口，只认文件的增删改查 + 一个 current_time
 *   agent/AgentExecutor → 走 OpenAI function calling，工具齐全
 *                        （BuiltinTools + NetworkTools + SkillTools）
 *
 * 结果就是：云端模型能 http_request、能跑技能脚本，
 * 本地模型只能列目录写文件。同一个「Agent」按钮，能力差一大截。
 *
 * 这个桥的作用：把 AgentTool（云端那套）适配成 AgentLoop 能用的形式，
 * 让本地模型也能拿到完整工具集。
 *
 * ============================================================
 * 适配要点
 * ============================================================
 *  1. AgentTool.executor 是 suspend 函数，而 AgentLoop 的 execute()
 *     是同步调用 → 用 runBlocking 桥接（调用方本来就在 IO 线程）
 *  2. AgentTool 用 JSON Schema 描述参数，AgentLoop 用
 *     Map<String,String> 传参 → 直接传 Map，名字完全一致
 *  3. 工具名统一用 AgentTool.name（snake_case），
 *     和云端保持一致，避免模型在两套提示词间混淆
 */
object AgentToolBridge {

    /** 桥接后的工具：名字 + 描述 + 参数 + 同步执行体 */
    data class Bridged(
        val name: String,
        val desc: String,
        val params: Map<String, String>,
        val run: (Map<String, String>) -> String
    )

    /**
     * 收集全部可用工具。
     *
     * @param useProxy 网络工具是否走代理（和模型配置保持一致）
     */
    fun all(useProxy: Boolean): List<Bridged> {
        val tools = mutableListOf<AgentTool>()
        tools += BuiltinTools.all()
        tools += NetworkTools.all(useProxy)
        // 技能工具依赖数据库，这里不主动加载，避免在非主线程查库
        // 如需启用，可在此追加 SkillTools.all()

        return tools.map { t ->
            Bridged(
                name = t.name,
                desc = t.cleanDescription(),
                params = t.parameters.associate { p ->
                    p.name to p.description
                },
                run = { args ->
                    try {
                        val r = runBlocking { t.executor(args) }
                        if (r.success) r.content else "错误：${r.content}"
                    } catch (e: Throwable) {
                        "错误：${e.message ?: e.javaClass.simpleName}"
                    }
                }
            )
        }
    }

    /**
     * AgentTool 的描述里可能带很长的说明和示例。
     * 小模型上下文很紧，截断到 2 行以内，只保留第一句。
     */
    private fun AgentTool.cleanDescription(): String {
        val firstSentence = description
            .split('。', '\n')
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            .orEmpty()
        return if (firstSentence.length > 80) {
            firstSentence.take(80) + "…"
        } else {
            firstSentence.ifEmpty { description.take(60) }
        }
    }
}