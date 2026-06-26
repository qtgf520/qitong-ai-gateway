package com.qtwl.gateway.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.qtwl.gateway.data.model.AiModel
import com.qtwl.gateway.data.model.Provider
import com.qtwl.gateway.ui.theme.Error
import com.qtwl.gateway.ui.theme.Offline
import com.qtwl.gateway.ui.theme.Online
import com.qtwl.gateway.ui.theme.Warning
import com.qtwl.gateway.ui.viewmodel.GatewayViewModel

/**
 * 主屏幕 —— 带底部导航的容器
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: GatewayViewModel = viewModel(factory = GatewayViewModel.Factory())
) {
    var selectedTab by remember { mutableStateOf(0) }
    val snackbarMessage by viewModel.snackbarMessage.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // 处理 Snackbar 显示
    LaunchedEffect(snackbarMessage) {
        snackbarMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearSnackbar()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("綦桐AI网关") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Text("🏠") },
                    label = { Text("首页") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Text("🔌") },
                    label = { Text("服务商") }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Text("🤖") },
                    label = { Text("模型") }
                )
                NavigationBarItem(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    icon = { Text("💬") },
                    label = { Text("聊天") }
                )
                NavigationBarItem(
                    selected = selectedTab == 4,
                    onClick = { selectedTab = 4 },
                    icon = { Text("📊") },
                    label = { Text("统计") }
                )
                NavigationBarItem(
                    selected = selectedTab == 5,
                    onClick = { selectedTab = 5 },
                    icon = { Text("⚙️") },
                    label = { Text("管理") }
                )
                NavigationBarItem(
                    selected = selectedTab == 6,
                    onClick = { selectedTab = 6 },
                    icon = { Text("ℹ️") },
                    label = { Text("关于") }
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues)) {
            when (selectedTab) {
                0 -> HomeScreen(viewModel)
                1 -> ProvidersScreen(viewModel)
                2 -> ModelsScreen(viewModel)
                3 -> ChatScreen(viewModel)
                4 -> StatsScreen(viewModel)
                5 -> DataManagementScreen(viewModel)
                6 -> AboutScreen(viewModel)
            }
        }
    }
}

// ============================================================
// 首页 —— 网关状态与启停控制
// ============================================================
@Composable
fun HomeScreen(viewModel: GatewayViewModel) {
    val serviceRunning by viewModel.serviceRunning.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        // 状态卡片
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (serviceRunning) "🟢 网关运行中" else "🔴 网关已停止",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (serviceRunning) Online else Error
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(12.dp))

                // 网关端口设置
                val gatewayPort by viewModel.gatewayPort.collectAsState()
                var portInput by remember { mutableStateOf(gatewayPort.toString()) }
                
                LaunchedEffect(gatewayPort) {
                    portInput = gatewayPort.toString()
                }
                
                Text(
                    text = "网关监听端口",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = portInput,
                        onValueChange = { 
                            portInput = it
                            if (it.toIntOrNull() in 1..65535) {
                                viewModel.setGatewayPort(it.toInt())
                            }
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    Text(
                        text = "默认: 8889",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                
                InfoRow("监听端口", gatewayPort.toString())
                InfoRow(
                    "服务状态",
                    if (serviceRunning) "运行中" else "已停止",
                    if (serviceRunning) Online else Error
                )
                // 本地地址（可复制）
                val context = LocalContext.current
                val localAddr = "http://localhost:$gatewayPort"
                val localLanIp = remember { getLocalIpAddress() }
                val lanAddr = "http://$localLanIp:$gatewayPort"
                
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            copyToClipboard(context, "本地地址", localAddr)
                        }
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("本地地址", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(4.dp))
                        Text("📋", style = MaterialTheme.typography.labelSmall)
                    }
                    Text(localAddr, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            copyToClipboard(context, "局域网地址", lanAddr)
                        }
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("局域网地址", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(4.dp))
                        Text("📋", style = MaterialTheme.typography.labelSmall)
                    }
                    Text(lanAddr, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 启停控制按钮
        Button(
            onClick = { viewModel.toggleGateway() },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (serviceRunning) Error else Online
            )
        ) {
            Icon(
                imageVector = if (serviceRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (serviceRunning) "停止网关服务" else "启动网关服务",
                style = MaterialTheme.typography.titleMedium
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // ★★ 自动故障转移开关 ★★
        val autoFailover by viewModel.autoFailover.collectAsState()
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("🔄 自动故障转移", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(
                        text = if (autoFailover) "开启：请求失败自动切换其他可用模型" else "关闭：只使用指定模型",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = autoFailover,
                    onCheckedChange = { viewModel.toggleAutoFailover() }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 使用说明
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = "📖 使用说明",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "1. 添加服务商（AI API提供商）\n" +
                            "2. 为服务商同步模型列表\n" +
                            "3. 启动网关服务\n" +
                            "4. 在第三方应用中设置 Base URL:\n" +
                            "   http://手机IP:8889/v1\n" +
                            "5. API Key 任意填写即可转发",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 提示
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = Warning.copy(alpha = 0.15f)
            )
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("⚠️", style = MaterialTheme.typography.titleLarge)
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "请确保手机与目标设备在同一局域网内，\n且防火墙未阻止 8889 端口",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
    valueColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = valueColor
        )
    }
}

// ============================================================
// 服务商管理页面 —— 完整 CRUD
// ============================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProvidersScreen(viewModel: GatewayViewModel) {
    val providers by viewModel.providers.collectAsState()
    val showDialog by viewModel.showAddProviderDialog.collectAsState()
    val editProvider by viewModel.showEditProviderDialog.collectAsState()

    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { viewModel.showAddProvider() },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("添加服务商") }
            )
        }
    ) { padding ->
        if (providers.isEmpty()) {
            // 空状态
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("🔌", fontSize = MaterialTheme.typography.displayLarge.fontSize)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "暂无服务商",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "点击右下角按钮添加 AI 服务商",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(providers, key = { it.id }) { provider ->
                    ProviderCard(
                        provider = provider,
                        onToggleEnabled = { viewModel.toggleProviderEnabled(provider) },
                        onEdit = { viewModel.showEditProvider(provider) },
                        onDelete = { viewModel.deleteProvider(provider) },
                        onSync = { viewModel.syncModels(provider) }
                    )
                }
            }
        }
    }

    // 添加服务商对话框
    if (showDialog) {
        AddProviderDialog(
            viewModel = viewModel,
            onDismiss = { viewModel.hideAddProvider() }
        )
    }

    // 编辑服务商对话框
    editProvider?.let { provider ->
        EditProviderDialog(
            provider = provider,
            onDismiss = { viewModel.hideEditProvider() },
            onSave = { updated ->
                viewModel.updateProvider(updated)
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderCard(
    provider: Provider,
    onToggleEnabled: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onSync: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (provider.isEnabled)
                MaterialTheme.colorScheme.surface
            else
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = provider.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        color = if (provider.isEnabled) Online.copy(alpha = 0.15f) else Offline.copy(alpha = 0.15f),
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(
                            text = if (provider.isEnabled) "已启用" else "已禁用",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (provider.isEnabled) Online else Offline,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
                // 操作按钮
                Row {
                    IconButton(onClick = onToggleEnabled) {
                        Text(
                            if (provider.isEnabled) "🔴" else "🟢",
                            style = MaterialTheme.typography.titleSmall
                        )
                    }
                    IconButton(onClick = onSync) {
                        Icon(
                            Icons.Default.Sync,
                            contentDescription = "同步模型",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    IconButton(onClick = onEdit) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = "编辑",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    IconButton(onClick = onDelete) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "删除",
                            tint = Error
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "类型: ${provider.type}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = provider.baseUrl,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
            if (provider.apiKey != null) {
                Text(
                    text = "API Key: ${provider.apiKey.take(8)}...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

// ============================================================
// 添加服务商对话框
// ============================================================
@Composable
private fun AddProviderDialog(
    viewModel: GatewayViewModel,
    onDismiss: () -> Unit
) {
    val form by viewModel.providerForm.collectAsState()
    val types = GatewayViewModel.PROVIDER_TYPES
    var selectedIndex by remember { mutableStateOf(0) }
    var showApiKey by remember { mutableStateOf(false) }
    
    // 判断当前类型是否匹配预设
    LaunchedEffect(form.type) {
        val idx = types.indexOfFirst { it.defaultType == form.type }
        if (idx >= 0) selectedIndex = idx else selectedIndex = 4 // 默认自定义
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("添加服务商", fontWeight = FontWeight.Bold)
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 服务商名称
                OutlinedTextField(
                    value = form.name,
                    onValueChange = { viewModel.updateFormField("name", it) },
                    label = { Text("服务商名称") },
                    placeholder = { Text("例如: OpenAI, Claude, 本地Ollama") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // 大模型类型选择（下拉）
                var expanded by remember { mutableStateOf(false) }
                @OptIn(ExperimentalMaterial3Api::class)
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = !expanded }
                ) {
                    @OptIn(ExperimentalMaterial3Api::class)
                    OutlinedTextField(
                        value = types.getOrElse(selectedIndex) { types[4] }.displayName,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("大模型类型") },
                        trailingIcon = {
                            @OptIn(ExperimentalMaterial3Api::class)
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor()
                    )
                    
                    @OptIn(ExperimentalMaterial3Api::class)
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        types.forEachIndexed { index, preset ->
                            @OptIn(ExperimentalMaterial3Api::class)
                            DropdownMenuItem(
                                text = { Text(preset.displayName) },
                                onClick = {
                                    selectedIndex = index
                                    viewModel.selectProviderType(index)
                                    expanded = false
                                }
                            )
                        }
                    }
                }

                // 类型标识（自动匹配，只读）
                OutlinedTextField(
                    value = form.type,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("类型标识") },
                    placeholder = { Text("OpenAI Compatible / Anthropic / Custom") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = false
                )

                // API 地址
                val finalUrlDisplay = remember(form.baseUrl) {
                    val base = form.baseUrl.trimEnd('/')
                    if (base.startsWith("http")) "$base/v1/chat/completions" else ""
                }
                OutlinedTextField(
                    value = form.baseUrl,
                    onValueChange = { 
                        viewModel.updateFormField("baseUrl", it)
                        // 自动提取端口
                        val extractedPort = viewModel.extractPortFromUrl(it)
                        if (extractedPort.isNotBlank()) {
                            viewModel.updateFormField("port", extractedPort)
                        }
                    },
                    label = { Text("API 地址") },
                    supportingText = {
                        if (form.baseUrl.startsWith("http")) {
                            Text("最终URL: $finalUrlDisplay", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        } else {
                            Text("提示: 输入 http://... 开头地址会自动拼接 /v1/chat/completions", style = MaterialTheme.typography.labelSmall)
                        }
                    },
                    placeholder = { Text("https://api.openai.com 或 http://localhost:11434") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // 端口
                OutlinedTextField(
                    value = form.port,
                    onValueChange = { viewModel.updateFormField("port", it) },
                    label = { Text("端口 (可选)") },
                    placeholder = { Text("如 443, 11434, 8080") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )

                // API Key
                OutlinedTextField(
                    value = form.apiKey,
                    onValueChange = { viewModel.updateFormField("apiKey", it) },
                    label = { Text("API Key") },
                    placeholder = { Text("sk-... 或留空（本地服务无需Key）") },
                    singleLine = true,
                    visualTransformation = if (showApiKey) VisualTransformation.None
                        else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showApiKey = !showApiKey }) {
                            Icon(
                                imageVector = if (showApiKey) Icons.Default.Visibility
                                    else Icons.Default.VisibilityOff,
                                contentDescription = if (showApiKey) "隐藏" else "显示"
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                
                // 提示信息
                if (selectedIndex != 4) {
                    Text(
                        text = "💡 已自动填充对应类型的默认配置，你可手动修改",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { viewModel.saveProvider() }) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

// ============================================================
// 编辑服务商对话框
// ============================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditProviderDialog(
    provider: Provider,
    onDismiss: () -> Unit,
    onSave: (Provider) -> Unit
) {
    var name by remember { mutableStateOf(provider.name) }
    var type by remember { mutableStateOf(provider.type) }
    var baseUrl by remember { mutableStateOf(provider.baseUrl) }
    var port by remember { mutableStateOf(provider.port) }
    var apiKey by remember { mutableStateOf(provider.apiKey ?: "") }
    var showApiKey by remember { mutableStateOf(false) }
    var typeExpanded by remember { mutableStateOf(false) }
    val types = GatewayViewModel.PROVIDER_TYPES
    var selectedIndex by remember(type) { mutableStateOf(types.indexOfFirst { it.defaultType == type }.takeIf { it >= 0 } ?: 4) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("编辑服务商", fontWeight = FontWeight.Bold)
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("服务商名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // 大模型类型选择（下拉）
                ExposedDropdownMenuBox(expanded = typeExpanded, onExpandedChange = { typeExpanded = !typeExpanded }) {
                    @OptIn(ExperimentalMaterial3Api::class)
                    OutlinedTextField(
                        value = types.getOrElse(selectedIndex) { types[4] }.displayName,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("大模型类型") },
                        trailingIcon = {
                            @OptIn(ExperimentalMaterial3Api::class)
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = typeExpanded)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor()
                    )
                    
                    @OptIn(ExperimentalMaterial3Api::class)
                    ExposedDropdownMenu(
                        expanded = typeExpanded,
                        onDismissRequest = { typeExpanded = false }
                    ) {
                        types.forEachIndexed { index, preset ->
                            @OptIn(ExperimentalMaterial3Api::class)
                            DropdownMenuItem(
                                text = { Text(preset.displayName) },
                                onClick = {
                                    selectedIndex = index
                                    type = preset.defaultType
                                    baseUrl = preset.defaultBaseUrl
                                    port = preset.defaultPort
                                    typeExpanded = false
                                }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = type,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("类型标识") },
                    singleLine = true,
                    enabled = false,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { 
                        baseUrl = it
                        // 自动提取端口
                        val extractedPort = extractPortFromUrlSimple(it)
                        if (extractedPort.isNotBlank()) {
                            port = extractedPort
                        }
                    },
                    label = { Text("API 地址") },
                    supportingText = {
                        if (baseUrl.startsWith("http")) {
                            val finalUrl = baseUrl.trimEnd('/') + "/v1/chat/completions"
                            Text("最终URL: $finalUrl", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        } else {
                            Text("提示: 输入 http://... 开头地址会自动拼接 /v1/chat/completions", style = MaterialTheme.typography.labelSmall)
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it },
                    label = { Text("端口") },
                    placeholder = { Text("如 443, 11434, 8080") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API Key") },
                    singleLine = true,
                    visualTransformation = if (showApiKey) VisualTransformation.None
                        else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showApiKey = !showApiKey }) {
                            Icon(
                                imageVector = if (showApiKey) Icons.Default.Visibility
                                    else Icons.Default.VisibilityOff,
                                contentDescription = null
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                onSave(
                    provider.copy(
                        name = name,
                        type = type,
                        baseUrl = baseUrl.trimEnd('/'),
                        port = port,
                        apiKey = apiKey.ifBlank { null }
                    )
                )
            }) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

/** 简单的端口提取函数（用于编辑对话框） */
private fun extractPortFromUrlSimple(url: String): String {
    if (url.isBlank()) return ""
    return try {
        val regex = Regex("://[^:]+:(\\d+)")
        regex.find(url)?.groupValues?.getOrNull(1) ?: ""
    } catch (_: Exception) { "" }
}

