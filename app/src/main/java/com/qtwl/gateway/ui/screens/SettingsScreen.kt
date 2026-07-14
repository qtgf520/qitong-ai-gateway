package com.qtwl.gateway.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.qtwl.gateway.ui.viewmodel.GatewayViewModel
import com.qtwl.gateway.utils.localizedText

/**
 * 设置屏幕 —— 极简模式
 * 仅保留后台权限引导，代理配置已隐藏（连点3下触发）
 */
@Composable
fun SettingsScreen(
    viewModel: GatewayViewModel = viewModel(factory = GatewayViewModel.Factory())
) {
    com.qtwl.gateway.utils.TranslationManager.currentLanguageFlow.collectAsState().value
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ========== 标题 ==========
        Text(
            text = localizedText("⚙️ 设置", "⚙️ Settings"),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
        )

        // ========== 后台权限卡片 ==========
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = localizedText("🔋 后台权限管理", "🔋 Background permission management"),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                )
                Text(
                    text = localizedText("确保应用在后台稳定运行，不被系统杀死。将尝试：\\n", "Keep the app running reliably in the background and prevent the system from killing it. Will try to:\\n") +
                            localizedText("1️⃣ 加入电池优化白名单\\n", "1️⃣ Add to the battery optimization whitelist\\n") +
                            localizedText("2️⃣ 允许后台运行\\n", "2️⃣ Allow background running\\n") +
                            localizedText("3️⃣ 忽略电池优化（需要 Root）", "3️⃣ Ignore battery optimization (requires root)"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Button(
                    onClick = { viewModel.bindBackgroundPermissions() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary
                    )
                ) {
                    Text(localizedText("🔗 绑定后台权限", "🔗 Bind background permissions"))
                }

                // 提示信息
                Surface(
                    color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.1f),
                    shape = MaterialTheme.shapes.small
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary
                        )
                        Text(
                            text = localizedText("部分操作需要 Root 权限。非 Root 设备请手动在「系统设置 → 应用 → 綦桐AI网关 → 电池」中设置为「无限制」。", "Some actions require root. On non-rooted devices, manually set System Settings → Apps → QiTong AI Gateway → Battery to “Unrestricted”."),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // ========== 代理隐藏触发提示 ==========
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = localizedText("🤫 代理加速", "🤫 Proxy acceleration"),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                )
                Text(
                    text = localizedText("代理配置已隐藏，在「关于」页面连点 3 下可快速开关代理加速。\\n开启后所有大模型 API 请求将自动经过代理转发。", "Proxy configuration is hidden. Tap the About page 3 times to quickly toggle proxy acceleration.\\nWhen enabled, all LLM API requests are automatically forwarded through the proxy."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}
