package com.tyranor.next.core.game.save.sync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class SaveLaunchAdmissionTest {
    @Test
    fun commonMutationGateSerializesResolvedBaselineWithDisabledAndUnresolvedLaunches() = runBlocking {
        val baselineEntered = CompletableDeferred<String?>()
        val releaseBaseline = CompletableDeferred<Unit>()
        val baseline = launch(start = CoroutineStart.UNDISPATCHED) {
            withSaveSyncLaunchAdmission<Unit>(
                resolve = { SaveSyncLaunchAdmission.Directory("admission-baseline") },
                managed = { key ->
                    baselineEntered.complete(key)
                    releaseBaseline.await()
                },
                unmanaged = { throw AssertionError("A directory baseline must stay managed") },
            )
        }
        assertEquals(
            "admission-baseline",
            withTimeout(5_000) { baselineEntered.await() },
        )

        var disabledCallbacks = 0
        var unresolvedCallbacks = 0
        val disabledEntered = CompletableDeferred<String?>()
        val unresolvedEntered = CompletableDeferred<String?>()
        val disabled = launch(start = CoroutineStart.UNDISPATCHED) {
            withSaveSyncLaunchAdmission<Unit>(
                resolve = { SaveSyncLaunchAdmission.Disabled },
                managed = { key ->
                    disabledCallbacks++
                    disabledEntered.complete(key)
                },
                unmanaged = { throw AssertionError("Disabled admission must stay managed") },
            )
        }
        val unresolved = launch(start = CoroutineStart.UNDISPATCHED) {
            withSaveSyncLaunchAdmission<Unit>(
                resolve = { SaveSyncLaunchAdmission.Unresolved },
                managed = { key ->
                    unresolvedCallbacks++
                    unresolvedEntered.complete(key)
                },
                unmanaged = { throw AssertionError("Unresolved admission must stay managed") },
            )
        }

        assertFalse(disabledEntered.isCompleted)
        assertFalse(unresolvedEntered.isCompleted)
        assertEquals(0, disabledCallbacks)
        assertEquals(0, unresolvedCallbacks)

        releaseBaseline.complete(Unit)
        withTimeout(5_000) {
            baseline.join()
            disabled.join()
            unresolved.join()
        }

        assertNull(disabledEntered.await())
        assertNull(unresolvedEntered.await())
        assertEquals(1, disabledCallbacks)
        assertEquals(1, unresolvedCallbacks)
    }

    @Test
    fun directoryChangeWhileWaitingForOldActiveIsReadmittedWithoutStaleCallback() = runBlocking {
        val activeA = "admission-active-a"
        val activeB = "admission-active-b"
        val activeAHeld = CompletableDeferred<Unit>()
        val releaseActiveA = CompletableDeferred<Unit>()
        val firstResolve = CompletableDeferred<Unit>()
        val callbackEntered = CompletableDeferred<Unit>()
        val releaseCallback = CompletableDeferred<Unit>()
        val activeBWaiterEntered = CompletableDeferred<Unit>()
        val admission = AtomicReference<SaveSyncLaunchAdmission>(SaveSyncLaunchAdmission.Directory(activeA))
        val managedKeys = mutableListOf<String?>()
        var resolveCalls = 0

        val activeAHolder = launch(start = CoroutineStart.UNDISPATCHED) {
            SaveSyncLocks.active(activeA) {
                activeAHeld.complete(Unit)
                releaseActiveA.await()
            }
        }
        withTimeout(5_000) { activeAHeld.await() }

        val managedLaunch = launch(start = CoroutineStart.UNDISPATCHED) {
            withSaveSyncLaunchAdmission<Unit>(
                resolve = {
                    resolveCalls++
                    if (resolveCalls == 1) firstResolve.complete(Unit)
                    admission.get()
                },
                managed = { key ->
                    managedKeys += key
                    callbackEntered.complete(Unit)
                    releaseCallback.await()
                },
                unmanaged = { throw AssertionError("Directory admission must not bypass locks") },
            )
        }
        withTimeout(5_000) { firstResolve.await() }
        assertFalse(callbackEntered.isCompleted)
        assertTrue(managedKeys.isEmpty())

        admission.set(SaveSyncLaunchAdmission.Directory(activeB))
        releaseActiveA.complete(Unit)
        withTimeout(5_000) { callbackEntered.await() }

        assertEquals(listOf(activeB), managedKeys)
        val activeBWaiter = launch(start = CoroutineStart.UNDISPATCHED) {
            SaveSyncLocks.active(activeB) {
                activeBWaiterEntered.complete(Unit)
            }
        }
        assertFalse(activeBWaiterEntered.isCompleted)

        releaseCallback.complete(Unit)
        withTimeout(5_000) {
            managedLaunch.join()
            activeAHolder.join()
            activeBWaiter.join()
        }
        assertTrue(activeBWaiterEntered.isCompleted)
        assertEquals(listOf(activeB), managedKeys)
    }

    @Test
    fun unmanagedBypassesHeldMutationGate() = runBlocking {
        val mutationHeld = CompletableDeferred<Unit>()
        val releaseMutation = CompletableDeferred<Unit>()
        val mutationHolder = launch(start = CoroutineStart.UNDISPATCHED) {
            SaveSyncLocks.mutation {
                mutationHeld.complete(Unit)
                releaseMutation.await()
            }
        }
        withTimeout(5_000) { mutationHeld.await() }

        var resolveCalls = 0
        var managedCalls = 0
        var unmanagedCalls = 0
        val result = withTimeout(5_000) {
            withSaveSyncLaunchAdmission(
                resolve = {
                    resolveCalls++
                    SaveSyncLaunchAdmission.Unmanaged
                },
                managed = {
                    managedCalls++
                    throw AssertionError("Unmanaged admission acquired the managed gate")
                },
                unmanaged = {
                    unmanagedCalls++
                    "unmanaged-launch"
                },
            )
        }

        assertEquals("unmanaged-launch", result)
        assertEquals(1, resolveCalls)
        assertEquals(0, managedCalls)
        assertEquals(1, unmanagedCalls)

        releaseMutation.complete(Unit)
        withTimeout(5_000) { mutationHolder.join() }
    }

    @Test
    fun cancellationWhileWaitingForMutationDoesNotRunManagedCallback() = runBlocking {
        val mutationHeld = CompletableDeferred<Unit>()
        val releaseMutation = CompletableDeferred<Unit>()
        val mutationHolder = launch(start = CoroutineStart.UNDISPATCHED) {
            SaveSyncLocks.mutation {
                mutationHeld.complete(Unit)
                releaseMutation.await()
            }
        }
        withTimeout(5_000) { mutationHeld.await() }

        var resolveCalls = 0
        var managedCalls = 0
        val waitingLaunch = launch(start = CoroutineStart.UNDISPATCHED) {
            withSaveSyncLaunchAdmission<Unit>(
                resolve = {
                    resolveCalls++
                    SaveSyncLaunchAdmission.Disabled
                },
                managed = {
                    managedCalls++
                    throw AssertionError("Cancelled waiter acquired the managed gate")
                },
                unmanaged = { throw AssertionError("Disabled admission must stay managed") },
            )
        }

        assertEquals(1, resolveCalls)
        assertFalse(waitingLaunch.isCompleted)
        waitingLaunch.cancelAndJoin()
        assertTrue(waitingLaunch.isCancelled)
        assertEquals(0, managedCalls)

        releaseMutation.complete(Unit)
        withTimeout(5_000) { mutationHolder.join() }
        assertEquals(0, managedCalls)
    }
}
