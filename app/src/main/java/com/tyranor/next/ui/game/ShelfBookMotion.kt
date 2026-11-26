package com.tyranor.next.ui.game

import android.animation.ValueAnimator
import android.os.Build
import android.util.Log
import android.view.Window
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.tyranor.next.ui.common.AppDialogHost
import com.tyranor.next.ui.common.AppDialogSpec
import com.tyranor.next.ui.common.DialogExitController
import com.tyranor.next.ui.common.motion.MotionHaptics
import com.tyranor.next.ui.common.motion.MotionMode
import com.tyranor.next.ui.common.motion.MotionSprings
import com.tyranor.next.ui.common.motion.MotionTokens
import com.tyranor.next.ui.common.motion.awaitHostBack
import com.tyranor.next.ui.common.motion.awaitHostLeft
import com.tyranor.next.ui.common.motion.dialogScrimAlpha
import com.tyranor.next.ui.common.motion.passThroughTouches
import com.tyranor.next.ui.common.motion.passTouchesThrough
import com.tyranor.next.ui.common.motion.restoreTouches
import com.tyranor.next.ui.common.motion.releaseDialogDim
import com.tyranor.next.ui.common.motion.syncDialogWindow
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 书架上那一盒此刻在屏幕上的样子（起飞 / 落回的锚点）：正面与书脊共用的那条棱的底端位置（同时处于 Y 轴与 X 轴旋转轴上，
 * 零透视畸变）、含正中抽出放大后的屏上物理高度、正面未缩放布局尺寸与书脊厚度。
 */
internal class ShelfBookOrigin(
    val left: Float,
    val bottom: Float,
    val height: Float,
    val face: IntSize,
    val spineDepth: Int,
)

/** 书盒飞离/落回书架时，单排书架背后悬浮影与玻璃搁板倒影的平滑连续在场度（0 = 已飞远，1 = 落稳）。 */
/** 藏书阁收起时书架上投影 / 倒影的回归：比翻开的书更早、更缓地回来（书落位前它们已经在了，不是落稳后才冒出来）。 */
internal fun shelfCatalogGroundAlpha(recede: Float): Float {
    val t = (recede / 0.78f).coerceIn(0f, 1f)
    return 1f - t * t * (3f - 2f * t)
}

internal fun shelfLentGroundAlpha(flightOrRecede: Float): Float {
    val t = (flightOrRecede / 0.48f).coerceIn(0f, 1f)
    return 1f - t * t * (3f - 2f * t)
}

/**
 * 书架与翻开的书之间的交接（「游戏盒」风格专用）。
 *
 * 书架把每一盒正面与书脊的布局坐标登记进来（摆放时更新，不触发重组）；点开正中那一盒时记下序号、总数与封面主色，
 * 翻开的书据此从那一盒的位置起飞。书在外面期间书架把那一盒藏起来（[lentUri]），书合上、落回原位时再通过 [lentAlpha] 平滑放出来。
 */
@Stable
internal class ShelfBookStage {
    private val fronts = HashMap<String, LayoutCoordinates>()
    private val spines = HashMap<String, LayoutCoordinates>()
    private var pendingUri: String? = null

    /** 被抽出去的那一本：书架上把它藏起来（只在图层里读）。 */
    var lentUri by mutableStateOf<String?>(null)
        internal set

    /**
     * 正在合上（还没落回）的那本书：合上途中它的窗口不接触摸，手指落在书架上——点回这一本就让它重新翻开
     * （[reopenClosing]），拨动书架 / 点别的书就让它就地收尾（[interruptClosing]），不用等动画放完。
     */
    internal var closingMotion: ShelfBookMotion? = null

    /** 点到了正在合上的这一本：重新翻开，返回是否接手了这一次点击。 */
    fun reopenClosing(uri: String): Boolean {
        val motion = closingMotion ?: return false
        if (motion.uri != uri) return false
        return motion.reopen()
    }

    /** 书架被拨动 / 点了别的书：正在合上的书不再飞回（落点已经不对了），就地淡出。 */
    fun interruptClosing() {
        closingMotion?.interruptClose()
    }

    /** 被抽出的那一盒在交接期间的不透明度（0 = 隐藏，落稳后平滑升至 1 再放行书架原盒）。 */
    var lentAlpha by mutableFloatStateOf(0f)
        internal set

    /** 被抽出那一盒背后的悬浮投影与玻璃倒影的在场度（0 = 已飞起淡出，1 = 落回就位）。 */
    var lentShadowAlpha by mutableFloatStateOf(1f)
        internal set
    var index = 0
        private set
    var count = 0
        private set
    var tint: Color = Color.Unspecified
        private set

    /** 书架正中那一盒正面 / 书脊的背光压暗浓度（起飞时照着画）。 */
    var faceShade = 0f
        private set
    var spineShade = 0f
        private set

    fun placeFront(uri: String, coordinates: LayoutCoordinates) {
        fronts[uri] = coordinates
    }

    fun placeSpine(uri: String, coordinates: LayoutCoordinates) {
        spines[uri] = coordinates
    }

    fun forget(uri: String) {
        fronts.remove(uri)
        spines.remove(uri)
    }

    /** 轻点正中那一盒：记下这一本，接下来打开的书从它的位置起飞。 */
    fun capture(uri: String, index: Int, count: Int, tint: Color, faceShade: Float, spineShade: Float) {
        pendingUri = uri
        this.index = index
        this.count = count
        this.tint = tint
        this.faceShade = faceShade
        this.spineShade = spineShade
    }

    /** 这一本是不是刚从书架上点开的（旋转重建后重新出现的书直接摊开，不再起飞）。 */
    fun takePending(uri: String): Boolean = (pendingUri == uri).also { pendingUri = null }

    /** 这一本此刻在书架上的样子；不在书架上（被删、没组合）时为 null。 */
    fun origin(uri: String): ShelfBookOrigin? {
        val front = fronts[uri]?.takeIf { it.isAttached } ?: return null
        if (front.size.width <= 0 || front.size.height <= 0) return null
        // 左底角正好落在 rotationY 与 rotationX 两条转轴的交点上，不受顶部外倾与透视偏移影响
        val bottom = front.localToScreen(Offset(0f, front.size.height.toFloat()))
        val height = front.size.height * shelfPoseScale(0f)
        if (height <= 0f) return null
        val spine = spines[uri]?.takeIf { it.isAttached }?.size?.width ?: 0
        return ShelfBookOrigin(bottom.x, bottom.y, height, front.size, spine)
    }
}

/**
 * 翻开的书的运动：
 *
 * - [flight]：0 = 还立在书架上（与正中那一盒重合：同样的大小、同样侧转露出书脊、同样的丝带与 ▶），
 *   1 = 捧在眼前（铺满大半屏）。起飞途中书一边放大一边转正，中途再微微侧过来一点；
 * - [cover]：0 = 封面合着，1 = 绕书脊翻到左边摊平。封面晚一拍开始翻，落平时略微冲过一点再回来；
 * - 合上：封面先盖回去，盖到一半书开始往回飞，落回原位时轻轻 Q 弹一下，再与书架上的那一盒交叉淡入淡出地交还；
 * - 往右拖书页：封面跟着手指合回来，松手按位置与速度决定合上还是弹开。
 *
 * 「普通」档没有飞出、翻开与跟手：书直接摊开，窗口保留平台自带的进出场。
 */
