package com.tyranor.next.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
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
    content: @Composable ColumnScope.() -> Unit = {},
) {
    if (!open) return
    val scope = rememberCoroutineScope()
    val dimAlpha = remember { Animatable(0f) }
    // 1f = 完全移出屏幕左侧，0f = 就位
    val slideFraction = remember { Animatable(1f) }
    val dismissing = remember { mutableStateOf(false) }
    val currentOnDismiss by rememberUpdatedState(onDismiss)

    fun dismiss() {
        if (dismissing.value) return
        dismissing.value = true
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
        // 遮罩：与 AppAlertDialog 使用同一档不透明度
        val scrimAlpha = 0.5f
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // 水平居中后面板左缘到屏幕左缘的距离：滑出动画据此把面板完全移出屏幕
            val panelLeft = ((maxWidth - SidePanelWidth) / 2).coerceAtLeast(0.dp)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = scrimAlpha * dimAlpha.value))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { dismiss() },
                    ),
            ) {
                Card(
                    modifier = Modifier
                        // 水平+垂直居中：面板高度随内容收缩（不再强制满高），超出可用高度时由条目区滚动
                        .align(Alignment.Center)
                        .padding(
                            top = SidePanelVerticalMargin,
                            bottom = SidePanelVerticalMargin,
                        )
                        .width(SidePanelWidth)
                        // 滑入/滑出：位移含面板本体与居中后的左侧留白，保证收起时完全离屏
                        .graphicsLayer {
                            translationX = -slideFraction.value * (size.width + panelLeft.toPx())
                        }
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
                        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                            Text(
                                text = stringResource(R.string.side_panel_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 18.dp, bottom = 10.dp),
                            )
                            // 标题固定，条目区在剩余高度内滚动：横屏/小高度窗口下列表不再被卡片圆角截断
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f, fill = false)
                                    .verticalScroll(rememberScrollState())
                                    .padding(bottom = 14.dp),
                                verticalArrangement = Arrangement.spacedBy(SidePanelItemSpacing),
                            ) {
                                content()
                            }
                        }
                    }
                }
            }
        }
    }

    // 进入动画：遮罩渐入 + 面板自左边缘滑入
    LaunchedEffect(Unit) {
        launch { dimAlpha.animateTo(1f, tween(250, easing = LinearOutSlowInEasing)) }
        slideFraction.animateTo(
            targetValue = 0f,
            animationSpec = spring(dampingRatio = 0.88f, stiffness = 450f, visibilityThreshold = 0.0001f),
        )
    }
}

/**
 * 左缘区域右滑唤出侧边栏的指针手势（挂给**页面根布局**的 Modifier，而不是覆盖层）：
 * 起点在屏幕左缘 [SidePanelSwipeStartInset] 到屏宽 [SidePanelSwipeWidthFraction] 之间、且位于 [bottomBarTopY] 之上
 * （底部导航栏区域不参与识别），向右滑过 [SidePanelSwipeThreshold] 即回调 [onOpen]。
 *
 * 实现要点：
 * - 挂在根布局而不是做覆盖层：Compose 重叠的兄弟节点默认只有最上层能收到指针事件，覆盖层会让
 *   覆盖范围内（识别区）的点击/滚动全部失效；挂在祖先节点上则子内容优先，不遮挡任何交互；
 * - 在 **Initial 传递**上取事件（父节点先于子内容处理）：只认「横向位移占优且向右」的手势，
 *   判定后立即消费，子内容（行内左滑删除、横向分页器、封面流）不会再响应这一次右滑；
 *   纵向滑动与向左的横向滑动一律不消费，原样交还给子内容。
 *   也就是说：左缘识别区内**向右**的横滑会被本手势占用（这是用户要求的宽识别区取舍），
 *   其余方向完全不受影响。
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
                var decided = false
                var claimed = false
                var triggered = false
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    val delta = change.positionChange()
                    dx += delta.x
                    dy += delta.y
                    if (!decided && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        decided = true
                        // 只认「横向位移占优且向右」的手势；纵向/左滑放行给子内容
                        claimed = dx > 0f && abs(dx) > abs(dy)
                        if (!claimed) break
                    }
                    if (claimed) {
                        change.consume()
                        if (!triggered && dx >= thresholdPx) {
                            triggered = true
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
