package com.qtwl.gateway.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qtwl.gateway.data.model.TokenUsage
import com.qtwl.gateway.service.GatewayForegroundService
import com.qtwl.gateway.ui.theme.Error
import com.qtwl.gateway.ui.theme.Online
import com.qtwl.gateway.ui.theme.Warning
import com.qtwl.gateway.ui.viewmodel.GatewayViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 用量统计屏幕 —— Token消耗监控面板
 * 对标 One-API 的额度统计功能
 */
@Composable
fun StatsScreen(viewModel: GatewayViewModel) {
    val allTokenUsage by viewModel.allTokenUsage.collectAsState()
    val totalPromptTokens by viewModel.totalPromptTokens.collectAsState()
    val totalCompletionTokens by viewModel.totalCompletionTokens.collectAsState()
    val totalTokensAll by viewModel.totalTokensAll.collectAsState()
    val providers by viewModel.providers.collectAsState()
    val snackbarMessage by viewModel.snackbarMessage.collectAsState()
    // 网关流量统计
    val gwUpload = com.qtwl.gateway.service.GatewayForegroundService.trafficUploadBytes
    val gwDownload = com.qtwl.gateway.service.GatewayForegroundService.trafficDownloadBytes

    val snackbarHostState = remember { SnackbarHostState() }

    // 处理 Snackbar
    LaunchedEffect(snackbarMessage) {
        snackbarMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearSnackbar()
        }
    }

    // 按服务商/模型分组的用量汇总
    val statsByProvider = remember(allTokenUsage, providers) {
        val providerMap = providers.associateBy { it.id }
        allTokenUsage
            .groupBy { it.providerId }
            .mapValues { (providerId, usages) ->
                val providerName = providerMap[providerId]?.name ?: "未知(ID:$providerId)"
                val totalPrompt = usages.sumOf { it.promptTokens }
                val totalCompletion = usages.sumOf { it.completionTokens }
                val total = usages.sumOf { it.totalTokens }
                val count = usages.size
                ProviderTokenSummary(providerName, totalPrompt, totalCompletion, total, count)
            }
            .entries
            .sortedByDescending { it.value.totalTokens }
    }

    // 按模型分组的用量
    val statsByModel = remember(allTokenUsage) {
        allTokenUsage
            .groupBy { it.modelId }
            .mapValues { (_, usages) ->
                val total = usages.sumOf { it.totalTokens }
                val count = usages.size
                total to count
            }
            .entries
            .sortedByDescending { it.value.first }
    }

    var showClearConfirm by remember { mutableStateOf(false) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            // ==================== 总览卡片 ====================
            item {
                Text(
                    text = "📊 Token 用量总览",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
            }

            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "总计消耗",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = formatTokenCount(totalTokensAll),
                            style = MaterialTheme.typography.displaySmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "tokens",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                        )
                    }
                }
            }

            // ==================== 网关流量卡片 ====================
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("🌐 网关流量统计", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("⬆ 上传", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(formatBytes(gwUpload), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.height(4.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("⬇ 下载", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(formatBytes(gwDownload), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // ==================== 明细统计卡片 ====================
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "明细统计",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        StatRow("输入 Tokens (Prompt)", formatTokenCount(totalPromptTokens), Online)
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        StatRow("输出 Tokens (Completion)", formatTokenCount(totalCompletionTokens), MaterialTheme.colorScheme.primary)
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        StatRow("API 调用次数", "${allTokenUsage.size}", MaterialTheme.colorScheme.onSurface)
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        StatRow("平均每次消耗", formatAverage(totalTokensAll, allTokenUsage.size), Warning)
                    }
                }
            }

            // ==================== 按服务商统计 ====================
            if (statsByProvider.isNotEmpty()) {
                item {
                    Text(
                        text = "🏢 按服务商统计",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }

                statsByProvider.forEach { (_, summary) ->
                    item {
                        ProviderStatCard(summary = summary)
                    }
                }
            }

            // ==================== 按模型统计 ====================
            if (statsByModel.isNotEmpty()) {
                item {
                    Text(
                        text = "🤖 按模型统计 (Top 10)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }

                statsByModel.take(10).forEach { (modelId, stats) ->
                    item {
                        ModelStatCard(
                            modelId = modelId,
                            totalTokens = stats.first,
                            callCount = stats.second
                        )
                    }
                }
            }

            // ==================== 最近记录 ====================
            if (allTokenUsage.isNotEmpty()) {
                item {
                    Text(
                        text = "📋 最近用量记录",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }

                items(allTokenUsage.take(20), key = { it.id }) { usage ->
                    UsageRecordCard(
                        usage = usage,
                        providerName = providers.firstOrNull { it.id == usage.providerId }?.name
                            ?: "未知"
                    )
                }
            } else {
                // 空状态
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("📊", fontSize = 48.sp)
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "暂无用量数据",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "发送聊天消息后会自动记录 Token 消耗",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }

            // ==================== 操作按钮 ====================
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = { viewModel.refreshTokenStats() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("刷新")
                    }
                    Button(
                        onClick = { showClearConfirm = true },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Error
                        )
                    ) {
                        Icon(
                            Icons.Default.DeleteSweep,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("清除数据")
                    }
                }
                Spacer(modifier = Modifier.height(80.dp))
            }
        }
    }

    // ==================== 清除确认对话框 ====================
    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = {
                Text("确认清除", fontWeight = FontWeight.Bold)
            },
            text = {
                Text("将删除所有 Token 用量记录，此操作不可恢复。确定继续吗？")
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.clearAllUsage()
                        showClearConfirm = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Error)
                ) {
                    Text("确认清除")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text("取消")
                }
            }
        )
    }
}

