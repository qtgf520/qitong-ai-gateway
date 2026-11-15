package com.qtwl.gateway.ui.screens

import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.qtwl.gateway.ui.viewmodel.GatewayViewModel
import com.qtwl.gateway.utils.localizedText

/**
 * 设置屏幕 —— 极简模式
 * 仅保留后台权限引导，代理配置已隐藏（连点3下触发）
 */
@Composable
fun SettingsScreen(
    viewModel: GatewayViewModel = viewModel(factory = GatewayViewModel.Factory()),
    /** 点击「功能入口」时回调，由外部切换页面（统计/管理/实例/关于） */
    onNavigate: (Int) -> Unit = {}
) {
    com.qtwl.gateway.utils.TranslationManager.currentLanguageFlow.collectAsState().value
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            // 底部多留 96dp：内容能滚到悬浮胶囊上方，又不会露出白底
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ========== 标题 ==========
        Text(
            text = localizedText("设置", "Settings"),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
        )

        // ========== 外观（主题切换）==========
        // 原来是顶部 TopAppBar 占据 56dp，里面只有应用名 + 三点菜单（仅「设置」一项）。
        // 现在 TopAppBar 删了，标题和版本信息集中到这里。
        AppearanceCard()

        // ========== 液态玻璃 / 手感（iOS 27）==========
        GlassOptionsCard()

        // ========== 公网映射（免账号内网穿透）==========
        TunnelCard()

        // ========== 后台权限卡片 ==========
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = localizedText("后台权限管理", "Background permission management"),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                )
                Text(
                    text = localizedText("确保应用在后台稳定运行，不被系统杀死。将尝试：\n", "Keep the app running reliably in the background and prevent the system from killing it. Will try to:\n") +
                            localizedText("1️⃣ 加入电池优化白名单\n", "1️⃣ Add to the battery optimization whitelist\n") +
                            localizedText("2️⃣ 允许后台运行\n", "2️⃣ Allow background running\n") +
                            localizedText("3️⃣ 忽略电池优化（需要 Root）", "3️⃣ Ignore battery optimization (requires root)"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Button(
                    onClick = { viewModel.bindBackgroundPermissions() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary
                    )
                ) {
                    Text(localizedText("绑定后台权限", "Bind background permissions"))
                }
                // ★ 唤醒保活开关（开启 = 前台服务 + 唤醒锁 + 定时唤醒 + Shizuku 强制后台）
                var wakeKeepAlive by remember { mutableStateOf(com.qtwl.gateway.service.GatewayForegroundService.isWakeKeepAliveEnabled()) }
                androidx.compose.material3.Switch(
                    checked = wakeKeepAlive,
                    onCheckedChange = { on ->
                        wakeKeepAlive = on
                        com.qtwl.gateway.service.GatewayForegroundService.setWakeKeepAlive(on)
                        // ★ 开启时同时强制后台保活（deviceidle 白名单 + appops）+ Shizuku 授权
                        viewModel.bindBackgroundPermissions()
                        if (on) {
                            try {
                                com.qtwl.gateway.coagent.ShizukuBridge.requestPermission()
                            } catch (_: Exception) { }
                        }
                    }
                )
                Text(
                    text = if (wakeKeepAlive)
                        localizedText("✅ 唤醒保活已开启：前台服务 + 唤醒锁 + 每5分钟自唤醒 + 后台白名单", "✅ Keep-alive ON: foreground service + wake lock + 5-min self-wake + background whitelist")
                    else
                        localizedText("唤醒保活：开启后应用在后台/关屏持续运行，不被系统杀死", "Keep-alive: keeps the app running in background/screen-off"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // 提示信息
                Surface(
                    color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.1f),
                    shape = MaterialTheme.shapes.small
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary
                        )
                        Text(
                            text = localizedText("部分操作需要 Root 权限。非 Root 设备请手动在「系统设置 → 应用 → 綦桐AI网关 → 电池」中设置为「无限制」。", "Some actions require root. On non-rooted devices, manually set System Settings → Apps → QiTong AI Gateway → Battery to “Unrestricted”."),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // ========== 代理隐藏触发提示 ==========
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = localizedText("代理加速", "Proxy acceleration"),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                )
                Text(
                    text = localizedText("代理配置已隐藏，在「关于」页面连点 3 下可快速开关代理加速。\n开启后所有大模型 API 请求将自动经过代理转发。", "Proxy configuration is hidden. Tap the About page 3 times to quickly toggle proxy acceleration.\nWhen enabled, all LLM API requests are automatically forwarded through the proxy."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // ========== 功能入口（HyperOS 风格：分组标题 + 圆角卡片）==========
        com.qtwl.gateway.ui.theme.HyperOsSectionHeader(
            text = localizedText("功能", "Features")
        )
        com.qtwl.gateway.ui.theme.HyperOsCard {
            com.qtwl.gateway.ui.theme.HyperOsItem(
                icon = Icons.Outlined.BarChart,
                title = localizedText("统计", "Statistics"),
                subtitle = localizedText("请求量、Token 消耗、响应耗时", "Requests, tokens, latency"),
                showArrow = true,
                onClick = { onNavigate(8) }
            )
            com.qtwl.gateway.ui.theme.HyperOsItem(
                icon = Icons.Outlined.Memory,
                iconTint = com.qtwl.gateway.ui.theme.HyperOsColors.Accent,
                title = localizedText("本地模型", "Local models"),
                subtitle = localizedText("下载小模型，离线可用、无需额度", "Download small models, offline and free"),
                showArrow = true,
                onClick = { onNavigate(9) }
            )
            com.qtwl.gateway.ui.theme.HyperOsItem(
                icon = Icons.Outlined.Backup,
                iconTint = com.qtwl.gateway.ui.theme.HyperOsColors.Accent,
                title = localizedText("数据管理", "Data management"),
                subtitle = localizedText("备份、导入导出、清理", "Backup, import/export, cleanup"),
                showArrow = true,
                onClick = { onNavigate(5) }
            )
            com.qtwl.gateway.ui.theme.HyperOsItem(
                icon = Icons.Outlined.Lan,
                iconTint = com.qtwl.gateway.ui.theme.HyperOsColors.Accent,
                title = localizedText("实例", "Instance"),
                subtitle = localizedText("多实例管理与切换", "Manage multiple instances"),
                showArrow = true,
                onClick = { onNavigate(7) }
            )
            com.qtwl.gateway.ui.theme.HyperOsItem(
                icon = Icons.Outlined.Info,
                iconTint = com.qtwl.gateway.ui.theme.HyperOsColors.Accent,
                title = localizedText("关于", "About"),
                subtitle = localizedText("版本信息、开源许可", "Version and licenses"),
                showArrow = true,
                isLast = true,
                onClick = { onNavigate(6) }
            )
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

/** 设置内的单条入口行 */
@Composable
private fun SettingsEntryRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.size(18.dp)
        )
    }
}

/**
 * 外观设置卡片：主题风格 + 明暗模式
 *
 * 两套风格的差别（不是只换主色，是换整套设计语言）：
 *
 *  ┌──────────┬─────────────────────┬─────────────────────────┐
 *  │          │ MIUI 风             │ Apple 风                │
 *  ├──────────┼─────────────────────┼─────────────────────────┤
 *  │ 主色     │ 靛蓝 #4F46E5        │ 系统蓝 #007AFF          │
 *  │ 页面底色 │ 冷白 #F8FAFC        │ 分组灰 #F2F2F7          │
 *  │ 卡片     │ 纯白                │ 纯白（靠灰底衬托）      │
 *  │ 分隔线   │ #CBD5E1 较粗        │ #C6C6C8 极细            │
 *  │ 次要文字 │ #64748B 灰蓝        │ 60% 黑                  │
 *  └──────────┴─────────────────────┴─────────────────────────┘
 */
@Composable
private fun AppearanceCard() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val currentStyle = com.qtwl.gateway.ui.theme.ThemeState.style
    val currentDark = com.qtwl.gateway.ui.theme.ThemeState.dark

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = localizedText("外观", "Appearance"),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
            )

            // ---------- 主题风格 ----------
            Text(
                text = localizedText("主题风格", "Theme style"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                com.qtwl.gateway.ui.theme.ThemeStyle.entries.forEach { s ->
                    val selected = s == currentStyle
                    FilterChip(
                        selected = selected,
                        onClick = {
                            com.qtwl.gateway.ui.theme.ThemeState.setStyle(ctx, s)
                        },
                        label = {
                            Text(
                                when (s) {
                                    com.qtwl.gateway.ui.theme.ThemeStyle.Miui ->
                                        localizedText("HyperOS", "HyperOS")
                                    com.qtwl.gateway.ui.theme.ThemeStyle.Apple ->
                                        localizedText("Apple 风", "Apple")
                                }
                            )
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // 风格说明（一行小字，让用户知道选了什么）
            Text(
                text = when (currentStyle) {
                    com.qtwl.gateway.ui.theme.ThemeStyle.Miui ->
                        localizedText(
                            "澎湃 OS 风：系统蓝主色，浅灰底白卡片，大圆角无边框",
                            "HyperOS: system blue, light gray page with white cards, large radius, borderless"
                        )
                    com.qtwl.gateway.ui.theme.ThemeStyle.Apple ->
                        localizedText(
                            "系统蓝主色，灰色分组背景，白色卡片，细分隔线",
                            "System blue, grouped gray background, white cards, hairline separators"
                        )
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))

            // ---------- 明暗模式 ----------
            Text(
                text = localizedText("明暗模式", "Light / Dark"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // 三档：跟随系统 / 浅色 / 深色
                val options: List<Pair<String, Boolean?>> = listOf(
                    localizedText("跟随系统", "System") to null,
                    localizedText("浅色", "Light") to false,
                    localizedText("深色", "Dark") to true
                )
                options.forEach { (label, value) ->
                    FilterChip(
                        selected = currentDark == value,
                        onClick = {
                            com.qtwl.gateway.ui.theme.ThemeState.setDark(ctx, value)
                        },
                        label = { Text(label) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}
/**
 * 液态玻璃开关卡。两个开关都写进 UiSettings，改完立即持久化。
 */
@Composable
private fun GlassOptionsCard() {
    val context = androidx.compose.ui.platform.LocalContext.current
    com.qtwl.gateway.utils.UiSettings.attach(context)
    var stretch by remember { mutableStateOf(com.qtwl.gateway.utils.UiSettings.navStretch) }
    var glass by remember { mutableStateOf(com.qtwl.gateway.utils.UiSettings.glassSurfaces) }

    com.qtwl.gateway.ui.components.GlassGroup {
        com.qtwl.gateway.ui.components.GlassSwitchRow(
            title = localizedText("底栏拉伸手感", "Tab bar stretch"),
            subtitle = localizedText("按住胶囊左右甩时拉伸、越界回弹", "Stretch and rubber-band when flinging the capsule"),
            checked = stretch,
            onCheckedChange = {
                stretch = it
                com.qtwl.gateway.utils.UiSettings.navStretch = it
            }
        )
        HorizontalDivider(modifier = Modifier.padding(horizontal = 14.dp),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
        com.qtwl.gateway.ui.components.GlassSwitchRow(
            title = localizedText("液态玻璃质感", "Liquid glass"),
            subtitle = localizedText("卡片与输入栏使用 iOS 27 玻璃效果", "iOS 27 glass on cards and input bar"),
            checked = glass,
            onCheckedChange = {
                glass = it
                com.qtwl.gateway.utils.UiSettings.glassSurfaces = it
            }
        )
    }
}

/**
 * 公网映射卡片：一键开一条免账号、免 root 的 SSH 反向隧道，
 * 把本机网关（也就是你勾选启用的那些模型）暴露到公网。
 */
private fun lanAddress(context: android.content.Context): String? {
    return try {
        val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
                as android.net.ConnectivityManager
        val net = cm.activeNetwork ?: return null
        val props = cm.getLinkProperties(net) ?: return null
        props.linkAddresses
            .map { it.address }
            .filterIsInstance<java.net.Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress
    } catch (_: Throwable) { null }
}

@Composable
private fun TunnelCard() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val ssh by com.qtwl.gateway.utils.SshTunnel.state.collectAsState()
    val cf by com.qtwl.gateway.utils.TunnelManager.state.collectAsState()
    var requireKey by remember { mutableStateOf(com.qtwl.gateway.service.GatewayForegroundService.getRequireApiKey()) }
    var copied by remember { mutableStateOf(false) }
    var showCloudflare by remember { mutableStateOf(false) }
    val port = com.qtwl.gateway.service.GatewayForegroundService.getGatewayPort()
    val lan = remember { lanAddress(context) }

    com.qtwl.gateway.ui.components.GlassGroup {
        // ★ 主方案：SSH 反向隧道。纯 Java，走 Android 的 DNS，不需要 root、不需要账号。
        com.qtwl.gateway.ui.components.GlassSwitchRow(
            title = localizedText("公网映射（免账号 · 免 root）", "Public tunnel (no account, no root)"),
            subtitle = when {
                ssh.running -> localizedText("已连通，地址在下方", "Connected, address below")
                ssh.starting -> localizedText("正在连接跳板…", "Connecting…")
                else -> localizedText("生成一个临时公网地址，别的 App 或电脑都能调这些模型", "Temporary public URL for other apps and PCs")
            },
            checked = ssh.running || ssh.starting,
            onCheckedChange = { on ->
                if (on) com.qtwl.gateway.utils.SshTunnel.start(context, port)
                else com.qtwl.gateway.utils.SshTunnel.stop()
            }
        )

        // 局域网地址：同 Wi-Fi 下不需要任何映射就能用
        if (lan != null) {
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 14.dp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
            )
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(
                    text = localizedText("局域网地址（同 Wi-Fi 直接用）", "LAN address (same Wi-Fi)"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "http://" + lan + ":" + port + "/v1",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
                )
                Text(
                    text = localizedText(
                        "别的 App、其他 Agent、电脑上的客户端都填这个地址，模型名填你勾选启用的模型 ID。",
                        "Point other apps or agents at this address; use an enabled model id."
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        val url = ssh.url ?: cf.url
        if (url != null) {
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 14.dp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
            )
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(
                    text = localizedText("公网地址", "Public URL"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = url,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
                )
                com.qtwl.gateway.ui.components.IosButton(
                    text = localizedText(if (copied) "已复制" else "复制地址", if (copied) "Copied" else "Copy"),
                    onClick = {
                        val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("url", url))
                        copied = true
                    },
                    modifier = Modifier.padding(top = 6.dp)
                )
                Text(
                    text = localizedText(
                        "客户端里填 " + url + "/v1，模型名填启用的模型 ID。免费跳板生成的地址在断开后会变。",
                        "Use " + url + "/v1 as endpoint. Free relay URLs change after disconnect."
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 14.dp),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
        )

        com.qtwl.gateway.ui.components.GlassSwitchRow(
            title = localizedText("要求 API Key", "Require API key"),
            subtitle = localizedText(
                "开着更安全：别人必须带密钥才能调；关掉后拿到地址就能用",
                "On: callers need a key. Off: anyone with the URL can call."
            ),
            checked = requireKey,
            onCheckedChange = {
                requireKey = it
                com.qtwl.gateway.service.GatewayForegroundService.saveRequireApiKey(it)
            }
        )

        // 备用方案：cloudflared（Go 程序，Android 上常因缺 resolv.conf 失败）
        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 14.dp),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
        )
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            TextButton(onClick = { showCloudflare = !showCloudflare }) {
                Text(
                    localizedText(
                        if (showCloudflare) "收起备用方案" else "备用：Cloudflare 隧道",
                        if (showCloudflare) "Hide fallback" else "Fallback: Cloudflare tunnel"
                    ),
                    style = MaterialTheme.typography.labelMedium
                )
            }
            if (showCloudflare) {
                Text(
                    text = localizedText(
                        "cloudflared 是 Go 程序，靠 /etc/resolv.conf 找 DNS，Android 没有这个文件，多数机型会直接失败。已内置两条兜底（root 写 listen 配置 / 本地 DNS 代理），但优先用上面的 SSH 隧道。",
                        "cloudflared is a Go binary that needs /etc/resolv.conf, which Android lacks. Prefer the SSH tunnel above."
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                com.qtwl.gateway.ui.components.GlassSwitchRow(
                    title = localizedText("启动 Cloudflare 隧道", "Start Cloudflare tunnel"),
                    subtitle = if (cf.running) localizedText("已连通", "Connected")
                        else if (cf.starting) localizedText("正在启动…", "Starting…")
                        else localizedText("失败时会显示具体原因", "Shows the real reason on failure"),
                    checked = cf.running || cf.starting,
                    onCheckedChange = { on ->
                        if (on) com.qtwl.gateway.utils.TunnelManager.start(context, port)
                        else com.qtwl.gateway.utils.TunnelManager.stop()
                    }
                )
            }
        }

        (ssh.error ?: cf.error)?.let { err ->
            Text(
                text = err,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
            )
        }
    }
}
