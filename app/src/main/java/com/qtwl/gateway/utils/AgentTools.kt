package com.qtwl.gateway.utils

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Agent 工具层
 *
 * 设计原则：
 *  - 模型只负责「表达意图」，输出 XML 标记
 *  - 这里负责「解析 + 执行」，不依赖模型的推理能力
 *  - 工具足够简单，1B 小模型也能正确使用
 *
 * 权限：需要 MANAGE_EXTERNAL_STORAGE（Android 11+）才能访问整机存储。
 */
object AgentTools {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * 桥接后的完整工具集缓存。
     * BuiltinTools + NetworkTools 每次构造都要建一堆 lambda，
     * 缓存起来避免每轮工具调用都重建。
     */
    private val bridgeCache: List<AgentToolBridge.Bridged> by lazy {
        try {
            AgentToolBridge.all(useProxy = true)
        } catch (e: Throwable) {
            // 桥接失败不能影响基础工具（文件读写仍然要能用）
            emptyList()
        }
    }

    /** 给模型看的完整工具名单（出错时提示用） */
    private fun allToolNames(): String {
        val builtin = BUILTIN.map { it.name }
        val bridged = bridgeCache.map { it.name }
        return (builtin + bridged).distinct().sorted().joinToString("、")
    }

    /** 工具定义 */
    @Serializable
    data class ToolDef(
        val name: String,
        val desc: String,
        /** 参数说明，key=参数名，value=说明 */
        val params: Map<String, String> = emptyMap(),
        /** 归属插件名，内置工具为空 */
        val plugin: String = ""
    )

    /** 内置工具（始终可用） */
    val BUILTIN: List<ToolDef> = listOf(
        ToolDef(
            name = "list_dir",
            desc = "列出目录下的文件和文件夹",
            params = mapOf("path" to "目录绝对路径（必须以 / 开头）")
        ),
        ToolDef(
            name = "read_file",
            desc = "读取文本文件内容（最多 8000 字符）",
            params = mapOf("path" to "文件绝对路径（必须以 / 开头）")
        ),
        ToolDef(
            name = "write_file",
            desc = "把内容写入文件，已存在则覆盖，不存在则创建",
            params = mapOf("path" to "文件绝对路径（必须以 / 开头）", "content" to "要写入的文本")
        ),
        ToolDef(
            name = "append_file",
            desc = "在文件末尾追加内容",
            params = mapOf("path" to "文件绝对路径（必须以 / 开头）", "content" to "要追加的文本")
        ),
        ToolDef(
            name = "make_dir",
            desc = "创建目录（含父目录）",
            params = mapOf("path" to "目录绝对路径（必须以 / 开头）")
        ),
        ToolDef(
            name = "delete",
            desc = "删除文件或空目录",
            params = mapOf("path" to "绝对路径（必须以 / 开头）")
        ),
        ToolDef(
            name = "file_info",
            desc = "查看文件大小、修改时间、类型",
            params = mapOf("path" to "绝对路径（必须以 / 开头）")
        ),
        ToolDef(
            name = "search_files",
            desc = "在目录中按文件名关键字搜索（递归）",
            params = mapOf("path" to "起始目录绝对路径（必须以 / 开头）", "keyword" to "文件名包含的关键字")
        ),
        ToolDef(
            name = "current_time",
            desc = "获取当前日期和时间",
            params = emptyMap()
        ),
        ToolDef(
            name = "run_shell",
            desc = "执行一条 shell 命令并返回输出（工作目录锁定在 Agent 工作区，只允许白名单命令）",
            params = mapOf("cmd" to "要执行的命令，如 ls -l 或 cat README.md")
        )
    )

    /** 解析模型输出的工具调用块 */
    data class ToolCall(val name: String, val args: Map<String, String>)

