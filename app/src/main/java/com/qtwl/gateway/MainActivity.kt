package com.qtwl.gateway

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.qtwl.gateway.ui.screens.MainScreen
import com.qtwl.gateway.ui.theme.GatewayTheme
import com.qtwl.gateway.utils.localizedText

class MainActivity : AppCompatActivity() {

    private var showPermDialog by mutableStateOf(false)
    private var permMessage by mutableStateOf("")

    // ★ 通知权限请求器（Android 13+）
    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 用户已响应 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // ★★ 应用隐藏多任务设置（运行时从最近任务中移除）★★
        val hideFromRecents = com.qtwl.gateway.service.GatewayForegroundService.getGatewayConfig("hide_from_recents", "false").toBoolean()
        if (hideFromRecents) {
            try {
                val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                am.appTasks.firstOrNull()?.setExcludeFromRecents(true)
            } catch (_: Exception) {}
        }

        // ★★ 恢复测速开关状态 ★★
        val pipelineEnabled = com.qtwl.gateway.service.GatewayForegroundService.getGatewayConfig("pipeline_test_enabled", "true").toBoolean()

        // ★★ 打开软件时自动检查关键权限 ★★
        checkPermissionsOnStart()

        // ★★ 应用隐藏多任务设置（运行时从最近任务中移除）★★
        val hideFromRecents = com.qtwl.gateway.service.GatewayForegroundService.getGatewayConfig("hide_from_recents", "false").toBoolean()
        if (hideFromRecents) {
            try {
                val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                am.appTasks.firstOrNull()?.setExcludeFromRecents(true)
            } catch (_: Exception) {}
        }

        // ★★ 恢复测速开关状态 ★★
        val pipelineEnabled = com.qtwl.gateway.service.GatewayForegroundService.getGatewayConfig("pipeline_test_enabled", "true").toBoolean()

        setContent {
            GatewayTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen()
                }

                // ★★ 权限缺失弹窗 ★★
                if (showPermDialog) {
                    AlertDialog(
                        onDismissRequest = { showPermDialog = false },
                        title = { Text(localizedText("⚙️ 优化建议", "⚙️ Optimization suggestion")) },
                        text = { Text(permMessage) },
                        confirmButton = {
                            Button(onClick = {
                                showPermDialog = false
                                // 引导用户去电池优化设置
                                try {
                                    val intent = Intent(
                                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                                    ).apply {
                                        data = android.net.Uri.fromParts("package", packageName, null)
                                    }
                                    startActivity(intent)
                                } catch (_: Exception) {
                                    // 降级到应用详情页
                                    try {
                                        val intent = Intent(
                                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                                        ).apply {
                                            data = android.net.Uri.fromParts("package", packageName, null)
                                        }
                                        startActivity(intent)
                                    } catch (_: Exception) {}
                                }
                            }) {
                                Text(localizedText("去设置", "Go to settings"))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showPermDialog = false }) {
                                Text(localizedText("稍后", "Later"))
                            }
                        }
                    )
                }
            }
        }
    }

    /** 启动时检查关键权限，缺失则弹窗引导 */
    private fun checkPermissionsOnStart() {
        val missingPerms = mutableListOf<String>()

        // 1. 通知权限 (Android 13+)
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                missingPerms.add(localizedText("通知权限", "Notification permission"))
                // 尝试直接请求
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // 2. 忽略电池优化（后台保活关键）
        if (Build.VERSION.SDK_INT >= 23) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                missingPerms.add(localizedText("忽略电池优化", "Battery optimization whitelist"))
            }
        }

        // 3. 检查唤醒保活是否已开启（没有则建议用户开启）
        val wakeEnabled = com.qtwl.gateway.service.GatewayForegroundService.run {
            com.qtwl.gateway.GatewayApplication.getInstance()
                .getSharedPreferences("gateway_config", Context.MODE_PRIVATE)
                .getBoolean("wake_enabled", false)
        }
        if (!wakeEnabled) {
            missingPerms.add(localizedText("🔌 唤醒保活（通知栏开启）", "🔌 Keep-alive mode (enable in notification)"))
        }

        // 如果有缺失，生成提示消息
        if (missingPerms.isNotEmpty()) {
            permMessage = localizedText(
                "检测到以下功能未开启，可能影响后台运行：\n\n• " + missingPerms.joinToString("\n• ") + "\n\n建议开启以确保网关稳定运行。",
                "The following features are not enabled and may affect background operation:\n\n• " + missingPerms.joinToString("\n• ") + "\n\nEnable them to ensure stable gateway operation."
            )
            showPermDialog = true
        }
    }
}