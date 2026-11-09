package com.tyranor.runtime.systemnnn

import com.tyranor.next.core.game.save.GameSaveException
import com.tyranor.next.core.game.save.SaveErrorCode
import com.tyranor.next.core.game.save.SystemNnnSaveStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

class SystemNnnSaveInteropTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun replacesOnlyManagedFilesAndKeepsTheSharedLock() {
        val game = temp.newFolder().canonicalFile
        val saves = game.resolve("sav").apply { check(mkdir()) }
        val store = SystemNnnSaveStore(game, saves)
        val pc = saves.resolve("sysfile3.sav").apply { writeText("PC") }
        val temporary = saves.resolve("emberfall.sav.new").apply { writeText("temporary") }
        val incoming = temp.newFolder().canonicalFile
        put(incoming, "emberfall.sav", "slot")
        put(incoming, "emberfall.quick.sav.bak", "backup")
        put(incoming, "emberfall.chart/chart1/slot0.dat", "chart")
        put(incoming, "emberfall.slot20.sav", "outside range")
        put(incoming, "sysfile3.sav", "excluded")
        val lock = saves.resolve("emberfall.lock").toPath()

        store.access {
            val fileKey = Files.readAttributes(lock, BasicFileAttributes::class.java).fileKey()
            val busy = runCatching { SystemNnnHost.lockSaveDirectory(game).close() }.exceptionOrNull()
            assertTrue(busy is SystemNnnHost.SaveBusyException)
            assertEquals(3, store.replace(incoming))
            assertEquals(3, store.files().size)
            assertEquals("slot", saves.resolve("emberfall.sav").readText())
            assertEquals(fileKey, Files.readAttributes(lock, BasicFileAttributes::class.java).fileKey())
            assertEquals(3, store.replace(null))
            assertTrue(store.files().isEmpty())
        }
        assertTrue(Files.isRegularFile(lock))
        assertEquals("PC", pc.readText())
        assertEquals("temporary", temporary.readText())
    }

    @Test fun interruptedRestoreBlocksStandaloneUntilTyranorRecoversTheOldSaves() {
        val game = temp.newFolder().canonicalFile
        val saves = game.resolve("sav").apply { check(mkdir()) }
        val store = SystemNnnSaveStore(game, saves)
        val stage = saves.resolve(SystemNnnHost.SAVE_MIGRATION_DIRECTORY)
        val restore = stage.resolve(SystemNnnHost.SAVE_RESTORE_DIRECTORY)
        put(restore, "before/emberfall.sav", "old")
        put(restore, "pending", "")
        put(saves, "emberfall.sav", "partial")
        put(saves, "emberfall.read", "partial")
        val legacy = temp.newFolder()
        val refused = runCatching { GameSavePaths.forGame(game).open(legacy).close() }.exceptionOrNull()
        assertTrue(refused is IOException)
        assertEquals("partial", saves.resolve("emberfall.sav").readText())
        assertTrue(restore.resolve("pending").isFile)

        store.recoverForLaunch()
        assertEquals("old", saves.resolve("emberfall.sav").readText())
        assertFalse(saves.resolve("emberfall.read").exists())
        assertFalse(stage.exists())
        GameSavePaths.forGame(game).open(legacy).close()

        put(stage, "emberfall.quick.sav", "legacy")
        put(stage, "ready", "")
        val error = runCatching { store.access {} }.exceptionOrNull() as GameSaveException
        assertEquals(SaveErrorCode.SAVE_DIR_UNAVAILABLE, error.code)
        store.recoverForLaunch()
        assertEquals("legacy", stage.resolve("emberfall.quick.sav").readText())
        GameSavePaths.forGame(game).open(legacy).close()
        assertEquals("legacy", saves.resolve("emberfall.quick.sav").readText())
    }

    @Test fun ordinaryAccessClearsAnEmptyMigrationDirectory() {
        val game = temp.newFolder().canonicalFile
        val saves = game.resolve("sav").apply { check(mkdir()) }
        put(saves, "emberfall.sav", "saved")
        val stage = saves.resolve(SystemNnnHost.SAVE_MIGRATION_DIRECTORY).apply { check(mkdir()) }
        val store = SystemNnnSaveStore(game, saves)

        store.access {
            assertFalse(stage.exists())
            assertEquals("saved", store.files().getValue("emberfall.sav").readText())
        }
    }

    @Test fun failedReplacementRestoresTheFilesItActuallyBackedUp() {
        val game = temp.newFolder().canonicalFile
        val saves = game.resolve("sav").apply { check(mkdir()) }
        val original = linkedMapOf(
            "emberfall.sav" to "original slot",
            "emberfall.read" to "original progress",
            "emberfall.read.ext" to "original extension",
            "emberfall.chart/chart1/slot0.dat" to "original chart",
        )
        for ((name, content) in original) put(saves, name, content)
        val pc = saves.resolve("sysfile3.sav").apply { writeText("PC") }
        val incoming = temp.newFolder().canonicalFile
        for ((name, _) in original) put(incoming, name, "replacement $name")
        put(incoming, "emberfall.quick.sav", "new slot")
        var interrupted = false
        val store = SystemNnnSaveStore(game, saves) { source, target ->
            Files.move(source.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            if (!interrupted && target == saves.resolve("emberfall.sav")) {
                interrupted = true
                assertEquals("replacement emberfall.sav", target.readText())
                throw IOException("Interrupted after replacing an existing slot")
            }
        }
        val lock = saves.resolve("emberfall.lock").toPath()
        var lockKey: Any? = null
        val failure = runCatching {
            store.access {
                lockKey = Files.readAttributes(lock, BasicFileAttributes::class.java).fileKey()
                store.replace(incoming)
            }
        }.exceptionOrNull() as GameSaveException

        assertEquals(SaveErrorCode.SAVE_DIR_UNAVAILABLE, failure.code)
        assertTrue(interrupted)
        store.access {
            assertEquals(original, store.files().mapValues { (_, file) -> file.readText() })
            assertEquals(lockKey, Files.readAttributes(lock, BasicFileAttributes::class.java).fileKey())
        }
        assertFalse(saves.resolve("emberfall.quick.sav").exists())
        assertFalse(saves.resolve(SystemNnnHost.SAVE_MIGRATION_DIRECTORY).exists())
        assertEquals("PC", pc.readText())
    }

    @Test fun detectsAnUncommittedAutosaveWhenThePreviousFileStillExists() {
        val saves = temp.newFolder()
        val target = saves.resolve("emberfall.resume").apply { writeText("previous") }
        val pending = saves.resolve("emberfall.resume.new").apply { writeText("next") }

        val failure = runCatching { GameSavePaths.verifyCommitted(target) }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertEquals("previous", target.readText())

        Files.move(pending.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        GameSavePaths.verifyCommitted(target)
        assertEquals("next", target.readText())
    }

    @Test fun importedCopyUsesTheOriginalPathToFindPrivateLegacySaves() {
        val original = temp.newFolder().canonicalFile
        val imported = temp.newFolder().canonicalFile
        val legacy = temp.newFolder()
        val source = legacy.resolve(UUID.nameUUIDFromBytes(original.path.toByteArray(Charsets.UTF_8)).toString() + ".resume")
        source.writeText("old private autosave")

        GameSavePaths.forGame(imported).open(legacy, original).use {
            assertEquals(source.readText(), imported.resolve("sav/emberfall.resume").readText())
        }
        assertTrue(source.isFile)
        assertFalse(original.resolve("sav").exists())
    }

    @Test fun clearingAndRestoringImportedResourcesKeepsTheOriginalSaveDirectoryAndLock() {
        val base = temp.newFolder("systemnnn-imports").canonicalFile
        val source = "primary:Games/Sample"
        val entry = base.resolve(SystemNnnGameImport.key(source))
        val game = entry.resolve("game")
        put(entry, "source.id", source)
        put(game, "spt/sys.spt", "script")
        put(game, "graphics.dat", "resources")
        put(game, "SaV/emberfall.resume", "autosave")
        put(game, "SaV/sysfile3.sav", "PC")
        val lock = game.resolve("SaV/emberfall.lock").toPath()
        val lockIdentity = SystemNnnHost.lockSaveDirectory(game).use {
            val refused = runCatching { SystemNnnGameImport.clearResources(base, source) }.exceptionOrNull()
            assertTrue(refused is SystemNnnHost.SaveBusyException)
            assertTrue(SystemNnnGameImport.readableBootstrap(game))
            Files.readAttributes(lock, BasicFileAttributes::class.java).fileKey()
        }

        assertTrue(SystemNnnGameImport.clearResources(base, source))
        assertFalse(SystemNnnGameImport.hasImportedResources(game))
        assertEquals(game, SystemNnnGameImport.committedRoot(base, source))
        assertTrue(SystemNnnGameImport.resourcesEvicted(game))
        put(game, "spt/sys.spt", "partial publish")
        assertFalse(SystemNnnGameImport.readableBootstrap(game))

        val restored = SystemNnnGameImport.importDirectory(base, source, {}) {
            SystemNnnGameImport.ImportSource { staging, restoring, publish ->
                assertTrue(restoring)
                put(staging, "spt/sys.spt", "reimported script")
                put(staging, "graphics.dat", "reimported graphics")
                put(staging, "sav/emberfall.resume", "source autosave")
                put(staging, "sav/sysfile3.sav", "source PC save")
                publish.run()
            }
        }
        assertEquals(game, restored)
        assertFalse(SystemNnnGameImport.resourcesEvicted(restored))
        assertTrue(SystemNnnGameImport.readableBootstrap(restored))
        assertEquals("reimported script", restored.resolve("spt/sys.spt").readText())
        assertEquals("reimported graphics", restored.resolve("graphics.dat").readText())
        assertEquals("autosave", restored.resolve("SaV/emberfall.resume").readText())
        assertEquals("PC", restored.resolve("SaV/sysfile3.sav").readText())
        assertEquals(lockIdentity, Files.readAttributes(lock, BasicFileAttributes::class.java).fileKey())
    }

    @Test fun globalProgressRecoveryKeepsValidGenerationAndStorySaves() {
        val saves = temp.newFolder()
        val base = saves.resolve("emberfall.read")
        val backup = saves.resolve("emberfall.read.bak")
        val extension = saves.resolve("emberfall.read.ext").apply { writeText("paired extension") }
        val pc = saves.resolve("sysfile3.sav").apply { writeText("PC") }
        val autosave = saves.resolve("emberfall.resume").apply { writeText("autosave") }
        base.writeText("truncated base")
        DataOutputStream(backup.outputStream()).use {
            it.writeInt(0x4e4e5231)
            it.writeInt(0)
        }
        val valid = backup.readBytes()

        GameProgress.recoverCorruptBase(base)
        assertFalse(base.exists())
        assertTrue(valid.contentEquals(backup.readBytes()))
        assertEquals("paired extension", extension.readText())
        assertEquals("truncated base", saves.listFiles()!!.single {
            it.name.startsWith("emberfall.read.corrupt-")
        }.readText())

        base.writeBytes(valid)
        backup.writeText("bad backup")
        GameProgress.recoverCorruptBase(base)
        assertTrue(valid.contentEquals(base.readBytes()))
        assertFalse(backup.exists())
        assertEquals("paired extension", extension.readText())

        base.writeText("unusable progress")
        assertTrue(backup.mkdir())
        val refused = runCatching { GameProgress.recoverCorruptBase(base) }.exceptionOrNull()
        assertTrue(refused is IOException)
        assertEquals("unusable progress", base.readText())
        assertEquals("paired extension", extension.readText())
        Files.delete(backup.toPath())
        GameProgress.recoverCorruptBase(base)
        assertFalse(base.exists())
        assertFalse(extension.exists())
        assertEquals("paired extension", saves.listFiles()!!.single {
            it.name.startsWith("emberfall.read.ext.corrupt-")
        }.readText())
        assertEquals("PC", pc.readText())
        assertEquals("autosave", autosave.readText())
    }

    @Test fun countsAPlayerOwnerBeforeAndAfterTheFileLock() {
        val game = temp.newFolder().canonicalFile
        val owner = GameSavePaths.acquireOwner()
        owner.use {
            assertTrue(SystemNnnHost.hasActiveSaveAccess())
            SystemNnnHost.lockSaveDirectory(game).use {
                assertTrue(SystemNnnHost.hasActiveSaveAccess())
            }
            assertTrue(SystemNnnHost.hasActiveSaveAccess())
        }
        owner.close()
        assertFalse(SystemNnnHost.hasActiveSaveAccess())
    }

    private fun put(root: File, name: String, content: String) {
        root.resolve(name).apply {
            parentFile.mkdirs()
            writeText(content)
        }
    }
}
