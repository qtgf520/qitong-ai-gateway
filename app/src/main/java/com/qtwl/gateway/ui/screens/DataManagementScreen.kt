package com.qtwl.gateway.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.Manifest
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

    // ★ 文件存储权限请求器（Android 11+ 专用目录写入需要）
    val storagePermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        scope.launch {
            if (granted) {
                snackbarHostState.showSnackbar("✅ 文件存储权限已授予")
            } else {
                snackbarHostState.showSnackbar("⚠️ 权限被拒，备份将使用 MediaStore 保存到 Downloads")
            }
        }
    }

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
    var showDebugLogs by remember { mutableStateOf(false) }

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

                    // ★ 按钮行：立即备份 | 恢复备份（自动扫 Downloads + 专用目录）
                    val appBackupDir = File(Environment.getExternalStorageDirectory(), "QiTongGateway/backups")
                    var showBackupList by remember { mutableStateOf(false) }
                    // ★ 检查/请求文件存储权限
                    val hasStoragePerm = if (Build.VERSION.SDK_INT >= 30) {
                        // Android 11+ 用 MediaStore 不需要额外权限，但写专用目录需要
                        Environment.getExternalStorageDirectory().canWrite()
                    } else {
                        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            // ★ 检查权限，没有则请求
                            if (Build.VERSION.SDK_INT < 30 && !hasStoragePerm) {
                                storagePermLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                                return@Button
                            }
                            scope.launch {
                                try {
                                    val result = withContext(Dispatchers.IO) { viewModel.getBackupJson() }
                                    result.onSuccess { json ->
                                        val timeStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                                        val fileName = "qitong_gateway_backup_$timeStr.json"

                                        // ★ 存到 Downloads
                                        if (Build.VERSION.SDK_INT >= 29) {
                                            val values = ContentValues().apply {
                                                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                                                put(MediaStore.Downloads.MIME_TYPE, "application/json")
                                                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                                            }
                                            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                                            uri?.let {
                                                context.contentResolver.openOutputStream(it)?.use { os -> os.write(json.toByteArray()) }
                                            }
                                        } else {
                                            val file = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), fileName)
                                            file.writeText(json)
                                        }

                                        // ★ 再存到专用目录（Android 10+ 用 MediaStore 方式，避免 EPERM）
                                        withContext(Dispatchers.IO) {
                                            try {
                                                if (Build.VERSION.SDK_INT >= 29) {
                                                    // Android 10+ 用 MediaStore 存到 Downloads 子目录
                                                    val values2 = ContentValues().apply {
                                                        put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                                                        put(MediaStore.Downloads.MIME_TYPE, "application/json")
                                                        put(MediaStore.Downloads.RELATIVE_PATH, "QiTongGateway/backups")
                                                    }
                                                    val uri2 = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values2)
                                                    uri2?.let {
                                                        context.contentResolver.openOutputStream(it)?.use { os -> os.write(json.toByteArray()) }
                                                    }
                                                } else {
                                                    appBackupDir.mkdirs()
                                                    File(appBackupDir, fileName).writeText(json)
                                                }
                                            } catch (e: Exception) {
                                                // 专用目录写入失败不影响主备份，仅提示
                                                android.util.Log.w("Backup", "专用目录备份失败: ${e.message}")
                                            }
                                        }

                                        snackbarHostState.showSnackbar("✅ 备份完成: $fileName")
                                    }.onFailure { e -> snackbarHostState.showSnackbar("❌ 备份失败: ${e.message}") }
                                } catch (e: Exception) {
                                    snackbarHostState.showSnackbar("❌ 备份失败: ${e.message}")
                                }
                            }
                        }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Save, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("立即备份")
                        }
                        OutlinedButton(onClick = {
                            showBackupList = true
                        }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Restore, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("恢复备份")
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    // ★ 第二行：手动导入（调文件选择器）
                    OutlinedButton(onClick = {
                        filePickerLauncher.launch("application/json")
                    }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.NoteAdd, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("📂 手动导入（从文件选择器选择备份 JSON）")
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    if (autoBackupEnabled.value) {
                        Text("⏱️ 下次自动备份: ${String.format("%02d", autoBackupHour.value)}:${String.format("%02d", autoBackupMinute.value)}",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    } else {
                        Text("💡 备份到 Downloads + QiTongGateway/backups/ 专用目录",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    // 扫描并列出备份文件弹窗（扫两个目录）
                    if (showBackupList) {
                        var backupFiles by remember { mutableStateOf<List<File>>(emptyList()) }
                        var isLoading by remember { mutableStateOf(true) }
                        LaunchedEffect(showBackupList) {
                            withContext(Dispatchers.IO) {
                                val files = mutableListOf<File>()
                                // 扫 Downloads
                                val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                                downloadDir.listFiles()?.filter {
                                    it.name.startsWith("qitong_gateway_backup") && it.name.endsWith(".json")
                                }?.let { files.addAll(it) }
                                // 扫专用目录
                                if (appBackupDir.exists()) {
                                    appBackupDir.listFiles()?.filter {
                                        it.name.startsWith("qitong_gateway_backup") && it.name.endsWith(".json")
                                    }?.let { files.addAll(it) }
                                }
                                // 去重排序
                                backupFiles = files.distinctBy { it.name }.sortedByDescending { it.lastModified() }
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
                        Text("🧠 大脑记忆", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Switch(checked = memEnabled, onCheckedChange = { e ->
                            memEnabled = e
                            BrainMemoryManager.updateConfig(cfg.copy(enabled = e))
                        })
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("qtai-sj 大脑：${memList.size}条记忆 · 模式:${memMode} · 情感感知:${if (cfg.emotionalAwareness) "开" else "关"}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    
                    if (memEnabled) {
                        Spacer(modifier = Modifier.height(8.dp))
                        // 保存模式
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("保存模式:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(8.dp))
                            val modes = listOf("frequent" to "频繁", "normal" to "正常", "occasional" to "偶尔")
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
                            Text("上限:", style = MaterialTheme.typography.labelSmall)
                            Spacer(Modifier.width(4.dp))
                            OutlinedTextField(value = memMax, onValueChange = { v ->
                                memMax = v; v.toIntOrNull()?.let { n ->
                                    BrainMemoryManager.updateConfig(cfg.copy(maxShortTerm = n))
                                }
                            }, singleLine = true, modifier = Modifier.width(80.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(onClick = { BrainMemoryManager.clearAll(); memList = emptyList(); scope.launch { snackbarHostState.showSnackbar("🧹 所有记忆已清空") } },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Error)) {
                                Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(2.dp)); Text("清空", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        // 展开/折叠
                        TextButton(onClick = { showMemDetail = !showMemDetail }) {
                            Text(text = if (showMemDetail) "▲ 收起记忆列表" else "▼ 展开记忆列表 (${memList.size}条)", style = MaterialTheme.typography.labelMedium)
                        }
                        if (showMemDetail) {
                            // 搜索框
                            OutlinedTextField(value = memSearchQuery, onValueChange = { memSearchQuery = it },
                                placeholder = { Text("搜索记忆...", style = MaterialTheme.typography.bodySmall) },
                                singleLine = true, modifier = Modifier.fillMaxWidth(), leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp)) })
                            Spacer(modifier = Modifier.height(4.dp))
                            // 记忆列表
                            val filtered = if (memSearchQuery.isBlank()) memList else BrainMemoryManager.search(memSearchQuery)
                            if (filtered.isEmpty()) {
                                Text("暂无记忆", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
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
                                                    Text("重要:${mem.importance}/10 · ${mem.type} · ${java.text.SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(java.util.Date(mem.timestamp))}",
                                                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                }
                                                IconButton(onClick = { BrainMemoryManager.deleteMemory(mem.id); memList = BrainMemoryManager.getAll() }, modifier = Modifier.size(28.dp)) {
                                                    Icon(Icons.Default.Delete, contentDescription = "删除", tint = Error, modifier = Modifier.size(16.dp))
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
                    title = { Text("✏️ 编辑记忆", fontWeight = FontWeight.Bold) },
                    text = {
                        Column {
                            OutlinedTextField(value = editTitle, onValueChange = { editTitle = it }, label = { Text("标题") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(value = editContent, onValueChange = { editContent = it }, label = { Text("内容") }, modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp), maxLines = 5)
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("情感:", style = MaterialTheme.typography.labelSmall)
                                Spacer(Modifier.width(8.dp))
                                listOf("neutral" to "😐", "happy" to "😊", "sad" to "😢", "angry" to "😠", "surprised" to "😮").forEach { (k, v) ->
                                    FilterChip(selected = editEmotion == k, onClick = { editEmotion = k },
                                        label = { Text(v, style = MaterialTheme.typography.labelSmall) }, modifier = Modifier.padding(end = 2.dp))
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("重要性 (0-10):", style = MaterialTheme.typography.labelSmall)
                                Spacer(Modifier.width(8.dp))
                                OutlinedTextField(value = editImportance, onValueChange = { editImportance = it }, singleLine = true, modifier = Modifier.width(80.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                            }
                        }
                    },
                    confirmButton = {
                        Button(onClick = {
                            BrainMemoryManager.updateMemory(editingMem!!.id, title = editTitle, content = editContent, emotion = editEmotion, importance = editImportance.toIntOrNull())
                            editingMem = null; memList = BrainMemoryManager.getAll()
                            scope.launch { snackbarHostState.showSnackbar("✅ 记忆已更新") }
                        }) { Text("保存") }
                    },
                    dismissButton = { TextButton(onClick = { editingMem = null }) { Text("取消") } }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ★★ 人格设置卡片（綦小桐）★★
            val pCfg = BrainMemoryManager.getConfig()
            var personaEnabled by remember { mutableStateOf(pCfg.personaEnabled) }
            var personaName by remember { mutableStateOf(pCfg.personaName) }
            var personaAge by remember { mutableStateOf(pCfg.personaAge.toString()) }
            var personaTraits by remember { mutableStateOf(pCfg.personaTraits) }
            var personaStyle by remember { mutableStateOf(pCfg.personaStyle) }
            var personaBg by remember { mutableStateOf(pCfg.personaBackground) }
            var envAware by remember { mutableStateOf(pCfg.envAwareness) }
            
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Face, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("🧑 人格设定", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("定制 qtai-sj 的个性化形象", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("启用人格系统", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Switch(checked = personaEnabled, onCheckedChange = { e ->
                            personaEnabled = e
                            BrainMemoryManager.updateConfig(pCfg.copy(personaEnabled = e))
                        })
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = personaName, onValueChange = { v ->
                            personaName = v
                            BrainMemoryManager.updateConfig(pCfg.copy(personaName = v))
                            GatewayForegroundService.saveQtaiSjName(v)
                        }, label = { Text("名字") }, singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedTextField(value = personaAge, onValueChange = { v ->
                            personaAge = v
                            v.toIntOrNull()?.let { BrainMemoryManager.updateConfig(pCfg.copy(personaAge = it)) }
                        }, label = { Text("年龄") }, singleLine = true, modifier = Modifier.width(80.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    
                    OutlinedTextField(value = personaTraits, onValueChange = { v ->
                        personaTraits = v
                        BrainMemoryManager.updateConfig(pCfg.copy(personaTraits = v))
                    }, label = { Text("性格特征（逗号分隔）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(4.dp))
                    
                    val styleOptions = listOf("亲切自然", "专业严谨", "活泼可爱")
                    var styleExpanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(expanded = styleExpanded, onExpandedChange = { styleExpanded = it }) {
                        OutlinedTextField(value = personaStyle, onValueChange = {}, readOnly = true,
                            label = { Text("语气风格") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = styleExpanded) },
                            modifier = Modifier.fillMaxWidth().menuAnchor())
                        ExposedDropdownMenu(expanded = styleExpanded, onDismissRequest = { styleExpanded = false }) {
                            styleOptions.forEach { opt ->
                                DropdownMenuItem(text = { Text(opt) }, onClick = {
                                    personaStyle = opt
                                    BrainMemoryManager.updateConfig(pCfg.copy(personaStyle = opt))
                                    styleExpanded = false
                                })
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    
                    OutlinedTextField(value = personaBg, onValueChange = { v ->
                        personaBg = v
                        BrainMemoryManager.updateConfig(pCfg.copy(personaBackground = v))
                    }, label = { Text("背景设定") }, modifier = Modifier.fillMaxWidth(),
                    minLines = 2, maxLines = 3)
                    Spacer(modifier = Modifier.height(4.dp))
                    
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("环境感知（时间/网络）", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Switch(checked = envAware, onCheckedChange = { e ->
                            envAware = e
                            BrainMemoryManager.updateConfig(pCfg.copy(envAwareness = e))
                        })
                    }
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
                                if (autoDetect) "当前: ${currentLang.displayName}" 
                                else "手动: ${currentLang.displayName}",
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
                    confirmButton = { TextButton(onClick = { showLangSelector = false }) { Text("关闭") } }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ★ Debug 抓包模式
            val debugMode by viewModel.debugMode.collectAsState()
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

            // ★★ 抓包日志页面（全屏覆盖）★★

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(8.dp))

            // ★★ 思考引导配置 ★★
            val thinkingEnabled = remember { mutableStateOf(ThinkingConfigManager.isEnabled()) }
            val thinkingDepth = remember { mutableStateOf(ThinkingConfigManager.getDepth().label) }
            val depthOptions = listOf("关闭", "轻度", "深度")
            var depthExpanded by remember { mutableStateOf(false) }
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Psychology, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("🧠 思考引导", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Switch(checked = thinkingEnabled.value, onCheckedChange = { e ->
                            thinkingEnabled.value = e
                            ThinkingConfigManager.setEnabled(e)
                        })
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("在 qtai-sj 回复前注入思考引导，让 AI 先分析再回答",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (thinkingEnabled.value) {
                        Spacer(modifier = Modifier.height(8.dp))
                        ExposedDropdownMenuBox(expanded = depthExpanded, onExpandedChange = { depthExpanded = it }) {
                            OutlinedTextField(value = thinkingDepth.value, onValueChange = {}, readOnly = true,
                                label = { Text("思考深度") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = depthExpanded) },
                                modifier = Modifier.fillMaxWidth().menuAnchor())
                            ExposedDropdownMenu(expanded = depthExpanded, onDismissRequest = { depthExpanded = false }) {
                                depthOptions.forEach { opt ->
                                    DropdownMenuItem(text = { Text(opt) }, onClick = {
                                        thinkingDepth.value = opt
                                        when (opt) {
                                            "关闭" -> { ThinkingConfigManager.setEnabled(false); thinkingEnabled.value = false; ThinkingConfigManager.setDepth(ThinkingConfigManager.ThinkingDepth.OFF) }
                                            "轻度" -> ThinkingConfigManager.setDepth(ThinkingConfigManager.ThinkingDepth.LIGHT)
                                            "深度" -> ThinkingConfigManager.setDepth(ThinkingConfigManager.ThinkingDepth.DEEP)
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
                        Text("💬 群聊模式", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Switch(checked = groupChatEnabled.value, onCheckedChange = { e ->
                            groupChatEnabled.value = e
                            GroupChatManager.setEnabled(e)
                        })
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("虚拟沙箱：用户发消息 → AI依次发言 → 总结者输出",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (groupChatEnabled.value) {
                        Spacer(modifier = Modifier.height(8.dp))
                        // ★ 参与模型（从排行榜勾选）★★
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("参与模型: ", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(GroupChatManager.getParticipantModels().joinToString(", ").take(30) + if (GroupChatManager.getParticipantModels().joinToString(", ").length > 30) "..." else "",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Button(onClick = { showGroupChatModelPicker = true }, modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)) {
                            Icon(Icons.Default.List, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("📊 从测速排行榜选择模型 (${GroupChatManager.getParticipantModels().size}个)")
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        // ★ 总结模型（从排行榜勾选）★★
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("总结模型: ", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(GroupChatManager.getSummarizerModel().ifBlank { "未设置" },
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Button(onClick = { showGroupChatSummarizerPicker = true }, modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)) {
                            Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("🎯 选择总结模型")
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        OutlinedTextField(value = groupChatMaxRounds.value, onValueChange = { v ->
                            groupChatMaxRounds.value = v
                            v.toIntOrNull()?.let { GroupChatManager.setMaxRounds(it) }
                        }, label = { Text("轮次") }, singleLine = true, modifier = Modifier.width(100.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    }
                }
            }
            // ★ 参与模型选择弹窗（排行榜列表勾选）★★
            if (showGroupChatModelPicker) {
                val allModels = viewModel.enabledModels.collectAsState().value
                val sortedIds = com.qtwl.gateway.gateway.GatewayScheduler.pipelineSortedModelIds
                // 按排行榜排序，未在排行榜的放后面
                val sortedModels = allModels.sortedByDescending { sortedIds.indexOf(it.modelId) }.reversed()
                val currentParticipants = remember { mutableStateListOf<String>().apply { addAll(GroupChatManager.getParticipantModels()) } }
                AlertDialog(
                    onDismissRequest = { showGroupChatModelPicker = false },
                    title = { Text("选择参与模型", fontWeight = FontWeight.Bold) },
                    text = {
                        if (sortedModels.isEmpty()) {
                            Text("暂无可用模型，请先添加服务商和启用模型")
                        } else {
                            LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                                items(sortedModels) { model ->
                                    val isSelected = model.modelId in currentParticipants
                                    val rank = sortedIds.indexOf(model.modelId)
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
                        }) { Text("确定 (${currentParticipants.size}个)") }
                    },
                    dismissButton = { TextButton(onClick = { showGroupChatModelPicker = false }) { Text("取消") } }
                )
            }
            // ★ 总结模型选择弹窗 ★★
            if (showGroupChatSummarizerPicker) {
                val allModels = viewModel.enabledModels.collectAsState().value
                val sortedIds = com.qtwl.gateway.gateway.GatewayScheduler.pipelineSortedModelIds
                val sortedModels = allModels.sortedByDescending { sortedIds.indexOf(it.modelId) }.reversed()
                var selectedSummarizer by remember { mutableStateOf(GroupChatManager.getSummarizerModel()) }
                AlertDialog(
                    onDismissRequest = { showGroupChatSummarizerPicker = false },
                    title = { Text("选择总结模型", fontWeight = FontWeight.Bold) },
                    text = {
                        if (sortedModels.isEmpty()) {
                            Text("暂无可用模型")
                        } else {
                            LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                                // 加一个"无总结者"选项
                                item {
                                    Row(modifier = Modifier.fillMaxWidth().clickable { selectedSummarizer = "" }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                        RadioButton(selected = selectedSummarizer == "", onClick = { selectedSummarizer = "" })
                                        Spacer(Modifier.width(4.dp))
                                        Text("无总结者", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                                item { HorizontalDivider() }
                                items(sortedModels) { model ->
                                    val rank = sortedIds.indexOf(model.modelId)
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
                        }) { Text("确定") }
                    },
                    dismissButton = { TextButton(onClick = { showGroupChatSummarizerPicker = false }) { Text("取消") } }
                )
            }
            Spacer(modifier = Modifier.height(16.dp))

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
                        Text("🔍 网关抓包日志", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        TextButton(onClick = { showDebugLogs = false }) { Text("✕ 关闭") }
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
                            placeholder = { Text("🔍 搜索") }, singleLine = true, modifier = Modifier.weight(2f),
                            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp))
                        TextButton(onClick = { statusFilter = null }) { Text(if (statusFilter == null) "全部" else "全部") }
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
                                    title = { Text("📦 抓包详情 #${record.id}") },
                                    text = {
                                        LazyColumn(modifier = Modifier.heightIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            record.inbound?.let { inbound -> item {
                                                Text("📥 入站", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                                Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), shape = MaterialTheme.shapes.small) {
                                                    Text(buildString {
                                                        appendLine("方法: ${inbound.method} ${inbound.path}")
                                                        appendLine("头部: ${inbound.headers}")
                                                        appendLine("--- 请求体 (${inbound.bodySize}B) ---")
                                                        appendLine(inbound.body)
                                                    }, fontFamily = FontFamily.Monospace, fontSize = 10.sp, modifier = Modifier.padding(8.dp))
                                                }
                                            }}
                                            record.outbound?.let { outbound -> item {
                                                Text("📤 出站", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
                                                Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), shape = MaterialTheme.shapes.small) {
                                                    Text(buildString {
                                                        appendLine("URL: ${outbound.targetUrl}")
                                                        appendLine("模型: ${outbound.modelId}")
                                                        appendLine("--- 请求体 (${outbound.bodySize}B) ---")
                                                        appendLine(outbound.body)
                                                    }, fontFamily = FontFamily.Monospace, fontSize = 10.sp, modifier = Modifier.padding(8.dp))
                                                }
                                            }}
                                            record.response?.let { resp -> item {
                                                Text("📥 响应", fontWeight = FontWeight.Bold, color = if (resp.httpStatus >= 500) MaterialTheme.colorScheme.error else if (resp.httpStatus >= 400) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary)
                                                Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), shape = MaterialTheme.shapes.small) {
                                                    Text(buildString {
                                                        appendLine("状态: ${resp.httpStatus} | ${resp.elapsedMs}ms")
                                                        appendLine("Tokens: ↑${resp.promptTokens} ↓${resp.completionTokens}")
                                                        appendLine("--- 响应体 (${resp.bodySize}B) ---")
                                                        appendLine(resp.body)
                                                    }, fontFamily = FontFamily.Monospace, fontSize = 10.sp, modifier = Modifier.padding(8.dp))
                                                }
                                            }}
                                            record.failover?.let { failover -> item {
                                                Text("🔄 故障转移", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
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
                                                appendLine("📦 抓包详情 #${record.id}")
                                                appendLine("时间: ${timeFmt.format(record.timestamp)}")
                                                record.inbound?.let { inbound ->
                                                    appendLine("\n📥 入站")
                                                    appendLine("方法: ${inbound.method} ${inbound.path}")
                                                    appendLine("头部: ${inbound.headers}")
                                                    appendLine("--- 请求体 (${inbound.bodySize}B) ---")
                                                    appendLine(inbound.body)
                                                }
                                                record.outbound?.let { outbound ->
                                                    appendLine("\n📤 出站")
                                                    appendLine("URL: ${outbound.targetUrl}")
                                                    appendLine("模型: ${outbound.modelId}")
                                                    appendLine("--- 请求体 (${outbound.bodySize}B) ---")
                                                    appendLine(outbound.body)
                                                }
                                                record.response?.let { resp ->
                                                    appendLine("\n📥 响应")
                                                    appendLine("状态: ${resp.httpStatus} | ${resp.elapsedMs}ms")
                                                    appendLine("Tokens: ↑${resp.promptTokens} ↓${resp.completionTokens}")
                                                    appendLine("--- 响应体 (${resp.bodySize}B) ---")
                                                    appendLine(resp.body)
                                                }
                                                record.failover?.let { failover ->
                                                    appendLine("\n🔄 故障转移")
                                                    failover.attempts.forEach { attempt ->
                                                        appendLine("[${attempt.index}] ${attempt.modelId}: ${attempt.error} (${attempt.elapsedMs}ms)")
                                                    }
                                                }
                                            }
                                            val clipboard = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("抓包详情", detailText))
                                        }) { Text("📋 复制") }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        } // end showDebugLogs
    } // end Box
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
                        Text(formatTraffic(GatewayForegroundService.trafficUploadBytes.get()), style = MaterialTheme.typography.bodyMedium, color = Online)
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("↓ 下载:", style = MaterialTheme.typography.bodyMedium)
                        Text(formatTraffic(GatewayForegroundService.trafficDownloadBytes.get()), style = MaterialTheme.typography.bodyMedium, color = Online)
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
