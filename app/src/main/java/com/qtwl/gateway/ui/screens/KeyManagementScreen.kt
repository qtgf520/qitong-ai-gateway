package com.qtwl.gateway.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.qtwl.gateway.service.KeyManager
import com.qtwl.gateway.service.ApiKeyEntry
import com.qtwl.gateway.utils.localizedText
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeyManagementScreen(onDismiss: () -> Unit) {
    var keys by remember { mutableStateOf(KeyManager.getAllKeys()) }
    var showAddDialog by remember { mutableStateOf(false) }
    var editingKey by remember { mutableStateOf<ApiKeyEntry?>(null) }
    var requireApiKey by remember { mutableStateOf(com.qtwl.gateway.service.GatewayForegroundService.getRequireApiKey()) }
    
    fun refreshKeys() { keys = KeyManager.getAllKeys() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(localizedText("🔑 API 密钥管理", "🔑 API Key management")) },
                navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.Default.ArrowBack, contentDescription = null) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primary, titleContentColor = MaterialTheme.colorScheme.onPrimary, navigationIconContentColor = MaterialTheme.colorScheme.onPrimary)
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            // 全局开关
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(localizedText("API 密钥验证", "API key authentication"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Text(localizedText("本地请求（localhost/192.168.*）自动免密钥", "Local requests (localhost/192.168.*) are automatically exempt"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = requireApiKey, onCheckedChange = { e ->
                            requireApiKey = e
                            com.qtwl.gateway.service.GatewayForegroundService.saveRequireApiKey(e)
                        })
                    }
                }
            }
            
            Spacer(Modifier.height(12.dp))
            
            // 添加按钮
            Button(onClick = { showAddDialog = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text(localizedText("添加密钥", "Add key"))
            }
            
            Spacer(Modifier.height(12.dp))
            
            // 密钥列表
            if (keys.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text(localizedText("暂无密钥，点击上方添加", "No keys yet. Tap above to add one"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(keys) { entry ->
                        KeyCard(entry = entry, onEdit = { editingKey = it }, onDelete = {
                            KeyManager.deleteKey(entry.key)
                            refreshKeys()
                        }, onRefresh = { refreshKeys() })
                    }
                }
            }
        }
    }
    
    // 添加弹窗
    if (showAddDialog) {
        AddKeyDialog(onDismiss = { showAddDialog = false }, onSave = { key, label, models, qtai ->
            KeyManager.addKey(key, label, models, qtai)
            refreshKeys()
            showAddDialog = false
        })
    }
    
    // 编辑弹窗
    editingKey?.let { entry ->
        EditKeyDialog(entry = entry, onDismiss = { editingKey = null }, onSave = { label, enabled, models, qtai ->
            KeyManager.updateKey(entry.key, label = label, enabled = enabled, allowedModels = models, qtaiSjAccess = qtai)
            refreshKeys()
            editingKey = null
        })
    }
}

@Composable
private fun KeyCard(entry: ApiKeyEntry, onEdit: (ApiKeyEntry) -> Unit, onDelete: () -> Unit, onRefresh: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = if (entry.enabled) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.VpnKey, contentDescription = null, tint = if (entry.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(entry.key.take(20) + if (entry.key.length > 20) "..." else "", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                    if (entry.label.isNotBlank()) Text(entry.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = entry.enabled, onCheckedChange = { e ->
                    com.qtwl.gateway.service.KeyManager.updateKey(entry.key, enabled = e)
                    onRefresh()
                })
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val timeStr = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(entry.createdAt))
                Text(localizedText("创建于: ", "Created: ") + timeStr, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (entry.allowedModels.isNotEmpty()) {
                    Text(localizedText("限定 ${entry.allowedModels.size} 个模型", "Limited to ${entry.allowedModels.size} models"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
                if (!entry.qtaiSjAccess) {
                    Text(localizedText("qtai-sj 禁止", "qtai-sj denied"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = { onEdit(entry) }) { Text(localizedText("编辑", "Edit"), style = MaterialTheme.typography.labelSmall) }
                TextButton(onClick = onDelete) { Text(localizedText("删除", "Delete"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddKeyDialog(onDismiss: () -> Unit, onSave: (String, String, List<String>, Boolean) -> Unit) {
    var key by remember { mutableStateOf("sk-") }
    var label by remember { mutableStateOf("") }
    var qtaiSj by remember { mutableStateOf(true) }
    var showKey by remember { mutableStateOf(false) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(localizedText("添加密钥", "Add key"), fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = key, onValueChange = { key = it }, label = { Text(localizedText("密钥", "Key")) }, singleLine = true,
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = { IconButton(onClick = { showKey = !showKey }) { Icon(if (showKey) Icons.Default.Visibility else Icons.Default.VisibilityOff, null) } },
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = label, onValueChange = { label = it }, label = { Text(localizedText("备注（可选）", "Label (optional)")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(localizedText("允许 qtai-sj 访问", "Allow qtai-sj access"), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Switch(checked = qtaiSj, onCheckedChange = { qtaiSj = it })
                }
                Text(localizedText("💡 密钥为空列表时默认允许所有模型", "💡 Empty model list = allow all models"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { Button(onClick = { onSave(key, label, emptyList(), qtaiSj) }, enabled = key.isNotBlank()) { Text(localizedText("添加", "Add")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(localizedText("取消", "Cancel")) } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditKeyDialog(entry: ApiKeyEntry, onDismiss: () -> Unit, onSave: (String, Boolean, List<String>, Boolean) -> Unit) {
    var label by remember { mutableStateOf(entry.label) }
    var enabled by remember { mutableStateOf(entry.enabled) }
    var qtaiSj by remember { mutableStateOf(entry.qtaiSjAccess) }
    var modelsText by remember { mutableStateOf(entry.allowedModels.joinToString(", ")) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(localizedText("编辑密钥", "Edit key"), fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(entry.key, style = MaterialTheme.typography.bodySmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(value = label, onValueChange = { label = it }, label = { Text(localizedText("备注", "Label")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(localizedText("启用", "Enabled"), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(localizedText("允许 qtai-sj", "Allow qtai-sj"), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Switch(checked = qtaiSj, onCheckedChange = { qtaiSj = it })
                }
                OutlinedTextField(value = modelsText, onValueChange = { modelsText = it }, label = { Text(localizedText("限定模型ID（逗号分隔，空=全部）", "Allowed model IDs (comma-separated, empty=all)")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { Button(onClick = { onSave(label, enabled, modelsText.split(",").map { it.trim() }.filter { it.isNotBlank() }, qtaiSj) }) { Text(localizedText("保存", "Save")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(localizedText("取消", "Cancel")) } }
    )
}