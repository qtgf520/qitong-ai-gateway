package com.qtwl.gateway.ui.theme

import android.app.Activity
import android.content.Context
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * 主题入口（双风格）
 *
 * ============================================================
 * 两套风格怎么共存
 * ============================================================
 *  ThemeStyle.Miui  → 靛蓝 + 白底，圆角大，色块分层（当前观感，默认）
 *  ThemeStyle.Apple → 系统蓝 + 灰分组，白卡片，细分隔线（iOS 观感）
 *
 *  切换方式：ThemeState.style 是全局可变状态，
 *  设置页改它 → 整个 App 重组换肤，不需要重启。
 *
 *  dynamicColor 默认关掉：Material You 取色会把 primary 换成壁纸色，
 *  那样两套风格就都失去辨识度了。
 */

/** 主题全局状态：风格 + 明暗 */
object ThemeState {
    /** 当前风格，默认 MIUI（保持用户已认可的观感） */
    var style by mutableStateOf(ThemeStyle.Miui)
        private set

    /** 是否深色。null = 跟随系统 */
    var dark by mutableStateOf<Boolean?>(null)
        private set

    /** 从 SharedPreferences 载入（App 启动时调一次） */
    fun load(context: Context) {
        val sp = context.getSharedPreferences("qitong_theme", Context.MODE_PRIVATE)
        style = ThemeStyle.from(sp.getString("style", ThemeStyle.Miui.key))
        dark = when (sp.getString("dark", "system")) {
            "light" -> false
            "dark" -> true
            else -> null
        }
    }

    fun setStyle(context: Context, s: ThemeStyle) {
        style = s
        context.getSharedPreferences("qitong_theme", Context.MODE_PRIVATE)
            .edit().putString("style", s.key).apply()
    }

    /** null = 跟随系统 */
    fun setDark(context: Context, d: Boolean?) {
        dark = d
        val v = when (d) {
            true -> "dark"
            false -> "light"
            null -> "system"
        }
        context.getSharedPreferences("qitong_theme", Context.MODE_PRIVATE)
            .edit().putString("dark", v).apply()
    }
}

// ---------------- MIUI 风色板 ----------------

private val MiuiLight = lightColorScheme(
    primary = MiuiColors.Primary,
    onPrimary = MiuiColors.OnPrimary,
    primaryContainer = MiuiColors.PrimaryContainer,
    onPrimaryContainer = MiuiColors.OnPrimaryContainer,
    secondary = MiuiColors.LightSecondary,
    onSecondary = MiuiColors.LightOnSurface,
    // ★ Miuix 的层级（官方 Colors.kt）：
    //   background = 纯白（整页底）
    //   surface    = #F7F7F7（次级面）
    //   surfaceVariant / surfaceContainer = 纯白（卡片）
    //   tertiaryContainer = #EAF2FF（浅蓝选中底）
    background = MiuiColors.LightBackground,
    onBackground = MiuiColors.LightOnSurface,
    surface = MiuiColors.LightSurface,
    onSurface = MiuiColors.LightOnSurface,
    surfaceVariant = MiuiColors.LightSurfaceContainer,
    onSurfaceVariant = MiuiColors.LightOnSurfaceSummary,
    tertiaryContainer = MiuiColors.LightTertiaryContainer,
    outline = MiuiColors.LightOutline,
    error = MiuiColors.Error
)

private val MiuiDark = darkColorScheme(
    // 深色下 Miuix 用的是 #277AF7，比浅色的 #3482FF 略深
    primary = MiuiColors.DarkPrimary,
    onPrimary = MiuiColors.OnPrimary,
    primaryContainer = MiuiColors.PrimaryContainer,
    onPrimaryContainer = MiuiColors.OnPrimaryContainer,
    secondary = MiuiColors.DarkSecondary,
    onSecondary = MiuiColors.DarkOnSurface,
    background = MiuiColors.DarkBackground,
    onBackground = MiuiColors.DarkOnSurface,
    surface = MiuiColors.DarkSurface,
    onSurface = MiuiColors.DarkOnSurface,
    // 深色下卡片比页面亮一档：#2A2A2A
    surfaceVariant = MiuiColors.DarkSurfaceContainer,
    onSurfaceVariant = MiuiColors.DarkOnSurfaceSummary,
    tertiaryContainer = MiuiColors.DarkTertiaryContainer,
    outline = MiuiColors.DarkOutline,
    error = MiuiColors.Error
)

