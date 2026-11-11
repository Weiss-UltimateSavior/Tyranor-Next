package com.tyranor.next.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import com.tyranor.next.ui.common.notice.NoticeHost
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.tyranor.next.R
import com.tyranor.next.theme.AppComponentShape
import com.tyranor.next.theme.AppThemeColors
import com.tyranor.next.theme.GlassPanel
import com.tyranor.next.theme.MilkGlassPanelBackground
import com.tyranor.next.theme.NavWhite
import com.tyranor.next.theme.TextColor
import com.tyranor.next.theme.glassBorder
import com.tyranor.next.ui.common.motion.MotionHaptics
import com.tyranor.next.ui.common.motion.MotionIntent
import com.tyranor.next.ui.common.motion.MotionMode
import com.tyranor.next.ui.common.motion.MotionSprings
import com.tyranor.next.ui.common.motion.MotionTokens
import com.tyranor.next.ui.common.motion.Bind
import com.tyranor.next.ui.common.motion.ProvideEntrance
import com.tyranor.next.ui.common.motion.SheetBackdrop
import com.tyranor.next.ui.common.motion.passThroughTouches
import com.tyranor.next.ui.common.motion.releaseFocusForExit
import com.tyranor.next.ui.common.motion.restoreInteraction
import com.tyranor.next.ui.common.motion.rememberEntranceState
import com.tyranor.next.ui.common.motion.skipEntranceOnDrag
import com.tyranor.next.ui.common.motion.staggeredEntrance
import com.tyranor.next.ui.common.motion.DialogWindowScrim
import kotlinx.coroutines.launch
import kotlin.math.abs

/** 侧边栏面板宽度（固定值，后续按内容再调）。 */
private val SidePanelWidth = 250.dp

/** 面板内条目之间的统一纵向间距（与弹窗内 AppNavItem 列表一致）。 */
private val SidePanelItemSpacing = 8.dp

/** 面板与屏幕上、下边缘的间距（上下等距，保证面板垂直居中）。 */
private val SidePanelVerticalMargin = 30.dp

/** 右滑识别区起点内缩（与屏幕左缘留出的距离）。 */
private val SidePanelSwipeStartInset = 5.dp

/** 识别区右边界占屏幕宽度的比例（自屏幕左缘起算，取 1/4 屏宽）。 */
private const val SidePanelSwipeWidthFraction = 0.25f

/** 左缘系统返回手势排除条宽度（从 [SidePanelSwipeStartInset] 起算，覆盖系统返回手势边缘区）。 */
private val SidePanelEdgeExclusionWidth = 25.dp

/** 向右滑动触发唤出的阈值。 */
private val SidePanelSwipeThreshold = 40.dp

/** 唤出要求横向位移至少是纵向位移的这么多倍（斜着划 / 竖向为主的手势不是唤出手势）。 */
private const val SidePanelSwipeRatio = 2f

/**
 * 应用侧边栏（空白骨架 + 顶部标题）：主界面四大页面通过**左缘右滑**唤出，自左侧滑入的浮动面板。
 *
 * 几何与材质约定：
 * - 四边圆角（[AppComponentShape]）；水平、垂直均居中：上下各留 [SidePanelVerticalMargin]
 *   （同时作为面板最大高度的预留），左右留白由居中平分；宽度固定 [SidePanelWidth]；
 * - 用 [Dialog] 独立窗口实现（`decorFitsSystemWindows = false` 的全屏窗口）：侧边栏需要盖住
 *   整窗（含状态栏、底部导航栏与平板侧栏），并与 [AppAlertDialog] 共用
 *   「遮罩点击 / 返回键关闭 + 进出动画」宿主；
 * - 面板材质沿用应用弹窗/抽屉的玻璃适配：默认 [NavWhite]、复古玻璃 [GlassPanel]；
 * - 顶部固定标题（[R.string.side_panel_title]，水平居中），下方 [content] 为功能条目区：
 *   条目之间统一加 [SidePanelItemSpacing] 纵向间距（调用方不要自加外边距）；条目仍必须
 *   使用 AppNavItem 等统一组件（弹窗内传 `DialogItemSurface`）。
 * - 面板高度随内容自适应（不再强制满高）；条目总高超过可用高度时，仅条目区滚动，
 *   标题保持固定，横屏/小高度窗口下不会出现条目被截断且无法滑动的情况。
 */
