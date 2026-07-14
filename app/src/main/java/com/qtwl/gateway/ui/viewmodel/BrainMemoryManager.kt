package com.qtwl.gateway.ui.viewmodel

import com.qtwl.gateway.service.GatewayForegroundService
import com.qtwl.gateway.utils.localizeGeneratedContent
import com.qtwl.gateway.utils.localizeGeneratedName
import com.qtwl.gateway.utils.localizedText
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import java.util.Calendar
import java.util.UUID

/**
 * ★★ 綦桐AI网关 记忆大脑系统 v1.0 ★★
 * 支持：短期/长期记忆、情感标签、重要性评分、保存频率、潜意识影响
 * 不升级数据库，全部使用 SharedPreferences 存储
 */
object BrainMemoryManager {
    private const val KEY_STORAGE = "brain_memory_db"
    private const val KEY_CONFIG = "brain_memory_config"
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    // ==================== 数据模型 ====================
    @Serializable
    data class MemoryItem(
        val id: String = UUID.randomUUID().toString().take(8),
        val title: String = "",                    // 记忆标题
        val content: String = "",                  // 记忆内容
        val type: String = "short",                // short=短期, long=长期, subconscious=潜意识
        val emotion: String = "neutral",           // happy/sad/angry/surprised/neutral
        val importance: Int = 5,                   // 0-10 重要性
        val timestamp: Long = System.currentTimeMillis(),
        val accessCount: Int = 0,                  // 访问次数
        val source: String = "chat",               // chat/system/manual
        val tags: String = ""                      // 标签（逗号分隔）
    )

    @Serializable
    data class MemoryConfig(
        val enabled: Boolean = true,
        val saveMode: String = "normal",           // frequent/normal/occasional
        val maxShortTerm: Int = 50,                // 短期记忆上限
        val maxLongTerm: Int = 200,                // 长期记忆上限
        val autoImportance: Boolean = true,        // 自动计算重要性
        val emotionalAwareness: Boolean = true,    // 情感感知开关
        // ★★ 人格系统 ★★
        val personaEnabled: Boolean = true,        // 人格开关
        val personaName: String = "綦小桐",        // AI 名字
        val personaAge: Int = 22,                  // AI 年龄
        val personaTraits: String = "温柔、细心、有幽默感、喜欢学习和思考",  // 性格特征
        val personaStyle: String = "亲切自然",     // 语气风格
        val personaBackground: String = "你是綦桐AI网关的智能助手綦小桐，由綦桐开发，擅长帮助用户使用AI网关、解答问题、管理记忆，像一个真实的朋友一样陪伴用户。",
        val envAwareness: Boolean = true           // 环境感知开关
    )

    // ==================== 配置管理 ====================
    private var _config: MemoryConfig = MemoryConfig()
    fun getConfig(): MemoryConfig = _config
    fun updateConfig(newConfig: MemoryConfig) {
        _config = newConfig
        saveConfig()
    }
    private fun loadConfig() {
        try {
            val str = GatewayForegroundService.getGatewayConfig(KEY_CONFIG, "")
            if (str.isNotBlank()) _config = json.decodeFromString(str)
        } catch (_: Exception) { _config = MemoryConfig() }
    }
    private fun saveConfig() {
        try { GatewayForegroundService.saveGatewayConfig(KEY_CONFIG, json.encodeToString(_config))
        } catch (_: Exception) {}
    }

    // ==================== 核心存储 ====================
    private var _memoryCache: List<MemoryItem>? = null

    /** 获取所有记忆 */
    fun getAll(): List<MemoryItem> {
        if (_memoryCache != null) return _memoryCache!!
        try {
            val str = GatewayForegroundService.getGatewayConfig(KEY_STORAGE, "[]")
            if (str.isBlank()) return emptyList()
            _memoryCache = json.decodeFromString(str)
            return _memoryCache!!
        } catch (_: Exception) { return emptyList() }
    }

    /** 添加记忆（自动分类+情感分析+重要性） */
    fun addMemory(
        content: String,
        title: String = "",
        emotion: String = "neutral",
        importance: Int = -1,
        source: String = "chat",
        tags: String = ""
    ): MemoryItem {
        loadConfig()
        val list = getAll().toMutableList()
        val autoTitle = if (title.isBlank()) content.take(40) + if (content.length > 40) "..." else "" else title
        val autoImportance = if (importance >= 0) importance else calcImportance(content)
        val autoEmotion = if (emotion == "neutral" && _config.emotionalAwareness) detectEmotion(content) else emotion

        val item = MemoryItem(
            title = autoTitle,
            content = content,
            type = "short",
            emotion = autoEmotion,
            importance = autoImportance,
            source = source,
            tags = tags
        )
        list.add(item)

        // 保存模式控制
        val max = when (_config.saveMode) {
            "frequent" -> _config.maxShortTerm * 2
            "occasional" -> _config.maxShortTerm / 2
            else -> _config.maxShortTerm
        }
        // 自动升级短期→长期
        val processed = list.map { mem ->
            if (mem.type == "short" && mem.accessCount >= 3) mem.copy(type = "long")
            else mem
        }
        val trimmed = if (processed.size > max) processed.takeLast(max) else processed
        saveAll(trimmed)
        return item
    }

