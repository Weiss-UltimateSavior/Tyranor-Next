package com.tyranor.next.ui.common.motion

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
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
import com.tyranor.next.R
import com.tyranor.next.theme.AppThemeColors
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.utils.MiuixOverscrollFactory

/**
 * 独立 Activity 页面之间的转场：水平侧推（新页从右侧推入、上一级向左让开 30% 并压暗；返回原路倒放）。
 *
 * 窗口动画只能用插值器，这里用 [MotionSprings.PushEasing]（拟合 0.42s 临界阻尼弹簧）；
 * 上一级页面的压暗由该页自己的 [pageTransitionScrim] 在 Compose 里同步绘制。
 */
object PageTransitions {
    // 「增强」档为水平侧推；「普通」档沿用原版的上下翻页（进入向上、返回向下）
    val PushEnter: Int get() = if (MotionMode.enhanced) R.anim.page_push_enter else R.anim.page_slide_in_from_bottom
    val PushExit: Int get() = if (MotionMode.enhanced) R.anim.page_push_exit else R.anim.page_slide_out_to_top
    val PopEnter: Int get() = if (MotionMode.enhanced) R.anim.page_pop_enter else R.anim.page_slide_in_from_top
    val PopExit: Int get() = if (MotionMode.enhanced) R.anim.page_pop_exit else R.anim.page_slide_out_to_bottom

    @Volatile
    private var lastPushAt: Long = 0L

    /** 记一次「正在推入下一页」，供当前页在 onPause 时压暗（「普通」档不压暗）。 */
    fun markPush() {
        lastPushAt = if (MotionMode.enhanced) SystemClock.uptimeMillis() else 0L
    }

    internal fun consumeRecentPush(): Boolean {
        val recent = SystemClock.uptimeMillis() - lastPushAt < 1_000L
        if (recent) lastPushAt = 0L
        return recent
    }

    /** 推入转场的 ActivityOptions。 */
    fun pushOptions(activity: Activity): Bundle? =
        ActivityOptions.makeCustomAnimation(activity, PushEnter, PushExit).toBundle()

    /** 以侧推转场启动页面；启动失败时撤销压暗标记，不让随后无关的 onPause 误压暗。 */
    fun startPush(activity: Activity, intent: Intent) {
        markPush()
        try {
            activity.startActivity(intent, pushOptions(activity))
        } catch (e: RuntimeException) {
            lastPushAt = 0L
            throw e
        }
    }

    /** 在 Activity.finish() 之后调用：返回时原路倒放。 */
    @Suppress("DEPRECATION")
    fun applyPop(activity: Activity) {
        activity.overridePendingTransition(PopEnter, PopExit)
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

/**
 * 侧推时下层页面的压暗：本页推入下一页（onPause）时随转场渐暗，回到本页（onResume）时随返回渐亮。
 * 静止时透明度为 0，不改变外观；只在绘制阶段读取动画值。
 */
@Composable
fun Modifier.pageTransitionScrim(): Modifier {
    val dim = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> if (PageTransitions.consumeRecentPush()) {
                    scope.launch {
                        dim.animateTo(
                            1f,
                            tween(MotionSprings.PushDurationMillis, easing = MotionSprings.PushEasing),
                        )
                    }
                }
                // 无条件收回：被推入的页面立即 finish 时，ON_RESUME 可能早于压暗动画的第一帧，
                // 此时 dim 仍为 0；新的 animateTo 会取消尚未开始的压暗，避免停在暗态
                Lifecycle.Event.ON_RESUME -> {
                    scope.launch {
                        dim.animateTo(
                            0f,
                            tween(MotionSprings.PushDurationMillis, easing = MotionSprings.PushEasing),
                        )
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val maxAlpha = if (AppThemeColors.isDark || AppThemeColors.hasDarkBackdrop) 0.4f else 0.18f
    return this.drawWithContent {
        drawContent()
        val a = dim.value
        if (a > 0.001f) drawRect(Color.Black.copy(alpha = maxAlpha * a))
    }
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
    // 「普通」档保持系统默认的越界效果；始终保留同一个 Provider，切换档位不重建页面
    val overscroll = if (enhanced) MiuixOverscrollFactory else LocalOverscrollFactory.current
    CompositionLocalProvider(LocalOverscrollFactory provides overscroll, content = content)
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
    fun take(): (() -> Unit)? {
        return action.also { action = null }
    }
}
