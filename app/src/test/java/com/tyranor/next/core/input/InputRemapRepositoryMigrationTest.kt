package com.tyranor.next.core.input

import com.core.input.PadProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 旧布局迁移的写盘决策回归。
 *
 * 关键不变量：**只有全部待写方案都已存在或写入成功，才可以置位「已迁移」标记**。
 * 否则一次写盘失败（磁盘满、目录创建失败）会让标记落下，而下次启动在标记处直接返回，
 * 用户的旧 `__touch_pad.js` 布局永远迁不进来——静默数据丢失。
 */
class InputRemapRepositoryMigrationTest {

    private fun profile(id: String): PadProfile = PadProfile.defaultProfile(id, id)

    @Test
    fun allWritesSucceedMarksComplete() {
        val outcome = InputRemapRepository.writeMigratedProfiles(
            profiles = listOf(profile("a"), profile("b")),
            exists = { false },
            write = { true },
        )

        assertEquals(listOf("a", "b"), outcome.migrated)
        assertTrue("全部写成功时允许置位标记", outcome.allWritten)
    }

    @Test
    fun failedWriteLeavesMarkerUnsetForRetry() {
        // 第二个方案写失败（如磁盘满）：不得置位标记，否则首个方案已写入、
        // 第二个永远不再重试
        val outcome = InputRemapRepository.writeMigratedProfiles(
            profiles = listOf(profile("a"), profile("b")),
            exists = { false },
            write = { it.id != "b" },
        )

        assertEquals(listOf("a"), outcome.migrated)
        assertFalse("任一次写入失败都必须留待下次重试", outcome.allWritten)
    }

    @Test
    fun existingProfileIsSkippedAndDoesNotBlockMarker() {
        // 已存在的方案文件（用户在游戏内改过或已有自建方案）不重写，也不算失败
        val written = ArrayList<String>()
        val outcome = InputRemapRepository.writeMigratedProfiles(
            profiles = listOf(profile("a"), profile("b")),
            exists = { it == "a" },
            write = { written.add(it.id); true },
        )

        assertEquals(listOf("b"), written)
        assertEquals(listOf("b"), outcome.migrated)
        assertTrue("跳过已存在文件不应阻止置位标记", outcome.allWritten)
    }

    @Test
    fun allProfilesAlreadyExistIsCompleteWithNoWrites() {
        val outcome = InputRemapRepository.writeMigratedProfiles(
            profiles = listOf(profile("a"), profile("b")),
            exists = { true },
            write = { error("不应写入已存在的方案") },
        )

        assertTrue(outcome.migrated.isEmpty())
        assertTrue("无可写内容时视为完成（迁移标记可置位，避免每次启动重算）", outcome.allWritten)
    }

    @Test
    fun everyProfileFailingLeavesMarkerUnset() {
        val outcome = InputRemapRepository.writeMigratedProfiles(
            profiles = listOf(profile("a"), profile("b")),
            exists = { false },
            write = { false },
        )

        assertTrue(outcome.migrated.isEmpty())
        assertFalse(outcome.allWritten)
    }
}
