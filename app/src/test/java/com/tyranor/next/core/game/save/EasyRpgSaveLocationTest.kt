package com.tyranor.next.core.game.save

import com.tyranor.next.R
import com.tyranor.next.core.engine.EngineType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class EasyRpgSaveLocationTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder(File(System.getProperty("java.io.tmpdir")!!).canonicalFile)

    @Test
    fun supportsFileSavesAcrossSdkLevelsAndResolvesRootSaves() {
        for (sdkInt in listOf(26, 29, 30, 35)) {
            assertTrue(GameSaveManager.supportsFileSaves(EngineType.EASYRPG, sdkInt))
        }
        val root = File("/storage/emulated/0/Games/Yume2kki")
        val location = GameSaveManager.easyRpgSaveLocation(root)
        assertTrue(location.available)
        assertEquals(File(root, "saves"), location.directory)
        assertEquals(R.string.save_location_engine_game_dir, location.descriptionRes)
    }

    @Test
    fun missingRootIsUnavailableWithoutGuessingAnotherDirectory() {
        val location = GameSaveManager.easyRpgSaveLocation(null)
        assertFalse(location.available)
        assertNull(location.directory)
        assertEquals(R.string.save_error_resolve_game_dir, location.descriptionRes)
    }

    @Test
    fun locationResolutionDoesNotCreateSavesDirectory() {
        val root = temporaryFolder.newFolder()
        val location = GameSaveManager.easyRpgSaveLocation(root)
        assertTrue(location.available)
        assertFalse(location.directory!!.exists())
    }

    @Test
    fun importAndDeleteStayInsideSavesAndKeepGameDataFiles() {
        val root = temporaryFolder.newFolder()
        val ldb = File(root, "RPG_RT.ldb").apply { writeText("db") }
        val lmt = File(root, "RPG_RT.lmt").apply { writeText("tree") }
        val saves = GameSaveManager.easyRpgSaveLocation(root).directory!!
        File(saves, "Save01.lsd").apply { parentFile?.mkdirs(); writeText("old-save") }

        val source = temporaryFolder.newFolder()
        File(source, "Save02.lsd").writeText("new-save")
        File(source, "Save03.lsd").writeText("another-save")

        assertEquals(2, GameSaveManager.importSaveDirectory(saves, { false }) { source })
        assertFalse(File(saves, "Save01.lsd").exists())
        val listed = mutableListOf<File>()
        GameSaveManager.collectFiles(saves, listed, { false }, mirrorForced = false)
        assertEquals(setOf("Save02.lsd", "Save03.lsd"), listed.map { it.name }.toSet())

        assertEquals(2, GameSaveManager.deleteSaveDirectory(saves, { false }))
        assertEquals("db", ldb.readText())
        assertEquals("tree", lmt.readText())
    }
}
