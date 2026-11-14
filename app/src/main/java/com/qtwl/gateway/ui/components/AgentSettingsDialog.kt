package com.qtwl.gateway.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qtwl.gateway.agent.AgentConfig
import com.qtwl.gateway.agent.AgentGuard
import com.qtwl.gateway.agent.AgentScope
import com.qtwl.gateway.agent.ContextUsage
import com.qtwl.gateway.agent.ReasoningEffort

/**
 * Agent 设置面板
 *
 * 包含：
 *  · 推理强度（快速/均衡/深度）
 *  · 上下文窗口
 *  · 压缩阈值
 *  · 保留消息条数
 *  · 流式输出开关
 *  · 自动续跑开关
 *  · 最大工具轮次
 */
@Composable
fun AgentSettingsDialog(
    config: AgentConfig,
    contextUsage: ContextUsage,
    onDismiss: () -> Unit,
    onReasoningEffort: (ReasoningEffort) -> Unit,
    onContextWindow: (Int) -> Unit,
    onCompactThreshold: (Float) -> Unit,
    onKeepRecent: (Int) -> Unit,
    onStreaming: (Boolean) -> Unit,
    thinking: String = "off",
    onThinking: (String) -> Unit = {},
    shellTier: String = "app",
    onShellTier: (String) -> Unit = {},
    onAutoContinue: (Boolean) -> Unit,
    onMaxRounds: (Int) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(Icons.Default.Tune, contentDescription = null)
        },
        title = {
            Text("Agent 设置", fontWeight = FontWeight.Bold)
        },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .fillMaxWidth()
            ) {
                // ===== 上下文用量 =====
                SectionTitle("上下文用量")
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "已用 ${contextUsage.usedTokens} / ${contextUsage.maxTokens}",
                                style = MaterialTheme.typography.labelMedium
                            )
                            Spacer(Modifier.weight(1f))
                            Text(
                                text = "${(contextUsage.percent * 100).toInt()}%",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = if (contextUsage.percent > config.compactThreshold) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.primary
                                }
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        // 进度条
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .background(
                                    MaterialTheme.colorScheme.surfaceVariant,
                                    RoundedCornerShape(3.dp)
                                )
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(contextUsage.percent.coerceIn(0f, 1f))
                                    .height(6.dp)
                                    .background(
                                        if (contextUsage.percent > config.compactThreshold) {
                                            MaterialTheme.colorScheme.error
                                        } else {
                                            MaterialTheme.colorScheme.primary
                                        },
                                        RoundedCornerShape(3.dp)
                                    )
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "消息 ${contextUsage.messageCount} 条 · 达到 ${(config.compactThreshold * 100).toInt()}% 时自动压缩",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // ===== 推理强度 =====
                SectionTitle("推理强度")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ReasoningEffort.entries.forEach { effort ->
                        val selected = config.reasoningEffort == effort
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onReasoningEffort(effort) },
                            color = if (selected) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                            },
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(vertical = 8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = effort.label,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (selected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "${effort.budgetTokens / 1024}k",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = 9.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                Hint("深度模式会让模型思考更久，适合复杂任务；快速模式响应更快")

                // ===== 上下文窗口 =====
                SectionTitle("上下文窗口：${formatTokens(config.contextWindow)}")
                Slider(
                    value = config.contextWindow.toFloat(),
                    onValueChange = { onContextWindow(it.toInt()) },
                    valueRange = 4_000f..512_000f,
                    steps = 0
                )
                Hint("按模型实际能力设置。设太大会超出模型限制报错，设太小会频繁压缩")

                // ===== 压缩阈值 =====
                SectionTitle("压缩阈值：${(config.compactThreshold * 100).toInt()}%")
                Slider(
                    value = config.compactThreshold,
                    onValueChange = { onCompactThreshold(it) },
                    valueRange = 0.3f..0.95f
                )
                Hint("上下文用到这个比例时，自动把历史总结成摘要，避免超限")

                // ===== 保留条数 =====
                SectionTitle("压缩后保留：${config.keepRecentMessages} 条")
                Slider(
                    value = config.keepRecentMessages.toFloat(),
                    onValueChange = { onKeepRecent(it.toInt()) },
                    valueRange = 2f..20f,
                    steps = 17
                )

                // ===== 开关 =====
                Spacer(Modifier.height(8.dp))
                SwitchRow(
                    title = "逐字流式输出",
                    subtitle = "回答按打字机效果逐字显示",
                    checked = config.streaming,
                    onChange = onStreaming
                )

                // ===== 思考（推理模型专用）=====
                SectionTitle("思考：${when (thinking) {
                    "low" -> "快速"
                    "medium" -> "均衡"
                    "high" -> "深入"
                    else -> "关闭"
                }}")
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    listOf("off" to "关闭", "low" to "快速", "medium" to "均衡", "high" to "深入")
                        .forEach { pair ->
                            FilterChip(
                                selected = thinking == pair.first,
                                onClick = { onThinking(pair.first) },
                                label = { Text(pair.second, fontSize = 11.sp) }
                            )
                        }
                }
                Hint("只有推理模型（o 系列 / gpt-5 / DeepSeek-R1 等）才需要开。若服务商不支持，会自动摘掉该参数并提示，不会把请求打挂")

                // ===== Shell 身份 =====
                SectionTitle("Shell 身份：${when (shellTier) {
                    "adb" -> "ADB（UID 2000）"
                    "root" -> "Root（UID 0）"
                    else -> "普通用户"
                }}")
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    listOf("app" to "普通用户", "adb" to "ADB 2000", "root" to "Root")
                        .forEach { pair ->
                            FilterChip(
                                selected = shellTier == pair.first,
                                onClick = { onShellTier(pair.first) },
                                label = { Text(pair.second, fontSize = 11.sp) }
                            )
                        }
                }
                Hint("普通用户身份永远可用；ADB(2000) 和 Root(0) 需要设备已 root 并授权，不可用时会明确报出来。模型也能在单条命令里用 user 参数临时切换")

                // ===== Shizuku（无 root 拿 ADB 权限）=====
                ShizukuRow()
                SwitchRow(
                    title = "自动续跑",
                    subtitle = "输出被长度截断时自动接着写",
                    checked = config.autoContinue,
                    onChange = onAutoContinue
                )

                // ===== 最大轮次 =====
                SectionTitle("最大工具轮次：${config.maxRounds}")
                // ★ 数字输入 + 无限开关，替代滑条（滑条在大范围下拖不准）
                var roundsText by remember { mutableStateOf(if (config.maxRounds <= 0) "" else config.maxRounds.toString()) }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    androidx.compose.material3.OutlinedTextField(
                        value = if (config.maxRounds <= 0) "无限" else roundsText,
                        onValueChange = { s ->
                            val digits = s.filter { it.isDigit() }.take(6)
                            roundsText = digits
                            onMaxRounds((digits.toIntOrNull() ?: 0).coerceIn(0, 100000))
                        },
                        enabled = config.maxRounds > 0,
                        singleLine = true,
                        label = { Text("轮数", style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.width(130.dp)
                    )
                    Text("无限调用", style = MaterialTheme.typography.bodySmall)
                    com.qtwl.gateway.ui.components.IosSwitch(
                        checked = config.maxRounds <= 0,
                        onCheckedChange = { open ->
                            roundsText = if (open) "" else "200"
                            onMaxRounds(if (open) 0 else 200)
                        }
                    )
                }
                Hint("默认 200 轮。打开「无限调用」= 不设上限，由模型自己判断何时结束（写完整项目建议这样用）")

                // ===== 工具开关 =====
                SectionTitle("可用工具")
                var toolTick by remember { mutableStateOf(0) }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AgentGuard.TOOL_GROUPS.forEach { (id, label) ->
                        val on = AgentGuard.isGroupEnabled(id)
                        ScopeChip(label + if (on) " 开" else " 关", on) {
                            AgentGuard.setGroupEnabled(id, !on)
                            toolTick++
                        }
                    }
                }
                Hint("关掉的分组模型不会调用（省 token，也能防它乱删文件）· 已切换 " + toolTick + " 次")

                // ===== 权限与沙盒（Agent 内嵌，不是牢房） =====
                SectionTitle("访问范围")
                var scopeSel by remember { mutableStateOf(AgentGuard.scope) }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ScopeChip("仅工作区", scopeSel == AgentScope.WORKSPACE) {
                        scopeSel = AgentScope.WORKSPACE
                        AgentGuard.scope = AgentScope.WORKSPACE
                        com.qtwl.gateway.utils.AgentSettings.workspaceLocked = true
                    }
                    ScopeChip("整机只读", scopeSel == AgentScope.DEVICE_READ) {
                        scopeSel = AgentScope.DEVICE_READ
                        AgentGuard.scope = AgentScope.DEVICE_READ
                        com.qtwl.gateway.utils.AgentSettings.workspaceLocked = false
                    }
                    ScopeChip("整机读写", scopeSel == AgentScope.DEVICE_FULL) {
                        scopeSel = AgentScope.DEVICE_FULL
                        AgentGuard.scope = AgentScope.DEVICE_FULL
                        com.qtwl.gateway.utils.AgentSettings.workspaceLocked = false
                    }
                }
                Hint(
                    when (scopeSel) {
                        AgentScope.WORKSPACE -> "只能在 " + com.qtwl.gateway.utils.AgentSettings.workspace + " 里读写"
                        AgentScope.DEVICE_READ -> "整机可读，写只能落回工作区"
                        AgentScope.DEVICE_FULL -> "整机可读可写，写工作区外会弹确认"
                    }
                )

                Spacer(Modifier.height(4.dp))
                var needApproval by remember { mutableStateOf(AgentGuard.requireApproval) }
                SwitchRow(
                    title = "危险操作需确认",
                    subtitle = "rm / mv / pm / 重定向 / root 都要你点一下才执行",
                    checked = needApproval,
                    onChange = { needApproval = it; AgentGuard.requireApproval = it }
                )
                var readOnly by remember { mutableStateOf(AgentGuard.readOnly) }
                SwitchRow(
                    title = "只读模式",
                    subtitle = "禁止任何写入与删除",
                    checked = readOnly,
                    onChange = { readOnly = it; AgentGuard.readOnly = it }
                )
                var rootOn by remember { mutableStateOf(AgentGuard.allowRoot) }
                SwitchRow(
                    title = "Root 指导",
                    subtitle = "允许模型执行 su -c 探查命令（必须配合确认）",
                    checked = rootOn,
                    onChange = { rootOn = it; AgentGuard.allowRoot = it }
                )
                Hint("没开 root 时，模型会把需要的命令写进回复，让你自己执行")
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("完成")
            }
        }
    )
}

