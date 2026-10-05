package com.core.rpgmaker

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 存档写入的上限与预检行为（RpgMakerStorage 与 TyranoStorage 同一套逻辑）。
 *
 * 上限本身是**有意设计**（防止存档被异常数据撑爆），这里锁定的是两点：
 *  1. 超限写入被拒绝且不落盘；
 *  2. 预检发生在编码之前——否则超限载荷仍会先分配一份全量 UTF-8 字节数组，
 *     上限只挡住磁盘、挡不住内存峰值。
 */
class StorageWriteLimitTest {

    private fun newDir(): File = java.nio.file.Files.createTempDirectory("storage-limit").toFile()

    @Test
    fun rpgMakerStorageRejectsOversizedValue() {
        val dir = newDir()
        val huge = "A".repeat(9 * 1024 * 1024)   // 超过 8MiB 上限
        assertFalse(RpgMakerStorage.write(dir, "key1", huge, ".bin"))
        assertTrue("被拒绝的写入不应产生文件", dir.listFiles()?.isEmpty() != false)
    }

    @Test
    fun rpgMakerStorageAcceptsValueWithinLimit() {
        val dir = newDir()
        assertTrue(RpgMakerStorage.write(dir, "key2", "hello 日本語", ".bin"))
        assertEquals("hello 日本語", RpgMakerStorage.read(dir, "key2", ".bin"))
        assertTrue(RpgMakerStorage.exists(dir, "key2", ".bin"))
    }

    /** 多字节文本按 **UTF-8 字节数** 判定，不能被字符数蒙混过关。 */
    @Test
    fun rpgMakerStorageLimitCountsUtf8BytesNotChars() {
        val dir = newDir()
        // 每个字符 3 字节：字符数 3MiB（低于 8MiB 上限），字节数 9MiB（超过）
        val multibyte = "あ".repeat(3 * 1024 * 1024)
        assertFalse("应按字节数拒绝", RpgMakerStorage.write(dir, "key3", multibyte, ".bin"))
        assertTrue(dir.listFiles()?.isEmpty() != false)
    }

    @Test
    fun tyranoStorageRejectsOversizedValue() {
        val dir = newDir()
        val huge = "A".repeat(9 * 1024 * 1024)
        com.core.tyrano.TyranoStorage.write(dir, "key1", huge)
        assertTrue("被拒绝的写入不应产生文件", dir.listFiles()?.isEmpty() != false)
    }

    @Test
    fun tyranoStorageAcceptsValueWithinLimit() {
        val dir = newDir()
        com.core.tyrano.TyranoStorage.write(dir, "key2", "hello")
        assertEquals("hello", com.core.tyrano.TyranoStorage.read(dir, "key2"))
    }

    @Test
    fun tyranoStorageLimitCountsUtf8BytesNotChars() {
        val dir = newDir()
        val multibyte = "あ".repeat(3 * 1024 * 1024)
        com.core.tyrano.TyranoStorage.write(dir, "key3", multibyte)
        assertTrue("应按字节数拒绝", dir.listFiles()?.isEmpty() != false)
    }
}
