package com.tyranor.next.ui.common.motion

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asComposeRenderEffect
import com.tyranor.next.ui.common.notice.NoticeHost
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.tyranor.next.core.settings.AppSettingsStore
import com.tyranor.next.core.i18n.AppLocaleController
import com.tyranor.next.R
import com.tyranor.next.theme.AppThemeColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.utils.MiuixOverscrollFactory

/**
 * 独立 Activity 页面之间的转场：水平侧推（新页从右侧推入、上一级向左让开 30% 并压暗；返回原路倒放）。
 *
 * 窗口动画只能用插值器，这里用 [MotionSprings.PushEasing]（单调减速曲线：起步就在全速、越近停位越慢，没有「先黏后冲」）；
 * 上一级页面的压暗与翻折景深由该页自己的 [PageDepth]（[pageTransitionScrim] / [depthFoldStack]）在 Compose 里同步绘制。
 */
object PageTransitions {
    private const val PUSH_TOKEN_EXTRA = "com.tyranor.next.ui.common.motion.PAGE_PUSH_TOKEN"
    // 「增强」档为水平侧推；「普通」档沿用原版的上下翻页（进入向上、返回向下）
    val PushEnter: Int get() = if (MotionMode.enhanced) R.anim.page_push_enter else R.anim.page_slide_in_from_bottom
    val PushExit: Int get() = if (MotionMode.enhanced) R.anim.page_push_exit else R.anim.page_slide_out_to_top
    val PopEnter: Int get() = if (MotionMode.enhanced) R.anim.page_pop_enter else R.anim.page_slide_in_from_top
    val PopExit: Int get() = if (MotionMode.enhanced) R.anim.page_pop_exit else R.anim.page_slide_out_to_bottom

    @Volatile
    private var lastPushAt: Long = 0L

    /** 记一次「正在推入下一页」，供当前页在 onPause 时压暗（「普通」档不压暗）。 */
    private fun markPush(): Long? {
        lastPushAt = if (MotionMode.enhanced) SystemClock.uptimeMillis() else 0L
        // 每次推入新开一层「收回」登记：同一次推入里被盖住的页面和它上面的抽屉，在上层页面退出时一起收回
        return if (MotionMode.enhanced) popLevels.push() else null
    }

    /** 仅消费最近一秒的侧推标记，避免普通生命周期变化触发页面压暗。 */
    internal fun consumeRecentPush(): Boolean {
        val recent = SystemClock.uptimeMillis() - lastPushAt < 1_000L
        if (recent) lastPushAt = 0L
        return recent
    }

    /**
     * 被推入下一页的那个页面登记「开始压暗 / 翻折」的动作，等新页第一帧画出（[notifyPushStarted]）再开始。
     * 为什么不在 onPause 就开始：新 Activity 创建 + 第一帧要占住主线程几十毫秒，这段时间里压暗 / 翻折的动画时钟已经在走，
     * 阻塞结束后的第一帧一下子跳过去 40~60%（实测侧推刚开始 33ms 内页面从不暗直接暗到六成），像顿了一下再突然变暗。
     * 只在主线程访问。
     */
    private var pendingPush: (() -> Unit)? = null

    internal fun deferPush(start: () -> Unit) {
        pendingPush = start
    }

    /**
     * 被推入下一页的页面（及其抽屉）登记「收回压暗 / 翻折」的动作，按推入层次入栈；最上面的页面 `finish()` 时（[notifyPopStarted]）立刻执行栈顶一层——
     * 与窗口的返回动画同一个起点。原来要等下层页面 onResume 才开始收回，比窗口动画晚几十毫秒，一级页面看起来「没有立刻抬起」。
     * 只在主线程访问。
     */
    // Seed tokens from the monotonic process start, so restored intents cannot claim a
    // new process's first push merely because both counters started at one.
    private val popLevels = PagePopLevels<Activity>(SystemClock.elapsedRealtimeNanos())

    internal fun registerPop(unfold: () -> Unit) {
        popLevels.register(unfold)
    }

    internal fun unregisterPop(unfold: () -> Unit) {
        popLevels.unregister(unfold)
    }

