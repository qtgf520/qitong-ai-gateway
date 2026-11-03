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
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qtwl.gateway.agent.AgentConfig
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
                SwitchRow(
                    title = "自动续跑",
                    subtitle = "输出被长度截断时自动接着写",
                    checked = config.autoContinue,
                    onChange = onAutoContinue
                )

                // ===== 最大轮次 =====
                SectionTitle("最大工具轮次：${config.maxRounds}")
                Slider(
                    value = config.maxRounds.toFloat(),
                    onValueChange = { onMaxRounds(it.toInt()) },
                    valueRange = 1f..20f,
                    steps = 18
                )
                Hint("模型最多可以连续调用几轮工具。太少任务可能做不完，太多会费 token")
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