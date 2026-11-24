package com.tyranor.next.ui.common.motion

import android.animation.ValueAnimator
import android.app.Activity
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.animation.PathInterpolator
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.tyranor.next.theme.AppThemeColors
import com.tyranor.next.theme.PageGrey
import kotlinx.coroutines.flow.collectLatest
import java.lang.ref.WeakReference
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
 *   之后它被拉伸到虚拟窗口的矩形里、随进度淡出，露出下面正在长大的新版式。截屏失败就不带这层（第一帧只有底色）。
 *
 * 只在「增强」档、平板（最小宽度 ≥ 600dp）、系统动画开启时生效；折叠屏展开、分屏拖动、输入法顶起窗口不触发
 * （要求 Activity 收到了方向翻转的配置变化）。过渡期间触摸暂时不接收（控件还在动）。进度时钟每帧最多推进 [MAX_STEP_NANOS]：
 * 第一帧的重布局不会吃掉动画的一大段。宿主挂在 [ProvideMotionEnvironment]（每个页面根部），新页面自动具备；
 * 不过渡时不改约束、不改尺寸、不录图层。
 */
@Stable
internal class ScreenRotationState {
    /** 上一次稳定的窗口大小（过渡期间也在更新，用来识别下一次变化）。 */
    var lastSize = IntSize.Zero

    /** 旋转前的窗口大小与那一刻的画面（半分辨率，拉伸画进虚拟窗口的矩形）。 */
    var oldSize = IntSize.Zero
    var snapshot: ImageBitmap? = null

    /**
     * 是否在过渡中。普通变量：布局阶段置位，**同一帧**的布局与绘制立刻看得到（状态变量的写入要到下一帧才通知观察者，
     * 旋转后的第一帧就会直接排出新版式）。
     */
    var frozen = false

    /** 过渡的第一帧：新方向按最终尺寸正常排版（重的内容块记下最终约束），整个被旧画面盖住；排完即清。 */
    var firstFrame = false

    /** 第一帧的绘制：新排版画在底下，再整个盖上底色与旧画面（之后的帧是底色在下、虚拟窗口在上）。 */
    var coverNextDraw = false

    /** 这一帧虚拟窗口在真实窗口里的矩形（布局阶段算出，绘制阶段用）。 */
    var rectLeft = 0
    var rectTop = 0
    var rectWidth = 0
    var rectHeight = 0

    /** 过渡进度 0..1：每帧由时钟写入，布局与绘制阶段读取。 */
    var progress by mutableFloatStateOf(0f)

    /** 起止各加一：驱动时钟协程与绘制。 */
    var epoch by mutableIntStateOf(0)

    var noticeDrawNode: RotationNoticeDrawNode? = null

    private var transitionGeneration = 0
    private var committedGeneration = 0
    private val mainHandler = Handler(Looper.getMainLooper())
    private val commitRunnable = Runnable { commitSnapshotState() }

    /** 布局阶段仅读取纯变量或延后提交的状态，不在布局期写 Compose 状态。 */
    fun activeProgress(): Float =
        if (firstFrame || transitionGeneration != committedGeneration) 0f else progress

    /** 绘制阶段读取与 [coverNextDraw] 匹配的进度，确保第 0 帧在提交前也从 0 开始淡出。 */
    fun drawProgress(coverThisFrame: Boolean): Float =
        if (coverThisFrame || transitionGeneration != committedGeneration) 0f else progress

    fun snapshotAlpha(): Float {
        if (epoch < 0 || !frozen || snapshot == null || rectWidth <= 0 || rectHeight <= 0) return 0f
        return 1f - smoothStepRotation(drawProgress(coverNextDraw) / SNAPSHOT_FADE_SPAN)
    }

    fun showsSnapshot(): Boolean = snapshotAlpha() > SNAPSHOT_VISIBLE_ALPHA

