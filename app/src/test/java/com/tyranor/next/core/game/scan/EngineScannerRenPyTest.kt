package com.tyranor.next.core.game.scan

import com.tyranor.next.core.engine.EngineType
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EngineScannerRenPyTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun detectsRenPyFromArchiveAtGameRoot() {
        val gameRoot = temporaryFolder.newFolder("RenPy Archive Game")
        gameRoot.resolve("archive.rpa").writeText("fake")

        val detection = EngineScanner.detectEngine(gameRoot)

        assertEquals(EngineType.RENPY, detection.engine)
        assertEquals("[游戏目录]", detection.launchTarget)
    }

    @Test
    fun detectsRenPyFromGameScript() {
        val gameRoot = temporaryFolder.newFolder("RenPy Script Game")
        val gameDir = gameRoot.resolve("game")
        gameDir.mkdirs()
        gameDir.resolve("script.rpy").writeText("label start:")

        val detection = EngineScanner.detectEngine(gameRoot)

        assertEquals(EngineType.RENPY, detection.engine)
        assertEquals("[游戏目录]", detection.launchTarget)
    }

    @Test
    fun detectsRenPyFromRenpyRuntimeAndCompiledScript() {
        val gameRoot = temporaryFolder.newFolder("RenPy Compiled Game")
        gameRoot.resolve("renpy").mkdirs()
        gameRoot.resolve("game").mkdirs()
        gameRoot.resolve("game/script.rpyc").writeText("fake")

        assertEquals(EngineType.RENPY, EngineScanner.detectEngine(gameRoot).engine)
    }

    @Test
    fun keepsPlainIndexAsWebOther() {
        val gameRoot = temporaryFolder.newFolder("Plain Web Game")
        gameRoot.resolve("index.html").writeText("<html></html>")

        assertEquals(EngineType.WEB_OTHER, EngineScanner.detectEngine(gameRoot).engine)
    }
}
