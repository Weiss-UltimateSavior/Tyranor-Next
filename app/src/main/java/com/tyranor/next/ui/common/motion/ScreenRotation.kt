package com.tyranor.next.ui.common.motion

import android.animation.ValueAnimator
import android.app.Activity
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.animation.PathInterpolator
import androidx.compose.animation.core.FloatSpringSpec
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.tyranor.next.theme.AppThemeColors
import com.tyranor.next.theme.PageGrey
import kotlinx.coroutines.flow.collectLatest
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 平板横竖屏切换（屏幕旋转）时的页面过渡：页面在清单里声明自己处理方向 / 尺寸变化而不被销毁重建（不再整页重载），
 * 窗口变成新方向后，**界面本身连续地「长」成新版式**，而不是把旧画面换成新画面：
 *
 * - 页面内容在一个「虚拟窗口」里实时布局：虚拟窗口的宽高从旧窗口的大小逐帧过渡到新窗口的大小，
 *   再整体等比缩放贴进真实窗口（起步缩小站直、结束时 1:1 铺满）——顶栏、卡片、侧栏、设置列表这些轻的内容每一帧都按当时的尺寸真实排出来，
 *   位置、大小连续变化，没有任何一帧是「拼」出来的，也不转圈；
 * - **重的内容块**（书架的书、藏书阁的书柜；常驻在后台的其它标签页）逐帧重新组合 / 排版负担不起：它们在新方向的**第一帧**
 *   按最终尺寸排好，之后过渡全程保持这个排版不变，只是等比缩放贴进虚拟窗口分给它的那块矩形（[rotationMorphBlock]），随矩形一起长到 1:1；
 *   结束时与正常布局完全一致，不会有换版的一跳；
 * - 旋转的那一刻 Activity 先截下旧画面（[ScreenRotationSync]，`PixelCopy` 半分辨率位图，在专门的线程上跑，主线程最多等
 *   [CAPTURE_LIMIT_MILLIS]）：第一帧（新方向正常排版、整页重新布局，上百毫秒）整个被它盖住（起步矩形里是旧版式站直的样子），
 *   之后它被拉伸到虚拟窗口的矩形里、随进度淡出。截屏失败就直接画实时虚拟窗口，不用底色盖住页面。
 * - 中途再转时，从上一帧已绘制的虚拟尺寸接力，改用页面弹簧；截取上一帧的 viewport，不把留白带进新的 viewport。
 *
 * 只在「增强」档、平板（最小宽度 ≥ 600dp）、系统动画开启时生效；折叠屏展开、分屏拖动、输入法顶起窗口不触发
 * （要求 Activity 收到了方向翻转的配置变化）。过渡期间触摸暂时不接收（控件还在动）。进度时钟每帧最多推进 [MAX_STEP_NANOS]：
 * 第一帧的重布局不会吃掉动画的一大段。宿主挂在 [ProvideMotionEnvironment]（每个页面根部），新页面自动具备；
 * 不过渡时不改约束、不改尺寸、不录图层。
 */
@Stable
internal class ScreenRotationState {
    private var lastSize = IntSize.Zero
    private var snapshot: RotationSnapshot? = null
    private var leg: RotationLeg? = null
    val frozen: Boolean get() = leg != null
    val freezesBlocks: Boolean get() = leg != null && hasTargetLayout

    /** 只有完整的新截图才能盖住按目标窗口预排版的那一帧。 */
    var firstFrame = false
    var coverNextDraw = false
    var viewport: RotationViewport? = null
    var windowOrigin = IntOffset.Zero
    private var hasTargetLayout = false
    private var drawnWindow = IntSize.Zero
    private var drawnViewport: RotationViewport? = null
    private var drawnBoundsInWindow: IntRect? = null
    private var drawnSnapshotAlpha = 0f
    private var drawnSnapshotHasNotice = false

    private var progress by mutableFloatStateOf(0f)

    /** 布局同步更新 [leg]，稍后提交起止通知；时钟、布局和两处绘制均订阅。 */
    var publishedLeg by mutableStateOf<RotationLeg?>(null)
        private set

