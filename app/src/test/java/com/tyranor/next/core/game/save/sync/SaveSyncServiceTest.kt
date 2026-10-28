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
            assertTrue(harness.transport.versions(harness.remoteGame(game)).isEmpty())
        }
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
        val unavailableUris = HashSet<String>()
        val activeKeyEntered = CompletableDeferred<Unit>()

        override fun config(): WebDavConfig = webDavConfig
        override fun games(): List<ScanGame> = synchronized(lock) { games.toList() }
        override fun available(game: ScanGame): Boolean = synchronized(lock) { game in games && game.uri !in unavailableUris }
        override fun running(game: ScanGame): Boolean = isRunning
        override fun activeKey(game: ScanGame): String {
            activeKeyEntered.complete(Unit)
            return game.uri
        }

        override suspend fun fingerprint(game: ScanGame): String? =
            synchronized(lock) { activeHashes[game.uri] }

        override suspend fun export(game: ScanGame, archive: File) {
            val source = synchronized(lock) { active[game.uri] }
                ?: throw SaveSyncException(SaveSyncError.SAVE_UNAVAILABLE)
            source.copyTo(archive, overwrite = true)
        }

        override suspend fun import(game: ScanGame, archive: File, committed: () -> Unit) {
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

        override fun recordRestored(game: ScanGame, hash: String): Boolean = synchronized(lock) {
            restoredMetadataCounts[game.uri] = restoredMetadataCounts.getOrDefault(game.uri, 0) + 1
            if (recordFailures > 0) {
                recordFailures--
                false
            } else {
                true
            }
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
