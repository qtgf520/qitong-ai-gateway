package com.qtwl.gateway.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 双主题系统：MIUI 风 / Apple 风
 *
 * ============================================================
 * 为什么要有两套
 * ============================================================
 *  当前这套（靛蓝 #4F46E5 + 白底）更接近 MIUI/ColorOS 的观感：
 *  圆角大、色彩饱和、层级靠色块区分。
 *
 *  另一套是 Apple 风（iOS / iPadOS）：
 *  灰色分组背景、白色卡片、系统蓝 #007AFF、
 *  分隔线极细、层级靠灰度和留白区分。
 *
 *  两套不是换颜色，而是换「设计语言」，所以单独列出各自的
 *  语义色板，而不是只替换 primary。
 */

/** 主题风格 */
enum class ThemeStyle(val key: String, val label: String) {
    /** 当前风格：MIUI / ColorOS 观感（靛蓝 + 白底 + 大圆角） */
    Miui("miui", "MIUI 风"),

    /** 苹果风：iOS 分组列表观感（系统蓝 + 灰分组 + 白卡片） */
    Apple("apple", "Apple 风");

    companion object {
        fun from(key: String?): ThemeStyle =
            entries.firstOrNull { it.key == key } ?: Miui
    }
}

/**
 * Apple 风配色（iOS 系统色板）
 *
 * 取值来自 iOS HIG 的标准系统色：
 *   systemBlue   #007AFF
 *   systemGray6  #F2F2F7  ← 分组列表背景
 *   systemGray5  #E5E5EA  ← 分隔线
 *   label        #000000  ← 主文字
 *   secondaryLabel #3C3C43 @ 60%
 */
object AppleColors {
    // 主色
    val Blue = Color(0xFF007AFF)
    val Green = Color(0xFF34C759)
    val Orange = Color(0xFFFF9500)
    val Red = Color(0xFFFF3B30)
    val Purple = Color(0xFFAF52DE)
    val Teal = Color(0xFF5AC8FA)

    // 浅色模式
    val LightGroupedBackground = Color(0xFFF2F2F7)   // 分组页背景
    val LightCardBackground = Color(0xFFFFFFFF)      // 卡片
    val LightSeparator = Color(0xFFC6C6C8)           // 分隔线
    val LightLabel = Color(0xFF000000)               // 主文字
    val LightSecondaryLabel = Color(0x993C3C43)      // 次要文字（60%）
    val LightTertiaryLabel = Color(0x4D3C3C43)       // 三级文字（30%）
    val LightFill = Color(0x1F767680)                // 输入框填充（12%）

    // 深色模式
    val DarkGroupedBackground = Color(0xFF000000)
    val DarkCardBackground = Color(0xFF1C1C1E)
    val DarkCardBackgroundElevated = Color(0xFF2C2C2E)
    val DarkSeparator = Color(0xFF38383A)
    val DarkLabel = Color(0xFFFFFFFF)
    val DarkSecondaryLabel = Color(0x99EBEBF5)       // 60%
    val DarkTertiaryLabel = Color(0x4DEBEBF5)        // 30%
    val DarkFill = Color(0x1F767680)

    // 深色模式下的系统蓝（iOS 会调亮）
    val BlueDark = Color(0xFF0A84FF)
    val GreenDark = Color(0xFF30D158)
    val OrangeDark = Color(0xFFFF9F0A)
    val RedDark = Color(0xFFFF453A)
}

/**
 * MIUI / HyperOS 风配色
 *
 * 依据 HyperOS（小米澎湃 OS）系统设置页与 KernelSU 管理器的实际观感：
 *  - 强调色 #3482FF（HyperOS 系统蓝，比 MIUI 12 的 #3482FF 略亮）
 *  - 页面底色 #F5F5F5（浅灰，不是纯白；卡片才是纯白）
 *  - 卡片 #FFFFFF，深色 #1F1F1F
 *  - 文字 #000000 / 60% 黑
 *  - 分隔线 8% 黑，极淡
 *
 * 与原 Color.kt 的差异：
 *  - primary 从靛蓝 #4F46E5 改为 HyperOS 蓝 #3482FF
 *  - background 从冷白 #F8FAFC 改为中性灰 #F5F5F5
 */
object MiuiColors {
    // ★★★ 以下色值全部取自 Miuix 官方源码 ★★★
    //   仓库：compose-miuix-ui/miuix
    //   文件：miuix-ui/src/commonMain/kotlin/top/yukonga/miuix/kmp/theme/Colors.kt
    //   这就是 KernelSU 管理器用的那套 Compose 主题库。
    //
    //   注意它的层级和常规 Material 是反的：
    //     background = 纯白（整页底色）
    //     surface    = #F7F7F7（略暗的次级面）
    //     surfaceContainer = 纯白（卡片）
    //   所以「页面白 → 卡片白」靠的是圆角+分组间距区分，不是靠色差。

    // 浅色（官方 lightColorScheme 默认值）
    val Primary = Color(0xFF3482FF)          // primary
    val OnPrimary = Color(0xFFFFFFFF)        // onPrimary
    val PrimaryContainer = Color(0xFF5D9BFF) // primaryContainer
    val OnPrimaryContainer = Color(0xFFFFFFFF)

    val LightBackground = Color(0xFFFFFFFF)  // background = 纯白
    val LightSurface = Color(0xFFF7F7F7)     // surface
    val LightSurfaceContainer = Color(0xFFFFFFFF) // 卡片底
    val LightOutline = Color(0xFFD9D9D9)     // outline
    val LightDivider = Color(0xFFE0E0E0)     // dividerLine
    val LightOnSurface = Color(0xFF000000)
    val LightOnSurfaceSecondary = Color(0xCC000000) // onSurfaceSecondary 80%
    val LightOnSurfaceSummary = Color(0x99000000)   // onSurfaceVariantSummary 60%
    val LightSecondary = Color(0xFFE6E6E6)
    val LightTertiaryContainer = Color(0xFFEAF2FF)  // 浅蓝选中底

    // 深色（官方 darkColorScheme 默认值）
    val DarkPrimary = Color(0xFF277AF7)
    val DarkBackground = Color(0xFF000000)
    val DarkSurface = Color(0xFF1C1C1C)
    val DarkSurfaceContainer = Color(0xFF2A2A2A)
    val DarkOutline = Color(0xFF3A3A3A)
    val DarkDivider = Color(0xFF2E2E2E)
    val DarkOnSurface = Color(0xFFFFFFFF)
    val DarkOnSurfaceSecondary = Color(0xCCFFFFFF)
    val DarkOnSurfaceSummary = Color(0x99FFFFFF)
    val DarkSecondary = Color(0xFF505050)
    val DarkTertiaryContainer = Color(0xFF1A2A42)

    val Error = Color(0xFFE94634)            // 官方 error
}