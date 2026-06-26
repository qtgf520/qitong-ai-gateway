package com.qtwl.gateway.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.qtwl.gateway.service.GatewayForegroundService
import com.qtwl.gateway.ui.theme.Error
import com.qtwl.gateway.ui.theme.Online
import com.qtwl.gateway.ui.theme.Warning
import com.qtwl.gateway.ui.viewmodel.GatewayViewModel
import com.qtwl.gateway.utils.AppLanguage
import com.qtwl.gateway.utils.TranslationManager
import com.qtwl.gateway.utils.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.ui.text.style.TextOverflow

/**
 * 数据管理 & 添加服务 统一界面
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataManagementScreen(
    viewModel: GatewayViewModel = viewModel(factory = GatewayViewModel.Factory())
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    val inputStream = context.contentResolver.openInputStream(uri)
                    val reader = BufferedReader(InputStreamReader(inputStream))
                    val jsonString = reader.readText()
                    reader.close()
                    val result = withContext(Dispatchers.IO) {
                        viewModel.restoreFromJson(jsonString)
                    }
                    result.onSuccess {
                        snackbarHostState.showSnackbar("✅ 数据导入成功！")
                    }.onFailure { e ->
                        snackbarHostState.showSnackbar("❌ 导入失败: ${e.message}")
                    }
                } catch (e: Exception) {
                    snackbarHostState.showSnackbar("❌ 读取文件失败: ${e.message}")
                }
            }
        }
    }

    var showResetConfirm by remember { mutableStateOf(false) }
    var showAddServiceDialog by remember { mutableStateOf(false) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 标题
            Text("📋 数据管理", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("备份、恢复和重置应用数据，以及添加新的 AI 服务商",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(8.dp))

            // 自启管理
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.BatterySaver, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("🔄 自启管理", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("确保应用能在后台自启动，不被系统杀死。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(onClick = { viewModel.bindBackgroundPermissions() }, modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)) {
                        Text("🔗 一键引导自启授权")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            try {
                                val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = android.net.Uri.fromParts("package", context.packageName, null)
                                }
                                context.startActivity(intent)
                            } catch (_: Exception) {}
                        }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Settings, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("系统设置")
                        }
                        OutlinedButton(onClick = {
                            try {
                                val intent = Intent("android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS")
                                context.startActivity(intent)
                            } catch (_: Exception) {}
                        }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.BatterySaver, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("电池优化")
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))

            // 导出备份
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Upload, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("导出备份", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("将所有配置、模型、聊天记录和 Token 用量导出为 JSON 备份文件",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            scope.launch {
                                val result = withContext(Dispatchers.IO) { viewModel.getBackupJson() }
                                result.onSuccess { json ->
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("AI网关备份", json))
                                    snackbarHostState.showSnackbar("✅ 备份 JSON 已复制到剪贴板")
                                }.onFailure { e -> snackbarHostState.showSnackbar("❌ 导出失败: ${e.message}") }
                            }
                        }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("复制到剪贴板")
                        }
                        OutlinedButton(onClick = {
                            scope.launch {
                                val result = withContext(Dispatchers.IO) { viewModel.getBackupJson() }
                                result.onSuccess { json ->
                                    context.startActivity(Intent.createChooser(Intent().apply {
                                        action = Intent.ACTION_SEND; putExtra(Intent.EXTRA_TEXT, json); type = "application/json"
                                    }, "分享备份"))
                                }.onFailure { e -> snackbarHostState.showSnackbar("❌ 导出失败: ${e.message}") }
                            }
                        }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Share, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("分享")
                        }
                    }
                }
            }

            // 自动备份（含定时开关）+ 一键恢复
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Save, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("💾 备份 & 恢复", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("手动备份 / 定时自动备份 / 从备份文件一键恢复",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(12.dp))

                    // ★ 定时备份开关 + 时间设置
                    val autoBackupEnabled = remember { mutableStateOf(false) }
                    val autoBackupHour = remember { mutableStateOf(3) }  // 默认凌晨3点
                    val autoBackupMinute = remember { mutableStateOf(0) }
                    var showTimePicker by remember { mutableStateOf(false) }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("⏰ 定时自动备份", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Switch(
                            checked = autoBackupEnabled.value,
                            onCheckedChange = { enabled ->
                                autoBackupEnabled.value = enabled
                                GatewayForegroundService.saveGatewayConfig("auto_backup_enabled", enabled.toString())
                                if (enabled) {
                                    // 保存时间到配置
                                    GatewayForegroundService.saveGatewayConfig("auto_backup_hour", autoBackupHour.value.toString())
                                    GatewayForegroundService.saveGatewayConfig("auto_backup_minute", autoBackupMinute.value.toString())
                                }
                            }
                        )
                    }
                    if (autoBackupEnabled.value) {
                        Spacer(modifier = Modifier.height(4.dp))
                        TextButton(onClick = { showTimePicker = true }) {
                            Text("🕐 备份时间: ${String.format("%02d", autoBackupHour.value)}:${String.format("%02d", autoBackupMinute.value)}", style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    // 时间选择弹窗
                    if (showTimePicker) {
                        AlertDialog(
                            onDismissRequest = { showTimePicker = false },
                            title = { Text("设置自动备份时间") },
                            text = {
                                Column {
                                    Text("选择每天自动备份的小时和分钟", style = MaterialTheme.typography.bodySmall)
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                        OutlinedTextField(
                                            value = autoBackupHour.value.toString(),
                                            onValueChange = { v -> v.toIntOrNull()?.let { if (it in 0..23) autoBackupHour.value = it } },
                                            label = { Text("小时 (0-23)") },
                                            singleLine = true, modifier = Modifier.width(120.dp),
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                                        )
                                        Text(":", style = MaterialTheme.typography.titleLarge)
                                        OutlinedTextField(
                                            value = autoBackupMinute.value.toString(),
                                            onValueChange = { v -> v.toIntOrNull()?.let { if (it in 0..59) autoBackupMinute.value = it } },
                                            label = { Text("分钟 (0-59)") },
                                            singleLine = true, modifier = Modifier.width(120.dp),
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                                        )
                                    }
                                }
                            },
                            confirmButton = {
                                Button(onClick = {
                                    GatewayForegroundService.saveGatewayConfig("auto_backup_hour", autoBackupHour.value.toString())
                                    GatewayForegroundService.saveGatewayConfig("auto_backup_minute", autoBackupMinute.value.toString())
                                    showTimePicker = false
                                }) { Text("确定") }
                            },
                            dismissButton = { TextButton(onClick = { showTimePicker = false }) { Text("取消") } }
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(8.dp))

                    // 按钮行
                    var showBackupList by remember { mutableStateOf(false) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            scope.launch {
                                try {
                                    val result = withContext(Dispatchers.IO) { viewModel.getBackupJson() }
                                    result.onSuccess { json ->
                                        val timeStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                                        val fileName = "qitong_gateway_backup_$timeStr.json"
                                        if (Build.VERSION.SDK_INT >= 29) {
                                            val values = ContentValues().apply {
                                                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                                                put(MediaStore.Downloads.MIME_TYPE, "application/json")
                                                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                                            }
                                            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                                            uri?.let {
                                                context.contentResolver.openOutputStream(it)?.use { os -> os.write(json.toByteArray()) }
                                                snackbarHostState.showSnackbar("✅ 备份完成: $fileName")
                                            }
                                        } else {
                                            val file = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), fileName)
                                            file.writeText(json)
                                            snackbarHostState.showSnackbar("✅ 备份完成: $fileName")
                                        }
                                    }.onFailure { e -> snackbarHostState.showSnackbar("❌ 备份失败: ${e.message}") }
                                } catch (e: Exception) {
                                    snackbarHostState.showSnackbar("❌ 备份失败: ${e.message}")
                                }
                            }
                        }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Backup, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("立即备份")
                        }
                        OutlinedButton(onClick = {
                            scope.launch { showBackupList = true }
                        }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Restore, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("一键恢复")
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    if (autoBackupEnabled.value) {
                        Text("⏱️ 下次自动备份: ${String.format("%02d", autoBackupHour.value)}:${String.format("%02d", autoBackupMinute.value)}",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    } else {
                        Text("💡 开启「定时自动备份」后，每天指定时间自动保存到 Downloads",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    // 扫描并列出备份文件弹窗
                    if (showBackupList) {
                        var backupFiles by remember { mutableStateOf<List<File>>(emptyList()) }
                        var isLoading by remember { mutableStateOf(true) }
                        LaunchedEffect(showBackupList) {
                            withContext(Dispatchers.IO) {
                                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                                backupFiles = dir.listFiles()?.filter {
                                    it.name.startsWith("qitong_gateway_backup") && it.name.endsWith(".json")
                                }?.sortedByDescending { it.lastModified() } ?: emptyList()
                                isLoading = false
                            }
                        }
                        AlertDialog(
                            onDismissRequest = { showBackupList = false },
                            title = { Text("选择备份文件恢复", fontWeight = FontWeight.Bold) },
                            text = {
                                if (isLoading) {
                                    Box(Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                                        CircularProgressIndicator()
                                    }
                                } else if (backupFiles.isEmpty()) {
                                    Text("Downloads 目录中未找到备份文件\n请先点击「立即备份」创建备份", style = MaterialTheme.typography.bodyMedium)
                                } else {
                                    LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                                        items(backupFiles) { file ->
                                            Card(
                                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp).clickable {
                                                    scope.launch {
                                                        try {
                                                            val json = file.readText()
                                                            val result = withContext(Dispatchers.IO) { viewModel.restoreFromJson(json) }
                                                            result.onSuccess {
                                                                snackbarHostState.showSnackbar("✅ 数据恢复成功！")
                                                                showBackupList = false
                                                            }.onFailure { e ->
                                                                snackbarHostState.showSnackbar("❌ 恢复失败: ${e.message}")
                                                            }
                                                        } catch (e: Exception) {
                                                            snackbarHostState.showSnackbar("❌ 读取备份失败: ${e.message}")
                                                        }
                                                    }
                                                },
                                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                                            ) {
                                                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                                    Icon(Icons.Default.Description, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                                    Spacer(Modifier.width(8.dp))
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Text(file.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                        Text(formatFileSize(file.length()), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                    }
                                                    Icon(Icons.Default.RestorePage, contentDescription = "恢复", tint = MaterialTheme.colorScheme.primary)
                                                }
                                            }
                                        }
                                    }
                                }
                            },
                            confirmButton = { TextButton(onClick = { showBackupList = false }) { Text("关闭") } }
                        )
                    }
                }
            }
            
            // 重置数据
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Error.copy(alpha = 0.08f))) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.DeleteForever, contentDescription = null, tint = Error)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("重置所有数据", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Error)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("⚠️ 此操作将清空所有服务商、模型、聊天记录和 Token 用量，不可恢复！",
                        style = MaterialTheme.typography.bodySmall, color = Error.copy(alpha = 0.8f))
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(onClick = { showResetConfirm = true }, modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Error)) {
                        Icon(Icons.Default.Warning, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("重置所有数据")
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ★ 多语言设置卡片
            var showLangSelector by remember { mutableStateOf(false) }
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Translate, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("🌐 " + tr("language_settings"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    // 自动跟随系统开关
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(tr("auto_follow_system"), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Text(
                                if (TranslationManager.autoDetect) "当前: ${TranslationManager.currentLanguage.displayName}" 
                                else "手动: ${TranslationManager.currentLanguage.displayName}",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = TranslationManager.autoDetect,
                            onCheckedChange = { enabled ->
                                TranslationManager.setAutoDetect(enabled, context)
                                showLangSelector = !enabled
                            }
                        )
                    }
                    
                    if (!TranslationManager.autoDetect) {
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(onClick = { showLangSelector = true }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Language, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text(TranslationManager.currentLanguage.displayName)
                        }
                    }
                }
            }

            // 语言选择弹窗
            if (showLangSelector) {
                AlertDialog(
                    onDismissRequest = { showLangSelector = false },
                    title = { Text(tr("manual_select")) },
                    text = {
                        LazyColumn(modifier = Modifier.heightIn(max = 350.dp)) {
                            items(AppLanguage.entries) { lang ->
                                Card(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp).clickable {
                                        TranslationManager.setLanguage(lang, context)
                                        showLangSelector = false
                                    },
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (lang == TranslationManager.currentLanguage) 
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.1f) 
                                        else MaterialTheme.colorScheme.surface
                                    )
                                ) {
                                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text(lang.displayName, 
                                            modifier = Modifier.weight(1f),
                                            fontWeight = if (lang == TranslationManager.currentLanguage) FontWeight.Bold else FontWeight.Normal
                                        )
                                        if (lang == TranslationManager.currentLanguage) {
                                            Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                }
                            }
                        }
                    },
                    confirmButton = { TextButton(onClick = { showLangSelector = false }) { Text("关闭") } }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ★ Debug 抓包模式
            val debugMode by viewModel.debugMode.collectAsState()
            var showDebugLogs by remember { mutableStateOf(false) }
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.BugReport, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("🔍 网关抓包", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("开启后记录所有网关请求/响应到内存，可查看最近20条（含实时输入/输出流量）", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = { viewModel.toggleDebugMode() }, colors = ButtonDefaults.buttonColors(
                            containerColor = if (debugMode) Error else Online)) {
                            Text(if (debugMode) "⏹ 停止抓包" else "▶️ 开始抓包")
                        }
                        OutlinedButton(onClick = { showDebugLogs = true }) {
                            Icon(Icons.Default.List, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("查看日志")
                        }
                    }
                    if (debugMode) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("🟢 抓包运行中...", style = MaterialTheme.typography.labelSmall, color = Online)
                    }
                }
            }

            // ○ Debug 日志弹窗
            if (showDebugLogs) {
                val logs = viewModel.getDebugLogs()
                AlertDialog(
                    onDismissRequest = { showDebugLogs = false },
                    title = { Text("📋 网关抓包日志", fontWeight = FontWeight.Bold) },
                    text = {
                        if (logs.isEmpty()) {
                            Text("暂无日志，请先开启抓包模式并发送请求", style = MaterialTheme.typography.bodyMedium)
                        } else {
                            LazyColumn(modifier = Modifier.heightIn(max = 350.dp)) {
                                items(logs.reversed()) { log ->
                                    Text(log, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 2.dp))
                                }
                            }
                        }
                    },
                    confirmButton = {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = {
                                viewModel.clearDebugLogs()
                                showDebugLogs = false
                            }) { Text("🗑️ 清空") }
                            TextButton(onClick = { showDebugLogs = false }) { Text("关闭") }
                        }
                    }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(8.dp))

            // 添加服务
            Text("🔌 添加服务", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("支持 OpenAI Compatible API，自动检测端口，智能获取模型列表",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(8.dp))

            ServiceTemplateCard("Ollama (本地)", "http://localhost:11434") { viewModel.showAddProvider() }
            ServiceTemplateCard("OpenAI", "https://api.openai.com") { viewModel.showAddProvider() }
            ServiceTemplateCard("自定义 OpenAI Compatible", "输入任意兼容 OpenAI API 格式的地址") { showAddServiceDialog = true }

            Spacer(modifier = Modifier.height(80.dp))
        }
    }

    // 代理管理弹窗（AboutScreen 连点触发）
    val showProxyDialog by viewModel.showProxyConfigDialog.collectAsState()
    if (showProxyDialog) {
        ProxyManagementDialog(viewModel = viewModel, onDismiss = { viewModel.hideProxyConfig() })
    }

    // 添加代理弹窗（从 DataManagementScreen 直接添加）
    var showAddProxyDialog by remember { mutableStateOf(false) }
    if (showAddProxyDialog) {
        AddEditProxyDialog(
            title = "添加代理", viewModel = viewModel,
            onDismiss = { showAddProxyDialog = false },
            onConfirm = { profile -> viewModel.addProxy(profile); showAddProxyDialog = false }
        )
    }

    // 重置确认弹窗
    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text("⚠️ 确认重置？", fontWeight = FontWeight.Bold) },
            text = { Text("此操作将永久删除所有数据，包括：\n\n• 所有服务商配置\n• 所有 AI 模型列表\n• 所有聊天记录和对话\n• 所有 Token 用量统计\n\n此操作不可撤销！") },
            confirmButton = {
                Button(onClick = { viewModel.resetAllData(); showResetConfirm = false }, colors = ButtonDefaults.buttonColors(containerColor = Error)) {
                    Text("确认重置", color = MaterialTheme.colorScheme.onError)
                }
            },
            dismissButton = { TextButton(onClick = { showResetConfirm = false }) { Text("取消") } }
        )
    }

    // 智能添加服务弹窗
    if (showAddServiceDialog) {
        SmartAddServiceDialog(viewModel = viewModel, onDismiss = { showAddServiceDialog = false },
            onSuccess = { showAddServiceDialog = false; scope.launch { snackbarHostState.showSnackbar("✅ 服务商添加成功！") } })
    }
}

// ============================================================
// 服务模板卡片
// ============================================================
@Composable
private fun ServiceTemplateCard(title: String, subtitle: String, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 格式化文件大小 */
private fun formatFileSize(bytes: Long): String = when {
    bytes < 1024 -> "${bytes}B"
    bytes < 1024 * 1024 -> "${bytes / 1024}KB"
    else -> String.format("%.1fMB", bytes.toDouble() / (1024 * 1024))
}

