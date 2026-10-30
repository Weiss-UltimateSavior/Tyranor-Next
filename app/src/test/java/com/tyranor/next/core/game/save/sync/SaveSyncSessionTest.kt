package com.tyranor.next.core.game.save.sync

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SaveSyncSessionTest {
    @get:Rule val temp = TemporaryFolder()
    private val key = SaveContentHash.text("game-uri")

    @Test
    fun oldConfirmationWaitingForLaunchCannotMarkNewYurisSessionReturned() = runBlocking {
        val store = SaveSyncStateStore(temp.root)
        val old = session("old").put("leftApp", true)
        store.save(key, old)
        lateinit var event: kotlinx.coroutines.Deferred<Unit?>
        SaveSyncLocks.active("directory-A") {
            event = async(start = CoroutineStart.UNDISPATCHED) {
                withCurrentSaveSession(store, key, old, "directory-A") { current ->
                    requireExternalConfirmationSession(current, "old", "directory-A")
                    store.save(key, current.put("returned", true).put("externalExitConfirmedToken", "old"))
                }
            }
            store.save(key, session("new"))
        }
        assertNull(event.await())
        val current = store.load(key)
        assertEquals("new", current.getString("token"))
        assertFalse(current.optBoolean("returned"))
        assertFalse(current.optBoolean("leftApp"))
    }

    @Test
    fun oldBackgroundWaitingForLaunchCannotMarkNewSessionLeftApp() = runBlocking {
        val store = SaveSyncStateStore(temp.root)
        val old = session("old")
        store.save(key, old)
        lateinit var event: kotlinx.coroutines.Deferred<Unit?>
        SaveSyncLocks.active("directory-A") {
            event = async(start = CoroutineStart.UNDISPATCHED) {
                markSaveSessionBackground(store, key, old, "directory-A")
            }
            store.save(key, session("new"))
        }
        assertNull(event.await())
        assertEquals("new", store.load(key).getString("token"))
        assertFalse(store.load(key).optBoolean("leftApp"))
    }

    @Test
    fun differentDirectoryLocksCannotInterleaveSessionReadAndWrite() = runBlocking {
        val store = SaveSyncStateStore(temp.root)
        val old = session("old")
        store.save(key, old)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val oldEvent = async(start = CoroutineStart.UNDISPATCHED) {
            withCurrentSaveSession(store, key, old, "directory-A") { current ->
                entered.complete(Unit)
                release.await()
                store.save(key, current.put("leftApp", true))
            }
        }
        entered.await()
        val newLaunch = async(start = CoroutineStart.UNDISPATCHED) {
            SaveSyncLocks.active("directory-B") {
                SaveSyncLocks.session(key) { store.save(key, session("new").put("activeKey", "directory-B")) }
            }
        }
        assertFalse(newLaunch.isCompleted)
        release.complete(Unit)
        oldEvent.await()
        newLaunch.await()
        assertEquals("new", store.load(key).getString("token"))
        assertFalse(store.load(key).optBoolean("leftApp"))
        // Even a callback with the wrong mutex key cannot mutate the bound session.
        assertNull(markSaveSessionBackground(store, key, store.load(key), "directory-A"))
        assertFalse(store.load(key).optBoolean("leftApp"))
    }

    @Test
    fun externalConfirmationRejectsChangedTokenOrDirectoryBinding() {
        val current = session("current")
        for ((token, directory) in listOf("old" to "directory-A", "current" to "directory-B")) {
            val failure = assertThrows(SaveSyncException::class.java) {
                requireExternalConfirmationSession(current, token, directory)
            }
            assertEquals(SaveSyncError.LOCAL_STATE_CHANGED, failure.code)
            assertFalse(current.optBoolean("returned"))
        }
        requireExternalConfirmationSession(current, "current", "directory-A")
    }

    @Test
    fun emptyNewDirectoryCannotRetirePendingProgressFromOriginalDirectory() = runBlocking {
        verifyDirectoryChange(empty = true)
    }

    @Test
    fun oldBytesInNewDirectoryCannotUploadAgainstOriginalDirectoryBaseline() = runBlocking {
        verifyDirectoryChange(empty = false)
    }

    private suspend fun verifyDirectoryChange(empty: Boolean) {
        val store = SaveSyncStateStore(File(temp.root, "sessions"))
        val original = session("pending").put("baseline", "before-A")
        store.save(key, original)
        val rootA = temp.newFolder("A")
        File(rootA, "save").writeText("pending-progress-A")
        val rootB = temp.newFolder("B")
        if (!empty) File(rootB, "save").writeText("older-progress-B")
        var currentDirectory = "directory-B"
        var captures = 0
        var uploads = 0
        try {
            withCurrentSaveSession(store, key, original, "directory-A", { currentDirectory }) {
                captures++
                if (rootB.listFiles().orEmpty().isNotEmpty()) uploads++
                store.remove(key)
            }
            throw AssertionError("A task was allowed to read directory B")
        } catch (failure: SaveSyncException) {
            assertEquals(SaveSyncError.SAVE_LOCATION_CHANGED, failure.code)
        }
        assertEquals(0, captures)
        assertEquals(0, uploads)
        assertEquals(original.toString(), store.load(key).toString())
        assertEquals("pending-progress-A", File(rootA, "save").readText())
        // Returning to A resumes this exact ticket; no migration/re-baselining was performed.
        currentDirectory = "directory-A"
        assertEquals("pending-progress-A",
            withCurrentSaveSession(store, key, original, "directory-A", { currentDirectory }) {
                File(rootA, "save").readText()
            })
    }

    @Test
    fun directoryChangeWhileWaitingForLockIsRecheckedBeforeCapture() = runBlocking {
        val store = SaveSyncStateStore(temp.root)
        val original = session("pending")
        store.save(key, original)
        var directory = "directory-A"
        var captured = false
        lateinit var pending: kotlinx.coroutines.Deferred<SaveSyncError?>
        SaveSyncLocks.active("directory-A") {
            pending = async(start = CoroutineStart.UNDISPATCHED) {
                try {
                    withCurrentSaveSession(store, key, original, "directory-A", { directory }) { captured = true }
                    null
                } catch (failure: SaveSyncException) { failure.code }
            }
            directory = "directory-B"
        }
        assertEquals(SaveSyncError.SAVE_LOCATION_CHANGED, pending.await())
        assertFalse(captured)
        assertEquals("pending", store.load(key).getString("token"))
    }

    @Test
    fun legacySessionWithoutDirectoryBindingIsRetainedForExplicitSelection() = runBlocking {
        val store = SaveSyncStateStore(temp.root)
        val old = session("legacy").apply { remove("activeKey") }
        store.save(key, old)
        try {
            withCurrentSaveSession(store, key, old, "directory-A", { "directory-A" }) {
                throw AssertionError("Unbound session was adopted silently")
            }
            throw AssertionError("Missing directory binding accepted")
        } catch (failure: SaveSyncException) {
            assertEquals(SaveSyncError.SAVE_LOCATION_CHANGED, failure.code)
        }
        assertEquals(old.toString(), store.load(key).toString())
    }

    private fun session(token: String) = JSONObject().put("token", token)
        .put("activeKey", "directory-A").put("ownerPid", 7)
}
