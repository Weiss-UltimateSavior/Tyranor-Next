package com.tyranor.next.ui.common.motion

import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import kotlin.math.roundToInt

/**
 * 修复「弹窗已经退场，灰色遮罩却还停留一会儿、期间点不动页面」：
 * Compose `Dialog` 的平台窗口自带背景变暗（dim）与系统进出场动画，窗口移除时系统再单独淡出一次 dim，
 * 而这段时间窗口仍拦截触摸。
 *
 * 在 `Dialog { }` 内容里调用：默认关闭该窗口的系统进出场动画，并让窗口 dim 跟随 [dimProgress]（0..1，通常就是弹窗自己的
 * 遮罩进度）同步淡入淡出，停稳时的总暗度与原来一致。关闭开始时调用 [passThroughTouches]，退场期间点击直接落到下层页面。
 * 这是缺陷修复，「普通」与「增强」动效档都生效。
 * 没有自绘进退场的原版弹窗可用 [preserveWindowAnimations] 保留平台效果，关闭时须同步清零 dim。
 *
 * @return 弹窗所在的平台窗口（取不到时为 null）
 */
@Composable
fun syncDialogWindow(preserveWindowAnimations: Boolean = false, dimProgress: () -> Float): Window? {
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window
    val originalAnimations = remember(window) { window?.attributes?.windowAnimations ?: 0 }
    val baseDim = remember(window) {
        val dim = window?.attributes?.dimAmount ?: 0f
        window?.setDimAmount(0f)
        dim
    }
    SideEffect {
        val animations = if (preserveWindowAnimations) originalAnimations else 0
        if (window != null && window.attributes.windowAnimations != animations) window.setWindowAnimations(animations)
    }
    LaunchedEffect(window) {
        if (window == null) return@LaunchedEffect
        // 暗度只有 8 位精度：弹簧尾段肉眼不可辨的微小变化不再触发一次窗口布局与 WMS 调用；两端仍精确落到 0 与 baseDim
        snapshotFlow { (dimProgress().coerceIn(0f, 1f) * baseDim * 255f).roundToInt() }.collect { level ->
            val fullLevel = (baseDim * 255f).roundToInt()
            window.setDimAmount(if (level >= fullLevel) baseDim else level / 255f)
        }
    }
    return window
}

/** 弹窗开始关闭时调用：窗口不再接收触摸，退场动画期间的点击直接交给下层页面。 */
fun Window?.passThroughTouches() {
    this?.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
}

/**
 * 关闭开始时同时交还焦点：系统返回手势发给有焦点的窗口。屏幕左缘右滑既是返回手势也是唤出侧边栏的手势，
 * 窗口退场期间若还占着焦点，这次手势会被正在关闭的窗口吞掉，无法重新唤出。
 */
fun Window?.releaseFocusForExit() {
    this?.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
}

/** 撤销 [passThroughTouches] 与 [releaseFocusForExit]：退场途中被重新打开时恢复正常交互。 */
fun Window?.restoreInteraction() {
    this?.clearFlags(
        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
    )
}
