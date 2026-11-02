package com.tyranor.next.core.game.save

import android.net.Uri
import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.game.model.ScanGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SaveRecoveryDeviceTest {
    @Test
    fun realManagerRecoversUnprovenArtemisBackupWithoutReplacingCurrentSave() = withFixture { fixture ->
        fixture.interruptImport()
        fixture.manager.completePendingVersionImport(fixture.game)
        assertEquals("current-new-save", fixture.destination.resolve("slot.dat").readText())
        assertEquals("engine-resource", fixture.destination.resolve("system/data.pfs").readText())
        assertEquals("old-save", fixture.orphans().single().resolve("slot.dat").readText())
        assertFalse(fixture.backup.exists())
        assertFalse(fixture.record.exists())
        fixture.manager.completePendingVersionImport(fixture.game)
        assertEquals(1, fixture.orphans().size)
    }

    @Test
    fun realResourceCopyPermissionFailureKeepsIndexedBackupForRetry() = withFixture { fixture ->
        fixture.interruptImport()
        val system = fixture.destination.resolve("system").apply { mkdirs() }
        Os.chmod(system.absolutePath, 0b101_000_000)
        try {
            assumeTrue("Filesystem must enforce app write permissions", !system.canWrite())
            assertThrows(java.io.IOException::class.java) {
                fixture.manager.completePendingVersionImport(fixture.game)
            }
            assertTrue(fixture.backup.isDirectory)
            assertTrue(fixture.record.isFile)
            assertTrue(fixture.orphans().isEmpty())
            assertFalse(system.resolve("data.pfs").exists())
            assertEquals("old-save", fixture.backup.resolve("slot.dat").readText())
        } finally {
            Os.chmod(system.absolutePath, 0b111_000_000)
        }
        fixture.manager.completePendingVersionImport(fixture.game)
        assertEquals("engine-resource", system.resolve("data.pfs").readText())
        assertEquals("current-new-save", fixture.destination.resolve("slot.dat").readText())
        assertEquals("old-save", fixture.orphans().single().resolve("slot.dat").readText())
        assertFalse(fixture.backup.exists())
        assertFalse(fixture.record.exists())
    }

    @Test
    fun realManagerRecoversMissingDestinationAndFinalizesProvenCommit() = withFixture { fixture ->
        fixture.seedOld()
        fixture.transaction.prepare(fixture.staging)
        assertTrue(fixture.destination.renameTo(fixture.backup))
        fixture.manager.completePendingVersionImport(fixture.game)
        assertEquals("old-save", fixture.destination.resolve("slot.dat").readText())
        assertFalse(fixture.backup.exists())

        fixture.staging.resolve("slot.dat").writeText("committed-new-save")
        fixture.transaction.prepare(fixture.staging)
        assertTrue(fixture.destination.renameTo(fixture.backup))
        assertTrue(fixture.staging.renameTo(fixture.destination))
        fixture.manager.completePendingVersionImport(fixture.game)
        assertEquals("committed-new-save", fixture.destination.resolve("slot.dat").readText())
        assertEquals("engine-resource", fixture.destination.resolve("system/data.pfs").readText())
        assertFalse(fixture.backup.exists())
        assertFalse(fixture.record.exists())
        assertFalse(fixture.destination.resolve(SaveImportTransaction.MARKER).exists())
    }

    private fun withFixture(block: (Fixture) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue("Dedicated acceptance package required",
            context.packageName.startsWith("com.tyranor.next.webdavacceptance"))
        assumeTrue("Explicit device acceptance opt-in required",
            InstrumentationRegistry.getArguments().getString("webdav_device_acceptance") == "true")
        val root = File(context.filesDir, "webdav_recovery_${UUID.randomUUID()}")
        require(root.mkdirs())
        val destination = File(root, "game")
        val game = ScanGame("WebDAV recovery fixture", Uri.fromFile(destination).toString(),
            EngineType.ARTEMIS, destination.absolutePath)
        val fixture = Fixture(root, destination, game, GameSaveManager(context))
        try {
            block(fixture)
        } finally {
            SavePathSafety.deleteTreeNoFollow(root)
        }
    }

    private class Fixture(
        val root: File,
        val destination: File,
        val game: ScanGame,
        val manager: GameSaveManager,
    ) {
        val staging = File(root, "game.import_staging")
        val backup = File(root, "game.import_backup")
        val record = File(root, "game.import_transaction")
        val transaction = SaveImportTransaction(destination, game.uri)

        fun seedOld() {
            require(destination.mkdirs())
            destination.resolve("slot.dat").writeText("old-save")
            destination.resolve("system").mkdirs()
            destination.resolve("system/data.pfs").writeText("engine-resource")
            require(staging.mkdirs())
        }

        fun interruptImport() {
            seedOld()
            transaction.prepare(staging)
            require(destination.renameTo(backup))
            require(destination.mkdirs())
            destination.resolve("slot.dat").writeText("current-new-save")
        }

        fun orphans(): List<File> = root.listFiles().orEmpty().filter { it.name.startsWith("game.import_orphan_") }
    }
}
