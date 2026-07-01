package com.qtwl.gateway.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.qtwl.gateway.GatewayApplication
import com.qtwl.gateway.data.db.AppDatabase
import com.qtwl.gateway.data.model.AiModel
import com.qtwl.gateway.data.model.ChatMessage
import com.qtwl.gateway.data.model.Conversation
import com.qtwl.gateway.data.model.Provider
import com.qtwl.gateway.data.model.TokenUsage
import com.qtwl.gateway.data.db.BackupManager
import com.qtwl.gateway.network.Socks5SocketFactory
import com.qtwl.gateway.network.UpstreamClient
import com.qtwl.gateway.service.GatewayForegroundService
import com.qtwl.gateway.gateway.GatewayService
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

/**
 * 网关应用的主 ViewModel —— 管理全部业务状态
 * 包括：服务商管理、模型同步、聊天对话、会话管理、Token用量统计
 */

// ==================== 包级全局变量（Activity重建不丢失）====================
/** 流水线测速状态条目 */
data class PipelineTestItem(
    val modelId: String,
    val modelName: String,
    val status: String,
    val latencyMs: Long = 0,
    val isCurrent: Boolean = false
)

private val _pipelineStatus = MutableStateFlow<List<PipelineTestItem>>(emptyList())
val pipelineStatus: StateFlow<List<PipelineTestItem>> = _pipelineStatus.asStateFlow()

private val _pipelineRunning = MutableStateFlow(false)
val pipelineRunning: StateFlow<Boolean> = _pipelineRunning.asStateFlow()

private var pipelineJob: kotlinx.coroutines.Job? = null

