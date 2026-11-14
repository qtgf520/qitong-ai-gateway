package com.qtwl.gateway.coagent

import com.qtwl.gateway.agent.AgentGuard
import com.qtwl.gateway.utils.AgentSettings
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.concurrent.TimeUnit

/** 一个工具参数 */
data class ToolArg(
    val name: String,
    val desc: String,
    val required: Boolean = false,
    val type: String = "string"
)

/**
 * 工具定义。
 *
 * 与旧实现的区别：schema 只由这份声明生成，字段名、required、type 全部显式，
 * 不会再出现「required 里有 properties 里没有的字段」这种被服务商判 400 的情况。
 */
class AgentToolSpec(
    val name: String,
    val summary: String,
    val args: List<ToolArg> = emptyList(),
    val group: String = "file",
    val run: (Map<String, String>) -> String
) {
    fun schema(): JsonObject = buildJsonObject {
        put("type", "function")
        put("function", buildJsonObject {
            put("name", name)
            put("description", summary)
            put("parameters", buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    args.forEach { a ->
                        put(a.name, buildJsonObject {
                            put("type", a.type)
                            put("description", a.desc)
                        })
                    }
                })
                val required = args.filter { it.required }.map { it.name }
                if (required.isNotEmpty()) {
                    put("required", buildJsonArray { required.forEach { add(it) } })
                }
            })
        })
    }
}

/**
 * 工具集合。
 *
 * 沙盒策略全部走 AgentGuard：路径检查、越界审批、命令风险评估、
 * 工具分组开关（file / shell / extra）都在那一层，这里只负责干活。
 */
object Toolkit {

    @Volatile
    var workspace: String = "/storage/emulated/0/KovaAgent"

    fun schemas(): JsonArray = buildJsonArray { all().forEach { add(it.schema()) } }

    fun all(): List<AgentToolSpec> = fileTools() + shellTools() + extraTools()

    fun find(name: String): AgentToolSpec? = all().firstOrNull { it.name == name }

    // ---------- 文件 ----------

    private fun fileTools(): List<AgentToolSpec> = listOf(
        AgentToolSpec(
            name = "list_dir",
            summary = "列出目录下的文件与子目录。path 省略时用工作区根目录。",
            args = listOf(ToolArg("path", "目录绝对路径，或工作区内的相对路径", required = false))
        ) { a ->
            val d = resolve(a["path"] ?: ".")
            AgentGuard.fileCheck(d.absolutePath, forWrite = false)?.let { return@AgentToolSpec it }
            if (!d.exists()) return@AgentToolSpec "目录不存在：" + d.absolutePath
            if (!d.isDirectory) return@AgentToolSpec "不是目录：" + d.absolutePath
            val children = d.listFiles()?.sortedBy { it.name }.orEmpty()
            if (children.isEmpty()) return@AgentToolSpec "（空目录）"
            children.joinToString("\n") { f ->
                (if (f.isDirectory) "目录" else humanSize(f.length())) + "\t" + f.name
            }
        },
        AgentToolSpec(
            name = "read_file",
            summary = "读取文本文件内容。超出 max_kb 会被截断。",
            args = listOf(
                ToolArg("path", "文件路径", required = true),
                ToolArg("max_kb", "最多读取多少 KB，默认 64", required = false)
            )
        ) { a ->
            val f = resolve(a["path"].orEmpty())
            AgentGuard.fileCheck(f.absolutePath, forWrite = false)?.let { return@AgentToolSpec it }
            if (!f.exists()) return@AgentToolSpec "文件不存在：" + f.absolutePath
            if (!f.isFile) return@AgentToolSpec "不是文件：" + f.absolutePath
            val cap = (a["max_kb"]?.toIntOrNull() ?: 64).coerceIn(1, 512) * 1024L
            val length = f.length()
            val bytes = f.inputStream().use { input ->
                val buf = ByteArray(minOf(length, cap).toInt())
                var read = 0
                while (read < buf.size) {
                    val n = input.read(buf, read, buf.size - read)
                    if (n <= 0) break
                    read += n
                }
                buf.copyOf(read)
            }
            val text = String(bytes, Charsets.UTF_8)
            if (length > cap) text + "\n\n…（已截断，文件共 " + humanSize(length) + "）" else text
        },
        AgentToolSpec(
            name = "write_file",
            summary = "把内容写入文件（覆盖已有内容）。父目录不存在会自动创建。",
            args = listOf(
                ToolArg("path", "文件路径", required = true),
                ToolArg("content", "要写入的完整文本内容", required = true)
            )
        ) { a ->
            val f = resolve(a["path"].orEmpty())
            AgentGuard.fileCheck(f.absolutePath, forWrite = true)?.let { return@AgentToolSpec it }
            AgentGuard.gateOutsideWrite(f.absolutePath)?.let { return@AgentToolSpec it }
            f.parentFile?.mkdirs()
            val text = a["content"].orEmpty()
            f.writeText(text, Charsets.UTF_8)
            "已写入 " + f.absolutePath + "（" + humanSize(text.toByteArray(Charsets.UTF_8).size.toLong()) + "）"
        },
        AgentToolSpec(
            name = "append_file",
            summary = "在文件末尾追加内容，文件不存在则创建。",
            args = listOf(
                ToolArg("path", "文件路径", required = true),
                ToolArg("content", "要追加的文本", required = true)
            )
        ) { a ->
            val f = resolve(a["path"].orEmpty())
            AgentGuard.fileCheck(f.absolutePath, forWrite = true)?.let { return@AgentToolSpec it }
            AgentGuard.gateOutsideWrite(f.absolutePath)?.let { return@AgentToolSpec it }
            f.parentFile?.mkdirs()
            f.appendText(a["content"].orEmpty(), Charsets.UTF_8)
            "已追加到 " + f.absolutePath
        },
        AgentToolSpec(
            name = "make_dir",
            summary = "创建目录（含多级父目录）。",
            args = listOf(ToolArg("path", "目录路径", required = true))
        ) { a ->
            val f = resolve(a["path"].orEmpty())
            AgentGuard.fileCheck(f.absolutePath, forWrite = true)?.let { return@AgentToolSpec it }
            AgentGuard.gateOutsideWrite(f.absolutePath)?.let { return@AgentToolSpec it }
            if (f.mkdirs() || f.isDirectory) "已创建 " + f.absolutePath else "创建失败：" + f.absolutePath
        },
        AgentToolSpec(
            name = "delete_path",
            summary = "删除文件或目录（目录会递归删除）。危险操作，会先征求确认。",
            args = listOf(ToolArg("path", "要删除的路径", required = true))
        ) { a ->
            val f = resolve(a["path"].orEmpty())
            AgentGuard.fileCheck(f.absolutePath, forWrite = true)?.let { return@AgentToolSpec it }
            AgentGuard.gateOutsideWrite(f.absolutePath)?.let { return@AgentToolSpec it }
            if (!f.exists()) return@AgentToolSpec "路径不存在：" + f.absolutePath
            val ok = f.deleteRecursively()
            if (ok) "已删除 " + f.absolutePath else "删除失败（可能无权限）：" + f.absolutePath
        }
    )

