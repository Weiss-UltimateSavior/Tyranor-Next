package com.tyranor.next.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import com.tyranor.next.theme.NavWhite
import com.tyranor.next.theme.glassBorder
import com.tyranor.next.theme.glassShadow
import com.tyranor.next.theme.rememberAdvancedGlassPanelSurface
import kotlinx.coroutines.launch
import kotlin.math.abs

/** 侧边栏面板宽度（固定值，后续按内容再调）。 */
private val SidePanelWidth = 250.dp

/** 面板内条目之间的统一纵向间距（与弹窗内 AppNavItem 列表一致）。 */
private val SidePanelItemSpacing = 8.dp

/** 面板与屏幕左边缘的间距。 */
private val SidePanelStartMargin = 10.dp

/** 面板与屏幕上、下边缘的间距（上比下多 10dp）。 */
private val SidePanelTopMargin = 35.dp
private val SidePanelBottomMargin = 25.dp

/** 左半屏右滑识别区起点内缩（与屏幕左缘留出的距离）。 */
private val SidePanelSwipeStartInset = 5.dp

/** 左缘系统返回手势排除条宽度（从 [SidePanelSwipeStartInset] 起算，覆盖系统返回手势边缘区）。 */
private val SidePanelEdgeExclusionWidth = 25.dp

/** 向右滑动触发唤出的阈值。 */
private val SidePanelSwipeThreshold = 40.dp

/**
 * 应用侧边栏（空白骨架 + 顶部标题）：主界面四大页面通过**左半屏右滑**唤出，自左侧滑入的浮动面板。
 *
 * 几何与材质约定：
 * - 四边圆角（[AppComponentShape]）；与屏幕边缘间距：左 [SidePanelStartMargin]、
 *   上 [SidePanelTopMargin] / 下 [SidePanelBottomMargin]，右侧为内边不设间距；宽度固定 [SidePanelWidth]；
 * - 用 [Dialog] 独立窗口实现（`decorFitsSystemWindows = false` 的全屏窗口）：侧边栏需要盖住
 *   整窗（含状态栏、底部导航栏与平板侧栏），并与 [AppAlertDialog] 共用
 *   「遮罩点击 / 返回键关闭 + 进出动画」宿主；
 * - 面板材质沿用应用弹窗/抽屉的玻璃适配：默认 [NavWhite]、复古玻璃 [GlassPanel]、
 *   高级玻璃用 [rememberAdvancedGlassPanelSurface] 的页面取色渐变；
 * - 顶部固定标题（[R.string.side_panel_title]，水平居中），下方 [content] 为功能条目区：
 *   条目之间统一加 [SidePanelItemSpacing] 纵向间距（调用方不要自加外边距）；条目仍必须
 *   使用 AppNavItem 等统一组件（弹窗内传 `DialogItemSurface`）。
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
    val advancedPanelSurface = if (AppThemeColors.isAdvancedGlass) rememberAdvancedGlassPanelSurface() else null

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
        // 遮罩：高级玻璃面板不透明度更高，遮罩略加深（与 AppAlertDialog 同规则）
        val scrimAlpha = if (AppThemeColors.isAdvancedGlass) 0.6f else 0.5f
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
                    .align(Alignment.CenterStart)
                    .fillMaxHeight()
                    .padding(
                        start = SidePanelStartMargin,
                        top = SidePanelTopMargin,
                        bottom = SidePanelBottomMargin,
                    )
                    .width(SidePanelWidth)
                    // 滑入/滑出：位移含面板本体与左侧留白，保证收起时完全离屏
                    .graphicsLayer {
                        translationX = -slideFraction.value * (size.width + SidePanelStartMargin.toPx())
                    }
                    // 消费面板区域点击，避免穿透到遮罩
                    .pointerInput(Unit) { detectTapGestures { } }
                    .glassShadow()
                    .then(
                        if (advancedPanelSurface != null) {
                            Modifier.background(advancedPanelSurface.brush, AppComponentShape)
                        } else {
                            Modifier
                        },
                    )
                    .glassBorder(),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                colors = CardDefaults.cardColors(
                    containerColor = when {
                        AppThemeColors.isAdvancedGlass -> Color.Transparent
                        AppThemeColors.isGlass -> GlassPanel
                        else -> NavWhite
                    },
                ),
                shape = AppComponentShape,
            ) {
                // 顶部标题（水平居中）+ 功能条目区（条目之间统一间距）
                Column(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                    Text(
                        text = stringResource(R.string.side_panel_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 18.dp, bottom = 10.dp),
                    )
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(SidePanelItemSpacing),
                    ) {
                        content()
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
 * 左半屏右滑唤出侧边栏的指针手势（挂给**页面根布局**的 Modifier，而不是覆盖层）：
 * 起点在屏幕左缘 [SidePanelSwipeStartInset] 到水平居中之间，向右滑过 [SidePanelSwipeThreshold] 即回调 [onOpen]。
 *
 * 实现要点：
 * - 挂在根布局而不是做覆盖层：Compose 重叠的兄弟节点默认只有最上层能收到指针事件，覆盖层会让
 *   覆盖范围内（左半屏）的点击/滚动全部失效；挂在祖先节点上则子内容优先，不遮挡任何交互；
 * - 在 **Initial 传递**上取事件（父节点先于子内容处理）：只认「横向位移占优且向右」的手势，
 *   判定后立即消费，子内容（行内左滑删除、横向分页器、封面流）不会再响应这一次右滑；
 *   纵向滑动与向左的横向滑动一律不消费，原样交还给子内容。
 *   也就是说：左半屏内**向右**的横滑会被本手势占用（这是用户要求的宽识别区取舍），
 *   其余方向完全不受影响。
 */
@Composable
internal fun rememberSidePanelSwipeModifier(onOpen: () -> Unit): Modifier {
    val currentOnOpen by rememberUpdatedState(onOpen)
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
                // 起点必须在识别区内：左缘内缩起、到屏幕水平居中为止
                val startX = down.position.x
                if (startX < startInsetPx || startX > size.width / 2f) return@awaitEachGesture
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
