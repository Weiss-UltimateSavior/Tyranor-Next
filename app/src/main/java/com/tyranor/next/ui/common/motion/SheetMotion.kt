package com.tyranor.next.ui.common.motion

import android.os.Build
import android.view.Window
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
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
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineStart
import com.tyranor.next.theme.AppSheetTopShape
import kotlinx.coroutines.withTimeoutOrNull
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.animation.core.tween
import com.tyranor.next.ui.common.notice.NoticeCover
import com.tyranor.next.ui.common.rubberBandOffset

/**
 * 底部抽屉（Material3 `ModalBottomSheet`）的「增强」动效控制器。Material3 1.4 稳定版不开放抽屉自身的展开 / 收起弹簧，
 * 这里在抽屉外层叠一层位移，完全接管抽屉的运动：
 * - 展开：抽屉从屏幕下方带速度弹上来，冲过停位后 Q 弹地回落（冲过的部分以底边为基准竖向拉伸，底边始终贴住屏幕，不露缝）；
 * - 遮罩：由 [motionSheetSurface] 自绘，透明度跟抽屉露出的比例走——升起时渐显、下沉 / 下拉时渐隐，抽屉与遮罩是同一个动作
 *   （Material3 自带的遮罩是「抽屉一出现就整片变暗、抽屉走完了灰色再慢慢褪」，两件事各演各的）；
 * - 收起：点遮罩、返回键、下拉超过 1/3、在抽屉里执行动作，统一走同一段平滑下沉，遮罩同步淡出；
 * - 跟手下拉：抽屉内容（含把手）上直接拖，或列表到顶后继续下拉；松手按位置与有限速度投影判定，约超过高度 1/3 收起，否则回弹。
 * 「普通」动效档完全沿用 Material3 原有行为。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Stable
class MotionSheetController internal constructor(
    private val scope: CoroutineScope,
    internal val enhanced: Boolean,
    // 抽屉在独立窗口里，直接拖把手不会经过 pressSquish：记录这次触摸，并给速度采样使用同一时间轴。
    private val recordTouch: () -> Long = {
        MotionIntent.noteTouch()
        android.os.SystemClock.uptimeMillis()
    },
) {
    internal var scrimWindow by mutableStateOf<Window?>(null)
    internal val dragConnection = SheetDragConnection(this)

    /** 抽屉额外位移（px）：初始在屏幕外，展开动画把它带到 0。 */
    internal val offset = Animatable(if (enhanced) OFFSCREEN else 0f)
    internal var height by mutableFloatStateOf(0f)
        private set
    internal var closing = false
        private set

    /** 所在页面的景深（点击进入二级页面时抽屉随整个界面一起翻折 / 褪色）；由 [rememberMotionSheetController] 同步。 */
    internal var pageDepth: PageDepth? = null

    /** 窗口高度（px），换算翻折枢轴用。 */
    internal var windowHeightPx by mutableFloatStateOf(0f)

    /** 窗口宽度（px），换算翻折枢轴用（抽屉比窗口窄时枢轴在窗口左缘）。 */
    internal var windowWidthPx by mutableFloatStateOf(0f)

    /** 抽屉露出的比例：0 = 完全在屏幕下方，1 = 完全展开（冲过停位时夹在 1）；遮罩与背后页面的翻折都跟它走。 */
    internal val cover: Float
        get() = if (!enhanced || height <= 0f) 0f else (1f - offset.value / height).coerceIn(0f, 1f)

    internal val sinkStrength: Float
        get() = sinkStrengthFor(height / windowHeightPx.coerceAtLeast(1f))

    internal fun updateHeight(measured: Float) {
        if (height == measured) return
        height = measured
        if (closing && !leaving && !closeCommitted) {
            // 分屏或内容在退场途中变高 / 变矮：沿当前速度改去新的离屏线，不能按旧高度移除。
            val velocity = offset.velocity
            closeJob?.cancel()
            animateClose(velocity)
        }
    }

    /** Material3 收起是否放行：增强档下只有本控制器的收起流程走到最后才放行。 */
    internal var allowHide = !enhanced
        private set

    internal var sheetState: SheetState? = null
    internal var onClosed: () -> Unit = {}

    /** 关闭请求的业务守卫；下拉窗口展开等临时模态状态下保留抽屉。 */
    internal var canClose: () -> Boolean = { true }

    /** 跳转到新页面时抽屉随所在页面一起压暗的进度（0..1）。 */
    internal val navDim = Animatable(0f)

    /**
     * 从抽屉跳转到新页面（进入二级页面、启动游戏）：抽屉不再自己下沉，而是像所在页面的一部分一样
     * 保持原位、随页面一起被新页面推入覆盖并同步压暗（抽屉窗口本身随所在页面一起左移），
     * 侧推完成、完全被盖住后再无动画地移除；返回时抽屉已关闭。若跳转没有发生（未进入 onPause），回退为普通收起。
     */
    private var navigating = false

    /** 收起是否已经承诺为「跳转交接」：这种收起不可回头。 */
    private var leaving = false

    /** 已提交的页面跳转（正在跳转 / 交接收起）：抽屉内的跳转条目不再重复触发，避免重复启动。普通的下拉 / 点遮罩收起不算。 */
    internal val busy: Boolean get() = navigating || leaving

    /**
     * 抽屉内条目 / 按钮被点：返回 true 表示照常执行动作。
     * 普通收起途中（还没完全离屏）先把抽屉接回来再执行，点击不会被静默吞掉；
     * 收起已越过离屏线 / 页面跳转已提交则拒绝并给一次拒绝触觉。
     */
    internal fun interact(): Boolean {
        // 收起越过离屏线后抽屉当即被移出组合，此时执行动作会被取消、静默丢失，也会让连点重复启动：
        // 和已提交的页面跳转一样拒绝并给拒绝触觉；离屏之前（下沉的约 0.2 秒内）接回来再执行
        if (busy || (closing && closeCommitted)) {
            MotionHaptics.perform(MotionHaptics.Kind.Reject)
            return false
        }
        reopen()
        return true
    }

    /** 收起的协程；在抽屉完全离屏之前可以取消并把抽屉接回来。 */
    private var closeJob: kotlinx.coroutines.Job? = null

    /** 收起是否已越过离屏线（抽屉底边越过屏幕底边）：此刻抽屉就被移除，不可回头。 */
    private var closeCommitted = false

    /**
     * 收起途中（尚未完全离屏）把抽屉接回来：取消收起，位移从当前位置带着速度弹回展开位，遮罩随之回到原暗度。
     * 已越过离屏线或跳转交接则返回 false。
     */
    internal fun reopen(): Boolean {
        if (!closing || closeCommitted || leaving) return false
        // 先取速度再取消收起：取消后动画状态可能已被清掉，接回就成了从零速重新起步
        val v = offset.velocity
        closeJob?.cancel()
        closeJob = null
        closing = false
        scrimWindow.restoreDialogDim()
        scope.launch { offset.animateTo(0f, SettleSpec, initialVelocity = v) }
        return true
    }

    /**
     * 从抽屉进入二级页面且**保持抽屉打开**：抽屉随所在页面一起被新页面推入覆盖并同步压暗；
     * 返回（本页重新 onResume）时随返回动画同步变亮，回到抽屉界面。跳转没有发生时什么也不做。
     */
    fun stayDuringNavigation(owner: LifecycleOwner) {
        if (!enhanced || closing || navigating) return
        navigating = true
        scope.launch {
            var unfold: (() -> Unit)? = null
            try {
                val paused = withTimeoutOrNull(NAVIGATION_START_TIMEOUT_MILLIS) {
                    owner.lifecycle.currentStateFlow.first { !it.isAtLeast(Lifecycle.State.RESUMED) }
                } != null
                if (!paused) return@launch
                launch { navDim.animateTo(1f, tween(MotionSprings.PushDurationMillis, easing = MotionSprings.PushEasing)) }
                // 回来：上层页面一开始退出（与窗口返回动画、页面区的收回同一个起点）或本页重新 RESUMED，先到者为准
                val back = kotlinx.coroutines.CompletableDeferred<Unit>()
                unfold = { back.complete(Unit) }
                PageTransitions.registerPop(unfold)
                val resumed = launch {
                    owner.lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.RESUMED) }
                    back.complete(Unit)
                }
                back.await()
                resumed.cancel()
                // 抽屉已经回到眼前：条目立刻可以再点，不要等变亮动画放完才放行——
                // 否则返回后马上再点同一个条目会被当成「正在跳转」拒绝（拒绝触觉，进不去）
                navigating = false
                navDim.animateTo(0f, tween(MotionSprings.PushDurationMillis, easing = MotionSprings.PushEasing))
            } finally {
                unfold?.let { PageTransitions.unregisterPop(it) }
                navigating = false
            }
        }
    }

    /** 等所在页面开始让位后同步压暗并移除抽屉；跳转未发生时退回常规收起。 */
    fun navigateAway(owner: LifecycleOwner) {
        if (!enhanced) {
            onClosed()
            return
        }
        if (closing) return
        closing = true
        leaving = true
        scope.launch {
            val paused = withTimeoutOrNull(NAVIGATION_START_TIMEOUT_MILLIS) {
                owner.lifecycle.currentStateFlow.first { !it.isAtLeast(Lifecycle.State.RESUMED) }
            } != null
            if (!paused) {
                closing = false
                leaving = false
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
     *
     * 下沉由控制器的位移独自完成，遮罩随抽屉位置同步淡出（见 [motionSheetSurface]）：抽屉与遮罩是同一个动作，
     * 不会「抽屉先没了、灰色还留着再慢慢褪」。抽屉底边越过屏幕底边（完全离屏）的那一刻直接移除，不再交给 Material3 的
     * `hide()`——它会在抽屉早已离屏之后再空等一段动画，期间窗口还拦着点击。离屏之前随时可以被点击接回（[reopen]）
     * 或被手指抓住（[reopenForGrab]）。
     */
    fun close(velocity: Float = 0f) {
        if (!canClose()) return
        if (!enhanced) {
            onClosed()
            return
        }
        if (closing) return
        closing = true
        closeCommitted = false
        // A nested sheet returns its mask immediately; keep its grab/click-to-reopen input.
        scrimWindow.beginDialogDimExit()
        animateClose(velocity)
    }

    private fun animateClose(velocity: Float) {
        closeJob = scope.launch {
            // 位移 ≥ 抽屉自身高度 = 底边越过屏幕底边 = 完全离屏（此刻遮罩也已淡尽）；越过这里收起不可回头
            val exitLine = if (height > 0f) height else 2000f
            val startVelocity = if (velocity != 0f) velocity else offset.velocity
            val slide = launch { offset.animateTo(exitLine * 1.05f, CloseSpec, initialVelocity = startVelocity) }
            val exit = launch {
                snapshotFlow { offset.value >= exitLine }.first { it }
                closeCommitted = true
                allowHide = true
                slide.cancel()
                onClosed()
            }
            slide.join()
            // 下沉被别的位移动画顶掉（例如刚展开时的 snapTo / animateTo）而没走到离屏：这次收起作废，
            // 不能卡在「收起中」——否则返回键 / 点遮罩都会因 closing 为真而直接被忽略
            if (!closeCommitted) {
                exit.cancel()
                closing = false
                scrimWindow.restoreDialogDim()
            }
        }
    }

    /** 拖动速度（px/s）：松手后的回弹 / 收起从手指的速度接着走，跟手不「断」。 */
    private val tracker = androidx.compose.ui.input.pointer.util.VelocityTracker()

    /** 新手势开始时清除上一段拖动样本，防止旧速度影响松手判定。 */
    internal fun resetVelocity() = tracker.resetTracking()

    /** 本次手势是否真的拖动过抽屉（用于忽略展开动画期间的 fling）。 */
    internal var dragged = false
        private set

    /** 抓住收起中的抽屉：取消收起并立刻停住回弹动画，让跟手位移接管（不 snapTo，位置保持当前值）。 */
    private fun reopenForGrab(): Boolean {
        if (closeCommitted || leaving || !closing) return false
        closeJob?.cancel()
        closeJob = null
        closing = false
        scrimWindow.restoreDialogDim()
        scope.launch(start = CoroutineStart.UNDISPATCHED) { offset.stop() }
        return true
    }

    /** 下拉是否已越过「松手会关闭」的线（越过时震一次，退回线内重新武装、不再震）。 */
    private var pastLine = false

    /** 将拖动立即施加到抽屉；向上越界时增加阻力，关闭过程中忽略新拖动。 */
    internal fun dragBy(delta: Float) {
        // 收起刚开始时按下再拖 = 抓住抽屉：取消收起，从当前位置继续跟手（已承诺的收起 / 跳转交接不接管）
        if (closing && !reopenForGrab()) return
        dragged = true
        val sampleTime = recordTouch()
        val current = offset.value
        val next = if (current + delta >= 0f) {
            current + delta
        } else {
            // 往上拖：橡皮筋阻力
            rubberBandOffset(current + delta, RUBBER)
        }
        tracker.addPosition(sampleTime, Offset(0f, next))
        if (height > 0f) {
            val line = height * DISMISS_FRACTION
            if (!pastLine && next > line) {
                pastLine = true
                MotionHaptics.perform(MotionHaptics.Kind.ThresholdActivate)
            } else if (pastLine && next < line * LINE_REARM) {
                pastLine = false
            }
        }
        // 同一帧内立即生效，不经协程调度晚一帧
        scope.launch(start = CoroutineStart.UNDISPATCHED) { offset.snapTo(next) }
    }

    /** 拖动结束，包括抽屉已归位、松手惯性交还给列表的情况。 */
    internal fun endDrag() {
        dragged = false
        pastLine = false
        resetVelocity()
    }

    /** 按当前位置与受限速度投影选择收起或回弹，并结束本次拖动标记。 */
    internal fun settle() {
        val v = tracker.calculateVelocity().y
        endDrag()
        if (closing) return
        // 「超过约 1/3 收起」：按 30% 判定，抵消手指越过触摸阈值前没被计入的那一小段位移
        // 判定位置 = 松手位置 + 约 0.12s 的惯性投影：慢拉看位置（约 1/3），明显的快速下甩也能收起；
        // 只有直接拉抽屉才计速度，列表滚到顶的余量惯性不会转交，不会重现误关
        val projected = offset.value + v.coerceIn(-6000f, 6000f) * FLING_PROJECTION_SECONDS
        if (canClose() && height > 0f && projected > height * DISMISS_FRACTION) {
            close(v)
        } else {
            // 回弹的幅度跟拉下的距离走：慢慢拉下一小段再松手，速度几乎为 0，弹簧的自然过冲只有拉距的几个百分点
            // （拉 60px 才不到 3px），肉眼看就是「没有回弹」。给一个与拉距成正比、朝向原位的底速度，让过冲稳定可见。
            val kick = -offset.value * SETTLE_KICK_PER_PX
            scope.launch { offset.animateTo(0f, SettleSpec, initialVelocity = minOf(v, kick)) }
        }
    }

    /**
     * 抽屉在这次手势里被拉下去、又被手指推回了原位，手指还带着向上的速度松手（向上甩）：此刻位移已经是 0，
     * 没有「回弹」可播，到顶就像撞上了墙——按松手速度向上冲过停位（竖向拉伸）再落回，幅度随速度变化。
     */
    internal fun bounceAtTop(flingVelocity: Float) {
        if (!enhanced || closing) return
        val v = flingVelocity.coerceIn(-BOUNCE_MAX_VELOCITY, 0f) * BOUNCE_GAIN
        scope.launch { offset.animateTo(0f, SettleSpec, initialVelocity = v) }
    }

    internal companion object {
        const val OFFSCREEN = 100_000f
        const val RUBBER = 24f
        const val DISMISS_FRACTION = 0.3f

        /** 退回到关闭线的这一比例以内才重新武装越线触觉（滞回，避免在线上来回抖动时连震）。 */
        const val LINE_REARM = 0.85f
        const val NAVIGATION_START_TIMEOUT_MILLIS = 700L
        const val FLING_PROJECTION_SECONDS = 0.12f

        /** 松手回弹的底速度：每拉下 1px 给多少 px/s（朝向原位）。 */
        const val SETTLE_KICK_PER_PX = 5f

        /** 向上甩到顶的最小速度（px/s）：低于它视为没有甩，不弹。 */
        const val BOUNCE_MIN_VELOCITY = 400f
        const val BOUNCE_MAX_VELOCITY = 3000f
        const val BOUNCE_GAIN = 0.7f
        /**
         * 展开：约 0.37s 周期（含全局加速），像抽屉被推上来——利落地升起、越接近停位越慢，只在停位处轻轻多走一点再落稳
         * （阻尼 0.82，过冲约 1%，被竖向拉伸吃掉，底边始终贴住屏幕；不是果冻式的来回弹）；遮罩随抽屉位置渐显，不是先「啪」地全暗。
         */
        val OpenSpec = spring<Float>(dampingRatio = 0.82f, stiffness = MotionSprings.stiffnessFor(420))

        /** 收起：临界阻尼平滑下沉（约 0.21s 离屏），不回弹；遮罩随抽屉位置同步淡出。 */
        val CloseSpec = spring<Float>(dampingRatio = 1f, stiffness = MotionSprings.stiffnessFor(320), visibilityThreshold = 0.5f)

        /** 下拉未过 1/3 松手：带一点回弹退回原位。 */
        val SettleSpec = spring<Float>(dampingRatio = 0.72f, stiffness = MotionSprings.stiffnessFor(440), visibilityThreshold = 0.5f)
    }
}

/** 创建控制器（随当前动效档位）。 */
@Composable
fun rememberMotionSheetController(): MotionSheetController {
    val scope = rememberCoroutineScope()
    val enhanced = MotionMode.enhanced
    val depth = LocalPageDepth.current
    return remember(enhanced) { MotionSheetController(scope, enhanced) }.also {
        it.pageDepth = depth
    }
}

/**
 * 抽屉状态：增强档以「已展开」初始化（由控制器自己做展开动画），并拦截 Material3 自带的收起（点遮罩），
 * 转交给控制器的平滑收起；普通档等同 `rememberModalBottomSheetState(skipPartiallyExpanded = true)`。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun rememberMotionSheetState(
    controller: MotionSheetController,
    confirmValueChange: (SheetValue) -> Boolean = { true },
): SheetState {
    val currentConfirm by rememberUpdatedState(confirmValueChange)
    controller.canClose = { currentConfirm(SheetValue.Hidden) }
    if (!controller.enhanced) {
        return rememberModalBottomSheetState(
            skipPartiallyExpanded = true,
            confirmValueChange = { currentConfirm(it) },
        ).also { controller.sheetState = it }
    }
    val density = LocalDensity.current
    return remember(controller) {
        SheetState(
            skipPartiallyExpanded = true,
            positionalThreshold = { with(density) { 56.dp.toPx() } },
            velocityThreshold = { with(density) { 125.dp.toPx() } },
            initialValue = SheetValue.Expanded,
            confirmValueChange = { value ->
                if (!currentConfirm(value)) {
                    false
                } else if (value == SheetValue.Hidden && !controller.allowHide) {
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

/**
 * Material3 的透明遮罩仍负责点击关闭与无障碍语义；实际绘制统一参与叠层遮罩协调。
 */
fun MotionSheetController.nativeScrim(color: Color): Color = color.copy(alpha = 0f)

// Material3 1.4.0 StandardMotionTokens.DefaultEffects, used by ModalBottomSheet's Scrim.
// MotionScheme is internal in that version; keep these pinned values in this shared owner.
private val OrdinarySheetScrimSpec = spring<Float>(dampingRatio = 1f, stiffness = 1600f)

/** Register the sheet's self-drawn mask without enabling native window dim. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MotionSheetController.BindWindowScrim(color: Color) {
    // Same target and pinned motion scheme used by Material3 1.4's Scrim. Enhanced mode
    // keeps its existing cover track; ordinary mode retains Material3's original fade.
    val ordinaryProgress = if (enhanced) null else animateFloatAsState(
        targetValue = if (sheetState?.targetValue != SheetValue.Hidden) 1f else 0f,
        animationSpec = OrdinarySheetScrimSpec,
        label = "Sheet scrim",
    )
    val window = syncDialogWindow(preserveWindowAnimations = true, scrimAlpha = color.alpha) {
        if (enhanced) cover else ordinaryProgress!!.value
    }
    SideEffect { scrimWindow = window }
    DisposableEffect(window) {
        onDispose { if (scrimWindow === window) scrimWindow = null }
    }
    val state = sheetState
    LaunchedEffect(window, state, enhanced) {
        if (enhanced || window == null || state == null) return@LaunchedEffect
        // M3 can query confirmValueChange more than once before hiding. Yield only when
        // its actual target changes, so a veto cannot withdraw a still-open sheet's mask.
        var opened = state.currentValue != SheetValue.Hidden
        snapshotFlow { state.targetValue }.collect { target ->
            if (target != SheetValue.Hidden) {
                opened = true
                window.restoreDialogDim()
            } else if (opened) {
                window.beginDialogDimExit()
            }
        }
    }
}

/**
 * 页面景深的来源登记处：被抽屉 / 弹窗 / 侧栏盖住的页面向后翻折（见 [depthFoldStack]），翻折的进度跟着浮层的展开走。
 * 页面（主界面的页面区、二级页面壳）调用 [rememberSheetBackdrop] 取得句柄，经 [LocalSheetBackdrop] 提供给子树，
 * 并把句柄交给 [depthFoldStack]；抽屉里调用 [BindBackdrop]、弹窗里调用 [Bind] 登记自己的进度。底部导航不在页面里，不受影响。
 */
@Stable
class SheetBackdrop internal constructor(private val scope: CoroutineScope) {
    /** 一个浮层：[progress] 展开进度（0..1），[strength] 页面下沉的强度（0..1，盖住屏幕越多越深，见 [sink]）。 */
    internal class Source(val progress: () -> Float, val strength: () -> Float)

    /**
     * 驱动页面景深的进度来源（各 0..1，在绘制阶段读取）。同一页上可以同时有多个浮层（抽屉里再弹弹窗等），
     * 页面按其中最大的那个走，不会叠加出过头的翻折。
     */
    private val sources = mutableStateListOf<Source>()

    internal val cover: Float
        get() {
            var max = 0f
            for (s in sources) max = maxOf(max, s.progress())
            return max
        }

    /**
     * 页面「下沉」的进度：各浮层的展开进度 × 各自的强度，取最大的那个（同样不叠加）。强度让下沉幅度跟浮层的体量走——
     * 满屏的面板接近原版的全幅，半屏的抽屉、弹窗沉得浅一些（见 [BindBackdrop] 与 `AppAlertDialog`）。
     */
    internal val sink: Float
        get() {
            var max = 0f
            for (s in sources) max = maxOf(max, s.progress() * s.strength())
            return max
        }

    internal fun add(source: Source) {
        sources.add(source)
    }

    internal fun remove(source: Source) {
        val index = sources.indexOf(source)
        if (index < 0) return
        val last = source.progress()
        if (last <= 0.01f) {
            sources.removeAt(index)
            return
        }
        // 外部移除时只接续这一层的进度和强度。别的层（包括还在退的层）继续自己的轨迹；
        // cover 与 sink 的最大值可能来自不同层，不能用全局 cover 配上最后离开那层的强度。
        val strength = source.strength()
        val progress = Animatable(last)
        val tail = Source({ progress.value }, { strength })
        sources[index] = tail
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                progress.animateTo(0f, MotionTokens.fadeOut(0.001f))
            } finally {
                sources.remove(tail)
            }
        }
    }
}