// ============================================================
// 服务商用量汇总卡片
// ============================================================
@Composable
private fun ProviderStatCard(summary: ProviderTokenSummary) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "🔌",
                    fontSize = 20.sp
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = summary.providerName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = "${summary.callCount} 次调用",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 进度条
            val maxTokens = summary.totalTokens.coerceAtLeast(1)
            val promptRatio = summary.promptTokens.toFloat() / maxTokens
            val completionRatio = summary.completionTokens.toFloat() / maxTokens

            // Prompt 条
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Prompt",
                    style = MaterialTheme.typography.labelSmall,
                    color = Online,
                    modifier = Modifier.width(56.dp)
                )
                LinearProgressIndicator(
                    progress = { promptRatio.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .weight(1f)
                        .height(8.dp),
                    color = Online,
                    trackColor = Online.copy(alpha = 0.12f),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = formatTokenCount(summary.promptTokens.toLong()),
                    style = MaterialTheme.typography.labelSmall,
                    color = Online
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Completion 条
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Completion",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.width(56.dp)
                )
                LinearProgressIndicator(
                    progress = { completionRatio.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .weight(1f)
                        .height(8.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = formatTokenCount(summary.completionTokens.toLong()),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "总消耗",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = formatTokenCount(summary.totalTokens.toLong()),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

// ============================================================
// 模型用量卡片
// ============================================================
@Composable
private fun ModelStatCard(
    modelId: String,
    totalTokens: Int,
    callCount: Int
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("🤖", fontSize = 18.sp)
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = modelId,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "$callCount 次调用",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = formatTokenCount(totalTokens.toLong()),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

// ============================================================
// 单条用量记录卡片
// ============================================================
@Composable
private fun UsageRecordCard(
    usage: TokenUsage,
    providerName: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = providerName,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "·",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = usage.modelId,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row {
                    Text(
                        text = "⬆ ${usage.promptTokens}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Online
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "⬇ ${usage.completionTokens}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "合计 ${usage.totalTokens}",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Text(
                text = formatTimestamp(usage.timestamp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
    }
}

// ============================================================
// 统计行组件
// ============================================================
@Composable
private fun StatRow(
    label: String,
    value: String,
    valueColor: Color
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = valueColor
        )
    }
}

// ============================================================
// 数据模型
// ============================================================
private data class ProviderTokenSummary(
    val providerName: String,
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int,
    val callCount: Int
)

// ============================================================
// 工具函数
// ============================================================

private fun formatTokenCount(count: Long): String {
    return when {
        count >= 1_000_000 -> String.format(Locale.US, "%.1fM", count / 1_000_000.0)
        count >= 1_000 -> String.format(Locale.US, "%.1fK", count / 1_000.0)
        else -> count.toString()
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "${bytes}B"
    bytes < 1024 * 1024 -> "${bytes / 1024}KB"
    else -> "%.1fMB".format(bytes.toDouble() / (1024 * 1024))
}

private fun formatAverage(total: Long, count: Int): String {
    if (count == 0) return "0"
    val avg = total / count
    return formatTokenCount(avg)
}

private fun formatTimestamp(timestamp: Long): String {
    val sdf = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault())
    return sdf.format(Date(timestamp))
}
