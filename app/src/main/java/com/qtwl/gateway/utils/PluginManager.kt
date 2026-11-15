package com.qtwl.gateway.utils

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 插件管理
 *
 * 插件就是一个 JSON 文件，放在应用私有目录 plugins/ 下。
 * 用户可以自己写、自己放，也可以在界面里导入。
 *
 * 插件文件格式：
 * {
 *   "name": "文件操作",
 *   "version": "1.0",
 *   "author": "xxx",
 *   "tools": [
 *     { "name": "read_file", "desc": "读取文件", "params": {"path": "文件路径"} }
 *   ],
 *   "instructions": "使用本插件时，优先用绝对路径"
 * }
 */
object PluginManager {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; prettyPrint = true }

    @Serializable
    data class Plugin(
        val name: String,
        val version: String = "1.0",
        val author: String = "",
        val enabled: Boolean = true,
        val description: String = "",
        val tools: List<AgentTools.ToolDef> = emptyList(),
        /** 附加到系统提示词里的说明 */
        val instructions: String = ""
    )

    fun pluginDir(context: Context): File {
        val dir = File(context.filesDir, "plugins")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** 插件文件路径（按插件名） */
    private fun fileFor(context: Context, name: String): File =
        File(pluginDir(context), "${safeName(name)}.json")

    private fun safeName(name: String): String =
        name.replace(Regex("[^\\w\\u4e00-\\u9fa5-]"), "_")

    /** 读取全部插件 */
    fun loadAll(context: Context): List<Plugin> {
        val dir = pluginDir(context)
        val files = dir.listFiles { f -> f.extension == "json" } ?: return emptyList()
        return files.mapNotNull { f ->
            try {
                json.decodeFromString<Plugin>(f.readText())
            } catch (_: Exception) {
                null
            }
        }
    }

    /** 只取启用的插件 */
    fun enabledPlugins(context: Context): List<Plugin> =
        loadAll(context).filter { it.enabled }

    /** 保存插件 */
    fun save(context: Context, plugin: Plugin): Result<File> = try {
        val f = fileFor(context, plugin.name)
        f.writeText(json.encodeToString(Plugin.serializer(), plugin))
        Result.success(f)
    } catch (e: Exception) {
        Result.failure(e)
    }

    /** 删除插件 */
    fun delete(context: Context, name: String): Boolean =
        fileFor(context, name).delete()

    /** 从文本导入（用户粘贴的 JSON） */
    fun importFromText(context: Context, text: String): Result<Plugin> = try {
        val p = json.decodeFromString<Plugin>(text)
        if (p.name.isBlank()) {
            Result.failure(IllegalArgumentException("插件缺少 name 字段"))
        } else {
            save(context, p).map { p }
        }
    } catch (e: Exception) {
        Result.failure(e)
    }

    /** 内置示例插件（首次进入时写入，用户可改可删） */
    fun ensureSamples(context: Context) {
        val dir = pluginDir(context)
        if (dir.listFiles { f -> f.extension == "json" }?.isNotEmpty() == true) return

        val samples = listOf(
            Plugin(
                name = "文件助手",
                version = "1.0",
                author = "内置",
                description = "读写本地文件：列出目录、读取、写入、追加、搜索",
                tools = listOf(
                    AgentTools.ToolDef("list_dir", "列出目录内容", mapOf("path" to "目录路径"), "文件助手"),
                    AgentTools.ToolDef("read_file", "读取文件", mapOf("path" to "文件路径"), "文件助手"),
                    AgentTools.ToolDef("write_file", "写入文件", mapOf("path" to "路径", "content" to "内容"), "文件助手"),
                    AgentTools.ToolDef("append_file", "追加内容", mapOf("path" to "路径", "content" to "内容"), "文件助手"),
                    AgentTools.ToolDef("make_dir", "创建目录", mapOf("path" to "路径"), "文件助手"),
                    AgentTools.ToolDef("search_files", "搜索文件", mapOf("path" to "起始目录", "keyword" to "关键字"), "文件助手")
                ),
                instructions = "所有 path 参数必须是【绝对路径】（以 / 开头），且落在工作区内。相对路径一律被拒绝。"
            ),
            Plugin(
                name = "Shell 终端",
                version = "1.0",
                author = "内置",
                description = "执行 shell 命令、运行脚本、后台任务。支持管道、重定向与多行脚本",
                tools = listOf(
                    AgentTools.ToolDef(
                        "run_shell",
                        "执行 shell 命令，返回退出码与输出",
                        mapOf("cmd" to "命令", "timeout" to "超时秒数，可选"),
                        "Shell 终端"
                    ),
                    AgentTools.ToolDef(
                        "run_script",
                        "把脚本内容写成文件再执行，适合多行脚本",
                        mapOf(
                            "content" to "脚本全文",
                            "path" to "保存路径，可选",
                            "timeout" to "超时秒数，可选"
                        ),
                        "Shell 终端"
                    ),
                    AgentTools.ToolDef(
                        "run_background",
                        "后台启动命令，不等待结束",
                        mapOf("cmd" to "命令", "log" to "日志路径，可选"),
                        "Shell 终端"
                    )
                ),
                instructions = "工作目录是 Agent 工作区。安全级别为「严格」时只能跑白名单命令、" +
                        "不能使用管道与重定向；需要完整 shell 时请让用户在设置里改为「完全放开」。" +
                        "多行脚本优先用 run_script，避免 JSON 转义问题。"
            ),
            Plugin(
                name = "系统信息",
                version = "1.0",
                author = "内置",
                description = "查询当前时间、文件信息",
                tools = listOf(
                    AgentTools.ToolDef("current_time", "获取当前时间", emptyMap(), "系统信息"),
                    AgentTools.ToolDef("file_info", "查看文件详情", mapOf("path" to "路径"), "系统信息")
                ),
                instructions = ""
            )
        )
        samples.forEach { save(context, it) }
    }

    /** 生成注入系统提示词的工具说明段落 */
    fun buildToolPrompt(context: Context): String {
        val plugins = enabledPlugins(context)
        if (plugins.isEmpty()) return ""

        val ws = AgentSettings.workspace
        val locked = AgentSettings.workspaceLocked

        val sb = StringBuilder()

        // ---------- 工作区约束（必须放在最前面，模型最先读到） ----------
        if (locked) {
            sb.append("## 工作区\n\n")
            sb.append("你的工作目录是：").append(ws).append('\n')
            sb.append("所有文件操作都限制在这个目录内。\n")
            sb.append("★ 所有 path 参数必须写成【绝对路径】，并且必须以 " + ws + "/ 开头。\n")
            sb.append("  正确写法：" + ws + "/README.md   " + ws + "/src/main/App.java\n")
            sb.append("  错误写法：README.md、./src/a.txt、../other/x.txt —— 相对路径会被直接拒绝。\n")
            sb.append("  不知道现有结构时，先调用 list_dir，path 传 " + ws + "\n")
            sb.append("  越出工作区的绝对路径同样会被拒绝。\n\n")
        }

        sb.append("## 可用工具\n\n")

        // ---------- 格式说明：单行格式，小模型最容易照做 ----------
        // 0.5B 模型的通病是「看到规则就复述规则」。所以这里：
        //   1. 指令用最短的句子，不带从句
        //   2. 给 3 个正例 + 1 个反例，正例覆盖「读目录/读文件/执行命令」
        //   3. 明确写出「不要解释、不要复述」，因为小模型很爱复述提示词
        sb.append("## 工具调用格式\n\n")
        sb.append("需要工具时，你的整个回复只能是这一行：\n\n")
        sb.append("    [TOOL] 工具名 {\"参数\":\"值\"}\n\n")
        sb.append("正确示例：\n")
        sb.append("    用户：看看目录里有什么\n")
        sb.append("    你：[TOOL] list_dir {\"path\":\"" + ws + "\"}\n\n")
        sb.append("    用户：读一下 README.md\n")
        sb.append("    你：[TOOL] read_file {\"path\":\"" + ws + "/README.md\"}\n\n")
        sb.append("    用户：执行 ls -l\n")
        sb.append("    你：[TOOL] run_shell {\"cmd\":\"ls -l\"}\n\n")
        sb.append("错误示例（不要这样）：\n")
        sb.append("    ✗ 好的，我来帮你调用 list_dir 工具，参数是 path=.  ← 这是解释，不是工具调用\n")
        sb.append("    ✗ [TOOL] list_dir(path=\".\")                      ← 参数必须是 JSON 花括号\n")
        sb.append("    ✗ [TOOL] read_file {\"path\":\"README.md\"}           ← 相对路径会被拒绝\n\n")
        sb.append("铁律：\n")
        sb.append("1. 工具调用必须独占一行，开头就是 [TOOL]\n")
        sb.append("2. 参数必须用 {\"键\":\"值\"} 的 JSON 大括号\n")
        sb.append("3. 一次只调用一个工具\n")
        sb.append("4. 不需要工具时，直接用中文回答用户，不要出现 [TOOL]\n")
        sb.append("5. 工具返回结果后，用中文总结给用户，不要重复工具调用行\n\n")

        sb.append("## 工具清单\n\n")

        plugins.forEach { p ->
            p.tools.forEach { t ->
                sb.append("- ").append(t.name).append("：").append(t.desc)
                if (t.params.isNotEmpty()) {
                    sb.append("（参数：")
                    sb.append(t.params.entries.joinToString("，") { (k, v) -> "$k = $v" })
                    sb.append("）")
                }
                sb.append('\n')
            }
            if (p.instructions.isNotBlank()) {
                sb.append("  ").append(p.instructions).append('\n')
            }
        }

        // ---------- 桥接的工具（BuiltinTools + NetworkTools） ----------
        //   这些原本只有云端 Agent 能用，现在本地模型也能调。
        //   为了让小模型不被长描述淹没，只列名字 + 一句话 + 参数名。
        val bridged = try { AgentToolBridge.all(useProxy = true) } catch (_: Throwable) { emptyList() }
        if (bridged.isNotEmpty()) {
            sb.append("## 通用能力\n\n")
            bridged.forEach { t ->
                sb.append("- ").append(t.name).append("：").append(t.desc)
                if (t.params.isNotEmpty()) {
                    sb.append("（参数：").append(t.params.keys.joinToString("，")).append("）")
                }
                sb.append('\n')
            }
            sb.append('\n')
        }
        sb.append('\n')

        return sb.toString()
    }
}