/**
 * 当前页面的景深句柄：页面根提供（主界面的页面区、二级页面壳），抽屉与 [com.tyranor.next.ui.common.AppAlertDialog] 弹窗
 * 在各自组合里读取并登记，页面随它们的展开进度向后翻折。没有提供者（独立窗口里的页面）时为 null，不做景深。
 */
val LocalSheetBackdrop = staticCompositionLocalOf<SheetBackdrop?> { null }

/** 创建页面侧的抽屉背景句柄。 */
@Composable
fun rememberSheetBackdrop(): SheetBackdrop {
    val scope = rememberCoroutineScope()
    return remember { SheetBackdrop(scope) }
}

/** 放在抽屉所在的 composable 里：把本抽屉登记为 [backdrop] 的驱动者，抽屉离开组合时自动注销。 */
@Composable
fun MotionSheetController.BindBackdrop(backdrop: SheetBackdrop) {
    // 下沉强度跟抽屉盖住的屏幕比例走：半屏的抽屉沉得浅一些，满屏接近 1（见 sinkStrengthFor）
    backdrop.Bind(strength = { sinkStrength }) { cover }
}

/**
 * 页面下沉的强度（0..1）：浮层盖住屏幕的比例 [coverage] 越大，页面沉得越深——满屏面板（mochi 演示量出来的全幅）取 1，
 * 半屏的抽屉约 0.75，再小的也不低于 0.5（只做一点后退的暗示，不白白让页面动得大）。
 */
