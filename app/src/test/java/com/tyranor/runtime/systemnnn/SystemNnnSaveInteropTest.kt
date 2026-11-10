package com.tyranor.runtime.systemnnn

import com.tyranor.next.core.game.save.GameSaveException
import com.tyranor.next.core.game.save.SaveErrorCode
import com.tyranor.next.core.game.save.SystemNnnSaveStore
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class SystemNnnSaveInteropTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun replacesOnlyManagedFilesAndKeepsTheSharedLock() {
        val game = temp.newFolder().canonicalFile
        val saves = game.resolve("sav").apply { check(mkdir()) }
        val store = SystemNnnSaveStore(game, saves)
        val pc = saves.resolve("sysfile3.sav").apply { writeText("PC") }
        val temporary = saves.resolve("emberfall.sav.new").apply { writeText("temporary") }
        val incoming = temp.newFolder().canonicalFile
        val story = incoming.resolve("emberfall.sav").apply {
            DataOutputStream(outputStream()).use {
                it.writeInt(GameSave.MAGIC_NS1)
                it.writeInt(1)
                it.writeInt(1)
                it.writeInt(20)
                it.writeInt(0)
                it.writeInt(0)
                it.writeBoolean(false)
                it.writeInt(0)
                it.writeUTF("start.spt")
                it.writeInt(1)
                it.writeInt(0)
                it.writeInt(1)
            }
        }
        val backup = putStory(incoming, "emberfall.quick.sav.bak", SaveSlots.QUICK)
        put(incoming, "emberfall.chart/chart1/slot0.dat", "chart")
        val highSlot = putStory(incoming, "emberfall.slot118.sav", 118, snapshotPayload())
        put(incoming, "emberfall.slot118.sav.thumb", "high slot thumbnail")
        put(incoming, "emberfall.last-slot", "last selection")
        put(incoming, "emberfall.last-slot.new", "interrupted selection")
        put(incoming, "emberfall.slot-ui", "slot presentation")
        put(incoming, "emberfall.slot-ui.new", "interrupted presentation")
        put(incoming, "emberfall.slot-ui.bak", "unknown presentation backup")
        put(incoming, "emberfall.slot0118.sav", "noncanonical alias")
        put(incoming, "emberfall.slot4096.sav", "outside range")
        put(incoming, "sysfile3.sav", "excluded")
        val metadataOnly = temp.newFolder().canonicalFile
        put(metadataOnly, "emberfall.slot-ui", "slot presentation only")
        val lock = saves.resolve("emberfall.lock").toPath()

        store.access {
            val fileKey = Files.readAttributes(lock, BasicFileAttributes::class.java).fileKey()
            val busy = runCatching { SystemNnnHost.lockSaveDirectory(game).close() }.exceptionOrNull()
            assertTrue(busy is SystemNnnHost.SaveBusyException)
            assertEquals(7, store.replace(incoming))
            assertEquals(7, store.files().size)
            assertArrayEquals(story.readBytes(), saves.resolve(story.name).readBytes())
            assertArrayEquals(backup.readBytes(), saves.resolve(backup.name).readBytes())
            assertFalse(incoming.resolve("emberfall.quick.sav").exists())
            assertFalse(saves.resolve("emberfall.quick.sav").exists())
            assertArrayEquals(highSlot.readBytes(), saves.resolve(highSlot.name).readBytes())
            assertEquals("high slot thumbnail", saves.resolve("emberfall.slot118.sav.thumb").readText())
            assertEquals("last selection", saves.resolve("emberfall.last-slot").readText())
            assertFalse(saves.resolve("emberfall.last-slot.new").exists())
            assertEquals("slot presentation", saves.resolve("emberfall.slot-ui").readText())
            assertFalse(saves.resolve("emberfall.slot-ui.new").exists())
            assertFalse(saves.resolve("emberfall.slot-ui.bak").exists())
            assertFalse(saves.resolve("emberfall.slot0118.sav").exists())
            assertFalse(saves.resolve("emberfall.slot4096.sav").exists())
            assertEquals(fileKey, Files.readAttributes(lock, BasicFileAttributes::class.java).fileKey())
            val error = assertThrows(GameSaveException::class.java) { store.replace(metadataOnly) }
            assertEquals(SaveErrorCode.NO_FILES_IN_ZIP, error.code)
            assertArrayEquals(story.readBytes(), saves.resolve(story.name).readBytes())
            assertEquals(7, store.replace(null))
            assertTrue(store.files().isEmpty())
        }
        assertTrue(Files.isRegularFile(lock))
        assertEquals("PC", pc.readText())
        assertEquals("temporary", temporary.readText())
    }

    @Test fun rejectsIncompleteOrDamagedSnapshotsBeforeChangingLiveSaves() {
        val game = temp.newFolder().canonicalFile
        val saves = game.resolve("sav").apply { check(mkdir()) }
        val store = SystemNnnSaveStore(game, saves)
        val story = putStory(saves, "emberfall.resume.bak", SaveSlots.AUTO)
        val progress = putProgress(saves, 9)
        val storyBytes = story.readBytes()
        val progressBytes = progress.readBytes()

        fun rejectsSnapshot(prepare: (File) -> Unit) {
            val incoming = temp.newFolder().canonicalFile
            prepare(incoming)
            val original = incoming.listFiles()!!.associate { it.name to it.readBytes() }
            var committed = false
            val error = assertThrows(GameSaveException::class.java) {
                store.access { store.replace(incoming) { committed = true } }
            }
            assertEquals(SaveErrorCode.NO_FILES_IN_ZIP, error.code)
            assertFalse(committed)
            assertFalse(saves.resolve(SystemNnnHost.SAVE_MIGRATION_DIRECTORY).exists())
            assertArrayEquals(storyBytes, story.readBytes())
            assertArrayEquals(progressBytes, progress.readBytes())
            assertEquals(original.keys, incoming.list()!!.toSet())
            for ((name, bytes) in original) assertArrayEquals(bytes, incoming.resolve(name).readBytes())
        }

        rejectsSnapshot { put(it, "emberfall.last-slot", "index") }
        rejectsSnapshot { put(it, "emberfall.sav.thumb", "thumbnail") }
        rejectsSnapshot { putProgress(it, 11) }
        rejectsSnapshot { put(it, "emberfall.sav", "damaged") }
        rejectsSnapshot { putStory(it, "emberfall.sav", 1) }
        rejectsSnapshot { putStory(it, "emberfall.resume", SaveSlots.AUTO, byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9)) }
        rejectsSnapshot { putStory(it, "emberfall.resume", SaveSlots.AUTO, snapshotPayload(version = 14)) }
        rejectsSnapshot {
            putStory(it, "emberfall.resume", SaveSlots.AUTO, snapshotPayload()).apply {
                val bytes = readBytes()
                writeBytes(bytes.copyOf(bytes.size - 1))
            }
        }
        rejectsSnapshot {
            putStory(it, "emberfall.sav", 0)
            put(it, "emberfall.sav.bak", "damaged legacy backup")
            putStory(it, "emberfall.slot01.sav", 1)
        }
        rejectsSnapshot {
            putStory(it, "emberfall.sav", 0)
            put(it, "emberfall.read", "damaged progress primary")
            put(it, "emberfall.read.bak", "damaged progress backup")
        }
    }

    @Test fun validatesStagedProgressPairsWithoutChangingEitherGeneration() {
        val incoming = temp.newFolder().canonicalFile
        val story = putStory(incoming, "emberfall.sav", 0)
        val primary = putProgress(incoming, 21)
        val backup = incoming.resolve("emberfall.read.bak").apply { writeText("damaged backup") }
        val files = listOf(story, primary, backup).associateBy { it.name }

        fun validatesUnchanged() {
            val before = files.mapValues { (_, file) -> file.readBytes() }
            assertTrue(SystemNnnHost.canReplaceSaveSnapshot(files, emptySet()))
            assertEquals(files.keys, incoming.list()!!.toSet())
            for ((name, bytes) in before) assertArrayEquals(bytes, files.getValue(name).readBytes())
        }

        validatesUnchanged()
        primary.writeText("damaged primary")
        DataOutputStream(backup.outputStream()).use { output ->
            output.writeInt(0x4e4e5231)
            output.writeInt(1)
            output.writeUTF("legacy progress")
        }
        validatesUnchanged()
    }

    @Test fun restoresAValidBackupOnlyStorySnapshot() {
        val game = temp.newFolder().canonicalFile
        val saves = game.resolve("sav").apply { check(mkdir()) }
        val store = SystemNnnSaveStore(game, saves)
        val incoming = temp.newFolder().canonicalFile
        val backup = putStory(incoming, "emberfall.quick.sav.bak", SaveSlots.QUICK)
        store.access {
            assertEquals(1, store.replace(incoming))
            assertArrayEquals(backup.readBytes(), saves.resolve(backup.name).readBytes())
        }
        assertFalse(incoming.resolve("emberfall.quick.sav").exists())
        assertFalse(saves.resolve("emberfall.quick.sav").exists())
    }

    @Test fun restoresGlobalProgressOnlyWhenTheDestinationHasNoStoryOrChart() {
        val game = temp.newFolder().canonicalFile
        val saves = game.resolve("sav").apply { check(mkdir()) }
        val store = SystemNnnSaveStore(game, saves)
        val incoming = temp.newFolder().canonicalFile
        val progress = putProgress(incoming, 27, "emberfall.read.bak")
        store.access {
            assertEquals(1, store.replace(incoming))
            assertArrayEquals(progress.readBytes(), saves.resolve(progress.name).readBytes())
            put(saves, "emberfall.chart/chart1/slot0.dat", "checkpoint")
            val error = assertThrows(GameSaveException::class.java) { store.replace(incoming) }
            assertEquals(SaveErrorCode.NO_FILES_IN_ZIP, error.code)
            assertEquals("checkpoint", saves.resolve("emberfall.chart/chart1/slot0.dat").readText())
        }
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
        val replacement = putStory(incoming, "emberfall.sav", 0)
        for (name in original.keys - setOf(replacement.name, "emberfall.read")) put(incoming, name, "replacement $name")
        putProgress(incoming, 34)
        putStory(incoming, "emberfall.quick.sav", SaveSlots.QUICK)
        var interrupted = false
        val store = SystemNnnSaveStore(game, saves) { source, target ->
            Files.move(source.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            if (!interrupted && target == saves.resolve("emberfall.sav")) {
                interrupted = true
                assertArrayEquals(replacement.readBytes(), target.readBytes())
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

    @Test fun committedCallbackFailureKeepsTheReplacementAndCleansTheStage() {
        val game = temp.newFolder().canonicalFile
        val saves = game.resolve("sav").apply { check(mkdir()) }
        val previous = putStory(saves, "emberfall.sav", 0)
        val incoming = temp.newFolder().canonicalFile
        val replacement = putStory(incoming, "emberfall.resume", SaveSlots.AUTO)
        val store = SystemNnnSaveStore(game, saves)
        val stage = saves.resolve(SystemNnnHost.SAVE_MIGRATION_DIRECTORY)
        val error = IOException("Could not record the committed save")

        val failure = assertThrows(GameSaveException::class.java) {
            store.access { store.replace(incoming) { throw error } }
        }

        assertEquals(SaveErrorCode.SAVE_DIR_UNAVAILABLE, failure.code)
        assertSame(error, failure.cause)
        assertFalse(stage.exists())
        assertFalse(previous.exists())
        store.access {
            assertArrayEquals(replacement.readBytes(), store.files().getValue(replacement.name).readBytes())
        }
    }

    @Test fun committedCallbackCancellationKeepsTheReplacementAndPreservesCleanupFailure() {
        val game = temp.newFolder().canonicalFile
        val saves = game.resolve("sav").apply { check(mkdir()) }
        val previous = putStory(saves, "emberfall.sav", 0)
        val incoming = temp.newFolder().canonicalFile
        val replacement = putStory(incoming, "emberfall.resume", SaveSlots.AUTO)
        val store = SystemNnnSaveStore(game, saves)
        val stage = saves.resolve(SystemNnnHost.SAVE_MIGRATION_DIRECTORY)
        val restore = stage.resolve(SystemNnnHost.SAVE_RESTORE_DIRECTORY)
        val cancelled = CancellationException("Version update cancelled")

        val failure = assertThrows(CancellationException::class.java) {
            store.access {
                store.replace(incoming) {
                    assertFalse(restore.resolve("pending").exists())
                    stage.resolve("cleanup-blocker").writeText("Keep the parent directory nonempty")
                    throw cancelled
                }
            }
        }

        assertSame(cancelled, failure)
        assertTrue(failure.suppressed.single() is IOException)
        assertFalse(restore.exists())
        assertFalse(previous.exists())
        assertArrayEquals(replacement.readBytes(), saves.resolve(replacement.name).readBytes())
    }

    @Test fun aSeparateJvmLockBlocksSaveReplacementUntilTheHolderExits() {
        val game = temp.newFolder().canonicalFile
        val saves = game.resolve("sav").apply { check(mkdir()) }
        val previous = putStory(saves, "emberfall.sav", 0)
        val incoming = temp.newFolder().canonicalFile
        val replacement = putStory(incoming, "emberfall.resume", SaveSlots.AUTO)
        val store = SystemNnnSaveStore(game, saves)

        withExternalLock(saves.resolve("emberfall.lock")) {
            val failure = assertThrows(GameSaveException::class.java) {
                store.access { store.replace(incoming) }
            }
            assertEquals(SaveErrorCode.ENGINE_RUNNING, failure.code)
            assertTrue(failure.cause is SystemNnnHost.SaveBusyException)
            assertTrue(previous.exists())
            assertFalse(saves.resolve(replacement.name).exists())
        }

        assertEquals(1, store.access { store.replace(incoming) })
        assertFalse(previous.exists())
        assertArrayEquals(replacement.readBytes(), saves.resolve(replacement.name).readBytes())
    }

    private fun withExternalLock(path: File, block: () -> Unit) {
        val java = File(System.getProperty("java.home"), "bin/java").let {
            if (it.isFile) it else File(it.parentFile, "java.exe")
        }
        val fixture = SystemNnnLockHolder::class.java
        val classes = File(checkNotNull(fixture.protectionDomain?.codeSource?.location).toURI())
        val child = ProcessBuilder(java.path, "-cp", classes.path, fixture.name, path.path)
            .redirectErrorStream(true)
            .start()

        try {
            val ready = CompletableFuture.supplyAsync { child.inputStream.bufferedReader().use { it.readLine() } }
            assertEquals("locked", ready.get(30, TimeUnit.SECONDS))
            block()
            child.outputStream.close()
            assertTrue("Lock holder should exit when stdin closes", child.waitFor(10, TimeUnit.SECONDS))
            assertEquals(0, child.exitValue())
        } finally {
            if (child.isAlive) {
                child.destroyForcibly()
                child.waitFor(10, TimeUnit.SECONDS)
            }
        }

    }

    @Test fun importedCopyUsesTheOriginalPathToFindPrivateLegacySaves() {
        val original = temp.newFolder().canonicalFile
        val imported = temp.newFolder().canonicalFile
        val legacy = temp.newFolder()
        val oldKey = UUID.nameUUIDFromBytes(original.path.toByteArray(Charsets.UTF_8)).toString()
        val source = legacy.resolve(oldKey + ".resume")
        source.writeText("old private autosave")
        val high = legacy.resolve(oldKey + ".slot118.sav.bak").apply { writeText("old high slot backup") }
        val selected = legacy.resolve(oldKey + ".last-slot").apply { writeText("old last selection") }

        GameSavePaths.forGame(imported).open(legacy, original).use {
            assertEquals(source.readText(), imported.resolve("sav/emberfall.resume").readText())
            assertEquals(high.readText(), imported.resolve("sav/emberfall.slot118.sav").readText())
            assertEquals(selected.readText(), imported.resolve("sav/emberfall.last-slot").readText())
        }
        assertTrue(source.isFile)
        assertFalse(original.resolve("sav").exists())
    }

    @Test fun freshSafCopyAndZipReplacementCannotUseTheSourceAtTheSameTime() {
        val base = temp.newFolder("systemnnn-imports").canonicalFile
        val source = "primary:Games/Sample"
        val original = temp.newFolder().canonicalFile
        val saves = original.resolve("sav").apply { check(mkdir()) }
        val oldStory = putStory(saves, "emberfall.sav", 0)
        val incoming = temp.newFolder()
        val newStory = putStory(incoming, "emberfall.resume", SaveSlots.AUTO)
        val store = SystemNnnSaveStore(original, saves)

        fun copy(): File = SystemNnnGameImport.importDirectory(base, source, true, {}) {
            SystemNnnGameImport.ImportSource { staging, restoring, publish ->
                assertFalse(restoring)
                val busy = assertThrows(SystemNnnGameImport.Failure::class.java) {
                    SystemNnnGameImport.withImportLock(base, source) { error("Save target must wait for publication") }
                }
                assertEquals(SystemNnnGameImport.Reason.BUSY, busy.reason)
                put(staging, "spt/sys.spt", "script")
                saves.copyRecursively(staging.resolve("sav"))
                publish.run()
            }
        }

        withExternalLock(base.resolve("${SystemNnnGameImport.key(source)}.lock")) {
            val busy = assertThrows(SystemNnnGameImport.Failure::class.java) { copy() }
            assertEquals(SystemNnnGameImport.Reason.BUSY, busy.reason)
        }

        SystemNnnGameImport.withImportLock(base, source) {
            assertEquals(null, SystemNnnGameImport.committedRoot(base, source))
            val busy = assertThrows(SystemNnnGameImport.Failure::class.java) { copy() }
            assertEquals(SystemNnnGameImport.Reason.BUSY, busy.reason)
            store.access { store.replace(incoming) }
        }

        val importedSaves = copy().resolve("sav")
        assertFalse(importedSaves.resolve(oldStory.name).exists())
        assertArrayEquals(newStory.readBytes(), importedSaves.resolve(newStory.name).readBytes())
    }

    @Test fun resourceRehydrationCannotCreateAReplacementSaveDirectory() {
        val base = temp.newFolder("systemnnn-imports").canonicalFile
        val source = "primary:Games/MissingCopy"
        val failure = assertThrows(SystemNnnGameImport.Failure::class.java) {
            SystemNnnGameImport.importDirectory(base, source, false, {}) { error("Missing copy must fail first") }
        }
        assertEquals(SystemNnnGameImport.Reason.CORRUPT_COPY, failure.reason)
        assertEquals(null, SystemNnnGameImport.committedRoot(base, source))
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

        val restored = SystemNnnGameImport.importDirectory(base, source, false, {}) {
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

    @Test fun orphanEnumerationAcceptsOnlyValidatedCopiesWithResources() {
        val base = temp.newFolder("systemnnn-imports").canonicalFile
        val source = "primary:Games/Orphan"
        val entry = base.resolve(SystemNnnGameImport.key(source))
        put(entry, "source.id", source)
        put(entry, "game/spt/sys.spt", "script")
        put(entry, "game/SaV/sysfile3.sav", "PC")

        val saveOnly = "primary:Games/SaveOnly"
        val emptyCopy = base.resolve(SystemNnnGameImport.key(saveOnly))
        put(emptyCopy, "source.id", saveOnly)
        put(emptyCopy, "game/sav/emberfall.resume", "autosave")

        val wrongHash = base.resolve(SystemNnnGameImport.key("primary:Games/WrongHash"))
        put(wrongHash, "source.id", source)
        put(wrongHash, "game/spt/sys.spt", "wrong source")
        val staging = base.resolve("${entry.name}.pending-test")
        put(staging, "source.id", source)
        put(staging, "game/spt/sys.spt", "staged")

        assertEquals(listOf(source), SystemNnnGameImport.importedResourceSourceIds(base))
        assertTrue(SystemNnnGameImport.clearResources(base, source))
        assertTrue(SystemNnnGameImport.importedResourceSourceIds(base).isEmpty())
        assertEquals("PC", entry.resolve("game/SaV/sysfile3.sav").readText())
        assertEquals("wrong source", wrongHash.resolve("game/spt/sys.spt").readText())
        assertEquals("staged", staging.resolve("game/spt/sys.spt").readText())
    }

    @Test fun readingCorruptProgressRestoresValidatedBackupAndItsExtension() {
        val game = temp.newFolder()
        val saves = game.resolve("sav").apply { check(mkdir()) }
        val base = saves.resolve("emberfall.read")
        val backup = saves.resolve("emberfall.read.bak")
        val extension = saves.resolve("emberfall.read.ext")
        val corrupt = byteArrayOf(0x4e, 0x4e, 0x52, 0x34, 0, 0, 0, 1, 0)
        base.writeBytes(corrupt)
        val read = intArrayOf(9, 117)
        val variables = IntArray(NativeGame.SYSTEM_VARIABLES).apply { this[5] = 37 }
        val results = IntArray(NativeGame.SCRIPT_PROGRESS).apply { this[7] = 12 }
        val expected = GameProgress.State(read, variables, results, null, null, null, listOf("legacy"))
        DataOutputStream(backup.outputStream()).use { GameProgress.encode(it, expected) }
        val valid = backup.readBytes()
        val matchingFingerprint = MessageDigest.getInstance("SHA-256").digest(valid)
        val restoredText = "recovered".toByteArray()
        DataOutputStream(extension.outputStream()).use { output ->
            output.writeInt(0x4e4e4532)
            output.writeByte(2)
            for ((fingerprint, label) in listOf(ByteArray(32) to "stale".toByteArray(), matchingFingerprint to restoredText)) {
                output.write(fingerprint)
                output.writeByte(label.size)
                output.write(label)
                repeat(101) { output.writeByte(0) }
                output.writeShort(1)
                output.writeShort(2)
                output.writeInt(16)
                output.writeInt(0)
            }
        }
        val extensionBytes = extension.readBytes()

        SystemNnnHost.lockSaveDirectory(game).use {
            val restored = checkNotNull(GameProgress(base).read())
            assertArrayEquals(read, restored.read)
            assertArrayEquals(variables, restored.variables)
            assertArrayEquals(results, restored.results)
            assertEquals(expected.legacy, restored.legacy)
            assertArrayEquals(restoredText, restored.text[0])
            assertEquals(16, restored.optional[2])
            assertEquals(0, restored.optional[NativeGame.LEGACY_OPTIONAL_PROGRESS_WORDS])
            assertArrayEquals(valid, base.readBytes())
            assertFalse(backup.exists())
            assertArrayEquals(extensionBytes, extension.readBytes())
            val archived = saves.listFiles()!!.single { it.name.startsWith("emberfall.read.corrupt-") }
            assertArrayEquals(corrupt, archived.readBytes())
        }
    }

    @Test fun validPrimaryWinsAndSelectsItsOwnExtension() {
        val saves = temp.newFolder()
        val primary = putProgress(saves, 21)
        val backup = putProgress(saves, 9, "emberfall.read.bak")
        val old = backup.readBytes()
        val extension = saves.resolve("emberfall.read.ext")
        putExtension(extension, backup to 9, primary to 21)

        val progress = GameProgress(primary)
        val current = checkNotNull(progress.read())
        assertArrayEquals(intArrayOf(21), current.read)
        assertArrayEquals(byteArrayOf(21), current.text[0])
        assertArrayEquals(old, backup.readBytes())

        // A legacy extension backup can still contain the selected base's record.
        putExtension(saves.resolve("emberfall.read.ext.bak"), primary to 21)
        putExtension(extension, backup to 9)
        val recovered = checkNotNull(progress.read())
        assertArrayEquals(intArrayOf(21), recovered.read)
        assertArrayEquals(byteArrayOf(21), recovered.text[0])
    }

    @Test fun healthyPrimaryReadsEvenWhenMalformedBackupCannotBeIsolated() {
        val saves = temp.newFolder()
        val primary = putProgress(saves, 21)
        val backup = saves.resolve("emberfall.read.bak").apply { writeText("broken backup") }
        val extension = saves.resolve("emberfall.read.ext")
        putExtension(extension, primary to 21)
        var attempted = false
        val locked = GameProgress(primary) { from, _ ->
            assertEquals(backup.toPath(), from)
            attempted = true
            throw IOException("cannot isolate backup")
        }
        val healthy = checkNotNull(locked.read())
        assertTrue(attempted)
        assertArrayEquals(intArrayOf(21), healthy.read)
        assertArrayEquals(byteArrayOf(21), healthy.text[0])
        assertEquals("broken backup", backup.readText())

        assertArrayEquals(intArrayOf(21), checkNotNull(GameProgress(primary).read()).read)
        assertFalse(backup.exists())
        assertEquals("broken backup", saves.listFiles()!!.single {
            it.name.startsWith("emberfall.read.bak.corrupt-")
        }.readText())
    }

    @Test fun backupRecoveryCanRetryAfterEitherMoveFails() {
        val saves = temp.newFolder()
        val primary = saves.resolve("emberfall.read").apply { writeText("broken primary") }
        val backup = putProgress(saves, 9, "emberfall.read.bak")
        val backupBytes = backup.readBytes()
        putExtension(saves.resolve("emberfall.read.ext"), backup to 9)

        fun failsAt(file: File) {
            val store = GameProgress(primary) { from, to ->
                if (from == file.toPath()) throw IOException("interrupted recovery")
                Files.move(from, to, ATOMIC_MOVE, REPLACE_EXISTING)
            }
            val failure = assertThrows(IOException::class.java) { store.read() }
            assertFalse(failure is GameProgress.InvalidBase)
            assertArrayEquals(backupBytes, backup.readBytes())
        }

        failsAt(primary)
        assertEquals("broken primary", primary.readText())
        failsAt(backup)
        assertFalse(primary.exists())
        val isolated = saves.listFiles()!!.single { it.name.startsWith("emberfall.read.corrupt-") }
        assertEquals("broken primary", isolated.readText())

        val restored = checkNotNull(GameProgress(primary).read())
        assertArrayEquals(intArrayOf(9), restored.read)
        assertArrayEquals(byteArrayOf(9), restored.text[0])
        assertArrayEquals(backupBytes, primary.readBytes())
        assertFalse(backup.exists())
        assertEquals("broken primary", isolated.readText())
    }

    @Test fun failedReopenWriteKeepsCurrentBaseAndMatchingExtensionForRetry() {
        val saves = temp.newFolder()
        val primary = saves.resolve("emberfall.read")
        val store = GameProgress(primary)
        store.write(progressState(21, 21))
        val current = primary.readBytes()
        val backup = putProgress(saves, 9, "emberfall.read.bak")
        val old = backup.readBytes()
        val pending = progressState(34, 34)

        val interrupted = GameProgress(primary) { from, to ->
            if (to == primary.toPath()) throw IOException("cannot commit recovered progress")
            Files.move(from, to, ATOMIC_MOVE, REPLACE_EXISTING)
        }
        assertThrows(IOException::class.java) { interrupted.write(pending) }
        assertArrayEquals(current, primary.readBytes())
        val previous = checkNotNull(store.read())
        assertArrayEquals(intArrayOf(21), previous.read)
        assertArrayEquals(byteArrayOf(21), previous.text[0])
        assertFalse(saves.resolve("emberfall.read.new").exists())
        val archived = saves.listFiles()!!.single { it.name.startsWith("emberfall.read.bak.previous-") }
        assertArrayEquals(old, archived.readBytes())

        store.write(pending)
        val recovered = checkNotNull(store.read())
        assertArrayEquals(intArrayOf(34), recovered.read)
        assertArrayEquals(byteArrayOf(34), recovered.text[0])
    }

    @Test fun extensionOnlyCommitSucceedsWhenTheLegacyBackupCannotBeRetired() {
        val saves = temp.newFolder()
        val primary = saves.resolve("emberfall.read")
        val extension = saves.resolve("emberfall.read.ext")
        val backup = saves.resolve("emberfall.read.ext.bak")
        val store = GameProgress(primary)
        store.write(progressState(21, 21))
        extension.copyTo(backup)
        val previous = backup.readBytes()
        val currentBase = primary.readBytes()
        var attempted = false
        val interrupted = GameProgress(primary) { from, to ->
            if (from == backup.toPath()) {
                attempted = true
                throw IOException("cannot retire the legacy extension backup")
            }
            Files.move(from, to, ATOMIC_MOVE, REPLACE_EXISTING)
        }

        interrupted.write(progressState(21, 34))

        assertTrue(attempted)
        assertArrayEquals(currentBase, primary.readBytes())
        assertArrayEquals(previous, backup.readBytes())
        assertArrayEquals(byteArrayOf(34), checkNotNull(store.read()).text[0])
    }

    @Test fun readsSeparateVoiceRoleAndClipFlags() {
        val saves = temp.newFolder()
        val base = saves.resolve("emberfall.read")
        val extension = saves.resolve("emberfall.read.ext")
        val state = GameProgress.State(
            intArrayOf(9), IntArray(NativeGame.SYSTEM_VARIABLES), IntArray(NativeGame.SCRIPT_PROGRESS),
            null, null, null, emptyList(),
        )
        DataOutputStream(base.outputStream()).use { GameProgress.encode(it, state) }
        DataOutputStream(extension.outputStream()).use { output ->
            output.writeInt(0x4e4e4533)
            output.writeByte(1)
            output.write(MessageDigest.getInstance("SHA-256").digest(base.readBytes()))
            repeat(102) { output.writeByte(0) }
            output.writeShort(2)
            output.writeShort(1024)
            output.writeInt(1 shl 17)
            output.writeShort(NativeGame.LEGACY_OPTIONAL_PROGRESS_WORDS)
            output.writeInt(1 shl 6)
            output.writeInt(0)
        }

        val restored = checkNotNull(GameProgress(base).read())
        assertArrayEquals(state.read, restored.read)
        assertEquals(NativeGame.OPTIONAL_PROGRESS_WORDS, restored.optional.size)
        assertEquals(1 shl 17, restored.optional[1024])
        assertEquals(1 shl 6, restored.optional[NativeGame.LEGACY_OPTIONAL_PROGRESS_WORDS])
    }

    @Test fun readingCorruptProgressWithoutValidBackupPreservesBothFiles() {
        val saves = temp.newFolder()
        val base = saves.resolve("emberfall.read").apply { writeText("truncated main") }
        val backup = saves.resolve("emberfall.read.bak")
        val progress = GameProgress(base)

        assertThrows(GameProgress.InvalidBase::class.java) { progress.read() }
        backup.writeText("truncated backup")
        assertThrows(GameProgress.InvalidBase::class.java) { progress.read() }
        assertThrows(GameProgress.InvalidBase::class.java) { progress.write(progressState(21, 21)) }
        assertEquals("truncated main", base.readText())
        assertEquals("truncated backup", backup.readText())
        assertEquals(setOf(base.name, backup.name), saves.list()!!.toSet())

        Files.delete(backup.toPath())
        check(backup.mkdir())
        val access = assertThrows(IOException::class.java) { progress.read() }
        assertFalse(access is GameProgress.InvalidBase)
        assertEquals("truncated main", base.readText())
        assertTrue(backup.isDirectory)
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

    private fun snapshotPayload(version: Int = 13): ByteArray =
        ByteBuffer.wrap(Random(7).nextBytes(32768)).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0x31534e4e).putInt(version).array()

    private fun putStory(root: File, name: String, slot: Int, snapshot: ByteArray? = null): File {
        val script = "start.spt"
        val save = if (snapshot == null) GameSave().apply { append(20, 0, 0, false, 0) }
        else GameSave.nativeBaseline()
        val metadata = GameSave.Metadata(slot, 1L, script, 1, 0, 1, "", "Story", false)
        val loaded = GameSave.Loaded(save, metadata, arrayOf(script.toByteArray(), byteArrayOf()), snapshot)
        return root.resolve(name).apply { outputStream().use { GameSave.writePayload(it, loaded) } }
    }

    private fun progressState(read: Int, text: Byte? = null): GameProgress.State =
        GameProgress.State(intArrayOf(read), IntArray(NativeGame.SYSTEM_VARIABLES), IntArray(NativeGame.SCRIPT_PROGRESS),
            text?.let { value -> Array(102) { if (it == 0) byteArrayOf(value) else byteArrayOf() } },
            null, null, emptyList())

    private fun putProgress(root: File, read: Int, name: String = "emberfall.read"): File {
        return root.resolve(name).apply {
            DataOutputStream(outputStream()).use { GameProgress.encode(it, progressState(read)) }
        }
    }

    private fun putExtension(file: File, vararg records: Pair<File, Int>) {
        DataOutputStream(file.outputStream()).use { output ->
            output.writeInt(0x4e4e4532)
            output.writeByte(records.size)
            for ((base, value) in records) {
                output.write(MessageDigest.getInstance("SHA-256").digest(base.readBytes()))
                output.writeByte(1)
                output.writeByte(value)
                repeat(101) { output.writeByte(0) }
                output.writeShort(0)
                output.writeInt(0)
            }
        }
    }

    private fun put(root: File, name: String, content: String) {
        root.resolve(name).apply {
            parentFile.mkdirs()
            writeText(content)
        }
    }
}
