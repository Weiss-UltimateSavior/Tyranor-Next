package com.tyranor.next.core.game.save

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.game.model.ScanGame
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class SystemNnnSaveValidationDeviceTest {
    @Test
    fun zipValidationLoadsTheRuntimeOutsideTheLauncher() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val workspace = Files.createTempDirectory(app.cacheDir.toPath(), "systemnnn-validation-").toFile()
        try {
            val gameDir = File(workspace, "game").apply { check(mkdir()) }
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
