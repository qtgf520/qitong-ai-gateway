package com.qtwl.gateway.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.qtwl.gateway.ui.viewmodel.GatewayViewModel
import com.qtwl.gateway.utils.LocalLlmEngine
import com.qtwl.gateway.utils.LocalModelManager
import com.qtwl.gateway.utils.AgentSettings
import com.qtwl.gateway.utils.localizedText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 本地模型页
 *
 * 流程：下载（多源 + 续传 + 速度/剩余时间/暂停） → 加载 → 对话
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalModelScreen(
    viewModel: GatewayViewModel = viewModel(factory = GatewayViewModel.Factory()),
    onBack: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val states by LocalModelManager.downloadStates.collectAsState()
    val engineState by LocalLlmEngine.state.collectAsState()

    var refreshTick by remember { mutableStateOf(0) }
    val downloaded = remember(refreshTick) { LocalModelManager.downloadedModels(context) }
    val downloadedIds = downloaded.map { it.id }.toSet()
    val usedBytes = remember(refreshTick) { LocalModelManager.usedBytes(context) }

    // 对话测试区
    var testInput by remember { mutableStateOf("") }
    var testOutput by remember { mutableStateOf("") }
    var generating by remember { mutableStateOf(false) }

    // 进入页面时清理孤儿缓存（对应模型已删除的 xnnpack 缓存）
    var cleanedMb by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        val freed = LocalModelManager.cleanOrphanCaches(context)
        if (freed > 0) {
            cleanedMb = freed / 1024 / 1024
            refreshTick++
        }
    }

    // 运行时完整性检查：缺 .so 时直接给出可诊断的提示，
    // 而不是等用户点「加载」再收到一句「推理引擎缺失」。
    val missingLibs = remember(refreshTick) { LocalLlmEngine.missingLibs(context) }

    // 下载完成后自动刷新
    LaunchedEffect(states) {
        if (states.values.any { !it.running && it.downloaded > 0 && it.downloaded == it.total }) {
            refreshTick++
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(localizedText("本地模型", "Local models")) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                // navBottomPad：滚动内容底部留白，最后一项可停在胶囊上方
                .padding(bottom = 96.dp)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ---- 顶部说明 ----
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                )
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.primary)
                    Column {
                        Text(
                            localizedText("完全免费、无需账号", "Free, no account needed"),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            localizedText(
                                "模型在手机本地推理，不联网、不消耗任何额度。下载后点「加载并使用」，就能在聊天页直接选择本地模型对话。",
                                "Models run on-device — no network, no quota. Once downloaded you can chat directly, and it also works as a fallback provider for the gateway."
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // ---- 运行时缺失提示 ----
            // 缺 .so 时功能一定不可用，提前告知具体缺哪些文件
            if (missingLibs.isNotEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)
                    )
                ) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text(
                            localizedText(
                                "推理引擎不完整，本地模型暂不可用",
                                "Inference engine incomplete — local models unavailable"
                            ),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            localizedText(
                                "安装包缺少 ${missingLibs.size} 个原生库：",
                                "Missing ${missingLibs.size} native libraries: "
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                        Text(
                            missingLibs.joinToString("、"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        Text(
                            localizedText(
                                "需要用包含完整 jniLibs 的安装包重新构建。",
                                "Rebuild with a complete jniLibs set."
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }

            // ---- 孤儿缓存清理提示 ----
            if (cleanedMb > 0) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.6f)
                    )
                ) {
                    Text(
                        localizedText(
                            "已清理 ${cleanedMb} MB 无效缓存（对应模型已删除）",
                            "Cleaned ${cleanedMb} MB of orphan cache"
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }

            // ---- 统计 ----
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    localizedText("已下载 ${downloaded.size} 个", "${downloaded.size} downloaded"),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    localizedText("占用 ${fmtBytes(usedBytes)}", "Using ${fmtBytes(usedBytes)}"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            HorizontalDivider()

            // ---- 模型列表 ----
            LocalModelManager.CATALOG.forEach { spec ->
                val st = states[spec.id]
                val isDone = spec.id in downloadedIds
                val isLoaded = engineState.ready && engineState.modelId == spec.id

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = when {
                            isLoaded -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                            isDone -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
                            else -> MaterialTheme.colorScheme.surface
                        }
                    )
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // 标题行
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        spec.displayName,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    if (spec.recommend) {
                                        Spacer(Modifier.width(6.dp))
                                        Badge(containerColor = MaterialTheme.colorScheme.primary) {
                                            Text(
                                                localizedText("推荐", "Recommended"),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onPrimary,
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                            )
                                        }
                                    }
                                }
                                Text(
                                    "${spec.paramsB} · ${spec.quant} · ${fmtBytes(spec.sizeBytes)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Icon(
                                if (isLoaded) Icons.Default.CheckCircle else Icons.Default.Memory,
                                null,
                                tint = if (isLoaded) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Text(spec.description, style = MaterialTheme.typography.bodySmall)

                        // 下载进度
                        if (!isDone && st != null && (st.running || st.paused || st.downloaded > 0)) {
                            LinearProgressIndicator(
                                progress = { st.percent / 100f },
                                modifier = Modifier.fillMaxWidth().height(6.dp),
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    "${st.percent}%  ${fmtBytes(st.downloaded)} / ${fmtBytes(st.total)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    buildString {
                                        if (st.running && st.speedBps > 0) {
                                            append(fmtSpeed(st.speedBps))
                                            val eta = st.etaSeconds
                                            if (eta > 0) append("  ·  ").append(fmtEta(eta))
                                        } else if (st.paused) {
                                            append(localizedText("已暂停", "Paused"))
                                        }
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            if (st.sourceIndex > 0) {
                                Text(
                                    localizedText(
                                        "已切换到备用源 ${st.sourceIndex + 1}",
                                        "Switched to backup source ${st.sourceIndex + 1}"
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.tertiary
                                )
                            }
                        }

                        st?.error?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }

                        // 引擎加载状态
                        if (engineState.loading && engineState.modelId == spec.id) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    localizedText("正在加载模型…", "Loading model…"),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                        if (engineState.modelId == spec.id && engineState.error != null) {
                            Text(
                                localizedText("加载失败：", "Load failed: ") + engineState.error,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }

                        // 操作按钮
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            when {
                                !isDone -> {
                                    if (st?.running == true) {
                                        Button(
                                            onClick = { LocalModelManager.pauseDownload(spec) },
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Icon(Icons.Default.Pause, null, Modifier.size(18.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text(localizedText("暂停", "Pause"))
                                        }
                                    } else {
                                        Button(
                                            onClick = {
                                                LocalModelManager.startDownload(context, scope, spec)
                                            },
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Icon(Icons.Default.Download, null, Modifier.size(18.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text(
                                                if (st != null && st.downloaded > 0)
                                                    localizedText("继续", "Resume")
                                                else localizedText("下载", "Download")
                                            )
                                        }
                                    }
                                }
                                else -> {
                                    Button(
                                        onClick = {
                                            scope.launch {
                                                // 优先用该模型推荐的预算，避免小模型被过大的上下文拖慢
                                                // 修复：旧版用 maxOf，会把 AgentSettings.contextTokens
                                                //   强行抬到模型推荐值以上，0.5B 模型被塞 4096 导致极慢。
                                                //   改为按模型推荐值封顶，用户设置只起收缩作用。
                                                val tokens = minOf(
                                                    AgentSettings.contextTokens,
                                                    spec.recommendedTokens
                                                )
                                                val result = LocalLlmEngine.load(context, spec, tokens)
                                                // 加载成功后自动登记到聊天模型列表，无需再手动配置
                                                if (result.isSuccess) {
                                                    viewModel.registerLocalModelProvider(
                                                        spec.id, spec.displayName
                                                    )
                                                }
                                            }
                                        },
                                        enabled = !engineState.loading,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            when {
                                                engineState.loading -> localizedText("加载中…", "Loading…")
                                                isLoaded -> localizedText("已就绪", "Ready")
                                                else -> localizedText("加载并使用", "Load & use")
                                            }
                                        )
                                    }
                                }
                            }

                            if (isDone) {
                                OutlinedButton(
                                    onClick = {
                                        if (engineState.modelId == spec.id) LocalLlmEngine.release()
                                        LocalModelManager.delete(context, spec)
                                        refreshTick++
                                    }
                                ) {
                                    Icon(Icons.Default.Delete, null, Modifier.size(18.dp))
                                }
                            } else if (st != null && st.downloaded > 0) {
                                OutlinedButton(
                                    onClick = {
                                        LocalModelManager.delete(context, spec)
                                        refreshTick++
                                    }
                                ) {
                                    Icon(Icons.Default.Delete, null, Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
            }

            HorizontalDivider()

            // ---- Agent 与上下文设置 ----
            var agentOn by remember { mutableStateOf(AgentSettings.enabled) }
            var ctxTokens by remember { mutableStateOf(AgentSettings.contextTokens) }
            var rounds by remember { mutableStateOf(AgentSettings.maxRounds) }

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f)
                )
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        localizedText("Agent 设置", "Agent settings"),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )

                    // Agent 开关
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                localizedText("启用工具调用", "Enable tool calling"),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                localizedText("让模型能读写文件、执行命令", "Let the model read/write files"),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = agentOn,
                            onCheckedChange = {
                                agentOn = it
                                AgentSettings.enabled = it
                            }
                        )
                    }

                    // 上下文长度
                    Text(
                        localizedText("上下文长度", "Context length") + "：$ctxTokens tokens",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AgentSettings.CONTEXT_OPTIONS.forEach { opt ->
                            FilterChip(
                                selected = ctxTokens == opt,
                                onClick = {
                                    ctxTokens = opt
                                    AgentSettings.contextTokens = opt
                                },
                                label = { Text("$opt") }
                            )
                        }
                    }
                    Text(
                        localizedText(
                            "输入+输出合计上限。设太小会导致回答被截断（只回几个字）。修改后需重新加载模型。",
                            "Larger context remembers more but uses more memory. Reload the model to apply."
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // 最大轮数
                    Text(
                        localizedText("最大工具调用轮数", "Max tool rounds") + "：$rounds",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    // ★ 不再用滑条。
                    //   滑条在大范围（10~500）下拖不准，且用户要的是「无限」或直接填数字。
                    //   约定：rounds <= 0 表示无限，>0 是具体轮数。
                    var roundsText by remember { mutableStateOf(if (rounds <= 0) "" else rounds.toString()) }
                    androidx.compose.foundation.layout.Row(
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
                    ) {
                        androidx.compose.material3.OutlinedTextField(
                            value = if (rounds <= 0) localizedText("无限", "∞") else roundsText,
                            onValueChange = { s ->
                                val digits = s.filter { it.isDigit() }.take(6)
                                roundsText = digits
                                val n = digits.toIntOrNull() ?: 0
                                rounds = n.coerceIn(0, 100000)
                                AgentSettings.maxRounds = rounds
                                viewModel.setAgentMaxRounds(rounds)
                            },
                            enabled = rounds > 0,
                            singleLine = true,
                            label = { Text(localizedText("轮数", "Rounds"), style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.width(130.dp)
                        )
                        Text(localizedText("无限调用", "Unlimited"), style = MaterialTheme.typography.bodySmall)
                        com.qtwl.gateway.ui.components.IosSwitch(
                            checked = rounds <= 0,
                            onCheckedChange = { open ->
                                rounds = if (open) 0 else 200
                                roundsText = if (open) "" else "200"
                                AgentSettings.maxRounds = rounds
                            }
                        )
                    }

                    HorizontalDivider()

                    // ===== Shell 安全级别 =====
                    var shellFull by remember { mutableStateOf(AgentSettings.shellFullAccess) }
                    var shellTimeout by remember { mutableStateOf(AgentSettings.shellTimeoutSec) }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                localizedText("Shell 完全放开", "Full shell access"),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                localizedText(
                                    "开启：任意命令、管道、重定向、多行脚本。" +
                                            "关闭：仅白名单命令，禁止管道与重定向",
                                    "On: any command, pipes, redirects, multi-line scripts. " +
                                            "Off: whitelisted commands only"
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = shellFull,
                            onCheckedChange = {
                                shellFull = it
                                AgentSettings.shellFullAccess = it
                            }
                        )
                    }

                    if (shellFull) {
                        Text(
                            localizedText(
                                "⚠ 已放开：AI 可以执行任意命令（含删除、覆盖文件）。" +
                                        "App 无 root 权限，影响范围限于本应用可写的位置。",
                                "⚠ Enabled: the AI can run any command, including destructive ones."
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    Text(
                        localizedText("命令超时", "Command timeout") + "：${shellTimeout}s",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        AgentSettings.TIMEOUT_OPTIONS.forEach { opt ->
                            FilterChip(
                                selected = shellTimeout == opt,
                                onClick = {
                                    shellTimeout = opt
                                    AgentSettings.shellTimeoutSec = opt
                                },
                                label = { Text("${opt}s") }
                            )
                        }
                    }

                    HorizontalDivider()

                    // ===== 工作区（沙箱） =====
                    var wsPath by remember { mutableStateOf(AgentSettings.workspace) }
                    var wsLocked by remember { mutableStateOf(AgentSettings.workspaceLocked) }
                    val ctxWs = LocalContext.current

                    Text(
                        localizedText("Agent 工作区", "Agent workspace"),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )

                    OutlinedTextField(
                        value = wsPath,
                        onValueChange = { wsPath = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(localizedText("工作区路径", "Workspace path")) },
                        textStyle = MaterialTheme.typography.bodySmall
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                wsPath = AgentSettings.DEFAULT_WORKSPACE
                                AgentSettings.workspace = wsPath
                                java.io.File(wsPath).mkdirs()
                            }
                        ) {
                            Text(localizedText("恢复默认", "Default"), style = MaterialTheme.typography.labelMedium)
                        }
                        OutlinedButton(
                            onClick = {
                                AgentSettings.workspace = wsPath
                                val d = java.io.File(wsPath)
                                if (!d.exists()) d.mkdirs()
                            }
                        ) {
                            Text(localizedText("保存路径", "Save"), style = MaterialTheme.typography.labelMedium)
                        }
                        OutlinedButton(
                            onClick = {
                                try {
                                    AgentSettings.workspace = wsPath
                                    val d = java.io.File(wsPath)
                                    if (!d.exists()) d.mkdirs()
                                    val uri = android.net.Uri.parse("file://" + d.absolutePath)
                                    val i = android.content.Intent(android.content.Intent.ACTION_VIEW)
                                    i.setDataAndType(uri, "resource/folder")
                                    ctxWs.startActivity(i)
                                } catch (_: Exception) {
                                }
                            }
                        ) {
                            Text(localizedText("打开", "Open"), style = MaterialTheme.typography.labelMedium)
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                localizedText("限制在工作区内", "Lock to workspace"),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                localizedText(
                                    "开启后 AI 只能在这个目录里读写代码，越界会被拒绝",
                                    "AI can only read/write inside this folder"
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = wsLocked,
                            onCheckedChange = {
                                wsLocked = it
                                AgentSettings.workspaceLocked = it
                            }
                        )
                    }

                    Text(
                        "当前工作区：${AgentSettings.workspace}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )

                    HorizontalDivider()

                    // 存储权限
                    val ctx = LocalContext.current
                    Button(
                        onClick = {
                            try {
                                val intent = android.content.Intent(
                                    android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                    android.net.Uri.parse("package:" + ctx.packageName)
                                )
                                ctx.startActivity(intent)
                            } catch (_: Exception) {
                                try {
                                    ctx.startActivity(
                                        android.content.Intent(
                                            android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION
                                        )
                                    )
                                } catch (_: Exception) {}
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(localizedText("授予全部文件访问权限", "Grant all-files access"))
                    }
                    Text(
                        localizedText(
                            "授予后 Agent 才能读写内部存储中的文件。Android 11 及以上需要手动开启。",
                            "Required for the agent to read/write files. On Android 11+ this must be enabled manually."
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            HorizontalDivider()

            // ---- 对话测试 ----
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        localizedText("试一下", "Try it"),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )

                    if (!engineState.ready) {
                        Text(
                            localizedText(
                                "先下载并点「加载并使用」，之后在聊天页的模型列表里就能选到它。",
                                "Download and load a model first, then you can chat here."
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            localizedText("当前模型：", "Current model: ") +
                                    LocalModelManager.CATALOG
                                        .firstOrNull { it.id == engineState.modelId }
                                        ?.displayName.orEmpty() +
                                    "（加载 ${engineState.loadMs} ms）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        // ★ 运行时信息：CPU 后端 / 线程 / 批 / 上下文 / KV 量化
                        //   这些是决定速度的关键参数，摆出来就不用猜了
                        Text(
                            localizedText("运行时：", "Runtime: ") +
                                    engineState.backend + " · " + localizedText("线程", "threads") + " " + engineState.threads +
                                    " · " + localizedText("批", "batch") + " " + engineState.batch +
                                    " · " + localizedText("上下文", "ctx") + " " + engineState.maxTokens +
                                    " · KV " + (if (engineState.kvQuant) "q8_0" else "f16"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        OutlinedTextField(
                            value = testInput,
                            onValueChange = { testInput = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(localizedText("输入问题", "Your question")) },
                            minLines = 2,
                            maxLines = 4
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    if (testInput.isBlank() || generating) return@Button
                                    generating = true
                                    testOutput = ""
                                    scope.launch {
                                        try {
                                            val prompt = LocalLlmEngine.buildPrompt(
                                                listOf("user" to testInput)
                                            )
                                            val result = withContext(Dispatchers.IO) {
                                                LocalLlmEngine.generate(prompt)
                                            }
                                            testOutput = result
                                        } catch (e: Exception) {
                                            testOutput = "错误：" + (e.message ?: e.javaClass.simpleName)
                                        } finally {
                                            generating = false
                                        }
                                    }
                                },
                                enabled = !generating,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Send, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(if (generating) localizedText("生成中…", "Generating…") else localizedText("发送", "Send"))
                            }
                            OutlinedButton(
                                onClick = {
                                    LocalLlmEngine.release()
                                    testOutput = ""
                                }
                            ) {
                                Icon(Icons.Default.Stop, null, Modifier.size(18.dp))
                            }
                        }

                        if (generating) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        }

                        // ★ 真实速度：直接取服务端 timings，不靠估算
                        val perf = LocalLlmEngine.lastPerf
                        if (!generating && perf.genTps > 0.0) {
                            Text(
                                localizedText("速度：", "Speed: ") +
                                        String.format("%.1f", perf.genTps) + " tok/s " + localizedText("生成", "gen") +
                                        " · " + String.format("%.0f", perf.promptTps) + " tok/s " + localizedText("读入", "prompt"),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        if (testOutput.isNotBlank()) {
                            Surface(
                                color = MaterialTheme.colorScheme.surface,
                                shape = MaterialTheme.shapes.small,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    testOutput,
                                    modifier = Modifier.padding(10.dp),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

private fun fmtBytes(bytes: Long): String {
    if (bytes <= 0) return "0 MB"
    val gb = bytes / 1024.0 / 1024.0 / 1024.0
    return if (gb >= 1.0) String.format("%.2f GB", gb)
    else String.format("%.0f MB", bytes / 1024.0 / 1024.0)
}

private fun fmtSpeed(bps: Long): String {
    val mb = bps / 1024.0 / 1024.0
    return if (mb >= 1.0) String.format("%.1f MB/s", mb)
    else String.format("%.0f KB/s", bps / 1024.0)
}

private fun fmtEta(seconds: Int): String {
    return if (seconds >= 60) {
        val m = seconds / 60
        val s = seconds % 60
        "剩余 ${m}分${s}秒"
    } else "剩余 ${seconds}秒"
}