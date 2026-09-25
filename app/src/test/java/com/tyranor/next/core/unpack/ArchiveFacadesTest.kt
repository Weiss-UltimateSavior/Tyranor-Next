package com.tyranor.next.core.unpack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

/** Rust 桥 facade 的纯解析回归（不加载 native 库）：列表 JSON 与结果 JSON。 */
class ArchiveFacadesTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun backendDetectionPrefersExtensionThenSniffsMagic() {
        // 扩展名错配但内容是 XP3 魔数：嗅探纠正（SAF 下常见）。
        val mislabeled = temporaryFolder.newFile("game.dat")
        mislabeled.writeBytes(
            byteArrayOf(0x58, 0x50, 0x33, 0x0D, 0x0A, 0x20, 0x0A, 0x1A, 0x8B.toByte(), 0x67) +
                "payload".toByteArray(),
        )
        assertEquals(ArchiveBackend.XP3, detectArchiveBackend(mislabeled))
        // 无扩展名但内容是 PFS 魔数。
        val noExt = temporaryFolder.newFile("noext")
        noExt.writeBytes(byteArrayOf(0x70, 0x66, 0x38) + ByteArray(64))
        assertEquals(ArchiveBackend.PFS, detectArchiveBackend(noExt))
        // 扩展名优先：内容垃圾但扩展名明确时不读文件。
        val named = temporaryFolder.newFile("real.xp3")
        named.writeBytes("junk".toByteArray())
        assertEquals(ArchiveBackend.XP3, detectArchiveBackend(named))
        val namedPfs = temporaryFolder.newFile("empty.pfs")
        assertEquals(ArchiveBackend.PFS, detectArchiveBackend(namedPfs))
        // 魔数不明回 null，调用方出本地化错误。
        val unknown = temporaryFolder.newFile("mystery.bin")
        unknown.writeBytes("junk-junk-junk".toByteArray())
        assertNull(detectArchiveBackend(unknown))
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
    fun pfsListParsesFilesAndDirs() {
        val entries = PfsArchive.parseListEntries(
            """[{"n":"system","s":0,"d":true,"e":false},""" +
                """{"n":"system.ini","s":5522,"d":false,"e":false}]""",
        )
        assertEquals(2, entries.size)
        assertTrue(entries[0].isDirectory)
        assertEquals(5522L, entries[1].size)
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
            try {
                PfsArchive.parseListEntries(bad)
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
            try {
                PfsArchive.parseListEntries(bad)
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
