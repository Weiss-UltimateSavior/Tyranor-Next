package com.tyranor.next.core.game.save

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class GameSaveManagerTransactionIntegrationTest {
    @get:Rule
    val temporary = TemporaryFolder(File(System.getProperty("java.io.tmpdir")).canonicalFile)

    @Test
    fun markerCommitIsVisibleToCallbackAndClearedAfterSuccess() {
        val parent = temporary.newFolder("callback-commit")
        val destination = oldSaves(parent, "existing save")
        val source = temporary.newFolder("callback-source").apply {
            resolve("slot").mkdirs()
            resolve("slot/data.sav").writeText("imported save")
        }
        val transaction = SaveImportTransaction(destination, GAME_URI)
        var callbackCalls = 0

        val copied = GameSaveManager.importSaveDirectory(
            destination = destination,
            exclude = { false },
            transaction = transaction,
            onDirectoryCommitted = {
                callbackCalls++
                assertEquals("imported save", destination.resolve("slot/data.sav").readText())
                assertFalse(destination.resolve("slot.sav").exists())
                assertTrue(destination.resolve(SaveImportTransaction.MARKER).isFile)
                assertTrue(transaction.committed())
                assertEquals(
                    "existing save",
                    transactionPath(destination, "backup").resolve("slot.sav").readText(),
                )
            },
        ) { source }

        assertEquals(1, copied)
        assertEquals(1, callbackCalls)
        assertEquals("imported save", destination.resolve("slot/data.sav").readText())
        assertFalse(destination.resolve(SaveImportTransaction.MARKER).exists())
        assertFalse(transaction.committed())
        assertTransactionSlotsClean(destination)
    }

    @Test
    fun allZeroImportOrphansOriginalSavesAndClearsMarker() {
        val parent = temporary.newFolder("all-zero")
        val destination = oldSaves(parent, "recoverable save")
        val source = temporary.newFolder("all-zero-source").apply {
            resolve("empty.sav").createNewFile()
        }
        val transaction = SaveImportTransaction(destination, GAME_URI)

        val copied = GameSaveManager.importSaveDirectory(
            destination = destination,
            exclude = { false },
            transaction = transaction,
        ) { source }

        assertEquals(1, copied)
        assertTrue(destination.resolve("empty.sav").isFile)
        assertEquals(0L, destination.resolve("empty.sav").length())
        assertFalse(destination.resolve(SaveImportTransaction.MARKER).exists())
        assertFalse(transaction.committed())

        val orphan = importOrphans(destination).single()
        assertEquals("recoverable save", orphan.resolve("slot.sav").readText())
        assertFalse(transactionPath(destination, "backup").exists())
        assertFalse(transactionPath(destination, "staging").exists())
        assertFalse(transactionRecord(destination).exists())
    }

    @Test
    fun zeroByteCallbackFailureRecoveryOrphansOriginalBackupAndClearsMarker() {
        val parent = temporary.newFolder("all-zero-callback-failure")
        val destination = oldSaves(parent, "original save")
        val source = temporary.newFolder("all-zero-callback-source").apply {
            resolve("empty.sav").createNewFile()
        }
        val transaction = SaveImportTransaction(destination, GAME_URI)
        val callbackFailure = IOException("zero import callback failed")

        val thrown = assertThrows(IOException::class.java) {
            GameSaveManager.importSaveDirectory(
                destination = destination,
                exclude = { false },
                transaction = transaction,
                onDirectoryCommitted = { throw callbackFailure },
            ) { source }
        }

        assertSame(callbackFailure, thrown)
        val backup = transactionPath(destination, "backup")
        assertTrue(destination.resolve("empty.sav").isFile)
        assertEquals(0L, destination.resolve("empty.sav").length())
        assertEquals("original save", backup.resolve("slot.sav").readText())
        val originalBytes = backup.resolve("slot.sav").readBytes()
        assertTrue(destination.resolve(SaveImportTransaction.MARKER).isFile)
        assertTrue(transaction.committed())

        var restoreCommittedCalls = 0
        var copyMissingResourcesCalls = 0
        transaction.recoverBackup(
            backup = backup,
            restoreCommitted = { restoreCommittedCalls++ },
            copyMissingResources = { copyMissingResourcesCalls++ },
        )

        assertEquals(1, restoreCommittedCalls)
        assertEquals(0, copyMissingResourcesCalls)
        assertFalse(backup.exists())
        assertEquals(0L, destination.resolve("empty.sav").length())
        val orphan = importOrphans(destination).single()
        assertArrayEquals(originalBytes, orphan.resolve("slot.sav").readBytes())
        assertFalse(destination.resolve(SaveImportTransaction.MARKER).exists())
        assertFalse(transaction.committed())
        assertFalse(transactionRecord(destination).exists())
        assertFalse(transactionPath(destination, "staging").exists())
    }

    @Test
    fun callbackFailureRetainsOriginalBackupAndRetryKeepsItRecoverable() {
        val parent = temporary.newFolder("callback-failure")
        val destination = oldSaves(parent, "original save")
        val firstSource = temporary.newFolder("callback-failure-source").apply {
            resolve("first.sav").writeText("first import")
        }
        val firstTransaction = SaveImportTransaction(destination, GAME_URI)
        val callbackFailure = IOException("commit callback failed")

        val thrown = assertThrows(IOException::class.java) {
            GameSaveManager.importSaveDirectory(
                destination = destination,
                exclude = { false },
                transaction = firstTransaction,
                onDirectoryCommitted = { throw callbackFailure },
            ) { firstSource }
        }

        assertSame(callbackFailure, thrown)
        assertEquals("first import", destination.resolve("first.sav").readText())
        assertTrue(firstTransaction.committed())
        assertEquals(
            "original save",
            transactionPath(destination, "backup").resolve("slot.sav").readText(),
        )
        assertTrue(transactionRecord(destination).isFile)

        val retrySource = temporary.newFolder("callback-retry-source").apply {
            resolve("retry.sav").writeText("retry import")
        }
        val retry = GameSaveManager.importSaveDirectory(
            destination = destination,
            exclude = { false },
            transaction = SaveImportTransaction(destination, GAME_URI),
        ) { retrySource }

        assertEquals(1, retry)
        assertEquals("retry import", destination.resolve("retry.sav").readText())
        val retained = File(destination.parentFile, "${destination.name}.import_backup_retained")
        assertEquals("original save", retained.resolve("slot.sav").readText())
        assertFalse(destination.resolve(SaveImportTransaction.MARKER).exists())
        assertFalse(transactionPath(destination, "backup").exists())
        assertFalse(transactionPath(destination, "staging").exists())
        assertFalse(transactionRecord(destination).exists())
    }

    @Test
    fun resourceRestoreFailurePreservesOriginalBackup() {
        val parent = temporary.newFolder("resource-failure")
        val destination = oldSaves(parent, "original save").apply {
            resolve("system.pfs").writeText("engine resource")
        }
        val source = temporary.newFolder("resource-source").apply {
            resolve("new.sav").writeText("new save")
        }
        val transaction = SaveImportTransaction(destination, GAME_URI)
        val restoreFailure = IOException("resource restore failed")

        val thrown = assertThrows(IOException::class.java) {
            GameSaveManager.importSaveDirectory(
                destination = destination,
                exclude = { it.endsWith(".pfs") },
                restoreExcluded = { _, _ -> throw restoreFailure },
                transaction = transaction,
            ) { source }
        }

        assertSame(restoreFailure, thrown)
        val backup = transactionPath(destination, "backup")
        assertEquals("original save", backup.resolve("slot.sav").readText())
        assertEquals("engine resource", backup.resolve("system.pfs").readText())
        assertEquals("new save", destination.resolve("new.sav").readText())
        assertTrue(destination.resolve(SaveImportTransaction.MARKER).isFile)
        assertTrue(transaction.committed())
        assertFalse(transactionPath(destination, "staging").exists())
    }

    private fun oldSaves(parent: File, contents: String): File =
        File(parent, "saves").apply {
            mkdirs()
            resolve("slot.sav").writeText(contents)
        }

    private fun transactionPath(destination: File, kind: String): File =
        File(destination.parentFile, "${destination.name}.import_$kind")

    private fun transactionRecord(destination: File): File =
        File(destination.parentFile, "${destination.name}.import_transaction")

    private fun importOrphans(destination: File): List<File> =
        destination.parentFile?.listFiles().orEmpty()
            .filter { it.name.startsWith("${destination.name}.import_orphan_") }

    private fun assertTransactionSlotsClean(destination: File) {
        assertFalse(transactionPath(destination, "backup").exists())
        assertFalse(transactionPath(destination, "staging").exists())
        assertFalse(transactionRecord(destination).exists())
    }

    companion object {
        private const val GAME_URI = "content://games/save-import"
    }
}
