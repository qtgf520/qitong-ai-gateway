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

    /**
     * 最大工具调用轮数（默认 200）。
     *
     * ★ 原来默认 5、上限 20，写一个完整 HTML 都要几十轮工具调用（读文件、写文件、
     *   改错、再写），20 轮根本做不完 —— 用户反馈「连个 HTML 都生成不了」。
     *   现在默认 200，滑条上限 500，正常任务不会撞墙。
     */
    var maxRounds: Int
        get() = GatewayForegroundService.getGatewayConfig(KEY_AGENT_ROUNDS, "200").toIntOrNull() ?: 200
        // 约定：<= 0 表示「无限」，使用处会转成 Int.MAX_VALUE
        set(v) = GatewayForegroundService.saveGatewayConfig(KEY_AGENT_ROUNDS, v.toString())

    /** 本地模型上下文长度（默认 4096，与下方 getter 保持一致） */
    var contextTokens: Int
        get() = GatewayForegroundService.getGatewayConfig(KEY_CONTEXT_TOKENS, "4096").toIntOrNull() ?: 4096
        set(v) = GatewayForegroundService.saveGatewayConfig(KEY_CONTEXT_TOKENS, v.toString())

    /** 用户自定义系统提示词（附加在工具说明之前） */
    var userSystemPrompt: String
        get() = GatewayForegroundService.getGatewayConfig(KEY_SYSTEM_PROMPT, "")
        set(v) = GatewayForegroundService.saveGatewayConfig(KEY_SYSTEM_PROMPT, v)

    private const val KEY_AGENT_STREAM = "agent_stream"

    /**
     * 是否逐字流式输出（默认开）。
     *
     * 关掉后模型算完整段再一次性显示 —— 小模型在低端机上更稳，
     * 部分不支持流式工具调用的服务商也建议关掉。
     */
    var streamEnabled: Boolean
        get() = GatewayForegroundService.getGatewayConfig(KEY_AGENT_STREAM, "1") == "1"
        set(v) = GatewayForegroundService.saveGatewayConfig(KEY_AGENT_STREAM, if (v) "1" else "0")

    private const val KEY_SHELL_TIER = "agent_shell_tier"

    /** 默认 shell 身份：app / adb / root */
    var shellTier: String
        get() = GatewayForegroundService.getGatewayConfig(KEY_SHELL_TIER, "app")
        set(v) = GatewayForegroundService.saveGatewayConfig(KEY_SHELL_TIER, v)

    private const val KEY_SHELL_AUTO = "agent_shell_auto"

    /** 工作区内的常规命令是否免确认（默认开，否则一个任务要点几十次弹窗） */
    var shellAutoTrust: Boolean
        get() = GatewayForegroundService.getGatewayConfig(KEY_SHELL_AUTO, "1") == "1"
        set(v) = GatewayForegroundService.saveGatewayConfig(KEY_SHELL_AUTO, if (v) "1" else "0")

    private const val KEY_AGENT_THINKING = "agent_thinking"

    /**
     * 思考档位：off / low / medium / high。
     *
     * 默认关闭 —— 官方模型（o 系列、gpt-5 等）对 reasoning 参数很敏感，
     * 非推理模型则会直接 400，所以必须由用户显式打开。
     */
    var thinking: String
        get() = GatewayForegroundService.getGatewayConfig(KEY_AGENT_THINKING, "off")
        set(v) = GatewayForegroundService.saveGatewayConfig(KEY_AGENT_THINKING, v)

    /** 工具调用轮数上限选项 */
    val ROUND_OPTIONS = listOf(1, 3, 5, 10)

    /** 可选的上下文长度档位 */
    val CONTEXT_OPTIONS = listOf(2048, 4096, 8192)

    // ==================== Shell 安全级别 ====================

    private const val KEY_SHELL_SAFETY = "shell_safety_full"
    private const val KEY_SHELL_TIMEOUT = "shell_timeout_sec"

    /**
     * 是否放开全部 shell 能力。
     *
     * false（默认）= 严格模式：命令名白名单 + 禁止管道/重定向/命令替换。
     *                 即使模型被诱导，最坏也只能读文件。
     * true          = 完全放开：任意命令、管道、重定向、多行脚本都能跑。
     *                 开启后模型拥有与 App 同等的执行权限
     *                 （无 root，破坏范围限于本应用可写位置）。
     *
     * 之所以默认关闭：一旦默认放开，「AI 能不能删我的文件」这个问题
     * 的答案就变成了「能」。宁可让用户主动开，也不要默认埋雷。
     */
    var shellFullAccess: Boolean
        get() = GatewayForegroundService.getGatewayConfig(KEY_SHELL_SAFETY, "0") == "1"
        set(v) = GatewayForegroundService.saveGatewayConfig(KEY_SHELL_SAFETY, if (v) "1" else "0")

    /** Shell 超时秒数（默认 60，编译/打包这类命令需要更长） */
    var shellTimeoutSec: Int
        get() = GatewayForegroundService.getGatewayConfig(KEY_SHELL_TIMEOUT, "60")
            .toIntOrNull()?.coerceIn(5, 600) ?: 60
        set(v) = GatewayForegroundService.saveGatewayConfig(
            KEY_SHELL_TIMEOUT, v.coerceIn(5, 600).toString()
        )

    /** 超时档位 */
    val TIMEOUT_OPTIONS = listOf(15, 30, 60, 120, 300)

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