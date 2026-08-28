package com.tyranor.next.core.game.scan

import com.tyranor.next.core.settings.EngineSettingsStore
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RenPyVersionDetectorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun parsesCommaSeparatedScriptVersionLikeJoiPlay() {
        assertEquals(70411, RenPyVersionDetector.parseVersionCode("script_version = (7, 4, 11)"))
        assertEquals(80500, RenPyVersionDetector.parseVersionCode("8,5,0"))
    }

    @Test
    fun parsesDotSeparatedScriptVersion() {
        assertEquals(70701, RenPyVersionDetector.parseVersionCode("Ren'Py 7.7.1"))
        assertEquals(80500, RenPyVersionDetector.parseVersionCode("8.5"))
    }

    @Test
    fun mapsVersionCodeToRuntimeFamily() {
        assertEquals(EngineSettingsStore.RENPY_77, RenPyVersionDetector.moduleVersionForCode(70701))
        assertEquals(EngineSettingsStore.RENPY_85, RenPyVersionDetector.moduleVersionForCode(80000))
        assertEquals(EngineSettingsStore.RENPY_85, RenPyVersionDetector.moduleVersionForCode(80500))
    }

    @Test
    fun detectsPython2FallbackWhenVersionFileMissing() {
        assertEquals(
            EngineSettingsStore.RENPY_77,
            RenPyVersionDetector.detect(scriptVersionTxt = null, scriptVersionRpy = null, hasPython27 = true),
        )
        assertEquals(
            EngineSettingsStore.RENPY_85,
            RenPyVersionDetector.detect(scriptVersionTxt = null, scriptVersionRpy = null, hasPython27 = false),
        )
    }

    @Test
    fun detectsFileGameByScriptVersionAndPythonLib() {
        val renpy7 = temporaryFolder.newFolder("renpy7")
        renpy7.resolve("game").mkdirs()
        renpy7.resolve("game/script_version.rpy").writeText("script_version = (7, 7, 1)")

        val renpy7ByPython = temporaryFolder.newFolder("renpy7-python")
        renpy7ByPython.resolve("game").mkdirs()
        renpy7ByPython.resolve("lib/pythonlib2.7").mkdirs()

        val renpy8 = temporaryFolder.newFolder("renpy8")
        renpy8.resolve("game").mkdirs()
        renpy8.resolve("game/script_version.txt").writeText("8,5,0")

        assertEquals(EngineSettingsStore.RENPY_77, RenPyVersionDetector.detect(renpy7))
        assertEquals(EngineSettingsStore.RENPY_77, RenPyVersionDetector.detect(renpy7ByPython))
        assertEquals(EngineSettingsStore.RENPY_85, RenPyVersionDetector.detect(renpy8))
    }
}