/**
 * 复制文本到剪贴板
 */
private fun copyToClipboard(context: Context, label: String, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
}

/**
 * 获取设备当前的局域网 IP 地址（WiFi）
 */
private fun getLocalIpAddress(): String {
    try {
        val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
        while (interfaces.hasMoreElements()) {
            val intf = interfaces.nextElement()
            // 只遍历 wlan 或非回环接口
            if (intf.isLoopback || intf.isPointToPoint) continue
            val addrs = intf.inetAddresses
            while (addrs.hasMoreElements()) {
                val addr = addrs.nextElement()
                if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                    return addr.hostAddress ?: continue
                }
            }
        }
    } catch (_: Exception) { }
    return "无法获取IP"
}

// ============================================================
// 模型管理页面（带搜索）
// ============================================================
@Composable
fun ModelsScreen(viewModel: GatewayViewModel) {
    val models by viewModel.models.collectAsState()
    val providers by viewModel.providers.collectAsState()
    val syncingProviderId by viewModel.syncingProviderId.collectAsState()
    val syncResult by viewModel.syncResult.collectAsState()
    val editModelDialogModel by viewModel.showEditModelDialog.collectAsState()

    var searchQuery by remember { mutableStateOf("") }

    // 搜索过滤
    val filteredModels = remember(models, searchQuery) {
        if (searchQuery.isBlank()) models
        else models.filter { 
            it.displayName.contains(searchQuery, ignoreCase = true) ||
            it.modelId.contains(searchQuery, ignoreCase = true) ||
            it.customAlias.contains(searchQuery, ignoreCase = true)
        }
    }

    // 按服务商分组
    val modelsByProvider = remember(filteredModels, providers) {
        val providerMap = providers.associateBy { it.id }
        filteredModels.groupBy { model ->
            providerMap[model.providerId]?.name ?: "未知服务商(ID:${model.providerId})"
        }
    }

    if (models.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("🤖", fontSize = MaterialTheme.typography.displayLarge.fontSize)
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "暂无模型",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "请先添加服务商并同步模型列表",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                if (providers.isNotEmpty()) {
                    Text(
                        text = "在「服务商」页面点击 🔄 按钮同步模型",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    } else {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 搜索框
        item {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = { Text("🔍 搜索模型") },
                placeholder = { Text("输入模型名称/ID/别名...") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
        // 同步结果提示
            syncResult?.let { result ->
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (result.startsWith("✅"))
                                Online.copy(alpha = 0.15f)
                            else if (result.startsWith("❌"))
                                Error.copy(alpha = 0.15f)
                            else
                                Warning.copy(alpha = 0.15f)
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = result,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { viewModel.clearSyncResult() }) {
                                Text("关闭")
                            }
                        }
                    }
                }
            }

            // 批量测速按钮
            item {
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val isBatchTesting by viewModel.batchTesting.collectAsState()
                    Button(
                        onClick = { viewModel.batchTestAllModels() },
                        enabled = !isBatchTesting,
                        modifier = Modifier.weight(1f)
                    ) {
                        if (isBatchTesting) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("测速中...")
                        } else {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("🔍 批量测速(自动开启)")
                        }
                    }
                }
            }
            // 按服务商分组显示模型
            modelsByProvider.forEach { (providerName, modelList) ->
                item {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "📌 $providerName",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }

                items(modelList, key = { it.id }) { model ->
                    ModelCard(model = model, viewModel = viewModel)
                }
            }

            item {
                Spacer(modifier = Modifier.height(80.dp))
            }
        }
    }

    // 编辑模型别名对话框
    editModelDialogModel?.let { model ->
        EditModelAliasDialog(
            model = model,
            viewModel = viewModel,
            onDismiss = { viewModel.hideEditModelAlias() }
        )
    }
}