internal fun sinkStrengthFor(coverage: Float): Float = 0.5f + 0.5f * coverage.coerceIn(0f, 1f)

/** 弹窗（`AppAlertDialog`，卡片从下方升起，高度随内容 / 屏幕自适应、通常盖住 3~6 成）固定取的下沉强度。 */
internal const val DialogSinkStrength = 0.7f

/**
 * 把 [progress]（0 = 浮层不在、1 = 完全展开，在绘制阶段读取）登记为本背景的驱动者，离开组合时自动注销。
 * 抽屉用露出比例，侧栏用遮罩进度（遮罩平滑、不带回弹，页面翻折与遮罩同步）。
 * [strength] 是页面下沉的强度（见 [SheetBackdrop.sink]）：只影响抽屉 / 弹窗的下沉，翻折与褪色不受影响。
 */
@Composable
fun SheetBackdrop.Bind(strength: () -> Float = { 1f }, progress: () -> Float) {
    val latest by rememberUpdatedState(progress)
    val latestStrength by rememberUpdatedState(strength)
    val backdrop = this
    DisposableEffect(backdrop) {
        val source = SheetBackdrop.Source({ latest() }, { latestStrength() })
        backdrop.add(source)
        onDispose { backdrop.remove(source) }
    }
}

/** 自绘遮罩的出血：遮罩矩形向四周多画这么多像素，保证盖住整个窗口（超出窗口的部分由窗口裁掉，没有额外开销）。 */
private const val SCRIM_BLEED = 8192f