    var noticeDrawNode: RotationNoticeDrawNode? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val commitRunnable = Runnable { publishLeg() }

    fun frameProgress(): Float {
        val published = publishedLeg
        return if (published != null && published === leg) progress else 0f
    }

    fun virtualSize(window: IntSize, timeFraction: Float): IntSize = leg?.virtualSize(window, timeFraction) ?: window

    fun snapshotAlpha(progress: Float): Float = rotationSnapshotAlpha(snapshot?.startAlpha ?: 0f, progress)

    fun snapshotImage(): ImageBitmap? = snapshot?.image

    fun showsSnapshotNotice(): Boolean {
        val progress = frameProgress()
        return rotationSnapshotOwnsNotice(snapshot?.hasNotice == true, snapshotAlpha(progress))
    }

    fun recordDraw(window: IntSize, viewport: RotationViewport?, snapshotAlpha: Float) {
        drawnWindow = window
        drawnViewport = viewport
        drawnBoundsInWindow = viewport?.bounds?.translate(windowOrigin)
        drawnSnapshotAlpha = snapshotAlpha
        drawnSnapshotHasNotice = snapshotAlpha > SNAPSHOT_VISIBLE_ALPHA && snapshot?.hasNotice == true
    }

    fun capturePlan(window: IntSize): RotationCapturePlan? = rotationCapturePlan(
        window = window,
        previousWindow = drawnWindow,
        viewport = drawnBoundsInWindow,
        liveNotice = noticeDrawNode?.drewContent == true,
        snapshotNotice = drawnSnapshotHasNotice,
    )

    /** 布局阶段调用：窗口大小有变化时，判断是否开始过渡（要有 [activity] 刚收到的方向翻转配置变化）。 */
    fun onMeasured(activity: Activity?, size: IntSize, enabled: Boolean) {
        val previous = lastSize
        lastSize = size
        if (!enabled || activity == null || previous.isEmptyForRotation() || size.isEmptyForRotation()) {
            if (frozen) {
                clearLeg()
                scheduleCommit()
            }
            if (activity != null) ScreenRotationController.discard(activity)
            return
        }
        if (size == previous) return
        if (previous.isLandscapeForRotation() == size.isLandscapeForRotation()) {
            ScreenRotationController.discard(activity)
            return
        }
        val taken = ScreenRotationController.take(activity, size) ?: return
        val nextLeg = rotationRetarget(previous, leg, drawnViewport)
        val nextSnapshot = taken.bitmap?.let { RotationSnapshot(it.asImageBitmap(), taken.hasNotice, 1f) }
        snapshot = nextSnapshot ?: snapshot?.let { old ->
            rotationFallbackAlpha(drawnSnapshotAlpha)?.let { old.copy(startAlpha = it) }
        }
        drawnSnapshotAlpha = 0f
        leg = nextLeg
        hasTargetLayout = nextSnapshot != null
        firstFrame = hasTargetLayout
        coverNextDraw = false
        noticeDrawNode?.invalidateDraw()
        scheduleCommit()
    }

    private fun clearLeg() {
        leg = null
        firstFrame = false
        coverNextDraw = false
        hasTargetLayout = false
        snapshot = null
        viewport = null
        noticeDrawNode?.invalidateDraw()
    }

    private fun scheduleCommit() {
        mainHandler.removeCallbacks(commitRunnable)
        mainHandler.post(commitRunnable)
    }

    private fun publishLeg() {
        progress = if (frozen) 0f else 1f
        publishedLeg = leg
    }

    fun advance(expected: RotationLeg, fraction: Float): Boolean {
        if (leg !== expected) return false
        progress = fraction
        return true
    }

    fun finish(expected: RotationLeg) {
        if (leg !== expected) return
        mainHandler.removeCallbacks(commitRunnable)
        clearLeg()
        publishLeg()
    }

