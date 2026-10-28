package com.qtwl.gateway.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qtwl.gateway.local.LocalModelManager
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * 本地模型页 —— 引擎管理 / 启停状态 / 模型商城下载 / 已安装模型
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalModelScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val engineStatus by LocalModelManager.engineStatus.collectAsState()
    val serverRunning by LocalModelManager.serverRunning.collectAsState()
    val serverPid by LocalModelManager.serverPid.collectAsState()
    val models by LocalModelManager.models.collectAsState()
    val storeModels by LocalModelManager.storeModels.collectAsState()
    val storeLoading by LocalModelManager.storeLoading.collectAsState()
    val download by LocalModelManager.download.collectAsState()
    val lastLog by LocalModelManager.lastLog.collectAsState()
    val shizukuOk by remember { mutableStateOf(LocalModelManager.shizukuAvailable()) }

    var selectedModel by remember { mutableStateOf<String?>(null) }
    var showStore by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        LocalModelManager.scanModels(context)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("🧠 本地模型") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        },
        floatingActionButton = {
            if (!showStore) {
                ExtendedFloatingActionButton(
                    onClick = { showStore = true },
                    icon = { Text("🛍️") },
                    text = { Text("模型商城") }
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ===== 引擎状态卡 =====
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("⚙️ 本地推理引擎 (llama.cpp)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.weight(1f))
                        // 状态灯
                        val color = when {
                            serverRunning -> MaterialTheme.colorScheme.primary
                            engineStatus == "已就绪" || engineStatus == "已停止" -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.error
                        }
                        Surface(color = color.copy(alpha = 0.15f), shape = MaterialTheme.shapes.small) {
                            Text("  $engineStatus  ", color = color, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(4.dp))
                        }
                    }

                    if (serverRunning) {
                        Text("🔄 运行中  PID=$serverPid  端口 18080", style = MaterialTheme.typography.bodySmall)
                    }
                    if (!shizukuOk) {
                        Text("⚠️ Shizuku 未授权/未运行，请先在 Shizuku 应用或系统设置中启动并授权", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { scope.launch { LocalModelManager.installEngine() } },
                            enabled = !engineStatus.startsWith("下载"),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(if (LocalModelManager.isEngineInstalled()) "↻ 更新引擎" else "⬇ 安装引擎")
                        }
                        Button(
                            onClick = {
                                scope.launch {
                                    if (serverRunning) LocalModelManager.stopServer()
                                    else LocalModelManager.startServer(context, selectedModel)
                                }
                            },
                            enabled = LocalModelManager.isEngineInstalled(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (serverRunning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                            ),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(if (serverRunning) "⏹ 停止" else "▶ 启动")
                        }
                    }

                    // 已装模型选择
                    if (models.isNotEmpty()) {
                        Text("已安装模型（点击选择要加载的）", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        models.forEach { m ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(MaterialTheme.shapes.small)
                                    .clickable { selectedModel = m.name }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(selected = selectedModel == m.name, onClick = { selectedModel = m.name })
                                Text(m.name, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                Text(formatSize(m.size), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (m.isLoaded) Text("  ✅加载中", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }

            // ===== 商城 =====
            if (showStore) {
                Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("🛍️ 模型商城 (HuggingFace)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { showStore = false }) { Text("关闭") }
                        }
                        Text("自动获取 GGUF 模型，点击下载到本地", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            // 快捷搜索
                            listOf("qwen2.5 gguf", "llama3.2 gguf", "deepseek gguf", "gemma2 gguf").forEach { q ->
                                OutlinedButton(onClick = { scope.launch { LocalModelManager.loadStore(q) } }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                                    Text(q.removeSuffix(" gguf"), fontSize = 12.sp)
                                }
                            }
                        }
                        Button(
                            onClick = { scope.launch { LocalModelManager.loadStore() } },
                            enabled = !storeLoading,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (storeLoading) "加载中..." else "🔄 拉取模型列表")
                        }

                        if (storeLoading) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }

                        LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                            items(storeModels, key = { it.downloadUrl }) { sm ->
                                StoreModelRow(sm = sm, isDownloading = download?.fileName == sm.fileName && download?.active == true) {
                                    scope.launch { LocalModelManager.downloadModel(context, sm) }
                                }
                            }
                        }

                        // 下载进度
                        download?.let { d ->
                            if (d.active || d.done) {
                                Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Text(d.fileName, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, maxLines = 1)
                                        if (d.active) {
                                            LinearProgressIndicator(
                                                progress = { if (d.totalBytes > 0) d.downloadedBytes.toFloat() / d.totalBytes else 0f },
                                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                                            )
                                            Text("${formatSize(d.downloadedBytes)} / ${formatSize(d.totalBytes)}  (${(d.downloadedBytes * 100 / (d.totalBytes.coerceAtLeast(1)))}%)", style = MaterialTheme.typography.labelSmall)
                                        }
                                        when {
                                            d.done -> Text("✅ 下载完成", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                            d.error.isNotBlank() -> Text("❌ ${d.error}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ===== 运行日志 =====
            if (lastLog.isNotBlank()) {
                Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("📄 引擎日志", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        Text(lastLog.take(600), style = MaterialTheme.typography.labelSmall, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun StoreModelRow(sm: com.qtwl.gateway.local.LocalModelManager.StoreModel, isDownloading: Boolean, onDownload: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(sm.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(sm.fileName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                Row {
                    if (sm.quant.isNotBlank()) {
                        Surface(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), shape = MaterialTheme.shapes.small) {
                            Text(" ${sm.quant} ", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp))
                        }
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(formatSize(sm.size), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Button(onClick = onDownload, enabled = !isDownloading) {
                Text(if (isDownloading) "下载中" else "⬇ 下载")
            }
        }
    }
}

private fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "未知"
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1 -> String.format(Locale.US, "%.2f GB", gb)
        mb >= 1 -> String.format(Locale.US, "%.1f MB", mb)
        else -> String.format(Locale.US, "%.0f KB", kb)
    }
}