@Composable
private fun ModelCard(model: AiModel, viewModel: GatewayViewModel) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (model.isEnabled)
                MaterialTheme.colorScheme.surface
            else
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
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
                        text = model.displayName,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (!model.isEnabled) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(
                            color = Error.copy(alpha = 0.15f),
                            shape = MaterialTheme.shapes.small
                        ) {
                            Text(
                                text = "已禁用",
                                style = MaterialTheme.typography.labelSmall,
                                color = Error,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                            )
                        }
                    }
                }
                Text(
                    text = "ID: ${model.modelId}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 测试按钮
            IconButton(
                onClick = { viewModel.testModelSpeed(model) },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "测试",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            // 编辑别名按钮
            IconButton(
                onClick = { viewModel.showEditModelAlias(model) },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = "编辑别名",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 启用/禁用开关
Switch(
    checked = model.isEnabled,
    onCheckedChange = { viewModel.toggleModelEnabled(model) }
)

// ★ 代理模式按钮（走代理/直连）
IconButton(
    onClick = { viewModel.toggleModelProxy(model) },
    modifier = Modifier.size(36.dp)
) {
    Text(
        text = if (model.useProxy) "🔄" else "🔗",
        style = MaterialTheme.typography.labelLarge
    )
}

// 同步状态标签
            Surface(
                color = when (model.syncStatus) {
                    "Synced" -> Online.copy(alpha = 0.15f)
                    "Pending" -> Warning.copy(alpha = 0.15f)
                    "Failed" -> Error.copy(alpha = 0.15f)
                    else -> Offline.copy(alpha = 0.15f)
                },
                shape = MaterialTheme.shapes.small
            ) {
                Text(
                    text = when (model.syncStatus) {
                        "Synced" -> "✅ 已同步"
                        "Pending" -> "⏳ 待同步"
                        "Failed" -> "❌ 失败"
                        else -> model.syncStatus
                    },
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
        }
    }
}

// ============================================================
// 编辑模型别名对话框
// ============================================================
@Composable
private fun EditModelAliasDialog(
    model: AiModel,
    viewModel: GatewayViewModel,
    onDismiss: () -> Unit
) {
    var aliasText by remember { mutableStateOf(model.customAlias) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑模型别名") },
        text = {
            Column {
                Text(
                    text = "模型: ${model.displayName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = aliasText,
                    onValueChange = { aliasText = it },
                    label = { Text("自定义别名") },
                    placeholder = { Text("输入别名（留空则使用默认名称）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    viewModel.saveModelAlias(model, aliasText)
                    onDismiss()
                }
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = {
                viewModel.hideEditModelAlias()
                onDismiss()
            }) {
                Text("取消")
            }
        }
    )
}