    fun dispose() {
        mainHandler.removeCallbacks(commitRunnable)
        clearLeg()
        publishLeg()
        recordDraw(IntSize.Zero, null, 0f)
    }
}

internal class RotationLeg(val from: IntSize, val redirected: Boolean) {
    private val spring = if (redirected) MotionTokens.page(0.001f).let {
        FloatSpringSpec(it.dampingRatio, it.stiffness, visibilityThreshold = 0.001f)
    } else null
    val durationNanos: Long = spring?.getDurationNanos(0f, 1f, 0f) ?: ROTATION_MILLIS * 1_000_000L

    fun sizeProgress(timeFraction: Float): Float = if (spring == null) {
        ROTATION_INTERPOLATOR.getInterpolation(timeFraction)
    } else {
        spring.getValueFromNanos((timeFraction.toDouble() * durationNanos).toLong(), 0f, 1f, 0f).coerceIn(0f, 1f)
    }

    fun virtualSize(window: IntSize, timeFraction: Float): IntSize {
        if (timeFraction <= 0f) return from
        if (timeFraction >= 1f) return window
        val fraction = sizeProgress(timeFraction)
        return IntSize(
            lerpInt(from.width, window.width, fraction).coerceAtLeast(1),
            lerpInt(from.height, window.height, fraction).coerceAtLeast(1),
        )
    }
}

private data class RotationSnapshot(val image: ImageBitmap, val hasNotice: Boolean, val startAlpha: Float)

internal fun Modifier.handoffRotationNotice(state: ScreenRotationState): Modifier =
    this.then(RotationNoticeDrawElement(state))

internal fun Modifier.blockRotationTouches(state: ScreenRotationState): Modifier = pointerInput(state) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (state.frozen) event.changes.forEach { it.consume() }
        }
    }
}

private data class RotationNoticeDrawElement(val state: ScreenRotationState) : ModifierNodeElement<RotationNoticeDrawNode>() {
    override fun create() = RotationNoticeDrawNode(state)
    override fun update(node: RotationNoticeDrawNode) = node.bind(state)
    override fun InspectorInfo.inspectableProperties() { name = "handoffRotationNotice" }
}

internal class RotationNoticeDrawNode(private var state: ScreenRotationState) : Modifier.Node(), DrawModifierNode {
    var drewContent = false
        private set

    override fun onAttach() { state.noticeDrawNode = this }

    override fun onDetach() {
        if (state.noticeDrawNode === this) state.noticeDrawNode = null
    }

    fun bind(next: ScreenRotationState) {
        if (next === state) return
        if (isAttached) onDetach()
        state = next
        drewContent = false
        if (isAttached) onAttach()
    }

    override fun ContentDrawScope.draw() {
        drewContent = !state.showsSnapshotNotice()
        if (drewContent) drawContent()
    }
}

/** 当前页面的旋转过渡状态（[rotationMorphBlock] 用）；没有宿主时为 null。 */
internal val LocalScreenRotationMorph = staticCompositionLocalOf<ScreenRotationState?> { null }

/**
 * 旋转前截下的旧画面：由 [ScreenRotationSync] 放进来，宿主在窗口变成新方向的那次布局里取走（只取一次、有时效、只给同一个 Activity）。
 * 位图只丢引用不手动回收：最后一帧的绘制可能还在用它。截屏失败时仍登记「刚旋转过」，只是没有位图。
 */
internal object ScreenRotationController {
    class Taken(val bitmap: Bitmap?, val hasNotice: Boolean)

    private class Pending(activity: Activity, val orientation: Int, val expiresAt: Long) {
        val owner = WeakReference(activity)
        var result = Taken(null, false)
    }

    private val hosts = WeakHashMap<Activity, WeakReference<ScreenRotationState>>()
    private var pending: Pending? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val expireCapture = Runnable { pending = null }
    private val copyExecutor = Executors.newSingleThreadExecutor { work -> Thread(work, "screen-rotation-copy") }
    private val callbackHandler by lazy {
        val thread = HandlerThread("screen-rotation-copy-result").apply { start() }
        Handler(thread.looper)
    }
    private val copyInFlight = AtomicBoolean(false)

