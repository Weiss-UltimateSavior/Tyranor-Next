package com.tyranor.next.ui.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShelfCatalogSessionTest {
    @Test fun initialSessionIsClosedAtZeroSequence() {
        val session = ShelfCatalogSession()
        assertFalse(session.isOpen)
        assertEquals(0L, session.openSequence)
        assertNull(session.dismiss(0L))
        assertFalse(session.isOpen)
    }

    @Test fun requestOpenWhenClosedOpensAtNextSequence() {
        val session = ShelfCatalogSession()
        assertTrue(session.requestOpen("game://alpha"))
        assertTrue(session.isOpen)
        assertEquals(1L, session.openSequence)
    }

    @Test fun normalDismissClosesWithoutReopen() {
        val session = ShelfCatalogSession()
        session.requestOpen("game://alpha")
        assertNull(session.dismiss(session.openSequence))
        assertFalse(session.isOpen)
        // 同一序号的重复回调不再生效
        assertNull(session.dismiss(1L))
        assertFalse(session.isOpen)
    }

    @Test fun longPressDuringExitIsDeferredNotSwallowed() {
        val session = ShelfCatalogSession()
        session.requestOpen("game://alpha")
        val firstSequence = session.openSequence

        // 退场期间（旧覆盖层还没交付收尾）再次长按：不是直接打开，而是登记
        assertFalse(session.requestOpen("game://beta"))
        assertTrue(session.isOpen)
        assertEquals(firstSequence, session.openSequence)

        // 旧覆盖层收尾：不关闭，立刻接着开新一轮（序号 +1），并交回登记的锚点
        assertEquals("game://beta", session.dismiss(firstSequence))
        assertTrue(session.isOpen)
        assertEquals(firstSequence + 1L, session.openSequence)
    }

    @Test fun staleDismissAfterReopenIsIgnored() {
        val session = ShelfCatalogSession()
        session.requestOpen("game://alpha")
        val firstSequence = session.openSequence
        session.requestOpen("game://beta")
        session.dismiss(firstSequence)
        val secondSequence = session.openSequence

        // 旧实例销毁时晚到的收尾回调（旧序号）不得把刚重开的藏书阁关掉，也不得重复触发重开
        assertNull(session.dismiss(firstSequence))
        assertTrue(session.isOpen)
        assertEquals(secondSequence, session.openSequence)

        // 新实例自己正常收尾才会关闭，且不再重开
        assertNull(session.dismiss(secondSequence))
        assertFalse(session.isOpen)
    }

    @Test fun lastRequestedAnchorWinsWhenSeveralLongPressesArriveDuringExit() {
        val session = ShelfCatalogSession()
        session.requestOpen("game://alpha")
        val firstSequence = session.openSequence

        assertFalse(session.requestOpen("game://beta"))
        assertFalse(session.requestOpen("game://gamma"))

        assertEquals("game://gamma", session.dismiss(firstSequence))
        // 登记只消费一次：新一轮收尾时没有再登记就正常关闭
        assertNull(session.dismiss(session.openSequence))
        assertFalse(session.isOpen)
    }

    @Test fun reopenAfterFullyClosedStartsFreshWithoutStalePending() {
        val session = ShelfCatalogSession()
        session.requestOpen("game://alpha")
        val firstSequence = session.openSequence
        assertNull(session.dismiss(firstSequence))

        assertTrue(session.requestOpen("game://beta"))
        assertEquals(firstSequence + 1L, session.openSequence)
        // 旧序号的过期回调仍被忽略
        assertNull(session.dismiss(firstSequence))
        assertTrue(session.isOpen)
    }

    @Test fun closeNowDropsPendingReopen() {
        val session = ShelfCatalogSession()
        session.requestOpen("game://alpha")
        val sequence = session.openSequence
        session.requestOpen("game://beta")

        // 外部原因（换了卡片风格 / 游戏库清空）直接关闭：登记的重开作废
        assertTrue(session.closeNow())
        assertFalse(session.isOpen)
        assertNull(session.dismiss(sequence))
        assertFalse(session.isOpen)
        assertFalse(session.closeNow())
    }

    @Test fun restoredOpenSessionContinuesFromSavedSequence() {
        val session = ShelfCatalogSession(initialOpen = true, initialSequence = 5L)
        assertTrue(session.isOpen)
        assertEquals(5L, session.openSequence)
        assertNull(session.dismiss(5L))
        assertFalse(session.isOpen)
    }

    @Test fun saverPromotesPendingReopenAnchorAutomaticallyOnRecreation() {
        val original = ShelfCatalogSessionState()
        assertTrue(original.requestOpen("game://alpha"))
        val firstSequence = original.openSequence
        assertEquals("game://alpha", original.anchorUri)
        assertFalse(original.requestOpen("game://beta"))
        assertEquals("game://beta", original.pendingAnchorUri)

        val saved = with(ShelfCatalogSessionState.Saver) {
            androidx.compose.runtime.saveable.SaverScope { true }.save(original)
        }
        val restored = ShelfCatalogSessionState.Saver.restore(saved!!)!!
        // 重建后无需手动调用 dismiss，旧退场实例已销毁，挂起的重开锚点 B 自动晋升生效
        assertTrue(restored.isOpen)
        assertEquals(firstSequence + 1L, restored.openSequence)
        assertEquals("game://beta", restored.anchorUri)
        assertNull(restored.pendingAnchorUri)

        // 旧序号的过期收尾被忽略，新序号收尾后正常关闭
        assertNull(restored.dismiss(firstSequence))
        assertTrue(restored.isOpen)
        assertNull(restored.dismiss(firstSequence + 1L))
        assertFalse(restored.isOpen)
        assertNull(restored.anchorUri)
    }
}
