package com.tyranor.next.core.game.scan

import com.tyranor.next.core.engine.EngineType
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class EngineScannerEasyRpgTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun folder(name: String, vararg files: String): File {
        val root = temporaryFolder.newFolder(name)
        files.forEach { rel ->
            val target = root.resolve(rel)
            target.parentFile?.mkdirs()
            target.writeBytes(byteArrayOf(0))
        }
        return root
    }

    @Test
    fun detectsStandardRm2kAnd2k3CaseInsensitively() {
        val upper = folder("rm2k3-upper", "RPG_RT.ldb", "RPG_RT.lmt", "RPG_RT.ini")
        val lower = folder("rm2k-lower", "rpg_rt.ldb", "rpg_rt.lmt")
        val mixed = folder("rm2k3-mixed", "Rpg_Rt.LDB", "rPg_rT.LmT")

        for (dir in listOf(upper, lower, mixed)) {
            val detection = EngineScanner.detectEngine(dir)
            assertEquals(EngineType.EASYRPG, detection.engine)
            assertEquals(EngineScanner.LAUNCH_TARGET_GAME_DIR, detection.launchTarget)
            assertEquals("internal.easyrpg", detection.externalModuleAlias)
            assertEquals(95, detection.confidence)
        }
    }

    @Test
    fun detectsEasyRpgNativeFormatCaseInsensitively() {
        val dir = folder("easyrpg-native", "easy_rt.EDB", "EASY_RT.emt")

        val detection = EngineScanner.detectEngine(dir)

        assertEquals(EngineType.EASYRPG, detection.engine)
        assertEquals(EngineScanner.LAUNCH_TARGET_GAME_DIR, detection.launchTarget)
        assertEquals("internal.easyrpg", detection.externalModuleAlias)
    }

    @Test
    fun rejectsIncompleteOrMixedOrNestedEasyRpgFiles() {
        val onlyLdb = folder("only-ldb", "RPG_RT.ldb")
        val onlyLmt = folder("only-lmt", "RPG_RT.lmt")
        val onlyEdb = folder("only-edb", "EASY_RT.edb")
        val crossPair = folder("cross-pair", "RPG_RT.ldb", "EASY_RT.emt")
        val nested = folder("nested", "sub/RPG_RT.ldb", "sub/RPG_RT.lmt")

        for (dir in listOf(onlyLdb, onlyLmt, onlyEdb, crossPair, nested)) {
            assertEquals(dir.name, EngineType.UNKNOWN, EngineScanner.detectEngine(dir).engine)
        }
    }

    @Test
    fun doesNotConfuseWithRpgMakerMvMzOrRgss() {
        val mv = folder("mv", "index.html", "js/rpg_core.js")
        val mz = folder("mz", "index.html", "js/rmmz_core.js")
        val xp = folder("xp", "Game.ini", "Data/Scripts.rxdata")
        val vx = folder("vx", "Game.ini", "Data/Scripts.rvdata")
        val vxAce = folder("vxace", "Game.ini", "Data/Scripts.rvdata2")

        assertEquals(EngineType.RPG_MV, EngineScanner.detectEngine(mv).engine)
        assertEquals(EngineType.RPG_MZ, EngineScanner.detectEngine(mz).engine)
        assertEquals(EngineType.RPGMAKER, EngineScanner.detectEngine(xp).engine)
        assertEquals(EngineType.RPGMAKER, EngineScanner.detectEngine(vx).engine)
        assertEquals(EngineType.RPGMAKER, EngineScanner.detectEngine(vxAce).engine)
    }

    @Test
    fun winsOverStrayManualHtmlInRm2kDirectory() {
        val dir = folder("rm2k-with-manual", "RPG_RT.ldb", "RPG_RT.lmt", "index.html")

        assertEquals(EngineType.EASYRPG, EngineScanner.detectEngine(dir).engine)
    }
}
