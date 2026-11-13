package com.tyranor.next.ui.common.motion

import android.os.SystemClock

/**
 * 「手指意图」的进程级记录（只在主线程读写）：最近一次触摸的时刻。
 * 触觉门面 [MotionHaptics] 据此判断事件是不是「手指引起的」（后台完成、自动检测不震）。
 * `pressSquish`、主题揭开宿主、侧栏手势、抽屉拖动在手指按下 / 拖动时调用 [noteTouch]。
 */
object MotionIntent {
    /** 距最近一次触摸多久内的事件才算「手指引起的」。 */
    const val TOUCH_WINDOW_MILLIS: Long = 1500L

    private var lastTouchAt: Long = 0L

    /** 记录一次触摸（任何控件的按下）。 */
    fun noteTouch() {
        lastTouchAt = SystemClock.uptimeMillis()
    }

    /** 距最近一次触摸是否不超过 [windowMillis]。 */
    fun touchedRecently(windowMillis: Long = TOUCH_WINDOW_MILLIS): Boolean =
        SystemClock.uptimeMillis() - lastTouchAt <= windowMillis
}
