package com.tyranor.next.core.game.save.sync

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SaveActivationJournalTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun preparedZipMovesToDurablePerGameArchiveAndSurvivesNewInstance() {
        val root = temp.newFolder("journal")
        val journal = SaveActivationJournal(root)
        val staging = archive(root, "selected-s")
        val expectedBytes = staging.readBytes()
        val selected = version(staging)

        val pending = journal.prepare(KEY_A, selected, staging, "before-s")

        assertFalse(staging.exists())
        assertTrue(journal.archive(KEY_A).isFile)
        assertArrayEquals(expectedBytes, journal.archive(KEY_A).readBytes())
        assertEquals("before-s", pending.beforeHash)
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