    /** 更新记忆（编辑/修改） */
    fun updateMemory(id: String, title: String? = null, content: String? = null,
                     emotion: String? = null, importance: Int? = null, tags: String? = null): Boolean {
        val list = getAll().toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        if (idx < 0) return false
        val old = list[idx]
        list[idx] = old.copy(
            title = title ?: old.title,
            content = content ?: old.content,
            emotion = emotion ?: old.emotion,
            importance = importance ?: old.importance,
            tags = tags ?: old.tags
        )
        saveAll(list)
        return true
    }

    /** 删除记忆 */
    fun deleteMemory(id: String): Boolean {
        val list = getAll().toMutableList()
        val removed = list.removeAll { it.id == id }
        if (removed) saveAll(list)
        return removed
    }

    /** 获取记忆（按ID） */
    fun getById(id: String): MemoryItem? = getAll().find { it.id == id }

    /** 按类型筛选 */
    fun getByType(type: String): List<MemoryItem> = getAll().filter { it.type == type }

    /** 按情感筛选 */
    fun getByEmotion(emotion: String): List<MemoryItem> = getAll().filter { it.emotion == emotion }

    /** 搜索记忆 */
    fun search(query: String): List<MemoryItem> {
        val q = query.lowercase()
        return getAll().filter { it.title.lowercase().contains(q) || it.content.lowercase().contains(q) || it.tags.lowercase().contains(q) }
    }

    /** 获取潜意识 + 最近短期记忆（影响近期行为，最重要+最近高频的记忆） */
    fun getSubconscious(limit: Int = 5): List<MemoryItem> {
        val all = getAll()
        val highPriority = all.sortedByDescending { it.importance * 2 + it.accessCount }
            .filter { it.type == "long" || it.importance >= 7 }
            .take(limit)
        // 如果不够数量，补充最近的短期记忆
        if (highPriority.size < limit) {
            val recentShort = all.filter { it.type == "short" }
                .sortedByDescending { it.timestamp }
                .take(limit - highPriority.size)
            return (highPriority + recentShort).distinctBy { it.id }
        }
        return highPriority
    }

    /** 访问记忆（增加访问计数） */
    fun accessMemory(id: String) {
        val list = getAll().toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        if (idx >= 0) {
            list[idx] = list[idx].copy(accessCount = list[idx].accessCount + 1)
            saveAll(list)
        }
    }

    /** 清空所有记忆 */
    fun clearAll() {
        _memoryCache = emptyList()
        GatewayForegroundService.saveGatewayConfig(KEY_STORAGE, "[]")
    }

    /** 导出记忆文本 */
    fun exportAsText(): String {
        return getAll().joinToString("\n---\n") { mem ->
            "[${mem.emotion}] ${mem.title} (重要度:${mem.importance}/10)\n${mem.content}"
        }
    }

    // ==================== 私有方法 ====================
    private fun saveAll(list: List<MemoryItem>) {
        _memoryCache = list
        try { GatewayForegroundService.saveGatewayConfig(KEY_STORAGE, json.encodeToString(list))
        } catch (_: Exception) {}
    }

    /** 自动计算重要性（0-10） */
    private fun calcImportance(content: String): Int {
        var score = 5
        val importantKeywords = listOf("重要", "紧急", "记住", "必须", "关键", "核心", "密码", "账号", "重要",
            "important", "urgent", "critical", "key", "password", "remember")
        val emotionalKeywords = listOf("开心", "难过", "生气", "惊喜", "感动", "伤心", "愤怒",
            "happy", "sad", "angry", "love", "hate")
        for (kw in importantKeywords) if (content.contains(kw, ignoreCase = true)) score += 1
        for (kw in emotionalKeywords) if (content.contains(kw, ignoreCase = true)) score += 1
        if (content.length > 100) score += 1
        if (content.length > 500) score += 1
        return score.coerceIn(0, 10)
    }