@Stable
internal class ShelfBookMotion(
    private val scope: CoroutineScope,
    val enhanced: Boolean,
    private val stage: ShelfBookStage,
    internal val uri: String,
    fromShelf: Boolean,
    private val animatorsEnabled: () -> Boolean = ValueAnimator::areAnimatorsEnabled,
) {
    private fun canAnimate() = enhanced && animatorsEnabled()
    private val flies = fromShelf && canAnimate()
    val flight = Animatable(if (flies) 0f else 1f)
    val cover = Animatable(if (flies) 0f else 1f)

    /** 整本书的不透明度：只在落回书架、与那一盒交接时淡掉。 */
    val presence = Animatable(1f)

    /** 起飞 / 落回的那一盒；null = 那一盒不在书架上，原地浮现 / 收起。 */
    var origin by mutableStateOf(if (flies) stage.origin(uri) else null)
        private set

    /** 书摊开时在屏幕上的位置（布局摆好时更新）；摆好之前书不画（起点还算不出来，先画就是摊开大小的一帧）。 */
    internal var bookBounds by mutableStateOf<Rect?>(null)

    private var window: Window? = null
    internal val hostWindow: Window? get() = window
    internal var dismissAction: (() -> Unit)? = null
    private var delivered = false
    private var openJob: Job? = null

    var closing by mutableStateOf(false)
        private set
    private var navigating = false
    private var leavingForGame = false

    /** 正在合上 / 跳转中：拒绝新的业务点击，迟到的启动结果也不再导航。 */
    val leaving: Boolean get() = closing || navigating

    /** 遮罩进度：跟书离开书架的程度走。 */
    fun scrim(): Float = if (enhanced) flight.value.coerceIn(0f, 1f) else 1f

    /** 本窗口自绘遮罩此刻的暗度（与上层弹窗协调后的值）。 */
    fun scrimAlpha(): Float = window.dialogScrimAlpha()


    /** 书此刻的纵向缩放（相对摊开时）：起点是书架上那一盒的高度。 */
    fun scale(): Float {
        val from = origin ?: return 1f
        val book = bookBounds ?: return 1f
        if (book.height <= 0f) return 1f
        val start = from.height / book.height
        return start + (1f - start) * flight.value
    }

    /**
     * 书此刻的横向缩放：起点是书架上那一盒的宽度。摊开的书页比封面高挑（见 `shelfBookFrame`），起飞那一刻横纵缩放不同、
     * 整本书正好是书架上那一盒的大小与比例，飞到眼前时舒展成书页的比例；封面图与封面上的装饰按此刻的横纵比补偿，不变形。
     */
    fun scaleX(): Float {
        val from = origin ?: return 1f
        val book = bookBounds ?: return 1f
        if (book.width <= 0f || from.face.height <= 0) return 1f
        val start = from.height * from.face.width / from.face.height / book.width
        return start + (1f - start) * flight.value
    }

    /**
     * 书此刻侧转的角度（度，正 = 右缘朝里、露出左侧书脊）：起点与书架正中那一盒相同，摊开时正对，途中微微侧一下。
     * 书架上那一点侧转在飞行的前 [BOOK_TURN_SPAN] 里平滑转正（落回时同样在后段平滑转回、书脊慢慢露出来），
     * 不跟着飞行进度线性走——否则落回的最后几帧书脊一下子冒出来。
     */
    fun yaw(): Float {
        val f = flight.value
        val turn = (f / BOOK_TURN_SPAN).coerceIn(0f, 1f)
        val rest = if (origin != null) HERO_YAW_DEGREES * (1f - turn * turn * (3f - 2f * turn)) else 0f
        return rest + BOOK_FLIGHT_YAW * sin(PI.toFloat() * f.coerceIn(0f, 1f))
    }

    /** 书此刻的外倾角（度）：落回书架时与正中盒保持同一 [HERO_TILT_DEGREES] 顶部微倾，飞起后平稳扶正。 */
    fun tilt(): Float = if (enhanced) shelfBookTiltDegrees(flight.value, origin != null) else 0f

    /** 起飞前封面上那枚 ▶ 胶囊还看得见多少（与书架上一致，离开书架很快收起）。 */
    fun badge(): Float = (1f - flight.value / BOOK_BADGE_FADE).coerceIn(0f, 1f)

    @Composable
    fun BindWindow(scrimAlpha: Float) {
        // 增强档书的遮罩整个由自己画（drawnOnly）：系统的窗口 dim 是一块系统层的「暗层」，窗口退场期间它还在，
        // 系统会按「被别的来源的半透明层盖住」把落在下面书架上的触摸整次丢掉——合上途中点不了书架、拨不动书架就是这个原因
        val current = syncDialogWindow(preserveWindowAnimations = !enhanced, scrimAlpha = scrimAlpha, drawnOnly = enhanced) { scrim() }
        window = current
        DisposableEffect(current) {
            onDispose {
                current.passThroughTouches()
                current.releaseDialogDim()
            }
        }
        LaunchedEffect(Unit) {
            val job = kotlin.coroutines.coroutineContext[Job]
            openJob = job
            try {
                open()
            } finally {
                if (openJob === job) openJob = null
            }
        }
    }

    private suspend fun open() {
        if (!flies) return
        // 书摆好、第一帧真正画上屏以后再把书架上那一盒藏起来：中间不空一帧，也不先闪一帧摊开大小的书
        snapshotFlow { bookBounds }.first { it != null }
        withFrameNanos { }
        withFrameNanos { }
        if (closing) return
        if (!canAnimate()) {
            flight.snapTo(1f)
            cover.snapTo(1f)
            origin = null
            return
        }
        stage.lentAlpha = 0f
        stage.lentShadowAlpha = 1f
        stage.lentUri = uri
        coroutineScope {
            launch {
                flight.animateTo(1f, BOOK_FLIGHT_OPEN) {
                    stage.lentShadowAlpha = shelfLentGroundAlpha(value)
                }
            }
            launch {
                delay(BOOK_COVER_LAG_MILLIS)
                if (!closing) cover.animateTo(1f, BOOK_COVER_OPEN)
            }
        }
    }

    /** 宿主的点击门：合上 / 跳转途中拒绝（给一次拒绝触觉）。 */
    fun interact(): Boolean {
        if (leaving) {
            MotionHaptics.perform(MotionHaptics.Kind.Reject)
            return false
        }
        return true
    }

    /** 合上途中的收尾方式：正常飞回 / 被书架上的操作打断就地淡出 / 被点回来重新翻开（后两种由对应的函数自己收尾，不走 [deliver]）。 */
    private enum class CloseMode { Fly, Interrupted, Reopened }

    private var closeMode = CloseMode.Fly
    private var closeGeneration = 0
    private var closeJob: Job? = null

    /** 合上并落回书架；[velocity] 是封面合上方向的初速度（每秒多少个「全开」）。 */
    fun close(velocity: Float = 0f) {
        if (closing) return
        closing = true
        closeMode = CloseMode.Fly
        val generation = ++closeGeneration
        openJob?.cancel()
        openJob = null
        if (!canAnimate()) {
            deliver()
            return
        }
        // 合上途中书的窗口不再接触摸：手指直接落到下面的书架上，点回这一本能马上重新翻开、拨动书架能翻到别的书，
        // 不用等书飞回去（见 [reopen] / [interruptClose]）
        window.passTouchesThrough()
        stage.closingMotion = this
        // 旋转重建后直接摊开的书也要落回去：这时才去找那一盒，并在飞回途中把它藏起来
        origin = stage.origin(uri)
        if (origin != null) {
            stage.lentAlpha = 0f
            stage.lentShadowAlpha = shelfLentGroundAlpha(flight.value)
            stage.lentUri = uri
        }
        closeJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val shut = launch { cover.animateTo(0f, BOOK_COVER_CLOSE, initialVelocity = velocity) }
                snapshotFlow { cover.value }.first { it <= BOOK_RETURN_AT }
                flight.animateTo(0f, BOOK_FLIGHT_CLOSE) {
                    stage.lentShadowAlpha = shelfLentGroundAlpha(value)
                }
                shut.join()
                // 落稳且与书架盒身 100% 重合：保持上层遮挡，先柔和淡入书架原盒（阴影与倒影已在落回末段预先渐显就位），再静默撤走上层
                if (origin != null && stage.lentUri == uri) {
                    stage.lentShadowAlpha = 1f
                    Animatable(stage.lentAlpha).animateTo(1f, BOOK_LENT_REVEAL) { stage.lentAlpha = value }
                    release()
                    presence.animateTo(0f, BOOK_HANDOFF_EXIT)
                } else {
                    release()
                    presence.animateTo(0f, BOOK_HANDOFF)
                }
            } finally {
                withContext(NonCancellable) {
                    if (generation == closeGeneration && closeMode == CloseMode.Fly) deliver()
                }
            }
        }
    }

    /**
     * 合上途中书架上点回了这一本：书从此刻的位置与姿态接着翻开（位置、封面角度、速度都连续），不用等落回去。
     * 已经交还 / 在去游戏的路上时不接手。
     */
    fun reopen(): Boolean {
        if (!enhanced || !closing || leavingForGame || delivered || closeMode == CloseMode.Interrupted) return false
        closeMode = CloseMode.Reopened
        closeJob?.cancel()
        closeJob = null
        closing = false
        window.restoreTouches()
        stage.closingMotion = null
        if (!canAnimate()) {
            release()
            origin = null
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                presence.snapTo(1f)
                flight.snapTo(1f)
                cover.snapTo(1f)
            }
            return true
        }
        // 书重新抬起：书架上那一盒再藏起来（落回末段已经放出来的部分不要，书会盖在它上面飞起来）
        origin = stage.origin(uri) ?: origin
        if (origin != null) {
            stage.lentAlpha = 0f
            stage.lentShadowAlpha = shelfLentGroundAlpha(flight.value)
            stage.lentUri = uri
        }
        openJob?.cancel()
        openJob = scope.launch {
            launch { presence.snapTo(1f) }
            launch {
                flight.animateTo(1f, BOOK_FLIGHT_OPEN) { stage.lentShadowAlpha = shelfLentGroundAlpha(value) }
            }
            launch { cover.animateTo(1f, BOOK_COVER_OPEN) }
        }
        MotionHaptics.perform(MotionHaptics.Kind.Tick)
        return true
    }

    /**
     * 合上途中书架被拨动 / 点了别的书：书不再飞回（书架已经动了，落点不对），就地淡出，书架上的那一盒放出来，随后交还。
     * 比等整段飞回动画快得多，书架一直是活的。
     */
    fun interruptClose() {
        if (!enhanced || !closing || leavingForGame || delivered || closeMode != CloseMode.Fly) return
        closeMode = CloseMode.Interrupted
        openJob?.cancel()
        openJob = null
        closeJob?.cancel()
        closeJob = null
        if (!canAnimate()) {
            deliver()
            return
        }
        scope.launch {
            try {
                launch {
                    stage.lentShadowAlpha = 1f
                    if (stage.lentUri == uri) {
                        Animatable(stage.lentAlpha).animateTo(1f, BOOK_INTERRUPT_REVEAL) { stage.lentAlpha = value }
                    }
                }
                presence.animateTo(0f, BOOK_INTERRUPT_EXIT)
            } finally {
                withContext(NonCancellable) { deliver() }
            }
        }
    }

    /** 应用锁生效等强制收起：不播动画，立即交还。 */
    fun dismissImmediately() {
        closing = true
        openJob?.cancel()
        openJob = null
        deliver()
    }

    /** 第几次进二级页面：旧的一次回来得晚时，不去动新的一次的点击门与扉页。 */
    private var navigationGeneration = 0

    /**
     * 进二级页面：书留在原处，回来时还摊开着；跳转途中拒绝重复点击（上层页面一开始退出，或本页重新回到前台，
     * 先到者为准，就放开）。[onBack] 在本页重新回到前台时调用（书露出来、看得见翻页；没跳走也会调用）。
     * 等待跳转与回来与操作抽屉共用 [awaitHostLeft] / [awaitHostBack]。
     */
    fun stayDuringNavigation(owner: LifecycleOwner, onBack: () -> Unit = {}) {
        if (!canAnimate() || closing || navigating) {
            onBack()
            return
        }
        navigating = true
        val generation = ++navigationGeneration
        scope.launch {
            try {
                if (awaitHostLeft(owner)) {
                    awaitHostBack(owner)
                    // 上层页面一开始退出就放开点击（返回途中可以再点），但翻回目录要等这本书真的露出来再翻：
                    // 宿主回到前台后再等两帧。宿主停在后台时组合的帧时钟是暂停的，回来后的头一帧还带着暂停前的时间，
                    // 这时起步的弹簧会把整段停顿当成已经播完、一帧翻到底（实测 24ms 翻完），返回时就看不到翻页
                    if (navigationGeneration == generation) navigating = false
                    withTimeoutOrNull(BOOK_RESUME_TIMEOUT_MILLIS) {
                        owner.lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.RESUMED) }
                    }
                    repeat(BOOK_RESUME_SETTLE_FRAMES) { withFrameNanos { } }
                }
            } finally {
                // 这期间又进了一次二级页面（用户先收掉扉页再点了别的章）：门与扉页归新的那一次管
                if (navigationGeneration == generation) {
                    navigating = false
                    onBack()
                }
            }
        }
    }

    /** 游戏启动成功：等游戏界面盖上来、转场放完再悄悄收起（不再飞回）；没跳走则照常合上。 */
    fun navigateAway(owner: LifecycleOwner) {
        if (!canAnimate()) {
            closing = true
            deliver()
            return
        }
        if (closing) return
        closing = true
        leavingForGame = true
        scope.launch {
            if (!awaitHostLeft(owner)) {
                if (delivered) return@launch
                closing = false
                leavingForGame = false
                close()
                return@launch
            }
            try {
                delay(MotionSprings.PushDurationMillis + BOOK_LEAVE_GRACE_MILLIS)
            } finally {
                withContext(NonCancellable) { deliver() }
            }
        }
    }

    /** 手指往右拖了 [delta] 像素：封面跟着合回来，拖过 [span] 像素合到底。 */
    fun dragBy(delta: Float, span: Float) {
        if (!canAnimate() || closing || span <= 0f) return
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            cover.snapTo((cover.value - delta / span).coerceIn(0f, 1f))
        }
    }

    /** 松手：封面合过一大半或往右甩得够快就合上，否则弹回摊平。 */
    fun dragEnd(velocity: Float, span: Float) {
        if (closing || span <= 0f) return
        if (!canAnimate()) {
            dragCancel()
            return
        }
        val coverVelocity = -velocity / span
        if (cover.value < BOOK_DRAG_CLOSE_AT || coverVelocity < -BOOK_DRAG_CLOSE_SPEED) {
            close(coverVelocity)
        } else {
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                cover.animateTo(1f, BOOK_COVER_OPEN, initialVelocity = coverVelocity)
            }
        }
    }

    fun dragCancel() {
        if (!enhanced || closing) return
        scope.launch {
            if (canAnimate()) cover.animateTo(1f, BOOK_COVER_OPEN) else cover.snapTo(1f)
        }
    }

    internal fun deliverIfLeaving(event: Lifecycle.Event) {
        // 旋转 / 重建前先走 onSaveInstanceState：正在合上的书在暂停时提前交还，免得重建后又摊开；
        // 等游戏盖上来再收起的那一种在 onStop 时交还
        if ((event == Lifecycle.Event.ON_PAUSE && closing && !leavingForGame) ||
            (event == Lifecycle.Event.ON_STOP && closing)
        ) deliver()
    }

    internal fun release() {
        openJob?.cancel()
        openJob = null
        if (stage.closingMotion === this) stage.closingMotion = null
        if (stage.lentUri == uri) {
            stage.lentUri = null
            stage.lentAlpha = 1f
            stage.lentShadowAlpha = 1f
        }
    }

    private fun deliver() {
        if (delivered) return
        delivered = true
        if (stage.closingMotion === this) stage.closingMotion = null
        release()
        window.passThroughTouches()
        window.releaseDialogDim()
        dismissAction?.invoke()
    }
}

@Composable
internal fun rememberShelfBookMotion(stage: ShelfBookStage, uri: String, onDismiss: () -> Unit): ShelfBookMotion {
    val enhanced = MotionMode.enhanced
    val scope = rememberCoroutineScope()
    val motion = remember(stage, uri, enhanced) {
        ShelfBookMotion(scope, enhanced, stage, uri, stage.takePending(uri))
    }
    motion.dismissAction = onDismiss
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, motion) {
        val observer = LifecycleEventObserver { _, event ->
            try {
                motion.deliverIfLeaving(event)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.w("ShelfBookMotion", "Book dismiss callback failed on $event", failure)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            // 无论怎么离开，书架上的那一盒都要放回去
            motion.release()
        }
    }
    return motion
}

/** 书里翻到的一页：书里的选项弹出的二级弹窗（经 [AppDialogHost] 交过来的 [AppDialogSpec]）。 */
@Stable
internal class ShelfBookLeaf(spec: AppDialogSpec?) {
    var spec by mutableStateOf(spec)

    /** 这一页被更上一层的页翻过去的程度：0 = 平躺在右边，1 = 翻到书脊左边。 */
    val turn = Animatable(0f)

    /** 已经接受了关闭（正在翻回）：不再重复处理，这一页也不再接触摸（见 `ShelfBookSheet` 的 blocked）。 */
    internal var leaving by mutableStateOf(false)

    /** 这一页最近一次画出来的样子：调用方直接撤掉弹窗时，翻回来的那一页底下还要画着它，直到被盖住。 */
    internal var snapshot: GraphicsLayer? = null
    internal var graphics: GraphicsContext? = null

    /** 快照已释放：这一页离开组合前的最后几帧不再新建图层（新建的没人释放）。 */
    internal var released = false
        private set

    /** 撤掉后交给了「换页」：被翻回盖住的那条路不再释放它（由往前翻的那一页负责）。 */
    internal var handedOff = false

    internal fun release() {
        released = true
        snapshot?.let { layer -> graphics?.releaseGraphicsLayer(layer) }
        snapshot = null
    }
}

/** 换页时往前翻过去的那一页（已被调用方撤掉，只剩最后的样子）与它翻开后露出的那一页。 */
internal class ShelfBookDeparture(val leaf: ShelfBookLeaf, val above: ShelfBookLeaf)

/**
 * 书里的「翻页」：目录是第 0 页，选项弹出的二级弹窗依次叠成后面的页。打开一页时下面那一页绕书脊翻过去（翻到左边），
 * 关闭（返回、点书外空白、按钮的「先退场再执行」）时把它翻回来盖住，翻完再交付关闭；往右拖时翻回的那一页跟着手指走。
 * 调用方直接撤掉弹窗时，用这一页最后画出的样子垫在底下，直到被翻回来的那一页盖住。
 *
 * 作为 [AppDialogHost] 由 `ShelfOpenBook` 提供：书打开期间由选项弹出的 [com.tyranor.next.ui.common.AppAlertDialog]
 * 全部翻到书页上，不另开窗口。「普通」档不播翻页，直接换页。
 */