@Composable
internal fun AppSidePanel(
    open: Boolean,
    onDismiss: () -> Unit,
    /** 背后页面的景深句柄（见 [SheetBackdrop]）：侧栏展开时页面随遮罩进度绕右缘翻折并褪色；不传则页面不翻折。 */
    backdrop: SheetBackdrop? = null,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    // 「增强」动效档：外部把 open 置为 false（例如点条目跳转到其它页面）时，面板先滑出再移除，
    // 而不是整窗瞬间消失；「普通」档保持原版立即移除
    var mounted by remember { mutableStateOf(open) }
    LaunchedEffect(open) { if (open) mounted = true }
    val show = open || (mounted && MotionMode.enhanced)
    if (!show) {
        if (mounted) mounted = false
        return
    }
    SidePanelWindow(
        closeRequested = !open,
        onClosed = { mounted = false },
        onDismiss = onDismiss,
        backdrop = backdrop,
        content = content,
    )
}

/**
 * 承载侧栏独立窗口与同步遮罩；增强档外部关闭完成退场后才移除，重新打开可反向接续。
 * 普通档沿用原版进退场，关闭时两档都释放窗口触摸，避免残留遮罩阻塞页面。
 */
@Composable
private fun SidePanelWindow(
    closeRequested: Boolean,
    onClosed: () -> Unit,
    onDismiss: () -> Unit,
    backdrop: SheetBackdrop?,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scope = rememberCoroutineScope()
    val dimAlpha = remember { Animatable(0f) }
    // 背后页面随遮罩进度后退（遮罩平滑不回弹；面板本体的进场带回弹，不拿它驱动页面）
    backdrop?.Bind { dimAlpha.value.coerceIn(0f, 1f) }
    // 1f = 完全移出屏幕左侧，0f = 就位
    val slideFraction = remember { Animatable(1f) }
    val dismissing = remember { mutableStateOf(false) }
    val dialogWindow = remember { mutableStateOf<android.view.Window?>(null) }
    val currentOnDismiss by rememberUpdatedState(onDismiss)

    // 点遮罩 / 返回键关闭（快速滑出），区别于条目跳转时的交接滑出
    val quickExit = remember { BooleanArray(1) }

    /** 遮罩或返回触发的快速关闭；增强档先通知宿主，以允许退场途中重新唤出。 */
    fun dismiss() {
        if (dismissing.value) return
        dismissing.value = true
        dialogWindow.value.passThroughTouches()
        if (MotionMode.enhanced) {
            // 增强档：立即把 open 置为 false，滑出交给下方的 LaunchedEffect(closeRequested) 播放。
            // 这样滑出途中再次边缘右滑能把 open 改回 true，从当前位置重新滑入；否则这次右滑会因 open 仍为 true 而被吞掉
            quickExit[0] = true
            dialogWindow.value.releaseFocusForExit()
            currentOnDismiss()
            return
        }
        scope.launch {
            launch { dimAlpha.animateTo(0f, tween(200, easing = FastOutLinearInEasing)) }
            slideFraction.animateTo(1f, tween(220, easing = FastOutLinearInEasing))
            currentOnDismiss()
        }
    }

    Dialog(
        onDismissRequest = { dismiss() },
        // 全屏窗口：面板可延伸到系统栏区域，遮罩覆盖整屏
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(Modifier.fillMaxSize()) {
            val safe = if (isTabletScreen()) WindowInsets.safeDrawing else null
            DialogWindowScrim(
                enhanced = MotionMode.enhanced,
                progress = { dimAlpha.value },
                onWindow = { dialogWindow.value = it },
                onDismissRequest = { dismiss() },
            ) {
                Card(
                    modifier = Modifier
                        .positionSidePanel(safe) { slideFraction.value }
                        .width(SidePanelWidth)
                        // 消费面板区域点击，避免穿透到遮罩
                        .pointerInput(Unit) { detectTapGestures { } }
                        .glassBorder(),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = when {
                            AppThemeColors.isGlass -> GlassPanel
                            AppThemeColors.isMilkGlass -> Color.Transparent
                            else -> NavWhite
                        },
                        contentColor = TextColor,
                    ),
                    shape = AppComponentShape,
                ) {
                    Box(Modifier.fillMaxWidth().clip(AppComponentShape)) {
                        if (AppThemeColors.isMilkGlass) {
                            MilkGlassPanelBackground(Modifier.matchParentSize())
                        }
                        // 顶部标题（水平居中）+ 功能条目区（条目之间统一间距）
                        // 每次打开时标题与条目区依次弹入
                        ProvideEntrance(rememberEntranceState()) {
                            Column(modifier = Modifier.fillMaxWidth().skipEntranceOnDrag().padding(horizontal = 12.dp)) {
                                Text(
                                    text = stringResource(R.string.side_panel_title),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier
                                        .staggeredEntrance(0)
                                        .fillMaxWidth()
                                        .padding(top = 18.dp, bottom = 10.dp),
                                )
                                // 标题固定，条目区在剩余高度内滚动：横屏/小高度窗口下列表不再被卡片圆角截断
                                Column(
                                    modifier = Modifier
                                        .staggeredEntrance(1)
                                        .fillMaxWidth()
                                        .weight(1f, fill = false)
                                        .verticalScroll(rememberScrollState())
                                        .padding(bottom = 14.dp),
                                    verticalArrangement = Arrangement.spacedBy(SidePanelItemSpacing),
                                ) {
                                    if (MotionMode.enhanced) {
                                        // 「增强」档：条目随面板滑动逐条「拖尾」——越靠下的条目落后面板越多，
                                        // 面板滑入时像扇面依次展开、到位随面板回弹一起落稳；滑出时依次先走。
                                        // 位移只由面板进度推出（不另起动画），停稳时为 0，与原样逐像素一致
                                        val columnScope = this
                                        SidePanelTrailingItems(slideFraction = { slideFraction.value }) {
                                            columnScope.content()
                                        }
                                    } else {
                                        content()
                                    }
                                }
                            }
                        }
                    }
                }
            }
            NoticeHost(Modifier.matchParentSize())
        }
    }

    // 外部关闭（open 变为 false，例如条目跳转）：与点遮罩关闭同一段滑出，结束后再移除窗口
    LaunchedEffect(closeRequested) {
        if (!closeRequested) {
            // 滑出途中被重新打开：撤销关闭状态并从当前位置回到就位
            if (dismissing.value) {
                dismissing.value = false
                quickExit[0] = false
                dialogWindow.value.restoreInteraction()
                launch { dimAlpha.animateTo(1f, MotionTokens.fadeIn(0.001f)) }
                slideFraction.animateTo(0f, SidePanelEnterSpec)
            }
            return@LaunchedEffect
        }
        if (quickExit[0]) {
            launch { dimAlpha.animateTo(0f, MotionTokens.fadeOut(0.001f)) }
            slideFraction.animateTo(1f, MotionTokens.surfaceExit(0.002f))
        } else if (!dismissing.value) {
            dismissing.value = true
            dialogWindow.value.passThroughTouches()
            dialogWindow.value.releaseFocusForExit()
            // 外部关闭来自条目跳转：交接滑出，时长与页面侧推相当，面板向左滑出与新页面从右推入同时进行
            launch { dimAlpha.animateTo(0f, MotionSprings.effects(420, 0.001f)) }
            slideFraction.animateTo(1f, MotionSprings.smooth(500, 0.002f))
        }
        onClosed()
    }

    // 进入动画：遮罩渐入 + 面板自左边缘滑入
    LaunchedEffect(Unit) {
        if (MotionMode.enhanced) {
            launch { dimAlpha.animateTo(1f, MotionTokens.fadeIn(0.001f)) }
            slideFraction.animateTo(targetValue = 0f, animationSpec = SidePanelEnterSpec)
        } else {
            launch { dimAlpha.animateTo(1f, tween(250, easing = LinearOutSlowInEasing)) }
            slideFraction.animateTo(
                targetValue = 0f,
                animationSpec = spring(dampingRatio = 0.88f, stiffness = 450f, visibilityThreshold = 0.0001f),
            )
        }
    }
}

