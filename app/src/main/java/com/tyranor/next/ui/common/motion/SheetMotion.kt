package com.tyranor.next.ui.common.motion

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineStart
import com.tyranor.next.theme.AppSheetTopShape
import com.tyranor.next.theme.AppThemeColors
import kotlinx.coroutines.withTimeoutOrNull
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.animation.core.tween

/**
 * 底部抽屉（Material3 `ModalBottomSheet`）的「增强」动效控制器。Material3 1.4 稳定版不开放抽屉自身的展开 / 收起弹簧，
 * 这里在抽屉外层叠一层位移，完全接管抽屉的运动：
 * - 展开：抽屉从屏幕下方带速度弹上来，略冲过停位后回落（冲过的部分以底边为基准竖向拉伸，底边始终贴住屏幕，不露缝）；
 * - 收起：点遮罩、返回键、下拉超过 1/3、在抽屉里执行动作，统一走同一段平滑下沉，同时遮罩淡出；
 * - 跟手下拉：抽屉内容（含把手）上直接拖，或列表到顶后继续下拉；松手按位置与有限速度投影判定，约超过高度 1/3 收起，否则回弹。
 * 「普通」动效档完全沿用 Material3 原有行为。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Stable
class MotionSheetController internal constructor(
    private val scope: CoroutineScope,
    internal val enhanced: Boolean,
) {
    /** 抽屉额外位移（px）：初始在屏幕外，展开动画把它带到 0。 */
    internal val offset = Animatable(if (enhanced) OFFSCREEN else 0f)
    internal var height by mutableFloatStateOf(0f)
    internal var closing = false
        private set

    /** Material3 收起是否放行：增强档下只有本控制器的收起流程走到最后才放行。 */
    internal var allowHide = !enhanced
        private set

    internal var sheetState: SheetState? = null
    internal var onClosed: () -> Unit = {}

    /** 跳转到新页面时抽屉随所在页面一起压暗的进度（0..1）。 */
    internal val navDim = Animatable(0f)

    /**
     * 从抽屉跳转到新页面（进入二级页面、启动游戏）：抽屉不再自己下沉，而是像所在页面的一部分一样
     * 保持原位、随页面一起被新页面推入覆盖并同步压暗（抽屉窗口本身随所在页面一起左移），
     * 侧推完成、完全被盖住后再无动画地移除；返回时抽屉已关闭。若跳转没有发生（未进入 onPause），回退为普通收起。
     */
    private var navigating = false

    /** 正在收起或正在跳转：此时抽屉内的跳转条目应忽略点击，避免重复启动。 */
    internal val busy: Boolean get() = closing || navigating

    /**
     * 从抽屉进入二级页面且**保持抽屉打开**：抽屉随所在页面一起被新页面推入覆盖并同步压暗；
     * 返回（本页重新 onResume）时随返回动画同步变亮，回到抽屉界面。跳转没有发生时什么也不做。
     */
    fun stayDuringNavigation(owner: LifecycleOwner) {
        if (!enhanced || closing || navigating) return
        navigating = true
        scope.launch {
            try {
                val paused = withTimeoutOrNull(NAVIGATION_START_TIMEOUT_MILLIS) {
                    owner.lifecycle.currentStateFlow.first { !it.isAtLeast(Lifecycle.State.RESUMED) }
                } != null
                if (!paused) return@launch
                launch { navDim.animateTo(1f, tween(MotionSprings.PushDurationMillis, easing = MotionSprings.PushEasing)) }
                owner.lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.RESUMED) }
                navDim.animateTo(0f, tween(MotionSprings.PushDurationMillis, easing = MotionSprings.PushEasing))
            } finally {
                navigating = false
            }
        }
    }

    fun navigateAway(owner: LifecycleOwner) {
        if (!enhanced) {
            onClosed()
            return
        }
        if (closing) return
        closing = true
        scope.launch {
            val paused = withTimeoutOrNull(NAVIGATION_START_TIMEOUT_MILLIS) {
                owner.lifecycle.currentStateFlow.first { !it.isAtLeast(Lifecycle.State.RESUMED) }
            } != null
            if (!paused) {
                closing = false
                close()
                return@launch
            }
            launch {
                navDim.animateTo(1f, tween(MotionSprings.PushDurationMillis, easing = MotionSprings.PushEasing))
            }
            delay(MotionSprings.PushDurationMillis + 80L)
            allowHide = true
            onClosed()
        }
    }

    /**
     * 动画收起抽屉，结束后回调关闭（等同于原来的 onDismiss）。「普通」档直接关闭。
     */
    @OptIn(ExperimentalMaterial3Api::class)
    /**
     * @param forNavigation 跳转到新页面时的「交接」收起：下沉时长与页面侧推相当，抽屉与新页面同时运动（并行），
     *   而不是抽屉先一闪而过、新页面再单独推入
     */
    fun close(velocity: Float = 0f, forNavigation: Boolean = false) {
        if (!enhanced) {
            onClosed()
            return
        }
        if (closing) return
        closing = true
        scope.launch {
            val target = (if (height > 0f) height else 2000f) * 1.05f
            val spec = if (forNavigation) HandoffCloseSpec else CloseSpec
            launch { offset.animateTo(target, spec, initialVelocity = if (velocity != 0f) velocity else offset.velocity) }
            // 位移下沉一小段后再让 Material3 进入隐藏态：遮罩随之淡出（交接收起时遮罩多留一会儿，与侧推同步淡去）
            delay(if (forNavigation) 160 else 90)
            allowHide = true
            runCatching { sheetState?.hide() }
            onClosed()
        }
    }

    /** 拖动速度（px/s）：松手后的回弹 / 收起从手指的速度接着走，跟手不「断」。 */
    private val tracker = androidx.compose.ui.input.pointer.util.VelocityTracker()

    internal fun resetVelocity() = tracker.resetTracking()

    /** 本次手势是否真的拖动过抽屉（用于忽略展开动画期间的 fling）。 */
    internal var dragged = false
        private set

    internal fun dragBy(delta: Float) {
        if (closing) return
        dragged = true
        val current = offset.value
        val next = if (current + delta >= 0f) {
            current + delta
        } else {
            // 往上拖：橡皮筋阻力
            val over = current + delta
            -RUBBER * (1f - 1f / (-over / RUBBER + 1f))
        }
        tracker.addPosition(android.os.SystemClock.uptimeMillis(), Offset(0f, next))
        // 同一帧内立即生效，不经协程调度晚一帧
        scope.launch(start = CoroutineStart.UNDISPATCHED) { offset.snapTo(next) }
    }

    private fun releaseVelocity(): Float = tracker.calculateVelocity().y.also { tracker.resetTracking() }

    internal fun settle() {
        dragged = false
        if (closing) return
        // 「超过约 1/3 收起」：按 30% 判定，抵消手指越过触摸阈值前没被计入的那一小段位移
        val v = releaseVelocity()
        // 判定位置 = 松手位置 + 约 0.12s 的惯性投影：慢拉看位置（约 1/3），明显的快速下甩也能收起；
        // 只有直接拉抽屉才计速度，列表滚到顶的余量惯性不会转交，不会重现误关
        val projected = offset.value + v.coerceIn(-6000f, 6000f) * FLING_PROJECTION_SECONDS
        if (height > 0f && projected > height * DISMISS_FRACTION) {
            close(v)
        } else {
            scope.launch { offset.animateTo(0f, SettleSpec, initialVelocity = v) }
        }
    }

    internal companion object {
        const val OFFSCREEN = 100_000f
        const val RUBBER = 24f
        const val DISMISS_FRACTION = 0.3f
        const val NAVIGATION_START_TIMEOUT_MILLIS = 700L
        const val FLING_PROJECTION_SECONDS = 0.12f
        /** 展开：约 0.37s 周期（含全局加速）、轻微回弹，快速利落地弹上来再丝滑停稳。 */
        val OpenSpec = spring<Float>(dampingRatio = 0.85f, stiffness = MotionSprings.stiffnessFor(420))

        /** 收起：临界阻尼平滑下沉（约 0.3s 内离屏），不回弹。 */
        val CloseSpec = spring<Float>(dampingRatio = 1f, stiffness = MotionSprings.stiffnessFor(320), visibilityThreshold = 0.5f)

        /** 跳转交接收起：临界阻尼、时长与页面侧推（420ms）相当，与新页面并行运动。 */
        val HandoffCloseSpec = spring<Float>(dampingRatio = 1f, stiffness = MotionSprings.stiffnessFor(560), visibilityThreshold = 0.5f)

        /** 下拉未过 1/3 松手：带一点回弹退回原位。 */
        val SettleSpec = spring<Float>(dampingRatio = 0.72f, stiffness = MotionSprings.stiffnessFor(440), visibilityThreshold = 0.5f)
    }
}

