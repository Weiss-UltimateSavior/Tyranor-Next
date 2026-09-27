package com.tyranor.next.core.game.scan

import com.tyranor.next.core.engine.EngineType
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * YU-RIS 识别：yscfg.dat / pac 内 .ypf / 引擎 DLL / .ymv 特征组合与误判防护。
 */
class EngineScannerYurisTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun yurisFolder(name: String): java.io.File {
        val dir = temporaryFolder.newFolder(name)
        val pac = dir.resolve("pac")
        pac.mkdirs()
        pac.resolve("bn.ypf").writeText("YPF")
        return dir
    }

    @Test
    fun detectsYurisFromYscfgAndPacYpf() {
        val dir = yurisFolder("AnimalTrailGirlishSquare 2")
        dir.resolve("yscfg.dat").writeBytes(ByteArray(128))
        dir.resolve("kemonomichi2.exe").writeText("exe")
        dir.resolve("settings.exe").writeText("exe")
        dir.resolve("YSPNG.DLL").writeText("dll")

        val detection = EngineScanner.detectEngine(dir)

        assertEquals(EngineType.YURIS, detection.engine)
        assertEquals(96, detection.confidence)
        assertEquals(EngineScanner.LAUNCH_TARGET_GAME_DIR, detection.launchTarget)
    }

    @Test
    fun detectsYurisFromPacYpfAlone() {
        val dir = yurisFolder("Loose Yuris")
        dir.resolve("game.exe").writeText("exe")

        val detection = EngineScanner.detectEngine(dir)

        assertEquals(EngineType.YURIS, detection.engine)
        assertEquals(90, detection.confidence)
    }

    @Test
    fun detectsYurisFromRootYpf() {
        val dir = temporaryFolder.newFolder("Root Ypf")
        dir.resolve("update1.ypf").writeText("YPF")

        val detection = EngineScanner.detectEngine(dir)

        assertEquals(EngineType.YURIS, detection.engine)
        assertEquals(90, detection.confidence)
    }

    @Test
    fun detectsYurisFromYscfgAlone() {
        val dir = temporaryFolder.newFolder("Yscfg Only")
        dir.resolve("yscfg.dat").writeBytes(ByteArray(128))

        val detection = EngineScanner.detectEngine(dir)

        assertEquals(EngineType.YURIS, detection.engine)
        assertEquals(85, detection.confidence)
    }

    @Test
    fun detectsYurisFromEngineDllPair() {
        val dir = temporaryFolder.newFolder("Dll Only")
        dir.resolve("YSPNG.DLL").writeText("dll")
        dir.resolve("YSZLB.DLL").writeText("dll")

        val detection = EngineScanner.detectEngine(dir)

        assertEquals(EngineType.YURIS, detection.engine)
        assertEquals(80, detection.confidence)
    }

    @Test
    fun detectsYurisFromYmvVideo() {
        val dir = temporaryFolder.newFolder("Ymv Only")
        dir.resolve("mv001.ymv").writeText("ymv")

        val detection = EngineScanner.detectEngine(dir)

        assertEquals(EngineType.YURIS, detection.engine)
        assertEquals(75, detection.confidence)
    }

    @Test
    fun doesNotMistakeOtherEnginesForYuris() {
        val kirikiri = temporaryFolder.newFolder("Kirikiri")
        kirikiri.resolve("data.xp3").writeText("xp3")
        assertEquals(EngineType.KIRIKIRI, EngineScanner.detectEngine(kirikiri).engine)

        val siglus = temporaryFolder.newFolder("Siglus")
        siglus.resolve("Gameexe.dat").writeText("bin")
        siglus.resolve("Scene.pck").writeText("pck")
        assertEquals(EngineType.SIGLUS, EngineScanner.detectEngine(siglus).engine)
    }
}