@Stable
internal class ShelfBookPages(
    private val scope: CoroutineScope,
    val enhanced: Boolean,
    private val animatorsEnabled: () -> Boolean = ValueAnimator::areAnimatorsEnabled,
) : AppDialogHost {
    /** 目录页被翻过去的程度。 */
    val toc = Animatable(0f)
    val leaves = mutableStateListOf<ShelfBookLeaf>()

    /** 宿主是否在前台（由 [rememberShelfBookPages] 接上）：不在前台时不播翻页，直接交付。 */
    internal var resumed: () -> Boolean = { true }

    private fun canAnimate() = enhanced && animatorsEnabled() && resumed()

    /** 被调用方直接撤掉、正在被翻回来的那一页盖住的页（只剩最后的样子）。 */
    var ghost by mutableStateOf<ShelfBookLeaf?>(null)
        private set

    /** 把 [ghost] 翻回盖住的那段协程；[ghostPending] = 还在等一帧，看是不是紧接着翻开了新的一页。 */
    private var ghostJob: Job? = null
    private var ghostPending = false

    /** 这一帧刚翻开的那一页（下一帧清掉）：紧接着撤掉它上面那一页也是「换页」。 */
    private var justPushed: ShelfBookLeaf? = null

    /**
     * 「换页」：调用方撤掉这一页的同时翻开了新的一页（如选好封面来源 → 搜索封面），撤掉的那一页不被翻回来盖住，
     * 而是用它最后的样子往前翻过去、露出底下的新页（[ShelfBookDeparture.above]），翻完撤走。
     */
    var departure by mutableStateOf<ShelfBookDeparture?>(null)
        private set

    /** 正在翻回、翻完才交付的关闭（[closeTop]）；宿主暂停时立即交付（见 [deliverPending]）。 */
    private var pendingClose: PendingClose? = null

    private class PageDrag(val leaf: ShelfBookLeaf, val below: Animatable<Float, AnimationVector1D>)

    private var drag: PageDrag? = null

    private class PendingClose(
        val leaf: ShelfBookLeaf,
        val below: Animatable<Float, AnimationVector1D>,
        val action: () -> Unit,
    ) {
        var delivered = false
    }

    /** 有没有翻到目录以外的页。 */
    val turned: Boolean get() = leaves.isNotEmpty()

    fun dismissOrClose(closeBook: () -> Unit) {
        if (turned) dismissTop() else closeBook()
    }

    /** 第 [level] 页（0 = 目录）被翻过去的程度。 */
    fun turnOf(level: Int): Animatable<Float, AnimationVector1D> = if (level <= 0) toc else leaves[level - 1].turn

    /** 按 [com.tyranor.next.ui.common.AppAlertDialog] 的约定给翻开的这一页提供的退场控制器：先翻回再执行。 */
    val exit = DialogExitController { action -> closeTop(action = action) }

    @Composable
    override fun Present(dialog: AppDialogSpec) {
        val leaf = remember { ShelfBookLeaf(dialog) }
        SideEffect { leaf.spec = dialog }
        DisposableEffect(leaf) {
            push(leaf)
            onDispose { remove(leaf) }
        }
    }

    internal fun push(leaf: ShelfBookLeaf) {
        dragCancel()
        val turn = turnOf(leaves.size)
        val belowLeaf = leaves.lastOrNull()
        val replaced = ghost?.takeIf { ghostPending }
        if (replaced != null) {
            // 同一帧里先撤掉了上一页：不再把它翻回来盖住，改成它往前翻过去
            replaced.handedOff = true
            ghostJob?.cancel()
            ghostJob = null
            ghostPending = false
            ghost = null
            leaves.add(leaf)
            depart(replaced, above = leaf)
            if (turn.value != 1f) openPage(turn, warmUp = true, guardLeaf = leaf, guardBelowLeaf = belowLeaf)
            return
        }
        leaves.add(leaf)
        if (!canAnimate()) {
            openPage(turn)
            return
        }
        justPushed = leaf
        scope.launch {
            withFrameNanos { }
            if (justPushed === leaf) justPushed = null
        }
        openPage(turn, warmUp = true, guardLeaf = leaf, guardBelowLeaf = belowLeaf)
    }

    private fun openPage(
        turn: Animatable<Float, AnimationVector1D>,
        velocity: Float = 0f,
        warmUp: Boolean = false,
        guardLeaf: ShelfBookLeaf? = null,
        guardBelowLeaf: ShelfBookLeaf? = null,
    ) {
        val animated = canAnimate()
        // 关动画时立刻 UNDISPATCHED snapTo；拖手松开（无需预热）立即可用 UNDISPATCHED 衔接初速度；新推入页先让出 2 帧等待复杂内容组合测量完毕再起飞，避免首帧抽帧
        val start = if (!animated || !warmUp) CoroutineStart.UNDISPATCHED else CoroutineStart.DEFAULT
        scope.launch(start = start) {
            if (warmUp && canAnimate()) {
                repeat(BOOK_PAGE_TURN_WARMUP_FRAMES) { withFrameNanos { } }
            }
            if (guardLeaf != null && (guardLeaf !in leaves || guardLeaf.leaving)) return@launch
            if (guardBelowLeaf != null && guardBelowLeaf !in leaves) return@launch
            if (canAnimate()) turn.animateTo(1f, BOOK_PAGE_TURN, initialVelocity = velocity) else turn.snapTo(1f)
        }
    }

    /** 撤掉的 [leaf] 用最后的样子从当前位置往前翻过去，翻完撤走（换页，见 [departure]）。 */
    private fun depart(leaf: ShelfBookLeaf, above: ShelfBookLeaf) {
        if (!canAnimate()) {
            leaf.release()
            return
        }
        departure?.leaf?.release()
        val departing = ShelfBookDeparture(leaf, above)
        departure = departing
        scope.launch {
            try {
                repeat(BOOK_PAGE_TURN_WARMUP_FRAMES) { withFrameNanos { } }
                if (departure !== departing || above !in leaves || above.leaving) return@launch
                leaf.turn.animateTo(1f, BOOK_PAGE_TURN)
            } finally {
                if (departure === departing) departure = null
                leaf.release()
            }
        }
    }

    /**
     * 这一页此刻被上面的页翻开的程度：正有一页往前翻过去、露出它时跟着那一页走。按对象找位置（不用组合时记下的层号）：
     * 页被撤掉后、书页重组前的那一帧还会画，层号可能已经越界。已经不在书里的页按「全开」算。
     */
    fun coverOf(leaf: ShelfBookLeaf): Float {
        departure?.takeIf { it.above === leaf }?.let { return it.leaf.turn.value }
        val index = leaves.indexOf(leaf)
        return if (index < 0) 1f else turnOf(index).value
    }

    internal fun remove(leaf: ShelfBookLeaf) {
        val index = leaves.indexOf(leaf)
        if (index < 0) return
        val dragged = drag
        if (dragged != null && (dragged.leaf === leaf || dragged.below === leaf.turn)) drag = null
        val top = index == leaves.lastIndex
        // 换页的另一种先后：新的一页已经翻开、这一页正被翻过去时它才被撤掉，就让它照常翻完再撤
        val above = leaves.getOrNull(index + 1)
        val turningAway = canAnimate() && !leaf.leaving && index == leaves.lastIndex - 1 && above != null && above === justPushed
        leaves.removeAt(index)
        if (turningAway && above != null) {
            depart(leaf, above)
            return
        }
        val below = turnOf(index)
        if (!top || !canAnimate() || leaf.leaving || below.value <= 0.001f) {
            if (top) scope.launch { below.snapTo(0f) }
            leaf.release()
            return
        }
        // 调用方直接撤掉了这一页：用它最后的样子垫在底下，把下面那一页翻回来盖住。先等一帧：
        // 同一帧里紧接着翻开了新的一页就是「换页」（见 push），不翻回来。之前还有一页在等 / 在翻回，先让它收尾（释放它的快照）
        ghostJob?.cancel()
        ghost = leaf
        ghostPending = true
        ghostJob = scope.launch {
            try {
                withFrameNanos { }
                if (ghost === leaf) ghostPending = false
                below.animateTo(0f, BOOK_PAGE_TURN_BACK)
            } finally {
                if (ghost === leaf) {
                    ghost = null
                    ghostPending = false
                }
                if (!leaf.handedOff) leaf.release()
            }
        }
    }

    /**
     * 把最上面那一页翻回去，翻完再执行 [action]；翻回途中这一页不接触摸（不会在关闭途中再点出别的动作）。
     * 动作执行后这一页还在（调用方没有关掉它，或晚几帧才关）就稍等一下，仍在才再翻开。宿主不在前台、
     * 或翻回途中宿主暂停（见 [deliverPending]）时立即交付，不让已经接受的关闭停在半路。
     */
    fun closeTop(velocity: Float = 0f, action: () -> Unit) {
        val leaf = leaves.lastOrNull()
        if (leaf == null) {
            action()
            return
        }
        if (leaf.leaving) return
        drag = null
        leaf.leaving = true
        val below = turnOf(leaves.size - 1)
        val pending = PendingClose(leaf, below, action)
        pendingClose = pending
        if (!canAnimate()) {
            scope.launch(start = CoroutineStart.UNDISPATCHED) { below.snapTo(0f) }
            finishClose(pending)
            return
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                below.animateTo(0f, BOOK_PAGE_TURN_BACK, initialVelocity = velocity)
            } finally {
                finishClose(pending)
            }
        }
    }

    private fun finishClose(pending: PendingClose) {
        if (pending.delivered) return
        pending.delivered = true
        if (pendingClose === pending) pendingClose = null
        pending.action()
        val leaf = pending.leaf
        if (leaf !in leaves) return
        // 若关闭回调立刻往它上面压了一张子页（例如未保存更改确认页），立即解冻当前页并翻开回来
        if (leaves.lastOrNull() !== leaf) {
            leaf.leaving = false
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                if (canAnimate()) pending.below.animateTo(1f, BOOK_PAGE_TURN) else pending.below.snapTo(1f)
            }
            return
        }
        scope.launch {
            // 调用方可能晚几帧才撤掉这一页：等一小会儿，确实还在才翻开回来
            withTimeoutOrNull(BOOK_CLOSE_GRACE_MILLIS) { snapshotFlow { leaf in leaves }.first { !it } }
            if (leaf in leaves) {
                leaf.leaving = false
                if (canAnimate()) pending.below.animateTo(1f, BOOK_PAGE_TURN) else pending.below.snapTo(1f)
            }
        }
    }

    /** 宿主暂停（切后台、旋转重建前）：正在翻回的关闭立即交付，免得已经接受的关闭因保存状态而在重建后复活。 */
    fun deliverPending() {
        val pending = pendingClose ?: return
        scope.launch { pending.below.snapTo(0f) }
        finishClose(pending)
    }

    /** 返回 / 点书外空白：最上面那一页能关就翻回去并交付关闭（翻完时取调用方最新的关闭回调）；不能关（如正在保存）就原地不动。 */
    fun dismissTop(velocity: Float = 0f) {
        val leaf = leaves.lastOrNull() ?: return
        val spec = leaf.spec
        if (spec != null && !spec.dismissEnabled) {
            MotionHaptics.perform(MotionHaptics.Kind.Reject)
            return
        }
        closeTop(velocity = velocity) {
            val latest = leaf.spec
            if (latest != null) latest.onDismissRequest() else remove(leaf)
        }
    }

    fun dragStart() {
        if (!canAnimate()) return
        val leaf = leaves.lastOrNull() ?: return
        if (leaf.leaving) return
        val below = turnOf(leaves.lastIndex)
        scope.launch(start = CoroutineStart.UNDISPATCHED) { below.stop() }
        drag = PageDrag(leaf, below)
    }

    /** 往右拖：最上面那一页下面的那一页跟着手指翻回来，拖过 [span] 像素翻到底。 */
    fun dragBy(delta: Float, span: Float) {
        val current = drag ?: return
        if (!canAnimate()) {
            dragCancel()
            return
        }
        if (span <= 0f || !ownsTop(current)) return
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            current.below.snapTo((current.below.value - delta / span).coerceIn(0f, 1f))
        }
    }

    /** 松手：翻回过半或往右甩得够快就关闭这一页（带手势末速度衔接），否则弹回翻开。 */
    fun dragEnd(velocity: Float, span: Float) {
        val current = drag ?: return
        drag = null
        if (!ownsTop(current)) return
        if (span <= 0f || !canAnimate()) {
            openPage(current.below)
            return
        }
        val turnVelocity = -velocity / span
        if (current.leaf.spec?.dismissEnabled != false &&
            (current.below.value < BOOK_DRAG_CLOSE_AT || turnVelocity < -BOOK_DRAG_CLOSE_SPEED)
        ) {
            dismissTop(turnVelocity)
        } else {
            openPage(current.below, turnVelocity)
        }
    }

    fun dragCancel() {
        val current = drag ?: return
        drag = null
        if (ownsTop(current)) openPage(current.below)
    }

    private fun ownsTop(current: PageDrag): Boolean =
        leaves.lastOrNull() === current.leaf && !current.leaf.leaving && turnOf(leaves.lastIndex) === current.below

    fun releaseAll() {
        leaves.forEach { it.release() }
        ghost?.release()
        departure?.leaf?.release()
    }
}

@Composable
internal fun rememberShelfBookPages(): ShelfBookPages {
    val enhanced = MotionMode.enhanced
    val scope = rememberCoroutineScope()
    val pages = remember(enhanced) { ShelfBookPages(scope, enhanced) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(pages, owner) {
        pages.resumed = { owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) pages.deliverPending() }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            pages.releaseAll()
        }
    }
    return pages
}

/**
 * 书页绕书脊（左缘）翻过去：[turn] 0 = 平躺在右边，1 = 翻到左边；形变途中叠加书脊 3D 手托折角，
 * 未翻起页随右封板微抬、翻过页随左封板向观者倾折。挂在每一页自己的图层上
 * （页与页之间不裁切，翻过去的页才能伸到书脊左边）。
 */
internal fun Modifier.shelfBookLeafTurn(turn: () -> Float, morph: ShelfBookLayoutMorph? = null): Modifier = graphicsLayer {
    val t = turn()
    val h = morph?.hingeAngle ?: 0f
    val morphDeg = h * t - h * BOOK_MORPH_RIGHT_CRADLE_RATIO * (1f - t)
    transformOrigin = TransformOrigin(0f, 0.5f)
    rotationY = BOOK_COVER_TURN * t + morphDeg
    cameraDistance = SHELF_FACE_CAMERA * density
}

/** 这一页的正面 / 背面：翻过一半之前看到正面内容，之后是背面。 */
internal fun Modifier.shelfBookLeafSide(turn: () -> Float, back: Boolean): Modifier = graphicsLayer {
    alpha = if ((turn() > 0.5f) == back) 1f else 0f
}

