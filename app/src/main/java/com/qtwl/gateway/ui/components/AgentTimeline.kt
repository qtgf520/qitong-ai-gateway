package com.qtwl.gateway.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qtwl.gateway.ui.model.AgentItem

/**
 * 时间线里的一项：正文 / 思考 / 工具卡片。
 *
 * 设计原则：像正常对话一样往下追加，不占据输入框上方的固定区域。
 */
@Composable
fun AgentTimelineItem(item: AgentItem) {
    when (item) {
        is AgentItem.Speech -> SpeechBlock(item.text)
        is AgentItem.Think -> ThinkBlock(item.text)
        is AgentItem.Tool -> ToolCard(item)
    }
}

@Composable
private fun SpeechBlock(text: String) {
    if (text.isBlank()) return
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp, 12.dp, 12.dp, 4.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(10.dp)
        )
    }
}

@Composable
private fun ThinkBlock(text: String) {
    if (text.isBlank()) return
    var open by remember { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.08f),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
    ) {
        Column(Modifier.padding(8.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { open = !open },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Psychology,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.secondary
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (open) "思考过程（点收起）" else "思考过程（点展开）",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = text.length.toString() + " 字",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            AnimatedVisibility(visible = open) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .heightIn(max = 240.dp)
                        .verticalScroll(rememberScrollState())
                )
            }
        }
    }
}

@Composable
private fun ToolCard(item: AgentItem.Tool) {
    var open by remember(item.id) { mutableStateOf(false) }
    val accent = when {
        item.running -> MaterialTheme.colorScheme.primary
        item.ok -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.error
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
    ) {
        Column(Modifier.padding(9.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { open = !open },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = when {
                        item.running -> Icons.Default.Build
                        item.ok -> Icons.Default.CheckCircle
                        else -> Icons.Default.Error
                    },
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = accent
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.labelLarge,
                    fontFamily = FontFamily.Monospace,
                    color = accent,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = when {
                        item.running -> "运行中"
                        item.ok -> "完成"
                        else -> "失败"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = accent
                )
            }
            // 参数摘要一直显示，不用点开也能看个大概
            if (item.args.isNotBlank()) {
                Text(
                    text = summarize(item.args),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 3.dp)
                )
            }
            AnimatedVisibility(visible = open) {
                Column(Modifier.padding(top = 6.dp)) {
                    Text(
                        text = "参数",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = item.args,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .heightIn(max = 140.dp)
                            .verticalScroll(rememberScrollState())
                    )
                    item.result?.let { res ->
                        Spacer(Modifier.padding(top = 6.dp))
                        Text(
                            text = "结果",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = res,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier
                                .heightIn(max = 220.dp)
                                .verticalScroll(rememberScrollState())
                        )
                    }
                }
            }
        }
    }
}

/** 参数压成一行，太长就截断 */
private fun summarize(args: String): String {
    val one = args.replace("\n", " ").replace(Regex("\\s+"), " ").trim()
    return if (one.length <= 90) one else one.take(90) + "…"
}