    /** 只有本次 push 真正打开的页面可绑定此层；重建时同 token 改绑新 Activity 实例。 */
    internal fun bindPushOwner(activity: Activity) {
        val token = activity.intent?.getLongExtra(PUSH_TOKEN_EXTRA, 0L) ?: return
        popLevels.bind(token, activity)
    }

    /** 栈顶的实际接收页面开始退出时收回下一层；直接打开的二级页不会误消费别人的层。 */
    fun notifyPopStarted(activity: Activity) {
        popLevels.pop(activity)
    }

    /** 新页第一帧已经画出（窗口侧推即将开始）：此刻才让被盖住的下层页面开始压暗 / 翻折，与侧推同一个起点。 */
    fun notifyPushStarted() {
        val start = pendingPush ?: return
        pendingPush = null
        start()
    }

    /** 推入转场的 ActivityOptions。 */
    fun pushOptions(activity: Activity): Bundle? =
        ActivityOptions.makeCustomAnimation(activity, PushEnter, PushExit).toBundle()

    /** 以侧推转场启动页面；启动失败时撤销压暗标记，不让随后无关的 onPause 误压暗。 */
    fun startPush(activity: Activity, intent: Intent) {
        val token = markPush()
        val pushIntent = Intent(intent).apply {
            if (token != null) putExtra(PUSH_TOKEN_EXTRA, token) else removeExtra(PUSH_TOKEN_EXTRA)
        }
        try {
            activity.startActivity(pushIntent, pushOptions(activity))
        } catch (e: RuntimeException) {
            lastPushAt = 0L
            if (token != null) popLevels.cancel(token)
            throw e
        }
    }

    /** 让系统在选择关闭转场时就能拿到本页的资源，包括刚从最近任务恢复的情况。 */
    internal fun registerCloseTransition(activity: Activity) {
        if (Build.VERSION.SDK_INT >= 34) {
            activity.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, PopEnter, PopExit)
        }
    }

    /** 老版本没有可预注册的关闭转场，只能在 finish 后覆盖这一次。 */
    @Suppress("DEPRECATION")
    fun applyPop(activity: Activity) {
        if (Build.VERSION.SDK_INT < 34) activity.overridePendingTransition(PopEnter, PopExit)
    }

    /** 尝试从任意 Context 找到 Activity。 */
    fun findActivity(context: Context): Activity? {
        var c: Context? = context
        while (c is android.content.ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return null
    }
}

/** One push owns one pop level. Registration alone never manufactures an unowned level. */
internal class PagePopLevels<O : Any>(initialToken: Long = 0L) {
    private class Level<O>(val token: Long) {
        var owner: O? = null
        val callbacks = ArrayList<() -> Unit>()
    }

    private var nextToken = initialToken
    private val levels = ArrayList<Level<O>>()

    fun push(): Long = (++nextToken).also { levels.add(Level(it)) }

    fun bind(token: Long, owner: O) {
        levels.firstOrNull { it.token == token }?.owner = owner
    }

    fun register(callback: () -> Unit) {
        levels.lastOrNull()?.callbacks?.add(callback)
    }

    fun unregister(callback: () -> Unit) {
        for (level in levels) level.callbacks.remove(callback)
    }

    fun cancel(token: Long) {
        levels.removeAll { it.token == token }
    }

    fun pop(owner: O) {
        val level = levels.lastOrNull() ?: return
        if (level.owner !== owner) return
        levels.removeAt(levels.lastIndex)
        for (callback in level.callbacks.toList()) callback()
    }
}

