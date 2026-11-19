package com.tyranor.next.core.privacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 图案哈希钉住：同一盐 + 同一点序必须得到同一哈希（解锁判定依赖），
 * 点序/盐任一不同必须得到不同哈希；非法输入一律不可哈希（不得落盘半套凭据）。
 *
 * 点序按用户实际按下顺序精确匹配，不做形状归一化：
 * 0→2 与 0→1→2 是不同图案，跳点 / 回扫变体不能解锁。
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
    fun `跳点与完整经过中间点是不同图案`() {
        // 不按形状归一化：直接跳 0→2 与完整经过 0→1→2 必须是不同凭据
        assertNotEquals(
            PatternHasher.hash(salt, listOf(0, 2, 5, 8)),
            PatternHasher.hash(salt, listOf(0, 1, 2, 5, 8)),
        )
        // 回扫已经被经过的点同样不折叠
        assertNotEquals(
            PatternHasher.hash(salt, listOf(0, 2, 1, 4)),
            PatternHasher.hash(salt, listOf(0, 1, 2, 4)),
        )
    }

    @Test
    fun `点数不足最少点数不可哈希`() {
        assertNull(PatternHasher.hash(salt, emptyList()))
        assertNull(PatternHasher.hash(salt, listOf(0, 1, 2)))
        assertNull(PatternHasher.hash(salt, listOf(4)))
        assertNull(PatternHasher.hash(salt, listOf(0, 2)))
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
    fun `图案合法性校验与哈希使用同一条规则`() {
        assertTrue(PatternHasher.isValidPattern(listOf(0, 1, 2, 5)))
        assertFalse(PatternHasher.isValidPattern(listOf(0, 1, 2)))
        assertFalse(PatternHasher.isValidPattern(listOf(-1, 0, 1, 2)))
        assertFalse(PatternHasher.isValidPattern(listOf(0, 1, 1, 2)))
    }

    @Test
    fun `新盐长度固定且两两不同`() {
        val first = PatternHasher.newSalt()
        val second = PatternHasher.newSalt()
        assertEquals(PatternHasher.SALT_BYTES, first.size)
        assertNotEquals(first.toList(), second.toList())
    }

    @Test
    fun `哈希输出为 64 位十六进制字符串`() {
        val hash = PatternHasher.hash(salt, listOf(0, 3, 6, 7, 8))!!
        assertEquals(64, hash.length)
        assertTrue(hash.all { it in '0'..'9' || it in 'a'..'f' })
    }
}
