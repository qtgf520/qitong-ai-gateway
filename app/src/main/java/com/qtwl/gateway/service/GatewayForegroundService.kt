package com.qtwl.gateway.service

import android.app.PendingIntent
import android.app.Service
import android.app.AlarmManager
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.qtwl.gateway.GatewayApplication
import com.qtwl.gateway.MainActivity
import com.qtwl.gateway.R
import com.qtwl.gateway.utils.localizedText
import com.qtwl.gateway.gateway.GatewayService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// ★★ 实时会话条目（歌词式，不持久化）★★
data class LiveSession(
    val id: Long = System.nanoTime(),
    val modelName: String,
    val requestPreview: String,
    val status: String = "📤 发送",   // 📤 发送 | 💭 思考 | 📥 回复
    val responsePreview: String = "",
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * 前台服务 —— 保持网关在后台持续运行，动态通知栏显示Token和流量
 */
class GatewayForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var gatewayService: GatewayService
    private var notificationJob: Job? = null
    private var wakeEnabled = false // 是否开启唤醒保活
    private var wakeLock: PowerManager.WakeLock? = null
    private var alarmKeepAliveJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        val app = application as GatewayApplication
        gatewayService = GatewayService(app.database)
        wakeEnabled = getWakeEnabled()
        // ★★★ 恢复持久化的总流量统计（APP内使用）★★★ 通知栏流量不从持久化恢复，重启即清零
        totalUploadBytes.set(getSavedTraffic("total_upload"))
        totalDownloadBytes.set(getSavedTraffic("total_download"))
        // 通知栏流量从零开始（重启清零）
        trafficUploadBytes.set(0L)
        trafficDownloadBytes.set(0L)
        
        // ★★ 关屏保活：获取电源锁 + 定时Alarm唤醒 ★★
        if (wakeEnabled) {
            acquireWakeLock()
            scheduleAlarmKeepAlive()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 处理广播过来的唤醒/取消唤醒意图
        if (intent?.hasExtra(EXTRA_TOGGLE_WAKE) == true) {
            wakeEnabled = !wakeEnabled
            saveWakeEnabled(wakeEnabled)
            if (wakeEnabled) {
                acquireWakeLock()
                scheduleAlarmKeepAlive()
            } else {
                releaseWakeLock()
                cancelAlarmKeepAlive()
            }
        }

        updateNotification()

        serviceScope.launch {
            val port = getGatewayPort()
            gatewayService.start(port = port)
        }

        notificationJob?.cancel()
        notificationJob = serviceScope.launch {
            while (isActive) {
                delay(1000)
                updateNotification()
                // ★★★ 每10秒持久化流量统计 ★★★
                if (System.currentTimeMillis() % 10000 < 1000) saveTraffic()
            }
        }

        return START_STICKY
    }

    private fun updateNotification() {
        val pendingIntent = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val port = getGatewayPort()

        // 从代理列表 JSON 中读取当前激活的代理（与APP内同步）
        val proxyListJson = getProxyListJson()
        var proxyText = localizedText("代理: 未开启", "Proxy: disabled")
        if (proxyListJson.isNotBlank()) {
            try {
                // 直接解析 JSON 数组
                val arr = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                    .decodeFromString<kotlinx.serialization.json.JsonArray>(proxyListJson)
                for (elem in arr) {
                    val obj = elem.jsonObject
                    if (obj["enabled"]?.jsonPrimitive?.content == "true") {
                        val t = obj["type"]?.jsonPrimitive?.content ?: "HTTP"
                        val h = obj["host"]?.jsonPrimitive?.content ?: ""
                        val p = obj["port"]?.jsonPrimitive?.content ?: "0"
                        val u = obj["username"]?.jsonPrimitive?.content ?: ""
                        proxyText = localizedText("代理: ", "Proxy: ") + "$t $h:$p"
                        if (u.isNotBlank()) proxyText += " ($u)"
                        break
                    }
                }
            } catch (_: Exception) { }
        }

        // ★ 动态流量指示灯 — 累计模式，永不归零！仅通过时间戳判断活跃/空闲
        val upBytes = trafficUploadBytes.get()
        val downBytes = trafficDownloadBytes.get()
        val totalUp = totalUploadBytes.get()
        val totalDown = totalDownloadBytes.get()
        val hasTraffic = upBytes > 0 || downBytes > 0
        val now = System.currentTimeMillis()
        val idleSeconds = if (lastActivityTime > 0) (now - lastActivityTime) / 1000 else -1
        val isActive = idleSeconds >= 0 && idleSeconds < 30 // 30秒内无流量视为空闲
        val nodeName = activeNodeName

        // 更新最后活跃时间（只在有流量时更新）
        if (upBytes > lastUploadBytes || downBytes > lastDownloadBytes) {
            lastActivityTime = now
            idleCount = 0
        } else if (hasTraffic) {
            idleCount++
        }
        lastUploadBytes = upBytes
        lastDownloadBytes = downBytes

        val text = buildString {
            append(localizedText("端口 ", "Port ")).append(port)
            // ★★ 当前会话流量（可重置）★★
            append(localizedText("\n📊 当前会话 ", "\n📊 Current session ")).append("↑${formatBytes(upBytes)} ↓${formatBytes(downBytes)}")
            // ★★ 总统计（持久化）★★
            append(localizedText("\n📈 总统计 ", "\n📈 All-time totals ")).append("↑${formatBytes(totalUp)} ↓${formatBytes(totalDown)}")
            // ★★ 始终显示模型名（不只在传输中）
            if (nodeName.isNotBlank()) {
                append("\n🧠 $nodeName")
            }
            if (hasTraffic && isActive) {
                append(localizedText("\n🟢 传输中", "\n🟢 Transferring"))
            } else if (hasTraffic && !isActive) {
                append(localizedText("\n⚪ 空闲", "\n⚪ Idle"))
            }
            append("\n$proxyText")
        }

        // 唤醒/取消唤醒按钮
        val toggleWakeIntent = Intent(this, GatewayForegroundService::class.java).apply {
            putExtra(EXTRA_TOGGLE_WAKE, true)
        }
        val toggleWakePI = PendingIntent.getService(this, 2, toggleWakeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val title = (if (wakeEnabled) localizedText("🟢 綦桐网关(保活中)", "🟢 QiTong Gateway (keep-alive)") else localizedText("綦桐网关", "QiTong Gateway")) + if (nodeName.isNotBlank()) " · $nodeName" else ""
        val notification = NotificationCompat.Builder(this, GatewayApplication.CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_sort_by_size,
                if (wakeEnabled) localizedText("取消唤醒", "Disable keep-alive") else localizedText("唤醒保活", "Enable keep-alive"), toggleWakePI)
            .build()

        try { startForeground(NOTIFICATION_ID, notification) } catch (_: Exception) {}
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "${bytes}B"
        bytes < 1024 * 1024 -> "${bytes / 1024}KB"
        else -> "%.1fMB".format(bytes.toDouble() / (1024 * 1024))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        notificationJob?.cancel()
        alarmKeepAliveJob?.cancel()
        releaseWakeLock()
        cancelAlarmKeepAlive()
        gatewayService.stop()
        super.onDestroy()
    }

    // ★★ 关屏保活：WakeLock ★★
    private fun acquireWakeLock() {
        if (wakeLock == null) {
            try {
                val pm = getSystemService(POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "qitong:gateway_keepalive"
                ).apply {
                    setReferenceCounted(false)
                    acquire(30 * 60 * 1000L) // 最多30分钟自动释放
                }
                addDebugLog("🔌 WakeLock acquired")
            } catch (_: Exception) { }
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.release()
        } catch (_: Exception) { }
        wakeLock = null
    }

    // ★★ 关屏保活：AlarmManager 定时唤醒（每5分钟自唤醒一次）★★
    private fun scheduleAlarmKeepAlive() {
        try {
            val alarmMgr = getSystemService(ALARM_SERVICE) as AlarmManager
            val intent = Intent(this, GatewayForegroundService::class.java)
            val pi = PendingIntent.getService(this, 1, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            alarmMgr.setRepeating(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                android.os.SystemClock.elapsedRealtime() + 300000,
                300000, // 每5分钟
                pi
            )
            addDebugLog("⏰ Alarm keep-alive scheduled (5min)")
        } catch (_: Exception) { }
    }

    private fun cancelAlarmKeepAlive() {
        try {
            val alarmMgr = getSystemService(ALARM_SERVICE) as AlarmManager
            val intent = Intent(this, GatewayForegroundService::class.java)
            val pi = PendingIntent.getService(this, 1, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            alarmMgr.cancel(pi)
            pi.cancel()
        } catch (_: Exception) { }
    }

    companion object {
        const val NOTIFICATION_ID = 1001
        private const val PREF_NAME = "gateway_config"
        private const val KEY_GATEWAY_PORT = "gateway_port"
        private const val KEY_PROXY_ENABLED = "proxy_enabled"
        private const val KEY_PROXY_HOST = "proxy_host"
        private const val KEY_PROXY_PORT = "proxy_port"
        private const val KEY_PROXY_PROTOCOL = "proxy_protocol"
        private const val KEY_WAKE_ENABLED = "wake_enabled"
        private const val KEY_PROXY_LIST_JSON = "proxy_list_json"
        private const val KEY_DEBUG_MODE = "debug_mode"
        private const val KEY_AUTO_FAILOVER = "auto_failover"
        private const val KEY_FAILOVER_MODEL = "failover_model"
        private const val KEY_QTAI_SJ_ENABLED = "qtai_sj_enabled"
        private const val KEY_QTAI_SJ_BRAIN = "qtai_sj_brain_model"
        private const val KEY_QTAI_SJ_NAME = "qtai_sj_name"
        private const val KEY_FORCED_MODEL = "forced_model"
        private const val KEY_LAST_REAL_MODEL = "last_real_model"
        private const val EXTRA_TOGGLE_WAKE = "toggle_wake"
        private const val KEY_TRAFFIC_UPLOAD = "traffic_upload"
        private const val KEY_TRAFFIC_DOWNLOAD = "traffic_download"
        private const val DEFAULT_PORT = 8889
        private const val DEFAULT_PROXY_PORT = 7890

        // 运行时流量统计（由gateway更新）
        @Volatile var tokenPromptInput: Long = 0L
@Volatile var tokenCompletionOutput: Long = 0L
val trafficUploadBytes = java.util.concurrent.atomic.AtomicLong(0L)   // ★ 通知栏显示（可重置）
val trafficDownloadBytes = java.util.concurrent.atomic.AtomicLong(0L) // ★ 通知栏显示（可重置）
val totalUploadBytes = java.util.concurrent.atomic.AtomicLong(0L)     // ★ APP内总统计（持久化不重置）
val totalDownloadBytes = java.util.concurrent.atomic.AtomicLong(0L)   // ★ APP内总统计（持久化不重置）
        @Volatile var isServiceRunning: Boolean = false  // 由 start/stop 同步更新

        @Volatile var activeNodeName: String = ""
        @Volatile var lastUploadBytes: Long = 0L
        @Volatile var lastDownloadBytes: Long = 0L
        @Volatile var idleCount: Int = 0
        @Volatile var lastActivityTime: Long = 0L  // ★ 最后活跃时间戳（毫秒）

        // ★ Debug 日志（环形缓冲区，保留最近20条）
        @Volatile var debugLogBuffer = mutableListOf<String>()
        private const val MAX_DEBUG_LOG = 20
        
        // ★★ 实时会话条目（最近10条，不持久化）★★
        private val _liveSessions = mutableListOf<LiveSession>()
        private const val MAX_LIVE_SESSIONS = 10
        val liveSessions: List<LiveSession> get() = synchronized(_liveSessions) { _liveSessions.toList() }
        
        fun addLiveSession(session: LiveSession) {
            synchronized(_liveSessions) {
                _liveSessions.add(0, session)
                if (_liveSessions.size > MAX_LIVE_SESSIONS) {
                    _liveSessions.removeAt(_liveSessions.size - 1)
                }
            }
        }
        
        fun updateLiveSession(id: Long, status: String, responsePreview: String = "") {
            synchronized(_liveSessions) {
                val idx = _liveSessions.indexOfFirst { it.id == id }
                if (idx >= 0) {
                    val old = _liveSessions[idx]
                    _liveSessions[idx] = old.copy(
                        status = status,
                        responsePreview = if (responsePreview.isNotBlank()) responsePreview else old.responsePreview
                    )
                }
            }
        }
        
        fun clearLiveSessions() {
            synchronized(_liveSessions) { _liveSessions.clear() }
        }

        fun addDebugLog(msg: String) {
            synchronized(debugLogBuffer) {
                val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
                // 带上当前流量统计
                val up = trafficUploadBytes.get()
                val down = trafficDownloadBytes.get()
                val trafficInfo = if (up > 0 || down > 0) " ↑${formatBytesStatic(up)} ↓${formatBytesStatic(down)}" else ""
                debugLogBuffer.add("[$time$trafficInfo] $msg")
                if (debugLogBuffer.size > MAX_DEBUG_LOG) {
                    debugLogBuffer.removeAt(0)
                }
            }
        }
        fun getDebugLogs(): List<String> = synchronized(debugLogBuffer) { debugLogBuffer.toList() }
        fun clearDebugLogs() { synchronized(debugLogBuffer) { debugLogBuffer.clear() } }

        private fun formatBytesStatic(bytes: Long): String = when {
            bytes < 1024 -> "${bytes}B"
            bytes < 1024 * 1024 -> "${bytes / 1024}KB"
            else -> "%.1fMB".format(bytes.toDouble() / (1024 * 1024))
        }

        fun saveDebugMode(enabled: Boolean) {
            GatewayApplication.getInstance().getSharedPreferences(PREF_NAME, 0).edit().putBoolean(KEY_DEBUG_MODE, enabled).apply()
        }
        fun getDebugMode(): Boolean = GatewayApplication.getInstance().getSharedPreferences(PREF_NAME, 0).getBoolean(KEY_DEBUG_MODE, false)

        fun saveAutoFailover(enabled: Boolean) {
            GatewayApplication.getInstance().getSharedPreferences(PREF_NAME, 0).edit().putBoolean(KEY_AUTO_FAILOVER, enabled).apply()
        }
        fun getAutoFailover(): Boolean = GatewayApplication.getInstance().getSharedPreferences(PREF_NAME, 0).getBoolean(KEY_AUTO_FAILOVER, false)

        fun saveQtaiSjEnabled(enabled: Boolean) {
            GatewayApplication.getInstance().getSharedPreferences(PREF_NAME, 0).edit().putBoolean(KEY_QTAI_SJ_ENABLED, enabled).apply()
        }
        fun getQtaiSjEnabled(): Boolean = GatewayApplication.getInstance().getSharedPreferences(PREF_NAME, 0).getBoolean(KEY_QTAI_SJ_ENABLED, true)

        /** ★★ qtai-sj 绑定的脑子模型ID ★★ */
    fun saveQtaiSjBrain(modelId: String) {
        GatewayApplication.getInstance().getSharedPreferences(PREF_NAME, 0).edit().putString(KEY_QTAI_SJ_BRAIN, modelId).apply()
    }
    fun getQtaiSjBrain(): String = GatewayApplication.getInstance().getSharedPreferences(PREF_NAME, 0).getString(KEY_QTAI_SJ_BRAIN, "") ?: ""
    
    /** ★★ qtai-sj 人格名称（动态绑定，用户可自定义） ★★ */
    fun saveQtaiSjName(name: String) {
        GatewayApplication.getInstance().getSharedPreferences(PREF_NAME, 0).edit().putString(KEY_QTAI_SJ_NAME, name).apply()
    }
    fun getQtaiSjName(): String = GatewayApplication.getInstance().getSharedPreferences(PREF_NAME, 0).getString(KEY_QTAI_SJ_NAME, "") ?: ""
    
    fun saveForcedModel(modelId: String) {
        // ★ 保存上一个真实模型（用于qtai-sj选中时知道用户选过什么模型）
        val current = getForcedModel()
        if (current != "qtai-sj" && current.isNotBlank() && current != modelId) {
            saveLastRealModel(current)
        }
        if (modelId == "qtai-sj" && current != "qtai-sj" && current.isNotBlank()) {
            saveLastRealModel(current)
        }
        GatewayApplication.getInstance().getSharedPreferences(PREF_NAME, 0).edit().putString(KEY_FORCED_MODEL, modelId).apply()
    }
    fun getForcedModel(): String = GatewayApplication.getInstance().getSharedPreferences(PREF_NAME, 0).getString(KEY_FORCED_MODEL, "") ?: ""
    fun saveLastRealModel(modelId: String) {
        GatewayApplication.getInstance().getSharedPreferences(PREF_NAME, 0).edit().putString(KEY_LAST_REAL_MODEL, modelId).apply()
    }
    fun getLastRealModel(): String = GatewayApplication.getInstance().getSharedPreferences(PREF_NAME, 0).getString(KEY_LAST_REAL_MODEL, "") ?: ""

        fun saveFailoverModel(modelId: String) {
            GatewayApplication.getInstance().getSharedPreferences(PREF_NAME, 0).edit().putString(KEY_FAILOVER_MODEL, modelId).apply()
        }
        fun getFailoverModel(): String = GatewayApplication.getInstance().getSharedPreferences(PREF_NAME, 0).getString(KEY_FAILOVER_MODEL, "") ?: ""

        fun saveGatewayPort(port: Int) {
            GatewayApplication.getInstance().getSharedPreferences(PREF_NAME, 0).edit().putInt(KEY_GATEWAY_PORT, port).apply()
        }
        fun getGatewayPort(): Int = GatewayApplication.getInstance().getSharedPreferences(PREF_NAME, 0).getInt(KEY_GATEWAY_PORT, DEFAULT_PORT)

        fun saveGatewayConfig(key: String, value: String) {
            GatewayApplication.getInstance().getSharedPreferences(PREF_NAME, 0).edit().putString("config_$key", value).apply()
        }
        fun getGatewayConfig(key: String, default: String = ""): String {
            return GatewayApplication.getInstance().getSharedPreferences(PREF_NAME, 0).getString("config_$key", default) ?: default
        }

        fun saveProxyEnabled(enabled: Boolean) {
            GatewayApplication.getInstance().getSharedPreferences(PREF_NAME, 0).edit().putBoolean(KEY_PROXY_ENABLED, enabled).apply()
        }
        fun isProxyEnabled(): Boolean = GatewayApplication.getInstance().getSharedPreferences(PREF_NAME, 0).getBoolean(KEY_PROXY_ENABLED, false)

        fun saveProxyConfig(protocol: String, host: String, port: Int, username: String = "", password: String = "") {
            val e = GatewayApplication.getInstance().getSharedPreferences(PREF_NAME, 0).edit()
            e.putString(KEY_PROXY_PROTOCOL, protocol).putString(KEY_PROXY_HOST, host).putInt(KEY_PROXY_PORT, port).apply()
        }
        fun getProxyHost(): String = GatewayApplication.getInstance().getSharedPreferences(PREF_NAME, 0).getString(KEY_PROXY_HOST, "127.0.0.1") ?: "127.0.0.1"
        fun getProxyPort(): Int = GatewayApplication.getInstance().getSharedPreferences(PREF_NAME, 0).getInt(KEY_PROXY_PORT, DEFAULT_PROXY_PORT)
        fun getProxyProtocol(): String = GatewayApplication.getInstance().getSharedPreferences(PREF_NAME, 0).getString(KEY_PROXY_PROTOCOL, "HTTP") ?: "HTTP"

        fun saveProxyListJson(json: String) {
            GatewayApplication.getInstance().getSharedPreferences(PREF_NAME, 0).edit().putString(KEY_PROXY_LIST_JSON, json).apply()
        }
        fun getProxyListJson(): String = GatewayApplication.getInstance().getSharedPreferences(PREF_NAME, 0).getString(KEY_PROXY_LIST_JSON, "") ?: ""

        private fun saveWakeEnabled(enabled: Boolean) {
            GatewayApplication.getInstance().getSharedPreferences(PREF_NAME, 0).edit().putBoolean(KEY_WAKE_ENABLED, enabled).apply()
        }
        private fun getWakeEnabled(): Boolean = GatewayApplication.getInstance().getSharedPreferences(PREF_NAME, 0).getBoolean(KEY_WAKE_ENABLED, false)

        fun start() {
            GatewayApplication.getInstance().startForegroundService(Intent(GatewayApplication.getInstance(), GatewayForegroundService::class.java))
        }
        fun stop() {
            GatewayApplication.getInstance().stopService(Intent(GatewayApplication.getInstance(), GatewayForegroundService::class.java))
        }

        // ★ API密钥验证配置
        fun getRequireApiKey(): Boolean = getGatewayConfig("require_api_key", "false").toBooleanStrictOrNull() ?: false
        fun saveRequireApiKey(enabled: Boolean) = saveGatewayConfig("require_api_key", enabled.toString())
        fun getAllowedApiKeys(): Set<String> {
            val keysStr = getGatewayConfig("allowed_api_keys", "")
            return keysStr.split(",").map { it.trim() }.filter { it.isNotBlank() }.toSet()
        }
        fun saveAllowedApiKeys(keys: Set<String>) = saveGatewayConfig("allowed_api_keys", keys.joinToString(","))

        // ★★★ 流量统计持久化（总流量持久化，通知栏流量不持久化）★★★
        fun saveTraffic() {
            GatewayApplication.getInstance().getSharedPreferences(PREF_NAME, 0).edit()
                .putLong(KEY_TRAFFIC_UPLOAD + "_total", totalUploadBytes.get())
                .putLong(KEY_TRAFFIC_DOWNLOAD + "_total", totalDownloadBytes.get())
                .apply()
        }
        fun getSavedTraffic(type: String): Long {
            val sp = GatewayApplication.getInstance().getSharedPreferences(PREF_NAME, 0)
            return when (type) {
                "total_upload" -> sp.getLong(KEY_TRAFFIC_UPLOAD + "_total", 0L)
                "total_download" -> sp.getLong(KEY_TRAFFIC_DOWNLOAD + "_total", 0L)
                else -> 0L
            }
        }
        
        /** ★★ 切换模型时调用：通知栏流量清零重新累计，APP内总统计不变 ★★ */
        fun resetNotificationTraffic() {
            trafficUploadBytes.set(0L)
            trafficDownloadBytes.set(0L)
        }
    }
}