    /** 布局阶段调用：窗口大小有变化时，判断是否开始过渡（要有 [activity] 刚收到的方向翻转配置变化）。 */
    fun onMeasured(activity: Activity?, size: IntSize, enabled: Boolean) {
        val previous = lastSize
        lastSize = size
        if (!enabled) {
            if (frozen) finishFromLayout()
            if (activity != null) ScreenRotationController.discard(activity)
            return
        }
        if (activity == null || previous.isEmptyForRotation() || size.isEmptyForRotation()) return
        // 方向翻转（竖 ↔ 横）才播
        if ((previous.width > previous.height) == (size.width > size.height)) return
        // 上一段还没放完又转了一次：不叠着播，直接放掉，按新方向正常画
        if (frozen) {
            finishFromLayout()
            ScreenRotationController.discard(activity)
            return
        }
        val taken = ScreenRotationController.take(activity) ?: return
        snapshot = taken.bitmap?.asImageBitmap()
        oldSize = previous
        frozen = true
        firstFrame = true
        coverNextDraw = true
        transitionGeneration++
        noticeDrawNode?.invalidateDraw()
        scheduleCommit()
    }

    private fun finishFromLayout() {
        frozen = false
        firstFrame = false
        coverNextDraw = false
        snapshot = null
        transitionGeneration++
        noticeDrawNode?.invalidateDraw()
        scheduleCommit()
    }

    private fun scheduleCommit() {
        mainHandler.removeCallbacks(commitRunnable)
        mainHandler.post(commitRunnable)
    }

    private fun commitSnapshotState() {
        committedGeneration = transitionGeneration
        progress = if (frozen) 0f else 1f
        epoch++
    }

    fun finish() {
        mainHandler.removeCallbacks(commitRunnable)
        frozen = false
        firstFrame = false
        coverNextDraw = false
        progress = 1f
        snapshot = null
        committedGeneration = transitionGeneration
        epoch++
    }

    fun dispose() {
        mainHandler.removeCallbacks(commitRunnable)
        frozen = false
        firstFrame = false
        coverNextDraw = false
        snapshot = null
    }
}

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
    override fun onAttach() { state.noticeDrawNode = this }

    override fun onDetach() {
        if (state.noticeDrawNode === this) state.noticeDrawNode = null
    }

    fun bind(next: ScreenRotationState) {
        if (next === state) return
        if (isAttached) onDetach()
        state = next
        if (isAttached) onAttach()
    }

    override fun ContentDrawScope.draw() {
        if (!state.showsSnapshot()) drawContent()
    }
}

/** 当前页面的旋转过渡状态（[rotationMorphBlock] 用）；没有宿主时为 null。 */
internal val LocalScreenRotationMorph = staticCompositionLocalOf<ScreenRotationState?> { null }

/**
 * 旋转前截下的旧画面：由 [ScreenRotationSync] 放进来，宿主在窗口变成新方向的那次布局里取走（只取一次、有时效、只给同一个 Activity）。
 * 位图只丢引用不手动回收：最后一帧的绘制可能还在用它。截屏失败时仍登记「刚旋转过」，只是没有位图。
 */
internal object ScreenRotationController {
    class Taken(val bitmap: Bitmap?)

    private var owner: WeakReference<Activity>? = null
    private var bitmap: Bitmap? = null
    private var capturedAt = 0L
    private val copyExecutor = Executors.newSingleThreadExecutor { work -> Thread(work, "screen-rotation-copy") }
    private val callbackHandler by lazy {
        val thread = HandlerThread("screen-rotation-copy-result").apply { start() }
        Handler(thread.looper)
    }
    private val copyInFlight = AtomicBoolean(false)

