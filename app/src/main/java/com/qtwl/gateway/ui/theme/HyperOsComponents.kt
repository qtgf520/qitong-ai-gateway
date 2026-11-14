package com.qtwl.gateway.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * MIUI / HyperOS 风格组件
 *
 * ============================================================
 * HyperOS（小米澎湃 OS）设置页的设计特征
 * ============================================================
 *  参考 KSU / KernelSU 管理器与 HyperOS 系统设置的观感：
 *
 *  ① 分组标题：小号、灰色、左对齐、14sp，不带背景
 *  ② 卡片：白色圆角 18dp，**无边框**，靠背景色差区分层级
 *  ③ 列表项高度：56dp，左图标 36dp 圆角方块（带 12% 同色淡底）
 *  ④ 标题 16sp 常规字重，副标题 13sp 灰色
 *  ⑤ 分隔线：从**文字左沿**开始（不是从图标左沿），
 *     右侧留 16dp，宽度 0.5dp，颜色极淡
 *  ⑥ 点击反馈：整行 Ripple 关闭，HyperOS 用的是整行淡色高亮
 *  ⑦ 强调色：#3482FF（HyperOS 系统蓝）
 *
 * ============================================================
 * 与 Material3 默认风格的差异
 * ============================================================
 *  Material3 的 ListItem 用的是 12dp 圆角 + 标题 16sp Medium +
 *  图标 24dp 无色底。HyperOS 的圆角更大、图标有彩色底块、
 *  标题更细，整体更「软」。
 */

/**
 * 色值别名（全部指回 MiuiColors，即 Miuix 官方值）。
 * 保留这一层是为了不改调用方，同时保证只有一个色源。
 */
object HyperOsColors {
    /** 强调色 = Miuix primary */
    val Accent = MiuiColors.Primary              // #3482FF
    val AccentDark = MiuiColors.DarkPrimary      // #277AF7

    /** 浅色 */
    val CardLight = MiuiColors.LightSurfaceContainer  // 卡片白
    val PageLight = MiuiColors.LightBackground        // 页面白
    val TextPrimaryLight = MiuiColors.LightOnSurface
    val TextSecondaryLight = MiuiColors.LightOnSurfaceSummary
    val DividerLight = MiuiColors.LightDivider        // #E0E0E0

    /** 深色 */
    val CardDark = MiuiColors.DarkSurfaceContainer    // #2A2A2A
    val PageDark = MiuiColors.DarkBackground
    val TextPrimaryDark = MiuiColors.DarkOnSurface
    val TextSecondaryDark = MiuiColors.DarkOnSurfaceSummary
    val DividerDark = MiuiColors.DarkDivider
}

/**
 * 分组标题
 *
 * HyperOS 的写法：
 *   通用
 *   ┌──────────────────────────┐
 *   │  项 1                    │
 *   └──────────────────────────┘
 * 标题在卡片外，左边距 32dp（比卡片内边距 16dp 多一倍）。
 */
@Composable
fun HyperOsSectionHeader(
    text: String,
    modifier: Modifier = Modifier
) {
    // ★ 规格来自 Miuix 官方 SmallTitle.kt：
    //   padding = PaddingValues(28.dp, 8.dp)   ← 左 28dp / 上下 8dp
    //   颜色   = onBackgroundVariant（官方 #8C93B0，冷灰蓝）
    //   字号   = Miuix 的 body2（约 14sp）
    //   之前我写的 32dp / #99 黑是我自己编的，已按官方值改。
    val style = ThemeState.style
    val color = if (style == ThemeStyle.Apple) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        // Miuix 官方 onBackgroundVariant 是偏蓝的灰 #8C93B0，
        // 比纯灰更「系统感」，这也是 HyperOS 分组标题的辨识点之一。
        Color(0xFF8C93B0)
    }
    Text(
        text = text,
        modifier = modifier.padding(start = 28.dp, top = 8.dp, bottom = 8.dp),
        fontSize = 14.sp,
        color = color
    )
}

/**
 * 分组卡片容器
 *
 * 圆角 18dp，无边框，内部垂直排列子项。
 */
@Composable
fun HyperOsCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    // ★ 圆角按风格区分：
    //   MIUIx = 16.dp —— 这是 Miuix 官方 Card.kt 里的硬编码值
    //           （miuix-ui/.../basic/Card.kt: val CornerRadius = 16.dp）
    //           之前写的 18dp 是我凭印象猜的，不对。
    //   Apple  = 10dp（iOS inset grouped 卡片）
    val radius = if (ThemeState.style == ThemeStyle.Apple) 10.dp else 16.dp

    // 修饰符顺序（从左到右依次生效）：
    //   .fillMaxWidth()          → 先撑满父宽度
    //   .padding(horizontal=16)  → 再左右各缩 16dp（卡片不贴边）
    //   .clip(RoundedCornerShape(radius)) → 在缩好的区域内裁圆角
    //   .background(surface)     → 填色，被上一步的圆角裁掉四角
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(radius))
            .background(MaterialTheme.colorScheme.surface),
        content = content
    )
}

/**
 * 列表项
 *
 * @param icon      左侧图标（null 表示无图标，文字直接顶到左边距）
 * @param iconTint  图标颜色（底块用它的 12% 淡色）
 * @param title     主标题
 * @param subtitle  副标题（null 不显示）
 * @param showArrow 右侧是否显示箭头
 * @param trailing  右侧自定义内容（和箭头互斥）
 * @param isLast    是否是该卡片最后一项（最后一项不画分隔线）
 */
@Composable
fun HyperOsItem(
    icon: ImageVector? = null,
    iconTint: Color = HyperOsColors.Accent,
    title: String,
    subtitle: String? = null,
    showArrow: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    isLast: Boolean = false,
    showDivider: Boolean = true,
    onClick: (() -> Unit)? = null
) {
    val interaction = remember { MutableInteractionSource() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = interaction,
                        indication = null,
                        onClick = onClick
                    )
                } else Modifier
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左图标：
            //   Miuix 官方列表项（SmallTitle / BasicComponent）用**无底块的纯色图标**，
            //   尺寸 24dp，间距 12dp。之前我写的「36dp 圆角方块 + 12% 淡底」
            //   是凭印象编的，KSU 里并没有这种写法，已按官方规格改掉。
            //   Apple 也是 24dp 无底块，两者这里一致。
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
            }

            // 文字区
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // 右侧
            when {
                trailing != null -> trailing()
                showArrow -> {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        // 分隔线：
        //   两种风格现在图标规格一致（24dp + 12dp 间距），
        //   所以缩进统一是 24+12+16 = 52dp，右端留 16dp。
        //   分隔线从「文字左沿」开始而不是贯穿整卡，这是 Miuix / iOS 共同的做法。
        if (showDivider && !isLast) {
            val indent = if (icon == null) 16.dp else 52.dp
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = indent, end = 16.dp)
                    .height(0.5.dp)
                    .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
            )
        }
    }
}