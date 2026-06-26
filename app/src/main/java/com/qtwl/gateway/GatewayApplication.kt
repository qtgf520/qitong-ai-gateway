package com.qtwl.gateway

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.qtwl.gateway.data.db.AppDatabase
import com.qtwl.gateway.utils.TranslationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class GatewayApplication : Application() {

    /** 全局数据库实例 */
    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }

    /** 应用级协程作用域 */
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        instance = this
        TranslationManager.init(this)  // ★ 初始化多语言
        createNotificationChannel()
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
