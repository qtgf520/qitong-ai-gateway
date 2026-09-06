package com.qtwl.gateway.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import android.app.ActivityManager
import android.content.Context
import com.qtwl.gateway.GatewayApplication
import com.qtwl.gateway.data.db.AppDatabase
import com.qtwl.gateway.data.model.AiModel
import com.qtwl.gateway.data.model.ModelRouteKey
import com.qtwl.gateway.data.model.findByRouteKey
import com.qtwl.gateway.data.model.routeKey
import com.qtwl.gateway.data.model.ChatMessage
import com.qtwl.gateway.data.model.Conversation
import com.qtwl.gateway.data.model.Provider
import com.qtwl.gateway.data.model.TokenUsage
import com.qtwl.gateway.data.model.ApiKeyUsageRow
import com.qtwl.gateway.data.db.BackupManager
import com.qtwl.gateway.network.Socks5SocketFactory
import com.qtwl.gateway.network.UpstreamClient
import com.qtwl.gateway.service.GatewayForegroundService
import com.qtwl.gateway.service.LiveSession
import com.qtwl.gateway.gateway.GatewayService
import com.qtwl.gateway.gateway.GatewayScheduler
import com.qtwl.gateway.data.model.SpeedHistory
import com.qtwl.gateway.data.db.SpeedHistoryDao
import com.qtwl.gateway.utils.localizeGeneratedName
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.encodeToString
import kotlinx.serialization.Serializable
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit

// 三指标测速
import com.qtwl.gateway.utils.ModelSpeedTester
import com.qtwl.gateway.data.model.SpeedMetrics
import com.qtwl.gateway.data.model.ModelCapabilities
import com.qtwl.gateway.data.model.CapabilityTag

/**
 * 网关应用的主 ViewModel —— 管理全部业务状态
 * 包括：服务商管理、模型同步、聊天对话、会话管理、Token用量统计
 */

// ==================== 包级全局变量（Activity重建不丢失）====================
/** 流水线测速状态条目 */
@kotlinx.serialization.Serializable
data class PipelineTestItem(
    val modelId: String,
    val modelName: String,
    val providerId: Long = 0L,
    val status: String,
    val latencyMs: Long = 0,
    val isCurrent: Boolean = false
) {
    val selectionKey: String
        get() = ModelRouteKey.encode(providerId, modelId)
}

private val _pipelineStatus = MutableStateFlow<List<PipelineTestItem>>(emptyList())
val pipelineStatus: StateFlow<List<PipelineTestItem>> = _pipelineStatus.asStateFlow()

/** ★★ 流水线测速进度 ★★ */
private val _pipelineProgress = MutableStateFlow(0f)
val pipelineProgress: StateFlow<Float> = _pipelineProgress.asStateFlow()

private val _pipelineRunning = MutableStateFlow(false)
val pipelineRunning: StateFlow<Boolean> = _pipelineRunning.asStateFlow()

/** ★★ 测速倒计时（秒），0表示不在倒计时 ★★ */
private val _pipelineCountdown = MutableStateFlow(0)
val pipelineCountdown: StateFlow<Int> = _pipelineCountdown.asStateFlow()

/** ★★ qtai-sj 虚拟模型独立状态 ★★ */
data class QtaiSjStatus(
    val available: Boolean = false,
    val bestModelId: String? = null,
    val bestModelName: String? = null,
    val bestTtft: Long = 0,
    val lastUpdated: Long = 0,
    val isTesting: Boolean = false,
)

private val _qtaiSjStatus = MutableStateFlow(QtaiSjStatus())
val qtaiSjStatus: StateFlow<QtaiSjStatus> = _qtaiSjStatus.asStateFlow()

private var pipelineJob: kotlinx.coroutines.Job? = null

/** ★★ qtai-sj 记忆管理器（SharedPreferences 存储，不升级数据库）★★ */
object ChatMemoryManager {
    private const val KEY_MEMORY = "chat_memory_json"
    private const val KEY_MEMORY_ENABLED = "chat_memory_enabled"
    private const val KEY_MEMORY_MAX = "chat_memory_max"
    private val json = Json { ignoreUnknownKeys = true }
    
    /** 记忆是否开启 */
    fun isEnabled(): Boolean = GatewayForegroundService.getGatewayConfig(KEY_MEMORY_ENABLED, "true").toBoolean()
    fun setEnabled(enabled: Boolean) = GatewayForegroundService.saveGatewayConfig(KEY_MEMORY_ENABLED, enabled.toString())
    
    /** 记忆条数 */
    fun getMaxCount(): Int = GatewayForegroundService.getGatewayConfig(KEY_MEMORY_MAX, "20").toIntOrNull() ?: 20
    fun setMaxCount(n: Int) = GatewayForegroundService.saveGatewayConfig(KEY_MEMORY_MAX, n.toString())
    
    /** 保存记忆（追加一条） */
    fun addMemory(role: String, content: String) {
        val list = getAll().toMutableList()
        list.add(MemoryItem(role = role, content = content))
        // 只保留最新的N条
        val max = getMaxCount()
        val trimmed = if (list.size > max) list.takeLast(max) else list
        saveAll(trimmed)
    }
    
    /** 获取所有记忆 */
    fun getAll(): List<MemoryItem> {
        try {
            val str = GatewayForegroundService.getGatewayConfig(KEY_MEMORY, "[]")
            if (str.isBlank()) return emptyList()
            return json.decodeFromString<List<MemoryItem>>(str)
        } catch (_: Exception) { return emptyList() }
    }
    
    /** 清空记忆 */
    fun clear() = GatewayForegroundService.saveGatewayConfig(KEY_MEMORY, "[]")
    
    /** 导出记忆文本 */
    fun exportAsText(): String = getAll().joinToString("\n") { "${it.role}: ${it.content}" }
    
    private fun saveAll(list: List<MemoryItem>) {
        try {
            GatewayForegroundService.saveGatewayConfig(KEY_MEMORY, json.encodeToString(list))
        } catch (_: Exception) {}
    }
    
    @kotlinx.serialization.Serializable
    data class MemoryItem(val role: String, val content: String)
}
/**
 * 9维能力管理器 — 权威标签来源
 * 支持：tool_call / vision / thinking / audio_in / audio_out / video / image_gen / embeddings / realtime
 */
object ModelCapabilityManager {
    private const val KEY_CAPABILITIES = "capabilities_json_v2"
    private const val KEY_AUTO_DETECT = "capabilities_auto_detect"
    private val json = Json { ignoreUnknownKeys = true }

    // 能力字段缩写映射（节省存储）
    private val KEY_MAP = mapOf(
        "tool_call" to "t", "vision" to "v", "thinking" to "th",
        "audio_in" to "ai", "audio_out" to "ao", "video" to "vi",
        "image_gen" to "ig", "embeddings" to "em", "realtime" to "rt"
    )

    /** 获取模型9维能力（兼容旧 Triple 接口） */
    fun getCapabilities(modelId: String): Triple<Boolean, Boolean, Boolean> {
        val caps = loadModel(modelId)
        return Triple(caps.toolCall, caps.vision, caps.imageGen)
    }

    /** 获取完整9维能力 */
    fun getFullCapabilities(modelId: String): com.qtwl.gateway.data.model.ModelCapabilities {
        return loadModel(modelId)
    }

    /** 合并本地标签 + 远程 /v1/models 返回的 capabilities 字段 */
    fun mergeRemoteCapabilities(modelId: String, remoteCaps: List<String>) {
        val current = loadModel(modelId)
        val merged = com.qtwl.gateway.data.model.ModelCapabilities.fromKeys(remoteCaps)
        // 远程为权威，覆盖本地
        saveModel(modelId, merged)
    }

    /** 从本地 assets/model_capabilities.json 加载兜底标签 */
    fun loadFallback(context: android.content.Context) {
        try {
            val jsonStr = context.assets.open("model_capabilities.json").bufferedReader().readText()
            val map = json.parseToJsonElement(jsonStr).jsonObject
            val all = loadAll().toMutableMap()
            for ((modelId, capArray) in map.entries) {
                val keys = capArray.jsonArray.map { it.jsonPrimitive.content }
                all[modelId] = com.qtwl.gateway.data.model.ModelCapabilities.fromKeys(keys)
            }
            saveAll(all)
        } catch (e: Exception) {
            // 静默
        }
    }

    /** 设置模型能力 */
    fun setCapabilities(modelId: String, toolCall: Boolean, vision: Boolean, imageGen: Boolean) {
        val caps = com.qtwl.gateway.data.model.ModelCapabilities(toolCall = toolCall, vision = vision, imageGen = imageGen)
        saveModel(modelId, caps)
    }

    // ── 探测（后台静默） ──────────────────────────────