/** 翻动中的那一页背光变暗（立起来时最暗）。 */
internal fun Modifier.shelfBookLeafShade(turn: () -> Float, shade: Color): Modifier = drawWithContent {
    drawContent()
    val a = BOOK_TURN_SHADE * sin(PI.toFloat() * turn().coerceIn(0f, 1f))
    if (a > 0.003f) drawRect(shade.copy(alpha = a))
}

/** 被上面那一页压着的书页随它翻开逐渐亮起来（[cover] = 上面那一页被翻开的程度）。 */
internal fun Modifier.shelfBookUnderShade(cover: () -> Float, shade: Color): Modifier = drawWithContent {
    drawContent()
    val closed = 1f - cover().coerceIn(0f, 1f)
    val a = BOOK_PAGE_SHADE * closed * closed
    if (a > 0.003f) drawRect(shade.copy(alpha = a))
}

/** 把这一页每一帧的样子记下来（[ShelfBookLeaf.snapshot]），调用方直接撤掉时还能垫在底下。 */
@Composable
internal fun Modifier.shelfBookLeafSnapshot(leaf: ShelfBookLeaf): Modifier {
    val graphics = LocalGraphicsContext.current
    return drawWithContent {
        if (leaf.released) {
            drawContent()
            return@drawWithContent
        }
        val layer = leaf.snapshot ?: graphics.createGraphicsLayer().also {
            leaf.snapshot = it
            leaf.graphics = graphics
        }
        layer.record { this@drawWithContent.drawContent() }
        drawLayer(layer)
    }
}

/** 画出被撤掉那一页最后的样子。 */
internal fun Modifier.shelfBookGhost(leaf: ShelfBookLeaf): Modifier = drawBehind {
    leaf.snapshot?.let { drawLayer(it) }
}

/** 翻到背面 / 被盖住的页不接触摸：在最早的一轮就吃掉，子级拿不到（翻过去的页在书脊左边镜像着，不能误点）。 */
internal fun Modifier.shelfBookBlockTouches(blocked: () -> Boolean): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (blocked()) event.changes.forEach { it.consume() }
        }
    }
}

/** 登记这一盒正面的布局坐标（挂在正面自己的图层之后，拿到的是屏幕上真实的样子）。 */
internal fun Modifier.shelfBookFront(stage: ShelfBookStage?, uri: String): Modifier =
    if (stage == null) this else onPlaced { stage.placeFront(uri, it) }

/** 登记这一盒书脊的布局坐标（只用它的厚度）。 */
internal fun Modifier.shelfBookSpine(stage: ShelfBookStage?, uri: String): Modifier =
    if (stage == null) this else onPlaced { stage.placeSpine(uri, it) }

/** 书此刻的俯仰外倾角度（度）：在书架上时与正中那一盒同样外倾 [HERO_TILT_DEGREES]，起飞后平滑扶正、落回时平滑恢复。 */
internal fun shelfBookTiltDegrees(flight: Float, hasOrigin: Boolean): Float {
    if (!hasOrigin) return 0f
    val turn = (flight / BOOK_TURN_SPAN).coerceIn(0f, 1f)
    return HERO_TILT_DEGREES * (1f - turn * turn * (3f - 2f * turn))
}

/** 以底边中点 (0.5f, 1f) 为轴缩放与外倾时，使书脊底角严格沿直线落回 [originLeft] 所需的横向平移。 */
internal fun shelfBookFlightTranslationX(
    originLeft: Float,
    bookLeft: Float,
    bookWidth: Float,
    flight: Float,
    scaleX: Float,
): Float = (originLeft - bookLeft) * (1f - flight) - bookWidth * 0.5f * (1f - scaleX)

/** 以底边中点 (0.5f, 1f) 为轴缩放与外倾时，使书脊底角严格沿直线落回 [originBottom] 所需的纵向平移。 */
internal fun shelfBookFlightTranslationY(
    originBottom: Float,
    bookBottom: Float,
    flight: Float,
): Float = (originBottom - bookBottom) * (1f - flight)

/**
 * 书的整体位置：从书架上那一盒的位置飞到摊开的位置（读 [ShelfBookMotion.flight]，只在图层里）。
 * 以底边中点 (0.5f, 1f) 为轴执行缩放与顶部外倾（与书架 `shelfBoxPose` 一致），再按底角直线对齐书脊棱底端。
 */
internal fun Modifier.shelfBookFlight(motion: ShelfBookMotion): Modifier = this
    .onPlaced { coordinates ->
        val topLeft = coordinates.localToScreen(Offset.Zero)
        val bounds = Rect(topLeft, Size(coordinates.size.width.toFloat(), coordinates.size.height.toFloat()))
        if (bounds != motion.bookBounds) motion.bookBounds = bounds
    }
    .graphicsLayer {
        alpha = motion.presence.value
        if (!motion.enhanced) return@graphicsLayer
        val f = motion.flight.value
        if (f == 1f) return@graphicsLayer
        val book = motion.bookBounds
        if (book == null) {
            alpha = 0f
            return@graphicsLayer
        }
        val from = motion.origin
        if (from == null || book.height <= 0f) {
            // 那一盒不在书架上：原地浮现 / 收起
            alpha = motion.presence.value * f.coerceIn(0f, 1f)
            val s = BOOK_APPEAR_SCALE + (1f - BOOK_APPEAR_SCALE) * f
            scaleX = s
            scaleY = s
            return@graphicsLayer
        }
        val sx = motion.scaleX()
        val sy = motion.scale()
        transformOrigin = TransformOrigin(0.5f, 1f)
        cameraDistance = POSE_CAMERA_DISTANCE * density
        rotationX = motion.tilt()
        scaleX = sx
        scaleY = sy
        translationX = shelfBookFlightTranslationX(from.left, book.left, book.width, f, sx)
        translationY = shelfBookFlightTranslationY(from.bottom, book.bottom, f)
    }

/**
 * 书的一个面（后封板、书页）随整本书侧转：绕书脊那条棱（左缘）。透视距离按当前缩放折算，书在书架上那么小的时候
 * 透视也和书架上的那一盒一样，捧到眼前时不至于夸张；旋转形变途中同步向观者微抬形成 V 形手托折角。
 */
internal fun Modifier.shelfBookFace(motion: ShelfBookMotion, morph: ShelfBookLayoutMorph? = null): Modifier =
    if (!motion.enhanced && morph == null) this else graphicsLayer {
        val cradle = (morph?.hingeAngle ?: 0f) * BOOK_MORPH_RIGHT_CRADLE_RATIO
        transformOrigin = TransformOrigin(0f, 0.5f)
        rotationY = (if (motion.enhanced) motion.yaw() else 0f) - cradle
        cameraDistance = SHELF_FACE_CAMERA * density / (if (motion.enhanced) motion.scale() else 1f)
    }

/** 右侧书芯切口层在旋转形变途中的 3D 手托微抬倾角（静止与飞行期均为 0）。 */
internal fun Modifier.shelfBookMorphCradle(morph: ShelfBookLayoutMorph): Modifier = graphicsLayer {
    val angle = morph.hingeAngle
    if (angle != 0f) {
        transformOrigin = TransformOrigin(0f, 0.5f)
        rotationY = -angle * BOOK_MORPH_RIGHT_CRADLE_RATIO
        cameraDistance = SHELF_FACE_CAMERA * density
    }
}

/** 封面绕书脊（左缘）翻开（读 [ShelfBookMotion.cover]），叠在整本书的侧转与形变 3D 书脊折角上。 */
internal fun Modifier.shelfBookCover(motion: ShelfBookMotion, morph: ShelfBookLayoutMorph? = null): Modifier = graphicsLayer {
    transformOrigin = TransformOrigin(0f, 0.5f)
    rotationY = motion.coverRotationY(morph)
    cameraDistance = SHELF_FACE_CAMERA * density / motion.scale()
}

/** 封面相对屏幕的实时 Y 轴转角（度）：叠加飞行侧转、开合进度与旋转形变书脊折角。 */
internal fun shelfBookCoverRotationY(
    yawDeg: Float,
    coverProgress: Float,
    hingeAngle: Float = 0f,
): Float = yawDeg + BOOK_COVER_TURN * coverProgress + hingeAngle

internal fun ShelfBookMotion.coverRotationY(morph: ShelfBookLayoutMorph? = null): Float =
    shelfBookCoverRotationY(if (enhanced) yaw() else 0f, cover.value, morph?.hingeAngle ?: 0f)

/** 按真实合成转角（以 `-90°` 为正面/内面分界）判定封面内侧是否朝向观者。 */
internal fun ShelfBookMotion.isCoverInsideVisible(morph: ShelfBookLayoutMorph? = null): Boolean =
    coverRotationY(morph) < -90f

/** 双页左页交互层相对屏幕的实时 Y 轴转角（度）：以书脊右缘为轴，与封面内侧左页保持 100% 空间重合。 */
internal fun shelfBookSpreadLeftRotationY(
    yawDeg: Float,
    coverProgress: Float,
    hingeAngle: Float = 0f,
): Float = shelfBookCoverRotationY(yawDeg, coverProgress, hingeAngle) - BOOK_COVER_TURN

/** 双页左页交互层随封面开合的柔和显隐曲线：跨过 `-90°` 切正面或低于起显点时严格为 `0f`。 */
internal fun shelfBookSpreadLeftRevealAlpha(
    coverProgress: Float,
    coverRotationYDeg: Float,
): Float {
    if (coverRotationYDeg >= -90f) return 0f
    val raw = ((coverProgress - BOOK_SPREAD_LEFT_START) / (1f - BOOK_SPREAD_LEFT_START)).coerceIn(0f, 1f)
    return tabSmoothstep(raw)
}

/**
 * 居中窄边单页或旋转形变途中左封板 / 翻页背面的实时透视补偿宽度（像素）：
 * 在 `-180°` 平摊时等于收束宽度 [baseWidthPx]；随着封面/书页朝 `-90°` 竖直抬起，
 * 按 3D 透视投影反解使屏幕左缘投影始终停在 `gutterX - baseWidthPx` 不向左反跳，
 * 至 `-90°` 处刚好展开为完整 [pageWidthPx]，与正面无缝交接。
 */
internal fun shelfBookEffectiveClippedWidthPx(
    baseWidthPx: Float,
    pageWidthPx: Float,
    rotationYDeg: Float,
    cameraDistancePx: Float,
): Float {
    val safePage = pageWidthPx.coerceAtLeast(1f)
    val safeBase = baseWidthPx.coerceIn(0f, safePage)
    if (safeBase >= safePage || rotationYDeg > -90f) return safePage
    val rad = Math.toRadians((-rotationYDeg).toDouble())
    val cosAbs = (-cos(rad)).coerceAtLeast(0.0)
    val sinPos = sin(rad).coerceAtLeast(0.0)
    val denom = cosAbs * cameraDistancePx + safeBase * sinPos
    if (denom <= 1e-4) return safePage
    return ((safeBase * cameraDistancePx) / denom).toFloat().coerceIn(safeBase, safePage)
}

/** 中缝装订结构（堵头布、缝线与深沟影）显隐曲线：封面立直（`-90°`）前对称、平滑淡隐，避免近竖直时骤消。 */
internal fun shelfBookBindingAlpha(rotationYDeg: Float): Float = when {
    rotationYDeg <= -132f -> 1f
    rotationYDeg >= -94f -> 0f
    else -> tabSmoothstep((-94f - rotationYDeg) / 38f)
}

/**
 * 中缝装订结构修饰符：直接作用在以书脊为中心的 [BOOK_BINDING_OVERLAY_WIDTH] 容器上，
 * 避免外层整页 `graphicsLayer(alpha < 1)` 触发离屏缓冲并将伸出书脊左侧的堵头布切掉一半。
 */
internal fun Modifier.shelfBookBindingOverlay(
    motion: ShelfBookMotion,
    morph: ShelfBookLayoutMorph? = null,
): Modifier = graphicsLayer {
    transformOrigin = TransformOrigin(0.5f, 0.5f)
    if (motion.enhanced) {
        rotationY = motion.yaw()
        cameraDistance = SHELF_FACE_CAMERA * density / motion.scale()
    }
    alpha = shelfBookBindingAlpha(motion.coverRotationY(morph))
}

/**
 * 书脊：贴在书的左缘外侧，和书架上一样按侧转角度横向压缩（仿射，不做透视）。书转正后就看不见了。
 * 宽度是书架上那一盒书脊的厚度按书的缩放折算，见 [ShelfBookOrigin.spineDepth]。
 */
internal fun Modifier.shelfBookSpineFace(motion: ShelfBookMotion): Modifier = graphicsLayer {
    transformOrigin = TransformOrigin(1f, 0.5f)
    val turn = Math.toRadians(motion.yaw().toDouble()).toFloat()
    scaleX = sin(turn).coerceAtLeast(0f)
    alpha = if (scaleX < 0.01f) 0f else 1f
}

/**
 * 起飞时封面上与书架一致的背光压暗：浓度随书转正按比例退掉（书架上的压暗与侧转角成正比）。
 * 覆盖层自身底色为压暗色，进度只写进透明度。
 */
internal fun Modifier.shelfBookFrontShade(motion: ShelfBookMotion, shade: Float): Modifier = graphicsLayer {
    alpha = if (!motion.enhanced || motion.origin == null) 0f else (shade * motion.yaw() / HERO_YAW_DEGREES).coerceIn(0f, 1f)
}

/** 封面的两面：合成转角过 `-90°` 之前看到封面内侧，之后看到正面封面。 */
internal fun Modifier.shelfBookCoverSide(
    motion: ShelfBookMotion,
    inside: Boolean,
    morph: ShelfBookLayoutMorph? = null,
): Modifier = graphicsLayer {
    alpha = if (motion.isCoverInsideVisible(morph) == inside) 1f else 0f
}

