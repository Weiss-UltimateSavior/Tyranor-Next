package com.tyranor.next.ui.common.motion

import android.app.Activity
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.PixelCopy
import android.view.animation.PathInterpolator
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import java.lang.ref.WeakReference
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 主题切换的通用过渡：切换深浅色、外观主题等「整屏换皮」时——无论是用户在设置里主动切换，
 * 还是系统深浅色变化触发的自动切换——先截下当前画面，应用新主题后，以一个点为圆心用软边圆形向外揭开新界面
 * （旧画面留在圆外并轻微放大后退，圆边有光环、拖尾涟漪与飘散的碎光点缀）。
 *
 * 用法（主动切换）：任何会让整屏换皮的设置，把「应用新主题」的代码交给 [rememberThemeSwitch] 返回的函数执行：
 * ```
 * val themeSwitch = rememberThemeSwitch()
 * onSelectedIndexChange = { themeSwitch { AppSettingsStore.setThemeMode(ctx, mode); AppThemeColors.refresh(ctx) } }
 * ```
 * 系统主题自动变化见 [SystemThemeSync]（页面原地接收 uiMode 变化而不重建，保持旧主题直到截完旧画面）。
 * 宿主挂在 [ProvideMotionEnvironment]（每个页面根部），新页面自动具备；「普通」档、系统关闭动画、截屏失败时直接应用，
 * 没有过渡。过渡期间不拦截触摸；揭开途中再次触发会接管：以当前合成画面重新截屏、从新的按压点重新扩散（同一时间只有一个揭开在播放）。
 *
 * 快照与动画放在不依赖组合的全局控制器里：主题一变，页面根部的组合可能整棵重建，宿主随之销毁，
 * 动画和旧画面必须在重建后继续，否则会被中途取消；进度用 Choreographer 帧回调驱动（不依赖 Compose 帧时钟，且每帧推进量有上限）。
 */
private object ThemeRevealController {
    var snapshot by mutableStateOf<ImageBitmap?>(null)
    var progress by mutableFloatStateOf(0f)
    var origin: Offset = Offset.Zero
    var owner: WeakReference<Activity>? = null
    /** 进度时钟的代数：揭开途中再次触发（重入）会换新一代，旧时钟的帧回调看到代数不符就自行结束。 */
    private var clockGeneration = 0

    /**
     * 帧驱动的进度时钟：每帧最多推进 [MAX_STEP_NANOS]，所以途中偶发的重帧（整页重组 100ms+）
     * 不会一口气吃掉动画的一大段而「跳」过去——重帧只会让那一刻慢一点，整段动画仍完整地被看到。
     * 平台 ValueAnimator 按墙钟走，做不到这一点。
     */
    private fun startClock(durationMillis: Int) {
        val interpolator = REVEAL_INTERPOLATOR
        var last = 0L
        var elapsed = 0L
        val generation = ++clockGeneration
        Choreographer.getInstance().postFrameCallback(object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                // 已被更新一代的揭开接管（重入）：这一代的时钟到此为止，不再推进进度、也不收尾
                if (generation != clockGeneration) return
                if (last != 0L) elapsed += min(frameTimeNanos - last, MAX_STEP_NANOS)
                last = frameTimeNanos
                val x = (elapsed.toFloat() / (durationMillis * 1_000_000f)).coerceIn(0f, 1f)
                progress = interpolator.getInterpolation(x)
                if (x >= 1f) finish() else Choreographer.getInstance().postFrameCallback(this)
            }
        })
    }

    private fun finish() {
        // 只丢引用，不手动回收：最后一帧的绘制可能还在用这张位图
        snapshot = null
        owner = null
    }

    /**
     * @param origin 揭开圆心；[Offset.Unspecified] 时：[fromTop] 为 true 取屏幕顶部中央（系统切换：下拉通知栏的位置），否则取屏幕中心
     */
    fun run(activity: Activity?, origin: Offset, fromTop: Boolean, apply: () -> Unit) {
        val window = activity?.window
        // 系统关闭动画（动画时长缩放 = 0）时同样直接应用，与其它动效一致。
        // 揭开途中再次触发（连续切换深浅色 / 外观）不再直接应用：下面会把「当前合成画面」（含进行中的揭开）重新截下来
        // 当作新一轮的旧画面，从新的按压点重新扩散，连续切换不会突兀
        if (!MotionMode.enhanced || window == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            !android.animation.ValueAnimator.areAnimatorsEnabled()
        ) {
            apply()
            return
        }
        val decor = window.decorView
        val width = decor.width
        val height = decor.height
        if (width <= 0 || height <= 0) {
            apply()
            return
        }
        val bitmap = try {
            // 半分辨率：旧画面本来就在被擦掉，省 3/4 的显存、截屏与每帧绘制开销
            Bitmap.createBitmap(
                (width * SNAPSHOT_SCALE).roundToInt().coerceAtLeast(1),
                (height * SNAPSHOT_SCALE).roundToInt().coerceAtLeast(1),
                Bitmap.Config.ARGB_8888,
            )
        } catch (_: OutOfMemoryError) {
            apply()
            return
        }
        val center = when {
            origin.isSpecified -> origin
            fromTop -> Offset(width / 2f, 0f)
            else -> Offset(width / 2f, height / 2f)
        }
        try {
            PixelCopy.request(window, bitmap, { result ->
                if (result == PixelCopy.SUCCESS) {
                    this.owner = WeakReference(activity)
                    this.origin = center
                    progress = 0f
                    // 应用新主题与显示旧画面在同一帧：先有覆盖层再换皮，不会闪一下
                    snapshot = bitmap.asImageBitmap()
                    apply()
                    // 新主题首次重组很重（整页换色）：这几帧里动画若已在计时，会被卡顿吞掉头几帧、看起来一跳一跳。
                    // 先让这几帧过去（覆盖层此时完整显示旧画面，看不出来），再开始计时
                    // 立刻起跑：进度时钟每帧最多推进 24ms，新主题首次重组那一帧（100ms+）只会算作 24ms，
                    // 不会吃掉动画，所以不必再等它过去——点下去就开始扩散，跟手
                    startClock(revealMillis(center, width, height, decor.resources.displayMetrics.density))
                } else {
                    apply()
                }
            }, Handler(Looper.getMainLooper()))
        } catch (_: Exception) {
            apply()
        }
    }
}

