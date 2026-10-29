package com.tyranor.next.core.game.save

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import java.nio.file.LinkOption.NOFOLLOW_LINKS

class GameSaveRecoverySafetyTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun invalidPreservePreviousEvidenceFailsCommitAndRecoveryKeepsOriginalBytes() {
        val tamperCases: List<Pair<String, (RecoveryFixture) -> Unit>> = listOf(
            "numeric-zero" to { fixture -> setPreservePrevious(fixture, 0) },
            "string-false" to { fixture -> setPreservePrevious(fixture, "false") },
            "json-null" to { fixture -> setPreservePrevious(fixture, JSONObject.NULL) },
            "record-missing" to { fixture ->
                rewriteJson(fixture.record) { it.remove("preservePrevious") }
            },
            "marker-missing" to { fixture ->
                rewriteJson(fixture.marker) { it.remove("preservePrevious") }
            },
        )

        tamperCases.forEachIndexed { index, (label, tamper) ->
            val fixture = committedFixture("tamper-$index-$label")
            assertTrue(fixture.transaction.committed())
            tamper(fixture)
            assertFalse(label, fixture.transaction.committed())

            var restoreCommittedCalls = 0
            var copyMissingResourcesCalls = 0
            fixture.transaction.recoverBackup(
                backup = fixture.backup,
                restoreCommitted = { restoreCommittedCalls++ },
                copyMissingResources = { copyMissingResourcesCalls++ },
            )

            assertEquals(label, 0, restoreCommittedCalls)
            assertEquals(label, 1, copyMissingResourcesCalls)
            assertFalse(label, fixture.backup.exists())
            val orphan = fixture.orphans().single()
            assertArrayEquals(label, fixture.originalBytes, orphan.resolve("original.bin").readBytes())
            assertEquals(label, "new save", fixture.destination.resolve("new.bin").readText())
            assertFalse(label, fixture.marker.exists())
            assertFalse(label, fixture.record.exists())
            assertFalse(label, fixture.transaction.committed())
        }
    }

    @Test
    fun legacyTokenAndUriWithoutPreservePreviousRemainCommittedEvidence() {
        val fixture = committedFixture("legacy")
        rewriteJson(fixture.record) { it.remove("preservePrevious") }
        rewriteJson(fixture.marker) { it.remove("preservePrevious") }

        assertFalse(JSONObject(fixture.record.readText()).has("preservePrevious"))
        assertFalse(JSONObject(fixture.marker.readText()).has("preservePrevious"))
        assertTrue(fixture.transaction.committed())
    }

    @Test
    fun deleteRejectsSymlinkSidecarBeforeRestoreCanMoveOutsideAsset() {
        val root = temporary.newFolder("delete-sidecar")
        val destination = File(root, "saves").apply {
            mkdirs()
            resolve("slot.sav").writeText("live save")
        }
        val outside = temporary.newFolder("delete-outside").apply {
            resolve("system.pfs").writeText("outside asset")
        }
        val sidecar = File(destination.parentFile, "${destination.name}.import_backup")
        createSymbolicLink(sidecar, outside)
        var restoreCalls = 0
        val exclude: (String) -> Boolean = { it.endsWith(".pfs") }

        val failure = assertThrows(GameSaveException::class.java) {
            GameSaveManager.deleteSaveDirectory(destination, exclude) { backup, target ->
                restoreCalls++
                GameSaveManager.restoreExcludedContents(backup, target, exclude, rejectExisting = true)
            }
        }

        assertEquals(SaveErrorCode.RESOURCE_RESTORE_CONFLICT, failure.code)
        assertEquals(0, restoreCalls)
        assertEquals("outside asset", outside.resolve("system.pfs").readText())
        assertEquals("live save", destination.resolve("slot.sav").readText())
        assertTrue(Files.isSymbolicLink(sidecar.toPath()))
    }

    @Test
    fun restoreExcludedContentsDirectlyRejectsSymlinkBackup() {
        val root = temporary.newFolder("restore-direct")
        val runtime = File(root, "runtime").apply { mkdirs() }
        val destination = File(runtime, "saves").apply { mkdirs() }
        val outside = File(root, "outside").apply {
            mkdirs()
            resolve("system.pfs").writeText("outside asset")
        }
        val backup = File(runtime, "saves.import_backup")
        createSymbolicLink(backup, outside)

        val failure = assertThrows(GameSaveException::class.java) {
            GameSaveManager.restoreExcludedContents(
                backup = backup,
                destination = destination,
                exclude = { it.endsWith(".pfs") },
                rejectExisting = true,
            )
        }

        assertEquals(SaveErrorCode.SAVE_DIR_UNAVAILABLE, failure.code)
        assertEquals("outside asset", outside.resolve("system.pfs").readText())
        assertFalse(destination.resolve("system.pfs").exists())
        assertTrue(Files.isSymbolicLink(backup.toPath()))
    }

    @Test
    fun parkRotationTreatsDanglingSymlinkAsOccupiedWithoutFollowingIt() {
        val root = temporary.newFolder("park-rotation")
        val destination = File(root, "saves").apply {
            mkdirs()
            resolve("current.sav").writeText("current save")
        }
        val backup = File(root, "saves.import_backup").apply {
            mkdirs()
            resolve("old.sav").writeText("old backup")
        }
        val firstRetained = File(root, "saves.import_backup_retained")
        val missingOutside = File(root, "outside-missing")
        createSymbolicLink(firstRetained, missingOutside)
        val source = temporary.newFolder("park-source").apply {
            resolve("new.sav").writeText("new save")
        }

        val copied = GameSaveManager.importSaveDirectory(destination, { false }) { source }

        assertEquals(1, copied)
        assertEquals("new save", destination.resolve("new.sav").readText())
        assertTrue(Files.exists(firstRetained.toPath(), NOFOLLOW_LINKS))
        assertTrue(Files.isSymbolicLink(firstRetained.toPath()))
        assertFalse(missingOutside.exists())
        val secondRetained = File(root, "saves.import_backup_retained1")
        assertEquals("old backup", secondRetained.resolve("old.sav").readText())
        assertFalse(backup.exists())
    }

    private fun committedFixture(name: String): RecoveryFixture {
        val parent = temporary.newFolder(name)
        val originalBytes = byteArrayOf(0, 1, 2, 3, -1)
        val destination = File(parent, "saves").apply {
            mkdirs()
            resolve("original.bin").writeBytes(originalBytes)
        }
        val staging = File(parent, "staging").apply {
            mkdirs()
            resolve("new.bin").writeText("new save")
        }
        val transaction = SaveImportTransaction(destination, GAME_URI)
        transaction.prepare(staging, preservePrevious = true)
        val backup = File(parent, "${destination.name}.import_backup")
        assertTrue(destination.renameTo(backup))
        assertTrue(staging.renameTo(destination))
        assertTrue(transaction.committed())
        return RecoveryFixture(
            parent = parent,
            destination = destination,
            backup = backup,
            marker = File(destination, SaveImportTransaction.MARKER),
            record = File(parent, "${destination.name}.import_transaction"),
            originalBytes = originalBytes,
            transaction = transaction,
        )
    }

    private fun setPreservePrevious(fixture: RecoveryFixture, value: Any) {
        rewriteJson(fixture.record) { it.put("preservePrevious", value) }
        rewriteJson(fixture.marker) { it.put("preservePrevious", value) }
    }

    private fun rewriteJson(file: File, edit: (JSONObject) -> Unit) {
        val json = JSONObject(file.readText())
        edit(json)
        file.writeText(json.toString())
    }

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
        assumeTrue("symbolic link was not created", Files.isSymbolicLink(link.toPath()))
    }

    private data class RecoveryFixture(
        val parent: File,
        val destination: File,
        val backup: File,
        val marker: File,
        val record: File,
        val originalBytes: ByteArray,
        val transaction: SaveImportTransaction,
    ) {
        fun orphans(): List<File> = parent.listFiles().orEmpty()
            .filter { it.name.startsWith(destination.name + ".import_orphan_") }
    }

    companion object {
        private const val GAME_URI = "content://games/save-recovery-safety"
    }
}