/** 翻动中的封面背光变暗（立起来时最暗），摊平 / 合上时恢复。 */
internal fun Modifier.shelfBookTurnShade(motion: ShelfBookMotion, shade: Color): Modifier = drawWithContent {
    drawContent()
    val a = BOOK_TURN_SHADE * sin(PI.toFloat() * motion.cover.value.coerceIn(0f, 1f))
    if (a > 0.003f) drawRect(shade.copy(alpha = a))
}

/** 被封面压着的书页是暗的，封面掀开多少就亮多少。 */
internal fun Modifier.shelfBookPageShade(motion: ShelfBookMotion, shade: Color): Modifier = drawWithContent {
    drawContent()
    val closed = 1f - motion.cover.value.coerceIn(0f, 1f)
    val a = BOOK_PAGE_SHADE * closed * closed
    if (a > 0.003f) drawRect(shade.copy(alpha = a))
}

/** 书页上的内容随封面掀开逐行「印」出来（第 [order] 行稍晚一点，超过 [BOOK_INK_MAX_ORDER] 的与最后一行同时）；
 * 合上时倒着收回。封面摊平时全部印完。
 */
internal fun Modifier.shelfBookInk(motion: ShelfBookMotion, order: Int): Modifier =
    if (!motion.enhanced) this else graphicsLayer {
        val step = order.coerceIn(0, BOOK_INK_MAX_ORDER) * BOOK_INK_STEP
        val t = ((motion.cover.value - BOOK_INK_START - step) / BOOK_INK_SPAN).coerceIn(0f, 1f)
        alpha = t
        translationX = (1f - t) * BOOK_INK_SHIFT.toPx()
    }

/** 书里翻页：翻过去、翻回来（稍慢一点、接近无过冲的高阻尼，并预热 2 帧避开首帧组合卡顿）。 */
private val BOOK_PAGE_TURN = MotionSprings.response<Float>(0.56f, 0.92f, 0.0008f)
private val BOOK_PAGE_TURN_BACK = MotionSprings.response<Float>(0.48f, 0.94f, 0.001f)
private const val BOOK_PAGE_TURN_WARMUP_FRAMES = 2

/** 翻开 / 合上：书离开书架、封面掀开与盖回的弹簧；封面晚书一拍起翻，盖到一半时书开始往回飞；落稳后交接淡出。 */
private val BOOK_FLIGHT_OPEN = MotionSprings.response<Float>(0.5f, 0.86f, 0.0005f)
private val BOOK_COVER_OPEN = MotionSprings.response<Float>(0.56f, 0.8f, 0.0008f)
private val BOOK_COVER_CLOSE = MotionSprings.response<Float>(0.34f, 1f, 0.001f)
private val BOOK_FLIGHT_CLOSE = MotionSprings.response<Float>(0.42f, 1f, 0.002f)
private val BOOK_HANDOFF = MotionSprings.response<Float>(0.14f, 1f, 0.01f)
private val BOOK_LENT_REVEAL = tween<Float>(durationMillis = 45, easing = FastOutSlowInEasing)
private val BOOK_HANDOFF_EXIT = tween<Float>(durationMillis = 40, easing = LinearEasing)

/** 合上途中被打断：书就地淡出、书架上的盒子同步放出来（比飞回去快得多，书架一直是活的）。 */
private val BOOK_INTERRUPT_EXIT = tween<Float>(durationMillis = 150, easing = LinearEasing)
private val BOOK_INTERRUPT_REVEAL = tween<Float>(durationMillis = 110, easing = FastOutSlowInEasing)
private const val BOOK_COVER_LAG_MILLIS = 70L
private const val BOOK_RETURN_AT = 0.45f

/** 从二级页面回来（上层页面开始退出）后最多等多久宿主回到前台再翻回目录（毫秒）。 */
private const val BOOK_RESUME_TIMEOUT_MILLIS = 700L

/** 从二级页面回来后，先让帧时钟走几帧再翻回目录（见 [ShelfBookMotion.stayDuringNavigation]）。 */
private const val BOOK_RESUME_SETTLE_FRAMES = 2

/** 关闭交付后调用方晚几帧才撤掉这一页时，等它多久再决定是否翻开回来。 */
private const val BOOK_CLOSE_GRACE_MILLIS = 80L
private const val BOOK_LEAVE_GRACE_MILLIS = 80L

/** 书架正中那一盒侧转的角度（度，与书架一致）、飞行途中书额外侧过来的最大角度（度）、封面翻开的总角度（负 = 朝观者掀起）。 */
internal val HERO_YAW_DEGREES = Math.toDegrees(HERO_YAW.toDouble()).toFloat()
private const val BOOK_FLIGHT_YAW = 8f
private const val BOOK_TURN_SPAN = 0.65f
internal const val BOOK_COVER_TURN = -180f

/** Android RenderNode 相机焦距换算基准（72 × cameraDistance × density = 像素焦距）。 */
internal const val BOOK_CAMERA_DISTANCE_PX_UNIT = 72f

/** 起飞后封面上的 ▶ 胶囊在飞到这个进度时完全收起。 */
private const val BOOK_BADGE_FADE = 0.25f

/** 没有起点时原地浮现的起始缩放。 */
private const val BOOK_APPEAR_SCALE = 0.9f

/** 封面立起时最暗的程度；被压着的书页最暗的程度。 */
private const val BOOK_TURN_SHADE = 0.45f
private const val BOOK_PAGE_SHADE = 0.6f

/** 书页内容逐行印出：封面掀开到多少开始、每行晚多少、最多错开几行、每行用多少、从右边挪进来的距离。 */
private const val BOOK_INK_START = 0.42f
private const val BOOK_INK_STEP = 0.04f
private const val BOOK_INK_MAX_ORDER = 7
private const val BOOK_INK_SPAN = 0.28f
private val BOOK_INK_SHIFT = 10.dp
private const val BOOK_SPREAD_LEFT_START = 0.60f

/** 往右拖合书：封面合过这一点（剩余开度）就合上；或往右甩得够快（每秒多少个「全开」）。 */
private const val BOOK_DRAG_CLOSE_AT = 0.62f
private const val BOOK_DRAG_CLOSE_SPEED = 1.4f

/** 展开书籍右页的两栏切换：目录（游戏抽屉操作）与管理（原长按游戏菜单）。 */
internal enum class ShelfBookTab { Contents, Manage }

/** 切换栈中某一层所处角色：正浮现的新页或正在散墨退场的旧页。 */
internal enum class ShelfBookTabRole { Entering, Exiting }

private fun tabSmoothstep(x: Float): Float {
    val t = x.coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/** 分栏切换时旧页基准「墨散渐隐」的阶段进度（0 = 刚起步，1 = 完全散去）。 */
internal fun shelfBookTabExitPhase(progress: Float): Float =
    shelfBookTabRowExitPhase(progress, order = 0)

/** 第 [order] 行旧页「散墨模糊漂移退场」的阶段进度（0 = 原位清晰，1 = 彻底散去）。 */
internal fun shelfBookTabRowExitPhase(progress: Float, order: Int): Float {
    val start = order.coerceIn(0, BOOK_TAB_EXIT_MAX_ORDER) * BOOK_TAB_EXIT_STEP
    return tabSmoothstep((progress - start) / BOOK_TAB_EXIT_SPAN)
}

/**
 * 退场容器外层透明度：增强档在全行散墨完成（[BOOK_TAB_EXIT_DONE]）后及卸载过渡帧中严格锁为 `0f`，
 * 普通档按柔和渐变至 `0f`，防止动画落定前最后几帧旧分栏闪烁回显。
 */
internal fun shelfBookTabExitingContainerAlpha(
    progress: Float,
    enhanced: Boolean,
    isTransitioning: Boolean,
): Float {
    if (!isTransitioning) return 0f
    return if (enhanced) {
        if (progress >= BOOK_TAB_EXIT_DONE) 0f else 1f
    } else {
        (1f - tabSmoothstep(progress / BOOK_TAB_ORDINARY_EXIT_END)).coerceIn(0f, 1f)
    }
}

/** 退场分栏单行透明度：动画已收尾（[BOOK_TAB_SETTLED]）或非过渡期严格为 `0f`。 */
internal fun shelfBookTabExitingRowAlpha(
    progress: Float,
    order: Int,
    isTransitioning: Boolean,
): Float {
    if (!isTransitioning || progress >= BOOK_TAB_SETTLED) return 0f
    val t = shelfBookTabRowExitPhase(progress, order)
    return ((1f - t) * (1f - t * 0.25f)).coerceIn(0f, 1f)
}

/**
 * 分栏切换时新页第 [order] 行块的显影进度（0 = 尚未浮现，1 = 清晰定稿）。
 * 不同行按 [BOOK_TAB_REVEAL_STEP] 错开波前起笔，并通过起点封顶确保在 `progress = 1f` 前全部收敛到 1。
 */
internal fun shelfBookTabRevealPhase(progress: Float, order: Int, subDelay: Float = 0f): Float {
    val rowStart = BOOK_TAB_REVEAL_START + order.coerceIn(0, BOOK_TAB_REVEAL_MAX_ORDER) * BOOK_TAB_REVEAL_STEP
    val start = (rowStart + subDelay.coerceAtLeast(0f)).coerceAtMost(1f - BOOK_TAB_REVEAL_SPAN)
    return tabSmoothstep((progress - start) / BOOK_TAB_REVEAL_SPAN)
}

/**
 * 分栏切换时新页第 [order] 行内第 [charIndex] 个字/段的「逐字模糊显影」进度（0..1）。
 * 末字最迟完成点严格受限于 `1f`，避免总文字较长时在动画落定时仍留模糊残影。
 */
internal fun shelfBookTabCharPhase(
    progress: Float,
    order: Int,
    charIndex: Int,
    charCount: Int,
    subDelay: Float = 0f,
): Float {
    val rowStart = BOOK_TAB_REVEAL_START + order.coerceIn(0, BOOK_TAB_REVEAL_MAX_ORDER) * BOOK_TAB_REVEAL_STEP
    val spread = if (charCount <= 1) 0f else ((charCount - 1) * BOOK_TAB_CHAR_STEP).coerceAtMost(BOOK_TAB_CHAR_MAX_SPREAD)
    val base = (rowStart + subDelay.coerceAtLeast(0f)).coerceAtMost(1f - BOOK_TAB_CHAR_SPAN - spread)
    val charDelay = if (charCount <= 1) 0f else spread * charIndex.coerceIn(0, charCount - 1) / (charCount - 1).toFloat()
    return tabSmoothstep((progress - (base + charDelay)) / BOOK_TAB_CHAR_SPAN)
}

/** 第 [order] 行目录/操作条的引导点「钢笔逐点绘出」进度（0..1）。 */
internal fun shelfBookTabLeaderPhase(progress: Float, order: Int): Float {
    val rowStart = BOOK_TAB_REVEAL_START + order.coerceIn(0, BOOK_TAB_REVEAL_MAX_ORDER) * BOOK_TAB_REVEAL_STEP
    val start = (rowStart + BOOK_TAB_LEADER_DELAY).coerceAtMost(1f - BOOK_TAB_LEADER_SPAN)
    return tabSmoothstep((progress - start) / BOOK_TAB_LEADER_SPAN)
}

/** 将文案按 Unicode 码点拆分为逐字片段；超过 [maxSegments] 时按均匀步长合并，不拆断代理对且无损拼接。 */
internal fun splitInkSegments(text: String, maxSegments: Int = BOOK_TAB_MAX_INK_SEGMENTS): List<String> {
    if (text.isEmpty() || maxSegments <= 1) return if (text.isEmpty()) emptyList() else listOf(text)
    val points = ArrayList<String>(text.length)
    var offset = 0
    while (offset < text.length) {
        val count = Character.charCount(text.codePointAt(offset))
        points.add(text.substring(offset, offset + count))
        offset += count
    }
    if (points.size <= maxSegments) return points
    val chunkSize = ceil(points.size.toFloat() / maxSegments).toInt().coerceAtLeast(2)
    val grouped = ArrayList<String>(maxSegments)
    var i = 0
    while (i < points.size) {
        val end = (i + chunkSize).coerceAtMost(points.size)
        grouped.add(points.subList(i, end).joinToString(""))
        i = end
    }
    return grouped
}

/**
 * 书籍页内分栏切换动效控制器：管理选中态、正退场旧页、切换方向与过渡状态，
 * 在首帧同步等待新页完成首次组合/测量后再启动时轴，避免复杂新页的首帧构图吞掉旧页散墨退隐；
 * 同时驱动分栏标题下方笔触游标的弹簧滑行。系统关闭动画时瞬切，普通档使用轻柔渐变。
 */
@Stable
internal class ShelfBookTabMotion(
    private val scope: CoroutineScope,
    val enhanced: Boolean,
    initial: ShelfBookTab = ShelfBookTab.Contents,
) {
    var selected by mutableStateOf(initial)
        private set
    var shown by mutableStateOf(initial)
        private set
    var exiting by mutableStateOf<ShelfBookTab?>(null)
        private set
    var direction by mutableFloatStateOf(1f)
        private set
    var isTransitioning by mutableStateOf(false)
        private set

    val progress = Animatable(1f)
    val indicator = Animatable(initial.ordinal.toFloat())

    private var generation = 0
    private var job: Job? = null

    fun switchTo(tab: ShelfBookTab) {
        if (tab == selected) return
        MotionHaptics.perform(MotionHaptics.Kind.Tick)
        val gen = ++generation
        job?.cancel()
        if (!ValueAnimator.areAnimatorsEnabled()) {
            selected = tab
            shown = tab
            exiting = null
            isTransitioning = false
            job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                progress.snapTo(1f)
                indicator.snapTo(tab.ordinal.toFloat())
            }
            return
        }
        val previous = shown
        direction = if (tab.ordinal >= previous.ordinal) 1f else -1f
        exiting = previous
        shown = tab
        selected = tab
        isTransitioning = true
        job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                progress.snapTo(0f)
                launch { indicator.animateTo(tab.ordinal.toFloat(), BOOK_TAB_INDICATOR_SPRING) }
                // 等两帧让新分栏完成首次组合与测量，防止首帧布局耗时把退场散墨区间吞掉
                withFrameNanos { }
                withFrameNanos { }
                if (generation != gen) return@launch
                progress.animateTo(
                    1f,
                    tween(
                        durationMillis = if (enhanced) BOOK_TAB_SWAP_MILLIS else BOOK_TAB_ORDINARY_MILLIS,
                        easing = LinearEasing,
                    ),
                )
            } finally {
                if (generation == gen) {
                    exiting = null
                    isTransitioning = false
                }
            }
        }
    }
}

