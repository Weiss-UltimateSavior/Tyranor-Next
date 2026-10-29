package com.tyranor.next.core.game.launch

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class GameLaunchCompletionTest {
    @Test fun bookkeepingFailureAfterDispatchReturnsSuccessAndReportsFailure() {
        val failure = IOException("recent-game write failed")
        var dispatched = false
        var recordedFailure: Exception? = null

        val result = completeDispatchedGameLaunch(
            onDispatched = { dispatched = true },
            recordRecent = { throw failure },
            onRecordFailure = { recordedFailure = it },
        )

        assertTrue(dispatched)
        assertSame(failure, recordedFailure)
        assertSame(LaunchResult.Success, result)
    }

    @Test fun dispatchIsMarkedBeforeBookkeeping() {
        val events = mutableListOf<String>()

        val result = completeDispatchedGameLaunch(
            onDispatched = { events += "dispatched" },
            recordRecent = { events += "recordRecent" },
        )

        assertEquals(listOf("dispatched", "recordRecent"), events)
        assertSame(LaunchResult.Success, result)
    }

    @Test fun cancellationRethrowsSameObjectAfterDispatchWithoutReportingFailure() {
        val cancellation = CancellationException("cancelled during recent-game recording")
        var dispatched = false
        var reported = false

        val observed = assertThrows(CancellationException::class.java) {
            completeDispatchedGameLaunch(
                onDispatched = { dispatched = true },
                recordRecent = { throw cancellation },
                onRecordFailure = { reported = true },
            )
        }

        assertTrue(dispatched)
        assertSame(cancellation, observed)
        assertFalse(reported)
    }

    @Test fun successfulBookkeepingReturnsSuccessAfterDispatch() {
        var dispatched = false
        var recorded = false

        val result = completeDispatchedGameLaunch(
            onDispatched = { dispatched = true },
            recordRecent = { recorded = true },
        )

        assertTrue(dispatched)
        assertTrue(recorded)
        assertSame(LaunchResult.Success, result)
    }
}
