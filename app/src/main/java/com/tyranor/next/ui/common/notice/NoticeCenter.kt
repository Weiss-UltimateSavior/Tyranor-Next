package com.tyranor.next.ui.common.notice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import com.tyranor.next.ui.common.motion.MotionHaptics
import com.tyranor.next.ui.common.motion.MotionMode

private object MainNoticeClock : NoticeClock {
    val handler = Handler(Looper.getMainLooper())
    override fun now() = SystemClock.uptimeMillis()
    override fun post(at: Long, task: Runnable) { handler.postAtTime(task, at) }
    override fun remove(task: Runnable) { handler.removeCallbacks(task) }
}

/** 所有窗口共用一个提示状态和一个主线程闹钟。 */
internal object NoticeCenter : NoticeState(MainNoticeClock, { notice ->
    if (!notice.strong) MotionHaptics.perform(MotionHaptics.Kind.Tick)
}) {
    private var nextId = 1L

    fun show(context: Context, text: CharSequence, duration: Int, kind: AppNotice.Kind) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            MainNoticeClock.handler.post { show(context, text, duration, kind) }
            return
        }
        if (!MotionMode.enhanced || !canPresent) {
            Toast.makeText(context, text, duration).show()
            return
        }
        val appContext = context.applicationContext ?: context
        val base = if (duration == Toast.LENGTH_LONG) 3500L else 2000L
        val extra = if (kind == AppNotice.Kind.Failure) 800L else 0L
        val dwell = maxOf(base, 1200L + 55L * text.length).coerceAtMost(5000L) + extra
        show(Notice(nextId++, text.toString(), kind, dwell) { Toast.makeText(appContext, text, duration).show() })
    }
}
