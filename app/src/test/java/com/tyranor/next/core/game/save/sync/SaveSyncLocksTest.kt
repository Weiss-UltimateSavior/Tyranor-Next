package com.tyranor.next.core.game.save.sync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SaveSyncLocksTest {
    @Test
    fun sameUriSerializesAndWaiterKeepsTheSharedEntryAlive() = runBlocking {
        val locks = SaveSyncLockRegistry()
        val holderEntered = CompletableDeferred<Unit>()
        val releaseHolder = CompletableDeferred<Unit>()
        val waiterEntered = CompletableDeferred<Unit>()

        val holder = launch(start = CoroutineStart.UNDISPATCHED) {
            locks.withLock("game-a") {
                holderEntered.complete(Unit)
                releaseHolder.await()
            }
        }
        holderEntered.await()

        val waiter = launch(start = CoroutineStart.UNDISPATCHED) {
            locks.withLock("game-a") {
                assertTrue(releaseHolder.isCompleted)
                waiterEntered.complete(Unit)
            }
        }

        assertEquals(2, locks.referenceCount("game-a"))
        assertFalse(waiterEntered.isCompleted)

        releaseHolder.complete(Unit)
        holder.join()
        waiter.join()

        assertTrue(waiterEntered.isCompleted)
        assertEquals(0, locks.size)
    }

    @Test
    fun cancellingWaiterReleasesOnlyItsReference() = runBlocking {
        val locks = SaveSyncLockRegistry()
        val holderEntered = CompletableDeferred<Unit>()
        val releaseHolder = CompletableDeferred<Unit>()

        val holder = launch(start = CoroutineStart.UNDISPATCHED) {
            locks.withLock("game-a") {
                holderEntered.complete(Unit)
                releaseHolder.await()
            }
        }
        holderEntered.await()

        val waiter = launch(start = CoroutineStart.UNDISPATCHED) {
            locks.withLock("game-a") {
                throw AssertionError("cancelled waiter must not acquire the lock")
            }
        }

        assertEquals(2, locks.referenceCount("game-a"))
        waiter.cancelAndJoin()
        assertEquals(1, locks.referenceCount("game-a"))
        assertEquals(1, locks.size)

        releaseHolder.complete(Unit)
        holder.join()
        assertEquals(0, locks.size)
    }

    @Test
    fun differentUrisUseIndependentEntries() = runBlocking {
        val locks = SaveSyncLockRegistry()
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()

        val first = launch(start = CoroutineStart.UNDISPATCHED) {
            locks.withLock("game-a") {
                firstEntered.complete(Unit)
                releaseFirst.await()
            }
        }
        firstEntered.await()

        var secondCompleted = false
        locks.withLock("game-b") {
            secondCompleted = true
        }

        assertTrue(secondCompleted)
        assertEquals(1, locks.referenceCount("game-a"))
        assertEquals(0, locks.referenceCount("game-b"))

        releaseFirst.complete(Unit)
        first.join()
        assertEquals(0, locks.size)
    }

    @Test
    fun exceptionAndCancellationAlwaysReleaseReferences() = runBlocking {
        val locks = SaveSyncLockRegistry()

        try {
            locks.withLock("exception") {
                throw IllegalStateException("expected")
            }
        } catch (_: IllegalStateException) {
            // Expected: the finally path must still recycle the entry.
        }
        assertEquals(0, locks.size)

        val entered = CompletableDeferred<Unit>()
        val holder = launch(start = CoroutineStart.UNDISPATCHED) {
            locks.withLock("cancelled") {
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        entered.await()
        assertEquals(1, locks.size)

        holder.cancelAndJoin()
        assertEquals(0, locks.size)
    }
}