// ============================================================
// 智能添加服务弹窗
// ============================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SmartAddServiceDialog(viewModel: GatewayViewModel, onDismiss: () -> Unit, onSuccess: () -> Unit) {
    val form by viewModel.providerForm.collectAsState()
    var showApiKey by remember { mutableStateOf(false) }
    val detectedPort = remember(form.baseUrl) { viewModel.extractPortFromUrl(form.baseUrl) }
    LaunchedEffect(detectedPort) { if (detectedPort.isNotBlank() && form.port != detectedPort) viewModel.updateFormField("port", detectedPort) }
    var isTesting by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加服务 (OpenAI Compatible)", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = form.name, onValueChange = { viewModel.updateFormField("name", it) },
                    label = { Text("服务商名称") }, placeholder = { Text("例如: 我的 Ollama") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = form.baseUrl, onValueChange = {
                    viewModel.updateFormField("baseUrl", it)
                    val port = viewModel.extractPortFromUrl(it)
                    if (port.isNotBlank()) viewModel.updateFormField("port", port)
                }, label = { Text("API 地址 (Base URL)") }, placeholder = { Text("http://192.168.1.100:11434") },
                    supportingText = { if (detectedPort.isNotBlank()) Text("检测到端口: $detectedPort", color = Online) },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = form.port, onValueChange = { viewModel.updateFormField("port", it) },
                    label = { Text("端口 (可选)") }, placeholder = { Text("如 11434, 8080") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = form.apiKey, onValueChange = { viewModel.updateFormField("apiKey", it) },
                    label = { Text("API Key (可选)") }, placeholder = { Text("sk-...") }, singleLine = true,
                    visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = { IconButton(onClick = { showApiKey = !showApiKey }) {
                        Icon(if (showApiKey) Icons.Default.Visibility else Icons.Default.VisibilityOff, contentDescription = null) } },
                    modifier = Modifier.fillMaxWidth())

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { isTesting = true; testResult = null; viewModel.fetchAvailableModels(form.baseUrl, form.apiKey.ifBlank { null }) },
                        enabled = form.baseUrl.isNotBlank() && !isTesting, modifier = Modifier.weight(1f)) {
                        if (isTesting) { CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(modifier = Modifier.width(4.dp)); Text("检测中...") }
                        else { Icon(Icons.Default.NetworkCheck, contentDescription = null); Spacer(modifier = Modifier.width(4.dp)); Text("检测模型列表") }
                    }
                }
                val syncResult by viewModel.syncResult.collectAsState()
                LaunchedEffect(syncResult) { isTesting = false; testResult = syncResult }
                if (testResult != null) {
                    Card(colors = CardDefaults.cardColors(containerColor = if (testResult!!.startsWith("✅")) Online.copy(alpha = 0.15f) else if (testResult!!.startsWith("❌")) Error.copy(alpha = 0.15f) else Warning.copy(alpha = 0.15f))) {
                        Text(testResult!!, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { viewModel.saveProvider(); onSuccess() }, enabled = form.name.isNotBlank() && form.baseUrl.isNotBlank()) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

// ============================================================
// 代理管理弹窗
// ============================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProxyManagementDialog(viewModel: GatewayViewModel, onDismiss: () -> Unit) {
    val proxyProfiles by viewModel.proxyProfiles.collectAsState()
    val proxyEnabled by viewModel.proxyEnabled.collectAsState()
    val activeProxyId by viewModel.activeProxyId.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var editingProfile by remember { mutableStateOf<GatewayViewModel.ProxyProfile?>(null) }
    var showSubscriptionDialog by remember { mutableStateOf(false) }
    var showPasteDialog by remember { mutableStateOf(false) }
    var pasteLinkText by remember { mutableStateOf("") }

    // 剪贴板检测
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            if (cm.hasPrimaryClip()) {
                val clipText = cm.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                val detected = viewModel.detectClipboardLink(clipText)
                if (detected != null) {
                    pasteLinkText = detected
                    showPasteDialog = true
                }
            }
        } catch (_: Exception) { }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("⚙️ 代理管理", fontWeight = FontWeight.Bold)
                Text(if (proxyEnabled) "🟢 已激活" else "🔴 未激活", style = MaterialTheme.typography.bodySmall, color = if (proxyEnabled) Online else Error)
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 订阅按钮（放在内容区确保可点击）
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { showSubscriptionDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("📡 一键订阅", style = MaterialTheme.typography.labelMedium)
                    }
                }
                if (proxyProfiles.isEmpty()) {
                    Text("还没有代理配置，点击下方按钮添加", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    proxyProfiles.forEach { profile ->
                        ProxyProfileCard(profile = profile, isActive = profile.id == activeProxyId && proxyEnabled,
                            onToggleEnable = { viewModel.toggleProxyEnabled(profile) }, onEdit = { editingProfile = profile },
                            onDelete = { viewModel.deleteProxy(profile) }, onTestSpeed = { viewModel.testProxySpeed(profile) })
                        if (profile != proxyProfiles.last()) HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { showAddDialog = true }) { Icon(Icons.Default.Add, contentDescription = null); Spacer(Modifier.width(4.dp)); Text("添加代理") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )

    if (showAddDialog) {
        AddEditProxyDialog(title = "添加代理", viewModel = viewModel, onDismiss = { showAddDialog = false },
            onConfirm = { profile -> viewModel.addProxy(profile); showAddDialog = false })
    }
    editingProfile?.let { profile ->
        AddEditProxyDialog(title = "编辑代理", initialProfile = profile, viewModel = viewModel,
            onDismiss = { editingProfile = null }, onConfirm = { updated -> viewModel.updateProxy(updated); editingProfile = null })
    }
    // 订阅弹窗
    if (showSubscriptionDialog) {
        var subUrl by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showSubscriptionDialog = false },
            title = { Text("📡 一键订阅", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("输入订阅地址，自动拉取并批量导入节点", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = subUrl, onValueChange = { subUrl = it },
                        label = { Text("订阅URL") }, placeholder = { Text("https://example.com/sub?token=...") },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                Button(onClick = { viewModel.importSubscription(subUrl); showSubscriptionDialog = false }) { Text("导入") }
            },
            dismissButton = { TextButton(onClick = { showSubscriptionDialog = false }) { Text("取消") } }
        )
    }
    // 剪贴板检测弹窗
    if (showPasteDialog) {
        AlertDialog(
            onDismissRequest = { showPasteDialog = false },
            title = { Text("📋 检测到代理链接", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("剪贴板中检测到代理/订阅链接：", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(4.dp))
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
                        Text(pasteLinkText.take(80) + if (pasteLinkText.length > 80) "..." else "",
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(8.dp))
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("是否自动解析并导入？", style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (pasteLinkText.startsWith("http")) {
                        viewModel.importSubscription(pasteLinkText)
                    } else {
                        viewModel.addProxyFromLink(pasteLinkText)
                    }
                    showPasteDialog = false
                }) { Text("立即导入") }
            },
            dismissButton = { TextButton(onClick = { showPasteDialog = false }) { Text("忽略") } }
        )
    }
}

// ============================================================
// 代理卡片
// ============================================================
@Composable
private fun ProxyProfileCard(
    profile: GatewayViewModel.ProxyProfile, isActive: Boolean,
    onToggleEnable: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit, onTestSpeed: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = if (isActive) Online.copy(alpha = 0.1f) else MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val icon = when (profile.type.uppercase()) { "HTTP", "HTTPS" -> Icons.Default.Language; "SOCKS5", "SOCKS" -> Icons.Default.Shield; else -> Icons.Default.Dns }
                    Icon(icon, contentDescription = null, tint = if (profile.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(profile.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Text("${profile.type} · ${profile.host}:${profile.port}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (profile.username.isNotBlank()) Text("👤 ${profile.username}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Switch(checked = profile.enabled, onCheckedChange = { onToggleEnable() })
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                IconButton(onClick = onTestSpeed, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.Speed, contentDescription = "测速", modifier = Modifier.size(18.dp)) }
                IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.Edit, contentDescription = "编辑", modifier = Modifier.size(18.dp)) }
                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.Delete, contentDescription = "删除", tint = Error, modifier = Modifier.size(18.dp)) }
                if (isActive) {
                    Surface(color = Online.copy(alpha = 0.15f), shape = MaterialTheme.shapes.extraSmall) {
                        Text("已激活", style = MaterialTheme.typography.labelSmall, color = Online, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                    }
                }
            }
        }
    }
}

