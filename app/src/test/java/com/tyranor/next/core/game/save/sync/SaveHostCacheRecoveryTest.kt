package com.tyranor.next.core.game.save.sync

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SaveHostCacheRecoveryTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun malformedHostRecordRemainsUnknownBusyUntilLifecycleRewrite() {
        val hosts = temp.newFolder("hosts")
        val corrupt = File(hosts, "${SaveContentHash.text(HOST_PROCESS)}.json").apply {
            writeText(CORRUPT_JSON)
        }
        val probe = StoredHostProbe(
            store = SaveSyncStateStore(hosts),
            visibleHosts = null,
            identities = mapOf(DEAD_PID to SaveHostIdentity.Dead),
        )

        assertTrue(running(probe))
        assertEquals(1, probe.visibleHostsCalls)
        assertTrue(corrupt.isFile)
        assertTrue(File(hosts, "${corrupt.name}.corrupt").exists())
        assertEquals(listOf(KEY), probe.store.keys())

        assertTrue(running(probe))
        assertEquals(2, probe.visibleHostsCalls)
        assertTrue(corrupt.isFile)
        assertEquals(CORRUPT_JSON, File(hosts, "${corrupt.name}.corrupt").readText())
        assertEquals(listOf(KEY), probe.store.keys())

        probe.store.save(KEY, hostRecord(DEAD_PID, activities = 0))
        assertEquals(DEAD_PID, probe.store.load(KEY).getInt("pid"))
        assertTrue(File(hosts, "${corrupt.name}.corrupt").exists())

        assertFalse(running(probe))
        assertEquals(3, probe.visibleHostsCalls)
        assertTrue(File(hosts, "${corrupt.name}.corrupt").exists())
    }

    @Test
    fun emptyAndNonpositivePidKnownRecordsRemainBusyOnRepeatedReads() {
        for ((index, text) in listOf("", "{}", "{\"pid\":0}", "{\"pid\":-12}").withIndex()) {
            val hosts = temp.newFolder("invalid-$index")
            val original = File(hosts, "$KEY.json").apply { writeText(text) }
            val probe = StoredHostProbe(SaveSyncStateStore(hosts), emptyList())
            repeat(2) {
                assertTrue(running(probe))
                assertEquals(text, original.readText())
            }
            probe.store.save(KEY, hostRecord(DEAD_PID, 0))
            assertFalse(running(StoredHostProbe(probe.store, emptyList(), mapOf(DEAD_PID to SaveHostIdentity.Dead))))
        }
    }

    @Test
    fun unrelatedCorruptKeyCannotMasqueradeAsKnownHost() {
        val hosts = temp.newFolder("unknown-key")
        val unknown = File(hosts, "${SaveContentHash.text("not-a-host")}.json").apply { writeText(CORRUPT_JSON) }
        val probe = StoredHostProbe(SaveSyncStateStore(hosts), emptyList())
        assertFalse(running(probe))
        assertEquals(CORRUPT_JSON, unknown.readText())
        assertFalse(File(hosts, "${unknown.name}.corrupt").exists())
    }

    @Test
    fun malformedHostRecordStillBlocksSessionEvidence() {
        val hosts = temp.newFolder("hosts-session")
        File(hosts, "${SaveContentHash.text(HOST_PROCESS)}.json").apply { writeText(CORRUPT_JSON) }
        val probe = StoredHostProbe(store = SaveSyncStateStore(hosts), visibleHosts = null)

        assertTrue(
            hasRunningSaveHost(
                probe = probe,
                gameUri = TARGET_URI,
                hasSession = true,
                inStartGrace = false,
                elapsedMillis = 0L,
            ),
        )
    }

    @Test
    fun visibleLiveHostRemainsBusyWithoutCachedRecord() {
        val hosts = temp.newFolder("visible-host")
        val probe = StoredHostProbe(
            store = SaveSyncStateStore(hosts),
            visibleHosts = listOf(SaveHostProcess(PID, HOST_PROCESS)),
            identities = mapOf(PID to SaveHostIdentity.Live(HOST_PROCESS)),
        )

        assertTrue(running(probe))
        assertEquals(1, probe.visibleHostsCalls)
        assertTrue(probe.store.keys().isEmpty())
    }

    @Test
    fun checkpointCorruptionRemainsStrict() {
        val root = temp.newFolder("checkpoint-root")
        val checkpoints = File(root, "checkpoints").apply { mkdirs() }
        val corrupt = File(checkpoints, "$KEY.json").apply { writeText(CORRUPT_JSON) }
        val store = SaveSyncStateStore(checkpoints)

        val failure = assertThrows(SaveSyncException::class.java) { store.load(KEY) }

        assertEquals(SaveSyncError.LOCAL_IO, failure.code)
        assertTrue(corrupt.exists())
    }

    @Test
    fun journalCorruptionRemainsStrict() {
        val pending = temp.newFolder("pending")
        val records = File(pending, "records").apply { mkdirs() }
        val corrupt = File(records, "$KEY.json").apply { writeText(CORRUPT_JSON) }

        val failure = assertThrows(SaveSyncException::class.java) {
            SaveActivationJournal(pending).load(KEY)
        }

        assertEquals(SaveSyncError.LOCAL_IO, failure.code)
        assertTrue(corrupt.exists())
    }

    private fun running(probe: SaveHostProbe): Boolean = hasRunningSaveHost(
        probe = probe,
        gameUri = TARGET_URI,
        hasSession = false,
        inStartGrace = false,
        elapsedMillis = 0L,
    )

    private fun hostRecord(pid: Int, activities: Int): JSONObject = JSONObject()
        .put("pid", pid)
        .put("activities", activities)
        .put("idleSince", 1L)
        .put("nativeOwnerPending", false)
        .put("uris", JSONArray())

    private class StoredHostProbe(
        val store: SaveSyncStateStore,
        private val visibleHosts: List<SaveHostProcess>?,
        private val identities: Map<Int, SaveHostIdentity> = emptyMap(),
    ) : SaveHostProbe {
        var visibleHostsCalls: Int = 0
            private set

        override fun visibleHosts(): List<SaveHostProcess>? {
            visibleHostsCalls++
            return visibleHosts
        }

        override fun records(): Map<String, JSONObject> =
            loadSaveHostRecords(store, setOf(KEY))

        override fun identity(pid: Int): SaveHostIdentity =
            identities[pid] ?: SaveHostIdentity.Unknown
    }

    companion object {
        private const val HOST_PROCESS = "com.tyranor.next:kirikiroid"
        private const val PID = 101
        private const val DEAD_PID = 202
        private const val TARGET_URI = "content://saves/target-game"
        private const val CORRUPT_JSON = "{not-json"
        private val KEY = SaveContentHash.text(HOST_PROCESS)
    }
}