private fun Modifier.sheetWindowScrim(controller: MotionSheetController, color: Color): Modifier = drawWithContent {
    // Outside Material3's sheet offset: the mask stays in window coordinates.
    val alpha = controller.scrimWindow.dialogScrimAlpha()
    if (alpha > 0f) {
        drawRect(
            color = color.copy(alpha = alpha),
            topLeft = Offset(-SCRIM_BLEED, -SCRIM_BLEED),
            size = Size(size.width + 2f * SCRIM_BLEED, size.height + 2f * SCRIM_BLEED),
        )
    }
    drawContent()
}

/** The shared Window mask stays behind the sheet; only the actual panel receives this extra dim. */
@OptIn(ExperimentalMaterial3Api::class)
private fun Modifier.sheetPanelDim(controller: MotionSheetController): Modifier = drawWithCache {
    val outline = AppSheetTopShape.createOutline(size, layoutDirection, this)
    onDrawWithContent {
        drawContent()
        val alpha = controller.scrimWindow.dialogPanelDimAlpha()
        if (alpha > 0f) {
            // ModalBottomSheet applies its own offset inside this modifier in both motion modes.
            val top = runCatching { controller.sheetState?.requireOffset() }.getOrNull() ?: 0f
            translate(top = top) {
                drawOutline(outline = outline, color = Color.Black.copy(alpha = alpha))
            }
        }
    }
}