class GatewayViewModel(
    private val database: AppDatabase
) : ViewModel() {

    // ==================== JSON 解析器 ====================
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

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

    // ==================== 备份管理器 ====================
    private val backupManager = BackupManager(database)

    // ==================== 添加服务商表单 ====================
    
    /** 大模型服务商类型预设 */
    data class ProviderTypePreset(
        val displayName: String,      // 显示名称
        val defaultType: String,       // 类型标识
        val defaultBaseUrl: String,    // 默认基础地址
        val defaultPort: String,       // 默认端口
        val exampleApiKey: String      // API Key 提示
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
                exampleApiKey = "sk-ant-..."
            ),
            ProviderTypePreset(
                displayName = "Google (Gemini)",
                defaultType = "Google Gemini",
                defaultBaseUrl = "https://generativelanguage.googleapis.com",
                defaultPort = "443",
                exampleApiKey = "AIza..."
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
        val orderIndex: Int = 0
    )

    private val _providerForm = MutableStateFlow(ProviderForm())
    val providerForm: StateFlow<ProviderForm> = _providerForm.asStateFlow()

    // ==================== 初始化 ====================
    init {
        refreshTokenStats()
        loadProxyListFromPrefs()
        // 同步真实服务运行状态
        _serviceRunning.value = GatewayForegroundService.isServiceRunning
        // ★★ 初始化时检查自动故障转移是否已开启，若是则自动启动接力测速 ★★
        if (GatewayForegroundService.getAutoFailover()) {
            startPipelineTest()
        }
    }

    // ========== 服务生命周期控制 ==========

    /** 启动网关服务 */
    fun startGateway() {
        try {
            GatewayForegroundService.start()
            _serviceRunning.value = true
            GatewayForegroundService.isServiceRunning = true
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
            apiKey = preset.exampleApiKey
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
                        orderIndex = form.orderIndex
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

    /** 从服务商同步模型列表 */
    fun syncModels(provider: Provider) {
        viewModelScope.launch {
            _syncingProviderId.value = provider.id
            _syncResult.value = null

            try {
                withContext(Dispatchers.IO) {
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
                            isEnabled = enabledMap[modelId] ?: false,  // 保留旧的启用状态
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
        return "🔄 自动化切换"
    }
    return if (model.customAlias.isNotBlank()) {
        "${model.displayName} (${model.customAlias})"
    } else {
        model.displayName
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

        val model = _selectedModel.value
        if (model == null) {
            _chatError.value = "⚠️ 请先选择一个模型"
            return
        }

        viewModelScope.launch {
            _isSending.value = true
            _chatError.value = null

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

// 2. 获取服务商信息
                    val provider = if (model.modelId == "qtai-sj") {
                        // ★★ qtai-sj：通过本地网关转发，让网关自动选最优模型 ★★
                        Provider(
                            id = -1,
                            name = "本地网关",
                            type = "OpenAI Compatible",
                            baseUrl = "http://localhost:${_gatewayPort.value}",
                            port = "",
                            apiKey = "qtai-sj",
                            isEnabled = true
                        )
                    } else {
                        database.providerDao().getProviderById(model.providerId)
                    }
                    if (provider == null || !provider.isEnabled) {
                    _chatError.value = "⚠️ 服务商不可用或已禁用"
                    _isSending.value = false
                    return@launch
                }

                // 3. 构造请求体
                val messagesJson = buildMessagesJson(_currentMessages.value)
                val requestBody = buildJsonObject {
                    put("model", JsonPrimitive(model.modelId))
                    put("messages", messagesJson)
                    put("stream", JsonPrimitive(true))
                }.toString()

                // 4. 创建助手消息（流式占位）
                val assistantMsg = ChatMessage(
                    conversationId = conversation.id,
                    role = "assistant",
                    content = "",
                    modelId = model.modelId,
                    providerId = model.providerId,
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
                withContext(Dispatchers.IO) {
                    UpstreamClient.requestStream(
                        baseUrl = resolvedUrl,
                        apiKey = provider.apiKey,
                        requestBody = requestBody,
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
                                        // 实时更新 UI
                                        val updatedMsg = streamingMsg.copy(
                                            content = contentBuilder.toString(),
                                            isStreaming = true
                                        )
                                        val msgs = _currentMessages.value.toMutableList()
                                        msgs[msgs.lastIndex] = updatedMsg
                                        _currentMessages.value = msgs
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
                                _chatError.value = "❌ 请求失败: ${t?.message ?: "未知错误"}"
                            }

                            override fun onClosed(eventSource: okhttp3.sse.EventSource) {
                                // 流结束——保存最终结果
                                viewModelScope.launch {
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
                            }
                        }
                    )
                }
            } catch (e: Exception) {
            _chatError.value = "❌ 发送失败: ${e.message}"
            // ★★ 规则5：失败自动兜底—用测速最优模型重试一次
            val currentModel = _selectedModel.value
            if (currentModel != null) {
                val sortedIds = com.qtwl.gateway.gateway.pipelineSortedModelIds
                if (sortedIds.isNotEmpty() && (currentModel.modelId != sortedIds.first())) {
                    val bestModelId = sortedIds.first()
                    // 通过 enabledModels 列表查找最优模型
                    val enabledList = database.aiModelDao().getEnabledModelsList()
                    val bestModel = enabledList.find { it.modelId == bestModelId }
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
        } finally {
                _isSending.value = false
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

    /** 构造消息 JSON 数组 */
    private fun buildMessagesJson(messages: List<ChatMessage>): JsonArray {
        val list = messages.filter { !it.isStreaming }.map { msg ->
            buildJsonObject {
                put("role", JsonPrimitive(msg.role))
                put("content", JsonPrimitive(msg.content))
            }
        }
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

    /** 导出所有数据为备份 JSON */
    fun backupData() {
        viewModelScope.launch {
            try {
                val result = backupManager.exportToJson()
                result.onSuccess { json ->
                    _snackbarMessage.value = "✅ 数据导出成功"
                }.onFailure { e ->
                    _snackbarMessage.value = "❌ 导出失败: ${e.message}"
                }
            } catch (e: Exception) {
                _snackbarMessage.value = "❌ 导出失败: ${e.message}"
            }
        }
    }

    /** 获取备份 JSON 字符串（供 UI 调用） */
    suspend fun getBackupJson(): Result<String> {
        return backupManager.exportToJson()
    }

    /** 从 JSON 字符串恢复数据 */
    suspend fun restoreFromJson(jsonString: String): Result<Unit> {
        return withContext(Dispatchers.IO) {
            backupManager.importFromJson(jsonString)
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
                withContext(Dispatchers.IO) {
                    val response = UpstreamClient.fetchModels(
                        baseUrl = baseUrl.trimEnd('/'),
                        apiKey = apiKey
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

    /** 模型测速 - 握手测试 */
    fun testModelSpeed(model: AiModel) {
        viewModelScope.launch {
            try {
                _snackbarMessage.value = "⏳ 正在测试 ${model.displayName}..."
                withContext(Dispatchers.IO) {
                    val provider = database.providerDao().getProviderById(model.providerId) ?: return@withContext
                    val resolvedUrl = provider.resolvedBaseUrl
                    val client = okhttp3.OkHttpClient.Builder()
                        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                        .build()
                    val requestBody = """{"model":"${model.modelId}","messages":[{"role":"user","content":"hi"}],"max_tokens":1}"""
                        .toByteArray()
                        .toRequestBody("application/json".toMediaType())
                    val startTime = System.currentTimeMillis()
                    val request = okhttp3.Request.Builder()
                        .url("$resolvedUrl/v1/chat/completions")
                        .post(requestBody)
                        .apply { provider.apiKey?.let { header("Authorization", "Bearer $it") } }
                        .build()
                    val response = client.newCall(request).execute()
                    val latency = System.currentTimeMillis() - startTime
                    val result = if (response.isSuccessful) {
                        "✅ ${model.displayName}: ${latency}ms ✅"
                    } else {
                        "❌ ${model.displayName}: HTTP ${response.code} ${response.body?.string()?.take(100) ?: ""}"
                    }
                    _snackbarMessage.value = result
                }
            } catch (e: Exception) {
                _snackbarMessage.value = "❌ ${model.displayName} 测试失败: ${e.localizedMessage ?: e.message}"
            }
        }
    }

    // ========== 批量测速 ==========
    private val _batchTesting = MutableStateFlow(false)
    val batchTesting: StateFlow<Boolean> = _batchTesting.asStateFlow()

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
                            val request = okhttp3.Request.Builder()
                                .url("${provider.resolvedBaseUrl}/v1/chat/completions").post(body)
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
    private val DEFAULT_CT = "application/json".toMediaType()

    /** 启动流水线测速（全自动循环模式，每20秒刷新一轮） */
    fun startPipelineTest() {
        if (_pipelineRunning.value) return
        pipelineJob?.cancel()
        pipelineJob = viewModelScope.launch {
            _pipelineRunning.value = true
            try {
                var firstRound = true
                while (_pipelineRunning.value) {
                    val enabledList = database.aiModelDao().getEnabledModelsList().filter { it.isEnabled }
                    if (enabledList.isEmpty()) { _pipelineRunning.value = false; return@launch }

                    // 保留旧测速结果，新模型加入等待中
                    val oldStatus = _pipelineStatus.value
                    val oldMap = oldStatus.associateBy { it.modelId }
                    _pipelineStatus.value = enabledList.map { model ->
                        val old = oldMap[model.modelId]
                        if (old != null) old
                        else PipelineTestItem(
                            modelId = model.modelId,
                            modelName = if (model.customAlias.isNotBlank()) model.customAlias else model.displayName,
                            status = "等待中"
                        )
                    }

                    // 第1轮按默认顺序，之后按速度排行
                    val sortedList = if (firstRound) enabledList
                    else {
                        val speedMap = _pipelineStatus.value.associate { it.modelId to it.latencyMs }
                        enabledList.sortedBy { speedMap[it.modelId] ?: Long.MAX_VALUE }
                    }

                    for (model in sortedList) {
                        if (!_pipelineRunning.value) break
                        val realIdx = _pipelineStatus.value.indexOfFirst { it.modelId == model.modelId }
                        if (realIdx < 0) continue
                        if (!_pipelineRunning.value) break

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

                        val startTime = System.currentTimeMillis()
                        var success = false; var latency = 0L; var errorMsg = ""
                        try {
                            withContext(Dispatchers.IO) {
                                val resolvedUrl = provider.resolvedBaseUrl.trimEnd('/')
                                val testBody = """{"model":"${model.modelId}","messages":[{"role":"user","content":"Say just OK"}],"max_tokens":5,"stream":false}"""
                                val req = okhttp3.Request.Builder()
                                    .url("$resolvedUrl/v1/chat/completions")
                                    .post(testBody.toRequestBody(DEFAULT_CT))
                                    .apply { if (!provider.apiKey.isNullOrBlank()) header("Authorization", "Bearer ${provider.apiKey}") }
                                    .build()
                                val client = OkHttpClient.Builder()
                                    .connectTimeout(8000, TimeUnit.MILLISECONDS)
                                    .readTimeout(8000, TimeUnit.MILLISECONDS)
                                    .build()
                                val resp = client.newCall(req).execute()
                                latency = System.currentTimeMillis() - startTime
                                if (resp.isSuccessful) {
                                    val bodyStr = resp.body?.string() ?: ""
                                    try {
                                        val respJson = json.parseToJsonElement(bodyStr).jsonObject
                                        val content = respJson["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.content
                                        success = content != null && content.isNotBlank()
                                    } catch (_: Exception) { success = false }
                                    if (!success) errorMsg = "回复为空"
                                } else { errorMsg = "HTTP ${resp.code}" }
                                resp.close()
                            }
                        } catch (e: Exception) { errorMsg = e.message?.take(60) ?: "超时" }

                        val rl = _pipelineStatus.value.toMutableList()
                        rl[realIdx] = rl[realIdx].copy(
                            status = if (success) "✅ ${latency}ms" else "❌ $errorMsg",
                            latencyMs = if (success) latency else Long.MAX_VALUE, isCurrent = false
                        )
                        _pipelineStatus.value = rl
                    }

                    val sorted = _pipelineStatus.value.sortedBy { it.latencyMs }
                    _pipelineStatus.value = sorted
                    com.qtwl.gateway.gateway.pipelineSortedModelIds = sorted.map { it.modelId }
                    firstRound = false
                    if (_pipelineRunning.value) delay(30000)
                }
            } catch (_: Exception) { }
            _pipelineRunning.value = false
        }
    }

    fun stopPipelineTest() {
        _pipelineRunning.value = false
        pipelineJob?.cancel()
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
        _snackbarMessage.value = if (newMode) "🔄 自动化切换已开启" else "🔄 自动化切换已关闭"
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
