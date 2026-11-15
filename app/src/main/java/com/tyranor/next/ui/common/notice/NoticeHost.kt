package com.tyranor.next.ui.common.notice

import android.graphics.BlurMaskFilter
import android.graphics.Paint
import android.os.Build
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.tyranor.next.R
import com.tyranor.next.theme.AppThemeColors
import com.tyranor.next.theme.NoticeLightCapsuleBackground
import com.tyranor.next.theme.NoticeLightCapsuleContent
import com.tyranor.next.theme.NoticeLightCapsuleFailure
import com.tyranor.next.theme.NoticeDarkCapsuleBackground
import com.tyranor.next.theme.NoticeDarkCapsuleContent
import com.tyranor.next.theme.NoticeDarkCapsuleFailure
import com.tyranor.next.ui.common.isTabletScreen
import com.tyranor.next.ui.common.motion.BlurCrossfade
import com.tyranor.next.ui.common.motion.MotionMode
import com.tyranor.next.ui.common.motion.MotionSprings
import com.tyranor.next.ui.common.motion.MotionTokens
import com.tyranor.next.ui.common.motion.shakeOnTrigger
import com.tyranor.next.ui.common.rubberBandOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 抽屉等「画不到屏幕顶部」的窗口：只登记一个遮挡槽位（提示条此时退回系统 Toast），自己不画。
 */
@Composable
internal fun NoticeCover() {
    if (MotionMode.enhanced) rememberHostEntry(cover = true)
}

/**
 * 提示条槽位：放在页面根（`ProvideMotionEnvironment`）与每个 `AppAlertDialog` 窗口里。
 * 只有「前台界面里最上层」的槽位真正绘制；其余槽位什么也不画（见 [NoticeCenter]）。
 * 提示条只在自己的矩形内拦截触摸，其余区域完全穿透。
 */
@Composable
internal fun NoticeHost(modifier: Modifier = Modifier) {
    if (!MotionMode.enhanced) return
    val entry = rememberHostEntry(cover = false)
    val draw = NoticeCenter.render ?: return
    if (draw.host !== entry) return
    val notice = NoticeCenter.displayed ?: return
    NoticeSurface(modifier, draw, notice)
}

/** 监听 Activity 的离页；弹窗关闭只是同一页面内的窗口交接。 */
@Composable
private fun rememberHostEntry(cover: Boolean): NoticeHostEntry {
    val owner = LocalActivity.current as? LifecycleOwner ?: LocalLifecycleOwner.current
    val overlay = cover || LocalView.current.parent is DialogWindowProvider
    val entry = remember(owner, overlay, cover) { NoticeHostEntry(owner, overlay, cover) }
    DisposableEffect(entry) {
        val lifecycle = owner.lifecycle
        NoticeCenter.register(entry, lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> NoticeCenter.setResumed(entry, true)
                Lifecycle.Event.ON_PAUSE -> NoticeCenter.setResumed(entry, false)
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            if (!MotionMode.enhanced) NoticeCenter.clear()
            NoticeCenter.unregister(entry)
        }
    }
    return entry
}

/**
 * 灵动岛式提示条（设计参考 mochi-compose 的 MochiToast，Apache-2.0，见 THIRD_PARTY_NOTICES.md，代码为本项目重写）。
 *
 * - **出现**：从屏幕顶部中央一颗小「岛」（[ISLAND_WIDTH] × [ISLAND_HEIGHT]）柔和展开成内容大小的胶囊并落位，内容提前显影；
 *   尺寸由进场进度 [NoticeCenter.appear] 在岛与内容之间插值，外壳与内容共用同一帧尺寸。
 * - **连续点 / 替换**：容器不收起，胶囊直接变成新内容的大小，图标与文字模糊交叉替换（[BlurCrossfade] 自带尺寸过渡）。
 * - **按住**：放大 1.04、轻微跟手（朝手指偏 ≤ 4dp）、按住点发光；按住期间暂停计时，松手后至少再留约 0.8 秒。
 * - **手势**：往上甩 / 上拉过 24dp 收起；往下拉被橡皮筋拉住；轻点收起；其余区域的触摸完全穿透。
 * - **收起**：独立于展开，190ms 轻收（缩 3.5%）、上移 18dp、淡出。
 * - **配色**：按主题深浅取反色——浅色类主题（默认浅色、白雾玻璃）黑胶囊白字，深色类（深色外观、复古玻璃）白胶囊黑字；不做玻璃。
 */