/**
 * 抽屉 `ModalBottomSheet(modifier = …)` 上的外观层：位移与展开时的果冻拉伸，以及随抽屉位置渐显渐隐的遮罩。只改绘制，不拦截触摸。
 *
 * @param scrim 遮罩色（含停稳时的透明度，与传给 [nativeScrim] 的是同一个）：遮罩透明度 = 其 alpha × 抽屉露出的比例，
 *   抽屉从屏幕下方升起时遮罩同步渐显、下沉 / 下拉时同步渐隐，抽屉与遮罩是同一个动作；停稳时与 Material3 原遮罩逐像素一致
 */
@OptIn(ExperimentalMaterial3Api::class)
fun Modifier.motionSheetSurface(controller: MotionSheetController, scrim: Color): Modifier = if (!controller.enhanced) {
    sheetWindowScrim(controller, scrim).sheetPanelDim(controller)
} else this
    .onSizeChanged { controller.updateHeight(it.height.toFloat()) }
    // 跳转时随所在页面同步压暗（与 pageTransitionScrim 同一暗度与节奏）
    .sheetWindowScrim(controller, scrim)
    // 外层：从抽屉进入二级页面时整个界面一起翻折（单独一层，不和下面的位移 / 拉伸共用枢轴）。
    // 图层坐标系以本修饰符的布局框（Material3 偏移之前、窗口顶部）为准：竖直枢轴取窗口高度的 DepthPivotY 处，
    // 水平枢轴取窗口左缘（抽屉比窗口窄时——平板 / 横屏的最大宽度——左缘在本布局框左侧 (窗口宽 − 抽屉宽) / 2 处）
    .graphicsLayer {
        val push = controller.pageDepth?.progress?.value ?: 0f
        if (push > 0.01f) {
            val w = size.width.coerceAtLeast(1f)
            depthFoldLeft(
                push,
                pivotFracY = controller.windowHeightPx * DepthPivotY / size.height.coerceAtLeast(1f),
                desaturate = false,
                pivotFracX = -(((controller.windowWidthPx - w) / 2f).coerceAtLeast(0f)) / w,
            )
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
            // Material 的实际面板已在此图层内下移；枢轴须落在可见面板的底边。
            val top = controller.sheetState?.let { runCatching { it.requireOffset() }.getOrNull() } ?: 0f
            transformOrigin = TransformOrigin(0.5f, 1f + top / h)
        }
    }
    .sheetPanelDim(controller)
    // 压暗与褪色盖在抽屉上，并且在图层里面画——跟着抽屉一起翻折，不会留下一块没翻的方形暗影。
    // （褪色不用渲染效果：渲染效果的离屏缓冲只有本布局框那么大，抽屉画在 Material3 的偏移之后，会被裁掉一大块）
    .drawWithCache {
        val outline = AppSheetTopShape.createOutline(size, layoutDirection, this)
        val path = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            androidx.compose.ui.graphics.Path().apply { addOutline(outline) }
        } else null
        onDrawWithContent {
            drawContent()
            val depth = controller.pageDepth?.progress?.value ?: 0f
            // 压暗跟页面景深走（点击时就开始），取与交接动画的较大者
            val d = maxOf(controller.navDim.value, depth)
            if (d > 0.001f) {
                // 本修饰符在 Material3 自身的纵向偏移之前：抽屉实际画在下移 sheetOffset 之后的位置，覆盖物必须跟着下移
                val top = runCatching { controller.sheetState?.requireOffset() }.getOrNull() ?: 0f
                // 按抽屉的圆角轮廓画：直接画矩形会在圆角外露出深色直角
                translate(top = top) {
                    drawOutline(outline = outline, color = Color.Black.copy(alpha = pageDimMaxAlpha() * d))
                    // 褪色：用「饱和度」混合模式盖一层灰（饱和度为 0），只改抽屉内容的饱和度、不改亮度；API 31+（与页面区的褪色同档，低版本两边都只有压暗和翻折）
                    if (depth > 0.01f && path != null) {
                        clipPath(path) {
                            drawRect(color = Color.Gray, alpha = 0.5f * depth.coerceAtMost(1f), blendMode = BlendMode.Saturation)
                        }
                    }
                }
            }
        }
    }

