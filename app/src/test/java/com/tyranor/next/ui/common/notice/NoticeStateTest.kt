package com.tyranor.next.ui.common.notice

import com.tyranor.next.ui.common.notice.AppNotice.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NoticeStateTest {
    private val clock = FakeClock()
    private val fallbacks = mutableListOf<String>()
    private val state = NoticeState(clock) {}
    private val root = host()
    private var nextId = 1L

    private fun host(page: Any = Any(), overlay: Boolean = false, cover: Boolean = false, resumed: Boolean = true) =
        NoticeHostEntry(page, overlay, cover).also { state.register(it, resumed) }

    private fun show(text: String, kind: Kind = Kind.Info, dwell: Long = 2000) =
        Notice(nextId++, text, kind, dwell) { fallbacks.add(text) }.also(state::show)

    private val draw get() = checkNotNull(state.render)

    private fun owns(entry: NoticeHostEntry, id: Long, exiting: Boolean) =
        state.render?.let { it.host === entry && it.id == id && it.exiting == exiting } == true

    private fun tap() {
        val hold = state.beginHold(draw)!!
        state.endHold(hold, dismiss = true)
    }

    @Test fun heldAndRepeatedNoticeStillLeavesAt350ms() {
        show("A")
        val hold = state.beginHold(draw)!!
        clock.advance(349)
        show("A")
        clock.advance(1)
        state.setResumed(root, false)
        assertTrue(state.exiting)
        assertSame(root, state.drawing)
        assertFalse(state.ownsHold(hold))
        clock.advance(260)
        assertNull(state.displayed)
        assertTrue(fallbacks.isEmpty())
    }

    @Test fun justIssuedNoticeCarriesWithoutCountingTimeBetweenWindows() {
        val a = show("A")
        clock.advance(349)
        state.setResumed(root, false)
        clock.advance(1100)
        val next = host()
        assertTrue(owns(next, a.id, false))
        clock.advance(1650)
        assertFalse(state.exiting)
        clock.advance(1)
        assertTrue(state.exiting)
        assertTrue(fallbacks.isEmpty())
    }

    @Test fun sameNoticeKeepsHoldAndUsesNewDurationWithoutAnotherEntrance() {
        val a = show("A")
        val hold = state.beginHold(draw)!!
        state.setAppear(draw, 0.8f)
        show("A", dwell = 3500)
        clock.advance(10_000)
        assertEquals(0, clock.wakes)
        assertTrue(state.ownsHold(hold))
        assertEquals(0.8f, state.appear, 0f)
        assertEquals(1, state.bounceSeq)
        state.endHold(hold, dismiss = false)
        clock.advance(3499)
        assertTrue(owns(root, a.id, false))
        clock.advance(1)
        assertTrue(state.exiting)
    }

    @Test fun releasingOldPressCannotCloseOrUnpauseReplacement() {
        show("A")
        val old = state.beginHold(draw)!!
        val b = show("B")
        val fresh = state.beginHold(draw)!!
        state.endHold(old, dismiss = true)
        state.endHold(old, dismiss = false)
        clock.advance(3000)
        assertTrue(state.ownsHold(fresh))
        assertTrue(owns(root, b.id, false))
        state.endHold(fresh, dismiss = true)
        assertTrue(state.exiting)
    }

    @Test fun dialogHandoffCancelsHoldWithGraceAndRejectsLateInputEvenAfterHandback() {
        val a = show("A")
        clock.advance(1950)
        val old = state.beginHold(draw)!!
        val dialog = host(root.page, overlay = true)
        assertTrue(owns(dialog, a.id, false))
        state.unregister(dialog)
        val fresh = state.beginHold(draw)!!
        state.endHold(old, dismiss = true)
        state.endHold(fresh, dismiss = false)
        clock.advance(799)
        assertFalse(state.exiting)
        clock.advance(1)
        assertTrue(state.exiting)
    }

    @Test fun pausedDwellProtectsStrongNoticeAndQueueDropsOnlyOldest() {
        val strong = show("error", Kind.Failure, 2800)
        clock.advance(1199)
        val hold = state.beginHold(draw)!!
        clock.advance(2000)
        listOf("one", "two", "three").forEach { show(it) }
        assertTrue(owns(root, strong.id, false))
        state.endHold(hold, dismiss = true)
        assertEquals("two", state.displayed?.text)
        clock.advance(2000)
        assertEquals("three", state.displayed?.text)
        tap()
        assertTrue(state.exiting)
    }

    @Test fun weakNoticeCanReplaceAtProtectionBoundary() {
        show("error", Kind.Limit)
        clock.advance(1200)
        val b = show("B")
        assertTrue(owns(root, b.id, false))
    }

    @Test fun directReplacementAt1200msNeverReplaysAnOlderQueuedMessage() {
        show("failure", Kind.Failure, 2800)
        clock.advance(100)
        show("saved", Kind.Success)
        clock.advance(100)
        show("outdated status")
        clock.advance(1000)
        val latest = show("saved", Kind.Success)
        assertEquals(latest.id, draw.id)
        tap()
        assertTrue(state.exiting)
        assertEquals(latest.id, draw.id)
    }

    @Test fun protectedQueueKeepsDistinctKindsInOrderOfLastSend() {
        show("failure", Kind.Failure, 2800)
        show("saved", Kind.Success)
        val info = show("saved", Kind.Info)
        show("saved", Kind.Success)
        val latest = show("saved", Kind.Success, dwell = 3500)
        tap()
        assertEquals(info.id, draw.id)
        tap()
        assertEquals(latest.id, draw.id)
        clock.advance(3499)
        assertFalse(state.exiting)
        clock.advance(1)
        assertTrue(state.exiting)
    }

    @Test fun handbackRejectsOriginalPagesAnimationEvenWithSameNoticeAndPhase() {
        val a = show("A", Kind.Failure)
        val first = draw
        val dialog = host(root.page, overlay = true)
        state.setAppear(draw, 0.65f)
        state.unregister(dialog)
        assertTrue(owns(root, a.id, false))
        state.setAppear(first, 0f) // 交接前已排入原窗口的第一帧。
        assertEquals(0.65f, state.appear, 0f)
        assertNull(state.beginHold(first))
        assertFalse(state.takeShake(first))
        assertTrue(state.takeShake(draw))
        state.setAppear(draw, 1f)
        assertEquals(1f, state.appear, 0f)

        tap()
        val firstExit = draw
        val overlay = host(root.page, overlay = true)
        state.setDepart(draw, 0.7f)
        state.unregister(overlay)
        assertTrue(owns(root, a.id, true))
        state.setDepart(firstExit, 0f)
        state.finishExit(firstExit)
        assertEquals(0.7f, state.depart, 0f)
        assertEquals(a.id, draw.id)
        state.finishExit(draw)
        assertNull(state.displayed)
    }

    @Test fun oldFadeCannotWriteOrClearNewNoticeInSameOrDifferentWindow() {
        show("A")
        state.setAppear(draw, 1f)
        tap()
        val exitA = draw
        state.setDepart(exitA, 0.4f)
        val b = show("B")
        assertEquals(0.4f, state.depart, 0f) // 原窗口从淡出中的位置折返。
        state.finishExit(exitA)
        assertTrue(owns(root, b.id, false))
        clock.advance(350)
        state.setResumed(root, false)
        val exitB = draw
        val next = host()
        assertSame(root, state.drawing)
        val c = show("C")
        state.setDepart(exitB, 1f)
        state.setAppear(exitB, 1f)
        state.finishExit(exitB)
        clock.advance(261)
        assertTrue(owns(next, c.id, false))
        assertEquals(0f, state.appear, 0f)
        assertEquals(0f, state.depart, 0f)
    }

    @Test fun latePauseAndDisposeCannotDismissAnotherPagesNotice() {
        show("A")
        clock.advance(350)
        val next = host()
        val b = show("B")
        clock.advance(500)
        state.setResumed(root, false)
        state.unregister(root)
        assertTrue(owns(next, b.id, false))
    }

    @Test fun foregroundFadeContinuesInDialogAndOnlyItsOwnerCanFinish() {
        val a = show("A")
        tap()
        val exit = draw
        state.setDepart(exit, 0.3f)
        val dialog = host(root.page, overlay = true)
        clock.advance(500) // 系统调慢动画时，前台没有固定的挂钟超时。
        state.setDepart(exit, 0.9f)
        state.finishExit(exit)
        assertEquals(0.3f, state.depart, 0f)
        assertTrue(owns(dialog, a.id, true))
        state.finishExit(draw)
        assertNull(state.displayed)
        assertEquals(0, clock.wakes)
    }

    @Test fun backgroundCoverDoesNotDiscardAndFrontCoverNeverResurrects() {
        val a = show("A")
        val sheet = host(overlay = true, cover = true, resumed = false)
        assertTrue(owns(root, a.id, false))
        state.setResumed(sheet, true)
        // 页面根可能晚于同一窗口里的抽屉登记。
        host(sheet.page)
        assertFalse(state.canPresent)
        assertNull(state.displayed)
        state.unregister(sheet)
        assertTrue(state.canPresent)
        assertNull(state.displayed)
        assertNull(clock.at)
    }

    @Test fun absentHostFallsBackOnceWithPendingQueueAndIdleHasNoWake() {
        val a = show("failure", Kind.Failure, 2800)
        show("queued")
        state.unregister(root)
        clock.advance(1499)
        assertEquals(a.id, state.displayed?.id)
        assertTrue(fallbacks.isEmpty())
        clock.advance(1)
        assertEquals(listOf("failure", "queued"), fallbacks)
        assertNull(state.displayed)
        state.unregister(host())
        clock.advance(60_000)
        assertEquals(1, clock.wakes)
        assertNull(clock.at)
    }

    private class FakeClock : NoticeClock {
        private var time = 0L
        var wakes = 0
        var at: Long? = null
        private var pending: Runnable? = null

        override fun now() = time

        override fun post(at: Long, task: Runnable) {
            assertNull(pending)
            this.at = at
            pending = task
        }

        override fun remove(task: Runnable) {
            assertSame(pending, task)
            pending = null
            at = null
        }

        fun advance(delta: Long) {
            val end = time + delta
            while ((at ?: Long.MAX_VALUE) <= end) {
                time = at!!
                val next = pending!!
                at = null
                pending = null
                wakes++
                next.run()
            }
            time = end
        }
    }
}
