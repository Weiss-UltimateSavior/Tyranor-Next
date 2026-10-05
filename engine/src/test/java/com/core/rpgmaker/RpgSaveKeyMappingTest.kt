package com.core.rpgmaker

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 存档键映射的**单一实现**契约。
 *
 * 引擎按 [RpgSaveKeyMapping] 读写落盘名，应用侧转化按同一实现写入——两处漂移会导致
 * 「转化写进去、引擎读不到」且无任何报错。本测试把引擎的实际解析结果与共享映射对齐，
 * 应用侧另有对应契约（`RpgSaveFormatTest.appNamingMatchesEngineMapping`），
 * 二者合起来保证 app == 共享实现 == engine。
 */
class RpgSaveKeyMappingTest {

    private fun root(): File = java.nio.file.Files.createTempDirectory("keymap").toFile()

    /** 引擎实际解析出的文件名必须与共享映射一致（这是接线的核心断言）。 */
    @Test
    fun engineResolutionMatchesSharedMapping() {
        val dir = root()
        val keys = listOf(
            "RPG Global", "RPG Config", "RPG File1", "RPG File999", "RPG File3bak",
            "RPG タイトル File1",           // YEP_SaveCore 带标题
            "file1", "global", "config",    // MZ 形态
            "config.bak",
        )
        keys.forEach { key ->
            val resolved = RpgMakerStorage.resolveFile(dir, key, ".bin")
            assertEquals(
                "键 '$key' 的解析结果必须等于共享映射",
                RpgSaveKeyMapping.canonicalFileName(key.trim(), ".bin"),
                resolved?.name,
            )
        }
    }

    /** 直接可用的键（MZ 形态）保持原名，避免既有存档失联。 */
    @Test
    fun plainKeysKeepRawName() {
        val dir = root()
        listOf("file1", "global", "config").forEach { key ->
            assertEquals("$key.bin", RpgMakerStorage.resolveFile(dir, key, ".bin")?.name)
        }
    }

    /** 含特殊字符的键（MV 形态）走确定性哈希名。 */
    @Test
    fun specialKeysMapToDeterministicHash() {
        val dir = root()
        listOf("RPG Global", "RPG File1", "RPG タイトル File1").forEach { key ->
            val name = RpgMakerStorage.resolveFile(dir, key, ".bin")?.name
            assertEquals("key_${RpgSaveKeyMapping.sha256Hex(key)}.bin", name)
            assertTrue("必须被识别为哈希名", RpgSaveKeyMapping.isHashedFileName(name))
        }
    }

    /** 非法键一律拒绝（键是数据不是路径）。 */
    @Test
    fun invalidKeysAreRejected() {
        val dir = root()
        assertNull(RpgMakerStorage.resolveFile(dir, "../escape", ".bin"))
        assertNull(RpgMakerStorage.resolveFile(dir, "a/b", ".bin"))
        assertNull(RpgMakerStorage.resolveFile(dir, "a\\b", ".bin"))
        assertNull(RpgMakerStorage.resolveFile(dir, "", ".bin"))
        assertNull(RpgMakerStorage.resolveFile(dir, null, ".bin"))
        assertNull(RpgMakerStorage.resolveFile(dir, "key", ".exe"))
    }

    /** 同一键多次调用结果稳定（确定性映射，不含时间/随机成分）。 */
    @Test
    fun mappingIsDeterministic() {
        val dir = root()
        val first = RpgMakerStorage.resolveFile(dir, "RPG File7", ".bin")?.name
        repeat(3) {
            assertEquals(first, RpgMakerStorage.resolveFile(dir, "RPG File7", ".bin")?.name)
        }
        assertEquals(first, RpgSaveKeyMapping.canonicalFileName("RPG File7", ".bin"))
    }

    /** 读取优先复用已存在的 legacy 原始键文件（兼容历史落盘），不迁移新写入。 */
    @Test
    fun legacyRawKeyFileIsReusedWhenPresent() {
        val dir = root()
        val legacy = File(dir, "RPG File5.bin").apply { writeText("LEGACY") }
        val resolved = RpgMakerStorage.resolveFile(dir, "RPG File5", ".bin")
        assertEquals("既有 legacy 文件必须被复用（否则历史存档失联）", legacy.name, resolved?.name)
    }

    /** legacy 不存在时写入哈希名（两者不会同时产生，槽位仍唯一）。 */
    @Test
    fun hashNameIsUsedWhenNoLegacyFileExists() {
        val dir = root()
        val resolved = RpgMakerStorage.resolveFile(dir, "RPG File6", ".bin")
        assertEquals("key_${RpgSaveKeyMapping.sha256Hex("RPG File6")}.bin", resolved?.name)
    }
}
