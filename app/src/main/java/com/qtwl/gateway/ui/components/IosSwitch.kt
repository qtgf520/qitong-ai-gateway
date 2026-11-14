package com.qtwl.gateway.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * iOS 27 同款开关。
 *
 * 尺寸照抄苹果的分段标准：轨道 51x31dp、滑块 27dp、内边距 2dp。
 * 开关状态用弹簧动画滑动（阻尼 0.78、刚度 1100），不是线性过渡 ——
 * 这是「苹果手感」的来源：快、略微过冲、立刻停住。
 *
 * 不用 Material3 的 Switch，是因为它的轨道比例和滑块比例跟 iOS 差太多，
 * 圆角、间距、动画曲线都对不上，换皮也改不出来。
 */
@Composable
fun IosSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val light = MaterialTheme.colorScheme.surface.luminance() > 0.5f
    val trackOn = if (light) Color(0xFF34C759) else Color(0xFF30D158)
    val trackOff = if (light) Color(0xFFE9E9EA) else Color(0xFF39393D)
    val thumb = Color.White

    val progress by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.78f, stiffness = 1100f),
        label = "ios_switch"
    )
    val density = LocalDensity.current
    val interaction = remember { MutableInteractionSource() }

    Canvas(
        modifier = modifier
            .size(width = 51.dp, height = 31.dp)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled
            ) { onCheckedChange(!checked) }
    ) {
        val h = size.height
        val w = size.width
        val pad = with(density) { 2.dp.toPx() }
        val thumbD = h - pad * 2
        val radius = CornerRadius(h / 2f, h / 2f)

        // 轨道：关是浅灰、开是荧光绿，颜色也跟着进度插值
        val track = Color(
            red = trackOff.red + (trackOn.red - trackOff.red) * progress,
            green = trackOff.green + (trackOn.green - trackOff.green) * progress,
            blue = trackOff.blue + (trackOn.blue - trackOff.blue) * progress,
            alpha = 1f
        )
        drawRoundRect(
            color = if (enabled) track else track.copy(alpha = 0.5f),
            topLeft = Offset.Zero,
            size = Size(w, h),
            cornerRadius = radius
        )
        // 轨道顶部一点点高光，避免像一块塑料
        drawRoundRect(
            brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                0f to Color.White.copy(alpha = if (light) 0.18f else 0.10f),
                0.5f to Color.Transparent
            ),
            topLeft = Offset.Zero,
            size = Size(w, h),
            cornerRadius = radius
        )

        // 滑块：阴影 + 白圆
        val thumbLeft = pad + (w - thumbD - pad * 2) * progress
        drawCircle(
            color = Color.Black.copy(alpha = 0.18f),
            radius = thumbD / 2f,
            center = Offset(thumbLeft + thumbD / 2f, h / 2f + pad * 0.6f)
        )
        drawCircle(
            color = thumb,
            radius = thumbD / 2f,
            center = Offset(thumbLeft + thumbD / 2f, h / 2f)
        )
    }
}
