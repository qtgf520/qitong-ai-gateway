package com.qtwl.gateway.agent

import com.qtwl.gateway.service.GatewayForegroundService
import com.qtwl.gateway.utils.AgentSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID

/**
 * Agent 的权限沙盒（内嵌，但不是牢房）。
 *
 * 和 3.18 那套独立「沙盒模式」的区别：
 *  · 只有一种 Agent 形态，文件读写与命令执行都走同一套策略；
 *  · 访问范围可调：仅工作区 / 整机只读 / 整机读写；
 *  · 危险操作（越界写、管道、root、pm/settings 等）弹出审批卡片，用户点一下才放行；
 *  · 允许开 root 指导（su -c），开了才执行，没开就只把命令写在回复里。
 */
enum class AgentScope {
    /** 只能在工作区内读写（默认） */
    WORKSPACE,
    /** 整机可读，写只能落回工作区 */
    DEVICE_READ,
    /** 整机可读可写（危险，建议保持审批开启） */
    DEVICE_FULL;

    companion object {
        fun fromKey(k: String?): AgentScope = when (k) {
            "read" -> DEVICE_READ
            "full" -> DEVICE_FULL
            else -> WORKSPACE
        }
        fun keyOf(s: AgentScope): String = when (s) {
            DEVICE_READ -> "read"
            DEVICE_FULL -> "full"
            WORKSPACE -> "workspace"
        }
    }
}

/** 等待用户确认的一条操作 */
data class GuardRequest(
    val id: String,
    val title: String,
    val detail: String,
    val risk: Risk,
    val key: String
) {
    enum class Risk { Low, Medium, High }
}

object AgentGuard {

    private const val KEY_SCOPE = "agent_scope"
    private const val KEY_APPROVAL = "agent_require_approval"
    private const val KEY_ROOT = "agent_allow_root"
    private const val KEY_READONLY = "agent_read_only"

    /** 文件访问范围 */
    var scope: AgentScope
        get() = AgentScope.fromKey(GatewayForegroundService.getGatewayConfig(KEY_SCOPE, "workspace"))
        set(v) = GatewayForegroundService.saveGatewayConfig(KEY_SCOPE, AgentScope.keyOf(v))

    /** 危险操作是否需要用户点确认（默认开） */
    var requireApproval: Boolean
        get() = GatewayForegroundService.getGatewayConfig(KEY_APPROVAL, "1") == "1"
        set(v) = GatewayForegroundService.saveGatewayConfig(KEY_APPROVAL, if (v) "1" else "0")

    /** 是否允许模型执行 su（root 指导）。默认关 */
    var allowRoot: Boolean
        get() = GatewayForegroundService.getGatewayConfig(KEY_ROOT, "0") == "1"
        set(v) = GatewayForegroundService.saveGatewayConfig(KEY_ROOT, if (v) "1" else "0")

    /** 只读模式：禁止任何写入/删除 */
    var readOnly: Boolean
        get() = GatewayForegroundService.getGatewayConfig(KEY_READONLY, "0") == "1"
        set(v) = GatewayForegroundService.saveGatewayConfig(KEY_READONLY, if (v) "1" else "0")

    // ==================== 工具开关 ====================

    private const val KEY_TOOLS = "agent_tool_groups"

    /** 工具分组：file（文件读写）/ shell（命令执行）/ extra（计算、时间、网络、技能） */
    val TOOL_GROUPS: List<Pair<String, String>> = listOf(
        "file" to "文件读写",
        "shell" to "命令执行",
        "extra" to "内置与网络"
    )

    private val _enabled: MutableStateFlow<Set<String>> by lazy {
        MutableStateFlow(loadGroups())
    }
    val enabledGroups: StateFlow<Set<String>> get() = _enabled.asStateFlow()

