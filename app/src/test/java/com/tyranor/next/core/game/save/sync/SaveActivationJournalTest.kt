package com.tyranor.next.core.game.save.sync

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SaveActivationJournalTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun orphanArchiveMustBeRecoveredBeforePreparingAnotherSelection() {
        val root = temp.newFolder("orphan-journal")
        val journal = SaveActivationJournal(root)
        val previous = archive(root, "orphan-selected")
        val protected = journal.archive(KEY_A)
        protected.parentFile!!.mkdirs()
        previous.copyTo(protected)
        val expected = protected.readBytes()
        val incoming = archive(root, "new-selection")

        val failure = assertThrows(SaveSyncException::class.java) {
            journal.prepare(KEY_A, version(incoming), incoming, "before")
        }

        assertEquals(SaveSyncError.SELECTION_INCOMPLETE, failure.code)
        assertArrayEquals(expected, protected.readBytes())
        assertTrue(incoming.exists())
        assertNull(journal.load(KEY_A))
    }

    @Test fun preparedZipMovesToDurablePerGameArchiveAndSurvivesNewInstance() {
        val root = temp.newFolder("journal")
        val journal = SaveActivationJournal(root)
        val staging = archive(root, "selected-s")
        val expectedBytes = staging.readBytes()
        val selected = version(staging)

        val pending = journal.prepare(KEY_A, selected, staging, "before-s", activeKey = "save-directory:A")

        assertFalse(staging.exists())
        assertTrue(journal.archive(KEY_A).isFile)
        assertArrayEquals(expectedBytes, journal.archive(KEY_A).readBytes())
        assertEquals("before-s", pending.beforeHash)
        assertEquals("save-directory:A", SaveActivationJournal(root).load(KEY_A)!!.activeKey)
        assertEquals(pending, SaveActivationJournal(root).load(KEY_A))
    }

    @Test fun committedJournalFinalizesContinuedNWhileUncommittedRequiresSelectedS() {
        val root = temp.newFolder("journal")
        val journal = SaveActivationJournal(root)
        val selectedFile = archive(root, "selected-s")
        val selected = version(selectedFile)
        val continuedHash = SaveContentHash.archive(archive(root, "continued-n"))
        val pending = journal.prepare(KEY_A, selected, selectedFile, "before-s")

        assertTrue(pending.canFinalize(selected.contentHash))
        assertFalse(pending.canFinalize(continuedHash))

        val committed = journal.markCommitted(KEY_A)
        assertTrue(committed.committed)
        assertTrue(committed.canFinalize(continuedHash))
        val reopened = SaveActivationJournal(root).load(KEY_A) ?: error("Committed journal was not persisted")
        assertEquals(committed, reopened)
        assertTrue(reopened.canFinalize(continuedHash))
    }

    @Test fun sameBytesAlreadyActiveBeforeImportDoNotProveCommit() {
        val root = temp.newFolder("journal")
        val journal = SaveActivationJournal(root)
        val selectedFile = archive(root, "already-active")
        val selected = version(selectedFile)

        val pending = journal.prepare(KEY_A, selected, selectedFile, selected.contentHash)

        assertFalse(pending.canFinalize(selected.contentHash))
    }

    @Test fun clearRemovesRecordAndArchive() {
        val root = temp.newFolder("journal")
        val journal = SaveActivationJournal(root)
        val staging = archive(root, "selected")
        journal.prepare(KEY_A, version(staging), staging, null)
        val record = File(root, "records/$KEY_A.json")
        assertTrue(record.isFile)
        assertTrue(journal.archive(KEY_A).isFile)

        journal.clear(KEY_A)

        assertFalse(record.exists())
        assertFalse(journal.archive(KEY_A).exists())
        assertNull(SaveActivationJournal(root).load(KEY_A))
    }

    @Test fun invalidIncomingArchiveDoesNotCreateIntent() {
        val root = temp.newFolder("journal")
        val journal = SaveActivationJournal(root)
        val validSource = archive(root, "valid-source")
        val invalid = File(root, "invalid.zip").apply { writeText("not a ZIP") }

        val failure = assertThrows(SaveSyncException::class.java) {
            journal.prepare(KEY_A, version(validSource), invalid, "before")
        }

        assertEquals(SaveSyncError.CORRUPT_ARCHIVE, failure.code)
        assertTrue(invalid.isFile)
        assertNull(journal.load(KEY_A))
        assertFalse(journal.archive(KEY_A).exists())

        val mismatched = archive(root, "mismatched")
        val mismatchFailure = assertThrows(SaveSyncException::class.java) {
            journal.prepare(KEY_A, version(validSource), mismatched, "before")
        }
        assertEquals(SaveSyncError.CORRUPT_ARCHIVE, mismatchFailure.code)
        assertTrue(mismatched.isFile)
        assertNull(journal.load(KEY_A))
        assertFalse(journal.archive(KEY_A).exists())
    }

    @Test fun failedStagingDeletionKeepsCompletedTokenForRetry() {
        val root = temp.newFolder("failed-clear")
        val journal = SaveActivationJournal(root)
        val staging = archive(root, "selected")
        val expectedBytes = staging.readBytes()
        journal.prepare(KEY_A, version(staging), staging, null)
        val pending = journal.markCommitted(KEY_A)
        val source = journal.archive(KEY_A)
        assertTrue(source.delete())
        assertTrue(source.mkdir())
        val blocker = File(source, "cannot-delete-nonempty-directory").apply { writeText("block") }

        val failure = assertThrows(SaveSyncException::class.java) { journal.clear(KEY_A) }

        assertEquals(SaveSyncError.LOCAL_IO, failure.code)
        assertEquals(pending.token, journal.load(KEY_A)!!.token)
        assertTrue(journal.load(KEY_A)!!.committed)
        assertTrue(blocker.delete())
        assertTrue(source.delete())
        source.writeBytes(expectedBytes)
        journal.clear(KEY_A)
        assertNull(journal.load(KEY_A))
    }

    @Test fun abortedImportRetainsEvictedSourceWithinHistoryLimitAndClearsIntent() {
        val root = temp.newFolder("aborted")
        val journal = SaveActivationJournal(File(root, "pending"))
        val snapshots = SaveSnapshotStore(File(root, "history"))
        val staging = archive(root, "selected")
        val selected = version(staging)
        snapshots.add(KEY_A, staging, selected.createdAt, selected.source, selected.contentHash)
        journal.prepare(KEY_A, selected, staging, "active-before-import")
        repeat(5) { index ->
            snapshots.add(KEY_A, archive(root, "later-$index"), selected.createdAt + index + 1,
                SaveVersionSource.LOCAL)
        }
        assertTrue(snapshots.list(KEY_A).none { it.contentHash == selected.contentHash })

        journal.discardUncommitted(KEY_A) { source, version ->
            snapshots.add(KEY_A, source, version.createdAt, version.source, version.contentHash,
                restored = version.restored, previousCreatedAt = version.previousCreatedAt)
        }

        assertNull(journal.load(KEY_A))
        assertFalse(journal.archive(KEY_A).exists())
        assertEquals(5, snapshots.list(KEY_A).size)
        assertTrue(snapshots.isValid(KEY_A, snapshots.list(KEY_A).single { it.id == selected.id }))
    }

    @Test fun failedAbortRecoveryKeepsIntentAndSourceForRetry() {
        val root = temp.newFolder("abort-failure")
        val journal = SaveActivationJournal(root)
        val staging = archive(root, "selected")
        val pending = journal.prepare(KEY_A, version(staging), staging, "before")
        val failure = IOException("directory rollback failed")

        assertSame(failure, assertThrows(IOException::class.java) {
            journal.discardUncommitted(KEY_A) { _, _ -> throw failure }
        })

        assertEquals(pending, journal.load(KEY_A))
        assertTrue(journal.archive(KEY_A).isFile)
    }

    @Test fun abortCannotDiscardCommittedSelection() {
        val root = temp.newFolder("committed-abort")
        val journal = SaveActivationJournal(root)
        val staging = archive(root, "selected")
        journal.prepare(KEY_A, version(staging), staging, "before")
        val committed = journal.markCommitted(KEY_A)

        val failure = assertThrows(SaveSyncException::class.java) {
            journal.discardUncommitted(KEY_A) { _, _ -> error("must not discard a committed import") }
        }

        assertEquals(SaveSyncError.SELECTION_INCOMPLETE, failure.code)
        assertEquals(committed, journal.load(KEY_A))
        assertTrue(journal.archive(KEY_A).isFile)
    }

    @Test fun pendingGuardCannotOverwriteEarlierIntent() {
        val root = temp.newFolder("journal")
        val journal = SaveActivationJournal(root)
        val firstStaging = archive(root, "first")
        val firstBytes = firstStaging.readBytes()
        val first = journal.prepare(KEY_A, version(firstStaging), firstStaging, "before-first")
        val secondStaging = archive(root, "second")

        val failure = assertThrows(SaveSyncException::class.java) {
            journal.prepare(KEY_A, version(secondStaging), secondStaging, "before-second")
        }

        assertEquals(SaveSyncError.SELECTION_INCOMPLETE, failure.code)
        assertTrue(secondStaging.isFile)
        assertArrayEquals(firstBytes, journal.archive(KEY_A).readBytes())
        assertEquals(first, journal.load(KEY_A))
    }

    @Test fun regularFileRecordRootKeepsValidatedArchiveAndFailsClosed() {
        val root = temp.newFolder("obstructed-records")
        val journal = SaveActivationJournal(root)
        val records = File(root, "records")
        assertTrue(records.createNewFile())
        records.writeText("obstruction")
        val staging = archive(root, "selected")
        val expectedBytes = staging.readBytes()

        val failure = assertThrows(SaveSyncException::class.java) {
            journal.prepare(KEY_A, version(staging), staging, "before")
        }

        // The unreadable root cannot hold a pending intent, so the validated ZIP must survive in
        // the durable archive instead of being released with the staging copy.
        assertEquals(SaveSyncError.LOCAL_IO, failure.code)
        assertFalse(staging.exists())
        assertArrayEquals(expectedBytes, journal.archive(KEY_A).readBytes())
        assertTrue(records.isFile)
        assertEquals("obstruction", records.readText())
        assertThrows(SaveSyncException::class.java) { journal.load(KEY_A) }
    }

    @Test fun keysIsolateRecordsAndArchives() {
        val root = temp.newFolder("journal")
        val journal = SaveActivationJournal(root)
        val firstStaging = archive(root, "game-a")
        val secondStaging = archive(root, "game-b")
        val first = journal.prepare(KEY_A, version(firstStaging), firstStaging, null)
        val second = journal.prepare(KEY_B, version(secondStaging), secondStaging, null)

        assertNotEquals(first.token, second.token)
        assertEquals(first, journal.load(KEY_A))
        assertEquals(second, journal.load(KEY_B))
        assertTrue(journal.archive(KEY_A).isFile)
        assertTrue(journal.archive(KEY_B).isFile)

        journal.clear(KEY_A)

        assertNull(journal.load(KEY_A))
        assertEquals(second, journal.load(KEY_B))
        assertFalse(journal.archive(KEY_A).exists())
        assertTrue(journal.archive(KEY_B).isFile)
    }

    private fun version(archive: File): SaveVersion {
        val hash = SaveContentHash.archive(archive)
        return SaveVersion(
            id = SaveVersionNames.create(1_700_000_000_000L, hash),
            contentHash = hash,
            createdAt = 1_700_000_000_000L,
            size = archive.length(),
            source = SaveVersionSource.CLOUD,
        )
    }

    private fun archive(root: File, name: String): File {
        val file = File(root, "$name.zip")
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("save.bin").apply { time = 1_700_000_000_000L })
            zip.write("progress-$name".toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return file
    }

    companion object {
        private val KEY_A = "a".repeat(64)
        private val KEY_B = "b".repeat(64)
    }
}
