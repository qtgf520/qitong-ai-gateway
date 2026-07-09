package com.qtwl.gateway.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.qtwl.gateway.capture.PacketCapture
import com.qtwl.gateway.capture.PacketRecord
import com.qtwl.gateway.ui.viewmodel.GatewayViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PacketCaptureScreen(
    viewModel: GatewayViewModel = viewModel(factory = GatewayViewModel.Factory())
) {
    var selectedRecord by remember { mutableStateOf<PacketRecord?>(null) }
    var filterText by remember { mutableStateOf("") }
    var statusFilter by remember { mutableStateOf<String?>(null) }
    var showClearConfirm by remember { mutableStateOf(false) }

    // 实时刷新
    val (records, setRecords) = remember { mutableStateOf(PacketCapture.records) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1500)
            setRecords(PacketCapture.records)
        }
    }

    // 筛选
    val filteredRecords = remember(records, filterText, statusFilter) {
        var list = records
        if (statusFilter == "200") list = list.filter { it.response?.httpStatus == 200 }
        else if (statusFilter == "4xx") list = list.filter { it.response?.httpStatus in 400..499 }
        else if (statusFilter == "5xx") list = list.filter { it.response?.httpStatus in 500..599 }
        else if (statusFilter == "FAIL") list = list.filter { it.failover != null }
        if (filterText.isNotBlank()) list = list.filter {
            it.summary.contains(filterText, ignoreCase = true) ||
            it.outbound?.body?.contains(filterText, ignoreCase = true) == true ||
            it.inbound?.body?.contains(filterText, ignoreCase = true) == true
        }
        list
    }

    val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(8.dp)
    ) {
        // 标题行
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("🔍 抓包日志", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            if (showClearConfirm) {
                Row {
                    TextButton(onClick = { showClearConfirm = false }) { Text("取消") }
                    TextButton(onClick = { PacketCapture.clear(); showClearConfirm = false }) { Text("确认清空") }
                }
            } else {
                TextButton(onClick = { showClearConfirm = true }) { Text("🗑 清空") }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 筛选栏
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val (searchText, setSearchText) = remember { mutableStateOf("") }
            OutlinedTextField(
                value = searchText,
                onValueChange = setSearchText,
                placeholder = { Text("🔍 搜索关键词") },
                singleLine = true,
                maxLines = 1,
                modifier = Modifier.weight(2f),
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchText.isNotBlank()) {
                        IconButton(onClick = { setSearchText("") }) { Icon(Icons.Default.Clear, contentDescription = null) }
                    }
                },
                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            )
            TextButton(onClick = { statusFilter = null }) { Text(if (statusFilter == null) "📋 全部" else "全部", fontWeight = if (statusFilter == null) FontWeight.Bold else FontWeight.Normal) }
            TextButton(onClick = { statusFilter = "200" }) { Text(if (statusFilter == "200") "✅ 200" else "200") }
            TextButton(onClick = { statusFilter = "4xx" }) { Text(if (statusFilter == "4xx") "⚠️ 4xx" else "4xx") }
            TextButton(onClick = { statusFilter = "5xx" }) { Text(if (statusFilter == "5xx") "❌ 5xx" else "5xx") }
            TextButton(onClick = { statusFilter = "FAIL" }) { Text(if (statusFilter == "FAIL") "🔄 FAILOVER" else "FAIL") }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 记录列表
        if (filteredRecords.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text("📭 暂无抓包记录\n先开启抓包模式再发请求", textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(filteredRecords) { record ->
                    PacketRecordCard(record, timeFmt, onClick = { selectedRecord = record })
                }
            }
        }
    }

    // 详情页
    selectedRecord?.let { record ->
        PacketCaptureDetailDialog(record = record, onDismiss = { selectedRecord = null })
    }
}