/**
 * 抽屉内容 / 把手上的跟手下拉手势。必须放在抽屉**内部**（内容根节点、把手），不要放在 `ModalBottomSheet` 的 modifier 上：
 * Material3 把该 modifier 放在自身位移之前，命中区域会落在屏幕顶部，挡住遮罩的点击。
 */
fun Modifier.motionSheetDrag(controller: MotionSheetController): Modifier = if (!controller.enhanced) this else this
    .nestedScroll(controller.dragConnection)
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
    // 抽屉窗口画不到屏幕顶部：登记遮挡槽位，此时提示条退回系统 Toast
    NoticeCover()
    val currentOnClosed by rememberUpdatedState(onClosed)
    controller.onClosed = { currentOnClosed() }
    if (!controller.enhanced) return
    // 遮罩和面板一起绘在抽屉窗口内；旋转/分屏时按这个窗口的实际像素尺寸定位枢轴。
    val windowSize = LocalWindowInfo.current.containerSize
    SideEffect {
        controller.windowWidthPx = windowSize.width.toFloat()
        controller.windowHeightPx = windowSize.height.toFloat()
    }
    BackHandler { controller.close() }
    LaunchedEffect(controller) {
        val h = snapshotFlow { controller.height }.first { it > 0f }
        controller.offset.snapTo(h)
        controller.offset.animateTo(0f, MotionSheetController.OpenSpec)
    }
}

