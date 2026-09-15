package com.tyranor.next.core.game.scan

import com.tyranor.next.core.engine.EngineType
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * SiglusEngine（siglus_rs）识别：Gameexe + Scene.pck 特征组合与大小写/本地化变体。
 */
class EngineScannerSiglusTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun detectsSiglusFromGameexeDatAndScenePck() {
        val gameRoot = temporaryFolder.newFolder("Siglus Game")
        gameRoot.resolve("Gameexe.dat").writeText("fake")
        gameRoot.resolve("Scene.pck").writeText("fake")

        val detection = EngineScanner.detectEngine(gameRoot)

        assertEquals(EngineType.SIGLUS, detection.engine)
        assertEquals(96, detection.confidence)
        assertEquals(EngineScanner.LAUNCH_TARGET_GAME_DIR, detection.launchTarget)
    }

    @Test
    fun detectsSiglusFromGameexeIniAndDataScenePck() {
        val gameRoot = temporaryFolder.newFolder("Siglus Game Ini")
        gameRoot.resolve("Gameexe.ini").writeText("[SCREEN_SIZE]")
        val data = gameRoot.resolve("Data")
        data.mkdirs()
        data.resolve("Scene.pck").writeText("fake")

        val detection = EngineScanner.detectEngine(gameRoot)

        assertEquals(EngineType.SIGLUS, detection.engine)
        assertEquals(95, detection.confidence)
    }

    @Test
    fun detectsSiglusFromLocalizedGameexeVariants() {
        val gameRoot = temporaryFolder.newFolder("Siglus Game EN")
        gameRoot.resolve("gameexeen.ini").writeText("[SCREEN_SIZE]")
        gameRoot.resolve("scene.pck").writeText("fake")

        val detection = EngineScanner.detectEngine(gameRoot)

        assertEquals(EngineType.SIGLUS, detection.engine)
        assertEquals(95, detection.confidence)
    }

    @Test
    fun detectsSiglusFromGameexeAloneWithLowerConfidence() {
        val gameRoot = temporaryFolder.newFolder("Siglus Gameexe Only")
        gameRoot.resolve("Gameexe.dat").writeText("fake")

        val detection = EngineScanner.detectEngine(gameRoot)

        assertEquals(EngineType.SIGLUS, detection.engine)
        assertEquals(85, detection.confidence)
    }

    @Test
    fun detectsSiglusFromScenePckSelectIniAndG00() {
        val gameRoot = temporaryFolder.newFolder("Siglus Loose")
        gameRoot.resolve("Scene.pck").writeText("fake")
        gameRoot.resolve("Select.ini").writeText("[Select]")
        gameRoot.resolve("image.g00").writeText("fake")

        val detection = EngineScanner.detectEngine(gameRoot)

        assertEquals(EngineType.SIGLUS, detection.engine)
        assertEquals(80, detection.confidence)
    }

    @Test
    fun doesNotMistakeOtherEnginesForSiglus() {
        val rpgMaker = temporaryFolder.newFolder("RPG Maker XP")
        rpgMaker.resolve("Game.ini").writeText("[Game]")
        val rpgData = rpgMaker.resolve("Data")
        rpgData.mkdirs()
        rpgData.resolve("Scripts.rxdata").writeText("fake")
        assertEquals(EngineType.RPGMAKER, EngineScanner.detectEngine(rpgMaker).engine)

        val artemis = temporaryFolder.newFolder("Artemis Game")
        artemis.resolve("root.pfs").writeText("fake")
        assertEquals(EngineType.ARTEMIS, EngineScanner.detectEngine(artemis).engine)
    }
}