@Composable
fun PacketRecordCard(
    record: PacketRecord,
    timeFmt: SimpleDateFormat,
    onClick: () -> Unit
) {
    val summary = record.summary
    val isComplete = record.isComplete
    val isError = record.response?.httpStatus ?: 0 >= 400

    val cardColor = when {
        isError -> MaterialTheme.colorScheme.error.copy(alpha = 0.05f)
        record.failover != null -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.08f)
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
    }

    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = cardColor)
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            // 状态标签
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val statusColor = when {
                    isError -> MaterialTheme.colorScheme.error
                    record.failover != null -> MaterialTheme.colorScheme.tertiary
                    isComplete -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Text(
                    text = if (isError) "❌" else if (record.failover != null) "🔄" else if (isComplete) "✅" else "⏳",
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // 时间和tokens
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = timeFmt.format(record.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace
                )
                record.response?.let { resp ->
                    if (resp.promptTokens > 0) {
                        Text(
                            text = "↑${resp.promptTokens} ↓${resp.completionTokens}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = record.outbound?.modelId ?: "?",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
        }
    }
}

@Composable
fun PacketCaptureDetailDialog(
    record: PacketRecord,
    onDismiss: () -> Unit
) {
    var currentRecord by remember { mutableStateOf(record) }
    // 实时更新
    LaunchedEffect(record.id) {
        while (!currentRecord.isComplete) {
            delay(2000)
            val updated = PacketCapture.records.find { it.id == record.id }
            if (updated != null) currentRecord = updated
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("📦 抓包详情 #${currentRecord.id}")
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = 400.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 入站
                currentRecord.inbound?.let { inbound ->
                    item {
                        Text("📥 入站", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        CodeBlock(
                            text = buildString {
                                appendLine("时间: ${currentRecord.timestamp}")
                                appendLine("来源: ${inbound.sourceIp}")
                                appendLine("方法: ${inbound.method}")
                                appendLine("路径: ${inbound.path}")
                                appendLine("头部: ${inbound.headers}")
                                appendLine("--- 请求体 (${inbound.bodySize}B) ---")
                                appendLine(inbound.body)
                            }
                        )
                    }
                }

                // 出站
                currentRecord.outbound?.let { outbound ->
                    item {
                        Text("📤 出站", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
                        CodeBlock(
                            text = buildString {
                                appendLine("URL: ${outbound.targetUrl}")
                                appendLine("模型: ${outbound.modelId}")
                                appendLine("头部: ${outbound.headers}")
                                appendLine("--- 请求体 (${outbound.bodySize}B) ---")
                                appendLine(outbound.body)
                            }
                        )
                    }
                }

                // 响应
                currentRecord.response?.let { resp ->
                    item {
                        Text("📥 响应", fontWeight = FontWeight.Bold, color = when {
                            resp.httpStatus >= 500 -> MaterialTheme.colorScheme.error
                            resp.httpStatus >= 400 -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.primary
                        })
                        CodeBlock(
                            text = buildString {
                                appendLine("状态码: ${resp.httpStatus}")
                                appendLine("耗时: ${resp.elapsedMs}ms")
                                appendLine("模型: ${resp.modelId}")
                                appendLine("Tokens: ↑${resp.promptTokens} ↓${resp.completionTokens}")
                                appendLine("头部: ${resp.headers}")
                                appendLine("--- 响应体 (${resp.bodySize}B) ---")
                                appendLine(resp.body)
                            }
                        )
                    }
                }

                // 故障转移
                currentRecord.failover?.let { failover ->
                    item {
                        Text("🔄 故障转移", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                        CodeBlock(
                            text = buildString {
                                appendLine("最终模型: ${failover.finalModel}")
                                appendLine("最终状态: ${failover.finalStatus}")
                                appendLine("--- 尝试记录 ---")
                                failover.attempts.forEach { attempt ->
                                    appendLine("[${attempt.index}] ${attempt.modelId}: ${attempt.error} (${attempt.elapsedMs}ms)")
                                }
                            }
                        )
                    }
                }
            }
        },
        confirmButton = {}
    )
}

@Composable
fun CodeBlock(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = MaterialTheme.shapes.small
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
        ) {
            Text(
                text = text,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}