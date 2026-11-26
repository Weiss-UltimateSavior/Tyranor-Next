package com.tyranor.next.ui.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickActionsDialogSessionTest {
    @Test fun initialSessionIsClosedAtZeroSequence() {
        val session = QuickActionsDialogSession()
        assertNull(session.targetUri)
        assertEquals(0L, session.openSequence)
        assertFalse(session.dismissIfCurrent(0L))
    }

    @Test fun openAdvancesSequenceAndMatchingDismissClosesDialog() {
        val session = QuickActionsDialogSession()
        val firstSeq = session.open("game://alpha")
        assertEquals(1L, firstSeq)
        assertEquals("game://alpha", session.targetUri)

        assertTrue(session.dismissIfCurrent(firstSeq))
        assertNull(session.targetUri)
        // 同序号重复回调不得再次生效
        assertFalse(session.dismissIfCurrent(firstSeq))
    }

    @Test fun reopeningSameGameDuringExitIgnoresStaleDismissCallback() {
        val session = QuickActionsDialogSession()
        val firstSeq = session.open("game://alpha")

        // 退场途中（旧关闭回调尚未交付前）再次长按同一张卡片
        val secondSeq = session.open("game://alpha")
        assertEquals(firstSeq + 1L, secondSeq)
        assertEquals("game://alpha", session.targetUri)

        // 旧弹窗实例在退场收尾/销毁时交付的过期回调必须被忽略
        assertFalse(session.dismissIfCurrent(firstSeq))
        assertEquals("game://alpha", session.targetUri)
        assertEquals(secondSeq, session.openSequence)

        // 只有新实例自己的关闭回调才会清空目标
        assertTrue(session.dismissIfCurrent(secondSeq))
        assertNull(session.targetUri)
    }

    @Test fun reopeningDifferentGameDuringExitSwitchesTargetAndIgnoresStaleDismiss() {
        val session = QuickActionsDialogSession()
        val firstSeq = session.open("game://alpha")
        val secondSeq = session.open("game://beta")

        assertEquals("game://beta", session.targetUri)
        assertFalse(session.dismissIfCurrent(firstSeq))
        assertEquals("game://beta", session.targetUri)

        assertTrue(session.dismissIfCurrent(secondSeq))
        assertNull(session.targetUri)
    }

    @Test fun normalExitBeforeNextLongPressCompletesThenReopensCleanly() {
        val session = QuickActionsDialogSession()
        val firstSeq = session.open("game://alpha")
        assertTrue(session.dismissIfCurrent(firstSeq))
        assertNull(session.targetUri)

        val secondSeq = session.open("game://alpha")
        assertEquals(2L, secondSeq)
        assertEquals("game://alpha", session.targetUri)
        assertFalse(session.dismissIfCurrent(firstSeq))
        assertEquals("game://alpha", session.targetUri)
    }

    @Test fun clearIfTargetOnlyClearsMatchingGameAndGuardsSubsequentReopen() {
        val session = QuickActionsDialogSession()
        val firstSeq = session.open("game://alpha")

        assertFalse(session.clearIfTarget("game://beta"))
        assertEquals("game://alpha", session.targetUri)

        assertTrue(session.clearIfTarget("game://alpha"))
        assertNull(session.targetUri)

        val secondSeq = session.open("game://beta")
        assertFalse(session.dismissIfCurrent(firstSeq))
        assertEquals("game://beta", session.targetUri)
        assertEquals(2L, secondSeq)
    }

    @Test fun clearIfMissingClosesOnlyWhenTargetNoLongerInLibrary() {
        val session = QuickActionsDialogSession()
        session.open("game://alpha")

        assertFalse(session.clearIfMissing { it == "game://alpha" })
        assertEquals("game://alpha", session.targetUri)

        assertTrue(session.clearIfMissing { it == "game://beta" })
        assertNull(session.targetUri)
    }
}
