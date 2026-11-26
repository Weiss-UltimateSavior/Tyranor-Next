package com.tyranor.next.core.game.scan

import com.tyranor.next.core.engine.EngineType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.charset.Charset

/**
 * System4（xsystem4）识别：根目录 `System40.ini` / `AliceStart.ini` 为硬特征，根目录 `*.ain` 为辅助；
 * 带 `System39.ini` / `system39.ain` 的目录不判 System4。
 *
 * 顺序：XSYS4 规则位于所有既有引擎与 ONS 之后、UNKNOWN 之前；XSYS35 的三条规则带 `!isSystem4` 护栏，
 * 因此 System4 目录（根目录常含 `*.ald`）不会被 XSYS35 吞掉，而既有引擎目录混入杂散 System40.ini 仍判原引擎。
 */
class EngineScannerXs4Test {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun detectsXs4FromSystem40IniAndAinAs97() {
        val gameRoot = temporaryFolder.newFolder("Rance6")
        gameRoot.resolve("System40.ini").writeText("fake")
        gameRoot.resolve("Rance.ain").writeText("fake")

        val detection = EngineScanner.detectEngine(gameRoot)

        assertEquals(EngineType.XSYS4, detection.engine)
        assertEquals(97, detection.confidence)
        assertEquals(EngineScanner.LAUNCH_TARGET_GAME_DIR, detection.launchTarget)
    }

    @Test
    fun detectsXs4FromAliceStartIniWithoutAinAs90() {
        val gameRoot = temporaryFolder.newFolder("AliceStart Only")
        gameRoot.resolve("AliceStart.ini").writeText("fake")

        val detection = EngineScanner.detectEngine(gameRoot)

        assertEquals(EngineType.XSYS4, detection.engine)
        assertEquals(90, detection.confidence)
    }

    @Test
    fun detectsXs4FromAliceStartIniWithAinAs97() {
        val gameRoot = temporaryFolder.newFolder("AliceStart With Ain")
        gameRoot.resolve("AliceStart.ini").writeText("fake")
        gameRoot.resolve("main.ain").writeText("fake")

        assertEquals(97, EngineScanner.detectEngine(gameRoot).confidence)
    }

    @Test
    fun detectsXs4CaseInsensitive() {
        val gameRoot = temporaryFolder.newFolder("Xs4 Case Insensitive")
        gameRoot.resolve("system40.INI").writeText("fake")
        gameRoot.resolve("MAIN.AIN").writeText("fake")

        val detection = EngineScanner.detectEngine(gameRoot)

        assertEquals(EngineType.XSYS4, detection.engine)
        assertEquals(97, detection.confidence)
    }

    @Test
    fun nestedIniDoesNotCountAsXs4Feature() {
        val gameRoot = temporaryFolder.newFolder("Nested Xs4 Ini")
        val data = gameRoot.resolve("data").apply { mkdirs() }
        data.resolve("System40.ini").writeText("fake")
        data.resolve("main.ain").writeText("fake")

        assertNotEquals(EngineType.XSYS4, EngineScanner.detectEngine(gameRoot).engine)
    }

    @Test
    fun nestedAinDoesNotCountAsAinFeature() {
        val gameRoot = temporaryFolder.newFolder("Nested Ain")
        gameRoot.resolve("System40.ini").writeText("fake")
        val data = gameRoot.resolve("data").apply { mkdirs() }
        data.resolve("main.ain").writeText("fake")

        val detection = EngineScanner.detectEngine(gameRoot)

        assertEquals(EngineType.XSYS4, detection.engine)
        assertEquals(90, detection.confidence)
    }

    @Test
    fun rootAldAndAlkDoNotStealSystem4FromXs35() {
        // System 4 根目录常含 *.ald / *.alk（资源包）：XSYS35 规则受 isSystem4 护栏约束，不得被 hasAldScript 抢判
        val withAin = temporaryFolder.newFolder("Rance6BA")
        withAin.resolve("System40.ini").writeText("fake")
        withAin.resolve("main.ain").writeText("fake")
        withAin.resolve("Rance6BA.ald").writeText("fake")
        withAin.resolve("Rance6BA.alk").writeText("fake")
        val withAinDetection = EngineScanner.detectEngine(withAin)
        assertEquals(EngineType.XSYS4, withAinDetection.engine)
        assertEquals(97, withAinDetection.confidence)

        val withoutAin = temporaryFolder.newFolder("Rance6BA No Ain")
        withoutAin.resolve("AliceStart.ini").writeText("fake")
        withoutAin.resolve("Rance6BA.ald").writeText("fake")
        withoutAin.resolve("Rance6BB.ald").writeText("fake")
        withoutAin.resolve("Rance6BA.alk").writeText("fake")
        val withoutAinDetection = EngineScanner.detectEngine(withoutAin)
        assertEquals(EngineType.XSYS4, withoutAinDetection.engine)
        assertEquals(90, withoutAinDetection.confidence)
    }