/** 通过揭开过渡应用「系统主题变化」引起的换皮；[origin] 为 null 时从屏幕顶部中央揭开。供 [SystemThemeSync] 使用。 */
internal fun runThemeReveal(activity: Activity, origin: Offset? = null, apply: () -> Unit) {
    ThemeRevealController.run(activity, origin ?: Offset.Unspecified, fromTop = true, apply = apply)
}

/** 当前页面根部的主题揭开入口；没有宿主（如部分独立窗口）时为 null，调用方直接应用即可。 */
@Stable
class ThemeRevealState internal constructor(private val activity: Activity?) {
    /** 根容器内最近一次按下的位置：揭开的圆心（下拉弹出层渲染在根容器内，取到的是所点的那个选项）。 */
    internal var lastTouch: Offset = Offset.Unspecified

    /** 通过揭开过渡应用 [apply]（新主题）；不能过渡时直接调用 [apply]。 */
    fun run(apply: () -> Unit) = ThemeRevealController.run(activity, lastTouch, fromTop = false, apply = apply)
}

val LocalThemeReveal = staticCompositionLocalOf<ThemeRevealState?> { null }

/** 返回「通过主题揭开过渡应用新主题」的函数，用法见 [ThemeRevealController] 的说明。 */
@Composable
fun rememberThemeSwitch(): (apply: () -> Unit) -> Unit {
    val reveal = LocalThemeReveal.current
    return remember(reveal) { { apply -> if (reveal != null) reveal.run(apply) else apply() } }
}

/**
 * 主题揭开宿主：包住页面内容并在最上层绘制旧画面覆盖层；布局与不包时完全一致（不改约束、不改尺寸）。
 * 宿主本身可以随主题变化被重建：覆盖层从全局控制器读取快照与进度，重建后继续播放。
 *
 * 视觉：没有「一条线」的圆边——旧画面被一个**很宽的羽化前沿**渐变擦除：圆心附近完全是新主题，
 * 向外按平滑曲线一点点过渡回旧画面，前沿处叠一层主题色的柔光，像一圈光晕带着新主题向四周扩散；
 * 旧画面同时轻微放大后退，新主题则从略大的尺寸缓缓落稳（一前一后的景深），光晕里有几粒飘散的柔光点。
 */
