package com.qtwl.gateway.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 液态玻璃（Glassmorphism）卡片组件
 *
 * 特性：
 * - 半透明背景 + 模糊效果（Android 12+ 使用 RenderEffect）
 * - 顶部白色渐变高光（模拟玻璃反光）
 * - 可选底部渐变背景（让玻璃更通透）
 * - 微妙的白色边框增强玻璃质感
 *
 * @param modifier 自定义修饰符
 * @param glassColor 玻璃底色（带透明度）
 * @param borderColor 玻璃边框颜色
 * @param highlightColor 顶部高光颜色
 * @param shape 圆角形状
 * @param elevation 阴影高度
 * @param blurRadius 模糊半径（Android 12+ 生效）
 * @param withHighlight 是否启用顶部高光条
 * @param content 卡片内容
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    glassColor: Color = MaterialTheme.colorScheme.surface.copy(alpha = 0.68f),
    borderColor: Color = Color.White.copy(alpha = 0.22f),
    highlightColor: Color = Color.White.copy(alpha = 0.18f),
    shape: Shape = RoundedCornerShape(28.dp),
    elevation: Dp = 12.dp,
    blurRadius: Dp = 20.dp,
    withHighlight: Boolean = true,
    content: @Composable () -> Unit
) {
    Card(
        modifier = modifier,
        shape = shape,
        elevation = CardDefaults.cardElevation(defaultElevation = elevation),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        content = { }
    )

    // 玻璃主体
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(shape)
            .background(glassColor)
            .then(
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    Modifier.blur(radius = blurRadius )
                } else Modifier
            )
            .border(
                width = 1.5.dp,
                color = borderColor,
                shape = shape
            )
    ) {
        // 顶部高光条（玻璃反光效果）
        if (withHighlight) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .align(Alignment.TopCenter)
                    .background(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                Color.Transparent,
                                highlightColor,
                                Color.Transparent
                            )
                        )
                    )
            )
        }

        // 内容
        Box(modifier = Modifier.padding(20.dp)) {
            content()
        }
    }
}

/**
 * 渐变背景修饰符 —— 用于 HomeScreen 容器
 */
fun Modifier.gradientBackground(
    colors: List<Color> = listOf(
        Color(0xFF0F0C29),
        Color(0xFF302B63),
        Color(0xFF24243E)
    ),
    angleDegrees: Float = 135f
): Modifier {
    val brush = Brush.linearGradient(
        colors = colors,
        start = Offset(0f, 0f),
        end = Offset(
            x = kotlin.math.cos(Math.toRadians(angleDegrees.toDouble())).toFloat(),
            y = kotlin.math.sin(Math.toRadians(angleDegrees.toDouble())).toFloat()
        ),
        tileMode = TileMode.Clamp
    )
    return this.then(Modifier.background(brush))
}

/**
 * 发光按钮 —— 带渐变背景和辉光阴影
 */
@Composable
fun GlowButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    glowColor: Color = MaterialTheme.colorScheme.primary,
    containerColor: Color = MaterialTheme.colorScheme.primary,
    contentColor: Color = MaterialTheme.colorScheme.onPrimary,
    shape: Shape = RoundedCornerShape(16.dp),
    elevation: Dp = 8.dp,
    content: @Composable RowScope.() -> Unit
) {
    Button(
        onClick = onClick,
        modifier = modifier
            .shadow(
                elevation = 8.dp,
                shape = shape,
                ambientColor = glowColor.copy(alpha = 0.3f),
                spotColor = glowColor.copy(alpha = 0.5f)
            ),
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor
        ),
        shape = shape,
        elevation = ButtonDefaults.buttonElevation(
            defaultElevation = elevation,
            pressedElevation = 2.dp,
            disabledElevation = 0.dp
        ),
        content = content
    )
}

/**
 * 霓虹色指示点 —— 用于状态指示灯
 */
@Composable
fun NeonDot(
    isActive: Boolean,
    activeColor: Color = Color(0xFF00FFAA),
    inactiveColor: Color = Color(0xFF555566),
    size: Dp = 10.dp,
    glowSize: Dp = 18.dp
) {
    val dotColor = if (isActive) activeColor else inactiveColor
    val glowAlpha = if (isActive) 0.7f else 0f

    Box(
        modifier = Modifier
            .size(glowSize)
            .then(
                if (isActive) {
                    Modifier.shadow(
                        elevation = 6.dp,
                        shape = RoundedCornerShape(size / 2),
                        ambientColor = activeColor.copy(alpha = glowAlpha),
                        spotColor = activeColor.copy(alpha = glowAlpha)
                    )
                } else Modifier
            ),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(RoundedCornerShape(size / 2))
                .background(dotColor)
        )
    }
}
