package com.qtwl.gateway

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.qtwl.gateway.data.db.AppDatabase
import com.qtwl.gateway.service.GatewayForegroundService
import com.qtwl.gateway.utils.TranslationManager
import com.qtwl.gateway.utils.CrashHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class GatewayApplication : Application() {

    /** 全局数据库实例 */
    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }

    /** 应用级协程作用域 */
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /**
     * 主题配置是否已从 SharedPreferences 读入。
     *
     * 为什么需要：Activity 会因旋转屏幕、切换深色模式、分屏、字号变化而重建，
     * 每次重建都会走 onCreate。如果每次都调 ThemeState.load()，
     * 而用户刚用 setStyle() 改了内存里的值（SP 的 apply 是异步落盘），
     * load() 可能读到旧值，表现为「切了主题一旋转又变回去」。
     * 用进程级标记保证整个进程生命周期内只读一次 SP。
     */
    var themeLoaded: Boolean = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        CrashHandler.init(this)  // ★ 初始化全局崩溃捕获
        TranslationManager.init(this)  // ★ 初始化多语言
        createNotificationChannel()
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