@Composable
internal fun ThemeRevealHost(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val activity = remember(context) { PageTransitions.findActivity(context) }
    val state = remember(activity) { ThemeRevealState(activity) }
    val accent = MaterialTheme.colorScheme.primary
    CompositionLocalProvider(LocalThemeReveal provides state) {
        Box(
            Modifier.pointerInput(state) {
                // 只观察、不消费：记录最近一次按下的位置作为揭开圆心
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    state.lastTouch = down.position
                    MotionIntent.noteTouch()
                }
            },
            propagateMinConstraints = true,
        ) {
            val old = ThemeRevealController.snapshot
            val active = old != null && ThemeRevealController.owner?.get() === activity
            // 新主题从略大的尺寸落稳（以圆心为中心）；只在揭开期间挂这层变换，停稳时为 1
            Box(
                if (active) {
                    Modifier.graphicsLayer {
                        val o = ThemeRevealController.origin
                        val s = 1f + NEW_SETTLE_SCALE * (1f - ThemeRevealController.progress)
                        scaleX = s
                        scaleY = s
                        transformOrigin = TransformOrigin(
                            if (size.width > 0f) o.x / size.width else 0.5f,
                            if (size.height > 0f) o.y / size.height else 0.5f,
                        )
                    }
                } else {
                    Modifier
                },
                propagateMinConstraints = true,
            ) { content() }
            if (old != null && active) {
                Canvas(
                    Modifier
                        .matchParentSize()
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
                ) {
                    val p = ThemeRevealController.progress
                    val o = ThemeRevealController.origin
                    val reach = hypot(max(o.x, size.width - o.x), max(o.y, size.height - o.y))
                    // 前沿外沿半径：进度 1 时「完全擦除的内核」恰好盖满整个屏幕
                    val outer = (reach * REVEAL_REACH * p).coerceAtLeast(1f)
                    // 旧画面随揭开轻微放大后退（以圆心为中心）
                    val zoom = 1f + OLD_RECEDE_SCALE * p
                    drawImage(
                        old,
                        dstOffset = IntOffset((o.x * (1f - zoom)).roundToInt(), (o.y * (1f - zoom)).roundToInt()),
                        dstSize = IntSize((size.width * zoom).roundToInt(), (size.height * zoom).roundToInt()),
                        // 结束前整体淡出：羽化前沿变窄后远角会残留一点旧画面，收尾时一并淡掉
                        alpha = 1f - smoothStep((p - OLD_FADE_START) / (1f - OLD_FADE_START)),
                    )
                    // 羽化擦除：内核全擦，向外按平滑曲线渐隐回旧画面，没有清晰的边界线
                    // （离屏图层 + 混合擦除；实测用 clipPath 差集画旧画面每帧更贵，掉帧率 0.6% → 5.7%）
                    drawCircle(
                        brush = Brush.radialGradient(colorStops = ERASE_STOPS, center = o, radius = outer),
                        radius = outer,
                        center = o,
                        blendMode = BlendMode.DstOut,
                    )
                    // ===== 点缀：前沿柔光 + 飘散柔光点（都是渐变，没有线条；随进度渐隐） =====
                    val glow = (min(1f, p * 6f) * (1f - p)).coerceIn(0f, 1f)
                    if (glow > 0.01f && outer > 8f) {
                        // 主题色的宽柔光带，峰值落在前沿渐变的中段
                        drawCircle(
                            brush = Brush.radialGradient(
                                colorStops = arrayOf(
                                    0f to Color.Transparent,
                                    0.42f to Color.Transparent,
                                    0.58f to accent.copy(alpha = 0.10f * glow),
                                    0.72f to accent.copy(alpha = 0.30f * glow),
                                    0.86f to accent.copy(alpha = 0.10f * glow),
                                    1f to Color.Transparent,
                                ),
                                center = o,
                                radius = outer,
                            ),
                            radius = outer,
                            center = o,
                        )
                        // 一层更靠内的白色泛光，让前沿像「亮起」而不是「画了一圈」
                        drawCircle(
                            brush = Brush.radialGradient(
                                colorStops = arrayOf(
                                    0f to Color.Transparent,
                                    0.40f to Color.Transparent,
                                    0.55f to Color.White.copy(alpha = 0.16f * glow),
                                    0.70f to Color.Transparent,
                                    1f to Color.Transparent,
                                ),
                                center = o,
                                radius = outer,
                            ),
                            radius = outer,
                            center = o,
                        )
                        // 飘散柔光点：在光带里缓缓向外漂，带一圈更淡的光晕
                        for (i in 0 until SPARKLES) {
                            val jitter = fract(i * 0.618f)
                            val angle = i * GOLDEN_ANGLE + 0.3f + p * 0.7f
                            val rr = outer * (0.5f + 0.4f * jitter) + p * 18.dp.toPx() * (0.4f + jitter)
                            val x = o.x + cos(angle) * rr
                            val y = o.y + sin(angle) * rr
                            if (x < -24f || y < -24f || x > size.width + 24f || y > size.height + 24f) continue
                            val tint = (if (i % 2 == 0) Color.White else accent)
                            val a = glow * (0.3f + 0.5f * jitter)
                            val r = (1.2f + 2.0f * jitter).dp.toPx()
                            drawCircle(color = tint.copy(alpha = a * 0.22f), radius = r * 3.2f, center = Offset(x, y))
                            drawCircle(color = tint.copy(alpha = a), radius = r, center = Offset(x, y))
                        }
                    }
                }
            }
        }
    }
}

