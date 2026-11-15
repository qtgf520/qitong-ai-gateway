package com.qtwl.gateway.ui.screens

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.FastOutSlowInEasing
import kotlin.math.abs
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Settings
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
import com.qtwl.gateway.data.model.ModelRouteKey
import com.qtwl.gateway.data.model.routeKey
import com.qtwl.gateway.data.model.Provider
import com.qtwl.gateway.ui.theme.Error
import com.qtwl.gateway.ui.theme.Offline
import com.qtwl.gateway.ui.theme.Online
import com.qtwl.gateway.ui.theme.Warning
import com.qtwl.gateway.ui.viewmodel.GatewayViewModel
import com.qtwl.gateway.ui.viewmodel.pipelineStatus
import com.qtwl.gateway.ui.viewmodel.pipelineRunning
import com.qtwl.gateway.ui.viewmodel.pipelineProgress
import com.qtwl.gateway.ui.viewmodel.pipelineCountdown
import com.qtwl.gateway.service.LiveSession
import com.qtwl.gateway.service.GatewayForegroundService
import com.qtwl.gateway.gateway.GatewayScheduler
import com.qtwl.gateway.ui.components.AppIcons
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.qtwl.gateway.ui.components.GlassNavItem
import com.qtwl.gateway.ui.components.LiquidGlassBottomNav
import com.qtwl.gateway.utils.TranslationManager
import com.qtwl.gateway.utils.tr
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import com.qtwl.gateway.utils.localizedText
import com.qtwl.gateway.utils.localizeRuntimeText
import com.qtwl.gateway.utils.localizeGeneratedName
import com.qtwl.gateway.service.ThinkingConfigManager
import com.qtwl.gateway.service.GroupChatManager
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Person
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import com.qtwl.gateway.utils.PipelineStatusMark
import com.qtwl.gateway.utils.MessageMark

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
    var selectedTab by rememberSaveable { mutableStateOf(0) }
    // ★★ 系统返回键适配：二级页（5-9）按返回回到设置页(4)，主 tab 交给系统退出 ★★
    androidx.activity.compose.BackHandler(enabled = selectedTab >= 5) {
        selectedTab = 4
    }
    val snackbarMessage by viewModel.snackbarMessage.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // 处理 Snackbar 显示 → 改用 Toast（始终在最前面）
    LaunchedEffect(snackbarMessage) {
        snackbarMessage?.let {
            val ctx = context
            android.widget.Toast.makeText(ctx, localizeRuntimeText(MessageMark.strip(it)), android.widget.Toast.LENGTH_SHORT).show()
            viewModel.clearSnackbar()
        }
    }

    // 右上角菜单展开状态
    var menuExpanded by remember { mutableStateOf(false) }
    // Liquid Glass：把整页内容录制成层，供底部导航栏采样模糊
    val glassBackdrop = rememberLayerBackdrop()

    // 界面手感开关：从设置页改完，切页回来立刻生效
    com.qtwl.gateway.utils.UiSettings.attach(context)
    val navStretch = remember(selectedTab) { com.qtwl.gateway.utils.UiSettings.navStretch }

    // 导航栏只看「所属主标签」：二级页归属到它的入口标签
    val navSelected = when (selectedTab) {
        5, 6, 7, 8 -> 4
        9 -> 2
        else -> selectedTab.coerceIn(0, 4)
    }

    Scaffold(
        // 关键：不让 Scaffold 自己消费 windowInsets，
        // 避免与 bottomBar 内的 imePadding/navigationBarsPadding 叠加
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        // ★★★ 顶部网关栏（TopAppBar）已删除 ★★★
        //   原因：
        //     1. 它是整条 56dp 的纯色块，占了大量垂直空间，
        //        而里面只有「应用名 + 一个三点菜单」。
        //     2. 三点菜单里只有「设置」一项，而底部导航第 5 格就是设置，
        //        功能完全重复。
        //   现在标题和版本号移到了「设置」页顶部，三个点里的入口也一并搬过去。
        //   省下来的 56dp 全部让给内容区。
        topBar = {},
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .padding(paddingValues)
                // ★ 补状态栏高度：topBar 删掉后 paddingValues.top 变成 0，
                //   不加这一句内容会钻到状态栏/挖孔屏底下。
                .statusBarsPadding()
        ) {
            // ★★★ 闪退根因修复 ★★★
            //   底栏要采样「页面内容」做折射，所以这一层必须是【只含页面】的。
            //   之前 layerBackdrop 挂在外层 Box 上，而底栏也在那个 Box 里，
            //   于是底栏把自己也录进了采样源 → 绘制时递归采样自己 → StackOverflow 闪退。
            //   现在拆成：内层只包 AnimatedContent，底栏放在外层、在内层之外。
            // ★★★「给导航栏留空位」★★★
            //
            //   之前页面内容一直铺到屏幕最底，导航栏是浮在上面的，
            //   于是滚到最底部时最后一项（首页那张黄色卡片）被压在胶囊底下看不见。
            //
            //   苹果的做法是：内容视口在导航栏之上就结束，导航栏下面是一条固定的空白带。
            //   所以这里给内容加一个底部内缩 = 胶囊总高（56dp 栏 + 上下留白），
            //   列表滚到底时最后一项正好停在胶囊上方。
            //
            //   注意：layerBackdrop 仍挂在【整个】区域上，所以页面滑动时导航栏
            //   依然能采样到被它覆盖的内容（模糊/折射不会因此消失）。
            Box(modifier = Modifier.fillMaxSize().layerBackdrop(glassBackdrop)) {
            // 页面转场：按 index 差值判断方向，横向整页推移（iOS 式滑过效果）
            AnimatedContent(
                targetState = selectedTab,
                transitionSpec = {
                    val forward = targetState > initialState
                    val dir = if (forward) 1 else -1
                    // 距离越远，滑得越快（跨多页时不会拖沓）
                    val distance = abs(targetState - initialState).coerceAtLeast(1)
                    val duration = (320 - (distance - 1) * 40).coerceAtLeast(180)
                    (slideInHorizontally(
                        animationSpec = tween(duration, easing = FastOutSlowInEasing)
                    ) { full -> dir * full } + fadeIn(tween(duration)))
                        .togetherWith(
                            slideOutHorizontally(
                                animationSpec = tween(duration, easing = FastOutSlowInEasing)
                            ) { full -> -dir * full } + fadeOut(tween(duration))
                        )
                        // 页面尺寸相同，禁用尺寸动画避免裁剪抖动
                        .using(SizeTransform(clip = false))
                },
                // ★ 回退：不再给内容区做底部内缩。
                //   内缩会在导航栏下方露出一条 Scaffold 白底 —— 用户看到的「白边挡住主视角」
                //   就是它，而且白底上没有内容可采样，胶囊也就模糊不出来。
                //   现在页面重新铺满全屏，模糊有真实内容可采；
                modifier = Modifier.fillMaxSize(),
                label = "page_transition"
            ) { tab ->
                when (tab) {
                    0 -> HomeScreen(viewModel)
                    1 -> ProvidersScreen(viewModel)
                    2 -> ModelsScreen(viewModel)
                    3 -> ChatScreen(viewModel)
                    // 导航栏第 5 项 = 设置页（统计/管理/实例/关于已收进它的「功能」分组）
                    4 -> SettingsScreen(viewModel, onNavigate = { selectedTab = it })
                    // 以下为设置页内跳转的二级页面，不占导航栏位置
                    5 -> DataManagementScreen(viewModel)
                    6 -> AboutScreen(viewModel)
                    7 -> InstancesScreen(viewModel)
                    8 -> StatsScreen(viewModel)
                    9 -> LocalModelScreen(viewModel, onBack = { selectedTab = 4 })
                    else -> HomeScreen(viewModel)
                }
            }

            }

            // ★ 悬浮玻璃底栏：压在内容之上，内容从玻璃下面穿过去。
            //   胶囊四周看到的是页面本身，而不是 Scaffold 的纯色底
            //   —— 这就是之前那圈「白框」的来源。
            Box(modifier = Modifier.align(Alignment.BottomCenter)) {
                LiquidGlassBottomNav(
                    selectedTab = navSelected,
                    onTabSelected = { selectedTab = it },
                    items = listOf(
                        GlassNavItem(Icons.Outlined.Home, tr("nav_home")),
                        GlassNavItem(Icons.Outlined.Cloud, tr("nav_providers")),
                        GlassNavItem(Icons.Outlined.Memory, tr("nav_models")),
                        GlassNavItem(Icons.Outlined.ChatBubbleOutline, tr("nav_chat")),
                        GlassNavItem(Icons.Outlined.Settings, tr("nav_settings"))
                    ),
                    // ★ 背景采样已重新打开。
                    //   闪退的根因是「底栏自己在被采样的那一层里」，
                    //   结构已经拆开（见上面 layerBackdrop 那个内层 Box），
                    //   底栏是它的兄弟节点，采样安全，模糊/折射是真实的。
                    backdrop = glassBackdrop,
                    stretch = navStretch
                )
            }
        }
    }
}

