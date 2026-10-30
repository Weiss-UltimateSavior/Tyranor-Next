package com.tyranor.next.core.game.save

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SaveAppDataCleanupTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun removingOriginalDirectoryGameCannotDeleteSameNamedScopedSaveUsedByAnotherGame() {
        val app = temp.newFolder("app")
        val original = temp.newFolder("original", "title")
        val siblingScoped = File(app, "save/title").apply { mkdirs() }
        File(siblingScoped, "progress.dat").writeText("other-game")
        assertNull(GameSaveManager.appDataCleanupTarget(original, siblingScoped, listOf(app)))
        assertEquals("other-game", File(siblingScoped, "progress.dat").readText())
    }

    @Test
    fun krkrCleanupIsBoundToSavedataAndPreservesItsMirrorParent() {
        val app = temp.newFolder("krkr-app")
        val mirror = File(app, "krkr_mirror/title").apply { mkdirs() }
        val savedata = File(mirror, "savedata").apply { mkdirs() }
        assertEquals(savedata.canonicalFile,
            GameSaveManager.appDataCleanupTarget(savedata, mirror, listOf(app)))
        assertNull(GameSaveManager.appDataCleanupTarget(File(app, "other-root/savedata"), mirror, listOf(app)))
    }

    @Test
    fun admittedScopedDirectoryCanBeCleanedButAppRootAndPrefixSiblingCannot() {
        val app = temp.newFolder("scoped-app")
        val scoped = File(app, "save/title").apply { mkdirs() }
        assertEquals(scoped.canonicalFile,
            GameSaveManager.appDataCleanupTarget(scoped, scoped, listOf(app)))
        assertNull(GameSaveManager.appDataCleanupTarget(app, app, listOf(app)))
        assertNull(GameSaveManager.appDataCleanupTarget(File(app, "save/title-other"), scoped, listOf(app)))
        assertNull(GameSaveManager.appDataCleanupTarget(null, scoped, listOf(app)))
    }
}
