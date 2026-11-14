package com.tyranor.next.core.game.save

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.game.storage.GameLibraryFacade
import com.tyranor.next.core.game.storage.GameLibraryRepository
import com.tyranor.next.core.game.storage.SystemNnnCopyStore
import com.tyranor.next.core.game.storage.SystemNnnGameDirectory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class SystemNnnSaveValidationDeviceTest {
    @Test
    fun zipAndListingCannotCrossLegacyCopyRetirement() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val external = checkNotNull(app.getExternalFilesDir(null)).canonicalFile
        val workspace = Files.createTempDirectory(external.toPath(), "systemnnn-authority-").toFile()
        val original = File(workspace, "game").apply { check(mkdir()) }
        val game = ScanGame("SystemNNN directory handoff", Uri.fromFile(original).toString(), EngineType.SYSTEMNNN, "__game_dir__")
        val sourceId = checkNotNull(SystemNnnGameDirectory.sourceIdentity(game.uri))
        val copyBase = File(external, "systemnnn-imports")
        val copyEntry = File(copyBase, SystemNnnCopyStore.key(sourceId))
        val copy = File(copyEntry, "game")
        val manager = GameSaveManager(app)
        fun progress(read: Int) = ByteArrayOutputStream().apply {
            DataOutputStream(this).use { it.writeInt(0x4e4e5231); it.writeInt(1); it.writeInt(read) }
        }.toByteArray()
        val originalBytes = progress(11)
        val copyBytes = progress(22)
        val originalSave = File(original, "sav/emberfall.read")
        val copySave = File(copy, "sav/emberfall.read")
        val archive = File(workspace, "saves.zip")
        fun exported() = ZipFile(archive).use { zip ->
            assertEquals(1, zip.size())
            zip.getInputStream(checkNotNull(zip.getEntry("emberfall.read"))).use { it.readBytes() }
        }
        try {
            check(originalSave.parentFile!!.mkdir())
            originalSave.writeBytes(originalBytes)
            check(copySave.parentFile!!.mkdirs())
            copySave.writeBytes(copyBytes)
            File(copyEntry, "source.id").writeText(sourceId)
            File(copy, "resource.bin").writeBytes(byteArrayOf(1))
            runBlocking { GameLibraryFacade.registerGames(app) { it + game } }
            assertEquals(copy, SystemNnnGameDirectory.accessible(app, game.uri))
            assertEquals(1, runBlocking { manager.exportToZip(game, Uri.fromFile(archive)) })
            assertArrayEquals(copyBytes, exported())

            SystemNnnCopyStore.withImportLock(app, sourceId) {
                val export = assertThrows(GameSaveException::class.java) {
                    runBlocking { manager.exportToZip(game, Uri.fromFile(archive)) }
                }
                assertEquals(SaveErrorCode.ENGINE_RUNNING, export.code)
                val listing = assertThrows(GameSaveException::class.java) {
                    runBlocking { manager.listSaveFiles(game, strict = true) }
                }
                assertEquals(SaveErrorCode.ENGINE_RUNNING, listing.code)
                assertArrayEquals(copyBytes, exported())
            }

            assertTrue(GameLibraryFacade.removeGame(app, game.uri))
            val cleanup = runBlocking { manager.drainSystemNnnCleanupQueue(game.uri) }
            assertEquals(0, cleanup.failed)
            assertNull(SystemNnnGameDirectory.committed(app, game.uri))
            assertArrayEquals(copyBytes, copySave.readBytes())
            assertArrayEquals(originalBytes, originalSave.readBytes())
            runBlocking { GameLibraryFacade.registerGames(app) { it + game } }
            assertEquals(original, SystemNnnGameDirectory.accessible(app, game.uri))
            assertEquals(originalSave.parentFile, runBlocking { manager.inspectSaves(game) }.location.directory)
            assertEquals(listOf(originalSave), runBlocking { manager.listSaveFiles(game, strict = true) })
            assertEquals(1, runBlocking { manager.exportToZip(game, Uri.fromFile(archive)) })
            assertArrayEquals(originalBytes, exported())
            val stale = assertThrows(GameSaveException::class.java) {
                runBlocking { manager.exportToZip(game, Uri.fromFile(archive), expectedDirectory = copySave.parentFile) }
            }
            assertEquals(SaveErrorCode.SAVE_DIR_UNAVAILABLE, stale.code)
            assertArrayEquals(originalBytes, exported())
        } finally {
            GameLibraryFacade.removeGame(app, game.uri)
            runBlocking { manager.drainSystemNnnCleanupQueue(game.uri) }
            GameLibraryRepository.readBlocking(app, GameLibraryRepository::loadGamesAfterWrites)
            SavePathSafety.deleteTreeNoFollow(copyEntry)
            Files.deleteIfExists(File(copyBase, SystemNnnCopyStore.key(sourceId) + ".lock").toPath())
            SavePathSafety.deleteTreeNoFollow(workspace)
        }
    }

    @Test
    fun zipValidationLoadsTheRuntimeOutsideTheLauncher() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val workspace = Files.createTempDirectory(app.cacheDir.toPath(), "systemnnn-validation-").toFile()
        try {
            val gameDir = File(workspace, "game").apply { check(mkdir()) }
            // Admission requires the startup marker; this test validates progress, not script execution.
            File(gameDir, "spt/sys.spt").apply {
                check(parentFile!!.mkdir())
                writeBytes(byteArrayOf())
            }
            val game = ScanGame(
                title = "SystemNNN ZIP validation",
                uri = Uri.fromFile(gameDir).toString(),
                engine = EngineType.SYSTEMNNN,
                launchTarget = "__game_dir__",
            )
            val manager = GameSaveManager(app)
            val bytes = ByteArrayOutputStream().apply {
                DataOutputStream(this).use {
                    it.writeInt(0x4e4e5231) // NNR1
                    it.writeInt(0)
                }
            }.toByteArray()
            val archive = File(workspace, "saves.zip")
            fun writeZip(payload: ByteArray) {
                ZipOutputStream(archive.outputStream()).use { zip ->
                    zip.putNextEntry(ZipEntry("emberfall.read"))
                    zip.write(payload)
                    zip.closeEntry()
                }
            }
            writeZip(bytes)
            assertEquals(1, runBlocking { manager.importFromZip(game, Uri.fromFile(archive)) })
            val installed = File(gameDir, "sav/emberfall.read")
            assertArrayEquals(bytes, installed.readBytes())

            writeZip(byteArrayOf(1, 2, 3))
            val failure = assertThrows(GameSaveException::class.java) {
                runBlocking { manager.importFromZip(game, Uri.fromFile(archive)) }
            }
            assertEquals(SaveErrorCode.NO_FILES_IN_ZIP, failure.code)
            assertArrayEquals(bytes, installed.readBytes())
            assertFalse(File("/proc/self/maps").useLines { lines -> lines.any { it.contains("libsystemnnn.so") } })
        } finally {
            SavePathSafety.deleteTreeNoFollow(workspace)
        }
    }
}
