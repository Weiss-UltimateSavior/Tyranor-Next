package com.tyranor.next.core.net

import java.io.StringReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [readTextCapped] 限长读取纯函数回归（BK-22 随手补：该函数原本 private、仅经
 * [httpJsonResult] 间接触达，提为 internal 供两服务手写刷新路径共用后获得直接 JVM 面）：
 * 1. 限内/恰限正常返回全文；
 * 2. 超限抛 [IllegalStateException]（调用方按各自既有失败分支兜底）；
 * 3. 跨内部 16K 读缓冲边界的超长输入同样被限长截停。
 */
class HttpJsonReadTextCappedTest {

    @Test
    fun readsWithinLimitReturnFullText() {
        assertEquals("hello", StringReader("hello").readTextCapped(16))
        assertEquals("", StringReader("").readTextCapped(16))
    }

    @Test
    fun textExactlyAtLimitIsAccepted() {
        val exact = "abcdefgh"
        assertEquals(exact, StringReader(exact).readTextCapped(exact.length))
    }

    @Test
    fun textOverLimitThrowsIllegalState() {
        val over = "abcdefghi"
        val thrown = assertThrows(IllegalStateException::class.java) {
            StringReader(over).readTextCapped(over.length - 1)
        }
        assertTrue(thrown.message.orEmpty().isNotBlank())
    }

    @Test
    fun capHoldsAcrossInternalReadBufferBoundary() {
        // 内部读缓冲为 16K：用跨多次 read() 的 20K 输入验证累计长度判定
        val big = "x".repeat(20 * 1024)
        assertEquals(big, StringReader(big).readTextCapped(20 * 1024))
        assertThrows(IllegalStateException::class.java) {
            StringReader(big).readTextCapped(19 * 1024)
        }
    }
}
