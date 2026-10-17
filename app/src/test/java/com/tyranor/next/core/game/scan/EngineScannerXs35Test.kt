package com.tyranor.next.core.game.scan

import com.tyranor.next.core.engine.EngineType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * System3.x（xsystem35）识别：根目录 / `GAMEDATA/` 子目录的 `*.ALD`（长度 ≥ 6）为硬特征，
 * `System39.ain`/`System39.ini`/`*.wai`/`*.bgi`/`*.alk` 为辅助特征。
 * 判定顺序在既有 native 引擎（framebuffer / Siglus / YURIS / CS2）之后：
 * 「既有引擎特征 + 杂散 ALD」不被抢判；仅含非 S 前缀 ALD（如 `GA.ALD`）的目录按当前规则
 * 仍低分识别为 XSYS35，由宿主启动预检兜底。
 */
class EngineScannerXs35Test {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun detectsXs35FromScenarioAldAndSystemIni() {
        val gameRoot = temporaryFolder.newFolder("Rance4")
        gameRoot.resolve("SA.ALD").writeText("fake")
        gameRoot.resolve("System39.ini").writeText("fake")

        val detection = EngineScanner.detectEngine(gameRoot)

        assertEquals(EngineType.XSYS35, detection.engine)
        assertEquals(96, detection.confidence)
        assertEquals(EngineScanner.LAUNCH_TARGET_GAME_DIR, detection.launchTarget)
    }

    @Test
    fun detectsXs35FromMultipleAldWithoutSupportFile() {
        val gameRoot = temporaryFolder.newFolder("Toushin")
        gameRoot.resolve("SAsa.ALD").writeText("fake")
        gameRoot.resolve("GA.ALD").writeText("fake")

        val detection = EngineScanner.detectEngine(gameRoot)

        assertEquals(EngineType.XSYS35, detection.engine)
        assertEquals(90, detection.confidence)
    }

    @Test
    fun detectsXs35FromSingleAld() {
        val gameRoot = temporaryFolder.newFolder("Single Ald")
        gameRoot.resolve("SA.ALD").writeText("fake")

        val detection = EngineScanner.detectEngine(gameRoot)

        assertEquals(EngineType.XSYS35, detection.engine)
        assertEquals(80, detection.confidence)
    }

    @Test
    fun detectsXs35CaseInsensitive() {
        val gameRoot = temporaryFolder.newFolder("Case Insensitive")
        gameRoot.resolve("sa.ald").writeText("fake")
        gameRoot.resolve("SYSTEM39.AIN").writeText("fake")

        val detection = EngineScanner.detectEngine(gameRoot)

        assertEquals(EngineType.XSYS35, detection.engine)
        assertEquals(96, detection.confidence)
    }

    @Test
    fun detectsXs35FromGamedataSubdirectory() {
        val gameRoot = temporaryFolder.newFolder("Gamedata Layout")
        val gamedata = gameRoot.resolve("GAMEDATA")
        gamedata.mkdirs()
        gamedata.resolve("SA.ALD").writeText("fake")
        gamedata.resolve("System39.ini").writeText("fake")

        val detection = EngineScanner.detectEngine(gameRoot)

        assertEquals(EngineType.XSYS35, detection.engine)
        assertEquals(96, detection.confidence)
    }

    @Test
    fun detectsXs35SupportExtensionInGamedata() {
        val gameRoot = temporaryFolder.newFolder("Gamedata Wai")
        val gamedata = gameRoot.resolve("gamedata")
        gamedata.mkdirs()
        gamedata.resolve("SA.ALD").writeText("fake")
        gamedata.resolve("Data.wai").writeText("fake")

        val detection = EngineScanner.detectEngine(gameRoot)

        assertEquals(EngineType.XSYS35, detection.engine)
        assertEquals(96, detection.confidence)
    }

    @Test
    fun nonScenarioAldOnlyStillDetectsLowConfidence() {
        // 记录当前行为：只有非 S 前缀 ALD（引擎拒绝启动）仍会被低分识别为 XSYS35，
        // 由宿主启动预检（场景档 S*.ALD）兜底拦下
        val gameRoot = temporaryFolder.newFolder("Graphics Only")
        gameRoot.resolve("GA.ALD").writeText("fake")

        val detection = EngineScanner.detectEngine(gameRoot)

        assertEquals(EngineType.XSYS35, detection.engine)
        assertEquals(80, detection.confidence)
    }

    @Test
    fun doesNotDetectXs35FromShortAldNamesOrNestedAld() {
        val shortName = temporaryFolder.newFolder("Short Ald")
        shortName.resolve("A.ALD").writeText("fake")
        assertNotEquals(EngineType.XSYS35, EngineScanner.detectEngine(shortName).engine)

        // ALD 特征只看根目录与 GAMEDATA/；data/ 等搜索目录下的 ALD 不构成 XS35 特征
        val nested = temporaryFolder.newFolder("Nested Ald")
        val data = nested.resolve("data")
        data.mkdirs()
        data.resolve("SA.ALD").writeText("fake")
        data.resolve("System39.ini").writeText("fake")
        assertNotEquals(EngineType.XSYS35, EngineScanner.detectEngine(nested).engine)
    }

