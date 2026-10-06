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

    // ---------- 方案命名去重（后缀必须先预留长度，再截断） ----------

    @Test
    fun uniqueNameKeepsSuffixForFullLengthBase() {
        // 满长名称重复：后缀必须保留，否则副本名被截回原名、列表里无法区分
        val full = "n".repeat(InputRemapRepository.PROFILE_NAME_MAX_LENGTH)
        val name = InputRemapRepository.uniqueName(full, setOf(full))

        assertEquals(InputRemapRepository.PROFILE_NAME_MAX_LENGTH, name.length)
        assertTrue("满长名称的去重结果必须与原名不同，实际：$name", name != full)
        assertTrue("后缀应保留在末尾，实际：$name", name.endsWith(" 2"))
    }

    @Test
    fun uniqueNameChecksTruncatedCandidateForCollision() {
        // 按未截断串查重会漏掉「截断后才撞名」：base 满长 + 已存在 base 与「截断版 + 2」
        val full = "n".repeat(InputRemapRepository.PROFILE_NAME_MAX_LENGTH)
        val colliding = full.dropLast(2) + " 2"
        val name = InputRemapRepository.uniqueName(full, setOf(full, colliding))

        assertTrue("必须避开截断后的候选名，实际：$name", name !in setOf(full, colliding))
        assertTrue(name.endsWith(" 3"))
    }

    @Test
    fun uniqueNameReturnsBaseWhenAvailable() {
        val name = InputRemapRepository.uniqueName("My Layout", setOf("Other"))
        assertEquals("My Layout", name)
    }

    @Test
    fun uniqueNameTruncatesOverlongBaseAndChecksIt() {
        // 超长名先截到上限，且以截断结果为查重目标
        val overlong = "x".repeat(40)
        val truncated = "x".repeat(InputRemapRepository.PROFILE_NAME_MAX_LENGTH)
        val name = InputRemapRepository.uniqueName(overlong, setOf(truncated))

        assertTrue("截断后撞名必须继续去重，实际：$name", name != truncated)
        assertTrue(name.endsWith(" 2"))
    }

    @Test
    fun uniqueNameFallsBackForBlankInput() {
        val name = InputRemapRepository.uniqueName("   ", emptySet())
        assertTrue("空白名应回退到非空名称，实际：'$name'", name.isNotBlank())
        assertTrue(name.length <= InputRemapRepository.PROFILE_NAME_MAX_LENGTH)
    }

    @Test
    fun uniqueNameAlwaysTerminatesAndStaysWithinLimit() {
        // 序号被占满：仍须返回一个不与现有集合冲突、且不超长的名称。
        // 占用名必须按**实际后缀长度**构造（` 10` 是 3 字符而非 2），否则两位数序号那轮
        // 构造出的名字与函数候选不匹配，函数会在该轮提前返回、兜底分支根本执行不到。
        val full = "n".repeat(InputRemapRepository.PROFILE_NAME_MAX_LENGTH)
        val existing = HashSet<String>()
        existing.add(full)
        val occupiedIndexes = 2..999
        for (index in occupiedIndexes) {
            val suffix = " $index"
            val room = (InputRemapRepository.PROFILE_NAME_MAX_LENGTH - suffix.length).coerceAtLeast(0)
            existing.add(full.take(room) + suffix)
        }

        val name = InputRemapRepository.uniqueName(full, existing)
        assertTrue("结果不得超长，实际长度 ${name.length}", name.length <= InputRemapRepository.PROFILE_NAME_MAX_LENGTH)
        assertTrue("结果不得与现有名冲突", name !in existing)
        // 1..999 全部占满（full 本身即序号 1 的形态），因此结果必然来自时间戳兜底分支，
        // 而不是某个序号候选；用「不再是 base+序号」的形式确认真的走到兜底
        assertFalse(
            "序号未耗尽时不应走兜底：$name",
            name.matches(Regex("^n+ \\d+$")),
        )
    }
}