@Composable
internal fun rememberShelfBookTabMotion(initial: ShelfBookTab = ShelfBookTab.Contents): ShelfBookTabMotion {
    val enhanced = MotionMode.enhanced
    val scope = rememberCoroutineScope()
    return remember(enhanced, initial) { ShelfBookTabMotion(scope, enhanced, initial) }
}

/** 退场分栏外层容器：退场期间吞掉触摸防误触；普通档淡出、增强档退场完成后锁死 `alpha = 0f` 防止尾帧回闪。 */
internal fun Modifier.shelfBookTabExitingContainer(motion: ShelfBookTabMotion): Modifier =
    this
        .graphicsLayer {
            alpha = shelfBookTabExitingContainerAlpha(
                progress = motion.progress.value,
                enhanced = motion.enhanced,
                isTransitioning = motion.isTransitioning,
            )
        }
        .shelfBookBlockTouches { true }

/** 进场分栏外层容器：增强档由内部逐行/逐字各自显影；普通档负责整栏轻柔淡入。 */
internal fun Modifier.shelfBookTabEnteringContainer(motion: ShelfBookTabMotion): Modifier =
    if (motion.enhanced || !motion.isTransitioning) this else graphicsLayer {
        alpha = tabSmoothstep((motion.progress.value - BOOK_TAB_ORDINARY_ENTER_START) / BOOK_TAB_ORDINARY_ENTER_SPAN)
    }

/** 分栏选项卡文字渐显层：零重组读取游标进度实现墨色沉浮。 */
internal fun Modifier.shelfBookTabLabelFade(
    motion: ShelfBookTabMotion,
    tabIndex: Int,
    activeLayer: Boolean,
): Modifier = graphicsLayer {
    val focus = (1f - abs(motion.indicator.value - tabIndex)).coerceIn(0f, 1f)
    alpha = if (activeLayer) focus else 1f - focus
}

/** 退场单行共享的散墨模糊与尾帧零透明锁死逻辑，供常规行与页脚按钮复用。 */
private fun GraphicsLayerScope.applyExitingRowEffect(motion: ShelfBookTabMotion, order: Int) {
    if (!motion.enhanced) return
    val p = motion.progress.value
    val a = shelfBookTabExitingRowAlpha(p, order, motion.isTransitioning)
    if (a <= 0.001f) {
        alpha = 0f
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) renderEffect = null
        return
    }
    val t = shelfBookTabRowExitPhase(p, order)
    alpha = a
    val scale = 1f - BOOK_TAB_EXIT_SCALE_DROP * t
    scaleX = scale
    scaleY = scale
    translationX = -motion.direction * BOOK_TAB_EXIT_SHIFT_X.toPx() * t
    translationY = -BOOK_TAB_EXIT_SHIFT_Y.toPx() * t
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        renderEffect = cachedDecalBlur(BOOK_TAB_EXIT_BLUR.toPx() * t)
    }
}

/**
 * 分栏单个块/行/图标动效修饰符：
 * - 退场期先于早退判断执行，确保达到全透明后至卸载期间始终保持不可见；
 * - 进场期从轻雾微移与轻微模糊中聚拢定稿；静止期零额外开销。
 */
internal fun Modifier.shelfBookTabItem(
    motion: ShelfBookTabMotion,
    role: ShelfBookTabRole,
    order: Int,
    subDelay: Float = 0f,
): Modifier = graphicsLayer {
    if (role == ShelfBookTabRole.Exiting) {
        applyExitingRowEffect(motion, order)
        return@graphicsLayer
    }
    if (!motion.enhanced || !motion.isTransitioning) return@graphicsLayer
    val p = motion.progress.value
    if (p >= BOOK_TAB_SETTLED) return@graphicsLayer
    val t = shelfBookTabRevealPhase(p, order, subDelay)
    val inv = 1f - t
    alpha = t
    val scale = 1f - BOOK_TAB_BLOCK_SCALE_DROP * inv
    scaleX = scale
    scaleY = scale
    translationX = motion.direction * BOOK_TAB_BLOCK_SHIFT_X.toPx() * inv
    translationY = BOOK_TAB_BLOCK_SHIFT_Y.toPx() * inv
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        renderEffect = cachedDecalBlur(BOOK_TAB_BLOCK_BLUR.toPx() * inv)
    }
}

/** 书页细分隔线钢笔拉画动效：退场散墨，进场自左端舒展拉出。 */
internal fun Modifier.shelfBookTabRuleReveal(
    motion: ShelfBookTabMotion,
    role: ShelfBookTabRole,
    order: Int,
): Modifier = if (role == ShelfBookTabRole.Exiting) {
    shelfBookTabItem(motion, role, order)
} else {
    graphicsLayer {
        if (!motion.enhanced || !motion.isTransitioning) return@graphicsLayer
        val p = motion.progress.value
        if (p >= BOOK_TAB_SETTLED) return@graphicsLayer
        val t = shelfBookTabRevealPhase(p, order, BOOK_TAB_RULE_SUB_DELAY)
        transformOrigin = TransformOrigin(0f, 0.5f)
        scaleX = t
        alpha = t
    }
}

/** 页脚主按钮动效（启动游戏 / 删除游戏）：退场随末行散去并锁死尾帧零透明，进场自下方微升并从模糊定稿。 */
internal fun Modifier.shelfBookTabFooterReveal(
    motion: ShelfBookTabMotion,
    role: ShelfBookTabRole,
    order: Int,
): Modifier = graphicsLayer {
    if (role == ShelfBookTabRole.Exiting) {
        applyExitingRowEffect(motion, order)
        return@graphicsLayer
    }
    if (!motion.enhanced || !motion.isTransitioning) return@graphicsLayer
    val p = motion.progress.value
    if (p >= BOOK_TAB_SETTLED) return@graphicsLayer
    val t = shelfBookTabRevealPhase(p, order)
    val inv = 1f - t
    alpha = t
    val scale = 1f - BOOK_TAB_FOOTER_SCALE_DROP * inv
    scaleX = scale
    scaleY = scale
    translationY = BOOK_TAB_FOOTER_SHIFT_Y.toPx() * inv
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        renderEffect = cachedDecalBlur(BOOK_TAB_FOOTER_BLUR.toPx() * inv)
    }
}

/** 切栏期间掠过纸面的柔和墨息光带（仅切换途中使用，静止时零绘制）。 */
internal fun Modifier.shelfBookTabPageWash(motion: ShelfBookTabMotion, tint: Color): Modifier = drawWithContent {
    drawContent()
    if (!motion.enhanced || !motion.isTransitioning) return@drawWithContent
    val p = motion.progress.value
    if (p <= BOOK_TAB_WASH_START || p >= BOOK_TAB_WASH_END) return@drawWithContent
    val u = ((p - BOOK_TAB_WASH_START) / (BOOK_TAB_WASH_END - BOOK_TAB_WASH_START)).coerceIn(0f, 1f)
    val envelope = sin(PI.toFloat() * u)
    val washAlpha = BOOK_TAB_WASH_ALPHA * envelope
    if (washAlpha <= 0.002f || size.width <= 0f) return@drawWithContent
    val bandWidth = size.width * BOOK_TAB_WASH_BAND
    val travel = if (motion.direction >= 0f) u else 1f - u
    val centerX = -bandWidth * 0.5f + (size.width + bandWidth) * travel
    drawRect(
        Brush.horizontalGradient(
            0f to Color.Transparent,
            0.5f to tint.copy(alpha = washAlpha),
            1f to Color.Transparent,
            startX = centerX - bandWidth * 0.5f,
            endX = centerX + bandWidth * 0.5f,
        ),
    )
}

/**
 * 书页逐字晕墨显影文字：
 * - 静止期与退场期直接渲染单个普通 `Text`（零额外图层与排版开销）；
 * - 增强档新页进场期间，放得下的单行按字符/短段显影，并保留 [FirstBaseline] 与紧致宽度；
 *   放不下的单行与多行沿用原生 `Text` 排版，对整段做显影。
 */
@Composable
internal fun ShelfBookInkText(
    text: String,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    tabMotion: ShelfBookTabMotion,
    tabRole: ShelfBookTabRole,
    order: Int,
    modifier: Modifier = Modifier,
    subDelay: Float = 0f,
    maxLines: Int = 1,
    overflow: TextOverflow = TextOverflow.Ellipsis,
    textAlign: TextAlign? = null,
) {
    val plainText: @Composable (Modifier) -> Unit = { textModifier ->
        Text(
            text = text,
            modifier = textModifier,
            style = style,
            color = color,
            maxLines = maxLines,
            overflow = overflow,
            textAlign = textAlign,
        )
    }
    if (maxLines != 1 || !tabMotion.enhanced || !tabMotion.isTransitioning || tabRole == ShelfBookTabRole.Exiting || text.isEmpty()) {
        plainText(
            if (maxLines != 1 && tabRole == ShelfBookTabRole.Entering) {
                modifier.shelfBookTabItem(tabMotion, tabRole, order, subDelay)
            } else {
                modifier
            },
        )
        return
    }
    val segments = remember(text) { splitInkSegments(text) }
    val content: @Composable () -> Unit = {
        plainText(Modifier.shelfBookTabItem(tabMotion, tabRole, order, subDelay))
        segments.forEachIndexed { idx, segment ->
            Text(
                text = segment,
                style = style,
                color = color,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.graphicsLayer {
                    val p = tabMotion.progress.value
                    if (p >= BOOK_TAB_SETTLED) return@graphicsLayer
                    val t = shelfBookTabCharPhase(p, order, idx, segments.size, subDelay)
                    val inv = 1f - t
                    alpha = t
                    translationX = tabMotion.direction * BOOK_TAB_CHAR_SHIFT_X.toPx() * inv
                    translationY = BOOK_TAB_CHAR_SHIFT_Y.toPx() * inv
                    val scale = 1f - BOOK_TAB_CHAR_SCALE_DROP * inv
                    scaleX = scale
                    scaleY = scale
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        renderEffect = cachedDecalBlur(BOOK_TAB_CHAR_BLUR.toPx() * inv)
                    }
                },
            )
        }
    }
    val layoutModifier = if (overflow == TextOverflow.Visible) modifier else modifier.clipToBounds()
    Layout(content = content, modifier = layoutModifier) { measurables, constraints ->
        val wholeLine = measurables.first()
        val wholeLineFits = !constraints.hasBoundedWidth ||
            wholeLine.maxIntrinsicWidth(constraints.maxHeight) <= constraints.maxWidth
        val loose = Constraints(maxWidth = Constraints.Infinity, maxHeight = constraints.maxHeight)
        val placeables = if (wholeLineFits) measurables.drop(1).map { it.measure(loose) } else emptyList()
        val totalWidth = placeables.sumOf { it.width }
        if (!wholeLineFits || totalWidth > constraints.maxWidth) {
            val line = wholeLine.measure(constraints)
            return@Layout layout(line.width, line.height) { line.placeRelative(0, 0) }
        }
        val height = placeables.maxOfOrNull { it.height } ?: constraints.minHeight
        val layoutWidth = totalWidth.coerceIn(constraints.minWidth, constraints.maxWidth)
        val firstPlaceable = placeables.firstOrNull()
        val firstBaseline = firstPlaceable?.get(FirstBaseline)?.takeIf { it != AlignmentLine.Unspecified }
        val alignmentLines: Map<AlignmentLine, Int> = if (firstBaseline != null) {
            val firstY = (height - firstPlaceable.height) / 2
            mapOf<AlignmentLine, Int>(FirstBaseline to firstY + firstBaseline)
        } else {
            emptyMap()
        }
        val startX = when {
            totalWidth >= layoutWidth -> 0
            textAlign == TextAlign.Center -> (layoutWidth - totalWidth) / 2
            textAlign == TextAlign.End || textAlign == TextAlign.Right -> layoutWidth - totalWidth
            else -> 0
        }
        layout(layoutWidth, height, alignmentLines) {
            var textX = startX
            for (placeable in placeables) {
                placeable.placeRelative(textX, (height - placeable.height) / 2)
                textX += placeable.width
            }
        }
    }
}

