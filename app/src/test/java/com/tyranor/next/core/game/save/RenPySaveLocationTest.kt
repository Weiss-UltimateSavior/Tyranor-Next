package com.tyranor.next.core.game.save

import com.tyranor.next.R
import com.tyranor.next.core.engine.EngineType
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RenPySaveLocationTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder(File(System.getProperty("java.io.tmpdir")).canonicalFile)

    @Test
    fun android11AndLaterUseRootSavesOnPrimaryAndRemovableStorage() {
        for (root in listOf(File("/storage/emulated/0/Games/RenPy"), File("/storage/1234-5678/RenPy"))) {
            for (sdkInt in listOf(30, 36)) {
                val location = GameSaveManager.renpySaveLocation(root, sdkInt)
                assertTrue(location.available)
                assertEquals(File(root, "saves"), location.directory)
                assertEquals(R.string.save_location_engine_game_dir, location.descriptionRes)
            }
        }
    }

    @Test
    fun android10AndEarlierNeverExposeAFileSaveDirectory() {
        for (sdkInt in listOf(26, 28, 29)) {
            assertFalse(GameSaveManager.supportsFileSaves(EngineType.RENPY, sdkInt))
            val location = GameSaveManager.renpySaveLocation(File("/storage/emulated/0/Game"), sdkInt)
            assertFalse(location.available)
            assertNull(location.directory)
            assertEquals(R.string.save_location_engine_no_file_interface, location.descriptionRes)
        }
    }

    @Test
    fun missingRootIsUnavailableWithoutGuessingAnotherDirectory() {
        val location = GameSaveManager.renpySaveLocation(null, 30)
        assertFalse(location.available)
        assertNull(location.directory)
        assertEquals(R.string.save_error_resolve_game_dir, location.descriptionRes)
    }

    @Test
    fun locationResolutionDoesNotCreateSavesBeforeFirstGameLaunch() {
        val root = temporaryFolder.newFolder()
        val location = GameSaveManager.renpySaveLocation(root, 30)
        assertTrue(location.available)
        assertFalse(location.directory!!.exists())
    }

    @Test
    fun replacementAndDeletionStayInsideSavesAndKeepGameResources() {
        val root = temporaryFolder.newFolder()
        val script = File(root, "game/script.rpy").apply { parentFile.mkdirs(); writeText("game script") }
        val extras = File(root, "extras/patch.rpy").apply { parentFile.mkdirs(); writeText("module patch") }
        val saves = GameSaveManager.renpySaveLocation(root, 30).directory!!
        File(saves, "old.save").apply { parentFile.mkdirs(); writeText("old") }
        val source = temporaryFolder.newFolder()
        File(source, "1-1-LT1.save").writeText("slot")
        File(source, "persistent").writeText("persistent data")
        File(source, "nested/extra.save").apply { parentFile.mkdirs(); writeText("nested") }

        assertEquals(3, GameSaveManager.importSaveDirectory(saves, { false }) { source })
        assertFalse(File(saves, "old.save").exists())
        val files = mutableListOf<File>()
        GameSaveManager.collectFiles(saves, files, { false }, mirrorForced = false)
        assertEquals(setOf("1-1-LT1.save", "persistent", "nested/extra.save"),
            files.map { it.relativeTo(saves).invariantSeparatorsPath }.toSet())
        assertEquals(3, GameSaveManager.deleteSaveDirectory(saves, { false }))
        assertEquals("game script", script.readText())
        assertEquals("module patch", extras.readText())
    }
}
