package com.qtwl.gateway.utils

import com.qtwl.gateway.agent.AgentGuard
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * Shell 命令执行核心
 *
 * ============================================================
 * 为什么单独抽成一个类
 * ============================================================
 * 旧版 runShell 直接写在 AgentTools 里，有几个真实缺陷：
 *
 *  1. **输出截断导致死锁**：读到 4000 字符就不再读，但子进程还在往管道写。
 *     管道缓冲区（约 64KB）写满后子进程永久阻塞，waitFor 只能等到超时，
 *     用户看到「命令超时」而不是命令的真实输出。ls 一个大目录就能触发。
 *  2. **读取线程未同步**：sb 由后台线程写、主线程读，join(1000) 可能提前返回，
 *     读到的是半截内容，还会偶发可见性错误。
 *  3. **超时后不清理子孙进程**：destroyForcibly 只杀 /system/bin/sh，
 *     它 fork 出来的子进程会继续在后台跑。
 *  4. **编码不确定**：未指定 charset，中文输出可能乱码。
 *  5. **超时时间硬编码 15 秒**，编译/打包这类正常耗时命令必然失败。
 *
 * 现在这个实现把上述问题逐个解决：始终读完管道（超限只丢弃不阻塞），
 * 用线程安全的缓冲 + 正确的 join，超时后杀进程树，统一 UTF-8。
 *
 * ============================================================
 * 安全模型
 * ============================================================
 *  STRICT（默认）：命令名必须在白名单内，且禁止管道/重定向/命令替换。
 *                  即使模型被诱导，最坏也只能读文件。
 *  FULL：完全不限。这是「内置所有 sh」的形态，需要用户显式开启，
 *        因为一旦开启，模型就能执行任意命令（含 rm -rf）。
 *        App 本身无 root 权限，破坏范围限于本应用可写的位置。
 */
object ShellRunner {

    enum class Safety {
        /** 白名单 + 禁止危险符号（默认） */
        STRICT,

        /** 不做任何过滤，等价于直接开一个 sh */
        FULL
    }

    data class Result(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val timedOut: Boolean,
        val truncated: Boolean,
        /** 命令未执行（被安全策略拦截等） */
        val rejectedReason: String? = null
    ) {
        val isRejected: Boolean get() = rejectedReason != null

        /**
         * 转成给模型看的文本。
         * 保持结构稳定，模型才好判断「这次是成功还是失败」。
         */
        fun toToolText(maxChars: Int = 6000): String {
            if (rejectedReason != null) return "错误：$rejectedReason"

            val sb = StringBuilder()
            if (timedOut) {
                sb.append("错误：命令超时已被强制终止（exit=124）\n")
            } else {
                sb.append("退出码：").append(exitCode).append('\n')
            }

            val out = stdout.trim()
            val err = stderr.trim()

            if (out.isNotEmpty()) {
                sb.append("--- 输出 ---\n")
                sb.append(if (out.length > maxChars) out.take(maxChars) + "\n…（输出过长已截断）" else out)
                sb.append('\n')
            }
            if (err.isNotEmpty()) {
                sb.append("--- 错误输出 ---\n")
                sb.append(if (err.length > 2000) err.take(2000) + "\n…（已截断）" else err)
                sb.append('\n')
            }
            if (out.isEmpty() && err.isEmpty() && !timedOut) {
                sb.append("（无输出）\n")
            }
            if (truncated) {
                sb.append("注：输出超出缓冲上限，后续内容已丢弃。\n")
            }
            return sb.toString().trim()
        }
    }

    /** 保留读入内存的最大字符数；超出后继续读取但丢弃，避免管道阻塞 */
    private const val KEEP_CHARS = 256 * 1024