    /** 记下 [activity] 刚旋转过，并尽力截下窗口此刻的画面（旧画面）。在主线程调用，截屏最多阻塞 [CAPTURE_LIMIT_MILLIS]。 */
    fun capture(activity: Activity) {
        val start = SystemClock.uptimeMillis()
        synchronized(this) {
            owner = WeakReference(activity)
            capturedAt = start
            bitmap = null
        }
        val window = activity.window ?: return
        val decor = window.peekDecorView() ?: return
        val width = decor.width
        val height = decor.height
        if (width <= 0 || height <= 0 || !copyInFlight.compareAndSet(false, true)) return
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
                PixelCopy.request(window, target, { result ->
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
            synchronized(this) {
                if (owner?.get() === activity) bitmap = resultBitmap
            }
        }
    }

    @Synchronized
    fun take(activity: Activity): Taken? {
        if (owner?.get() !== activity) return null
        val fresh = SystemClock.uptimeMillis() - capturedAt <= SNAPSHOT_TTL_MILLIS
        val b = bitmap
        bitmap = null
        owner = null
        return if (fresh) Taken(b) else null
    }

    @Synchronized
    fun discard(activity: Activity) {
        if (owner?.get() === activity) {
            owner = null
            bitmap = null
        }
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
        ScreenRotationController.capture(activity)
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
        onDispose {
            state.dispose()
            if (activity != null) ScreenRotationController.discard(activity)
        }
    }

    LaunchedEffect(state) {
        snapshotFlow { state.epoch }.collectLatest {
            if (!state.frozen) return@collectLatest
            val totalNanos = ROTATION_MILLIS * 1_000_000L
            var elapsed = 0L
            var last = 0L
            while (elapsed < totalNanos) {
                val now = withFrameNanos { it }
                if (last != 0L) elapsed += min(now - last, MAX_STEP_NANOS)
                last = now
                state.progress = (elapsed.toFloat() / totalNanos).coerceIn(0f, 1f)
            }
            state.finish()
        }
    }

    Box(
        Modifier
            // 外层（真实窗口坐标）：底色、过渡期间盖在上面渐隐的旧画面
            .drawWithContent {
                if (state.epoch < 0 || !state.frozen) {
                    drawContent()
                    return@drawWithContent
                }
                val coverThisFrame = state.coverNextDraw
                val alpha = state.snapshotAlpha()
                if (coverThisFrame) {
                    // 第一帧：新方向的正常排版画在底下，整个盖上底色（旧画面在上面画）
                    state.coverNextDraw = false
                    drawContent()
                    drawRect(fill)
                } else {
                    drawRect(fill)
                    drawContent()
                }
                val snapshot = state.snapshot
                if (snapshot != null && alpha > SNAPSHOT_VISIBLE_ALPHA) {
                    drawImage(
                        snapshot,
                        dstOffset = IntOffset(state.rectLeft, state.rectTop),
                        dstSize = IntSize(state.rectWidth, state.rectHeight),
                        alpha = alpha,
                        filterQuality = FilterQuality.Medium,
                    )
                }
            }
            .layout { measurable, constraints ->
                val windowW = constraints.maxWidth
                val windowH = constraints.maxHeight
                state.onMeasured(activity, IntSize(windowW, windowH), enabledNow.value)
                if (!state.frozen || windowW <= 0 || windowH <= 0) {
                    val placeable = measurable.measure(constraints)
                    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                } else {
                    // 虚拟窗口：宽高从旧窗口过渡到新窗口，再等比缩放贴进真实窗口
                    val e = ROTATION_INTERPOLATOR.getInterpolation(state.activeProgress().coerceIn(0f, 1f))
                    val virtualW = lerpInt(state.oldSize.width, windowW, e).coerceAtLeast(1)
                    val virtualH = lerpInt(state.oldSize.height, windowH, e).coerceAtLeast(1)
                    val scale = min(windowW.toFloat() / virtualW, windowH.toFloat() / virtualH)
                    val left = ((windowW - virtualW * scale) / 2f)
                    val top = ((windowH - virtualH * scale) / 2f)
                    state.rectLeft = left.roundToInt()
                    state.rectTop = top.roundToInt()
                    state.rectWidth = (virtualW * scale).roundToInt()
                    state.rectHeight = (virtualH * scale).roundToInt()
                    if (state.firstFrame) {
                        // 第一帧：新方向按最终尺寸正常排版（重的内容块借此记下最终约束），盖在旧画面下面
                        val placeable = measurable.measure(constraints)
                        state.firstFrame = false
                        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                    } else {
                        val placeable = measurable.measure(Constraints.fixed(virtualW, virtualH))
                        layout(windowW, windowH) {
                            placeable.placeWithLayer(left.roundToInt(), top.roundToInt()) {
                                transformOrigin = TransformOrigin(0f, 0f)
                                scaleX = scale
                                scaleY = scale
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
        if (!morph.frozen || !freeze) {
            cache.final = null
            val placeable = measurable.measure(constraints)
            return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
        }
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

private fun smoothStepRotation(x: Float): Float {
    val c = x.coerceIn(0f, 1f)
    return c * c * (3f - 2f * c)
}

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
private val ROTATION_INTERPOLATOR = PathInterpolator(0.4f, 0f, 0.2f, 1f)
