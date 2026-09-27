package com.tyranor.next.core.game.launch

import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.game.model.ScanGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * YU-RIS 主程序解析：干扰项排除、目录名匹配、体积兜底与手动覆盖。
 */
class YurisLaunchFilesTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun game(projectDir: File, launchFile: String? = null): ScanGame = ScanGame(
        title = projectDir.name,
        uri = projectDir.absolutePath,
        engine = EngineType.YURIS,
        launchTarget = "DIR",
        launchFile = launchFile,
    )

    private fun file(dir: File, name: String, size: Int): File =
        dir.resolve(name).apply { writeBytes(ByteArray(size)) }

    @Test
    fun prefersLargestExeAndSkipsSettings() {
        val dir = temporaryFolder.newFolder("AnimalTrailGirlishSquare 2")
        file(dir, "settings.exe", 360)
        file(dir, "kemonomichi2.exe", 2100)
        file(dir, "unins000.exe", 100)

        val exe = YurisLaunchFiles.resolveExe(game(dir), dir.absolutePath)

        assertEquals("kemonomichi2.exe", exe?.name)
    }

    @Test
    fun prefersExeNameMatchingDirectoryWhenSmaller() {
        val dir = temporaryFolder.newFolder("MyGame")
        file(dir, "OtherTool.exe", 5000)
        file(dir, "my_game.exe", 1000)

        val exe = YurisLaunchFiles.resolveExe(game(dir), dir.absolutePath)

        assertEquals("my_game.exe", exe?.name)
    }

    @Test
    fun manualOverrideWins() {
        val dir = temporaryFolder.newFolder("MyGame")
        file(dir, "main.exe", 5000)
        file(dir, "alt.exe", 100)

        val exe = YurisLaunchFiles.resolveExe(game(dir, launchFile = "alt.exe"), dir.absolutePath)

        assertEquals("alt.exe", exe?.name)
    }

    @Test
    fun manualOverrideSupportsSubdirectory() {
        val dir = temporaryFolder.newFolder("MyGame")
        val sub = dir.resolve("bin").apply { mkdirs() }
        file(dir, "main.exe", 5000)
        file(sub, "nested.exe", 10)

        val exe = YurisLaunchFiles.resolveExe(game(dir, launchFile = "bin/nested.exe"), dir.absolutePath)

        assertEquals("nested.exe", exe?.name)
    }

    @Test
    fun fallsBackToUnfilteredWhenAllExcludedKeywordsMatch() {
        val dir = temporaryFolder.newFolder("MyGame")
        file(dir, "settings.exe", 900)
        file(dir, "update.exe", 100)

        val exe = YurisLaunchFiles.resolveExe(game(dir), dir.absolutePath)

        assertEquals("settings.exe", exe?.name)
    }

    @Test
    fun cs2AcceptsBinAndPrefersCs2Runtime() {
        val dir = temporaryFolder.newFolder("CS2 Game")
        file(dir, "game.bin", 900)
        file(dir, "launcher.bin", 5000)
        file(dir, "cs2.exe", 100)

        // 默认（YU-RIS/PC）：只列 .exe
        assertEquals(listOf("cs2.exe"), YurisLaunchFiles.candidates(dir).map { it.name })

        // CatSystem2：接受 .bin，且 cs2.exe 优先
        val cs2 = YurisLaunchFiles.candidates(dir, allowBin = true, preferCs2Runtime = true).map { it.name }
        assertEquals("cs2.exe", cs2.first())
        assertEquals(setOf("cs2.exe", "game.bin", "launcher.bin"), cs2.toSet())
    }

    @Test
    fun returnsNullWithoutExe() {
        val dir = temporaryFolder.newFolder("MyGame")
        file(dir, "readme.txt", 10)

        assertNull(YurisLaunchFiles.resolveExe(game(dir), dir.absolutePath))
    }
}
