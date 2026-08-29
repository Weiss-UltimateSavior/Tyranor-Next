package com.tyranor.next.core.game.scan

import com.tyranor.next.core.engine.EngineType
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EngineScannerRpgMakerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun detectsRpgMakerXpFromRgssArchive() {
        val gameRoot = temporaryFolder.newFolder("RPGXP Game")
        gameRoot.resolve("Game.rgssad").writeText("fake")

        val detection = EngineScanner.detectEngine(gameRoot)

        assertEquals(EngineType.RPGMAKER, detection.engine)
        assertEquals("Game.rgssad".lowercase(), detection.launchTarget)
        assertEquals("internal.rpgmxp", detection.externalModuleAlias)
    }

    @Test
    fun detectsRpgMakerVxFromRgss2Archive() {
        val gameRoot = temporaryFolder.newFolder("RPGVX Game")
        gameRoot.resolve("Game.rgss2a").writeText("fake")

        val detection = EngineScanner.detectEngine(gameRoot)

        assertEquals(EngineType.RPGMAKER, detection.engine)
        assertEquals("internal.rpgmvx", detection.externalModuleAlias)
    }

    @Test
    fun detectsRpgMakerVxAceFromRgss3Archive() {
        val gameRoot = temporaryFolder.newFolder("RPGVXAce Game")
        gameRoot.resolve("Game.rgss3a").writeText("fake")

        val detection = EngineScanner.detectEngine(gameRoot)

        assertEquals(EngineType.RPGMAKER, detection.engine)
        assertEquals("internal.rpgmvxace", detection.externalModuleAlias)
    }

    @Test
    fun detectsRpgMakerXpFromGameIniAndRxdata() {
        val gameRoot = temporaryFolder.newFolder("RPGXP Loose Game")
        gameRoot.resolve("Game.ini").writeText("[Game]")
        val data = gameRoot.resolve("Data")
        data.mkdirs()
        data.resolve("Scripts.rxdata").writeText("fake")

        val detection = EngineScanner.detectEngine(gameRoot)

        assertEquals(EngineType.RPGMAKER, detection.engine)
        assertEquals("[游戏目录]", detection.launchTarget)
        assertEquals("internal.rpgmxp", detection.externalModuleAlias)
    }

    @Test
    fun detectsRpgMakerVxFromGameIniAndRvdata() {
        val gameRoot = temporaryFolder.newFolder("RPGVX Loose Game")
        gameRoot.resolve("Game.ini").writeText("[Game]")
        val data = gameRoot.resolve("Data")
        data.mkdirs()
        data.resolve("Scripts.rvdata").writeText("fake")

        val detection = EngineScanner.detectEngine(gameRoot)

        assertEquals(EngineType.RPGMAKER, detection.engine)
        assertEquals("internal.rpgmvx", detection.externalModuleAlias)
    }

    @Test
    fun detectsRpgMakerVxAceFromGameIniAndRvdata2() {
        val gameRoot = temporaryFolder.newFolder("RPGVXAce Loose Game")
        gameRoot.resolve("Game.ini").writeText("[Game]")
        val data = gameRoot.resolve("Data")
        data.mkdirs()
        data.resolve("Scripts.rvdata2").writeText("fake")

        val detection = EngineScanner.detectEngine(gameRoot)

        assertEquals(EngineType.RPGMAKER, detection.engine)
        assertEquals("internal.rpgmvxace", detection.externalModuleAlias)
    }

    @Test
    fun keepsRpgMakerMvOnWebRuntime() {
        val gameRoot = temporaryFolder.newFolder("MV Game")
        val www = gameRoot.resolve("www")
        www.resolve("js").mkdirs()
        www.resolve("index.html").writeText("<html></html>")
        www.resolve("js/rpg_core.js").writeText("// RPG Maker MV")

        assertEquals(EngineType.RPG_MV, EngineScanner.detectEngine(gameRoot).engine)
    }
}
