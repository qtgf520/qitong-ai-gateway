package com.qtwl.gateway.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.qtwl.gateway.ui.viewmodel.GatewayViewModel
import com.qtwl.gateway.utils.localizedText
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * 多实例管理屏幕
 * 对应竞品的多实例管理功能
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstancesScreen(viewModel: GatewayViewModel = viewModel(factory = GatewayViewModel.Factory())) {
    val instances by viewModel.instances.collectAsState()
    val currentInstance by viewModel.currentInstance.collectAsState()
    val modelMap by viewModel.modelMap.collectAsState()
    val logServer by viewModel.logServer.collectAsState()
    
    var showAddDialog by remember { mutableStateOf(false) }
            var newInstanceName by remember { mutableStateOf("") }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(localizedText("实例管理", "Instance Management")) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary
                ),
                actions = {
                    IconButton(onClick = { viewModel.refreshInstances() }) {
                        Icon(Icons.Default.Sync, contentDescription = null)
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = null)
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.Top
        ) {
            // 实例列表
            Text(
                text = localizedText("已配置的实例", "Configured Instances"),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            
            if (instances.isEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = localizedText("暂无实例，点击下方+号添加", "No instances yet. Tap + to add"),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(instances, key = { it }) { instanceName ->
                        val isCurrent = instanceName == currentInstance
                        
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { if (!isCurrent) viewModel.switchInstance(instanceName) },
                            colors = CardDefaults.cardColors(
                                containerColor = if (isCurrent) 
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                else 
                                    MaterialTheme.colorScheme.surface
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        if (isCurrent) {
                                            Icon(
                                                Icons.Default.Home,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(20.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                        }
                                        Text(
                                            text = instanceName,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal
                                        )
                                    }
                                    if (isCurrent) {
                                        Text(
                                            text = localizedText("（当前实例）", " (current)"),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                                
                                if (!isCurrent) {
                                    IconButton(
                                        onClick = { viewModel.removeInstance(instanceName) },
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // 当前实例配置
            if (currentInstance.isNotBlank()) {
                Text(
                    text = localizedText("当前实例配置", "Current Instance Config"),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // modelMap 配置
                        OutlinedTextField(
                            value = modelMap,
                            onValueChange = { viewModel.saveModelMap(it) },
                            label = { Text(localizedText("modelMap 别名映射 (JSON)", "modelMap alias mapping (JSON)")) },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 3,
                            placeholder = {
                                Text("{\n  \"gpt-4\": \"deepseek-chat\",\n  \"gpt-4o\": \"deepseek-coder\"\n}")
                            }
                        )
                        
                        // 远程日志地址
                        OutlinedTextField(
                            value = logServer,
                            onValueChange = { viewModel.saveLogServer(it) },
                            label = { Text(localizedText("远程日志上报地址 (可选)", "Remote log server (optional)")) },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("http://your-log-server.com/log") }
                        )
                    }
                }
            }
        }
    }
    
    // 添加实例对话框
    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text(localizedText("添加新实例", "Add New Instance")) },
            text = {
                OutlinedTextField(
                    value = newInstanceName,
                    onValueChange = { newInstanceName = it },
                    label = { Text(localizedText("实例名称", "Instance Name")) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newInstanceName.isNotBlank()) {
                            viewModel.addInstance(newInstanceName.trim())
                            newInstanceName = ""
                            showAddDialog = false
                        }
                    }
                ) {
                    Text(localizedText("添加", "Add"))
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text(localizedText("取消", "Cancel"))
                }
            }
        )
    }
}