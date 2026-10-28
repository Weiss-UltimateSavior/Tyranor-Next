package com.tyranor.next.core.game.save.sync

import org.json.JSONObject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SaveSessionBaselineTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun firstSaveRemainsChangedAfterFailedUploadAndRelaunchFromEmptyBaseline() {
        val directory = File(temp.root, "sessions")
        val key = SaveContentHash.text("game-uri")
        val firstSession = JSONObject().put("token", "first-launch").put("baseline", "")
        SaveSyncStateStore(directory).save(key, firstSession)
        val firstSave = SaveContentHash.text("first-progress-H")
        // Upload fails: the persisted session survives a process restart.
        val previous = SaveSyncStateStore(directory).load(key)
        val baseline = inheritedSessionBaseline(previous)
        assertEquals("", baseline)
        val relaunched = JSONObject().put("token", "second-launch").put("baseline", baseline)
        SaveSyncStateStore(directory).save(key, relaunched)
        val nextExitBaseline = inheritedSessionBaseline(SaveSyncStateStore(directory).load(key))
        assertTrue(firstSave != nextExitBaseline)
        assertEquals("", nextExitBaseline)
    }

    @Test
    fun absentSessionHasNoBaselineButNonemptyPendingBaselineIsInherited() {
        assertNull(inheritedSessionBaseline(null))
        assertNull(inheritedSessionBaseline(JSONObject().put("token", "missing-baseline")))
        assertNull(inheritedSessionBaseline(JSONObject().put("baseline", "")))
        assertEquals("known", inheritedSessionBaseline(JSONObject().put("token", "pending").put("baseline", "known")))
    }

    @Test
    fun directorySelectionRetiresEveryPersistedSessionAndBaselineForKey() = runBlocking {
        val sessions = SaveSyncStateStore(File(temp.root, "sessions"))
        val baselines = SaveSyncStateStore(File(temp.root, "baselines"))
        val sameDirectory = listOf("alias-A", "selected-alias-B", "unresolved-alias")
        for (uri in sameDirectory) {
            val key = SaveContentHash.text(uri)
            sessions.save(key, JSONObject().put("token", uri).put("baseline", "old-progress").put("activeKey", "directory-A"))
            baselines.save(key, JSONObject().put("contentHash", "old-progress").put("activeKey", "directory-A"))
        }
        val unrelatedKey = SaveContentHash.text("unrelated-C")
        sessions.save(unrelatedKey, JSONObject().put("token", "unrelated-C")
            .put("baseline", "unrelated-progress").put("activeKey", "directory-C"))
        baselines.save(unrelatedKey, JSONObject().put("contentHash", "unrelated-progress")
            .put("activeKey", "directory-C"))

        clearDirectorySessionBaselines("directory-A", sessions, baselines)

        for (uri in sameDirectory) {
            val key = SaveContentHash.text(uri)
            assertEquals(0, sessions.load(key).length())
            assertEquals(0, baselines.load(key).length())
        }
        assertEquals("unrelated-C", sessions.load(unrelatedKey).getString("token"))
        assertEquals("unrelated-progress", baselines.load(unrelatedKey).getString("contentHash"))
    }

    @Test
    fun directorySelectionRetainsWrongDirectoryAndLegacyPendingSessions() = runBlocking {
        val sessions = SaveSyncStateStore(File(temp.root, "sessions"))
        val baselines = SaveSyncStateStore(File(temp.root, "baselines"))
        val pendingKey = SaveContentHash.text("alias-pending-B")
        val legacyKey = SaveContentHash.text("legacy")
        sessions.save(pendingKey, JSONObject().put("token", "pending-B").put("activeKey", "directory-C"))
        baselines.save(pendingKey, JSONObject().put("contentHash", "B").put("activeKey", "directory-C"))
        sessions.save(legacyKey, JSONObject().put("token", "unbound-legacy"))
        baselines.save(legacyKey, JSONObject().put("contentHash", "unknown"))

        clearDirectorySessionBaselines("directory-A", sessions, baselines)

        assertEquals("pending-B", sessions.load(pendingKey).getString("token"))
        assertEquals("B", baselines.load(pendingKey).getString("contentHash"))
        assertEquals("unbound-legacy", sessions.load(legacyKey).getString("token"))
        assertEquals("unknown", baselines.load(legacyKey).getString("contentHash"))
    }
}
