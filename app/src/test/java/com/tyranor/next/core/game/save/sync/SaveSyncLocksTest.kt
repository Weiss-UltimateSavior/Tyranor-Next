package com.tyranor.next.core.game.save.sync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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

    @Test
    fun admissionTimeoutReleasesWaiterWithoutRecyclingHeldEntry() = runTest {
        val locks = SaveSyncLockRegistry()
        val holder = launch(start = CoroutineStart.UNDISPATCHED) {
            locks.withLock("busy") { awaitCancellation() }
        }
        try {
            locks.withLock("busy", 100) { fail("Timed-out admission ran cleanup") }
            fail("Busy admission succeeded")
        } catch (failure: SaveSyncException) {
            assertEquals(SaveSyncError.GAME_RUNNING, failure.code)
        }
        assertEquals(1, locks.referenceCount("busy"))
        holder.cancelAndJoin()
        locks.withLock("busy", 0) { assertEquals(1, locks.referenceCount("busy")) }
        assertEquals(0, locks.size)
    }

    @Test
    fun admittedBlockCanOutliveItsAdmissionBudget() = runTest {
        val locks = SaveSyncLockRegistry()
        locks.withLock("cleanup", 1) { delay(10_000) }
        assertEquals(0, locks.size)

        SaveSyncLocks.versions("cleanup-budget", 1) {
            SaveSyncLocks.active("cleanup-budget", 1) {
                SaveSyncLocks.mutation("cleanup-budget", 1) {
                    SaveSyncLocks.session("cleanup-budget", 1) { delay(10_000) }
                }
            }
        }
        SaveSyncLocks.mutation(1) { delay(10_000) }
    }

    @Test
    fun admittedAfterWaitingCanOutliveAdmissionDeadline() = runTest {
        val locks = SaveSyncLockRegistry()
        val holder = launch(start = CoroutineStart.UNDISPATCHED) {
            locks.withLock("wait-then-cleanup") { delay(50) }
        }
        locks.withLock("wait-then-cleanup", 100) { delay(10_000) }
        holder.join()
        assertEquals(0, locks.size)

        val gate = SaveSyncOperationGate()
        val exclusive = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.withPermit(exclusive = true) { delay(50) }
        }
        gate.withPermit(exclusive = false, waitMillis = 100) { delay(10_000) }
        exclusive.join()
        gate.withPermit(exclusive = true, waitMillis = 0) {}
    }

    @Test
    fun callerCancellationIsNotTranslatedToBusyAndReleasesWaiter() = runTest {
        val locks = SaveSyncLockRegistry()
        val holder = launch(start = CoroutineStart.UNDISPATCHED) {
            locks.withLock("cancel-budget") { awaitCancellation() }
        }
        try {
            withTimeout(10) {
                locks.withLock("cancel-budget", 1_000) { fail("Cancelled admission ran cleanup") }
            }
            fail("Caller timeout did not cancel admission")
        } catch (_: TimeoutCancellationException) {
            // Only the lock's own admission timeout may become GAME_RUNNING.
        }
        assertEquals(1, locks.referenceCount("cancel-budget"))
        holder.cancelAndJoin()
        assertEquals(0, locks.size)
    }

    @Test
    fun operationWriterWaitsForAllReadersAndPrecedesLaterReaders() = runBlocking {
        val gate = SaveSyncOperationGate()
        val releaseFirst = CompletableDeferred<Unit>()
        val releaseSecond = CompletableDeferred<Unit>()
        val releaseWriter = CompletableDeferred<Unit>()
        val writerEntered = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val first = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.withPermit(exclusive = false) { releaseFirst.await() }
        }
        val second = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.withPermit(exclusive = false) { releaseSecond.await() }
        }
        val writer = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.withPermit(exclusive = true) {
                events += "writer"
                writerEntered.complete(Unit)
                releaseWriter.await()
            }
        }
        val laterReader = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.withPermit(exclusive = false) { events += "later-reader" }
        }
        assertTrue(events.isEmpty())
        releaseFirst.complete(Unit)
        first.join()
        assertTrue(events.isEmpty())
        releaseSecond.complete(Unit)
        second.join()
        withTimeout(5_000) { writerEntered.await() }
        assertEquals(listOf("writer"), events)
        releaseWriter.complete(Unit)
        withTimeout(5_000) { writer.join(); laterReader.join() }
        assertEquals(listOf("writer", "later-reader"), events)
    }

    @Test
    fun cancelledWriterDoesNotLeaveLaterSharedWorkBlocked() = runBlocking {
        val gate = SaveSyncOperationGate()
        val holder = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.withPermit(exclusive = false) { awaitCancellation() }
        }
        val writer = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.withPermit(exclusive = true) { fail("Cancelled writer was admitted") }
        }
        var laterReaderEntered = false
        val laterReader = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.withPermit(exclusive = false) { laterReaderEntered = true }
        }
        assertFalse(laterReaderEntered)
        writer.cancelAndJoin()
        withTimeout(5_000) { laterReader.join() }
        assertTrue(laterReaderEntered)
        holder.cancelAndJoin()
        withTimeout(5_000) { gate.withPermit(exclusive = true) {} }
    }

    @Test
    fun operationTimeoutRetiresExclusiveAndSharedWaiters() = runTest {
        val gate = SaveSyncOperationGate()
        for (exclusive in listOf(false, true)) {
            val holder = launch(start = CoroutineStart.UNDISPATCHED) {
                gate.withPermit(exclusive = !exclusive) { awaitCancellation() }
            }
            try {
                gate.withPermit(exclusive, 100) { fail("Timed-out operation ran cleanup") }
                fail("Busy operation succeeded")
            } catch (failure: SaveSyncException) {
                assertEquals(SaveSyncError.GAME_RUNNING, failure.code)
            }
            holder.cancelAndJoin()
            gate.withPermit(exclusive, 0) {}
        }
    }

    @Test
    fun cancellingGrantedButNotResumedPermitDoesNotLeakReaderOrWriter() = runTest {
        val gate = SaveSyncOperationGate()
        for (exclusive in listOf(false, true)) {
            lateinit var waiter: Job
            gate.withPermit(exclusive = true) {
                waiter = launch(start = CoroutineStart.UNDISPATCHED) {
                    gate.withPermit(exclusive) { fail("Cancelled granted waiter ran its block") }
                }
            }
            // Release grants the permit synchronously, while runTest queues its continuation.
            waiter.cancelAndJoin()
            gate.withPermit(exclusive = true, waitMillis = 0) {}
        }
    }
}