    fun bind(activity: Activity, state: ScreenRotationState) {
        hosts[activity] = WeakReference(state)
    }

    fun unbind(activity: Activity, state: ScreenRotationState) {
        if (hosts[activity]?.get() !== state) return
        hosts.remove(activity)
        discard(activity)
    }

    /** 记下 [activity] 刚旋转过，并尽力截下窗口此刻的画面（旧画面）。在主线程调用，截屏最多阻塞 [CAPTURE_LIMIT_MILLIS]。 */
    fun capture(activity: Activity, orientation: Int) {
        val start = SystemClock.uptimeMillis()
        val capture = Pending(activity, orientation, start + SNAPSHOT_TTL_MILLIS)
        clearPending()
        pending = capture
        mainHandler.postAtTime(expireCapture, capture.expiresAt)
        val window = activity.window ?: return
        val decor = window.peekDecorView() ?: return
        val size = IntSize(decor.width, decor.height)
        if (size.isEmptyForRotation()) return
        val host = hosts[activity]?.get()
        val plan = if (host != null) host.capturePlan(size) ?: return else RotationCapturePlan(null, false)
        if (!copyInFlight.compareAndSet(false, true)) return
        val crop = plan.crop
        val source = crop?.let { Rect(it.left, it.top, it.right, it.bottom) }
        val width = crop?.width ?: size.width
        val height = crop?.height ?: size.height
        val deadline = start + CAPTURE_LIMIT_MILLIS
        val latch = CountDownLatch(1)
        var resultBitmap: Bitmap? = null
        fun complete(result: Bitmap? = null) {
            resultBitmap = result
            copyInFlight.set(false)
            latch.countDown()
        }
        copyExecutor.execute {
            try {
                val handler = callbackHandler
                if (SystemClock.uptimeMillis() >= deadline) {
                    complete()
                    return@execute
                }
                val target = Bitmap.createBitmap(
                    (width * SNAPSHOT_SCALE).roundToInt().coerceAtLeast(1),
                    (height * SNAPSHOT_SCALE).roundToInt().coerceAtLeast(1),
                    Bitmap.Config.ARGB_8888,
                )
                if (SystemClock.uptimeMillis() >= deadline) {
                    complete()
                    return@execute
                }
                PixelCopy.request(window, source, target, { result ->
                    complete(target.takeIf { result == PixelCopy.SUCCESS })
                }, handler)
            } catch (_: Exception) {
                complete()
            } catch (_: OutOfMemoryError) {
                complete()
            }
        }
        val finished = try {
            latch.await((deadline - SystemClock.uptimeMillis()).coerceAtLeast(0L), TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (finished && SystemClock.uptimeMillis() <= deadline) {
            capture.result = Taken(resultBitmap, resultBitmap != null && plan.mayContainNotice)
        }
    }

    fun take(activity: Activity, size: IntSize): Taken? {
        val capture = pending ?: return null
        if (capture.owner.get() !== activity) return null
        clearPending()
        val orientation = if (size.isLandscapeForRotation()) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
        return capture.result.takeIf { capture.orientation == orientation && SystemClock.uptimeMillis() < capture.expiresAt }
    }

    fun discard(activity: Activity) {
        if (pending?.owner?.get() === activity) clearPending()
    }

    private fun clearPending() {
        mainHandler.removeCallbacks(expireCapture)
        pending = null
    }
}

/**
 * 每个页面 Activity 持有一个，在 `onConfigurationChanged` 里（先于 `super`）调用：平板方向翻转时，趁屏幕上还是旧画面截下来，
 * 供 [ScreenRotationHost] 在窗口按新方向布局时用。「普通」档、手机、关闭系统动画、不在前台时什么都不做。
 */
class ScreenRotationSync(private val activity: Activity) {
    private var orientation = activity.resources.configuration.orientation

    fun onConfigurationChanged(newConfig: Configuration) {
        val previousOrientation = orientation
        orientation = newConfig.orientation
        if (previousOrientation == orientation || previousOrientation == Configuration.ORIENTATION_UNDEFINED ||
            orientation == Configuration.ORIENTATION_UNDEFINED
        ) {
            return
        }
        if (!MotionMode.enhanced || newConfig.smallestScreenWidthDp < TABLET_MIN_SMALLEST_WIDTH_DP) return
        if (!ValueAnimator.areAnimatorsEnabled()) return
        val resumed = (activity as? LifecycleOwner)?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) == true
        if (!resumed) return
        ScreenRotationController.capture(activity, orientation)
    }
}

@Composable
internal fun ScreenRotationHost(state: ScreenRotationState, content: @Composable () -> Unit) {
    val configuration = LocalConfiguration.current
    val enabled = MotionMode.enhanced &&
        (configuration.smallestScreenWidthDp >= TABLET_MIN_SMALLEST_WIDTH_DP) &&
        ValueAnimator.areAnimatorsEnabled()
    // 组合结构不随 enabled 变化（在应用设置里切动效档不能把整个页面的内容重建）：不满足条件时只是直通
    val enabledNow = rememberUpdatedState(enabled)
    val context = LocalContext.current
    val activity = remember(context) { PageTransitions.findActivity(context) }
    val fill = rotationFill()

    DisposableEffect(state, activity) {
        if (activity != null) ScreenRotationController.bind(activity, state)
        onDispose {
            state.dispose()
            if (activity != null) ScreenRotationController.unbind(activity, state)
        }
    }

    LaunchedEffect(state) {
        snapshotFlow { state.publishedLeg }.collectLatest { leg ->
            if (leg == null) return@collectLatest
            var elapsedNanos = 0L
            var lastFrameNanos = withFrameNanos { it }
            while (elapsedNanos < leg.durationNanos) {
                val frameNanos = withFrameNanos { it }
                elapsedNanos = advanceRotationTime(elapsedNanos, lastFrameNanos, frameNanos, leg.durationNanos)
                lastFrameNanos = frameNanos
                if (!state.advance(leg, elapsedNanos.toFloat() / leg.durationNanos)) return@collectLatest
            }
            state.finish(leg)
        }
    }

    Box(
        Modifier
            .onPlaced { coordinates ->
                val origin = coordinates.localToWindow(Offset.Zero)
                if (origin.x.isFinite() && origin.y.isFinite()) {
                    state.windowOrigin = IntOffset(origin.x.roundToInt(), origin.y.roundToInt())
                }
            }
            // 外层（真实窗口坐标）：底色、过渡期间盖在上面渐隐的旧画面
            .drawWithContent {
                val progress = state.frameProgress()
                val window = IntSize(size.width.roundToInt(), size.height.roundToInt())
                val viewport = state.viewport
                if (viewport == null) {
                    drawContent()
                    state.recordDraw(window, null, 0f)
                    return@drawWithContent
                }
                val alpha = state.snapshotAlpha(progress)
                val snapshot = state.snapshotImage()
                val drawsSnapshot = snapshot != null && alpha > SNAPSHOT_VISIBLE_ALPHA
                val coverThisFrame = state.coverNextDraw && drawsSnapshot
                state.coverNextDraw = false
                if (coverThisFrame) {
                    // 第一帧：新方向的正常排版画在底下，整个盖上底色（旧画面在上面画）
                    drawContent()
                    drawRect(fill)
                } else {
                    drawRect(fill)
                    drawContent()
                }
                if (snapshot != null && alpha > SNAPSHOT_VISIBLE_ALPHA) {
                    drawImage(
                        snapshot,
                        dstOffset = viewport.bounds.topLeft,
                        dstSize = viewport.bounds.size,
                        alpha = alpha,
                        filterQuality = FilterQuality.Medium,
                    )
                }
                state.recordDraw(window, viewport, if (drawsSnapshot) alpha else 0f)
            }
            .layout { measurable, constraints ->
                val windowW = constraints.maxWidth
                val windowH = constraints.maxHeight
                val window = IntSize(windowW, windowH)
                state.onMeasured(activity, window, enabledNow.value && constraints.hasBoundedWidth && constraints.hasBoundedHeight)
                val progress = state.frameProgress()
                if (!state.frozen) {
                    val placeable = measurable.measure(constraints)
                    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                } else {
                    // 虚拟窗口：宽高从旧窗口过渡到新窗口，再等比缩放贴进真实窗口
                    val virtual = state.virtualSize(window, progress)
                    val viewport = rotationViewport(window, virtual)
                    state.viewport = viewport
                    if (state.firstFrame) {
                        // 第一帧：新方向按最终尺寸正常排版（重的内容块借此记下最终约束），盖在旧画面下面
                        val placeable = measurable.measure(constraints)
                        state.firstFrame = false
                        state.coverNextDraw = true
                        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                    } else {
                        val placeable = measurable.measure(Constraints.fixed(virtual.width, virtual.height))
                        layout(windowW, windowH) {
                            placeable.placeWithLayer(viewport.bounds.left, viewport.bounds.top) {
                                transformOrigin = TransformOrigin(0f, 0f)
                                scaleX = viewport.scale
                                scaleY = viewport.scale
                            }
                        }
                    }
                }
            },
        propagateMinConstraints = true,
    ) {
        CompositionLocalProvider(LocalScreenRotationMorph provides state) {
            content()
        }
    }
}

private fun IntSize.isEmptyForRotation(): Boolean = width <= 0 || height <= 0
private fun IntSize.isLandscapeForRotation(): Boolean = width > height

internal class RotationViewport(val virtualSize: IntSize, val bounds: IntRect, val scale: Float)

internal fun rotationViewport(window: IntSize, virtual: IntSize): RotationViewport {
    val scale = min(window.width.toFloat() / virtual.width, window.height.toFloat() / virtual.height)
    val width = virtual.width * scale
    val height = virtual.height * scale
    val left = ((window.width - width) / 2f).roundToInt()
    val top = ((window.height - height) / 2f).roundToInt()
    val right = (left + width.roundToInt().coerceAtLeast(1)).coerceAtMost(window.width)
    val bottom = (top + height.roundToInt().coerceAtLeast(1)).coerceAtMost(window.height)
    return RotationViewport(virtual, IntRect(left, top, right, bottom), scale)
}

internal fun rotationRetarget(previous: IntSize, active: RotationLeg?, drawn: RotationViewport?): RotationLeg =
    RotationLeg(drawn?.virtualSize ?: active?.from ?: previous, redirected = drawn != null || active != null)

internal data class RotationCapturePlan(val crop: IntRect?, val mayContainNotice: Boolean)

internal fun rotationCapturePlan(
    window: IntSize,
    previousWindow: IntSize,
    viewport: IntRect?,
    liveNotice: Boolean,
    snapshotNotice: Boolean,
): RotationCapturePlan? {
    if (window.isEmptyForRotation() || (!previousWindow.isEmptyForRotation() && previousWindow != window)) return null
    if (viewport != null && (viewport.width <= 0 || viewport.height <= 0 || viewport.left < 0 || viewport.top < 0 ||
            viewport.right > window.width || viewport.bottom > window.height)
    ) return null
    return RotationCapturePlan(viewport, liveNotice || snapshotNotice)
}

internal fun advanceRotationTime(elapsed: Long, previousFrame: Long, frame: Long, duration: Long): Long =
    (elapsed + (frame - previousFrame).coerceIn(0L, MAX_STEP_NANOS)).coerceAtMost(duration)

/**
 * 重的内容块（书架的书、藏书阁的书柜、后台的其它标签页）：旋转过渡里不跟着虚拟窗口逐帧重排——
 * 第一帧（新方向按最终尺寸正常排版）记下自己的最终约束，之后过渡全程用这个约束排版（内容与第一帧完全一样，不重组、不重排），
 * 只是等比缩放贴进虚拟窗口分给它的那块矩形（居中），随矩形一起长到 1:1；过渡结束与没有这一层完全一致。
 * 放在 `fillMaxWidth()` / `weight()` 等给出尺寸的修饰符**之后**，这样拿到的是分给它的矩形。
 *
 * @param freeze false 时不参与（轻的内容跟着虚拟窗口正常重排）；常驻页按「是否隐藏」传，隐藏页不画，冻住就是一次空测量。
 */
@Composable
internal fun Modifier.rotationMorphBlock(freeze: Boolean = true): Modifier {
    val morph = LocalScreenRotationMorph.current ?: return this
    val cache = remember { RotationBlockCache() }
    return this.layout { measurable, constraints ->
        if (!morph.freezesBlocks || !freeze) {
            cache.final = null
            val placeable = measurable.measure(constraints)
            return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
        }
        if (morph.firstFrame) cache.final = constraints
        val final = cache.final ?: constraints.also { cache.final = it }
        val placeable = measurable.measure(final)
        if (morph.firstFrame) {
            return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
        }
        val allotW = if (constraints.hasBoundedWidth) constraints.maxWidth else placeable.width
        val allotH = if (constraints.hasBoundedHeight) constraints.maxHeight else placeable.height
        val scale = min(allotW.toFloat() / placeable.width.coerceAtLeast(1), allotH.toFloat() / placeable.height.coerceAtLeast(1))
        val offsetX = ((allotW - placeable.width * scale) / 2f).roundToInt()
        val offsetY = ((allotH - placeable.height * scale) / 2f).roundToInt()
        layout(allotW, allotH) {
            placeable.placeWithLayer(offsetX, offsetY) {
                transformOrigin = TransformOrigin(0f, 0f)
                scaleX = scale
                scaleY = scale
            }
        }
    }
}

private class RotationBlockCache {
    var final: Constraints? = null
}

/** 转动过程中两侧留出的底色：页面底色；玻璃类外观页面底色是透明的（画在内容里），取接近的深 / 浅色。 */
@Composable
private fun rotationFill(): Color {
    val base = PageGrey
    return when {
        base.alpha >= 0.99f -> base
        AppThemeColors.isDark || AppThemeColors.hasDarkBackdrop -> Color(0xFF0B0C0E)
        else -> Color(0xFFDDE1E6)
    }
}

private fun lerpInt(from: Int, to: Int, t: Float): Int = (from + (to - from) * t).roundToInt()

internal fun rotationSnapshotAlpha(startAlpha: Float, progress: Float): Float {
    val fraction = (progress / SNAPSHOT_FADE_SPAN).coerceIn(0f, 1f)
    return startAlpha * (1f - fraction * fraction * (3f - 2f * fraction))
}

internal fun rotationFallbackAlpha(drawnAlpha: Float): Float? = drawnAlpha.takeIf { it > SNAPSHOT_VISIBLE_ALPHA }

internal fun rotationSnapshotOwnsNotice(hasNotice: Boolean, alpha: Float): Boolean =
    hasNotice && alpha > SNAPSHOT_VISIBLE_ALPHA

private const val TABLET_MIN_SMALLEST_WIDTH_DP = 600
private const val ROTATION_MILLIS = 520L
private const val MAX_STEP_NANOS = 24_000_000L

/** 旧画面（拉伸盖在虚拟窗口上）在过渡进度的前这一段里淡出。 */
private const val SNAPSHOT_FADE_SPAN = 0.28f
private const val SNAPSHOT_VISIBLE_ALPHA = 0.001f
private const val SNAPSHOT_SCALE = 0.5f
private const val SNAPSHOT_TTL_MILLIS = 2000L
private const val CAPTURE_LIMIT_MILLIS = 80L

/** 标准的先慢后快再慢（Material 强调曲线）：整套布局的位移 / 缩放没有生硬的起步和急停。 */
private val ROTATION_INTERPOLATOR by lazy { PathInterpolator(0.4f, 0f, 0.2f, 1f) }