    @Test
    fun system39MarkerKeepsXs35Detection() {
        val gameRoot = temporaryFolder.newFolder("System39 With AliceStart")
        gameRoot.resolve("System39.ini").writeText("fake")
        gameRoot.resolve("AliceStart.ini").writeText("fake")
        gameRoot.resolve("SA.ALD").writeText("fake")

        val detection = EngineScanner.detectEngine(gameRoot)

        assertEquals(EngineType.XSYS35, detection.engine)
        assertEquals(96, detection.confidence)
    }

    @Test
    fun system39AinIsNotSystem4() {
        // system39.ain 是 System 3.x 标记：既不计作 hasAin，也阻止 System4 判定
        val gameRoot = temporaryFolder.newFolder("System39 Ain With System40")
        gameRoot.resolve("System40.ini").writeText("fake")
        gameRoot.resolve("system39.ain").writeText("fake")

        assertNotEquals(EngineType.XSYS4, EngineScanner.detectEngine(gameRoot).engine)
    }

    @Test
    fun existingEnginesAreNotStolenByStraySystem40Ini() {
        val siglus = temporaryFolder.newFolder("Siglus With System40")
        siglus.resolve("Gameexe.dat").writeText("fake")
        siglus.resolve("Scene.pck").writeText("fake")
        siglus.resolve("System40.ini").writeText("fake")
        assertEquals(EngineType.SIGLUS, EngineScanner.detectEngine(siglus).engine)

        val reallive = temporaryFolder.newFolder("RealLive With System40")
        reallive.resolve("Gameexe.ini").writeText("[REGNAME]")
        reallive.resolve("SEEN.TXT").writeBytes(realLiveSeenArchive())
        reallive.resolve("System40.ini").writeText("fake")
        assertEquals(EngineType.REALLIVE, EngineScanner.detectEngine(reallive).engine)

        val avg32 = temporaryFolder.newFolder("AVG32 With System40")
        avg32.resolve("Gameexe.ini").writeText("[REGNAME]")
        avg32.resolve("SEEN001.TXT").writeText("TPC32")
        avg32.resolve("System40.ini").writeText("fake")
        assertEquals(EngineType.AVG32, EngineScanner.detectEngine(avg32).engine)

        val uk2 = temporaryFolder.newFolder("UK2 With System40")
        uk2.resolve("UK2.CFG").writeText("fake")
        uk2.resolve("System40.ini").writeText("fake")
        assertEquals(EngineType.UK2, EngineScanner.detectEngine(uk2).engine)

        val fvp = temporaryFolder.newFolder("FVP With System40")
        fvp.resolve("Marguerite.hcb").writeText("fake")
        fvp.resolve("graph.bin").writeText("fake")
        fvp.resolve("System40.ini").writeText("fake")
        assertEquals(EngineType.FVP, EngineScanner.detectEngine(fvp).engine)

        val yuris = temporaryFolder.newFolder("YURIS With System40")
        yuris.resolve("yscfg.dat").writeText("fake")
        yuris.resolve("pac").apply { mkdirs() }.resolve("bn.ypf").writeText("fake")
        yuris.resolve("System40.ini").writeText("fake")
        assertEquals(EngineType.YURIS, EngineScanner.detectEngine(yuris).engine)

        val cs2 = temporaryFolder.newFolder("CS2 With System40")
        cs2.resolve("config").apply { mkdirs() }.resolve("startup.xml").writeText("<startup/>")
        cs2.resolve("scene.int").writeText("int")
        cs2.resolve("image.int").writeText("int")
        cs2.resolve("System40.ini").writeText("fake")
        assertEquals(EngineType.CATSYSTEM2, EngineScanner.detectEngine(cs2).engine)
    }

