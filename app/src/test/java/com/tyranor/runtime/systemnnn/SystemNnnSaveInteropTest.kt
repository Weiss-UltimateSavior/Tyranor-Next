package com.tyranor.runtime.systemnnn

import com.tyranor.next.core.game.save.GameSaveException
import com.tyranor.next.core.game.save.GameSaveManager
import com.tyranor.next.core.game.save.SaveErrorCode
import com.tyranor.next.core.game.save.SystemNnnCleanupQueue
import com.tyranor.next.core.game.save.SystemNnnSaveStore
import com.tyranor.next.core.game.save.sync.SaveSyncError
import com.tyranor.next.core.game.save.sync.SaveSyncException
import com.tyranor.next.core.game.storage.GameLibraryWriteGate
import com.tyranor.next.core.game.storage.SystemNnnCopyStore
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class SystemNnnSaveInteropTest {
    @get:Rule val temp = TemporaryFolder()

    private fun validateFixture(
        @Suppress("UNUSED_PARAMETER") stage: File,
        incoming: Map<String, File>,
        existing: Set<String>,
        checkCancelled: () -> Unit,
    ): Boolean {
        checkCancelled()
        return try { validateFixtureFiles(incoming, existing) }
        catch (_: IOException) { false }
    }

    @Test fun replacesOnlyManagedFilesAndKeepsTheSharedLock() {
        val game = temp.newFolder().canonicalFile
        val saves = game.resolve("sav").apply { check(mkdir()) }
        val store = SystemNnnSaveStore(game, saves, ::validateFixture)
        val pc = saves.resolve("sysfile3.org").apply { writeText("PC") }
        val temporary = saves.resolve("data0.sav.new").apply { writeText("temporary") }
        val retired = putLegacySlot(saves)
        val incoming = temp.newFolder().canonicalFile
        putLegacySlot(incoming)
        val story = putPcSlot(incoming, "data0.sav")
        val backup = putPcSlot(incoming, "data3.sav.bak")
        putChartNode(incoming, "emberfall.chart/chart1/slot0.dat")
        val highSlot = putPcSlot(incoming, "data118.sav")
        put(incoming, "emberfall.last-slot", "last selection")
        put(incoming, "emberfall.last-slot.new", "interrupted selection")
        put(incoming, "emberfall.slot-ui", "slot presentation")
        put(incoming, "emberfall.slot-ui.new", "interrupted presentation")
        put(incoming, "emberfall.slot-ui.bak", "unknown presentation backup")
        put(incoming, "data0118.sav", "noncanonical alias")
        put(incoming, "data4096.sav", "outside range")
        put(incoming, "sysfile3.org", "excluded")
        val metadataOnly = temp.newFolder().canonicalFile
        put(metadataOnly, "emberfall.slot-ui", "slot presentation only")
        val lock = saves.resolve("emberfall.lock").toPath()

        store.access {
            val fileKey = Files.readAttributes(lock, BasicFileAttributes::class.java).fileKey()
            val busy = runCatching { SystemNnnHost.lockSaveDirectory(game).close() }.exceptionOrNull()
            assertTrue(busy is SystemNnnHost.SaveBusyException)
            assertEquals(6, store.replace(incoming))
            assertEquals(6, store.files().size)
            assertArrayEquals(story.readBytes(), saves.resolve(story.name).readBytes())
            assertArrayEquals(backup.readBytes(), saves.resolve(backup.name).readBytes())
            assertFalse(incoming.resolve("data3.sav").exists())
            assertFalse(saves.resolve("data3.sav").exists())
            assertArrayEquals(highSlot.readBytes(), saves.resolve(highSlot.name).readBytes())
            assertEquals("last selection", saves.resolve("emberfall.last-slot").readText())
            assertFalse(saves.resolve("emberfall.last-slot.new").exists())
            assertEquals("slot presentation", saves.resolve("emberfall.slot-ui").readText())
            assertFalse(saves.resolve("emberfall.slot-ui.new").exists())
            assertFalse(saves.resolve("emberfall.slot-ui.bak").exists())
            assertFalse(saves.resolve("data0118.sav").exists())
            assertFalse(saves.resolve("data4096.sav").exists())
            assertEquals(fileKey, Files.readAttributes(lock, BasicFileAttributes::class.java).fileKey())
            val error = assertThrows(GameSaveException::class.java) { store.replace(metadataOnly) }
            assertEquals(SaveErrorCode.NO_FILES_IN_ZIP, error.code)
            assertArrayEquals(story.readBytes(), saves.resolve(story.name).readBytes())
            assertEquals(6, store.replace(null))
            assertTrue(store.files().isEmpty())
        }
        assertTrue(Files.isRegularFile(lock))
        assertEquals("PC", pc.readText())
        assertEquals("temporary", temporary.readText())
        assertTrue(retired.isFile)
    }

    @Test fun includesPcGlobalProgressAndKeepsItWhenAnOlderBackupOmitsIt() {
        val game = temp.newFolder().canonicalFile
        val saves = game.resolve("sav").apply { check(mkdir()) }
        val store = SystemNnnSaveStore(game, saves, ::validateFixture)
        val config = GameUiAssets.readConfig(game, "game")
        val state = GameProgress.State(
            intArrayOf(9), IntArray(1000), IntArray(2048), Array(102) { byteArrayOf() },
            IntArray(NativeGame.OPTIONAL_PROGRESS_WORDS), null, emptyList(),
        )
        PcSystemSave.open(game, saves, config, intArrayOf(1, 0)).write(state, intArrayOf())
        val current = saves.resolve("sysfile.sav")
        val original = current.readBytes()
        assertTrue(store.files().containsKey(current.name))
        val older = temp.newFolder()
        putProgress(older, 11)
        store.access { store.replace(older) }
        assertArrayEquals(original, current.readBytes())

        val newer = temp.newFolder()
        state.variables[5] = 1
        PcSystemSave.open(game, newer, config, intArrayOf(1, 0)).write(state, intArrayOf())
        val replacement = newer.resolve(current.name)
        store.access { store.replace(newer) }
        assertArrayEquals(replacement.readBytes(), current.readBytes())
        replacement.writeText("damaged")
        assertThrows(GameSaveException::class.java) { store.access { store.replace(newer) } }
        assertTrue(PcSystemSave.valid(current))
    }

    @Test fun usesCachedGlobalArchiveEvenWhenAnotherVersionExists() {
        val game = temp.newFolder().canonicalFile
        val saves = game.resolve("sav").apply { check(mkdir()) }
        val config = GameUiAssets.readConfig(game, "game")
        val state = GameProgress.State(
            intArrayOf(9), IntArray(1000), IntArray(2048), Array(102) { byteArrayOf() },
            IntArray(NativeGame.OPTIONAL_PROGRESS_WORDS), null, emptyList(),
        )
        PcSystemSave.open(game, saves, config, intArrayOf(1, 0)).write(state, intArrayOf())
        val previous = saves.resolve("sysfile.sav")
        val bytes = previous.readBytes()
        saves.resolve("sysfile.org").writeBytes(bytes)

        val selected = PcSystemSave.open(game, saves, config, intArrayOf(2, 0))
        assertFalse(selected.load().live)
        selected.write(state, intArrayOf())
        assertTrue(PcSystemSave.valid(saves.resolve("sysfile2.sav")))
        assertArrayEquals(bytes, previous.readBytes())
    }

    @Test fun rejectsIncompleteOrDamagedSnapshotsBeforeChangingLiveSaves() {
        val game = temp.newFolder().canonicalFile
        val saves = game.resolve("sav").apply { check(mkdir()) }
        val store = SystemNnnSaveStore(game, saves, ::validateFixture)
        val story = putPcSlot(saves, "data1.sav.bak")
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
        rejectsSnapshot { put(it, "data0.sav.thumb", "thumbnail") }
        rejectsSnapshot { putProgress(it, 11) }
        rejectsSnapshot { putLegacySlot(it) }
        rejectsSnapshot { put(it, "data0.sav", "damaged PC slot") }
        rejectsSnapshot {
            putPcSlot(it, "data0.sav")
            put(it, "data0.sav.bak", "damaged backup preferred by AtomicFile")
        }
        rejectsSnapshot {
            putPcSlot(it, "data0.sav")
            put(it, "emberfall.read", "damaged progress primary")
            put(it, "emberfall.read.bak", "damaged progress backup")
        }
    }

    @Test fun validatesStagedProgressPairsWithoutChangingEitherGeneration() {
        val incoming = temp.newFolder().canonicalFile
        val story = putPcSlot(incoming, "data0.sav")
        val primary = putProgress(incoming, 21)
        val backup = incoming.resolve("emberfall.read.bak").apply { writeText("damaged backup") }
        val files = listOf(story, primary, backup).associateBy { it.name }

        fun validatesUnchanged() {
            val before = files.mapValues { (_, file) -> file.readBytes() }
            assertTrue(validateFixtureFiles(files, emptySet()))
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
        val store = SystemNnnSaveStore(game, saves, ::validateFixture)
        val incoming = temp.newFolder().canonicalFile
        val backup = putPcSlot(incoming, "data3.sav.bak")
        store.access {
            assertEquals(1, store.replace(incoming))
            assertArrayEquals(backup.readBytes(), saves.resolve(backup.name).readBytes())
        }
        assertFalse(incoming.resolve("data3.sav").exists())
        assertFalse(saves.resolve("data3.sav").exists())
    }

    @Test fun replacementKeepsValidPrimaryProgressAndItsOwnExtension() {
        val game = temp.newFolder().canonicalFile
        val saves = game.resolve("sav").apply { check(mkdir()) }
        putProgress(saves, 3)
        val incoming = GameSaveManager.createZipStaging(temp.root)
        putPcSlot(incoming, "data0.sav")
        val primary = putProgress(incoming, 21)
        val backup = putProgress(incoming, 9, "emberfall.read.bak")
        putExtension(incoming.resolve("emberfall.read.ext"), primary to 21)
        putExtension(incoming.resolve("emberfall.read.ext.bak"), backup to 9)

        val store = SystemNnnSaveStore(game, saves, ::validateFixture)
        store.access {
            assertEquals(5, store.replace(incoming))
            val installed = checkNotNull(GameProgress(saves.resolve(primary.name)).read())
            assertArrayEquals(intArrayOf(21), installed.read)
            assertArrayEquals(byteArrayOf(21), installed.text[0])
            assertArrayEquals(backup.readBytes(), saves.resolve(backup.name).readBytes())
        }
    }

    @Test fun restoresGlobalProgressOnlyWhenTheDestinationHasNoStoryOrChart() {
        val game = temp.newFolder().canonicalFile
        val saves = game.resolve("sav").apply { check(mkdir()) }
        val store = SystemNnnSaveStore(game, saves, ::validateFixture)
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
        val store = SystemNnnSaveStore(game, saves, ::validateFixture)
        val stage = saves.resolve(SystemNnnHost.SAVE_MIGRATION_DIRECTORY)
        val restore = stage.resolve(SystemNnnHost.SAVE_RESTORE_DIRECTORY)
        put(restore, "before/data0.sav", "old")
        put(restore, "pending", "\u0001")
        put(saves, "data0.sav", "partial")
        put(saves, "emberfall.read", "partial")
        val legacy = temp.newFolder()
        val refused = runCatching { GameSavePaths.forGame(game).open(legacy).close() }.exceptionOrNull()
        assertTrue(refused is IOException)
        assertEquals("partial", saves.resolve("data0.sav").readText())
        assertTrue(restore.resolve("pending").isFile)

        store.recoverForLaunch()
        assertEquals("old", saves.resolve("data0.sav").readText())
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
        assertFalse(saves.resolve("emberfall.quick.sav").exists())
    }

    @Test fun ordinaryAccessClearsAnEmptyMigrationDirectory() {
        val game = temp.newFolder().canonicalFile
        val saves = game.resolve("sav").apply { check(mkdir()) }
        put(saves, "data0.sav", "saved")
        val stage = saves.resolve(SystemNnnHost.SAVE_MIGRATION_DIRECTORY).apply { check(mkdir()) }
        val store = SystemNnnSaveStore(game, saves, ::validateFixture)

        store.access {
            assertFalse(stage.exists())
            assertEquals("saved", store.files().getValue("data0.sav").readText())
        }
    }

    @Test fun failedReplacementRestoresTheFilesItActuallyBackedUp() {
        val game = temp.newFolder().canonicalFile
        val saves = game.resolve("sav").apply { check(mkdir()) }
        val original = linkedMapOf(
            "data0.sav" to "original slot",
            "emberfall.read" to "original progress",
            "emberfall.read.ext" to "original extension",
            "emberfall.chart/chart1/slot0.dat" to "original chart",
        )
        for ((name, content) in original) put(saves, name, content)
        val pc = saves.resolve("sysfile3.sav").apply { writeText("PC") }
        val incoming = temp.newFolder().canonicalFile
        val replacement = putPcSlot(incoming, "data0.sav")
        for (name in original.keys - setOf(replacement.name, "emberfall.read")) {
            if (SystemNnnHost.isChartNodeSave(name)) putChartNode(incoming, name)
            else put(incoming, name, "replacement $name")
        }
        putProgress(incoming, 34)
        putPcSlot(incoming, "data3.sav")
        var interrupted = false
        val store = SystemNnnSaveStore(game, saves, ::validateFixture) { source, target ->
            Files.move(source.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            if (!interrupted && target == saves.resolve("data0.sav")) {
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
            assertEquals(original + (pc.name to "PC"), store.files().mapValues { (_, file) -> file.readText() })
            assertEquals(lockKey, Files.readAttributes(lock, BasicFileAttributes::class.java).fileKey())
        }
        assertFalse(saves.resolve("data3.sav").exists())
        assertFalse(saves.resolve(SystemNnnHost.SAVE_MIGRATION_DIRECTORY).exists())
        assertEquals("PC", pc.readText())
    }

    @Test fun detectsAnUncommittedPcSlotWhenThePreviousFileStillExists() {
        val saves = temp.newFolder()
        val target = saves.resolve("data1.sav").apply { writeText("previous") }
        val pending = saves.resolve("data1.sav.new").apply { writeText("next") }

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
        val previous = putPcSlot(saves, "data0.sav")
        val incoming = temp.newFolder().canonicalFile
        val replacement = putPcSlot(incoming, "data1.sav")
        val store = SystemNnnSaveStore(game, saves, ::validateFixture)
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
        val previous = putPcSlot(saves, "data0.sav")
        val incoming = temp.newFolder().canonicalFile
        val replacement = putPcSlot(incoming, "data1.sav")
        val store = SystemNnnSaveStore(game, saves, ::validateFixture)
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
        val previous = putPcSlot(saves, "data0.sav")
        val incoming = temp.newFolder().canonicalFile
        val replacement = putPcSlot(incoming, "data1.sav")
        val store = SystemNnnSaveStore(game, saves, ::validateFixture)

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

    @Test fun importedCopyMigratesGlobalProgressAndLeavesRetiredSlotsInPlace() {
        val original = temp.newFolder().canonicalFile
        val imported = temp.newFolder().canonicalFile
        val legacy = temp.newFolder()
        val oldKey = UUID.nameUUIDFromBytes(original.path.toByteArray(Charsets.UTF_8)).toString()
        val source = legacy.resolve(oldKey + ".resume")
        source.writeText("old private autosave")
        val high = legacy.resolve(oldKey + ".slot118.sav.bak").apply { writeText("old high slot backup") }
        val selected = legacy.resolve(oldKey + ".last-slot").apply { writeText("old last selection") }

        val progress = putProgress(legacy, 13, oldKey + ".read")
        GameSavePaths.forGame(imported).open(legacy, original).use {
            assertFalse(imported.resolve("sav/emberfall.resume").exists())
            assertFalse(imported.resolve("sav/emberfall.slot118.sav").exists())
            assertFalse(imported.resolve("sav/emberfall.last-slot").exists())
            assertArrayEquals(progress.readBytes(), imported.resolve("sav/emberfall.read").readBytes())
        }
        assertTrue(source.isFile && high.isFile && selected.isFile)
        assertFalse(original.resolve("sav").exists())
    }

    @Test fun normalizesSourceIdentitiesConsistently() {
        val cases = mapOf(
            "primary:Games/Sample" to "primary:Games/Sample",
            "PRIMARY:Games/Sample" to "primary:Games/Sample",
            "0A1B-2C3D:roms/game" to "0A1B-2C3D:roms/game",
            "primary:" to "primary:",
        )
        for ((raw, expected) in cases) {
            assertEquals(expected, SystemNnnCopyStore.normalizeIdentity(raw))
            assertEquals(
                SystemNnnCopyStore.key(expected),
                SystemNnnCopyStore.key(SystemNnnCopyStore.normalizeIdentity(raw)),
            )
        }
        for (invalid in listOf("", ":missing", "primary:../escape", "primary:a//b", "primary:a\\b")) {
            val failure = assertThrows(SystemNnnCopyStore.Failure::class.java) {
                SystemNnnCopyStore.normalizeIdentity(invalid)
            }
            assertEquals(SystemNnnCopyStore.Reason.INVALID_SOURCE, failure.reason)
        }
    }

    @Test fun clearingOneCopyLeavesOtherCopiesUntouched() {
        val base = temp.newFolder("systemnnn-imports").canonicalFile
        val source = "primary:Games/First"
        val other = "primary:Games/Second"
        for ((identity, script) in listOf(source to "first script", other to "second script")) {
            val entry = base.resolve(SystemNnnCopyStore.key(identity))
            put(entry, "source.id", identity)
            put(entry, "game/spt/sys.spt", script)
            put(entry, "game/sav/sysfile3.sav", "PC " + identity)
        }
        assertTrue(SystemNnnCopyStore.clearResources(base, source))
        assertFalse(SystemNnnCopyStore.hasImportedResources(SystemNnnCopyStore.committedRoot(base, source)!!))
        val untouched = SystemNnnCopyStore.committedRoot(base, other)!!
        assertTrue(SystemNnnCopyStore.hasImportedResources(untouched))
        assertTrue(SystemNnnCopyStore.readableBootstrap(untouched))
        assertEquals("PC " + other, untouched.resolve("sav/sysfile3.sav").readText())
        assertEquals("PC " + source, SystemNnnCopyStore.committedRoot(base, source)!!.resolve("sav/sysfile3.sav").readText())
    }

    @Test fun clearingImportedResourcesKeepsTheOriginalSaveDirectoryAndLock() {
        val base = temp.newFolder("systemnnn-imports").canonicalFile
        val source = "primary:Games/Sample"
        val entry = base.resolve(SystemNnnCopyStore.key(source))
        val game = entry.resolve("game")
        put(entry, "source.id", source)
        put(game, "spt/sys.spt", "script")
        put(game, "graphics.dat", "resources")
        put(game, "SaV/emberfall.resume", "autosave")
        put(game, "SaV/sysfile3.sav", "PC")
        val lock = game.resolve("SaV/emberfall.lock").toPath()
        val lockIdentity = SystemNnnHost.lockSaveDirectory(game).use {
            val refused = runCatching { SystemNnnCopyStore.clearResources(base, source) }.exceptionOrNull()
            assertTrue(refused is SystemNnnHost.SaveBusyException)
            assertTrue(SystemNnnCopyStore.readableBootstrap(game))
            Files.readAttributes(lock, BasicFileAttributes::class.java).fileKey()
        }

        assertTrue(SystemNnnCopyStore.clearResources(base, source))
        assertFalse(SystemNnnCopyStore.hasImportedResources(game))
        assertEquals(game, SystemNnnCopyStore.committedRoot(base, source))
        assertTrue(SystemNnnCopyStore.resourcesEvicted(game))
        put(game, "spt/sys.spt", "partial publish")
        assertFalse(SystemNnnCopyStore.readableBootstrap(game))

    }

    @Test fun cleanupIntentSurvivesRestartAndAnOlderDrainCannotEraseExplicitRetirement() {
        val directory = temp.newFolder("cleanup")
        val legacyUri = "primary:Games/Legacy"
        val uri = "primary:Games/Explicit"
        fun queue() = SystemNnnCleanupQueue(directory) { listOf(legacyUri) }

        queue().add(listOf(uri), retireCopy = false)
        val oldAttempt = queue().pending().last()
        queue().add(listOf(uri), retireCopy = true)
        queue().add(listOf(uri), retireCopy = false)
        queue().complete(oldAttempt)
        val pending = queue().pending()
        assertEquals(listOf(SystemNnnCleanupQueue.Target(legacyUri, false), SystemNnnCleanupQueue.Target(uri, true)), pending)

        pending.forEach { queue().complete(it) }
        assertTrue(queue().pending().isEmpty())
    }

    @Test fun cleanupQueueWriteFailureReportsTypedErrorAndCanBeRetried() {
        val directory = temp.newFile("cleanup")
        val uri = "primary:Games/Explicit"
        val queue = SystemNnnCleanupQueue(directory)
        val failure = assertThrows(SaveSyncException::class.java) { queue.add(listOf(uri), retireCopy = true) }
        assertEquals(SaveSyncError.LOCAL_IO, failure.code)
        assertTrue(directory.isFile)
        assertTrue(directory.delete())
        queue.add(listOf(uri), retireCopy = true)
        assertEquals(listOf(SystemNnnCleanupQueue.Target(uri, true)), SystemNnnCleanupQueue(directory).pending())
    }

    @Test fun retiredCopyRemainsBackedUpWithoutSupersedingAnAuthorizedReaddedRoot() {
        val base = temp.newFolder("systemnnn-imports").canonicalFile
        val identity = "primary:Games/Sample"
        val entry = base.resolve(SystemNnnCopyStore.key(identity))
        val copy = entry.resolve("game")
        put(entry, "source.id", identity)
        put(copy, "spt/sys.spt", "old resources")
        put(copy, "sav/data1.sav", "old saves")
        assertTrue(SystemNnnCopyStore.clearResources(base, identity))
        assertEquals(copy, SystemNnnCopyStore.activeRoot(SystemNnnCopyStore.committedRoot(base, identity)))

        val queueDirectory = temp.newFolder("cleanup")
        SystemNnnCleanupQueue(queueDirectory).add(listOf(identity), retireCopy = true)
        val gate = GameLibraryWriteGate()
        val pending = SystemNnnCleanupQueue(queueDirectory).pending().single()
        val retire = { SystemNnnCopyStore.clearResources(base, identity, pending.retireCopy) }
        var cachedReference = false
        var committedReference = true
        val referenced = { cachedReference || committedReference }
        assertNull(gate.evictIfUnused(referenced, retire)) // Removal was published in memory only.
        committedReference = false
        cachedReference = true
        assertNull(gate.evictIfUnused(referenced, retire)) // A same-source alias was registered.
        assertEquals(copy, SystemNnnCopyStore.activeRoot(SystemNnnCopyStore.committedRoot(base, identity)))
        assertEquals(listOf(pending), SystemNnnCleanupQueue(queueDirectory).pending())
        cachedReference = false
        SystemNnnHost.lockSaveDirectory(copy).use {
            assertThrows(SystemNnnHost.SaveBusyException::class.java) { gate.evictIfUnused(referenced, retire) }
        }
        assertEquals(false, gate.evictIfUnused(referenced, retire))
        assertEquals(false, gate.evictIfUnused(referenced, retire)) // Resume after retirement and before dequeue.
        SystemNnnCleanupQueue(queueDirectory).complete(pending)

        assertNull(SystemNnnCopyStore.activeRoot(SystemNnnCopyStore.committedRoot(base, identity)))
        assertEquals(copy, SystemNnnCopyStore.committedRoot(base, identity))
        assertEquals("old saves", copy.resolve("sav/data1.sav").readText())
        assertTrue(SystemNnnCleanupQueue(queueDirectory).pending().isEmpty())
    }

    @Test fun pendingSaveRecoveryKeepsCopyAndCleanupRequestUntilRetry() {
        val base = temp.newFolder("systemnnn-imports").canonicalFile
        val source = "primary:Games/Pending"
        val entry = base.resolve(SystemNnnCopyStore.key(source))
        val copy = entry.resolve("game")
        val saves = copy.resolve("SaV")
        val migration = SystemNnnHost.SAVE_MIGRATION_DIRECTORY
        val restore = SystemNnnHost.SAVE_RESTORE_DIRECTORY
        put(entry, "source.id", source)
        put(copy, "spt/sys.spt", "copy resource")
        put(saves, "data1.sav", "partially restored")
        put(saves, "$migration/$restore/before/data1.sav", "old save")
        put(saves, "$migration/$restore/pending", "")
        val queue = SystemNnnCleanupQueue(temp.newFolder("cleanup"))
        queue.add(listOf(source), retireCopy = true)
        fun drain() {
            val target = queue.pending().single()
            SystemNnnCopyStore.clearResources(base, source, target.retireCopy)
            queue.complete(target)
        }

        val interrupted = assertThrows(GameSaveException::class.java) { drain() }
        assertEquals(SaveErrorCode.SAVE_SELECTION_INCOMPLETE, interrupted.code)
        assertEquals(copy, SystemNnnCopyStore.activeRoot(SystemNnnCopyStore.committedRoot(base, source)))
        assertTrue(SystemNnnCopyStore.hasImportedResources(copy))
        assertFalse(entry.resolve("resources-evicted").exists())
        assertEquals("partially restored", saves.resolve("data1.sav").readText())
        assertTrue(queue.pending().single().retireCopy)

        SystemNnnSaveStore(copy, saves, ::validateFixture).recoverForLaunch()
        assertEquals("old save", saves.resolve("data1.sav").readText())
        drain()
        assertTrue(queue.pending().isEmpty())
        assertNull(SystemNnnCopyStore.activeRoot(SystemNnnCopyStore.committedRoot(base, source)))
        assertEquals("old save", saves.resolve("data1.sav").readText())
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

    @Test fun unreadableProgressUsesBackupWithoutRewritingEitherFile() {
        val saves = temp.newFolder()
        val primary = saves.resolve("emberfall.read").apply { check(mkdir()) }
        val backup = putProgress(saves, 42, "emberfall.read.bak")
        val before = backup.readBytes()
        val progress = GameProgress(primary)

        assertArrayEquals(intArrayOf(42), checkNotNull(progress.read()).read)
        val unavailable = assertThrows(IOException::class.java) { progress.write(progressState(43)) }
        assertFalse(unavailable is GameProgress.InvalidBase)
        assertTrue(primary.isDirectory)
        assertArrayEquals(before, backup.readBytes())
        assertFalse(saves.listFiles().orEmpty().any { ".corrupt-" in it.name })
    }

    @Test fun retiredArchivesAreRejectedAndOldRollbackPreservesPcSlots() {
        val game = temp.newFolder().canonicalFile
        val saves = game.resolve("sav").apply { check(mkdir()) }
        val pc = putPcSlot(saves, "data3.sav")
        val onDisk = putLegacySlot(saves)
        val stage = temp.newFolder()
        val legacy = putLegacySlot(stage)
        val store = SystemNnnSaveStore(game, saves, ::validateFixture)

        assertTrue(SystemNnnHost.isManagedSaveFile(pc.toPath().fileName))
        assertFalse(SystemNnnHost.isManagedSaveFile(legacy.toPath().fileName))
        val failure = assertThrows(GameSaveException::class.java) { store.access { store.replace(stage) } }
        assertEquals(SaveErrorCode.NO_FILES_IN_ZIP, failure.code)
        assertArrayEquals(PC_SLOT_FIXTURE, pc.readBytes())
        assertArrayEquals(legacy.readBytes(), onDisk.readBytes())

        val restore = saves.resolve(SystemNnnHost.SAVE_MIGRATION_DIRECTORY)
            .resolve(SystemNnnHost.SAVE_RESTORE_DIRECTORY)
        put(restore, "before/emberfall.sav", "retired rollback slot")
        put(restore, "pending", "")
        check(onDisk.delete())
        store.recoverForLaunch()
        assertArrayEquals(PC_SLOT_FIXTURE, pc.readBytes())
        assertEquals("retired rollback slot", onDisk.readText())
        assertFalse(store.files().containsKey(onDisk.name))
    }

    @Test fun unavailableSnapshotValidatorLeavesLiveSavesUntouched() {
        val game = temp.newFolder().canonicalFile
        val saves = game.resolve("sav").apply { check(mkdir()) }
        val current = putPcSlot(saves, "data0.sav")
        val old = current.readBytes()
        val stage = GameSaveManager.createZipStaging(temp.root)
        putPcSlot(stage, "data1.sav")
        val store = SystemNnnSaveStore(game, saves, validateSnapshot = { _, _, _, _ ->
            throw IOException("Validator disconnected")
        })

        val failure = assertThrows(GameSaveException::class.java) { store.access { store.replace(stage) } }
        assertEquals(SaveErrorCode.SAVE_DIR_UNAVAILABLE, failure.code)
        assertArrayEquals(old, current.readBytes())
        assertFalse(saves.resolve("emberfall.migrate").exists())
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
        val archived = saves.resolve("emberfall.read.bak.previous")
        assertArrayEquals(old, archived.readBytes())

        store.write(pending)
        val recovered = checkNotNull(store.read())
        assertArrayEquals(intArrayOf(34), recovered.read)
        assertArrayEquals(byteArrayOf(34), recovered.text[0])
    }

    @Test fun legacyProgressMigrationUsesTheValidPrimaryOrMatchingBackupWithoutMutatingTheSource() {
        for (damagePrimary in listOf(false, true)) {
            val game = temp.newFolder().canonicalFile
            val legacy = temp.newFolder()
            val key = UUID.nameUUIDFromBytes(game.path.toByteArray(Charsets.UTF_8)).toString()
            val primary = legacy.resolve("$key.read")
            GameProgress(primary).write(progressState(21, 21))
            val old = legacy.resolve("old.read")
            GameProgress(old).write(progressState(9, 9))
            Files.move(old.toPath(), legacy.resolve("$key.read.bak").toPath())
            Files.move(legacy.resolve("old.read.ext").toPath(), legacy.resolve("$key.read.ext.bak").toPath())
            if (damagePrimary) primary.writeText("damaged")
            val source = legacy.listFiles()!!.associate { it.name to it.readBytes() }

            GameSavePaths.forGame(game).open(legacy, game).use {
                val progress = checkNotNull(GameProgress(game.resolve("sav/emberfall.read")).read())
                val expected = if (damagePrimary) 9 else 21
                assertArrayEquals(intArrayOf(expected), progress.read)
                assertArrayEquals(byteArrayOf(expected.toByte()), progress.text[0])
            }
            source.forEach { (name, bytes) -> assertArrayEquals(bytes, legacy.resolve(name).readBytes()) }
        }
    }

    @Test fun deletingSavesAlsoRemovesRecognizedRetiredBackups() {
        val game = temp.newFolder().canonicalFile
        val saves = game.resolve("sav").apply { check(mkdir()) }
        put(saves, "emberfall.read.bak.previous", "retired")
        put(saves, "emberfall.read.ext.bak.previous-${UUID.randomUUID()}", "older retired")
        val corrupt = saves.resolve("emberfall.read.corrupt-${UUID.randomUUID()}").apply { writeText("quarantined") }
        val store = SystemNnnSaveStore(game, saves, ::validateFixture)
        assertEquals(2, store.access { store.replace(null) })
        assertTrue(corrupt.isFile)
        assertEquals(0, store.access { store.replace(null) })
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

    @Test fun failedQuarantineRestoresFilesMovedEarlier() {
        val saves = temp.newFolder()
        val base = saves.resolve("emberfall.read").apply { writeText("truncated base") }
        val backup = saves.resolve("emberfall.read.bak").apply { writeText("truncated backup") }
        val extension = saves.resolve("emberfall.read.ext").apply { writeText("progress extension") }
        val original = listOf(base, backup, extension).associateWith { it.readBytes() }
        val failure = IOException("cannot isolate the backup")

        val thrown = assertThrows(IOException::class.java) {
            GameProgress.recoverCorruptBase(base) { from, to ->
                if (from == backup.toPath()) {
                    assertFalse(extension.exists())
                    throw failure
                }
                Files.move(from, to, ATOMIC_MOVE)
            }
        }
        assertSame(failure, thrown)
        assertEquals(original.keys.map(File::getName).toSet(), saves.list()!!.toSet())
        for ((file, bytes) in original) assertArrayEquals(bytes, file.readBytes())
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

    @Test fun chartOwnershipIsBoundedAndSeparateFromLegacyMigration() {
        for (name in listOf("mita.dat", "tsuka.dat", "mushiex/mita.dat", "mushiex/tsuka.dat",
            "mushiex/chart1/slot1001.dat", "mushiex/chart9/slot9999.dat", "emberfall.chart/chart32/slot262143.dat")) {
            assertTrue(name, SystemNnnHost.isManagedSaveFile(Paths.get(name)))
        }
        for (name in listOf("mushiex/chart10/slot9999.dat", "mushiex/chart1/slot10000.dat",
            "mushiex/chart01/slot1001.dat", "mushiex/chart1/slot01001.dat", "mushiex/chart1/slot1001.dat.new",
            "mushiex/chart1/extra/slot1001.dat", "emberfall.chart/chart33/slot0.dat", "emberfall.chart/chart1/slot262144.dat",
            "mushiex/../mita.dat", "../mita.dat")) {
            assertFalse(name, SystemNnnHost.isManagedSaveFile(Paths.get(name)))
        }
        assertTrue(SystemNnnHost.isManagedSaveDirectory(Paths.get("mushiex/chart9")))
        assertFalse(SystemNnnHost.isManagedSaveDirectory(Paths.get("mushiex/chart10")))
        assertFalse(LegacySaveMigration.ownedFile(Paths.get("mushiex/chart1/slot1001.dat")))
        assertFalse(LegacySaveMigration.chartDirectory(Paths.get("mushiex")))
    }

    @Test fun chartSnapshotEnumerationMatchesTheRecursiveValidator() {
        val game = temp.newFolder().canonicalFile
        val saves = game.resolve("sav").apply { check(mkdir()) }
        val store = SystemNnnSaveStore(game, saves, ::validateFixture)
        val expected = putChartFlags(saves, false) + putChartFlags(saves, true)
        putChartNode(saves, "mushiex/chart1/slot1001.dat")
        putChartNode(saves, "emberfall.chart/chart1/slot0.dat")
        put(saves, "mushiex/chart1/slot1001.dat.new", "pending")
        put(saves, "mushiex/chart01/slot1001.dat", "alias")
        val files = store.files()
        assertEquals(expected.keys + setOf("mushiex/chart1/slot1001.dat", "emberfall.chart/chart1/slot0.dat"), files.keys)
        assertEquals(files, SystemNnnSaveValidationService.stagedFiles(saves.toPath()))
        for ((name, bytes) in expected) assertArrayEquals(bytes, files.getValue(name).readBytes())
    }

    @Test fun chartGlobalsDoNotAuthorizeOverwritingAnExistingNode() {
        val game = temp.newFolder().canonicalFile
        val saves = game.resolve("sav").apply { check(mkdir()) }
        val node = putChartNode(saves, "mushiex/chart1/slot1001.dat")
        val store = SystemNnnSaveStore(game, saves, ::validateFixture)
        val incoming = temp.newFolder().canonicalFile
        putChartFlags(incoming, true)
        assertTrue(SystemNnnHost.requiresStorySnapshot(store.files().keys))
        assertThrows(GameSaveException::class.java) { store.access { store.replace(incoming) } }
        assertArrayEquals(CHART_NODE_FIXTURE, node.readBytes())
        val blank = temp.newFolder().canonicalFile
        val emptySaves = blank.resolve("sav").apply { check(mkdir()) }
        val emptyStore = SystemNnnSaveStore(blank, emptySaves, ::validateFixture)
        emptyStore.access { assertEquals(2, emptyStore.replace(incoming)) }
        assertEquals(10004, emptySaves.resolve("mushiex/mita.dat").length().toInt())
    }

    @Test fun chartValidationRejectsBadNodesAndLengthsBeforeAnyReplacement() {
        val game = temp.newFolder().canonicalFile
        val saves = game.resolve("sav").apply { check(mkdir()) }
        val current = putChartNode(saves, "mushiex/chart1/slot1001.dat")
        val store = SystemNnnSaveStore(game, saves, ::validateFixture)
        val incoming = temp.newFolder().canonicalFile
        putPcSlot(incoming, "data0.sav")
        val node = putChartNode(incoming, "mushiex/chart1/slot1001.dat")
        for (invalid in listOf(byteArrayOf(0), CHART_NODE_FIXTURE + byteArrayOf(0), ByteArray(2 * 1024 * 1024 + 1))) {
            node.writeBytes(invalid)
            assertThrows(GameSaveException::class.java) { store.access { store.replace(incoming) } }
            assertArrayEquals(CHART_NODE_FIXTURE, current.readBytes())
        }
        node.writeBytes(CHART_NODE_FIXTURE)
        putChartFlags(incoming, true)
        incoming.resolve("mushiex/mita.dat").writeBytes(ByteArray(10000))
        assertThrows(GameSaveException::class.java) { store.access { store.replace(incoming) } }
        assertArrayEquals(CHART_NODE_FIXTURE, current.readBytes())
        assertFalse(saves.resolve(SystemNnnHost.SAVE_MIGRATION_DIRECTORY).exists())
    }

    @Test fun chartValidatorsReceiveEveryRelativePathWithoutTreatingGlobalsAsNodes() {
        val directory = temp.newFolder().canonicalFile
        putChartFlags(directory, true)
        putChartNode(directory, "mushiex/chart1/slot1001.dat")
        val files = SystemNnnSaveValidationService.stagedFiles(directory.toPath())
        val inspected = linkedSetOf<String>()
        assertTrue(SystemNnnHost.canReplaceSaveSnapshot(files, true, { false }) { path, _ ->
            inspected.add(path)
            true
        })
        assertEquals(files.keys, inspected)
        assertFalse(SystemNnnHost.canReplaceSaveSnapshot(files, true, { false }) { path, _ ->
            !SystemNnnHost.isChartNodeSave(path)
        })
        val aliased = files + ("MUSHIEX/MITA.DAT" to files.getValue("mushiex/mita.dat"))
        assertFalse(SystemNnnHost.canReplaceSaveSnapshot(aliased, true, { false }) { _, _ -> true })
    }

    @Test fun olderBackupsPreserveAbsentChartFamiliesButExplicitClearDeletesThem() {
        val game = temp.newFolder().canonicalFile
        val saves = game.resolve("sav").apply { check(mkdir()) }
        val original = putChartFlags(saves, false) + putChartFlags(saves, true)
        putChartNode(saves, "mushiex/chart1/slot1001.dat")
        val modernNode = putChartNode(saves, "emberfall.chart/chart1/slot0.dat")
        val store = SystemNnnSaveStore(game, saves, ::validateFixture)
        val older = temp.newFolder().canonicalFile
        putPcSlot(older, "data0.sav")
        val state = GameProgress.State(intArrayOf(), IntArray(1000), IntArray(2048), Array(102) { byteArrayOf() },
            IntArray(NativeGame.OPTIONAL_PROGRESS_WORDS), null, emptyList())
        PcSystemSave.open(game, older, GameUiAssets.readConfig(game, "game"), intArrayOf(3, 0)).write(state, intArrayOf())
        store.access { store.replace(older) }
        for ((name, bytes) in original) assertArrayEquals(bytes, saves.resolve(name).readBytes())
        assertArrayEquals(CHART_NODE_FIXTURE, saves.resolve("mushiex/chart1/slot1001.dat").readBytes())
        assertFalse(modernNode.exists()) // Modern nodes were already part of older backups.

        val modern = temp.newFolder().canonicalFile
        putPcSlot(modern, "data0.sav")
        val replacement = putChartFlags(modern, false, 7)
        store.access { store.replace(modern) }
        for ((name, bytes) in replacement) assertArrayEquals(bytes, saves.resolve(name).readBytes())
        for ((name, bytes) in original.filterKeys { it.startsWith("mushiex/") })
            assertArrayEquals(bytes, saves.resolve(name).readBytes())

        val legacy = temp.newFolder().canonicalFile
        putChartFlags(legacy, true, 9)
        putChartNode(legacy, "mushiex/chart1/slot1002.dat")
        store.access { store.replace(legacy) }
        assertFalse(saves.resolve("mushiex/chart1/slot1001.dat").exists())
        assertArrayEquals(CHART_NODE_FIXTURE, saves.resolve("mushiex/chart1/slot1002.dat").readBytes())
        for ((name, bytes) in replacement) assertArrayEquals(bytes, saves.resolve(name).readBytes())
        store.access { store.replace(null) }
        assertTrue(store.files().isEmpty())
        for (name in original.keys + "mushiex/chart1/slot1002.dat") assertFalse(saves.resolve(name).exists())
    }

    @Test fun olderRollbackMarkersPreserveChartFamiliesTheyNeverBackedUp() {
        for (version in 1..2) {
            val game = temp.newFolder().canonicalFile
            val saves = game.resolve("sav").apply { check(mkdir()) }
            val flags = putChartFlags(saves, false) + putChartFlags(saves, true)
            putChartNode(saves, "mushiex/chart1/slot1001.dat")
            put(saves, "emberfall.chart/chart1/slot0.dat", "partial modern node")
            val addedModernNode = putChartNode(saves, "emberfall.chart/chart1/slot1.dat")
            val restore = saves.resolve("${SystemNnnHost.SAVE_MIGRATION_DIRECTORY}/${SystemNnnHost.SAVE_RESTORE_DIRECTORY}")
            put(restore, "before/data0.sav", "old slot")
            put(restore, "before/emberfall.chart/chart1/slot0.dat", "original modern node")
            restore.resolve("pending").writeBytes(byteArrayOf(version.toByte()))
            put(saves, "data0.sav", "partial slot")
            SystemNnnSaveStore(game, saves, ::validateFixture).recoverForLaunch()
            assertEquals("old slot", saves.resolve("data0.sav").readText())
            for ((name, bytes) in flags) assertArrayEquals(bytes, saves.resolve(name).readBytes())
            assertArrayEquals(CHART_NODE_FIXTURE, saves.resolve("mushiex/chart1/slot1001.dat").readBytes())
            assertEquals("original modern node", saves.resolve("emberfall.chart/chart1/slot0.dat").readText())
            assertFalse(addedModernNode.exists())
            assertFalse(restore.exists())
        }
    }

    @Test fun versionThreeRollbackRestoresOldChartBytesAndRemovesNewNodes() {
        val game = temp.newFolder().canonicalFile
        val saves = game.resolve("sav").apply { check(mkdir()) }
        putChartFlags(saves, false)
        putChartFlags(saves, true)
        putChartNode(saves, "mushiex/chart1/slot1001.dat")
        val incoming = temp.newFolder().canonicalFile
        putChartFlags(incoming, false, 4)
        putChartFlags(incoming, true, 5)
        val newNode = "mushiex/chart1/slot1002.dat"
        putChartNode(incoming, newNode)
        var interrupted = false
        val store = SystemNnnSaveStore(game, saves, ::validateFixture) { source, target ->
            Files.move(source.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            if (!interrupted && target == saves.resolve(newNode)) {
                interrupted = true
                val marker = saves.resolve("${SystemNnnHost.SAVE_MIGRATION_DIRECTORY}/${SystemNnnHost.SAVE_RESTORE_DIRECTORY}/pending")
                assertArrayEquals(byteArrayOf(3), marker.readBytes())
                throw IOException("Interrupted after publishing a chart node")
            }
        }
        val original = store.files().mapValues { it.value.readBytes() }
        assertThrows(GameSaveException::class.java) { store.access { store.replace(incoming) } }
        assertTrue(interrupted)
        assertEquals(original.keys, store.files().keys)
        for ((name, bytes) in original) assertArrayEquals(bytes, saves.resolve(name).readBytes())
        assertFalse(saves.resolve(newNode).exists())
        assertFalse(saves.resolve(SystemNnnHost.SAVE_MIGRATION_DIRECTORY).exists())
    }

    @Test fun chartReplacementKeepsExistingCaseSpellingAndPublishedFiles() {
        val game = temp.newFolder().canonicalFile
        val saves = game.resolve("sav").apply { check(mkdir()) }
        val node = put(saves, "MUSHIEX/CHART1/SLOT1001.DAT", "original node")
        val flags = put(saves, "MUSHIEX/MITA.DAT", "").apply { writeBytes(ByteArray(10004) { 1 }) }
        val incoming = temp.newFolder().canonicalFile
        val replacement = putChartFlags(incoming, true, 7)
        putChartNode(incoming, "mushiex/chart1/slot1001.dat")
        putChartNode(incoming, "mushiex/chart1/slot1002.dat")
        val store = SystemNnnSaveStore(game, saves, ::validateFixture)

        store.access { store.replace(incoming) }

        assertArrayEquals(CHART_NODE_FIXTURE, node.readBytes())
        assertArrayEquals(replacement.getValue("mushiex/mita.dat"), flags.readBytes())
        assertArrayEquals(CHART_NODE_FIXTURE, saves.resolve("MUSHIEX/CHART1/slot1002.dat").readBytes())
        assertEquals(listOf("MUSHIEX"), saves.listFiles()!!.filter { it.name.equals("mushiex", true) }.map { it.name })
        assertEquals(listOf("CHART1"), saves.resolve("MUSHIEX").listFiles()!!
            .filter { it.name.equals("chart1", true) }.map { it.name })
        assertFalse(saves.resolve(SystemNnnHost.SAVE_MIGRATION_DIRECTORY).exists())
    }

    @Test fun chartRollbackKeepsCaseAliasedOriginalsAfterPublishing() {
        val game = temp.newFolder().canonicalFile
        val saves = game.resolve("sav").apply { check(mkdir()) }
        put(saves, "MUSHIEX/CHART1/SLOT1001.DAT", "original node")
        put(saves, "MUSHIEX/MITA.DAT", "").writeBytes(ByteArray(10004) { 1 })
        val incoming = temp.newFolder().canonicalFile
        putChartFlags(incoming, true, 7)
        putChartNode(incoming, "mushiex/chart1/slot1001.dat")
        putChartNode(incoming, "mushiex/chart1/slot1002.dat")
        var interrupted = false
        val store = SystemNnnSaveStore(game, saves, ::validateFixture) { source, target ->
            Files.move(source.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            if (!interrupted && target.name.equals("slot1001.dat", true)) {
                interrupted = true
                throw IOException("Interrupted after publishing a case-aliased node")
            }
        }
        val original = store.files().mapValues { it.value.readBytes() }

        assertThrows(GameSaveException::class.java) { store.access { store.replace(incoming) } }

        assertTrue(interrupted)
        assertEquals(original.keys, store.files().keys)
        for ((name, bytes) in original) assertArrayEquals(bytes, saves.resolve(name).readBytes())
        assertFalse(saves.resolve("MUSHIEX/CHART1/slot1002.dat").exists())
        assertFalse(saves.resolve(SystemNnnHost.SAVE_MIGRATION_DIRECTORY).exists())
    }

    @Test fun chartDirectoryCaseCollisionsFailBeforeChangingLiveFiles() {
        for (collisionInSource in listOf(false, true)) {
            val game = temp.newFolder().canonicalFile
            val saves = game.resolve("sav").apply { check(mkdir()) }
            val incoming = temp.newFolder().canonicalFile
            putPcSlot(incoming, "data0.sav")
            val source = if (collisionInSource) incoming else saves
            source.resolve("mushiex").mkdir()
            val alias = source.resolve("MUSHIEX")
            org.junit.Assume.assumeTrue("Requires a case-sensitive filesystem", alias.mkdir())
            val lower = putChartNode(source, "mushiex/chart1/slot1001.dat")
            val upper = putChartNode(source, "MUSHIEX/chart1/slot1002.dat")
            val existing = put(saves, "data0.sav", "original slot")
            val store = SystemNnnSaveStore(game, saves, ::validateFixture)

            assertThrows(GameSaveException::class.java) { store.access { store.replace(incoming) } }

            assertEquals("original slot", existing.readText())
            assertArrayEquals(CHART_NODE_FIXTURE, lower.readBytes())
            assertArrayEquals(CHART_NODE_FIXTURE, upper.readBytes())
            assertFalse(saves.resolve(SystemNnnHost.SAVE_MIGRATION_DIRECTORY).exists())
        }
    }

    @Test fun chartPathsRejectUnicodeLookalikesAndNeverOverwriteTheirFiles() {
        for (name in listOf("mush\u0131ex", "mu\u017fhiex")) {
            assertFalse(SystemNnnHost.isManagedSaveDirectory(Paths.get(name)))
            assertFalse(SystemNnnHost.isManagedSaveFile(Paths.get("$name/mita.dat")))
            assertFalse(SystemNnnHost.isManagedSaveFile(Paths.get("$name/chart1/slot1001.dat")))
        }
        for (name in listOf("m\u0131ta.dat", "t\u017fuka.dat", "tsu\u212aa.dat")) {
            assertFalse(SystemNnnHost.isManagedSaveFile(Paths.get(name)))
            assertFalse(SystemNnnHost.isManagedSaveFile(Paths.get("mushiex/$name")))
        }
        val game = temp.newFolder().canonicalFile
        val saves = game.resolve("sav").apply { check(mkdir()) }
        val untouched = listOf(
            put(saves, "mush\u0131ex/chart1/slot1001.dat", "unmanaged dotless-i node"),
            put(saves, "mushiex/tsu\u212aa.dat", "unmanaged Kelvin-sign flags"),
        )
        val original = untouched.associateWith(File::readBytes)
        val incoming = temp.newFolder().canonicalFile
        putChartFlags(incoming, true)
        putChartNode(incoming, "mushiex/chart1/slot1001.dat")
        val store = SystemNnnSaveStore(game, saves, ::validateFixture)
        // A volume may itself alias Unicode spellings. Either retain that unowned entry
        // and reject the target, or commit to a distinct ASCII path; never overwrite it.
        val failure = runCatching { store.access { store.replace(incoming) } }.exceptionOrNull()
        if (failure != null) assertTrue(failure is GameSaveException)
        else assertArrayEquals(CHART_NODE_FIXTURE, saves.resolve("mushiex/chart1/slot1001.dat").readBytes())
        for ((file, bytes) in original) assertArrayEquals(bytes, file.readBytes())
        assertFalse(saves.resolve(SystemNnnHost.SAVE_MIGRATION_DIRECTORY).exists())
    }

    @Test fun chartSnapshotNeverTraversesSymbolicDirectoriesOrFiles() {
        val stage = temp.newFolder().canonicalFile
        val outside = temp.newFolder().canonicalFile
        putChartFlags(outside, false)
        try { Files.createSymbolicLink(stage.resolve("mushiex").toPath(), outside.toPath()) }
        catch (failure: IOException) { assumeNoException(failure); return }
        catch (failure: UnsupportedOperationException) { assumeNoException(failure); return }
        catch (failure: SecurityException) { assumeNoException(failure); return }
        assertThrows(IOException::class.java) { SystemNnnSaveValidationService.stagedFiles(stage.toPath()) }
        Files.delete(stage.resolve("mushiex").toPath())
        Files.createSymbolicLink(stage.resolve("mita.dat").toPath(), outside.resolve("mita.dat").toPath())
        assertThrows(IOException::class.java) { SystemNnnSaveValidationService.stagedFiles(stage.toPath()) }
        assertEquals(8, outside.resolve("mita.dat").length().toInt())
    }

    // The Android service runs the native decoder. Transaction tests isolate it from the filesystem moves.
    private val PC_SLOT_FIXTURE = "validated slot fixture".toByteArray()
    private val CHART_NODE_FIXTURE = "validated chart node fixture".toByteArray()

    private fun validateFixtureFiles(incoming: Map<String, File>, existing: Set<String>): Boolean =
        SystemNnnHost.canReplaceSaveSnapshot(
            incoming, SystemNnnHost.requiresStorySnapshot(existing),
            { it.readBytes().contentEquals(PC_SLOT_FIXTURE) },
            { name, bytes ->
                when {
                    SystemNnnHost.isChartNodeSave(name) -> bytes.contentEquals(CHART_NODE_FIXTURE)
                    name.equals("mushiex/mita.dat", true) -> bytes.size == 10004
                    name.equals("mushiex/tsuka.dat", true) -> bytes.size == 10000
                    name.equals("mita.dat", true) -> bytes.size == 8
                    name.equals("tsuka.dat", true) -> bytes.size == 5
                    else -> false
                }
            },
        )

    private fun putChartNode(root: File, name: String): File = root.resolve(name).apply {
        requireNotNull(parentFile).mkdirs()
        writeBytes(CHART_NODE_FIXTURE)
    }

    private fun putChartFlags(root: File, legacy: Boolean, value: Byte = 1): Map<String, ByteArray> {
        val prefix = if (legacy) "mushiex/" else ""
        val files = linkedMapOf(
            "${prefix}mita.dat" to ByteArray(if (legacy) 10004 else 8) { value },
            "${prefix}tsuka.dat" to ByteArray(if (legacy) 10000 else 5) { value },
        )
        for ((name, bytes) in files) root.resolve(name).apply { requireNotNull(parentFile).mkdirs(); writeBytes(bytes) }
        return files
    }

    private fun putPcSlot(root: File, name: String): File =
        root.resolve(name).apply { writeBytes(PC_SLOT_FIXTURE) }

    private fun putLegacySlot(root: File): File {
        val script = "start.spt"
        val save = GameSave().apply { append(20, 0, 0, false, 0) }
        val metadata = GameSave.Metadata(0, 1L, script, 1, 0, 1, "", "Story", false)
        val loaded = GameSave.Loaded(save, metadata, arrayOf(script.toByteArray(), byteArrayOf()))
        return root.resolve("emberfall.sav").apply { outputStream().use { GameSave.writePayload(it, loaded) } }
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

    private fun put(root: File, name: String, content: String): File =
        root.resolve(name).apply {
            requireNotNull(parentFile).mkdirs()
            writeText(content)
        }
}
