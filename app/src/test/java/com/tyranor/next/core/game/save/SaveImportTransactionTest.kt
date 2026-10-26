package com.tyranor.next.core.game.save

import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files

class SaveImportTransactionTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun markerMovedWithStagingProvesCommit() {
        val parent = temporary.newFolder("commit")
        val destination = File(parent, "saves")
        val staging = File(parent, "staging").apply { mkdirs() }
        val transaction = SaveImportTransaction(destination, GAME_URI)

        transaction.prepare(staging)
        assertFalse(transaction.committed())

        assertTrue(staging.renameTo(destination))
        assertTrue(transaction.committed())
        assertTrue(File(destination, SaveImportTransaction.MARKER).isFile)
    }

    @Test
    fun recreatedDestinationDoesNotProveCommitEvenWithBackup() {
        val parent = temporary.newFolder("recreated")
        val destination = File(parent, "saves").apply {
            mkdirs()
            resolve("progress.bin").writeText("old")
        }
        val backup = File(parent, "saves.import_backup")
        val staging = File(parent, "staging").apply { mkdirs() }
        val transaction = SaveImportTransaction(destination, GAME_URI)

        transaction.prepare(staging)
        assertTrue(destination.renameTo(backup))
        assertTrue(destination.mkdirs())
        destination.resolve("progress.bin").writeText("recreated")

        assertTrue(backup.isDirectory)
        assertFalse(transaction.committed())
    }

    @Test
    fun interruptedBackupRecoveryKeepsIndexedBackupAndRecordUntilRetrySucceeds() {
        val recovery = ambiguousRecovery("recovery_retry")
        val oldBytes = recovery.backup.resolve("old.bin").readBytes()
        val failures = listOf(
            IOException("resource copy failed"),
            CancellationException("resource copy cancelled"),
        )

        for (failure in failures) {
            val caught = assertThrows(Exception::class.java) {
                recovery.transaction.recoverBackup(
                    recovery.backup,
                    restoreCommitted = { error("unproven backup must not be finalized") },
                    copyMissingResources = { throw failure },
                )
            }

            assertSame(failure, caught)
            assertTrue(recovery.backup.isDirectory)
            assertTrue(recovery.record.isFile)
            assertTrue(recovery.orphans().isEmpty())
            assertArrayEquals(oldBytes, recovery.backup.resolve("old.bin").readBytes())
        }

        var copyCalls = 0
        recovery.transaction.recoverBackup(
            recovery.backup,
            restoreCommitted = { error("unproven backup must not be finalized") },
            copyMissingResources = { copyCalls++ },
        )

        assertEquals(1, copyCalls)
        assertFalse(recovery.backup.exists())
        assertFalse(recovery.record.exists())
        val orphan = recovery.orphans().single()
        assertArrayEquals(oldBytes, orphan.resolve("old.bin").readBytes())
        assertEquals("new", recovery.destination.resolve("new.bin").readText())
    }

    @Test
    fun missingDestinationRollsBackIndexedBackup() {
        val parent = temporary.newFolder("missing_destination")
        val destination = File(parent, "saves")
        val backup = File(parent, "saves.import_backup").apply {
            mkdirs()
            resolve("old.bin").writeText("old")
        }
        val oldBytes = backup.resolve("old.bin").readBytes()
        val staging = File(parent, "staging").apply { mkdirs() }
        val transaction = SaveImportTransaction(destination, GAME_URI)
        transaction.prepare(staging)
        var restoreCalled = false
        var copyCalled = false

        transaction.recoverBackup(
            backup,
            restoreCommitted = { restoreCalled = true },
            copyMissingResources = { copyCalled = true },
        )

        assertFalse(restoreCalled)
        assertFalse(copyCalled)
        assertFalse(backup.exists())
        assertFalse(recordFor(destination).exists())
        assertTrue(destination.isDirectory)
        assertArrayEquals(oldBytes, destination.resolve("old.bin").readBytes())
    }

    @Test
    fun committedBackupRecoveryDeletesBackupAndClearsProof() {
        val parent = temporary.newFolder("committed_cleanup")
        val destination = File(parent, "saves").apply {
            mkdirs()
            resolve("old.bin").writeText("old")
        }
        val backup = File(parent, "saves.import_backup")
        val staging = File(parent, "staging").apply {
            mkdirs()
            resolve("new.bin").writeText("new")
        }
        val transaction = SaveImportTransaction(destination, GAME_URI)
        transaction.prepare(staging)
        assertTrue(destination.renameTo(backup))
        assertTrue(staging.renameTo(destination))
        assertTrue(transaction.committed())
        var restoreCalls = 0

        transaction.recoverBackup(
            backup,
            restoreCommitted = { restoreCalls++ },
            copyMissingResources = { error("committed backup must not enter ambiguous recovery") },
        )

        assertEquals(1, restoreCalls)
        assertFalse(backup.exists())
        assertFalse(recordFor(destination).exists())
        assertFalse(File(destination, SaveImportTransaction.MARKER).exists())
        assertEquals("new", destination.resolve("new.bin").readText())
    }

    @Test
    fun copyMissingImportResourceDoesNotOverwriteAndSkipsMarker() {
        val parent = temporary.newFolder("copy_merge")
        val destination = File(parent, "destination").apply { mkdirs() }
        val source = File(parent, "backup/system").apply {
            mkdirs()
            resolve("existing.bin").writeText("source")
            resolve("missing.bin").writeText("source")
            resolve(SaveImportTransaction.MARKER).writeText("marker")
            resolve("nested").mkdirs()
            resolve("nested/keep.bin").writeText("nested")
        }
        val target = File(destination, "system").apply {
            mkdirs()
            resolve("existing.bin").writeText("destination")
        }

        copyMissingImportResource(source, target, destination) { it == SaveImportTransaction.MARKER }

        assertEquals("destination", target.resolve("existing.bin").readText())
        assertEquals("source", target.resolve("missing.bin").readText())
        assertEquals("nested", target.resolve("nested/keep.bin").readText())
        assertFalse(File(target, SaveImportTransaction.MARKER).exists())
        assertTrue(resourceTemps(destination).isEmpty())
    }

    @Test
    fun failedResourceCopyLeavesNoPartialTarget() {
        val parent = temporary.newFolder("copy_failure")
        val destination = File(parent, "destination").apply { mkdirs() }
        val source = File(parent, "source.bin").apply { writeText("source") }
        val unreadable = source.setReadable(false, false)
        val hasDeniedRead = unreadable && !source.canRead()
        if (!hasDeniedRead) source.setReadable(true, false)
        assumeTrue("filesystem cannot revoke read access", hasDeniedRead)
        val target = File(destination, "missing.bin")

        try {
            assertThrows(IOException::class.java) {
                copyMissingImportResource(source, target, destination) { false }
            }

            assertFalse(target.exists())
            assertTrue(resourceTemps(destination).isEmpty())
        } finally {
            source.setReadable(true, false)
        }
    }

    @Test
    fun targetAncestorSymlinkIsRejected() {
        val parent = temporary.newFolder("copy_symlink")
        val destination = File(parent, "destination").apply { mkdirs() }
        val outside = File(parent, "outside").apply { mkdirs() }
        val source = File(parent, "source.bin").apply { writeText("source") }
        val link = File(destination, "linked")
        createSymbolicLink(link, outside)
        val target = File(link, "missing.bin")
        try {
            val failure = assertThrows(GameSaveException::class.java) {
                copyMissingImportResource(source, target, destination) { false }
            }

            assertEquals(SaveErrorCode.SAVE_DIR_UNAVAILABLE, failure.code)
            assertFalse(File(outside, "missing.bin").exists())
        } finally {
            runCatching { Files.deleteIfExists(link.toPath()) }
        }
    }

    @Test
    fun mismatchedUriFailsClosed() {
        val committed = committed("mismatched_uri")
        rewriteJson(committed.marker) { it.put("uri", "content://other/game") }

        assertFalse(committed.transaction.committed())
    }

    @Test
    fun mismatchedTokenFailsClosed() {
        val committed = committed("mismatched_token")
        rewriteJson(committed.marker) { it.put("token", "different-token") }

        assertFalse(committed.transaction.committed())
    }

    @Test
    fun missingMarkerFailsClosed() {
        val committed = committed("missing_marker")
        assertTrue(committed.marker.delete())

        assertFalse(committed.transaction.committed())
    }

    @Test
    fun malformedMarkerFailsClosed() {
        val committed = committed("malformed_marker")
        committed.marker.writeText("{not-json")

        assertFalse(committed.transaction.committed())
    }

    @Test
    fun oversizedMarkerFailsClosed() {
        val committed = committed("oversized_marker")
        committed.marker.writeBytes(ByteArray(4_097))

        assertFalse(committed.transaction.committed())
    }

    @Test
    fun preserveUnprovenBackupKeepsOldBytesAtUniquePaths() {
        val parent = temporary.newFolder("preserve")
        val destination = File(parent, "saves")
        val transaction = SaveImportTransaction(destination, GAME_URI)
        val firstBackup = File(parent, "saves.import_backup").apply {
            mkdirs()
            resolve("progress.bin").writeText("first-old")
        }
        val firstBytes = firstBackup.resolve("progress.bin").readBytes()

        val firstRetained = transaction.preserveUnprovenBackup(firstBackup)

        assertFalse(firstBackup.exists())
        assertNotEquals(firstBackup.absolutePath, firstRetained.absolutePath)
        assertTrue(firstRetained.name.startsWith("saves.import_orphan_"))
        assertArrayEquals(firstBytes, firstRetained.resolve("progress.bin").readBytes())

        val secondBackup = File(parent, "saves.import_backup").apply {
            mkdirs()
            resolve("progress.bin").writeText("second-old")
        }
        val secondBytes = secondBackup.resolve("progress.bin").readBytes()

        val secondRetained = transaction.preserveUnprovenBackup(secondBackup)

        assertNotEquals(firstRetained.absolutePath, secondRetained.absolutePath)
        assertArrayEquals(secondBytes, secondRetained.resolve("progress.bin").readBytes())
    }

    @Test
    fun clearRemovesCommitProof() {
        val committed = committed("clear")

        assertTrue(committed.transaction.committed())
        committed.transaction.clear()

        assertFalse(committed.marker.exists())
        assertFalse(committed.record.exists())
        assertFalse(committed.transaction.committed())
    }

    private fun committed(name: String): CommittedTransaction {
        val parent = temporary.newFolder(name)
        val destination = File(parent, "saves")
        val staging = File(parent, "staging").apply { mkdirs() }
        val transaction = SaveImportTransaction(destination, GAME_URI)
        transaction.prepare(staging)
        assertTrue(staging.renameTo(destination))
        assertTrue(transaction.committed())
        return CommittedTransaction(
            transaction = transaction,
            marker = File(destination, SaveImportTransaction.MARKER),
            record = recordFor(destination),
        )
    }

    private fun rewriteJson(file: File, edit: (JSONObject) -> Unit) {
        val json = JSONObject(file.readText())
        edit(json)
        file.writeText(json.toString())
    }

    private fun recordFor(destination: File): File =
        File(destination.parentFile, destination.name + ".import_transaction")

    private fun ambiguousRecovery(name: String): RecoveryFixture {
        val parent = temporary.newFolder(name)
        val destination = File(parent, "saves").apply {
            mkdirs()
            resolve("old.bin").writeText("old")
        }
        val backup = File(parent, "saves.import_backup")
        val staging = File(parent, "staging").apply { mkdirs() }
        val transaction = SaveImportTransaction(destination, GAME_URI)
        transaction.prepare(staging)
        assertTrue(destination.renameTo(backup))
        assertTrue(destination.mkdirs())
        destination.resolve("new.bin").writeText("new")
        assertFalse(transaction.committed())
        return RecoveryFixture(
            parent = parent,
            destination = destination,
            backup = backup,
            record = recordFor(destination),
            transaction = transaction,
        )
    }

    private fun resourceTemps(root: File): List<File> =
        root.walkTopDown().filter { isImportRecoveryTemporary(it.name) }.toList()

    private fun createSymbolicLink(link: File, target: File) {
        try {
            Files.createSymbolicLink(link.toPath(), target.toPath())
        } catch (failure: UnsupportedOperationException) {
            assumeNoException(failure)
        } catch (failure: SecurityException) {
            assumeNoException(failure)
        } catch (failure: IOException) {
            assumeNoException(failure)
        }
    }

    private data class CommittedTransaction(
        val transaction: SaveImportTransaction,
        val marker: File,
        val record: File,
    )

    private data class RecoveryFixture(
        val parent: File,
        val destination: File,
        val backup: File,
        val record: File,
        val transaction: SaveImportTransaction,
    ) {
        fun orphans(): List<File> = parent.listFiles().orEmpty()
            .filter { it.name.startsWith("saves.import_orphan_") }
    }

    companion object {
        private const val GAME_URI = "content://games/save-import"
    }
}