/** 分栏切换节奏、逐行/逐字波前曲线与笔触游标弹簧。 */
private const val BOOK_TAB_SWAP_MILLIS = 740
private const val BOOK_TAB_ORDINARY_MILLIS = 240
private const val BOOK_TAB_ORDINARY_EXIT_END = 0.55f
private const val BOOK_TAB_ORDINARY_ENTER_START = 0.25f
private const val BOOK_TAB_ORDINARY_ENTER_SPAN = 0.75f
private val BOOK_TAB_INDICATOR_SPRING = MotionSprings.response<Float>(0.36f, 0.78f, 0.001f)
private const val BOOK_TAB_MAX_INK_SEGMENTS = 12
private const val BOOK_TAB_EXIT_STEP = 0.024f
private const val BOOK_TAB_EXIT_SPAN = 0.24f
private const val BOOK_TAB_EXIT_MAX_ORDER = 6
private const val BOOK_TAB_EXIT_DONE = BOOK_TAB_EXIT_MAX_ORDER * BOOK_TAB_EXIT_STEP + BOOK_TAB_EXIT_SPAN
private val BOOK_TAB_EXIT_BLUR = 14.dp
private val BOOK_TAB_EXIT_SHIFT_X = 14.dp
private val BOOK_TAB_EXIT_SHIFT_Y = 4.dp
private const val BOOK_TAB_EXIT_SCALE_DROP = 0.035f
private const val BOOK_TAB_REVEAL_START = 0.22f
private const val BOOK_TAB_REVEAL_STEP = 0.068f
private const val BOOK_TAB_REVEAL_SPAN = 0.26f
private const val BOOK_TAB_REVEAL_MAX_ORDER = 6
private val BOOK_TAB_BLOCK_BLUR = 12.dp
private val BOOK_TAB_BLOCK_SHIFT_X = 10.dp
private val BOOK_TAB_BLOCK_SHIFT_Y = 5.dp
private const val BOOK_TAB_BLOCK_SCALE_DROP = 0.10f
private const val BOOK_TAB_CHAR_STEP = 0.036f
private const val BOOK_TAB_CHAR_MAX_SPREAD = 0.19f
private const val BOOK_TAB_CHAR_SPAN = 0.17f
private val BOOK_TAB_CHAR_BLUR = 11.dp
private val BOOK_TAB_CHAR_SHIFT_X = 7.dp
private val BOOK_TAB_CHAR_SHIFT_Y = 4.5.dp
private const val BOOK_TAB_CHAR_SCALE_DROP = 0.14f
private const val BOOK_TAB_LEADER_DELAY = 0.08f
private const val BOOK_TAB_LEADER_SPAN = 0.20f
private const val BOOK_TAB_RULE_SUB_DELAY = 0.04f
private val BOOK_TAB_FOOTER_BLUR = 12.dp
private val BOOK_TAB_FOOTER_SHIFT_Y = 12.dp
private const val BOOK_TAB_FOOTER_SCALE_DROP = 0.06f
private const val BOOK_TAB_WASH_START = 0.08f
private const val BOOK_TAB_WASH_END = 0.62f
private const val BOOK_TAB_WASH_ALPHA = 0.065f
private const val BOOK_TAB_WASH_BAND = 0.42f
private const val BOOK_TAB_SETTLED = 0.999f

private val decalBlurCache = java.util.concurrent.ConcurrentHashMap<Int, BlurEffect>(64)

/** 按 0.5px 量化复用 Decal 模糊效果对象，消除逐字 / 逐行晕墨显隐期间每帧大量 BlurEffect 分配。 */
@androidx.annotation.RequiresApi(Build.VERSION_CODES.S)
private fun cachedDecalBlur(radiusPx: Float): BlurEffect? {
    if (radiusPx < 0.5f) return null
    val halfSteps = (radiusPx * 2f).roundToInt().coerceAtLeast(1)
    if (halfSteps > 240) return BlurEffect(radiusPx, radiusPx, TileMode.Decal)
    return decalBlurCache.getOrPut(halfSteps) {
        val quantized = halfSteps * 0.5f
        BlurEffect(quantized, quantized, TileMode.Decal)
    }
}

/** 旋转 / 窗口比例变化时的旧布局淡出截止点、新布局显现起点与左/右封板 3D 重折呼吸上限。 */
private const val BOOK_MORPH_EXIT_END = 0.36f
private const val BOOK_MORPH_ENTER_START = 0.32f
private const val BOOK_MORPH_MILLIS = 560
private const val BOOK_MORPH_ORDINARY_MILLIS = 180
private const val BOOK_MORPH_SAME_BREATH_MILLIS = 440
private const val BOOK_MORPH_HINGE_DEG = 16f
private const val BOOK_MORPH_HINGE_LARGE_DEG = 22f
private const val BOOK_MORPH_HINGE_SAME_DEG = 10f
private const val BOOK_MORPH_RIGHT_CRADLE_RATIO = 0.35f
private const val BOOK_MORPH_HINGE_POW = 0.78
private val BOOK_MORPH_SHIFT_EXPAND = 16.dp
private val BOOK_MORPH_SHIFT_COLLAPSE = 14.dp
private val BOOK_MORPH_BLUR = 7.dp
private val BOOK_MORPH_TAB_SLIDE = 18.dp
private val BOOK_MORPH_GEOMETRY_SPRING = MotionSprings.response<Float>(0.44f, 0.86f, 0.5f)
private val BOOK_MORPH_WEIGHT_SPRING = MotionSprings.response<Float>(0.36f, 0.92f, 0.002f)

/**
 * 当横竖屏旋转等视口变化导致旧书体轮廓超出新视口边界时，保持旧形态左右页比例将起势帧等比缩放到新视口内居中，
 * 避免旋转首帧书底被裁切或书页从屏外飞入；若旧书体已完整位于新视口内则原样返回以平滑过渡。
 */
internal fun shelfBookMorphReprojectedStart(
    old: ShelfBookFrame,
    viewportWidth: Dp,
    viewportHeight: Dp,
): ShelfBookFrame {
    if (viewportWidth <= 0.dp || viewportHeight <= 0.dp) return old
    val oldLeft = old.gutterX - old.leftPageWidth
    val oldRight = old.gutterX + old.pageWidth
    val oldTop = old.top
    val oldBottom = old.top + old.pageHeight
    if (oldLeft >= -2.dp && oldRight <= viewportWidth + 2.dp && oldTop >= -2.dp && oldBottom <= viewportHeight + 2.dp) {
        return old
    }
    val oldTotalW = (old.leftPageWidth + old.pageWidth).coerceAtLeast(1.dp)
    val fit = minOf(
        1f,
        (viewportWidth - 20.dp).coerceAtLeast(80.dp) / oldTotalW,
        (viewportHeight - 32.dp).coerceAtLeast(80.dp) / old.pageHeight.coerceAtLeast(1.dp),
    )
    val scaledLeft = old.leftPageWidth * fit
    val scaledRight = old.pageWidth * fit
    val scaledH = old.pageHeight * fit
    val scaledGutter = (viewportWidth - (scaledLeft + scaledRight)) / 2 + scaledLeft
    val scaledTop = (viewportHeight - scaledH) / 2
    return ShelfBookFrame(
        gutterX = scaledGutter,
        top = scaledTop,
        pageWidth = scaledRight,
        pageHeight = scaledH,
        spread = old.spread,
        layoutMode = old.layoutMode,
        leftPageWidth = scaledLeft,
    )
}

/** 形态切换时旧布局内容在前半段（0..[BOOK_MORPH_EXIT_END]）平滑淡出。 */
internal fun shelfBookMorphExitAlpha(progress: Float): Float {
    val p = progress.coerceIn(0f, 1f)
    if (p >= BOOK_MORPH_EXIT_END) return 0f
    return 1f - tabSmoothstep(p / BOOK_MORPH_EXIT_END)
}

/** 形态切换时新布局内容在后半段（[BOOK_MORPH_ENTER_START]..1）以缓出曲线平滑显影。 */
internal fun shelfBookMorphEnterAlpha(progress: Float): Float {
    val p = progress.coerceIn(0f, 1f)
    if (p <= BOOK_MORPH_ENTER_START) return 0f
    val u = ((p - BOOK_MORPH_ENTER_START) / (1f - BOOK_MORPH_ENTER_START)).coerceIn(0f, 1f)
    val inv = 1f - u
    return 1f - inv * inv * inv
}

/** 双向单双页切换与同形态横竖屏旋转时的左/右封板 3D 重折呼吸（非激活期恒为 0）。 */
internal fun shelfBookMorphHingeBreath(
    progress: Float,
    active: Boolean,
    peakDeg: Float = BOOK_MORPH_HINGE_DEG,
): Float {
    if (!active) return 0f
    val p = progress.coerceIn(0f, 1f)
    val eased = p.toDouble().pow(BOOK_MORPH_HINGE_POW).toFloat()
    return sin(PI.toFloat() * eased) * peakDeg
}

private fun leftPaperTarget(mode: ShelfBookLayoutMode): Float =
    if (mode == ShelfBookLayoutMode.Spread) 1f else 0f

private fun tabBarTarget(mode: ShelfBookLayoutMode): Float =
    if (mode != ShelfBookLayoutMode.Spread) 1f else 0f

/**
 * 书本在横竖屏旋转或窗口尺寸变化时的连续形态控制器：
 * - 视口大幅旋转时先将起势几何等比重投影到新视口内，再用低过冲弹簧连续追踪书脊位置、书高、左右页宽与显隐权重；
 * - 跨越双页 ↔ 单页阈值时驱动跨阶段交叉显隐，同形态旋转亦叠加强弱分级的 3D V 形手托呼吸倾角；
 * - 严格同步左封板裁剪门限与几何弹簧生命周期，消除落定瞬间封板突变；普通档仅做轻柔淡入淡出。
 */
@Stable
internal class ShelfBookLayoutMorph(
    private val scope: CoroutineScope,
    val enhanced: Boolean,
    initial: ShelfBookFrame,
) {
    val gutterX = Animatable(initial.gutterX.value)
    val top = Animatable(initial.top.value)
    val pageWidth = Animatable(initial.pageWidth.value)
    val pageHeight = Animatable(initial.pageHeight.value)
    val leftPageWidth = Animatable(initial.leftPageWidth.value)
    val leftPaperWeight = Animatable(leftPaperTarget(initial.layoutMode))
    val tabBarWeight = Animatable(tabBarTarget(initial.layoutMode))
    val morphProgress = Animatable(1f)

    var currentMode by mutableStateOf(initial.layoutMode)
        private set
    var previousMode by mutableStateOf<ShelfBookLayoutMode?>(null)
        private set
    var animateMorphEffects by mutableStateOf(false)
        private set
    var hingePeakDeg by mutableFloatStateOf(BOOK_MORPH_HINGE_DEG)
        private set

    private var initialized = false
    private var lastViewportWidth: Dp = 0.dp
    private var lastViewportHeight: Dp = 0.dp
    private var morphGeneration = 0
    private var geometryJob: Job? = null
    private var morphJob: Job? = null

    val animatedFrame: ShelfBookFrame
        get() = ShelfBookFrame(
            gutterX = gutterX.value.dp,
            top = top.value.dp,
            pageWidth = pageWidth.value.dp,
            pageHeight = pageHeight.value.dp,
            spread = currentMode == ShelfBookLayoutMode.Spread,
            layoutMode = currentMode,
            leftPageWidth = leftPageWidth.value.dp,
        )

    private var rotationPaperFrame by mutableStateOf<ShelfBookFrame?>(null)

    private fun viewportFlipped(width: Dp, height: Dp): Boolean = lastViewportWidth > 0.dp &&
        ((lastViewportWidth > lastViewportHeight) != (width > height))

    fun rotationPageLayout(target: ShelfBookFrame, width: Dp, height: Dp, tablet: Boolean): ShelfBookFrame? {
        if (!tablet || !enhanced) return null
        return if (viewportFlipped(width, height)) target else rotationPaperFrame
    }

    /** 同步裁剪当前视觉帧至最新视口安全边界，消除旋转首帧尚未执行 [LaunchedEffect] 前的越界裁底。 */
    fun visualFrame(viewportWidth: Dp, viewportHeight: Dp): ShelfBookFrame =
        shelfBookMorphReprojectedStart(animatedFrame, viewportWidth, viewportHeight)

    val isMorphing: Boolean get() = previousMode != null

    val isGeometryMoving: Boolean
        get() = gutterX.isRunning || top.isRunning || pageWidth.isRunning || pageHeight.isRunning || leftPageWidth.isRunning

    val isLeftBoardClipped: Boolean
        get() = isMorphing ||
            isGeometryMoving ||
            currentMode == ShelfBookLayoutMode.SingleCentered ||
            (currentMode == ShelfBookLayoutMode.Spread && abs(leftPageWidth.value - pageWidth.value) > 0.5f)

    val hingeAngle: Float
        get() = shelfBookMorphHingeBreath(
            progress = morphProgress.value,
            active = animateMorphEffects && (isMorphing || morphProgress.value < 0.999f),
            peakDeg = hingePeakDeg,
        )

    val leftPaperAlpha: Float get() = leftPaperWeight.value.coerceIn(0f, 1f)

    val horizontalTabWeight: Float get() = tabBarWeight.value.coerceIn(0f, 1f)

    fun showsLeftMode(mode: ShelfBookLayoutMode): Boolean = currentMode == mode || previousMode == mode

    fun isInteractiveLeftMode(mode: ShelfBookLayoutMode): Boolean =
        currentMode == mode && (!isMorphing || morphProgress.value >= 0.45f)

    fun rightNeedsSpreadBodySwap(selectedTab: ShelfBookTab): Boolean {
        val prev = previousMode ?: return false
        return selectedTab == ShelfBookTab.Manage &&
            ((prev == ShelfBookLayoutMode.Spread) != (currentMode == ShelfBookLayoutMode.Spread))
    }

    fun update(target: ShelfBookFrame, viewportWidth: Dp, viewportHeight: Dp, flightReady: Boolean, tablet: Boolean) {
        val immediate = !initialized || !flightReady || !ValueAnimator.areAnimatorsEnabled()
        if (immediate) {
            initialized = true
            lastViewportWidth = viewportWidth
            lastViewportHeight = viewportHeight
            morphGeneration++
            geometryJob?.cancel()
            morphJob?.cancel()
            rotationPaperFrame = null
            previousMode = null
            currentMode = target.layoutMode
            animateMorphEffects = false
            hingePeakDeg = BOOK_MORPH_HINGE_DEG
            geometryJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                gutterX.snapTo(target.gutterX.value)
                top.snapTo(target.top.value)
                pageWidth.snapTo(target.pageWidth.value)
                pageHeight.snapTo(target.pageHeight.value)
                leftPageWidth.snapTo(target.leftPageWidth.value)
                leftPaperWeight.snapTo(leftPaperTarget(target.layoutMode))
                tabBarWeight.snapTo(tabBarTarget(target.layoutMode))
                morphProgress.snapTo(1f)
            }
            return
        }

        val viewportFlipped = viewportFlipped(viewportWidth, viewportHeight)
        lastViewportWidth = viewportWidth
        lastViewportHeight = viewportHeight
        val modeChanged = target.layoutMode != currentMode
        if (!enhanced) {
            geometryJob?.cancel()
            geometryJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                gutterX.snapTo(target.gutterX.value)
                top.snapTo(target.top.value)
                pageWidth.snapTo(target.pageWidth.value)
                pageHeight.snapTo(target.pageHeight.value)
                leftPageWidth.snapTo(target.leftPageWidth.value)
                leftPaperWeight.snapTo(leftPaperTarget(target.layoutMode))
                tabBarWeight.snapTo(tabBarTarget(target.layoutMode))
            }
            if (modeChanged) {
                startModeTransition(
                    nextMode = target.layoutMode,
                    animateFX = false,
                    durationMs = BOOK_MORPH_ORDINARY_MILLIS,
                    peakDeg = BOOK_MORPH_HINGE_SAME_DEG,
                )
            }
            return
        }

        val currentSnap = animatedFrame
        val start = shelfBookMorphReprojectedStart(currentSnap, viewportWidth, viewportHeight)
        geometryJob?.cancel()
        val paperFrame = target.takeIf { tablet && (viewportFlipped || rotationPaperFrame != null) }
        rotationPaperFrame = paperFrame
        if (modeChanged) {
            val spreadBoundary = (currentMode == ShelfBookLayoutMode.Spread) != (target.layoutMode == ShelfBookLayoutMode.Spread)
            val peak = if (spreadBoundary) BOOK_MORPH_HINGE_LARGE_DEG else BOOK_MORPH_HINGE_SAME_DEG
            startModeTransition(target.layoutMode, animateFX = true, durationMs = BOOK_MORPH_MILLIS, peakDeg = peak)
        } else if (viewportFlipped) {
            startSameModeBreath(peakDeg = BOOK_MORPH_HINGE_SAME_DEG, durationMs = BOOK_MORPH_SAME_BREATH_MILLIS)
        }
        geometryJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            if (start !== currentSnap) {
                gutterX.snapTo(start.gutterX.value)
                top.snapTo(start.top.value)
                pageWidth.snapTo(start.pageWidth.value)
                pageHeight.snapTo(start.pageHeight.value)
                leftPageWidth.snapTo(start.leftPageWidth.value)
            }
            val targetPaper = leftPaperTarget(target.layoutMode)
            val targetTab = tabBarTarget(target.layoutMode)
            coroutineScope {
                launch { gutterX.animateTo(target.gutterX.value, BOOK_MORPH_GEOMETRY_SPRING) }
                launch { top.animateTo(target.top.value, BOOK_MORPH_GEOMETRY_SPRING) }
                launch { pageWidth.animateTo(target.pageWidth.value, BOOK_MORPH_GEOMETRY_SPRING) }
                launch { pageHeight.animateTo(target.pageHeight.value, BOOK_MORPH_GEOMETRY_SPRING) }
                launch { leftPageWidth.animateTo(target.leftPageWidth.value, BOOK_MORPH_GEOMETRY_SPRING) }
                launch { leftPaperWeight.animateTo(targetPaper, BOOK_MORPH_WEIGHT_SPRING) }
                launch { tabBarWeight.animateTo(targetTab, BOOK_MORPH_WEIGHT_SPRING) }
            }
            if (paperFrame != null) {
                morphJob?.join()
                rotationPaperFrame = null
            }
        }
    }

    private fun startModeTransition(
        nextMode: ShelfBookLayoutMode,
        animateFX: Boolean,
        durationMs: Int,
        peakDeg: Float,
    ) {
        val gen = ++morphGeneration
        val prev = currentMode
        morphJob?.cancel()
        previousMode = prev
        currentMode = nextMode
        animateMorphEffects = animateFX
        hingePeakDeg = peakDeg
        morphJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                morphProgress.snapTo(0f)
                morphProgress.animateTo(1f, tween(durationMs, easing = FastOutSlowInEasing))
            } finally {
                if (morphGeneration == gen) {
                    previousMode = null
                    animateMorphEffects = false
                }
            }
        }
    }

    private fun startSameModeBreath(peakDeg: Float, durationMs: Int) {
        val gen = ++morphGeneration
        morphJob?.cancel()
        previousMode = null
        animateMorphEffects = true
        hingePeakDeg = peakDeg
        morphJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                morphProgress.snapTo(0f)
                morphProgress.animateTo(1f, tween(durationMs, easing = FastOutSlowInEasing))
            } finally {
                if (morphGeneration == gen) {
                    animateMorphEffects = false
                }
            }
        }
    }
}