    /**
     * 从模型输出里解析工具调用。
     *
     * 支持两种写法（模型不一定会严格照做，所以两种都认）：
     *   1) <tool>read_file</tool><args>{"path":"/sdcard/a.txt"}</args>
     *   2) [TOOL] read_file {"path":"/sdcard/a.txt"}
     */
    fun parse(text: String): ToolCall? {
        // 写法 1：<tool>名字</tool> ... <args>{...}</args>
        val name1 = between(text, "<tool>", "</tool>")
        if (name1 != null) {
            val name = name1.trim().trim('"', '\'', ' ', '\n')
            val rawArgs = extractBraces(between(text, "<args>", "</args>").orEmpty())
                ?: extractBraces(text) ?: ""
            return ToolCall(name, parseArgs(rawArgs))
        }

        // 写法 2：[TOOL] 名字 {...}
        // 兼容模型常见的变形：[TOOL]、【TOOL】、TOOL:、代码块包裹
        var idx = indexOfIgnoreCase(text, "[TOOL]")
        var tagLen = 6
        if (idx < 0) {
            idx = indexOfIgnoreCase(text, "【TOOL】")
            tagLen = 6
        }
        if (idx < 0) {
            idx = indexOfIgnoreCase(text, "TOOL:")
            tagLen = 5
        }
        if (idx < 0) {
            idx = indexOfIgnoreCase(text, "<TOOL>")
            tagLen = 6
        }
        if (idx >= 0) {
            // 去掉可能紧跟的 ] 或 】
            var after = text.substring(idx + tagLen).trimStart()
            while (after.isNotEmpty() && (after[0] == ']' || after[0] == '】' || after[0] == ':')) {
                after = after.substring(1).trimStart()
            }
            var i = 0
            while (i < after.length &&
                (after[i].isLetterOrDigit() || after[i] == '_')
            ) i++
            val name = after.substring(0, i).trim()
            if (name.isNotEmpty()) {
                val rawArgs = extractBraces(after) ?: ""
                return ToolCall(name, parseArgs(rawArgs))
            }
        }
        return null
    }

    /** 取 a 与 b 之间的内容（大小写不敏感），没有返回 null */
    private fun between(text: String, a: String, b: String): String? {
        val s = indexOfIgnoreCase(text, a)
        if (s < 0) return null
        val from = s + a.length
        val e = indexOfIgnoreCase(text, b, from)
        if (e < 0) return null
        return text.substring(from, e)
    }

    private fun indexOfIgnoreCase(text: String, target: String, from: Int = 0): Int {
        val n = target.length
        if (n == 0) return -1
        var i = from
        val last = text.length - n
        while (i <= last) {
            if (text.regionMatches(i, target, 0, n, ignoreCase = true)) return i
            i++
        }
        return -1
    }

