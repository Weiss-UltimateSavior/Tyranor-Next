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
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SaveSyncServiceTest {
    @get:Rule
    val temp = TemporaryFolder()

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
            assertEquals(1, report.busy)
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
            assertEquals(1, report.busy)

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
    fun manualMarkerRecoveryPreservesValidPromotionFromSelectedSToPlayedN() = runBlocking {
        val harness = Harness(temp)
        val game = harness.addGame("manual-recovery-promotion")
        harness.identify(game)
        harness.setActive(game, "before-selection")
        val key = harness.key(game)
        val selected = harness.addSnapshot(game, "selected-S", 1_700_000_000_010L)
        harness.transport.seed(harness.remoteGame(game), RemoteSaveVersion(selected.id, selected.contentHash,
            selected.createdAt, selected.size), harness.snapshots.file(key, selected.id))
        assertEquals(SaveActivationResult.APPLIED, harness.service.activateVersion(game, selected.id))
        val current = harness.setActive(game, "played-N")
        harness.world.recoveryNeeded = true

        harness.service.recoverSelection(game)
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
            assertEquals(1, report.busy)
            assertTrue(report.failures.isEmpty())
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
            assertEquals(1, report.busy)
            assertTrue(report.failures.isEmpty())
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

        assertEquals(1, report.busy)
        assertTrue(report.failures.isEmpty())
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

        assertEquals(listOf(SaveSyncError.SELECTION_INCOMPLETE), report.failures.map { it.error })
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

        assertEquals(1, stale.busy)
        assertTrue(stale.failures.isEmpty())
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

    private class Harness(private val temp: TemporaryFolder) {
        val root: File = temp.newFolder("save-sync")
        private val cache: File = temp.newFolder("cache")
        private val work: File = temp.newFolder("work")
        private val config = WebDavConfig(
            serverUrl = "https://dav.example.invalid/saves",
            username = "tester",
            password = "secret",
            mode = SaveSyncMode.BIDIRECTIONAL,
            remoteLimit = 10,
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
    }

    private class FakeEnvironment(
        override val cacheDirectory: File,
        private val webDavConfig: WebDavConfig,
        private val transport: FakeTransport,
    ) : SaveSyncEnvironment {
        private val lock = Any()
        private val games = mutableListOf<ScanGame>()
        private val active = HashMap<String, File>()
        private val activeHashes = HashMap<String, String>()
        private val importCounts = HashMap<String, Int>()
        private val restoredMetadataCounts = HashMap<String, Int>()
        private var recordFailures = 0
        private var openedClients = 0
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
        val unavailableUris = HashSet<String>()
        val activeKeyEntered = CompletableDeferred<Unit>()
        @Volatile
        var onRetireDirectory: (suspend (String) -> Unit)? = null

        override fun config(): WebDavConfig = webDavConfig
        override fun games(): List<ScanGame> = synchronized(lock) { games.toList() }
        override fun available(game: ScanGame): Boolean = synchronized(lock) { game in games && game.uri !in unavailableUris }
        override fun running(game: ScanGame): Boolean = isRunning
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

        override fun completeImport(game: ScanGame) = Unit
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

        fun failNextRestoredMetadata() = synchronized(lock) {
            recordFailures++
        }

        fun openClientCount(): Int = synchronized(lock) { openedClients }
    }

    private class FakeTransport : SaveSyncTransport {
        private val lock = Any()
        private val remote = HashMap<String, LinkedHashMap<String, RemoteSaveVersion>>()
        private val archives = HashMap<String, ByteArray>()
        private val uploads = HashMap<String, Int>()
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
        val listEntered = CompletableDeferred<Unit>()
        val uploadEntered = CompletableDeferred<Unit>()
        val discardEntered = CompletableDeferred<Unit>()

        override suspend fun list(game: RemoteSaveGame): List<RemoteSaveVersion> {
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
            uploadEntered.complete(Unit)
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
            synchronized(lock) {
                deletes++
                if (deleteFailures > 0) {
                    deleteFailures--
                    throw SaveSyncException(SaveSyncError.SERVER)
                }
                this.remote[game.key]?.remove(remote.name)
            }
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

        fun versions(game: RemoteSaveGame): List<RemoteSaveVersion> = synchronized(lock) {
            remote[game.key]?.values?.toList().orEmpty()
        }

        fun uploadCount(hash: String): Int = synchronized(lock) {
            uploads.getOrDefault(hash, 0)
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
    }
}
