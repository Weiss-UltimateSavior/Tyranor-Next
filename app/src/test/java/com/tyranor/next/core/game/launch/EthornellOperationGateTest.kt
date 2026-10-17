package com.tyranor.next.core.game.launch

import com.tyranor.next.core.game.save.EthornellSaveCapability
import com.core.ethornell.EthornellDispatchRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class EthornellOperationGateTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun anyActiveHostOrInitializationOrRacingRegistryBlocksAllUserData() {
        assertFalse(EthornellHostSnapshot(false, false).busy)
        assertTrue(EthornellHostSnapshot(true, false).busy)
        assertTrue(EthornellHostSnapshot(true, true).busy)
        assertTrue(EthornellHostSnapshot(false, true).busy)
    }

    @Test fun unavailableProcessSnapshotFailsClosed() {
        assertTrue(EthornellHostSnapshot(false, false, known = false).busy)
    }

    @Test fun noPollingMemoryCanPermanentlyBlockFastFailedOrUnobservedHost() {
        assertTrue(EthornellHostSnapshot(true, false).busy)
        repeat(10) { assertFalse(EthornellHostSnapshot(false, false).busy) }
        // A launch missed entirely by polling is idle for launch policy, but
        // that snapshot alone is NOT proof of safe saves.
        assertEquals(EthornellLaunchPolicy.Action.START_NEW, EthornellLaunchPolicy.decide("identity", null, false))
    }

    @Test fun verifiedDispatchAloneDoesNotOpenSaveManagement() {
        assertTrue(EthornellSaveCapability.HOST_DISPATCH_PROTOCOL_VERIFIED)
        assertFalse(EthornellSaveCapability.DEVICE_SAVE_IO_VERIFIED)
        assertFalse(EthornellSaveCapability.available)
    }

    @Test fun unobservedDispatchAfterMainDeathIsAtomicallyRevokedAndLateHostRejected() {
        val files = temporary.newFolder()
        val token = EthornellDispatchRegistry.issue(files, "identity")
        // New recovery instance simulates launcher-process restart; no pending
        // memory or process-list observation is required.
        assertFalse(EthornellDispatchRecovery(files) { fail("Unclaimed owner"); false }.busy())
        assertEquals(EthornellDispatchRegistry.Stage.REVOKED, EthornellDispatchRegistry.status(files, token).stage)
        assertFalse(EthornellDispatchRegistry.claim(files, token, "identity", 123, "456"))
        val next = EthornellDispatchRegistry.issue(files, "identity")
        assertNotEquals(token, next)
    }

    @Test fun completeHostLifecycleBetweenPollsLeavesDurableTerminalAndDoesNotBlockNewStart() {
        val files = temporary.newFolder()
        val token = EthornellDispatchRegistry.issue(files, "identity")
        assertTrue(EthornellDispatchRegistry.claim(files, token, "identity", 123, "456"))
        assertTrue(EthornellDispatchRegistry.finish(files, token, "identity", 123, "456"))
        assertFalse(EthornellDispatchRecovery(files) { fail("Terminal needs no claimed-owner recovery"); false }.busy())
        assertFalse(EthornellDispatchRegistry.claim(files, token, "identity", 123, "456"))
        EthornellDispatchRegistry.issue(files, "identity") // Retired record may now be absent.
        assertNull(EthornellDispatchRegistry.status(files, token))
        assertFalse(EthornellDispatchRegistry.claim(files, token, "identity", 123, "456"))
    }

    @Test fun claimedOwnerBlocksWhileAliveButDemonstrablyDeadOwnerDoesNotPoisonLaunch() {
        val files = temporary.newFolder()
        val token = EthornellDispatchRegistry.issue(files, "identity")
        assertTrue(EthornellDispatchRegistry.claim(files, token, "identity", 123, "456"))
        assertTrue(EthornellDispatchRecovery(files) { record ->
            assertEquals(token, record.token)
            assertEquals(123, record.pid)
            assertEquals("456", record.startTime)
            false // Exact owner still alive.
        }.busy())
        assertFalse(EthornellDispatchRecovery(files) { true }.busy())
        assertEquals(EthornellDispatchRegistry.Stage.CLAIMED, EthornellDispatchRegistry.status(files, token).stage)
        // App does not mutate or forge native cleanup ACK for a killed process.
    }

    @Test fun uncertainOwnerEvidenceAndCorruptMetadataFailClosedWithoutClearingIt() {
        val files = temporary.newFolder()
        val token = EthornellDispatchRegistry.issue(files, "identity")
        assertTrue(EthornellDispatchRegistry.claim(files, token, "identity", 123, "456"))
        val denied = IOException("unknown owner")
        try {
            EthornellDispatchRecovery(files) { throw denied }.busy()
            fail("Must propagate uncertain evidence")
        } catch (error: IOException) { assertSame(denied, error) }
        File(files, "ethornell_dispatch/$token.record").writeText("corrupt")
        try { EthornellDispatchRecovery(files) { true }.busy(); fail("Corrupt metadata") }
        catch (_: IOException) { }
    }

    @Test fun legacyUnobservedPollingMarkerIsNeverReadByNewRecovery() {
        val files = temporary.newFolder()
        File(files, "ethornell/.ethornell-launch-pending").apply { parentFile!!.mkdirs(); writeText("pending") }
        assertFalse(EthornellDispatchRecovery(files) { false }.busy())
    }

    @Test fun launchAndEverySaveOperationShareOneSuspendingMutexAndCancellationReleasesIt() = runTest {
        val launchEntered = CompletableDeferred<Unit>()
        val allowDispatch = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val launch = launch {
            EthornellOperationGate.exclusive {
                order += "preflight"
                launchEntered.complete(Unit)
                allowDispatch.await()
                order += "startActivity"
            }
        }
        launchEntered.await()
        assertFalse(EthornellOperationGate.tryExclusive { fail("Cleanup must retain data while locked") })
        val save = async { EthornellOperationGate.exclusive { order += "save" } }
        assertEquals(listOf("preflight"), order)
        allowDispatch.complete(Unit)
        launch.join()
        save.await()
        assertEquals(listOf("preflight", "startActivity", "save"), order)
        val entered = CompletableDeferred<Unit>()
        val cancelled = launch { EthornellOperationGate.exclusive { entered.complete(Unit); CompletableDeferred<Unit>().await() } }
        entered.await()
        cancelled.cancel()
        cancelled.join()
        EthornellOperationGate.exclusive { order += "afterCancel" }
        assertEquals("afterCancel", order.last())
        assertTrue(EthornellOperationGate.tryExclusive { order += "cleanup" })
        assertEquals("cleanup", order.last())
    }
}