/** 创建控制器（随当前动效档位）。 */
@Composable
fun rememberMotionSheetController(): MotionSheetController {
    val scope = rememberCoroutineScope()
    val enhanced = MotionMode.enhanced
    return remember(enhanced) { MotionSheetController(scope, enhanced) }
}

/**
 * 抽屉状态：增强档以「已展开」初始化（由控制器自己做展开动画），并拦截 Material3 自带的收起（点遮罩），
 * 转交给控制器的平滑收起；普通档等同 `rememberModalBottomSheetState(skipPartiallyExpanded = true)`。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun rememberMotionSheetState(controller: MotionSheetController): SheetState {
    if (!controller.enhanced) {
        return rememberModalBottomSheetState(skipPartiallyExpanded = true).also { controller.sheetState = it }
    }
    val density = LocalDensity.current
    return remember(controller) {
        SheetState(
            skipPartiallyExpanded = true,
            positionalThreshold = { with(density) { 56.dp.toPx() } },
            velocityThreshold = { with(density) { 125.dp.toPx() } },
            initialValue = SheetValue.Expanded,
            confirmValueChange = { value ->
                if (value == SheetValue.Hidden && !controller.allowHide) {
                    controller.close()
                    false
                } else {
                    true
                }
            },
            skipHiddenState = false,
        ).also { controller.sheetState = it }
    }
}

/** 抽屉 `ModalBottomSheet(modifier = …)` 上的外观层：位移与展开时的果冻拉伸。只改绘制，不拦截触摸。 */
@OptIn(ExperimentalMaterial3Api::class)
fun Modifier.motionSheetSurface(controller: MotionSheetController): Modifier = if (!controller.enhanced) this else this
    .onSizeChanged { controller.height = it.height.toFloat() }
    // 跳转时随所在页面同步压暗（与 pageTransitionScrim 同一暗度与节奏）
    .drawWithContent {
        drawContent()
        val d = controller.navDim.value
        if (d > 0.001f) {
            val maxAlpha = if (AppThemeColors.isDark || AppThemeColors.hasDarkBackdrop) 0.4f else 0.18f
            // Material3 把本修饰符放在自身的纵向偏移之前：抽屉实际画在下移 sheetOffset 之后的位置，
            // 遮罩必须跟着下移，才正好盖住整个抽屉（而不是从屏幕顶部画到抽屉高度）
            val top = runCatching { controller.sheetState?.requireOffset() }.getOrNull() ?: 0f
            // 按抽屉的圆角轮廓画：本修饰符在 Surface 的裁剪之外，直接画矩形会在圆角外露出深色直角
            translate(top = top) {
                drawOutline(
                    outline = AppSheetTopShape.createOutline(Size(size.width, size.height), layoutDirection, this),
                    color = Color.Black.copy(alpha = maxAlpha * d),
                )
            }
        }
    }
    .graphicsLayer {
        val o = controller.offset.value
        if (o >= 0f) {
            translationY = o
        } else {
            // 冲过停位：不往上平移（会露出底边缝隙），改为以底边为基准竖向拉伸
            val h = size.height.coerceAtLeast(1f)
            scaleY = 1f + (-o / h)
            transformOrigin = TransformOrigin(0.5f, 1f)
        }
    }

