package com.tyranor.next.core.game.save.sync

import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.game.model.ScanGame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.AccessDeniedException
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SaveSyncServiceTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun excludedGameIsSkippedByUriWhileSameTitleGameStillUploads() = runBlocking {
        val harness = Harness(temp)
        val excluded = harness.addGame("same-title")
        val other = harness.addGame("same-title")
        harness.service.setIdentity(excluded, "excluded-identity")
        harness.service.setIdentity(other, "other-identity")
        harness.setActive(excluded, "excluded-active")
        harness.setActive(other, "other-active")

        val report = harness.service.syncAll(SaveSyncDirection.UPLOAD, excludedUris = setOf(excluded.uri))

        assertTrue(report.failures.isEmpty())
        assertEquals(1, report.uploaded)
        assertTrue(harness.transport.versions(harness.remoteGame(excluded)).isEmpty())
        assertEquals(1, harness.transport.versions(harness.remoteGame(other)).size)
    }

    @Test
    fun excludedGameStillReservesItsRemoteIdentityDuringConflictCheck() = runBlocking {
        val harness = Harness(temp)
        val excluded = harness.addGame("excluded-conflict")
        val other = harness.addGame("other-conflict")
        harness.service.setIdentity(excluded, "shared-identity")
        harness.service.setIdentity(other, "shared-identity")
        harness.setActive(other, "other-active")

        val report = harness.service.syncAll(SaveSyncDirection.UPLOAD, excludedUris = setOf(excluded.uri))

        assertEquals(listOf(SaveSyncError.IDENTITY_CONFLICT), report.failures.map { it.error })
        assertEquals(listOf(other.uri), report.failures.map { it.gameUri })
        assertEquals(0, report.uploaded)
        assertTrue(harness.transport.versions(harness.remoteGame(other)).isEmpty())
    }

    @Test
    fun committedSelectionAfterSaveAdvancesStillImportsExplicitlyReselectedVersion() = runBlocking {
        val harness = Harness(temp)
        val game = harness.addGame("comitted-reselect")
        harness.identify(game)
        harness.setActive(game, "initial")
        val selected = harness.addSnapshot(game, "selected-S", 1_700_000_000_000L)
        harness.world.failNextRestoredMetadata()

        assertEquals(
            SaveActivationResult.METADATA_PENDING,
            harness.service.activateVersion(game, selected.id),
        )
        assertEquals(1, harness.world.importCount(game))
        assertEquals(selected.contentHash, harness.world.activeHash(game))

        harness.setActive(game, "played-N")
        assertEquals(
            SaveActivationResult.APPLIED,
            harness.service.activateVersion(game, selected.id),
        )

        assertEquals(2, harness.world.importCount(game))
        assertEquals(selected.contentHash, harness.world.activeHash(game))
        assertNull(harness.journal.load(harness.key(game)))
    }

    @Test
    fun transientMetadataFailureRetryDoesNotImportTheSameVersionTwice() = runBlocking {
        val harness = Harness(temp)
        val game = harness.addGame("metadata-retry")
        harness.identify(game)
        harness.setActive(game, "initial")
        val selected = harness.addSnapshot(game, "selected", 1_700_000_000_100L)
        harness.world.failNextRestoredMetadata()

        assertEquals(
            SaveActivationResult.METADATA_PENDING,
            harness.service.activateVersion(game, selected.id),
        )
        assertEquals(1, harness.world.importCount(game))
        assertEquals(selected.contentHash, harness.world.activeHash(game))
        val key = harness.key(game)
        val chainAfterFailure = JSONObject(harness.states.load(key).toString())
        assertEquals(chainAfterFailure.getString("promotionFromHash"), selected.contentHash)
        assertTrue(chainAfterFailure.getBoolean("promotionAwaitingChange"))

        assertEquals(
            SaveActivationResult.APPLIED,
            harness.service.activateVersion(game, selected.id),
        )

        assertEquals(1, harness.world.importCount(game))
        assertEquals(2, harness.world.restoredMetadataCount(game))
        assertEquals(1, harness.snapshots.list(key).count {
            it.contentHash == selected.contentHash
        })
        val chainAfterRetry = harness.states.load(key)
        assertEquals(
            chainAfterFailure.getLong("promotionAt"),
            chainAfterRetry.getLong("promotionAt"),
        )
        assertEquals(
            chainAfterFailure.getLong("promotionFromTime"),
            chainAfterRetry.getLong("promotionFromTime"),
        )
        assertEquals(selected.contentHash, chainAfterRetry.getString("promotionContentHash"))
        assertTrue(chainAfterRetry.getBoolean("promotionAwaitingChange"))
        assertNull(harness.journal.load(key))
    }

    @Test
    fun committedPendingWithSecondMetadataFailureAndAdvancedSaveReturnsPreviousPending() = runBlocking {
        val harness = Harness(temp)
        val game = harness.addGame("previous-pending")
        harness.identify(game)
        harness.setActive(game, "initial")
        val selected = harness.addSnapshot(game, "selected-S", 1_700_000_000_050L)
        harness.world.failNextRestoredMetadata()
        assertEquals(
            SaveActivationResult.METADATA_PENDING,
            harness.service.activateVersion(game, selected.id),
        )
        assertEquals(1, harness.world.importCount(game))

        val currentHash = harness.setActive(game, "current-N")
        harness.world.failNextRestoredMetadata()
        val result = harness.service.activateVersion(game, selected.id)

        assertEquals(SaveActivationResult.PREVIOUS_PENDING, result)
        assertEquals(currentHash, harness.world.activeHash(game))
        assertEquals(1, harness.world.importCount(game))
        assertEquals(2, harness.world.restoredMetadataCount(game))
        assertTrue(harness.journal.load(harness.key(game)) != null)
    }

    @Test
    fun corruptedJournalRescuesItsVerifiedArchiveBeforeAbandon() = runBlocking {
        val harness = Harness(temp)
        val game = harness.addGame("corrupt-journal")
        harness.identify(game)
        val currentHash = harness.setActive(game, "current-N")
        val key = harness.key(game)
        val selectedArchive = harness.archive("journal-selected", "selected-bytes")
        val selectedHash = SaveContentHash.archive(selectedArchive)
        val selected = SaveVersion(
            id = SaveVersionNames.create(1_700_000_000_200L, selectedHash),
            contentHash = selectedHash,
            createdAt = 1_700_000_000_200L,
            size = selectedArchive.length(),
            source = SaveVersionSource.CLOUD,
        )
        harness.journal.prepare(key, selected, selectedArchive, "current-N")
        File(harness.root, "pending/records/$key.json").writeText("{damaged", Charsets.UTF_8)

        harness.service.recoverSelection(game)

        assertNull(harness.journal.load(key))
        assertFalse(harness.journal.archive(key).exists())
        val rescued = harness.snapshots.list(key).single { it.contentHash == selectedHash }
        assertTrue(harness.snapshots.isValid(key, rescued))
        assertEquals(currentHash, harness.world.activeHash(game))
        assertEquals(0, harness.world.importCount(game))
    }

    @Test
    fun pullDownloadsCloudHeadAgainAfterItWasEvictedLocally() = runBlocking {
        val harness = Harness(temp)
        val game = harness.addGame("pull-evicted-head")
        harness.identify(game)
        harness.setActive(game, "current")
        val key = harness.key(game)
        val cloudArchive = harness.archive("cloud-head", "cloud-bytes")
        val cloudHash = SaveContentHash.archive(cloudArchive)
        val cloud = RemoteSaveVersion(
            name = SaveVersionNames.create(1_700_000_000_300L, cloudHash),
            contentHash = cloudHash,
            createdAt = 1_700_000_000_300L,
            size = cloudArchive.length(),
        )
        harness.transport.seed(harness.remoteGame(game), cloud, cloudArchive)

        val first = harness.service.syncGame(game, SaveSyncDirection.PULL)
        assertEquals(1, first.downloaded)
        assertTrue(harness.snapshots.list(key).any { it.contentHash == cloudHash })

        repeat(5) { index ->
            val archive = harness.archive("retained-$index", "retained-$index")
            harness.snapshots.add(
                key,
                archive,
                1_700_000_001_000L + index,
                SaveVersionSource.LOCAL,
            )
        }
        assertTrue(harness.snapshots.list(key).none { it.contentHash == cloudHash })

        val second = harness.service.syncGame(game, SaveSyncDirection.PULL)

        assertEquals(1, second.downloaded)
        assertEquals(2, harness.transport.downloadCount())
        val redownloaded = harness.snapshots.list(key).single { it.contentHash == cloudHash }
        assertTrue(harness.snapshots.isValid(key, redownloaded))
    }

    @Test
    fun restoredUploadBeforeFirstSaveStillPromotesLaterVersionWithSAndNTimes() = runBlocking {
        val harness = Harness(temp)
        val game = harness.addGame("restore-chain")
        harness.identify(game)
        harness.setActive(game, "initial")
        val selectedTime = 1_700_000_000_400L
        val selected = harness.addSnapshot(game, "selected-S", selectedTime)

        assertEquals(SaveActivationResult.APPLIED, harness.service.activateVersion(game, selected.id))
        assertEquals(
            1,
            harness.service.syncGame(game, SaveSyncDirection.UPLOAD).uploaded,
        )
        harness.setActive(game, "played-N")

        assertEquals(
            1,
            harness.service.syncGame(game, SaveSyncDirection.UPLOAD).uploaded,
        )

        val playedHash = harness.world.activeHash(game)
        val remote = harness.transport.versions(harness.remoteGame(game))
        val promoted = remote.single()
        assertEquals(playedHash, promoted.contentHash)
        assertTrue(promoted.restored)
        assertEquals(selectedTime, promoted.previousCreatedAt)
        assertEquals(1, harness.transport.uploadCount(selected.contentHash))
        assertEquals(1, harness.transport.uploadCount(playedHash))
        assertTrue(remote.none { it.contentHash == selected.contentHash })
        val history = harness.snapshots.list(harness.key(game)).single { it.contentHash == playedHash }
        assertTrue(history.restored)
        assertEquals(selectedTime, history.previousCreatedAt)
    }

    @Test
    fun localHistoryAndActivationCompleteWhileNetworkListIsSuspended() = runBlocking {
        val harness = Harness(temp)
        val game = harness.addGame("local-during-list")
        harness.identify(game)
        harness.setActive(game, "current-N")
        val selected = harness.addSnapshot(game, "selected-S", 1_700_000_000_500L)
        val gate = CompletableDeferred<Unit>()
        harness.transport.listGate = gate

        coroutineScope {
            val sync = async(Dispatchers.Default) {
                harness.service.syncGame(game, SaveSyncDirection.PULL)
            }
            try {
                withTimeout(5_000) { harness.transport.listEntered.await() }
                val versions = withTimeout(5_000) { harness.service.listVersions(game) }
                assertTrue(versions.any { it.id == selected.id })
                val activation = withTimeout(5_000) {
                    harness.service.activateVersion(game, selected.id)
                }
                assertEquals(SaveActivationResult.APPLIED, activation)
                assertEquals(selected.contentHash, harness.world.activeHash(game))
            } finally {
                gate.complete(Unit)
            }
            val report = withTimeout(5_000) { sync.await() }
            assertLocalStateChanged(report)
        }
    }

    @Test
    fun activationDuringStalledUploadDoesNotLoseItsLocalMetadata() = runBlocking {
        val harness = Harness(temp)
        val game = harness.addGame("activation-during-upload")
        harness.identify(game)
        harness.setActive(game, "current-N")
        val selected = harness.addSnapshot(game, "selected-S", 1_700_000_000_600L)
        val gate = CompletableDeferred<Unit>()
        harness.transport.uploadGate = gate
        val key = harness.key(game)

        coroutineScope {
            val sync = async(Dispatchers.Default) {
                harness.service.syncGame(game, SaveSyncDirection.UPLOAD)
            }
            lateinit var expectedRevision: String
            try {
                withTimeout(5_000) { harness.transport.uploadEntered.await() }
                assertEquals(
                    SaveActivationResult.APPLIED,
                    withTimeout(5_000) { harness.service.activateVersion(game, selected.id) },
                )
                val activationState = JSONObject(harness.states.load(key).toString())
                expectedRevision = activationState.getString("revision")
                assertEquals(selected.contentHash, activationState.getString("activationHash"))
            } finally {
                gate.complete(Unit)
            }
            val report = withTimeout(5_000) { sync.await() }
            assertLocalStateChanged(report)

            val after = harness.states.load(key)
            assertEquals(expectedRevision, after.getString("revision"))
            assertEquals(selected.contentHash, after.getString("activationHash"))
            assertEquals(selected.contentHash, harness.world.activeHash(game))
        }
    }

    @Test
    fun cancellationFromNetworkIsPropagatedUnchanged() = runBlocking {
        val harness = Harness(temp)
        val game = harness.addGame("network-cancellation")
        harness.identify(game)
        harness.setActive(game, "current")
        val cancellation = CancellationException("cancelled by caller")
        harness.transport.listFailure = cancellation

        try {
            harness.service.syncGame(game, SaveSyncDirection.PULL)
            fail("Cancellation was converted into a sync report")
        } catch (actual: CancellationException) {
            assertEquals(cancellation.javaClass, actual.javaClass)
            assertEquals(cancellation.message, actual.message)
            assertTrue(causeChainContains(actual, cancellation))
        }
    }

    @Test
    fun pruningFailureRetriesWithoutUploadingTheSamePublicationAgain() = runBlocking {
        val harness = Harness(temp)
        val game = harness.addGame("prune-retry")
        harness.identify(game)
        harness.setActive(game, "current")
        repeat(11) { index ->
            val hash = (index + 1).toString(16).padStart(64, '0')
            val version = RemoteSaveVersion(
                name = "old-$index.zip",
                contentHash = hash,
                createdAt = 1_700_000_000_000L + index,
                size = 10,
            )
            harness.transport.seed(harness.remoteGame(game), version)
        }
        harness.transport.failNextDelete()

        val first = harness.service.syncGame(game, SaveSyncDirection.UPLOAD)

        assertEquals(listOf(SaveSyncError.SERVER), first.failures.map { it.error })
        val currentHash = harness.world.activeHash(game)
        assertEquals(1, harness.transport.uploadCount(currentHash))
        val checkpointKey = harness.checkpointKey(game)
        assertTrue(harness.states.load(checkpointKey).getBoolean("needsPrune"))

        val second = harness.service.syncGame(game, SaveSyncDirection.UPLOAD)

        assertEquals(emptyList<SaveSyncFailure>(), second.failures)
        assertEquals(1, harness.transport.uploadCount(currentHash))
        assertEquals(10, harness.transport.versions(harness.remoteGame(game)).size)
        assertFalse(harness.states.load(checkpointKey).optBoolean("needsPrune"))
    }

    @Test
    fun cleanupBudgetConvergesOverRoundsAndPublishesNewVersionOnce() = runBlocking {
        val harness = Harness(temp)
        val game = harness.addGame("cleanup-budget")
        harness.identify(game)
        val newHash = harness.setActive(game, "new-version")
        val remoteGame = harness.remoteGame(game)
        seedCleanupHistory(harness, game, count = 65)
        val checkpointKey = harness.checkpointKey(game)

        val first = harness.service.syncGame(game, SaveSyncDirection.UPLOAD)
        val firstDeletes = harness.transport.deleteCount()
        val firstRemote = harness.transport.versions(remoteGame)
        val published = firstRemote.single { it.contentHash == newHash }

        assertEquals(1, first.uploaded)
        assertEquals(1, first.cleanupPending)
        assertEquals(0, first.busy)
        assertTrue(first.failures.isEmpty())
        assertEquals(20, firstDeletes)
        assertEquals(46, firstRemote.size)
        assertTrue(firstRemote.any { it.name == published.name })
        assertTrue(harness.states.load(checkpointKey).getBoolean("needsPrune"))
        assertTrue(harness.hasPendingPromotion(game))

        harness.world.clearActive(game)
        assertFalse(harness.world.hasActive(game))
        val secondVerifies = harness.transport.verifyCount()
        val second = harness.service.syncGame(game, SaveSyncDirection.UPLOAD)
        val secondDeletes = harness.transport.deleteCount() - firstDeletes
        val secondRemote = harness.transport.versions(remoteGame)

        assertEquals(0, second.uploaded)
        assertEquals(1, second.cleanupPending)
        assertEquals(0, second.busy)
        assertTrue(second.failures.isEmpty())
        assertEquals(20, secondDeletes)
        assertEquals(26, secondRemote.size)
        assertTrue(secondRemote.any { it.name == published.name && it.contentHash == newHash })
        assertTrue(harness.states.load(checkpointKey).getBoolean("needsPrune"))
        assertTrue(harness.hasPendingPromotion(game))
        assertEquals(1, harness.transport.verifyCount() - secondVerifies)
        assertEquals(1, harness.transport.uploadCount(newHash))

        val finalVerifies = harness.transport.verifyCount()
        val final = harness.service.syncGame(game, SaveSyncDirection.UPLOAD)
        val finalDeletes = harness.transport.deleteCount() - firstDeletes - secondDeletes
        val finalRemote = harness.transport.versions(remoteGame)

        assertEquals(0, final.uploaded)
        assertEquals(0, final.cleanupPending)
        assertEquals(0, final.busy)
        assertTrue(final.failures.isEmpty())
        assertEquals(16, finalDeletes)
        assertEquals(10, finalRemote.size)
        assertTrue(finalRemote.any { it.name == published.name && it.contentHash == newHash })
        assertFalse(harness.states.load(checkpointKey).optBoolean("needsPrune"))
        assertFalse(harness.hasPendingPromotion(game))
        assertEquals(1, harness.transport.verifyCount() - finalVerifies)
        assertFalse(harness.world.hasActive(game))
        assertEquals(1, harness.transport.uploadCount(newHash))
    }

    @Test
    fun unlimitedRestoredPromotionDrainsSupersededSourcesInDeleteBatches() = runBlocking {
        val harness = Harness(temp, remoteLimit = 0)
        val game = harness.addGame("unlimited-promotion-cleanup")
        harness.identify(game)
        harness.setActive(game, "before-S")
        val selectedTime = 1_700_000_000_010L
        val selected = harness.addSnapshot(game, "selected-S", selectedTime)
        assertEquals(SaveActivationResult.APPLIED, harness.service.activateVersion(game, selected.id))

        val remoteGame = harness.remoteGame(game)
        repeat(65) { index ->
            val createdAt = 1_700_000_010_000L + index
            harness.transport.seed(
                remoteGame,
                RemoteSaveVersion(
                    name = SaveVersionNames.create(createdAt, selected.contentHash),
                    contentHash = selected.contentHash,
                    createdAt = createdAt,
                    size = selected.size,
                ),
            )
        }
        val newHash = harness.setActive(game, "played-N")
        val key = harness.key(game)
        val checkpointKey = harness.checkpointKey(game)
        val partialDeletes = listOf(20, 20, 20)
        val partialRemoteSizes = listOf(46, 26, 6)
        var deletesBefore = harness.transport.deleteCount()
        lateinit var publicationName: String
        var promotionAt = 0L

        partialDeletes.forEachIndexed { round, expectedDeletes ->
            val report = harness.service.syncGame(game, SaveSyncDirection.UPLOAD)
            val deletes = harness.transport.deleteCount() - deletesBefore
            deletesBefore += deletes
            val remote = harness.transport.versions(remoteGame)
            val publication = remote.single { it.contentHash == newHash }

            assertEquals(expectedDeletes, deletes)
            assertEquals(partialRemoteSizes[round], remote.size)
            assertEquals(1, remote.count { it.contentHash == newHash })
            assertEquals(1, report.cleanupPending)
            assertEquals(0, report.busy)
            assertTrue(report.failures.isEmpty())
            assertEquals(1, harness.transport.uploadInvocationCount(newHash))
            if (round == 0) publicationName = publication.name
            else assertEquals(publicationName, publication.name)

            assertTrue(harness.hasPendingPromotion(game))
            val state = harness.states.load(key)
            if (round == 0) promotionAt = state.getLong("promotionAt")
            else assertEquals(promotionAt, state.getLong("promotionAt"))
            assertTrue(promotionAt > 0)
            assertTrue(state.getBoolean("promotionAwaitingChange"))
            val checkpoint = harness.states.load(checkpointKey)
            assertEquals(publicationName, checkpoint.getString("promotionPublication"))
            assertEquals(promotionAt, checkpoint.getLong("publishingPromotionAt"))
            assertTrue(checkpoint.optLong("promotedAt") < promotionAt)
        }

        val finalReport = harness.service.syncGame(game, SaveSyncDirection.UPLOAD)
        val finalDeletes = harness.transport.deleteCount() - deletesBefore
        val finalRemote = harness.transport.versions(remoteGame)
        val finalState = harness.states.load(key)
        val finalCheckpoint = harness.states.load(checkpointKey)

        assertEquals(5, finalDeletes)
        assertEquals(1, finalRemote.size)
        assertEquals(0, finalReport.cleanupPending)
        assertFalse(harness.hasPendingPromotion(game))
        assertEquals(listOf(newHash), finalRemote.map { it.contentHash })
        assertTrue(finalRemote.none { it.contentHash == selected.contentHash })
        assertEquals(1, harness.transport.uploadInvocationCount(newHash))
        assertFalse(finalState.getBoolean("promotionAwaitingChange"))
        assertEquals(promotionAt, finalCheckpoint.getLong("promotedAt"))
    }

    @Test
    fun retentionBudgetCapDoesNotCommitRestoredPromotionBeforeNextVersion() = runBlocking {
        val harness = Harness(temp)
        val game = harness.addGame("promotion-retention-cap")
        harness.identify(game)
        harness.setActive(game, "before-S")
        val selectedTime = 1_700_000_000_010L
        val selected = harness.addSnapshot(game, "selected-S", selectedTime)
        assertEquals(SaveActivationResult.APPLIED, harness.service.activateVersion(game, selected.id))

        val remoteGame = harness.remoteGame(game)
        repeat(3) { index ->
            val createdAt = selectedTime + index + 1
            harness.transport.seed(
                remoteGame,
                RemoteSaveVersion(
                    name = SaveVersionNames.create(createdAt, selected.contentHash),
                    contentHash = selected.contentHash,
                    createdAt = createdAt,
                    size = selected.size,
                ),
            )
        }
        seedCleanupHistory(harness, game, count = 65)
        val firstHash = harness.setActive(game, "played-N")
        val key = harness.key(game)
        val checkpointKey = harness.checkpointKey(game)

        val first = harness.service.syncGame(game, SaveSyncDirection.UPLOAD)
        val firstRemote = harness.transport.versions(remoteGame)
        val firstCheckpoint = harness.states.load(checkpointKey)
        val firstState = harness.states.load(key)

        assertEquals(1, first.cleanupPending)
        assertEquals(1, first.uploaded)
        assertEquals(20, harness.transport.deleteCount())
        assertEquals(1, firstRemote.count { it.contentHash == firstHash })
        assertEquals(0, firstRemote.count { it.contentHash == selected.contentHash })
        assertTrue(firstState.getBoolean("promotionAwaitingChange"))
        assertFalse(firstCheckpoint.has("promotedAt"))
        assertTrue(firstCheckpoint.getBoolean("needsPrune"))

        val laterHash = harness.setActive(game, "played-M")
        val deletesBefore = harness.transport.deleteCount()
        val second = harness.service.syncGame(game, SaveSyncDirection.UPLOAD)
        val secondDeletes = harness.transport.deleteCount() - deletesBefore
        val secondRemote = harness.transport.versions(remoteGame)
        val secondCheckpoint = harness.states.load(checkpointKey)
        val secondState = harness.states.load(key)
        val later = secondRemote.single { it.contentHash == laterHash }

        assertEquals(1, second.cleanupPending)
        assertEquals(1, second.uploaded)
        assertTrue(second.failures.isEmpty())
        assertEquals(20, secondDeletes)
        assertTrue(later.restored)
        assertEquals(selectedTime, later.previousCreatedAt)
        assertTrue(secondState.getBoolean("promotionAwaitingChange"))
        assertFalse(secondCheckpoint.has("promotedAt"))
        assertTrue(secondCheckpoint.getBoolean("needsPrune"))
        assertTrue(harness.hasPendingPromotion(game))
        assertEquals(1, harness.transport.uploadInvocationCount(firstHash))
        assertEquals(1, harness.transport.uploadInvocationCount(laterHash))
    }

    @Test
    fun emptyActiveCleanupDoesNotCreateUploadPinWhenCacheHasNoSpace() = runBlocking {
        val harness = Harness(temp)
        val game = harness.addGame("empty-active-low-space-cleanup")
        harness.identify(game)
        val newHash = harness.setActive(game, "new-version")
        seedCleanupHistory(harness, game, count = 65)
        val remoteGame = harness.remoteGame(game)
        val checkpointKey = harness.checkpointKey(game)

        val first = harness.service.syncGame(game, SaveSyncDirection.UPLOAD)
        val firstDeletes = harness.transport.deleteCount()
        val firstRemote = harness.transport.versions(remoteGame)
        assertEquals(1, first.cleanupPending)
        assertEquals(0, first.busy)
        assertTrue(first.failures.isEmpty())
        assertEquals(20, firstDeletes)
        assertEquals(46, firstRemote.size)
        assertEquals(1, firstRemote.count { it.contentHash == newHash })
        assertEquals(1, harness.transport.uploadInvocationCount(newHash))

        harness.world.clearActive(game)
        val unusableCache = harness.makeCacheUnusable()
        assertEquals(0L, unusableCache.usableSpace)
        assertFalse(harness.world.hasActive(game))

        val secondVerifies = harness.transport.verifyCount()
        val second = harness.service.syncGame(game, SaveSyncDirection.UPLOAD)
        val secondDeletes = harness.transport.deleteCount() - firstDeletes
        val secondRemote = harness.transport.versions(remoteGame)

        assertEquals(1, second.cleanupPending)
        assertEquals(0, second.busy)
        assertEquals(0, second.downloaded)
        assertTrue(second.failures.isEmpty())
        assertEquals(20, secondDeletes)
        assertEquals(26, secondRemote.size)
        assertEquals(1, secondRemote.count { it.contentHash == newHash })
        assertEquals(1, harness.transport.verifyCount() - secondVerifies)
        assertTrue(harness.states.load(checkpointKey).getBoolean("needsPrune"))
        assertTrue(harness.hasPendingPromotion(game))

        val finalVerifies = harness.transport.verifyCount()
        val final = harness.service.syncGame(game, SaveSyncDirection.UPLOAD)
        val finalDeletes = harness.transport.deleteCount() - firstDeletes - secondDeletes
        val finalRemote = harness.transport.versions(remoteGame)

        assertEquals(0, final.cleanupPending)
        assertEquals(0, final.busy)
        assertEquals(0, final.downloaded)
        assertTrue(final.failures.isEmpty())
        assertEquals(16, finalDeletes)
        assertEquals(10, finalRemote.size)
        assertEquals(1, finalRemote.count { it.contentHash == newHash })
        assertEquals(1, harness.transport.verifyCount() - finalVerifies)
        assertFalse(harness.states.load(checkpointKey).optBoolean("needsPrune"))
        assertFalse(harness.hasPendingPromotion(game))
        assertEquals(1, harness.transport.uploadInvocationCount(newHash))
        assertEquals(0, harness.transport.downloadCount())
        assertFalse(harness.world.hasActive(game))
    }

    @Test
    fun cleanupBudgetDoesNotStarveNewActiveVersionInLaterRound() = runBlocking {
        val harness = Harness(temp)
        val game = harness.addGame("cleanup-new-version")
        harness.identify(game)
        val firstHash = harness.setActive(game, "first-new-version")
        seedCleanupHistory(harness, game, count = 65)
        val remoteGame = harness.remoteGame(game)
        val checkpointKey = harness.checkpointKey(game)

        val first = harness.service.syncGame(game, SaveSyncDirection.UPLOAD)
        val firstDeletes = harness.transport.deleteCount()
        assertEquals(1, first.cleanupPending)
        assertEquals(1, first.uploaded)
        assertEquals(20, firstDeletes)
        assertEquals(1, harness.transport.versions(remoteGame).count { it.contentHash == firstHash })

        val laterHash = harness.setActive(game, "later-new-version")
        val secondDeletesBefore = harness.transport.deleteCount()
        val second = harness.service.syncGame(game, SaveSyncDirection.UPLOAD)
        val secondDeletes = harness.transport.deleteCount() - secondDeletesBefore
        val secondRemote = harness.transport.versions(remoteGame)

        assertEquals(1, second.cleanupPending)
        assertEquals(1, second.uploaded)
        assertEquals(0, second.busy)
        assertTrue(second.failures.isEmpty())
        assertEquals(20, secondDeletes)
        assertEquals(1, secondRemote.count { it.contentHash == firstHash })
        assertEquals(1, secondRemote.count { it.contentHash == laterHash })
        assertEquals(1, harness.transport.uploadInvocationCount(firstHash))
        assertEquals(1, harness.transport.uploadInvocationCount(laterHash))
        assertTrue(harness.states.load(checkpointKey).getBoolean("needsPrune"))
        assertTrue(harness.hasPendingPromotion(game))

        val finalDeletesBefore = harness.transport.deleteCount()
        val final = harness.service.syncGame(game, SaveSyncDirection.UPLOAD)
        val finalDeletes = harness.transport.deleteCount() - finalDeletesBefore
        val finalRemote = harness.transport.versions(remoteGame)

        assertEquals(0, final.cleanupPending)
        assertEquals(0, final.uploaded)
        assertEquals(0, final.busy)
        assertTrue(final.failures.isEmpty())
        assertEquals(17, finalDeletes)
        assertEquals(10, finalRemote.size)
        assertEquals(1, finalRemote.count { it.contentHash == firstHash })
        assertEquals(1, finalRemote.count { it.contentHash == laterHash })
        assertEquals(1, harness.transport.uploadInvocationCount(firstHash))
        assertEquals(1, harness.transport.uploadInvocationCount(laterHash))
        assertFalse(harness.states.load(checkpointKey).optBoolean("needsPrune"))
        assertFalse(harness.hasPendingPromotion(game))
    }

    @Test
    fun cleanupPendingSurvivesNewVersionUploadFailure() = runBlocking {
        val harness = Harness(temp)
        val game = harness.addGame("cleanup-upload-failure")
        harness.identify(game)
        harness.setActive(game, "first-version")
        seedCleanupHistory(harness, game, count = 65)
        val remoteGame = harness.remoteGame(game)
        val first = harness.service.syncGame(game, SaveSyncDirection.UPLOAD)
        assertEquals(1, first.cleanupPending)
        assertEquals(1, first.uploaded)

        val laterHash = harness.setActive(game, "failed-later-version")
        harness.transport.failNextUpload(SaveSyncException(SaveSyncError.NETWORK))
        val deletesBefore = harness.transport.deleteCount()
        val failed = harness.service.syncGame(game, SaveSyncDirection.UPLOAD)

        assertEquals(1, failed.cleanupPending)
        assertEquals(0, failed.uploaded)
        assertEquals(listOf(SaveSyncError.NETWORK), failed.failures.map { it.error })
        assertEquals(20, harness.transport.deleteCount() - deletesBefore)
        assertEquals(1, harness.transport.uploadInvocationCount(laterHash))
        assertTrue(harness.transport.versions(remoteGame).none { it.contentHash == laterHash })
        assertTrue(harness.hasPendingPromotion(game))
    }

    @Test
    fun gameStartingAfterFirstCleanupDeleteStopsRemainingDeletesAsBusy() = runBlocking {
        val harness = Harness(temp)
        val game = harness.addGame("cleanup-game-started")
        harness.identify(game)
        val activeHash = harness.setActive(game, "current-version")
        seedCleanupHistory(harness, game, count = 65)
        val checkpointKey = harness.checkpointKey(game)
        val first = harness.service.syncGame(game, SaveSyncDirection.UPLOAD)
        assertEquals(1, first.cleanupPending)

        val deletesBefore = harness.transport.deleteCount()
        var callbacks = 0
        harness.transport.onDelete = {
            callbacks++
            harness.world.isRunning = true
        }
        val blocked = harness.service.syncGame(game, SaveSyncDirection.UPLOAD)
        val deletes = harness.transport.deleteCount() - deletesBefore

        assertEquals(1, blocked.busy)
        assertEquals(0, blocked.cleanupPending)
        assertTrue(blocked.failures.isEmpty())
        assertEquals(1, callbacks)
        assertEquals(1, deletes)
        assertEquals(1, harness.transport.versions(harness.remoteGame(game)).count { it.contentHash == activeHash })
        assertTrue(harness.states.load(checkpointKey).getBoolean("needsPrune"))
        assertTrue(harness.hasPendingPromotion(game))
    }

    @Test
    fun downloadTemporaryLowSpaceReturnsStorageFullBeforeNetworkDownload() = runBlocking {
        val harness = Harness(temp)
        val game = harness.addGame("download-low-space")
        harness.identify(game)
        harness.setActive(game, "current")
        val cloud = harness.archive("cloud-download", "cloud bytes")
        val cloudHash = SaveContentHash.archive(cloud)
        val cloudAt = 1_700_000_000_900L
        val remote = RemoteSaveVersion(
            name = SaveVersionNames.create(cloudAt, cloudHash),
            contentHash = cloudHash,
            createdAt = cloudAt,
            size = cloud.length(),
        )
        harness.transport.seed(harness.remoteGame(game), remote, cloud)
        assertEquals(0L, harness.makeCacheUnusable().usableSpace)

        val report = harness.service.syncGame(game, SaveSyncDirection.PULL)

        assertEquals(listOf(SaveSyncError.STORAGE_FULL), report.failures.map { it.error })
        assertEquals(0, report.downloaded)
        assertEquals(0, report.busy)
        assertEquals(0, harness.transport.downloadCount())
        assertFalse(SaveSyncWorkPolicy.shouldScheduleFallback(report))
        assertEquals(SaveSyncWorkOutcome.FAILURE, SaveSyncWorkPolicy.outcome(report, fallback = true))
    }

    @Test
    fun emptyActiveCleanupRetiresMissingReplacement() = runBlocking {
        val harness = Harness(temp)
        val game = harness.addGame("missing-cleanup-replacement")
        harness.identify(game)
        val key = harness.key(game)
        val checkpointKey = harness.checkpointKey(game)
        val missingHash = "a".repeat(64)
        val missingName = SaveVersionNames.create(1_700_000_000_950L, missingHash)
        harness.states.save(checkpointKey, JSONObject()
            .put("gameKey", key)
            .put("needsPrune", true)
            .put("publicationIntent", missingName)
            .put("publicationConfirmed", false))
        assertEquals(0L, harness.makeCacheUnusable().usableSpace)

        val report = harness.service.syncGame(game, SaveSyncDirection.UPLOAD)

        assertTrue(report.failures.isEmpty())
        assertEquals(0, report.uploaded)
        assertEquals(0, report.downloaded)
        assertEquals(0, report.busy)
        assertEquals(0, report.cleanupPending)
        assertEquals(0, harness.transport.uploadInvocationCount(missingHash))
        assertEquals(0, harness.transport.downloadCount())
        assertFalse(harness.states.load(checkpointKey).optBoolean("needsPrune"))
    }

    @Test
    fun emptyActiveCleanupDiscardsCorruptReplacement() = runBlocking {
        val harness = Harness(temp)
        val game = harness.addGame("corrupt-cleanup-replacement")
        harness.identify(game)
        val key = harness.key(game)
        val checkpointKey = harness.checkpointKey(game)
        val corruptHash = "b".repeat(64)
        val corruptName = SaveVersionNames.create(1_700_000_000_951L, corruptHash)
        harness.states.save(checkpointKey, JSONObject()
            .put("gameKey", key)
            .put("needsPrune", true)
            .put("publicationIntent", corruptName)
            .put("publicationConfirmed", false))
        val remoteGame = harness.remoteGame(game)
        harness.transport.seed(remoteGame,
            RemoteSaveVersion(corruptName, corruptHash, 1_700_000_000_951L, 128L, "\"etag\""))
        harness.transport.markCorrupt(corruptName)
        harness.makeCacheUnusable()

        val report = harness.service.syncGame(game, SaveSyncDirection.UPLOAD)

        assertTrue(report.failures.isEmpty())
        assertEquals(0, report.uploaded)
        assertEquals(0, report.downloaded)
        assertEquals(0, report.busy)
        assertEquals(0, report.cleanupPending)
        assertFalse(harness.states.load(checkpointKey).optBoolean("needsPrune"))
        assertTrue(harness.transport.versions(remoteGame).isEmpty())
        assertEquals(1, harness.transport.verifyCount())
    }

    @Test
    fun confirmedCleanupTargetIsNotDownloadedAgain() = runBlocking {
        val harness = Harness(temp)
        val game = harness.addGame("confirmed-cleanup-target")
        harness.identify(game)
        val key = harness.key(game)
        val checkpointKey = harness.checkpointKey(game)
        val hash = "c".repeat(64)
        val name = SaveVersionNames.create(1_700_000_000_953L, hash)
        harness.states.save(checkpointKey, JSONObject()
            .put("gameKey", key)
            .put("needsPrune", true)
            .put("publicationIntent", name)
            .put("publicationConfirmed", true)
            .put("publicationTargetHash", hash)
            .put("publicationTargetSize", 128L)
            .put("publicationTargetEtag", "\"etag\""))
        val remoteGame = harness.remoteGame(game)
        harness.transport.seed(remoteGame, RemoteSaveVersion(name, hash, 1_700_000_000_953L, 128L, "\"etag\""))
        harness.makeCacheUnusable()

        val report = harness.service.syncGame(game, SaveSyncDirection.UPLOAD)

        assertTrue(report.failures.isEmpty())
        assertEquals(0, harness.transport.verifyCount())
        assertFalse(harness.states.load(checkpointKey).optBoolean("needsPrune"))
    }

    @Test
    fun activationWaitsForMutationGateAndRechecksRunningAfterLaunch() = runBlocking {
        val harness = Harness(temp)
        val game = harness.addGame("mutation-gate-activation")
        harness.identify(game)
        harness.setActive(game, "initial")
        val selected = harness.addSnapshot(game, "selected-mutation", 1_700_000_000_010L)
        coroutineScope {
            val held = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val gate = async {
                SaveSyncLocks.mutation {
                    held.complete(Unit)
                    release.await()
                }
            }
            held.await()
            harness.world.isRunning = false
            val activation = async {
                try {
                    harness.service.activateVersion(game, selected.id)
                    null
                } catch (caught: SaveSyncException) {
                    caught
                }
            }
            awaitPreparedActivation(harness, harness.key(game))
            harness.world.isRunning = true
            release.complete(Unit)
            gate.await()
            val failure = activation.await()
            assertEquals(SaveSyncError.GAME_RUNNING, failure?.code)
        }
    }

    @Test
    fun unquotedEtagDoesNotReuseConfirmation() = runBlocking {
        val harness = Harness(temp)
        val game = harness.addGame("unquoted-etag-cleanup")
        harness.identify(game)
        val key = harness.key(game)
        val checkpointKey = harness.checkpointKey(game)
        val hash = "d".repeat(64)
        val name = SaveVersionNames.create(1_700_000_000_954L, hash)
        harness.states.save(checkpointKey, JSONObject()
            .put("gameKey", key)
            .put("needsPrune", true)
            .put("publicationIntent", name)
            .put("publicationConfirmed", true)
            .put("publicationTargetHash", hash)
            .put("publicationTargetSize", 128L)
            .put("publicationTargetEtag", "etag"))
        val remoteGame = harness.remoteGame(game)
        harness.transport.seed(remoteGame, RemoteSaveVersion(name, hash, 1_700_000_000_954L, 128L, "etag"))
        harness.makeCacheUnusable()

        val report = harness.service.syncGame(game, SaveSyncDirection.UPLOAD)

        assertTrue(report.failures.isEmpty())
        assertEquals(1, harness.transport.verifyCount())
        assertFalse(harness.states.load(checkpointKey).optBoolean("needsPrune"))
    }

    @Test
    fun recoveryWaitsForMutationGateAndRechecksRunningAfterLaunch() = runBlocking {
        val harness = Harness(temp)
        val game = harness.addGame("mutation-gate-recovery")
        harness.identify(game)
        harness.setActive(game, "initial")
        coroutineScope {
            val held = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val gate = async {
                SaveSyncLocks.mutation {
                    held.complete(Unit)
                    release.await()
                }
            }
            held.await()
            harness.world.isRunning = false
            val recovery = async {
                try {
                    harness.service.recoverSelection(game)
                    null
                } catch (caught: SaveSyncException) {
                    caught
                }
            }
            withTimeout(5_000) { harness.world.activeKeyEntered.await() }
            harness.world.isRunning = true
            release.complete(Unit)
            gate.await()
            val failure = recovery.await()
            assertEquals(SaveSyncError.GAME_RUNNING, failure?.code)
        }
    }

    @Test
    fun pendingActivationRetryRechecksRunningBeforeEarlyCompletionTouchesSaveState() = runBlocking {
        val harness = Harness(temp)
        val game = harness.addGame("mutation-gate-pending-retry")
        harness.identify(game)
        val selected = prepareMetadataPendingActivation(
            harness,
            game,
            before = "before-pending-retry",
            selected = "selected-pending-retry",
            selectedAt = 1_700_000_000_011L,
        )
        val key = harness.key(game)
        val pendingBefore = checkNotNull(harness.journal.load(key))
        val archiveBefore = harness.journal.archive(key).readBytes()
        val snapshotsBefore = harness.snapshots.list(key).map { it.id }
        val activeBefore = harness.world.activeHash(game)
        val exportBefore = harness.world.exportCount()
        val completeImportBefore = harness.world.completeImportCount()
        val restoredBefore = harness.world.restoredMetadataCount(game)
        val initialIdleCheck = CompletableDeferred<Unit>()
        harness.world.onRunning = { initialIdleCheck.complete(Unit) }

        coroutineScope {
            val held = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val gate = async {
                SaveSyncLocks.mutation {
                    held.complete(Unit)
                    release.await()
                }
            }
            held.await()
            val activation = async(Dispatchers.Default) {
                try {
                    harness.service.activateVersion(game, selected.id)
                    null
                } catch (caught: SaveSyncException) {
                    caught
                }
            }
            withTimeout(5_000) { initialIdleCheck.await() }
            harness.world.isRunning = true
            release.complete(Unit)
            gate.await()
            val failure = withTimeout(5_000) { activation.await() }
            assertEquals(SaveSyncError.GAME_RUNNING, failure?.code)
        }

        assertEquals(pendingBefore, harness.journal.load(key))
        assertTrue(harness.journal.archive(key).readBytes().contentEquals(archiveBefore))
        assertEquals(snapshotsBefore, harness.snapshots.list(key).map { it.id })
        assertEquals(activeBefore, harness.world.activeHash(game))
        assertEquals(exportBefore, harness.world.exportCount())
        assertEquals(completeImportBefore, harness.world.completeImportCount())
        assertEquals(restoredBefore, harness.world.restoredMetadataCount(game))
        assertTrue(harness.service.needsSelectionRecovery(game))
    }

    @Test
    fun cancelledPrecommitActivationCleansUpAfterMutationWaitWhenIdle() = runBlocking {
        verifyPrecommitCancellationAfterMutationWait(runningAfterWait = false)
    }

    @Test
    fun cancelledPrecommitActivationKeepsPendingWhenGameStartsAfterMutationWait() = runBlocking {
        verifyPrecommitCancellationAfterMutationWait(runningAfterWait = true)
    }

    private suspend fun verifyPrecommitCancellationAfterMutationWait(runningAfterWait: Boolean) {
        val harness = Harness(temp)
        val game = harness.addGame(if (runningAfterWait) "precommit-cancel-running" else "precommit-cancel-idle")
        harness.identify(game)
        harness.setActive(game, "before-precancel")
        val selected = harness.addSnapshot(game, "selected-precancel", 1_700_000_000_012L)
        val key = harness.key(game)
        val activeBefore = harness.world.activeHash(game)
        val importBefore = harness.world.importCount(game)
        val restoredBefore = harness.world.restoredMetadataCount(game)

        coroutineScope {
            val held = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val gate = async {
                SaveSyncLocks.mutation {
                    held.complete(Unit)
                    release.await()
                }
            }
            held.await()
            val activation = async(Dispatchers.Default) {
                harness.service.activateVersion(game, selected.id)
            }
            val pendingBefore = awaitPreparedActivation(harness, key)
            val archiveBefore = harness.journal.archive(key).readBytes()
            val exportAtCancellation = harness.world.exportCount()
            val completeImportBefore = harness.world.completeImportCount()

            activation.cancel()
            harness.world.enabled = false
            harness.world.isRunning = runningAfterWait
            release.complete(Unit)
            gate.await()
            withTimeout(5_000) { activation.join() }

            assertEquals(activeBefore, harness.world.activeHash(game))
            assertEquals(importBefore, harness.world.importCount(game))
            assertEquals(exportAtCancellation, harness.world.exportCount())
            assertEquals(restoredBefore, harness.world.restoredMetadataCount(game))
            if (runningAfterWait) {
                assertEquals(pendingBefore, harness.journal.load(key))
                assertTrue(harness.journal.archive(key).readBytes().contentEquals(archiveBefore))
                assertEquals(completeImportBefore, harness.world.completeImportCount())
                assertTrue(harness.service.needsSelectionRecovery(game))
            } else {
                assertNull(harness.journal.load(key))
                assertFalse(harness.journal.archive(key).exists())
                assertEquals(completeImportBefore + 1, harness.world.completeImportCount())
                assertFalse(harness.service.needsSelectionRecovery(game))
            }
        }
    }

    @Test
    fun uploadSyncPendingCompletionRechecksRunningAfterMutationWait() = runBlocking {
        val harness = Harness(temp)
        val game = harness.addGame("mutation-gate-sync-finish")
        harness.identify(game)
        prepareMetadataPendingActivation(
            harness,
            game,
            before = "before-sync-finish",
            selected = "selected-sync-finish",
            selectedAt = 1_700_000_000_013L,
        )
        val key = harness.key(game)
        val pendingBefore = checkNotNull(harness.journal.load(key))
        val archiveBefore = harness.journal.archive(key).readBytes()
        val exportBefore = harness.world.exportCount()
        val completeImportBefore = harness.world.completeImportCount()
        val restoredBefore = harness.world.restoredMetadataCount(game)
        val admissionCheck = CompletableDeferred<Unit>()
        harness.world.onRunning = { admissionCheck.complete(Unit) }

        coroutineScope {
            val held = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val gate = async {
                SaveSyncLocks.mutation {
                    held.complete(Unit)
                    release.await()
                }
            }
            held.await()
            val syncing = async(Dispatchers.Default) {
                harness.service.syncGame(game, SaveSyncDirection.UPLOAD)
            }
            withTimeout(5_000) { admissionCheck.await() }
            harness.world.isRunning = true
            release.complete(Unit)
            gate.await()
            val report = withTimeout(5_000) { syncing.await() }

            assertEquals(1, report.busy)
            assertEquals(0, report.uploaded)
            assertTrue(report.failures.isEmpty())
        }

        assertEquals(pendingBefore, harness.journal.load(key))
        assertTrue(harness.journal.archive(key).readBytes().contentEquals(archiveBefore))
        assertEquals(exportBefore, harness.world.exportCount())
        assertEquals(completeImportBefore, harness.world.completeImportCount())
        assertEquals(restoredBefore, harness.world.restoredMetadataCount(game))
        assertTrue(harness.service.needsSelectionRecovery(game))
    }

    @Test
    fun clearGameDeletesOnlyTargetPrivateStateAndNeverCloudOrActiveSave() = runBlocking {
        val harness = Harness(temp)
        val target = harness.addGame("clear-target")
        val sibling = harness.addGame("clear-sibling")
        harness.identify(target)
        harness.identify(sibling)
        harness.setActive(target, "target-active")
        harness.setActive(sibling, "sibling-active")
        val targetKey = harness.key(target)
        val siblingKey = harness.key(sibling)

        val targetSnapshot = harness.addSnapshot(target, "target-history", 1_700_000_000_700L)
        val siblingSnapshot = harness.addSnapshot(sibling, "sibling-history", 1_700_000_000_701L)
        val targetPendingSource = harness.archive("target-pending", "target-pending")
        val siblingPendingSource = harness.archive("sibling-pending", "sibling-pending")
        harness.journal.prepare(targetKey, harness.versionFor(targetPendingSource), targetPendingSource, "before")
        harness.journal.prepare(siblingKey, harness.versionFor(siblingPendingSource), siblingPendingSource, "before")
        harness.states.save(
            SaveContentHash.text("target-cloud-checkpoint"),
            JSONObject().put("gameKey", targetKey).put("seen", "target-seen"),
        )
        harness.states.save(
            SaveContentHash.text("sibling-cloud-checkpoint"),
            JSONObject().put("gameKey", siblingKey).put("seen", "sibling-seen"),
        )
        harness.sessions.save(targetKey, JSONObject().put("session", "target"))
        harness.sessions.save(siblingKey, JSONObject().put("session", "sibling"))
        harness.baselines.save(targetKey, JSONObject().put("baseline", "target"))
        harness.baselines.save(siblingKey, JSONObject().put("baseline", "sibling"))
        val targetRemote = RemoteSaveVersion("target-cloud.zip", targetSnapshot.contentHash, 1L, 1L)
        val siblingRemote = RemoteSaveVersion("sibling-cloud.zip", siblingSnapshot.contentHash, 2L, 1L)
        val targetRemoteGame = harness.remoteGame(target)
        val siblingRemoteGame = harness.remoteGame(sibling)
        harness.transport.seed(targetRemoteGame, targetRemote)
        harness.transport.seed(siblingRemoteGame, siblingRemote)
        val targetActiveHash = harness.world.activeHash(target)
        val targetActiveFile = harness.world.activeFile(target)

        harness.service.clearGame(target)

        assertFalse(File(harness.root, "versions/$targetKey").exists())
        assertTrue(harness.snapshots.list(siblingKey).any { it.id == siblingSnapshot.id })
        assertNull(harness.journal.load(targetKey))
        assertFalse(harness.journal.archive(targetKey).exists())
        assertTrue(harness.journal.load(siblingKey) != null)
        assertTrue(harness.journal.archive(siblingKey).isFile)
        assertEquals(0, harness.states.load(targetKey).length())
        assertEquals(0, harness.states.load(SaveContentHash.text("target-cloud-checkpoint")).length())
        assertEquals("identity-clear-sibling", harness.states.load(siblingKey).getString("identity"))
        assertEquals("sibling-seen", harness.states.load(SaveContentHash.text("sibling-cloud-checkpoint")).getString("seen"))
        assertEquals(0, harness.sessions.load(targetKey).length())
        assertEquals(0, harness.baselines.load(targetKey).length())
        assertEquals("sibling", harness.sessions.load(siblingKey).getString("session"))
        assertEquals("sibling", harness.baselines.load(siblingKey).getString("baseline"))
        assertTrue(targetActiveFile.isFile)
        assertEquals(targetActiveHash, harness.world.activeHash(target))
        assertEquals(listOf(targetRemote), harness.transport.versions(targetRemoteGame))
        assertEquals(listOf(siblingRemote), harness.transport.versions(siblingRemoteGame))
        assertEquals(0, harness.world.openClientCount())
    }

    private fun causeChainContains(actual: Throwable, expected: Throwable): Boolean {
        var current: Throwable? = actual
        while (current != null) {
            if (current === expected) return true
            current = current.cause
        }
        return false
    }

    private suspend fun awaitPreparedActivation(harness: Harness, key: String): SaveActivationJournal.Pending =
        withTimeout(5_000) {
            var pending: SaveActivationJournal.Pending? = null
            while (pending == null) {
                try {
                    pending = harness.journal.load(key)
                } catch (failure: SaveSyncException) {
                    // Windows may briefly deny a reader while the atomic rename publishes the
                    // record. Permanent I/O/corruption still fails this bounded observation.
                    if (failure.cause !is FileNotFoundException && failure.cause !is AccessDeniedException) throw failure
                }
                if (pending == null) yield()
            }
            pending
        }

    private suspend fun prepareMetadataPendingActivation(
        harness: Harness,
        game: ScanGame,
        before: String,
        selected: String,
        selectedAt: Long,
    ): SaveVersion {
        harness.setActive(game, before)
        val version = harness.addSnapshot(game, selected, selectedAt)
        harness.world.failNextRestoredMetadata()
        assertEquals(
            SaveActivationResult.METADATA_PENDING,
            harness.service.activateVersion(game, version.id),
        )
        assertEquals(1, harness.world.importCount(game))
        assertTrue(harness.journal.load(harness.key(game)) != null)
        assertTrue(harness.journal.archive(harness.key(game)).isFile)
        return version
    }

    @Test
    fun recoveryAtFiveVersionsRetainsBothOldSelectedAndNewActiveBytes() = runBlocking {
        val harness = Harness(temp)
        val game = harness.addGame("full-recovery")
        harness.identify(game)
        val key = harness.key(game)
        val selected = harness.addSnapshot(game, "oldest-S", 1_700_000_000_000L)
        val staged = harness.archive("staged-S", "progress-oldest-S")
        harness.journal.prepare(key, selected, staged, "before")
        repeat(5) { harness.addSnapshot(game, "newer-$it", 1_700_000_001_000L + it) }
        assertFalse(harness.snapshots.list(key).any { it.contentHash == selected.contentHash })
        val current = harness.setActive(game, "current-N")

        harness.service.recoverSelection(game)

        val retained = harness.snapshots.list(key)
        assertEquals(5, retained.size)
        for (hash in setOf(selected.contentHash, current)) {
            assertTrue(harness.snapshots.isValid(key, retained.single { it.contentHash == hash }))
        }
        assertEquals(current, harness.world.activeHash(game))
        assertEquals(0, harness.world.importCount(game))
        assertNull(harness.journal.load(key))
        assertFalse(harness.journal.archive(key).exists())
    }

    @Test
    fun listVersionsThenRecoveryProtectsPendingOldestVersionAfterInterruptedPruneWhenStagingIsMissing() = runBlocking {
        verifyInterruptedSixVersionRecovery(missingStaging = true)
    }

    @Test
    fun listVersionsThenRecoveryProtectsPendingOldestVersionAfterInterruptedPruneWhenStagingIsCorrupt() = runBlocking {
        verifyInterruptedSixVersionRecovery(missingStaging = false)
    }

    private suspend fun verifyInterruptedSixVersionRecovery(missingStaging: Boolean) {
        val harness = Harness(temp)
        val game = harness.addGame("interrupted-sixth-commit")
        harness.identify(game)
        val key = harness.key(game)
        val selectedTime = 1_700_000_000_000L
        val selectedSource = harness.archive("oldest-S", "progress-oldest-S")
        val selected = harness.stageSnapshot(game, selectedSource, selectedTime)
        // Model process death after the sixth ZIP committed but before retention ran.
        repeat(5) { index ->
            harness.stageSnapshot(game, "newer-$index", selectedTime + index + 1)
        }
        assertEquals(6, harness.snapshots.list(key).size)
        for (version in harness.snapshots.list(key)) {
            assertTrue(harness.snapshots.isValid(key, version))
        }
        val current = harness.setActive(game, "current-N")
        harness.journal.prepare(key, selected, selectedSource, "before-selection", game.uri)
        if (missingStaging) {
            assertTrue(harness.journal.archive(key).delete())
        } else {
            harness.journal.archive(key).writeText("damaged staging")
        }

        val listed = harness.service.listVersions(game)

        assertTrue(listed.any { it.id == selected.id })
        assertTrue(harness.snapshots.isValid(key, listed.single { it.id == selected.id }))

        harness.service.recoverSelection(game)

        assertNull(harness.journal.load(key))
        assertFalse(harness.journal.archive(key).exists())
        val retained = harness.snapshots.list(key)
        assertEquals(5, retained.size)
        assertTrue(harness.snapshots.isValid(key, retained.single { it.contentHash == selected.contentHash }))
        assertTrue(harness.snapshots.isValid(key, retained.single { it.contentHash == current }))
        assertEquals(current, harness.world.activeHash(game))
        assertEquals(0, harness.world.importCount(game))

        assertEquals(SaveActivationResult.APPLIED, harness.service.activateVersion(game, selected.id))
        harness.setActive(game, "played-N")
        val report = harness.service.syncGame(game, SaveSyncDirection.UPLOAD)

        assertTrue(report.failures.toString(), report.failures.isEmpty())
        assertEquals(1, report.uploaded)
        val published = harness.transport.versions(harness.remoteGame(game)).single()
        assertEquals(harness.world.activeHash(game), published.contentHash)
        assertTrue(published.restored)
        assertEquals(selectedTime, published.previousCreatedAt)
    }

    @Test
    fun listVersionsThenRecoveryProtectsLegacyUnboundPromotionSourceAtSixVersionOverflow() = runBlocking {
        verifyLegacyPromotionOverflowRecovery(legacyUnbound = true)
    }

    @Test
    fun listVersionsThenRecoveryProtectsStaleBoundPromotionSourceAtSixVersionOverflow() = runBlocking {
        verifyLegacyPromotionOverflowRecovery(legacyUnbound = false)
    }

    @Test
    fun listVersionsThenRecoveryProtectsPromotionWithEmptyDirectoryEpoch() = runBlocking {
        verifyLegacyPromotionOverflowRecovery(legacyUnbound = false, emptyEpoch = true)
    }

    private suspend fun verifyLegacyPromotionOverflowRecovery(legacyUnbound: Boolean, emptyEpoch: Boolean = false) {
        val harness = Harness(temp)
        val game = harness.addGame(if (legacyUnbound) "legacy-unbound-overflow" else "stale-bound-overflow")
        harness.identify(game)
        val key = harness.key(game)
        val selectedTime = 1_700_000_000_000L
        val selected = harness.addSnapshot(game, "oldest-only-S", selectedTime)
        repeat(5) { index ->
            harness.stageSnapshot(game, "newer-$index", selectedTime + index + 1)
        }
        assertEquals(6, harness.snapshots.list(key).size)
        assertEquals(1, harness.snapshots.list(key).count { it.contentHash == selected.contentHash })
        for (version in harness.snapshots.list(key)) {
            assertTrue(harness.snapshots.isValid(key, version))
        }
        val current = harness.setActive(game, "active-only-N")
        assertFalse(harness.snapshots.list(key).any { it.contentHash == current })
        val state = harness.states.load(key)
            .put("promotionFromHash", selected.contentHash)
            .put("promotionFromTime", selected.createdAt)
            .put("promotionAt", selected.createdAt + 100L)
            .put("promotionBaseHash", selected.contentHash)
            .put("promotionAwaitingChange", true)
            .put("promotionContentHash", selected.contentHash)
        if (legacyUnbound) {
            state.remove("promotionActiveKey")
            state.remove("promotionDirectoryRevision")
        } else {
            val activationKey = game.uri
            val priorOwner = "prior-directory-owner"
            val priorToken = "prior-directory-revision"
            if (!emptyEpoch) {
                SaveDirectoryActivationStore(harness.root).apply {
                    begin(activationKey, priorOwner, priorToken)
                    complete(activationKey, priorOwner, priorToken)
                }
            }
            state.put("promotionActiveKey", activationKey)
                .put("promotionDirectoryRevision", if (emptyEpoch) "" else "stale-$priorToken")
        }
        harness.states.save(key, state)
        assertNull(harness.journal.load(key))
        assertFalse(harness.journal.archive(key).exists())
        assertTrue(harness.service.needsSelectionRecovery(game))
        val importCount = harness.world.importCount(game)

        val listed = harness.service.listVersions(game)

        assertEquals(5, listed.size)
        assertTrue(harness.snapshots.isValid(
            key,
            listed.single { it.contentHash == selected.contentHash },
        ))
        assertEquals(current, harness.world.activeHash(game))
        assertEquals(importCount, harness.world.importCount(game))

        harness.service.recoverSelection(game)

        assertEquals(importCount, harness.world.importCount(game))
        assertEquals(current, harness.world.activeHash(game))
        val retained = harness.snapshots.list(key)
        assertEquals(5, retained.size)
        for (hash in setOf(selected.contentHash, current)) {
            assertTrue(harness.snapshots.isValid(
                key,
                retained.single { it.contentHash == hash },
            ))
        }
        val recoveredState = harness.states.load(key)
        for (field in listOf(
            "completedActivation",
            "activationHash",
            "promotionFromHash",
            "promotionFromTime",
            "promotionAt",
            "promotionBaseHash",
            "promotionAwaitingChange",
            "promotionContentHash",
            "promotionActiveKey",
            "promotionDirectoryRevision",
        )) {
            assertFalse(field, recoveredState.has(field))
        }
        assertFalse(harness.service.needsSelectionRecovery(game))
    }

    @Test
    fun parseableJournalWithWrongHashRescuesArchiveUsingItsActualContent() = runBlocking {
        val harness = Harness(temp)
        val game = harness.addGame("semantic-journal")
        harness.identify(game)
        val key = harness.key(game)
        val current = harness.setActive(game, "current-N")
        val staged = harness.archive("selected-S", "selected-S")
        val selected = harness.versionFor(staged)
        harness.journal.prepare(key, selected, staged, current)
        val record = File(harness.root, "pending/records/$key.json")
        val wrongHash = "a".repeat(64)
        val damaged = JSONObject(record.readText()).put("id", SaveVersionNames.create(selected.createdAt, wrongHash))
        record.writeText(damaged.toString())

        harness.service.recoverSelection(game)

        val retained = harness.snapshots.list(key)
        assertTrue(harness.snapshots.isValid(key, retained.single { it.contentHash == selected.contentHash }))
        assertTrue(harness.snapshots.isValid(key, retained.single { it.contentHash == current }))
        assertFalse(retained.any { it.contentHash == wrongHash })
        assertEquals(current, harness.world.activeHash(game))
        assertFalse(harness.journal.archive(key).exists())
        assertNull(harness.journal.load(key))
    }

    @Test
    fun failedArchiveRescueKeepsJournalAndArchiveForRetry() = runBlocking {
        val harness = Harness(temp)
        val game = harness.addGame("unreadable-archive")
        val key = harness.key(game)
        val current = harness.setActive(game, "current-N")
        val staged = harness.archive("selected-S", "selected-S")
        harness.journal.prepare(key, harness.versionFor(staged), staged, current)
        val source = harness.journal.archive(key)
        source.writeText("damaged ZIP")

        try {
            harness.service.recoverSelection(game)
            fail("Recovery must not clear an archive it could not rescue")
        } catch (failure: SaveSyncException) {
            assertEquals(SaveSyncError.CORRUPT_ARCHIVE, failure.code)
        }

        assertTrue(source.isFile)
        assertTrue(harness.journal.load(key) != null)
        assertEquals(current, harness.world.activeHash(game))
    }

    @Test
    fun corruptStagingRecoversUsingVerifiedHistoricalSource() = runBlocking {
        verifyHistoricalRecovery(missing = false)
    }

    @Test
    fun missingStagingRecoversUsingVerifiedHistoricalSource() = runBlocking {
        verifyHistoricalRecovery(missing = true)
    }

    @Test
    fun corruptHistoryCannotAuthorizeClearingAnInvalidStagingCopy() = runBlocking {
        val harness = Harness(temp)
        val game = harness.addGame("both-sources-invalid")
        val key = harness.key(game)
        val current = harness.setActive(game, "current-N")
        val selected = harness.addSnapshot(game, "selected-S", 1_700_000_000_090L)
        harness.journal.prepare(key, selected, harness.archive("staging-S", "progress-selected-S"), current)
        harness.journal.archive(key).writeText("damaged staging")
        harness.snapshots.file(key, selected.id).writeText("damaged history")

        try {
            harness.service.recoverSelection(game)
            fail("Invalid history was trusted by filename")
        } catch (failure: SaveSyncException) {
            assertEquals(SaveSyncError.CORRUPT_ARCHIVE, failure.code)
        }
        assertTrue(harness.journal.load(key) != null)
        assertEquals("damaged staging", harness.journal.archive(key).readText())
        assertEquals(current, harness.world.activeHash(game))
    }

    private suspend fun verifyHistoricalRecovery(missing: Boolean) {
        val harness = Harness(temp)
        val game = harness.addGame("historical-recovery")
        harness.identify(game)
        val key = harness.key(game)
        val selected = harness.addSnapshot(game, "selected-S", 1_700_000_000_090L)
        val current = harness.setActive(game, "current-N")
        val staging = harness.archive("staging-S", "progress-selected-S")
        harness.journal.prepare(key, selected, staging, current)
        if (missing) assertTrue(harness.journal.archive(key).delete())
        else harness.journal.archive(key).writeText("damaged")

        harness.service.recoverSelection(game)

        assertNull(harness.journal.load(key))
        assertFalse(harness.journal.archive(key).exists())
        val retained = harness.snapshots.list(key)
        for (hash in setOf(selected.contentHash, current)) {
            assertTrue(harness.snapshots.isValid(key, retained.single { it.contentHash == hash }))
        }
        assertEquals(current, harness.world.activeHash(game))
        assertEquals(0, harness.world.importCount(game))
        assertTrue(harness.service.syncGame(game, SaveSyncDirection.UPLOAD).failures.isEmpty())
        assertEquals(SaveActivationResult.APPLIED, harness.service.activateVersion(game, selected.id))
    }

    @Test
    fun validButMismatchedStagingPreservesBothActualBytesAndSelectedHistoryAtQuota() = runBlocking {
        val harness = Harness(temp)
        val game = harness.addGame("mismatched-valid-staging")
        val key = harness.key(game)
        val selected = harness.addSnapshot(game, "selected-S", 1_700_000_000_010L)
        harness.journal.prepare(key, selected, harness.archive("staging-S", "progress-selected-S"), "before")
        repeat(3) { harness.addSnapshot(game, "other-$it", 1_700_000_001_000L + it) }
        val replacement = harness.archive("replacement-T", "valid-but-different")
        val replacementHash = SaveContentHash.archive(replacement)
        replacement.copyTo(harness.journal.archive(key), overwrite = true)
        val current = harness.setActive(game, "current-N")

        harness.service.recoverSelection(game)

        val retained = harness.snapshots.list(key)
        assertEquals(5, retained.size)
        for (hash in setOf(selected.contentHash, replacementHash, current)) {
            assertTrue(harness.snapshots.isValid(key, retained.single { it.contentHash == hash }))
        }
        assertNull(harness.journal.load(key))
    }

    @Test
    fun selectedBytesStillActiveCanRescueMissingStagingAndHistory() = runBlocking {
        val harness = Harness(temp)
        val game = harness.addGame("active-selected-recovery")
        val key = harness.key(game)
        val current = harness.setActive(game, "selected-S")
        val staged = harness.archive("staging-S", "progress-selected-S")
        val selected = harness.versionFor(staged)
        harness.journal.prepare(key, selected, staged, "before")
        assertTrue(harness.journal.archive(key).delete())

        harness.service.recoverSelection(game)

        assertTrue(harness.snapshots.isValid(key, harness.snapshots.list(key).single()))
        assertEquals(current, harness.snapshots.list(key).single().contentHash)
        assertEquals(0, harness.world.importCount(game))
        assertNull(harness.journal.load(key))
    }

    @Test
    fun sameCommittedTokenRecoveryPreservesSingleRestoredChainAfterSavingN() = runBlocking {
        verifyCommittedChainRecovery(mismatchedStaging = false)
    }

    @Test
    fun sameCommittedTokenWithValidButMismatchedStagingStillUsesVerifiedHistoricalS() = runBlocking {
        verifyCommittedChainRecovery(mismatchedStaging = true)
    }

    private suspend fun verifyCommittedChainRecovery(mismatchedStaging: Boolean) {
        val harness = Harness(temp)
        val game = harness.addGame("committed-chain-recovery")
        harness.identify(game)
        harness.setActive(game, "before-selection")
        val key = harness.key(game)
        val selected = harness.addSnapshot(game, "selected-S", 1_700_000_000_010L)
        harness.transport.seed(harness.remoteGame(game), RemoteSaveVersion(selected.id, selected.contentHash,
            selected.createdAt, selected.size), harness.snapshots.file(key, selected.id))
        harness.world.failNextRestoredMetadata()
        assertEquals(SaveActivationResult.METADATA_PENDING, harness.service.activateVersion(game, selected.id))
        val token = harness.journal.load(key)!!.token
        val current = harness.setActive(game, "played-N")
        if (mismatchedStaging) {
            harness.archive("different-valid-T", "unrelated-but-valid")
                .copyTo(harness.journal.archive(key), overwrite = true)
        }

        harness.service.recoverSelection(game)

        assertNull(harness.journal.load(key))
        val state = harness.states.load(key)
        assertEquals(token, state.getString("completedActivation"))
        assertEquals(selected.contentHash, state.getString("promotionFromHash"))
        assertTrue(state.getBoolean("promotionAwaitingChange"))
        val report = harness.service.syncGame(game, SaveSyncDirection.UPLOAD)
        assertTrue(report.failures.toString(), report.failures.isEmpty())
        val published = harness.transport.versions(harness.remoteGame(game)).single()
        assertEquals(current, published.contentHash)
        assertTrue(published.restored)
        assertEquals(selected.createdAt, published.previousCreatedAt)
        assertEquals(1, harness.world.importCount(game))
    }

    @Test
    fun uncommittedMismatchedStagingCannotClearUnpublishedPromotionFromSelectedSToPlayedN() = runBlocking {
        val harness = Harness(temp)
        val game = harness.addGame("completed-promotion-uncommitted-staging")
        harness.identify(game)
        harness.setActive(game, "before-selection")
        val key = harness.key(game)
        val selected = harness.addSnapshot(game, "selected-S", 1_700_000_000_010L)
        harness.transport.seed(
            harness.remoteGame(game),
            RemoteSaveVersion(selected.id, selected.contentHash, selected.createdAt, selected.size),
            harness.snapshots.file(key, selected.id),
        )

        assertEquals(SaveActivationResult.APPLIED, harness.service.activateVersion(game, selected.id))
        assertNull(harness.journal.load(key))
        harness.setActive(game, "played-N")
        val completedState = JSONObject(harness.states.load(key).toString())
        assertTrue(completedState.getBoolean("promotionAwaitingChange"))
        assertEquals(selected.contentHash, completedState.getString("promotionBaseHash"))

        val attempted = harness.archive("uncommitted-T", "unrelated-attempt-T")
        harness.journal.prepare(
            key,
            harness.versionFor(attempted),
            attempted,
            harness.world.activeHash(game),
            game.uri,
        )
        val uncommitted = harness.journal.load(key)!!
        assertFalse(uncommitted.committed)
        assertTrue(uncommitted.token != completedState.getString("completedActivation"))
        val rescuedBytes = harness.archive("mismatched-U", "valid-but-unrelated-U")
        val rescuedHash = SaveContentHash.archive(rescuedBytes)
        rescuedBytes.copyTo(harness.journal.archive(key), overwrite = true)

        harness.service.recoverSelection(game)

        assertNull(harness.journal.load(key))
        val recoveredState = harness.states.load(key)
        for (field in listOf(
            "completedActivation",
            "activationHash",
            "promotionFromHash",
            "promotionFromTime",
            "promotionAt",
            "promotionBaseHash",
            "promotionAwaitingChange",
            "promotionContentHash",
        )) {
            assertEquals(completedState.opt(field), recoveredState.opt(field))
        }
        val rescued = harness.snapshots.list(key).single { it.contentHash == rescuedHash }
        assertTrue(harness.snapshots.isValid(key, rescued))

        val finalReport = harness.service.syncGame(game, SaveSyncDirection.UPLOAD)

        assertTrue(finalReport.failures.toString(), finalReport.failures.isEmpty())
        assertEquals(1, finalReport.uploaded)
        val published = harness.transport.versions(harness.remoteGame(game)).single()
        assertEquals(harness.world.activeHash(game), published.contentHash)
        assertTrue(published.restored)
        assertEquals(selected.createdAt, published.previousCreatedAt)
        assertTrue(harness.transport.versions(harness.remoteGame(game)).none {
            it.contentHash == selected.contentHash
        })
    }

    @Test
    fun failedJournalRecordWriteCannotRetireThePreviousActiveRestoreChain() = runBlocking {
        val harness = Harness(temp)
        val game = harness.addGame("orphan-before-import")
        harness.identify(game)
        harness.setActive(game, "before-A")
        val selected = harness.addSnapshot(game, "selected-A", 1_700_000_000_010L)
        val key = harness.key(game)
        harness.transport.seed(harness.remoteGame(game), RemoteSaveVersion(selected.id, selected.contentHash,
            selected.createdAt, selected.size), harness.snapshots.file(key, selected.id))
        assertEquals(SaveActivationResult.APPLIED, harness.service.activateVersion(game, selected.id))
        val continued = harness.setActive(game, "played-N")
        val previousToken = harness.states.load(key).getString("completedActivation")
        val records = File(harness.root, "pending/records")
        assertTrue(records.delete())
        records.writeText("simulate record-directory write failure")
        val attempted = harness.archive("new-attempt-B", "new-attempt-B")
        try {
            harness.journal.prepare(key, harness.versionFor(attempted), attempted, continued, game.uri)
            fail("Record write unexpectedly succeeded")
        } catch (failure: SaveSyncException) {
            assertEquals(SaveSyncError.LOCAL_IO, failure.code)
        }
        assertFalse(attempted.exists())
        assertTrue(harness.journal.archive(key).exists())
        assertTrue(records.delete())
        assertTrue(records.mkdir())
        assertNull(harness.journal.load(key))

        harness.service.recoverSelection(game)

        assertEquals(previousToken, harness.states.load(key).getString("completedActivation"))
        assertEquals(selected.contentHash, harness.states.load(key).getString("promotionFromHash"))
        assertEquals(1, harness.world.importCount(game))
        val report = harness.service.syncGame(game, SaveSyncDirection.UPLOAD)
        assertTrue(report.failures.toString(), report.failures.isEmpty())
        val published = harness.transport.versions(harness.remoteGame(game)).single()
        assertEquals(continued, published.contentHash)
        assertTrue(published.restored)
        assertEquals(selected.createdAt, published.previousCreatedAt)
    }

    @Test
    fun busyPruneRetryDoesNotReuseConfirmationAfterReplacementDisappears() = runBlocking {
        val harness = Harness(temp)
        val game = harness.addGame("busy-prune")
        harness.identify(game)
        val hash = harness.setActive(game, "current-N")
        val remoteGame = harness.remoteGame(game)
        repeat(11) { index ->
            harness.transport.seed(remoteGame, RemoteSaveVersion("old-$index.zip",
                (index + 1).toString(16).padStart(64, '0'), 1_700_000_000_000L + index, 10L))
        }
        harness.transport.failNextDelete()
        val first = harness.service.syncGame(game, SaveSyncDirection.UPLOAD)
        assertEquals(listOf(SaveSyncError.SERVER), first.failures.map { it.error })
        assertTrue(harness.states.load(harness.checkpointKey(game)).getBoolean("publicationConfirmed"))
        harness.transport.remove(remoteGame, hash)
        harness.world.isRunning = true
        val before = harness.transport.versions(remoteGame)
        val verifies = harness.transport.verifyCount()
        val deletes = harness.transport.deleteCount()

        val retry = harness.service.syncGame(game, SaveSyncDirection.UPLOAD)

        assertEquals(1, retry.busy)
        assertTrue(retry.failures.isEmpty())
        assertEquals(before, harness.transport.versions(remoteGame))
        assertEquals(verifies, harness.transport.verifyCount())
        assertEquals(deletes, harness.transport.deleteCount())
        assertTrue(harness.states.load(harness.checkpointKey(game)).getBoolean("needsPrune"))
    }

    @Test
    fun failedChildCleanupPreservesIdentityAndOwnershipUntilSuccessfulRetry() = runBlocking {
        val harness = Harness(temp)
        val game = harness.addGame("cleanup-retry")
        harness.identify(game)
        val key = harness.key(game)
        val child = SaveContentHash.text("owned-unreadable-checkpoint")
        harness.states.save(key, harness.states.load(key).put("checkpointKeys", JSONArray().put(child)))
        val obstructed = File(harness.root, "checkpoints/$child.json")
        assertTrue(obstructed.mkdir())
        File(obstructed, "obstruction").writeText("cannot delete as a file")

        try {
            harness.service.clearGame(game)
            fail("Child cleanup failure must remain retryable")
        } catch (failure: SaveSyncException) {
            assertEquals(SaveSyncError.LOCAL_IO, failure.code)
        }
        assertEquals("identity-cleanup-retry", harness.service.identity(game))
        assertEquals(child, harness.states.load(key).getJSONArray("checkpointKeys").getString(0))
        assertTrue(obstructed.isDirectory)

        assertTrue(File(obstructed, "obstruction").delete())
        harness.service.clearGame(game)
        assertFalse(obstructed.exists())
        assertEquals(0, harness.states.load(key).length())
    }

    @Test
    fun queuedSyncRechecksIdentityChangedWhileWaitingForNetworkLock() = runBlocking {
        val harness = Harness(temp)
        val first = harness.addGame("identity-A")
        val second = harness.addGame("identity-B")
        harness.identify(first)
        harness.identify(second)
        val hash = harness.setActive(first, "current-A")
        coroutineScope {
            lateinit var queued: kotlinx.coroutines.Deferred<SaveSyncReport>
            SaveSyncLocks.network(first.uri) {
                queued = async(Dispatchers.Default) { harness.service.syncGame(first, SaveSyncDirection.UPLOAD) }
                withTimeout(5_000) { while (harness.world.openClientCount() == 0) delay(10) }
                harness.service.setIdentity(first, harness.service.identity(second))
            }
            val report = withTimeout(5_000) { queued.await() }
            assertEquals(listOf(SaveSyncError.IDENTITY_CONFLICT), report.failures.map { it.error })
            assertEquals(0, harness.transport.uploadCount(hash))
        }
    }

    @Test
    fun manualUploadSyncQueuedOnDirectoryACannotCaptureDirectoryB() = runBlocking {
        verifyManualSyncRechecksDirectoryAfterWaitingForActiveLock(
            syncWholeLibrary = false,
            direction = SaveSyncDirection.UPLOAD,
        )
    }

    @Test
    fun manualBidirectionalSyncQueuedOnDirectoryACannotCaptureDirectoryB() = runBlocking {
        verifyManualSyncRechecksDirectoryAfterWaitingForActiveLock(
            syncWholeLibrary = false,
            direction = SaveSyncDirection.BOTH,
        )
    }

    @Test
    fun manualUploadSyncAllQueuedOnDirectoryACannotCaptureDirectoryB() = runBlocking {
        verifyManualSyncRechecksDirectoryAfterWaitingForActiveLock(
            syncWholeLibrary = true,
            direction = SaveSyncDirection.UPLOAD,
        )
    }

    @Test
    fun manualBidirectionalSyncAllQueuedOnDirectoryACannotCaptureDirectoryB() = runBlocking {
        verifyManualSyncRechecksDirectoryAfterWaitingForActiveLock(
            syncWholeLibrary = true,
            direction = SaveSyncDirection.BOTH,
        )
    }

    private suspend fun verifyManualSyncRechecksDirectoryAfterWaitingForActiveLock(
        syncWholeLibrary: Boolean,
        direction: SaveSyncDirection,
    ) {
        val harness = Harness(temp)
        val game = harness.addGame("queued-directory-binding")
        harness.identify(game)
        harness.setActive(game, "directory-A")
        val key = harness.key(game)
        coroutineScope {
            lateinit var queued: kotlinx.coroutines.Deferred<SaveSyncReport>
            var directoryBHash = ""
            SaveSyncLocks.active(game.uri) {
                queued = async(Dispatchers.Default) {
                    if (syncWholeLibrary) {
                        harness.service.syncAll(direction)
                    } else {
                        harness.service.syncGame(game, direction)
                    }
                }
                withTimeout(5_000) { harness.world.activeKeyEntered.await() }
                // Let the queued operation reach the already-held A lock before switching.
                repeat(10) { yield() }
                harness.world.directoryKey = "directory-B"
                directoryBHash = harness.setActive(game, "directory-B")
            }
            val report = withTimeout(5_000) { queued.await() }

            assertEquals(0, report.uploaded)
            assertTrue(
                report.toString(),
                report.busy > 0 || report.failures.any {
                    it.error == SaveSyncError.SAVE_LOCATION_CHANGED || it.error == SaveSyncError.SELECTION_INCOMPLETE
                },
            )
            assertEquals(0, harness.transport.uploadCount(directoryBHash))
            assertTrue(harness.transport.versions(harness.remoteGame(game)).isEmpty())
            assertFalse(harness.snapshots.list(key).any { it.contentHash == directoryBHash })
        }
    }

    @Test
    fun unavailableGameStillReservesItsCloudIdentity() = runBlocking {
        val harness = Harness(temp)
        val first = harness.addGame("available")
        val unavailable = harness.addGame("unavailable")
        harness.service.setIdentity(first, "shared")
        harness.service.setIdentity(unavailable, "shared")
        val hash = harness.setActive(first, "current")
        harness.world.unavailableUris += unavailable.uri

        val single = harness.service.syncGame(first, SaveSyncDirection.UPLOAD)
        val all = harness.service.syncAll(SaveSyncDirection.UPLOAD)

        assertEquals(listOf(SaveSyncError.IDENTITY_CONFLICT), single.failures.map { it.error })
        assertEquals(2, all.failures.count { it.error == SaveSyncError.IDENTITY_CONFLICT })
        assertEquals(0, harness.transport.uploadCount(hash))
    }

    @Test
    fun unavailableEntryWithoutPortableIdentityDoesNotBreakAnotherGamesSync() = runBlocking {
        val harness = Harness(temp)
        val first = harness.addGame("available")
        val unsupported = ScanGame("unsupported", "androidapp://unresolvable",
            EngineType.ANDROID_APP, "__game_dir__")
        harness.world.addGame(unsupported)
        harness.world.unavailableUris += unsupported.uri
        harness.identify(first)
        // The other unavailable entry has the same engine but no resolvable folder or custom ID.
        val unknown = harness.addGame("unknown")
        harness.world.unavailableUris += unknown.uri
        harness.setActive(first, "current")

        val single = harness.service.syncGame(first, SaveSyncDirection.UPLOAD)
        val all = harness.service.syncAll(SaveSyncDirection.UPLOAD)

        assertTrue(single.failures.isEmpty())
        assertTrue(all.failures.isEmpty())
        assertEquals(1, single.uploaded)
    }

    @Test
    fun runtimeModulesWithSharedLeafNameAreSkippedByWholeLibrarySync() = runBlocking {
        val harness = Harness(temp)
        fun runtimeGame(parent: String) = ScanGame(
            title = "runtime-$parent",
            uri = "file:///library/$parent/shared-game",
            engine = EngineType.RUNTIME_MODULE,
            launchTarget = "__game_dir__",
            runtimeModulePackage = "com.example.runtime",
        )
        val runtimeA = runtimeGame("a")
        val runtimeB = runtimeGame("b")
        harness.world.addGame(runtimeA)
        harness.world.addGame(runtimeB)
        harness.world.unavailableUris += listOf(runtimeA.uri, runtimeB.uri)

        val supported = harness.addGame("supported")
        harness.identify(supported)
        val supportedHash = harness.setActive(supported, "current")
        val supportedRemote = harness.remoteGame(supported)
        val runtimeRemoteA = harness.remoteGame(runtimeA)
        val runtimeRemoteB = harness.remoteGame(runtimeB)
        assertEquals(runtimeRemoteA, runtimeRemoteB)

        val upload = harness.service.syncAll(SaveSyncDirection.UPLOAD)
        val pull = harness.service.syncAll(SaveSyncDirection.PULL)

        assertTrue(upload.toString(), upload.failures.isEmpty())
        assertTrue(pull.toString(), pull.failures.isEmpty())
        assertFalse(upload.failures.any { it.error == SaveSyncError.IDENTITY_CONFLICT })
        assertFalse(pull.failures.any { it.error == SaveSyncError.IDENTITY_CONFLICT })
        assertEquals(1, upload.uploaded)
        assertEquals(1, pull.unchanged)
        assertEquals(1, harness.transport.versions(supportedRemote).count { it.contentHash == supportedHash })
        assertEquals(0, harness.transport.listInvocationCount(runtimeRemoteA))
        assertTrue(harness.transport.listInvocationCount(supportedRemote) > 0)
        assertTrue(harness.transport.versions(runtimeRemoteA).isEmpty())
    }

    @Test
    fun sameSizeUnverifiedPublicationCannotAuthorizeRetentionDeletes() = runBlocking {
        val harness = Harness(temp)
        val game = harness.addGame("unverified-publication")
        harness.identify(game)
        harness.setActive(game, "current-N")
        val remote = harness.remoteGame(game)
        repeat(11) { index ->
            harness.transport.seed(remote, RemoteSaveVersion("old-$index.zip",
                (index + 1).toString(16).padStart(64, '0'), 1_700_000_000_000L + index, 10L))
        }
        harness.transport.corruptNextUpload = true

        val report = harness.service.syncGame(game, SaveSyncDirection.UPLOAD)

        assertEquals(listOf(SaveSyncError.SERVER), report.failures.map { it.error })
        assertEquals(0, harness.transport.deleteCount())
        assertEquals(12, harness.transport.versions(remote).size)
        assertFalse(harness.states.load(harness.checkpointKey(game)).getBoolean("publicationConfirmed"))
    }

    @Test
    fun committedUnfinishedNewSelectionDoesNotInheritAnOlderPromotionChain() = runBlocking {
        val harness = Harness(temp)
        val game = harness.addGame("old-promotion")
        harness.identify(game)
        val key = harness.key(game)
        val current = harness.setActive(game, "new-selection-N")
        val source = harness.archive("new-selection-S", "new-selected-bytes")
        harness.journal.prepare(key, harness.versionFor(source), source, "before-new-selection")
        harness.journal.markCommitted(key)
        harness.states.save(key, harness.states.load(key).put("completedActivation", "older-token")
            .put("promotionFromHash", "older-S").put("promotionFromTime", 1L).put("promotionAt", 2L)
            .put("promotionAwaitingChange", true).put("promotionBaseHash", "old-base"))

        harness.service.recoverSelection(game)

        val state = harness.states.load(key)
        for (field in listOf("completedActivation", "promotionFromHash", "promotionAt", "promotionAwaitingChange",
            "promotionBaseHash")) assertFalse(state.has(field))
        assertEquals(current, harness.world.activeHash(game))
    }

    @Test
    fun damagedOwnershipIndexCannotDeleteReadableSiblingCheckpoint() = runBlocking {
        val harness = Harness(temp)
        val game = harness.addGame("cleanup-owner")
        harness.identify(game)
        val siblingKey = SaveContentHash.text("sibling-uri")
        val child = SaveContentHash.text("sibling-checkpoint")
        harness.states.save(child, JSONObject().put("gameKey", siblingKey).put("uploaded", "sibling-progress"))
        harness.states.save(harness.key(game), harness.states.load(harness.key(game))
            .put("checkpointKeys", JSONArray().put(child)))

        harness.service.clearGame(game)

        assertEquals("sibling-progress", harness.states.load(child).getString("uploaded"))
        assertEquals(0, harness.states.load(harness.key(game)).length())
    }

    @Test
    fun manualMarkerRecoveryAtSixVersionOverflowPreservesValidPromotionFromSelectedSToPlayedN() = runBlocking {
        val harness = Harness(temp)
        val game = harness.addGame("manual-recovery-promotion")
        harness.identify(game)
        harness.setActive(game, "before-selection")
        val key = harness.key(game)
        val selected = harness.addSnapshot(game, "selected-S", 1_700_000_000_010L)
        harness.transport.seed(harness.remoteGame(game), RemoteSaveVersion(selected.id, selected.contentHash,
            selected.createdAt, selected.size), harness.snapshots.file(key, selected.id))
        assertEquals(SaveActivationResult.APPLIED, harness.service.activateVersion(game, selected.id))
        val selectedHistory = harness.snapshots.list(key).single { it.contentHash == selected.contentHash }
        for (version in harness.snapshots.list(key).filter { it.contentHash != selected.contentHash }) {
            assertTrue(harness.snapshots.file(key, version.id).delete())
        }
        repeat(5) { index ->
            harness.stageSnapshot(game, "newer-manual-history-$index", selectedHistory.createdAt + index + 1L)
        }
        assertEquals(6, harness.snapshots.list(key).size)
        assertEquals(selected.contentHash, harness.snapshots.list(key).minBy { it.createdAt }.contentHash)
        val bound = harness.states.load(key)
        assertEquals(game.uri, bound.getString("promotionActiveKey"))
        assertEquals(SaveDirectoryActivationStore(harness.root).revision(game.uri),
            bound.getString("promotionDirectoryRevision"))
        val current = harness.setActive(game, "played-N")
        harness.world.recoveryNeeded = true
        val imports = harness.world.importCount(game)

        harness.service.recoverSelection(game)
        assertEquals(imports, harness.world.importCount(game))
        assertEquals(current, harness.world.activeHash(game))
        val retained = harness.snapshots.list(key)
        assertEquals(5, retained.size)
        for (hash in setOf(selected.contentHash, current)) {
            assertTrue(harness.snapshots.isValid(key, retained.single { it.contentHash == hash }))
        }
        assertEquals(selected.contentHash, harness.states.load(key).getString("promotionFromHash"))
        assertTrue(harness.states.load(key).getBoolean("promotionAwaitingChange"))
        val report = harness.service.syncGame(game, SaveSyncDirection.UPLOAD)

        assertTrue(report.failures.isEmpty())
        val published = harness.transport.versions(harness.remoteGame(game)).single()
        assertEquals(current, published.contentHash)
        assertTrue(published.restored)
        assertEquals(selected.createdAt, published.previousCreatedAt)
    }

    @Test
    fun identityEditDuringCorruptTargetVerificationPreventsItsDelete() = runBlocking {
        val harness = Harness(temp)
        val game = harness.addGame("discard-identity-edit")
        harness.identify(game)
        harness.setActive(game, "current")
        val remote = harness.remoteGame(game)
        harness.transport.corruptNextUpload = true
        assertEquals(listOf(SaveSyncError.SERVER),
            harness.service.syncGame(game, SaveSyncDirection.UPLOAD).failures.map { it.error })
        val target = harness.transport.versions(remote).single()
        val gate = CompletableDeferred<Unit>()
        harness.transport.discardGate = gate
        coroutineScope {
            val sync = async(Dispatchers.Default) { harness.service.syncGame(game, SaveSyncDirection.UPLOAD) }
            withTimeout(5_000) { harness.transport.discardEntered.await() }
            try {
                harness.service.setIdentity(game, "new-identity")
            } finally {
                gate.complete(Unit)
            }
            val report = withTimeout(5_000) { sync.await() }
            assertLocalStateChanged(report)
            assertEquals(listOf(target), harness.transport.versions(remote))
        }
    }

    @Test
    fun retiredAliasSessionCannotCaptureReplacementAfterWaitingForDirectoryLock() = runBlocking {
        val harness = Harness(temp)
        val game = harness.addGame("retired-alias")
        harness.identify(game)
        val key = harness.key(game)
        harness.sessions.save(key, JSONObject().put("token", "original-session"))
        harness.setActive(game, "old-progress")
        coroutineScope {
            lateinit var queued: kotlinx.coroutines.Deferred<SaveSyncReport>
            SaveSyncLocks.active(game.uri) {
                queued = async(Dispatchers.Default) {
                    harness.service.syncGameIfCurrent(game, SaveSyncDirection.UPLOAD) {
                        harness.sessions.load(key).optString("token") == "original-session"
                    }
                }
                withTimeout(5_000) { harness.world.activeKeyEntered.await() }
                // Explicit selection through the shared directory retires the old alias ticket.
                harness.sessions.remove(key)
                harness.setActive(game, "replacement-selected-through-alias")
            }
            val report = withTimeout(5_000) { queued.await() }
            assertLocalStateChanged(report)
            assertTrue(harness.snapshots.list(key).isEmpty())
            assertTrue(harness.transport.versions(harness.remoteGame(game)).isEmpty())
        }
    }

    @Test
    fun automaticUploadCannotAdoptDifferentBytesEvenWhenDirectoryIdentityMatchesAgain() = runBlocking {
        val harness = Harness(temp)
        val game = harness.addGame("automatic-content-binding")
        harness.identify(game)
        val decidedHash = harness.setActive(game, "directory-A-pending")
        // Model B's tree observed during an A -> B -> A settings round trip.
        harness.setActive(game, "directory-B-old")

        val report = harness.service.syncGameIfCurrent(game, SaveSyncDirection.UPLOAD,
            expectedActiveKey = game.uri, expectedContentHash = decidedHash) { true }

        assertLocalStateChanged(report)
        assertTrue(harness.snapshots.list(harness.key(game)).isEmpty())
        assertTrue(harness.transport.versions(harness.remoteGame(game)).isEmpty())
    }

    @Test
    fun automaticUploadRejectsDifferentDirectoryBeforeReadingSaves() = runBlocking {
        val harness = Harness(temp)
        val game = harness.addGame("automatic-directory-binding")
        harness.identify(game)
        val expected = harness.setActive(game, "pending-A")

        val report = harness.service.syncGameIfCurrent(game, SaveSyncDirection.UPLOAD,
            expectedActiveKey = "original-directory-A", expectedContentHash = expected) { true }

        assertEquals(listOf(SaveSyncError.SAVE_LOCATION_CHANGED), report.failures.map { it.error })
        assertTrue(harness.snapshots.list(harness.key(game)).isEmpty())
        assertTrue(harness.transport.versions(harness.remoteGame(game)).isEmpty())
    }

    @Test
    fun automaticUploadPublishesTheBoundDirectoryAndContent() = runBlocking {
        val harness = Harness(temp)
        val game = harness.addGame("automatic-matching-binding")
        harness.identify(game)
        val expected = harness.setActive(game, "pending-A")

        val report = harness.service.syncGameIfCurrent(game, SaveSyncDirection.UPLOAD,
            expectedActiveKey = game.uri, expectedContentHash = expected) { true }

        assertEquals(1, report.uploaded)
        assertTrue(report.failures.isEmpty())
        assertEquals(expected, harness.transport.versions(harness.remoteGame(game)).single().contentHash)
    }

    @Test
    fun activationRetryCannotRebindOriginalDirectoryToChangedSettings() = runBlocking {
        val harness = Harness(temp)
        val game = harness.addGame("activation-directory-binding")
        harness.identify(game)
        harness.setActive(game, "before-selection")
        val selected = harness.addSnapshot(game, "selected-S", 1_700_000_000_010L)
        harness.world.failNextRestoredMetadata()
        assertEquals(SaveActivationResult.METADATA_PENDING, harness.service.activateVersion(game, selected.id))
        val key = harness.key(game)
        assertEquals(game.uri, harness.journal.load(key)!!.activeKey)
        harness.world.directoryKey = "changed-directory-B"
        harness.setActive(game, "unrelated-B")
        val stateBefore = harness.states.load(key).toString()

        val report = harness.service.syncGame(game, SaveSyncDirection.UPLOAD)

        assertEquals(listOf(SaveSyncError.SAVE_LOCATION_CHANGED), report.failures.map { it.error })
        assertEquals(game.uri, harness.journal.load(key)!!.activeKey)
        assertEquals(1, harness.world.restoredMetadataCount(game))
        assertTrue(harness.transport.versions(harness.remoteGame(game)).isEmpty())
        assertEquals(JSONObject(stateBefore).getString("promotionFromHash"),
            harness.states.load(key).getString("promotionFromHash"))
        try {
            harness.service.recoverSelection(game)
            fail("Recovery adopted changed directory")
        } catch (failure: SaveSyncException) {
            assertEquals(SaveSyncError.SAVE_LOCATION_CHANGED, failure.code)
        }
        // Reverting settings completes the original token without another import.
        harness.world.directoryKey = null
        harness.setActive(game, "selected-S")
        assertTrue(harness.service.syncGame(game, SaveSyncDirection.UPLOAD).failures.isEmpty())
        assertEquals(1, harness.world.importCount(game))
        assertNull(harness.journal.load(key))
    }

    @Test
    fun manualUploadPausesWhenCompletedPromotionDirectoryChangesFromAToB() = runBlocking {
        val harness = Harness(temp)
        val game = harness.addGame("completed-promotion-directory-binding")
        harness.identify(game)
        harness.setActive(game, "before-A")
        val key = harness.key(game)
        val selected = harness.addSnapshot(game, "selected-S", 1_700_000_000_010L)
        val remoteS = RemoteSaveVersion(
            selected.id,
            selected.contentHash,
            selected.createdAt,
            selected.size,
        )
        harness.transport.seed(
            harness.remoteGame(game),
            remoteS,
            harness.snapshots.file(key, selected.id),
        )

        assertEquals(SaveActivationResult.APPLIED, harness.service.activateVersion(game, selected.id))
        assertNull(harness.journal.load(key))
        val promotionBefore = JSONObject(harness.states.load(key).toString())
        assertEquals(selected.contentHash, promotionBefore.getString("promotionFromHash"))
        assertTrue(promotionBefore.getBoolean("promotionAwaitingChange"))

        harness.world.directoryKey = "directory-B"
        val unrelatedB = harness.setActive(game, "unrelated-B")
        val report = harness.service.syncGame(game, SaveSyncDirection.UPLOAD)

        assertEquals(listOf(SaveSyncError.SAVE_LOCATION_CHANGED), report.failures.map { it.error })
        assertEquals(0, report.uploaded)
        assertEquals(unrelatedB, harness.world.activeHash(game))
        assertEquals(0, harness.transport.uploadCount(unrelatedB))
        assertEquals(listOf(remoteS), harness.transport.versions(harness.remoteGame(game)))
        val promotionAfter = harness.states.load(key)
        for (field in listOf(
            "completedActivation",
            "activationHash",
            "promotionFromHash",
            "promotionFromTime",
            "promotionAt",
            "promotionBaseHash",
            "promotionAwaitingChange",
            "promotionContentHash",
        )) {
            assertEquals(promotionBefore.opt(field), promotionAfter.opt(field))
        }

        harness.world.directoryKey = null
        val playedN = harness.setActive(game, "played-N")
        val resumed = harness.service.syncGame(game, SaveSyncDirection.UPLOAD)

        assertTrue(resumed.failures.toString(), resumed.failures.isEmpty())
        val published = harness.transport.versions(harness.remoteGame(game)).single()
        assertEquals(playedN, published.contentHash)
        assertTrue(published.restored)
        assertEquals(selected.createdAt, published.previousCreatedAt)
    }

    @Test
    fun metadataPendingActivationFencesAliasUploadWithDifferentIdentityAtSameDirectory() = runBlocking {
        val harness = Harness(temp)
        val activationGame = harness.addGame("activation-A")
        val aliasGame = harness.addGame("alias-B")
        harness.identify(activationGame)
        harness.identify(aliasGame)
        harness.world.directoryKey = "shared-directory-A"
        harness.setActive(activationGame, "before-A")
        val selected = harness.addSnapshot(activationGame, "selected-S", 1_700_000_000_010L)
        harness.world.failNextRestoredMetadata()

        assertEquals(
            SaveActivationResult.METADATA_PENDING,
            harness.service.activateVersion(activationGame, selected.id),
        )
        assertEquals(selected.contentHash, harness.world.activeHash(activationGame))
        assertEquals(1, harness.world.importCount(activationGame))
        assertTrue(harness.service.identity(activationGame) != harness.service.identity(aliasGame))
        val activationKey = harness.key(activationGame)
        assertTrue(harness.journal.load(activationKey) != null)
        assertTrue(harness.states.load(activationKey).has("completedActivation"))
        harness.setActive(aliasGame, "unrelated-B")
        val aliasHash = harness.world.activeHash(aliasGame)
        val aliasKey = harness.key(aliasGame)

        val report = harness.service.syncGame(aliasGame, SaveSyncDirection.UPLOAD)

        assertEquals(0, report.uploaded)
        assertTrue(
            report.toString(),
            report.busy > 0 || report.failures.any {
                it.error == SaveSyncError.SELECTION_INCOMPLETE || it.error == SaveSyncError.SAVE_LOCATION_CHANGED
            },
        )
        assertEquals(0, harness.transport.uploadCount(aliasHash))
        assertTrue(harness.transport.versions(harness.remoteGame(aliasGame)).isEmpty())
        assertFalse(harness.snapshots.list(aliasKey).any { it.contentHash == aliasHash })
        assertTrue(harness.journal.load(activationKey) != null)
    }

    @Test
    fun committedActivationCleanupFailureKeepsAliasBlockedAndStaleTokenCannotPublishSiblingBytes() = runBlocking {
        val harness = Harness(temp)
        val gameA = harness.addGame("cleanup-owner-A")
        val gameB = harness.addGame("cleanup-alias-B")
        harness.identify(gameA)
        harness.identify(gameB)
        val sharedDirectory = "shared-cleanup-directory"
        harness.world.directoryKey = sharedDirectory
        harness.setActive(gameA, "before-A")
        harness.setActive(gameB, "before-B")
        val keyA = harness.key(gameA)
        val keyB = harness.key(gameB)
        val selectedA = harness.addSnapshot(gameA, "selected-A-S", 1_700_000_000_010L)
        val selectedB = harness.addSnapshot(gameB, "selected-B-S", 1_700_000_000_020L)
        val remoteA = RemoteSaveVersion(
            selectedA.id,
            selectedA.contentHash,
            selectedA.createdAt,
            selectedA.size,
        )
        harness.transport.seed(
            harness.remoteGame(gameA),
            remoteA,
            harness.snapshots.file(keyA, selectedA.id),
        )
        val activationStore = SaveDirectoryActivationStore(harness.root)
        val journalArchive = harness.journal.archive(keyA)
        val journalRecord = File(harness.root, "pending/records/$keyA.json")
        val archiveBackup = File(harness.root, "blocked-staging-backup.zip")
        var obstructCleanup = true
        lateinit var staleToken: String
        harness.world.onRetireDirectory = { directoryKey ->
            assertEquals(sharedDirectory, directoryKey)
            if (obstructCleanup) {
                obstructCleanup = false
                val pending = requireNotNull(harness.journal.load(keyA))
                staleToken = pending.token
                harness.sessions.save(keyA, JSONObject()
                    .put("token", staleToken)
                    .put("baseline", selectedA.contentHash)
                    .put("activeKey", sharedDirectory)
                    .put("returned", true))
                journalArchive.copyTo(archiveBackup, overwrite = true)
                check(journalArchive.delete())
                check(journalArchive.mkdir())
                File(journalArchive, "blocked").writeText("cleanup blocked", Charsets.UTF_8)
            }
        }

        assertEquals(
            SaveActivationResult.METADATA_PENDING,
            harness.service.activateVersion(gameA, selectedA.id),
        )

        assertEquals(1, harness.world.importCount(gameA))
        assertEquals(selectedA.contentHash, harness.world.activeHash(gameA))
        assertEquals(staleToken, harness.states.load(keyA).getString("completedActivation"))
        assertEquals(staleToken, activationStore.revision(sharedDirectory))
        assertTrue(journalArchive.isDirectory)
        assertTrue(harness.journal.load(keyA) != null)
        val committedJournalText = journalRecord.readText(Charsets.UTF_8)
        assertTrue(JSONObject(committedJournalText).getBoolean("committed"))
        val owned = activationStore.owned(gameA.uri).single()
        assertEquals(sharedDirectory, owned.activeKey)
        assertEquals(gameA.uri, owned.uri)
        assertEquals(staleToken, owned.token)
        val importCountB = harness.world.importCount(gameB)
        val activeHashB = harness.world.activeHash(gameB)

        try {
            harness.service.activateVersion(gameB, selectedB.id)
            fail("An alias activation must wait for the owner's committed cleanup")
        } catch (failure: SaveSyncException) {
            assertEquals(SaveSyncError.SELECTION_INCOMPLETE, failure.code)
        }

        assertEquals(importCountB, harness.world.importCount(gameB))
        assertEquals(activeHashB, harness.world.activeHash(gameB))
        assertEquals(staleToken, activationStore.revision(sharedDirectory))

        assertTrue(File(journalArchive, "blocked").delete())
        assertTrue(journalArchive.delete())
        archiveBackup.copyTo(journalArchive, overwrite = true)
        assertTrue(
            harness.service.activateVersion(gameA, selectedA.id) in
                setOf(SaveActivationResult.APPLIED, SaveActivationResult.UNCHANGED),
        )
        assertEquals(staleToken, activationStore.revision(sharedDirectory))
        assertTrue(activationStore.owned(gameA.uri).isEmpty())
        assertNull(harness.journal.load(keyA))

        assertEquals(
            SaveActivationResult.APPLIED,
            harness.service.activateVersion(gameB, selectedB.id),
        )
        val revisionB = harness.states.load(keyB).getString("completedActivation")
        assertTrue(revisionB != staleToken)
        assertEquals(revisionB, activationStore.revision(sharedDirectory))
        assertEquals(1, harness.world.importCount(gameB))
        assertEquals(selectedB.contentHash, harness.world.activeHash(gameB))

        harness.world.setActive(gameA, harness.world.activeFile(gameB))
        journalRecord.writeText(committedJournalText, Charsets.UTF_8)
        archiveBackup.copyTo(journalArchive, overwrite = true)
        assertEquals(committedJournalText, journalRecord.readText(Charsets.UTF_8))
        assertTrue(JSONObject(journalRecord.readText()).getBoolean("committed"))
        val staleManual = harness.service.syncGame(gameA, SaveSyncDirection.UPLOAD)

        assertEquals(0, staleManual.uploaded)
        assertEquals(listOf(SaveSyncError.SELECTION_INCOMPLETE), staleManual.failures.map { it.error })
        assertEquals(revisionB, activationStore.revision(sharedDirectory))
        assertEquals(selectedB.contentHash, harness.world.activeHash(gameA))
        assertEquals(listOf(remoteA), harness.transport.versions(harness.remoteGame(gameA)))
        assertEquals(0, harness.transport.uploadCount(selectedB.contentHash))
        assertFalse(harness.snapshots.list(keyA).any { it.contentHash == selectedB.contentHash })

        val stale = harness.service.syncGameIfCurrent(gameA, SaveSyncDirection.UPLOAD) {
            harness.sessions.load(keyA).optString("token") == staleToken
        }

        assertEquals(0, stale.uploaded)
        assertEquals(0, stale.busy)
        assertEquals(listOf(SaveSyncError.SELECTION_INCOMPLETE), stale.failures.map { it.error })
        assertEquals(revisionB, activationStore.revision(sharedDirectory))
        assertEquals(selectedB.contentHash, harness.world.activeHash(gameA))
        assertEquals(listOf(remoteA), harness.transport.versions(harness.remoteGame(gameA)))
        assertEquals(0, harness.transport.uploadCount(selectedB.contentHash))
        assertFalse(harness.snapshots.list(keyA).any { it.contentHash == selectedB.contentHash })
        assertTrue(harness.snapshots.isValid(
            keyA,
            harness.snapshots.list(keyA).single { it.contentHash == selectedA.contentHash },
        ))
    }

    @Test
    fun siblingActivationRevisionMakesOlderPromotionRecoverableWithoutImportOrRestoredPublication() = runBlocking {
        val harness = Harness(temp)
        val gameA = harness.addGame("ancestry-A")
        val gameB = harness.addGame("ancestry-B")
        harness.identify(gameA)
        harness.identify(gameB)
        val sharedDirectory = "shared-ancestry-directory"
        harness.world.directoryKey = sharedDirectory
        harness.setActive(gameA, "before-A")
        harness.setActive(gameB, "before-B")
        val keyA = harness.key(gameA)
        val selectedA = harness.addSnapshot(gameA, "selected-A-S", 1_700_000_000_010L)
        val selectedB = harness.addSnapshot(gameB, "selected-B-S", 1_700_000_000_020L)
        val remoteA = RemoteSaveVersion(
            selectedA.id,
            selectedA.contentHash,
            selectedA.createdAt,
            selectedA.size,
        )
        harness.transport.seed(
            harness.remoteGame(gameA),
            remoteA,
            harness.snapshots.file(keyA, selectedA.id),
        )
        val activationStore = SaveDirectoryActivationStore(harness.root)

        assertEquals(
            SaveActivationResult.APPLIED,
            harness.service.activateVersion(gameA, selectedA.id),
        )
        val revisionA = harness.states.load(keyA).getString("promotionDirectoryRevision")
        assertEquals(revisionA, activationStore.revision(sharedDirectory))
        assertEquals(
            SaveActivationResult.APPLIED,
            harness.service.activateVersion(gameB, selectedB.id),
        )
        val revisionB = harness.states.load(harness.key(gameB)).getString("completedActivation")
        assertTrue(revisionB != revisionA)
        assertEquals(revisionB, activationStore.revision(sharedDirectory))
        val activeN = harness.setActive(gameA, "active-only-N")
        harness.world.setActive(gameB, harness.world.activeFile(gameA))
        assertFalse(harness.snapshots.list(keyA).any { it.contentHash == activeN })
        assertTrue(harness.service.needsSelectionRecovery(gameA))
        val importCount = harness.world.importCount(gameA)
        val restoredMetadataCount = harness.world.restoredMetadataCount(gameA)

        harness.service.recoverSelection(gameA)

        assertEquals(importCount, harness.world.importCount(gameA))
        assertEquals(restoredMetadataCount, harness.world.restoredMetadataCount(gameA))
        assertEquals(activeN, harness.world.activeHash(gameA))
        val recoveredHistory = harness.snapshots.list(keyA)
        for (hash in setOf(selectedA.contentHash, activeN)) {
            assertTrue(harness.snapshots.isValid(
                keyA,
                recoveredHistory.single { it.contentHash == hash },
            ))
        }
        val recoveredState = harness.states.load(keyA)
        for (field in listOf(
            "completedActivation",
            "activationHash",
            "promotionFromHash",
            "promotionFromTime",
            "promotionAt",
            "promotionBaseHash",
            "promotionAwaitingChange",
            "promotionContentHash",
            "promotionActiveKey",
            "promotionDirectoryRevision",
        )) {
            assertFalse(field, recoveredState.has(field))
        }
        assertFalse(harness.service.needsSelectionRecovery(gameA))

        val report = harness.service.syncGame(gameA, SaveSyncDirection.UPLOAD)

        assertTrue(report.failures.toString(), report.failures.isEmpty())
        assertEquals(1, report.uploaded)
        val remote = harness.transport.versions(harness.remoteGame(gameA))
        val publishedN = remote.single { it.contentHash == activeN }
        assertFalse(publishedN.restored)
        assertNull(publishedN.previousCreatedAt)
        assertTrue(remote.any { it.contentHash == selectedA.contentHash })
        assertEquals(restoredMetadataCount, harness.world.restoredMetadataCount(gameA))
        assertEquals(revisionB, activationStore.revision(sharedDirectory))
    }

    @Test
    fun abortedPreparedCleanupRestoresPriorRevisionAndKeepsCompletedPromotionChain() = runBlocking {
        val harness = Harness(temp)
        val game = harness.addGame("aborted-prepared-cleanup")
        harness.identify(game)
        val key = harness.key(game)
        val directoryKey = game.uri
        harness.setActive(game, "before-A")
        val selected = harness.addSnapshot(game, "selected-S", 1_700_000_000_010L)
        lateinit var completedToken: String
        harness.world.onRetireDirectory = { retiredDirectory ->
            assertEquals(directoryKey, retiredDirectory)
            val pending = harness.journal.load(key)
            if (pending != null) {
                completedToken = pending.token
                harness.sessions.save(key, JSONObject()
                    .put("token", completedToken)
                    .put("baseline", selected.contentHash)
                    .put("activeKey", directoryKey)
                    .put("returned", true))
            }
        }
        assertEquals(SaveActivationResult.APPLIED, harness.service.activateVersion(game, selected.id))
        val activationStore = SaveDirectoryActivationStore(harness.root)
        assertEquals(completedToken, activationStore.revision(directoryKey))
        val currentN = harness.setActive(game, "current-N")
        val stateBeforeAbort = JSONObject(harness.states.load(key).toString())
        val sessionBeforeAbort = JSONObject(harness.sessions.load(key).toString())
        val abandonedSource = harness.archive("aborted-Y", "aborted-Y")
        val abandoned = harness.versionFor(abandonedSource)
        val aborted = harness.journal.prepare(key, abandoned, abandonedSource, currentN, directoryKey)
        activationStore.begin(directoryKey, game.uri, aborted.token)
        activationStore.prepareCompletion(directoryKey, game.uri, aborted.token, committed = false)
        harness.journal.clear(key)

        assertFalse(harness.journal.archive(key).exists())
        assertEquals(currentN, harness.world.activeHash(game))
        assertTrue(activationStore.revision(directoryKey) != completedToken)
        assertTrue(activationStore.owned(game.uri).single().committed == false)
        val importCount = harness.world.importCount(game)
        val restoredMetadataCount = harness.world.restoredMetadataCount(game)

        harness.service.recoverSelection(game)

        assertEquals(importCount, harness.world.importCount(game))
        assertEquals(restoredMetadataCount, harness.world.restoredMetadataCount(game))
        assertEquals(currentN, harness.world.activeHash(game))
        assertEquals(completedToken, activationStore.revision(directoryKey))
        assertTrue(activationStore.owned(game.uri).isEmpty())
        val stateAfterRecovery = harness.states.load(key)
        for (field in listOf(
            "completedActivation",
            "activationHash",
            "promotionFromHash",
            "promotionFromTime",
            "promotionAt",
            "promotionBaseHash",
            "promotionAwaitingChange",
            "promotionContentHash",
            "promotionActiveKey",
            "promotionDirectoryRevision",
        )) {
            assertEquals(field, stateBeforeAbort.opt(field), stateAfterRecovery.opt(field))
        }
        val sessionAfterRecovery = harness.sessions.load(key)
        for (field in listOf("token", "baseline", "activeKey", "returned")) {
            assertEquals(field, sessionBeforeAbort.opt(field), sessionAfterRecovery.opt(field))
        }
        assertTrue(harness.snapshots.isValid(
            key,
            harness.snapshots.list(key).single { it.contentHash == currentN },
        ))
        assertFalse(harness.service.needsSelectionRecovery(game))

        val report = harness.service.syncGame(game, SaveSyncDirection.UPLOAD)

        assertTrue(report.failures.toString(), report.failures.isEmpty())
        assertEquals(1, report.uploaded)
        val published = harness.transport.versions(harness.remoteGame(game)).single()
        assertEquals(currentN, published.contentHash)
        assertTrue(published.restored)
        assertEquals(selected.createdAt, published.previousCreatedAt)
    }

    @Test
    fun committedPreparedCleanupWithoutJournalRetainsChainAndSessionBeforeRelease() = runBlocking {
        val harness = Harness(temp)
        val game = harness.addGame("committed-prepared-cleanup")
        harness.identify(game)
        val key = harness.key(game)
        val directoryKey = game.uri
        harness.setActive(game, "before-A")
        val selected = harness.addSnapshot(game, "selected-S", 1_700_000_000_010L)
        val journalArchive = harness.journal.archive(key)
        val archiveBackup = File(harness.root, "committed-cleanup-backup.zip")
        var obstructCleanup = true
        lateinit var completedToken: String
        harness.world.onRetireDirectory = { retiredDirectory ->
            assertEquals(directoryKey, retiredDirectory)
            val pending = harness.journal.load(key)
            if (pending != null) {
                completedToken = pending.token
                harness.sessions.save(key, JSONObject()
                    .put("token", completedToken)
                    .put("baseline", selected.contentHash)
                    .put("activeKey", directoryKey)
                    .put("returned", true))
            }
            if (obstructCleanup) {
                obstructCleanup = false
                journalArchive.copyTo(archiveBackup, overwrite = true)
                check(journalArchive.delete())
                check(journalArchive.mkdir())
                File(journalArchive, "blocked").writeText("cleanup blocked", Charsets.UTF_8)
            }
        }

        assertEquals(
            SaveActivationResult.METADATA_PENDING,
            harness.service.activateVersion(game, selected.id),
        )
        val activationStore = SaveDirectoryActivationStore(harness.root)
        val stateBeforeRecovery = JSONObject(harness.states.load(key).toString())
        val sessionBeforeRecovery = JSONObject(harness.sessions.load(key).toString())
        assertEquals(completedToken, activationStore.revision(directoryKey))
        assertTrue(activationStore.owned(game.uri).single().committed == true)
        assertTrue(File(journalArchive, "blocked").delete())
        assertTrue(journalArchive.delete())
        archiveBackup.copyTo(journalArchive, overwrite = true)
        harness.journal.clear(key)
        val currentN = harness.setActive(game, "current-N")
        val importCount = harness.world.importCount(game)
        assertNull(harness.journal.load(key))
        assertTrue(activationStore.owned(game.uri).single().committed == true)

        harness.service.recoverSelection(game)

        assertEquals(importCount, harness.world.importCount(game))
        assertEquals(currentN, harness.world.activeHash(game))
        assertEquals(completedToken, activationStore.revision(directoryKey))
        assertTrue(activationStore.owned(game.uri).isEmpty())
        val stateAfterRecovery = harness.states.load(key)
        for (field in listOf(
            "completedActivation",
            "activationHash",
            "promotionFromHash",
            "promotionFromTime",
            "promotionAt",
            "promotionBaseHash",
            "promotionAwaitingChange",
            "promotionContentHash",
            "promotionActiveKey",
            "promotionDirectoryRevision",
        )) {
            assertEquals(field, stateBeforeRecovery.opt(field), stateAfterRecovery.opt(field))
        }
        val sessionAfterRecovery = harness.sessions.load(key)
        for (field in listOf("token", "baseline", "activeKey", "returned")) {
            assertEquals(field, sessionBeforeRecovery.opt(field), sessionAfterRecovery.opt(field))
        }
        assertTrue(harness.snapshots.isValid(
            key,
            harness.snapshots.list(key).single { it.contentHash == currentN },
        ))
        assertFalse(harness.service.needsSelectionRecovery(game))
    }

    @Test
    fun recordRestoreHookFailureKeepsAliasSessionUntilOwnerRetryAndStaleClosureStaysBusy() = runBlocking {
        val harness = Harness(temp)
        val owner = harness.addGame("owner-A")
        val alias = harness.addGame("unavailable-alias-B")
        harness.identify(owner)
        harness.identify(alias)
        val sharedDirectory = "shared-directory-A"
        harness.world.directoryKey = sharedDirectory
        harness.world.unavailableUris += alias.uri
        harness.setActive(owner, "before-A")
        val selected = harness.addSnapshot(owner, "selected-S", 1_700_000_000_010L)
        val ownerKey = harness.key(owner)
        val aliasKey = harness.key(alias)
        val aliasToken = "alias-token-B"
        harness.sessions.save(aliasKey, JSONObject()
            .put("token", aliasToken)
            .put("baseline", selected.contentHash)
            .put("activeKey", sharedDirectory))
        harness.baselines.save(aliasKey, JSONObject()
            .put("contentHash", selected.contentHash)
            .put("activeKey", sharedDirectory))
        var retireCalls = 0
        var failFirstRetire = true
        harness.world.onRetireDirectory = { directoryKey ->
            retireCalls++
            assertEquals(sharedDirectory, directoryKey)
            if (failFirstRetire) {
                failFirstRetire = false
                assertEquals(aliasToken, harness.sessions.load(aliasKey).getString("token"))
                throw SaveSyncException(SaveSyncError.LOCAL_IO)
            }
            clearDirectorySessionBaselines(directoryKey, harness.sessions, harness.baselines)
        }

        assertEquals(
            SaveActivationResult.METADATA_PENDING,
            harness.service.activateVersion(owner, selected.id),
        )

        assertEquals(1, retireCalls)
        assertEquals(aliasToken, harness.sessions.load(aliasKey).getString("token"))
        assertEquals(selected.contentHash, harness.baselines.load(aliasKey).getString("contentHash"))
        assertTrue(harness.journal.load(ownerKey) != null)
        harness.world.setActive(alias, harness.world.activeFile(owner))
        val aliasHash = harness.world.activeHash(alias)
        val blocked = harness.service.syncGameIfCurrent(alias, SaveSyncDirection.UPLOAD) {
            harness.sessions.load(aliasKey).optString("token") == aliasToken
        }

        assertEquals(0, blocked.uploaded)
        assertEquals(aliasToken, harness.sessions.load(aliasKey).getString("token"))
        assertTrue(harness.transport.versions(harness.remoteGame(alias)).isEmpty())
        assertFalse(harness.snapshots.list(aliasKey).any { it.contentHash == aliasHash })

        assertEquals(SaveActivationResult.APPLIED, harness.service.activateVersion(owner, selected.id))

        assertEquals(2, retireCalls)
        assertEquals(1, harness.world.importCount(owner))
        assertEquals(0, harness.sessions.load(aliasKey).length())
        assertEquals(0, harness.baselines.load(aliasKey).length())
        assertNull(harness.journal.load(ownerKey))

        harness.world.unavailableUris.remove(alias.uri)
        val stale = harness.service.syncGameIfCurrent(alias, SaveSyncDirection.UPLOAD) {
            harness.sessions.load(aliasKey).optString("token") == aliasToken
        }

        assertLocalStateChanged(stale)
        assertEquals(0, harness.transport.uploadCount(aliasHash))
        assertTrue(harness.transport.versions(harness.remoteGame(alias)).isEmpty())
        assertFalse(harness.snapshots.list(aliasKey).any { it.contentHash == aliasHash })
    }

    @Test
    fun previousVersionPromotionWithoutDirectoryBindingPausesUntilExplicitReactivationThenPublishesN() = runBlocking {
        val harness = Harness(temp)
        val game = harness.addGame("legacy-unbound-promotion")
        harness.identify(game)
        harness.setActive(game, "before-A")
        val key = harness.key(game)
        val selected = harness.addSnapshot(game, "selected-S", 1_700_000_000_010L)
        val remoteS = RemoteSaveVersion(
            selected.id,
            selected.contentHash,
            selected.createdAt,
            selected.size,
        )
        harness.transport.seed(
            harness.remoteGame(game),
            remoteS,
            harness.snapshots.file(key, selected.id),
        )
        assertEquals(SaveActivationResult.APPLIED, harness.service.activateVersion(game, selected.id))
        assertNull(harness.journal.load(key))

        val legacyState = harness.states.load(key)
        legacyState.remove("promotionActiveKey")
        legacyState.remove("promotionDirectoryRevision")
        harness.states.save(key, legacyState)
        val deletesBefore = harness.transport.deleteCount()

        val paused = harness.service.syncGame(game, SaveSyncDirection.UPLOAD)

        assertEquals(0, paused.uploaded)
        assertTrue(
            paused.toString(),
            paused.busy > 0 || paused.failures.any {
                it.error == SaveSyncError.SAVE_LOCATION_CHANGED || it.error == SaveSyncError.SELECTION_INCOMPLETE
            },
        )
        assertEquals(deletesBefore, harness.transport.deleteCount())
        assertEquals(listOf(remoteS), harness.transport.versions(harness.remoteGame(game)))

        assertEquals(SaveActivationResult.APPLIED, harness.service.activateVersion(game, selected.id))

        val repaired = harness.states.load(key)
        assertEquals(game.uri, repaired.getString("promotionActiveKey"))
        assertTrue(repaired.getString("promotionDirectoryRevision").isNotBlank())
        assertNull(harness.journal.load(key))
        val playedN = harness.setActive(game, "played-N")
        val report = harness.service.syncGame(game, SaveSyncDirection.UPLOAD)

        assertTrue(report.failures.toString(), report.failures.isEmpty())
        assertEquals(1, report.uploaded)
        val published = harness.transport.versions(harness.remoteGame(game)).single()
        assertEquals(playedN, published.contentHash)
        assertTrue(published.restored)
        assertEquals(repaired.getLong("promotionFromTime"), published.previousCreatedAt)
    }

    @Test
    fun legacyUnboundPromotionRecoveryCapturesActiveOnlyNWithoutImportingAndPublishesItOrdinarily() = runBlocking {
        val harness = Harness(temp)
        val game = harness.addGame("legacy-active-only-n")
        harness.identify(game)
        harness.setActive(game, "before-A")
        val key = harness.key(game)
        val selected = harness.addSnapshot(game, "selected-S", 1_700_000_000_010L)
        val remoteS = RemoteSaveVersion(
            selected.id,
            selected.contentHash,
            selected.createdAt,
            selected.size,
        )
        harness.transport.seed(
            harness.remoteGame(game),
            remoteS,
            harness.snapshots.file(key, selected.id),
        )
        assertEquals(SaveActivationResult.APPLIED, harness.service.activateVersion(game, selected.id))

        val legacyState = harness.states.load(key)
        legacyState.remove("promotionActiveKey")
        legacyState.remove("promotionDirectoryRevision")
        harness.states.save(key, legacyState)
        val activeN = harness.setActive(game, "active-only-N")
        assertFalse(harness.snapshots.list(key).any { it.contentHash == activeN })
        val deletesBefore = harness.transport.deleteCount()

        val paused = harness.service.syncGame(game, SaveSyncDirection.UPLOAD)

        assertEquals(0, paused.uploaded)
        assertTrue(
            paused.toString(),
            paused.busy > 0 || paused.failures.any {
                it.error == SaveSyncError.SAVE_LOCATION_CHANGED || it.error == SaveSyncError.SELECTION_INCOMPLETE
            },
        )
        assertEquals(deletesBefore, harness.transport.deleteCount())
        assertEquals(listOf(remoteS), harness.transport.versions(harness.remoteGame(game)))
        assertTrue(harness.service.needsSelectionRecovery(game))
        val importCount = harness.world.importCount(game)

        harness.service.recoverSelection(game)

        assertEquals(activeN, harness.world.activeHash(game))
        assertEquals(importCount, harness.world.importCount(game))
        val history = harness.snapshots.list(key)
        assertTrue(harness.snapshots.isValid(key, history.single { it.contentHash == selected.contentHash }))
        assertTrue(harness.snapshots.isValid(key, history.single { it.contentHash == activeN }))
        val recoveredState = harness.states.load(key)
        for (field in listOf(
            "completedActivation",
            "activationHash",
            "promotionFromHash",
            "promotionFromTime",
            "promotionAt",
            "promotionBaseHash",
            "promotionAwaitingChange",
            "promotionContentHash",
            "promotionActiveKey",
            "promotionDirectoryRevision",
        )) {
            assertFalse(recoveredState.has(field))
        }
        assertFalse(harness.service.needsSelectionRecovery(game))

        val report = harness.service.syncGame(game, SaveSyncDirection.UPLOAD)

        assertTrue(report.failures.toString(), report.failures.isEmpty())
        assertEquals(1, report.uploaded)
        val remote = harness.transport.versions(harness.remoteGame(game))
        assertEquals(setOf(selected.contentHash, activeN), remote.map { it.contentHash }.toSet())
        val publishedN = remote.single { it.contentHash == activeN }
        assertFalse(publishedN.restored)
        assertNull(publishedN.previousCreatedAt)
        assertTrue(remote.any { it.contentHash == selected.contentHash })
    }

    @Test
    fun completedSameTokenJournalRetryRecreatesReturnedSessionWithoutRetiringItAgain() = runBlocking {
        val harness = Harness(temp)
        val game = harness.addGame("completed-token-session-retry")
        harness.identify(game)
        val beforeHash = harness.setActive(game, "before-A")
        val key = harness.key(game)
        val directoryKey = game.uri
        val selected = harness.addSnapshot(game, "selected-S", 1_700_000_000_010L)
        val returnedSessionToken = "selected-returned-session"
        var retireCalls = 0
        harness.world.onRetireDirectory = { retiredDirectory ->
            retireCalls++
            clearDirectorySessionBaselines(retiredDirectory, harness.sessions, harness.baselines)
            harness.sessions.save(key, JSONObject()
                .put("uri", game.uri)
                .put("baseline", selected.contentHash)
                .put("token", returnedSessionToken)
                .put("activeKey", retiredDirectory)
                .put("startedAt", 0L)
                .put("returned", true))
        }

        assertEquals(SaveActivationResult.APPLIED, harness.service.activateVersion(game, selected.id))

        val completedState = JSONObject(harness.states.load(key).toString())
        val completedToken = completedState.getString("completedActivation")
        assertEquals(1, retireCalls)
        assertNull(harness.journal.load(key))

        val staging = harness.archive("restaged-completed-S", "progress-selected-S")
        harness.journal.prepare(key, selected, staging, beforeHash, directoryKey)
        harness.journal.markCommitted(key)
        val record = File(harness.root, "pending/records/$key.json")
        record.writeText(JSONObject(record.readText())
            .put("token", completedToken)
            .put("committed", true)
            .toString())
        harness.sessions.save(key, JSONObject()
            .put("token", "pre-cleanup-session")
            .put("baseline", selected.contentHash)
            .put("activeKey", directoryKey)
            .put("returned", true))
        harness.baselines.save(key, JSONObject()
            .put("contentHash", selected.contentHash)
            .put("activeKey", directoryKey))
        SaveDirectoryActivationStore(harness.root).begin(directoryKey, game.uri, completedToken)
        val importCount = harness.world.importCount(game)

        harness.service.recoverSelection(game)

        assertEquals(2, retireCalls)
        assertEquals(importCount, harness.world.importCount(game))
        assertNull(harness.journal.load(key))
        val recoveredState = harness.states.load(key)
        for (field in listOf(
            "completedActivation",
            "activationHash",
            "promotionFromHash",
            "promotionFromTime",
            "promotionAt",
            "promotionBaseHash",
            "promotionAwaitingChange",
            "promotionContentHash",
            "promotionActiveKey",
            "promotionDirectoryRevision",
        )) {
            assertEquals(completedState.opt(field), recoveredState.opt(field))
        }
        val returnedSession = harness.sessions.load(key)
        assertEquals(returnedSessionToken, returnedSession.getString("token"))
        assertEquals(selected.contentHash, returnedSession.getString("baseline"))
        assertEquals(directoryKey, returnedSession.getString("activeKey"))
        assertTrue(returnedSession.getBoolean("returned"))
        assertEquals(0, harness.baselines.load(key).length())

        val playedN = harness.setActive(game, "played-N")
        val report = harness.service.syncGameIfCurrent(game, SaveSyncDirection.UPLOAD) {
            harness.sessions.load(key).optString("token") == returnedSessionToken
        }

        assertTrue(report.failures.toString(), report.failures.isEmpty())
        assertEquals(1, report.uploaded)
        val published = harness.transport.versions(harness.remoteGame(game)).single()
        assertEquals(playedN, published.contentHash)
        assertTrue(published.restored)
        assertEquals(completedState.getLong("promotionFromTime"), published.previousCreatedAt)
    }

    @Test
    fun restoreBookkeepingKeepsImportDirectoryWhenSettingsChangeAtRecordBoundary() = runBlocking {
        val harness = Harness(temp)
        val game = harness.addGame("restore-bookkeeping-binding")
        harness.identify(game)
        harness.setActive(game, "before")
        val selected = harness.addSnapshot(game, "selected-S", 1_700_000_000_010L)
        harness.world.changeDirectoryOnRecord = "directory-B"

        assertEquals(SaveActivationResult.METADATA_PENDING, harness.service.activateVersion(game, selected.id))

        assertEquals(game.uri, harness.world.lastRecordedDirectory)
        assertEquals(game.uri, harness.journal.load(harness.key(game))!!.activeKey)
        assertEquals(selected.contentHash, harness.world.activeHash(game))
        assertTrue(harness.transport.versions(harness.remoteGame(game)).isEmpty())
    }

    @Test
    fun legacyUnboundJournalRequiresExplicitRescueBeforeTrackingCurrentDirectory() = runBlocking {
        val harness = Harness(temp)
        val game = harness.addGame("legacy-unbound-activation")
        harness.identify(game)
        val key = harness.key(game)
        val selected = harness.addSnapshot(game, "old-selected-A", 1_700_000_000_010L)
        harness.journal.prepare(key, selected, harness.archive("staged-A", "progress-old-selected-A"), "before-A")
        harness.journal.markCommitted(key)
        harness.world.directoryKey = "changed-directory-B"
        val current = harness.setActive(game, "unrelated-current-B")

        val report = harness.service.syncGame(game, SaveSyncDirection.UPLOAD)
        assertEquals(listOf(SaveSyncError.SELECTION_INCOMPLETE), report.failures.map { it.error })
        assertTrue(harness.journal.load(key) != null)
        assertEquals(0, harness.world.restoredMetadataCount(game))
        assertTrue(harness.transport.versions(harness.remoteGame(game)).isEmpty())
        try {
            harness.service.activateVersion(game, selected.id)
            fail("Legacy activation was silently adopted")
        } catch (failure: SaveSyncException) {
            assertEquals(SaveSyncError.SELECTION_INCOMPLETE, failure.code)
        }

        // The explicit recovery action only rescues bytes and abandons unknown bookkeeping.
        harness.service.recoverSelection(game)
        assertNull(harness.journal.load(key))
        assertEquals(current, harness.world.activeHash(game))
        assertEquals(0, harness.world.importCount(game))
        for (hash in setOf(selected.contentHash, current)) {
            assertTrue(harness.snapshots.isValid(key, harness.snapshots.list(key).single { it.contentHash == hash }))
        }
        assertFalse(harness.states.load(key).has("promotionFromHash"))
    }

    private fun assertLocalStateChanged(report: SaveSyncReport) {
        assertEquals(0, report.busy)
        assertEquals(listOf(SaveSyncError.LOCAL_STATE_CHANGED), report.failures.map { it.error })
    }

    private fun seedCleanupHistory(harness: Harness, game: ScanGame, count: Int) {
        val remoteGame = harness.remoteGame(game)
        repeat(count) { index ->
            val createdAt = 1_700_000_000_000L + index
            val hash = (index + 1).toString(16).padStart(64, '0')
            harness.transport.seed(
                remoteGame,
                RemoteSaveVersion(
                    name = SaveVersionNames.create(createdAt, hash),
                    contentHash = hash,
                    createdAt = createdAt,
                    size = 10,
                ),
            )
        }
    }

    private class Harness(private val temp: TemporaryFolder, remoteLimit: Int = 10) {
        val root: File = temp.newFolder("save-sync")
        private val cache: File = temp.newFolder("cache")
        private val work: File = temp.newFolder("work")
        private val config = WebDavConfig(
            serverUrl = "https://dav.example.invalid/saves",
            username = "tester",
            password = "secret",
            mode = SaveSyncMode.BIDIRECTIONAL,
            remoteLimit = remoteLimit,
            enabled = true,
        )
        val transport = FakeTransport()
        val world = FakeEnvironment(cache, config, transport)
        val service = SaveSyncService(root, world)
        val snapshots = SaveSnapshotStore(File(root, "versions"))
        val states = SaveSyncStateStore(File(root, "checkpoints"))
        val journal = SaveActivationJournal(File(root, "pending"))
        val sessions = SaveSyncStateStore(File(root, "sessions"))
        val baselines = SaveSyncStateStore(File(root, "baselines"))
        private var archiveIndex = 0

        fun addGame(label: String): ScanGame {
            val game = ScanGame(
                title = label,
                uri = "file:///library/$label-${UUID.randomUUID()}",
                engine = EngineType.ONS,
                launchTarget = "__game_dir__",
            )
            world.addGame(game)
            return game
        }

        suspend fun identify(game: ScanGame) {
            service.setIdentity(game, "identity-${game.title}")
        }

        fun setActive(game: ScanGame, label: String): String {
            val source = archive(label, "progress-$label")
            world.setActive(game, source)
            return world.activeHash(game)
        }

        fun addSnapshot(game: ScanGame, label: String, createdAt: Long): SaveVersion {
            val source = archive(label, "progress-$label")
            return snapshots.add(
                key(game),
                source,
                createdAt,
                SaveVersionSource.CLOUD,
            )
        }

        fun stageSnapshot(game: ScanGame, label: String, createdAt: Long): SaveVersion =
            stageSnapshot(game, archive(label, "progress-$label"), createdAt)

        fun stageSnapshot(game: ScanGame, source: File, createdAt: Long): SaveVersion {
            val hash = SaveContentHash.archive(source)
            val id = SaveVersionNames.create(createdAt, hash)
            val destination = snapshots.file(key(game), id)
            val parent = requireNotNull(destination.parentFile)
            check(parent.isDirectory || parent.mkdirs()) { "Cannot create snapshot directory" }
            source.copyTo(destination, overwrite = true)
            File(parent, destination.name + ".cloud").writeText("")
            return SaveVersion(
                id = id,
                contentHash = hash,
                createdAt = createdAt,
                size = destination.length(),
                source = SaveVersionSource.CLOUD,
            )
        }

        fun archive(label: String, content: String): File {
            val safeLabel = label.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val file = File(work, "${archiveIndex++}-$safeLabel.zip")
            file.parentFile?.mkdirs()
            ZipOutputStream(file.outputStream()).use { output ->
                output.putNextEntry(ZipEntry("save.bin").apply { time = 1_700_000_000_000L })
                output.write(content.toByteArray(Charsets.UTF_8))
                output.closeEntry()
            }
            return file
        }

        fun versionFor(source: File): SaveVersion {
            val hash = SaveContentHash.archive(source)
            return SaveVersion(
                id = SaveVersionNames.create(1_700_000_000_800L, hash),
                contentHash = hash,
                createdAt = 1_700_000_000_800L,
                size = source.length(),
                source = SaveVersionSource.CLOUD,
            )
        }

        fun key(game: ScanGame): String = SaveContentHash.text(game.uri)

        fun remoteGame(game: ScanGame): RemoteSaveGame =
            RemoteSaveGame(game.engine.name, service.identity(game))

        fun checkpointKey(game: ScanGame): String {
            val remoteKey = SaveContentHash.text(game.engine.name + "\n" + service.identity(game))
            return SaveContentHash.text(
                config.serverUrl.trimEnd('/') + "\n" + config.username + "\n" + remoteKey + "\n" + game.uri,
            )
        }

        fun hasPendingPromotion(game: ScanGame): Boolean =
            service.hasPendingPromotion(game, config)

        fun makeCacheUnusable(): File {
            val unusable = LowSpaceFile(cache)
            world.setCacheDirectory(unusable)
            return unusable
        }
    }

    private class FakeEnvironment(
        initialCacheDirectory: File,
        private val webDavConfig: WebDavConfig,
        private val transport: FakeTransport,
    ) : SaveSyncEnvironment {
        private val lock = Any()
        @Volatile
        private var currentCacheDirectory = initialCacheDirectory
        private val games = mutableListOf<ScanGame>()
        private val active = HashMap<String, File>()
        private val activeHashes = HashMap<String, String>()
        private val importCounts = HashMap<String, Int>()
        private val restoredMetadataCounts = HashMap<String, Int>()
        private var recordFailures = 0
        private var openedClients = 0
        private var exports = 0
        private var completeImports = 0
        private var activeSequence = 0
        @Volatile
        override var enabled = true
        @Volatile
        var isRunning = false
        @Volatile
        var recoveryNeeded = false
        @Volatile var directoryKey: String? = null
        var changeDirectoryOnRecord: String? = null
        var lastRecordedDirectory: String? = null
        @Volatile var onRunning: (() -> Unit)? = null
        val unavailableUris = HashSet<String>()
        val activeKeyEntered = CompletableDeferred<Unit>()
        @Volatile
        var onRetireDirectory: (suspend (String) -> Unit)? = null

        override val cacheDirectory: File get() = currentCacheDirectory
        override fun config(): WebDavConfig = webDavConfig
        override fun games(): List<ScanGame> = synchronized(lock) { games.toList() }
        override fun available(game: ScanGame): Boolean = synchronized(lock) { game in games && game.uri !in unavailableUris }
        override fun running(game: ScanGame): Boolean {
            val value = isRunning
            onRunning?.invoke()
            return value
        }
        override fun activeKey(game: ScanGame): String {
            val key = directoryKey ?: game.uri
            activeKeyEntered.complete(Unit)
            return key
        }

        override suspend fun fingerprint(game: ScanGame): String? =
            synchronized(lock) { activeHashes[game.uri] }

        override suspend fun export(game: ScanGame, archive: File) {
            val source = synchronized(lock) { active[game.uri] }
                ?: throw SaveSyncException(SaveSyncError.SAVE_UNAVAILABLE)
            source.copyTo(archive, overwrite = true)
            synchronized(lock) {
                exports++
            }
        }

        override suspend fun import(game: ScanGame, archive: File, activeKey: String, committed: () -> Unit) {
            val destination = File(cacheDirectory.parentFile, "active-import-${activeSequence++}.zip")
            archive.copyTo(destination, overwrite = true)
            val hash = SaveContentHash.archive(destination)
            synchronized(lock) {
                active[game.uri] = destination
                activeHashes[game.uri] = hash
                importCounts[game.uri] = importCounts.getOrDefault(game.uri, 0) + 1
            }
            committed()
        }

        override fun completeImport(game: ScanGame) {
            synchronized(lock) { completeImports++ }
        }
        override fun needsManualRecovery(game: ScanGame): Boolean = recoveryNeeded
        override fun repairManualSelection(game: ScanGame, hasCurrent: Boolean): Boolean = true

        override suspend fun recordRestored(game: ScanGame, hash: String, activeKey: String): Boolean {
            val recorded = synchronized(lock) {
                restoredMetadataCounts[game.uri] = restoredMetadataCounts.getOrDefault(game.uri, 0) + 1
                lastRecordedDirectory = activeKey
                changeDirectoryOnRecord?.let { directoryKey = it; changeDirectoryOnRecord = null }
                when {
                    activeKey != (directoryKey ?: game.uri) -> false
                    recordFailures > 0 -> {
                        recordFailures--
                        false
                    }
                    else -> true
                }
            }
            if (!recorded) return false
            try {
                onRetireDirectory?.invoke(activeKey)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return false
            }
            return true
        }

        override suspend fun retireSessions(game: ScanGame, activeKey: String) {
            onRetireDirectory?.invoke(activeKey)
        }

        override fun openClient(config: WebDavConfig): SaveSyncTransport = synchronized(lock) {
            openedClients++
            transport
        }

        fun addGame(game: ScanGame) = synchronized(lock) {
            games += game
        }

        fun setActive(game: ScanGame, source: File) {
            val hash = SaveContentHash.archive(source)
            synchronized(lock) {
                active[game.uri] = source
                activeHashes[game.uri] = hash
            }
        }

        fun clearActive(game: ScanGame) {
            synchronized(lock) {
                active.remove(game.uri)
                activeHashes.remove(game.uri)
            }
        }

        fun hasActive(game: ScanGame): Boolean = synchronized(lock) {
            game.uri in active
        }

        fun setCacheDirectory(directory: File) {
            currentCacheDirectory = directory
        }

        fun activeHash(game: ScanGame): String = synchronized(lock) {
            checkNotNull(activeHashes[game.uri])
        }

        fun activeFile(game: ScanGame): File = synchronized(lock) {
            checkNotNull(active[game.uri])
        }

        fun importCount(game: ScanGame): Int = synchronized(lock) {
            importCounts.getOrDefault(game.uri, 0)
        }

        fun restoredMetadataCount(game: ScanGame): Int = synchronized(lock) {
            restoredMetadataCounts.getOrDefault(game.uri, 0)
        }

        fun exportCount(): Int = synchronized(lock) { exports }

        fun completeImportCount(): Int = synchronized(lock) { completeImports }

        fun failNextRestoredMetadata() = synchronized(lock) {
            recordFailures++
        }

        fun openClientCount(): Int = synchronized(lock) { openedClients }
    }

    private class LowSpaceFile(file: File) : File(file.path) {
        override fun getUsableSpace(): Long = 0L
    }

    private class FakeTransport : SaveSyncTransport {
        private val lock = Any()
        private val remote = HashMap<String, LinkedHashMap<String, RemoteSaveVersion>>()
        private val archives = HashMap<String, ByteArray>()
        private val uploads = HashMap<String, Int>()
        private val uploadInvocations = HashMap<String, Int>()
        private val listInvocations = HashMap<String, Int>()
        private var downloads = 0
        private var deleteFailures = 0
        private var verifies = 0
        private var deletes = 0
        private val corruptTargets = HashSet<String>()
        var corruptNextUpload = false
        private var closed = false
        @Volatile
        var listGate: CompletableDeferred<Unit>? = null
        @Volatile
        var uploadGate: CompletableDeferred<Unit>? = null
        @Volatile
        var discardGate: CompletableDeferred<Unit>? = null
        @Volatile
        var listFailure: Throwable? = null
        @Volatile
        private var uploadFailure: Throwable? = null
        @Volatile
        var onDelete: (() -> Unit)? = null
        val listEntered = CompletableDeferred<Unit>()
        val uploadEntered = CompletableDeferred<Unit>()
        val discardEntered = CompletableDeferred<Unit>()

        override suspend fun list(game: RemoteSaveGame): List<RemoteSaveVersion> {
            synchronized(lock) {
                listInvocations[game.key] = listInvocations.getOrDefault(game.key, 0) + 1
            }
            listEntered.complete(Unit)
            listGate?.await()
            listFailure?.let { throw it }
            return synchronized(lock) {
                remote[game.key]?.values?.toList().orEmpty()
            }
        }

        override suspend fun download(
            game: RemoteSaveGame,
            remote: RemoteSaveVersion,
            destination: File,
        ) {
            val bytes = synchronized(lock) {
                downloads++
                archives[remote.contentHash]
            } ?: throw SaveSyncException(SaveSyncError.SERVER)
            destination.writeBytes(bytes)
        }

        override suspend fun upload(
            game: RemoteSaveGame,
            version: SaveVersion,
            source: File,
        ): WebDavClient.UploadResult {
            val failure = synchronized(lock) {
                uploadInvocations[version.contentHash] = uploadInvocations.getOrDefault(version.contentHash, 0) + 1
                uploadFailure.also { uploadFailure = null }
            }
            uploadEntered.complete(Unit)
            failure?.let { throw it }
            uploadGate?.await()
            val bytes = source.readBytes()
            synchronized(lock) {
                archives[version.contentHash] = bytes
                remote.getOrPut(game.key) { LinkedHashMap() }[version.id] = RemoteSaveVersion(
                    name = version.id,
                    contentHash = version.contentHash,
                    createdAt = version.createdAt,
                    size = bytes.size.toLong(),
                    restored = version.restored,
                    previousCreatedAt = version.previousCreatedAt,
                )
                uploads[version.contentHash] = uploads.getOrDefault(version.contentHash, 0) + 1
                if (corruptNextUpload) {
                    corruptTargets += version.id
                    corruptNextUpload = false
                }
            }
            return WebDavClient.UploadResult(true, bytes.size.toLong())
        }

        override suspend fun refresh(
            game: RemoteSaveGame,
            previous: RemoteSaveVersion,
            publication: SaveVersion,
            source: File,
        ): Long {
            val bytes = source.readBytes()
            synchronized(lock) {
                val versions = remote.getOrPut(game.key) { LinkedHashMap() }
                versions.remove(previous.name)
                versions[publication.id] = RemoteSaveVersion(
                    name = publication.id,
                    contentHash = publication.contentHash,
                    createdAt = publication.createdAt,
                    size = bytes.size.toLong(),
                    restored = publication.restored,
                    previousCreatedAt = publication.previousCreatedAt,
                )
                archives[publication.contentHash] = bytes
                uploads[publication.contentHash] = uploads.getOrDefault(publication.contentHash, 0) + 1
            }
            return bytes.size.toLong()
        }

        override suspend fun verifyVersion(game: RemoteSaveGame, remote: RemoteSaveVersion): Boolean =
            synchronized(lock) {
                verifies++
                this.remote[game.key]?.containsKey(remote.name) == true && remote.name !in corruptTargets
            }

        override suspend fun discardCorruptPublication(
            game: RemoteSaveGame,
            remote: RemoteSaveVersion,
            beforeDelete: suspend () -> Unit,
        ): Boolean {
            discardEntered.complete(Unit)
            discardGate?.await()
            beforeDelete()
            return synchronized(lock) { this.remote[game.key]?.remove(remote.name) != null }
        }

        override suspend fun delete(game: RemoteSaveGame, remote: RemoteSaveVersion) {
            val callback = synchronized(lock) {
                deletes++
                if (deleteFailures > 0) {
                    deleteFailures--
                    throw SaveSyncException(SaveSyncError.SERVER)
                }
                this.remote[game.key]?.remove(remote.name)
                onDelete
            }
            callback?.invoke()
        }

        override fun close() {
            synchronized(lock) { closed = true }
        }

        fun seed(game: RemoteSaveGame, version: RemoteSaveVersion, source: File? = null) {
            synchronized(lock) {
                remote.getOrPut(game.key) { LinkedHashMap() }[version.name] = version
                if (source != null) archives[version.contentHash] = source.readBytes()
            }
        }

        fun markCorrupt(name: String) = synchronized(lock) { corruptTargets += name }

        fun versions(game: RemoteSaveGame): List<RemoteSaveVersion> = synchronized(lock) {
            remote[game.key]?.values?.toList().orEmpty()
        }

        fun uploadCount(hash: String): Int = synchronized(lock) {
            uploads.getOrDefault(hash, 0)
        }
        fun uploadInvocationCount(hash: String): Int = synchronized(lock) {
            uploadInvocations.getOrDefault(hash, 0)
        }
        fun listInvocationCount(game: RemoteSaveGame): Int = synchronized(lock) {
            listInvocations.getOrDefault(game.key, 0)
        }

        fun downloadCount(): Int = synchronized(lock) { downloads }
        fun verifyCount(): Int = synchronized(lock) { verifies }
        fun deleteCount(): Int = synchronized(lock) { deletes }
        fun remove(game: RemoteSaveGame, hash: String) = synchronized(lock) {
            remote[game.key]?.entries?.removeAll { it.value.contentHash == hash }
        }

        fun failNextDelete() = synchronized(lock) {
            deleteFailures++
        }

        fun failNextUpload(failure: Throwable) = synchronized(lock) {
            uploadFailure = failure
        }
    }
}
