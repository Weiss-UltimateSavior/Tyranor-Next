package com.tyranor.next.core.game.save.sync

import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class SaveDirectoryActivationStoreTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun pendingNestedSelectionBlocksParentAndCompletedParentStalesChildTransfers() {
        val root = temp.newFolder("nested-selections")
        val parent = SAVE_DIRECTORY_KEY_PREFIX + File(root, "game").canonicalPath
        val child = SAVE_DIRECTORY_KEY_PREFIX + File(root, "game/B/savedata").canonicalPath
        val disjoint = SAVE_DIRECTORY_KEY_PREFIX + File(root, "game-other").canonicalPath
        val store = SaveDirectoryActivationStore(root)
        val before = store.revision(parent)
        store.begin(child, "child-game", "child-token")
        val failure = assertThrows(SaveSyncException::class.java) { store.requireAvailable(parent, "parent-game") }
        assertEquals(SaveSyncError.SELECTION_INCOMPLETE, failure.code)
        store.requireAvailable(disjoint)
        assertTrue(before != store.revision(parent))
        store.complete(child, "child-game", "child-token")
        val childBefore = store.revision(child)
        store.begin(parent, "parent-game", "parent-token")
        store.complete(parent, "parent-game", "parent-token")
        assertTrue(childBefore != store.revision(child))
        assertEquals("", store.revision(disjoint))
    }

    @Test
    fun pendingOwnershipAndRevisionSurviveNewStoreInstanceUntilMatchingCompletion() {
        val root = temp.newFolder("activation")
        val activeKey = "directory-A"
        val owner = "alias-A"
        val token = "activation-token"
        val store = SaveDirectoryActivationStore(root)

        assertEquals("", store.revision(activeKey))
        store.requireAvailable(activeKey)
        store.begin(activeKey, owner, token)

        val pending = SaveDirectoryActivationStore.Pending(activeKey, owner, token)
        assertEquals(token, store.revision(activeKey))
        assertEquals(listOf(pending), store.owned(owner))

        val reopened = SaveDirectoryActivationStore(root)
        assertEquals(token, reopened.revision(activeKey))
        assertEquals(listOf(pending), reopened.owned(owner))
        reopened.requireAvailable(activeKey, owner)
        reopened.begin(activeKey, owner, token)
        assertEquals(token, reopened.revision(activeKey))

        reopened.complete(activeKey, owner, token)

        assertEquals(token, reopened.revision(activeKey))
        assertTrue(reopened.owned(owner).isEmpty())
        reopened.requireAvailable(activeKey, "alias-B")
    }

    @Test
    fun anotherOwnerOrTokenCannotReplacePendingOwnership() {
        val store = SaveDirectoryActivationStore(temp.newFolder("conflict"))
        val activeKey = "directory-A"
        val owner = "alias-A"
        val token = "activation-token"
        store.begin(activeKey, owner, token)

        val ownerFailure = assertThrows(SaveSyncException::class.java) {
            store.begin(activeKey, "alias-B", "other-token")
        }
        assertEquals(SaveSyncError.SELECTION_INCOMPLETE, ownerFailure.code)

        val tokenFailure = assertThrows(SaveSyncException::class.java) {
            store.begin(activeKey, owner, "other-token")
        }
        assertEquals(SaveSyncError.SELECTION_INCOMPLETE, tokenFailure.code)

        assertEquals(token, store.revision(activeKey))
        assertEquals(listOf(SaveDirectoryActivationStore.Pending(activeKey, owner, token)), store.owned(owner))
        assertTrue(store.owned("alias-B").isEmpty())
    }

    @Test
    fun requireAvailableFailsClosedWhenAnyOwnerIsPending() {
        val store = SaveDirectoryActivationStore(temp.newFolder("availability"))
        val activeKey = "directory-A"
        val owner = "alias-A"
        val token = "activation-token"
        store.begin(activeKey, owner, token)

        val unspecifiedFailure = assertThrows(SaveSyncException::class.java) {
            store.requireAvailable(activeKey)
        }
        val differentOwnerFailure = assertThrows(SaveSyncException::class.java) {
            store.requireAvailable(activeKey, "alias-B")
        }

        assertEquals(SaveSyncError.SELECTION_INCOMPLETE, unspecifiedFailure.code)
        assertEquals(SaveSyncError.SELECTION_INCOMPLETE, differentOwnerFailure.code)
        store.requireAvailable(activeKey, owner)
        assertTrue(store.owned(owner).isNotEmpty())
    }

    @Test
    fun completionRequiresTheMatchingOwnerAndTokenAndKeepsRevisionOnFailure() {
        val store = SaveDirectoryActivationStore(temp.newFolder("completion"))
        val activeKey = "directory-A"
        val owner = "alias-A"
        val token = "activation-token"
        store.begin(activeKey, owner, token)

        val ownerFailure = assertThrows(SaveSyncException::class.java) {
            store.complete(activeKey, "alias-B", token)
        }
        val tokenFailure = assertThrows(SaveSyncException::class.java) {
            store.complete(activeKey, owner, "other-token")
        }

        assertEquals(SaveSyncError.SELECTION_INCOMPLETE, ownerFailure.code)
        assertEquals(SaveSyncError.SELECTION_INCOMPLETE, tokenFailure.code)
        assertEquals(token, store.revision(activeKey))
        assertEquals(listOf(SaveDirectoryActivationStore.Pending(activeKey, owner, token)), store.owned(owner))
    }

    @Test
    fun ownedReturnsOnlyPendingDirectoriesForTheRequestedUri() {
        val store = SaveDirectoryActivationStore(temp.newFolder("owned"))
        store.begin("directory-A", "alias-A", "token-A")
        store.begin("directory-B", "alias-A", "token-B")
        store.begin("directory-C", "alias-B", "token-C")

        assertEquals(setOf(
            SaveDirectoryActivationStore.Pending("directory-A", "alias-A", "token-A"),
            SaveDirectoryActivationStore.Pending("directory-B", "alias-A", "token-B"),
        ), store.owned("alias-A").toSet())
        assertEquals(listOf(
            SaveDirectoryActivationStore.Pending("directory-C", "alias-B", "token-C"),
        ), store.owned("alias-B"))

        store.complete("directory-B", "alias-A", "token-B")

        assertEquals(listOf(
            SaveDirectoryActivationStore.Pending("directory-A", "alias-A", "token-A"),
        ), store.owned("alias-A"))
        assertEquals("token-B", store.revision("directory-B"))
    }

    @Test
    fun idempotentBeginAndUncommittedCompletionRestorePriorDirectoryRevision() {
        val root = temp.newFolder("rollback")
        val store = SaveDirectoryActivationStore(root)
        val activeKey = "directory-A"
        val priorCompleteToken = "completed-S-to-N"
        store.begin(activeKey, "alias-A", priorCompleteToken)
        store.complete(activeKey, "alias-A", priorCompleteToken)
        assertEquals(priorCompleteToken, store.revision(activeKey))

        val abortedToken = "uncommitted-new-selection"
        store.begin(activeKey, "alias-B", abortedToken)
        store.begin(activeKey, "alias-B", abortedToken)
        assertEquals(abortedToken, store.revision(activeKey))

        store.complete(activeKey, "alias-B", abortedToken, committed = false)

        val reopened = SaveDirectoryActivationStore(root)
        assertEquals(priorCompleteToken, reopened.revision(activeKey))
        assertTrue(reopened.owned("alias-B").isEmpty())
    }

    @Test
    fun completedOutcomeSurvivesRestartWithoutReleasingOtherAliasesBeforeJournalCleanup() {
        val root = temp.newFolder("pending-cleanup")
        val store = SaveDirectoryActivationStore(root)
        store.begin("directory", "alias-A", "token-A")
        store.prepareCompletion("directory", "alias-A", "token-A", committed = true)

        val reopened = SaveDirectoryActivationStore(root)
        assertEquals(true, reopened.owned("alias-A").single().committed)
        assertEquals(SaveSyncError.SELECTION_INCOMPLETE, assertThrows(SaveSyncException::class.java) {
            reopened.begin("directory", "alias-B", "token-B")
        }.code)
        reopened.resume("directory", "alias-A", "token-A")
        assertEquals(true, reopened.owned("alias-A").single().committed)
        reopened.complete("directory", "alias-A", "token-A")
        reopened.begin("directory", "alias-B", "token-B")
        reopened.complete("directory", "alias-B", "token-B")

        assertEquals(SaveSyncError.SELECTION_INCOMPLETE, assertThrows(SaveSyncException::class.java) {
            reopened.resume("directory", "alias-A", "token-A")
        }.code)
        assertEquals("token-B", reopened.revision("directory"))
        assertTrue(reopened.owned("alias-A").isEmpty())
    }

    @Test
    fun abortedOutcomeRetainsFenceAndOriginalRevisionUntilJournalCleanupCompletes() {
        val root = temp.newFolder("aborted-cleanup")
        val store = SaveDirectoryActivationStore(root)
        store.begin("directory", "alias-A", "previous")
        store.complete("directory", "alias-A", "previous")
        store.begin("directory", "alias-A", "aborted")
        store.prepareCompletion("directory", "alias-A", "aborted", committed = false)

        val reopened = SaveDirectoryActivationStore(root)
        assertEquals(false, reopened.owned("alias-A").single().committed)
        assertEquals("aborted", reopened.revision("directory"))
        assertEquals(SaveSyncError.SELECTION_INCOMPLETE, assertThrows(SaveSyncException::class.java) {
            reopened.complete("directory", "alias-A", "aborted", committed = true)
        }.code)
        reopened.complete("directory", "alias-A", "aborted", committed = false)
        assertEquals("previous", reopened.revision("directory"))
        assertTrue(reopened.owned("alias-A").isEmpty())
    }

    @Test
    fun ownedSkipsUnrelatedJsonSchemaAndIoFailuresWithoutRemovingTheirRecords() {
        val root = temp.newFolder("isolated-scan")
        val store = SaveDirectoryActivationStore(root)
        store.begin("directory", "alias-A", "token-A")
        val corrupt = record(root, "broken-json").apply { writeText("{broken-selection") }
        val quarantine = File(corrupt.parentFile, corrupt.name + ".corrupt").apply { writeText("original evidence") }
        val malformed = record(root, "broken-schema").apply {
            writeText(JSONObject().put("ownerUri", "alias-B").put("ownerToken", 5).toString())
        }
        val incomplete = record(root, "missing-active-key").apply {
            writeText(JSONObject().put("ownerUri", "alias-B").put("ownerToken", "token-B").toString())
        }
        val unreadable = record(root, "read-failure").apply { mkdir() }
        File(unreadable, "child").writeText("keep")
        val originalMalformed = malformed.readText()
        val originalIncomplete = incomplete.readText()

        assertEquals(listOf(SaveDirectoryActivationStore.Pending("directory", "alias-A", "token-A")),
            store.owned("alias-A"))

        assertEquals("{broken-selection", corrupt.readText())
        assertEquals("original evidence", quarantine.readText())
        assertEquals(originalMalformed, malformed.readText())
        assertEquals(originalIncomplete, incomplete.readText())
        assertEquals("keep", File(unreadable, "child").readText())
        assertFalse(File(malformed.parentFile, malformed.name + ".corrupt").exists())
        assertFalse(File(incomplete.parentFile, incomplete.name + ".corrupt").exists())
    }

    @Test
    fun ownedSkipsSchemaFailuresWhileDirectAccessAndRepairStayScopedToTheKnownDirectory() {
        val malformedStates = listOf(
            JSONObject().put("ownerUri", "alias-A"),
            JSONObject().put("activeKey", "directory").put("revision", "token-A")
                .put("ownerUri", "alias-A").put("ownerToken", 7),
            JSONObject().put("activeKey", "directory").put("revision", "token-A")
                .put("ownerUri", "alias-A").put("ownerToken", "token-A").put("completionCommitted", "true"),
            JSONObject().put("activeKey", "another-directory").put("revision", "token-A")
                .put("ownerUri", "alias-A").put("ownerToken", "token-A"),
        )
        malformedStates.forEachIndexed { index, state ->
            val root = temp.newFolder("owned-schema-$index")
            val file = record(root, "directory").apply { writeText(state.toString()) }
            val store = SaveDirectoryActivationStore(root)

            assertTrue(store.owned("alias-A").isEmpty())
            assertEquals(state.toString(), file.readText())
            assertFalse(File(file.parentFile, file.name + ".corrupt").exists())
            val failure = assertThrows(SaveSyncException::class.java) { store.revision("directory") }

            assertEquals(SaveSyncError.LOCAL_IO, failure.code)
            assertTrue(failure.cause is JSONException)
            assertEquals(state.toString(), file.readText())
            assertFalse(store.repairForRemoval("unrelated-directory", "alias-A"))
            assertTrue(file.isFile)
            assertTrue(store.repairForRemoval("directory", "alias-A"))
            assertTrue(store.revision("directory").isNotBlank())
            assertEquals(state.toString(), File(file.parentFile, file.name + ".corrupt").readText())
            assertTrue(store.owned("alias-A").isEmpty())
        }
    }

    @Test
    fun removalRepairsCurrentJsonCorruptionWithoutTouchingSiblingSelectionsOrQuarantine() {
        val root = temp.newFolder("scoped-json-repair")
        val store = SaveDirectoryActivationStore(root)
        val own = record(root, "directory").apply { writeText("{broken-own-selection") }
        val sibling = record(root, "sibling-directory").apply { writeText("{broken-sibling-selection") }
        val siblingQuarantine = File(sibling.parentFile, sibling.name + ".corrupt").apply { writeText("sibling evidence") }
        store.begin("valid-directory", "alias-B", "token-B")

        assertTrue(store.repairForRemoval("directory", "alias-A"))

        assertTrue(own.isFile)
        assertTrue(store.revision("directory").isNotBlank())
        assertEquals("{broken-own-selection", File(own.parentFile, own.name + ".corrupt").readText())
        assertEquals("{broken-sibling-selection", sibling.readText())
        assertEquals("sibling evidence", siblingQuarantine.readText())
        assertEquals(listOf(SaveDirectoryActivationStore.Pending("valid-directory", "alias-B", "token-B")),
            store.owned("alias-B"))
        assertFalse(store.repairForRemoval("directory", "alias-A"))
    }

    @Test
    fun removalRepairsUnownedInvalidSchemaButNormalOperationsFailClosed() {
        listOf(
            JSONObject(),
            JSONObject().put("activeKey", "directory").put("revision", 3),
            JSONObject().put("activeKey", "directory").put("revision", "prior")
                .put("ownerUri", JSONObject.NULL).put("ownerToken", "token"),
            JSONObject().put("activeKey", "directory").put("revision", "prior").put("completionCommitted", true),
        ).forEachIndexed { index, state ->
            val root = temp.newFolder("unowned-schema-$index")
            val file = record(root, "directory").apply { writeText(state.toString()) }
            val store = SaveDirectoryActivationStore(root)

            val failure = assertThrows(SaveSyncException::class.java) { store.requireAvailable("directory", "alias-A") }

            assertTrue(failure.cause is JSONException)
            assertEquals(state.toString(), file.readText())
            assertTrue(store.repairForRemoval("directory", "alias-A"))
            assertEquals(state.toString(), File(file.parentFile, file.name + ".corrupt").readText())
            store.begin("directory", "alias-A", "new-token")
            assertEquals("new-token", store.revision("directory"))
        }
    }

    @Test
    fun scopedRemovalReadFailuresPropagateAndKeepEvenEmptyDirectoryRecords() {
        listOf(false, true).forEach { nonempty ->
            val root = temp.newFolder("scoped-io-$nonempty")
            val file = record(root, "directory").apply { mkdir() }
            if (nonempty) File(file, "child").writeText("keep")
            val store = SaveDirectoryActivationStore(root)

            val failure = assertThrows(SaveSyncException::class.java) {
                store.repairForRemoval("directory", "alias-A")
            }

            assertEquals(SaveSyncError.LOCAL_IO, failure.code)
            assertTrue(failure.cause is IOException)
            assertTrue(file.isDirectory)
            if (nonempty) assertEquals("keep", File(file, "child").readText())
            assertFalse(File(file.parentFile, file.name + ".corrupt").exists())
        }
    }

    @Test
    fun repairPreservesValidPendingOwnersAndTheirRevision() {
        val root = temp.newFolder("valid-repair")
        val store = SaveDirectoryActivationStore(root)
        store.begin("directory", "alias-A", "token-A")
        val file = record(root, "directory")
        val original = file.readText()

        assertFalse(store.repairForRemoval("directory", "alias-A"))
        assertFalse(store.repairForRemoval("directory", "alias-B"))

        assertEquals(original, file.readText())
        assertEquals("token-A", store.revision("directory"))
        assertEquals(listOf(SaveDirectoryActivationStore.Pending("directory", "alias-A", "token-A")),
            store.owned("alias-A"))
        assertFalse(File(file.parentFile, file.name + ".corrupt").exists())
    }

    @Test
    fun repairCannotDiscardMalformedSelectionNamingAnotherUri() {
        val root = temp.newFolder("other-owner-repair")
        val state = JSONObject().put("ownerUri", "alias-B").put("ownerToken", 7)
        val file = record(root, "directory").apply { writeText(state.toString()) }
        val store = SaveDirectoryActivationStore(root)

        val failure = assertThrows(SaveSyncException::class.java) {
            store.repairForRemoval("directory", "alias-A")
        }

        assertEquals(SaveSyncError.SELECTION_INCOMPLETE, failure.code)
        assertEquals(state.toString(), file.readText())
        assertTrue(store.repairForRemoval("directory", "alias-B"))
        assertTrue(store.revision("directory").isNotBlank())
        assertEquals(state.toString(), File(file.parentFile, file.name + ".corrupt").readText())
    }

    @Test
    fun currentSchemaRepairKeepsOldSameUriMalformedSelectionAndInvalidatesEmptyRevisionTransfers() {
        val root = temp.newFolder("current-and-old-schema")
        val store = SaveDirectoryActivationStore(root)
        val old = record(root, "old-directory").apply {
            writeText(JSONObject().put("ownerUri", "alias-A").put("ownerToken", "old-token").toString())
        }
        val oldBytes = old.readText()
        val current = record(root, "current-directory").apply {
            writeText(JSONObject().put("activeKey", "current-directory").put("revision", "")
                .put("ownerUri", "alias-A").toString())
        }
        val currentBytes = current.readText()

        assertTrue(store.owned("alias-A").isEmpty())
        assertTrue(store.repairForRemoval("current-directory", "alias-A"))

        val repairedRevision = store.revision("current-directory")
        assertTrue(repairedRevision.isNotBlank())
        assertEquals("current-directory", JSONObject(current.readText()).getString("activeKey"))
        assertEquals(currentBytes, File(current.parentFile, current.name + ".corrupt").readText())
        assertEquals(oldBytes, old.readText())
        assertFalse(File(old.parentFile, old.name + ".corrupt").exists())
        assertTrue(store.owned("alias-A").isEmpty())
        assertTrue(store.owned("alias-B").isEmpty())
        assertFalse(store.repairForRemoval("current-directory", "alias-A"))
        assertEquals(repairedRevision, SaveDirectoryActivationStore(root).revision("current-directory"))
        assertEquals(SaveSyncError.SELECTION_INCOMPLETE, assertThrows(SaveSyncException::class.java) {
            store.resume("current-directory", "alias-B", "old-pinned-token")
        }.code)
        val failure = assertThrows(SaveSyncException::class.java) { store.requireAvailable("old-directory", "alias-B") }
        assertTrue(failure.cause is JSONException)
        assertEquals(oldBytes, old.readText())
    }

    private fun record(root: File, activeKey: String): File =
        File(File(root, "directory_selections").apply { mkdirs() }, "${SaveContentHash.text(activeKey)}.json")
}
