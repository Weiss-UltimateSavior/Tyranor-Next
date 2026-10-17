package com.tyranor.next.core.game.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [Xs35Paths] 纯函数：生效游戏目录（root / 大小写不敏感 GAMEDATA）、场景档判定
 * （`S`+字母 `*.ALD` 或 `adisk.ald`，其余 `*.ALD` 不算）与存档目录路径形态。
 */
class Xs35PathsTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun effectiveGameDirPrefersRootWithScenarioAld() {
        val root = temporaryFolder.newFolder("Root Scenario")
        root.resolve("SA.ALD").writeText("fake")
        val gamedata = root.resolve("GAMEDATA").apply { mkdirs() }
        gamedata.resolve("SA.ALD").writeText("fake")

        assertEquals(root, Xs35Paths.effectiveGameDir(root))
    }

    @Test
    fun effectiveGameDirFallsBackToGamedataCaseInsensitive() {
        val root = temporaryFolder.newFolder("Gamedata Lower")
        val gamedata = root.resolve("gamedata").apply { mkdirs() }
        gamedata.resolve("SA.ALD").writeText("fake")

        assertEquals(gamedata, Xs35Paths.effectiveGameDir(root))
    }

    @Test
    fun effectiveGameDirUsesAnyAldDirectoryWhenNoScenarioAld() {
        val root = temporaryFolder.newFolder("Graphics Only")
        val gamedata = root.resolve("GAMEDATA").apply { mkdirs() }
        gamedata.resolve("GA.ALD").writeText("fake")

        assertEquals(gamedata, Xs35Paths.effectiveGameDir(root))
    }

    @Test
    fun effectiveGameDirFallsBackToRootWithoutAnyAld() {
        val root = temporaryFolder.newFolder("No Ald")
        root.resolve("GAMEDATA").apply { mkdirs() }
        root.resolve("readme.txt").writeText("fake")

        assertEquals(root, Xs35Paths.effectiveGameDir(root))
    }

    @Test
    fun hasScenarioAldAcceptsSTaggedAndAdiskForms() {
        val dir = temporaryFolder.newFolder("Scenario Forms")
        dir.resolve("SA.ALD").writeText("fake")
        dir.resolve("SAsa.ald").writeText("fake")
        dir.resolve("adisk.ald").writeText("fake")

        assertTrue(Xs35Paths.hasScenarioAld(dir))
    }

    @Test
    fun hasScenarioAldAcceptsAdiskAldAlone() {
        val dir = temporaryFolder.newFolder("Adisk Only")
        dir.resolve("adisk.ald").writeText("fake")

        assertTrue(Xs35Paths.hasScenarioAld(dir))
    }

    @Test
    fun hasScenarioAldRejectsNonScenarioAldForms() {
        // S1.ALD：倒数第 5 位是数字，上游 toupper(x)-'A' 越界被丢弃；GA.ALD 是图形档
        val dir = temporaryFolder.newFolder("Non Scenario Forms")
        dir.resolve("S1.ALD").writeText("fake")
        dir.resolve("GA.ALD").writeText("fake")

        assertFalse(Xs35Paths.hasScenarioAld(dir))
    }

    @Test
    fun hasScenarioAldRejectsDirectoryNamedLikeAld() {
        val dir = temporaryFolder.newFolder("Directory Disguise")
        val disguised = dir.resolve("SA.ALD").apply { mkdirs() }

        assertFalse(Xs35Paths.hasScenarioAld(dir))
        assertTrue(disguised.isDirectory)
    }

    @Test
    fun saveDirPathFormsStayAlignedWithLauncher() {
        val root = temporaryFolder.newFolder("Rance4")
        val external = temporaryFolder.newFolder("external")
        val rootPath = root.absolutePath
        val name = GamePathUtils.safeSaveName(rootPath)

        assertEquals(
            root.resolve("save").absolutePath,
            Xs35Paths.gameDirSaveDir(root).absolutePath,
        )
        assertEquals(
            external.resolve("save").resolve(name).absolutePath,
            Xs35Paths.scopedSaveDir(external, rootPath).absolutePath,
        )
        // GAMEDATA 形态：非独立存档落在生效游戏目录（GAMEDATA/save）而非游戏根
        assertEquals(
            root.resolve("GAMEDATA").resolve("save").absolutePath,
            Xs35Paths.gameDirSaveDir(root.resolve("GAMEDATA")).absolutePath,
        )
    }
}