internal class SheetDragConnection(private val c: MotionSheetController) : NestedScrollConnection {
    /** 用户上滑时优先抵消抽屉已有下移，归位后的余量交给内容滚动。 */
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        // 抽屉已被下拉时，往上滑先把抽屉推回去
        if (source == NestedScrollSource.UserInput && available.y < 0f && c.offset.value > 0f) {
            val consume = maxOf(available.y, -c.offset.value)
            c.dragBy(consume)
            return Offset(0f, consume)
        }
        return Offset.Zero
    }

    /** 接收内容到顶后剩余的用户下拉距离，惯性滚动不转交给抽屉。 */
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        // 列表已到顶仍在下拉：转交给抽屉
        if (source == NestedScrollSource.UserInput && available.y > 0f) {
            c.dragBy(available.y)
            return Offset(0f, available.y)
        }
        return Offset.Zero
    }

    /** 仅在本次实际拖动且抽屉尚未归位时接管松手，避免列表惯性误关抽屉。 */
    override suspend fun onPreFling(available: Velocity): Velocity {
        if (!c.dragged) return Velocity.Zero
        if (c.offset.value == 0f) {
            c.endDrag()
            // 拉下去又推回原位、带着向上的速度松手：到顶按速度弹一下（此前这里直接放过，几乎碰不到回弹）
            if (available.y < -MotionSheetController.BOUNCE_MIN_VELOCITY) c.bounceAtTop(available.y)
            return Velocity.Zero
        }
        c.settle()
        return available
    }
}