/** Uses the hosting Activity's page transition, falling back to a new task for non-Activity contexts. */
internal fun startActivityWithPageTransition(context: Context, intent: Intent) {
    val activity = AppLocaleController.findActivity(context)
    if (activity != null) {
        // 水平侧推：新页从右侧推入，上一级向左让开并压暗（见 ui/common/motion/PageTransitions.kt）
        PageTransitions.startPush(activity, intent)
    } else {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}

/**
 * 侧推时下层页面的「景深」：本页推入下一页（onPause）时进度 0 → 1，回到本页（onResume）时 1 → 0；
 * 压暗（[pageTransitionScrim]）、页面翻折（[depthFoldStack]）与抽屉的整体翻折（`motionSheetSurface`）共用这一个进度，
 * 所以永远同步。静止时为 0，不改变外观。
 */
@Stable
class PageDepth internal constructor() {
    /** 0 = 本页在最上层，1 = 被新页完全盖住；只在绘制 / 图层阶段读取。 */
    val progress = Animatable(0f)
}

/**
 * 抽屉窗口翻折的竖直枢轴：窗口高度的这一比例处。页面区取它自己图层的中线（0.5），两者近似同一个翻折平面
 * （页面区比窗口矮一个底栏，差别很小）。
 */
internal const val DepthPivotY = 0.47f

/**
 * 主界面里「页面区」（不含底部导航）用来翻折的景深：[MainActivity] 提供，主界面的页面区与抽屉读取。
 * 主界面的底栏不参与翻折——底栏样式与图标的运动一律不动，只随整个窗口一起左移与压暗。
 */
val LocalPageDepth = staticCompositionLocalOf<PageDepth?> { null }

/**
 * 侧推进场时压暗 / 翻折（下层页）与入场翻折（新页）起步时先垫的进度：动画的第一帧进度恒为 0、第二帧才开始动，
 * 而窗口的侧推从新页第一帧可见起就在走——不垫的话页面已经滑进来两三帧了倾角 / 压暗还纹丝不动，像「没有立刻抬起」。
 */
internal const val PushStartLead = 0.06f

/** 新页迟迟没有通知「第一帧已画出」时，压暗 / 翻折最迟在 onPause 之后这么久开始。 */
private const val PUSH_START_FALLBACK_MILLIS = 220L

/** 创建本页的景深状态，并随生命周期驱动：被新页盖住时渐深，回到最上层时渐浅。 */
@Composable
fun rememberPageDepth(): PageDepth {
    val depth = remember { PageDepth() }
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        // 只有「确实被推入过下一页」才在回到最上层时收回：普通的 onResume（首次进入、从后台回来）不空跑一段补间，
        // 「普通」档也完全不碰进度。该标记在 onPause 里同步置位，所以被推入的页面立即 finish、ON_RESUME 早于翻折动画第一帧时，
        // 新的收回动画仍会顶掉尚未开始的翻折，不会停在翻折态
        var pushed = false
        // 收回压暗 / 翻折：上层页面 finish()（[PageTransitions.notifyPopStarted]）或本页 onResume 时执行，只执行一次
        val unfold: () -> Unit = {
            if (pushed) {
                pushed = false
                scope.launch {
                    depth.progress.animateTo(
                        0f,
                        tween(MotionSprings.PushDurationMillis, easing = MotionSprings.PushEasing),
                    )
                }
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> if (PageTransitions.consumeRecentPush()) {
                    pushed = true
                    // 等新页第一帧画出再开始（见 PageTransitions.deferPush）；新页没有走 AppScreenScaffold（不会通知）或迟迟画不出来时按时间兜底
                    var started = false
                    val begin = {
                        if (pushed && !started) {
                            started = true
                            scope.launch {
                                // 与新页的入场翻折同样先垫一点起步进度（见 PushStartLead），两者每一帧的进度完全一致
                                depth.progress.snapTo(PushStartLead)
                                depth.progress.animateTo(
                                    1f,
                                    tween(MotionSprings.PushDurationMillis, easing = MotionSprings.PushEasing),
                                )
                            }
                        }
                    }
                    PageTransitions.deferPush(begin)
                    scope.launch {
                        delay(PUSH_START_FALLBACK_MILLIS)
                        begin()
                    }
                    PageTransitions.registerPop(unfold)
                }
                Lifecycle.Event.ON_RESUME -> if (pushed) {
                    // 上层页面 finish() 时已经提前收回过的，这里 unfold 是空操作
                    PageTransitions.unregisterPop(unfold)
                    unfold()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            PageTransitions.unregisterPop(unfold)
        }
    }
    return depth
}

/** 侧推时下层页面的压暗：静止时透明度为 0，不改变外观；只在绘制阶段读取进度。 */
@Composable
fun Modifier.pageTransitionScrim(depth: PageDepth): Modifier {
    val maxAlpha = pageDimMaxAlpha()
    // 绘制只关心最终颜色。减速末段相邻进度可能得到同一个 Color，不必为它再次使整窗绘制失效。
    val scrim by remember(depth, maxAlpha) {
        derivedStateOf(structuralEqualityPolicy()) {
            val a = depth.progress.value
            if (a > 0.001f) Color.Black.copy(alpha = maxAlpha * a) else Color.Unspecified
        }
    }
    return this.drawWithContent {
        drawContent()
        val color = scrim
        if (color != Color.Unspecified) drawRect(color)
    }
}

/**
 * 被盖住的页面的「翻折景深」：二级页面从右侧推入 → 页面绕左缘向后翻折一个小角度（带透视）并渐渐褪色；
 * 妙妙工具从左侧滑出 → 绕右缘翻。**翻折只有左右方向**——抽屉 / 弹窗从下方升起时页面不再上下翻折（用户明确要求去掉），
 * 改成「下沉」：整页以正中为枢轴缩小到约 0.94、正着往里沉，浮层收起 / 被下拉时同步升回（缩小量取自 mochi 底部面板录屏，见 [SinkShrink]），
 * 再加褪色（见下，[overlay]）。
 * 不模糊（实测全屏模糊让抽屉打开那几帧 GPU 耗时涨到约 20ms，掉帧、耗电），不裁圆角。
 *
 * 三层：最内层是抽屉 / 弹窗的**下沉**（缩放 + 下沉），中层是侧栏（绕右缘）的翻折，外层是**页面推入**的翻折（绕左缘）并负责褪色。
 * 在抽屉已经打开的前提下再进入二级页面，页面会整体绕左缘翻开
 * （抽屉窗口自己也同步翻折，见 `motionSheetSurface`），整个界面一起翻、一起褪色；褪色只做一次（取各来源的最大进度，不连乘）。
 * 只改绘制图层，进度为 0 时没有任何效果，停稳外观不变。「普通」档不加。挂在页面**内容**上（不含页面根背景）。
 *
 * @param overlay 抽屉 / 弹窗的景深句柄：驱动页面下沉与褪色，不翻折；null = 没有这个来源
 * @param panel 侧栏的景深句柄（绕右缘）
 * @param push 页面被推入下一页的景深（绕左缘）
 * @param desaturate 是否褪色：褪色用渲染效果，会让整个子树走一次离屏渲染；Web 首页（WebView）不加，免得和 Chromium 的离屏绘制打架
 */
fun Modifier.depthFoldStack(
    overlay: SheetBackdrop?,
    panel: SheetBackdrop?,
    push: PageDepth?,
    desaturate: Boolean = true,
    enter: PageEnter? = null,
): Modifier {
    if (!MotionMode.enhanced) return this
    val key = DepthFoldStackKey(overlay, panel, push, desaturate, enter)
    // 最内层：抽屉 / 弹窗升起时页面「下沉」——整页以正中为枢轴均匀缩小一点、正着往里沉，
    // 浮层收起 / 被下拉时同步升回。不翻折（用户明确要求去掉上下翻折，改成这个下沉上升），进度跟浮层的展开走
    val sink = LayerSource { p, density ->
        // 进度已按浮层的体量加权（SheetBackdrop.sink）；宽屏（平板 / 横屏）上限制两侧收进去的绝对距离，免得按比例缩得过头
        p.applySheetSink(overlay?.sink ?: 0f, density)
    }
    // 中层：侧栏（绕右缘）的翻折
    val side = LayerSource { p, density ->
        p.depthFold(panel?.cover ?: 0f, FoldAxis.Right, density)
    }
    // 外层：页面推入的翻折（绕左缘）+ 整体褪色
    val depth = LayerSource { p, density ->
        val c = push?.progress?.value ?: 0f
        val e = maxOf(c, overlay?.cover ?: 0f, panel?.cover ?: 0f)
        p.depthFold(c, FoldAxis.Left, density)
        // 褪色档位与翻折同一档（见 FoldSteps）：进度每帧都在变，但图层属性不变的帧不必重新合成（见 depthFold）
        if (desaturate && e > 0.01f) p.renderEffect = depthDesaturationAt(quantizeFold(e))
    }
    // 二级页原先先画下沉 / 翻折，再由外面的进场图层变换整组。保持这个次序，
    // 包括两层褪色同时有效的情况；仅共用内容录制、尺寸和缓存边界。
    return when {
        enter == null -> drawPhaseLayers(key, sink, side, depth)
        panel == null -> drawPhaseLayers(key, sink, depth, enter.foldSource())
        else -> drawPhaseLayers(key, sink, side, depth, enter.foldSource())
    }
}

/** [depthFoldStack] 的节点身份：来源句柄与褪色开关都没变就不重建节点。 */
private data class DepthFoldStackKey(
    val overlay: SheetBackdrop?,
    val panel: SheetBackdrop?,
    val push: PageDepth?,
    val desaturate: Boolean,
    val enter: PageEnter?,
)

/**
 * 页面推入的翻折（绕左缘）的图层变换，抽屉窗口自己也用它，保证整个界面在同一个翻折平面上：
 * [pivotFracY] 是竖直枢轴在本图层高度上的位置（页面区取 0.5，抽屉按窗口高度换算，见 `motionSheetSurface`）。
 */
internal fun GraphicsLayerScope.depthFoldLeft(p: Float, pivotFracY: Float, desaturate: Boolean = true, pivotFracX: Float = 0f) {
    if (p <= 0.01f) return
    // 与页面区同一个量化，两者每一帧的翻折角度完全一致
    val e = quantizeFold(p)
    cameraDistance = DepthCameraDistance * density
    transformOrigin = TransformOrigin(pivotFracX, pivotFracY)
    rotationY = FoldAxis.Left.degrees * e
    if (desaturate) renderEffect = depthDesaturationAt(e)
}

/**
 * 二级页面自己进出时的「入场翻折」：新页从右侧推入的那 0.4 秒里，它才是眼睛盯着的东西——
 * 之前的景深都落在被盖住的下层页面上，而下层页面在侧推开始后马上就被盖住、一闪而过，所以「进得快就看不到效果」。
 * 现在新页自己也带着效果进来：入场时绕右缘向后倾（先进来的左侧远离屏幕）并带一点褪色，随滑入逐渐转正、恢复颜色，
 * 落稳后完全平整；退出时原路倒放。进度与窗口的侧推同曲线同时长，不增加任何时间。
 */
@Stable
class PageEnter internal constructor(initial: Float = 0f) {
    /** 0 = 还没进来 / 已经退出，1 = 完全就位；只在图层阶段读取。Activity 被系统重建（旋转 / 折叠屏 / 字体变化）时从 1 开始，不重播入场。 */
    val progress = Animatable(initial)

    /**
     * 带帧时钟的协程作用域：页面组合里的 `rememberCoroutineScope()`，由 `AppScreenScaffold` 绑定、离开组合时解绑。
     * 退出倒放必须在它里面跑——Activity 的 `lifecycleScope` 没有 MonotonicFrameClock，
     * `Animatable.animateTo` 在那里会直接抛 IllegalStateException，把整个进程崩掉（返回时整个 App 重启）。
     */
    internal var scope: CoroutineScope? = null

    /** 在页面组合的作用域里补间到 [target]；系统关闭动画时保持完全就位，避免退出前闪出最大翻折。 */
    internal fun playTo(target: Float, durationMillis: Int) {
        scope?.launch {
            if (durationMillis <= 0) {
                progress.snapTo(1f)
            } else {
                progress.animateTo(target, tween(durationMillis, easing = MotionSprings.PushEasing))
            }
        }
    }
}

/** 新页入场时的最大后倾角度（度）。 */
private const val PageEnterDegrees = 14f

/** 挂在二级页面内容上：随 [enter] 进度翻折 / 褪色；进度满（就位）时没有任何效果。「普通」档不加。 */
internal fun Modifier.pageEnterFold(enter: PageEnter?): Modifier =
    if (enter == null || !MotionMode.enhanced) this else drawPhaseLayers(enter, enter.foldSource())

private fun PageEnter.foldSource() = LayerSource { p, density ->
    val rest = quantizeFold(1f - progress.value)
    if (rest > 0.01f) {
        p.cameraDistance = DepthCameraDistance * density
        p.pivotFracX = 1f
        p.pivotFracY = 0.5f
        p.rotationY = -PageEnterDegrees * rest
        p.renderEffect = depthDesaturationAt(rest)
    }
}

/** 翻折的轴：被盖住的页面绕「远离浮层的那条边」向后翻。只有左右，没有上下（用户明确要求去掉上下翻折）。 */
enum class FoldAxis(internal val pivotX: Float, internal val degrees: Float) {
    Left(0f, DepthFoldDegrees),
    Right(1f, -DepthFoldDegrees),
}

/** 翻折的最大角度（度）：页面侧推、抽屉、弹窗、侧栏共用。 */
private const val DepthFoldDegrees = 12f
private const val DepthCameraDistance = 14f

/**
 * 翻折进度的量化档数（每档的旋转约 0.4°）：进度每帧都在变，图层属性不变的帧页面不必重新合成。
 * 曾按 1/8 一档（每档 1.5°）量化，运动本身是「先快后慢」的减速曲线，最后一档（1.5°）要停留一百多毫秒再突然归零——
 * 看起来就是「快要抬起 / 快要落稳的时候顿一下才动」；现在档数加密到 1/32，最后一档不到半度，停留与归零都看不出来。
 */
private const val FoldSteps = 32f

private fun quantizeFold(p: Float): Float = kotlin.math.round(p.coerceIn(0f, 1f) * FoldSteps) / FoldSteps

/**
 * 页面「下沉」的最大缩小量与缩放枢轴（占页面高度的比例）：缩小量取自 mochi-compose 演示的「底部面板」录屏——
 * 面板升起时背后页面缩到约 0.942（宽 1149 → 1082px）。枢轴取页面**正中**：整个页面正着往里沉、四周均匀收进去。
 * 录屏里量出的不动点在高度 71% 处（顶部下沉、底部略上收），但那是整屏页面、底部被面板盖住；放到底栏固定的主界面里，
 * 偏下的枢轴看上去就是「整个界面向屏幕下方缩」（用户明确说不要），所以不照搬。只改绘制；浮层进度为 0 时没有任何效果，停稳外观不变。
 */
private const val SinkShrink = 0.058f
private const val SinkPivotY = 0.5f

/** 下沉时页面两侧最多收进去的距离（dp）：手机（约 360dp 宽）上 0.058 的缩放每侧只收约 10dp，不受限；平板 / 横屏宽度大，按比例会收进去几十 dp，在此封顶。 */
private const val SinkMaxInsetDp = 20f

/** 下沉进度按 1/64 一档量化（一档的缩放不到 0.1%、不到 1 像素）：弹簧尾巴上进度不变的帧不用重新合成。 */
private const val SinkSteps = 64f

private fun quantizeSink(p: Float): Float = kotlin.math.round(p.coerceIn(0f, 1f) * SinkSteps) / SinkSteps

/** 在下沉图层上应用浮层进度；宽屏按实际绘制宽度限制两边的缩进（每侧最多 20dp）。 */
internal fun LayerParams.applySheetSink(sink: Float, density: Float) {
    if (sink > 0.005f) {
        val maxShrink = if (width > 0f) minOf(SinkShrink, 2f * SinkMaxInsetDp * density / width) else SinkShrink
        val scale = 1f - maxShrink * quantizeSink(sink)
        scaleX = scale
        scaleY = scale
        pivotFracX = 0.5f
        pivotFracY = SinkPivotY
    }
}

/** 褪色的最大程度：饱和度从 1 降到 1 - 这个值。 */
private const val DepthFoldDesaturation = 0.5f
private const val DesaturationSteps = 20

/** 褪色效果按 5% 一档预先建好：进度每帧都在变，查表即可，不必每帧新建渲染效果。API 31 以下为空（只有压暗和翻折）。 */
private val DepthDesaturationEffects: Array<RenderEffect?> by lazy {
    Array(DesaturationSteps + 1) { i ->
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || i == 0) {
            null
        } else {
            val matrix = android.graphics.ColorMatrix().apply { setSaturation(1f - DepthFoldDesaturation * i / DesaturationSteps) }
            android.graphics.RenderEffect
                .createColorFilterEffect(android.graphics.ColorMatrixColorFilter(matrix))
                .asComposeRenderEffect()
        }
    }
}

private fun depthDesaturationAt(fold: Float): RenderEffect? =
    DepthDesaturationEffects[kotlin.math.round(fold * DesaturationSteps).toInt().coerceIn(0, DesaturationSteps)]

/** 提前建好褪色效果表（首次使用时一次建 20 个渲染效果，不要落在抽屉升起的第一帧里）。 */
internal fun prewarmDepthEffects() {
    DepthDesaturationEffects.size
}

/** 页面的左右翻折在绘制层生效；抽屉独立窗口的枢轴另算，但角度、透视和进度档位相同。 */
private fun LayerParams.depthFold(p: Float, axis: FoldAxis, density: Float) {
    if (p <= 0.01f) return
    cameraDistance = DepthCameraDistance * density
    pivotFracX = axis.pivotX
    pivotFracY = 0.5f
    rotationY = axis.degrees * quantizeFold(p)
}

/**
 * 全局动效环境：滚动容器的 Q 弹橡皮筋越界回弹（替代系统默认的拉伸 / 光晕效果）。
 * 放在每个 Activity 的根部；Dialog 内容沿用同一组合上下文，同样生效。
 */
@Composable
fun ProvideMotionEnvironment(content: @Composable () -> Unit) {
    val context = LocalContext.current
    // 首帧前同步读取动效档位，避免「普通」档用户启动时先闪一帧增强动效
    val motionState = remember(context) {
        AppSettingsStore.initMotionEnhanced(context)
        AppSettingsStore.motionEnhancedState
    }
    val enhanced by motionState.collectAsState()
    MotionMode.sync(enhanced)
    if (enhanced) {
        // 翻折景深的褪色效果表提前建好，不落在第一次抽屉 / 弹窗升起的那一帧里
        LaunchedEffect(Unit) { prewarmDepthEffects() }
    }
    // 触觉门面绑定到本页的触觉宿主（见 MotionHaptics）
    val haptic = LocalHapticFeedback.current
    DisposableEffect(haptic) {
        MotionHaptics.bind(haptic)
        onDispose { MotionHaptics.unbind(haptic) }
    }
    // 「普通」档保持系统默认的越界效果；始终保留同一个 Provider，切换档位不重建页面
    val overscroll = if (enhanced) MiuixOverscrollFactory else LocalOverscrollFactory.current
    CompositionLocalProvider(LocalOverscrollFactory provides overscroll) {
        // 主题揭开宿主：切换深浅色 / 外观主题时的整屏过渡（见 ThemeReveal.kt）；不改布局
        // 提示条槽位（页面根）：与主题揭开宿主叠放，提示条只在自己的矩形内拦截触摸
        Box(propagateMinConstraints = true) {
            ThemeRevealHost(content)
            NoticeHost(Modifier.matchParentSize())
        }
    }
}

/**
 * 从浮层（游戏操作抽屉、妙妙工具侧边栏）跳转到新页面时，浮层的收起要和侧推同步：新页面的第一帧通常要 ~200ms 才就绪，
 * 立即收起会先露出空的旧页面再开始侧推。返回的函数把 [action]（收起浮层）推迟到本页真正让位（onPause，
 * 即侧推开始）时执行。「普通」动效档立即执行（原版行为）。
 * 用生命周期观察器等待，不以固定超时推断侧推已发生；组合移除时同时撤销待执行动作。
 */
@Composable
fun rememberAfterPushStarts(): (action: () -> Unit) -> Unit {
    val owner = LocalLifecycleOwner.current
    val pending = remember(owner) { PendingPushAction() }
    DisposableEffect(owner, pending) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> pending.take()?.invoke()
                Lifecycle.Event.ON_DESTROY -> pending.take()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            pending.take()
        }
    }
    return remember(owner, pending) {
        { action ->
            pending.take()
            if (!MotionMode.enhanced || !owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                action()
            } else {
                pending.action = action
            }
        }
    }
}

private class PendingPushAction {
    var action: (() -> Unit)? = null
    /** 取出并清除待执行动作，使暂停、销毁或组合移除只消费一次。 */
    fun take(): (() -> Unit)? {
        return action.also { action = null }
    }
}

/** 页面被新页面盖住时的压暗上限：抽屉随页面压暗（SheetMotion）与页面自己（pageTransitionScrim）共用，保证同一暗度。 */
internal fun pageDimMaxAlpha(): Float =
    if (AppThemeColors.isDark || AppThemeColors.hasDarkBackdrop) 0.45f else 0.24f
