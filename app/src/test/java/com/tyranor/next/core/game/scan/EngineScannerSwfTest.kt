package com.tyranor.next.core.game.scan

import com.tyranor.next.core.engine.EngineType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * SWF / Ruffle 扫描：文件型 ROM 映射、包装目录判定与标题策略。
 */
class EngineScannerSwfTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun mapsSwfExtensionToRuffle() {
        assertEquals(EngineType.RUFFLE, EngineScanner.romEngineOf("game.swf"))
        assertEquals(EngineType.RUFFLE, EngineScanner.romEngineOf("GAME.SWF"))
        assertNull(EngineScanner.romEngineOf("game.fla"))
        assertNull(EngineScanner.romEngineOf("swf"))
    }

    @Test
    fun emitsOneGamePerSwfWithFileUriAndModuleAlias() {
        val dir = temporaryFolder.newFolder("Two Swfs")
        val first = dir.resolve("first.swf").apply { writeText("swf") }
        val second = dir.resolve("second.swf").apply { writeText("swf") }
        dir.resolve("notes.txt").writeText("x")

        val games = EngineScanner.romGamesForFile(dir.listFiles()!!, coverUri = null, directoryName = dir.name)

        assertEquals(2, games.size)
        assertTrue(games.all { it.engine == EngineType.RUFFLE })
        assertTrue(games.all { it.externalModuleAlias == "internal.flash" })
        val byTarget = games.associateBy { it.launchTarget }
        assertEquals(first.absolutePath, byTarget.getValue("first.swf").uri)
        assertEquals(second.absolutePath, byTarget.getValue("second.swf").uri)
        assertEquals("first", byTarget.getValue("first.swf").title)
        assertEquals("second", byTarget.getValue("second.swf").title)
    }

    @Test
    fun usesDirectoryNameForSingleSwfAndStemForMultiple() {
        val single = temporaryFolder.newFolder("Single Flash")
        single.resolve("only.swf").writeText("swf")

        val singleGames = EngineScanner.romGamesForFile(
            single.listFiles()!!,
            coverUri = null,
            directoryName = single.name,
        )
        assertEquals(single.name, singleGames.single().title)

        val noDirName = EngineScanner.romGamesForFile(single.listFiles()!!, coverUri = null)
        assertEquals("only", noDirName.single().title)

        val multi = temporaryFolder.newFolder("Multi Flash")
        multi.resolve("a.swf").writeText("a")
        multi.resolve("b.swf").writeText("b")
        val multiGames = EngineScanner.romGamesForFile(
            multi.listFiles()!!,
            coverUri = null,
            directoryName = multi.name,
        )
        assertEquals(setOf("a", "b"), multiGames.map { it.title }.toSet())
    }

    @Test
    fun usesFileStemWhenMultipleSwfsEvenIfOthersAreAlreadyKnown() {
        val dir = temporaryFolder.newFolder("Incremental Swfs")
        val first = dir.resolve("a.swf").apply { writeText("a") }
        dir.resolve("b.swf").writeText("b")

        val games = EngineScanner.romGamesForFile(
            dir.listFiles()!!,
            coverUri = null,
            known = setOf(first.absolutePath),
            directoryName = dir.name,
        )

        assertEquals(1, games.size)
        assertEquals("b", games.single().title)
    }

    @Test
    fun detectsWrapperFolderWithSingleTopLevelSwf() {
        val wrapper = temporaryFolder.newFolder("Flash Wrapper")
        wrapper.resolve("story.swf").writeText("swf")
        wrapper.resolve("index.html").writeText("<html></html>")

        val detection = EngineScanner.detectEngine(wrapper)

        assertEquals(EngineType.RUFFLE, detection.engine)
        assertEquals("story.swf", detection.launchTarget)
        assertEquals("internal.flash", detection.externalModuleAlias)
        assertTrue(detection.confidence > 0)
    }

    @Test
    fun doesNotOutrankExistingWebEngineSignals() {
        val tyrano = temporaryFolder.newFolder("Tyrano Wrapper")
        tyrano.resolve("tyrano").mkdirs()
        tyrano.resolve("index.html").writeText("<html></html>")
        tyrano.resolve("story.swf").writeText("swf")
        assertEquals(EngineType.TYRANO, EngineScanner.detectEngine(tyrano).engine)

        val rpgMv = temporaryFolder.newFolder("MV Wrapper")
        rpgMv.resolve("js").mkdirs()
        rpgMv.resolve("js/rpg_core.js").writeText("// rpg")
        rpgMv.resolve("index.html").writeText("<html></html>")
        rpgMv.resolve("story.swf").writeText("swf")
        assertEquals(EngineType.RPG_MV, EngineScanner.detectEngine(rpgMv).engine)
    }

    @Test
    fun ignoresHiddenAndZeroByteSwfEntries() {
        val dir = temporaryFolder.newFolder("Filtered Swfs")
        dir.resolve("._game.swf").writeText("apple-double")
        dir.resolve("empty.swf").writeText("")
        dir.resolve("game.swf").writeText("swf")

        assertNull(EngineScanner.romEngineOf("._game.swf"))
        val games = EngineScanner.romGamesForFile(dir.listFiles()!!, coverUri = null, directoryName = dir.name)
        assertEquals(listOf("game.swf"), games.map { it.launchTarget })
    }

    @Test
    fun multipleSwfsDoNotCountAsWrapperFolder() {
        val dir = temporaryFolder.newFolder("Many Swfs")
        dir.resolve("a.swf").writeText("a")
        dir.resolve("b.swf").writeText("b")
        dir.resolve("index.html").writeText("<html></html>")

        // 无单一入口：退回通用网页判定，而不是把目录当成一个 Ruffle 游戏
        assertEquals(EngineType.WEB_OTHER, EngineScanner.detectEngine(dir).engine)

        val roms = EngineScanner.romGamesForFile(dir.listFiles()!!, coverUri = null, directoryName = dir.name)
        assertEquals(2, roms.size)
        assertTrue(roms.all { it.engine == EngineType.RUFFLE })
    }

    @Test
    fun looseSwfAtScanRootWithSubdirectoriesIsNotAWrapper() {
        val root = temporaryFolder.newFolder("Library Root")
        root.resolve("loose.swf").writeText("swf")
        root.resolve("GameA").mkdirs()
        root.resolve("GameB").mkdirs()

        // 扫描根不是游戏目录：不能因为只有一个散装 SWF 就把整棵目录吞成一个 Ruffle 游戏
        assertEquals(EngineType.UNKNOWN, EngineScanner.detectEngine(root).engine)

        val roms = EngineScanner.romGamesForFile(root.listFiles()!!, coverUri = null, directoryName = root.name)
        assertEquals(1, roms.size)
        assertEquals(EngineType.RUFFLE, roms.single().engine)
        assertEquals("loose.swf", roms.single().launchTarget)
    }

    @Test
    fun rootIndexWithChildDirectoriesIsNotAWrapper() {
        val root = temporaryFolder.newFolder("Index Root")
        root.resolve("index.html").writeText("<html></html>")
        root.resolve("loose.swf").writeText("swf")
        root.resolve("Sub").mkdirs()

        // 有子目录即不是叶子包装目录；退回通用网页判定，SWF 留给文件型扫描
        assertNotEquals(EngineType.RUFFLE, EngineScanner.detectEngine(root).engine)
    }

    @Test
    fun kirikiriWithLooseSwfKeepsKirikiriPrecedence() {
        val dir = temporaryFolder.newFolder("KRKR With Swf")
        dir.resolve("data.xp3").writeText("xp3")
        dir.resolve("story.swf").writeText("swf")

        // 旧规则会把「单 SWF」误判为 Ruffle 包装；KRKR 特征必须优先
        assertEquals(EngineType.KIRIKIRI, EngineScanner.detectEngine(dir).engine)
    }

    @Test
    fun onsWithLooseSwfKeepsOnsPrecedence() {
        val dir = temporaryFolder.newFolder("ONS With Swf")
        dir.resolve("nscript.dat").writeText("script")
        dir.resolve("story.swf").writeText("swf")

        assertEquals(EngineType.ONS, EngineScanner.detectEngine(dir).engine)
    }

    @Test
    fun xsys4WithLooseSwfKeepsSystem4Precedence() {
        val dir = temporaryFolder.newFolder("XSYS4 With Swf")
        dir.resolve("System40.ini").writeText("")
        dir.resolve("game.ain").writeText("")
        dir.resolve("story.swf").writeText("swf")

        assertEquals(EngineType.XSYS4, EngineScanner.detectEngine(dir).engine)
    }

    @Test
    fun pspIsoWithLooseSwfKeepsRomFileRecords() {
        val dir = temporaryFolder.newFolder("PSP With Swf")
        dir.resolve("game.iso").writeText("iso")
        dir.resolve("story.swf").writeText("swf")

        // 主机 ROM 优先：目录不判 Ruffle，两个文件各自入库
        assertEquals(EngineType.UNKNOWN, EngineScanner.detectEngine(dir).engine)

        val roms = EngineScanner.romGamesForFile(dir.listFiles()!!, coverUri = null, directoryName = dir.name)
        assertEquals(setOf(EngineType.PSP, EngineType.RUFFLE), roms.map { it.engine }.toSet())
        assertEquals(setOf("game.iso", "story.swf"), roms.map { it.launchTarget }.toSet())
    }

    @Test
    fun consoleRomChildBlocksWrapperEvenWithRootIndex() {
        val dir = temporaryFolder.newFolder("Indexed PSP")
        dir.resolve("index.html").writeText("<html></html>")
        dir.resolve("game.iso").writeText("iso")
        dir.resolve("story.swf").writeText("swf")

        // 根 index + 单 SWF 也不足以吞掉主机 ROM 目录
        assertNotEquals(EngineType.RUFFLE, EngineScanner.detectEngine(dir).engine)
    }
}