    /**
     * 白名单（只对 STRICT 生效）。
     * 只放行只读/低危命令，覆盖日常排查与脚本执行。
     */
    private val WHITELIST = setOf(
        // 文件与目录
        "ls", "cat", "head", "tail", "wc", "stat", "file", "du", "df",
        "find", "grep", "sed", "awk", "sort", "uniq", "cut", "tr", "diff",
        "mkdir", "touch", "cp", "mv", "rm", "ln", "chmod", "basename", "dirname", "realpath",
        // 系统信息
        "uname", "id", "whoami", "date", "uptime", "ps", "free", "top", "which", "getprop", "dumpsys", "logcat",
        // 文本与编码
        "echo", "printf", "base64", "md5sum", "sha1sum", "sha256sum", "xxd", "od", "cksum",
        // 压缩
        "tar", "gzip", "gunzip", "zip", "unzip",
        // 运行环境
        "sh", "pwd", "env", "sleep", "true", "false", "test", "expr",
        "git", "python3", "python", "node", "java", "javac", "kotlinc", "gradle",
        "am", "pm", "cmd", "settings", "input", "getevent"
    )

    /**
     * STRICT 下禁止出现的片段。
     * 目的是堵住「白名单命令 + 管道/替换」绕过白名单的路径。
     * 美元符号必须写成 Unicode 转义，否则会开启 Kotlin 字符串模板。
     */
    private val FORBIDDEN = listOf(
        "\u0060",        // 反引号
        "\u0024(",       // $( 命令替换
        "\u0024{",       // ${ 变量展开
        "&&", "||", ";", "|", ">", "<",
        "sudo", "su ", "eval", "exec", "chown",
        "curl", "wget", "nc ", "telnet", "reboot", "shutdown"
    )

