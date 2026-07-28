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
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import com.qtwl.gateway.data.model.TokenUsage
import com.qtwl.gateway.data.model.SpeedHistory
import com.qtwl.gateway.data.model.routeKey
import com.qtwl.gateway.service.GatewayForegroundService
import com.qtwl.gateway.ui.theme.Error
import com.qtwl.gateway.ui.theme.Online
import com.qtwl.gateway.ui.theme.Warning
import com.qtwl.gateway.ui.viewmodel.GatewayViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.qtwl.gateway.utils.localizedText
import com.qtwl.gateway.utils.localizeRuntimeText
import kotlinx.coroutines.delay

/**
 * 用量统计屏幕 —— Token消耗监控面板
 * 对标 One-API 的额度统计功能
 */
@Composable
fun StatsScreen(viewModel: GatewayViewModel) {
    val languageTick = com.qtwl.gateway.utils.TranslationManager.currentLanguageFlow.collectAsState().value
    val allTokenUsage by viewModel.allTokenUsage.collectAsState()
    val totalPromptTokens by viewModel.totalPromptTokens.collectAsState()
    val totalCompletionTokens by viewModel.totalCompletionTokens.collectAsState()
    val totalTokensAll by viewModel.totalTokensAll.collectAsState()
    val providers by viewModel.providers.collectAsState()
    val snackbarMessage by viewModel.snackbarMessage.collectAsState()
// 网关流量统计
    val gwUpload = com.qtwl.gateway.service.GatewayForegroundService.trafficUploadBytes.get()
    val gwDownload = com.qtwl.gateway.service.GatewayForegroundService.trafficDownloadBytes.get()
    val gwTotalUpload = com.qtwl.gateway.service.GatewayForegroundService.totalUploadBytes.get()
    val gwTotalDownload = com.qtwl.gateway.service.GatewayForegroundService.totalDownloadBytes.get()
    // 当前活跃模型（轮询方式）
    var activeModel by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        while (true) {
            activeModel = com.qtwl.gateway.service.GatewayForegroundService.activeNodeName
            delay(2000)
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }

    // 处理 Snackbar
    LaunchedEffect(snackbarMessage) {
        snackbarMessage?.let {
            snackbarHostState.showSnackbar(localizeRuntimeText(it))
            viewModel.clearSnackbar()
        }
    }

    // ★★ 测速历史趋势图 ★★
    val latestSpeedHistory by viewModel.latestSpeedHistory.collectAsState()
    val selectedModelHistory by viewModel.selectedModelHistory.collectAsState()
    val selectedHistoryModelKey by viewModel.selectedHistoryModelKey.collectAsState()
    // 模型选择器状态
    var showModelSelector by remember { mutableStateOf(false) }
    val enabledModels by viewModel.enabledModels.collectAsState()
    // 图表类型切换：0=TTFT, 1=TPS, 2=总耗时
    var chartMetricIndex by remember { mutableIntStateOf(0) }
    val chartMetrics = listOf(
        localizedText("TTFT (ms)", "TTFT (ms)"),
        localizedText("TPS", "TPS"),
        localizedText("总耗时 (ms)", "Total (ms)")
    )

    // 按服务商/模型分组的用量汇总
    val statsByProvider = remember(allTokenUsage, providers, languageTick) {
        val providerMap = providers.associateBy { it.id }
        allTokenUsage
            .groupBy { it.providerId }
            .mapValues { (providerId, usages) ->
                val providerName = providerMap[providerId]?.name ?: localizedText("未知(ID:", "Unknown (ID:") + providerId + ")"
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
                    text = localizedText("📊 Token 用量总览", "📊 Token usage overview"),
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
                            text = localizedText("总计消耗", "Total usage"),
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

            // ==================== 测速历史趋势图 ====================
            item {
                SpeedTrendChartCard(
                    viewModel = viewModel,
                    latestSpeedHistory = latestSpeedHistory,
                    selectedModelHistory = selectedModelHistory,
                    selectedHistoryModelKey = selectedHistoryModelKey,
                    enabledModels = enabledModels,
                    chartMetricIndex = chartMetricIndex,
                    chartMetrics = chartMetrics,
                    onChartMetricChange = { chartMetricIndex = it },
                    onShowModelSelector = { showModelSelector = true }
                )
            }

            // ==================== 网关流量卡片 ====================
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(localizedText("🌐 网关流量统计", "🌐 Gateway traffic statistics"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(localizedText("⬆ 上传", "⬆ Upload"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(formatBytes(gwUpload), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.height(4.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(localizedText("⬇ 下载", "⬇ Download"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(formatBytes(gwDownload), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                        }
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(localizedText("📈 总上传", "📈 Total upload"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(formatBytes(gwTotalUpload), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.height(4.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(localizedText("📈 总下载", "📈 Total download"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(formatBytes(gwTotalDownload), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                        }
                        if (activeModel.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(localizedText("🧠 当前模型", "🧠 Current model"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(activeModel, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }

            // ==================== 明细统计卡片 ====================
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = localizedText("明细统计", "Detailed statistics"),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        StatRow(localizedText("输入 Tokens (Prompt)", "Input tokens (prompt)"), formatTokenCount(totalPromptTokens), Online)
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        StatRow(localizedText("输出 Tokens (Completion)", "Output tokens (completion)"), formatTokenCount(totalCompletionTokens), MaterialTheme.colorScheme.primary)
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        StatRow(localizedText("API 调用次数", "API call count"), "${allTokenUsage.size}", MaterialTheme.colorScheme.onSurface)
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        StatRow(localizedText("平均每次消耗", "Average per call"), formatAverage(totalTokensAll, allTokenUsage.size), Warning)
                    }
                }
            }

            // ==================== 按服务商统计 ====================
            if (statsByProvider.isNotEmpty()) {
                item {
                    Text(
                        text = localizedText("🏢 按服务商统计", "🏢 By provider"),
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
                        text = localizedText("🤖 按模型统计 (Top 10)", "🤖 By model (Top 10)"),
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
                        text = localizedText("📋 最近用量记录", "📋 Recent usage records"),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }

                items(allTokenUsage.take(20), key = { it.id }) { usage ->
                    UsageRecordCard(
                        usage = usage,
                        providerName = providers.firstOrNull { it.id == usage.providerId }?.name
                            ?: localizedText("未知", "Unknown")
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
                                text = localizedText("暂无用量数据", "No usage data yet"),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = localizedText("发送聊天消息后会自动记录 Token 消耗", "Token usage will be recorded automatically after sending chat messages"),
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
                        Text(localizedText("刷新", "Refresh"))
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
                        Text(localizedText("清除数据", "Clear data"))
                    }
                    // ★★ 新增：清除总流量统计按钮 ★★
                    OutlinedButton(
                        onClick = {
                            com.qtwl.gateway.service.GatewayForegroundService.clearTotalTraffic()
                            viewModel.refreshTokenStats()
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = Error
                        )
                    ) {
                        Icon(
                            Icons.Default.DeleteSweep,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(localizedText("清空流量", "Clear traffic"))
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
                Text(localizedText("确认清除", "Confirm clear"), fontWeight = FontWeight.Bold)
            },
            text = {
                Text(localizedText("将删除所有 Token 用量记录，此操作不可恢复。确定继续吗？", "This will delete all token usage records and cannot be undone. Continue?"))
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.clearAllUsage()
                        showClearConfirm = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Error)
                ) {
                    Text(localizedText("确认清除", "Confirm clear"))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text(localizedText("取消", "Cancel"))
                }
            }
        )
    }
    // ==================== 模型选择器对话框 ====================
    if (showModelSelector) {
        AlertDialog(
            onDismissRequest = { showModelSelector = false },
            title = { Text(localizedText("选择模型查看趋势", "Select model for trend"), fontWeight = FontWeight.Bold) },
            text = {
                LazyColumn {
                    items(enabledModels) { model ->
                        Surface(
                            modifier = Modifier.fillMaxWidth().clickable {
                                viewModel.loadModelHistory(model.routeKey)
                                showModelSelector = false
                            },
                            color = if (selectedHistoryModelKey == model.routeKey)
                                MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surface
                        ) {
                            Text(
                                text = model.customAlias.ifBlank { model.displayName },
                                modifier = Modifier.padding(12.dp),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showModelSelector = false }) {
                    Text(localizedText("取消", "Cancel"))
                }
            }
        )
    }
}

// ============================================================
// 测速历史趋势图卡片
// ============================================================
@Composable
private fun SpeedTrendChartCard(
    viewModel: GatewayViewModel,
    latestSpeedHistory: List<SpeedHistory>,
    selectedModelHistory: List<SpeedHistory>,
    selectedHistoryModelKey: String?,
    enabledModels: List<com.qtwl.gateway.data.model.AiModel>,
    chartMetricIndex: Int,
    chartMetrics: List<String>,
    onChartMetricChange: (Int) -> Unit,
    onShowModelSelector: () -> Unit
) {
    val history = if (selectedHistoryModelKey != null) selectedModelHistory else latestSpeedHistory
    val hasData = history.isNotEmpty()

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = localizedText("📈 模型延迟趋势", "📈 Model latency trend"),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                // 选择模型按钮
                TextButton(onClick = onShowModelSelector) {
                    val selectedName = if (selectedHistoryModelKey != null) {
                        enabledModels.firstOrNull { it.routeKey == selectedHistoryModelKey }
                            ?.let { it.customAlias.ifBlank { it.displayName } } ?: selectedHistoryModelKey
                    } else {
                        localizedText("所有模型", "All models")
                    }
                    Text(selectedName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }

            // 指标切换按钮组
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                chartMetrics.forEachIndexed { index, label ->
                    FilterChip(
                        selected = chartMetricIndex == index,
                        onClick = { onChartMetricChange(index) },
                        label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (hasData) {
                // Canvas 折线图
                val lineColor = when (chartMetricIndex) {
                    0 -> Color(0xFFFF6B35)  // TTFT - 橙色
                    1 -> Color(0xFF2196F3)  // TPS - 蓝色
                    else -> Color(0xFF4CAF50) // 总耗时 - 绿色
                }

                // 获取指标值
                val values = history.map { h ->
                    when (chartMetricIndex) {
                        0 -> h.ttftMs.toFloat()
                        1 -> h.tps.toFloat()
                        else -> h.totalMs.toFloat()
                    }
                }
                val maxVal = values.maxOrNull()?.coerceAtLeast(1f) ?: 1f
                val minVal = values.minOrNull()?.coerceAtMost(maxVal - 1f) ?: 0f
                val range = (maxVal - minVal).coerceAtLeast(1f)

                // 时间标签
                val timeLabels = history.map { h ->
                    val sdf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                    sdf.format(java.util.Date(h.measuredAt))
                }

                Card(
                    modifier = Modifier.fillMaxWidth().height(200.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    )
                ) {
                    Canvas(
                        modifier = Modifier.fillMaxSize().padding(8.dp)
                    ) {
                        val w = size.width
                        val h = size.height
                        if (values.size < 2) {
                            // 数据点太少，画一条水平线
                            drawLine(
                                color = lineColor,
                                start = Offset(0f, h / 2),
                                end = Offset(w, h / 2),
                                strokeWidth = 2f
                            )
                            return@Canvas
                        }
                        val step = w / (values.size - 1).coerceAtLeast(1)

                        // 绘制网格线（3条水平参考线）
                        val gridColor = Color.Gray.copy(alpha = 0.2f)
                        for (i in 0..3) {
                            val y = h * i / 3
                            drawLine(gridColor, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
                        }

                        // 绘制折线路径
                        val path = Path()
                        values.forEachIndexed { i, v ->
                            val x = i * step
                            val y = h - ((v - minVal) / range) * (h - 16f) - 8f
                            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                        }

                        drawPath(
                            path,
                            color = lineColor,
                            style = Stroke(
                                width = 2.5f,
                                cap = StrokeCap.Round,
                                join = StrokeJoin.Round
                            )
                        )

                        // 绘制数据点
                        values.forEachIndexed { i, v ->
                            val x = i * step
                            val y = h - ((v - minVal) / range) * (h - 16f) - 8f
                            drawCircle(lineColor, radius = 3f, center = Offset(x, y))
                        }
                    }
                }

                // 统计摘要
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    val metricValues = history.map { h ->
                        when (chartMetricIndex) {
                            0 -> h.ttftMs.toDouble()
                            1 -> h.tps
                            else -> h.totalMs.toDouble()
                        }
                    }
                    val avg = metricValues.average()
                    val last = metricValues.lastOrNull() ?: 0.0
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(localizedText("最新", "Latest"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val lastStr = if (chartMetricIndex == 1) "%.1f".format(last) else "%.0f".format(last)
                        Text(lastStr, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = lineColor)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(localizedText("平均", "Avg"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val avgStr = if (chartMetricIndex == 1) "%.1f".format(avg) else "%.0f".format(avg)
                        Text(avgStr, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(localizedText("数据点", "Points"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${history.size}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                    }
                }
            } else {
                // 空状态
                Box(
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = localizedText("暂无测速数据\n运行测速后自动生成趋势图", "No speed data yet\nRun speed test to generate trend chart"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
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
                        text = summary.callCount.toString() + localizedText(" 次调用", " calls"),
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
                    text = localizedText("总消耗", "Total usage"),
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
                    text = callCount.toString() + localizedText(" 次调用", " calls"),
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
                        text = localizedText("合计 ", "Total ") + usage.totalTokens,
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
