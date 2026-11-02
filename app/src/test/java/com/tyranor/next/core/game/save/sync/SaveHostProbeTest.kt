package com.tyranor.next.core.game.save.sync

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SaveHostProbeTest {
    @Test
    fun zeroActivityCacheBecomesIdleAtGraceBoundary() {
        val name = "com.tyranor.next:kirikiroid"
        val pid = 101
        val probe = FakeProbe(
            visibleHosts = listOf(SaveHostProcess(pid, name)),
            records = mapOf(
                key(name) to record(pid, activities = 0, uris = listOf(TARGET_URI), idleSince = 8_000L),
            ),
            identities = mapOf(pid to SaveHostIdentity.Live(name)),
        )

        assertTrue(running(probe, elapsedMillis = 9_499L, hasSession = true))
        assertFalse(running(probe, elapsedMillis = 9_500L, hasSession = true))
    }

    @Test
    fun activeHostForAnotherGameDoesNotBlockTarget() {
        val name = "com.tyranor.next:kirikiroid"
        val pid = 102
        val probe = FakeProbe(
            visibleHosts = listOf(SaveHostProcess(pid, name)),
            records = mapOf(
                key(name) to record(pid, activities = 1, uris = listOf("content://saves/other-game")),
            ),
            identities = mapOf(pid to SaveHostIdentity.Live(name)),
        )

        assertFalse(running(probe, hasSession = true))
    }

    @Test
    fun activeHostOmittedFromOnlyMainProcessListIsFoundFromRecords() {
        val name = "com.tyranor.next:kirikiroid"
        val pid = 103
        val probe = FakeProbe(
            visibleHosts = emptyList(),
            records = mapOf(
                key(name) to record(pid, activities = 1, uris = listOf(TARGET_URI)),
            ),
            identities = mapOf(pid to SaveHostIdentity.Live(name)),
        )

        assertTrue(running(probe, hasSession = false))
        assertEquals(1, probe.visibleHostsCalls)
    }

    @Test
    fun unavailableProcessListWithoutRequiredSessionAndWithoutRecordsIsIdle() {
        val probe = FakeProbe(visibleHosts = null)

        assertFalse(running(probe, hasSession = false))
        assertEquals(1, probe.visibleHostsCalls)
    }

    @Test
    fun unavailableProcessListWithRequiredSessionIsBusy() {
        val name = "com.tyranor.next:kirikiroid"
        val pid = 108
        val probe = FakeProbe(
            visibleHosts = null,
            records = mapOf(
                key(name) to record(pid, activities = 1, uris = listOf(TARGET_URI)),
            ),
            identities = mapOf(pid to SaveHostIdentity.Dead),
        )

        assertTrue(running(probe, hasSession = true))
        assertEquals(1, probe.visibleHostsCalls)
    }

    @Test
    fun unavailableProcessListStillEvaluatesLivePersistedHosts() {
        val name = "com.tyranor.next:kirikiroid"
        val pid = 109
        val probe = FakeProbe(
            visibleHosts = null,
            records = mapOf(
                key(name) to record(pid, activities = 1, uris = listOf(TARGET_URI)),
            ),
            identities = mapOf(pid to SaveHostIdentity.Live(name)),
        )

        assertTrue(running(probe, hasSession = false))
        assertEquals(1, probe.visibleHostsCalls)
    }

    @Test
    fun unavailableProcessListIgnoresLiveHostForAnotherSaveDirectory() {
        val name = "com.tyranor.next:kirikiroid"
        val pid = 113
        val probe = FakeProbe(
            visibleHosts = null,
            records = mapOf(
                key(name) to record(pid, activities = 1, uris = listOf("content://saves/other-game")),
            ),
            identities = mapOf(pid to SaveHostIdentity.Live(name)),
        )

        assertFalse(running(probe, hasSession = false))
    }

    @Test
    fun unavailableProcessListStillEvaluatesPendingNativeOwner() {
        val name = "com.tyranor.next:siglus"
        val pid = 114
        val state = record(pid, activities = 0, uris = listOf(TARGET_URI), idleSince = 1L)
            .put("nativeOwnerPending", true)
        val probe = FakeProbe(
            visibleHosts = null,
            records = mapOf(key(name) to state),
            identities = mapOf(pid to SaveHostIdentity.Live(name)),
        )

        assertTrue(running(probe, elapsedMillis = 60_000L, hasSession = false))
    }

    @Test
    fun externalOnlySessionDoesNotRequireMissingNativeHostEvidence() {
        val probe = FakeProbe(visibleHosts = null)
        val nativeSessionRequiresHostEvidence = sessionRequiresHostEvidence(
            hasToken = false,
            startedAt = 0L,
            returned = true,
        )

        assertFalse(running(probe, hasSession = nativeSessionRequiresHostEvidence))
    }

    @Test
    fun missingMalformedAndPidMismatchedRecordsAreBusy() {
        val name = "com.tyranor.next:kirikiroid"
        val pid = 104
        val visible = listOf(SaveHostProcess(pid, name))

        assertTrue(running(FakeProbe(visibleHosts = visible), hasSession = false))
        assertTrue(
            running(
                FakeProbe(
                    visibleHosts = visible,
                    records = mapOf(
                        key(name) to JSONObject().put("pid", pid).put("activities", 1).put("uris", "not-an-array"),
                    ),
                ),
                hasSession = false,
            ),
        )
        assertTrue(
            running(
                FakeProbe(
                    visibleHosts = visible,
                    records = mapOf(
                        key(name) to record(pid + 1, activities = 1, uris = listOf(TARGET_URI)),
                    ),
                ),
                hasSession = false,
            ),
        )
    }

    @Test
    fun unknownProcessIdentityFailsClosed() {
        val name = "com.tyranor.next:kirikiroid"
        val pid = 105
        val probe = FakeProbe(
            visibleHosts = null,
            records = mapOf(
                key(name) to record(pid, activities = 1, uris = listOf(TARGET_URI)),
            ),
            identities = mapOf(pid to SaveHostIdentity.Unknown),
        )

        assertTrue(running(probe, hasSession = false))
    }

    @Test
    fun malformedPersistedRecordStaysBusyWithoutVisibleProcesses() {
        val name = "com.tyranor.next:kirikiroid"
        val probe = FakeProbe(
            visibleHosts = null,
            records = mapOf(
                key(name) to record(pid = 0, activities = 1, uris = listOf(TARGET_URI)),
            ),
        )

        assertTrue(running(probe, hasSession = false))
    }

    @Test
    fun corruptRecordSnapshotRemainsConservativeAcrossRepeatedChecks() {
        val name = "com.tyranor.next:kirikiroid"
        val records = mutableMapOf(key(name) to JSONObject())
        val probe = FakeProbe(
            visibleHosts = null,
            records = records,
        )

        assertTrue(running(probe, hasSession = false))
        assertEquals(1, probe.visibleHostsCalls)

        assertTrue(running(probe, hasSession = false))
        assertEquals(2, probe.visibleHostsCalls)
    }

    @Test
    fun startGraceIsBusyWithoutReadingAnUnavailableProcessList() {
        val probe = FakeProbe(
            visibleHosts = null,
            recordsFailure = IllegalStateException("must not read records during grace"),
        )

        assertTrue(running(probe, inStartGrace = true))
        assertEquals(0, probe.visibleHostsCalls)
    }

    @Test
    fun deadRecordIsObservedAndDoesNotLeaveSessionBusy() {
        val name = "com.tyranor.next:kirikiroid"
        val pid = 106
        val probe = FakeProbe(
            visibleHosts = emptyList(),
            records = mapOf(
                key(name) to record(pid, activities = 1, uris = listOf(TARGET_URI)),
            ),
            identities = mapOf(pid to SaveHostIdentity.Dead),
        )

        assertFalse(running(probe, hasSession = true))
    }

    @Test
    fun reusedPidWithAnotherLiveNameTreatsOldHostAsDead() {
        val name = "com.tyranor.next:kirikiroid"
        val pid = 107
        val probe = FakeProbe(
            visibleHosts = emptyList(),
            records = mapOf(
                key(name) to record(pid, activities = 1, uris = listOf(TARGET_URI)),
            ),
            identities = mapOf(pid to SaveHostIdentity.Live("com.tyranor.next:new-owner")),
        )

        assertFalse(running(probe, hasSession = true))
    }

    @Test
    fun sessionWithoutAnyHostEvidenceIsBusyButNoSessionIsIdle() {
        val probe = FakeProbe(visibleHosts = emptyList())

        assertTrue(running(probe, hasSession = true))
        assertFalse(running(probe, hasSession = false))
    }

    @Test
    fun recordReadFailurePropagatesToCaller() {
        val failure = IllegalStateException("unreadable host records")
        val probe = FakeProbe(
            visibleHosts = emptyList(),
            recordsFailure = failure,
        )

        val actual = assertThrows(IllegalStateException::class.java) {
            running(probe, hasSession = false)
        }

        assertSame(failure, actual)
    }

    private fun running(
        probe: SaveHostProbe,
        elapsedMillis: Long = 10_000L,
        hasSession: Boolean = false,
        inStartGrace: Boolean = false,
    ): Boolean = hasRunningSaveHost(
        probe = probe,
        gameUri = TARGET_URI,
        hasSession = hasSession,
        inStartGrace = inStartGrace,
        elapsedMillis = elapsedMillis,
    )

    @Test
    fun aliasOfActiveSaveDirectoryBlocksEvenWhenOriginalUriDiffers() {
        val name = "com.tyranor.next:kirikiroid"
        val pid = 110
        val alias = "content://storage/overlapping-root/Demo"
        val probe = FakeProbe(listOf(SaveHostProcess(pid, name)),
            mapOf(key(name) to record(pid, 1, listOf(alias))), mapOf(pid to SaveHostIdentity.Live(name)))
        assertTrue(hasRunningSaveHost(probe, TARGET_URI, false, false, 10_000L, setOf(TARGET_URI, alias)))
    }

    @Test
    fun pendingNativeOwnerStaysBusyBeyondIdleGraceUntilAcknowledged() {
        val name = "com.tyranor.next:siglus"
        val pid = 111
        val state = record(pid, 0, emptyList(), idleSince = 1L).put("nativeOwnerPending", true)
        val probe = FakeProbe(listOf(SaveHostProcess(pid, name)),
            mapOf(key(name) to state), mapOf(pid to SaveHostIdentity.Live(name)))
        assertTrue(running(probe, elapsedMillis = 60_000L))
        state.put("nativeOwnerPending", false).put("idleSince", 60_000L)
        assertTrue(running(probe, elapsedMillis = 61_499L))
        assertFalse(running(probe, elapsedMillis = 61_500L))
    }

    @Test
    fun pendingOwnerForUnrelatedDirectoryDoesNotBlockTarget() {
        val name = "com.tyranor.next:siglus"
        val pid = 112
        val state = record(pid, 0, listOf("content://saves/unrelated"), idleSince = 0L)
            .put("nativeOwnerPending", true)
        val probe = FakeProbe(listOf(SaveHostProcess(pid, name)),
            mapOf(key(name) to state), mapOf(pid to SaveHostIdentity.Live(name)))
        assertFalse(running(probe, elapsedMillis = 60_000L))
    }

    private fun key(processName: String): String = SaveContentHash.text(processName)

    private fun record(
        pid: Int,
        activities: Int,
        uris: List<String>,
        idleSince: Long = 0L,
    ): JSONObject = JSONObject()
        .put("pid", pid)
        .put("activities", activities)
        .put(
            "uris",
            JSONArray().apply {
                uris.forEach { put(it) }
            },
        )
        .put("idleSince", idleSince)

    private class FakeProbe(
        private val visibleHosts: List<SaveHostProcess>?,
        private val records: Map<String, JSONObject> = emptyMap(),
        private val identities: Map<Int, SaveHostIdentity> = emptyMap(),
        private val recordsFailure: RuntimeException? = null,
    ) : SaveHostProbe {
        var visibleHostsCalls: Int = 0
            private set

        override fun visibleHosts(): List<SaveHostProcess>? {
            visibleHostsCalls++
            return visibleHosts
        }

        override fun records(): Map<String, JSONObject> =
            recordsFailure?.let { throw it } ?: records

        override fun identity(pid: Int): SaveHostIdentity =
            identities[pid] ?: SaveHostIdentity.Unknown
    }

    companion object {
        private const val TARGET_URI = "content://saves/target-game"
    }
}
