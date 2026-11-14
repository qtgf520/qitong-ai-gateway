package com.qtwl.gateway.coagent

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter

/**
 * 分层 shell：三种身份各一个常驻会话。
 *
 *   APP  —— App 自身沙盒身份（uid 10xxx / u0_aXXX），永远可用，但受沙盒限制
 *   ADB  —— uid 2000（shell 用户），能读更多路径、能用 am/pm/dumpsys/run-as。
 *           普通 App 无法自行变成 2000，必须借 root（su 2000 -c sh）或 Shizuku
 *   ROOT —— uid 0，需要 su 授权
 *
 * 每条命令都保留 cd / export 状态，支持管道、重定向、多行脚本。
 * 身份不可用时给出明确原因，绝不假装可用。
 */
object Shell {

    enum class User(val id: String, val label: String) {
        APP("app", "普通用户"),
        ADB("adb", "ADB（UID 2000）"),
        ROOT("root", "Root（UID 0）");

        companion object {
            fun of(s: String?): User = when (s?.trim()?.lowercase()) {
                "adb", "2000", "shell" -> ADB
                "root", "0", "su" -> ROOT
                else -> APP
            }
        }
    }

    private const val MARK = "__QITONG_RC_"

    private class Session(val user: User) {
        var proc: Process? = null
        var stdin: BufferedWriter? = null
        var stdout: BufferedReader? = null
        var nonce: String = ""
        var startedAt = 0L
        var uid: Int = -1
        var failReason: String? = null
        var channel: String = ""
        @Volatile var cwd: String = "/"
        fun alive(): Boolean = proc?.isAlive == true
    }

    private val sessions = java.util.Collections.synchronizedMap(HashMap<User, Session>())

    /** 默认身份，由设置决定 */
    var defaultUser: User = User.APP

    private fun session(user: User): Session =
        synchronized(sessions) { sessions.getOrPut(user) { Session(user) } }

    // ---------------- 启动 ----------------

    private fun suBinary(): String? = listOf(
        "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su", "/debug_ramdisk/su"
    ).firstOrNull { File(it).exists() }

    private fun start(s: Session, workdir: String): Boolean {
        stopSession(s)
        val dir = File(workdir).takeIf { it.isDirectory } ?: File("/")
        return try {
            val p: Process = when (s.user) {
                User.APP -> {
                    s.channel = "app"
                    ProcessBuilder(listOf("/system/bin/sh"))
                        .directory(dir).redirectErrorStream(true).start()
                }
                User.ROOT -> {
                    s.channel = "su"
                    ProcessBuilder(listOf(suBinary() ?: "su"))
                        .directory(dir).redirectErrorStream(true).start()
                }
                User.ADB -> {
                    // ① Shizuku（不需要 root，用户手动启动过一次即可）
                    val viaShizuku = ShizukuBridge.newShell(workdir)
                    if (viaShizuku != null) {
                        s.channel = "Shizuku"
                        viaShizuku
                    } else {
                        // ② 有 root 的话借 su 切到 2000
                        s.channel = "su 2000"
                        ProcessBuilder(listOf(suBinary() ?: "su", "2000", "-c", "sh"))
                            .directory(dir).redirectErrorStream(true).start()
                    }
                }
            }
            s.proc = p
            s.stdin = BufferedWriter(OutputStreamWriter(p.outputStream, Charsets.UTF_8))
            s.stdout = BufferedReader(InputStreamReader(p.inputStream, Charsets.UTF_8))
            s.nonce = java.lang.Long.toHexString(System.nanoTime())
            s.startedAt = System.currentTimeMillis()
            s.cwd = workdir
            true
        } catch (e: Throwable) {
            s.failReason = (e.message ?: e.javaClass.simpleName)
            false
        }
    }

    private fun reasonFor(user: User): String = when (user) {
        User.APP -> "普通用户 shell 启动失败，这台设备可能缺少 /system/bin/sh"
        User.ADB -> "ADB 身份（UID 2000）不可用：没有 su 或未授权。\n" +
            "普通 App 无法自行切换成 2000，需要 root（su 2000 -c sh）或 Shizuku 一类的助手服务。"
        User.ROOT -> "Root 身份不可用：设备没有 su，或授权被拒绝。"
    }

    // ---------------- 执行 ----------------

    fun exec(
        command: String,
        timeoutMs: Long = 120_000L,
        workdir: String? = null,
        user: User = defaultUser
    ): Pair<Int, String> {
        val s = session(user)
        synchronized(s) {
            ensure(s, workdir)?.let { return -1 to it }
            return execLocked(s, command, timeoutMs, applyWorkdir = workdir)
        }
    }

    private fun ensure(s: Session, workdir: String?): String? {
        if (s.alive()) {
            workdir?.let { w ->
                if (File(w).isDirectory) {
                    execLocked(s, "cd " + quote(w), 5_000L)
                    s.cwd = w
                }
            }
            return null
        }
        val dir = workdir ?: s.cwd
        if (!start(s, dir)) {
            return s.failReason ?: reasonFor(s.user)
        }
        // 确认身份是不是真的切过去了（su 可能被拒绝、Shizuku 可能没启动）
        val (code, out) = execLocked(s, "id -u", 10_000L)
        val uid = out.trim().lines().lastOrNull { it.trim().toIntOrNull() != null }
            ?.trim()?.toIntOrNull() ?: -1
        s.uid = uid
        val expected = when (s.user) {
            User.APP -> null
            User.ADB -> 2000
            User.ROOT -> 0
        }
        if (code != 0 || uid < 0) {
            stopSession(s)
            s.failReason = reasonFor(s.user)
            return s.failReason
        }
        if (expected != null && uid != expected) {
            stopSession(s)
            s.failReason = "请求的身份是 " + s.user.label + "，实际拿到的 uid 是 " + uid + "。\n" +
                "说明 su 没有赋予对应权限，请检查授权。"
            return s.failReason
        }
        execLocked(s, "exec 2>&1", 3_000L)
        return null
    }

