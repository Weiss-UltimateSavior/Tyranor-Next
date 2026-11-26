package com.tyranor.next.core.game.scan

import com.tyranor.next.core.engine.EngineType
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * RealLive / AVG32 / UK2（framebuffer 引擎）识别：
 * `SEEN.TXT` 按内容区分 PACL（AVG32）与 10000 项 TOC（RealLive），
 * UK2 走 `UK2.CFG` / MES 头，另有散装 `SEEN###.TXT` / `SEEN####.TXT` 名称宽度特征。
 */
class EngineScannerFramebufferTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

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
        gameRoot.resolve("SEEN.TXT").writeBytes(realLiveSeenArchive())

        val detection = EngineScanner.detectEngine(gameRoot)

        assertEquals(EngineType.REALLIVE, detection.engine)
        assertEquals(96, detection.confidence)
    }

    @Test
    fun detectsRealLiveWithOnlyAHighNumberedScene() {
        val gameRoot = temporaryFolder.newFolder("Sparse RealLive")
        gameRoot.resolve("GAMEEXE.INI").writeText("#REGNAME")
        gameRoot.resolve("REALLIVE.EXE").createNewFile()
        gameRoot.resolve("SEEN.TXT").writeBytes(realLiveSeenArchive(sceneNumber = 9_999))

        assertEquals(EngineType.REALLIVE, EngineScanner.detectEngine(gameRoot).engine)
    }

    @Test
    fun ignoresTruncatedArchiveAndEmptyLooseScene() {
        val gameRoot = temporaryFolder.newFolder("Truncated RealLive")
        gameRoot.resolve("GAMEEXE.INI").writeText("#REGNAME")
        val archive = realLiveSeenArchive(sceneNumber = 4).dropLast(1).toByteArray()
        gameRoot.resolve("SEEN.TXT").writeBytes(archive)
        gameRoot.resolve("SEEN9999.TXT").createNewFile()

        assertEquals(EngineType.SIGLUS, EngineScanner.detectEngine(gameRoot).engine)
        assertEquals(null, SeenArchiveProbe.detect(size = null) { archive.inputStream() })
    }

    @Test
    fun readsArchiveWithUnknownSize() {
        val archive = realLiveSeenArchive(sceneNumber = 8_000, headerSize = 0x1cc)
        assertEquals(EngineType.REALLIVE, SeenArchiveProbe.detect(size = null) { archive.inputStream() })
    }

    @Test
    fun detectsRealLiveFromDatFolderArchive() {
        val gameRoot = temporaryFolder.newFolder("RealLive Dat")
        gameRoot.resolve("Gameexe.ini").writeText("[FOLDNAME.TXT]")
        val dat = gameRoot.resolve("DAT")
        dat.mkdirs()
        dat.resolve("SEEN.TXT").writeBytes(realLiveSeenArchive())

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

/** RealLive index and one scene header. Empty slots model a sparse SEEN.TXT. */
internal fun realLiveSeenArchive(sceneNumber: Int = 0, headerSize: Int = 0x1d0): ByteArray {
    val tableBytes = 10_000 * 8
    return ByteBuffer.allocate(tableBytes + headerSize)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putInt(sceneNumber * 8, tableBytes)
        .putInt(sceneNumber * 8 + 4, headerSize)
        .putInt(tableBytes, headerSize)
        .array()
}