/**
 * 侧栏面板的进场弹簧：响应 0.44s、阻尼 0.78（只轻轻冲过一点再落稳）。
 * 通用的大面板进场（[MotionTokens.surfaceEnter]，阻尼 0.2）弹得太狠，侧栏是从屏幕侧边滑出的工具面板，
 * 来回晃好几下反而显得轻浮；与抽屉的克制回弹统一。
 */
private val SidePanelEnterSpec = MotionSprings.response<Float>(0.44f, 0.78f, 0.0001f)

/** 先按安全区限制面板尺寸，再用整窗中心定位；左右分别按物理边缘避让。 */
private fun Modifier.positionSidePanel(safe: WindowInsets?, slideFraction: () -> Float): Modifier =
    layout { measurable, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val left = safe?.getLeft(this, layoutDirection) ?: 0
        val right = safe?.getRight(this, layoutDirection) ?: 0
        val margin = SidePanelVerticalMargin.roundToPx()
        val top = maxOf(margin, safe?.getTop(this) ?: 0)
        val bottom = maxOf(margin, safe?.getBottom(this) ?: 0)
        val panel = measurable.measure(Constraints(
            maxWidth = (width - left - right).coerceAtLeast(0),
            maxHeight = (height - top - bottom).coerceAtLeast(0),
        ))
        val x = sidePanelStart(width, panel.width, left, right)
        val y = sidePanelStart(height, panel.height, top, bottom)
        layout(width, height) {
            panel.placeWithLayer(x, y) {
                translationX = -slideFraction() * (panel.width + x)
            }
        }
    }