/**
 * 抽屉内容 / 把手上的跟手下拉手势。必须放在抽屉**内部**（内容根节点、把手），不要放在 `ModalBottomSheet` 的 modifier 上：
 * Material3 把该 modifier 放在自身位移之前，命中区域会落在屏幕顶部，挡住遮罩的点击。
 */
fun Modifier.motionSheetDrag(controller: MotionSheetController): Modifier = if (!controller.enhanced) this else this
    .nestedScroll(SheetDragConnection(controller))
    .pointerInput(controller) {
        val slop = viewConfiguration.touchSlop
        var first = true
        detectVerticalDragGestures(
            onDragStart = {
                first = true
                controller.resetVelocity()
            },
            onDragEnd = { controller.settle() },
            onDragCancel = { controller.settle() },
        ) { change, dragAmount ->
            change.consume()
            // 第一段补上触摸阈值内的位移，让抽屉与手指的实际下拉距离一致
            val delta = if (first) dragAmount + (if (dragAmount >= 0f) slop else -slop) else dragAmount
            first = false
            controller.dragBy(delta)
        }
    }

/**
 * 控制器的副作用：高度测得后播放展开动画；接管返回键（增强档）。放在抽屉内容里。
 *
 * @param onClosed 抽屉完全收起后的关闭回调（即原来的 onDismissRequest）
 */
@Composable
fun MotionSheetEffects(controller: MotionSheetController, onClosed: () -> Unit) {
    val currentOnClosed by rememberUpdatedState(onClosed)
    controller.onClosed = { currentOnClosed() }
    if (!controller.enhanced) return
    BackHandler { controller.close() }
    LaunchedEffect(controller) {
        val h = snapshotFlow { controller.height }.first { it > 0f }
        controller.offset.snapTo(h)
        controller.offset.animateTo(0f, MotionSheetController.OpenSpec)
    }
}

private class SheetDragConnection(private val c: MotionSheetController) : NestedScrollConnection {
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        // 抽屉已被下拉时，往上滑先把抽屉推回去
        if (source == NestedScrollSource.UserInput && available.y < 0f && c.offset.value > 0f) {
            val consume = maxOf(available.y, -c.offset.value)
            c.dragBy(consume)
            return Offset(0f, consume)
        }
        return Offset.Zero
    }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        // 列表已到顶仍在下拉：转交给抽屉
        if (source == NestedScrollSource.UserInput && available.y > 0f) {
            c.dragBy(available.y)
            return Offset(0f, available.y)
        }
        return Offset.Zero
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        if (c.dragged && c.offset.value != 0f) {
            c.settle()
            return available
        }
        return Velocity.Zero
    }
}