// ============================================================
// 添加/编辑代理弹窗
// ============================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddEditProxyDialog(
    title: String, initialProfile: GatewayViewModel.ProxyProfile? = null,
    viewModel: GatewayViewModel, onDismiss: () -> Unit, onConfirm: (GatewayViewModel.ProxyProfile) -> Unit
) {
    var name by remember { mutableStateOf(initialProfile?.name ?: "") }
    var type by remember { mutableStateOf(initialProfile?.type ?: "HTTP") }
    var host by remember { mutableStateOf(initialProfile?.host ?: "") }
    var port by remember { mutableStateOf((initialProfile?.port ?: 1080).toString()) }
    var username by remember { mutableStateOf(initialProfile?.username ?: "") }
    var password by remember { mutableStateOf(initialProfile?.password ?: "") }
    var showPassword by remember { mutableStateOf(false) }
    var typeExpanded by remember { mutableStateOf(false) }
    val typeOptions = listOf("HTTP", "HTTPS", "SOCKS5", "SOCKS", "VMESS", "SS", "VLESS", "Trojan", "Hysteria2")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("代理名称") }, placeholder = { Text("例如：机场节点1") }, singleLine = true, modifier = Modifier.fillMaxWidth())

                // 代理类型选择器
                ExposedDropdownMenuBox(expanded = typeExpanded, onExpandedChange = { typeExpanded = !typeExpanded }) {
                    OutlinedTextField(value = type, onValueChange = {}, readOnly = true, label = { Text("代理类型") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = typeExpanded) }, modifier = Modifier.fillMaxWidth().menuAnchor())
                    ExposedDropdownMenu(expanded = typeExpanded, onDismissRequest = { typeExpanded = false }) {
                        typeOptions.forEach { option ->
                            DropdownMenuItem(text = { Text(option) }, onClick = { type = option; typeExpanded = false
                                if (port == "1080" || port == "7890") port = if (option.startsWith("SOCKS")) "1080" else "7890" })
                        }
                    }
                }

                OutlinedTextField(value = host, onValueChange = { host = it }, label = { Text("代理服务器地址") }, placeholder = { Text("例如：192.168.1.100") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = port, onValueChange = { port = it.filter { c -> c.isDigit() } }, label = { Text("端口") },
                    placeholder = { Text(if (type.startsWith("SOCKS")) "1080" else "7890") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())

                HorizontalDivider()

                OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text("用户名 (可选)") }, placeholder = { Text("SOCKS5/HTTP 认证用户名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("密码 (可选)") }, placeholder = { Text("SOCKS5/HTTP 认证密码") }, singleLine = true,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = { IconButton(onClick = { showPassword = !showPassword }) { Icon(if (showPassword) Icons.Default.Visibility else Icons.Default.VisibilityOff, contentDescription = null) } },
                    modifier = Modifier.fillMaxWidth())

                if (type.startsWith("SOCKS") && (username.isNotBlank() || password.isNotBlank())) {
                    Surface(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f), shape = MaterialTheme.shapes.small) {
                        Text("✅ SOCKS5 将使用 RFC 1929 用户名/密码认证", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(8.dp))
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                onConfirm(GatewayViewModel.ProxyProfile(
                    id = initialProfile?.id ?: java.util.UUID.randomUUID().toString().take(8),
                    name = name.ifBlank { "未命名代理" }, type = type, host = host, port = port.toIntOrNull() ?: 1080,
                    username = username, password = password, enabled = initialProfile?.enabled ?: false))
            }, enabled = host.isNotBlank() && (port.toIntOrNull() ?: 0) in 1..65535) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

// ============================================================
// 关于我们页面
// ============================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(viewModel: GatewayViewModel = viewModel(factory = GatewayViewModel.Factory())) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val gatewayPort by viewModel.gatewayPort.collectAsState(initial = 8889)
    val proxyEnabled by viewModel.proxyEnabled.collectAsState(initial = false)
    val proxyProfiles by viewModel.proxyProfiles.collectAsState()
    val activeProxyId by viewModel.activeProxyId.collectAsState()
    var clickCount by remember { mutableIntStateOf(0) }
    var lastClickTime by remember { mutableLongStateOf(0L) }

    // 动态获取版本号
    val appVersion = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).let { "${it.versionName}" }
        } catch (_: Exception) { "1.8.0" }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("ℹ️ 关于我们", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("綦桐APP v$appVersion - AI 网关管理工具", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(8.dp))

            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("📱 应用信息", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("版本: v$appVersion", style = MaterialTheme.typography.bodyMedium)
                    Text("开发者: 綦桐网络", style = MaterialTheme.typography.bodyMedium)
                    Text("协议: OpenAI Compatible API", style = MaterialTheme.typography.bodyMedium)
                }
            }

            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("⚙️ 当前配置", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("网关端口:", style = MaterialTheme.typography.bodyMedium)
                        Text(gatewayPort.toString(), style = MaterialTheme.typography.bodyMedium)
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("代理加速:", style = MaterialTheme.typography.bodyMedium)
                        Text(if (proxyEnabled) "✅ 已开启" else "❌ 未开启", color = if (proxyEnabled) Online else Error)
                    }
                    // 显示激活的代理详情
                    val activeProxy = if (proxyEnabled) proxyProfiles.find { it.id == activeProxyId } else null
                    if (activeProxy != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("代理节点:", style = MaterialTheme.typography.bodyMedium)
                            Text("${activeProxy.type} · ${activeProxy.host}:${activeProxy.port}", style = MaterialTheme.typography.bodyMedium, color = Online)
                        }
                        if (activeProxy.username.isNotBlank()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("代理用户:", style = MaterialTheme.typography.bodyMedium)
                                Text(activeProxy.username, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                    // 流量统计
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("↑ 上传:", style = MaterialTheme.typography.bodyMedium)
                        Text(formatTraffic(GatewayForegroundService.trafficUploadBytes), style = MaterialTheme.typography.bodyMedium, color = Online)
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("↓ 下载:", style = MaterialTheme.typography.bodyMedium)
                        Text(formatTraffic(GatewayForegroundService.trafficDownloadBytes), style = MaterialTheme.typography.bodyMedium, color = Online)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 隐藏的秘密通道 — 连点3次打开代理管理
            Surface(modifier = Modifier.fillMaxWidth().height(40.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f), shape = MaterialTheme.shapes.small,
                onClick = {
                    val now = System.currentTimeMillis()
                    if (now - lastClickTime <= 3000) { clickCount++; if (clickCount >= 3) { viewModel.showProxyConfig(); clickCount = 0 } }
                    else { clickCount = 1 }
                    lastClickTime = now
                }) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("🔧", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                }
            }

            // 代理管理弹窗
            val showProxyDialog by viewModel.showProxyConfigDialog.collectAsState()
            if (showProxyDialog) {
                ProxyManagementDialog(viewModel = viewModel, onDismiss = { viewModel.hideProxyConfig() })
            }
        }
    }
}
private fun formatTraffic(bytes: Long): String = when {
    bytes < 1024 -> "${bytes}B"
    bytes < 1024 * 1024 -> "${bytes / 1024}KB"
    else -> "%.1fMB".format(bytes.toDouble() / (1024 * 1024))
}