/** 与 Box 的 Center 一样，半像素向上取整；只有中心位置会越过边界时才移动。 */
internal fun sidePanelStart(window: Int, panel: Int, leading: Int, trailing: Int): Int {
    val space = window - panel
    val last = (space - trailing).coerceAtLeast(0)
    return ((space + 1) / 2).coerceIn(leading.coerceAtMost(last), last)
}

/** 条目拖尾：第 i 条（0 起）在面板完全移出时额外落后 (i + 1) × 该距离；按进度线性缩小，就位时为 0。 */
private val SidePanelItemTrail = 16.dp

/** 参与拖尾递增的条目数上限（再往下的条目与最后一档相同）。 */
private const val SidePanelItemTrailMaxSteps = 6

/**
 * 侧栏条目区的「拖尾」排布：与 `Column(verticalArrangement = spacedBy(SidePanelItemSpacing))` 的测量与摆放
 * 完全相同（子项最小约束为 0、按剩余高度依次测量、起始侧对齐、项间 [SidePanelItemSpacing]），
 * 只是每个条目放在自己的图层上，按面板滑动进度 [slideFraction] 叠加横向落后量。
 * 图层块在绘制阶段读取进度，滑动期间不重组、不重新布局。
 *
 * 注：子项上的 `ColumnScope.weight` 等父数据在此不生效（现有条目均为固定高度的 AppNavItem）；「普通」档不经过本布局。
 */
