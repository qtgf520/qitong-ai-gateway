package com.qtwl.gateway.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.qtwl.gateway.utils.CustomSkillManager
import com.qtwl.gateway.utils.SkillRegistry
import com.qtwl.gateway.utils.localizedText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 技能管理全屏页面
 * 内置技能只读，自定义技能可添加/编辑/删除/导入
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillManagementScreen(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    
    var builtinSkills by remember { mutableStateOf(SkillRegistry.allSkills) }
    var customSkills by remember { mutableStateOf(CustomSkillManager.getAll()) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var editingSkill by remember { mutableStateOf<CustomSkillManager.CustomSkill?>(null) }
    var showDetail by remember { mutableStateOf<CustomSkillManager.CustomSkill?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 顶部栏（不用Scaffold避免嵌套崩溃）
            Surface(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface, shadowElevation = 4.dp) {
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = localizedText("关闭", "Close"))
                    }
                    Text(localizedText("📋 技能管理", "📋 Skill Management"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
            }
            
            Column(
                modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
            // 内置技能
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Build, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text(localizedText("🔧 内置技能 (${builtinSkills.size})", "🔧 Built-in skills (${builtinSkills.size})"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(localizedText("内置技能不可删除和编辑，仅可查看详情", "Built-in skills cannot be deleted or edited"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    var showBuiltin by remember { mutableStateOf(false) }
                    if (!showBuiltin) {
                        TextButton(onClick = { showBuiltin = true }) {
                            Text(localizedText("展开查看全部", "Expand to view all"))
                        }
                    }
                    if (showBuiltin) {
                        builtinSkills.sortedBy { it.code }.forEach { skill ->
                            Card(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("${skill.code} ${skill.name}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                                        Text(skill.description, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                        TextButton(onClick = { showBuiltin = false }) {
                            Text(localizedText("收起", "Collapse"))
                        }
                    }
                }
            }

            // 自定义技能
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Extension, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(localizedText("🧩 自定义技能 (${customSkills.size})", "🧩 Custom skills (${customSkills.size})"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    // 操作按钮
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            editingSkill = null
                            showAddDialog = true
                        }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text(localizedText("手动添加", "Add manually"))
                        }
                        OutlinedButton(onClick = { showImportDialog = true }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.CloudDownload, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text(localizedText("Git导入", "Git import"))
                        }
                    }
                    Spacer(Modifier.height(8.dp))

                    if (customSkills.isEmpty()) {
                        Text(localizedText("暂无自定义技能，点击上方添加", "No custom skills yet. Tap to add one."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        customSkills.forEach { skill ->
                            Card(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                            ) {
                                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(skill.name, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                                        Text(skill.description, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                                        Text(localizedText("关键词: ", "Keywords: ") + skill.keywords, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f))
                                        Text(localizedText("来源: ", "Source: ") + skill.source, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                                    }
                                    IconButton(onClick = { showDetail = skill }) {
                                        Icon(Icons.Default.Info, contentDescription = localizedText("详情", "Details"), modifier = Modifier.size(20.dp))
                                    }
                                    IconButton(onClick = {
                                        editingSkill = skill
                                        showAddDialog = true
                                    }) {
                                        Icon(Icons.Default.Edit, contentDescription = localizedText("编辑", "Edit"), modifier = Modifier.size(20.dp))
                                    }
                                    IconButton(onClick = {
                                        CustomSkillManager.delete(skill.id)
                                        customSkills = CustomSkillManager.getAll()
                                        scope.launch { snackbarHostState.showSnackbar(localizedText("✅ 已删除: ", "✅ Deleted: ") + skill.name) }
                                    }) {
                                        Icon(Icons.Default.Delete, contentDescription = localizedText("删除", "Delete"), tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
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

    // 添加/编辑弹窗
    if (showAddDialog) {
        AddEditSkillDialog(
            editingSkill = editingSkill,
            onDismiss = { showAddDialog = false },
            onSave = {
                showAddDialog = false
                customSkills = CustomSkillManager.getAll()
                scope.launch { snackbarHostState.showSnackbar(localizedText("✅ 技能已保存", "✅ Skill saved")) }
            }
        )
    }

    // 导入弹窗
    if (showImportDialog) {
        ImportSkillDialog(
            onDismiss = { showImportDialog = false },
            onImported = { count ->
                showImportDialog = false
                customSkills = CustomSkillManager.getAll()
                scope.launch { snackbarHostState.showSnackbar(localizedText("✅ 导入了 $count 个技能", "✅ Imported $count skills")) }
            }
        )
    }

    // 详情弹窗
    if (showDetail != null) {
        AlertDialog(
            onDismissRequest = { showDetail = null },
            title = { Text(showDetail!!.name, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(localizedText("描述: ", "Description: ") + showDetail!!.description, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(localizedText("关键词: ", "Keywords: ") + showDetail!!.keywords, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    Text(localizedText("执行提示词:", "Execution prompt:"), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))) {
                        Text(showDetail!!.prompt, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(localizedText("来源: ", "Source: ") + showDetail!!.source, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = { TextButton(onClick = { showDetail = null }) { Text(localizedText("关闭", "Close")) } }
        )
    }
}

@Composable
private fun AddEditSkillDialog(
    editingSkill: CustomSkillManager.CustomSkill?,
    onDismiss: () -> Unit,
    onSave: () -> Unit
) {
    val isEdit = editingSkill != null
    var name by remember { mutableStateOf(editingSkill?.name ?: "") }
    var description by remember { mutableStateOf(editingSkill?.description ?: "") }
    var keywords by remember { mutableStateOf(editingSkill?.keywords ?: "") }
    var prompt by remember { mutableStateOf(editingSkill?.prompt ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isEdit) localizedText("编辑技能", "Edit skill") else localizedText("添加技能", "Add skill"), fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(localizedText("技能名称 *", "Skill name *")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text(localizedText("技能描述", "Description")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = keywords, onValueChange = { keywords = it }, label = { Text(localizedText("触发关键词（逗号分隔）", "Keywords (comma separated)")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = prompt, onValueChange = { prompt = it }, label = { Text(localizedText("执行提示词", "Execution prompt")) }, modifier = Modifier.fillMaxWidth().height(120.dp), maxLines = 5)
            }
        },
        confirmButton = {
            Button(onClick = {
                if (name.isBlank()) return@Button
                if (isEdit) {
                    CustomSkillManager.update(editingSkill!!.copy(name = name, description = description, keywords = keywords, prompt = prompt))
                } else {
                    CustomSkillManager.add(CustomSkillManager.CustomSkill(name = name, description = description, keywords = keywords, prompt = prompt))
                }
                onSave()
            }, enabled = name.isNotBlank()) {
                Text(localizedText("保存", "Save"))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(localizedText("取消", "Cancel")) } }
    )
}

@Composable
private fun ImportSkillDialog(
    onDismiss: () -> Unit,
    onImported: (Int) -> Unit
) {
    var gitUrl by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(localizedText("📥 从Git导入技能", "📥 Import skills from Git"), fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(localizedText("输入raw.githubusercontent.com的raw链接", "Enter raw.githubusercontent.com URL"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = gitUrl,
                    onValueChange = { gitUrl = it; errorMsg = "" },
                    label = { Text(localizedText("Git Raw URL", "Git Raw URL")) },
                    placeholder = { Text("https://raw.githubusercontent.com/.../skills.json") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (errorMsg.isNotBlank()) {
                    Text(errorMsg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                if (isLoading) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                if (gitUrl.isBlank()) return@Button
                isLoading = true
                errorMsg = ""
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
                    val result = CustomSkillManager.importFromGit(gitUrl)
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        isLoading = false
                        result.onSuccess { onImported(it.size) }.onFailure { errorMsg = it.message ?: "导入失败" }
                    }
                }
            }, enabled = gitUrl.isNotBlank() && !isLoading) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text(localizedText("导入", "Import"))
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(localizedText("取消", "Cancel")) } }
    )
}