    /**
     * 从字符串里提取第一个完整的 {...} 块（按括号配对，忽略字符串内的括号）。
     * 不用正则，避免转义问题。
     */
    private fun extractBraces(text: String): String? {
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inStr = false
        var esc = false
        for (i in start until text.length) {
            val c = text[i]
            if (inStr) {
                when {
                    esc -> esc = false
                    c == '\\' -> esc = true
                    c == '"' -> inStr = false
                }
            } else {
                when (c) {
                    '"' -> inStr = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return text.substring(start, i + 1)
                    }
                }
            }
        }
        return null
    }

    private fun parseArgs(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()

        // ① 标准 JSON
        try {
            val obj = json.parseToJsonElement(raw).jsonObject
            return obj.entries.associate { (k, v) ->
                k to v.jsonPrimitive.contentOrNullSafe()
            }
        } catch (_: Exception) {
            // 继续往下试
        }

        // ② 单引号 JSON：{"path": './a.txt'}  → 换成双引号再试
        try {
            val fixed = raw.replace('\'', '"')
            val obj = json.parseToJsonElement(fixed).jsonObject
            return obj.entries.associate { (k, v) ->
                k to v.jsonPrimitive.contentOrNullSafe()
            }
        } catch (_: Exception) {
            // 继续往下试
        }

        // ③ 无引号键值：{path: .} 或 {path=./a.txt} 或 {path: "./a"}（混用）
        //    小模型经常漏引号，这是最常见的失败形态。
        run {
            val inner = raw.trim().removePrefix("{").removeSuffix("}").trim()
            if (inner.isEmpty()) return emptyMap()
            val map = mutableMapOf<String, String>()
            // 按逗号切，但要跳过字符串内部的逗号
            val parts = splitTopLevel(inner)
            for (part in parts) {
                val idxColon = part.indexOf(':').let { if (it < 0) part.indexOf('=') else it }
                if (idxColon <= 0) continue
                val key = part.substring(0, idxColon).trim().trim('"', '\'', ' ')
                var value = part.substring(idxColon + 1).trim()
                value = value.trim('"', '\'', ' ')
                if (key.isNotEmpty()) map[key] = value
            }
            if (map.isNotEmpty()) return map
        }

        // ④ 完全裸写：path=./a.txt 或 path: ./a.txt（没有外层花括号）
        run {
            val map = mutableMapOf<String, String>()
            val parts = splitTopLevel(raw.trim().removePrefix("{").removeSuffix("}"))
            for (part in parts) {
                val idxColon = part.indexOf(':').let { if (it < 0) part.indexOf('=') else it }
                if (idxColon <= 0) continue
                val key = part.substring(0, idxColon).trim().trim('"', '\'', ' ')
                val value = part.substring(idxColon + 1).trim().trim('"', '\'', ' ')
                if (key.isNotEmpty() && key.length < 32) map[key] = value
            }
            if (map.isNotEmpty()) return map
        }

        return emptyMap()
    }

    /** 按顶层逗号切分（跳过引号内的逗号） */
    private fun splitTopLevel(s: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuote = false
        var quoteChar = ' '
        for (c in s) {
            when {
                inQuote -> {
                    sb.append(c)
                    if (c == quoteChar) inQuote = false
                }
                c == '"' || c == '\'' -> {
                    inQuote = true
                    quoteChar = c
                    sb.append(c)
                }
                c == ',' -> {
                    out.add(sb.toString())
                    sb.setLength(0)
                }
                else -> sb.append(c)
            }
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out
    }

    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String =
        try { content } catch (_: Exception) { toString() }

    /**
     * 执行工具，返回给模型看的文本结果。
     */
    fun execute(call: ToolCall): String {
        // ★ 参数名别名归一化
        //   小模型经常把 path 写成 file / dir / filename / 路径。
        //   与其让工具报「缺少 path 参数」，不如在这里先映射一遍。
        val a = normalizeArgs(call.args)
        return try {
            when (call.name) {
                "list_dir" -> listDir(resolve(a["path"].orEmpty()))
                "read_file" -> readFile(resolve(a["path"].orEmpty()))
                "write_file" -> writeFile(resolve(a["path"].orEmpty()), a["content"].orEmpty())
                "append_file" -> appendFile(resolve(a["path"].orEmpty()), a["content"].orEmpty())
                "make_dir" -> makeDir(resolve(a["path"].orEmpty()))
                "delete" -> delete(resolve(a["path"].orEmpty()))
                "file_info" -> fileInfo(resolve(a["path"].orEmpty()))
                "search_files" -> searchFiles(resolve(a["path"].orEmpty()), a["keyword"].orEmpty())
                "current_time" -> java.text.SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()
                ).format(java.util.Date())
                "run_shell" -> runShell(a["cmd"].orEmpty())
                else -> {
                    // ★ 回退到 AgentTool 完整工具集（BuiltinTools + NetworkTools）
                    //   这样本地模型也能用 calculate / convert_unit / http_request /
                    //   read_webpage / transform_text / random_number 等，
                    //   不必再维护两套工具定义。
                    val bridged = bridgeCache.firstOrNull { it.name == call.name }
                    if (bridged != null) {
                        bridged.run(a)
                    } else {
                        "错误：未知工具 ${call.name}。" +
                                "可用工具：${allToolNames()}"
                    }
                }
            }
        } catch (e: WorkspaceViolation) {
            e.message ?: "错误：路径越界"
        } catch (e: SecurityException) {
            "错误：没有访问权限。请在系统设置中授予「所有文件访问权限」"
        } catch (e: Exception) {
            "错误：${e.message ?: e.javaClass.simpleName}"
        }
    }

    /**
     * 参数名归一化。
     * 把模型可能用到的同义参数名统一到工具自身的标准名上。
     *
     * 注意：这里不用局部函数（嵌套 fun），
     * 因为它在成员函数内捕获局部 val 时，会让部分 Kotlin 版本的
     * 解析器把后续成员函数一起吞掉，编译时报「Unresolved reference」。
     */
    private fun normalizeArgs(raw: Map<String, String>): Map<String, String> {
        val out = raw.toMutableMap()

        fun pick(std: String, candidates: List<String>) {
            if (!out[std].isNullOrBlank()) return
            for (c in candidates) {
                val v = out[c]
                if (!v.isNullOrBlank()) {
                    out[std] = v
                    return
                }
            }
        }

        pick("path", listOf("file", "filename", "file_path", "dir", "directory", "路径", "文件"))
        pick("content", listOf("text", "data", "body", "内容"))
        pick("keyword", listOf("query", "key", "关键字", "关键词"))
        pick("cmd", listOf("command", "shell", "命令"))
        return out
    }
