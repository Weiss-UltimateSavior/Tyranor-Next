package com.tyranor.next.ui.game

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GameLaunchPreparationTest {
    @Test
    fun repeatedActivationsAcceptOneRequestBeforePreparationEvenStarts() = runTest {
        val preparation = GameLaunchPreparation()
        val ready = CompletableDeferred<Unit>()
        var accepted = 0
        var delivered = 0
        fun activate() {
            if (!preparation.tryBegin()) return
            accepted++
            launch {
                preparation.step {
                    ready.await()
                    delivered++
                    preparation.finish()
                }
            }
        }

        activate()
        activate()
        assertEquals(1, accepted)
        runCurrent()
        activate()
        assertEquals(1, accepted)
        assertEquals(0, delivered)
        ready.complete(Unit)
        runCurrent()
        assertEquals(1, delivered)
        assertTrue("A delivered request must allow a later attempt", preparation.tryBegin())
    }

    @Test
    fun ownershipSurvivesSuccessfulStageHandoffsAndConfirmationCancellationAllowsRetry() = runTest {
        val preparation = GameLaunchPreparation()
        assertTrue(preparation.tryBegin())
        preparation.step { /* Patch check hands off to its confirmation. */ }
        assertFalse(preparation.tryBegin())
        preparation.step { /* Accepted confirmation hands off to the save-format check. */ }
        assertFalse(preparation.tryBegin())
        preparation.finish()
        assertTrue("Cancelling confirmation must make the next request available", preparation.tryBegin())
    }

    @Test
    fun failedPreparationPropagatesTheOriginalThrowableAndAllowsRetry() = runTest {
        for (failure in listOf(IOException("prepare"), AssertionError("prepare"), CancellationException("prepare"))) {
            val preparation = GameLaunchPreparation()
            assertTrue(preparation.tryBegin())
            val actual = try {
                preparation.step { throw failure }
                null
            } catch (error: Throwable) {
                error
            }
            assertSame(failure, actual)
            assertTrue("Failure must release the request before it propagates", preparation.tryBegin())
        }
    }

    @Test
    fun cancellingSuspendedPreparationReleasesOwnership() = runTest {
        val preparation = GameLaunchPreparation()
        val neverReady = CompletableDeferred<Unit>()
        assertTrue(preparation.tryBegin())
        val pending = launch { preparation.step { neverReady.await() } }
        runCurrent()
        assertFalse(preparation.tryBegin())
        pending.cancel()
        pending.join()
        assertTrue("Cancelling a suspended stage must permit a retry", preparation.tryBegin())
    }
}
