package com.tyranor.next.core.game.save.sync

import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.game.save.EthornellSaveCapability
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
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
    fun enginesWithoutUsableSaveInterfaceStayUnmanaged() {
        assertEquals(EthornellSaveCapability.available, supportsManagedSave(EngineType.ETHORNELL))
        assertTrue(supportsManagedSave(EngineType.KIRIKIRI))
        assertFalse(supportsManagedSave(EngineType.RUFFLE))
    }

    @Test
    fun operationGateSerializesResolvedBaselineWithDisabledAndUnresolvedLaunches() = runBlocking {
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
    fun slowResolvedLaunchAllowsOtherDirectoryLaunchCaptureAndActivationButFencesAliases() = runBlocking {
        val slowKey = "admission-slow-directory"
        val slowEntered = CompletableDeferred<Unit>()
        val slow = launch(start = CoroutineStart.UNDISPATCHED) {
            withSaveSyncLaunchAdmission<Unit>(
                resolve = { SaveSyncLaunchAdmission.Directory(slowKey) },
                managed = { slowEntered.complete(Unit); awaitCancellation() },
                unmanaged = { throw AssertionError("Resolved launch bypassed admission") },
            )
        }
        withTimeout(5_000) { slowEntered.await() }
        val events = mutableListOf<String>()
        val alias = launch(start = CoroutineStart.UNDISPATCHED) {
            SaveSyncLocks.active(slowKey) {
                SaveSyncLocks.mutation(slowKey) { events += "alias" }
            }
        }
        withTimeout(5_000) {
            withSaveSyncLaunchAdmission<Unit>(
                resolve = { SaveSyncLaunchAdmission.Directory("admission-other-launch") },
                managed = { events += "launch" },
                unmanaged = { throw AssertionError("Resolved launch bypassed admission") },
            )
            for (operation in listOf("capture", "activation")) {
                SaveSyncLocks.versions("other-$operation") {
                    SaveSyncLocks.active("other-$operation") {
                        SaveSyncLocks.mutation("other-$operation") { events += operation }
                    }
                }
            }
        }
        assertEquals(listOf("launch", "capture", "activation"), events)
        slow.cancelAndJoin()
        withTimeout(5_000) { alias.join() }
        assertEquals(listOf("launch", "capture", "activation", "alias"), events)
    }

    @Test
    fun unresolvedAndDisabledLaunchesExcludeEveryDirectoryUntilDispatchFinishes() = runBlocking {
        for (admission in listOf(SaveSyncLaunchAdmission.Unresolved, SaveSyncLaunchAdmission.Disabled)) {
            val entered = CompletableDeferred<Unit>()
            val unknown = launch(start = CoroutineStart.UNDISPATCHED) {
                withSaveSyncLaunchAdmission<Unit>(
                    resolve = { admission },
                    managed = { entered.complete(Unit); awaitCancellation() },
                    unmanaged = { throw AssertionError("Unknown launch bypassed admission") },
                )
            }
            withTimeout(5_000) { entered.await() }
            var replacementEntered = false
            val replacement = launch(start = CoroutineStart.UNDISPATCHED) {
                SaveSyncLocks.active("unknown-replacement") {
                    SaveSyncLocks.mutation("unknown-replacement") { replacementEntered = true }
                }
            }
            var knownLaunchEntered = false
            val known = launch(start = CoroutineStart.UNDISPATCHED) {
                withSaveSyncLaunchAdmission<Unit>(
                    resolve = { SaveSyncLaunchAdmission.Directory("unknown-other-launch") },
                    managed = { knownLaunchEntered = true },
                    unmanaged = { throw AssertionError("Known launch bypassed admission") },
                )
            }
            assertFalse(replacementEntered)
            assertFalse(knownLaunchEntered)
            unknown.cancelAndJoin()
            withTimeout(5_000) { replacement.join(); known.join() }
            assertTrue(replacementEntered)
            assertTrue(knownLaunchEntered)
        }
    }

    @Test
    fun unresolvedAdmissionThatBecomesResolvedReleasesExclusiveAndPinsNewDirectory() = runBlocking {
        val admission = AtomicReference<SaveSyncLaunchAdmission>(SaveSyncLaunchAdmission.Unresolved)
        val gateHolder = launch(start = CoroutineStart.UNDISPATCHED) {
            SaveSyncLocks.mutation { awaitCancellation() }
        }
        val managedKeys = mutableListOf<String?>()
        val callbackEntered = CompletableDeferred<Unit>()
        val managedLaunch = launch(start = CoroutineStart.UNDISPATCHED) {
            withSaveSyncLaunchAdmission<Unit>(
                resolve = { admission.get() },
                managed = { key ->
                    managedKeys += key
                    callbackEntered.complete(Unit)
                    awaitCancellation()
                },
                unmanaged = { throw AssertionError("Resolved admission bypassed its active lock") },
            )
        }
        admission.set(SaveSyncLaunchAdmission.Directory("readmitted-directory"))
        gateHolder.cancelAndJoin()
        withTimeout(5_000) { callbackEntered.await() }
        assertEquals(listOf("readmitted-directory"), managedKeys)
        var aliasEntered = false
        val alias = launch(start = CoroutineStart.UNDISPATCHED) {
            SaveSyncLocks.active("readmitted-directory") { aliasEntered = true }
        }
        assertFalse(aliasEntered)
        // Re-admission must release the exclusive permit, not retain it around the known launch.
        withTimeout(5_000) {
            SaveSyncLocks.active("readmitted-unrelated") {
                SaveSyncLocks.mutation("readmitted-unrelated") {}
            }
        }
        managedLaunch.cancelAndJoin()
        withTimeout(5_000) { alias.join() }
        assertTrue(aliasEntered)
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