@Composable
internal fun rememberShelfBookLayoutMorph(
    target: ShelfBookFrame,
    viewportWidth: Dp,
    viewportHeight: Dp,
    tablet: Boolean,
    flightReady: () -> Boolean,
): ShelfBookLayoutMorph {
    val enhanced = MotionMode.enhanced
    val scope = rememberCoroutineScope()
    val morph = remember(enhanced, scope) { ShelfBookLayoutMorph(scope, enhanced, target) }
    LaunchedEffect(
        target.gutterX,
        target.top,
        target.pageWidth,
        target.pageHeight,
        target.leftPageWidth,
        target.layoutMode,
        viewportWidth,
        viewportHeight,
        tablet,
    ) {
        morph.update(target, viewportWidth, viewportHeight, flightReady(), tablet)
    }
    return morph
}

internal fun shelfBookPaperScale(finalSize: IntSize, slotSize: IntSize): Float =
    minOf(slotSize.width.toFloat() / finalSize.width, slotSize.height.toFloat() / finalSize.height)

/** 正文按旋转终态测量，在逐帧变化的纸面里靠书脊等比缩放。 */
internal fun Modifier.shelfBookFitPaper(finalSize: IntSize?, gutterAtRight: Boolean = false): Modifier =
    if (finalSize == null) this else layout { measurable, constraints ->
        val placeable = measurable.measure(Constraints.fixed(finalSize.width, finalSize.height))
        val slot = IntSize(constraints.constrainWidth(placeable.width), constraints.constrainHeight(placeable.height))
        val scale = shelfBookPaperScale(finalSize, slot)
        val left = if (gutterAtRight) (slot.width - placeable.width * scale).roundToInt() else 0
        val top = ((slot.height - placeable.height * scale) / 2f).roundToInt()
        layout(slot.width, slot.height) {
            placeable.placeWithLayer(left, top) {
                transformOrigin = TransformOrigin(0f, 0f)
                scaleX = scale
                scaleY = scale
            }
        }
    }

/**
 * 左页交互层（`Spread` 管理页）显隐、散焦交叉与同步 3D 翻合：
 * 围绕书脊右缘 `TransformOrigin(1f, 0.5f)` 跟随左封板与内侧左页做 100% 同步的 Y 轴翻合、透视焦距与飞行变换，
 * 不越过中缝线，跨过 `-90°` 切正面或右页翻过半后立即隐去，确保内容始终紧贴在缓缓合起的左页纸面上柔和渐隐。
 */
internal fun Modifier.shelfBookMorphLeftSlot(
    morph: ShelfBookLayoutMorph,
    roleMode: ShelfBookLayoutMode,
    motion: ShelfBookMotion,
    pages: ShelfBookPages,
): Modifier = graphicsLayer {
    val p = motion.presence.value
    val coverRotY = motion.coverRotationY(morph)
    if (p <= 0.001f || pages.toc.value > 0.5f || coverRotY >= -90f) {
        alpha = 0f
        return@graphicsLayer
    }
    val openAlpha = if (!motion.enhanced) {
        p
    } else {
        shelfBookSpreadLeftRevealAlpha(motion.cover.value, coverRotY) * p
    }
    if (openAlpha <= 0.001f) {
        alpha = 0f
        return@graphicsLayer
    }
    val leftRotY = coverRotY - BOOK_COVER_TURN
    val flightScale = if (motion.enhanced) motion.scale().coerceAtLeast(0.01f) else 1f
    if (leftRotY != 0f) {
        transformOrigin = TransformOrigin(1f, 0.5f)
        rotationY = leftRotY
        cameraDistance = SHELF_FACE_CAMERA * density / flightScale
    }
    var finalOpenAlpha = openAlpha
    if (motion.enhanced && motion.flight.value < 0.999f) {
        val book = motion.bookBounds
        if (book == null) {
            alpha = 0f
            return@graphicsLayer
        }
        val from = motion.origin
        val f = motion.flight.value
        if (from != null && book.height > 0f) {
            val sx = motion.scaleX()
            val sy = motion.scale()
            transformOrigin = TransformOrigin(1f, 0.5f)
            scaleX = sx
            scaleY = sy
            translationX = (from.left - book.left) * (1f - f)
            translationY = (from.bottom - book.bottom) * (1f - f) + book.height * 0.5f * (1f - sy)
        } else {
            finalOpenAlpha *= f.coerceIn(0f, 1f)
        }
    }
    val prev = morph.previousMode
    if (prev == null) {
        alpha = if (roleMode == morph.currentMode) finalOpenAlpha else 0f
        return@graphicsLayer
    }
    val progress = morph.morphProgress.value
    val fx = morph.animateMorphEffects && morph.enhanced
    when (roleMode) {
        prev -> {
            val a = shelfBookMorphExitAlpha(progress)
            alpha = finalOpenAlpha * a
            if (fx) {
                val t = (1f - a).coerceIn(0f, 1f)
                transformOrigin = TransformOrigin(1f, 0.5f)
                translationX = -BOOK_MORPH_SHIFT_COLLAPSE.toPx() * 0.6f * t
                val s = 1f - 0.035f * t
                scaleX = s
                scaleY = s
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    renderEffect = cachedDecalBlur(BOOK_MORPH_BLUR.toPx() * t)
                }
            }
        }
        morph.currentMode -> {
            val a = shelfBookMorphEnterAlpha(progress)
            alpha = finalOpenAlpha * a
            if (fx) {
                val inv = (1f - a).coerceIn(0f, 1f)
                val expanding = morph.currentMode.ordinal > prev.ordinal
                transformOrigin = TransformOrigin(1f, 0.5f)
                val shift = if (expanding) -BOOK_MORPH_SHIFT_EXPAND.toPx() * 0.4f else -BOOK_MORPH_SHIFT_COLLAPSE.toPx() * 0.55f
                translationX = shift * inv
                val s = 1f - 0.03f * inv
                scaleX = s
                scaleY = s
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    renderEffect = cachedDecalBlur(BOOK_MORPH_BLUR.toPx() * inv)
                }
            }
        }
        else -> alpha = 0f
    }
}

/** 旋转形变 3D 折页呼吸期间在书脊内缘叠加柔和折痕受光影（静止时零绘制）。 */
internal fun Modifier.shelfBookMorphSpineShadow(
    morph: ShelfBookLayoutMorph,
    shade: Color,
    gutterAtStart: Boolean,
): Modifier = drawWithContent {
    drawContent()
    val angle = morph.hingeAngle
    if (angle <= 0.1f || size.width <= 0f) return@drawWithContent
    val strength = (angle / BOOK_MORPH_HINGE_LARGE_DEG).coerceIn(0f, 1f)
    val span = minOf(size.width * 0.32f, 26.dp.toPx())
    val alpha = 0.16f * strength
    if (gutterAtStart) {
        drawRect(
            Brush.horizontalGradient(
                0f to shade.copy(alpha = alpha),
                1f to Color.Transparent,
                startX = 0f,
                endX = span,
            ),
        )
    } else {
        drawRect(
            Brush.horizontalGradient(
                0f to Color.Transparent,
                1f to shade.copy(alpha = alpha),
                startX = size.width - span,
                endX = size.width,
            ),
        )
    }
}

/** 右页顶部水平分栏随布局权重平滑退散或浮现。 */
internal fun Modifier.shelfBookMorphTabSlot(weight: () -> Float): Modifier = graphicsLayer {
    val w = weight().coerceIn(0f, 1f)
    alpha = w
    if (w < 0.999f) {
        translationX = -(1f - w) * BOOK_MORPH_TAB_SLIDE.toPx()
    }
}

/** 处于「管理」分栏且跨越单双页边界旋转时，右页正文做柔和交叉显隐。 */
internal fun Modifier.shelfBookMorphRightBody(
    morph: ShelfBookLayoutMorph,
    incoming: Boolean,
): Modifier = graphicsLayer {
    if (!morph.isMorphing) {
        alpha = if (incoming) 1f else 0f
        return@graphicsLayer
    }
    val p = morph.morphProgress.value
    val a = if (incoming) shelfBookMorphEnterAlpha(p) else shelfBookMorphExitAlpha(p)
    alpha = a
    if (morph.animateMorphEffects && morph.enhanced) {
        val inv = (1f - a).coerceIn(0f, 1f)
        val s = 1f - 0.025f * inv
        scaleX = s
        scaleY = s
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            renderEffect = cachedDecalBlur((BOOK_MORPH_BLUR * 0.8f).toPx() * inv)
        }
    }
}

/** 随形态权重按自然测量高度平滑展开/收拢并同步渐隐（兼容系统大字号，零硬编码高度）。 */
internal fun Modifier.shelfBookMorphRevealHeight(weight: () -> Float): Modifier =
    clipToBounds().layout { measurable, constraints ->
        val w = weight().coerceIn(0f, 1f)
        val placeable = measurable.measure(constraints)
        val h = (placeable.height * w).roundToInt()
        layout(placeable.width, h) {
            placeable.placeRelativeWithLayer(0, 0) {
                alpha = w
            }
        }
    }
