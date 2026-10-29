package com.tyranor.next.core.game.save.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SaveDirectoryActivationStoreTest {
    @get:Rule val temp = TemporaryFolder()

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
}
