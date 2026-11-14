package com.qtwl.gateway.agent

import com.qtwl.gateway.utils.AgentSettings
import com.qtwl.gateway.utils.AgentTools

/**
 * 设备工具：把 AgentTools 的文件 / 命令工具包装成 OpenAI function-calling 工具。
 *
 * 为什么需要：Agent 模式走的是 AgentExecutor（function calling），
 * 它默认只有「时间/计算/文本/随机/单位/网络/技能」，
 * 根本没有读写文件和执行命令的能力 —— 用户让它「在工作区写一个 HTML」必然做不了。
 *
 * 这里直接复用 AgentTools.BUILTIN 的定义，执行也走 AgentTools.execute，
 * 所以 AgentGuard 的访问范围、审批、只读、工具分组全部照常生效。
 */
object DeviceTools {

    fun all(): List<AgentTool> {
        val workspace = try { AgentSettings.workspace } catch (_: Throwable) { "" }
        return AgentTools.BUILTIN.map { def ->
            val hasPath = def.params.containsKey("path")
            val desc = buildString {
                append(def.desc)
                if (hasPath && workspace.isNotBlank()) {
                    append("\n路径规则：path 必须是绝对路径；当前工作区根目录是 ")
                    append(workspace)
                }
            }
            AgentTool(
                name = def.name,
                description = desc,
                parameters = def.params.map { (key, help) ->
                    ToolParam(
                        name = key,
                        type = "string",
                        description = help,
                        required = key == "path" || key == "cmd" || key == "content"
                    )
                },
                requiresConfirm = def.name == "delete" || def.name == "run_shell",
                executor = { args ->
                    try {
                        val out = AgentTools.execute(AgentTools.ToolCall(def.name, args))
                        if (out.startsWith("错误") || out.contains("错误：")) ToolResult.fail(out)
                        else ToolResult.ok(out)
                    } catch (e: Throwable) {
                        ToolResult.fail("工具执行失败：" + (e.message ?: e.javaClass.simpleName))
                    }
                }
            )
        }
    }
}