    @Test
    fun titleHintReadsShiftJisGameName() {
        val gameRoot = temporaryFolder.newFolder("Title Sjis")
        val title = "\u30e9\u30f3\u30b9".toByteArray(Charset.forName("Shift_JIS"))
        gameRoot.resolve("System40.ini").writeBytes(
            "GameName = \"".toByteArray(Charsets.US_ASCII) + title + "\"\n".toByteArray(Charsets.US_ASCII),
        )

        assertEquals("\u30e9\u30f3\u30b9", EngineScanner.detectEngine(gameRoot).titleHint)
    }

    @Test
    fun titleHintFallsBackToGbkWhenShiftJisIsInvalid() {
        // GB2312 二级汉字 E0FE：Shift-JIS 解码出现 U+FFFD，应回退 GBK
        val title = byteArrayOf(0xE0.toByte(), 0xFE.toByte())
        val expected = String(title, Charset.forName("GBK"))
        val gameRoot = temporaryFolder.newFolder("Title Gbk")
        gameRoot.resolve("System40.ini").writeBytes(
            "GameName = \"".toByteArray(Charsets.US_ASCII) + title + "\"\n".toByteArray(Charsets.US_ASCII),
        )

        val hint = EngineScanner.detectEngine(gameRoot).titleHint
        assertEquals(expected, hint)
        assertFalse(hint.orEmpty().contains('\uFFFD'))
    }

    @Test
    fun titleHintDecodesGbkWhenShiftJisWouldLookLikeHalfwidthKana() {
        // GBK「我的游戏」：SJIS 解码为半角片假名且无 U+FFFD，应取 GBK
        val gameRoot = temporaryFolder.newFolder("Title Gbk Kana")
        gameRoot.resolve("System40.ini").writeBytes(
            "GameName = \"".toByteArray(Charsets.US_ASCII) +
                hexBytes("ced2b5c4d3cecfb7") +
                "\"\n".toByteArray(Charsets.US_ASCII),
        )

        assertEquals("\u6211\u7684\u6e38\u620f", EngineScanner.detectEngine(gameRoot).titleHint)
    }

    @Test
    fun titleHintReadsShiftJisJapaneseTitleWithKatakana() {
        val gameRoot = temporaryFolder.newFolder("Title Sjis Katakana")
        gameRoot.resolve("AliceStart.ini").writeBytes(
            "GameName = \"".toByteArray(Charsets.US_ASCII) +
                hexBytes("90ed8d91838983938358") +
                "\"\n".toByteArray(Charsets.US_ASCII),
        )

        assertEquals("\u6226\u56fd\u30e9\u30f3\u30b9", EngineScanner.detectEngine(gameRoot).titleHint)
    }

    @Test
    fun titleHintIsNullWithoutGameName() {
        val gameRoot = temporaryFolder.newFolder("Title Missing")
        gameRoot.resolve("System40.ini").writeText("SaveFolder = \"Saves\"\n")
        gameRoot.resolve("main.ain").writeText("fake")

        val detection = EngineScanner.detectEngine(gameRoot)

        assertEquals(EngineType.XSYS4, detection.engine)
        assertNull(detection.titleHint)
    }

    @Test
    fun titleHintPrefersSystem40IniOverAliceStart() {
        val gameRoot = temporaryFolder.newFolder("Title Priority")
        gameRoot.resolve("System40.ini").writeText("GameName = \"FromSystem40\"\n")
        gameRoot.resolve("AliceStart.ini").writeText("GameName = \"FromAliceStart\"\n")

        assertEquals("FromSystem40", EngineScanner.detectEngine(gameRoot).titleHint)
    }

    @Test
    fun gamedataSystem39MarkerKeepsXs35WithRootAliceStart() {
        // GAMEDATA/System39.ini 是 System 3.x 标记：根目录 AliceStart.ini 不得把它判成 System4
        val gameRoot = temporaryFolder.newFolder("GAMEDATA System39 With Root AliceStart")
        gameRoot.resolve("AliceStart.ini").writeText("fake")
        val gamedata = gameRoot.resolve("GAMEDATA").apply { mkdirs() }
        gamedata.resolve("System39.ini").writeText("fake")
        gamedata.resolve("SA.ALD").writeText("fake")

        val detection = EngineScanner.detectEngine(gameRoot)

        assertEquals(EngineType.XSYS35, detection.engine)
        assertEquals(96, detection.confidence)
    }

