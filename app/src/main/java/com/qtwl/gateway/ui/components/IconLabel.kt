package com.qtwl.gateway.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.qtwl.gateway.utils.PipelineStatusMark

/**
 * 「矢量图标 + 文字」标签行 —— 全项目统一用于替代原来的 emoji 前缀文本
 *
 * 例：旧写法  Text("模型排行榜")
 *     新写法  IconLabel("show_chart", "模型排行榜")
 */
@Composable
fun IconLabel(
    iconName: String,
    text: String,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    iconSize: Dp = 16.dp,
    fontWeight: FontWeight? = null,
    spacing: Dp = 6.dp
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start
    ) {
        Icon(
            imageVector = AppIcons.get(iconName),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(iconSize)
        )
        Spacer(Modifier.width(spacing))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = fontWeight,
            color = tint
        )
    }
}

/**
 * 居中空状态插图 —— 替代原来 64.sp 的 emoji
 * 用大号矢量图标 + 说明文字，视觉上比 emoji 更统一
 */
@Composable
fun EmptyStateBlock(
    iconName: String,
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    androidx.compose.foundation.layout.Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = AppIcons.get(iconName),
            contentDescription = null,
            tint = tint.copy(alpha = 0.6f),
            modifier = Modifier.size(64.dp)
        )
        Spacer(Modifier.width(0.dp))
        Spacer(modifier = Modifier.size(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = tint
        )
        if (subtitle != null) {
            Spacer(modifier = Modifier.size(8.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = tint.copy(alpha = 0.7f)
            )
        }
    }
}

/**
 * 状态点（矢量）+ 状态文字 —— 替代 check / cross / hourglass 前缀
 */
@Composable
fun StatusLabel(
    status: String,
    modifier: Modifier = Modifier
) {
    val (icon, color) = when {
        PipelineStatusMark.isOk(status) -> "check_circle" to Color(0xFF22C55E)
        PipelineStatusMark.isFail(status) -> "error" to Color(0xFFEF4444)
        PipelineStatusMark.isWait(status) -> "hourglass_empty" to Color(0xFFF59E0B)
        status.contains("走代理") -> "autorenew" to MaterialTheme.colorScheme.primary
        status.contains("直连") -> "link" to MaterialTheme.colorScheme.primary
        else -> "info" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    IconLabel(
        iconName = icon,
        text = PipelineStatusMark.strip(status),
        modifier = modifier,
        tint = color,
        iconSize = 14.dp
    )
}