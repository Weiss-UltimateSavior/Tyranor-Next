package com.tyranor.next.ui.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemNnnSheetRequestTest {
    /** Holds an accepted launch until the test delivers its result, as the retained ViewModel does. */
    private class Launcher {
        val starts = mutableListOf<String>()
        var active: String? = null
        var result: String? = null

        fun click(sheet: SystemNnnSheetRequest, game: String) {
            sheet.launch(busy = active != null, pendingResult = result == game) {
                starts += game
                active = game
                result = null
            }
        }

        fun complete() {
            result = checkNotNull(active)
            active = null
        }
    }

    @Test
    fun leavingDoesNotCancelLaunchOrDeliverItsResultToAReopenedSheet() {
        val engine = Launcher()
        val first = SystemNnnSheetRequest()
        engine.click(first, "A")
        engine.click(first, "A")
        first.leave()

        val reopened = SystemNnnSheetRequest()
        engine.click(reopened, "A")
        engine.complete()
        assertEquals("A", engine.result)
        assertFalse(first.takeResult())
        assertFalse(reopened.takeResult())

        engine.click(first, "A") // A queued activation from the disposed drawer.
        assertEquals(listOf("A"), engine.starts)
        engine.click(reopened, "A") // Only an explicit fresh activation can launch again.
        engine.complete()
        assertTrue(reopened.takeResult())
        assertFalse(reopened.takeResult())
        assertEquals(listOf("A", "A"), engine.starts)
    }

    @Test
    fun switchingGamesDoesNotStealThePendingLaunchOrReplayItsResult() {
        val engine = Launcher()
        val first = SystemNnnSheetRequest()
        engine.click(first, "A")
        first.leave()
        val second = SystemNnnSheetRequest()
        engine.click(second, "B")
        engine.complete()
        assertEquals("A", engine.result)
        assertFalse(second.takeResult())
        assertEquals(listOf("A"), engine.starts)

        engine.click(second, "B")
        engine.complete()
        assertEquals("B", engine.result)
        assertTrue(second.takeResult())
        assertEquals(listOf("A", "B"), engine.starts)
    }

    @Test
    fun recreationKeepsTheClaimAndConsumesCompletionOnceWithoutRelaunching() {
        val engine = Launcher()
        val first = SystemNnnSheetRequest()
        engine.click(first, "A")
        val savedClaim = first.awaitingResult
        first.leave()
        engine.complete()

        val restored = SystemNnnSheetRequest(savedClaim)
        engine.click(restored, "A")
        assertTrue(restored.takeResult())
        assertFalse(restored.takeResult())
        assertEquals(listOf("A"), engine.starts)
    }
}
