package com.tyranor.next.core.game.save.sync

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SaveSnapshotStoreTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun versionsAreDeduplicatedByContentsIgnoringZipOrderAndMtime() {
        val store = SaveSnapshotStore(temp.newFolder())
        val first = archive(listOf("a" to "first", "b" to "second"), 1000)
        val reordered = archive(listOf("b" to "second", "a" to "first"), 200000)
        val one = store.add(KEY_A, first, 1, SaveVersionSource.LOCAL)
        val two = store.add(KEY_A, reordered, 2, SaveVersionSource.CLOUD)
        assertEquals(one.id, two.id)
        assertEquals(1, store.list(KEY_A).size)
    }

    @Test fun fileContentChangesWithTheSameSizeAndMtimeProduceAnotherVersion() {
        val first = archive(listOf("save" to "one"), 1000)
        val changed = archive(listOf("save" to "two"), 1000)
        assertNotEquals(SaveContentHash.archive(first), SaveContentHash.archive(changed))
    }

    @Test fun localRetentionIsFiveForEachGameAndNeverEvictsAnotherGame() {
        val store = SaveSnapshotStore(temp.newFolder())
        for (version in 1L..4L) store.add(KEY_B, archive(listOf("save" to "b$version")), version, SaveVersionSource.LOCAL)
        for (version in 1L..13L) store.add(KEY_A, archive(listOf("save" to "a$version")), version, SaveVersionSource.LOCAL)
        assertEquals(listOf(13L, 12L, 11L, 10L, 9L), store.list(KEY_A).map { it.createdAt })
        assertEquals(listOf(4L, 3L, 2L, 1L), store.list(KEY_B).map { it.createdAt })
        val oldestRetained = store.list(KEY_A).last()
        repeat(8) { store.add(KEY_A, store.file(KEY_A, oldestRetained.id), 99L, SaveVersionSource.LOCAL) }
        assertEquals(5, store.list(KEY_A).size)
        assertTrue(store.file(KEY_A, oldestRetained.id).isFile)
    }

    @Test fun olderCloudTimeIsPreservedAndThePulledVersionRemainsSelectable() {
        val store = SaveSnapshotStore(temp.newFolder())
        for (version in 10L..14L) store.add(KEY_A, archive(listOf("save" to "a$version")), version, SaveVersionSource.LOCAL)
        val downloaded = store.add(KEY_A, archive(listOf("save" to "cloud")), 2L, SaveVersionSource.CLOUD)
        store.maintain(KEY_A)
        assertEquals(5, store.list(KEY_A).size)
        assertEquals(2L, downloaded.createdAt)
        assertTrue(store.file(KEY_A, downloaded.id).isFile)
        assertTrue(downloaded.size > 0)
        assertTrue(store.list(KEY_A).any { it.id == downloaded.id })
        assertFalse(store.list(KEY_A).any { it.createdAt == 10L })
    }

    @Test fun restoredRefreshPromotesOldestCloudProfileWithoutEvictingEitherGame() {
        val root = temp.newFolder()
        val store = SaveSnapshotStore(root)
        for (version in 1L..4L) store.add(KEY_B, archive(listOf("save" to "b$version")), version, SaveVersionSource.LOCAL)
        val selectedArchive = archive(listOf("save" to "selected"))
        val selected = store.add(KEY_A, selectedArchive, 1L, SaveVersionSource.CLOUD)
        for (version in 2L..5L) store.add(KEY_A, archive(listOf("save" to "a$version")), version, SaveVersionSource.LOCAL)
        val unrelatedBefore = store.list(KEY_B)

        val refreshed = store.add(
            KEY_A, selectedArchive, 100L, SaveVersionSource.LOCAL,
            restored = true, refreshExisting = true,
        )
        val profiles = store.list(KEY_A)
        assertEquals(5, profiles.size)
        assertEquals(1, profiles.count { it.contentHash == selected.contentHash })
        assertEquals(refreshed, profiles.first())
        assertEquals(100L, refreshed.createdAt)
        assertTrue(refreshed.restored)
        assertEquals(SaveVersionSource.CLOUD, refreshed.source)

        val reopened = SaveSnapshotStore(root)
        val reopenedProfiles = reopened.list(KEY_A)
        assertEquals(profiles, reopenedProfiles)
        assertEquals(refreshed, reopenedProfiles.first())
        assertTrue(reopened.file(KEY_A, refreshed.id).isFile)
        assertEquals(unrelatedBefore, reopened.list(KEY_B))
    }

    @Test fun evictedSelectedArchiveCanBeRestoredWithoutLosingRetentionIntegrity() {
        val root = temp.newFolder()
        val store = SaveSnapshotStore(root)
        val selectedArchive = archive(listOf("save" to "selected"))
        val selected = store.add(KEY_A, selectedArchive, 1L, SaveVersionSource.CLOUD)
        for (version in 2L..5L) store.add(KEY_A, archive(listOf("save" to "a$version")), version, SaveVersionSource.LOCAL)
        val selectedCopy = temp.newFile().apply { selectedArchive.copyTo(this, overwrite = true) }

        store.add(KEY_A, archive(listOf("save" to "a6")), 6L, SaveVersionSource.LOCAL)
        assertEquals(5, store.list(KEY_A).size)
        assertFalse(store.list(KEY_A).any { it.id == selected.id })
        assertFalse(store.file(KEY_A, selected.id).exists())

        val recovered = store.add(
            KEY_A, selectedCopy, 100L, SaveVersionSource.CLOUD,
            restored = true, refreshExisting = true,
        )
        val retained = store.list(KEY_A)
        assertEquals(5, retained.size)
        assertEquals(listOf(100L, 6L, 5L, 4L, 3L), retained.map { it.createdAt })
        assertEquals(recovered, retained.first())
        assertEquals(1, retained.count { it.contentHash == selected.contentHash })
        assertTrue(recovered.restored)

        val reopened = SaveSnapshotStore(root)
        val reopenedProfiles = reopened.list(KEY_A)
        assertEquals(retained, reopenedProfiles)
        assertEquals(recovered, reopenedProfiles.first())
        assertEquals(recovered.contentHash, SaveContentHash.archive(reopened.file(KEY_A, recovered.id)))
    }

    @Test fun corruptOrMismatchedDownloadDoesNotEvictAnyLocalVersion() {
        val store = SaveSnapshotStore(temp.newFolder())
        for (version in 1L..5L) store.add(KEY_A, archive(listOf("save" to "$version")), version, SaveVersionSource.LOCAL)
        val before = store.list(KEY_A)
        val bad = temp.newFile().apply { writeText("not a ZIP") }
        try {
            store.add(KEY_A, bad, 6L, SaveVersionSource.CLOUD)
            throw AssertionError("Corrupt archive accepted")
        } catch (expected: SaveSyncException) { assertEquals(SaveSyncError.CORRUPT_ARCHIVE, expected.code) }
        try {
            store.add(KEY_A, archive(listOf("save" to "six")), 6L, SaveVersionSource.CLOUD, "0".repeat(64))
            throw AssertionError("Wrong content hash accepted")
        } catch (expected: SaveSyncException) { assertEquals(SaveSyncError.CORRUPT_ARCHIVE, expected.code) }
        assertEquals(before, store.list(KEY_A))
    }

    @Test fun validIncomingRepairsCorruptSameHashArchiveWithoutDuplicate() {
        val store = SaveSnapshotStore(temp.newFolder())
        val incoming = archive(listOf("save" to "stable"))
        val existing = store.add(KEY_A, incoming, 1L, SaveVersionSource.CLOUD)
        val cached = store.file(KEY_A, existing.id)
        val cloudMarker = File(cached.parentFile, cached.name + ".cloud")
        cached.writeText("corrupt")
        assertFalse(store.isValid(KEY_A, existing))

        val repaired = store.add(KEY_A, incoming, 99L, SaveVersionSource.LOCAL)

        assertEquals(existing.id, repaired.id)
        assertEquals(existing.createdAt, repaired.createdAt)
        assertEquals(SaveVersionSource.CLOUD, repaired.source)
        assertEquals(listOf(repaired), store.list(KEY_A))
        assertTrue(store.isValid(KEY_A, repaired))
        assertTrue(cloudMarker.isFile)
        assertFalse(cached.parentFile.listFiles().orEmpty().any { it.name.startsWith("snapshot_") })
    }

    @Test fun repairedArchiveStillAppliesRequestedRestoredMetadataRefresh() {
        val store = SaveSnapshotStore(temp.newFolder())
        val incoming = archive(listOf("save" to "restored"))
        val existing = store.add(KEY_A, incoming, 1L, SaveVersionSource.LOCAL)
        store.file(KEY_A, existing.id).writeText("corrupt")

        val refreshed = store.add(
            KEY_A,
            incoming,
            100L,
            SaveVersionSource.CLOUD,
            restored = true,
            refreshExisting = true,
            previousCreatedAt = existing.createdAt,
        )

        assertNotEquals(existing.id, refreshed.id)
        assertTrue(refreshed.restored)
        assertEquals(existing.createdAt, refreshed.previousCreatedAt)
        assertEquals(listOf(refreshed), store.list(KEY_A))
        assertTrue(store.isValid(KEY_A, refreshed))
    }

    @Test fun corruptIncomingLeavesCorruptCachedBytesUntouched() {
        val store = SaveSnapshotStore(temp.newFolder())
        val existing = store.add(KEY_A, archive(listOf("save" to "existing")), 1L, SaveVersionSource.LOCAL)
        val cached = store.file(KEY_A, existing.id).apply { writeText("cached-corrupt") }
        val cachedBytes = cached.readBytes()
        val corruptIncoming = temp.newFile().apply { writeText("not a ZIP") }

        try {
            store.add(KEY_A, corruptIncoming, 2L, SaveVersionSource.CLOUD)
            throw AssertionError("Corrupt archive accepted")
        } catch (expected: SaveSyncException) {
            assertEquals(SaveSyncError.CORRUPT_ARCHIVE, expected.code)
        }

        assertEquals(cachedBytes.toList(), cached.readBytes().toList())
        assertFalse(cached.parentFile.listFiles().orEmpty().any { it.name.startsWith("snapshot_") })
    }

    @Test fun cachedArchiveIoFailureIsNotReportedAsCorruption() {
        val store = SaveSnapshotStore(temp.newFolder())
        val id = SaveVersionNames.create(1L, "a".repeat(64))
        val missing = SaveVersion(id, "a".repeat(64), 1L, 1L)

        try {
            store.isValid(KEY_A, missing)
            throw AssertionError("Missing cached archive accepted")
        } catch (expected: SaveSyncException) {
            assertEquals(SaveSyncError.LOCAL_IO, expected.code)
        }
    }

    @Test fun cancellationDuringIncomingValidationPropagatesWithoutTouchingCache() {
        val store = SaveSnapshotStore(temp.newFolder())
        val incoming = archive(listOf("save" to "stable"))
        val existing = store.add(KEY_A, incoming, 1L, SaveVersionSource.LOCAL)
        val cachedBytes = store.file(KEY_A, existing.id).readBytes()
        val cancelled = CancellationException("cancelled")

        try {
            store.add(KEY_A, incoming, 2L, SaveVersionSource.CLOUD, check = { throw cancelled })
            throw AssertionError("Cancellation was swallowed")
        } catch (actual: CancellationException) {
            assertSame(cancelled, actual)
        }

        assertEquals(cachedBytes.toList(), store.file(KEY_A, existing.id).readBytes().toList())
        assertTrue(store.isValid(KEY_A, existing))
    }

    @Test fun zipIdentityMatchesExportedFilesAndRejectsTraversal() {
        val root = temp.newFolder().canonicalFile
        val save = File(root, "slot/save.bin").apply { checkNotNull(parentFile).mkdirs(); writeText("progress") }
        assertEquals(SaveContentHash.files(root, listOf(save)), SaveContentHash.archive(archive(listOf("slot/save.bin" to "progress"))))
        try {
            SaveContentHash.archive(archive(listOf("../save" to "bad")))
            throw AssertionError("Unsafe path accepted")
        } catch (expected: SaveSyncException) { assertEquals(SaveSyncError.CORRUPT_ARCHIVE, expected.code) }
    }

    @Test fun readingVersionsDoesNotPruneOrModifyTheirFiles() {
        val store = SaveSnapshotStore(temp.newFolder())
        val version = store.add(KEY_A, archive(listOf("slot" to "save")), 1, SaveVersionSource.CLOUD)
        val file = store.file(KEY_A, version.id)
        val modified = file.lastModified()
        repeat(10) { assertEquals(version, store.list(KEY_A).single()) }
        assertEquals(modified, file.lastModified())
        assertFalse(store.list(KEY_A).isEmpty())
    }

    @Test fun maintenanceClearsInterruptedWritesAndOrphanMarkersButKeepsRealHistory() {
        val root = temp.newFolder()
        val store = SaveSnapshotStore(root)
        val cloud = store.add(KEY_A, archive(listOf("save" to "cloud")), 1, SaveVersionSource.CLOUD)
        val local = store.add(KEY_A, archive(listOf("save" to "local")), 2, SaveVersionSource.LOCAL)
        val directory = File(root, KEY_A)
        val cloudMarker = File(directory, cloud.id + ".cloud")
        assertTrue(cloudMarker.isFile)

        // Registered in-flight write: cleaning must not delete a transfer still in progress.
        val active = SaveSyncTemporaryFiles.create("snapshot_", ".tmp", directory)
        val orphanTemp = File(directory, "snapshot_orphan.tmp").apply { writeText("half written") }
        val orphanMarker = File(directory, SaveVersionNames.create(9L, "c".repeat(64)) + ".cloud")
        val foreignMarker = File(directory, "not-a-version.zip.cloud").apply { writeText("") }
        val versionNames = { directory.listFiles().orEmpty().mapNotNull { SaveVersionNames.parse(it.name)?.name }.sorted() }
        val historyBefore = versionNames()
        try {
            store.maintain(KEY_A)

            assertEquals(listOf(local, cloud), store.list(KEY_A))
            assertTrue(active.isFile)
            assertTrue(cloudMarker.isFile)
            assertFalse("interrupted write must be reclaimed", orphanTemp.exists())
            assertFalse("marker without its ZIP must be reclaimed", orphanMarker.exists())
            // Only this feature's own files are visited; a foreign marker must survive untouched.
            assertTrue(foreignMarker.isFile)
            assertEquals(historyBefore, versionNames())
        } finally {
            SaveSyncTemporaryFiles.release(active)
        }
        assertFalse(active.exists())
        store.maintain(KEY_A)
        assertEquals(listOf(local, cloud), store.list(KEY_A))
    }

    @Test fun sixthCommittedArchiveSurvivingAPrunedCrashIsTrimmedOnNextAdd() {
        val root = temp.newFolder()
        val store = SaveSnapshotStore(root)
        for (version in 1L..4L) store.add(KEY_B, archive(listOf("save" to "b$version")), version, SaveVersionSource.LOCAL)
        val siblingBefore = store.list(KEY_B)
        for (version in 1L..5L) store.add(KEY_A, archive(listOf("save" to "a$version")), version, SaveVersionSource.LOCAL)

        // Simulate a process killed after committing the sixth ZIP but before pruning it.
        val overflow = archive(listOf("save" to "a6"))
        val directory = File(root, KEY_A)
        assertTrue(directory.isDirectory)
        directory.resolve(SaveVersionNames.create(6L, SaveContentHash.archive(overflow))).writeBytes(overflow.readBytes())

        val seventh = store.add(KEY_A, archive(listOf("save" to "a7")), 7L, SaveVersionSource.LOCAL)

        assertEquals(listOf(7L, 6L, 5L, 4L, 3L), store.list(KEY_A).map { it.createdAt })
        assertEquals(seventh, store.list(KEY_A).first())
        assertEquals(siblingBefore, store.list(KEY_B))
        assertEquals(4, File(root, KEY_B).listFiles().orEmpty().count { SaveVersionNames.parse(it.name) != null })
    }

    private fun archive(entries: List<Pair<String, String>>, time: Long = 1000L): File {
        val file = temp.newFile()
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((name, value) in entries) {
                zip.putNextEntry(ZipEntry(name).apply { this.time = time })
                zip.write(value.toByteArray())
                zip.closeEntry()
            }
        }
        return file
    }

    companion object {
        private val KEY_A = "a".repeat(64)
        private val KEY_B = "b".repeat(64)
    }
}
