package com.tyranor.next.ui.common.notice

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

internal data class Notice(
    val id: Long,
    val text: String,
    val kind: AppNotice.Kind,
    val dwellMillis: Long,
    val fallback: () -> Unit,
) {
    val strong: Boolean get() = kind == AppNotice.Kind.Failure || kind == AppNotice.Kind.Limit

    fun sameMessage(other: Notice) = text == other.text && kind == other.kind
}

internal interface NoticeClock {
    fun now(): Long
    fun post(at: Long, task: Runnable)
    fun remove(task: Runnable)
}

internal class NoticeHostEntry(val page: Any, val overlay: Boolean, val cover: Boolean) {
    var resumed = false
}

/** 一次绘制权交接。即使同一提示回到同一窗口，这个实例也不会复用。 */
internal class NoticeDrawing(val host: NoticeHostEntry, val id: Long, val exiting: Boolean)

/** 主线程状态。宿主交接只换绘制者；离页看已显示的时间，自动关闭只看未按住时的剩余驻留。 */
internal open class NoticeState(private val clock: NoticeClock, private val onPresent: (Notice) -> Unit) {
    private val hosts = mutableListOf<NoticeHostEntry>()
    private val queue = ArrayDeque<Notice>()
    private var top: NoticeHostEntry? = null
    val canPresent: Boolean get() = top?.cover == false

    var render by mutableStateOf<NoticeDrawing?>(null)
        private set
    val drawing get() = render?.host
    val exiting get() = render?.exiting == true
    // 退场时保留原内容，只有 drawing 可以写动画的进度或结束这次退场。
    var displayed by mutableStateOf<Notice?>(null)
        private set
    var appear by mutableFloatStateOf(0f)
        private set
    var depart by mutableFloatStateOf(0f)
        private set
    var bounceSeq by mutableIntStateOf(0)
        private set
    var pendingShake: Long? = null
        private set

    private var visibleMillis = 0L
    private var visibleSince = 0L
    private var remaining = 0L
    private var held: Hold? = null
    internal class Hold

    // 至多一个唤醒：正在显示时是驻留截止时间，无宿主时是 Toast 回退，淡出时是窗口停止绘帧的兜底。
    private var deadline: Long? = null
    private val wake = Runnable {
        val at = deadline
        if (at != null && clock.now() >= at) {
            deadline = null
            when {
                exiting -> clear()
                drawing == null -> {
                    val pending = listOfNotNull(displayed) + queue
                    clear()
                    pending.forEach { it.fallback() }
                }
                else -> advance()
            }
        }
    }

    private fun arm(at: Long?) {
        if (deadline == at) return
        if (deadline != null) clock.remove(wake)
        deadline = at
        if (at != null) clock.post(at, wake)
    }

    fun register(entry: NoticeHostEntry, resumed: Boolean) {
        entry.resumed = resumed
        val lastOnPage = hosts.indexOfLast { it.page === entry.page }
        if (lastOnPage < 0) hosts.add(entry) else hosts.add(lastOnPage + 1, entry)
        updateTop()
    }

    fun unregister(entry: NoticeHostEntry) {
        hosts.remove(entry)
        updateTop()
        if (drawing === entry && exiting) clear()
    }

    fun setResumed(entry: NoticeHostEntry, resumed: Boolean) {
        if (entry.resumed == resumed) return
        // 同一 Activity 的页面、弹窗与抽屉共用生命周期，只处理一次；旧页面迟到的 PAUSE 不收新页面的提示。
        if (!resumed && drawing?.page === entry.page) leaveIfAlreadyShown(clock.now())
        val pageHosts = hosts.filter { it.page === entry.page }
        pageHosts.forEach { it.resumed = resumed }
        if (resumed) {
            hosts.removeAll(pageHosts)
            hosts.addAll(pageHosts)
        }
        updateTop()
    }

    private fun updateTop() {
        val page = hosts.lastOrNull { it.resumed }?.page
        // 同页的弹窗 / 抽屉总在页面根上面，即使页面根的 DisposableEffect 登记得更晚。
        val next = hosts.lastOrNull { it.resumed && it.page === page && it.overlay }
            ?: hosts.lastOrNull { it.resumed }
        if (top === next) return
        top = next
        val notice = displayed ?: return
        if (next?.cover == true) {
            clear()
            return
        }
        val now = clock.now()
        val old = drawing
        if (next != null && old != null && next.page !== old.page) leaveIfAlreadyShown(now)
        if (exiting) {
            // 离页时留在原窗口淡出；同页的窗口交接则可以接着画原内容。
            if (next != null && next.page === old?.page) {
                if (next !== old) render = NoticeDrawing(next, notice.id, true)
                arm(null)
            } else if (deadline == null) {
                arm(now + LINGER_MILLIS)
            }
            return
        }

        if (drawing != null) {
            visibleMillis += now - visibleSince
            remaining = remainingAt(now)
            if (held != null) remaining = remaining.coerceAtLeast(AFTER_HOLD_MIN_MILLIS)
        }
        held = null
        render = next?.let { NoticeDrawing(it, notice.id, false) }
        visibleSince = now
        arm(now + if (next == null) ORPHAN_MILLIS else remaining)
    }

