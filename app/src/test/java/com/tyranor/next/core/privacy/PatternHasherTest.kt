package com.tyranor.next.core.privacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 图案哈希钉住：同一盐 + 同一点序必须得到同一哈希（解锁判定依赖），
 * 点序/盐任一不同必须得到不同哈希；非法输入一律不可哈希（不得落盘半套凭据）。
 */
class PatternHasherTest {

    private val salt = ByteArray(PatternHasher.SALT_BYTES) { it.toByte() }

    @Test
    fun `同一盐与点序得到同一哈希`() {
        val points = listOf(0, 3, 6, 7, 8)
        assertEquals(PatternHasher.hash(salt, points), PatternHasher.hash(salt, points))
    }

    @Test
    fun `不同盐得到不同哈希`() {
        val points = listOf(0, 3, 6, 7, 8)
        val otherSalt = ByteArray(PatternHasher.SALT_BYTES) { (it + 1).toByte() }
        assertNotEquals(PatternHasher.hash(salt, points), PatternHasher.hash(otherSalt, points))
    }

    @Test
    fun `不同点序得到不同哈希`() {
        assertNotEquals(
            PatternHasher.hash(salt, listOf(0, 3, 6, 7, 8)),
            PatternHasher.hash(salt, listOf(8, 7, 6, 3, 0)),
        )
    }

    @Test
    fun `点数不足最少点数不可哈希`() {
        assertNull(PatternHasher.hash(salt, emptyList()))
        assertNull(PatternHasher.hash(salt, listOf(0, 1, 2)))
        assertNull(PatternHasher.hash(salt, listOf(4)))
    }

    @Test
    fun `越界或重复的点序不可哈希`() {
        assertNull(PatternHasher.hash(salt, listOf(-1, 0, 1, 2)))
        assertNull(PatternHasher.hash(salt, listOf(0, 3, 6, 9)))
        assertNull(PatternHasher.hash(salt, listOf(0, 3, 3, 6)))
    }

    @Test
    fun `空盐不可哈希`() {
        assertNull(PatternHasher.hash(ByteArray(0), listOf(0, 3, 6, 7)))
    }

    @Test
    fun `新盐长度固定且两两不同`() {
        val first = PatternHasher.newSalt()
        val second = PatternHasher.newSalt()
        assertEquals(PatternHasher.SALT_BYTES, first.size)
        assertNotEquals(first.toList(), second.toList())
    }

    @Test
    fun `归一化会补上同行同列同对角之间的中间点`() {
        // 0→2 同行隔一点 → 补 1；0→6 同列隔一点 → 补 3；0→8 对角隔一点 → 补 4
        assertEquals(listOf(0, 1, 2), PatternHasher.normalize(listOf(0, 2)))
        assertEquals(listOf(0, 3, 6), PatternHasher.normalize(listOf(0, 6)))
        assertEquals(listOf(0, 4, 8), PatternHasher.normalize(listOf(0, 8)))
        // 相邻点不需要补
        assertEquals(listOf(0, 1), PatternHasher.normalize(listOf(0, 1)))
        // 非共线的跳跃不补（0→5 是斜走一格，中间没有格点）
        assertEquals(listOf(0, 5), PatternHasher.normalize(listOf(0, 5)))
    }

    @Test
    fun `归一化后同一形状得到同一哈希`() {
        // 用户精确划过 0→1→2 与直接 0→2 是同一形状，必须得到同一哈希（否则保存与校验会误拒）
        assertEquals(
            PatternHasher.hash(salt, listOf(0, 1, 2)),
            PatternHasher.hash(salt, listOf(0, 2)),
        )
        assertEquals(
            PatternHasher.hash(salt, listOf(0, 3, 6, 7)),
            PatternHasher.hash(salt, listOf(0, 6, 7)),
        )
    }

    @Test
    fun `归一化对非法点序返回 null`() {
        assertNull(PatternHasher.normalize(emptyList()))
        assertNull(PatternHasher.normalize(listOf(0, 9)))
        assertNull(PatternHasher.normalize(listOf(0, 1, 1)))
    }

    @Test
    fun `哈希输出为 64 位十六进制字符串`() {
        val hash = PatternHasher.hash(salt, listOf(0, 3, 6, 7, 8))!!
        assertEquals(64, hash.length)
        assertTrue(hash.all { it in '0'..'9' || it in 'a'..'f' })
    }
}
