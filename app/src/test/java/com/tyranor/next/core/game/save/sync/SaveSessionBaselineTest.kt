package com.tyranor.next.core.game.save.sync

import org.json.JSONObject
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
    fun explicitAliasSelectionRetiresOnlyOtherAliasesPendingGameplayBaselines() {
        val sessions = SaveSyncStateStore(File(temp.root, "sessions"))
        val baselines = SaveSyncStateStore(File(temp.root, "baselines"))
        val selected = "alias-B"
        for (uri in listOf("alias-A", selected, "unrelated-C")) {
            val key = SaveContentHash.text(uri)
            sessions.save(key, JSONObject().put("token", uri).put("baseline", "old-progress"))
            baselines.save(key, JSONObject().put("contentHash", "old-progress"))
        }

        clearRelatedSessionBaselines(selected, setOf("alias-A", selected), sessions, baselines)

        assertEquals(0, sessions.load(SaveContentHash.text("alias-A")).length())
        assertEquals(0, baselines.load(SaveContentHash.text("alias-A")).length())
        for (uri in listOf(selected, "unrelated-C")) {
            assertEquals(uri, sessions.load(SaveContentHash.text(uri)).getString("token"))
            assertEquals("old-progress", baselines.load(SaveContentHash.text(uri)).getString("contentHash"))
        }
    }
}
