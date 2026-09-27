package com.tyranor.next.core.game.scan

import com.tyranor.next.core.engine.EngineType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * FVPEngine（rfvp）识别：根目录脚本（*.hcb / 汉化 *.bch）与 graph/voice 资源包特征组合。
 */
class EngineScannerFvpTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun detectsFvpFromHcbAndPacks() {
        val gameRoot = temporaryFolder.newFolder("HappyMarguerite")
        gameRoot.resolve("Marguerite.hcb").writeText("fake")
        gameRoot.resolve("graph.bin").writeText("fake")
        gameRoot.resolve("voice.bin").writeText("fake")

        val detection = EngineScanner.detectEngine(gameRoot)

        assertEquals(EngineType.FVP, detection.engine)
        assertEquals(96, detection.confidence)
        assertEquals(EngineScanner.LAUNCH_TARGET_GAME_DIR, detection.launchTarget)
    }

    @Test
    fun detectsFvpFromBchPatch() {
        val gameRoot = temporaryFolder.newFolder("HappyMarguerite CHS")
        gameRoot.resolve("Marguerite.bch").writeText("fake")
        gameRoot.resolve("graph_vis.bin").writeText("fake")

        val detection = EngineScanner.detectEngine(gameRoot)

        assertEquals(EngineType.FVP, detection.engine)
        assertEquals(96, detection.confidence)
    }

    @Test
    fun detectsFvpFromScriptOnlyWithLowerConfidence() {
        val gameRoot = temporaryFolder.newFolder("FVP Script Only")
        gameRoot.resolve("game.hcb").writeText("fake")

        val detection = EngineScanner.detectEngine(gameRoot)

        assertEquals(EngineType.FVP, detection.engine)
        assertEquals(88, detection.confidence)
    }

    @Test
    fun detectsFvpCaseInsensitive() {
        val gameRoot = temporaryFolder.newFolder("FVP Upper Case")
        gameRoot.resolve("GAME.HCB").writeText("fake")
        gameRoot.resolve("GRAPH.BIN").writeText("fake")

        val detection = EngineScanner.detectEngine(gameRoot)

        assertEquals(EngineType.FVP, detection.engine)
        assertEquals(96, detection.confidence)
    }

    @Test
    fun doesNotDetectFvpFromPacksOnly() {
        val gameRoot = temporaryFolder.newFolder("FVP Packs Only")
        gameRoot.resolve("graph.bin").writeText("fake")
        gameRoot.resolve("voice.bin").writeText("fake")

        val detection = EngineScanner.detectEngine(gameRoot)

        assertNotEquals(EngineType.FVP, detection.engine)
    }

    @Test
    fun doesNotDetectFvpFromNestedScript() {
        // 脚本必须位于游戏根目录；data/ 下的 .hcb 不构成 FVP 特征
        val gameRoot = temporaryFolder.newFolder("FVP Nested Script")
        val data = gameRoot.resolve("data")
        data.mkdirs()
        data.resolve("game.hcb").writeText("fake")
        gameRoot.resolve("graph.bin").writeText("fake")

        val detection = EngineScanner.detectEngine(gameRoot)

        assertNotEquals(EngineType.FVP, detection.engine)
    }

    @Test
    fun doesNotMistakeOtherEnginesForFvp() {
        val siglus = temporaryFolder.newFolder("Siglus Game")
        siglus.resolve("Gameexe.dat").writeText("fake")
        siglus.resolve("Scene.pck").writeText("fake")
        assertEquals(EngineType.SIGLUS, EngineScanner.detectEngine(siglus).engine)

        val ons = temporaryFolder.newFolder("ONS Game")
        ons.resolve("nscript.dat").writeText("fake")
        assertEquals(EngineType.ONS, EngineScanner.detectEngine(ons).engine)
    }
}
