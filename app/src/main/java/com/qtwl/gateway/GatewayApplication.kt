package com.qtwl.gateway

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.qtwl.gateway.data.db.AppDatabase
import com.qtwl.gateway.service.GatewayForegroundService
import com.qtwl.gateway.utils.TranslationManager
import com.qtwl.gateway.utils.CrashHandler
import com.qtwl.gateway.local.LocalModelManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class GatewayApplication : Application() {

    /** 全局数据库实例 */
    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }

    /** 应用级协程作用域 */
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        instance = this
        CrashHandler.init(this)  // ★ 初始化全局崩溃捕获
        TranslationManager.init(this)  // ★ 初始化多语言
        createNotificationChannel()
        // ★★ 初始化 Shizuku（本地模型保活用），授权状态变化时自动更新 ★★
        try {
            rikka.shizuku.Shizuku.addBinderReceivedListenerSticky {
                // Shizuku 服务连接后自动请求权限（本地模型需要）
                try { rikka.shizuku.Shizuku.requestPermission(1001) } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
        // ★★ 启动本地模型保活心跳（后台线程轮询，检测 server 是否掉线并尝试拉起）★★
        applicationScope.launch(Dispatchers.IO) {
            var lastLocalRunning = false
            while (true) {
                kotlinx.coroutines.delay(30_000)  // 每30秒检查一次
                try {
                    if (rikka.shizuku.Shizuku.pingBinder()) {
                        val isRunning = LocalModelManager.refreshStatus()
                        if (lastLocalRunning && !isRunning) {
                            // 曾运行但掉了 → 尝试自愈（重新拉起 server）
                            kotlinx.coroutines.withContext(Dispatchers.IO) {
                                LocalModelManager.autoRestart()
                            }
                        }
                        lastLocalRunning = isRunning
                    }
                } catch (_: Throwable) {}
            }
        }
        // ★★ 从 SharedPreferences 恢复上次网关运行状态（进程重建时最可靠的初始化）★★
        if (GatewayForegroundService.getGatewayWasRunning()) {
            GatewayForegroundService.isServiceRunning = true
        }
        // ★★ 恢复定时备份 WorkManager 调度 ★★
        if (GatewayForegroundService.getGatewayConfig("auto_backup_enabled", "false").toBoolean()) {
            val hour = GatewayForegroundService.getGatewayConfig("auto_backup_hour", "3").toIntOrNull() ?: 3
            val minute = GatewayForegroundService.getGatewayConfig("auto_backup_minute", "0").toIntOrNull() ?: 0
            com.qtwl.gateway.data.db.AutoBackupWorker.schedule(this, hour, minute)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.channel_description)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "gateway_service_channel"

        @Volatile
        private var instance: GatewayApplication? = null

        fun getInstance(): GatewayApplication =
            instance ?: throw IllegalStateException("GatewayApplication not initialized")
    }
}