// ---------------- Apple 风色板 ----------------

private val AppleLight = lightColorScheme(
    primary = AppleColors.Blue,
    onPrimary = Color.White,
    secondary = AppleColors.Green,
    onSecondary = Color.White,
    // iOS 的层级关系：
    //   background  = #F2F2F7 分组灰（整页底色）
    //   surface     = #FFFFFF 白卡片
    //   surfaceVariant = 卡片内的次级块（例如输入框底、嵌套区块），
    //                    iOS 用 #F2F2F7 或 12% 灰填充
    //
    //   注意：surfaceVariant 不能也设成纯白，
    //   否则「用 surfaceVariant 做背景的组件」和「白卡片」无法区分，
    //   整个界面会糊成一片白。
    background = AppleColors.LightGroupedBackground,
    onBackground = AppleColors.LightLabel,
    surface = AppleColors.LightCardBackground,
    onSurface = AppleColors.LightLabel,
    surfaceVariant = AppleColors.LightGroupedBackground,
    onSurfaceVariant = AppleColors.LightSecondaryLabel,
    outline = AppleColors.LightSeparator,
    error = AppleColors.Red
)

private val AppleDark = darkColorScheme(
    primary = AppleColors.BlueDark,
    onPrimary = Color.White,
    secondary = AppleColors.GreenDark,
    onSecondary = Color.Black,
    background = AppleColors.DarkGroupedBackground,
    onBackground = AppleColors.DarkLabel,
    surface = AppleColors.DarkCardBackground,
    onSurface = AppleColors.DarkLabel,
    surfaceVariant = AppleColors.DarkCardBackgroundElevated,
    onSurfaceVariant = AppleColors.DarkSecondaryLabel,
    outline = AppleColors.DarkSeparator,
    error = AppleColors.RedDark
)

@Composable
fun GatewayTheme(
    darkTheme: Boolean = ThemeState.dark ?: isSystemInDarkTheme(),
    /**
     * 动态取色默认关闭。
     * 原因见文件头：Material You 会覆盖 primary，两套风格就都失去辨识度。
     */
    dynamicColor: Boolean = false,
    style: ThemeStyle = ThemeState.style,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        style == ThemeStyle.Apple && darkTheme -> AppleDark
        style == ThemeStyle.Apple -> AppleLight
        darkTheme -> MiuiDark
        else -> MiuiLight
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // 状态栏颜色跟随主题背景，而不是 primary。
            // 之前用 primary 会形成一条蓝带，和删掉 TopAppBar 后的观感冲突。
            window.statusBarColor = colorScheme.background.toArgb()
            window.navigationBarColor = colorScheme.background.toArgb()
            val ctrl = WindowCompat.getInsetsController(window, view)
            ctrl.isAppearanceLightStatusBars = !darkTheme
            ctrl.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = {
            // ★ MIUIx 风格下再套一层官方的 MiuixTheme
            //   这样后续用 Miuix 组件（Card / SmallTitle / BasicComponent）
            //   时会自动拿到官方色板，不需要每个组件单独传色值。
            //   Apple 风格不套，继续用 Material3。
            if (style == ThemeStyle.Miui) {
                top.yukonga.miuix.kmp.theme.MiuixTheme(
                    colors = if (darkTheme) {
                        top.yukonga.miuix.kmp.theme.darkColorScheme()
                    } else {
                        top.yukonga.miuix.kmp.theme.lightColorScheme()
                    },
                    content = content
                )
            } else {
                content()
            }
        }
    )
}