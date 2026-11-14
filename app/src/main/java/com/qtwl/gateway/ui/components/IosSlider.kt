package com.qtwl.gateway.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.animateFloatAsState as animFloat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * iOS 27 同款滑块。
 *
 * 为什么不用 Material3 的 Slider：它的轨道是双色硬切、滑块是 4dp 的小圆点、
 * 行进时还有一层 ripple。苹果是一条 4dp 的细轨 + 一个 26dp 的白色大圆钮，
 * 按住时圆钮轻微放大（1.12x），松手回弹，没有任何水波纹。
 * 比例和动画都不同，换皮改不出来，所以整个自绘。
 */
@Composable
fun IosSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null
) {
    val light = MaterialTheme.colorScheme.surface.luminance() > 0.5f
    val accent = if (light) Color(0xFF007AFF) else Color(0xFF0A84FF)
    val trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)

    var widthPx by remember { mutableFloatStateOf(0f) }
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed) 1.12f else 1f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 700f),
        label = "ios_slider_thumb"
    )

    val min = valueRange.start
    val max = valueRange.endInclusive
    // 防御：值域非法（max <= min）时直接退化成 0，避免 (max-min) 除零产生 NaN/Infinity，
    // 那样 coerceIn 会抛 IllegalArgumentException 直接把界面炸掉。
    val rangeSpan = if (max > min) max - min else 0f

    // ★ 圆钮半径：绘制和手势换算必须用同一个值，否则手指和圆钮错位
    val thumbRadiusPx = with(androidx.compose.ui.platform.LocalDensity.current) { 13.dp.toPx() }

    fun commit(x: Float) {
        if (widthPx <= 0f) return
        // ★★★ 修正：之前用 x / widthPx 换算，但绘制时轨道两端各内缩了一个圆钮半径，
        //   两套坐标系不一致 → 手指按在 50% 处，圆钮却在别的比例上，
        //   这就是「拖动条手感不对、按不住」的直接原因。
        //   现在手势也按同样的「可用宽度 = 总宽 - 2×半径」换算。
        val usable = (widthPx - thumbRadiusPx * 2).coerceAtLeast(1f)
        val ratio = ((x - thumbRadiusPx) / usable).coerceIn(0f, 1f)
        if (rangeSpan <= 0f) return
        var v = min + ratio * rangeSpan
        if (steps > 0) {
            val stepSize = (max - min) / (steps + 1)
            v = min + ((v - min) / stepSize).roundToInt() * stepSize
        }
        onValueChange(v.coerceIn(min, max))
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp)
            .onSizeChanged { widthPx = it.width.toFloat() }
            .pointerInput(Unit) {
                detectTapGestures { off ->
                    commit(off.x)
                    onValueChangeFinished?.invoke()
                }
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { off ->
                        pressed = true
                        commit(off.x)
                    },
                    onDragEnd = {
                        pressed = false
                        onValueChangeFinished?.invoke()
                    },
                    onDragCancel = { pressed = false }
                ) { change, _ ->
                    commit(change.position.x)
                }
            }
    ) {
        val cy = size.height / 2f
        val trackH = 4.dp.toPx()
        val ratio = if (rangeSpan > 0f) ((value - min) / rangeSpan).coerceIn(0f, 1f) else 0f
        val thumbR = thumbRadiusPx * scale
        // 轨道右端要给圆钮留出半个半径，否则拖到最右圆钮会被裁
        val usable = (size.width - thumbR * 2).coerceAtLeast(1f)
        val x = thumbR + usable * ratio

        // ① 底轨
        drawRoundRect(
            color = trackColor,
            topLeft = Offset(thumbR, cy - trackH / 2f),
            size = Size(size.width - thumbR * 2, trackH),
            cornerRadius = CornerRadius(trackH / 2f, trackH / 2f)
        )
        // ② 已选段
        drawRoundRect(
            color = accent,
            topLeft = Offset(thumbR, cy - trackH / 2f),
            size = Size((x - thumbR).coerceAtLeast(0f), trackH),
            cornerRadius = CornerRadius(trackH / 2f, trackH / 2f)
        )
        // ③ 圆钮：阴影 + 白面 + 一圈极淡描边
        drawCircle(
            color = Color.Black.copy(alpha = if (pressed) 0.22f else 0.16f),
            radius = thumbR,
            center = Offset(x, cy + 1.5f)
        )
        drawCircle(
            color = Color.White,
            radius = thumbR,
            center = Offset(x, cy)
        )
        drawCircle(
            color = Color.Black.copy(alpha = 0.06f),
            radius = thumbR,
            center = Offset(x, cy),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1f)
        )
    }
}

/**
 * iOS 风格按钮：胶囊形、按下轻微缩小（0.96）、无涟漪、无阴影扩散。
 *
 * 苹果的按钮按压反馈是「整块下沉」，Material 的 ripple 是「中心扩散」，
 * 观感完全不同，所以这里把 indication 关掉，改用缩放。
 */
@Composable
fun IosButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    filled: Boolean = true,
    enabled: Boolean = true
) {
    val light = MaterialTheme.colorScheme.surface.luminance() > 0.5f
    val accent = if (light) Color(0xFF007AFF) else Color(0xFF0A84FF)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animFloat(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 900f),
        label = "ios_button"
    )

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = if (enabled) 1f else 0.4f
            }
            .clip(RoundedCornerShape(percent = 50))
            .background(
                if (filled) accent.copy(alpha = 0.92f)
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                , RoundedCornerShape(percent = 50)
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled
            ) { onClick() }
            .padding(horizontal = 18.dp, vertical = 9.dp),
        contentAlignment = androidx.compose.ui.Alignment.Center
    ) {
        Text(
            text = text,
            color = if (filled) Color.White else accent,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}
