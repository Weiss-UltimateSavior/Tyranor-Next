package com.tyranor.next.core.game.save

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GameSaveManagerMirrorFilterTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun mirrorForcedListAndExportExcludeNestedEmptyPlaceholders() {
        val root = saveTree()
        assertListAndExport(root, mirrorForced = true, expected = setOf("slot/data.sav"))
    }

    @Test
    fun ordinaryListAndExportKeepLegitimateEmptyFiles() {
        val root = saveTree()
        assertListAndExport(root, mirrorForced = false, expected = setOf("empty.marker", "slot/empty.sav", "slot/data.sav"))
    }

    @Test
    fun excludedDirectoriesRemainExcludedInBothPaths() {
        val root = saveTree()
        root.resolve("ignored").mkdirs()
        root.resolve("ignored/real.sav").writeText("hidden")
        val exclude: (String) -> Boolean = { it == "ignored" }
        assertListAndExport(root, mirrorForced = false, expected = setOf("empty.marker", "slot/empty.sav", "slot/data.sav"), exclude = exclude)
    }

    @Test
    fun mirrorImportRetainsEveryZipFileThenListFiltersPlaceholders() {
        // R5-#D: the import source is a user ZIP — the 0-byte filter applies to list and
        // export of the mirror savedata only, never to what the user chose to import.
        val source = saveTree()
        val staging = temporaryFolder.newFolder()
        assertEquals(3, GameSaveManager.stageImportContents(source, staging, { false }))
        assertTrue(staging.resolve("empty.marker").isFile)
        assertTrue(staging.resolve("slot/empty.sav").isFile)
        assertTrue(staging.resolve("slot/data.sav").isFile)
        // After the exchange, the live directory holds everything; list/export filter.
        assertListAndExport(staging, mirrorForced = true, expected = setOf("slot/data.sav"))
    }

    @Test
    fun ordinaryImportRetainsAndCountsEmptyFiles() {
        val source = saveTree()
        val staging = temporaryFolder.newFolder()
        val copied = GameSaveManager.stageImportContents(source, staging, { false })
        assertEquals(3, copied)
        assertListAndExport(staging, mirrorForced = false, expected = setOf("empty.marker", "slot/empty.sav", "slot/data.sav"))
    }

    @Test
    fun ordinaryEmptyOnlyImportRemainsValid() {
        val source = temporaryFolder.newFolder()
        source.resolve("empty.marker").createNewFile()
        val staging = temporaryFolder.newFolder()
        assertEquals(1, GameSaveManager.stageImportContents(source, staging, { false }))
        assertTrue(staging.resolve("empty.marker").isFile)
        assertEquals(0L, staging.resolve("empty.marker").length())
    }

    @Test
    fun emptyOnlyMirrorImportIsPreservedWithoutRejection() {
        // R5-#D: an all-empty ZIP is a legitimate import (the user chose it); the
        // exchange happens and the empty files are preserved on disk.
        val source = temporaryFolder.newFolder()
        source.resolve("empty.sav").createNewFile()
        val live = liveSaves()
        assertEquals(1, GameSaveManager.importSaveDirectory(live, { false }, rename = { from, to ->
            from.renameTo(to)
        }) { source })
        assertTrue(live.resolve("empty.sav").isFile)
        assertEquals(0L, live.resolve("empty.sav").length())
        assertFalse(live.resolve("slot.sav").exists())
        // List/export still filter the empty placeholder from the savedata listing.
        val listed = mutableListOf<File>()
        GameSaveManager.collectFiles(live, listed, { false }, mirrorForced = true)
        assertTrue(listed.isEmpty())
        // An all-empty import replaces a non-empty live directory: the previous saves
        // stay recoverable in the backup until the next import resolves it (R9-#8).
        assertEquals("existing save", transactionPath(live, "backup").resolve("slot.sav").readText())
        assertFalse(transactionPath(live, "staging").exists())
    }

    @Test
    fun mirrorImportStagesEverythingAndExchangesTheLiveDirectory() {
        val source = saveTree()
        val live = liveSaves()
        var stagedBeforeExchange = false
        val copied = GameSaveManager.importSaveDirectory(live, { false }, rename = { from, to ->
            if (from == live) {
                val staging = transactionPath(live, "staging")
                assertEquals("existing save", live.resolve("slot.sav").readText())
                assertEquals("save contents", staging.resolve("slot/data.sav").readText())
                assertTrue(staging.resolve("empty.marker").isFile)
                stagedBeforeExchange = true
            }
            from.renameTo(to)
        }) { source }
        assertTrue(stagedBeforeExchange)
        assertEquals(3, copied)
        assertFalse(live.resolve("slot.sav").exists())
        assertTrue(live.resolve("empty.marker").isFile)
        // The live list (mirrorForced) filters empties; the files stay on disk.
        val listed = mutableListOf<File>()
        GameSaveManager.collectFiles(live, listed, { false }, mirrorForced = true)
        assertEquals(setOf("slot/data.sav"), listed.map { it.relativeTo(live).invariantSeparatorsPath }.toSet())
        assertTransactionClean(live)
    }

    @Test
    fun failedStagingExchangeRollsBackTheLiveSaves() {
        val source = saveTree()
        val live = liveSaves()
        val staging = transactionPath(live, "staging")
        expectImportError(SaveErrorCode.SAVE_DIR_UNAVAILABLE) {
            GameSaveManager.importSaveDirectory(live, { false }, rename = { from, to ->
                if (from == staging && to == live) false else from.renameTo(to)
            }) { source }
        }
        assertEquals("existing save", live.resolve("slot.sav").readText())
        assertFalse(live.resolve("slot/data.sav").exists())
        assertTransactionClean(live)
    }

    @Test
    fun failedRollbackRetainsBackupAndNextImportRecoversItBeforeReadingSource() {
        val source = saveTree()
        val live = liveSaves()
        val staging = transactionPath(live, "staging")
        val backup = transactionPath(live, "backup")
        expectImportError(SaveErrorCode.SAVE_DIR_UNAVAILABLE) {
            GameSaveManager.importSaveDirectory(live, { false }, rename = { from, to ->
                if ((from == staging || from == backup) && to == live) false else from.renameTo(to)
            }) { source }
        }
        assertFalse(live.exists())
        assertEquals("existing save", backup.resolve("slot.sav").readText())
        try {
            GameSaveManager.importSaveDirectory(live, { false }) {
                assertEquals("existing save", live.resolve("slot.sav").readText())
                assertFalse(backup.exists())
                throw IOException("Source unavailable")
            }
            fail("Source failure must be propagated")
        } catch (error: IOException) {
            assertEquals("Source unavailable", error.message)
        }
        assertEquals("existing save", live.resolve("slot.sav").readText())
        assertTransactionClean(live)
    }

    @Test
    fun resourceRestoreFailureRetainsTheCompleteBackupAfterExchange() {
        val source = saveTree()
        val live = liveSaves()
        live.resolve("system.pfs").writeText("engine resource")
        try {
            GameSaveManager.importSaveDirectory(live, { it.endsWith(".pfs") },
                restoreExcluded = { _, _ -> throw IOException("Resource restore failed") }) { source }
            fail("Resource restoration failure must be propagated")
        } catch (error: IOException) {
            assertEquals("Resource restore failed", error.message)
        }
        val backup = transactionPath(live, "backup")
        assertEquals("existing save", backup.resolve("slot.sav").readText())
        assertEquals("engine resource", backup.resolve("system.pfs").readText())
        assertEquals("save contents", live.resolve("slot/data.sav").readText())
        assertFalse(transactionPath(live, "staging").exists())
    }

    @Test
    fun ordinaryEmptyOnlyImportExchangesAndKeepsTheRecoveryBackup() {
        val source = temporaryFolder.newFolder()
        source.resolve("empty.marker").createNewFile()
        val live = liveSaves()
        assertEquals(1, GameSaveManager.importSaveDirectory(live, { false }) { source })
        assertTrue(live.resolve("empty.marker").isFile)
        assertEquals(0L, live.resolve("empty.marker").length())
        assertFalse(live.resolve("slot.sav").exists())
        assertEquals("existing save", transactionPath(live, "backup").resolve("slot.sav").readText())
        assertFalse(transactionPath(live, "staging").exists())
    }

    @Test
    fun recreatedEmptyDestinationRecoversBackupBeforeAnEmptyImport() {
        val live = liveSaves()
        val backup = transactionPath(live, "backup")
        assertTrue(live.renameTo(backup))
        assertTrue(live.mkdir())
        val emptyImport = temporaryFolder.newFolder()
        emptyImport.resolve("empty.ksd").createNewFile()
        // The empty import now succeeds (R5-#D); backup recovery runs first. After the
        // committed exchange the recovered saves land in the new backup slot (the empty
        // import must not destroy them); the empty file is preserved on disk.
        assertEquals(1, GameSaveManager.importSaveDirectory(live, { false }) {
            assertFalse(backup.exists())
            emptyImport
        })
        assertTrue(live.resolve("empty.ksd").isFile)
        assertEquals(0L, live.resolve("empty.ksd").length())
        assertEquals("existing save", transactionPath(live, "backup").resolve("slot.sav").readText())
        assertFalse(transactionPath(live, "staging").exists())
    }

    @Test
    fun interruptedExchangeWithGameplaySavesParksTheBackupInsteadOfDeletingIt() {
        // R9-#3: crash inside the exchange window, then the user plays and saves into a
        // recreated directory, then imports again — the leftover backup is the only copy
        // of the previous saves and must be parked, never deleted.
        val live = liveSaves()
        val backup = transactionPath(live, "backup")
        assertTrue(live.renameTo(backup))
        assertTrue(live.mkdir())
        live.resolve("slot.sav").writeText("post-crash save")
        val source = saveTree()
        assertEquals(3, GameSaveManager.importSaveDirectory(live, { false }) { source })
        assertEquals("save contents", live.resolve("slot/data.sav").readText())
        val retained = File(live.parentFile, live.name + ".import_backup_retained")
        assertEquals("existing save", retained.resolve("slot.sav").readText())
        assertFalse(transactionPath(live, "backup").exists())
        assertFalse(transactionPath(live, "staging").exists())
    }

    @Test
    fun interruptedExchangeWithResourceRestoreAlsoParksTheBackup() {
        // R10 #1: engines whose imports restore excluded resources (ARTEMIS/AVG32/UK2/
        // MV-MZ) must reach the same park policy — the pre-crash backup is the only
        // copy of the previous saves even after restoreExcluded ran. The resource is
        // seeded in the pre-crash saves so restore must run BEFORE the park decision,
        // then again after the exchange to move it out of the fresh backup.
        val live = liveSaves()
        live.resolve("system.pfs").writeText("engine resource")
        val backup = transactionPath(live, "backup")
        assertTrue(live.renameTo(backup))
        assertTrue(live.mkdir())
        live.resolve("slot.sav").writeText("post-crash save")
        val source = saveTree()
        val restored = mutableListOf<String>()
        assertEquals(3, GameSaveManager.importSaveDirectory(live, { false },
            restoreExcluded = { backupDir, destination ->
                backupDir.listFiles().orEmpty().filter { it.name == "system.pfs" }.forEach { resource ->
                    check(resource.renameTo(File(destination, resource.name)))
                    restored += resource.name
                }
            }) { source })
        assertEquals("save contents", live.resolve("slot/data.sav").readText())
        // The resource rode through the backup and was restored into the live directory.
        assertEquals(listOf("system.pfs", "system.pfs"), restored)
        assertEquals("engine resource", live.resolve("system.pfs").readText())
        val retained = File(live.parentFile, live.name + ".import_backup_retained")
        assertEquals("existing save", retained.resolve("slot.sav").readText())
        assertFalse(transactionPath(live, "backup").exists())
        assertFalse(transactionPath(live, "staging").exists())
    }

    @Test
    fun allEmptyImportWithResourceRestoreKeepsTheRecoveryBackup() {
        // R10 #1 + audit of 2fe2f38 (N5): an all-empty ZIP for a restoreExcluded engine
        // must keep the previous saves in the backup even though the real resource
        // restore repopulates the destination with non-empty files — the retention
        // decision reads the STAGED content (pre-exchange), not the destination.
        // With the old destination-based judgment this test fails (backup deleted).
        val source = temporaryFolder.newFolder()
        source.resolve("empty.sav").createNewFile()
        val live = liveSaves()
        live.resolve("system.pfs").writeText("engine resource")
        assertEquals(1, GameSaveManager.importSaveDirectory(live, { false },
            restoreExcluded = { backupDir, destination ->
                backupDir.listFiles().orEmpty().filter { it.name == "system.pfs" }.forEach { resource ->
                    check(resource.renameTo(File(destination, resource.name)))
                }
            }) { source })
        assertTrue(live.resolve("empty.sav").isFile)
        assertEquals(0L, live.resolve("empty.sav").length())
        assertFalse(live.resolve("slot.sav").exists())
        // The resource rode through the backup and was restored into the live directory.
        assertEquals("engine resource", live.resolve("system.pfs").readText())
        assertEquals("existing save", transactionPath(live, "backup").resolve("slot.sav").readText())
        assertFalse(transactionPath(live, "staging").exists())
    }

    @Test
    fun recreatedEmptyDestinationRecoversBackupBeforeInvalidZipParsing() {
        val live = liveSaves()
        val backup = transactionPath(live, "backup")
        assertTrue(live.renameTo(backup))
        assertTrue(live.mkdir())
        val extracted = temporaryFolder.newFolder()
        expectImportError(SaveErrorCode.NO_FILES_IN_ZIP) {
            GameSaveManager.importSaveDirectory(live, { false }) {
                assertEquals("existing save", live.resolve("slot.sav").readText())
                val count = GameSaveManager.extractZip(ByteArrayInputStream("not a zip".toByteArray()), extracted)
                if (count == 0) throw GameSaveException(SaveErrorCode.NO_FILES_IN_ZIP)
                extracted
            }
        }
        assertEquals("existing save", live.resolve("slot.sav").readText())
        assertTransactionClean(live)
    }

    @Test
    fun recreatedEmptyDestinationKeepsBackupWhenRecoveryRenameFails() {
        val live = liveSaves()
        val backup = transactionPath(live, "backup")
        assertTrue(live.renameTo(backup))
        assertTrue(live.mkdir())
        expectImportError(SaveErrorCode.SAVE_DIR_UNAVAILABLE) {
            GameSaveManager.importSaveDirectory(live, { false },
                rename = { from, to -> if (from == backup && to == live) false else from.renameTo(to) }) {
                error("Do not inspect the new import before backup recovery succeeds")
            }
        }
        assertEquals("existing save", backup.resolve("slot.sav").readText())
        assertFalse(transactionPath(live, "staging").exists())
    }

    @Test
    fun recreatedEmptyDestinationCanCompleteANewImportAfterRecoveringBackup() {
        val live = liveSaves()
        val backup = transactionPath(live, "backup")
        assertTrue(live.renameTo(backup))
        assertTrue(live.mkdir())
        val source = saveTree()
        assertEquals(3, GameSaveManager.importSaveDirectory(live, { false }) {
            assertEquals("existing save", live.resolve("slot.sav").readText())
            source
        })
        assertEquals("save contents", live.resolve("slot/data.sav").readText())
        assertTransactionClean(live)
    }

    @Test
    fun failedResourceImportThenDeletePreservesTheSoleGameResource() {
        val live = liveSaves()
        live.resolve("foo").mkdirs()
        live.resolve("foo/data.pfs").writeText("sole game resource")
        val source = temporaryFolder.newFolder().apply {
            resolve("foo").writeText("conflicting imported file")
            resolve("slot.sav").writeText("imported save")
        }
        val exclude: (String) -> Boolean = { it.endsWith(".pfs") }
        val restore: (File, File) -> Unit = { backup, destination ->
            GameSaveManager.restoreExcludedContents(backup, destination, exclude, rejectExisting = true)
        }
        expectImportError(SaveErrorCode.SAVE_DIR_UNAVAILABLE) {
            GameSaveManager.importSaveDirectory(live, exclude, restoreExcluded = restore) { source }
        }
        val backup = transactionPath(live, "backup")
        assertFalse(live.resolve("foo/data.pfs").exists())
        assertEquals("sole game resource", backup.resolve("foo/data.pfs").readText())
        expectImportError(SaveErrorCode.SAVE_DIR_UNAVAILABLE) {
            GameSaveManager.deleteSaveDirectory(live, exclude, restore)
        }
        assertEquals("sole game resource", backup.resolve("foo/data.pfs").readText())
        assertEquals("imported save", live.resolve("slot.sav").readText())
        // Once the conflicting imported file is removed, recover nested resources
        // before deleting saves; recursive cleanup must not delete the restored asset.
        assertTrue(live.resolve("foo").delete())
        assertEquals(1, GameSaveManager.deleteSaveDirectory(live, exclude, restore))
        assertEquals("sole game resource", live.resolve("foo/data.pfs").readText())
        assertFalse(live.resolve("slot.sav").exists())
        assertFalse(backup.exists())
    }

    @Test
    fun deleteRestoresResourcesFromEveryTransactionSlotForGameRootSavePolicies() {
        val policies = listOf<Pair<String, (String) -> Boolean>>(
            "slot.sav" to { name -> name.endsWith(".pfs") },
            "save.ini" to { name -> name != "save.ini" },
            "flag00.dat" to { name -> !Regex("^flag\\d{2}\\.dat$").matches(name) },
        )
        for ((saveName, exclude) in policies) {
            val live = temporaryFolder.newFolder().apply { resolve(saveName).writeText("live save") }
            val siblings = listOf("staging", "backup", "backup_retained", "backup_retained1", "backup_retained2")
                .mapIndexed { index, suffix ->
                    File(live.parentFile, live.name + ".import_" + suffix).apply {
                        mkdirs()
                        resolve(saveName).writeText("recovery save")
                        resolve("resource-$index.pfs").writeText("asset-$index")
                    }
                }
            GameSaveManager.deleteSaveDirectory(live, exclude) { backup, destination ->
                GameSaveManager.restoreExcludedContents(backup, destination, exclude, rejectExisting = true)
            }
            for (index in siblings.indices) {
                assertEquals("asset-$index", live.resolve("resource-$index.pfs").readText())
                assertFalse(siblings[index].exists())
            }
            assertFalse(live.resolve(saveName).exists())
        }
    }

    @Test
    fun deleteDoesNotOverwriteExistingResourcesWithRecoveryCopies() {
        val live = liveSaves()
        live.resolve("data.pfs").writeText("current resource")
        val backup = transactionPath(live, "backup").apply {
            mkdirs()
            resolve("data.pfs").writeText("unique recovery version")
        }
        val exclude: (String) -> Boolean = { it.endsWith(".pfs") }
        expectImportError(SaveErrorCode.SAVE_DIR_UNAVAILABLE) {
            GameSaveManager.deleteSaveDirectory(live, exclude) { from, to ->
                GameSaveManager.restoreExcludedContents(from, to, exclude, rejectExisting = true)
            }
        }
        assertEquals("current resource", live.resolve("data.pfs").readText())
        assertEquals("unique recovery version", backup.resolve("data.pfs").readText())
        assertEquals("existing save", live.resolve("slot.sav").readText())
    }

    private fun liveSaves(): File = temporaryFolder.newFolder().apply {
        resolve("slot.sav").writeText("existing save")
    }

    private fun transactionPath(live: File, kind: String): File =
        File(live.parentFile, live.name + ".import_" + kind)

    private fun assertTransactionClean(live: File) {
        assertFalse(transactionPath(live, "staging").exists())
        assertFalse(transactionPath(live, "backup").exists())
    }

    private fun expectImportError(code: SaveErrorCode, action: () -> Unit) {
        try {
            action()
            fail("Expected import failure")
        } catch (error: GameSaveException) {
            assertEquals(code, error.code)
        }
    }

    private fun saveTree(): File = temporaryFolder.newFolder().apply {
        resolve("empty.marker").createNewFile()
        resolve("slot").mkdirs()
        resolve("slot/empty.sav").createNewFile()
        resolve("slot/data.sav").writeText("save contents")
    }

    private fun assertListAndExport(
        root: File,
        mirrorForced: Boolean,
        expected: Set<String>,
        exclude: (String) -> Boolean = { false },
    ) {
        val listed = mutableListOf<File>()
        GameSaveManager.collectFiles(root, listed, exclude, mirrorForced)
        assertEquals(expected, listed.map { it.relativeTo(root).invariantSeparatorsPath }.toSet())
        val output = ByteArrayOutputStream()
        val count = ZipOutputStream(output).use { zip ->
            GameSaveManager.writeZipContents(root, root, zip, mutableSetOf(), exclude, mirrorForced = mirrorForced)
        }
        assertEquals(expected.size, count)
        val exported = mutableMapOf<String, ByteArray>()
        ZipInputStream(output.toByteArray().inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                exported[entry.name] = zip.readBytes()
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        assertEquals(expected, exported.keys)
        assertEquals("save contents", exported.getValue("slot/data.sav").toString(Charsets.UTF_8))
        if (!mirrorForced) assertTrue(exported.getValue("slot/empty.sav").isEmpty())
    }
}
