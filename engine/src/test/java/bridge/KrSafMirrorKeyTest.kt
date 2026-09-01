package bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/**
 * KrSafMirror.mirrorKey 回归：key 是存量镜像目录名（games/&lt;名&gt;-&lt;key&gt;）与 SAF 索引文件名的
 * 唯一标识，算法一旦漂移，升级后存档管理将指向错误目录（审计 P0-2 修复依赖此函数与
 * prepare 严格一致）。此处用独立实现的 sha256 断言锁定算法。
 */
class KrSafMirrorKeyTest {

    private fun expectedSha256Prefix16(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(16)

    @Test
    fun `key is sha256 of sourceReference newline logicalPath truncated to 16`() {
        val key = KrSafMirror.mirrorKey("content://com.android.externalstorage.documents/tree/primary%3AGames", "/storage/0000-1111/game")
        assertEquals(expectedSha256Prefix16("content://com.android.externalstorage.documents/tree/primary%3AGames\n/storage/0000-1111/game"), key)
    }

    @Test
    fun `key is deterministic and 16 lowercase hex chars`() {
        val a = KrSafMirror.mirrorKey("uri-a", "/storage/0000-1111/game")
        val b = KrSafMirror.mirrorKey("uri-a", "/storage/0000-1111/game")
        assertEquals(a, b)
        assertTrue(a.length == 16)
        assertTrue(a.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun `different uri or path yields different keys`() {
        val base = KrSafMirror.mirrorKey("uri-a", "/storage/0000-1111/game")
        assertNotEquals(base, KrSafMirror.mirrorKey("uri-b", "/storage/0000-1111/game"))
        assertNotEquals(base, KrSafMirror.mirrorKey("uri-a", "/storage/0000-2222/game"))
        assertNotEquals(base, KrSafMirror.mirrorKey("uri-a", "/storage/0000-1111/game/"))
    }
}
