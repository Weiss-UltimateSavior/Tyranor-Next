package com.tyranor.next.core.game.scan

import com.tyranor.next.core.engine.EngineType
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * CatSystem2 识别（`docs/cs2参考.md` §15 评分，文件名字段）：
 * 组合特征达标才判定；`cs2.exe` / 单个 `.int` 不能作为唯一依据。
 */
class EngineScannerCs2Test {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun detectsCs2FromStartupXmlAndResourceFormats() {
        val dir = temporaryFolder.newFolder("CS2 Game")
        val config = dir.resolve("config").apply { mkdirs() }
        config.resolve("startup.xml").writeText("<startup/>")
        dir.resolve("scene.int").writeText("int")
        dir.resolve("image.int").writeText("int")
        dir.resolve("save.cst").writeText("cst")
        dir.resolve("graph.hg3").writeText("hg3")

        val detection = EngineScanner.detectEngine(dir)

        assertEquals(EngineType.CATSYSTEM2, detection.engine)
        assertEquals(65, detection.confidence)
        assertEquals(EngineScanner.LAUNCH_TARGET_GAME_DIR, detection.launchTarget)
    }

    @Test
    fun cs2ExeAloneIsNotEnough() {
        val dir = temporaryFolder.newFolder("cs2 exe only")
        dir.resolve("cs2.exe").writeText("exe")

        assertEquals(EngineType.UNKNOWN, EngineScanner.detectEngine(dir).engine)
    }

    @Test
    fun singleIntAloneIsNotEnough() {
        val dir = temporaryFolder.newFolder("int only")
        dir.resolve("data.int").writeText("int")

        assertEquals(EngineType.UNKNOWN, EngineScanner.detectEngine(dir).engine)
    }

    @Test
    fun startupXmlWithMultipleIntsIsDetected() {
        val dir = temporaryFolder.newFolder("CS2 NoCst")
        val config = dir.resolve("config").apply { mkdirs() }
        config.resolve("startup.xml").writeText("<startup/>")
        dir.resolve("scene.int").writeText("int")
        dir.resolve("bgm.int").writeText("int")
        dir.resolve("cs2.exe").writeText("exe")

        val detection = EngineScanner.detectEngine(dir)

        assertEquals(EngineType.CATSYSTEM2, detection.engine)
        assertEquals(50, detection.confidence)
    }

    @Test
    fun doesNotMistakeOtherEnginesForCs2() {
        val kirikiri = temporaryFolder.newFolder("Kirikiri")
        kirikiri.resolve("data.xp3").writeText("xp3")
        assertEquals(EngineType.KIRIKIRI, EngineScanner.detectEngine(kirikiri).engine)

        val yuris = temporaryFolder.newFolder("Yuris")
        yuris.resolve("yscfg.dat").writeText("bin")
        val pac = yuris.resolve("pac").apply { mkdirs() }
        pac.resolve("bn.ypf").writeText("ypf")
        assertEquals(EngineType.YURIS, EngineScanner.detectEngine(yuris).engine)
    }
}