@Composable
private fun NoticeSurface(modifier: Modifier, draw: NoticeDrawing, last: Notice) {
    val id = last.id
    val density = LocalDensity.current
    val rubberLimit = with(density) { RUBBER_LIMIT_DP.dp.toPx() }
    val drag = remember { NoticeDrag() }
    val touchCoordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val pressK = remember { Animatable(0f) }
    val pulse = remember(id) { Animatable(1f) }
    var pointer by remember { mutableStateOf(Offset.Zero) }
    var shakeTrigger by remember { mutableIntStateOf(0) }
    val bounceSeq = NoticeCenter.bounceSeq
    var seenBounce by remember(id) { mutableIntStateOf(bounceSeq) }
    val scope = rememberCoroutineScope()

    // 捕获这一次的绘制权；页面 → 弹窗 → 同一页面也要重启动画并拒绝交接前的帧。
    LaunchedEffect(draw) {
        if (!draw.exiting) {
            drag.settle(scope, rubberLimit)
            coroutineScope {
                launch { animateCenter({ NoticeCenter.depart }, { NoticeCenter.setDepart(draw, it) }, 0f, tween(160)) }
                launch { animateCenter({ NoticeCenter.appear }, { NoticeCenter.setAppear(draw, it) }, 1f, MotionSprings.response(0.4f, 0.9f, 0.001f)) }
            }
        } else {
            animateCenter({ NoticeCenter.depart }, { NoticeCenter.setDepart(draw, it) }, 1f, tween(190, easing = FastOutLinearInEasing))
            NoticeCenter.finishExit(draw)
        }
    }
    LaunchedEffect(id, bounceSeq) {
        if (bounceSeq != seenBounce) {
            seenBounce = bounceSeq
            pulse.animateTo(BOUNCE_SCALE, MotionTokens.press(0.001f))
            pulse.animateTo(1f, MotionTokens.release(0.001f))
        }
    }
    LaunchedEffect(draw) {
        if (!draw.exiting && NoticeCenter.pendingShake == id) {
            delay(SHAKE_DELAY_MILLIS)
            if (NoticeCenter.takeShake(draw)) shakeTrigger++
        }
    }

    val colors = noticeColors()
    val islandW = with(density) { ISLAND_WIDTH.toPx() }
    val islandH = with(density) { ISLAND_HEIGHT.toPx() }
    // 模糊遮罩半径与高斯 σ 的换算同 Skia：σ = 0.57735 × 半径 + 0.5
    val shadowPaints = remember(density) {
        SHADOW_LAYERS.map { layer ->
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                val sigma = layer.sigmaDp * density.density
                maskFilter = BlurMaskFilter(((sigma - 0.5f) / 0.57735f).coerceAtLeast(1f), BlurMaskFilter.Blur.NORMAL)
            }
        }
    }
    Box(
        modifier
            .fillMaxSize()
            .then(if (isTabletScreen()) Modifier.windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
            ) else Modifier.statusBarsPadding())
            .padding(top = 6.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Layout(
            content = {
                Row(
                    Modifier
                        .widthIn(max = 360.dp)
                        .padding(start = 12.dp, end = 20.dp, top = 10.dp, bottom = 10.dp)
                        .graphicsLayer { alpha = smoothstep(0.12f, 0.6f, NoticeCenter.appear) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 内容替换独立于整条提示的进出场：图标 + 文字模糊交叉替换，尺寸过渡由 AnimatedContent 自带
                    BlurCrossfade(targetState = last, contentKey = { it.id }, contentAlignment = Alignment.CenterStart) { notice ->
                        NoticeRow(notice, colors)
                    }
                }
            },
            modifier = Modifier
                .graphicsLayer {
                    val p = NoticeCenter.appear.coerceIn(0f, 1f)
                    val exit = NoticeCenter.depart.coerceIn(0f, 1f)
                    val k = pressK.value
                    val scale = (ENTER_SCALE + (1f - ENTER_SCALE) * p) * (1f - EXIT_SHRINK * exit) * (1f + PRESS_SCALE * k) * pulse.value
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin(0.5f, 0f)
                    // 按住时轻微朝手指偏（跟手）
                    val followPx = FOLLOW_DP.dp.toPx() * k
                    val fx = if (size.width > 0f) (pointer.x / size.width - 0.5f) * 2f else 0f
                    val fy = if (size.height > 0f) (pointer.y / size.height - 0.5f) * 2f else 0f
                    translationX = fx.coerceIn(-1f, 1f) * followPx
                    translationY = drag.offset + fy.coerceIn(-1f, 1f) * followPx -
                        ENTER_RISE_DP.dp.toPx() * (1f - p) - EXIT_RISE_DP.dp.toPx() * exit
                    alpha = smoothstep(0f, 0.2f, p) * (1f - exit)
                    // 透明度逐元素调制、不走离屏缓冲：离屏缓冲只有胶囊外框那么大，画在外框外面的阴影会被切成一个灰方框
                    // （淡出 / 淡入时可见）
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                }
                .shakeOnTrigger(shakeTrigger, amplitude = if (last.kind == AppNotice.Kind.Failure) 6.dp else 3.dp)
                // 柔和投影：整颗胶囊的高斯模糊，两层（参数是从 mochi 原版录屏逐像素量出来再拟合的：边缘约 9%，
                // 一层贴边的小阴影 + 一层往外一直衰减到约 25dp 的大光晕、略往下偏）。系统按光源算的阴影底边一道实影，又硬又重；
                // 叠几层不同大小的圆角矩形则是线性衰减，会有一圈圈的环（用户反馈过「分层太强、很怪」）
                .drawBehind {
                    if (Build.VERSION.SDK_INT >= BLUR_MASK_MIN_SDK) {
                        val r = size.height / 2f
                        drawIntoCanvas { canvas ->
                            for (i in SHADOW_LAYERS.indices) {
                                val layer = SHADOW_LAYERS[i]
                                val paint = shadowPaints[i]
                                paint.color = colors.shadow.copy(alpha = layer.alpha).toArgb()
                                val dy = layer.dyDp.dp.toPx()
                                canvas.nativeCanvas.drawRoundRect(0f, dy, size.width, size.height + dy, r, r, paint)
                            }
                        }
                    }
                }
                .clip(CircleShape)
                // 命中跟随绘制的胶囊；在窗口坐标下累计位移，胶囊跟手移动不会抵消手势。
                .onPlaced { touchCoordinates[0] = it }
                .pointerInput(draw) {
                    val dismissPx = DISMISS_DP.dp.toPx()
                    val slop = viewConfiguration.touchSlop
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
                        // 提示条自己消费点击，避免弹窗遮罩也收到；退场中不再接手新手势。
                        if (down.isConsumed) return@awaitEachGesture
                        val coords = touchCoordinates[0]?.takeIf { it.isAttached } ?: return@awaitEachGesture
                        var previousInWindow = coords.localToWindow(down.position)
                        down.consume()
                        val hold = NoticeCenter.beginHold(draw) ?: return@awaitEachGesture
                        drag.stop()
                        pointer = down.position
                        scope.launch { pressK.animateTo(1f, MotionTokens.press(0.001f)) }
                        val tracker = VelocityTracker()
                        tracker.addPosition(down.uptimeMillis, previousInWindow)
                        val pull = NoticePull(slop, rubberLimit, drag.offset)
                        var dismiss = false
                        var velocity = 0f
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val c = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (c.isConsumed || !NoticeCenter.ownsHold(hold)) break
                                if (!coords.isAttached) break
                                val inWindow = coords.localToWindow(c.position)
                                tracker.addPosition(c.uptimeMillis, inWindow)
                                pointer = c.position
                                drag.offset = pull.move(inWindow - previousInWindow)
                                previousInWindow = inWindow
                                c.consume()
                                if (!c.pressed) {
                                    velocity = tracker.calculateVelocity().y
                                    dismiss = pull.dismissOnUp(dismissPx, velocity)
                                    break
                                }
                            }
                        } finally {
                            // CANCEL / 指针消失只恢复计时与位置；只有本次按下对应的真实 UP 才可能关闭。
                            val stillHeld = NoticeCenter.ownsHold(hold)
                            NoticeCenter.endHold(hold, dismiss)
                            scope.launch { pressK.animateTo(0f, MotionTokens.release(0.001f)) }
                            if (stillHeld && !dismiss) drag.settle(scope, rubberLimit, pull.releaseVelocity(velocity))
                        }
                    }
                }
                .background(colors.container)
                // 按住点发光：画在底色之上、内容之下
                .drawBehind {
                    val k = pressK.value
                    if (k > 0.01f) {
                        drawRect(
                            brush = Brush.radialGradient(
                                colors = listOf(colors.glow.copy(alpha = colors.glow.alpha * k), Color.Transparent),
                                center = pointer,
                                radius = maxOf(size.width, size.height) * 0.9f,
                            ),
                        )
                    }
                }
                .semantics {
                    liveRegion = if (last.kind == AppNotice.Kind.Failure) LiveRegionMode.Assertive else LiveRegionMode.Polite
                },
        ) { measurables, constraints ->
            val content = measurables.first().measure(constraints.copy(minWidth = 0, minHeight = 0))
            // 外壳直接用这一帧的尺寸：岛 -> 内容按进场进度插值，替换时内容尺寸由内部的模糊替换逐帧给出，不再叠第二层弹簧
            val k = NoticeCenter.appear.coerceAtLeast(0f)
            val w = constraints.constrainWidth(lerpF(islandW, content.width.toFloat(), k).roundToInt())
            val h = constraints.constrainHeight(lerpF(islandH, content.height.toFloat(), k).roundToInt())
            layout(w, h) { content.place((w - content.width) / 2, (h - content.height) / 2) }
        }
    }
}