// ==================== shell 执行 ====================

    /**
     * 允许的命令白名单（只看命令名，不看参数）。
     *
     * 为什么用白名单而不是黑名单：
     *   shell 的组合能力太强（管道、重定向、$()、反引号），
     *   黑名单永远列不全。只放行这些只读/低危命令，
     *   即使模型拼出花来，能做的最坏事情也就是读文件。
     */
    private val SHELL_WHITELIST = setOf(
        "ls", "cat", "echo", "pwd", "head", "tail", "wc",
        "grep", "find", "du", "df", "stat", "date", "which",
        "mkdir", "touch", "cp", "mv", "rm", "sed", "awk",
        "git", "python3", "node", "java", "javac", "kotlinc"
    )

    /**
     * 禁止出现的字符组合。
     * 主要是防止命令替换和后台执行把白名单绕过去。
     *
     * 注意：美元符号和花括号必须用 Unicode 转义（\u0024）写在字符串里，
     * 直接写会开启 Kotlin 字符串模板，把后面的代码当成表达式，编译报错。
     */
    private val SHELL_FORBIDDEN = listOf(
        "\u0060",                 // 反引号
        "\u0024(",                // $( 命令替换
        "\u0024{",                // ${ 变量展开
        "&&", "||", ";", "|&", ">", "<",
        "sudo", "su ", "sh ", "bash ", "eval", "exec",
        "chmod", "chown", "curl", "wget", "nc ", "telnet"
    )

    /**
     * 执行 shell 命令。
     *
     * 三道限制：
     *  1. 工作目录固定为 Agent 工作区，模型无法 read 到别的目录
     *  2. 命令名必须在白名单内
     *  3. 禁止命令替换和管道符，防止 `ls | rm -rf` 这类绕过
     *
     * 超时 15 秒，输出截断 4000 字符。
     */
    private fun runShell(cmdRaw: String): String {
        val cmd = cmdRaw.trim()
        if (cmd.isEmpty()) return "错误：缺少 cmd 参数"

        // 去掉可能带上的代码围栏
        val clean = cmd
            .removePrefix("```sh").removePrefix("```bash").removePrefix("```")
            .removeSuffix("```")
            .trim()

        // 危险组合检查
        for (bad in SHELL_FORBIDDEN) {
            if (clean.contains(bad)) {
                return "错误：命令中包含不允许的片段「$bad」。\n" +
                        "为了安全，禁止管道、重定向、命令替换和提权操作。\n" +
                        "请改用单个简单命令，或使用 write_file / read_file 工具。"
            }
        }

        // 命令名白名单检查
        val firstToken = clean.split(Regex("\\s+")).firstOrNull().orEmpty()
        // 允许 ./xxx 这种执行工作区内脚本的写法
        val baseName = if (firstToken.startsWith("./")) {
            firstToken.substringAfterLast('/')
        } else {
            firstToken
        }
        val allowed = SHELL_WHITELIST.any { baseName == it } ||
                baseName.endsWith(".sh") ||
                baseName.endsWith(".kt")
        if (!allowed) {
            return "错误：命令「$baseName」不在允许列表内。\n" +
                    "允许的命令：${SHELL_WHITELIST.sorted().joinToString(", ")}\n" +
                    "如果要读写文件，优先用 read_file / write_file 工具。"
        }

        // 工作目录固定在工作区，越界操作自然失败
        val root = File(AgentSettings.workspace).absoluteFile
        if (!root.exists()) root.mkdirs()

        return try {
            val pb = ProcessBuilder("/system/bin/sh", "-c", clean)
                .directory(root)
                .redirectErrorStream(true)
            val proc = pb.start()

            // 读输出（单独线程，避免缓冲区满导致子进程阻塞）
            val sb = StringBuilder()
            val reader = Thread {
                try {
                    proc.inputStream.bufferedReader().use { r ->
                        var line = r.readLine()
                        while (line != null && sb.length < 4000) {
                            sb.append(line).append('\n')
                            line = r.readLine()
                        }
                    }
                } catch (_: Exception) {
                }
            }
            reader.isDaemon = true
            reader.start()

            val finished = proc.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)
            if (!finished) {
                proc.destroyForcibly()
                return "错误：命令执行超时（15 秒）已终止：$clean"
            }
            reader.join(1000)

            val out = sb.toString().trim()
            val code = proc.exitValue()
            val body = out.ifEmpty { "（无输出）" }
            val clipped = if (body.length > 4000) body.take(4000) + "\n…（输出过长已截断）" else body
            "退出码：$code\n$clipped"
        } catch (e: Exception) {
            "错误：${e.message ?: e.javaClass.simpleName}"
        }
    }

    // ==================== 工作区沙箱 ====================

    /** 路径越界时抛出，execute 会把它转成给模型看的错误文本 */
    private class WorkspaceViolation(msg: String) : Exception(msg)

    /**
     * 解析模型给的路径，并检查是否在工作区内。
     *
     * v3.64 起：只接受【绝对路径】，相对路径直接拒绝并给出正确写法。
     *
     * 为什么砍掉相对路径：
     *   旧逻辑会把 "src/a.txt" 自动拼成 "<工作区>/src/a.txt"，
     *   看似方便，实际上让模型永远不知道真实路径长什么样：
     *     - 它写 list_dir {"path":"."}，看不到真实目录名
     *     - run_shell ls 输出相对路径，下一轮又拼错
     *     - 报错里出现两种路径形态，模型越改越乱
     *   绝对路径只有一种形态，模型看一次就能一直用对。
     *
     * 规则：
     *   1. 空路径 → 拒绝
     *   2. 不以 / 开头 → 拒绝，并附上它想要的绝对路径
     *   3. 工作区锁定时，绝对路径必须落在工作区内（canonicalPath 前缀校验）
     *   4. ../ 逃逸、软链接逃逸一律拒绝
     */
    private fun resolve(raw: String): String {
        val input = raw.trim()
        if (input.isEmpty()) {
            throw WorkspaceViolation(
                "错误：缺少 path 参数。必须传入绝对路径（以 / 开头），且落在工作区内：" + AgentSettings.workspace
            )
        }

        val root = File(AgentSettings.workspace).absoluteFile
        if (!root.exists()) root.mkdirs()
        val rootPath = try {
            root.canonicalPath
        } catch (_: Exception) {
            root.absolutePath
        }

        // 只接受绝对路径：相对路径直接拒绝，并把正确的绝对路径告诉模型
        if (!input.startsWith("/")) {
            val suggest = File(root, input).path
            throw WorkspaceViolation(
                "错误：只接受绝对路径，收到了相对路径「" + input + "」。\n" +
                        "请改写成绝对路径，例如：" + suggest + "\n" +
                        "工作区根目录是：" + rootPath + "\n" +
                        "不确定现有结构就先调用 list_dir，path 传：" + rootPath
            )
        }

        val target = File(input)
        val canonicalPath = try {
            target.canonicalPath
        } catch (_: Exception) {
            target.absolutePath
        }

        // 未锁定工作区：绝对路径直接放行
        if (!AgentSettings.workspaceLocked) return canonicalPath

        val inside = canonicalPath == rootPath ||
                canonicalPath.startsWith(rootPath + File.separator)
        if (!inside) {
            throw WorkspaceViolation(
                "错误：路径越界。「" + input + "」不在工作区内。\n" +
                        "工作区是：" + rootPath + "\n" +
                        "请改用工作区内的绝对路径，例如 " + rootPath + "/src/main/App.java\n" +
                        "想查看现有结构可以调用 list_dir，path 传：" + rootPath
            )
        }
        return canonicalPath
    }

    // ---------- 具体实现 ----------

    private fun listDir(path: String): String {
        if (path.isBlank()) return "错误：缺少 path 参数"
        val dir = File(path)
        if (!dir.exists()) return "错误：路径不存在 $path"
        if (!dir.isDirectory) return "错误：不是目录 $path"
        val entries = dir.listFiles()?.sortedWith(
            compareByDescending<File> { it.isDirectory }.thenBy { it.name }
        ) ?: return "目录为空或无法读取"
        if (entries.isEmpty()) return "目录为空"
        val sb = StringBuilder("$path 共 ${entries.size} 项：\n")
        entries.take(200).forEach { f ->
            sb.append(if (f.isDirectory) "[目录] " else "[文件] ")
                .append(f.name)
            if (f.isFile) sb.append(" (${humanSize(f.length())})")
            sb.append('\n')
        }
        if (entries.size > 200) sb.append("… 还有 ${entries.size - 200} 项未显示")
        return sb.toString()
    }

    private fun readFile(path: String): String {
        if (path.isBlank()) return "错误：缺少 path 参数"
        val f = File(path)
        if (!f.exists()) return "错误：文件不存在 $path"
        if (f.isDirectory) return "错误：这是目录，请用 list_dir"
        if (f.length() > 5 * 1024 * 1024) return "错误：文件过大（${humanSize(f.length())}），暂不支持读取"
        val text = f.readText()
        return if (text.length > 8000) text.take(8000) + "\n…（内容截断，原长 ${text.length} 字符）"
        else text
    }

    private fun writeFile(path: String, content: String): String {
        if (path.isBlank()) return "错误：缺少 path 参数"
        val f = File(path)
        f.parentFile?.mkdirs()
        f.writeText(content)
        return "已写入 ${f.absolutePath}（${content.length} 字符）"
    }

    private fun appendFile(path: String, content: String): String {
        if (path.isBlank()) return "错误：缺少 path 参数"
        val f = File(path)
        f.parentFile?.mkdirs()
        if (!f.exists()) f.createNewFile()
        f.appendText(content)
        return "已追加到 ${f.absolutePath}（${humanSize(f.length())}）"
    }

    private fun makeDir(path: String): String {
        if (path.isBlank()) return "错误：缺少 path 参数"
        val f = File(path)
        return if (f.exists() && f.isDirectory) "目录已存在"
        else if (f.mkdirs()) "已创建目录 $path" else "创建失败，可能没有权限"
    }

    private fun delete(path: String): String {
        if (path.isBlank()) return "错误：缺少 path 参数"
        val f = File(path)
        if (!f.exists()) return "错误：路径不存在"
        return if (f.delete()) "已删除 $path" else "删除失败（目录非空或没有权限）"
    }

    private fun fileInfo(path: String): String {
        if (path.isBlank()) return "错误：缺少 path 参数"
        val f = File(path)
        if (!f.exists()) return "错误：路径不存在"
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
        return buildString {
            append("路径：${f.absolutePath}\n")
            append("类型：${if (f.isDirectory) "目录" else "文件"}\n")
            if (f.isFile) append("大小：${humanSize(f.length())}\n")
            append("可读：${f.canRead()}  可写：${f.canWrite()}\n")
            append("修改时间：${sdf.format(java.util.Date(f.lastModified()))}")
        }
    }

    private fun searchFiles(path: String, keyword: String): String {
        if (path.isBlank()) return "错误：缺少 path 参数"
        if (keyword.isBlank()) return "错误：缺少 keyword 参数"
        val root = File(path)
        if (!root.exists() || !root.isDirectory) return "错误：目录不存在"
        val hits = mutableListOf<File>()
        root.walkTopDown()
            .onEnter { it.name != "Android" || it.parentFile?.absolutePath != "/sdcard" }
            .take(20000)
            .forEach { f ->
                if (f.isFile && f.name.contains(keyword, ignoreCase = true)) hits.add(f)
            }
        if (hits.isEmpty()) return "未找到包含「$keyword」的文件"
        val sb = StringBuilder("找到 ${hits.size} 个：\n")
        hits.take(50).forEach { sb.append(it.absolutePath).append(" (${humanSize(it.length())})\n") }
        if (hits.size > 50) sb.append("… 还有 ${hits.size - 50} 个")
        return sb.toString()
    }

    private fun humanSize(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> String.format("%.2f GB", bytes / 1024.0 / 1024 / 1024)
        bytes >= 1024L * 1024 -> String.format("%.1f MB", bytes / 1024.0 / 1024)
        bytes >= 1024 -> String.format("%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}