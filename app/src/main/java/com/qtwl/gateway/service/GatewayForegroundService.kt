package com.qtwl.gateway.service

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.qtwl.gateway.GatewayApplication
import com.qtwl.gateway.MainActivity
import com.qtwl.gateway.R
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

/**
 * 前台服务 —— 保持网关在后台持续运行，动态通知栏显示Token和流量
 */
class GatewayForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var gatewayService: GatewayService
    private var notificationJob: Job? = null
    private var wakeEnabled = false // 是否开启唤醒保活

    override fun onCreate() {
        super.onCreate()
        val app = application as GatewayApplication
        gatewayService = GatewayService(app.database)
        wakeEnabled = getWakeEnabled()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 处理广播过来的唤醒/取消唤醒意图
        if (intent?.hasExtra(EXTRA_TOGGLE_WAKE) == true) {
            wakeEnabled = !wakeEnabled
            saveWakeEnabled(wakeEnabled)
        }

        updateNotification()

        serviceScope.launch {
            val port = getGatewayPort()
            gatewayService.start(port = port)
        }

        notificationJob?.cancel()
        notificationJob = serviceScope.launch {
            while (isActive) {
                delay(3000)
                updateNotification()
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
        var proxyText = "代理: 未开启"
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
                        proxyText = "代理: $t $h:$p"
                        if (u.isNotBlank()) proxyText += " ($u)"
                        break
                    }
                }
            } catch (_: Exception) { }
        }

        // 动态指示灯 — 监控流量（闲置时自动归零）
        val prevUpload = lastUploadBytes
        val prevDownload = lastDownloadBytes
        val currentUpload = trafficUploadBytes
        val currentDownload = trafficDownloadBytes
        
        // 如果3秒内没有新增流量，视为闲置 → 显示归零
        val hasNewTraffic = currentUpload > prevUpload || currentDownload > prevDownload
        if (!hasNewTraffic && currentUpload > 0 && currentDownload > 0) {
            // 超过一次循环无变化则归零
            if (idleCount++ > 2) {
                trafficUploadBytes = 0
                trafficDownloadBytes = 0
                tokenPromptInput = 0
                tokenCompletionOutput = 0
                activeNodeName = ""
                idleCount = 0
            }
        } else if (hasNewTraffic) {
            idleCount = 0
        }
        lastUploadBytes = currentUpload
        lastDownloadBytes = currentDownload
        
        val uploadBytes = trafficUploadBytes
        val downloadBytes = trafficDownloadBytes
        val hasTraffic = uploadBytes > 0 || downloadBytes > 0
        val nodeName = activeNodeName

        val text = buildString {
            append("端口 $port")
            append("\n输入: ${formatBytes(uploadBytes)} | 输出: ${formatBytes(downloadBytes)}")
            if (hasTraffic && nodeName.isNotBlank()) {
                append("\n🟢 $nodeName")
            } else if (hasTraffic) {
                append("\n🟡 流量传输中...")
            }
            append("\n$proxyText")
        }

        // 唤醒/取消唤醒按钮
        val toggleWakeIntent = Intent(this, GatewayForegroundService::class.java).apply {
            putExtra(EXTRA_TOGGLE_WAKE, true)
        }
        val toggleWakePI = PendingIntent.getService(this, 2, toggleWakeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val notification = NotificationCompat.Builder(this, GatewayApplication.CHANNEL_ID)
            .setContentTitle(if (wakeEnabled) "🟢 綦桐网关(保活中)" else "綦桐网关")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_sort_by_size,
                if (wakeEnabled) "取消唤醒" else "唤醒保活", toggleWakePI)
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
        gatewayService.stop()
        super.onDestroy()
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
        private const val KEY_DEBUG_MODE = "debug_mode"        // ★ 新增
        private const val EXTRA_TOGGLE_WAKE = "toggle_wake"
        private const val DEFAULT_PORT = 8889
        private const val DEFAULT_PROXY_PORT = 7890

        // 运行时流量统计（由gateway更新）
        @Volatile var tokenPromptInput: Long = 0L
        @Volatile var tokenCompletionOutput: Long = 0L
        @Volatile var trafficUploadBytes: Long = 0L
        @Volatile var trafficDownloadBytes: Long = 0L
        @Volatile var isServiceRunning: Boolean = false  // 由 start/stop 同步更新

        @Volatile var activeNodeName: String = ""
        @Volatile var lastUploadBytes: Long = 0L
        @Volatile var lastDownloadBytes: Long = 0L
        @Volatile var idleCount: Int = 0

        // ★ Debug 日志（环形缓冲区，保留最近20条）
        @Volatile var debugLogBuffer = mutableListOf<String>()
        private const val MAX_DEBUG_LOG = 20

        fun addDebugLog(msg: String) {
            synchronized(debugLogBuffer) {
                val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
                // 带上当前流量统计
                val up = trafficUploadBytes
                val down = trafficDownloadBytes
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
    }
}
