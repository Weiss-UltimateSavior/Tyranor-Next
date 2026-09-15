package com.tyranor.next.core.game.scan

import com.tyranor.next.core.engine.EngineType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * PSP / Nintendo Switch ROM 识别：一 ROM 一条游戏，uri = ROM 文件路径，launchTarget = 文件名。
 */
class EngineScannerPspSwitchTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun mapsRomExtensionsToEngines() {
        assertEquals(EngineType.PSP, EngineScanner.romEngineOf("Game.iso"))
        assertEquals(EngineType.PSP, EngineScanner.romEngineOf("EBOOT.PBP"))
        assertEquals(EngineType.PSP, EngineScanner.romEngineOf("Game.cso"))
        assertEquals(EngineType.PSP, EngineScanner.romEngineOf("Game.chd"))
        assertEquals(EngineType.NINTENDO_SWITCH, EngineScanner.romEngineOf("Game.nsp"))
        assertEquals(EngineType.NINTENDO_SWITCH, EngineScanner.romEngineOf("Game.XCI"))
        assertEquals(EngineType.NINTENDO_SWITCH, EngineScanner.romEngineOf("Game.nca"))
        assertEquals(EngineType.NINTENDO_SWITCH, EngineScanner.romEngineOf("Game.nro"))
        assertNull(EngineScanner.romEngineOf("Game.exe"))
        assertNull(EngineScanner.romEngineOf("data.xp3"))
        assertNull(EngineScanner.romEngineOf("README"))
    }

    @Test
    fun emitsOneGamePerRomWithFileUriAndNameTarget() {
        val dir = temporaryFolder.newFolder("PSP Games")
        dir.resolve("Trails.iso").writeText("iso")
        dir.resolve("EBOOT.PBP").writeText("pbp")
        dir.resolve("notes.txt").writeText("x")

        val games = EngineScanner.romGamesForFile(dir.listFiles()!!, coverUri = null)

        assertEquals(2, games.size)
        assertTrue(games.all { it.engine == EngineType.PSP })
        val byName = games.associateBy { it.launchTarget }
        assertTrue(byName.containsKey("Trails.iso"))
        assertTrue(byName.containsKey("EBOOT.PBP"))
        assertEquals("Trails", byName.getValue("Trails.iso").title)
        assertEquals(
            dir.resolve("Trails.iso").absolutePath,
            byName.getValue("Trails.iso").uri,
        )
    }

    @Test
    fun emitsSwitchRomsFromSameDirectory() {
        val dir = temporaryFolder.newFolder("Switch Games")
        dir.resolve("Game A.nsp").writeText("nsp")
        dir.resolve("Game B.xci").writeText("xci")

        val games = EngineScanner.romGamesForFile(dir.listFiles()!!, coverUri = null)

        assertEquals(2, games.size)
        assertTrue(games.all { it.engine == EngineType.NINTENDO_SWITCH })
    }

    @Test
    fun skipsRomsAlreadyKnown() {
        val dir = temporaryFolder.newFolder("Known")
        val rom = dir.resolve("Known.iso")
        rom.writeText("iso")

        val games = EngineScanner.romGamesForFile(dir.listFiles()!!, coverUri = null, known = setOf(rom.absolutePath))

        assertTrue(games.isEmpty())
    }

    @Test
    fun appliesFolderCoverOnlyForSingleRom() {
        val single = temporaryFolder.newFolder("Single")
        single.resolve("Only.pbp").writeText("pbp")
        val cover = "content://cover/single"

        val singleGames = EngineScanner.romGamesForFile(single.listFiles()!!, coverUri = cover)
        assertEquals(cover, singleGames.single().coverUri)

        val multi = temporaryFolder.newFolder("Multi")
        multi.resolve("A.iso").writeText("a")
        multi.resolve("B.iso").writeText("b")
        val multiGames = EngineScanner.romGamesForFile(multi.listFiles()!!, coverUri = cover)
        assertTrue(multiGames.all { it.coverUri == null })
    }
}