@Composable
private fun SidePanelTrailingItems(
    slideFraction: () -> Float,
    content: @Composable () -> Unit,
) {
    Layout(content = content, modifier = Modifier.fillMaxWidth()) { measurables, constraints ->
        val gap = SidePanelItemSpacing.roundToPx()
        val trailPx = SidePanelItemTrail.toPx()
        val childBase = constraints.copy(minWidth = 0, minHeight = 0)
        var used = 0
        val placeables = measurables.mapIndexed { index, measurable ->
            if (index > 0) used += gap
            val maxH = if (constraints.hasBoundedHeight) {
                (constraints.maxHeight - used).coerceAtLeast(0)
            } else {
                Constraints.Infinity
            }
            measurable.measure(childBase.copy(maxHeight = maxH)).also { used += it.height }
        }
        val width = constraints.maxWidth
        val height = used.coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(width, height) {
            var y = 0
            placeables.forEachIndexed { index, placeable ->
                if (index > 0) y += gap
                val x = Alignment.Start.align(placeable.width, width, layoutDirection)
                val trail = trailPx * (minOf(index, SidePanelItemTrailMaxSteps - 1) + 1)
                placeable.placeWithLayer(x, y) {
                    // 面板 translationX = -进度 × (宽 + 左留白)：条目同向再多落后一点
                    val f = slideFraction()
                    if (f != 0f) translationX = -f * trail
                }
                y += placeable.height
            }
        }
    }
}

/**
 * 左缘区域右滑唤出侧边栏的指针手势（挂给**页面根布局**的 Modifier，而不是覆盖层）：
 * 起点在屏幕左缘 [SidePanelSwipeStartInset] 到屏宽 [SidePanelSwipeWidthFraction] 之间、且位于 [bottomBarTopY] 之上
 * （底部导航栏区域不参与识别），向右滑过 [SidePanelSwipeThreshold] 即回调 [onOpen]。
 *
 * **绝不误滑出**——本手势分两阶段，只在「没有任何子内容接手这次手势」时才接管：
 * 1. **观察阶段（Main 传递，子内容先处理每一次事件）**：只观察、不抢。任何控件消费了这次手势的位移
 *    （横向分页器 / 封面流翻页、滑条与开关拇指拖动、下拉滑选、拖拽排序、竖向列表滚动、分类栏透镜、滑动删除……），
 *    本手势就放弃，整段手势都不再属于侧栏；竖向为主 / 向左 / 多指的手势同样放弃（分类栏透镜长按接管期间也放弃，见 [SidePanelGestureExclusions]）；
 * 2. **接管阶段（Initial 传递）**：手势一路没被消费、已经是「向右、横向位移 >= [SidePanelSwipeRatio] 倍纵向位移」并越过 touch slop，
 *    才接管——此后每个事件都在 Initial 传递消费（Compose 的点击只在手指移出控件范围或事件被消费时才取消，
 *    不吞掉的话，在卡片上横着划一下并在卡片内抬手会被当成点击）；
 * 3. **只有从空白背景 / 左缘留白起手才唤出**：起点落在可点击控件上（点击处理会消费按下事件）的右滑被吞掉——既不唤出、也不触发点击；
 *    背景起手且滑过 [SidePanelSwipeThreshold] 才回调 [onOpen]。
 *
 * 其它要点：
 * - 挂在根布局而不是做覆盖层：Compose 重叠的兄弟节点默认只有最上层能收到指针事件，覆盖层会让
 *   覆盖范围内（识别区）的点击/滚动全部失效；挂在祖先节点上不遮挡任何交互；
 * - **底部导航栏排除**：起点落在 [bottomBarTopY]（导航栏顶边，根布局坐标 px）及以下的手势
 *   一律放行（不消费、不唤出），由导航栏自行处理；无底栏时传 `Float.POSITIVE_INFINITY`。
 *
 * @param bottomBarTopY 底部导航栏顶边取数回调（宿主布局后上报；手势发生时读取最新值）
 * @param excludeStart 指定控件拥有的手势起点，不参与侧栏识别；其它区域保持原有规则
 */