/** 胶囊里的一条内容：图标 + 文字（随胶囊的深浅取色）。 */
@Composable
private fun NoticeRow(notice: Notice, colors: NoticeColors) {
    val (iconRes, tint) = when (notice.kind) {
        AppNotice.Kind.Success -> R.drawable.ic_notice_success to colors.content
        AppNotice.Kind.Failure -> R.drawable.ic_notice_failure to colors.failure
        AppNotice.Kind.Limit -> R.drawable.ic_notice_limit to colors.content.copy(alpha = 0.78f)
        else -> R.drawable.ic_notice_info to colors.content
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = notice.text,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            color = colors.content,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 胶囊配色（见 [NoticeSurface]）：浅色类主题黑胶囊白字，深色类主题白胶囊黑字。 */
private class NoticeColors(
    val container: Color,
    val content: Color,
    val failure: Color,
    val glow: Color,
    val shadow: Color,
)

@Composable
private fun noticeColors(): NoticeColors {
    // 深色外观 / 复古玻璃（深色背景）→ 白胶囊；默认浅色 / 白雾玻璃 → 黑胶囊
    val whiteCapsule = AppThemeColors.isDark || AppThemeColors.isGlass
    return remember(whiteCapsule) {
        if (whiteCapsule) {
            NoticeColors(
                NoticeLightCapsuleBackground, NoticeLightCapsuleContent, NoticeLightCapsuleFailure,
                Color.Black.copy(alpha = 0.10f), Color.Black,
            )
        } else {
            NoticeColors(
                NoticeDarkCapsuleBackground, NoticeDarkCapsuleContent, NoticeDarkCapsuleFailure,
                Color.White.copy(alpha = 0.20f), Color.Black,
            )
        }
    }
}

/** 把 [NoticeCenter] 里的进度（[get] / [set]）从当前值动画到 [target]；协程被取消（新提示接管）时留在当前值。 */
private suspend fun animateCenter(
    get: () -> Float,
    set: (Float) -> Unit,
    target: Float,
    spec: AnimationSpec<Float>,
) {
    val start = Snapshot.withoutReadObservation(get)
    if (start == target) return
    val anim = Animatable(start)
    anim.animateTo(target, spec) { set(value) }
}

private fun lerpF(a: Float, b: Float, t: Float): Float = a + (b - a) * t

private fun smoothstep(a: Float, b: Float, x: Float): Float {
    val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/** 从当前显示位置接手：到达 edge 后，只对剩下的下拉空间施加橡皮筋。 */
private class NoticeRubberBand(start: Float, limit: Float) {
    private val edge = start.coerceIn(0f, limit)
    private val room = limit - edge

    fun position(y: Float): Float {
        if (y <= edge || room == 0f) return y.coerceAtMost(edge)
        return edge + rubberBandOffset(y - edge, room)
    }

    fun velocity(y: Float, fingerVelocity: Float): Float {
        if (y <= edge) return fingerVelocity
        // d(position)/dy = (room / (room + over))²。松手速度与最后一个 MOVE 用同一坐标系。
        val ratio = room / (room + (y - edge))
        return fingerVelocity * ratio * ratio
    }
}

/** 位移用每次事件的增量，避免内容换宽、布局原点改变被算成手指移动。 */
internal class NoticePull(private val slop: Float, private val limit: Float, private val start: Float) {
    private var travel = Offset.Zero
    private var dragged = false
    private val band = NoticeRubberBand(start, limit)

    fun move(delta: Offset): Float {
        travel += delta
        if (travel.getDistanceSquared() > slop * slop) dragged = true
        if (!dragged) return start
        return band.position(start + travel.y)
    }

    fun dismissOnUp(dismissPx: Float, velocityY: Float): Boolean =
        !dragged || travel.y < -dismissPx || velocityY < -FLICK_PX_PER_SECOND

    fun releaseVelocity(fingerVelocity: Float): Float = band.velocity(start + travel.y, fingerVelocity)
}

/** MOVE 直接更新绘制坐标；只有松手需要一个回弹协程，再次按下同步中断它。 */
internal class NoticeDrag {
    var offset by mutableFloatStateOf(0f)
    private var returning: Job? = null

    fun stop() { returning?.cancel() }

    fun settle(scope: CoroutineScope, limit: Float, velocity: Float? = null) {
        stop()
        if (offset == 0f && (velocity == null || velocity == 0f)) return
        returning = scope.launch {
            val spec = if (velocity == null) spring(dampingRatio = 1f, stiffness = 500f, visibilityThreshold = 0.5f)
                else MotionTokens.control()
            // 弹簧若继续向外冲，只压缩超出松手位置的部分；向内回位仍是原弹簧。
            // 不在上限截停动画，所以中途再抓到的是仍可移动的实际显示坐标。
            val band = NoticeRubberBand(offset, limit)
            Animatable(offset).animateTo(0f, spec, initialVelocity = velocity ?: 0f) { offset = band.position(value) }
        }
    }
}

private const val BOUNCE_SCALE = 1.05f
private const val SHAKE_DELAY_MILLIS = 120L

/** 灵动岛尺寸（收起时的「岛」）。 */
private val ISLAND_WIDTH = 120.dp
private val ISLAND_HEIGHT = 36.dp

private const val ENTER_SCALE = 0.98f
private const val ENTER_RISE_DP = 10
private const val EXIT_SHRINK = 0.035f
private const val EXIT_RISE_DP = 18
private const val PRESS_SCALE = 0.04f
private const val FOLLOW_DP = 4
private const val DISMISS_DP = 24
private const val RUBBER_LIMIT_DP = 14
private const val FLICK_PX_PER_SECOND = 600f

/** 投影的一层：高斯模糊的整颗胶囊，不透明度、σ（dp）、比胶囊往下偏的距离（dp）。 */
private class ShadowLayer(val alpha: Float, val sigmaDp: Float, val dyDp: Float)

/** 贴边的小阴影 + 往外衰减的大光晕（用原版录屏的暗度剖面拟合：残差 < 0.6%）。 */
private val SHADOW_LAYERS = listOf(ShadowLayer(0.06f, 3f, 0f), ShadowLayer(0.15f, 12f, 2f))

/** 模糊遮罩在硬件画布上 API 28 起才支持，更低版本不画投影（画出来是一条硬边）。 */
private const val BLUR_MASK_MIN_SDK = 28