    // ---------- 命令 ----------

    /** 解析并校验 shell 身份 */
    private fun pickUser(a: Map<String, String>): Pair<Shell.User, String?> {
        val u = Shell.User.of(a["user"] ?: com.qtwl.gateway.utils.AgentSettings.shellTier)
        if (u != Shell.User.APP && !AgentGuard.allowRoot) {
            return u to ("错误：当前未开启 root 授权，无法使用「" + u.label + "」身份。\n" +
                "请让用户在 Agent 设置里打开「Root 指导」，或改用 app 身份。")
        }
        return u to null
    }

    private fun shellTools(): List<AgentToolSpec> = listOf(
        AgentToolSpec(
            name = "run_shell",
            summary = "在常驻 shell 会话中执行命令。cd、export 会保留到后续命令，支持管道、重定向、&&、多行。工作目录与上次一致。",
            args = listOf(
                ToolArg("cmd", "要执行的命令，可含管道与重定向", required = true),
                ToolArg("timeout_sec", "超时秒数，默认 120，最大 600", required = false),
                ToolArg("workdir", "可选：执行前切到这个目录", required = false),
                ToolArg("user", "可选身份：app（默认）/ adb（UID 2000）/ root（UID 0）", required = false)
            ),
            group = "shell"
        ) { a ->
            if (!AgentGuard.isGroupEnabled("shell")) {
                return@AgentToolSpec AgentGuard.toolDisabledMessage("run_shell")
            }
            val cmd = a["cmd"].orEmpty().trim()
            if (cmd.isEmpty()) return@AgentToolSpec "cmd 不能为空"
            AgentGuard.gateCommand(cmd)?.let { return@AgentToolSpec it }
            val (user, uerr) = pickUser(a)
            if (uerr != null) return@AgentToolSpec uerr
            val timeout = (a["timeout_sec"]?.toIntOrNull() ?: 120).coerceIn(5, 600) * 1000L
            val (code, out) = Shell.exec(cmd, timeout, a["workdir"]?.takeIf { it.isNotBlank() }, user)
            "退出码 " + code + "\n" + out.trim().take(100_000)
        },
        AgentToolSpec(
            name = "run_script",
            summary = "执行一段多行 shell 脚本（支持 for/if/函数），脚本会写成临时文件再运行。",
            args = listOf(
                ToolArg("script", "完整脚本内容，可多行", required = true),
                ToolArg("timeout_sec", "超时秒数，默认 180", required = false),
                ToolArg("user", "可选身份：app / adb / root", required = false)
            ),
            group = "shell"
        ) { a ->
            if (!AgentGuard.isGroupEnabled("shell")) {
                return@AgentToolSpec AgentGuard.toolDisabledMessage("run_script")
            }
            val script = a["script"].orEmpty()
            if (script.isBlank()) return@AgentToolSpec "script 不能为空"
            AgentGuard.gateCommand(script)?.let { return@AgentToolSpec it }
            val dir = File(workspace, ".agent_scripts")
            dir.mkdirs()
            val file = File(dir, "s_" + System.currentTimeMillis() + ".sh")
            file.writeText(script, Charsets.UTF_8)
            val (user, uerr) = pickUser(a)
            if (uerr != null) return@AgentToolSpec uerr
            val timeout = (a["timeout_sec"]?.toIntOrNull() ?: 180).coerceIn(5, 600) * 1000L
            val (code, out) = Shell.exec("sh " + file.absolutePath, timeout, null, user)
            file.delete()
            "退出码 " + code + "\n" + out.trim().take(100_000)
        },
        AgentToolSpec(
            name = "run_background",
            summary = "后台启动长任务（服务器、下载），立即返回 PID 与日志文件路径，不阻塞对话。",
            args = listOf(
                ToolArg("cmd", "要后台执行的命令", required = true),
                ToolArg("log", "可选：日志文件路径，默认写到工作区 .agent_logs/", required = false),
                ToolArg("user", "可选身份：app / adb / root", required = false)
            ),
            group = "shell"
        ) { a ->
            if (!AgentGuard.isGroupEnabled("shell")) {
                return@AgentToolSpec AgentGuard.toolDisabledMessage("run_background")
            }
            val cmd = a["cmd"].orEmpty().trim()
            if (cmd.isEmpty()) return@AgentToolSpec "cmd 不能为空"
            AgentGuard.gateCommand(cmd)?.let { return@AgentToolSpec it }
            val log = a["log"]?.takeIf { it.isNotBlank() }
                ?: File(File(workspace, ".agent_logs"), "bg_" + System.currentTimeMillis() + ".log").absolutePath
            val (user, uerr) = pickUser(a)
            if (uerr != null) return@AgentToolSpec uerr
            val (pid, path) = Shell.execBackground(cmd, log, user)
            "已后台启动\nPID：" + pid + "\n日志：" + path +
                "\n查看：run_shell 执行 tail -n 50 " + path
        },
        AgentToolSpec(
            name = "shell_info",
            summary = "环境自检：三种 shell 身份（app/adb/root）各自是否可用、实际 uid、工作目录、可用命令、剩余存储。不确定环境时先调它。",
            group = "extra"
        ) {
            Shell.probe()
        }
    )

