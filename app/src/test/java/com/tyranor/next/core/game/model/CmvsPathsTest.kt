package com.tyranor.next.core.game.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CmvsPathsTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun userDirIsStableAndGameScoped() {
        val files = temporaryFolder.newFolder("files")
        val first = CmvsPaths.userDataDir(files, "content://tree/game-a")
        val again = CmvsPaths.userDataDir(files, "content://tree/game-a")
        val other = CmvsPaths.userDataDir(files, "content://tree/game-b")

        assertEquals(first, again)
        assertNotEquals(first, other)
        assertEquals("users", first.parentFile?.name)
    }

    @Test
    fun launchConfigAndFontDirStayUnderFilesDir() {
        val files = temporaryFolder.newFolder("files")
        assertEquals(files, CmvsPaths.launchConfig(files).parentFile)
        assertEquals(files, CmvsPaths.fontDir(files).parentFile)
    }
}