@Composable
private fun ScopeChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(50),
        modifier = Modifier
            .clickable { onClick() }
            .padding(0.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Spacer(Modifier.height(14.dp))
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface
    )
}

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        fontSize = 10.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 2.dp, bottom = 2.dp)
    )
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private fun formatTokens(v: Int): String = when {
    v >= 1_000_000 -> "${v / 1_000_000}M"
    v >= 1_000 -> "${v / 1_000}K"
    else -> "$v"
}
/**
 * Shizuku 授权行。
 *
 * Shizuku 由用户手动启动（新版可直接在它内部用无线调试启动），
 * 启动后本应用可以借它拿到 UID 2000 的 shell —— 不需要 root。
 */
@Composable
private fun ShizukuRow() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var status by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { status = com.qtwl.gateway.coagent.ShizukuBridge.status(ctx) }

    Column(Modifier.fillMaxWidth()) {
        SectionTitle("Shizuku（无 root 的 ADB 权限）")
        Text(status, fontSize = 12.sp, color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            androidx.compose.material3.TextButton(onClick = {
                msg = com.qtwl.gateway.coagent.ShizukuBridge.requestPermission()
                status = com.qtwl.gateway.coagent.ShizukuBridge.status(ctx)
            }) { Text("授权", fontSize = 12.sp) }
            androidx.compose.material3.TextButton(onClick = {
                msg = com.qtwl.gateway.coagent.ShizukuBridge.openManager(ctx)
            }) { Text("打开 Shizuku", fontSize = 12.sp) }
            androidx.compose.material3.TextButton(onClick = {
                status = com.qtwl.gateway.coagent.ShizukuBridge.status(ctx)
            }) { Text("刷新", fontSize = 12.sp) }
        }
        if (msg.isNotBlank()) Hint(msg)
    }
}
