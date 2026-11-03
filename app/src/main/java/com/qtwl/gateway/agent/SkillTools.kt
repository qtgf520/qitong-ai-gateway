package com.qtwl.gateway.agent

import com.qtwl.gateway.utils.CustomSkillManager
import com.qtwl.gateway.utils.SkillRegistry

/**
 * 技能工具适配器
 *
 * 把现有的技能池（SkillRegistry 内置 + CustomSkillManager 自定义）
 * 转成 Agent 可调用的工具，让模型能主动触发软件功能。
 *
 * 技能分两类：
 *  1. 内置技能（SkillRegistry）：有固定编码，触发软件内部动作
 *  2. 自定义技能（CustomSkillManager）：用户写的提示词模板
 *
 * 由于内置技能的实际执行由 GatewayService 负责，这里只负责：
 *  - 让模型知道有哪些技能可用
 *  - 返回技能对应的执行指令，由上层投递给服务
 */
object SkillTools {

    fun all(): List<AgentTool> = listOf(
        listSkillsTool(),
        runSkillTool()
    )

    /**
     * 列出技能
     *
     * 让模型先查有哪些技能，再决定调用哪个
     */
    private fun listSkillsTool() = AgentTool(
        name = "list_skills",
        description = "列出当前软件可用的技能。技能是软件内置的快捷操作，比如测速、切换模型、备份数据。" +
            "当用户想执行软件内的操作但不清楚具体叫什么时，先调用这个查看。",
        parameters = listOf(
            ToolParam(
                name = "keyword",
                type = "string",
                description = "可选，按关键词过滤技能名或描述",
                required = false
            )
        )
    ) { args ->
        val kw = args["keyword"]?.trim()?.lowercase().orEmpty()

        val builtin = SkillRegistry.allSkills.filter { skill ->
            if (kw.isEmpty()) true
            else skill.name.lowercase().contains(kw) ||
                skill.description.lowercase().contains(kw)
        }

        val custom = try {
            CustomSkillManager.getAll().filter { s ->
                if (kw.isEmpty()) true
                else s.name.lowercase().contains(kw) ||
                    s.description.lowercase().contains(kw) ||
                    s.keywords.lowercase().contains(kw)
            }
        } catch (_: Exception) {
            emptyList()
        }

        if (builtin.isEmpty() && custom.isEmpty()) {
            return@AgentTool ToolResult.ok("没有找到匹配「$kw」的技能")
        }

        buildString {
            if (builtin.isNotEmpty()) {
                appendLine("## 内置技能（${builtin.size} 个）")
                builtin.take(60).forEach { s ->
                    appendLine("- [${s.code}] ${s.name}：${s.description}")
                }
                appendLine()
            }
            if (custom.isNotEmpty()) {
                appendLine("## 自定义技能（${custom.size} 个）")
                custom.forEach { s ->
                    appendLine("- ${s.name}：${s.description}（关键词：${s.keywords}）")
                }
            }
        }.let { ToolResult.ok(it) }
    }

    /**
     * 执行技能
     *
     * 返回执行指令，由上层（GatewayService）识别并真正执行
     */
    private fun runSkillTool() = AgentTool(
        name = "run_skill",
        description = "执行一个软件技能。需要先通过 list_skills 拿到技能编码。" +
            "技能编码是一串数字（如 100001 表示单模型测速）。" +
            "这个工具会返回执行指令，软件会自动响应该指令。",
        parameters = listOf(
            ToolParam(
                name = "skill_code",
                type = "string",
                description = "技能编码（内置技能的数字编码）或自定义技能名称"
            ),
            ToolParam(
                name = "argument",
                type = "string",
                description = "可选，技能参数。例如切换到第 2 个模型时填 2",
                required = false
            )
        )
    ) { args ->
        val code = args["skill_code"]?.trim().orEmpty()
        if (code.isEmpty()) {
            return@AgentTool ToolResult.fail("缺少 skill_code 参数")
        }
        val arg = args["argument"]?.trim().orEmpty()

        // 先查内置技能
        val builtin = SkillRegistry.allSkills.firstOrNull { it.code == code }
        if (builtin != null) {
            val instruction = if (arg.isEmpty()) {
                "【指令:$code】"
            } else {
                "【指令:$code:$arg】"
            }
            return@AgentTool ToolResult.ok(
                "已触发内置技能：${builtin.name}\n" +
                    "执行指令：$instruction\n" +
                    "说明：${builtin.description}"
            )
        }

        // 再查自定义技能
        val custom = try {
            CustomSkillManager.getAll().firstOrNull { it.name == code }
        } catch (_: Exception) { null }

        if (custom != null) {
            return@AgentTool ToolResult.ok(
                "已找到自定义技能：${custom.name}\n" +
                    "提示词内容：\n${custom.prompt}\n\n" +
                    "请按照上述提示词的要求来完成任务。"
            )
        }

        ToolResult.fail("找不到技能：$code。请先调用 list_skills 查看可用技能列表。")
    }

    /**
     * 生成技能摘要（注入系统提示，让模型知道有哪些能力）
     */
    fun summaryForPrompt(): String = buildString {
        appendLine("## 软件内置技能概览")
        appendLine("用户可以用自然语言触发以下技能，你也可以通过 run_skill 调用：")
        appendLine()
        // 按分类挑代表
        val categories = mapOf(
            "1" to "测速与模型测试",
            "2" to "模型切换与控制",
            "3" to "网关与故障转移",
            "4" to "代理控制",
            "5" to "数据与备份",
            "6" to "查询与状态",
            "7" to "APP 导航与界面操作",
            "8" to "服务商与模型管理",
            "9" to "高级功能（记忆/群聊/思考）",
            "0" to "联网搜索"
        )
        categories.forEach { (prefix, name) ->
            val items = SkillRegistry.allSkills.filter { it.code.startsWith(prefix) }
            if (items.isNotEmpty()) {
                appendLine("- **$name**（${items.size} 个）")
                items.take(4).forEach { s ->
                    appendLine("  · ${s.name}")
                }
                if (items.size > 4) appendLine("  · ...")
            }
        }
        appendLine()
        appendLine("需要完整列表时调用 list_skills。")
    }
}