    @Test
    fun straySystem40IniKeepsArtemis() {
        assertStrayIniKeepsEngine(EngineType.ARTEMIS) { it.resolve("root.pfs").writeText("fake") }
    }

    @Test
    fun straySystem40IniKeepsTyrano() {
        assertStrayIniKeepsEngine(EngineType.TYRANO) {
            it.resolve("index.html").writeText("fake")
            it.resolve("tyrano").mkdirs()
        }
    }

    @Test
    fun straySystem40IniKeepsRpgMv() {
        assertStrayIniKeepsEngine(EngineType.RPG_MV) {
            it.resolve("index.html").writeText("fake")
            it.resolve("js").mkdirs()
            it.resolve("js/rpg_core.js").writeText("fake")
        }
    }

    @Test
    fun straySystem40IniKeepsRpgMz() {
        assertStrayIniKeepsEngine(EngineType.RPG_MZ) {
            it.resolve("index.html").writeText("fake")
            it.resolve("js").mkdirs()
            it.resolve("js/rmmz_core.js").writeText("fake")
        }
    }

    @Test
    fun straySystem40IniKeepsVn() {
        assertStrayIniKeepsEngine(EngineType.VN) {
            it.resolve("index.html").writeText("fake")
            it.resolve("globaldata.vndata").writeText("fake")
        }
    }

    @Test
    fun straySystem40IniKeepsRpgMaker() {
        assertStrayIniKeepsEngine(EngineType.RPGMAKER) { it.resolve("Game.rgss3a").writeText("fake") }
    }

    @Test
    fun straySystem40IniKeepsRenPy() {
        assertStrayIniKeepsEngine(EngineType.RENPY) {
            it.resolve("game").mkdirs()
            it.resolve("game/script.rpy").writeText("fake")
        }
    }

    @Test
    fun straySystem40IniKeepsWebOther() {
        assertStrayIniKeepsEngine(EngineType.WEB_OTHER) { it.resolve("index.html").writeText("fake") }
    }

    @Test
    fun straySystem40IniKeepsKirikiri() {
        assertStrayIniKeepsEngine(EngineType.KIRIKIRI) { it.resolve("data.xp3").writeText("fake") }
    }

    @Test
    fun straySystem40IniKeepsOns() {
        assertStrayIniKeepsEngine(EngineType.ONS) { it.resolve("nscript.dat").writeText("fake") }
    }

    @Test
    fun rance6StyleLayoutIsSystem4() {
        val gameRoot = temporaryFolder.newFolder("Rance6 Layout")
        gameRoot.resolve("System40.ini").writeText("GameName = \"Rance VI\"\n")
        gameRoot.resolve("Rance6.ain").writeText("fake")
        gameRoot.resolve("Rance6BA.ald").writeText("fake")
        gameRoot.resolve("Rance6GA.ald").writeText("fake")
        gameRoot.resolve("Rance6.alk").writeText("fake")
        gameRoot.resolve("Data").apply { mkdirs() }.resolve("DungeonData.dlf").writeText("fake")
        gameRoot.resolve("Manual").apply { mkdirs() }.resolve("index.html").writeText("fake")
        gameRoot.resolve("Rance6.exe").writeText("fake")
        gameRoot.resolve("icon.ico").writeText("fake")

        val detection = EngineScanner.detectEngine(gameRoot)

        assertEquals(EngineType.XSYS4, detection.engine)
        assertEquals(97, detection.confidence)
        assertEquals("Rance VI", detection.titleHint)
    }

    /** 既有引擎真实特征（[layout]）+ 杂散根目录 System40.ini，仍应判为 [expected]。 */
    private fun assertStrayIniKeepsEngine(expected: EngineType, layout: (File) -> Unit) {
        val gameRoot = temporaryFolder.newFolder("Stray ${expected.name}")
        layout(gameRoot)
        gameRoot.resolve("System40.ini").writeText("fake")

        assertEquals(expected, EngineScanner.detectEngine(gameRoot).engine)
    }

    /** 十六进制字节串 → 字节数组（测试字节不依赖平台默认编码）。 */
    private fun hexBytes(text: String): ByteArray =
        ByteArray(text.length / 2) { i -> text.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}