    @Test
    fun doesNotMistakeOtherEnginesForXs35() {
        val siglus = temporaryFolder.newFolder("Siglus Game")
        siglus.resolve("Gameexe.dat").writeText("fake")
        siglus.resolve("Scene.pck").writeText("fake")
        assertEquals(EngineType.SIGLUS, EngineScanner.detectEngine(siglus).engine)

        val reallive = temporaryFolder.newFolder("RealLive Game")
        reallive.resolve("Gameexe.ini").writeText("[REGNAME]")
        reallive.resolve("SEEN0001.TXT").writeText("fake")
        assertEquals(EngineType.REALLIVE, EngineScanner.detectEngine(reallive).engine)

        val avg32 = temporaryFolder.newFolder("AVG32 Game")
        avg32.resolve("Gameexe.ini").writeText("[REGNAME]")
        avg32.resolve("SEEN001.TXT").writeText("TPC32")
        assertEquals(EngineType.AVG32, EngineScanner.detectEngine(avg32).engine)

        val uk2 = temporaryFolder.newFolder("UK2 Game")
        uk2.resolve("UK2.CFG").writeText("fake")
        assertEquals(EngineType.UK2, EngineScanner.detectEngine(uk2).engine)

        val fvp = temporaryFolder.newFolder("FVP Game")
        fvp.resolve("Marguerite.hcb").writeText("fake")
        fvp.resolve("graph.bin").writeText("fake")
        assertEquals(EngineType.FVP, EngineScanner.detectEngine(fvp).engine)
    }

    @Test
    fun strayAldDoesNotStealExistingEngineDetection() {
        // 判定优先级：既有 native 引擎（framebuffer / Siglus / YURIS / CS2 …）先于 XSYS35，
        // 「既有引擎特征 + 杂散 SA.ALD」不得被抢判（Xs35Activity 场景预检仍会拦下错误启动）
        val uk2 = temporaryFolder.newFolder("UK2 With Stray Ald")
        uk2.resolve("UK2.CFG").writeText("fake")
        uk2.resolve("SA.ALD").writeText("fake")
        assertEquals(EngineType.UK2, EngineScanner.detectEngine(uk2).engine)

        val reallive = temporaryFolder.newFolder("RealLive With Stray Ald")
        reallive.resolve("Gameexe.ini").writeText("[REGNAME]")
        reallive.resolve("SEEN.TXT").writeBytes(realliveToc())
        reallive.resolve("SA.ALD").writeText("fake")
        assertEquals(EngineType.REALLIVE, EngineScanner.detectEngine(reallive).engine)

        val siglus = temporaryFolder.newFolder("Siglus With Stray Ald")
        siglus.resolve("Gameexe.dat").writeText("fake")
        siglus.resolve("Scene.pck").writeText("fake")
        siglus.resolve("SA.ALD").writeText("fake")
        assertEquals(EngineType.SIGLUS, EngineScanner.detectEngine(siglus).engine)

        val yuris = temporaryFolder.newFolder("YURIS With Stray Ald")
        yuris.resolve("yscfg.dat").writeText("fake")
        val pac = yuris.resolve("pac").apply { mkdirs() }
        pac.resolve("bn.ypf").writeText("fake")
        yuris.resolve("SA.ALD").writeText("fake")
        assertEquals(EngineType.YURIS, EngineScanner.detectEngine(yuris).engine)

        val cs2 = temporaryFolder.newFolder("CS2 With Stray Ald")
        val config = cs2.resolve("config").apply { mkdirs() }
        config.resolve("startup.xml").writeText("<startup/>")
        cs2.resolve("scene.int").writeText("int")
        cs2.resolve("image.int").writeText("int")
        cs2.resolve("SA.ALD").writeText("fake")
        assertEquals(EngineType.CATSYSTEM2, EngineScanner.detectEngine(cs2).engine)
    }

    @Test
    fun nestedDataGamedataAldDoesNotCountAsXs35Feature() {
        // GAMEDATA/ 特征只在根目录直接子目录生效：data/gamedata/ 嵌套同名目录不贡献特征
        val gameRoot = temporaryFolder.newFolder("Nested Gamedata")
        val nested = gameRoot.resolve("data").resolve("GAMEDATA")
        nested.mkdirs()
        nested.resolve("SA.ALD").writeText("fake")
        nested.resolve("System39.ini").writeText("fake")

        assertNotEquals(EngineType.XSYS35, EngineScanner.detectEngine(gameRoot).engine)
    }

    /** 构造 RealLive `SEEN.TXT` 头：首项 offset/length 指向 `0x1d0` 场景头。 */
    private fun realliveToc(): ByteArray {
        val bytes = ByteArray(0x100)
        writeU32(bytes, 0, 0x40)
        writeU32(bytes, 4, 16)
        writeU32(bytes, 0x40, 0x1d0)
        return bytes
    }

    private fun writeU32(bytes: ByteArray, at: Int, value: Int) {
        bytes[at] = (value and 0xFF).toByte()
        bytes[at + 1] = ((value ushr 8) and 0xFF).toByte()
        bytes[at + 2] = ((value ushr 16) and 0xFF).toByte()
        bytes[at + 3] = ((value ushr 24) and 0xFF).toByte()
    }
}
