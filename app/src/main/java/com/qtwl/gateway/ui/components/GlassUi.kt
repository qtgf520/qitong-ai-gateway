package com.qtwl.gateway.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * iOS 27 风格的「液态玻璃」卡片。
 *
 * 刻意不采样背景（没有 backdrop）：纯靠
 *   ① 顶部亮 → 底部暗的半透明渐变
 *   ② 内侧顶部一条白色高光（clip 在圆角内，不会变成外白框）
 *   ③ 极淡的外阴影做出悬浮感
 * 来模拟玻璃。这样任何页面都能用，而且绝不会出现「采样自己 → 递归闪退」。
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 22.dp,
    tint: Color = MaterialTheme.colorScheme.surface,
    alpha: Float = 0.56f,
    content: @Composable BoxScope.() -> Unit
) {
    // 同底栏：看当前配色亮度，不看系统设置（应用可强制深色）
    val light = MaterialTheme.colorScheme.surface.luminance() > 0.5f
    val shape = RoundedCornerShape(cornerRadius)
    val r = cornerRadius
    Box(
        modifier = modifier
            .shadow(
                elevation = if (light) 8.dp else 5.dp,
                shape = shape,
                clip = false,
                ambientColor = Color.Black.copy(alpha = 0.10f),
                spotColor = Color.Black.copy(alpha = 0.16f)
            )
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(
                        tint.copy(alpha = (alpha * 1.18f).coerceAtMost(0.95f)),
                        tint.copy(alpha = (alpha * 0.74f).coerceAtMost(0.95f))
                    )
                )
            )
            .drawWithContent {
                drawContent()
                // 内侧高光：顶部一条白，往下迅速消失 —— 玻璃的「厚度」
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        0f to Color.White.copy(alpha = if (light) 0.38f else 0.10f),
                        0.28f to Color.White.copy(alpha = 0f)
                    ),
                    cornerRadius = CornerRadius(r.toPx(), r.toPx())
                )
                // 底部内阴影：让卡片有体积，不是一张贴纸
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        0.72f to Color.Transparent,
                        1f to Color.Black.copy(alpha = if (light) 0.06f else 0.24f)
                    ),
                    cornerRadius = CornerRadius(r.toPx(), r.toPx())
                )
                // ★ 渐变内描边：空白背景上也能看清卡片边界
                val inset = 1f
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        0f to Color.White.copy(alpha = if (light) 0.85f else 0.34f),
                        0.5f to Color.White.copy(alpha = if (light) 0.28f else 0.12f),
                        1f to Color.White.copy(alpha = if (light) 0.45f else 0.18f)
                    ),
                    topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                    size = androidx.compose.ui.geometry.Size(size.width - inset * 2, size.height - inset * 2),
                    cornerRadius = CornerRadius(r.toPx() - inset, r.toPx() - inset),
                    style = Stroke(width = 1.3.dp.toPx())
                )
            },
        content = content
    )
}

/** iOS 风格开关行：标题 + 说明 + 绿色开关 */
@Composable
fun GlassSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
        }
        IosSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

/** 玻璃分组容器：把若干行装进一张玻璃卡，行之间自动加分隔线由调用方决定 */
@Composable
fun GlassGroup(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    GlassSurface(modifier = modifier.fillMaxWidth(), cornerRadius = 20.dp) {
        Column(modifier = Modifier.padding(vertical = 2.dp)) { content() }
    }
}
