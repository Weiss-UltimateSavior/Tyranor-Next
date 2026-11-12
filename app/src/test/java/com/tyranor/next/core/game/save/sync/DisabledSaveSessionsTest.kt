package com.tyranor.next.core.game.save.sync

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DisabledSaveSessionsTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun cleanupQueuedAcrossReenableRemovesObservedSession() = runBlocking {
        val store = SaveSyncStateStore(temp.root)
        val key = SaveContentHash.text("reenable")
        val directory = "reenable-directory-A"
        store.save(key, session("old", directory).put("enabledGeneration", 0L))
        var enabled = false

        val cleanup = SaveSyncLocks.active(directory) {
            val observed = snapshotDisabledSaveSessions(store)
            val queued = async(start = CoroutineStart.UNDISPATCHED) {
                discardDisabledSaveSessions(store, observed)
            }
            assertFalse(queued.isCompleted)
            enabled = true
            queued
        }
        cleanup.await()

        assertTrue(enabled)
        assertFalse(store.load(key).has("token"))
    }

    @Test
    fun replacementTokenSurvivesQueuedCleanup() = runBlocking {
        val store = SaveSyncStateStore(temp.root)
        val key = SaveContentHash.text("replacement")
        val directory = "replacement-directory-A"
        store.save(key, session("old", directory).put("enabledGeneration", 0L))

        val cleanup = SaveSyncLocks.active(directory) {
            val observed = snapshotDisabledSaveSessions(store)
            val queued = async(start = CoroutineStart.UNDISPATCHED) {
                discardDisabledSaveSessions(store, observed)
            }
            assertFalse(queued.isCompleted)
            // The persisted disable generation rejects the old task before its mutex is released.
            assertFalse(saveSessionGenerationMatches(store.load(key), 1L))
            store.save(key, session("new", directory).put("enabledGeneration", 1L))
            queued
        }
        cleanup.await()

        assertEquals("new", store.load(key).optString("token"))
        assertTrue(saveSessionGenerationMatches(SaveSyncStateStore(temp.root).load(key), 1L))
    }

    @Test
    fun preGenerationSessionsAreAcceptedOnlyUntilFirstPersistedDisable() {
        val store = SaveSyncStateStore(temp.root)
        val key = SaveContentHash.text("legacy-generation")
        store.save(key, session("legacy", "directory-A"))
        assertTrue(saveSessionGenerationMatches(store.load(key), 0L))
        assertFalse(saveSessionGenerationMatches(SaveSyncStateStore(temp.root).load(key), 1L))
        assertFalse(saveSessionGenerationMatches(store.load(key).put("enabledGeneration", 1L), 2L))
    }

    @Test
    fun enablingPreviouslyDisabledLegacyInstallRetiresItsPreGenerationTickets() {
        val old = session("pre-upgrade", "directory-A")
        val migrated = saveSyncGenerationAfterToggle(null, wasEnabled = false, enabled = true)
        assertFalse(saveSessionGenerationMatches(old, migrated))
        assertEquals(0L, saveSyncGenerationAfterToggle(null, wasEnabled = true, enabled = true))
        assertEquals(5L, saveSyncGenerationAfterToggle(4L, wasEnabled = true, enabled = false))
        assertEquals(5L, saveSyncGenerationAfterToggle(5L, wasEnabled = false, enabled = true))
    }

    @Test
    fun cleanupWaitsForObservedDirectoryAfterCurrentBindingChanges() = runBlocking {
        val store = SaveSyncStateStore(temp.root)
        val key = SaveContentHash.text("directory-change")
        val originalDirectory = "directory-change-A"
        store.save(key, session("same", originalDirectory))

        val cleanup = SaveSyncLocks.active(originalDirectory) {
            val observed = snapshotDisabledSaveSessions(store)
            store.save(key, session("same", "directory-change-B"))
            val queued = async(start = CoroutineStart.UNDISPATCHED) {
                discardDisabledSaveSessions(store, observed)
            }
            assertFalse(queued.isCompleted)
            queued
        }
        cleanup.await()

        assertFalse(store.load(key).has("token"))
    }

    @Test
    fun snapshotSkipsMissingTokensAndUsesUriForLegacyDirectoryBinding() = runBlocking {
        val store = SaveSyncStateStore(temp.root)
        val firstKey = SaveContentHash.text("multiple-first")
        val secondKey = SaveContentHash.text("multiple-second")
        val legacyKey = SaveContentHash.text("multiple-legacy")
        val emptyKey = SaveContentHash.text("multiple-empty")
        val blankTokenKey = SaveContentHash.text("multiple-blank-token")
        store.save(firstKey, session("first", "multiple-directory-A"))
        store.save(secondKey, session("second", "multiple-directory-B"))
        store.save(legacyKey, JSONObject()
            .put("token", "legacy")
            .put("uri", "legacy-directory"))
        store.save(emptyKey, JSONObject())
        store.save(blankTokenKey, JSONObject().put("token", "").put("uri", "blank-directory"))

        val observed = snapshotDisabledSaveSessions(store)
        val byKey = observed.associateBy { it.key }

        assertEquals(setOf(firstKey, secondKey, legacyKey), byKey.keys)
        assertEquals("multiple-directory-A", byKey.getValue(firstKey).activeKey)
        assertEquals("multiple-directory-B", byKey.getValue(secondKey).activeKey)
        assertEquals("legacy-directory", byKey.getValue(legacyKey).activeKey)

        discardDisabledSaveSessions(store, observed)

        assertFalse(File(temp.root, "$firstKey.json").exists())
        assertFalse(File(temp.root, "$secondKey.json").exists())
        assertFalse(File(temp.root, "$legacyKey.json").exists())
        assertTrue(File(temp.root, "$emptyKey.json").exists())
        assertTrue(File(temp.root, "$blankTokenKey.json").exists())
    }

    private fun session(token: String, activeKey: String) = JSONObject()
        .put("token", token)
        .put("activeKey", activeKey)
        .put("uri", "game-uri")
}