@Composable
internal fun rememberSidePanelSwipeModifier(
    onOpen: () -> Unit,
    bottomBarTopY: () -> Float = { Float.POSITIVE_INFINITY },
    excludeStart: (Offset) -> Boolean = { false },
): Modifier {
    val currentOnOpen by rememberUpdatedState(onOpen)
    val currentBottomBarTopY by rememberUpdatedState(bottomBarTopY)
    val currentExcludeStart by rememberUpdatedState(excludeStart)
    val density = LocalDensity.current
    val startInsetPx = with(density) { SidePanelSwipeStartInset.toPx() }
    val thresholdPx = with(density) { SidePanelSwipeThreshold.toPx() }
    return remember(startInsetPx, thresholdPx) {
        Modifier.pointerInput(startInsetPx, thresholdPx) {
            awaitEachGesture {
                val down = awaitFirstDown(
                    requireUnconsumed = false,
                    pass = PointerEventPass.Initial,
                )
                MotionIntent.noteTouch()
                // 起点必须在识别区内：左缘内缩起、到屏宽 1/4 为止
                val startX = down.position.x
                if (startX < startInsetPx || startX > size.width * SidePanelSwipeWidthFraction) return@awaitEachGesture
                // 底部导航栏区域不参与：起点落在导航栏顶边（含）之下直接放行，
                // 避免在底栏上滑动（切页/误触）被本手势占用而唤出侧边栏
                if (down.position.y >= currentBottomBarTopY()) return@awaitEachGesture
                if (currentExcludeStart(down.position)) return@awaitEachGesture
                val touchSlop = viewConfiguration.touchSlop
                var dx = 0f
                var dy = 0f
                var claimed = false
                var opened = false
                var first = true
                var startedOnBackground = true
                while (true) {
                    // 某个控件已长按接管本次手势（如引擎页分类栏的透镜选择）：侧栏手势放弃，不抢横向拖动
                    if (!claimed && SidePanelGestureExclusions.suppressed) break
                    // 观察阶段在 Main 传递（子内容先处理这一次事件）；接管之后改到 Initial 传递消费，点击才会被取消
                    val event = awaitPointerEvent(if (claimed) PointerEventPass.Initial else PointerEventPass.Main)
                    if (!claimed && event.changes.size > 1) break
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    val delta = change.positionChange()
                    if (first) {
                        // 起点就落在可交互控件上（可点击的卡片 / 列表行 / 按钮会消费按下事件）：不是唤出手势
                        startedOnBackground = !change.isConsumed
                        first = false
                    } else if (!claimed && change.isConsumed && delta != Offset.Zero) {
                        // 子内容消费了这次手势的位移（翻页 / 滑条 / 滚动 / 拖拽 / 透镜……）：整段手势都不再属于侧栏
                        break
                    }
                    dx += delta.x
                    dy += delta.y
                    if (!claimed) {
                        // 向左、竖向为主：不是唤出手势
                        if (dx < -touchSlop) break
                        if (abs(dy) > touchSlop && abs(dy) > abs(dx)) break
                        if (dx > touchSlop && dx >= SidePanelSwipeRatio * abs(dy)) claimed = true
                    }
                    if (claimed) {
                        change.consume()
                        if (!opened && startedOnBackground && dx >= thresholdPx && dx >= SidePanelSwipeRatio * abs(dy)) {
                            opened = true
                            MotionHaptics.perform(MotionHaptics.Kind.ThresholdActivate)
                            currentOnOpen()
                        }
                    }
                    if (!change.pressed) break
                }
            }
        }
    }
}

/**
 * 左缘系统返回手势排除条（**无指针输入，不拦截任何事件**）：
 * 让靠在左边缘起手的手势在系统「每条边最多 200dp」的放行区域内也能进入应用，
 * 由 [rememberSidePanelSwipeModifier] 识别；条带本身只向系统提交手势排除矩形。
 */
@Composable
internal fun SidePanelEdgeExclusionBar(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(SidePanelEdgeExclusionWidth)
            .padding(start = SidePanelSwipeStartInset)
            .systemGestureExclusion(),
    )
}

/**
 * 侧栏「左缘右滑」手势的临时屏蔽开关：长按接管了本次手势的控件（如 [tabDragSelect] 的透镜选择）在按住期间置 true，
 * 侧栏手势据此放弃识别（否则从最左边的标签长按后向右拖会弹出侧栏）。普通的快速滑动不受影响，仍可从左缘右滑唤出侧栏。
 */
internal object SidePanelGestureExclusions {
    /** 单点触控，主线程读写。 */
    var suppressed: Boolean = false
}
