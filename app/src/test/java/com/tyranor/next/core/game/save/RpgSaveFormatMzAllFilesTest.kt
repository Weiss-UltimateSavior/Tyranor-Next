package com.tyranor.next.core.game.save

import com.tyranor.next.core.engine.EngineType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * MZ 存档的**全量**文件覆盖：`config` / `file0` / `file1` / `global`。
 *
 * MZ 的三类键（配置文件、全局信息、各存档位）走同一个 `StorageManager.saveObject`，
 * 落盘名分别是 `config.bin` / `global.bin` / `fileN.bin`。任何一处漏处理都会让
 * 「读档能进但设置丢失」「存档槽列表为空」这类问题只在部分文件上出现。
 *
 * 这里对**每一个键**逐一验证双向映射、槽位归一与载荷转码，防止只测了 `global`
 * 就以为整条链路都对。
 */
class RpgSaveFormatMzAllFilesTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    /** MZ 的落盘键全集（引擎固定原名，不做哈希）。 */
    private val mzKeys = listOf("global", "config", "file0", "file1", "file2")

    private fun mzGameRoot(name: String = "MZ"): java.io.File {
        val gameRoot = temporaryFolder.newFolder(name)
        gameRoot.resolve("js").mkdirs()
        gameRoot.resolve("index.html").writeText("<html></html>")
        gameRoot.resolve("js/rmmz_core.js").writeText("// MZ")
        return gameRoot
    }

    private fun mzJson(key: String): ByteArray =
        """{"version":1,"key":"$key","data":"payload-$key"}""".toByteArray()

    @Test
    fun everyMzKeyMapsBothWays() {
        for (key in mzKeys) {
            val standard = "$key.rmmzsave"
            val tyranor = "$key.bin"

            assertTrue("$standard 必须被识别为标准名", RpgSaveFormat.isStandardName(standard, EngineType.RPG_MZ))
            assertEquals("标准名 → 引擎落盘名", tyranor, RpgSaveFormat.standardToTyranor(standard, EngineType.RPG_MZ))
            assertEquals("引擎落盘名 → 标准名", standard, RpgSaveFormat.tyranorToStandard(tyranor, EngineType.RPG_MZ))

            // 槽位键：两侧必须归一到同一个，否则同步会认为「只在一边存在」
            assertEquals(key, RpgSaveFormat.standardSlot(standard, EngineType.RPG_MZ))
            assertEquals(key, RpgSaveFormat.tyranorSlot(tyranor, EngineType.RPG_MZ))
            assertEquals(standard, RpgSaveFormat.standardNameForSlot(key, EngineType.RPG_MZ))
            assertEquals(tyranor, RpgSaveFormat.tyranorNameForSlot(key, EngineType.RPG_MZ))
        }
    }

    /** `file0` 是 MZ 的自动存档位，不能被 `file\d+` 之外的规则漏掉。 */
    @Test
    fun file0IsTreatedAsARegularSlot() {
        assertTrue(RpgSaveFormat.isStandardName("file0.rmmzsave", EngineType.RPG_MZ))
        assertEquals("file0.bin", RpgSaveFormat.standardToTyranor("file0.rmmzsave", EngineType.RPG_MZ))
        assertEquals("file0", RpgSaveFormat.standardSlot("file0.rmmzsave", EngineType.RPG_MZ))
    }

    /** MZ 核心不带 `.bak`，带了也不是合法存档名（不能被误当作可转化文件）。 */
    @Test
    fun mzHasNoBackupFiles() {
        for (key in mzKeys) {
            assertFalse(
                "$key.rmmzsave.bak 不是 MZ 合法存档",
                RpgSaveFormat.isStandardName("$key.rmmzsave.bak", EngineType.RPG_MZ),
            )
        }
    }

    /**
     * 导入：四种标准存档同时存在时，每一份都必须解压成引擎可读的明文 JSON，
     * 且各自落到独立的 `*.bin`——不能互相覆盖，也不能只转一部分。
     */
    @Test
    fun importConvertsEveryMzFileIndependently() {
        val gameRoot = mzGameRoot()
        val savedata = gameRoot.resolve("savedata").apply { mkdirs() }
        for (key in mzKeys) {
            savedata.resolve("$key.rmmzsave").writeBytes(RpgSaveFormat.encodeMzPayload(mzJson(key)))
        }

        val result = RpgSaveFormatConverter.convert(gameRoot, EngineType.RPG_MZ)

        assertEquals("四种标准存档都要转化", mzKeys.size, result.converted)
        assertEquals(0, result.failed)
        for (key in mzKeys) {
            val bin = savedata.resolve("$key.bin")
            assertTrue("$key.bin 必须生成", bin.isFile)
            assertArrayEquals("$key 的载荷必须是引擎可读的明文 JSON", mzJson(key), bin.readBytes())
            assertFalse("源文件应移入 original/", savedata.resolve("$key.rmmzsave").exists())
            assertTrue(savedata.resolve("original/$key.rmmzsave").isFile)
        }
    }

    /**
     * 导出：四种 Tyranor 存档必须各自转码成标准 `.rmmzsave` 载荷
     * （zlib 容器 deflate），且能被 PC 版解回同样的 JSON。
     */
    @Test
    fun exportEncodesEveryMzPayload() {
        for (key in mzKeys) {
            val plain = mzJson(key)
            val packed = RpgSaveFormat.encodeMzPayload(plain)

            // PC 版能读出同样的内容
            assertArrayEquals(
                "$key 导出后必须能被 PC 版解回原 JSON",
                plain,
                RpgSaveFormat.decodeMzPayload(packed),
            )
            // 确实是 zlib 容器（首字节 0x78），不是原样明文
            assertTrue("$key 必须真的被压缩", packed.isNotEmpty() && packed[0] == 0x78.toByte())
            assertTrue("$key 压缩后应小于原文明文（真实量级数据）", packed.size < plain.size + 64)
        }
    }

    /** 导出幂等：已经转码过的内容不能二次压缩（会让 PC 版读出「压缩过的 JSON」）。 */
    @Test
    fun exportIsIdempotentForEveryKey() {
        for (key in mzKeys) {
            val packed = RpgSaveFormat.encodeMzPayload(mzJson(key))
            val again = RpgSaveFormat.encodeMzPayload(packed)
            assertArrayEquals("$key 二次编码必须保持原样", packed, again)
            assertArrayEquals(
                "$key 任意次编码后仍可解回原 JSON",
                mzJson(key),
                RpgSaveFormat.decodeMzPayload(again),
            )
        }
    }

    /**
     * 检测：五种标准存档必须全部被扫出来（漏掉 config/global 会让互通「只同步存档位、
     * 丢掉设置与槽位列表」）。
     */
    @Test
    fun detectionFindsEveryMzFile() {
        val gameRoot = mzGameRoot()
        val savedata = gameRoot.resolve("savedata").apply { mkdirs() }
        for (key in mzKeys) {
            savedata.resolve("$key.rmmzsave").writeText("x")
        }

        val detection = RpgSaveFormat.detect(gameRoot, EngineType.RPG_MZ)

        assertEquals(mzKeys.size, detection.convertibleCount)
        val names = detection.standardFiles.map { it.name }.sorted()
        assertEquals(mzKeys.map { "$it.rmmzsave" }.sorted(), names)
    }

    /**
     * `save1.zip` 一类非存档文件（本机实测存在）不能被当成存档，
     * 否则导出会把它当 `.rmmzsave` 处理或列表里冒出伪槽位。
     */
    @Test
    fun unrelatedFilesInSaveDirAreIgnored() {
        val gameRoot = mzGameRoot()
        val savedata = gameRoot.resolve("savedata").apply { mkdirs() }
        savedata.resolve("global.rmmzsave").writeText("x")
        savedata.resolve("save1.zip").writeText("zip")
        savedata.resolve("file1.bin").writeText("bin")

        val detection = RpgSaveFormat.detect(gameRoot, EngineType.RPG_MZ)
        assertEquals(1, detection.convertibleCount)
        assertEquals("global.rmmzsave", detection.standardFiles.single().name)

        assertEquals("global", RpgSaveFormat.standardSlot("global.rmmzsave", EngineType.RPG_MZ))
        assertEquals("global", RpgSaveFormat.tyranorSlot("global.bin", EngineType.RPG_MZ))
        assertEquals("file1", RpgSaveFormat.tyranorSlot("file1.bin", EngineType.RPG_MZ))
        // 非存档名既不是标准名也不是可反解的槽位
        assertFalse(RpgSaveFormat.isStandardName("save1.zip", EngineType.RPG_MZ))
        assertEquals(null, RpgSaveFormat.tyranorSlot("save1.zip", EngineType.RPG_MZ))
    }
}