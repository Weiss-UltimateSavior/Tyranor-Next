package com.tyranor.next.core.game.scan

import com.tyranor.next.core.engine.EngineType
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream

/**
 * RealLive / AVG32 / UK2（framebuffer 引擎）识别：
 * `SEEN.TXT` 按内容区分 PACL（AVG32）与 10000 项 TOC（RealLive），
 * UK2 走 `UK2.CFG` / MES 头，另有散装 `SEEN###.TXT` / `SEEN####.TXT` 名称宽度特征。
 */
class EngineScannerFramebufferTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

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

    private fun paclArchive(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("PACL".toByteArray(Charsets.US_ASCII))
        out.write(ByteArray(64))
        return out.toByteArray()
    }

    @Test
    fun detectsAvg32FromPaclSeenArchive() {
        val gameRoot = temporaryFolder.newFolder("AVG32 Air")
        gameRoot.resolve("Gameexe.ini").writeText("[REGNAME]")
        gameRoot.resolve("SEEN.TXT").writeBytes(paclArchive())

        val detection = EngineScanner.detectEngine(gameRoot)

        assertEquals(EngineType.AVG32, detection.engine)
        assertEquals(96, detection.confidence)
        assertEquals(EngineScanner.LAUNCH_TARGET_GAME_DIR, detection.launchTarget)
    }

    @Test
    fun detectsRealLiveFromTocSeenArchive() {
        val gameRoot = temporaryFolder.newFolder("RealLive Clannad")
        gameRoot.resolve("Gameexe.ini").writeText("[REGNAME]")
        gameRoot.resolve("SEEN.TXT").writeBytes(realliveToc())

        val detection = EngineScanner.detectEngine(gameRoot)

        assertEquals(EngineType.REALLIVE, detection.engine)
        assertEquals(96, detection.confidence)
    }

    @Test
    fun detectsRealLiveFromDatFolderArchive() {
        val gameRoot = temporaryFolder.newFolder("RealLive Dat")
        gameRoot.resolve("Gameexe.ini").writeText("[FOLDNAME.TXT]")
        val dat = gameRoot.resolve("DAT")
        dat.mkdirs()
        dat.resolve("SEEN.TXT").writeBytes(realliveToc())

        val detection = EngineScanner.detectEngine(gameRoot)

        assertEquals(EngineType.REALLIVE, detection.engine)
        assertEquals(96, detection.confidence)
    }

    @Test
    fun detectsLooseSceneNameWidth() {
        val avg32 = temporaryFolder.newFolder("AVG32 Loose")
        avg32.resolve("Gameexe.ini").writeText("[REGNAME]")
        avg32.resolve("SEEN001.TXT").writeText("TPC32")
        assertEquals(EngineType.AVG32, EngineScanner.detectEngine(avg32).engine)
        assertEquals(92, EngineScanner.detectEngine(avg32).confidence)

        val reallive = temporaryFolder.newFolder("RealLive Loose")
        reallive.resolve("Gameexe.ini").writeText("[REGNAME]")
        reallive.resolve("SEEN0001.TXT").writeText("fake")
        assertEquals(EngineType.REALLIVE, EngineScanner.detectEngine(reallive).engine)
        assertEquals(92, EngineScanner.detectEngine(reallive).confidence)
    }

    @Test
    fun detectsUk2FromConfigAndMesHeader() {
        val withConfig = temporaryFolder.newFolder("UK2 Config")
        withConfig.resolve("UK2.CFG").writeText("fake")
        assertEquals(EngineType.UK2, EngineScanner.detectEngine(withConfig).engine)
        assertEquals(96, EngineScanner.detectEngine(withConfig).confidence)

        val withMes = temporaryFolder.newFolder("UK2 Mes")
        withMes.resolve("START.MES").writeBytes(
            "<< UK2 TEXT Ver1.00 >>".toByteArray(Charsets.US_ASCII),
        )
        assertEquals(EngineType.UK2, EngineScanner.detectEngine(withMes).engine)
        assertEquals(90, EngineScanner.detectEngine(withMes).confidence)
    }

    @Test
    fun siglusGameexeIniAloneStaysSiglus() {
        val gameRoot = temporaryFolder.newFolder("Siglus Only")
        gameRoot.resolve("Gameexe.ini").writeText("[SCREEN_SIZE]")
        assertEquals(EngineType.SIGLUS, EngineScanner.detectEngine(gameRoot).engine)
        assertEquals(85, EngineScanner.detectEngine(gameRoot).confidence)
    }

    @Test
    fun emptyDirectoryIsUnknown() {
        val gameRoot = temporaryFolder.newFolder("Nothing")
        assertEquals(EngineType.UNKNOWN, EngineScanner.detectEngine(gameRoot).engine)
    }
}
