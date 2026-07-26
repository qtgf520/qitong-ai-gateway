package com.qtwl.gateway.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.Manifest
import android.net.Uri
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
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
import com.qtwl.gateway.service.ThinkingConfigManager
import com.qtwl.gateway.service.GroupChatManager
import com.qtwl.gateway.ui.theme.Error
import com.qtwl.gateway.ui.theme.Online
import com.qtwl.gateway.ui.theme.Warning
import com.qtwl.gateway.ui.viewmodel.BrainMemoryManager
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
import androidx.compose.foundation.background
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import com.qtwl.gateway.utils.CrashHandler
import com.qtwl.gateway.utils.SkillRegistry
import com.qtwl.gateway.utils.CustomSkillManager
import com.qtwl.gateway.utils.localizedText
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.qtwl.gateway.utils.localizeRuntimeText
import com.qtwl.gateway.utils.localizeGeneratedName
import com.qtwl.gateway.utils.localizeGeneratedContent

@Composable
private fun PersonaSlider(label: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodySmall)
            Text("$value", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = range.last - range.first - 1
        )
    }
}

/**
 * 数据管理 & 添加服务 统一界面
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataManagementScreen(
    viewModel: GatewayViewModel = viewModel(factory = GatewayViewModel.Factory())
) {
    TranslationManager.currentLanguageFlow.collectAsState().value
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // ★ 文件存储权限请求器（Android 11+ 专用目录写入需要）
    val storagePermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        scope.launch {
            if (granted) {
                snackbarHostState.showSnackbar(localizedText("✅ 文件存储权限已授予", "✅ File storage permission granted"))
            } else {
                snackbarHostState.showSnackbar(localizedText("⚠️ 权限被拒，备份将使用 MediaStore 保存到 Downloads", "⚠️ Permission denied; backup will be saved to Downloads via MediaStore"))
            }
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    // 复制到临时文件
                    val tempFile = java.io.File(context.cacheDir, "restore_temp.qtbk")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        tempFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    val result = withContext(Dispatchers.IO) {
                        viewModel.restoreFromFile(tempFile.absolutePath)
                    }
                    result.onSuccess {
                        snackbarHostState.showSnackbar(localizedText("✅ 数据恢复成功！", "✅ Data restored successfully!"))
                    }.onFailure { e ->
                        snackbarHostState.showSnackbar(localizedText("❌ 恢复失败: ", "❌ Restore failed: ") + e.message)
                    }
                } catch (e: Exception) {
                    snackbarHostState.showSnackbar(localizedText("❌ 读取文件失败: ", "❌ Failed to read file: ") + e.message)
                }
            }
        }
    }

    var showResetConfirm by remember { mutableStateOf(false) }
    var showAddServiceDialog by remember { mutableStateOf(false) }
    var showDebugLogs by remember { mutableStateOf(false) }
    var showKeyManagement by remember { mutableStateOf(false) }
    var showSkillManagement by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
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
            Text(localizedText("📋 数据管理", "📋 Data management"), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(localizedText("备份、恢复和重置应用数据，以及添加新的 AI 服务商", "Back up, restore, and reset app data, and add new AI providers"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(8.dp))

            // 自启管理 + 隐藏多任务
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.BatterySaver, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(localizedText("🔄 自启管理", "🔄 Auto-start management"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(localizedText("确保应用能在后台自启动，不被系统杀死。", "Ensure the app can auto-start in the background and is not killed by the system."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(onClick = { viewModel.bindBackgroundPermissions() }, modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)) {
                        Text(localizedText("🔗 一键引导自启授权", "🔗 One-tap auto-start authorization guide"))
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
                            Text(localizedText("系统设置", "System settings"))
                        }
                        OutlinedButton(onClick = {
                            try {
                                val intent = Intent("android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS")
                                context.startActivity(intent)
                            } catch (_: Exception) {}
                        }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.BatterySaver, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(localizedText("电池优化", "Battery optimization"))
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(8.dp))
                    // ★ 隐藏多任务开关
                    var hideFromRecents by remember { mutableStateOf(
                        GatewayForegroundService.getGatewayConfig("hide_from_recents", "false").toBoolean()
                    ) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.VisibilityOff, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(localizedText("👻 隐藏多任务", "👻 Hide from recents"), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Text(localizedText("开启后APP不在多任务列表中显示", "When enabled, the app won't appear in the recent tasks list"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = hideFromRecents,
                            onCheckedChange = { enabled ->
                                hideFromRecents = enabled
                                GatewayForegroundService.saveGatewayConfig("hide_from_recents", enabled.toString())
                                // 运行时从最近任务隐藏
                                try {
                                    val am = context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                                    if (enabled) {
                                        am.appTasks.firstOrNull()?.setExcludeFromRecents(true)
                                    } else {
                                        am.appTasks.firstOrNull()?.setExcludeFromRecents(false)
                                    }
                                } catch (_: Exception) {}
                            }
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))

            // ★ 隐藏多任务开关
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.VisibilityOff, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(localizedText("👻 隐藏多任务", "👻 Hide from recents"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(localizedText("开启后APP不在多任务列表中显示", "When enabled, the app won't appear in the recent tasks list"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(8.dp))
                    var hideFromRecents by remember { mutableStateOf(
                        GatewayForegroundService.getGatewayConfig("hide_from_recents", "false").toBoolean()
                    ) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(localizedText("隐藏多任务", "Hide from recents"), modifier = Modifier.weight(1f))
                        Switch(
                            checked = hideFromRecents,
                            onCheckedChange = { enabled ->
                                hideFromRecents = enabled
                                GatewayForegroundService.saveGatewayConfig("hide_from_recents", enabled.toString())
                                try {
                                    val am = context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                                    am.appTasks.firstOrNull()?.setExcludeFromRecents(enabled)
                                } catch (_: Exception) {}
                            }
                        )
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
                        Text(localizedText("导出备份", "Export backup"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(localizedText("将所有配置、模型、聊天记录和 Token 用量导出为 JSON 备份文件", "Export all configuration, models, chats, and token usage as a JSON backup file"),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            scope.launch {
                                val result = withContext(Dispatchers.IO) { viewModel.getBackupJson() }
                                result.onSuccess { json ->
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText(localizedText("AI网关备份", "AI Gateway Backup"), json))
                                    snackbarHostState.showSnackbar(localizedText("✅ 备份 JSON 已复制到剪贴板", "✅ Backup JSON copied to clipboard"))
                                }.onFailure { e -> snackbarHostState.showSnackbar(localizedText("❌ 导出失败: ", "❌ Export failed: ") + e.message) }
                            }
                        }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(localizedText("复制到剪贴板", "Copy to clipboard"))
                        }
                        OutlinedButton(onClick = {
                            scope.launch {
                                val result = withContext(Dispatchers.IO) { viewModel.getBackupJson() }
                                result.onSuccess { json ->
                                    context.startActivity(Intent.createChooser(Intent().apply {
                                        action = Intent.ACTION_SEND; putExtra(Intent.EXTRA_TEXT, json); type = "application/json"
                                    }, localizedText("分享备份", "Share backup")))
                                }.onFailure { e -> snackbarHostState.showSnackbar(localizedText("❌ 导出失败: ", "❌ Export failed: ") + e.message) }
                            }
                        }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Share, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(localizedText("分享", "Share"))
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
                        Text(localizedText("💾 备份 & 恢复", "💾 Backup & Restore"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(localizedText("手动备份 / 定时自动备份 / 从备份文件一键恢复", "Manual backup / scheduled automatic backup / one-tap restore from backup file"),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(12.dp))

                    // ★ 定时备份开关 + 时间设置
                    val autoBackupEnabled = remember { mutableStateOf(false) }
                    val autoBackupHour = remember { mutableStateOf(3) }  // 默认凌晨3点
                    val autoBackupMinute = remember { mutableStateOf(0) }
                    var showTimePicker by remember { mutableStateOf(false) }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(localizedText("⏰ 定时自动备份", "⏰ Scheduled automatic backup"), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Switch(
                            checked = autoBackupEnabled.value,
                            onCheckedChange = { enabled ->
                                autoBackupEnabled.value = enabled
                                GatewayForegroundService.saveGatewayConfig("auto_backup_enabled", enabled.toString())
                                if (enabled) {
                                    GatewayForegroundService.saveGatewayConfig("auto_backup_hour", autoBackupHour.value.toString())
                                    GatewayForegroundService.saveGatewayConfig("auto_backup_minute", autoBackupMinute.value.toString())
                                    // 调度 WorkManager
                                    com.qtwl.gateway.data.db.AutoBackupWorker.schedule(context, autoBackupHour.value, autoBackupMinute.value)
                                } else {
                                    // 取消 WorkManager
                                    com.qtwl.gateway.data.db.AutoBackupWorker.cancel(context)
                                }
                            }
                        )
                    }
                    if (autoBackupEnabled.value) {
                        Spacer(modifier = Modifier.height(4.dp))
                        TextButton(onClick = { showTimePicker = true }) {
                            Text(localizedText("🕐 备份时间: ", "🕐 Backup time: ") + String.format("%02d", autoBackupHour.value) + ":" + String.format("%02d", autoBackupMinute.value), style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    // 时间选择弹窗
                    if (showTimePicker) {
                        AlertDialog(
                            onDismissRequest = { showTimePicker = false },
                            title = { Text(localizedText("设置自动备份时间", "Set automatic backup time")) },
                            text = {
                                Column {
                                    Text(localizedText("选择每天自动备份的小时和分钟", "Choose the hour and minute for daily automatic backup"), style = MaterialTheme.typography.bodySmall)
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                        OutlinedTextField(
                                            value = autoBackupHour.value.toString(),
                                            onValueChange = { v -> v.toIntOrNull()?.let { if (it in 0..23) autoBackupHour.value = it } },
                                            label = { Text(localizedText("小时 (0-23)", "Hour (0-23)")) },
                                            singleLine = true, modifier = Modifier.width(120.dp),
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                                        )
                                        Text(":", style = MaterialTheme.typography.titleLarge)
                                        OutlinedTextField(
                                            value = autoBackupMinute.value.toString(),
                                            onValueChange = { v -> v.toIntOrNull()?.let { if (it in 0..59) autoBackupMinute.value = it } },
                                            label = { Text(localizedText("分钟 (0-59)", "Minute (0-59)")) },
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
                                }) { Text(localizedText("确定", "OK")) }
                            },
                            dismissButton = { TextButton(onClick = { showTimePicker = false }) { Text(localizedText("取消", "Cancel")) } }
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(8.dp))

                    // ★ 按钮行：立即备份 | 恢复备份（自动扫 Downloads）
                    var showBackupList by remember { mutableStateOf(false) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            scope.launch {
                                try {
                                    val db = com.qtwl.gateway.data.db.AppDatabase.getInstance(context)
                                    val manager = com.qtwl.gateway.data.db.BackupManager(db)
                                    val dir = manager.getBackupDir()
                                    val timeStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                                    val file = java.io.File(dir, "backup_$timeStr.qtbk")
                                    val result = manager.exportToFile(file)
                                    result.onSuccess {
                                        snackbarHostState.showSnackbar(localizedText("✅ 备份完成: ", "✅ Backup complete: ") + file.name)
                                    }.onFailure { e -> snackbarHostState.showSnackbar(localizedText("❌ 备份失败: ", "❌ Backup failed: ") + e.message) }
                                } catch (e: Exception) {
                                    snackbarHostState.showSnackbar(localizedText("❌ 备份失败: ", "❌ Backup failed: ") + e.message)
                                }
                            }
                        }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Save, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(localizedText("立即备份", "Back up now"))
                        }
                        OutlinedButton(onClick = {
                            showBackupList = true
                        }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Restore, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(localizedText("恢复备份", "Restore backup"))
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    // ★ 第二行：手动导入（调文件选择器）
                    OutlinedButton(onClick = {
                        filePickerLauncher.launch("*/*")
                    }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.NoteAdd, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(localizedText("📂 手动导入（选择 .qtbk 文件）", "📂 Manual import (select .qtbk file)"))
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    if (autoBackupEnabled.value) {
                        Text(localizedText("⏱️ 下次自动备份: ", "⏱️ Next automatic backup: ") + String.format("%02d", autoBackupHour.value) + ":" + String.format("%02d", autoBackupMinute.value),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    } else {
Text(localizedText("💡 备份格式: .qtbk (GZIP压缩+SHA256校验+AES-256加密)", "💡 Backup format: .qtbk (GZIP+SHA256+AES-256)"))
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    // ★ 测试备份按钮
                    OutlinedButton(onClick = {
                        com.qtwl.gateway.data.db.AutoBackupWorker.scheduleTest(context)
                        scope.launch { snackbarHostState.showSnackbar(localizedText("🧪 测试备份已调度（10秒后执行）", "🧪 Test backup scheduled (10 seconds)")) }
                    }, modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.secondary)) {
                        Icon(Icons.Default.BugReport, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(localizedText("🧪 测试备份（10秒后执行）", "🧪 Test backup (10 seconds)"))
                    }
                    Spacer(modifier = Modifier.height(4.dp))

                    // 扫描并列出备份文件弹窗
                    if (showBackupList) {
                        var backupFiles by remember { mutableStateOf<List<com.qtwl.gateway.data.db.BackupManager.BackupMetadata>>(emptyList()) }
                        var isLoading by remember { mutableStateOf(true) }
                        LaunchedEffect(showBackupList) {
                            withContext(Dispatchers.IO) {
                                val db = com.qtwl.gateway.data.db.AppDatabase.getInstance(context)
                                val manager = com.qtwl.gateway.data.db.BackupManager(db)
                                backupFiles = manager.getBackupHistory()
                                isLoading = false
                            }
                        }
                        AlertDialog(
                            onDismissRequest = { showBackupList = false },
                            title = { Text(localizedText("选择备份文件恢复", "Select backup file to restore"), fontWeight = FontWeight.Bold) },
                            text = {
                                if (isLoading) {
                                    Box(Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                                        CircularProgressIndicator()
                                    }
                                } else if (backupFiles.isEmpty()) {
                                    Text(localizedText("未找到备份文件\n请先点击「立即备份」创建备份", "No backup files found\nTap Back up now to create a backup first"), style = MaterialTheme.typography.bodyMedium)
                                } else {
                                    LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                                        items(backupFiles) { meta ->
                                            Card(
                                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp).clickable {
                                                    scope.launch {
                                                        try {
                                                            val result = withContext(Dispatchers.IO) {
                                                                val db = com.qtwl.gateway.data.db.AppDatabase.getInstance(context)
                                                                val manager = com.qtwl.gateway.data.db.BackupManager(db)
                                                                manager.importFromFile(java.io.File(meta.filePath))
                                                            }
                                                            result.onSuccess {
                                                                snackbarHostState.showSnackbar(localizedText("✅ 数据恢复成功！", "✅ Data restored successfully!"))
                                                                showBackupList = false
                                                            }.onFailure { e ->
                                                                snackbarHostState.showSnackbar(localizedText("❌ 恢复失败: ", "❌ Restore failed: ") + e.message)
                                                            }
                                                        } catch (e: Exception) {
                                                            snackbarHostState.showSnackbar(localizedText("❌ 读取备份失败: ", "❌ Failed to read backup: ") + e.message)
                                                        }
                                                    }
                                                },
                                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                                            ) {
                                                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                                    Icon(Icons.Default.Description, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                                    Spacer(Modifier.width(8.dp))
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Text(meta.fileName, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                        Text(meta.sizeReadable + " - " + meta.createdAtReadable, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                    }
                                                    Icon(Icons.Default.RestorePage, contentDescription = localizedText("恢复", "Restore"), tint = MaterialTheme.colorScheme.primary)
                                                }
                                            }
                                        }
                                    }
                                }
                            },
                            confirmButton = { TextButton(onClick = { showBackupList = false }) { Text(localizedText("关闭", "Close")) } }
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
                        Text(localizedText("重置所有数据", "Reset all data"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Error)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(localizedText("⚠️ 此操作将清空所有服务商、模型、聊天记录和 Token 用量，不可恢复！", "⚠️ This will delete all providers, models, chats, and token usage. It cannot be undone!"),
                        style = MaterialTheme.typography.bodySmall, color = Error.copy(alpha = 0.8f))
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(onClick = { showResetConfirm = true }, modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Error)) {
                        Icon(Icons.Default.Warning, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(localizedText("重置所有数据", "Reset all data"))
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ★★ 记忆管理卡片（BrainMemory 大脑）★★
            val cfg = BrainMemoryManager.getConfig()
            var memEnabled by remember { mutableStateOf(cfg.enabled) }
            var memMode by remember { mutableStateOf(cfg.saveMode) }
            var memMax by remember { mutableStateOf(cfg.maxShortTerm.toString()) }
            var showMemDetail by remember { mutableStateOf(false) }
            var memSearchQuery by remember { mutableStateOf("") }
            var memList by remember { mutableStateOf(BrainMemoryManager.getAll()) }
            var editingMem by remember { mutableStateOf<com.qtwl.gateway.ui.viewmodel.BrainMemoryManager.MemoryItem?>(null) }
            var editTitle by remember { mutableStateOf("") }
            var editContent by remember { mutableStateOf("") }
            var editEmotion by remember { mutableStateOf("neutral") }
            var editImportance by remember { mutableStateOf("5") }

            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Memory, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(localizedText("🧠 大脑记忆", "🧠 Brain memory"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Switch(checked = memEnabled, onCheckedChange = { e ->
                            memEnabled = e
                            BrainMemoryManager.updateConfig(cfg.copy(enabled = e))
                        })
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(localizedText("qtai-sj 大脑：", "qtai-sj brain: ") + memList.size + localizedText("条记忆 · 模式:", " memories · mode:") + memMode + localizedText(" · 情感感知:", " · emotional awareness:") + if (cfg.emotionalAwareness) localizedText("开", "on") else localizedText("关", "off"),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                    if (memEnabled) {
                        Spacer(modifier = Modifier.height(8.dp))
                        // 保存模式
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(localizedText("保存模式:", "Save mode:"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(8.dp))
                            val modes = listOf("frequent" to localizedText("频繁", "Frequent"), "normal" to localizedText("正常", "Normal"), "occasional" to localizedText("偶尔", "Occasional"))
                            modes.forEach { (k, v) ->
                                FilterChip(selected = memMode == k, onClick = {
                                    memMode = k
                                    BrainMemoryManager.updateConfig(cfg.copy(saveMode = k))
                                }, label = { Text(v, style = MaterialTheme.typography.labelSmall) }, modifier = Modifier.padding(end = 4.dp))
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        // 上限设置
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(localizedText("上限:", "Limit:"), style = MaterialTheme.typography.labelSmall)
                            Spacer(Modifier.width(4.dp))
                            OutlinedTextField(value = memMax, onValueChange = { v ->
                                memMax = v; v.toIntOrNull()?.let { n ->
                                    BrainMemoryManager.updateConfig(cfg.copy(maxShortTerm = n))
                                }
                            }, singleLine = true, modifier = Modifier.width(80.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(onClick = { BrainMemoryManager.clearAll(); memList = emptyList(); scope.launch { snackbarHostState.showSnackbar(localizedText("🧹 所有记忆已清空", "🧹 All memories cleared")) } },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Error)) {
                                Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(2.dp)); Text(localizedText("清空", "Clear"), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        // 展开/折叠
                        TextButton(onClick = { showMemDetail = !showMemDetail }) {
                            Text(text = if (showMemDetail) localizedText("▲ 收起记忆列表", "▲ Collapse memory list") else localizedText("▼ 展开记忆列表 (", "▼ Expand memory list (") + memList.size + localizedText("条)", ")"), style = MaterialTheme.typography.labelMedium)
                        }
                        if (showMemDetail) {
                            // 搜索框
                            OutlinedTextField(value = memSearchQuery, onValueChange = { memSearchQuery = it },
                                placeholder = { Text(localizedText("搜索记忆...", "Search memories..."), style = MaterialTheme.typography.bodySmall) },
                                singleLine = true, modifier = Modifier.fillMaxWidth(), leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp)) })
                            Spacer(modifier = Modifier.height(4.dp))
                            // 记忆列表
                            val filtered = if (memSearchQuery.isBlank()) memList else BrainMemoryManager.search(memSearchQuery)
                            if (filtered.isEmpty()) {
                                Text(localizedText("暂无记忆", "No memories yet"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
                            } else {
                                LazyColumn(modifier = Modifier.heightIn(max = 300.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    items(filtered) { mem ->
                                        Card(modifier = Modifier.fillMaxWidth().clickable {
                                            editingMem = mem; editTitle = mem.title; editContent = mem.content; editEmotion = mem.emotion; editImportance = mem.importance.toString()
                                        }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                                            Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                                // 情感图标
                                                val emoji = when (mem.emotion) { "happy" -> "😊"; "sad" -> "😢"; "angry" -> "😠"; "surprised" -> "😮"; else -> "😐" }
                                                Text(emoji, style = MaterialTheme.typography.bodyMedium)
                                                Spacer(Modifier.width(6.dp))
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(mem.title, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                    Text(localizedText("重要:", "Importance:") + "${mem.importance}/10 · ${mem.type} · ${java.text.SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(java.util.Date(mem.timestamp))}",
                                                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                }
                                                IconButton(onClick = { BrainMemoryManager.deleteMemory(mem.id); memList = BrainMemoryManager.getAll() }, modifier = Modifier.size(28.dp)) {
                                                    Icon(Icons.Default.Delete, contentDescription = localizedText("删除", "Delete"), tint = Error, modifier = Modifier.size(16.dp))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            // 编辑记忆弹窗
            if (editingMem != null) {
                AlertDialog(onDismissRequest = { editingMem = null },
                    title = { Text(localizedText("✏️ 编辑记忆", "✏️ Edit memory"), fontWeight = FontWeight.Bold) },
                    text = {
                        Column {
                            OutlinedTextField(value = editTitle, onValueChange = { editTitle = it }, label = { Text(localizedText("标题", "Title")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(value = editContent, onValueChange = { editContent = it }, label = { Text(localizedText("内容", "Content")) }, modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp), maxLines = 5)
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(localizedText("情感:", "Emotion:"), style = MaterialTheme.typography.labelSmall)
                                Spacer(Modifier.width(8.dp))
                                listOf("neutral" to "😐", "happy" to "😊", "sad" to "😢", "angry" to "😠", "surprised" to "😮").forEach { (k, v) ->
                                    FilterChip(selected = editEmotion == k, onClick = { editEmotion = k },
                                        label = { Text(v, style = MaterialTheme.typography.labelSmall) }, modifier = Modifier.padding(end = 2.dp))
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(localizedText("重要性 (0-10):", "Importance (0-10):"), style = MaterialTheme.typography.labelSmall)
                                Spacer(Modifier.width(8.dp))
                                OutlinedTextField(value = editImportance, onValueChange = { editImportance = it }, singleLine = true, modifier = Modifier.width(80.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                            }
                        }
                    },
                    confirmButton = {
                        Button(onClick = {
                            BrainMemoryManager.updateMemory(editingMem!!.id, title = editTitle, content = editContent, emotion = editEmotion, importance = editImportance.toIntOrNull())
                            editingMem = null; memList = BrainMemoryManager.getAll()
                            scope.launch { snackbarHostState.showSnackbar(localizedText("✅ 记忆已更新", "✅ Memory updated")) }
                        }) { Text(localizedText("保存", "Save")) }
                    },
                    dismissButton = { TextButton(onClick = { editingMem = null }) { Text(localizedText("取消", "Cancel")) } }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ★★ 人格设置卡片（綦小桐）★★
            // ★ 实时读取配置，避免 stale 数据 ★
            val pCfg = BrainMemoryManager.getConfig()
            var personaEnabled by remember { mutableStateOf(pCfg.personaEnabled) }
            var personaName by remember { mutableStateOf(pCfg.personaName) }
            var personaAge by remember { mutableStateOf(pCfg.personaAge.toString()) }
            var personaTraits by remember { mutableStateOf(pCfg.personaTraits) }
            var personaStyle by remember { mutableStateOf(pCfg.personaStyle) }
            var personaBg by remember { mutableStateOf(pCfg.personaBackground) }
            var envAware by remember { mutableStateOf(pCfg.envAwareness) }

            // ★ 监听 config 变化刷新本地状态
            LaunchedEffect(Unit) {
                while (true) {
                    delay(500)
                    val cfg = BrainMemoryManager.getConfig()
                    if (cfg.personaEnabled != personaEnabled) personaEnabled = cfg.personaEnabled
                    if (cfg.personaName != personaName) personaName = cfg.personaName
                    if (cfg.personaAge.toString() != personaAge) personaAge = cfg.personaAge.toString()
                    if (cfg.personaTraits != personaTraits) personaTraits = cfg.personaTraits
                    if (cfg.personaStyle != personaStyle) personaStyle = cfg.personaStyle
                    if (cfg.personaBackground != personaBg) personaBg = cfg.personaBackground
                    if (cfg.envAwareness != envAware) envAware = cfg.envAwareness
                }
            }

            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Face, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(localizedText("🧑 人格设定", "🧑 Persona settings"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(localizedText("定制 qtai-sj 的个性化形象", "Customize the qtai-sj persona"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(localizedText("启用人格系统", "Enable persona system"), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Switch(checked = personaEnabled, onCheckedChange = { e ->
                            personaEnabled = e
                            BrainMemoryManager.updateConfig(BrainMemoryManager.getConfig().copy(personaEnabled = e))
                        })
                    }
                    Spacer(modifier = Modifier.height(4.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = personaName, onValueChange = { v ->
                            personaName = v
                            BrainMemoryManager.updateConfig(BrainMemoryManager.getConfig().copy(personaName = v))
                            GatewayForegroundService.saveQtaiSjName(v)
                        }, label = { Text(localizedText("名字", "Name")) }, singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedTextField(value = personaAge, onValueChange = { v ->
                            personaAge = v
                            v.toIntOrNull()?.let { BrainMemoryManager.updateConfig(BrainMemoryManager.getConfig().copy(personaAge = it)) }
                        }, label = { Text(localizedText("年龄", "Age")) }, singleLine = true, modifier = Modifier.width(80.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    }
                    Spacer(modifier = Modifier.height(4.dp))

                    OutlinedTextField(value = localizeGeneratedContent(personaTraits), onValueChange = { v ->
                        personaTraits = v
                        BrainMemoryManager.updateConfig(BrainMemoryManager.getConfig().copy(personaTraits = v))
                    }, label = { Text(localizedText("性格特征（逗号分隔）", "Personality traits (comma-separated)")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(4.dp))

                    val styleOptions = listOf(
                        "亲切自然" to localizedText("亲切自然", "Friendly and natural"),
                        "专业严谨" to localizedText("专业严谨", "Professional and precise"),
                        "活泼可爱" to localizedText("活泼可爱", "Lively and cute"),
                    )
                    var styleExpanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(expanded = styleExpanded, onExpandedChange = { styleExpanded = it }) {
                        OutlinedTextField(value = localizeGeneratedName(personaStyle), onValueChange = {}, readOnly = true,
                            label = { Text(localizedText("语气风格", "Tone style")) }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = styleExpanded) },
                            modifier = Modifier.fillMaxWidth().menuAnchor())
                        ExposedDropdownMenu(expanded = styleExpanded, onDismissRequest = { styleExpanded = false }) {
                            styleOptions.forEach { (storedValue, label) ->
                                DropdownMenuItem(text = { Text(label) }, onClick = {
                                    personaStyle = storedValue
                                    BrainMemoryManager.updateConfig(BrainMemoryManager.getConfig().copy(personaStyle = storedValue))
                                    styleExpanded = false
                                })
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))

                    OutlinedTextField(value = localizeGeneratedContent(personaBg), onValueChange = { v ->
                        personaBg = v
                        BrainMemoryManager.updateConfig(BrainMemoryManager.getConfig().copy(personaBackground = v))
                    }, label = { Text(localizedText("背景设定", "Background setting")) }, modifier = Modifier.fillMaxWidth(),
                    minLines = 2, maxLines = 3)
                    Spacer(modifier = Modifier.height(4.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(localizedText("环境感知（时间/网络）", "Context awareness (time/network)"), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Switch(checked = envAware, onCheckedChange = { e ->
                            envAware = e
                            BrainMemoryManager.updateConfig(BrainMemoryManager.getConfig().copy(envAwareness = e))
                        })
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(8.dp))

                    // ★★ 人格维度滑块 ★★
                    Text(localizedText("🎭 人格维度（大五人格）", "🎭 Personality Dimensions"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))

                    val pCfg = BrainMemoryManager.getConfig()
                    var openness by remember { mutableStateOf(pCfg.openness) }
                    var conscientiousness by remember { mutableStateOf(pCfg.conscientiousness) }
                    var extraversion by remember { mutableStateOf(pCfg.extraversion) }
                    var agreeableness by remember { mutableStateOf(pCfg.agreeableness) }
                    var neuroticism by remember { mutableStateOf(pCfg.neuroticism) }
                    var humorLevel by remember { mutableStateOf(pCfg.humorLevel) }
                    var empathyLevel by remember { mutableStateOf(pCfg.empathyLevel) }

                    PersonaSlider(label = localizedText("开放性", "Openness"), value = openness, range = 1..10, onChange = { openness = it; BrainMemoryManager.updateConfig(BrainMemoryManager.getConfig().copy(openness = it)) })
                    PersonaSlider(label = localizedText("尽责性", "Conscientiousness"), value = conscientiousness, range = 1..10, onChange = { conscientiousness = it; BrainMemoryManager.updateConfig(BrainMemoryManager.getConfig().copy(conscientiousness = it)) })
                    PersonaSlider(label = localizedText("外向性", "Extraversion"), value = extraversion, range = 1..10, onChange = { extraversion = it; BrainMemoryManager.updateConfig(BrainMemoryManager.getConfig().copy(extraversion = it)) })
                    PersonaSlider(label = localizedText("宜人性", "Agreeableness"), value = agreeableness, range = 1..10, onChange = { agreeableness = it; BrainMemoryManager.updateConfig(BrainMemoryManager.getConfig().copy(agreeableness = it)) })
                    PersonaSlider(label = localizedText("神经质", "Neuroticism"), value = neuroticism, range = 1..10, onChange = { neuroticism = it; BrainMemoryManager.updateConfig(BrainMemoryManager.getConfig().copy(neuroticism = it)) })
                    PersonaSlider(label = localizedText("幽默感", "Humor"), value = humorLevel, range = 1..10, onChange = { humorLevel = it; BrainMemoryManager.updateConfig(BrainMemoryManager.getConfig().copy(humorLevel = it)) })
                    PersonaSlider(label = localizedText("共情力", "Empathy"), value = empathyLevel, range = 1..10, onChange = { empathyLevel = it; BrainMemoryManager.updateConfig(BrainMemoryManager.getConfig().copy(empathyLevel = it)) })
                }
            }

            // ★ 多语言设置卡片
            var showLangSelector by remember { mutableStateOf(false) }
            val currentLang by TranslationManager.currentLanguageFlow.collectAsState()
            val autoDetect by TranslationManager.autoDetectFlow.collectAsState()
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
                                if (autoDetect) localizedText("当前: ", "Current: ") + currentLang.displayName
                                else localizedText("手动: ", "Manual: ") + currentLang.displayName,
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = autoDetect,
                            onCheckedChange = { enabled ->
                                TranslationManager.setAutoDetect(enabled, context)
                                showLangSelector = !enabled
                            }
                        )
                    }

                    if (!autoDetect) {
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(onClick = { showLangSelector = true }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Language, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text(currentLang.displayName)
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
                                        containerColor = if (lang == currentLang)
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                                        else MaterialTheme.colorScheme.surface
                                    )
                                ) {
                                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text(lang.displayName,
                                            modifier = Modifier.weight(1f),
                                            fontWeight = if (lang == currentLang) FontWeight.Bold else FontWeight.Normal
                                        )
                                        if (lang == currentLang) {
                                            Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                }
                            }
                        }
                    },
                    confirmButton = { TextButton(onClick = { showLangSelector = false }) { Text(localizedText("关闭", "Close")) } }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ★★★ 綦小桐技能管理 ★★★
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.SmartToy, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(localizedText("🤖 綦小桐技能管理", "🤖 QiTong AI skills"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Text(localizedText("管理綦小桐的连续对话和技能开关", "Manage continuous chat and skill toggles"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    // 连续对话模式开关
                    val continuousChat = remember { mutableStateOf(GatewayForegroundService.isContinuousChat()) }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(localizedText("💬 连续对话模式", "💬 Continuous chat mode"), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Switch(checked = continuousChat.value, onCheckedChange = { e ->
                            continuousChat.value = e
                            GatewayForegroundService.setContinuousChat(e)
                            scope.launch { snackbarHostState.showSnackbar(if (e) localizedText("✅ 连续对话已开启，所有消息自动走綦小桐大脑", "✅ Continuous chat ON") else localizedText("✅ 连续对话已关闭，需喊綦小桐才能触发", "✅ Continuous chat OFF")) }
                        })
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(if (continuousChat.value) localizedText("🔵 开启后所有消息自动由綦小桐大脑处理，无需喊前缀", "🔵 All messages auto-processed by 綦小桐 brain, no prefix needed") else localizedText("⚪ 关闭后需喊「綦小桐」或前缀才能触发大脑", "⚪ Say '綦小桐' or prefix to trigger brain"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    
                    // 技能列表
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { showSkillManagement = true }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.List, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text(localizedText("📋 管理技能 (${SkillRegistry.allSkills.size}内置 + ${CustomSkillManager.getAll().size}自定义)", "📋 Manage skills (${SkillRegistry.allSkills.size} built-in + ${CustomSkillManager.getAll().size} custom)"))
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            // ★ 密钥管理按钮
            Card(modifier = Modifier.fillMaxWidth().clickable { showKeyManagement = true }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.VpnKey, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(localizedText("🔑 API 密钥管理", "🔑 API Key management"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Text(localizedText("管理访问密钥，本地请求免密钥，每把钥匙可单独控制模型访问权限", "Manage access keys. Local requests are exempt. Each key can control model access individually"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))

            // ★ Debug 抓包模式
            val debugMode by viewModel.debugMode.collectAsState()
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.BugReport, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(localizedText("🔍 网关抓包", "🔍 Gateway packet capture"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(localizedText("开启后记录所有网关请求/响应到内存，可查看最近20条（含实时输入/输出流量）", "When enabled, records all gateway requests/responses in memory; view the latest 20 records including live input/output traffic"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = { viewModel.toggleDebugMode() }, colors = ButtonDefaults.buttonColors(
                            containerColor = if (debugMode) Error else Online)) {
                            Text(if (debugMode) localizedText("⏹ 停止抓包", "⏹ Stop capture") else localizedText("▶️ 开始抓包", "▶️ Start capture"))
                        }
                        OutlinedButton(onClick = { showDebugLogs = true }) {
                            Icon(Icons.Default.List, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text(localizedText("查看日志", "View logs"))
                        }
                    }
                    if (debugMode) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(localizedText("🟢 抓包运行中...", "🟢 Capture running..."), style = MaterialTheme.typography.labelSmall, color = Online)
                    }
                }
            }

            // ★★ 抓包日志页面（全屏覆盖）★★

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(8.dp))

            // ★★ 思考引导配置 ★★
            val thinkingEnabled = remember { mutableStateOf(ThinkingConfigManager.isEnabled()) }
            val thinkingDepth = remember { mutableStateOf(ThinkingConfigManager.getDepth()) }
            val depthOptions = ThinkingConfigManager.ThinkingDepth.entries
            var depthExpanded by remember { mutableStateOf(false) }
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Psychology, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(localizedText("🧠 思考引导", "🧠 Thinking guide"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Switch(checked = thinkingEnabled.value, onCheckedChange = { e ->
                            thinkingEnabled.value = e
                            ThinkingConfigManager.setEnabled(e)
                        })
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(localizedText("在 qtai-sj 回复前注入思考引导，让 AI 先分析再回答", "Inject a thinking guide before qtai-sj replies so the AI analyzes first, then answers"),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (thinkingEnabled.value) {
                        Spacer(modifier = Modifier.height(8.dp))
                        ExposedDropdownMenuBox(expanded = depthExpanded, onExpandedChange = { depthExpanded = it }) {
                            OutlinedTextField(value = thinkingDepth.value.localizedLabel(), onValueChange = {}, readOnly = true,
                                label = { Text(localizedText("思考深度", "Thinking depth")) }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = depthExpanded) },
                                modifier = Modifier.fillMaxWidth().menuAnchor())
                            ExposedDropdownMenu(expanded = depthExpanded, onDismissRequest = { depthExpanded = false }) {
                                depthOptions.forEach { opt ->
                                    DropdownMenuItem(text = { Text(opt.localizedLabel()) }, onClick = {
                                        thinkingDepth.value = opt
                                        ThinkingConfigManager.setDepth(opt)
                                        if (opt == ThinkingConfigManager.ThinkingDepth.OFF) {
                                            ThinkingConfigManager.setEnabled(false)
                                            thinkingEnabled.value = false
                                        }
                                        depthExpanded = false
                                    })
                                }
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))

            // ★★ 群聊模式配置 ★★
            val groupChatEnabled = remember { mutableStateOf(GroupChatManager.isEnabled()) }
            val groupChatSummarizer = remember { mutableStateOf(GroupChatManager.getSummarizerModel()) }
            val groupChatMaxRounds = remember { mutableStateOf(GroupChatManager.getMaxRounds().toString()) }
            var showGroupChatModelPicker by remember { mutableStateOf(false) }
            var showGroupChatSummarizerPicker by remember { mutableStateOf(false) }
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Group, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(localizedText("💬 群聊模式", "💬 Group chat mode"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Switch(checked = groupChatEnabled.value, onCheckedChange = { e ->
                            groupChatEnabled.value = e
                            GroupChatManager.setEnabled(e)
                        })
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(localizedText("虚拟沙箱：用户发消息 → AI依次发言 → 总结者输出", "Virtual sandbox: user sends a message → AIs speak in order → summarizer outputs"),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (groupChatEnabled.value) {
                        Spacer(modifier = Modifier.height(8.dp))
                        // ★ 参与模型（从排行榜勾选）★★
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(localizedText("参与模型: ", "Participant models: "), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(GroupChatManager.getParticipantModels().joinToString(", ").take(30) + if (GroupChatManager.getParticipantModels().joinToString(", ").length > 30) "..." else "",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Button(onClick = { showGroupChatModelPicker = true }, modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)) {
                            Icon(Icons.Default.List, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(localizedText("📊 从测速排行榜选择模型 (", "📊 Select models from speed ranking (") + GroupChatManager.getParticipantModels().size + localizedText("个)", ")"))
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        // ★ 总结模型（从排行榜勾选）★★
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(localizedText("总结模型: ", "Summary model: "), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(GroupChatManager.getSummarizerModel().ifBlank { localizedText("未设置", "Not set") },
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Button(onClick = { showGroupChatSummarizerPicker = true }, modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)) {
                            Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(localizedText("🎯 选择总结模型", "🎯 Select summary model"))
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        OutlinedTextField(value = groupChatMaxRounds.value, onValueChange = { v ->
                            groupChatMaxRounds.value = v
                            v.toIntOrNull()?.let { GroupChatManager.setMaxRounds(it) }
                        }, label = { Text(localizedText("轮次", "Rounds")) }, singleLine = true, modifier = Modifier.width(100.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    }
                }
            }
            // ★ 参与模型选择弹窗（排行榜列表勾选）★★
            if (showGroupChatModelPicker) {
                // ★★★ 弹窗打开时快照数据，避免实时刷新导致列表跳动 ★★★
                val snapshotModels = remember(viewModel) {
                    viewModel.enabledModels.value.ifEmpty {
                        listOf<com.qtwl.gateway.data.model.AiModel>()
                    }
                }
                val snapshotSortedIds = remember { com.qtwl.gateway.gateway.GatewayScheduler.pipelineSortedModelIds.toList() }
                val sortedModels = remember {
                    snapshotModels.sortedByDescending { snapshotSortedIds.indexOf(it.modelId) }.reversed()
                }
                val currentParticipants = remember { mutableStateListOf<String>().apply { addAll(GroupChatManager.getParticipantModels()) } }
                AlertDialog(
                    onDismissRequest = { showGroupChatModelPicker = false },
                    title = { Text(localizedText("选择参与模型", "Select participant models"), fontWeight = FontWeight.Bold) },
                    text = {
                        if (sortedModels.isEmpty()) {
                            Text(localizedText("暂无可用模型，请先添加服务商和启用模型", "No available models. Add a provider and enable models first"))
                        } else {
                            LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                                items(sortedModels) { model ->
                                    val isSelected = model.modelId in currentParticipants
                                    val rank = snapshotSortedIds.indexOf(model.modelId)
                                    val rankStr = if (rank >= 0) " #${rank + 1}" else ""
                                    Row(modifier = Modifier.fillMaxWidth().clickable {
                                        if (isSelected) currentParticipants.remove(model.modelId)
                                        else currentParticipants.add(model.modelId)
                                    }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(checked = isSelected, onCheckedChange = { c ->
                                            if (c) currentParticipants.add(model.modelId)
                                            else currentParticipants.remove(model.modelId)
                                        })
                                        Spacer(Modifier.width(4.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(model.modelId, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                                            Text(model.displayName.ifBlank { model.customAlias }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        if (rank >= 0) {
                                            Text(rankStr, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                    HorizontalDivider()
                                }
                            }
                        }
                    },
                    confirmButton = {
                        Button(onClick = {
                            GroupChatManager.setParticipantModels(currentParticipants.toList())
                            showGroupChatModelPicker = false
                        }) { Text(localizedText("确定 (", "OK (") + currentParticipants.size + localizedText("个)", ")")) }
                    },
                    dismissButton = { TextButton(onClick = { showGroupChatModelPicker = false }) { Text(localizedText("取消", "Cancel")) } }
                )
            }
            // ★ 总结模型选择弹窗 ★★
            if (showGroupChatSummarizerPicker) {
                // ★★★ 弹窗打开时快照数据，避免实时刷新导致列表跳动 ★★★
                val snapshotModels = remember(viewModel) {
                    viewModel.enabledModels.value.ifEmpty {
                        listOf<com.qtwl.gateway.data.model.AiModel>()
                    }
                }
                val snapshotSortedIds = remember { com.qtwl.gateway.gateway.GatewayScheduler.pipelineSortedModelIds.toList() }
                val sortedModels = remember {
                    snapshotModels.sortedByDescending { snapshotSortedIds.indexOf(it.modelId) }.reversed()
                }
                var selectedSummarizer by remember { mutableStateOf(GroupChatManager.getSummarizerModel()) }
                AlertDialog(
                    onDismissRequest = { showGroupChatSummarizerPicker = false },
                    title = { Text(localizedText("选择总结模型", "Select summary model"), fontWeight = FontWeight.Bold) },
                    text = {
                        if (sortedModels.isEmpty()) {
                            Text(localizedText("暂无可用模型", "No available models"))
                        } else {
                            LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                                // 加一个localizedText("无总结者", "No summarizer")选项
                                item {
                                    Row(modifier = Modifier.fillMaxWidth().clickable { selectedSummarizer = "" }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                        RadioButton(selected = selectedSummarizer == "", onClick = { selectedSummarizer = "" })
                                        Spacer(Modifier.width(4.dp))
                                        Text(localizedText("无总结者", "No summarizer"), style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                                item { HorizontalDivider() }
                                items(sortedModels) { model ->
                                    val rank = snapshotSortedIds.indexOf(model.modelId)
                                    val rankStr = if (rank >= 0) " #${rank + 1}" else ""
                                    Row(modifier = Modifier.fillMaxWidth().clickable { selectedSummarizer = model.modelId }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                        RadioButton(selected = selectedSummarizer == model.modelId, onClick = { selectedSummarizer = model.modelId })
                                        Spacer(Modifier.width(4.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(model.modelId, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                                            Text(model.displayName.ifBlank { model.customAlias }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        if (rank >= 0) {
                                            Text(rankStr, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                    HorizontalDivider()
                                }
                            }
                        }
                    },
                    confirmButton = {
                        Button(onClick = {
                            GroupChatManager.setSummarizerModel(selectedSummarizer)
                            groupChatSummarizer.value = selectedSummarizer
                            showGroupChatSummarizerPicker = false
                        }) { Text(localizedText("确定", "OK")) }
                    },
                    dismissButton = { TextButton(onClick = { showGroupChatSummarizerPicker = false }) { Text(localizedText("取消", "Cancel")) } }
                )
            }
            Spacer(modifier = Modifier.height(16.dp))

            // 添加服务
            Text(localizedText("🔌 添加服务", "🔌 Add service"), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(localizedText("支持 OpenAI Compatible API，自动检测端口，智能获取模型列表", "Supports OpenAI-compatible APIs, auto-detects ports, and intelligently fetches model lists"),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(8.dp))

            ServiceTemplateCard(localizedText("Ollama (本地)", "Ollama (local)"), "http://localhost:11434") { viewModel.showAddProvider() }
            ServiceTemplateCard("OpenAI", "https://api.openai.com") { viewModel.showAddProvider() }
            ServiceTemplateCard(localizedText("自定义 OpenAI Compatible", "Custom OpenAI-compatible"), localizedText("输入任意兼容 OpenAI API 格式的地址", "Enter any OpenAI API-compatible address")) { showAddServiceDialog = true }

            Spacer(modifier = Modifier.height(80.dp))
        }
    }
// ★★ 抓包日志全屏覆盖（脱离 verticalScroll）★★
        if (showDebugLogs) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
            ) {
                Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(localizedText("🔍 网关抓包日志", "🔍 Gateway packet-capture logs"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        TextButton(onClick = { showDebugLogs = false }) { Text(localizedText("✕ 关闭", "✕ Close")) }
                    }
                    Spacer(modifier = Modifier.height(8.dp))

                    var filterText by remember { mutableStateOf("") }
                    var statusFilter by remember { mutableStateOf<String?>(null) }

                    // ★ 筛选后的记录列表
                    val filteredRecords = remember(filterText, statusFilter) {
                        var list = com.qtwl.gateway.capture.PacketCapture.records.toList()
                        if (statusFilter == "200") list = list.filter { it.response?.httpStatus == 200 }
                        else if (statusFilter == "ERR") list = list.filter { it.response?.httpStatus ?: 0 >= 400 || it.failover != null }
                        if (filterText.isNotBlank()) list = list.filter {
                            it.summary.contains(filterText, ignoreCase = true) ||
                            it.outbound?.body?.contains(filterText, ignoreCase = true) == true ||
                            it.inbound?.body?.contains(filterText, ignoreCase = true) == true
                        }
                        list
                    }

                    val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(value = filterText, onValueChange = { filterText = it },
                            placeholder = { Text(localizedText("🔍 搜索", "🔍 Search")) }, singleLine = true, modifier = Modifier.weight(2f),
                            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp))
                        TextButton(onClick = { statusFilter = null }) { Text(if (statusFilter == null) localizedText("全部", "All") else localizedText("全部", "All")) }
                        TextButton(onClick = { statusFilter = "200" }) { Text("200") }
                        TextButton(onClick = { statusFilter = "ERR" }) { Text("ERR") }
                    }
                    Spacer(modifier = Modifier.height(8.dp))

                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(filteredRecords) { record ->
                            val isError = record.response?.httpStatus ?: 0 >= 400
                            var showDetail by remember(record.id) { mutableStateOf(false) }
                            Card(
                                modifier = Modifier.fillMaxWidth().clickable { showDetail = true },
                                colors = CardDefaults.cardColors(containerColor = if (isError) MaterialTheme.colorScheme.error.copy(alpha = 0.05f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(text = if (isError) "❌" else if (record.failover != null) "🔄" else "✅", style = MaterialTheme.typography.bodyMedium)
                                        Text(text = record.summary, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text(text = timeFmt.format(record.timestamp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace)
                                        record.response?.let { resp -> if (resp.promptTokens > 0) Text(text = "↑${resp.promptTokens} ↓${resp.completionTokens}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary) }
                                        Spacer(modifier = Modifier.weight(1f))
                                        Text(text = record.outbound?.modelId ?: "?", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                                    }
                                }
                            }
                            if (showDetail) {
                                AlertDialog(
                                    onDismissRequest = { showDetail = false },
                                    title = { Text(localizedText("📦 抓包详情 #", "📦 Packet capture details #") + record.id) },
                                    text = {
                                        LazyColumn(modifier = Modifier.heightIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            record.inbound?.let { inbound -> item {
                                                Text(localizedText("📥 入站", "📥 Inbound"), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                                Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), shape = MaterialTheme.shapes.small) {
                                                    Text(buildString {
                                                        appendLine(localizedText("方法: ", "Method: ") + "${inbound.method} ${inbound.path}")
                                                        appendLine(localizedText("头部: ", "Headers: ") + inbound.headers)
                                                        appendLine(localizedText("--- 请求体 (", "--- Request body (") + "${inbound.bodySize}B) ---")
                                                        appendLine(inbound.body)
                                                    }, fontFamily = FontFamily.Monospace, fontSize = 10.sp, modifier = Modifier.padding(8.dp))
                                                }
                                            }}
                                            record.outbound?.let { outbound -> item {
                                                Text(localizedText("📤 出站", "📤 Outbound"), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
                                                Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), shape = MaterialTheme.shapes.small) {
                                                    Text(buildString {
                                                        appendLine("URL: ${outbound.targetUrl}")
                                                        appendLine(localizedText("模型: ", "Model: ") + outbound.modelId)
                                                        appendLine(localizedText("--- 请求体 (", "--- Request body (") + "${outbound.bodySize}B) ---")
                                                        appendLine(outbound.body)
                                                    }, fontFamily = FontFamily.Monospace, fontSize = 10.sp, modifier = Modifier.padding(8.dp))
                                                }
                                            }}
                                            record.response?.let { resp -> item {
                                                Text(localizedText("📥 响应", "📥 Response"), fontWeight = FontWeight.Bold, color = if (resp.httpStatus >= 500) MaterialTheme.colorScheme.error else if (resp.httpStatus >= 400) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary)
                                                Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), shape = MaterialTheme.shapes.small) {
                                                    Text(buildString {
                                                        appendLine(localizedText("状态: ", "Status: ") + "${resp.httpStatus} | ${resp.elapsedMs}ms")
                                                        appendLine("Tokens: ↑${resp.promptTokens} ↓${resp.completionTokens}")
                                                        appendLine(localizedText("--- 响应体 (", "--- Response body (") + "${resp.bodySize}B) ---")
                                                        appendLine(resp.body)
                                                    }, fontFamily = FontFamily.Monospace, fontSize = 10.sp, modifier = Modifier.padding(8.dp))
                                                }
                                            }}
                                            record.failover?.let { failover -> item {
                                                Text(localizedText("🔄 故障转移", "🔄 Failover"), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                                                Surface(color = MaterialTheme.colorScheme.error.copy(alpha = 0.08f), shape = MaterialTheme.shapes.small) {
                                                    Text(buildString {
                                                        failover.attempts.forEach { attempt ->
                                                            appendLine("[${attempt.index}] ${attempt.modelId}: ${attempt.error} (${attempt.elapsedMs}ms)")
                                                        }
                                                    }, fontFamily = FontFamily.Monospace, fontSize = 10.sp, modifier = Modifier.padding(8.dp))
                                                }
                                            }}
                                        }
                                    },
                                    confirmButton = {
                                        val ctx = LocalContext.current
                                        Button(onClick = {
                                            val detailText = buildString {
                                                appendLine(localizedText("📦 抓包详情 #", "📦 Packet capture details #") + record.id)
                                                appendLine(localizedText("时间: ", "Time: ") + timeFmt.format(record.timestamp))
                                                record.inbound?.let { inbound ->
                                                    appendLine(localizedText("\\n📥 入站", "\\n📥 Inbound"))
                                                    appendLine(localizedText("方法: ", "Method: ") + "${inbound.method} ${inbound.path}")
                                                    appendLine(localizedText("头部: ", "Headers: ") + inbound.headers)
                                                    appendLine(localizedText("--- 请求体 (", "--- Request body (") + "${inbound.bodySize}B) ---")
                                                    appendLine(inbound.body)
                                                }
                                                record.outbound?.let { outbound ->
                                                    appendLine(localizedText("\\n📤 出站", "\\n📤 Outbound"))
                                                    appendLine("URL: ${outbound.targetUrl}")
                                                    appendLine(localizedText("模型: ", "Model: ") + outbound.modelId)
                                                    appendLine(localizedText("--- 请求体 (", "--- Request body (") + "${outbound.bodySize}B) ---")
                                                    appendLine(outbound.body)
                                                }
                                                record.response?.let { resp ->
                                                    appendLine(localizedText("\\n📥 响应", "\\n📥 Response"))
                                                    appendLine(localizedText("状态: ", "Status: ") + "${resp.httpStatus} | ${resp.elapsedMs}ms")
                                                    appendLine("Tokens: ↑${resp.promptTokens} ↓${resp.completionTokens}")
                                                    appendLine(localizedText("--- 响应体 (", "--- Response body (") + "${resp.bodySize}B) ---")
                                                    appendLine(resp.body)
                                                }
                                                record.failover?.let { failover ->
                                                    appendLine(localizedText("\\n🔄 故障转移", "\\n🔄 Failover"))
                                                    failover.attempts.forEach { attempt ->
                                                        appendLine("[${attempt.index}] ${attempt.modelId}: ${attempt.error} (${attempt.elapsedMs}ms)")
                                                    }
                                                }
                                            }
                                            val clipboard = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText(localizedText("抓包详情", "Packet capture details"), detailText))
                                        }) { Text(localizedText("📋 复制", "📋 Copy")) }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        } // end showDebugLogs
    } // end Box
    // 密钥管理全屏覆盖
    if (showKeyManagement) {
        Box(modifier = Modifier.fillMaxSize()) {
            KeyManagementScreen(onDismiss = { showKeyManagement = false })
        }
    }
    // 技能管理全屏覆盖
    if (showSkillManagement) {
        Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
            SkillManagementScreen(onDismiss = { showSkillManagement = false })
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
            title = localizedText("添加代理", "Add proxy"), viewModel = viewModel,
            onDismiss = { showAddProxyDialog = false },
            onConfirm = { profile -> viewModel.addProxy(profile); showAddProxyDialog = false }
        )
    }

    // 重置确认弹窗（需输入"确认重置"）
    if (showResetConfirm) {
        var confirmInput by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text(localizedText("⚠️ 危险操作确认", "⚠️ Dangerous operation confirmation"), fontWeight = FontWeight.Bold, color = Error) },
            text = {
                Column {
                    Text(localizedText("此操作将永久删除所有数据，包括：\\n• 所有服务商配置\\n• 所有 AI 模型列表\\n• 所有聊天记录和对话\\n• 所有 Token 用量统计\\n\\n此操作不可撤销！", "This will permanently delete all data, including:\\n• All provider configurations\\n• All AI model lists\\n• All chats and conversations\\n• All token usage statistics\\n\\nThis action cannot be undone!"), style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(localizedText("请输入「确认重置」以继续：", "Please type \"Confirm reset\" to continue:"), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = confirmInput,
                        onValueChange = { confirmInput = it },
                        label = { Text(localizedText("确认重置", "Confirm reset")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.resetAllData(); showResetConfirm = false },
                    enabled = confirmInput == localizedText("确认重置", "Confirm reset"),
                    colors = ButtonDefaults.buttonColors(containerColor = Error)
                ) {
                    Text(localizedText("永久删除", "Delete permanently"), color = MaterialTheme.colorScheme.onError)
                }
            },
            dismissButton = { TextButton(onClick = { showResetConfirm = false }) { Text(localizedText("取消", "Cancel")) } }
        )
    }

    // 智能添加服务弹窗
    if (showAddServiceDialog) {
        SmartAddServiceDialog(viewModel = viewModel, onDismiss = { showAddServiceDialog = false },
            onSuccess = { showAddServiceDialog = false; scope.launch { snackbarHostState.showSnackbar(localizedText("✅ 服务商添加成功！", "✅ Provider added successfully!")) } })
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
        title = { Text(localizedText("添加服务 (OpenAI Compatible)", "Add service (OpenAI-compatible)"), fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = form.name, onValueChange = { viewModel.updateFormField("name", it) },
                    label = { Text(localizedText("服务商名称", "Provider name")) }, placeholder = { Text(localizedText("例如: 我的 Ollama", "Example: My Ollama")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = form.baseUrl, onValueChange = {
                    viewModel.updateFormField("baseUrl", it)
                    val port = viewModel.extractPortFromUrl(it)
                    if (port.isNotBlank()) viewModel.updateFormField("port", port)
                }, label = { Text(localizedText("API 地址 (Base URL)", "API address (Base URL)")) }, placeholder = { Text("http://192.168.1.100:11434") },
                    supportingText = { if (detectedPort.isNotBlank()) Text(localizedText("检测到端口: ", "Detected port: ") + detectedPort, color = Online) },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = form.port, onValueChange = { viewModel.updateFormField("port", it) },
                    label = { Text(localizedText("端口 (可选)", "Port (optional)")) }, placeholder = { Text(localizedText("如 11434, 8080", "e.g. 11434, 8080")) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = form.apiKey, onValueChange = { viewModel.updateFormField("apiKey", it) },
                        label = { Text(localizedText("API Key (可选)", "API key (optional)")) }, placeholder = { Text("sk-...") }, singleLine = true,
                        visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = { IconButton(onClick = { showApiKey = !showApiKey }) {
                            Icon(if (showApiKey) Icons.Default.Visibility else Icons.Default.VisibilityOff, contentDescription = null) } },
                        modifier = Modifier.weight(1f))
                }

                // ★★★ API 路径选择（自动补全）★★★
                val apiPathOptions = listOf("/v1/chat/completions", "/v1/messages", "/v1/completions", "/v1/embeddings", "/v1/rerank", "/v1/moderations", "/v1/audio/speech", "/v1/images/generations", "/v1/videos", "/chat/completions", "/completions", "/generate")
                var chatPathExpanded by remember { mutableStateOf(false) }
                var chatPathText by remember(form.chatPath) { mutableStateOf(form.chatPath) }
                ExposedDropdownMenuBox(expanded = chatPathExpanded, onExpandedChange = { chatPathExpanded = it }) {
                    OutlinedTextField(
                        value = chatPathText,
                        onValueChange = { v -> chatPathText = v; viewModel.updateFormField("chatPath", v); chatPathExpanded = true },
                        label = { Text(localizedText("对话接口路径", "Chat API path")) },
                        placeholder = { Text("/v1/chat/completions") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = chatPathExpanded) }
                    )
                    ExposedDropdownMenu(expanded = chatPathExpanded, onDismissRequest = { chatPathExpanded = false }) {
                        val filtered = apiPathOptions.filter { chatPathText.isBlank() || it.contains(chatPathText, ignoreCase = true) }
                        filtered.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option, style = MaterialTheme.typography.bodyMedium) },
                                onClick = { chatPathText = option; viewModel.updateFormField("chatPath", option); chatPathExpanded = false }
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(localizedText("💡 输入 /c 自动补全 /v1/chat/completions，/m 补全 /v1/messages 等", "💡 Type /c to auto-complete /v1/chat/completions, /m for /v1/messages, etc."),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                // ★★★ 模型列表接口路径（自动补全）★★★
                val apiPathOptions2 = listOf("/v1/models", "/api/tags", "/v1beta/models", "/models")
                var apiPathExpanded by remember { mutableStateOf(false) }
                var apiPathText by remember(form.apiPath) { mutableStateOf(form.apiPath) }
                ExposedDropdownMenuBox(expanded = apiPathExpanded, onExpandedChange = { apiPathExpanded = it }) {
                    OutlinedTextField(
                        value = apiPathText,
                        onValueChange = { v -> apiPathText = v; viewModel.updateFormField("apiPath", v); apiPathExpanded = true },
                        label = { Text(localizedText("模型列表接口路径", "Models API path")) },
                        placeholder = { Text("/v1/models") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = apiPathExpanded) }
                    )
                    ExposedDropdownMenu(expanded = apiPathExpanded, onDismissRequest = { apiPathExpanded = false }) {
                        val filtered = apiPathOptions2.filter { apiPathText.isBlank() || it.contains(apiPathText, ignoreCase = true) }
                        filtered.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option, style = MaterialTheme.typography.bodyMedium) },
                                onClick = { apiPathText = option; viewModel.updateFormField("apiPath", option); apiPathExpanded = false }
                            )
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { isTesting = true; testResult = null; viewModel.fetchAvailableModels(form.baseUrl, form.apiKey.ifBlank { null }) },
                        enabled = form.baseUrl.isNotBlank() && !isTesting, modifier = Modifier.weight(1f)) {
                        if (isTesting) { CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(modifier = Modifier.width(4.dp)); Text(localizedText("检测中...", "Detecting...")) }
                        else { Icon(Icons.Default.NetworkCheck, contentDescription = null); Spacer(modifier = Modifier.width(4.dp)); Text(localizedText("检测模型列表", "Detect model list")) }
                    }
                }
                val syncResult by viewModel.syncResult.collectAsState()
                LaunchedEffect(syncResult) { isTesting = false; testResult = syncResult }
                if (testResult != null) {
                    Card(colors = CardDefaults.cardColors(containerColor = if (testResult!!.startsWith("✅")) Online.copy(alpha = 0.15f) else if (testResult!!.startsWith("❌")) Error.copy(alpha = 0.15f) else Warning.copy(alpha = 0.15f))) {
                        Text(localizeRuntimeText(testResult!!), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { viewModel.saveProvider(); onSuccess() }, enabled = form.name.isNotBlank() && form.baseUrl.isNotBlank()) { Text(localizedText("保存", "Save")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(localizedText("取消", "Cancel")) } }
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
                Text(localizedText("⚙️ 代理管理", "⚙️ Proxy management"), fontWeight = FontWeight.Bold)
                Text(if (proxyEnabled) localizedText("🟢 已激活", "🟢 Active") else localizedText("🔴 未激活", "🔴 Inactive"), style = MaterialTheme.typography.bodySmall, color = if (proxyEnabled) Online else Error)
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
                        Text(localizedText("📡 一键订阅", "📡 One-tap subscription"), style = MaterialTheme.typography.labelMedium)
                    }
                }
                if (proxyProfiles.isEmpty()) {
                    Text(localizedText("还没有代理配置，点击下方按钮添加", "No proxy configuration yet. Tap the button below to add one"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            Button(onClick = { showAddDialog = true }) { Icon(Icons.Default.Add, contentDescription = null); Spacer(Modifier.width(4.dp)); Text(localizedText("添加代理", "Add proxy")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(localizedText("关闭", "Close")) } }
    )

    if (showAddDialog) {
        AddEditProxyDialog(title = localizedText("添加代理", "Add proxy"), viewModel = viewModel, onDismiss = { showAddDialog = false },
            onConfirm = { profile -> viewModel.addProxy(profile); showAddDialog = false })
    }
    editingProfile?.let { profile ->
        AddEditProxyDialog(title = localizedText("编辑代理", "Edit proxy"), initialProfile = profile, viewModel = viewModel,
            onDismiss = { editingProfile = null }, onConfirm = { updated -> viewModel.updateProxy(updated); editingProfile = null })
    }
    // 订阅弹窗
    if (showSubscriptionDialog) {
        var subUrl by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showSubscriptionDialog = false },
            title = { Text(localizedText("📡 一键订阅", "📡 One-tap subscription"), fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(localizedText("输入订阅地址，自动拉取并批量导入节点", "Enter a subscription URL to fetch and batch-import nodes automatically"), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = subUrl, onValueChange = { subUrl = it },
                        label = { Text(localizedText("订阅URL", "Subscription URL")) }, placeholder = { Text("https://example.com/sub?token=...") },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                Button(onClick = { viewModel.importSubscription(subUrl); showSubscriptionDialog = false }) { Text(localizedText("导入", "Import")) }
            },
            dismissButton = { TextButton(onClick = { showSubscriptionDialog = false }) { Text(localizedText("取消", "Cancel")) } }
        )
    }
    // 剪贴板检测弹窗
    if (showPasteDialog) {
        AlertDialog(
            onDismissRequest = { showPasteDialog = false },
            title = { Text(localizedText("📋 检测到代理链接", "📋 Proxy link detected"), fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(localizedText("剪贴板中检测到代理/订阅链接：", "Proxy/subscription link detected in clipboard:"), style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(4.dp))
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
                        Text(pasteLinkText.take(80) + if (pasteLinkText.length > 80) "..." else "",
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(8.dp))
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(localizedText("是否自动解析并导入？", "Parse and import automatically?"), style = MaterialTheme.typography.bodyMedium)
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
                }) { Text(localizedText("立即导入", "Import now")) }
            },
            dismissButton = { TextButton(onClick = { showPasteDialog = false }) { Text(localizedText("忽略", "Ignore")) } }
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
                        Text(localizeGeneratedName(profile.name), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Text("${profile.type} · ${profile.host}:${profile.port}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (profile.username.isNotBlank()) Text("👤 ${profile.username}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Switch(checked = profile.enabled, onCheckedChange = { onToggleEnable() })
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                IconButton(onClick = onTestSpeed, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.Speed, contentDescription = localizedText("测速", "Speed test"), modifier = Modifier.size(18.dp)) }
                IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.Edit, contentDescription = localizedText("编辑", "Edit"), modifier = Modifier.size(18.dp)) }
                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.Delete, contentDescription = localizedText("删除", "Delete"), tint = Error, modifier = Modifier.size(18.dp)) }
                if (isActive) {
                    Surface(color = Online.copy(alpha = 0.15f), shape = MaterialTheme.shapes.extraSmall) {
                        Text(localizedText("已激活", "Active"), style = MaterialTheme.typography.labelSmall, color = Online, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
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
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(localizedText("代理名称", "Proxy name")) }, placeholder = { Text(localizedText("例如：机场节点1", "Example: proxy node 1")) }, singleLine = true, modifier = Modifier.fillMaxWidth())

                // 代理类型选择器
                ExposedDropdownMenuBox(expanded = typeExpanded, onExpandedChange = { typeExpanded = !typeExpanded }) {
                    OutlinedTextField(value = type, onValueChange = {}, readOnly = true, label = { Text(localizedText("代理类型", "Proxy type")) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = typeExpanded) }, modifier = Modifier.fillMaxWidth().menuAnchor())
                    ExposedDropdownMenu(expanded = typeExpanded, onDismissRequest = { typeExpanded = false }) {
                        typeOptions.forEach { option ->
                            DropdownMenuItem(text = { Text(option) }, onClick = { type = option; typeExpanded = false
                                if (port == "1080" || port == "7890") port = if (option.startsWith("SOCKS")) "1080" else "7890" })
                        }
                    }
                }

                OutlinedTextField(value = host, onValueChange = { host = it }, label = { Text(localizedText("代理服务器地址", "Proxy server address")) }, placeholder = { Text(localizedText("例如：192.168.1.100", "Example: 192.168.1.100")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = port, onValueChange = { port = it.filter { c -> c.isDigit() } }, label = { Text(localizedText("端口", "Port")) },
                    placeholder = { Text(if (type.startsWith("SOCKS")) "1080" else "7890") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())

                HorizontalDivider()

                OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text(localizedText("用户名 (可选)", "Username (optional)")) }, placeholder = { Text(localizedText("SOCKS5/HTTP 认证用户名", "SOCKS5/HTTP authentication username")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text(localizedText("密码 (可选)", "Password (optional)")) }, placeholder = { Text(localizedText("SOCKS5/HTTP 认证密码", "SOCKS5/HTTP authentication password")) }, singleLine = true,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = { IconButton(onClick = { showPassword = !showPassword }) { Icon(if (showPassword) Icons.Default.Visibility else Icons.Default.VisibilityOff, contentDescription = null) } },
                    modifier = Modifier.fillMaxWidth())

                if (type.startsWith("SOCKS") && (username.isNotBlank() || password.isNotBlank())) {
                    Surface(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f), shape = MaterialTheme.shapes.small) {
                        Text(localizedText("✅ SOCKS5 将使用 RFC 1929 用户名/密码认证", "✅ SOCKS5 will use RFC 1929 username/password authentication"), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(8.dp))
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
            }, enabled = host.isNotBlank() && (port.toIntOrNull() ?: 0) in 1..65535) { Text(localizedText("保存", "Save")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(localizedText("取消", "Cancel")) } }
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
            Text(localizedText("ℹ️ 关于我们", "ℹ️ About us"), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("QiTong APP v$appVersion - " + localizedText("AI 网关管理工具", "AI gateway management tool"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(8.dp))

            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(localizedText("📱 应用信息", "📱 App information"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(localizedText("版本: v", "Version: v") + appVersion, style = MaterialTheme.typography.bodyMedium)
                    Text(localizedText("开发者: 綦桐网络", "Developer: QiTong Network"), style = MaterialTheme.typography.bodyMedium)
                    Text(localizedText("协议: OpenAI Compatible API", "Protocol: OpenAI-compatible API"), style = MaterialTheme.typography.bodyMedium)
                }
            }

            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(localizedText("⚙️ 当前配置", "⚙️ Current configuration"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(localizedText("网关端口:", "Gateway port:"), style = MaterialTheme.typography.bodyMedium)
                        Text(gatewayPort.toString(), style = MaterialTheme.typography.bodyMedium)
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(localizedText("代理加速:", "Proxy acceleration:"), style = MaterialTheme.typography.bodyMedium)
                        Text(if (proxyEnabled) localizedText("✅ 已开启", "✅ Enabled") else localizedText("❌ 未开启", "❌ Disabled"), color = if (proxyEnabled) Online else Error)
                    }
                    // 显示激活的代理详情
                    val activeProxy = if (proxyEnabled) proxyProfiles.find { it.id == activeProxyId } else null
                    if (activeProxy != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(localizedText("代理节点:", "Proxy node:"), style = MaterialTheme.typography.bodyMedium)
                            Text("${activeProxy.type} · ${activeProxy.host}:${activeProxy.port}", style = MaterialTheme.typography.bodyMedium, color = Online)
                        }
                        if (activeProxy.username.isNotBlank()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(localizedText("代理用户:", "Proxy user:"), style = MaterialTheme.typography.bodyMedium)
                                Text(activeProxy.username, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                    // 流量统计
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(localizedText("↑ 上传:", "↑ Upload:"), style = MaterialTheme.typography.bodyMedium)
                        Text(formatTraffic(GatewayForegroundService.trafficUploadBytes.get()), style = MaterialTheme.typography.bodyMedium, color = Online)
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(localizedText("↓ 下载:", "↓ Download:"), style = MaterialTheme.typography.bodyMedium)
                        Text(formatTraffic(GatewayForegroundService.trafficDownloadBytes.get()), style = MaterialTheme.typography.bodyMedium, color = Online)
                    }
                }
            }

            // ★★★ 崩溃日志卡片 ★★★
            if (CrashHandler.hasCrashLog()) {
                Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Error.copy(alpha = 0.1f))) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = Error)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(localizedText("💥 检测到崩溃日志", "💥 Crash log detected"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Error)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(localizedText("APP上次运行发生崩溃，已自动保存日志。", "The app crashed last time, log has been saved."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                scope.launch {
                                    val log = CrashHandler.getCrashLog()
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("Crash Log", log))
                                    snackbarHostState.showSnackbar(localizedText("✅ 崩溃日志已复制", "✅ Crash log copied"))
                                }
                            }, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Default.ContentCopy, contentDescription = null, tint = Error)
                                Spacer(Modifier.width(4.dp))
                                Text(localizedText("复制日志", "Copy log"), color = Error)
                            }
                            Button(onClick = {
                                CrashHandler.submitCrashLogToGitHub(title = "崩溃报告 v$appVersion") { success, msg ->
                                    scope.launch { snackbarHostState.showSnackbar(msg) }
                                }
                            }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Error)) {
                                Icon(Icons.Default.BugReport, contentDescription = null)
                                Spacer(Modifier.width(4.dp))
                                Text(localizedText("提交反馈", "Report"), color = MaterialTheme.colorScheme.onError)
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        TextButton(onClick = { CrashHandler.clearCrashLog() }) {
                            Text(localizedText("🗑️ 清除日志", "🗑️ Clear log"), style = MaterialTheme.typography.bodySmall, color = Error.copy(alpha = 0.7f))
                        }
                    }
                }
            }

            // ★★★ 自动更新检查卡片 ★★★
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.SystemUpdate, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(localizedText("🔄 检查更新", "🔄 Check for updates"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    var updateStatus by remember { mutableStateOf("") }
                    var isChecking by remember { mutableStateOf(false) }
                    if (updateStatus.isNotBlank()) {
                        Text(updateStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            isChecking = true
                            updateStatus = localizedText("⏳ 正在检查...", "⏳ Checking...")
                            Thread {
                                try {
                                    val url = java.net.URL("https://api.github.com/repos/qtgf520/qitong-ai-gateway/releases/latest")
                                    val conn = url.openConnection() as java.net.HttpURLConnection
                                    conn.connectTimeout = 5000
                                    conn.readTimeout = 5000
                                    val body = conn.inputStream.bufferedReader().readText()
                                    conn.disconnect()
                                    val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                                    val release = json.parseToJsonElement(body).jsonObject
                                    val latestTag = release["tag_name"]?.jsonPrimitive?.content ?: "unknown"
                                    val latestName = release["name"]?.jsonPrimitive?.content ?: latestTag
                                    val releaseUrl = release["html_url"]?.jsonPrimitive?.content ?: ""
                                    val bodyText = release["body"]?.jsonPrimitive?.content ?: ""
                                    val current = appVersion
                                    val isNewer = latestTag.removePrefix("v") > current.removePrefix("v")
                                    scope.launch {
                                        if (isNewer) {
                                            updateStatus = localizedText("🎉 发现新版本: $latestName\n当前版本: v$current", "🎉 New version: $latestName\nCurrent: v$current")
                                        } else {
                                            updateStatus = localizedText("✅ 已是最新版本: v$current", "✅ Already latest: v$current")
                                        }
                                    }
                                } catch (e: Exception) {
                                    scope.launch {
                                        updateStatus = localizedText("❌ 检查失败: ${e.message}", "❌ Check failed: ${e.message}")
                                    }
                                }
                                isChecking = false
                            }.start()
                        }, modifier = Modifier.weight(1f), enabled = !isChecking) {
                            if (isChecking) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Default.Refresh, contentDescription = null)
                            }
                            Spacer(Modifier.width(4.dp))
                            Text(localizedText("检查更新", "Check update"))
                        }
                        OutlinedButton(onClick = {
                            try {
                                val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/qtgf520/qitong-ai-gateway/releases"))
                                context.startActivity(intent)
                            } catch (_: Exception) {}
                        }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.OpenInNew, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text(localizedText("查看发布页", "View releases"))
                        }
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