    private fun execLocked(
        s: Session,
        command: String,
        timeoutMs: Long,
        applyWorkdir: String? = null
    ): Pair<Int, String> {
        applyWorkdir?.let { w ->
            if (File(w).isDirectory && w != s.cwd) {
                write(s, "cd " + quote(w))
                s.cwd = w
            }
        }
        val tag = MARK + s.nonce + "_"
        val sb = StringBuilder()
        write(s, command)
        write(s, "__qitong_rc=\$?; echo \"" + tag + "\${__qitong_rc}__\"")

        val deadline = System.currentTimeMillis() + timeoutMs
        var code = -1
        var finished = false
        while (System.currentTimeMillis() < deadline) {
            if (!s.alive()) break
            val ready = try { s.stdout?.ready() == true } catch (_: Throwable) { false }
            if (!ready) {
                Thread.sleep(20)
                continue
            }
            val line = try { s.stdout?.readLine() } catch (_: Throwable) { null } ?: break
            val idx = line.indexOf(tag)
            if (idx >= 0) {
                val tail = line.substring(idx + tag.length).removeSuffix("__").trim()
                code = tail.toIntOrNull() ?: -1
                val head = line.substring(0, idx)
                if (head.isNotEmpty()) sb.append(head).append('\n')
                finished = true
                break
            }
            if (sb.length < 400_000) sb.append(line).append('\n')
        }

        if (!finished) {
            val partial = sb.toString()
            stopSession(s)
            return -1 to ("命令超时（" + (timeoutMs / 1000) + " 秒）或会话已断开，已清理\n" +
                partial.takeLast(4000))
        }
        return code to sb.toString()
    }

    private fun write(s: Session, line: String) {
        val w = s.stdin ?: return
        runCatching {
            w.write(line)
            w.write("\n")
            w.flush()
        }
    }

    /** 后台启动，返回 PID 与日志路径 */
    fun execBackground(command: String, logPath: String, user: User = defaultUser): Pair<Int, String> {
        val log = File(logPath)
        log.parentFile?.mkdirs()
        val wrapped = "nohup sh -c " + quote(command) + " > " + quote(log.absolutePath) + " 2>&1 &\necho \$!"
        val (_, out) = exec(wrapped, 20_000L, null, user)
        val pid = out.trim().lines().lastOrNull { it.trim().toIntOrNull() != null }?.trim() ?: "-"
        return (pid.toIntOrNull() ?: -1) to log.absolutePath
    }

    private fun stopSession(s: Session) {
        runCatching { s.stdin?.close() }
        runCatching { s.stdout?.close() }
        runCatching { s.proc?.destroyForcibly() }
        s.proc = null
        s.stdin = null
        s.stdout = null
        s.uid = -1
    }

    fun stopAll() {
        synchronized(sessions) { sessions.values.forEach { stopSession(it) } }
    }

    // ---------------- 自检 ----------------

    fun tierStatus(user: User): String {
        val s = session(user)
        synchronized(s) {
            val err = ensure(s, null)
            if (err != null) return user.label + "：不可用 —— " + err.lines().first()
            val via = if (s.channel.isNotBlank()) "，通道 " + s.channel else ""
            return user.label + "：可用（uid " + s.uid + via + "，目录 " + s.cwd + "）"
        }
    }

    fun probe(): String {
        val sb = StringBuilder()
        sb.append("=== shell 身份 ===\n")
        User.values().forEach { sb.append("· ").append(tierStatus(it)).append('\n') }

        val appS = session(User.APP)
        sb.append("\n工作目录：").append(appS.cwd).append('\n')
        val su = suBinary()
        sb.append("su：").append(su ?: "未找到").append('\n')
        sb.append("Shizuku：")
        sb.append(
            if (ShizukuBridge.alive()) {
                if (ShizukuBridge.granted()) "已授权（UID " + ShizukuBridge.remoteUid() + "）"
                else "运行中，未授权"
            } else {
                "未运行"
            }
        )
        sb.append('\n')

        val tools = listOf(
            "sh", "ls", "cat", "grep", "sed", "awk", "find", "xargs", "cut", "sort",
            "head", "tail", "wc", "tr", "tar", "gzip", "curl", "wget", "unzip", "zip",
            "python3", "node", "git", "am", "pm", "dumpsys", "getprop", "logcat", "run-as"
        )
        val found = mutableListOf<String>()
        val missing = mutableListOf<String>()
        tools.forEach { t ->
            val ok = runCatching {
                val p = ProcessBuilder("/system/bin/sh", "-c", "command -v " + t).start()
                p.waitFor()
                p.exitValue() == 0
            }.getOrDefault(false)
            if (ok) found += t else missing += t
        }
        sb.append("可用命令：").append(found.joinToString(" ")).append('\n')
        sb.append("不可用：").append(missing.joinToString(" ")).append('\n')
        sb.append("存储：").append(runCatching {
            val st = android.os.StatFs("/data")
            "剩余 " + (st.availableBytes / 1024 / 1024 / 1024) + "GB / 共 " +
                (st.totalBytes / 1024 / 1024 / 1024) + "GB"
        }.getOrDefault("未知"))
        return sb.toString()
    }

    private fun quote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}