    private fun remainingAt(now: Long): Long =
        if (held != null || drawing == null) remaining else ((deadline ?: now) - now).coerceAtLeast(0L)

    private fun leaveIfAlreadyShown(now: Long) {
        if (!exiting && visibleMillis + (now - visibleSince) >= CARRY_MILLIS) {
            queue.clear()
            startExit()
        }
    }

    fun show(notice: Notice) {
        val current = displayed
        if (current != null && !exiting) {
            if (current.sameMessage(notice)) {
                // 保留内容/手势身份和首次显示时间；这次重发可以有不同的 SHORT/LONG。
                displayed = notice.copy(id = current.id)
                remaining = notice.dwellMillis
                if (held == null) arm(clock.now() + remaining)
                bounceSeq++
                return
            }
            if (current.strong && !notice.strong && current.dwellMillis - remainingAt(clock.now()) < STRONG_MIN_MILLIS) {
                // 同一待发消息保留最近一次（含时长），按最后发送的先后出队。
                queue.removeAll { it.sameMessage(notice) }
                if (queue.size == QUEUE_LIMIT) queue.removeFirst()
                queue.addLast(notice)
                return
            }
        }
        // 新到的提示直接接管时，先前受保护期积压的消息已经过时，不能在它之后倒序重放。
        // 正常从队首接续走 advance()，其余尚未展示的后继仍按 FIFO 保留。
        queue.clear()
        present(notice)
    }

    private fun present(notice: Notice) {
        if (drawing !== top) resetProgress()
        render = top?.let { NoticeDrawing(it, notice.id, false) }
        displayed = notice
        held = null
        visibleSince = clock.now()
        visibleMillis = 0L
        remaining = notice.dwellMillis
        pendingShake = notice.id.takeIf { notice.strong }
        arm(visibleSince + remaining)
        onPresent(notice)
    }

    fun beginHold(draw: NoticeDrawing): Hold? {
        if (render !== draw || draw.exiting || held != null) return null
        remaining = remainingAt(clock.now())
        arm(null)
        return Hold().also { held = it }
    }

    fun ownsHold(hold: Hold) = held === hold

    fun endHold(hold: Hold, dismiss: Boolean) {
        if (!ownsHold(hold)) return
        held = null
        if (dismiss) advance() else {
            remaining = remaining.coerceAtLeast(AFTER_HOLD_MIN_MILLIS)
            arm(clock.now() + remaining)
        }
    }

    private fun advance() {
        val next = queue.removeFirstOrNull()
        if (next == null) startExit() else present(next)
    }

    private fun startExit() {
        val draw = render ?: return
        held = null
        render = NoticeDrawing(draw.host, draw.id, true)
        arm(null) // 前台淡出由动画完成，不用 260ms 截断系统调慢的动画。
    }

    fun setAppear(draw: NoticeDrawing, value: Float) {
        if (render === draw && !draw.exiting) appear = value
    }

    fun setDepart(draw: NoticeDrawing, value: Float) {
        if (render === draw) depart = value
    }

    fun finishExit(draw: NoticeDrawing) {
        if (render === draw && draw.exiting) clear()
    }

    fun takeShake(draw: NoticeDrawing): Boolean {
        if (render !== draw || draw.exiting || pendingShake != draw.id) return false
        pendingShake = null
        return true
    }

    fun clear() {
        arm(null)
        held = null
        queue.clear()
        pendingShake = null
        render = null
        displayed = null
        resetProgress()
    }

    private fun resetProgress() {
        appear = 0f
        depart = 0f
    }

    private companion object {
        const val CARRY_MILLIS = 350L
        const val ORPHAN_MILLIS = 1500L
        const val LINGER_MILLIS = 260L
        const val STRONG_MIN_MILLIS = 1200L
        const val AFTER_HOLD_MIN_MILLIS = 800L
        const val QUEUE_LIMIT = 2
    }
}