// ============================================================
// 首页 —— 网关状态与启停控制
// ============================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(viewModel: GatewayViewModel) {
    val serviceRunning by viewModel.serviceRunning.collectAsState()
    val providers by viewModel.providers.collectAsState()
    val providerMap = remember(providers) { providers.associateBy { it.id } }

    // 群聊模式状态变量（需在函数级别，供对话框使用）
    val groupChatEnabled = remember { mutableStateOf(GroupChatManager.isEnabled()) }
    val groupChatSummarizer = remember { mutableStateOf(GroupChatManager.getSummarizerModel()) }
    val groupChatMaxRounds = remember { mutableStateOf(GroupChatManager.getMaxRounds().toString()) }
    var showGroupChatModelPicker by remember { mutableStateOf(false) }
    var showGroupChatSummarizerPicker by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            // navBottomPad：滚动内容底部留白，最后一项可停在胶囊上方
            .padding(bottom = 96.dp)
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
// ★★ 实时活跃模型显示（优化：减少while循环频率）★★
                 var activeModel by remember { mutableStateOf("") }
                 LaunchedEffect(serviceRunning) {
                     while (serviceRunning) {
                         activeModel = GatewayForegroundService.activeNodeName
                         delay(2000)  // 保持2秒轮询，避免高频重组
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
                        Text("", style = MaterialTheme.typography.labelSmall)
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
                        Text("", style = MaterialTheme.typography.labelSmall)
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

        // ★★ 自动故障转移开关 + 流水线测速看板 ★★
        val autoFailover by viewModel.autoFailover.collectAsState()
        val autoContinueOnLength = remember { mutableStateOf(GatewayForegroundService.getAutoContinueOnLength()) }
        val pStatus by pipelineStatus.collectAsState()
        val pRunning by pipelineRunning.collectAsState()
        val pProgress by pipelineProgress.collectAsState()
        val pCountdown by pipelineCountdown.collectAsState()
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

                // ★★ v3.19.0：输出截断自动续写开关 ★★
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            localizedText("输出截断自动续写", "Auto-continue on length"),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = localizedText(
                                "finish_reason=length 时自动带着已生成内容再请求一次，拿到完整输出",
                                "When finish_reason=length, automatically re-request with the generated content to get the full output"
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = autoContinueOnLength.value,
                        onCheckedChange = {
                            autoContinueOnLength.value = it
                            GatewayForegroundService.saveAutoContinueOnLength(it)
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
                         text = localizedText("测速", "Speed test"),
                         style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    com.qtwl.gateway.ui.components.IosButton(
                        text = if (pRunning) localizedText("停止", "Stop") else localizedText("启动", "Start"),
                        onClick = {
                            if (pRunning) viewModel.stopPipelineTest()
                            else viewModel.startPipelineTest()
                        },
                        filled = !pRunning
                    )
                }

                // ★★ 自动测速间隔设置（滑块，5分钟~4小时）★★
                var intervalMinutes by remember { mutableStateOf(GatewayForegroundService.getPipelineInterval()) }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = localizedText("测速间隔", "Speed interval"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(80.dp)
                    )
                    com.qtwl.gateway.ui.components.IosSlider(
                        value = intervalMinutes.toFloat(),
                        onValueChange = { newVal ->
                            val rounded = newVal.toInt().coerceIn(5, 240)
                            intervalMinutes = rounded
                        },
                        onValueChangeFinished = {
                            GatewayForegroundService.savePipelineInterval(intervalMinutes)
                            // 测速正在运行时自动重启使新间隔生效
                            if (pRunning) {
                                viewModel.stopPipelineTest()
                                viewModel.startPipelineTest()
                            }
                        },
                        valueRange = 5f..240f,
                        steps = 46, // 5,10,15,20...240 = 47个点, steps=46
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = when {
                            intervalMinutes < 60 -> localizedText("${intervalMinutes}分钟", "${intervalMinutes}min")
                            intervalMinutes % 60 == 0 -> localizedText("${intervalMinutes / 60}小时", "${intervalMinutes / 60}h")
                            else -> localizedText("${intervalMinutes / 60}小时${intervalMinutes % 60}分", "${intervalMinutes / 60}h${intervalMinutes % 60}m")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.width(70.dp)
                    )
                }

                // ★★ 测速倒计时显示 ★★
                if (pCountdown > 0 && !pRunning) {
                    val mins = pCountdown / 60
                    val secs = pCountdown % 60
                    Text(
                        text = "${localizedText("下一轮测速", "Next speed test in")}: ${"%02d:%02d".format(mins, secs)}",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }

                // ★★ 指示灯 + 框1 + 框2 ★★
                // ★★ 排行榜显示优化：显示所有已启用模型（无需测速）；已测速的排前（按速度），未测速(⏳等待中)排后 ★★
                // 测速归测速、显示归显示、删除归删除 —— 只要模型存在于数据库即上排行，测速只影响排序不决定是否显示
                val doneItems = pStatus.sortedWith(
                    compareBy<com.qtwl.gateway.ui.viewmodel.PipelineTestItem> {
                        !PipelineStatusMark.isDone(it.status)
                    }.thenBy { it.latencyMs }
                )
                val forcedModelKey by viewModel.forcedModelKey.collectAsState()
                val hasReadyModel = doneItems.any { PipelineStatusMark.isOk(it.status) }
                val allFailed = doneItems.isNotEmpty() && doneItems.all { PipelineStatusMark.isFail(it.status) }

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
                val currentRankedItem = if (forcedModelKey.isNotBlank()) {
                    doneItems.find { it.selectionKey == forcedModelKey }
                } else {
                    doneItems.firstOrNull()
                }
                if (currentRankedItem != null) {
                    val currentRank = doneItems.indexOfFirst { it.selectionKey == currentRankedItem.selectionKey } + 1
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            localizedText("当前AI助手模型", "Current AI assistant model"),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "#$currentRank · P${GatewayForegroundService.getProviderDisplayId(currentRankedItem.providerId)} · ${currentRankedItem.modelName}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                // ★★ 正在测速状态（仅测速运行时显示，精简不重复）★★
                val currentItem = pStatus.find { it.isCurrent && !PipelineStatusMark.isDone(it.status) }
                if (pRunning && currentItem != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = MessageMark.WARN + "${localizedText("测速中", "Testing speed")}: ${currentItem.modelName}",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                // ★★ 框1：全部已启用模型排行榜（已测速的按速度排前，未测速的排后）★★
                if (doneItems.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(localizedText("模型排行榜（点击加入/移出强制故障池）", "Model ranking (tap to add/remove from forced failover pool)"), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
                        color = Online)
                    Spacer(modifier = Modifier.height(4.dp))
// ★ 显示强制故障池指示
                    val forcedPoolBanner by viewModel.forcedPool.collectAsState()
                    val forcedPoolSet = remember(forcedPoolBanner) { forcedPoolBanner.toSet() }
                    if (forcedPoolBanner.isNotEmpty()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${localizedText("强制故障池", "Forced failover pool")} (${forcedPoolBanner.size}): " +
                                    forcedPoolBanner.joinToString(" | ") { key ->
                                        val item = doneItems.find { it.selectionKey == key }
                                        if (item != null) "P${GatewayForegroundService.getProviderDisplayId(item.providerId)}·${item.modelName}" else ModelRouteKey.modelIdOf(key)
                                    },
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            TextButton(onClick = { viewModel.clearForcedPool() }) {
                                Text(localizedText("清空", "Clear"), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                        LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        itemsIndexed(
                            items = doneItems,
                            key = { _, item -> item.selectionKey }
                        ) { index, item ->
                            val isSelected = item.selectionKey in forcedPoolSet
                            val providerName = providerMap[item.providerId]?.name
                                ?: localizedText("未知服务商", "Unknown provider")
                            Card(
                                modifier = Modifier.fillMaxWidth()
                                    .clickable { viewModel.toggleForcedPool(item.modelId, item.providerId) }
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
                                            text = "#${index + 1} · P${GatewayForegroundService.getProviderDisplayId(item.providerId)} · ",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                                        )
                                        if (isSelected) {
                                            Text("", style = MaterialTheme.typography.bodySmall)
                                        }
                                        Text(
                                            text = item.modelName,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                    // 第二行：服务商名 · modelId
                                    Text(
                                        text = "$providerName · ${item.modelId}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    // 第三行：测速指标 + 状态
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        val statusText = item.status
                                        val isError = PipelineStatusMark.isFail(statusText)
                                        val isSuccess = PipelineStatusMark.isOk(statusText)
                                        if (isSuccess) {
                                            // 提取 TTFT/TPS 数字
                                            val ttftMatch = Regex("TTFT=(\\d+)ms").find(statusText)
                                            val tpsMatch = Regex("TPS=([\\d.]+)").find(statusText)
                                            val latencyMatch = Regex("(\\d+)ms$").find(statusText)
                                            if (ttftMatch != null) {
                                                Text("${ttftMatch.groupValues[1]}ms", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                Spacer(modifier = Modifier.width(8.dp))
                                            }
                                            if (tpsMatch != null) {
                                                Text("${tpsMatch.groupValues[1]} tok/s", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                Spacer(modifier = Modifier.width(8.dp))
                                            }
                                            if (latencyMatch != null) {
                                                Text("${latencyMatch.groupValues[1]}ms", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                            Spacer(modifier = Modifier.weight(1f))
                                            Icon(imageVector = AppIcons.get("check_circle"), contentDescription = null, tint = Online, modifier = Modifier.size(14.dp))
                                        } else if (isError) {
                                            Spacer(modifier = Modifier.weight(1f))
                                            Icon(imageVector = AppIcons.get("error"), contentDescription = localizedText("不可用", "Unavailable"), tint = Error, modifier = Modifier.size(14.dp))
                                        } else {
                                            Spacer(modifier = Modifier.weight(1f))
                                            Text(localizeRuntimeText(PipelineStatusMark.strip(statusText)), style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else if (!pRunning && pStatus.isNotEmpty()) {
                    Text(
                        text = localizedText("测速排队中，请点击「启动」开始测速", "Speed test queued. Tap Start to begin"),
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

        // ★★ 实时会话跑马灯（优化：水平滚动由 InfiniteTransition 动画驱动，会话列表低频刷新，避免全页每800ms重组卡顿）★★
        val currentSessions by viewModel.quickLiveSessions.collectAsState()
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
                        Text(localizedText("实时会话", "Live sessions"), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        TextButton(onClick = { viewModel.clearLiveSessions() }) { Text(localizedText("清空", "Clear"), style = MaterialTheme.typography.labelSmall) }
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
                                    color = if (session.status.contains("回复")) Online else MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(4.dp))
                                // 模型名
                                Text(session.modelName.take(12), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 70.dp))
                                Spacer(Modifier.width(4.dp))
                                // ★★ 合并发送+回复内容为一整行跑马灯 ★★
                                val marqueeText = buildString {
                                    if (session.requestPreview.isNotBlank()) {
                                        append(localizedText("我：", "Me: ") + session.requestPreview)
                                    }
                                    if (session.requestPreview.isNotBlank() && session.responsePreview.isNotBlank()) {
                                        append(" → ")
                                    }
                                    if (session.responsePreview.isNotBlank()) {
                                        append("AI：${session.responsePreview}")
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
                    Text(localizedText("思考引导", "Thinking guide"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
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
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Group, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(localizedText("群聊模式", "Group chat mode"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
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
                        Text(localizedText("从测速排行榜选择模型 (", "Select models from speed ranking (") + GroupChatManager.getParticipantModels().size + localizedText("个)", ")"))
                    }
                    Spacer(modifier = Modifier.height(4.dp))
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
                        Text(localizedText("选择总结模型", "Select summary model"))
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedTextField(value = groupChatMaxRounds.value, onValueChange = { v ->
                        groupChatMaxRounds.value = v
                        v.toIntOrNull()?.let { GroupChatManager.setMaxRounds(it) }
                    }, label = { Text(localizedText("轮次", "Rounds")) }, singleLine = true, modifier = Modifier.width(100.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))

        // ★★ 强制故障多转移绑定管理卡片 ★★
        val forcedPoolCard by viewModel.forcedPool.collectAsState()
        // ★★ 当前活跃模型（用于点亮池内灯，2秒轮询响应式）★★
        var activeNodeKey by remember { mutableStateOf("") }
        LaunchedEffect(Unit) {
            while (true) {
                activeNodeKey = com.qtwl.gateway.data.model.ModelRouteKey.encode(
                    com.qtwl.gateway.service.GatewayForegroundService.activeNodeProviderId,
                    com.qtwl.gateway.service.GatewayForegroundService.activeNodeName
                )
                delay(2000)
            }
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Sync, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(localizedText("强制故障多转移绑定", "Forced failover multi-binding"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(localizedText("● 灯亮=当前使用模型，点灯可手动切换；▲▼ 调顺序（序号=切换优先级）", "● Lit=current model, tap light to switch; ▲▼ reorder (=priority)"),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (forcedPoolCard.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(localizedText("已绑定 (${forcedPoolCard.size}):", "Bound (${forcedPoolCard.size}):"), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    forcedPoolCard.forEachIndexed { idx, key ->
                        val item = pStatus.find { it.selectionKey == key }
                        val displayName = if (item != null) "P${item.providerId}·${item.modelName}" else ModelRouteKey.modelIdOf(key)
                        val isActive = key == activeNodeKey
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // ★★ 灯：当前活跃=绿色实心可点（手动切换），不活跃=灰色 ★★
                            val lightColor = if (isActive) Online else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                            Canvas(
                                modifier = Modifier.size(14.dp)
                                    .clickable {
                                        if (item != null) viewModel.forceModel(item.modelId, item.providerId)
                                    }
                                    .padding(2.dp)
                            ) {
                                drawCircle(color = lightColor)
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("${idx + 1}. ", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            Text(
                                text = displayName,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                                color = if (isActive) Online else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                            IconButton(
                                onClick = { viewModel.moveForcedPoolUp(idx) },
                                enabled = idx > 0
                            ) { Text("▲", style = MaterialTheme.typography.labelSmall) }
                            IconButton(
                                onClick = { viewModel.moveForcedPoolDown(idx) },
                                enabled = idx < forcedPoolCard.size - 1
                            ) { Text("▼", style = MaterialTheme.typography.labelSmall) }
                        }
                    }
                    TextButton(onClick = {
                        viewModel.clearForcedPool()
                    }) {
                        Text(localizedText("清空全部", "Clear all"), style = MaterialTheme.typography.labelSmall)
                    }
                    // ★ 调度说明 + 预热按钮
                    Text(localizedText("模型自由调度：池内模型后台自动预热探活，故障时毫秒级切换到下一个可用模型，被调用APP无感知", "Free scheduling: pool models are pre-warmed in background; on failure the gateway switches to the next available model in ms, invisible to the calling APP"),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { viewModel.precheckForcedPoolModels() }) {
                        Text(localizedText("立即预热池内模型", "Pre-warm pool models now"), style = MaterialTheme.typography.labelSmall)
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
                    text = localizedText("使用说明", "Usage guide"),
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
                Text("", style = MaterialTheme.typography.titleLarge)
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = localizedText("请确保手机与目标设备在同一局域网内，\n且防火墙未阻止 8889 端口", "Make sure the phone and target device are on the same LAN,\nand that the firewall is not blocking port 8889"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }

    // ★ 参与模型选择弹窗（排行榜列表勾选）★★
    if (showGroupChatModelPicker) {
        val snapshotModels = remember(viewModel) {
            viewModel.enabledModels.value.ifEmpty {
                listOf<com.qtwl.gateway.data.model.AiModel>()
            }
        }
        val snapshotSortedIds = remember { com.qtwl.gateway.gateway.GatewayScheduler.pipelineSortedModelKeys.toList() }
        val sortedModels = remember {
            snapshotModels.sortedByDescending { snapshotSortedIds.indexOf(it.routeKey) }.reversed()
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
                            val isSelected = model.routeKey in currentParticipants
                            val rank = snapshotSortedIds.indexOf(model.routeKey)
                            val rankStr = if (rank >= 0) " #${rank + 1}" else ""
                            Row(modifier = Modifier.fillMaxWidth().clickable {
                                if (isSelected) currentParticipants.remove(model.routeKey)
                                else currentParticipants.add(model.routeKey)
                            }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = isSelected, onCheckedChange = { c ->
                                    if (c) currentParticipants.add(model.routeKey)
                                    else currentParticipants.remove(model.routeKey)
                                })
                                Spacer(Modifier.width(4.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("P${model.providerId}·${model.modelId}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
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
        val snapshotModels = remember(viewModel) {
            viewModel.enabledModels.value.ifEmpty {
                listOf<com.qtwl.gateway.data.model.AiModel>()
            }
        }
        val snapshotSortedIds = remember { com.qtwl.gateway.gateway.GatewayScheduler.pipelineSortedModelKeys.toList() }
        val sortedModels = remember {
            snapshotModels.sortedByDescending { snapshotSortedIds.indexOf(it.routeKey) }.reversed()
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
                        item {
                            Row(modifier = Modifier.fillMaxWidth().clickable { selectedSummarizer = "" }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = selectedSummarizer == "", onClick = { selectedSummarizer = "" })
                                Spacer(Modifier.width(4.dp))
                                Text(localizedText("无总结者", "No summarizer"), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        item { HorizontalDivider() }
                        items(sortedModels) { model ->
                            val rank = snapshotSortedIds.indexOf(model.routeKey)
                            val rankStr = if (rank >= 0) " #${rank + 1}" else ""
                            Row(modifier = Modifier.fillMaxWidth().clickable { selectedSummarizer = model.routeKey }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = selectedSummarizer == model.routeKey, onClick = { selectedSummarizer = model.routeKey })
                                Spacer(Modifier.width(4.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("P${model.providerId}·${model.modelId}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
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
                text = { Text(tr("add_provider")) },
                // ★ 底部避让：玻璃导航悬浮层会盖住 FAB，给 bottom padding 抬升
                modifier = Modifier.navigationBarsPadding().padding(bottom = 76.dp)
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
                    Text("", fontSize = MaterialTheme.typography.displayLarge.fontSize)
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
                    ProviderCard(
                        provider = provider,
                        onToggleEnabled = { viewModel.toggleProviderEnabled(provider) },
                        onEdit = { viewModel.showEditProvider(provider) },
                        onDelete = { viewModel.deleteProvider(provider) },
                        onSync = { viewModel.syncModels(provider) },
                        onMoveUp = if (index > 0) {{ viewModel.moveProvider(provider, -1) }} else null,
                        onMoveDown = if (index < providers.sortedBy { it.orderIndex }.size - 1) {{ viewModel.moveProvider(provider, 1) }} else null
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
    onSync: () -> Unit,
    onMoveUp: (() -> Unit)? = null,
    onMoveDown: (() -> Unit)? = null,
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
                        text = "P${provider.id}·${localizeGeneratedName(provider.name)}",
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
                    // ★ 上移/下移按钮（点击式，不抢列表滚动手势）
                    if (onMoveUp != null) {
                        IconButton(onClick = onMoveUp, modifier = Modifier.size(30.dp)) {
                            Text("▲")
                        }
                    }
                    if (onMoveDown != null) {
                        IconButton(onClick = onMoveDown, modifier = Modifier.size(30.dp)) {
                            Text("▼")
                        }
                    }
                    IconButton(onClick = onToggleEnabled, modifier = Modifier.size(36.dp)) {
                        Text(
                            if (provider.isEnabled) "" else "",
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
@OptIn(ExperimentalMaterial3Api::class)
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
                val finalUrl = remember(form.baseUrl, form.chatPath) {
                    val base = form.baseUrl.trimEnd('/')
                    val path = form.chatPath.ifBlank { "/v1/chat/completions" }
                    if (base.startsWith("http")) "$base$path" else ""
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
                            Text("${tr("final_url")}: $finalUrl", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
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
                // 对话接口路径
                val apiPathOptions = listOf("/v1/chat/completions", "/v1/messages", "/v1/completions", "/v1/embeddings", "/v1/rerank", "/v1/moderations", "/v1/audio/speech", "/v1/images/generations", "/v1/videos", "/chat/completions", "/completions", "/generate")
                var chatPathExpanded by remember { mutableStateOf(false) }
                var chatPathText by remember(form.chatPath) { mutableStateOf(form.chatPath) }
                ExposedDropdownMenuBox(expanded = chatPathExpanded, onExpandedChange = { chatPathExpanded = it }) {
                    OutlinedTextField(
                        value = chatPathText,
                        onValueChange = { v -> chatPathText = v; viewModel.updateFormField("chatPath", v); chatPathExpanded = true },
                        label = { Text(localizedText("对话接口路径（留空自动拼接）", "Chat API path (blank = auto-append)")) },
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
                
                // 自定义ID
                OutlinedTextField(
                    value = form.customId,
                    onValueChange = { viewModel.updateFormField("customId", it) },
                    label = { Text(localizedText("自定义ID（pID显示用，留空=用数据库ID）", "Custom ID (pID display, blank=default)")) },
                    placeholder = { Text(localizedText("如: P001", "e.g.: P001")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                
                // 提示信息
                if (selectedIndex != 4) {
                    Text(
                        text = localizedText("已自动填充对应类型的默认配置，你可手动修改", "Default configuration for this type has been filled automatically. You can edit it manually"),
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
    var chatPath by remember { mutableStateOf(provider.chatPath ?: "") }
    var customId by remember { mutableStateOf(GatewayForegroundService.getProviderCustomId(provider.id)) }
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
                            val finalUrl = baseUrl.trimEnd('/') + (chatPath.ifBlank { "/v1/chat/completions" })
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
                // 对话接口路径
                val apiPathOptions = listOf("/v1/chat/completions", "/v1/messages", "/v1/completions", "/v1/embeddings", "/v1/rerank", "/v1/moderations", "/v1/audio/speech", "/v1/images/generations", "/v1/videos", "/chat/completions", "/completions", "/generate")
                var chatPathExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded = chatPathExpanded, onExpandedChange = { chatPathExpanded = it }) {
                    OutlinedTextField(
                        value = chatPath,
                        onValueChange = { v -> chatPath = v; chatPathExpanded = true },
                        label = { Text(localizedText("对话接口路径（留空自动拼接）", "Chat API path (blank = auto-append)")) },
                        placeholder = { Text("/v1/chat/completions") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = chatPathExpanded) }
                    )
                    ExposedDropdownMenu(expanded = chatPathExpanded, onDismissRequest = { chatPathExpanded = false }) {
                        val filtered = apiPathOptions.filter { chatPath.isBlank() || it.contains(chatPath, ignoreCase = true) }
                        filtered.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option, style = MaterialTheme.typography.bodyMedium) },
                                onClick = { chatPath = option; chatPathExpanded = false }
                            )
                        }
                    }
                }
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
                
                // 自定义ID
                OutlinedTextField(
                    value = customId,
                    onValueChange = { customId = it },
                    label = { Text(localizedText("自定义ID（pID显示用，留空=用数据库ID）", "Custom ID (pID display, blank=default)")) },
                    placeholder = { Text(localizedText("如: P001", "e.g.: P001")) },
                    singleLine = true,
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
                        apiKey = apiKey.ifBlank { null },
                        chatPath = chatPath.ifBlank { null }
                    )
                )
                GatewayForegroundService.saveProviderCustomId(provider.id, customId)
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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreen(viewModel: GatewayViewModel) {
    val languageTick = TranslationManager.currentLanguageFlow.collectAsState().value
    val context = LocalContext.current
    val models by viewModel.models.collectAsState()
    val providers by viewModel.providers.collectAsState()
    val syncingProviderId by viewModel.syncingProviderId.collectAsState()
    val syncResult by viewModel.syncResult.collectAsState()
    val editModelDialogModel by viewModel.showEditModelDialog.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    var filterToolCall by remember { mutableStateOf(false) }
    var filterVision by remember { mutableStateOf(false) }
    var showManualAddModel by remember { mutableStateOf(false) }

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
            AiModel(id = -1, modelId = "qtai-sj", displayName = localizedText("自动化切换", "Auto switch"), providerId = 0, isEnabled = true)
        ) + fromDb
    }

    // 按服务商分组（按服务商 orderIndex 排序）
    val groupedModels: List<Pair<String, List<AiModel>>> = remember(filteredModels, providers, languageTick) {
        val providersById = providers.associateBy { it.id }
        val providerOrder = providers.sortedBy { it.orderIndex }.map { it.id }
        filteredModels
            .groupBy { it.providerId }
            .entries
            .sortedBy { (providerId, _) ->
                providerOrder.indexOf(providerId).let { if (it < 0) Int.MAX_VALUE else it }
            }
            .map { (providerId, modelList) ->
                val providerName = providersById[providerId]?.name
                    ?: if (providerId == 0L) localizedText("自动切换", "Auto switch")
                    else localizedText("未知服务商", "Unknown provider")
                "P$providerId · $providerName" to modelList
            }
    }

    if (models.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("", fontSize = MaterialTheme.typography.displayLarge.fontSize)
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
                        text = localizedText("在「服务商」页面点击同步按钮同步模型", "Tap Sync on the Providers page to sync models"),
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
                    label = { Text("${tr("search_model")}") },
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
                    label = { Text("") }
                )
                FilterChip(
                    selected = filterVision,
                    onClick = { filterVision = !filterVision },
                    label = { Text("") }
                )
            }
        }
        // 同步结果提示
            syncResult?.let { result ->
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (MessageMark.isOk(result))
                                Online.copy(alpha = 0.15f)
                            else if (MessageMark.isFail(result))
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
                                text = localizeRuntimeText(MessageMark.strip(result)),
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

            // 批量测速按钮 + 手动添加模型按钮
            item {
                Column {
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
                                Text(localizedText("批量测速(自动开启)", "Batch speed test (auto-enable)"))
                            }
                        }
                        // ★★ 手动添加模型按钮 ★★
                        OutlinedButton(
                            onClick = { showManualAddModel = true },
                            enabled = providers.isNotEmpty()
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(localizedText("手动添加", "Add manually"))
                        }
                    }
                    // 🔇 错误自动关闭开关
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("错误自动关闭", style = MaterialTheme.typography.bodyMedium)
                        Spacer(modifier = Modifier.weight(1f))
                        val autoClose by viewModel.batchTestingAutoClose.collectAsState()
                        Switch(
                            checked = autoClose,
                            onCheckedChange = { viewModel.setBatchTestingAutoClose(it) }
                        )
                    }

                    // ★★ v3.19.0：批量探测模型最大输出长度 ★★
                    var probingMaxOutput by remember { mutableStateOf(false) }
                    val probeScope = rememberCoroutineScope()
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                localizedText("探测最大输出", "Probe max output"),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                localizedText(
                                    "二分探测各模型真实最大输出 tokens，结果用于自动续写",
                                    "Binary-probe each model's real max output tokens, used by auto-continue"
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (probingMaxOutput) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            TextButton(
                                onClick = {
                                    probingMaxOutput = true
                                    probeScope.launch {
                                        try {
                                            val prober = com.qtwl.gateway.utils.ModelMaxOutputProber()
                                            val modelsToProbe = models.filter { it.isEnabled && it.providerId != 0L }
                                            for (m in modelsToProbe) {
                                                val prov = providers.find { it.id == m.providerId } ?: continue
                                                prober.probe(m.modelId, prov.resolvedBaseUrl, prov.apiKey, prov.chatPath)
                                            }
                                            android.widget.Toast.makeText(
                                                context,
                                                localizeRuntimeText("探测完成，共 ${modelsToProbe.size} 个模型"),
                                                android.widget.Toast.LENGTH_SHORT
                                            ).show()
                                        } finally {
                                            probingMaxOutput = false
                                        }
                                    }
                                },
                                enabled = providers.isNotEmpty()
                            ) {
                                Text(localizedText("开始探测", "Start probe"))
                            }
                        }
                    }
                }
            }
// 按服务商分组显示模型
            groupedModels.forEach { (providerLabel, modelList) ->
                item {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "$providerLabel",
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
                Spacer(modifier = Modifier.height(16.dp))
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

    // ★★ 手动添加模型对话框 ★★
    if (showManualAddModel) {
        var selectedProviderId by remember { mutableStateOf(providers.firstOrNull()?.id ?: 0L) }
        var newModelId by remember { mutableStateOf("") }
        var newModelName by remember { mutableStateOf("") }
        var addResult by remember { mutableStateOf<String?>(null) }
        var providerExpanded by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { showManualAddModel = false },
            title = { Text(localizedText("手动添加模型", "Add model manually"), fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // 选择服务商
                    ExposedDropdownMenuBox(expanded = providerExpanded, onExpandedChange = { providerExpanded = it }) {
                        val selectedProvider = providers.find { it.id == selectedProviderId }
                        OutlinedTextField(
                            value = selectedProvider?.name ?: "",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(localizedText("选择服务商", "Select provider")) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = providerExpanded) },
                            modifier = Modifier.fillMaxWidth().menuAnchor(),
                            singleLine = true
                        )
                        ExposedDropdownMenu(expanded = providerExpanded, onDismissRequest = { providerExpanded = false }) {
                            providers.forEach { provider ->
                                DropdownMenuItem(
                                    text = { Text("P${provider.id} · ${provider.name}") },
                                    onClick = { selectedProviderId = provider.id; providerExpanded = false }
                                )
                            }
                        }
                    }

                    // 模型ID输入
                    OutlinedTextField(
                        value = newModelId,
                        onValueChange = { newModelId = it },
                        label = { Text(localizedText("模型ID", "Model ID")) },
                        placeholder = { Text("gpt-4o, deepseek-chat, ...") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // 显示名称（可选）
                    OutlinedTextField(
                        value = newModelName,
                        onValueChange = { newModelName = it },
                        label = { Text(localizedText("显示名称 (可选)", "Display name (optional)")) },
                        placeholder = { Text(newModelId.ifBlank { localizedText("留空则使用模型ID", "Leave empty to use model ID") }) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // 结果提示
                    addResult?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall,
                            color = if (MessageMark.isOk(it)) Online else Error)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newModelId.isNotBlank()) {
                            viewModel.manualAddModel(selectedProviderId, newModelId.trim(), newModelName.trim()) { success, msg ->
                                addResult = msg
                                if (success) {
                                    newModelId = ""
                                    newModelName = ""
                                }
                            }
                        }
                    },
                    enabled = newModelId.isNotBlank()
                ) {
                    Text(localizedText("添加", "Add"))
                }
            },
            dismissButton = { TextButton(onClick = { showManualAddModel = false }) { Text(localizedText("关闭", "Close")) } }
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
                        Text(if (brainModelId.isNotBlank()) "${ModelRouteKey.display(brainModelId)}" else localizedText("绑定", "Bind"), style = MaterialTheme.typography.labelSmall)
                    }
                    if (showBrainPicker) {
                        val enabledModels by viewModel.enabledModels.collectAsState()
                        AlertDialog(
                            onDismissRequest = { showBrainPicker = false },
                            title = { Text(tr("select_brain")) },
                            text = { LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                                item { TextButton(onClick = { com.qtwl.gateway.service.GatewayForegroundService.saveQtaiSjBrain(""); showBrainPicker = false }) { Text(tr("clear_brain")) } }
                                items(enabledModels) { m ->
                                    val isSelected = m.routeKey == brainModelId
                                    Row(modifier = Modifier.fillMaxWidth().clickable {
                                        com.qtwl.gateway.service.GatewayForegroundService.saveQtaiSjBrain(m.routeKey); showBrainPicker = false
                                    }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        RadioButton(selected = isSelected, onClick = {
                                            com.qtwl.gateway.service.GatewayForegroundService.saveQtaiSjBrain(m.routeKey); showBrainPicker = false
                                        })
                                        Spacer(Modifier.width(8.dp))
                                        Text("P${m.providerId}·${m.modelId}", fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
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
                        Text(if (model.useProxy) "" else "", style = MaterialTheme.typography.labelLarge)
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