    private fun loadGroups(): Set<String> {
        val raw = GatewayForegroundService.getGatewayConfig(KEY_TOOLS, "file,shell,extra")
        return raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    fun isGroupEnabled(id: String): Boolean = _enabled.value.contains(id)

    fun setGroupEnabled(id: String, on: Boolean) {
        val next = if (on) _enabled.value + id else _enabled.value - id
        _enabled.value = next
        GatewayForegroundService.saveGatewayConfig(KEY_TOOLS, next.joinToString(","))
    }

    /** 工具名 → 分组 */
    fun groupOfTool(name: String): String = when (name) {
        "list_dir", "read_file", "write_file", "append_file", "make_dir", "delete",
        "file_info", "search_files" -> "file"
        "run_shell", "run_script", "run_background" -> "shell"
        else -> "extra"
    }

    /** 工具被关掉时给模型的说明 */
    fun toolDisabledMessage(name: String): String {
        val group = groupOfTool(name)
        val label = TOOL_GROUPS.firstOrNull { it.first == group }?.second ?: group
        return "错误：工具「" + name + "」已被关闭（分组：" + label + "）。\n" +
            "请改用其它可用工具，或者告诉用户到「Agent 设置」里打开「" + label + "」。"
    }

    // ==================== 审批桥 ====================

    private val _pending = MutableStateFlow<GuardRequest?>(null)
    val pending: StateFlow<GuardRequest?> = _pending.asStateFlow()

    private var gate: CompletableDeferred<Boolean>? = null
    private var gateKey: String = ""
    private val rememberKeys = mutableSetOf<String>()

    /** UI 调它：允许 / 拒绝（remember = 本次会话内同类操作不再问） */
    fun respond(allow: Boolean, remember: Boolean = false) {
        if (allow && remember && gateKey.isNotEmpty()) rememberKeys.add(gateKey)
        gate?.complete(allow)
        gate = null
        _pending.value = null
    }

    fun clearRemembered() = rememberKeys.clear()

    private suspend fun ask(title: String, detail: String, risk: GuardRequest.Risk, key: String): Boolean {
        if (!requireApproval) return true
        if (key.isNotEmpty() && rememberKeys.contains(key)) return true
        val d = CompletableDeferred<Boolean>()
        gate = d
        gateKey = key
        _pending.value = GuardRequest(UUID.randomUUID().toString(), title, detail, risk, key)
        val ok = withTimeoutOrNull(180_000) { d.await() } ?: false
        _pending.value = null
        gate = null
        return ok
    }

    /** 给非 suspend 的工具层用：内部 runBlocking 等审批 */
    private fun askBlocking(title: String, detail: String, risk: GuardRequest.Risk, key: String): Boolean {
        if (!requireApproval) return true
        if (key.isNotEmpty() && rememberKeys.contains(key)) return true
        return runBlocking { ask(title, detail, risk, key) }
    }

    // ==================== 文件访问 ====================

    private val BLOCKED_ROOTS = listOf("/proc", "/sys", "/dev", "/vendor", "/acct")

    private fun workspaceRoot(): File = try {
        File(AgentSettings.workspace).absoluteFile
    } catch (_: Exception) {
        File(AgentSettings.DEFAULT_WORKSPACE)
    }

    /** 工作区内的相对描述，给模型看 */
    fun hint(): String = workspaceRoot().absolutePath

    /**
     * 文件访问检查。返回 null = 放行；返回字符串 = 拒绝原因（直接喂给模型）。
     */
    fun fileCheck(rawPath: String, forWrite: Boolean): String? {
        val path = try {
            File(rawPath).canonicalPath
        } catch (_: Exception) {
            rawPath
        }
        BLOCKED_ROOTS.forEach { bad ->
            if (path == bad || path.startsWith(bad + "/")) {
                return "错误：系统目录 $bad 不允许访问（内核/设备节点会卡死进程）。"
            }
        }
        val root = try {
            workspaceRoot().canonicalPath
        } catch (_: Exception) {
            workspaceRoot().absolutePath
        }
        val inside = path == root || path.startsWith(root + File.separator)

        if (forWrite && readOnly) {
            return "错误：当前是只读模式，禁止写入或删除。需要写入请让用户在 Agent 设置里关掉「只读模式」。"
        }
        if (inside) return null

        return when (scope) {
            AgentScope.WORKSPACE ->
                "错误：路径越界。「$path」不在工作区内。\n" +
                    "工作区是：$root\n" +
                    "要读整机文件，请让用户在 Agent 设置里把「访问范围」改成「整机只读」或「整机读写」。"
            AgentScope.DEVICE_READ ->
                if (forWrite)
                    "错误：当前访问范围是「整机只读」，不能写工作区外的文件。\n工作区是：$root"
                else null
            AgentScope.DEVICE_FULL -> null
        }
    }

    // ==================== 命令风险 ====================

    private val HIGH_RISK = listOf(
        "rm -rf", "rm -r", "mkfs", "dd if=", "format", "reboot", "shutdown",
        "pm uninstall", "pm clear", "settings put", "chmod 777 /", "> /dev/block"
    )
    private val MEDIUM_RISK = listOf("rm ", "mv ", "cp ", "chmod ", "chown ", "kill", "pkill", "am force-stop", "pm install")

    fun commandRisk(cmd: String): GuardRequest.Risk {
        val c = cmd.lowercase()
        if (HIGH_RISK.any { c.contains(it) }) return GuardRequest.Risk.High
        if (MEDIUM_RISK.any { c.contains(it) }) return GuardRequest.Risk.Medium
        if (c.contains("su ") || c.contains("sudo")) return GuardRequest.Risk.High
        return GuardRequest.Risk.Low
    }

    /**
     * 命令守卫：返回 null = 放行，返回字符串 = 拒绝/提示文本。
     * 会在需要时同步等用户点审批（工具层不是 suspend，所以用 runBlocking）。
     */
    /** 命令里出现的绝对路径是否全部落在工作区内（/dev/null 这类放行） */
    private fun insideWorkspaceOnly(cmd: String): Boolean {
        val root = com.qtwl.gateway.utils.AgentSettings.workspace
        val cleaned = cmd.replace(Regex("[a-zA-Z]+://[^\\s]+"), " ")   // 去掉 URL
        val hits = Regex("/[A-Za-z0-9._/-]+").findAll(cleaned).map { it.value }.toList()
        if (hits.isEmpty()) return true
        val safe = listOf("/dev/null", "/dev/zero", "/dev/stdout", "/dev/stderr", "/data/local/tmp")
        return hits.all { p -> p.startsWith(root) || safe.any { p == it || p.startsWith(it + "/") } }
    }

    fun gateCommand(cmd: String): String? {
        val c = cmd.trim()
        if (c.isEmpty()) return "错误：缺少 cmd 参数"

        val wantsRoot = c.startsWith("su ") || c.contains(" su -c ") || c.startsWith("sudo")
        if (wantsRoot && !allowRoot) {
            return "错误：当前没有开启 root。\n" +
                "你不能执行 su —— 请把需要的命令写进回复里，让用户自己开权限执行；\n" +
                "或者让用户在 Agent 设置里打开「Root 指导」。"
        }

        val risk = commandRisk(c)

        // 工作区内的常规写操作直接放行。否则每次 mkdir/echo 都要弹窗，
        // 用户点慢一点就超时被当成"拒绝"，看起来就像 agent 什么都不会。
        if (risk == GuardRequest.Risk.Medium && !wantsRoot &&
            com.qtwl.gateway.utils.AgentSettings.shellAutoTrust && insideWorkspaceOnly(c)
        ) return null

        val needAsk = risk != GuardRequest.Risk.Low || wantsRoot
        if (!needAsk) return null

        val key = when {
            wantsRoot -> "root"
            risk == GuardRequest.Risk.High -> "shell_high"
            else -> "shell_medium"
        }
        val title = when {
            wantsRoot -> "请求 root 执行命令"
            risk == GuardRequest.Risk.High -> "高危命令需要确认"
            else -> "写入类命令需要确认"
        }
        val ok = askBlocking(title, c, if (wantsRoot) GuardRequest.Risk.High else risk, key)
        return if (ok) null else "错误：用户拒绝了这条命令，没有执行。请换个更安全的做法，或先向用户解释原因。"
    }

    /**
     * 拼给模型的「权限事实」。
     * 不告诉它边界在哪，它就会一直撞墙（越界读写、乱 su）。
     */
    fun promptBlock(): String = buildString {
        appendLine("## 你的权限范围（每次都用这段事实判断能不能做）")
        appendLine("- 工作区：" + hint() + "（工作区内的读写直接放行）")
        when (scope) {
            AgentScope.WORKSPACE -> appendLine("- 访问范围：仅工作区。不要用工作区外的绝对路径。")
            AgentScope.DEVICE_READ -> appendLine("- 访问范围：整机可读。可以读 /storage/emulated/0 等真实路径，但写只能落在工作区。")
            AgentScope.DEVICE_FULL -> appendLine("- 访问范围：整机可读可写。写工作区外会弹确认。")
        }
        if (readOnly) appendLine("- 只读模式开启：禁止任何写入/删除。")
        appendLine(
            if (allowRoot)
                "- root：已开启。需要时可用 run_shell 执行 su -c 的探查命令（会弹确认），执行前先说明这条命令在干什么。"
            else
                "- root：未开启。不要尝试 su/sudo；确实需要时，把命令和理由写在回复里让用户自己执行。"
        )
        appendLine("- shell 有三种身份：app（自身沙盒，永远可用）、adb（UID 2000）、root（UID 0）。" +
            "后两种需要 root 授权，能不能用先用 shell_info 查清楚，不要瞎试。")
        appendLine("- shell 有三种身份：app（自身沙盒，永远可用）、adb（UID 2000）、root（UID 0）。" +
            "后两种需要 root 授权，能不能用先用 shell_info 查清楚，不要瞎试。")
        appendLine("- /proc /sys /dev /vendor 一律禁止访问。")
        appendLine("- 危险命令（rm、mv、pm、reboot、重定向等）会弹给用户确认，被拒绝就不要重试同一条。")
        val off = TOOL_GROUPS.filter { !isGroupEnabled(it.first) }.map { it.second }
        if (off.isNotEmpty()) {
            appendLine("- 以下工具分组已被用户关闭，不要调用：" + off.joinToString("、"))
        }
    }

    /** 越界写文件时问一次 */
    fun gateOutsideWrite(path: String): String? {
        val root = try {
            workspaceRoot().canonicalPath
        } catch (_: Exception) {
            workspaceRoot().absolutePath
        }
        val inside = path.startsWith(root + File.separator) || path == root
        if (inside) return null
        val risk = GuardRequest.Risk.Medium
        if (!requireApproval) return null
        val ok = askBlocking("写入工作区外的文件", path, risk, "write_outside")
        return if (ok) null else "错误：用户拒绝了写入「$path」。"
    }
}
