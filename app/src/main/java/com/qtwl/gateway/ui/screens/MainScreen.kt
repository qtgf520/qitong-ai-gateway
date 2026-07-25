package com.qtwl.gateway.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.input.pointer.pointerInput
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
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
import androidx.compose.ui.text.style.TextOverflow
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
import com.qtwl.gateway.ui.viewmodel.pipelineStatus
import com.qtwl.gateway.ui.viewmodel.pipelineRunning
import com.qtwl.gateway.ui.viewmodel.pipelineProgress
import com.qtwl.gateway.service.LiveSession
import com.qtwl.gateway.service.GatewayForegroundService
import com.qtwl.gateway.gateway.GatewayScheduler
import com.qtwl.gateway.utils.TranslationManager
import com.qtwl.gateway.utils.tr
import kotlinx.coroutines.delay
import com.qtwl.gateway.utils.localizedText
import com.qtwl.gateway.utils.localizeRuntimeText
import com.qtwl.gateway.utils.localizeGeneratedName

/**
 * 主屏幕 —— 带底部导航的容器
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: GatewayViewModel = viewModel(factory = GatewayViewModel.Factory())
) {
    val context = LocalContext.current
    // Observe locale changes at the navigation root so every tab recomposes.
    val languageTick = TranslationManager.currentLanguageFlow.collectAsState().value
    var selectedTab by remember { mutableStateOf(0) }
    val snackbarMessage by viewModel.snackbarMessage.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // 处理 Snackbar 显示 → 改用 Toast（始终在最前面）
    LaunchedEffect(snackbarMessage) {
        snackbarMessage?.let {
            val ctx = context
            android.widget.Toast.makeText(ctx, localizeRuntimeText(it), android.widget.Toast.LENGTH_SHORT).show()
            viewModel.clearSnackbar()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(tr("app_name")) },
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
                    label = { Text(tr("nav_home")) }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Text("🔌") },
                    label = { Text(tr("nav_providers")) }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Text("🤖") },
                    label = { Text(tr("nav_models")) }
                )
                NavigationBarItem(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    icon = { Text("💬") },
                    label = { Text(tr("nav_chat")) }
                )
                NavigationBarItem(
                    selected = selectedTab == 4,
                    onClick = { selectedTab = 4 },
                    icon = { Text("📊") },
                    label = { Text(tr("nav_stats")) }
                )
                NavigationBarItem(
                    selected = selectedTab == 5,
                    onClick = { selectedTab = 5 },
                    icon = { Text("⚙️") },
                    label = { Text(tr("nav_manage")) }
                )
                NavigationBarItem(
                    selected = selectedTab == 6,
                    onClick = { selectedTab = 6 },
                    icon = { Text("ℹ️") },
                    label = { Text(tr("nav_about")) }
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
                        text = if (serviceRunning) tr("gateway_running") else tr("gateway_stopped"),
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
                    text = tr("port_label"),
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
                        text = "${tr("default")} 8889",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                
                InfoRow(tr("port_value"), gatewayPort.toString())
                InfoRow(tr("service_status"),
                    if (serviceRunning) tr("running") else tr("stopped"),
                    if (serviceRunning) Online else Error
                )
                // ★★ 实时活跃模型显示
                var activeModel by remember { mutableStateOf("") }
                LaunchedEffect(serviceRunning) {
                    while (serviceRunning) {
                        activeModel = com.qtwl.gateway.service.GatewayForegroundService.activeNodeName
                        delay(2000)
                    }
                }
                if (activeModel.isNotBlank()) {
                    InfoRow(
                        tr("active_model"),
                        activeModel,
                        MaterialTheme.colorScheme.primary
                    )
                }
                // 本地地址（可复制）
                val context = LocalContext.current
                val localAddr = "http://localhost:$gatewayPort"
                val localLanIp = remember { getLocalIpAddress() }
                val lanAddr = "http://$localLanIp:$gatewayPort"
                
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            copyToClipboard(context, localizedText("本地地址", "Local address"), localAddr)
                        }
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(tr("local_addr"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(4.dp))
                        Text("📋", style = MaterialTheme.typography.labelSmall)
                    }
                    Text(localAddr, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            copyToClipboard(context, localizedText("局域网地址", "LAN address"), lanAddr)
                        }
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(tr("lan_addr"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                text = if (serviceRunning) tr("stop_gateway") else tr("start_gateway"),
                style = MaterialTheme.typography.titleMedium
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
// ★★ 首页思考引导开关 & 卡片 ★★
          // 读取/保存开关状态（SharedPreferences via GatewayForegroundService)
          val showHintState = remember { mutableStateOf(true) }
          LaunchedEffect(Unit) {
              val stored = com.qtwl.gateway.service.GatewayForegroundService.getGatewayConfig("show_home_hint", "true")
              showHintState.value = stored.toBoolean()
          }
          Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
          ) {
              Text(tr("home_thinking_guide"), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
              Switch(
                  checked = showHintState.value,
                  onCheckedChange = { enabled ->
                      showHintState.value = enabled
                      com.qtwl.gateway.service.GatewayForegroundService.saveGatewayConfig("show_home_hint", enabled.toString())
                  }
              )
          }
          if (showHintState.value) {
              Card(
                  modifier = Modifier.fillMaxWidth(),
                  colors = CardDefaults.cardColors(
                      containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                  )
              ) {
                  Column(modifier = Modifier.padding(16.dp)) {
                      Row(verticalAlignment = Alignment.CenterVertically) {
                          Text("💡", style = MaterialTheme.typography.titleLarge)
                          Spacer(modifier = Modifier.width(8.dp))
                          Text(
                              text = tr("quick_start"),
                              style = MaterialTheme.typography.titleSmall,
                              fontWeight = FontWeight.Bold,
                              color = MaterialTheme.colorScheme.primary
                          )
                      }
                      Spacer(modifier = Modifier.height(8.dp))
                      Text(
                          text = "1. ${tr("add_provider")} → ${tr("sync_models")}\n" +
                                "2. ${tr("start_gateway")}\n" +
                                "3. ${tr("set_base_url")}\n" +
                                "4. ${tr("enable_failover")}",
                          style = MaterialTheme.typography.bodySmall,
                          color = MaterialTheme.colorScheme.onSurfaceVariant,
                          lineHeight = MaterialTheme.typography.bodySmall.lineHeight
                      )
                  }
              }
          }

        Spacer(modifier = Modifier.height(12.dp))

        // ★★ 自动故障转移开关 + 流水线测速看板 ★★
        val autoFailover by viewModel.autoFailover.collectAsState()
        val pStatus by pipelineStatus.collectAsState()
        val pRunning by pipelineRunning.collectAsState()
        val pProgress by pipelineProgress.collectAsState()
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // 开关行
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(tr("auto_failover"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Text(
                            text = if (autoFailover) tr("failover_on") else tr("failover_off"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = autoFailover,
                        onCheckedChange = {
                            viewModel.toggleAutoFailover()
                        }
                    )
                }

                // ★★ 测速排行榜 — 分两个框：框1=已完成，框2=正在测速 ★★
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))

                // 标题 + 启停按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "🏃 ${tr("test_speed")}",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    TextButton(
                        onClick = {
                            if (pRunning) viewModel.stopPipelineTest()
                            else viewModel.startPipelineTest()
                        }
                    ) {
                        Text(
                            if (pRunning) "⏹ ${tr("stop_gateway")}" else "▶️ ${tr("start_gateway")}",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }

                // ★★ 指示灯 + 框1 + 框2 ★★
                val doneItems = pStatus.filter {
                    it.status.startsWith("✅") || it.status.startsWith("❌")
                }.sortedBy { it.latencyMs }
                val forcedModelKey by viewModel.forcedModelKey.collectAsState()
                val hasReadyModel = doneItems.any { it.status.startsWith("✅") }
                val allFailed = doneItems.isNotEmpty() && doneItems.all { it.status.startsWith("❌") }

                // ★★ 指示灯：排行上方，亮红/绿灯 ★★
                Spacer(modifier = Modifier.height(4.dp))
                val indicatorColor = when {
                    pRunning -> MaterialTheme.colorScheme.primary
                    hasReadyModel -> Online
                    else -> Error
                }
                val indicatorText = when {
                    pStatus.isEmpty() && !pRunning -> localizedText("请先启动测速获取可用模型排行", "Start speed test first to get the available model ranking")
                    pRunning -> localizedText("测速中，完成一个即可使用", "Speed testing; you can use a model as soon as one completes")
                    hasReadyModel -> localizedText("全部测速完成，qtai-sj 已就绪", "All speed tests complete. qtai-sj is ready")
                    allFailed -> localizedText("全部模型异常，暂时无法使用", "All models are abnormal and temporarily unavailable")
                    else -> localizedText("部分模型异常，qtai-sj 可能受影响", "Some models are abnormal; qtai-sj may be affected")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Canvas(modifier = Modifier.size(14.dp)) {
                        drawCircle(color = indicatorColor)
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = indicatorText,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = indicatorColor
                    )
                }

                // ★★ 首页显示当前AI助手切换的模型 ★★
                val currentModelName = remember {
                    val forced = GatewayForegroundService.getForcedModel()
                    if (forced.isNotBlank()) forced
                    else if (GatewayScheduler.pipelineSortedModelIds.isNotEmpty()) GatewayScheduler.pipelineSortedModelIds.first()
                    else ""
                }
                if (currentModelName.isNotBlank()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(localizedText("🧠 AI助手当前模型", "🧠 Current AI assistant model"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(currentModelName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                }
                
                // ★★ 框2：正在测速的模型 ★★
                val currentItem = pStatus.find { it.isCurrent && !it.status.startsWith("✅") && !it.status.startsWith("❌") }
                if (currentItem != null || pRunning) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(localizedText("⏳ 正在测速", "⏳ Speed testing"), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(2.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.06f))
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = currentItem?.modelName ?: localizedText("准备中...", "Preparing..."),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            if (pRunning) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(4.dp))
                                    Text(localizedText("测速中", "Testing speed"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }

                // ★★ 框1：已测速完的模型（✅成功 / ❌失败），按速度排序 ★★
                if (doneItems.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(localizedText("✅ 已测速完成", "✅ Speed test complete"), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
                        color = Online)
                    Spacer(modifier = Modifier.height(4.dp))
// ★ 显示强制模式指示
                    if (forcedModelKey.isNotBlank()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = localizedText("🎯 强制模式: ", "🎯 Forced mode: ") + (doneItems.find { "${it.providerId}::${it.modelId}" == forcedModelKey || it.modelId == forcedModelKey }?.modelName ?: forcedModelKey),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            TextButton(onClick = { viewModel.clearForcedModel() }) {
                                Text(localizedText("↩️ 取消强制", "↩️ Cancel forced mode"), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                        LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        itemsIndexed(doneItems) { index, item ->
                            val itemKey = "${item.providerId}::${item.modelId}"
                            val isSelected = itemKey == forcedModelKey || item.modelId == forcedModelKey
                            Card(
                                modifier = Modifier.fillMaxWidth()
                                    .clickable { viewModel.forceModel(item.modelId, item.providerId) }
                                    .then(
                                        if (isSelected) Modifier.background(
                                            Warning.copy(alpha = 0.12f), MaterialTheme.shapes.small
                                        ) else Modifier
                                    ),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isSelected) Warning.copy(alpha = 0.08f)
                                        else MaterialTheme.colorScheme.surface
                                )
                            ) {
                                Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                                    // 第一行：排名 + 模型名 + 选中标记
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = "#${index + 1} ",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                                        )
                                        if (isSelected) {
                                            Text("🎯 ", style = MaterialTheme.typography.bodySmall)
                                        }
                                        Text(
                                            text = item.modelName,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                    // 第二行：测速指标 + 状态
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        val statusText = item.status
                                        val isError = statusText.startsWith("❌")
                                        val isSuccess = statusText.startsWith("✅")
                                        Spacer(modifier = Modifier.width(16.dp))
                                        if (isSuccess) {
                                            // 提取 TTFT/TPS 数字
                                            val ttftMatch = Regex("TTFT=(\\d+)ms").find(statusText)
                                            val tpsMatch = Regex("TPS=([\\d.]+)").find(statusText)
                                            val latencyMatch = Regex("(\\d+)ms$").find(statusText)
                                            if (ttftMatch != null) {
                                                Text("⚡ ${ttftMatch.groupValues[1]}ms", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                Spacer(modifier = Modifier.width(8.dp))
                                            }
                                            if (tpsMatch != null) {
                                                Text("🚀 ${tpsMatch.groupValues[1]} tok/s", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                Spacer(modifier = Modifier.width(8.dp))
                                            }
                                            if (latencyMatch != null) {
                                                Text("⏱ ${latencyMatch.groupValues[1]}ms", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                            Spacer(modifier = Modifier.weight(1f))
                                            Text("✅ 可用", style = MaterialTheme.typography.labelSmall, color = Online)
                                        } else if (isError) {
                                            Spacer(modifier = Modifier.weight(1f))
                                            Text("❌ 不可用", style = MaterialTheme.typography.labelSmall, color = Error)
                                        } else {
                                            Spacer(modifier = Modifier.weight(1f))
                                            Text(localizeRuntimeText(statusText), style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else if (!pRunning && pStatus.isNotEmpty()) {
                    Text(
                        text = localizedText("⏳ 测速排队中，请点击「▶️ 启动」开始测速", "⏳ Speed test queued. Tap ▶️ Start to begin"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                } else if (pStatus.isEmpty()) {
                    Text(
                        text = localizedText("暂无测速数据，点击「▶️ 启动」开始测速", "No speed-test data yet. Tap ▶️ Start to begin"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ★★ 实时会话跑马灯（每条从右←左滚动，显示时间+模型+发送+回复内容）★★
        var tick by remember { mutableStateOf(0L) }
        LaunchedEffect(Unit) {
            while (true) { delay(800); tick = System.currentTimeMillis() }
        }
        val currentSessions = remember(tick) { viewModel.liveSessions }
        if (currentSessions.isNotEmpty() && serviceRunning) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(localizedText("📡 实时会话", "📡 Live sessions"), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        TextButton(onClick = { viewModel.clearLiveSessions() }) { Text(localizedText("🗑️ 清空", "🗑️ Clear"), style = MaterialTheme.typography.labelSmall) }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    val transition = rememberInfiniteTransition(label = "marquee")
                    Column(modifier = Modifier.fillMaxWidth().heightIn(max = 160.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        currentSessions.take(10).forEachIndexed { idx, session ->
                            val offset by transition.animateFloat(
                                initialValue = 1200f, targetValue = -1200f,
                                animationSpec = infiniteRepeatable(
                                    animation = tween(durationMillis = (14000 + idx * 1000).toInt(), easing = LinearEasing, delayMillis = (idx * 600).toInt()),
                                    repeatMode = RepeatMode.Restart
                                ), label = "marquee_$idx"
                            )
                            Row(modifier = Modifier.fillMaxWidth().offset(x = offset.dp), verticalAlignment = Alignment.CenterVertically) {
                                // 时间
                                val timeStr = remember(session.timestamp) {
                                    val sdf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                                    sdf.format(java.util.Date(session.timestamp))
                                }
                                Text(timeStr, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Medium)
                                Spacer(Modifier.width(4.dp))
                                // 状态
                                Text(localizeRuntimeText(session.status), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
                                    color = if (session.status.startsWith("📤")) Online else MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(4.dp))
                                // 模型名
                                Text(session.modelName.take(12), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 70.dp))
                                Spacer(Modifier.width(4.dp))
                                // ★★ 合并发送+回复内容为一整行跑马灯 ★★
                                val marqueeText = buildString {
                                    if (session.requestPreview.isNotBlank()) {
                                        append(localizedText("📤 我：", "📤 Me: ") + session.requestPreview)
                                    }
                                    if (session.requestPreview.isNotBlank() && session.responsePreview.isNotBlank()) {
                                        append(" → ")
                                    }
                                    if (session.responsePreview.isNotBlank()) {
                                        append("📥 AI：${session.responsePreview}")
                                    }
                                }
                                if (marqueeText.isNotBlank()) {
                                    Text(marqueeText, style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
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
                    text = localizedText("📖 使用说明", "📖 Usage guide"),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = localizedText("1. 添加服务商（AI API提供商）\n", "1. Add a provider (AI API provider)\n") +
                            localizedText("2. 为服务商同步模型列表\n", "2. Sync the provider model list\n") +
                            localizedText("3. 启动网关服务\n", "3. Start the gateway service\n") +
                            localizedText("4. 在第三方应用中设置 Base URL:\n", "4. Set the Base URL in the third-party app:\n") +
                            localizedText("   http://手机IP:8889/v1\n", "   http://phone-ip:8889/v1\n") +
                            localizedText("5. API Key 任意填写即可转发", "5. Any API key can be entered for forwarding"),
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
                    text = localizedText("请确保手机与目标设备在同一局域网内，\n且防火墙未阻止 8889 端口", "Make sure the phone and target device are on the same LAN,\nand that the firewall is not blocking port 8889"),
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
                text = { Text(tr("add_provider")) }
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
                        text = localizedText("暂无服务商", "No providers yet"),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = localizedText("点击右下角按钮添加 AI 服务商", "Tap the bottom-right button to add an AI provider"),
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
                itemsIndexed(providers.sortedBy { it.orderIndex }, key = { _, p -> p.id }) { index, provider ->
                    var showMoveMenu by remember { mutableStateOf(false) }
                    ProviderCard(
                        provider = provider,
                        onToggleEnabled = { viewModel.toggleProviderEnabled(provider) },
                        onEdit = { viewModel.showEditProvider(provider) },
                        onDelete = { viewModel.deleteProvider(provider) },
                        onSync = { viewModel.syncModels(provider) },
                        modifier = Modifier.combinedClickable(
                            onClick = { },
                            onLongClick = { showMoveMenu = true }
                        )
                    )
                    // 长按弹出移动菜单
                    if (showMoveMenu) {
                        AlertDialog(
                            onDismissRequest = { showMoveMenu = false },
                            title = { Text(provider.name, fontWeight = FontWeight.Bold) },
                            text = {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    if (index > 0) {
                                        TextButton(onClick = { viewModel.moveProvider(provider, -1); showMoveMenu = false }, modifier = Modifier.fillMaxWidth()) {
                                            Text("▲  上移")
                                        }
                                    }
                                    if (index < providers.sortedBy { it.orderIndex }.size - 1) {
                                        TextButton(onClick = { viewModel.moveProvider(provider, 1); showMoveMenu = false }, modifier = Modifier.fillMaxWidth()) {
                                            Text("▼  下移")
                                        }
                                    }
                                }
                            },
                            confirmButton = { TextButton(onClick = { showMoveMenu = false }) { Text(tr("close")) } }
                        )
                    }
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
    onSync: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
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
                verticalAlignment = Alignment.Top
            ) {
                // 左侧：名称 + 状态标签，允许标题自动换行
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = localizeGeneratedName(provider.name),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        color = if (provider.isEnabled) Online.copy(alpha = 0.15f) else Offline.copy(alpha = 0.15f),
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(
                            text = if (provider.isEnabled) localizedText("已启用", "Enabled") else localizedText("已禁用", "Disabled"),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (provider.isEnabled) Online else Offline,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
                // 右侧：操作按钮，固定宽度不会被挤
                Row {
                    IconButton(onClick = onToggleEnabled, modifier = Modifier.size(36.dp)) {
                        Text(
                            if (provider.isEnabled) "🔴" else "🟢",
                            style = MaterialTheme.typography.titleSmall
                        )
                    }
                    IconButton(onClick = onSync, modifier = Modifier.size(36.dp)) {
                        Icon(
                            Icons.Default.Sync,
                            contentDescription = localizedText("同步模型", "Sync models"),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = localizedText("编辑", "Edit"),
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = localizedText("删除", "Delete"),
                            tint = Error,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = localizedText("类型: ", "Type: ") + provider.type,
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
            Text(tr("add_provider"), fontWeight = FontWeight.Bold)
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 服务商名称
                OutlinedTextField(
                    value = form.name,
                    onValueChange = { viewModel.updateFormField("name", it) },
                    label = { Text(tr("provider_type_label")) },
                    placeholder = { Text(tr("provider_type_hint")) },
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
                        label = { Text(tr("model_type")) },
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
                    label = { Text(tr("provider_type")) },
                    placeholder = { Text(tr("type_options")) },
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
                    label = { Text(tr("api_url")) },
                    supportingText = {
                        if (form.baseUrl.startsWith("http")) {
                            Text("${tr("final_url")}: $finalUrlDisplay", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        } else {
                            Text(tr("url_hint"), style = MaterialTheme.typography.labelSmall)
                        }
                    },
                    placeholder = { Text(tr("api_url_hint")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // 端口
                OutlinedTextField(
                    value = form.port,
                    onValueChange = { viewModel.updateFormField("port", it) },
                    label = { Text(tr("port")) },
                    placeholder = { Text(tr("port_hint")) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )

                // API Key
                OutlinedTextField(
                    value = form.apiKey,
                    onValueChange = { viewModel.updateFormField("apiKey", it) },
                    label = { Text("API Key") },
                    placeholder = { Text(tr("api_key_hint")) },
                    singleLine = true,
                    visualTransformation = if (showApiKey) VisualTransformation.None
                        else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showApiKey = !showApiKey }) {
                            Icon(
                                imageVector = if (showApiKey) Icons.Default.Visibility
                                    else Icons.Default.VisibilityOff,
                                contentDescription = if (showApiKey) localizedText("隐藏", "Hide") else localizedText("显示", "Show")
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                
                // 提示信息
                if (selectedIndex != 4) {
                    Text(
                        text = localizedText("💡 已自动填充对应类型的默认配置，你可手动修改", "💡 Default configuration for this type has been filled automatically. You can edit it manually"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { viewModel.saveProvider() }) {
                Text(tr("save"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(tr("cancel"))
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
            Text(tr("edit_provider"), fontWeight = FontWeight.Bold)
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(tr("provider_type_label")) },
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
                        label = { Text(tr("model_type")) },
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
                    label = { Text(tr("provider_type")) },
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
                    label = { Text(tr("api_url")) },
                    supportingText = {
                        if (baseUrl.startsWith("http")) {
                            val finalUrl = baseUrl.trimEnd('/') + "/v1/chat/completions"
                            Text("${tr("final_url")}: $finalUrl", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        } else {
                            Text(tr("url_hint"), style = MaterialTheme.typography.labelSmall)
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it },
                    label = { Text(tr("port")) },
                    placeholder = { Text(tr("port_hint")) },
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
                Text(tr("save"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(tr("cancel"))
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
    return localizedText("无法获取IP", "Unable to get IP")
}

// ============================================================
// 模型管理页面（带搜索）
// ============================================================
@Composable
fun ModelsScreen(viewModel: GatewayViewModel) {
    val languageTick = TranslationManager.currentLanguageFlow.collectAsState().value
    val models by viewModel.models.collectAsState()
    val providers by viewModel.providers.collectAsState()
    val syncingProviderId by viewModel.syncingProviderId.collectAsState()
    val syncResult by viewModel.syncResult.collectAsState()
    val editModelDialogModel by viewModel.showEditModelDialog.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    var filterToolCall by remember { mutableStateOf(false) }
    var filterVision by remember { mutableStateOf(false) }

    // 搜索 + 标签筛选
    val filteredModels = remember(models, searchQuery, languageTick, filterToolCall, filterVision) {
        var fromDb = if (searchQuery.isBlank()) models
        else models.filter {
            it.displayName.contains(searchQuery, ignoreCase = true) ||
            it.modelId.contains(searchQuery, ignoreCase = true) ||
            it.customAlias.contains(searchQuery, ignoreCase = true)
        }
        if (filterToolCall) fromDb = fromDb.filter { com.qtwl.gateway.ui.viewmodel.ModelCapabilityManager.getCapabilities(it.modelId).first }
        if (filterVision) fromDb = fromDb.filter { com.qtwl.gateway.ui.viewmodel.ModelCapabilityManager.getCapabilities(it.modelId).second }
        listOfNotNull(
            AiModel(id = -1, modelId = "qtai-sj", displayName = localizedText("🔄 自动化切换", "🔄 Auto switch"), providerId = 0, isEnabled = true)
        ) + fromDb
    }

    // 按服务商分组（按服务商 orderIndex 排序）
    val groupedModels: List<Triple<String, String, Pair<List<AiModel>, List<String>>>> = remember(filteredModels, providers, languageTick) {
        val providerMap = providers.associateBy { it.id }
        val providerOrder = providers.sortedBy { it.orderIndex }.map { it.id }
        filteredModels.groupBy { model ->
            providerMap[model.providerId]?.name ?: if (model.modelId == "qtai-sj") "🔄 Auto switch" else "Unknown"
        }.entries.sortedBy { entry ->
            val firstModel = entry.value.firstOrNull()
            val pid = firstModel?.providerId ?: 0L
            providerOrder.indexOf(pid).let { if (it < 0) Int.MAX_VALUE else it }
        }.map { (providerName, modelList) ->
            Triple(providerName, providerName, modelList to emptyList())
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
                    text = localizedText("暂无模型", "No models yet"),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = localizedText("请先添加服务商并同步模型列表", "Add a provider and sync the model list first"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                if (providers.isNotEmpty()) {
                    Text(
                        text = localizedText("在「服务商」页面点击 🔄 按钮同步模型", "Tap 🔄 on the Providers page to sync models"),
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
        // 搜索框 + 标签筛选
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text("🔍 ${tr("search_model")}") },
                    placeholder = { Text(tr("search_hint")) },
                    singleLine = true,
                    trailingIcon = {
                        if (filterToolCall || filterVision || searchQuery.isNotBlank()) {
                            IconButton(onClick = {
                                searchQuery = ""
                                filterToolCall = false
                                filterVision = false
                            }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear")
                            }
                        }
                    },
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = filterToolCall,
                    onClick = { filterToolCall = !filterToolCall },
                    label = { Text("🔧") }
                )
                FilterChip(
                    selected = filterVision,
                    onClick = { filterVision = !filterVision },
                    label = { Text("👁️") }
                )
            }
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
                                text = localizeRuntimeText(result),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { viewModel.clearSyncResult() }) {
                                Text(tr("close"))
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
                            Text(localizedText("测速中...", "Speed testing..."))
                        } else {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(localizedText("🔍 批量测速(自动开启)", "🔍 Batch speed test (auto-enable)"))
                        }
                    }
                }
            }
// 按服务商分组显示模型
            groupedModels.forEach { (providerName, _, modelListWithProviders) ->
                val (modelList, _) = modelListWithProviders
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
        Column(modifier = Modifier.padding(12.dp)) {
            // 第一行：模型名称 + ID
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = localizeGeneratedName(model.displayName),
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
                                    text = localizedText("已禁用", "Disabled"),
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
            }

            // 第二行：操作按钮
            if (model.modelId == "qtai-sj") {
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val qtaiSjEnabled by viewModel.qtaiSjEnabled.collectAsState()
                    val brainModelId = com.qtwl.gateway.service.GatewayForegroundService.getQtaiSjBrain()
                    var showBrainPicker by remember { mutableStateOf(false) }
                    Switch(checked = qtaiSjEnabled, onCheckedChange = { viewModel.toggleQtaiSj() })
                    Spacer(Modifier.width(4.dp))
                    TextButton(onClick = { showBrainPicker = true }) {
                        Text(if (brainModelId.isNotBlank()) "🧠 $brainModelId" else localizedText("🧠绑定", "🧠 Bind"), style = MaterialTheme.typography.labelSmall)
                    }
                    if (showBrainPicker) {
                        val enabledModels by viewModel.enabledModels.collectAsState()
                        AlertDialog(
                            onDismissRequest = { showBrainPicker = false },
                            title = { Text(tr("select_brain")) },
                            text = { LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                                item { TextButton(onClick = { com.qtwl.gateway.service.GatewayForegroundService.saveQtaiSjBrain(""); showBrainPicker = false }) { Text(tr("clear_brain")) } }
                                items(enabledModels) { m ->
                                    val isSelected = m.modelId == brainModelId
                                    Row(modifier = Modifier.fillMaxWidth().clickable {
                                        com.qtwl.gateway.service.GatewayForegroundService.saveQtaiSjBrain(m.modelId); showBrainPicker = false
                                    }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        RadioButton(selected = isSelected, onClick = {
                                            com.qtwl.gateway.service.GatewayForegroundService.saveQtaiSjBrain(m.modelId); showBrainPicker = false
                                        })
                                        Spacer(Modifier.width(8.dp))
                                        Text(m.displayName, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                                    }
                                }
                            } },
                            confirmButton = { TextButton(onClick = { showBrainPicker = false }) { Text(tr("close")) } }
                        )
                    }
                }
            } else {
                Spacer(modifier = Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { viewModel.testModelSpeed(model) }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.PlayArrow, contentDescription = localizedText("测试", "Test"), modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                    Text(localizedText("测试", "Test"), style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.CenterVertically))
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(onClick = { viewModel.showEditModelAlias(model) }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Edit, contentDescription = localizedText("编辑别名", "Edit alias"), modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(localizedText("别名", "Alias"), style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.CenterVertically))
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(onClick = { viewModel.deleteModel(model) }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Delete, contentDescription = localizedText("删除", "Delete"), modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                    }
                    Text(localizedText("删除", "Delete"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.align(Alignment.CenterVertically))
                    Spacer(modifier = Modifier.weight(1f))
                    IconButton(onClick = { viewModel.toggleModelProxy(model) }, modifier = Modifier.size(32.dp)) {
                        Text(if (model.useProxy) "🔄" else "🔗", style = MaterialTheme.typography.labelLarge)
                    }
                    Switch(checked = model.isEnabled, onCheckedChange = { viewModel.toggleModelEnabled(model) }, modifier = Modifier.height(24.dp))
                }
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
        title = { Text(localizedText("编辑模型别名", "Edit model alias")) },
        text = {
            Column {
                Text(
                    text = localizedText("模型: ", "Model: ") + model.displayName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = aliasText,
                    onValueChange = { aliasText = it },
                    label = { Text(localizedText("自定义别名", "Custom alias")) },
                    placeholder = { Text(localizedText("输入别名（留空则使用默认名称）", "Enter an alias (leave empty to use default name)")) },
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
                Text(tr("save"))
            }
        },
        dismissButton = {
            TextButton(onClick = {
                viewModel.hideEditModelAlias()
                onDismiss()
            }) {
                Text(tr("cancel"))
            }
        }
    )
}
