package com.qtwl.gateway.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightStyle
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedCornerStyle
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.floor

data class GlassNavItem(
    val icon: ImageVector,
    val label: String
)

/**
 * 单个导航项的内容（图标 + 文字）。
 * 抽出来是为了让「灰色底」和「蓝色上层」复用同一份布局，
 * 保证两层像素级重合，蓝色只出现在被胶囊覆盖的那一段。
 */
@Composable
private fun TabContent(
    item: GlassNavItem,
    color: Color
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically)
    ) {
        Icon(
            imageVector = item.icon,
            contentDescription = item.label,
            tint = color,
            modifier = Modifier.size(23.dp)
        )
        Text(
            text = item.label,
            color = color,
            fontSize = 10.sp,
            lineHeight = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * iOS 27 风格 Liquid Glass 悬浮胶囊底部导航栏
 *
 * ★ 本版关键修正（对照 com.kyant.backdrop.catalog.components.LiquidBottomTabs 官方实现）：
 *
 * 1. 玻璃必须自己画底色 —— `onDrawSurface = { drawRect(containerColor) }`
 *    之前完全依赖采样背景，采到空白处就什么都看不见。
 *
 * 2. `vibrancy()` 要保留 —— 它提供玻璃的通透感，不是白边来源。
 *
 * 3. 白边的真正来源：`highlight` / `innerShadow` / `shadow` 三者的 alpha 一直是满值。
 *    官方写法是 `Highlight.Default.copy(alpha = progress)`，
 *    progress = 按压进度（0 = 未按）。**平时它们是 0，完全不存在，所以不会有白边。**
 *
 * 4. 形状用 `Capsule()`（官方），不是 RoundedCornerShape(percent = 50)。
 *
 * 5. 指示器采样 `rememberCombinedBackdrop(backdrop, tabsBackdrop)`，
 *    其中 tabsBackdrop 挂在 `.alpha(0f)` 的副层上 —— 该层不可见但可被采样，
 *    于是折射能拿到图标，又不会把图标画出来。
 *
 * 6. 指示器自身底色：浅色主题画 `Color.Black.copy(0.1f)`，按住时这层淡出，
 *    所以常态几乎无色（靠折射显示），按住才出现明显的玻璃与彩边。
 */
@Composable
fun LiquidGlassBottomNav(
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
    items: List<GlassNavItem>,
    modifier: Modifier = Modifier,
    backdrop: Backdrop? = null,
    /** 拉伸手感总开关：关掉后胶囊只做滑动，不拉伸、不越界回弹 */
    stretch: Boolean = true
) {
    if (items.isEmpty()) return

    // ★★★ 暗夜模式发白的根因 ★★★
    //   原来用 isSystemInDarkTheme()，它读的是【系统】设置。
    //   但应用内可以强制深色主题，此时系统仍报浅色 → 玻璃按浅色画 = 白板。
    //   改成直接看当前 colorScheme 的表面亮度，永远和实际配色一致。
    val isLight = MaterialTheme.colorScheme.surface.luminance() > 0.5f
    val accent = if (isLight) Color(0xFF007AFF) else Color(0xFF0A84FF)
    // ★★★ 透明度与折射的正确做法（对应用户反馈：太灰 / 按压没有液态玻璃效果）★★★
    //
    //   问题诊断：
    //     旧值 0xFFFDFDFF @ 0.88 —— 88% 不透明的白色实心层。
    //     这不是玻璃，这是一块刷了白漆的板：背景完全被挡住，所以「看着灰」。
    //     同时 press 只驱动了 shadow 和 scale，没有驱动任何光学效果，
    //     所以按下去只有缩放，没有液态折射。
    //
    //   修正：
    //     常态   alpha 0.30 —— 只留一层薄雾，背景透出来，看起来是透明玻璃
    //     按下   alpha 0.30 → 0.42，同时叠加 lens 折射 + vibrancy 提亮
    //     这样「平时透明、按下才有液态玻璃」就对上了。
    //
    //   注意 containerColor 必须是【随 press 变化】的，
    //   所以在 drawBackdrop 的 onDrawSurface 里用 press 做插值，
    //   不能在这里算成常量。
    // 去掉描边后，底色要更实一点，否则在花背景上文字会糊
    // ★ 胶囊「旁边那圈白」的真正来源就是这里。
    //   底栏底色是接近白的 #F7F9FC，即使只有 0.12，
    //   在浅色页面上也会让「胶囊以外、底栏以内」那一圈发白 → 看起来像给胶囊描了白边。
    //   现在压到 0.05（深色 0.20）：那一圈几乎只剩模糊后的背景，白感消失。
    val baseAlpha = if (isLight) 0.05f else 0.20f
    val containerColor = if (isLight) {
        // 不用纯白：灰色雾面在浅色页面上不会有「白块」观感
        Color(0xFFEFF1F5)
    } else {
        // 深色下玻璃必须偏黑，否则叠在深色背景上会发灰发白
        Color(0xFF141416)
    }
    // 按压时叠加的高光色（只做提亮，不做描边，所以不会形成白边）
    val pressTint = if (isLight) Color(0xFFFFFFFF) else Color(0xFF3A3A3C)

    val fallbackBackdrop = rememberLayerBackdrop()
    val contentBackdrop: Backdrop = backdrop ?: fallbackBackdrop
    // 副层采样源：alpha(0f) 不可见，但 layerBackdrop 能录到内容供折射使用
    val tabsBackdrop = rememberLayerBackdrop()

    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    // ★ 常驻动画对象：拖动/点击都只改 targetValue，绝不再 cancel 重建
    //   （旧写法每个 MOVE 事件都 cancel 一次，导致 pos 永远停在按下时的值，
    //     表现就是胶囊不动、只有高亮跟手、松手才跳过去）
    val posAnim = remember { Animatable(selectedTab.toFloat(), Float.VectorConverter) }
    var pos by remember { mutableFloatStateOf(selectedTab.toFloat()) }
    var posJob by remember { mutableStateOf<Job?>(null) }

    /**
     * ★ 关键修复：当前选中项必须用 rememberUpdatedState 读取。
     *
     * pointerInput 的 lambda 只在 key（格子数 / 格宽）变化时才重建，
     * 如果直接在 lambda 里写 selectedTab，捕获的是「创建那一刻」的值（通常是 0），
     * 之后永远不变 —— 表现就是：离开首页后，点首页算出的 target=0
     * 与这个陈旧的 0 相等，于是不回调 onTabSelected，页面回不去。
     */
    val currentSelected by rememberUpdatedState(selectedTab)

    // Animatable 的值 → 镜像到 pos，供绘制读取
    LaunchedEffect(posAnim) {
        snapshotFlow { posAnim.value }.collect { pos = it }
    }
    // 按压进度 0..1：驱动 highlight/shadow/scale，全部据此从 0 渐显
    var press by remember { mutableFloatStateOf(0f) }
    var pressJob by remember { mutableStateOf<Job?>(null) }
    // 拖动预览：只改高亮，放手才切页
    var previewIndex by remember { mutableIntStateOf(-1) }
    // 拖动速度（单位：格/秒），驱动拉伸形变
    var dragVel by remember { mutableFloatStateOf(0f) }
    /**
     * 上一次触发 posAnim 动画的目标格。
     * 用来避免每个 MOVE 事件都新建协程重启动画 —— 那会打断弹簧动量，
     * 手感变「顿」。只在目标变化超过 0.02 格时才重设。
     */
    var lastAnimTarget by remember { mutableFloatStateOf(-1f) }
    /**
     * 拉伸桥的起点（手指按下时胶囊所在的位置）。
     *
     * 苹果的分段控件不是「以中心放大」，而是从起点往目标方向拉出一条桥：
     * 宽度 = |当前 - 起点| + 一格，松手后缩回一格宽。
     * 这个值在按下时锁定，松手后归到目标格。
     */
    var stretchOrigin by remember { mutableFloatStateOf(selectedTab.toFloat()) }
    /**
     * ★ 是否正在「手指拖动」。
     *   桥接拉伸只能发生在拖动时。点一下换页时 pos 会从旧格滑到新格，
     *   如果这期间也按 |pos - 起点| + 1 算宽度，胶囊就会瞬间变成一条横跨全屏的长条
     *   —— 这就是「从设置点回主页，整个变成一根拉伸条」的 bug。
     */
    var dragging by remember { mutableStateOf(false) }

    /**
     * 长按计时协程。
     *
     * 为什么单独用一个 Job：长按需要在「手指按住不动」时也能触发，
     * 而这时候 pointer 事件循环里没有任何事件可等。
     * 之前用 withTimeoutOrNull 包 awaitPointerEvent 来做轮询，
     * 结果超时取消破坏了 pointer 的状态机，导致后续点击全部失效
     * （「点几下之后导航栏没反应」）。
     * 现在改成独立计时，事件循环保持纯粹的阻塞等待。
     */
    var longPressJob by remember { mutableStateOf<Job?>(null) }

    // 用 key 绑定 selectedTab：连点时旧的 LaunchedEffect 会被自动取消，
    // 保证任何时刻只有一个动画在驱动 pos，不会再出现「页面切了胶囊没跟上」
    // ★ 单击切页：用带轻微过冲的弹簧，产生「液态」的黏滞感。
    //   dampingRatio < 1 会有回弹（过冲再回落），这是阻尼感的来源。
    //   同时用 snapTo 作为起点对齐，避免动画叠加造成的「先顿一下再走」。
    LaunchedEffect(selectedTab) {
        val target = selectedTab.toFloat()
        val delta = abs(posAnim.value - target)
        // ★ 只在偏离明显时才补动画（> 0.02 格）
        //   如果松手时胶囊已经被拖到位（delta ≈ 0），这里就不动，
        //   避免「弹簧从 0 距离起跳」造成的抖动。
        //   注意阈值不能设成精确相等：浮点动画的终值可能有 1e-7 的残差，
        //   用 0.02（约 1dp）既安全又能吃掉这点残差。
        if (delta > 0.02f) {
            posAnim.animateTo(
                targetValue = target,
                animationSpec = spring(
                    dampingRatio = 0.92f,   // 几乎不过冲，落位干净
                    stiffness = 620f,
                    visibilityThreshold = 0.001f
                )
            )
        } else {
            // 已经很接近目标：直接吸附，保证像素级对齐
            posAnim.snapTo(target)
        }
    }

    Box(
        modifier = modifier
            .imePadding()
            .navigationBarsPadding()
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxWidth(),
            // 关键：默认 TopStart 会让胶囊贴顶，必须显式居中
            contentAlignment = Alignment.CenterStart
        ) {
            val barHeight = 64.dp
            val innerPad = 4.dp
            val tabCount = items.size
            val innerPadPx = with(density) { innerPad.toPx() }
            val innerWidthPx = with(density) { (maxWidth - innerPad * 2).toPx() }
            val tabWidthPx = innerWidthPx / tabCount
            val tabWidthDp = with(density) { tabWidthPx.toDp() }

            // ---------- ① 玻璃主体 ----------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(barHeight)
                    // 玻璃保持 64dp 满幅（前一版误把 padding 提前，导致外框缩成 56dp，整体变小）
                    .drawBackdrop(
                        backdrop = contentBackdrop,
                        shape = { Capsule(RoundedCornerStyle.Circular) },
                        // ★★★ 按压才出现液态折射 ★★★
                        //   press 是 0→1 的按压进度（按下 spring 到 1，松手回 0）。
                        //   - blur：常态 6dp 恒定 → 背景虚化，这是玻璃的底子
                        //   - vibrancy：0 → 0.35 * press，按下时提亮，模拟光线聚焦
                        //   - lens：0 → 1 * press，折射强度随按压渐入
                        //   平时 lens 强度为 0 = 完全不折射，所以不会有恒定边缘色差，
                        //   白边问题从根上消失；按下去才有液态折射。
                        effects = {
                            // ★ 磨砂质感靠的是「大半径模糊」。
                            //   8dp 还能看出背景的形状，20dp 才能糊成一片均匀的雾。
                            blur(20.dp.toPx())
                            // ★ iOS 的玻璃「一直」是厚的：边缘常年有折射，
                            //   只是幅度很小，所以不会形成白边。
                            //   按下（press）和拖动（dragVel）把折射放大，
                            //   产生「料体被挤压」的液态感。
                            val drag = (abs(dragVel) / 12f).coerceIn(0f, 0.65f)
                            // ★ 折射要「看得见」：之前 10dp/9dp 幅度太小，几乎等于没有。
                            //   苹果的液态玻璃边缘有一条明显被放大的背景带，
                            //   靠 refractionHeight（影响范围）和 refractionAmount（位移）
                            //   两个值一起给到 20dp 量级才看得出来。
                            //   chromaticAberration 打开 → 边缘带一点点彩散，更像真玻璃。
                            // 折射收小 + 关闭色散：
                            //   色散会在边缘描出一条彩边，视觉上又是一圈「框」，
                            //   磨砂玻璃本来就不该有明显色散。
                            lens(
                                refractionHeight = (12.dp + 16.dp * press + 10.dp * drag).toPx(),
                                refractionAmount = (9.dp + 16.dp * press + 10.dp * drag).toPx(),
                                chromaticAberration = false
                            )
                        },
                        // 高光：常态不画（不画 = 没有白边），
                        // 按下时才出现一条极窄的内高光，宽度随 press 增长。
                        // 注意 Highlight 返回可空对象，必须用 if 表达式返回 null 而不是 Unit。
                        highlight = {
                            if (press > 0.01f) {
                                Highlight(
                                    width = (1.2f * press).dp,
                                    blurRadius = 2.dp,
                                    alpha = 0.35f * press
                                )
                            } else {
                                null
                            }
                        },
                        // 外阴影：常态极淡（0.06），按下加强（0.06 → 0.18）
                        shadow = {
                            Shadow(
                                radius = (6.dp + 8.dp * press),
                                alpha = (0.03f + 0.10f * press)
                            )
                        },
                        // 内阴影：按压时加一点点，让玻璃有厚度
                        innerShadow = {
                            InnerShadow(
                                radius = (4.dp + 4.dp * press),
                                alpha = 0.05f * press
                            )
                        },
                        onDrawSurface = {
                            // 底色随按压变化：常态 0.30 → 按下 0.42
                            // 这是「灰」的直接来源，必须做成变量，不能是常量
                            val surfaceAlpha = baseAlpha + 0.12f * press
                            val path = androidx.compose.ui.graphics.Path().apply {
                                addRoundRect(
                                    androidx.compose.ui.geometry.RoundRect(
                                        left = 0f,
                                        top = 0f,
                                        right = size.width,
                                        bottom = size.height,
                                        radiusX = size.height / 2f,
                                        radiusY = size.height / 2f
                                    )
                                )
                            }
                            val h = size.height
                            val w = size.width
                            val radius = h / 2f
                            clipPath(path) {
                                // ① 本体：★ 均匀填充，不再做「上亮下亮」的渐变 ★
                                //
                                //   之前是 上 alpha×1.35 / 中 ×1.0 / 下 ×1.2 的竖向渐变，
                                //   本意是模拟玻璃厚度，实际效果是：
                                //   胶囊的上下边缘各亮一圈、中间偏暗 —— 在浅色背景上
                                //   就是一圈「白边」，而且是很宽的一大块，怎么删描边都还在。
                                //   现在整块一个透明度，边缘由模糊后的背景自己界定。
                                drawRect(color = containerColor.copy(alpha = surfaceAlpha))
                                // ② 顶部反光：整段删除。
                                //   磨砂玻璃的亮不是「画上去的白」，而是背景被模糊后透出来的雾。
                                //   任何叠加的白色渐变，最终都会变成一条看得见的白边。
                                // ③ 底缘内阴影 → 有厚度的实心感
                                drawRect(
                                    brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                                        0.78f to Color.Transparent,
                                        1f to Color.Black.copy(alpha = if (isLight) 0.03f else 0.10f)
                                    )
                                )
                                // ④⑤ 明确删除：不再有任何描边 / 暗环。
                                //
                                //   用户要的是「磨砂玻璃」，不是「画了边框的板子」。
                                //   之前那圈 1.5dp 白色渐变描边 + 2dp 暗环，
                                //   在深色和纯色背景上就是一圈白框，非常丑。
                                //   现在胶囊的边界完全由「模糊后的背景 + 薄雾底色」自己形成，
                                //   和 iOS 的 tab bar 一样：看得见轮廓，但看不见线。
                            }
                        }
                    )
                    // ★★★ 手势挂这里，不是挂在指示器上 ★★★
                    //
                    //   之前的致命错误：
                    //     .pointerInput 挂在指示器 Box 上，而那个 Box 只有 tabWidthDp 宽
                    //     （约 48dp），还用 translationX 移到了当前选中项的位置。
                    //     → 手指按在「设置」格时，触摸落不到指示器上，
                    //       整段手势代码根本不会被触发，长按当然没反应。
                    //
                    //   现在挂到满宽的玻璃主体 Row 上，五个格子都能接住触摸。
                    //   坐标全部用「相对玻璃主体左内边」的绝对坐标，
                    //   不再依赖 pos 做偏移，避免两套坐标系混用。
                    // key 用 Int 而不是 Float：
                    //   tabWidthPx 是 Float，屏幕旋转/分屏时浮点值会微抖动，
                    //   一旦 key 变化，pointerInput 会被销毁重建，
                    //   手势进行到一半就被打断（表现为「按着按着没反应了」）。
                    //   取整后只在真正的尺寸变化时才重启。
                    // ★★★ iOS 27 的「拉伸条」在这里画，而不是用一个会缩放的 Box ★★★
                    //
                    //   旧实现：指示器是个 Box，用 graphicsLayer.scaleX 拉伸。
                    //   问题有两个：
                    //     ① 缩放是以中心为原点 → 看起来像「原地变胖」，不是往目标方向拉
                    //     ② Box 里的图标会跟着被拉长，只能靠 1/scale 反向补偿，很脏
                    //
                    //   现在改成 drawBehind：直接在绘制阶段按坐标画一个胶囊，
                    //   宽度 = |pos - 起点| + 一格，左边 = 两点的较小者。
                    //   于是拖到哪就延伸到哪，松手缩回一格，这就是苹果那种「桥」。
                    .drawBehind {
                        val contentW = size.width - innerPadPx * 2
                        if (contentW <= 0f || tabCount <= 0) return@drawBehind
                        // 防御：弹簧动画在极小概率下会给出 NaN/Infinity，
                        // 一旦传进 drawRoundRect 会导致这一帧画不出来（表现为胶囊消失）。
                        if (!pos.isFinite() || !dragVel.isFinite()) return@drawBehind
                        val oneTab = contentW / tabCount
                        val pillH = size.height - innerPadPx * 2

                        // ★★★ 拉伸只能是「一小截」，不能连成进度条 ★★★
                        //
                        //   苹果的分段控件：胶囊永远是一格宽，只在朝运动方向的那一侧
                        //   鼓出 10~16%，一旦跨过一格立刻收住 —— 视觉上还是「一个块在滑」。
                        //
                        //   我上一版用 |pos - 起点| + 1 当宽度，等于让胶囊把起点和目标
                        //   之间全部涂满。从首页滑到设置（跨 4 格）就会变成一条横跨半屏、
                        //   中间不间断的长条 —— 那是进度条，不是胶囊。这是本次修正的重点。
                        val velAbs = (abs(dragVel) / 24f).coerceIn(0f, 1f)
                        val bulge = if (dragging && stretch) velAbs * 0.16f else 0f
                        var left = innerPadPx + pos * oneTab
                        var width = oneTab * (1f + bulge)
                        // 往左移动时，鼓出的部分在左边缘
                        if (dragVel < 0f) left -= oneTab * bulge
                        left = left.coerceIn(innerPadPx, (size.width - innerPadPx - width).coerceAtLeast(innerPadPx))
                        if (width <= 0f || pillH <= 0f) return@drawBehind
                        val radius = androidx.compose.ui.geometry.CornerRadius(pillH / 2f, pillH / 2f)
                        val tl = androidx.compose.ui.geometry.Offset(left, innerPadPx)
                        val sz = androidx.compose.ui.geometry.Size(width, pillH)

                        // ★ 长按变大：press（按压进度 0→1）驱动胶囊膨胀
                        //   苹果的小胶囊按下去会「鼓起来」一点，松手回弹。
                        //   宽度只加 5%，高度加 10% —— 再多就变成气球了。
                        val gw = width * (1f + 0.05f * press)
                        val gh = pillH * (1f + 0.10f * press)
                        val gLeft = left - (gw - width) / 2f
                        val gTop = innerPadPx - (gh - pillH) / 2f
                        val gTl = androidx.compose.ui.geometry.Offset(gLeft, gTop)
                        val gSz = androidx.compose.ui.geometry.Size(gw, gh)
                        val gRadius = androidx.compose.ui.geometry.CornerRadius(gh / 2f, gh / 2f)

                        // ① 底色：选中块要和未选中的格子有明确色差
                        drawRoundRect(
                            brush = androidx.compose.ui.graphics.Brush.horizontalGradient(
                                listOf(
                                    accent.copy(alpha = if (isLight) 0.24f else 0.30f),
                                    accent.copy(alpha = if (isLight) 0.15f else 0.21f),
                                    accent.copy(alpha = if (isLight) 0.24f else 0.30f)
                                )
                            ),
                            topLeft = gTl, size = gSz, cornerRadius = gRadius
                        )
                        // ② 已删除：胶囊上原本还有一层 0.16 白的竖向渐变，
                        //   它铺满胶囊顶部 60%，在浅色主题下就是一道白边。
                        //   现在胶囊本体只有半透明的主色，不加任何白色。

                        // ③★★ 彩色折射：只有按下去才出现 ★★
                        //
                        //   用户要的是「长按变大 + 有彩色折射」。
                        //   之前那圈 1.2dp 白色描边是【常态】就画着的，
                        //   所以在任何背景上都像给胶囊套了个白框 —— 已彻底删除。
                        //   现在边缘的颜色只在 press 时出现，用红/绿两条错位描边模拟色散：
                        //   玻璃厚的地方光线被拆开，边缘会偏色，这就是「液态」的来源。
                        if (press > 0.02f) {
                            val d = (0.9f * press).dp.toPx()
                            val strokeW = (1.3f * press).dp.toPx()
                            if (strokeW > 0.2f) {
                                // 暖侧
                                drawRoundRect(
                                    color = Color(0xFFFF453A).copy(alpha = 0.30f * press),
                                    topLeft = androidx.compose.ui.geometry.Offset(gLeft - d, gTop),
                                    size = gSz, cornerRadius = gRadius,
                                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeW)
                                )
                                // 冷侧
                                drawRoundRect(
                                    color = Color(0xFF32D74B).copy(alpha = 0.22f * press),
                                    topLeft = androidx.compose.ui.geometry.Offset(gLeft + d, gTop),
                                    size = gSz, cornerRadius = gRadius,
                                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeW)
                                )
                                // 外圈柔光，颜色散开成雾，不是硬边
                                drawRoundRect(
                                    brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                                        0f to Color.White.copy(alpha = 0.22f * press),
                                        0.5f to Color.Transparent,
                                        1f to Color.White.copy(alpha = 0.10f * press)
                                    ),
                                    topLeft = androidx.compose.ui.geometry.Offset(gLeft, gTop - d * 0.5f),
                                    size = gSz, cornerRadius = gRadius,
                                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeW * 1.6f)
                                )
                            }
                        }
                    }
                    .pointerInput(tabCount, tabWidthPx.toInt()) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            // 玻璃主体已 padding(innerPad)，Row 内坐标原点就是第 0 格左沿
                            // ★ 坐标系修正：pointerInput 的坐标原点在玻璃最左边，
                            //   而格子是从 innerPad 之后才开始的。
                            //   不减这一段，每次点击都会系统性偏左（越靠右越明显），
                            //   这就是「有些时候划不到对应位置 / 高亮错一格」的来源。
                            val downIdx = ((down.position.x - innerPadPx) / tabWidthPx)
                                .coerceIn(0f, (tabCount - 1).toFloat())
                            var dragIndex = downIdx
                            var dragged = false
                            var longPressHandled = false
                            val longPressMs = 400L

                            previewIndex = floor(downIdx + 0.5f).toInt().coerceIn(0, tabCount - 1)
                            // 重置动画目标记录：新一轮手势从当前位置起算
                            // （否则上一次手势留下的 -1 或旧值会让第一次 MOVE 被跳过）
                            lastAnimTarget = posAnim.value
                            stretchOrigin = posAnim.value
                            dragVel = 0f
                            // ★★★ 长按计时用独立协程，不占用事件循环 ★★★
                            //   按下时起一个 delay(400) 的协程，到点就把 longPressHandled 置 true；
                            //   松手时取消它。这样事件循环里 awaitPointerEvent 永远
                            //   是纯粹阻塞等待，不会因为 timeout 破坏 pointer 状态机。
                            longPressJob?.cancel()
                            longPressJob = scope.launch {
                                delay(longPressMs)
                                if (!dragged) {
                                    longPressHandled = true
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                }
                            }

                            // 按压进度：0 → 1，驱动玻璃折射/高光/阴影
                            pressJob?.cancel()
                            pressJob = scope.launch {
                                animate(
                                    initialValue = press,
                                    targetValue = 1f,
                                    animationSpec = spring(stiffness = Spring.StiffnessHigh)
                                ) { v, _ -> press = v }
                            }

                            while (true) {
                                // ★★★ 这里不能用 withTimeoutOrNull 包 awaitPointerEvent ★★★
                                //
                                //  之前写成 withTimeoutOrNull(60L) { awaitPointerEvent() }，
                                //  想用超时轮询来检查长按时间。这是错的：
                                //  AwaitPointerEventScope 的挂起是「事件驱动的状态机」，
                                //  被 timeout 取消后，这个 scope 的内部状态（按键计数、
                                //  当前手势的 pointer 集合）会被破坏，
                                //  后续 awaitPointerEvent 收不到正常事件，
                                //  表现就是「点几下之后整个导航栏没反应了」。
                                //
                                //  正确做法：长按用一个独立的协程计时，事件循环本身
                                //  保持纯粹阻塞等待，不做任何超时包装。
                                val event = awaitPointerEvent(PointerEventPass.Main)

                                // ★ 找不到本指针的 change 时不能 break。
                                //   UP 事件的 PointerId 可能已经被系统回收，
                                //   直接 break 会跳过下面的收尾逻辑（onTabSelected 不执行），
                                //   表现为「点了没反应」。
                                //   改成：没有本指针事件时先检查是否有指针抬起，
                                //   有就正常走收尾；否则跳过多余事件继续等。
                                val change = event.changes.firstOrNull { it.id == down.id }
                                if (change == null) {
                                    val anyUp = event.changes.any { it.changedToUpIgnoreConsumed() }
                                    if (anyUp) break
                                    continue
                                }

                                // 松手
                                if (change.changedToUpIgnoreConsumed()) {
                                    // ★ 关键：必须先 consume 掉 UP 事件再 break
                                    //   awaitEachGesture 的语义是「手势负责到底」，
                                    //   如果 UP 没被消费，Compose 认为这次手势没结束，
                                    //   下一次 pointerInput 不会重新 awaitFirstDown，
                                    //   结果就是「第一次点有反应，之后全没反应」。
                                    change.consume()
                                    // ★ 取消长按计时（已经松手了，不该再触发）
                                    longPressJob?.cancel()
                                    longPressJob = null
                                    break
                                }

                                // 拖动检测
                                val dx = change.positionChange().x
                                if (!dragged && abs(dx) > 3f) {
                                    dragged = true
                                    dragging = true
                                }

                                if (dragged || longPressHandled) {
                                    change.consume()
                                    // 绝对坐标：手指当前落在哪一格
                                    // 同一个坐标系修正（拖动跟手）
                                    // ★ 橡皮筋阻尼：iOS 的「拉扯感」= 越界后位移按比例衰减，
                                    //   越拖越沉，松手弹回。这里系数 0.34，再叠加一层
                                    //   位置越远越硬的衰减，手感接近系统。
                                    val raw = (change.position.x - innerPadPx) / tabWidthPx
                                    val lastIdx = (tabCount - 1).toFloat()
                                    val idx = if (stretch) when {
                                        raw < 0f -> raw * 0.18f
                                        raw > lastIdx -> lastIdx + (raw - lastIdx) * 0.18f
                                        else -> raw
                                    } else raw.coerceIn(0f, lastIdx)

                                    // ★ 速度必须用「手指上一帧的格位置」算，不能用 posAnim.value
                                    //
                                    //  旧写法：dragVel = (idx - posAnim.value) * 2.5f
                                    //  posAnim 是弹簧，永远在追 idx，所以 (idx - posAnim.value)
                                    //  是「手指和胶囊的滞后差」，不是手指速度。
                                    //  结果：手指匀速平移时，dragVel 会随弹簧追赶而剧烈震荡，
                                    //  表现为胶囊在拖动中忽胖忽瘦（拉伸形变抖动）。
                                    //
                                    //  正确做法：用上一帧手指所在格（dragIndex）的差值。
                                    val prevIdx = dragIndex
                                    dragVel = (idx - prevIdx) * 60f   // 按 60fps 估算「格/秒」
                                    dragIndex = idx

                                    // 只在目标格变化时才重启动画，避免每个 MOVE 事件都 launch
                                    // （每帧新建协程会打断弹簧的动量，手感变「顿」）
                                    if (abs(idx - lastAnimTarget) > 0.02f) {
                                        lastAnimTarget = idx
                                        posJob = scope.launch {
                                            posAnim.animateTo(
                                                targetValue = idx,
                                                animationSpec = spring(
                                                    // 拖动期间必须「贴死手指」：
                                                    // 临界阻尼 + 高刚度，不做任何插值滞后。
                                                    // 苹果拖动时胶囊和手指几乎零延迟，
                                                    // 之前的 0.88/620 会明显落后半格。
                                                    dampingRatio = 1f,
                                                    stiffness = 1500f,
                                                    visibilityThreshold = 0.001f
                                                )
                                            )
                                        }
                                    }
                                    previewIndex = floor(idx + 0.5f).toInt()
                                        .coerceIn(0, tabCount - 1)
                                }
                            }

                            // 松手：按压进度归零
                            pressJob?.cancel()
                            pressJob = scope.launch {
                                animate(
                                    initialValue = press,
                                    targetValue = 0f,
                                    animationSpec = spring(stiffness = Spring.StiffnessMedium)
                                ) { v, _ -> press = v }
                            }
                            // 速度归零（解除拉伸形变）
                            scope.launch {
                                animate(
                                    initialValue = dragVel,
                                    targetValue = 0f,
                                    animationSpec = spring(dampingRatio = 0.6f, stiffness = 250f)
                                ) { v, _ -> dragVel = v }
                            }
                            previewIndex = -1
                            // ★ 跳转判定：拖动过、长按过、或者只是轻点，都要切页
                            val target = floor(dragIndex + 0.5f).toInt()
                                .coerceIn(0, tabCount - 1)

                            // ★★★ 修复「切走后回不了首页」★★★
                            //
                            //  旧代码这里有两处冲突：
                            //    ① 先 posJob = scope.launch { posAnim.animateTo(target) }
                            //    ② 紧接着 onTabSelected(target) → selectedTab 变化
                            //       → LaunchedEffect(selectedTab) 又启动一个 animateTo
                            //    两个动画同时往 posAnim 上写 targetValue，
                            //    后启动的会取消前一个，但取消时机不确定，
                            //    胶囊可能停在两格之间的中间位置。
                            //
                            //  更严重的是：如果 target == selectedTab（点当前格），
                            //  不会调用 onTabSelected，但 posAnim 已经被上面那个
                            //  launch 改过 targetValue —— 而 selectedTab 没变，
                            //  LaunchedEffect 不会重新触发，胶囊就永久停在错位处。
                            //
                            //  修法：
                            //    · 只在 target 真的不同时才启动动画 + 回调
                            //    · 交给 LaunchedEffect(selectedTab) 统一驱动动画，
                            //      这里不再自己 animateTo，避免两个源头
                            // ★ 统一处理：
                            //   ① 用 currentSelected（最新值）而不是闭包里的 selectedTab
                            //   ② 永远回调 onTabSelected —— 即使点的是当前高亮格，
                            //      也可能是「从二级页返回主标签」，此时 MainScreen 需要收到这次点击
                            val nowSelected = currentSelected
                            // 松手：结束桥接，起点归到目标格
                            dragging = false
                            stretchOrigin = target.toFloat()
                            posJob?.cancel()
                            onTabSelected(target)
                            if (target == nowSelected) {
                                // 点了当前格：只把胶囊归位到当前格，不切页
                                // （拖动后又拖回原格的情况）
                                posJob?.cancel()
                                posJob = scope.launch {
                                    posAnim.animateTo(
                                        targetValue = target.toFloat(),
                                        animationSpec = spring(
                                            dampingRatio = 0.75f,
                                            stiffness = 400f,
                                            visibilityThreshold = 0.001f
                                        )
                                    )
                                }
                            }
                        }
                    }
                    .padding(innerPad),
                verticalAlignment = Alignment.CenterVertically
            ) {
                items.forEachIndexed { index, item ->
                    // ★ 卡顿修复：组合阶段只读低频状态（selectedTab），不读每帧变化的 pos。
                    //   原来这里算了 overlaps（依赖 pos），导致 5 个格子每帧全部重组 → 掉帧。
                    val idleColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .clip(Capsule(RoundedCornerStyle.Circular)),
                        // ★ 这里不再挂 clickable。
                        //   上一版每个格子自己带 clickable，和父层的 pointerInput 抢事件：
                        //   clickable 会消费 MOVE/UP，父层的长按循环收不到 changedToUp，
                        //   导致「长按拖到首页」这条路径永远走不完。
                        //   现在点击/拖动/长按统一由玻璃主体上那个 pointerInput 处理。
                        contentAlignment = Alignment.Center
                    ) {
                        // ① 灰色底层：始终完整显示
                        //   ★ 反向补偿：指示器 Box 有 graphicsLayer 缩放，
                        //     里面所有内容都会被拉伸。这里按 1/scale 抵消回去，
                        //     图标就始终保持原始大小。
                        //     （旧版没有补偿，所以胶囊覆盖到的那个图标看着会变大）
                        val invScale = 1f / (1f + 0.06f * press)
                        Box(
                            modifier = Modifier.graphicsLayer {
                                scaleX = invScale
                                scaleY = invScale
                            }
                        ) {
                            TabContent(item = item, color = idleColor)
                        }

                        // ② 蓝色上层：只在「胶囊覆盖到本格的那一段」显示。
                        //    pos 在 drawWithContent 里读取 —— 属于绘制阶段，
                        //    只触发重绘、不触发重组，所以不会掉帧。
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .drawWithContent {
                                    val tabW = tabWidthPx
                                    if (tabW <= 0f) return@drawWithContent
                                    // 胶囊区间（父坐标系）
                                    val capLeft = pos * tabW
                                    val capRight = capLeft + tabW
                                    // 本格区间（父坐标系）
                                    val cellLeft = index * tabW
                                    val cellRight = cellLeft + tabW
                                    // 交集
                                    val l = maxOf(capLeft, cellLeft)
                                    val r = minOf(capRight, cellRight)
                                    if (r <= l) return@drawWithContent
                                    // 换算成本格内的像素坐标
                                    val from = (l - cellLeft).coerceIn(0f, size.width)
                                    val to = (r - cellLeft).coerceIn(0f, size.width)
                                    if (to <= from) return@drawWithContent
                                    clipRect(left = from, right = to) {
                                        this@drawWithContent.drawContent()
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier.graphicsLayer {
                                    // 与灰色底层同样的反向补偿
                                    scaleX = invScale
                                    scaleY = invScale
                                }
                            ) {
                                TabContent(item = item, color = accent)
                            }
                        }
                    }
                }
            }

            // ---------- ② 不可见副层：只用于给指示器提供"纯内容"采样 ----------
            Row(
                modifier = Modifier
                    .alpha(0f)
                    .fillMaxWidth()
                    .height(barHeight)
                    // 与图标行完全一致：同样的 height + padding，
                    // 这样 layerBackdrop 录到的坐标与真实渲染严格对齐，折射不会错位
                    .padding(innerPad)
                    .layerBackdrop(tabsBackdrop),
                verticalAlignment = Alignment.CenterVertically
            ) {
                items.forEachIndexed { index, item ->
                    // ★ 副层已不再被指示器采样（指示器只采样 contentBackdrop），
                    //   这里只保留稳定的静态内容，彻底去掉对 pos 的依赖，
                    //   避免每帧重组拖慢主线程。
                    val iconColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically)
                        ) {
                            Icon(
                                imageVector = item.icon,
                                contentDescription = null,
                                tint = iconColor,
                                modifier = Modifier.size(23.dp)
                            )
                            Text(
                                text = item.label,
                                color = iconColor,
                                fontSize = 10.sp,
                                lineHeight = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }

        }
    }
}