    // ---------- 其它 ----------

    private fun extraTools(): List<AgentToolSpec> = listOf(
        AgentToolSpec(
            name = "web_fetch",
            summary = "抓取一个网页的原始文本（最多 8000 字符）。",
            args = listOf(ToolArg("url", "完整网址，需带 http:// 或 https://", required = true)),
            group = "extra"
        ) { a ->
            val url = a["url"].orEmpty().trim()
            if (!url.startsWith("http")) return@AgentToolSpec "url 必须以 http:// 或 https:// 开头"
            runCatching {
                val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 20000
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) QiTongAgent")
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                val text = body.replace(Regex("<script[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), " ")
                    .replace(Regex("<style[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), " ")
                    .replace(Regex("<[^>]+>"), " ")
                    .replace(Regex("\\s+"), " ")
                    .trim()
                "HTTP " + code + "\n" + text.take(8000)
            }.getOrElse { "抓取失败：" + (it.message ?: it.javaClass.simpleName) }
        },
        AgentToolSpec(
            name = "now",
            summary = "获取设备当前时间。",
            group = "extra"
        ) {
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss EEEE", java.util.Locale.CHINA)
                .format(java.util.Date())
        }
    )

    // ---------- 内部 ----------

    /** 相对路径一律落到工作区下，避免模型写相对路径时落到进程当前目录 */
    fun resolve(raw: String): File {
        val text = raw.trim().ifEmpty { "." }
        val f = File(text)
        if (f.isAbsolute) return f
        return File(workspace, text)
    }

    private fun humanSize(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> String.format("%.1fMB", bytes / 1048576.0)
        bytes >= 1024L -> String.format("%.1fKB", bytes / 1024.0)
        else -> bytes.toString() + "B"
    }

}