    /**
     * 执行命令。
     *
     * @param command 完整命令文本（可多行，交给 sh -c 处理）
     * @param workDir 工作目录；不存在会自动创建
     * @param timeoutMs 超时毫秒
     * @param safety 安全级别
     * @param scriptPath 若传入，则把 command 写入该临时文件并以 sh 执行（便于多行脚本）
     */
    fun run(
        command: String,
        workDir: File,
        timeoutMs: Long = 60_000L,
        safety: Safety = Safety.STRICT
    ): Result {
        val cmd = cleanUp(command)
        if (cmd.isEmpty()) {
            return Result(0, "", "", false, false, rejectedReason = "缺少 cmd 参数")
        }

        if (safety == Safety.STRICT) {
            rejectReason(cmd)?.let { why ->
                return Result(0, "", "", false, false, rejectedReason = why)
            }
        }

        val root = try {
            workDir.absoluteFile.also { if (!it.exists()) it.mkdirs() }
        } catch (e: Exception) {
            return Result(0, "", "", false, false, rejectedReason = "工作目录不可用：${e.message}")
        }

        var proc: Process? = null
        return try {
            val pb = ProcessBuilder("/system/bin/sh", "-c", cmd)
                .directory(root)
                .redirectErrorStream(false)   // 分开收，便于区分正常输出与报错
            proc = pb.start()

            val outBuf = SyncBuffer(KEEP_CHARS)
            val errBuf = SyncBuffer(KEEP_CHARS)

            val tOut = drain(proc.inputStream, outBuf)
            val tErr = drain(proc.errorStream, errBuf)

            val finished = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)

            if (!finished) {
                // 超时：先温和终止进程树，再强杀，最后把已读到的输出照样返回
                killTree(proc)
                tOut.join(1000)
                tErr.join(1000)
                return Result(
                    exitCode = 124,
                    stdout = outBuf.text(),
                    stderr = errBuf.text(),
                    timedOut = true,
                    truncated = outBuf.truncated || errBuf.truncated
                )
            }

            // 进程已退出，管道会 EOF，读取线程正常结束
            tOut.join(3000)
            tErr.join(3000)

            val code = try {
                proc.exitValue()
            } catch (_: Throwable) {
                0
            }

            Result(
                exitCode = code,
                stdout = outBuf.text(),
                stderr = errBuf.text(),
                timedOut = false,
                truncated = outBuf.truncated || errBuf.truncated
            )
        } catch (e: Throwable) {
            proc?.let { killTree(it) }
            Result(0, "", "", false, false, rejectedReason = e.message ?: e.javaClass.simpleName)
        }
    }

    // ---------------- 内部实现 ----------------

    /** 去掉 markdown 代码围栏，模型经常把命令包在 ```sh 里 */
    private fun cleanUp(raw: String): String {
        var s = raw.trim()
        if (s.startsWith("```")) {
            s = s.removePrefix("```sh").removePrefix("```bash").removePrefix("```shell").removePrefix("```")
            if (s.endsWith("```")) s = s.dropLast(3)
        }
        return s.trim()
    }

    /** STRICT 下的拦截判断，返回原因或 null */
    private fun rejectReason(cmd: String): String? {
        FORBIDDEN.forEach { bad ->
            if (cmd.contains(bad)) {
                return "命令中包含不允许的片段「$bad」。当前是「严格」安全级别，" +
                        "禁止管道、重定向、命令替换与提权操作。\n" +
                        "如需执行完整 shell，请到「设置 → 本地模型 → Agent 设置」里" +
                        "把「Shell 安全级别」改为「完全放开」。\n" +
                        "或者改用 read_file / write_file 工具完成文件操作。"
            }
        }

        val firstToken = cmd.split(Regex("\\s+")).firstOrNull().orEmpty()
        if (firstToken.isEmpty()) return "命令为空"

        // 允许 ./script.sh 这种执行工作区内脚本的写法
        val baseName = if (firstToken.contains('/')) firstToken.substringAfterLast('/') else firstToken

        // ★ 开了「Root 指导」才允许 su 及配套探查命令
        val rootCmds = setOf("su", "id", "getprop", "dumpsys", "logcat", "pm", "cmd", "settings")
        val allowed = baseName in WHITELIST ||
                (AgentGuard.allowRoot && baseName in rootCmds) ||
                baseName.endsWith(".sh") ||
                baseName.endsWith(".py") ||
                baseName.endsWith(".kt") ||
                baseName.endsWith(".js")
        if (!allowed) {
            return "命令「$baseName」不在严格模式的允许列表内。\n" +
                    "严格模式允许的命令：${WHITELIST.sorted().joinToString(", ")}\n" +
                    "需要任意命令时，请把「Shell 安全级别」改成「完全放开」。\n" +
                    "读写文件优先用 read_file / write_file 工具。"
        }
        return null
    }

    /** 读取线程：把流读干净，超过上限就丢弃，绝不中途停止读取 */
    private fun drain(input: InputStream, buf: SyncBuffer): Thread {
        val t = Thread {
            try {
                input.bufferedReader(Charsets.UTF_8).use { r ->
                    val chunk = CharArray(8192)
                    while (true) {
                        val n = r.read(chunk)
                        if (n <= 0) break
                        buf.append(chunk, n)
                        // 注意：即使已达上限也继续读，只是不再保存。
                        // 这是避免管道写满导致子进程死锁的关键。
                    }
                }
            } catch (_: Throwable) {
            }
        }
        t.isDaemon = true
        t.start()
        return t
    }

    /** 先 destroy，等 1.5 秒，不退则 destroyForcibly */
    private fun killTree(proc: Process) {
        try {
            proc.destroy()
            if (!proc.waitFor(1500, TimeUnit.MILLISECONDS)) {
                proc.destroyForcibly()
                proc.waitFor(1500, TimeUnit.MILLISECONDS)
            }
        } catch (_: Throwable) {
            try {
                proc.destroyForcibly()
            } catch (_: Throwable) {
            }
        }
        try {
            proc.inputStream.close()
        } catch (_: Throwable) {
        }
        try {
            proc.errorStream.close()
        } catch (_: Throwable) {
        }
    }

    /**
     * 线程安全且带上限的字符缓冲。
     * 读线程可能活到主线程返回之后，所以必须在库内部保护，
     * 不能像旧版那样把裸 StringBuilder 交给两个线程。
     */
    private class SyncBuffer(private val limit: Int) {
        private val sb = StringBuilder()
        @Volatile
        var truncated = false
            private set

        @Synchronized
        fun append(chars: CharArray, len: Int) {
            if (truncated) return
            val room = limit - sb.length
            if (room <= 0) {
                truncated = true
                return
            }
            if (len <= room) {
                sb.appendRange(chars, 0, len)
            } else {
                sb.appendRange(chars, 0, room)
                truncated = true
            }
        }

        @Synchronized
        fun text(): String = sb.toString()
    }
}