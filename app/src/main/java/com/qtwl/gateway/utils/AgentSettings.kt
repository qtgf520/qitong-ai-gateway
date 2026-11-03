package com.qtwl.gateway.utils

import com.qtwl.gateway.service.GatewayForegroundService

/**
 * Agent 与本地模型的用户可调设置。
 *
 * 全部存在 GatewayForegroundService 的通用配置里，不需要新建数据库。
 */
object AgentSettings {

    private const val KEY_AGENT_ENABLED = "agent_enabled"
    private const val KEY_AGENT_ROUNDS = "agent_max_rounds"
    private const val KEY_CONTEXT_TOKENS = "local_context_tokens"
    private const val KEY_SYSTEM_PROMPT = "agent_system_prompt"

    /** Agent 是否启用（默认开） */
    var enabled: Boolean
        get() = GatewayForegroundService.getGatewayConfig(KEY_AGENT_ENABLED, "1") == "1"
        set(v) = GatewayForegroundService.saveGatewayConfig(KEY_AGENT_ENABLED, if (v) "1" else "0")

    /** 最大工具调用轮数（默认 5） */
    var maxRounds: Int
        get() = GatewayForegroundService.getGatewayConfig(KEY_AGENT_ROUNDS, "5").toIntOrNull() ?: 5
        set(v) = GatewayForegroundService.saveGatewayConfig(KEY_AGENT_ROUNDS, v.toString())

    /** 本地模型上下文长度（默认 2048） */
    var contextTokens: Int
        get() = GatewayForegroundService.getGatewayConfig(KEY_CONTEXT_TOKENS, "4096").toIntOrNull() ?: 4096
        set(v) = GatewayForegroundService.saveGatewayConfig(KEY_CONTEXT_TOKENS, v.toString())

    /** 用户自定义系统提示词（附加在工具说明之前） */
    var userSystemPrompt: String
        get() = GatewayForegroundService.getGatewayConfig(KEY_SYSTEM_PROMPT, "")
        set(v) = GatewayForegroundService.saveGatewayConfig(KEY_SYSTEM_PROMPT, v)

    /** 工具调用轮数上限选项 */
    val ROUND_OPTIONS = listOf(1, 3, 5, 10)

    /** 可选的上下文长度档位 */
    val CONTEXT_OPTIONS = listOf(2048, 4096, 8192)

    // ==================== 工作区（沙箱） ====================

    private const val KEY_WORKSPACE = "agent_workspace"
    private const val KEY_WORKSPACE_LOCK = "agent_workspace_lock"

    /** 默认工作区：内部存储根下的 KovaAgent 目录 */
    const val DEFAULT_WORKSPACE = "/storage/emulated/0/KovaAgent"

    /**
     * Agent 的工作区根目录。
     * 所有文件工具都只能在这个目录内部操作，越界会被拒绝。
     */
    var workspace: String
        get() = GatewayForegroundService.getGatewayConfig(KEY_WORKSPACE, DEFAULT_WORKSPACE)
            .ifBlank { DEFAULT_WORKSPACE }
        set(v) = GatewayForegroundService.saveGatewayConfig(
            KEY_WORKSPACE, v.trim().ifBlank { DEFAULT_WORKSPACE }
        )

    /**
     * 是否锁死在工作区内。
     * 1 = 只能在工作区里读写（默认，安全）
     * 0 = 允许访问任意路径（需要全部文件权限）
     */
    var workspaceLocked: Boolean
        get() = GatewayForegroundService.getGatewayConfig(KEY_WORKSPACE_LOCK, "1") == "1"
        set(v) = GatewayForegroundService.saveGatewayConfig(KEY_WORKSPACE_LOCK, if (v) "1" else "0")
}