private fun fract(x: Float): Float = x - floor(x)

/** 平滑步进（3x² − 2x³）。 */
private fun smoothStep(x: Float): Float {
    val c = x.coerceIn(0f, 1f)
    return c * c * (3f - 2f * c)
}

/**
 * 擦除用的羽化曲线：从内核边缘（[ERASE_CORE]）到外沿，擦除力度按 smoothstep 由 1 渐变到 0，
 * 采样 12 段近似，肉眼看不出分段。
 */
private val ERASE_STOPS: Array<Pair<Float, Color>> = Array(13) { k ->
    val x = k / 12f
    (ERASE_CORE + (1f - ERASE_CORE) * x) to Color(0f, 0f, 0f, 1f - smoothStep(x))
}.let { arr ->
    arrayOf<Pair<Float, Color>>(0f to Color.Black) + arr
}

/**
 * 揭开的时长按「前沿推进速度恒定」定：圆心离最远的屏幕角越远，扩散的距离越长，时长就按比例拉长——
 * 原来所有入口都是固定 900ms，从屏幕顶部的语言行揭开比从下面的深浅色行揭开前沿要快约 1/4（系统主题自动切换从顶部中央，更快），
 * 三类切换（主题 / 语言 / 配色）速度体感不一。现在每个入口前沿每毫秒推进的 dp 数一样；夹在 [MIN_REVEAL_MILLIS, MAX_REVEAL_MILLIS]，
 * 近的不至于一闪而过、远的不至于拖沓。
 */
private fun revealMillis(center: Offset, width: Int, height: Int, density: Float): Int {
    val reachDp = hypot(max(center.x, width - center.x), max(center.y, height - center.y)) / density.coerceAtLeast(0.5f)
    return (reachDp * REVEAL_MILLIS_PER_DP).roundToInt().coerceIn(MIN_REVEAL_MILLIS, MAX_REVEAL_MILLIS)
}

/**
 * 揭开的进度曲线：起步柔和但立刻有反应（p(0.1)≈0.06）、最快处在 37% 的时间点（峰值约平均速度的 1.6 倍）、尾部缓缓收住。
 * 原曲线 (0.25, 0.2, 0.2, 1) 起步就冲、峰值 2.5 倍，加上羽化前沿按 2.6 倍半径外推，肉眼可见的整屏扫过其实只占前 30% 的时间
 * （约 0.25 秒），后面 0.6 秒是几乎看不见的拖尾——体感又急促、又拖沓。
 */
private val REVEAL_INTERPOLATOR = PathInterpolator(0.3f, 0.1f, 0.55f, 1f)

private const val ERASE_CORE = 0.4f
/**
 * 羽化前沿的外沿半径 = 最远屏幕角距离 × 本系数 × 进度。原来 2.6：前沿很宽，进度到 0.5 时远角已经被擦掉一半以上，
 * 一整屏的扫过只用了前三成时间；现在 2.0，肉眼可见的扫过（50% 擦除线到达最远角）落在约 71% 的进度、也就是约 57% 的时间，
 * 剩下的「擦干净 + 新主题落稳」由旧画面在最后 20% 进度里整体淡出（[OLD_FADE_START]）收尾。
 */
private const val REVEAL_REACH = 2.0f

/** 旧画面整体淡出的起点（进度）：此前远角残留的少量旧画面在 [OLD_FADE_START, 1] 内渐隐，保证结束时一定是完整的新界面。 */
private const val OLD_FADE_START = 0.8f
private const val OLD_RECEDE_SCALE = 0.06f
private const val NEW_SETTLE_SCALE = 0.035f
private const val REVEAL_MILLIS_PER_DP = 1.4f
private const val MIN_REVEAL_MILLIS = 720
private const val MAX_REVEAL_MILLIS = 1000
private const val MAX_STEP_NANOS = 24_000_000L
private const val SNAPSHOT_SCALE = 0.5f
private const val SPARKLES = 10
private const val GOLDEN_ANGLE = (2.0 * PI * 0.381966).toFloat()
