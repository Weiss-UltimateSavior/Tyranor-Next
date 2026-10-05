package com.tyranor.next.core.unpack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/** Rust 桥 facade 的纯解析回归（不加载 native 库）：列表 JSON 与结果 JSON。 */
class ArchiveFacadesTest {

    @Test
    fun archiveDetectionMatchesByExtensionOnly() {
        // 仅按扩展名判定（CONTEXT.md：不做内容嗅探）。
        assertTrue(isArchiveFileName("real.xp3"))
        assertTrue(isArchiveFileName("REAL.XP3"))
        assertFalse(isArchiveFileName("game.dat"))
        assertFalse(isArchiveFileName("game.xp3.bak"))
        // PFS 等其他封包格式不再支持，按名不命中。
        assertFalse(isArchiveFileName("game.pfs"))
    }

    @Test
    fun xp3ListParsesFilesAndDirs() {
        val entries = Xp3Archive.parseListEntries(
            """[{"n":"data","s":0,"d":true,"e":false},""" +
                """{"n":"data/startup.tjs","s":128,"d":false,"e":false},""" +
                """{"n":"patch.xp3","s":10,"d":false,"e":false}]""",
        )
        assertEquals(3, entries.size)
        assertTrue(entries[0].isDirectory)
        assertEquals("data/startup.tjs", entries[1].name)
        assertEquals(128L, entries[1].size)
        assertFalse(entries[2].isDirectory)
    }

    @Test
    fun listRejectsMalformedJson() {
        // 注：org.json 对尾随字符宽容，此处只断言严格非法输入。
        for (bad in listOf("", "not json", "[{\"n\":}]")) {
            try {
                Xp3Archive.parseListEntries(bad)
                fail("expected IOException for $bad")
            } catch (error: IOException) {
                // expected
            }
        }
        assertTrue(Xp3Archive.parseListEntries("[]").isEmpty())
    }

    @Test
    fun listRejectsMissingFieldsAndWrongTypes() {
        // 缺字段 / 类型错必须抛，不能落空条目或崩溃。
        val badCases = listOf(
            """[{"s":10,"d":false}]""",
            """[{"n":"a","d":false}]""",
            """[{"n":"a","s":10}]""",
            """[{"n":"a","s":"x","d":false}]""",
            """[{"n":"a","s":10,"d":"yes"}]""",
            """[null]""",
            """[42]""",
        )
        for (bad in badCases) {
            try {
                Xp3Archive.parseListEntries(bad)
                fail("expected IOException for $bad")
            } catch (error: IOException) {
                // expected
            }
        }
    }

    @Test
    fun resultCountsParse() {
        val counts = NativeArchiveOp.parseResult(
            """{"total":10,"success":9,"error":1}""",
            "test",
        )
        assertEquals(10, counts.total)
        assertEquals(9, counts.success)
        assertEquals(1, counts.error)
        try {
            NativeArchiveOp.parseResult("garbage", "test")
            fail("expected IOException")
        } catch (error: IOException) {
            // expected
        }
        try {
            NativeArchiveOp.parseResult("""{"total":1}""", "test")
            fail("expected IOException for missing fields")
        } catch (error: IOException) {
            // expected
        }
    }
}
