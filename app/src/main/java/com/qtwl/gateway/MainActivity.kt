package com.qtwl.gateway

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.qtwl.gateway.service.GatewayForegroundService
import com.qtwl.gateway.ui.screens.MainScreen
import com.qtwl.gateway.ui.theme.GatewayTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 注意：不再自动启动网关服务
        // 用户通过 UI 中的「启动/停止」按钮手动控制

        setContent {
            GatewayTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 注意：不要在这里停止服务，让服务在后台继续运行
    }
}