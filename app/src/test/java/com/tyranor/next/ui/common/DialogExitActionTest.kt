package com.tyranor.next.ui.common

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DialogExitActionTest {
    @Test fun successfulActionCanReopenBeforeTheExitGuardIsReleased() = runBlocking {
        val events = mutableListOf<String>()
        runDialogExitAction(
            animateExit = { events += "exit" },
            action = { events += "action" },
            afterAction = { events += "reopen" },
            onFailure = { throw AssertionError("Unexpected failure", it) },
            cleanup = { events += "release" },
        )
        assertEquals(listOf("exit", "action", "reopen", "release"), events)
    }

    @Test fun failedActionIsNotRetriedOrReopenedAndAlwaysReleasesTheGuard() = runBlocking {
        val failure = IllegalStateException("action failed")
        val events = mutableListOf<String>()
        runDialogExitAction(
            animateExit = { events += "exit" },
            action = { events += "action"; throw failure },
            afterAction = { events += "reopen" },
            onFailure = { assertSame(failure, it); events += "close" },
            cleanup = { events += "release" },
        )
        assertEquals(listOf("exit", "action", "close", "release"), events)
    }

    @Test fun cancellingExitStillDeliversTheAcceptedActionExactlyOnce() = runTest {
        val exiting = CompletableDeferred<Unit>()
        var actions = 0
        var releases = 0
        val job = launch {
            runDialogExitAction(
                animateExit = { exiting.complete(Unit); awaitCancellation() },
                action = { actions++ },
                afterAction = { throw AssertionError("A cancelled exit must not reopen") },
                onFailure = { throw AssertionError("Cancellation is not an action failure", it) },
                cleanup = { releases++ },
            )
        }
        exiting.await()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertEquals(1, actions)
        assertEquals(1, releases)
    }

    @Test fun actionCancellationPropagatesWithoutFailureHandlingOrRetry() {
        val cancellation = CancellationException("action cancelled")
        var actions = 0
        var releases = 0
        val thrown = assertThrows(CancellationException::class.java) {
            runBlocking {
                runDialogExitAction(
                    animateExit = {},
                    action = { actions++; throw cancellation },
                    afterAction = { throw AssertionError("Must not reopen after cancellation") },
                    onFailure = { throw AssertionError("Must not treat cancellation as failure", it) },
                    cleanup = { releases++ },
                )
            }
        }
        assertSame(cancellation, thrown)
        assertEquals(1, actions)
        assertEquals(1, releases)
    }

    @Test fun aFailedFallbackDoesNotReplaceTheOriginalExitCancellation() {
        val cancellation = CancellationException("exit cancelled")
        val failure = IllegalStateException("accepted action failed")
        var actions = 0
        var releases = 0
        var failures = 0
        val thrown = assertThrows(CancellationException::class.java) {
            runBlocking {
                runDialogExitAction(
                    animateExit = { throw cancellation },
                    action = { actions++; throw failure },
                    afterAction = { throw AssertionError("Must not reopen") },
                    onFailure = { assertSame(failure, it); failures++ },
                    cleanup = { releases++ },
                )
            }
        }
        assertSame(cancellation, thrown)
        assertTrue(thrown.suppressed.contains(failure))
        assertEquals(1, actions)
        assertEquals(1, failures)
        assertEquals(1, releases)
    }

    @Test fun aFailureInTheCloseCallbackStillReleasesTheExitGuard() {
        val failure = IllegalStateException("close callback failed")
        var releases = 0
        val thrown = assertThrows(IllegalStateException::class.java) {
            runBlocking {
                runDialogExitAction(
                    animateExit = {},
                    action = { error("action failed") },
                    afterAction = { throw AssertionError("Must not reopen") },
                    onFailure = { throw failure },
                    cleanup = { releases++ },
                )
            }
        }
        assertSame(failure, thrown)
        assertEquals(1, releases)
    }
}
