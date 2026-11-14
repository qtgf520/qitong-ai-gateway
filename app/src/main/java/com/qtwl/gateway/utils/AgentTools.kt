package com.qtwl.gateway.utils

import com.qtwl.gateway.agent.AgentGuard
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
            desc = "读取文本文件内容（最多 60000 字符）",
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
            desc = "执行一条 shell 命令并返回输出。支持管道、重定向、多行脚本（需安全级别为完全放开）",
            params = mapOf(
                "cmd" to "要执行的命令，如 ls -l、cat a.txt | grep x、或整段多行脚本",
                "timeout" to "可选，超时秒数（默认 60，范围 5-600）"
            )
        ),
        ToolDef(
            name = "run_script",
            desc = "把内容写成脚本文件并执行（适合多行脚本，避免引号转义问题），返回输出与退出码",
            params = mapOf(
                "content" to "脚本全文，第一行可写 #!/system/bin/sh",
                "path" to "可选，脚本保存路径（绝对路径），默认写到工作区 .agent_scripts/ 下",
                "timeout" to "可选，超时秒数（默认 60）"
            )
        ),
        ToolDef(
            name = "run_background",
            desc = "在后台启动命令，不等待结束，返回后台任务标识。适合启动服务器或长任务",
            params = mapOf(
                "cmd" to "要后台执行的命令",
                "log" to "可选，输出日志文件路径（绝对路径），默认写到工作区 .agent_logs/ 下"
            )
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

        // ★ 工具开关：被关掉的分组不执行，直接给模型一句明确的说明
        if (!AgentGuard.isGroupEnabled(AgentGuard.groupOfTool(call.name))) {
            return AgentGuard.toolDisabledMessage(call.name)
        }

        return try {
            when (call.name) {
                "list_dir" -> listDir(resolve(a["path"].orEmpty()))
                "read_file" -> readFile(resolve(a["path"].orEmpty()))
                "write_file" -> writeFile(resolve(a["path"].orEmpty(), true), a["content"].orEmpty())
                "append_file" -> appendFile(resolve(a["path"].orEmpty(), true), a["content"].orEmpty())
                "make_dir" -> makeDir(resolve(a["path"].orEmpty(), true))
                "delete" -> delete(resolve(a["path"].orEmpty(), true))
                "file_info" -> fileInfo(resolve(a["path"].orEmpty()))
                "search_files" -> searchFiles(resolve(a["path"].orEmpty()), a["keyword"].orEmpty())
                "current_time" -> java.text.SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()
                ).format(java.util.Date())
                "run_shell" -> runShell(a["cmd"].orEmpty(), a["timeout"])
                "run_script" -> runScript(a["content"].orEmpty(), a["path"], a["timeout"])
                "run_background" -> runBackground(a["cmd"].orEmpty(), a["log"])
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
     * 执行 shell 命令。
     *
     * 真正的执行逻辑在 ShellRunner 里（输出读取、超时、杀进程都在那）。
     * 这里只负责：取安全级别与超时、定位工作目录、把结果转成模型能读的文本。
     *
     * 安全模型见 ShellRunner 的类注释：
     *  严格模式（默认）= 白名单 + 禁管道/重定向
     *  完全放开       = 任意命令、管道、多行脚本
     */
    private fun runShell(cmdRaw: String, timeoutSecRaw: String?): String {
        val cmd = cmdRaw.trim()
        if (cmd.isEmpty()) return "错误：缺少 cmd 参数"
        // ★ 沙盒守卫：root 策略 + 风险分级 + 用户审批
        AgentGuard.gateCommand(cmd)?.let { return it }

        val root = File(AgentSettings.workspace).absoluteFile
        if (!root.exists()) root.mkdirs()

        val timeout = resolveTimeoutMs(timeoutSecRaw)
        val safety = if (AgentSettings.shellFullAccess) ShellRunner.Safety.FULL
        else ShellRunner.Safety.STRICT

        val r = ShellRunner.run(cmd, root, timeout, safety)
        return r.toToolText()
    }

    /**
     * 把脚本写成文件再执行。
     *
     * 为什么需要独立工具：模型写多行脚本时常被 JSON 字符串转义搞坏
     * （换行、引号、$ 变量），直接写文件能绕开这一层。
     */
    private fun runScript(content: String, pathRaw: String?, timeoutSecRaw: String?): String {
        val body = content.trim()
        if (body.isEmpty()) return "错误：缺少 content 参数"
        AgentGuard.gateCommand(body)?.let { return it }

        val root = File(AgentSettings.workspace).absoluteFile
        if (!root.exists()) root.mkdirs()

        val scriptFile = try {
            if (!pathRaw.isNullOrBlank()) {
                val p = resolve(pathRaw, true)
                File(p)
            } else {
                val dir = File(root, ".agent_scripts")
                if (!dir.exists()) dir.mkdirs()
                File(dir, "script_${System.currentTimeMillis()}.sh")
            }
        } catch (e: WorkspaceViolation) {
            return e.message ?: "错误：脚本路径越界"
        } catch (e: Exception) {
            return "错误：脚本路径无效：${e.message}"
        }

        return try {
            scriptFile.parentFile?.mkdirs()
            scriptFile.writeText(body)
            scriptFile.setExecutable(true)

            val timeout = resolveTimeoutMs(timeoutSecRaw)
            val safety = if (AgentSettings.shellFullAccess) ShellRunner.Safety.FULL
            else ShellRunner.Safety.STRICT

            // 用 sh 显式执行，不依赖 shebang 与可执行位
            val quoted = "\"" + scriptFile.absolutePath.replace("\"", "\\\"") + "\""
            val r = ShellRunner.run("sh $quoted", root, timeout, safety)

            val head = "脚本已保存：${scriptFile.absolutePath}\n"
            head + r.toToolText()
        } catch (e: Exception) {
            "错误：写脚本失败 ${e.message ?: e.javaClass.simpleName}"
        }
    }

    /**
     * 后台执行命令。
     *
     * 用于启动常驻进程（本地服务器、长时间任务）。
     * 不做超时管理——进程的生命周期由用户或后续命令负责。
     */
    private fun runBackground(cmdRaw: String, logRaw: String?): String {
        val cmd = cmdRaw.trim()
        if (cmd.isEmpty()) return "错误：缺少 cmd 参数"
        AgentGuard.gateCommand(cmd)?.let { return it }

        val root = File(AgentSettings.workspace).absoluteFile
        if (!root.exists()) root.mkdirs()

        val logFile = try {
            if (!logRaw.isNullOrBlank()) File(resolve(logRaw, true))
            else {
                val dir = File(root, ".agent_logs")
                if (!dir.exists()) dir.mkdirs()
                File(dir, "bg_${System.currentTimeMillis()}.log")
            }
        } catch (e: WorkspaceViolation) {
            return e.message ?: "错误：日志路径越界"
        } catch (e: Exception) {
            return "错误：日志路径无效：${e.message}"
        }

        return try {
            logFile.parentFile?.mkdirs()
            val pb = ProcessBuilder("/system/bin/sh", "-c", cmd)
                .directory(root)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.to(logFile))
                .redirectInput(ProcessBuilder.Redirect.INHERIT)
            val p = pb.start()

            // 不等待：给 600ms 观察期，能立刻判断「起得来」还是「秒退」
            Thread.sleep(600)
            if (p.isAlive) {
                "已在后台启动\n" +
                        "日志：${logFile.absolutePath}\n" +
                        "如需查看：read_file {\"path\":\"${logFile.absolutePath}\"}\n" +
                        "如需停止：run_shell {\"cmd\":\"pkill -f 进程名\"}（需完全放开级别）"
            } else {
                val code = try { p.exitValue() } catch (_: Throwable) { -1 }
                val tail = try {
                    if (logFile.exists()) logFile.readText().takeLast(1500) else ""
                } catch (_: Throwable) {
                    ""
                }
                "错误：命令立即退出（退出码 $code）\n日志：${logFile.absolutePath}\n" +
                        if (tail.isNotBlank()) "--- 日志尾部 ---\n$tail" else "（无日志输出）"
            }
        } catch (e: Exception) {
            "错误：后台启动失败 ${e.message ?: e.javaClass.simpleName}"
        }
    }

    /** 解析超时参数：优先用模型给的值，否则用设置里的默认值 */
    private fun resolveTimeoutMs(raw: String?): Long {
        val sec = raw?.trim()?.toIntOrNull()?.coerceIn(5, 600)
            ?: AgentSettings.shellTimeoutSec
        return sec * 1000L
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
    private fun resolve(raw: String, forWrite: Boolean = false): String {
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

        // ★ 权限沙盒：范围（仅工作区/整机只读/整机读写）、只读模式、系统目录
        AgentGuard.fileCheck(canonicalPath, forWrite)?.let { throw WorkspaceViolation(it) }
        // ★ 越界写：即使范围放开，也要用户点一次确认
        if (forWrite) AgentGuard.gateOutsideWrite(canonicalPath)?.let { throw WorkspaceViolation(it) }

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
        // 原来只读 8000 字符，一个稍大的 HTML 都读不全，模型改不动自己的文件。
        // 提到 60000 字符（约 60KB），足以覆盖单文件页面。
        return if (text.length > 60000) text.take(60000) + "\n…（内容截断，原长 ${text.length} 字符）"
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