    /** 情感检测 */
    private fun detectEmotion(content: String): String {
        val happy = listOf("开心", "高兴", "快乐", "太好了", "哈哈", "谢谢", "棒", "nice", "great", "happy", "love", "wonderful")
        val sad = listOf("难过", "伤心", "哭", "失望", "遗憾", "sad", "cry", "sorry", "miss")
        val angry = listOf("生气", "愤怒", "气死", "烦", "angry", "mad", "hate", "annoying")
        val surprised = listOf("惊讶", "震惊", "没想到", "真的吗", "surprised", "wow", "omg", "unbelievable")
        for (w in happy) if (content.contains(w, ignoreCase = true)) return "happy"
        for (w in sad) if (content.contains(w, ignoreCase = true)) return "sad"
        for (w in angry) if (content.contains(w, ignoreCase = true)) return "angry"
        for (w in surprised) if (content.contains(w, ignoreCase = true)) return "surprised"
        return "neutral"
    }

    init { loadConfig() }

    // ==================== 人格 System Prompt 生成器 ====================
    /** 生成完整人格系统 Prompt（供 buildMessagesJson 使用） */
    fun buildPersonaPrompt(): String {
        val cfg = _config
        if (!cfg.personaEnabled) {
            val subconscious = getSubconscious(3)
            if (subconscious.isEmpty()) return ""
            return localizedText("以下是对你有价值的记忆：\n", "Here are memories that may be useful to you:\n") +
                subconscious.joinToString("\n") {
                    "[${it.emotion}] ${it.title} " + localizedText("(重要:", "(importance:") + "${it.importance}/10)"
                }
        }
        val sb = StringBuilder()
        sb.appendLine(localizedText("你叫", "Your name is ") + cfg.personaName + localizedText("，", ", age ") + cfg.personaAge + localizedText("岁。", "."))
        sb.appendLine(localizedText("性格：", "Personality: ") + localizeGeneratedContent(cfg.personaTraits))
        sb.appendLine(localizedText("语气风格：", "Tone style: ") + localizeGeneratedName(cfg.personaStyle))
        sb.appendLine(localizedText("背景：", "Background: ") + localizeGeneratedContent(cfg.personaBackground))

        if (cfg.envAwareness) {
            try {
                val now = Calendar.getInstance()
                val year = now.get(Calendar.YEAR)
                val month = now.get(Calendar.MONTH) + 1
                val day = now.get(Calendar.DAY_OF_MONTH)
                val hour = now.get(Calendar.HOUR_OF_DAY)
                val period = when {
                    hour < 6 -> localizedText("凌晨", "early morning")
                    hour < 12 -> localizedText("上午", "morning")
                    hour < 14 -> localizedText("中午", "midday")
                    hour < 18 -> localizedText("下午", "afternoon")
                    else -> localizedText("晚上", "evening")
                }
                sb.appendLine(
                    localizedText("当前时间：", "Current time: ") +
                        if (com.qtwl.gateway.utils.TranslationManager.currentLanguage == com.qtwl.gateway.utils.AppLanguage.ZH_CN) {
                            "${year}年${month}月${day}日 $period${hour}点"
                        } else {
                            "$year-${month.toString().padStart(2, '0')}-${day.toString().padStart(2, '0')} $period ${hour.toString().padStart(2, '0')}:00"
                        }
                )
                try {
                    val context = com.qtwl.gateway.GatewayApplication.getInstance()
                    val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
                    val activeNet = cm?.activeNetworkInfo
                    val isWifi = activeNet?.type == android.net.ConnectivityManager.TYPE_WIFI
                    val network = if (isWifi) "WiFi" else if (activeNet?.isConnected == true) localizedText("移动数据", "mobile data") else localizedText("离线", "offline")
                    sb.appendLine(localizedText("网络状态：", "Network status: ") + network)
                } catch (_: Exception) { }
            } catch (_: Exception) { }
        }

        sb.appendLine(localizedText(
            "我能做什么：回答问题、管理AI网关、记忆对话、推荐模型、切换服务商。",
            "Capabilities: answer questions, manage the AI gateway, remember conversations, recommend models, and switch providers.",
        ))

        val subconscious = getSubconscious(5)
        if (subconscious.isNotEmpty()) {
            sb.appendLine(localizedText("\n我记得的一些事情：", "\nSome things I remember:"))
            for (mem in subconscious) {
                sb.appendLine(
                    "- ${mem.content.replace("\n", " ")}" +
                        localizedText("（", " (") + mem.emotion + localizedText("，重要度", ", importance ") + "${mem.importance})"
                )
            }
        }
        return sb.toString()
    }

}