    fun probeModel(modelId: String, resolvedUrl: String, apiKey: String?, chatPath: String? = null) {
        val current = loadModel(modelId)
        // 简单探测工具和视觉
        var tools = current.toolCall
        var vision = current.vision
        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(3000, TimeUnit.MILLISECONDS)
                .readTimeout(3000, TimeUnit.MILLISECONDS)
                .build()
            val baseUrl = resolvedUrl.trimEnd('/')
            val ct = "application/json".toMediaType()
            val path = chatPath?.let { if (it.startsWith("/")) it else "/$it" } ?: "/v1/chat/completions"
            if (!tools) {
                val body = """{"model":"$modelId","messages":[{"role":"user","content":"hi"}],"tools":[{"type":"function","function":{"name":"t","description":"t","parameters":{"type":"object","properties":{}}}}],"max_tokens":1}"""
                val resp = client.newCall(okhttp3.Request.Builder().url("$baseUrl$path").post(body.toByteArray().toRequestBody(ct)).apply { if (!apiKey.isNullOrBlank()) header("Authorization", "Bearer $apiKey") }.build()).execute()
                tools = resp.code != 501 && resp.code != 404; resp.close()
            }
            if (!vision) {
                val body = """{"model":"$modelId","messages":[{"role":"user","content":[{"type":"text","text":"hi"},{"type":"image_url","image_url":{"url":"data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="}}]}],"max_tokens":1}"""
                val resp = client.newCall(okhttp3.Request.Builder().url("$baseUrl$path").post(body.toByteArray().toRequestBody(ct)).apply { if (!apiKey.isNullOrBlank()) header("Authorization", "Bearer $apiKey") }.build()).execute()
                vision = resp.code != 501 && resp.code != 404 && resp.code != 400; resp.close()
            }
        } catch (_: Exception) { }
        if (tools != current.toolCall || vision != current.vision) {
            saveModel(modelId, current.copy(toolCall = tools, vision = vision))
        }
    }

    // ── 持久化 ──────────────────────────────────────────

    private fun loadModel(modelId: String): com.qtwl.gateway.data.model.ModelCapabilities {
        val all = loadAll()
        return all[modelId] ?: com.qtwl.gateway.data.model.ModelCapabilities()
    }

    private fun saveModel(modelId: String, caps: com.qtwl.gateway.data.model.ModelCapabilities) {
        val all = loadAll().toMutableMap()
        all[modelId] = caps
        saveAll(all)
    }

    private fun loadAll(): Map<String, com.qtwl.gateway.data.model.ModelCapabilities> {
        try {
            val str = GatewayForegroundService.getGatewayConfig(KEY_CAPABILITIES, "{}")
            if (str.isBlank()) return emptyMap()
            val obj = json.parseToJsonElement(str).jsonObject
            return obj.entries.associate { (k, v) ->
                val j = v.jsonObject
                k to com.qtwl.gateway.data.model.ModelCapabilities(
                    toolCall   = j["t"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
                    vision     = j["v"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
                    thinking   = j["th"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
                    audioIn    = j["ai"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
                    audioOut   = j["ao"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
                    video      = j["vi"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
                    imageGen   = j["ig"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
                    embeddings = j["em"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
                    realtime   = j["rt"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
                )
            }
        } catch (_: Exception) { return emptyMap() }
    }

    private fun saveAll(map: Map<String, com.qtwl.gateway.data.model.ModelCapabilities>) {
        try {
            val jsonObj = buildJsonObject {
                map.forEach { (k, v) ->
                    put(k, buildJsonObject {
                        put("t", v.toolCall); put("v", v.vision); put("th", v.thinking)
                        put("ai", v.audioIn); put("ao", v.audioOut); put("vi", v.video)
                        put("ig", v.imageGen); put("em", v.embeddings); put("rt", v.realtime)
                    })
                }
            }
            GatewayForegroundService.saveGatewayConfig(KEY_CAPABILITIES, jsonObj.toString())
        } catch (_: Exception) { }
    }

    // ==================== 后台静默探针系统 ====================
    private var probeJob: kotlinx.coroutines.Job? = null

    /** ★★ 启动后台静默探针：对所有启用模型进行能力探测，不打扰用户 ★★ */
    fun startSilentProbe(database: com.qtwl.gateway.data.db.AppDatabase, scope: kotlinx.coroutines.CoroutineScope) {
        probeJob?.cancel()
        probeJob = scope.launch {
            while (true) {
                try {
                    // 获取所有已启用的模型
                    val models = withContext(Dispatchers.IO) {
                        database.aiModelDao().getEnabledModelsList().filter { it.isEnabled }
                    }
                    val providers = withContext(Dispatchers.IO) {
                        database.providerDao().getAllProvidersOnce()
                    }
                    val providerMap = providers.associateBy { it.id }

                    for (model in models) {
                        val provider = providerMap[model.providerId] ?: continue
                        // 只探测未探全的模型
                        val (t, v, g) = getCapabilities(model.modelId)
                        if (t && v && g) continue
                        
                        probeModel(model.modelId, provider.resolvedBaseUrl, provider.apiKey, provider.chatPath)
                        kotlinx.coroutines.delay(500) // 每个模型间隔500ms，避免并发
                    }
                } catch (_: Exception) { }
                // 每次循环间隔 30 分钟
                kotlinx.coroutines.delay(30 * 60 * 1000L)
            }
        }
    }

    /** ★★ 停止后台静默探针 ★★ */
    fun stopSilentProbe() {
        probeJob?.cancel()
        probeJob = null
    }
}
private fun savePipelineCache(items: List<PipelineTestItem>) {
    try {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val str = json.encodeToString(items)
        GatewayForegroundService.saveGatewayConfig("pipeline_cache", str)
    } catch (_: Exception) { }
}

/** ★ 从 SharedPreferences 加载缓存的测速结果 */
private fun loadPipelineCache(): List<PipelineTestItem> {
    return try {
        val str = GatewayForegroundService.getGatewayConfig("pipeline_cache", "")
        if (str.isBlank()) return emptyList()
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        json.decodeFromString<List<PipelineTestItem>>(str)
    } catch (_: Exception) { emptyList() }
}

class GatewayViewModel(
    private val database: AppDatabase
) : ViewModel() {

    // ==================== JSON 解析器 ====================
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    // ==================== 多实例管理 ====================
    private val _instances = MutableStateFlow<List<String>>(emptyList())
    val instances: StateFlow<List<String>> = _instances.asStateFlow()
    
    private val _currentInstance = MutableStateFlow("default")
    val currentInstance: StateFlow<String> = _currentInstance.asStateFlow()
    
    private val _modelMap = MutableStateFlow<String>("")
    val modelMap: StateFlow<String> = _modelMap.asStateFlow()
    
    private val _logServer = MutableStateFlow<String>("")
    val logServer: StateFlow<String> = _logServer.asStateFlow()
    
    init {
        // 初始化实例列表
        _instances.value = GatewayForegroundService.getInstances()
        _currentInstance.value = GatewayForegroundService.getCurrentInstance()
        _modelMap.value = GatewayForegroundService.getModelMap(_currentInstance.value)
        _logServer.value = GatewayForegroundService.getLogServer(_currentInstance.value)
        // ★★ 启动低频会话快照刷新（3秒），避免跑马灯高频重组 ★★
        viewModelScope.launch {
            while (true) {
                refreshQuickLiveSessions()
                delay(3000)
            }
        }
        // ★★ 自动故障转移/自动测速状态持久化恢复：启动APP时若上次是开启，自动恢复自动测速 ★★
        if (GatewayForegroundService.getAutoFailover()) {
            startPipelineTest()
        }
    }
    
    /** 刷新实例列表 */
    fun refreshInstances() {
        _instances.value = GatewayForegroundService.getInstances()
        _currentInstance.value = GatewayForegroundService.getCurrentInstance()
        _modelMap.value = GatewayForegroundService.getModelMap(_currentInstance.value)
        _logServer.value = GatewayForegroundService.getLogServer(_currentInstance.value)
    }
    
    /** 添加实例 */
    fun addInstance(name: String) {
        GatewayForegroundService.addInstance(name)
        refreshInstances()
        _snackbarMessage.value = "✅ 实例 [$name] 已创建"
    }
    
    /** 删除实例 */
    fun removeInstance(name: String) {
        GatewayForegroundService.removeInstance(name)
        refreshInstances()
        _snackbarMessage.value = "🗑️ 实例 [$name] 已删除"
    }
    
    /** 切换实例 */
    fun switchInstance(name: String) {
        GatewayForegroundService.saveCurrentInstance(name)
        refreshInstances()
        _snackbarMessage.value = "🔄 已切换到实例 [$name]"
    }
    
    /** 保存 modelMap */
    fun saveModelMap(map: String) {
        GatewayForegroundService.saveModelMap(_currentInstance.value, map)
        _modelMap.value = map
    }
    
    /** 保存远程日志地址 */
    fun saveLogServer(url: String) {
        GatewayForegroundService.saveLogServer(_currentInstance.value, url)
        _logServer.value = url
    }

    // ==================== 服务商相关 ====================
    val providers: StateFlow<List<Provider>> = database.providerDao()
        .getAllProviders()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ==================== 模型相关 ====================
    /** 所有模型（仅来自已启用服务商） */
    val models: StateFlow<List<AiModel>> = database.aiModelDao()
        .getAllModelsWithEnabledProviders()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 仅返回已启用的模型——供聊天界面和网关API使用 */
    val enabledModels: StateFlow<List<AiModel>> = database.aiModelDao()
        .getEnabledModels()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ==================== 网关服务状态 ====================
    private val _serviceRunning = MutableStateFlow(false)
    val serviceRunning: StateFlow<Boolean> = _serviceRunning.asStateFlow()

    // ==================== 网关端口配置 ====================
    private val _gatewayPort = MutableStateFlow(8889)
    val gatewayPort: StateFlow<Int> = _gatewayPort.asStateFlow()

    // ==================== 代理配置（多代理列表支持）====================

    /**
     * 单个代理配置条目
     */
    @kotlinx.serialization.Serializable
    data class ProxyProfile(
        val id: String = java.util.UUID.randomUUID().toString().take(8),  // 唯一标识
        val name: String = "新代理",                                       // 代理名称
        val type: String = "HTTP",                                         // HTTP / SOCKS5 / VMESS / SS / VLESS
        val host: String = "",
        val port: Int = 1080,
        val username: String = "",
        val password: String = "",
        val enabled: Boolean = false,
        val extraJson: String = ""                                         // 协议扩展参数（vmess/ss/vless的加密、path、security等）
    )

    /** 所有代理列表 */
    private val _proxyProfiles = MutableStateFlow<List<ProxyProfile>>(emptyList())
    val proxyProfiles: StateFlow<List<ProxyProfile>> = _proxyProfiles.asStateFlow()

    /** 当前激活的代理 ID（null 表示无代理） */
    private val _activeProxyId = MutableStateFlow<String?>(null)
    val activeProxyId: StateFlow<String?> = _activeProxyId.asStateFlow()

    /** 代理全局开关（是否启用代理加速） */
    private val _proxyEnabled = MutableStateFlow(false)
    val proxyEnabled: StateFlow<Boolean> = _proxyEnabled.asStateFlow()

    // ==================== 流式输出开关 ====================
    private val _streamEnabled = MutableStateFlow(true)
    val streamEnabled: StateFlow<Boolean> = _streamEnabled.asStateFlow()

    fun setStreamEnabled(enabled: Boolean) {
        _streamEnabled.value = enabled
        GatewayForegroundService.saveGatewayConfig("stream_enabled", enabled.toString())
    }

    // ==================== 当前活跃节点名称（通知栏动态指示灯用） ====================
    @Volatile
    var activeNodeName: String = ""
        private set

    fun setActiveNodeName(name: String) {
        activeNodeName = name
    }
    private val _aboutClickCount = MutableStateFlow(0)
    val aboutClickCount: StateFlow<Int> = _aboutClickCount.asStateFlow()
    private var _lastClickTime = 0L

    // ==================== 对话框/表单状态 ====================
    private val _showAddProviderDialog = MutableStateFlow(false)
    val showAddProviderDialog: StateFlow<Boolean> = _showAddProviderDialog.asStateFlow()

    private val _showEditProviderDialog = MutableStateFlow<Provider?>(null)
    val showEditProviderDialog: StateFlow<Provider?> = _showEditProviderDialog.asStateFlow()

    private val _showProxyConfigDialog = MutableStateFlow(false)
    val showProxyConfigDialog: StateFlow<Boolean> = _showProxyConfigDialog.asStateFlow()

    private val _showEditModelDialog = MutableStateFlow<AiModel?>(null)
    val showEditModelDialog: StateFlow<AiModel?> = _showEditModelDialog.asStateFlow()

    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()

    // ==================== 模型同步状态 ====================
    private val _syncingProviderId = MutableStateFlow<Long?>(null)
    val syncingProviderId: StateFlow<Long?> = _syncingProviderId.asStateFlow()

    private val _syncResult = MutableStateFlow<String?>(null)
    val syncResult: StateFlow<String?> = _syncResult.asStateFlow()

    // ==================== 聊天功能 ====================

    /** 当前选中的对话 */
    private val _currentConversation = MutableStateFlow<Conversation?>(null)
    val currentConversation: StateFlow<Conversation?> = _currentConversation.asStateFlow()

    /** 所有对话列表 */
    val conversations: StateFlow<List<Conversation>> = database.conversationDao()
        .getAllConversations()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 当前对话的消息列表 */
    private val _currentMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val currentMessages: StateFlow<List<ChatMessage>> = _currentMessages.asStateFlow()

    /** 输入框内容 */
    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    /** 是否正在发送消息（防止重复提交） */
    private val _isSending = MutableStateFlow(false)
    val isSending: StateFlow<Boolean> = _isSending.asStateFlow()

    /** ★★ 当前发送协程任务，用于停止生成 ★★ */
    private var sendJob: kotlinx.coroutines.Job? = null

    /** 停止当前正在生成的AI回复（只取消协程，不复位 _isSending——等 onClosed/onFailure 结束才恢复） */
fun cancelSend() {
    sendJob?.cancel()
    // _isSending 保持不变，让 UI 继续显示停止按钮，直到流真正结束
}

    /** 错误信息 */
    private val _chatError = MutableStateFlow<String?>(null)
    val chatError: StateFlow<String?> = _chatError.asStateFlow()

    /** 选中的模型 */
    private val _selectedModel = MutableStateFlow<AiModel?>(null)
    val selectedModel: StateFlow<AiModel?> = _selectedModel.asStateFlow()

    // ==================== Token 统计 ====================
val allTokenUsage: StateFlow<List<TokenUsage>> = database.tokenUsageDao()
    .getAllUsage()
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ==================== 按API密钥统计用量 ====================
    private val _apiKeyUsageRows = MutableStateFlow<List<ApiKeyUsageRow>>(emptyList())
    val apiKeyUsageRows: StateFlow<List<ApiKeyUsageRow>> = _apiKeyUsageRows.asStateFlow()
    fun loadApiKeyUsage() {
        viewModelScope.launch(Dispatchers.IO) {
            _apiKeyUsageRows.value = database.tokenUsageDao().getUsageByApiKey()
        }
    }

    // ==================== 自定义路由规则 ====================
    /** 加载所有路由规则 */
    suspend fun getAllRoutingRules(): List<com.qtwl.gateway.data.model.RoutingRule> =
        database.routingRuleDao().getAllRules().first()

    /** 保存路由规则 */
    fun saveRoutingRule(rule: com.qtwl.gateway.data.model.RoutingRule) {
        viewModelScope.launch(Dispatchers.IO) {
            database.routingRuleDao().insert(rule)
            com.qtwl.gateway.gateway.RoutingRuleManager.invalidateCache()
        }
    }

    /** 更新路由规则 */
    fun updateRoutingRule(rule: com.qtwl.gateway.data.model.RoutingRule) {
        viewModelScope.launch(Dispatchers.IO) {
            database.routingRuleDao().update(rule)
            com.qtwl.gateway.gateway.RoutingRuleManager.invalidateCache()
        }
    }

    /** 删除路由规则 */
    fun deleteRoutingRule(rule: com.qtwl.gateway.data.model.RoutingRule) {
        viewModelScope.launch(Dispatchers.IO) {
            database.routingRuleDao().delete(rule)
            com.qtwl.gateway.gateway.RoutingRuleManager.invalidateCache()
        }
    }

    /** 切换规则启用状态 */
    fun setRoutingRuleEnabled(id: Long, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            database.routingRuleDao().setEnabled(id, enabled)
            com.qtwl.gateway.gateway.RoutingRuleManager.invalidateCache()
        }
    }

    /** 清空所有路由规则 */
    fun clearAllRoutingRules() {
        viewModelScope.launch(Dispatchers.IO) {
            database.routingRuleDao().clearAll()
            com.qtwl.gateway.gateway.RoutingRuleManager.invalidateCache()
        }
    }


private val _totalPromptTokens = MutableStateFlow(0L)
val totalPromptTokens: StateFlow<Long> = _totalPromptTokens.asStateFlow()

private val _totalCompletionTokens = MutableStateFlow(0L)
val totalCompletionTokens: StateFlow<Long> = _totalCompletionTokens.asStateFlow()

private val _totalTokensAll = MutableStateFlow(0L)
val totalTokensAll: StateFlow<Long> = _totalTokensAll.asStateFlow()

/** 刷新 Token 统计数据 */
fun refreshTokenStats() {
    viewModelScope.launch {
        try {
            val prompt = database.tokenUsageDao().getTotalPromptTokens()
            val completion = database.tokenUsageDao().getTotalCompletionTokens()
            val total = database.tokenUsageDao().getTotalTokens()
            _totalPromptTokens.value = prompt
            _totalCompletionTokens.value = completion
            _totalTokensAll.value = total
        } catch (_: Exception) { }
    }
}

    // ==================== 测速历史趋势图 ====================
    /** 测速历史 DAO */
    val speedHistoryDao: SpeedHistoryDao = database.speedHistoryDao()

    /** 当前选中的模型 key（用于趋势图） */
    private val _selectedHistoryModelKey = MutableStateFlow<String?>(null)
    val selectedHistoryModelKey: StateFlow<String?> = _selectedHistoryModelKey.asStateFlow()

    /** 当前选中模型的测速历史 */
    private val _selectedModelHistory = MutableStateFlow<List<SpeedHistory>>(emptyList())
    val selectedModelHistory: StateFlow<List<SpeedHistory>> = _selectedModelHistory.asStateFlow()

    /** 记录一条测速历史 */
    fun recordSpeedHistory(modelKey: String, modelName: String, providerId: Long, metrics: SpeedMetrics) {
        viewModelScope.launch {
            try {
                val success = metrics.ttftMs > 0
                val history = SpeedHistory(
                    modelKey = modelKey,
                    modelName = modelName,
                    providerId = providerId,
                    ttftMs = if (success) metrics.ttftMs else -1,
                    tps = if (success) metrics.tps else 0.0,
                    totalMs = if (success) metrics.totalMs else -1,
                    success = success,
                    measuredAt = metrics.measuredAt
                )
                withContext(Dispatchers.IO) {
                    database.speedHistoryDao().insert(history)
                    // 自动清理超过7天的旧记录
                    val weekAgo = System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L
                    database.speedHistoryDao().deleteOlderThan(weekAgo)
                }
                // 如果当前已选中该模型，刷新历史
                if (_selectedHistoryModelKey.value == modelKey) {
                    loadModelHistory(modelKey)
                }
            } catch (_: Exception) { }
        }
    }

    /** 加载指定模型的测速历史 */
    fun loadModelHistory(modelKey: String) {
        if (modelKey.isBlank()) {
            _selectedHistoryModelKey.value = null
            _selectedModelHistory.value = emptyList()
            return
        }
        _selectedHistoryModelKey.value = modelKey
        viewModelScope.launch {
            try {
                val history = withContext(Dispatchers.IO) {
                    database.speedHistoryDao().getHistoryByModelOnce(modelKey)
                }
                _selectedModelHistory.value = history
            } catch (_: Exception) { }
        }
    }

    /** 获取所有模型的最新测速 */
    val latestSpeedHistory: StateFlow<List<SpeedHistory>> = database.speedHistoryDao()
        .getLatestEachModel()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ==================== 备份管理器 ====================
    private val backupManager = BackupManager(database)

    // ==================== 添加服务商表单 ====================
    
    /** 大模型服务商类型预设 */
    data class ProviderTypePreset(
        val displayName: String,      // 显示名称
        val defaultType: String,       // 类型标识
        val defaultBaseUrl: String,    // 默认基础地址
        val defaultPort: String,       // 默认端口
        val exampleApiKey: String,      // API Key 提示
        val defaultChatPath: String = "/v1/chat/completions",  // 聊天接口路径
        val defaultApiPath: String = "/v1/models"               // 模型列表接口路径
    )
    
companion object {
            val PROVIDER_TYPES = listOf(
                ProviderTypePreset(
                    displayName = "OpenAI",
                    defaultType = "OpenAI Compatible",
                    defaultBaseUrl = "https://api.openai.com",
                    defaultPort = "443",
                    exampleApiKey = "sk-..."
                ),
                ProviderTypePreset(
                    displayName = "Anthropic (Claude)",
                    defaultType = "Anthropic",
                    defaultBaseUrl = "https://api.anthropic.com",
                    defaultPort = "443",
                    exampleApiKey = "sk-ant-...",
                    defaultChatPath = "/v1/messages",
                    defaultApiPath = "/v1/models"
                ),
                ProviderTypePreset(
                    displayName = "Google (Gemini)",
                    defaultType = "Google Gemini",
                    defaultBaseUrl = "https://generativelanguage.googleapis.com",
                    defaultPort = "443",
                    exampleApiKey = "AIza..."
                ),
                ProviderTypePreset(
                    displayName = "DeepSeek",
                    defaultType = "OpenAI Compatible",
                    defaultBaseUrl = "https://api.deepseek.com",
                    defaultPort = "443",
                    exampleApiKey = "sk-..."
                ),
                ProviderTypePreset(
                    displayName = "Qwen (通义千问)",
                    defaultType = "OpenAI Compatible",
                    defaultBaseUrl = "https://dashscope.aliyuncs.com/compatible-mode",
                    defaultPort = "443",
                    exampleApiKey = "sk-..."
                ),
                ProviderTypePreset(
                    displayName = "Groq",
                    defaultType = "OpenAI Compatible",
                    defaultBaseUrl = "https://api.groq.com/openai",
                    defaultPort = "443",
                    exampleApiKey = "gsk-..."
                ),
                ProviderTypePreset(
                    displayName = "OpenRouter",
                    defaultType = "OpenAI Compatible",
                    defaultBaseUrl = "https://openrouter.ai/api",
                    defaultPort = "443",
                    exampleApiKey = "sk-or-..."
                ),
                ProviderTypePreset(
                    displayName = "Together",
                    defaultType = "OpenAI Compatible",
                    defaultBaseUrl = "https://api.together.xyz",
                    defaultPort = "443",
                    exampleApiKey = "api-..."
                ),
                ProviderTypePreset(
                    displayName = "AI.JILI5",
                    defaultType = "OpenAI Compatible",
                    defaultBaseUrl = "https://ai.jili5.cn",
                    defaultPort = "443",
                    exampleApiKey = ""
                ),
                ProviderTypePreset(
                    displayName = "Ollama (本地)",
                    defaultType = "Ollama",
                    defaultBaseUrl = "http://localhost",
                    defaultPort = "11434",
                    exampleApiKey = ""
                ),
                ProviderTypePreset(
                    displayName = "Custom (自定义)",
                    defaultType = "Custom",
                    defaultBaseUrl = "",
                    defaultPort = "",
                    exampleApiKey = ""
                )
            )
        }

        private val jsonStatic = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

        fun parseProxyList(jsonStr: String): List<ProxyProfile> {
            return try {
                jsonStatic.decodeFromString<List<ProxyProfile>>(jsonStr)
            } catch (_: Exception) { emptyList() }
        }

    data class ProviderForm(
    val name: String = "",
    val type: String = "OpenAI Compatible",
    val baseUrl: String = "",
    val port: String = "",
    val apiKey: String = "",
    val orderIndex: Int = 0,
    val chatPath: String = "", // 聊天接口路径（留空=自动拼接/v1/chat/completions）
    val apiPath: String = "/v1/models" // 模型列表接口路径
)

    private val _providerForm = MutableStateFlow(ProviderForm())
    val providerForm: StateFlow<ProviderForm> = _providerForm.asStateFlow()

    // ==================== 初始化 ====================
    init {
        refreshTokenStats()
        loadProxyListFromPrefs()
        // ★★ 智能检测网关服务是否真正在运行 ★★
        val spRunning = GatewayForegroundService.isServiceRunning
        var actualRunning = spRunning
        if (spRunning) {
            // 双重确认：检查前台服务是否真的存活
            try {
                val ctx = GatewayApplication.getInstance()
                val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                val runningServices = am.getRunningServices(Int.MAX_VALUE)
                val isAlive = runningServices.any { it.service.shortClassName.contains("GatewayForegroundService") }
                if (!isAlive) {
                    // 服务已死但标记为运行中 → 修正状态
                    actualRunning = false
                    GatewayForegroundService.isServiceRunning = false
                    GatewayForegroundService.saveGatewayRunningState(false)
                }
            } catch (_: Exception) { }
        }
        _serviceRunning.value = actualRunning
        // ★★ 加载上次测速缓存到排行榜，同步本地模型（去重+移除不存在的+添加新模型）★★
        val cached = loadPipelineCache()
        if (cached.isNotEmpty()) {
            viewModelScope.launch {
                val enabledList = withContext(Dispatchers.IO) {
                    database.aiModelDao().getEnabledModelsList().filter { it.isEnabled }
                }
                val enabledByKey = enabledList.associateBy { it.routeKey }
                val cachedByKey = linkedMapOf<String, PipelineTestItem>()

                // Legacy cache entries without a provider are migrated only if unique.
                for (item in cached) {
                    val model = if (item.providerId > 0L) {
                        enabledByKey[item.selectionKey]
                    } else {
                        enabledList.filter { it.modelId == item.modelId }.singleOrNull()
                    } ?: continue
                    cachedByKey[model.routeKey] = item.copy(
                        modelName = model.customAlias.ifBlank { model.displayName },
                        providerId = model.providerId,
                    )
                }

                val merged = enabledList.map { model ->
                    cachedByKey[model.routeKey] ?: PipelineTestItem(
                        modelId = model.modelId,
                        modelName = model.customAlias.ifBlank { model.displayName },
                        providerId = model.providerId,
                        status = "⏳ 等待中",
                        latencyMs = 0,
                        isCurrent = false
                    )
                }
                _pipelineStatus.value = merged
                val rankedKeys = merged
                    .filter { it.status.startsWith("✅") || it.status.startsWith("❌") }
                    .sortedBy { it.latencyMs }
                    .map { it.selectionKey }
                com.qtwl.gateway.gateway.GatewayScheduler.pipelineSortedModelKeys =
                    rankedKeys.ifEmpty { merged.map { it.selectionKey } }
                savePipelineCache(merged)
            }
        }
        // ★★ 初始化时检查自动故障转移是否已开启，若是则自动启动接力测速 ★★
        // 已移除自动启动测速 — 用户需要测速时手动点击"开始测速"按钮
        // 保留cache加载，让排行榜显示已有的缓存数据
        // ★★ 启动后台静默探针（30分钟循环，静默检测模型能力）★★
        ModelCapabilityManager.startSilentProbe(database, viewModelScope)
        // ★★ 测速开关 — 不自启测速，让用户手动点击"开始测速"按钮 ★★
    }

    // ========== 服务生命周期控制 ==========

    /** 启动网关服务 */
    fun startGateway() {
        try {
            GatewayForegroundService.start()
            _serviceRunning.value = true
            GatewayForegroundService.isServiceRunning = true
            GatewayForegroundService.saveGatewayRunningState(true)
        } catch (e: Exception) {
            _snackbarMessage.value = "启动网关失败: ${e.message}"
        }
    }

    /** 停止网关服务 */
    fun stopGateway() {
        try {
            GatewayForegroundService.stop()
            _serviceRunning.value = false
            GatewayForegroundService.isServiceRunning = false
            GatewayForegroundService.saveGatewayRunningState(false)
        } catch (e: Exception) {
            _snackbarMessage.value = "停止网关失败: ${e.message}"
        }
    }

    /** 切换网关状态 */
    fun toggleGateway() {
        if (_serviceRunning.value) stopGateway() else startGateway()
    }

    // ========== 网关端口配置 ==========

    /** 设置网关端口 */
    fun setGatewayPort(port: Int) {
        if (port in 1..65535) {
            _gatewayPort.value = port
            GatewayForegroundService.saveGatewayPort(port)
            _snackbarMessage.value = "✅ 网关端口已设置为 $port"
        } else {
            _snackbarMessage.value = "⚠️ 端口号范围：1-65535"
        }
    }

    /** 获取当前网关端口 */
    fun getGatewayPort(): Int = _gatewayPort.value

    // ========== 代理管理（多代理列表 CRUD）==========

    /** 添加新代理 */
    fun addProxy(profile: ProxyProfile) {
        val list = _proxyProfiles.value.toMutableList()
        list.add(profile)
        _proxyProfiles.value = list
        saveProxyListToPrefs()
        _snackbarMessage.value = "✅ 代理「${profile.name}」已添加"
    }

    /** 更新代理 */
    fun updateProxy(profile: ProxyProfile) {
        val list = _proxyProfiles.value.toMutableList()
        val index = list.indexOfFirst { it.id == profile.id }
        if (index >= 0) {
            list[index] = profile
            _proxyProfiles.value = list
            saveProxyListToPrefs()
            // 如果当前激活的就是这个代理，重新应用
            if (_activeProxyId.value == profile.id && _proxyEnabled.value) {
                applyProxyToNetwork(profile)
            }
            _snackbarMessage.value = "✅ 代理「${profile.name}」已更新"
        }
    }

    /** 删除代理 */
    fun deleteProxy(profile: ProxyProfile) {
        val list = _proxyProfiles.value.toMutableList()
        list.removeAll { it.id == profile.id }
        _proxyProfiles.value = list
        // 如果删除的是当前激活的代理，停用代理
        if (_activeProxyId.value == profile.id) {
            _activeProxyId.value = null
            _proxyEnabled.value = false
            UpstreamClient.setProxy(null)
        }
        saveProxyListToPrefs()
        _snackbarMessage.value = "🗑️ 代理「${profile.name}」已删除"
    }

    /** 启用/禁用某个代理（选中即激活 — 互斥：开启一个时自动关闭其他） */
    fun toggleProxyEnabled(profile: ProxyProfile) {
        val newEnabled = !profile.enabled

        if (newEnabled) {
            // ★ 互斥逻辑：把其他所有代理设为 disabled，当前设为 enabled
            val list = _proxyProfiles.value.toMutableList()
            val newList = list.map { it.copy(enabled = it.id == profile.id) }
            _proxyProfiles.value = newList
            saveProxyListToPrefs()

            _activeProxyId.value = profile.id
            _proxyEnabled.value = true
            applyProxyToNetwork(profile.copy(enabled = true))
            _snackbarMessage.value = "🚀 代理「${profile.name}」已启用（${profile.type}）"
        } else {
            if (_activeProxyId.value == profile.id) {
                _activeProxyId.value = null
                _proxyEnabled.value = false
                UpstreamClient.setProxy(null)
            }
            val list = _proxyProfiles.value.toMutableList()
            val newList = list.map { it.copy(enabled = it.id != profile.id && it.enabled) }
            _proxyProfiles.value = newList
            saveProxyListToPrefs()
            _snackbarMessage.value = "🔌 代理「${profile.name}」已关闭"
        }
    }

    /** 手动切换代理加速开关（如果当前有激活的代理则关闭，否则开启第一个启用的代理） */
    fun toggleProxy() {
        val activeId = _activeProxyId.value
        if (activeId != null && _proxyEnabled.value) {
            // 有关闭：停用代理
            _activeProxyId.value = null
            _proxyEnabled.value = false
            UpstreamClient.setProxy(null)
            _snackbarMessage.value = "🔌 代理已关闭"
        } else {
            // 找第一个 enabled 的代理激活
            val firstEnabled = _proxyProfiles.value.firstOrNull { it.enabled }
            if (firstEnabled != null) {
                _activeProxyId.value = firstEnabled.id
                _proxyEnabled.value = true
                applyProxyToNetwork(firstEnabled)
                _snackbarMessage.value = "🚀 代理「${firstEnabled.name}」已启用"
            } else {
                _snackbarMessage.value = "⚠️ 没有可用的代理配置，请先添加代理"
            }
        }
    }

    /** 将代理配置应用到网络层 */
    private fun applyProxyToNetwork(profile: ProxyProfile) {
        try {
            val upstreamConfig = UpstreamClient.ProxyConfig(
                type = profile.type,
                host = profile.host,
                port = profile.port,
                username = profile.username,
                password = profile.password,
                enabled = true
            )
            UpstreamClient.setProxy(upstreamConfig)
        } catch (e: Exception) {
            _snackbarMessage.value = "⚠️ 代理配置错误: ${e.message}"
        }
    }

    /** 智能测速 — 仅支持 HTTP/HTTPS/SOCKS5 */
    fun testProxySpeed(profile: ProxyProfile) {
        viewModelScope.launch {
            try {
                _snackbarMessage.value = "⏳ 正在测试 ${profile.name}..."
                withContext(Dispatchers.IO) {
                    val upstreamConfig = UpstreamClient.ProxyConfig(
                        type = profile.type, host = profile.host, port = profile.port,
                        username = profile.username, password = profile.password, enabled = true
                    )
                    val tempClient = when (profile.type.uppercase()) {
                        "HTTP", "HTTPS" -> OkHttpClient.Builder()
                            .connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS)
                            .proxy(java.net.Proxy(java.net.Proxy.Type.HTTP, InetSocketAddress(profile.host, profile.port)))
                            .build()
                        "SOCKS5", "SOCKS" -> OkHttpClient.Builder()
                            .connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS)
                            .socketFactory(Socks5SocketFactory(profile.host, profile.port, profile.username, profile.password))
                            .build()
                        else -> { _snackbarMessage.value = "⚠️ 仅支持 HTTP/HTTPS/SOCKS5 测速"; return@withContext }
                    }

                    // ★★ 先测谷歌，通=海外，不通再测百度
                    var result = ""
                    try {
                        val start = System.currentTimeMillis()
                        val gOk = tempClient.newCall(okhttp3.Request.Builder().url("https://www.google.com/favicon.ico").build()).execute().isSuccessful
                        if (gOk) { result = "✅ ${profile.name}: ${System.currentTimeMillis() - start}ms (🌍 海外)" }
                    } catch (_: Exception) { }

                    if (result.isEmpty()) {
                        try {
                            val start = System.currentTimeMillis()
                            val bOk = tempClient.newCall(okhttp3.Request.Builder().url("https://www.baidu.com/favicon.ico").build()).execute().isSuccessful
                            if (bOk) { result = "✅ ${profile.name}: ${System.currentTimeMillis() - start}ms (🇨🇳 国内)" }
                        } catch (_: Exception) { }
                    }

                    if (result.isEmpty()) { result = "❌ ${profile.name}: 国内外均无法访问" }
                    _snackbarMessage.value = result
                }
            } catch (e: Exception) {
                _snackbarMessage.value = "❌ ${profile.name} 测速失败: ${e.localizedMessage ?: e.message}"
            }
        }
    }

    /** 判断 host 是否为国内 IP/域名（用于智能选择测速目标） */
    private fun isChineseHost(host: String): Boolean {
        // .cn 域名直接判定为国内
        if (host.endsWith(".cn")) return true
        // 常见国内 IP 段
        if (host.startsWith("10.") || host.startsWith("172.16.") || host.startsWith("192.168.")) return true
        // 尝试匹配私有IP段
        val ipv4Parts = host.split(".").mapNotNull { it.toIntOrNull() }
        if (ipv4Parts.size == 4) {
            val first = ipv4Parts[0]
            // 国内常见公网IP段
            if (first == 1 || first == 14 || first == 27 || first == 36 || first == 39 ||
                first == 42 || first == 49 || first == 58 || first == 59 || first == 60 ||
                first == 61 || first == 101 || first == 103 || first == 106 || first == 110 ||
                first == 111 || first == 112 || first == 113 || first == 114 || first == 115 ||
                first == 116 || first == 117 || first == 118 || first == 119 || first == 120 ||
                first == 121 || first == 122 || first == 123 || first == 124 || first == 125 ||
                first == 139 || first == 140 || first == 144 || first == 150 || first == 152 ||
                first == 153 || first == 157 || first == 158 || first == 159 || first == 160 ||
                first == 161 || first == 162 || first == 163 || first == 166 || first == 167 ||
                first == 168 || first == 169 || first == 170 || first == 171 || first == 172 ||
                first == 175 || first == 180 || first == 182 || first == 183 || first == 202 ||
                first == 203 || first == 210 || first == 211 || first == 218 || first == 219 ||
                first == 220 || first == 221 || first == 222 || first == 223) return true
        }
        return false
    }

    /** 从 SharedPreferences 加载代理列表 */
    fun loadProxyListFromPrefs() {
        try {
            val jsonStr = GatewayForegroundService.getProxyListJson()
            if (jsonStr.isNotBlank()) {
                val list = json.decodeFromString<List<ProxyProfile>>(jsonStr)
                _proxyProfiles.value = list
                // 恢复激活状态
                val enabledOne = list.firstOrNull { it.enabled }
                if (enabledOne != null) {
                    _activeProxyId.value = enabledOne.id
                    _proxyEnabled.value = true
                    applyProxyToNetwork(enabledOne)
                }
            }
        } catch (_: Exception) {
            // 初次使用或格式异常，空列表
        }
    }

    /** 保存代理列表到 SharedPreferences */
    private fun saveProxyListToPrefs() {
        try {
            val jsonStr = json.encodeToString(_proxyProfiles.value)
            GatewayForegroundService.saveProxyListJson(jsonStr)
        } catch (_: Exception) { }
    }

    // ========== 订阅导入 & 剪贴板检测 ==========

    /** 解析并批量导入订阅链接 */
    fun importSubscription(url: String) {
        viewModelScope.launch {
            try {
                _snackbarMessage.value = "⏳ 正在获取订阅..."
                withContext(Dispatchers.IO) {
                    val request = okhttp3.Request.Builder().url(url).build()
                    val response = okhttp3.OkHttpClient.Builder()
                        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                        .build().newCall(request).execute()

                    if (!response.isSuccessful) {
                        _snackbarMessage.value = "❌ 订阅获取失败: HTTP ${response.code}"
                        return@withContext
                    }
                    val body = response.body?.string() ?: ""
                    if (body.isBlank()) {
                        _snackbarMessage.value = "❌ 订阅内容为空"
                        return@withContext
                    }

                    val parsed = com.qtwl.gateway.network.ProxyLinkParser.parseBatch(body)
                    if (parsed.isEmpty()) {
                        _snackbarMessage.value = "⚠️ 未解析到有效节点"
                        return@withContext
                    }

                    // 批量导入
                    val list = _proxyProfiles.value.toMutableList()
                    var added = 0
                    for (info in parsed) {
                        val exists = list.any { it.host == info.host && it.port == info.port }
                        if (!exists) {
                            val profile = ProxyProfile(
                                name = info.name, type = info.type, host = info.host, port = info.port,
                                enabled = false
                            )
                            list.add(profile)
                            added++
                        }
                    }
                    _proxyProfiles.value = list
                    saveProxyListToPrefs()
                    _snackbarMessage.value = "✅ 成功导入 $added 个节点"
                }
            } catch (e: Exception) {
                _snackbarMessage.value = "❌ 订阅导入失败: ${e.message}"
            }
        }
    }

    /** 解析单条代理链接并快速添加 */
    fun addProxyFromLink(link: String) {
        try {
            val info = com.qtwl.gateway.network.ProxyLinkParser.parse(link)
            if (info != null) {
                val profile = ProxyProfile(
                    name = info.name, type = info.type, host = info.host, port = info.port,
                    enabled = false
                )
                addProxy(profile)
            } else {
                _snackbarMessage.value = "❌ 无法解析该代理链接"
            }
        } catch (e: Exception) {
            _snackbarMessage.value = "❌ 解析失败: ${e.message}"
        }
    }

    /** 检测剪贴板中的代理链接/订阅链接（仅支持 HTTP/HTTPS/SOCKS5） */
    fun detectClipboardLink(clipText: String): String? {
        if (clipText.isBlank()) return null
        return when {
            clipText.startsWith("http://") && (clipText.contains("subscribe") || clipText.contains("sub") || clipText.contains("token=")) -> clipText
            clipText.startsWith("https://") && (clipText.contains("subscribe") || clipText.contains("sub") || clipText.contains("token=")) -> clipText
            else -> null
        }
    }
    fun bindBackgroundPermissions() {
        viewModelScope.launch {
            try {
                _snackbarMessage.value = "⏳ 正在申请后台权限..."
                withContext(Dispatchers.IO) {
                    try {
                        Runtime.getRuntime().exec(arrayOf(
                            "dumpsys", "deviceidle", "whitelist",
                            "+com.qtwl.gateway"
                        )).waitFor()
                    } catch (_: Exception) { }

                    try {
                        Runtime.getRuntime().exec(arrayOf(
                            "appops", "set", "com.qtwl.gateway",
                            "RUN_ANY_IN_BACKGROUND", "allow"
                        )).waitFor()
                    } catch (_: Exception) { }

                    try {
                        Runtime.getRuntime().exec(arrayOf(
                            "cmd", "deviceidle", "whitelist",
                            "+com.qtwl.gateway"
                        )).waitFor()
                    } catch (_: Exception) { }
                }
                _snackbarMessage.value = "✅ 后台权限已绑定！请确保已在系统设置中允许自启动"
            } catch (e: Exception) {
                _snackbarMessage.value = "⚠️ 部分权限申请失败（可能需要 Root）: ${e.message}"
            }
        }
    }

    // ========== 关于我们连点（改为打开代理管理页面）==========

    /** 重置关于我们连点计数 */
    fun resetAboutClickCount() {
        _aboutClickCount.value = 0
    }

    /** 增加关于我们点击计数，达到3次时打开代理管理页面 */
    fun incrementAboutClick() {
        val now = System.currentTimeMillis()
        // 3秒内连点3次才计数
        if (now - _lastClickTime > 3000) {
            _aboutClickCount.value = 1
        } else {
            val newCount = _aboutClickCount.value + 1
            _aboutClickCount.value = newCount
            if (newCount >= 3) {
                // 连点3次成功，打开代理管理页面
                showProxyConfig()
                resetAboutClickCount()
            }
        }
        _lastClickTime = now
    }

    // ========== 服务商 CRUD ==========

    fun showAddProvider() {
        _providerForm.value = ProviderForm()
        _showAddProviderDialog.value = true
    }
    
    /** 根据预设类型自动填充表单 */
    fun selectProviderType(index: Int) {
        if (index < 0 || index >= PROVIDER_TYPES.size) return
        val preset = PROVIDER_TYPES[index]
        _providerForm.value = ProviderForm(
            name = preset.displayName,
            type = preset.defaultType,
            baseUrl = preset.defaultBaseUrl,
            port = preset.defaultPort,
            apiKey = preset.exampleApiKey,
            chatPath = preset.defaultChatPath,
            apiPath = preset.defaultApiPath
        )
    }

    fun hideAddProvider() {
        _showAddProviderDialog.value = false
    }

    fun showEditProvider(provider: Provider) {
        _showEditProviderDialog.value = provider
    }

    fun hideEditProvider() {
        _showEditProviderDialog.value = null
    }

    /** 显示代理配置弹窗 */
    fun showProxyConfig() {
        _showProxyConfigDialog.value = true
    }

    /** 隐藏代理配置弹窗 */
    fun hideProxyConfig() {
        _showProxyConfigDialog.value = false
    }

    fun updateFormField(name: String, value: String) {
        _providerForm.value = when (name) {
            "name" -> _providerForm.value.copy(name = value)
            "type" -> _providerForm.value.copy(type = value)
            "baseUrl" -> _providerForm.value.copy(baseUrl = value)
            "port" -> _providerForm.value.copy(port = value)
            "apiKey" -> _providerForm.value.copy(apiKey = value)
            "chatPath" -> _providerForm.value.copy(chatPath = value)
            "apiPath" -> _providerForm.value.copy(apiPath = value)
            "orderIndex" -> _providerForm.value.copy(orderIndex = value.toIntOrNull() ?: 0)
            else -> _providerForm.value
        }
    }

    /** 从 Base URL 中自动提取端口号 */
    fun extractPortFromUrl(url: String): String {
        if (url.isBlank()) return ""
        return try {
            val regex = Regex("://[^:]+:(\\d+)")
            regex.find(url)?.groupValues?.getOrNull(1) ?: ""
        } catch (_: Exception) { "" }
    }

    /** 保存新服务商 */
    fun saveProvider() {
        val form = _providerForm.value
        if (form.name.isBlank()) {
            _snackbarMessage.value = "请输入服务商名称"
            return
        }
        if (form.baseUrl.isBlank()) {
            _snackbarMessage.value = "请输入 API 地址"
            return
        }

        viewModelScope.launch {
            try {
                database.providerDao().insert(
                    Provider(
                        name = form.name,
                        type = form.type,
                        baseUrl = form.baseUrl.trimEnd('/'),
                        port = form.port,
                        apiKey = form.apiKey.ifBlank { null },
                        orderIndex = form.orderIndex,
                        chatPath = form.chatPath.ifBlank { null }
                    )
                )
                _showAddProviderDialog.value = false
                _snackbarMessage.value = "✅ 服务商「${form.name}」添加成功"
            } catch (e: Exception) {
                _snackbarMessage.value = "添加失败: ${e.message}"
            }
        }
    }

    /** 更新服务商 */
    fun updateProvider(provider: Provider) {
        viewModelScope.launch {
            try {
                database.providerDao().update(provider)
                _showEditProviderDialog.value = null
                _snackbarMessage.value = "✅ 服务商已更新"
            } catch (e: Exception) {
                _snackbarMessage.value = "更新失败: ${e.message}"
            }
        }
    }

    /** 删除服务商 */
    fun deleteProvider(provider: Provider) {
        viewModelScope.launch {
            try {
                database.providerDao().delete(provider)
                // 同时删除该服务商下的所有模型
                database.aiModelDao().deleteByProvider(provider.id)
                _snackbarMessage.value = "🗑️ 服务商「${provider.name}」及关联模型已删除"
            } catch (e: Exception) {
                _snackbarMessage.value = "删除失败: ${e.message}"
            }
        }
    }

    /** 移动服务商排序（上下） */
    fun moveProvider(provider: com.qtwl.gateway.data.model.Provider, direction: Int) {
        viewModelScope.launch {
            try {
                val allProviders = database.providerDao().getAllProvidersOnce().sortedBy { it.orderIndex }
                val idx = allProviders.indexOfFirst { it.id == provider.id }
                if (idx < 0) return@launch
                val targetIdx = idx + direction
                if (targetIdx < 0 || targetIdx >= allProviders.size) return@launch
                val target = allProviders[targetIdx]
                // 交换 orderIndex
                database.providerDao().update(provider.copy(orderIndex = target.orderIndex))
                database.providerDao().update(target.copy(orderIndex = provider.orderIndex))
            } catch (_: Exception) { }
        }
    }

    /** 切换服务商启用状态 */
    fun toggleProviderEnabled(provider: Provider) {
        viewModelScope.launch {
            try {
                database.providerDao().update(
                    provider.copy(isEnabled = !provider.isEnabled)
                )
            } catch (e: Exception) {
                _snackbarMessage.value = "操作失败: ${e.message}"
            }
        }
    }

    // ========== 模型同步 ==========
/** 从服务商同步模型列表（支持平台预设模型） */
    fun syncModels(provider: Provider) {
        viewModelScope.launch {
            _syncingProviderId.value = provider.id
            _syncResult.value = null

            try {
                withContext(Dispatchers.IO) {
                    // ★★ 平台判断 & 预设模型 ★★
                    val platformType = provider.type
                    val presetModels: List<String>? = when {
                        platformType.contains("Claude", ignoreCase = true) -> listOf(
                            "claude-3-5-sonnet-latest", "claude-3-opus", "claude-3-haiku"
                        )
                        platformType.contains("Gemini", ignoreCase = true) -> listOf(
                            "gemini-2.5-pro", "gemini-2.5-flash", "gemini-1.5-pro"
                        )
                        else -> null
                    }

                    if (presetModels != null) {
                        // 使用预设模型列表
                        val existingModels = database.aiModelDao().getModelsByProvider(provider.id)
                        val enabledMap = existingModels.associate { it.modelId to it.isEnabled }

                        val models = presetModels.map { modelId ->
                            AiModel(
                                providerId = provider.id,
                                modelId = modelId,
                                displayName = modelId,
                                syncStatus = "Synced",
                                isEnabled = enabledMap[modelId] ?: true,
                                customAlias = ""
                            )
                        }
                        database.aiModelDao().deleteByProvider(provider.id)
                        database.aiModelDao().insertAll(models)
                        _syncResult.value = "✅ 已加载 ${presetModels.size} 个预设模型"
                        _snackbarMessage.value = "✅ 已加载 ${presetModels.size} 个预设模型"
                        return@withContext
                    }

                    // ★★ 其他平台：正常调用 fetchModels ★★
                    val resolvedUrl = provider.resolvedBaseUrl
                    val response = UpstreamClient.fetchModels(
                        baseUrl = resolvedUrl,
                        apiKey = provider.apiKey
                    )

                    if (!response.isSuccessful) {
                        val errorBody = response.body?.string() ?: "未知错误"
                        _syncResult.value = "❌ 同步失败 (${response.code}): $errorBody"
                        _snackbarMessage.value = "模型同步失败: HTTP ${response.code}"
                        _syncingProviderId.value = null
                        return@withContext
                    }

                    val responseBody = response.body?.string() ?: "{}"
                    val jsonObj = json.parseToJsonElement(responseBody).jsonObject
                    val dataArray = jsonObj["data"]?.jsonArray

                    if (dataArray == null) {
                        _syncResult.value = "❌ 响应中未找到模型列表"
                        _snackbarMessage.value = "模型同步失败: 接口返回格式异常"
                        _syncingProviderId.value = null
                        return@withContext
                    }

                    // 修复：同步模型时保留已有模型的启用状态
                    val existingModels = database.aiModelDao().getModelsByProvider(provider.id)
                    val enabledMap = existingModels.associate { it.modelId to it.isEnabled }

                    val models = dataArray.mapNotNull { element ->
                        val obj = element.jsonObject
                        val modelId = obj["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                        val displayName = obj["display_name"]?.jsonPrimitive?.content
                            ?: obj["id"]?.jsonPrimitive?.content
                            ?: modelId
                        AiModel(
                            providerId = provider.id,
                            modelId = modelId,
                            displayName = displayName,
                            syncStatus = "Synced",
                            isEnabled = enabledMap[modelId] ?: false,
                            customAlias = ""
                        )
                    }

                    if (models.isEmpty()) {
                        _syncResult.value = "⚠️ 服务商返回了空模型列表"
                        _snackbarMessage.value = "同步完成，但未找到模型"
                    } else {
                        database.aiModelDao().deleteByProvider(provider.id)
                        database.aiModelDao().insertAll(models)
                        _syncResult.value = "✅ 成功同步 ${models.size} 个模型"
                        _snackbarMessage.value = "✅ 已同步 ${models.size} 个模型"
                    }
                }
            } catch (e: Exception) {
                _syncResult.value = "❌ 同步出错: ${e.message}"
                _snackbarMessage.value = "模型同步失败: ${e.message}"
            } finally {
                _syncingProviderId.value = null
            }
        }
    }

    // ========== 聊天 - 会话管理 ==========

    /** 更新输入框文本 */
    fun updateInputText(text: String) {
        _inputText.value = text
    }

    /** 设置输入框文本（用于重发/编辑） */
    fun setInputText(text: String) {
        _inputText.value = text
    }

    /** 显示Snackbar信息 */
    fun showSnackbar(msg: String) {
        _snackbarMessage.value = msg
    }

    /** 更新消息内容（用于编辑消息后保存） */
    fun updateMessageContent(id: Long, content: String) {
        viewModelScope.launch {
            try {
                database.chatMessageDao().updateContent(id, content)
                // 刷新本地消息列表
                val current = _currentMessages.value.toMutableList()
                val idx = current.indexOfFirst { it.id == id }
                if (idx >= 0) {
                    current[idx] = current[idx].copy(content = content)
                    _currentMessages.value = current
                }
            } catch (e: Exception) {
                _snackbarMessage.value = "更新失败: ${e.message}"
            }
        }
    }

    /** 重生成最后一条AI消息 */
    fun regenerateLastMessage() {
        viewModelScope.launch {
            val msgs = _currentMessages.value
            if (msgs.size < 2) return@launch
            // 找到最后一条AI消息
            val lastAiIdx = msgs.lastIndexOf(msgs.lastOrNull { it.role == "assistant" })
            if (lastAiIdx < 0) return@launch
            // 删除最后一条AI消息
            val lastAi = msgs[lastAiIdx]
            try {
                database.chatMessageDao().deleteById(lastAi.id)
            } catch (_: Exception) { }
            // 从消息列表中移除
            val newMsgs = msgs.toMutableList()
            newMsgs.removeAt(lastAiIdx)
            _currentMessages.value = newMsgs
            // 重新发送（复用最后一条用户消息）
            sendMessage()
        }
    }
/** 选择模型（选中即启用，取消选中即暂停） */
fun selectModel(model: AiModel?) {
    _selectedModel.value = model
    // 选中模型时，自动启用该模型
    if (model != null && !model.isEnabled) {
        viewModelScope.launch {
            try {
                database.aiModelDao().update(model.copy(isEnabled = true))
            } catch (e: Exception) {
                _snackbarMessage.value = "启用模型失败: ${e.message}"
            }
        }
    }
}


    /** 切换模型启用/暂停状态 */
    fun toggleModelEnabled(model: AiModel) {
        viewModelScope.launch {
            try {
                val newEnabled = !model.isEnabled
                database.aiModelDao().update(
                    model.copy(isEnabled = newEnabled)
                )
                // 如果暂停的是当前选中的模型，清除选中状态
                if (_selectedModel.value?.id == model.id && !newEnabled) {
                    _selectedModel.value = null
                }
                _snackbarMessage.value = if (newEnabled) {
                    "✅ 模型已启用"
                } else {
                    "⏸️ 模型已暂停"
                }
            } catch (e: Exception) {
                _snackbarMessage.value = "操作失败: ${e.message}"
            }
        }
    }

    /** 打开模型编辑别名对话框 */
    fun showEditModelAlias(model: AiModel) {
        _showEditModelDialog.value = model
    }

    /** 隐藏模型编辑对话框 */
    fun hideEditModelAlias() {
        _showEditModelDialog.value = null
    }

    /** 手动添加模型（无需同步，直接写入数据库） */
    fun manualAddModel(providerId: Long, modelId: String, displayName: String, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 检查是否已存在
                val existing = database.aiModelDao().getModelByProviderAndId(providerId, modelId)
                if (existing != null) {
                    onResult(false, "模型 $modelId 已存在")
                    return@launch
                }
                val model = AiModel(
                    providerId = providerId,
                    modelId = modelId,
                    displayName = displayName.ifBlank { modelId },
                    syncStatus = "Manual",
                    isEnabled = true
                )
                database.aiModelDao().insert(model)
                onResult(true, "✅ 模型 $modelId 已添加")
            } catch (e: Exception) {
                onResult(false, "❌ 添加失败: ${e.message}")
            }
        }
    }

    /** 保存模型自定义别名 */
fun saveModelAlias(model: AiModel, alias: String) {
    viewModelScope.launch {
        try {
            database.aiModelDao().update(
                model.copy(customAlias = alias)
            )
            _snackbarMessage.value = if (alias.isNotBlank()) {
                "✅ 别名已更新: $alias"
            } else {
                "✅ 已恢复默认名称"
            }
        } catch (e: Exception) {
            _snackbarMessage.value = "❌ 别名保存失败: ${e.message}"
        }
    }
}

/** 切换模型是否走代理（按模型粒度控制代理） */
fun toggleModelProxy(model: AiModel) {
    viewModelScope.launch {
        try {
            val newUseProxy = !model.useProxy
            database.aiModelDao().update(model.copy(useProxy = newUseProxy))
            val status = if (newUseProxy) "🔄 走代理" else "🔗 直连"
            _snackbarMessage.value = "✅ ${model.displayName} 已切换为 $status"
        } catch (e: Exception) {
            _snackbarMessage.value = "❌ 模型代理配置失败: ${e.message}"
        }
    }
}

    /** 获取模型的显示名称（优先使用自定义别名） */
fun getDisplayModelName(model: AiModel): String {
    // ★★ qtai-sj 虚拟模型特殊显示
    if (model.modelId == "qtai-sj") {
        return localizeGeneratedName("🔄 自动化切换")
    }
    return if (model.customAlias.isNotBlank()) {
        "${localizeGeneratedName(model.displayName)} (${model.customAlias})"
    } else {
        localizeGeneratedName(model.displayName)
    }
    }

    /** 创建新对话 */
    fun createNewConversation() {
        viewModelScope.launch {
            val conversation = Conversation(
                title = "新对话",
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
            val id = database.conversationDao().insert(conversation)
            _currentConversation.value = conversation.copy(id = id)
            _currentMessages.value = emptyList()
            _chatError.value = null
        }
    }

    /** 选择对话 */
    fun selectConversation(conversation: Conversation) {
        _currentConversation.value = conversation
        _chatError.value = null
        // 加载该对话的消息
        viewModelScope.launch {
            val messages = database.chatMessageDao()
                .getMessagesByConversationList(conversation.id)
            _currentMessages.value = messages
        }
    }

    /** 删除对话 */
    fun deleteConversation(conversation: Conversation) {
        viewModelScope.launch {
            try {
                database.conversationDao().delete(conversation)
                if (_currentConversation.value?.id == conversation.id) {
                    _currentConversation.value = null
                    _currentMessages.value = emptyList()
                }
                _snackbarMessage.value = "🗑️ 对话已删除"
            } catch (e: Exception) {
                _snackbarMessage.value = "删除失败: ${e.message}"
            }
        }
    }

    /** 重命名对话 */
    fun renameConversation(id: Long, title: String) {
        viewModelScope.launch {
            try {
                database.conversationDao().updateTitle(id, title)
                val current = _currentConversation.value
                if (current?.id == id) {
                    _currentConversation.value = current.copy(title = title)
                }
            } catch (e: Exception) {
                _snackbarMessage.value = "重命名失败: ${e.message}"
            }
        }
    }

    // ========== 聊天 - 发送消息 ==========

    /** 发送聊天消息 */
    fun sendMessage() {
        val text = _inputText.value.trim()
        if (text.isBlank() || _isSending.value) return

        var model = _selectedModel.value
        if (model == null) {
            _chatError.value = "⚠️ 请先选择一个模型"
            return
        }
        
        // ★★ 对话绑定模型：不激进切换，仅 API 报错时才换 ★★
        val conv = _currentConversation.value
        val stableModel = model
        if (conv != null && stableModel != null && conv.modelId != stableModel.modelId) {
            kotlinx.coroutines.GlobalScope.launch {
                try {
                    database.conversationDao().update(conv.copy(modelId = stableModel.modelId))
                } catch (_: Exception) { }
            }
        }
        // ★★ 用的时候测：异步探针检测模型能力（不阻塞，结果缓存供后续使用）★★
        if (stableModel != null) {
            kotlinx.coroutines.GlobalScope.launch {
                try {
                    val provider = database.providerDao().getProviderById(stableModel.providerId)
                    if (provider != null) {
                        ModelCapabilityManager.probeModel(stableModel.modelId, provider.baseUrl.trimEnd('/'), provider.apiKey, provider.chatPath)
                    }
                } catch (_: Exception) { }
            }
        }
        
        // ★★ qtai-sj 大脑分析：不在此处做关键词匹配切换，交由 GatewayService 按排行榜选模型 ★★
        
        // ★★ 取消旧任务，启动新生成任务 ★★
        sendJob?.cancel()
        sendJob = viewModelScope.launch {
            _isSending.value = true
            _chatError.value = null

            // ★★ 候选列表（qtai-sj 故障转移用），提升作用域供 catch 访问 ★★
            var qtaiCandidates: List<Pair<Provider, String>> = emptyList()

            try {
                // 确保有当前对话
                val conversation = ensureConversation()

                // 1. 保存用户消息
                val userMsg = ChatMessage(
                    conversationId = conversation.id,
                    role = "user",
                    content = text,
                    modelId = model.modelId,
                    providerId = model.providerId
                )
                val userMsgId = database.chatMessageDao().insert(userMsg)
                val savedUserMsg = userMsg.copy(id = userMsgId)

                // 更新本地消息列表
                _currentMessages.value = _currentMessages.value + savedUserMsg
                _inputText.value = ""

                // 更新对话时间
                database.conversationDao().touchConversation(conversation.id)

 // 2. 获取服务商信息（qtai-sj 按强制池顺序构建候选列表）
                    // ★★ 候选列表：provider 对 (provider, modelId)，qtai-sj 按强制池顺序，故障时自动切换下一个 ★★
                    qtaiCandidates = if (model.modelId == "qtai-sj") {
                        val allEnabledModels = database.aiModelDao().getEnabledModelsList()
                        val forcedModelId = GatewayForegroundService.getForcedModel()
                        val effectiveForced = if (forcedModelId == "qtai-sj") GatewayForegroundService.activeNodeName else forcedModelId
                        // ★★ 强制池（顺序=切换优先级），池空则用当前模型+排行榜 ★★
                        val poolKeys: List<String> = if (!effectiveForced.isNullOrBlank()) {
                            GatewayForegroundService.getForcedPool().ifEmpty { listOfNotNull(effectiveForced) }
                        } else emptyList()
                        val poolModels = poolKeys.mapNotNull { allEnabledModels.findByRouteKey(it) }
                        val fallback = GatewayScheduler.pipelineSortedModelKeys.mapNotNull { allEnabledModels.findByRouteKey(it) }
                        val candidatesModels = (poolModels.ifEmpty { fallback.ifEmpty { allEnabledModels } }).distinctBy { it.routeKey }
                        candidatesModels.mapNotNull { m ->
                            val p = database.providerDao().getProviderById(m.providerId)
                            if (p != null && p.isEnabled) p to m.modelId else null
                        }
                    } else {
                        listOfNotNull(
                            database.providerDao().getProviderById(model.providerId)?.let { p -> if (p.isEnabled) p to model.modelId else null }
                        )
                    }
                    if (qtaiCandidates.isEmpty()) {
                        _chatError.value = if (model.modelId == "qtai-sj") "⚠️ 请先启动测速获取可用模型排行" else "⚠️ 服务商不可用或已禁用"
                        _isSending.value = false
                        return@launch
                    }
                    // ★★ 首选候选 provider/modelId ★★
                    val provider = qtaiCandidates.first().first
                    val actualModelId = qtaiCandidates.first().second

                // 3. 构造请求体
                val messagesJson = buildMessagesJson(_currentMessages.value)
                val requestBody = buildJsonObject {
                    put("model", JsonPrimitive(actualModelId))
                    put("messages", messagesJson)
                    put("stream", JsonPrimitive(true))
                }.toString()

                // 4. 创建助手消息（流式占位）
                val assistantMsg = ChatMessage(
                    conversationId = conversation.id,
                    role = "assistant",
                    content = "",
                    modelId = actualModelId,
                    providerId = provider.id,
                    isStreaming = true
                )
                val assistantMsgId = database.chatMessageDao().insert(assistantMsg)
                database.chatMessageDao().markStreaming(assistantMsgId)

                val streamingMsg = assistantMsg.copy(id = assistantMsgId)
                _currentMessages.value = _currentMessages.value + streamingMsg

                // 5. 发起流式请求
                val contentBuilder = StringBuilder()
                var totalPromptTokens = 0
                var totalCompletionTokens = 0

                val resolvedUrl = provider.resolvedBaseUrl
                val chatPath = provider.chatPath?.let { if (it.startsWith("/")) it else "/$it" } ?: "/v1/chat/completions"
                withContext(Dispatchers.IO) {
                    UpstreamClient.requestStream(
                        baseUrl = resolvedUrl,
                        apiKey = provider.apiKey,
                        requestBody = requestBody,
                        path = chatPath,
                        listener = object : okhttp3.sse.EventSourceListener() {
                            override fun onEvent(
                                eventSource: okhttp3.sse.EventSource,
                                id: String?,
                                type: String?,
                                data: String
                            ) {
                                if (data == "[DONE]") return

                                try {
                                    val eventJson = json.parseToJsonElement(data).jsonObject
                                    val choices = eventJson["choices"]?.jsonArray
                                    val delta = choices?.firstOrNull()
                                        ?.jsonObject?.get("delta")?.jsonObject
                                    val content = delta?.get("content")?.jsonPrimitive?.content
                                    if (content != null && content != "null" && content != "undefined") {
                                        contentBuilder.append(content)
                                        // ★★ 修复：切回 Main 线程更新 StateFlow，防止秒回复并发 crash ★★
                                        val displayContent = contentBuilder.toString()
                                        viewModelScope.launch(Dispatchers.Main) {
                                            val updatedMsg = streamingMsg.copy(
                                                content = displayContent,
                                                isStreaming = true
                                            )
                                            val msgs = _currentMessages.value.toMutableList()
                                            if (msgs.isNotEmpty()) {
                                                msgs[msgs.lastIndex] = updatedMsg
                                                _currentMessages.value = msgs
                                            }
                                        }
                                    }

                                    // 提取 token 使用信息
                                    val usage = eventJson["usage"]?.jsonObject
                                    if (usage != null) {
                                        totalPromptTokens = usage["prompt_tokens"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                                        totalCompletionTokens = usage["completion_tokens"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                                    }
                                } catch (_: Exception) { }
                            }

                            override fun onFailure(
                                eventSource: okhttp3.sse.EventSource,
                                t: Throwable?,
                                response: okhttp3.Response?
                            ) {
                                // ★★ 修复：切回 Main 线程更新 StateFlow ★★
                                val errMsg = "❌ 请求失败: ${t?.message ?: "未知错误"}"
                                viewModelScope.launch(Dispatchers.Main) {
                                    _chatError.value = errMsg
                                }
                                // ★★ 恢复 isSending（不管成功失败，流结束了）★★
                                _isSending.value = false
                            }

                            override fun onClosed(eventSource: okhttp3.sse.EventSource) {
                                // 流结束——保存最终结果
                                viewModelScope.launch {
                                    if (model.modelId == "qtai-sj") {
                                        // ★★ qtai-sj 走路由器通道，不写 TokenUsage，不碰 provider ★★
                                        finalizeRouterMessage(
                                            assistantMsgId = assistantMsgId,
                                            conversation = conversation,
                                            content = contentBuilder.toString()
                                        )
                                    } else {
                                        // 真模型走完整记账
                                        finalizeAssistantMessage(
                                            assistantMsgId = assistantMsgId,
                                            conversation = conversation,
                                            content = contentBuilder.toString(),
                                            promptTokens = totalPromptTokens,
                                            completionTokens = totalCompletionTokens,
                                            providerId = model.providerId,
                                            modelId = model.modelId
                                        )
                                    }
                                    // ★★ 保存到大脑记忆 ★★
                                    if (BrainMemoryManager.getConfig().enabled) {
                                        val userText = _currentMessages.value.lastOrNull { it.role == "user" }?.content ?: ""
                                        val aiText = contentBuilder.toString()
                                        if (userText.isNotBlank()) {
                                            BrainMemoryManager.addMemory(
                                                content = "用户: $userText\nAI: $aiText",
                                                title = userText.take(40) + if (userText.length > 40) "..." else "",
                                                source = "chat"
                                            )
                                        }
                                    }
                                }
                                // ★★ 恢复 isSending（流正常结束）★★
                                _isSending.value = false
                            }
                        }
                    )
                }
            } catch (e: Exception) {
            _chatError.value = "❌ 发送失败: ${e.message}"
            // ★★ 故障转移：qtai-sj 按强制池/候选列表顺序重试下一个模型 ★★
            if (model.modelId == "qtai-sj" && qtaiCandidates.size > 1) {
                val currentKey = qtaiCandidates.first().first.id to qtaiCandidates.first().second
                val retryCandidates = qtaiCandidates.drop(1)
                if (retryCandidates.isNotEmpty()) {
                    val next = retryCandidates.first()
                    _chatError.value = "↻ qtai-sj ${qtaiCandidates.first().second} 失败，切换到 ${next.second} 重试..."
                    // 找到候选模型对应的 AiModel
                    val enabledList = database.aiModelDao().getEnabledModelsList()
                    val nextModel = enabledList.find { it.modelId == next.second && it.providerId == next.first.id }
                    if (nextModel != null) {
                        _selectedModel.value = nextModel
                        _isSending.value = false
                        delay(500)
                        sendMessage()
                        return@launch
                    }
                }
            } else {
                // ★★ 规则5：失败自动兜底—用测速最优模型重试一次
                val currentModel = _selectedModel.value
                if (currentModel != null) {
                    val sortedIds = com.qtwl.gateway.gateway.GatewayScheduler.pipelineSortedModelKeys
                    if (sortedIds.isNotEmpty() && (currentModel.routeKey != sortedIds.first())) {
                        val bestModelId = sortedIds.first()
                        // 通过 enabledModels 列表查找最优模型
                        val enabledList = database.aiModelDao().getEnabledModelsList()
                        val bestModel = enabledList.findByRouteKey(bestModelId)
                        if (bestModel != null) {
                            _chatError.value = "↻ ${currentModel.displayName} 失败，自动切换到 ${bestModel.displayName} 重试..."
                            _selectedModel.value = bestModel
                            delay(500)
                            // 递归重试（只重试一次，防止死循环）
                            _isSending.value = false
                            sendMessage()
                            return@launch
                        }
                    }
                }
            }
        } finally {
    // ★★ 不移除 _isSending——由 onClosed/onFailure 异步回调来恢复 ★★
    // 只有当协程被取消（点停止按钮）且流尚未正常结束时不重置
}
        }
    }

    /** 确保有当前对话，没有则创建 */
    private suspend fun ensureConversation(): Conversation {
        val current = _currentConversation.value
        if (current != null) return current

        val conversation = Conversation(
            title = "新对话",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        val id = database.conversationDao().insert(conversation)
        val saved = conversation.copy(id = id)
        _currentConversation.value = saved
        return saved
    }

    /** 路由器专用收尾——只标记消息完成，不记账不写 TokenUsage */
    private suspend fun finalizeRouterMessage(
        assistantMsgId: Long,
        conversation: Conversation,
        content: String
    ) {
        // 更新消息内容（token 全部为 0，路由器不消耗 token）
        database.chatMessageDao().finalizeStreamingMessage(
            id = assistantMsgId,
            content = content,
            completionTokens = 0,
            totalTokens = 0
        )

        // 更新对话时间戳
        database.conversationDao().touchConversation(conversation.id)

        // 自动更新标题
        if (conversation.title == "新对话" && content.isNotBlank()) {
            val userMessages = database.chatMessageDao()
                .getMessagesByConversationList(conversation.id)
            val firstUserMsg = userMessages.firstOrNull { it.role == "user" }
            if (firstUserMsg != null) {
                val title = firstUserMsg.content.take(30).replace("\n", " ")
                database.conversationDao().updateTitle(conversation.id, title)
                _currentConversation.value = conversation.copy(title = title)
            }
        }

        // 刷新消息列表
        val messages = database.chatMessageDao()
            .getMessagesByConversationList(conversation.id)
        _currentMessages.value = messages
    }

    /** 最终确认助手消息 */
    private suspend fun finalizeAssistantMessage(
        assistantMsgId: Long,
        conversation: Conversation,
        content: String,
        promptTokens: Int,
        completionTokens: Int,
        providerId: Long,
        modelId: String
    ) {
        val totalTokens = promptTokens + completionTokens

        // 更新消息内容
        database.chatMessageDao().finalizeStreamingMessage(
            id = assistantMsgId,
            content = content,
            completionTokens = completionTokens,
            totalTokens = totalTokens
        )

        // 更新对话信息
        database.conversationDao().touchConversation(conversation.id)
        if (totalTokens > 0) {
            database.conversationDao().addTokens(conversation.id, totalTokens)
        }

        // 自动更新标题（取第一条用户消息的前20字）
        if (conversation.title == "新对话" && content.isNotBlank()) {
            val userMessages = database.chatMessageDao()
                .getMessagesByConversationList(conversation.id)
            val firstUserMsg = userMessages.firstOrNull { it.role == "user" }
            if (firstUserMsg != null) {
                val title = firstUserMsg.content.take(30).replace("\n", " ")
                database.conversationDao().updateTitle(conversation.id, title)
                _currentConversation.value = conversation.copy(title = title)
            }
        }

        // 记录 Token 用量
        if (totalTokens > 0) {
            database.tokenUsageDao().insert(
                TokenUsage(
                    providerId = providerId,
                    modelId = modelId,
                    promptTokens = promptTokens,
                    completionTokens = completionTokens,
                    totalTokens = totalTokens
                )
            )
            refreshTokenStats()
        }

        // 刷新消息列表
        val messages = database.chatMessageDao()
            .getMessagesByConversationList(conversation.id)
        _currentMessages.value = messages
    }

    /** 构造消息 JSON 数组（自动注入人格 + 潜意识记忆） */
    private fun buildMessagesJson(messages: List<ChatMessage>): JsonArray {
        val list = mutableListOf<JsonObject>()
        // ★★ 注入人格 System Prompt ★★
        if (BrainMemoryManager.getConfig().enabled) {
            val personaText = BrainMemoryManager.buildPersonaPrompt()
            if (personaText.isNotBlank()) {
                list.add(buildJsonObject {
                    put("role", JsonPrimitive("system"))
                    put("content", JsonPrimitive(personaText))
                })
            }
        }
        // ★★ 历史消息 ★★
        list.addAll(messages.filter { !it.isStreaming }.map { msg ->
            buildJsonObject {
                put("role", JsonPrimitive(msg.role))
                put("content", JsonPrimitive(msg.content))
            }
        })
        return JsonArray(list)
    }

    // ========== Token 统计 ==========

    /** 获取指定服务商的 Token 总量 */
    suspend fun getTotalTokensByProvider(providerId: Long): Long = database.tokenUsageDao()
        .getTotalTokensByProvider(providerId)

    /** 获取指定模型的 Token 总量 */
    suspend fun getTotalTokensByModel(modelId: String): Long = database.tokenUsageDao()
        .getTotalTokensByModel(modelId)

    /** 清除所有用量记录 */
    fun clearAllUsage() {
        viewModelScope.launch {
            try {
                database.tokenUsageDao().clearAll()
                refreshTokenStats()
                _snackbarMessage.value = "✅ 用量记录已清除"
            } catch (e: Exception) {
                _snackbarMessage.value = "清除失败: ${e.message}"
            }
        }
    }

    // ========== 数据备份与恢复 ==========

    /** 导出所有数据为备份 JSON（旧版兼容） */
    fun backupData() {
        viewModelScope.launch {
            _snackbarMessage.value = "请使用新版备份功能"
        }
    }

    /** 获取备份 JSON 字符串（供 UI 调用） */
    suspend fun getBackupJson(): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                val dir = backupManager.getBackupDir()
                val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
                val file = java.io.File(dir, "backup_$timestamp.qtbk")
                val result = backupManager.exportToFile(file)
                if (result.isSuccess) {
                    Result.success(file.absolutePath)
                } else {
                    Result.failure(result.exceptionOrNull() ?: Exception("导出失败"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    /** 从文件恢复数据 */
    suspend fun restoreFromFile(filePath: String, password: String = ""): Result<Unit> {
        return withContext(Dispatchers.IO) {
            backupManager.importFromFile(java.io.File(filePath), password)
        }
    }

    /** 从 JSON 字符串恢复数据（旧版兼容） */
    suspend fun restoreFromJson(jsonString: String): Result<Unit> {
        return withContext(Dispatchers.IO) {
            Result.failure(Exception("旧版 JSON 导入已废弃，请使用 .qtbk 文件恢复"))
        }
    }

    /** 重置所有数据 */
    fun resetAllData() {
        viewModelScope.launch {
            try {
                // 清空所有表 - 直接使用 DAO 清除
                database.tokenUsageDao().clearAll()
                database.chatMessageDao().deleteAll()
                database.conversationDao().deleteAll()
                database.aiModelDao().deleteAll()
                database.providerDao().deleteAll()
                _snackbarMessage.value = "✅ 所有数据已重置"
            } catch (e: Exception) {
                _snackbarMessage.value = "❌ 重置失败: ${e.message}"
            }
        }
    }

    // ========== 自动获取模型列表 ==========

    /** 根据 Base URL 自动获取可用模型列表 */
    fun fetchAvailableModels(baseUrl: String, apiKey: String? = null) {
        viewModelScope.launch {
            try {
                _syncResult.value = null
                val apiPath = _providerForm.value.apiPath
                withContext(Dispatchers.IO) {
                    val response = UpstreamClient.fetchModels(
                        baseUrl = baseUrl.trimEnd('/'),
                        apiKey = apiKey,
                        apiPath = apiPath
                    )

                    if (!response.isSuccessful) {
                        val errorBody = response.body?.string() ?: "未知错误"
                        _syncResult.value = "❌ 获取模型失败 (${response.code}): $errorBody"
                        return@withContext
                    }

                    val responseBody = response.body?.string() ?: "{}"
                    val jsonObj = json.parseToJsonElement(responseBody).jsonObject
                    val dataArray = jsonObj["data"]?.jsonArray

                    if (dataArray == null) {
                        _syncResult.value = "⚠️ 响应中未找到模型列表，但连接成功"
                        return@withContext
                    }

                    val modelNames = dataArray.mapNotNull { element ->
                        val obj = element.jsonObject
                        obj["id"]?.jsonPrimitive?.content
                    }

                    _syncResult.value = "✅ 成功获取 ${modelNames.size} 个模型: ${modelNames.joinToString(", ")}"
                }
            } catch (e: Exception) {
                _syncResult.value = "❌ 获取模型列表失败: ${e.message}"
            }
        }
    }

    // ★★ 三指标测速器（单例复用） ★★
    private val speedTester = ModelSpeedTester()

    /** 模型测速 - 三指标（TTFT / TPS / 总耗时） */
    fun testModelSpeed(model: AiModel) {
        viewModelScope.launch {
            try {
                _snackbarMessage.value = "⏳ 正在测速 ${model.displayName}..."
                withContext(Dispatchers.IO) {
                    val provider = database.providerDao().getProviderById(model.providerId) ?: run {
                        _snackbarMessage.value = "❌ ${model.displayName}: 未找到关联服务商"
                        return@withContext
                    }
                    val metrics = speedTester.measure(model.modelId, provider.resolvedBaseUrl, provider.apiKey, provider.chatPath)
                    val result = if (metrics.ttftMs < 0) {
                        "❌ ${model.displayName}: 测速失败"
                    } else {
                        "✅ ${model.displayName}: TTFT=${metrics.ttftMs}ms  TPS=${"%.1f".format(metrics.tps)}  总=${metrics.totalMs}ms  tokens=${metrics.tokenCount}"
                    }
                    _snackbarMessage.value = result
                    // ★★ 记录测速历史 ★★
                    recordSpeedHistory(model.routeKey, model.customAlias.ifBlank { model.displayName }, model.providerId, metrics)
                    // ★★ 同时探测模型能力 ★★
                    try {
                        ModelCapabilityManager.probeModel(model.modelId, provider.resolvedBaseUrl, provider.apiKey, provider.chatPath)
                    } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                _snackbarMessage.value = "❌ ${model.displayName} 测速失败: ${e.localizedMessage ?: e.message}"
            }
        }
    }

    /** 删除模型 */
    fun deleteModel(model: com.qtwl.gateway.data.model.AiModel) {
        viewModelScope.launch {
            try {
                database.aiModelDao().delete(model)
                _snackbarMessage.value = "🗑️ 已删除模型: ${model.displayName}"
            } catch (e: Exception) {
                _snackbarMessage.value = "删除失败: ${e.message}"
            }
        }
    }

    // ========== 批量测速 ==========
    private val _batchTesting = MutableStateFlow(false)
    val batchTesting: StateFlow<Boolean> = _batchTesting.asStateFlow()

    private val _batchTestingAutoClose = MutableStateFlow(false)
    val batchTestingAutoClose: StateFlow<Boolean> = _batchTestingAutoClose.asStateFlow()

    fun setBatchTestingAutoClose(enabled: Boolean) {
        _batchTestingAutoClose.value = enabled
    }

    /** 批量测速所有模型，通过的自动开启（顺序一个一个测） */
    fun batchTestAllModels() {
        viewModelScope.launch {
            if (_batchTesting.value) return@launch
            _batchTesting.value = true
            try {
                val allModels = database.aiModelDao().getAllModelsOnce()
                var passed = 0
                var failed = 0
                for ((i, model) in allModels.withIndex()) {
                    _snackbarMessage.value = "⏳ 测试 [${i+1}/${allModels.size}] ${model.displayName}..."
                    // 逐个测速，每个都在 IO 线程执行
                    val ok = withContext(Dispatchers.IO) {
                        try {
                            val provider = database.providerDao().getProviderById(model.providerId)
                            if (provider == null) return@withContext false
                            val client = okhttp3.OkHttpClient.Builder()
                                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                                .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                                .build()
                            val body = """{"model":"${model.modelId}","messages":[{"role":"user","content":"hi"}],"max_tokens":1}"""
                                .toByteArray().toRequestBody("application/json".toMediaType())
                            val chatPath = provider.chatPath?.let { if (it.startsWith("/")) it else "/$it" } ?: "/v1/chat/completions"
                            val request = okhttp3.Request.Builder()
                                .url("${provider.resolvedBaseUrl}$chatPath").post(body)
                                .apply { provider.apiKey?.let { header("Authorization", "Bearer $it") } }
                                .build()
                            val response = client.newCall(request).execute()
                            val success = response.isSuccessful
                            response.close()
                            success
                        } catch (_: Exception) { false }
                    }
                    if (ok) {
                        database.aiModelDao().update(model.copy(isEnabled = true))
                        passed++
                    } else {
                        if (_batchTestingAutoClose.value) {
                            database.aiModelDao().update(model.copy(isEnabled = false))
                        }
                        failed++
                    }
                }
                _snackbarMessage.value = "✅ 批量测试完成: $passed 个通过(已自动启用), $failed 个失败"
            } catch (e: Exception) {
                _snackbarMessage.value = "❌ 批量测试出错: ${e.message}"
            } finally {
                _batchTesting.value = false
            }
        }
    }

    fun clearSnackbar() {
    _snackbarMessage.value = null
}

fun clearSyncResult() {
    _syncResult.value = null
}

fun clearChatError() {
    _chatError.value = null
}

// ★ Debug 抓包模式
    private val _debugMode = MutableStateFlow(GatewayForegroundService.getDebugMode())
    val debugMode: StateFlow<Boolean> = _debugMode.asStateFlow()

    fun toggleDebugMode() {
        val newMode = !_debugMode.value
        _debugMode.value = newMode
        GatewayForegroundService.saveDebugMode(newMode)
        _snackbarMessage.value = if (newMode) "🔍 抓包模式已开启，请求日志将记录" else "🔍 抓包模式已关闭"
    }
    fun getDebugLogs(): List<String> = GatewayForegroundService.getDebugLogs()
    fun clearDebugLogs() { GatewayForegroundService.clearDebugLogs() }

    // ==================== 流水线接力测速 ====================

    /** 启动流水线测速（全自动循环模式，每20秒刷新一轮） */
    fun startPipelineTest() {
        if (_pipelineRunning.value) return
        pipelineJob?.cancel()
        pipelineJob = viewModelScope.launch {
            _pipelineRunning.value = true
            _qtaiSjStatus.value = _qtaiSjStatus.value.copy(isTesting = true)
            _pipelineProgress.value = 0f
            try {
                var firstRound = true
                while (_pipelineRunning.value) {
                    val enabledList = database.aiModelDao().getEnabledModelsList().filter { it.isEnabled }
                    if (enabledList.isEmpty()) { _pipelineRunning.value = false; refreshQtaiSjStatus(); return@launch }

                    // 保留旧测速结果，新模型加入等待中
                    val oldStatus = _pipelineStatus.value
                    val oldMap = oldStatus.associateBy { it.selectionKey }
                    _pipelineStatus.value = enabledList.map { model ->
                        oldMap[model.routeKey]?.copy(
                            modelName = model.customAlias.ifBlank { model.displayName },
                            providerId = model.providerId,
                            ) ?: PipelineTestItem(
                            modelId = model.modelId,
                            modelName = model.customAlias.ifBlank { model.displayName },
                            providerId = model.providerId,
                                status = "等待中"
                        )
                    }

                    val sortedList = if (firstRound) enabledList
                    else {
                        val speedMap = _pipelineStatus.value.associate { it.selectionKey to it.latencyMs }
                        enabledList.sortedBy { speedMap[it.routeKey] ?: Long.MAX_VALUE }
                    }

                    for (model in sortedList) {
                        if (!_pipelineRunning.value) break
                        val realIdx = _pipelineStatus.value.indexOfFirst { it.selectionKey == model.routeKey }
                        if (realIdx < 0) continue
                        if (!_pipelineRunning.value) break
                        // ★★ 更新进度 ★★
                        val testedCount = _pipelineStatus.value.count { it.status.startsWith("✅") || it.status.startsWith("❌") }
                        val total = _pipelineStatus.value.size
                        _pipelineProgress.value = if (total > 0) testedCount.toFloat() / total.toFloat() else 0f

                        val cur = _pipelineStatus.value.toMutableList()
                        cur[realIdx] = cur[realIdx].copy(status = "⏳ 测速中...", isCurrent = true)
                        for (i in cur.indices) { if (i != realIdx) cur[i] = cur[i].copy(isCurrent = false) }
                        _pipelineStatus.value = cur

                        val provider = database.providerDao().getProviderById(model.providerId)
                        if (provider == null || !provider.isEnabled) {
                            val sk = _pipelineStatus.value.toMutableList()
                            sk[realIdx] = sk[realIdx].copy(status = "23edFe0f 8df38fc7", latencyMs = Long.MAX_VALUE, isCurrent = false)
                            _pipelineStatus.value = sk; continue
                        }

                        // ★★ 三指标测速（TTFT / TPS / 总耗时） ★★
                        var success = false; var latency = 0L; var errorMsg = ""
                        var ttft = 0L; var tps = 0.0; var tokens = 0
                        try {
                            withContext(Dispatchers.IO) {
                                val resolvedUrl = provider.resolvedBaseUrl.trimEnd('/')
                                val metrics = speedTester.measure(model.modelId, resolvedUrl, provider.apiKey, provider.chatPath)
                                latency = metrics.totalMs
                                ttft = metrics.ttftMs
                                tps = metrics.tps
                                tokens = metrics.tokenCount
                                if (metrics.ttftMs >= 0) {
                                    success = true
                                    if (tokens == 0) errorMsg = "· 无输出"
                                } else {
                                    errorMsg = "测速失败"
                                }
                            }
                        } catch (e: Exception) { errorMsg = e.message?.take(60) ?: "超时" }

                        // ★★ 记录测速历史到数据库（用于趋势图）★★
                        if (ttft != 0L || tps != 0.0) {
                            val modelName = model.customAlias.ifBlank { model.displayName }
                            recordSpeedHistory(model.routeKey, modelName, model.providerId,
                                SpeedMetrics(ttftMs = ttft, tps = tps, totalMs = latency, tokenCount = tokens, measuredAt = System.currentTimeMillis()))
                        }

                        // ★★ 用完返回再发探针检测模型能力（异步，不影响排序）★★
                                if (success) {
                                    try {
                                        ModelCapabilityManager.probeModel(
                                            model.modelId,
                                            provider.resolvedBaseUrl,
                                            provider.apiKey,
                                            provider.chatPath
                                        )
                                    } catch (_: Exception) {}
                                }

                                val rl = _pipelineStatus.value.toMutableList()
                        rl[realIdx] = rl[realIdx].copy(
                            status = if (success) "✅ TTFT=${ttft}ms TPS=${"%.0f".format(tps)} ${latency}ms" else "❌ $errorMsg",
                            latencyMs = if (success) latency else Long.MAX_VALUE, isCurrent = false
                        )
                        _pipelineStatus.value = rl
                    }

                    val sorted = _pipelineStatus.value.sortedBy { it.latencyMs }
                    _pipelineStatus.value = sorted
                    com.qtwl.gateway.gateway.GatewayScheduler.pipelineSortedModelKeys = sorted.map { it.selectionKey }
                    // ★ 保存测速结果缓存
                    savePipelineCache(sorted)
                    firstRound = false
                    // ★★ 测速完成一轮 → 关闭运行状态，开启倒计时 ★★
                    _pipelineRunning.value = false
                    val intervalMinutes = GatewayForegroundService.getPipelineInterval()
                    val totalSeconds = (intervalMinutes * 60).coerceAtLeast(10) // 最少10秒
                    _pipelineCountdown.value = totalSeconds
                    // ★★ 倒计时循环，每秒更新一次，归零自动重启测速 ★★
                    // ★ 修复：使用独立try-catch保护倒计时，防止异常中断循环 ★
                    try {
                        while (_pipelineCountdown.value > 0) {
                            kotlinx.coroutines.delay(1000)
                            _pipelineCountdown.value--
                        }
                    } catch (_: Exception) {
                        // 倒计时被取消，不重启测速
                        _pipelineCountdown.value = 0
                        refreshQtaiSjStatus()
                        return@launch
                    }
                    _pipelineCountdown.value = 0
                    _pipelineRunning.value = true
                }
            } catch (_: Exception) {
                // 外层异常：测速异常中断，仍尝试重启
                _pipelineRunning.value = false
                _pipelineCountdown.value = 0
                refreshQtaiSjStatus()
                // 异常后延迟10秒自动重启
                kotlinx.coroutines.delay(10000)
                if (!_pipelineRunning.value) {
                    _pipelineRunning.value = true
                }
            }
            if (_pipelineRunning.value) {
                // 正常循环继续
            } else {
                _pipelineRunning.value = false
                _pipelineCountdown.value = 0
                refreshQtaiSjStatus()
            }
        }
    }

    fun stopPipelineTest() {
        _pipelineRunning.value = false
        _pipelineCountdown.value = 0
        pipelineJob?.cancel()
        refreshQtaiSjStatus()
    }

    /** ★★ 刷新 qtai-sj 虚拟模型状态（基于测速排行）★★ */
    private fun refreshQtaiSjStatus() {
        val sorted = com.qtwl.gateway.gateway.GatewayScheduler.pipelineSortedModelKeys
        if (sorted.isNotEmpty()) {
            val bestId = sorted.first()
            val bestModel = enabledModels.value.findByRouteKey(bestId)
            val bestMetrics = com.qtwl.gateway.gateway.GatewayScheduler.healthCache[bestId]
            _qtaiSjStatus.value = QtaiSjStatus(
                available = true,
                bestModelId = bestModel?.modelId ?: ModelRouteKey.modelIdOf(bestId),
                bestModelName = bestModel?.displayName ?: ModelRouteKey.display(bestId),
                bestTtft = bestMetrics?.latencyMs ?: 0,
                lastUpdated = System.currentTimeMillis(),
                isTesting = false,
            )
        } else {
            _qtaiSjStatus.value = QtaiSjStatus(
                available = false,
                lastUpdated = System.currentTimeMillis(),
                isTesting = _pipelineRunning.value,
            )
        }
    }

    // ★ 自动故障转移
    private val _autoFailover = MutableStateFlow(GatewayForegroundService.getAutoFailover())
    val autoFailover: StateFlow<Boolean> = _autoFailover.asStateFlow()

    fun toggleAutoFailover() {
        val newMode = !_autoFailover.value
        _autoFailover.value = newMode
        GatewayForegroundService.saveAutoFailover(newMode)
        // ★ 联动：故障转移开启 → 自动开启模型接力测速；关闭 → 关闭接力测速
        if (newMode) {
            startPipelineTest()
        } else {
            stopPipelineTest()
        }
        _snackbarMessage.value = if (newMode) "🔄 自动故障转移已开启，请求失败自动切换模型" else "🔄 自动故障转移已关闭"
    }

    // ★ 自动化切换（qtai-sj）独立开关
    private val _qtaiSjEnabled = MutableStateFlow(GatewayForegroundService.getQtaiSjEnabled())
    val qtaiSjEnabled: StateFlow<Boolean> = _qtaiSjEnabled.asStateFlow()

    fun toggleQtaiSj() {
        val newMode = !_qtaiSjEnabled.value
        _qtaiSjEnabled.value = newMode
        GatewayForegroundService.saveQtaiSjEnabled(newMode)
        if (newMode) {
            // 开启自动化切换时，清除强制切换，回到自动排行
            GatewayForegroundService.saveForcedModel("")
        }
        _snackbarMessage.value = if (newMode) "🔄 自动化切换已开启" else "🔄 自动化切换已关闭"
    }

    // ★ 手动强制切换模型（点排行榜上的模型）
    // 格式: "providerId::modelId" 精确匹配，避免同名模型混淆
    private val _forcedModelKey = MutableStateFlow(GatewayForegroundService.getForcedModel())
    val forcedModelKey: StateFlow<String> = _forcedModelKey.asStateFlow()

    // ★★★ 强制故障多转移池（多选，可无限加）★★★
    private val _forcedPool = MutableStateFlow(GatewayForegroundService.getForcedPool())
    val forcedPool: StateFlow<List<String>> = _forcedPool.asStateFlow()

    /** 刷新强制池（UI 外部变化后同步） */
    fun refreshForcedPool() {
        _forcedPool.value = GatewayForegroundService.getForcedPool()
    }

    /** 切换是否在强制池中（加入/移出） */
    fun toggleForcedPool(modelId: String, providerId: Long) {
        val modelKey = ModelRouteKey.encode(providerId, modelId)
        val pool = GatewayForegroundService.getForcedPool().toMutableList()
        if (pool.contains(modelKey)) {
            pool.remove(modelKey)
            savePoolAndSync(pool)
            _snackbarMessage.value = "🗑️ 已从强制故障池移除"
        } else {
            pool.add(modelKey)
            savePoolAndSync(pool)
            val item = _pipelineStatus.value.find { it.selectionKey == modelKey }
            _snackbarMessage.value = "🎯 已加入强制故障池: P$providerId · ${item?.modelName ?: modelId}"
        }
    }

    /** 保存池并同步状态 */
    private fun savePoolAndSync(pool: List<String>) {
        GatewayForegroundService.saveForcedPool(pool)
        _forcedPool.value = pool
        // 若池非空且当前无强制模型，把池第一个设为当前强制
        if (pool.isNotEmpty() && GatewayForegroundService.getForcedModel().isBlank()) {
            GatewayForegroundService.saveForcedModel(pool.first())
            _forcedModelKey.value = pool.first()
        }
        // 若池已空，清空强制模型
        if (pool.isEmpty() && GatewayForegroundService.getForcedModel().isNotBlank()) {
            GatewayForegroundService.saveForcedModel("")
            _forcedModelKey.value = ""
        }
    }

    /** 清空整个强制池 */
    fun clearForcedPool() {
        GatewayForegroundService.saveForcedPool(emptyList())
        GatewayForegroundService.saveForcedModel("")
        _forcedPool.value = emptyList()
        _forcedModelKey.value = ""
        _snackbarMessage.value = "↩️ 已清空强制故障池，回到自动排行模式"
    }

    /** 拖拽排序强制池模型 */
    fun reorderForcedPool(fromIndex: Int, toIndex: Int) {
        val pool = GatewayForegroundService.getForcedPool().toMutableList()
        if (fromIndex < 0 || fromIndex >= pool.size || toIndex < 0 || toIndex >= pool.size) return
        val item = pool.removeAt(fromIndex)
        pool.add(toIndex, item)
        savePoolAndSync(pool)
        _snackbarMessage.value = "↕️ 已调整强制池顺序"
    }

    /** 上移强制池模型 */
    fun moveForcedPoolUp(index: Int) {
        if (index <= 0) return
        reorderForcedPool(index, index - 1)
    }

    /** 下移强制池模型 */
    fun moveForcedPoolDown(index: Int) {
        val pool = GatewayForegroundService.getForcedPool()
        if (index < 0 || index >= pool.size - 1) return
        reorderForcedPool(index, index + 1)
    }

    /**
     * 预检查池内所有模型可用性（后台预热）
     * 提前探测下一个可用模型，减少故障切换延迟
     */
    fun precheckForcedPoolModels() {
        viewModelScope.launch(Dispatchers.IO) {
            val pool = GatewayForegroundService.getForcedPool()
            if (pool.size <= 1) return@launch
            for (modelKey in pool) {
                try {
                    val health = GatewayScheduler.healthCache[modelKey]
                    if (health == null || health.lastCheckTime < System.currentTimeMillis() - 5 * 60 * 1000L) {
                        val model = database.aiModelDao().getEnabledModelsList().find { it.routeKey == modelKey } ?: continue
                        val provider = database.providerDao().getProviderById(model.providerId) ?: continue
                        if (!provider.isEnabled) continue
                        val tester = ModelSpeedTester()
                        tester.measure(
                            modelId = model.modelId,
                            baseUrl = provider.resolvedBaseUrl,
                            apiKey = provider.apiKey,
                            chatPath = provider.chatPath
                        )
                    }
                } catch (_: Exception) {}
            }
        }
    }

    fun forceModel(modelId: String, providerId: Long, rankingId: Int? = null) {
        val modelKey = ModelRouteKey.encode(providerId, modelId)
        if (_forcedModelKey.value == modelKey) {
            _forcedModelKey.value = ""
            GatewayForegroundService.saveForcedModel("")
            _snackbarMessage.value = "↩️ 已取消强制切换，回到自动排行模式"
        } else {
            _forcedModelKey.value = modelKey
            GatewayForegroundService.saveForcedModel(modelKey)
            // ★★ 点灯切换：把该模型提升到强制池第一位（网关qtai-sj分支按池顺序优先用第一个）★★
            val pool = GatewayForegroundService.getForcedPool().toMutableList()
            if (pool.isNotEmpty() && pool.first() != modelKey) {
                pool.remove(modelKey)
                pool.add(0, modelKey)
                GatewayForegroundService.saveForcedPool(pool)
                _forcedPool.value = pool
            } else if (pool.isEmpty()) {
                // 池空时，把该模型作为唯一池成员，保证 qtai-sj 优先用它
                GatewayForegroundService.saveForcedPool(listOf(modelKey))
                _forcedPool.value = listOf(modelKey)
            }
            // ★★ 同步更新全局活跃节点，使池灯立即点亮（2秒轮询读取的就是这俩）★★
            GatewayForegroundService.activeNodeName = modelId
            GatewayForegroundService.activeNodeProviderId = providerId
            val item = _pipelineStatus.value.find { it.selectionKey == modelKey }
            val rankPrefix = rankingId?.let { "#$it · " }.orEmpty()
            _snackbarMessage.value = "🎯 已选择: ${rankPrefix}P$providerId · ${item?.modelName ?: modelId}"
        }
    }

    fun clearForcedModel() {
        _forcedModelKey.value = ""
        GatewayForegroundService.saveForcedModel("")
    }

    // ★★ 实时会话列表（从全局读）★★
    var liveSessions: List<LiveSession>
        get() = GatewayForegroundService.liveSessions
        private set(value) { /* 只读 */ }

    // ★★ 实时会话低频快照（3秒刷新一次，避免跑马灯高频重组导致卡顿）★★
    private val _quickLiveSessions = MutableStateFlow<List<LiveSession>>(emptyList())
    val quickLiveSessions: StateFlow<List<LiveSession>> = _quickLiveSessions.asStateFlow()
    
    fun refreshQuickLiveSessions() {
        _quickLiveSessions.value = GatewayForegroundService.liveSessions
    }
    
    fun clearLiveSessions() {
        GatewayForegroundService.clearLiveSessions()
    }

    // ========== Factory ==========

    class Factory : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val db = AppDatabase.getInstance(GatewayApplication.getInstance())
            return GatewayViewModel(db) as T
        }
    }
}
