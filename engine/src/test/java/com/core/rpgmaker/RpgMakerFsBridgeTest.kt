package com.core.rpgmaker

import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 文件系统桥的行为锁定（纯 JVM，临时目录当真机游戏目录用）。
 *
 * 这些用例之所以放在 Kotlin 而非 JS harness：harness 跑的是 bridges.js 的**仿真**实现，
 * 只能验证仿真与 Kotlin 语义一致，无法证明 Kotlin 本体正确。审核发现的
 * 「writeText 缺上限」正是因为替身实现了上限、本体没有——harness 全绿而真机会静默落盘。
 */
class RpgMakerFsBridgeTest {

    private fun newRoot(): File = createTempDir(prefix = "fsbridge-test")

    @Test
    fun writeTextRejectsOversizedPayload() {
        val root = newRoot()
        val bridge = RpgMakerFsBridge(root, root)
        // 17 MiB 文本超过 16 MiB 上限：必须拒绝并返回 false（此前无任何校验）
        val huge = "A".repeat(17 * 1024 * 1024)
        assertFalse("超限文本写入必须被拒绝", bridge.writeText("huge.txt", huge))
        assertFalse("被拒绝的文件不应落盘", File(root, "huge.txt").exists())
    }

    @Test
    fun writeTextAcceptsPayloadWithinLimit() {
        val root = newRoot()
        val bridge = RpgMakerFsBridge(root, root)
        assertTrue(bridge.writeText("ok.txt", "hello 日本語"))
        assertEquals("hello 日本語", bridge.readText("ok.txt"))
    }

    @Test
    fun writeBase64RejectsOversizedPayload() {
        val root = newRoot()
        val bridge = RpgMakerFsBridge(root, root)
        val encoded = Base64.getEncoder().encodeToString(ByteArray(17 * 1024 * 1024))
        assertFalse(bridge.writeBase64("huge.bin", encoded))
    }

    /** 回归：exists 必须真的查磁盘（此前只做路径归一化，对所有路径都返回 true）。 */
    @Test
    fun existsChecksDiskNotJustPathValidity() {
        val root = newRoot()
        val bridge = RpgMakerFsBridge(root, root)
        bridge.writeText("present.txt", "x")
        assertTrue(bridge.exists("present.txt"))
        assertFalse("不存在的文件必须返回 false", bridge.exists("missing.txt"))
        assertFalse("多级不存在的路径也必须返回 false", bridge.exists("a/b/c/nope.txt"))
    }

    /** 回归：读失败的原因码不能被一律伪装成 ENOENT。 */
    @Test
    fun errorForDistinguishesFailureCauses() {
        val root = newRoot()
        val bridge = RpgMakerFsBridge(root, root)
        assertEquals("ENOENT", bridge.errorFor("nope.txt"))
        // 越界与非法路径 → EPERM（不是「文件不存在」）
        assertEquals("EPERM", bridge.errorFor("../../etc/passwd"))
        assertEquals("EPERM", bridge.errorFor(""))
        assertEquals("EPERM", bridge.errorFor(null))
        // 目录 → EISDIR（读目录在 Node 里也是 EISDIR）
        bridge.makeDirs("adir")
        assertEquals("EISDIR", bridge.errorFor("adir"))
    }

    /** 路径边界：越界与含尾随空格的文件名（后者此前被 trim 掉而永远无法访问）。 */
    @Test
    fun pathBoundaryRejectsOutsideAndKeepsTrailingSpaces() {
        val root = newRoot()
        val bridge = RpgMakerFsBridge(root, root)
        assertFalse("越界写入必须被拒绝", bridge.writeText("../escaped.txt", "x"))
        assertFalse(bridge.exists("../../etc/passwd"))

        // 尾随空格是真实文件名的一部分（部分素材如此命名），不得 trim
        assertTrue(bridge.writeText("name   .txt", "spaced"))
        assertTrue(bridge.exists("name   .txt"))
        assertEquals("spaced", bridge.readText("name   .txt"))
    }

    /** 回归：读超限必须给 E2BIG，而不是让插件按「文件不存在」处理。 */
    @Test
    fun oversizedReadReportsE2BigNotEnoent() {
        val root = newRoot()
        val bridge = RpgMakerFsBridge(root, root)
        File(root, "big.bin").writeBytes(ByteArray(17 * 1024 * 1024))
        assertTrue(bridge.exists("big.bin"))
        assertNull("超限读应返回 null", bridge.readText("big.bin"))
        assertEquals("原因码必须是 E2BIG", "E2BIG", bridge.errorFor("big.bin"))
    }

    /** stdin 式小文件读写往返（含非 ASCII 与二进制）。 */
    @Test
    fun roundTripsTextAndBinary() {
        val root = newRoot()
        val bridge = RpgMakerFsBridge(root, root)
        assertNull("不存在的文件应返回 null", bridge.readText("none.txt"))

        val bytes = byteArrayOf(0x00, 0x01, 0xFF.toByte(), 0x7F)
        val encoded = Base64.getEncoder().encodeToString(bytes)
        assertTrue(bridge.writeBase64("bin.dat", encoded))
        assertEquals(encoded, bridge.readBase64("bin.dat"))

        assertTrue(bridge.writeText("utf8.txt", "日本語テキスト"))
        assertEquals("日本語テキスト", bridge.readText("utf8.txt"))
        assertNotNull(bridge.stat("utf8.txt"))
    }

    /** 目录相关：mkdirs / readdir / isDir / remove 的基本行为。 */
    @Test
    fun directoryOperationsWork() {
        val root = newRoot()
        val bridge = RpgMakerFsBridge(root, root)
        assertTrue(bridge.makeDirs("sub/dir"))
        assertTrue(bridge.isDir("sub/dir"))
        bridge.writeText("sub/dir/a.txt", "1")
        bridge.writeText("sub/dir/b.txt", "2")
        val listing = bridge.readdir("sub/dir")
        assertTrue("列目录应含 a.txt", listing.contains("a.txt"))
        assertTrue("列目录应含 b.txt", listing.contains("b.txt"))
        assertTrue(bridge.remove("sub/dir/a.txt"))
        assertFalse(bridge.exists("sub/dir/a.txt"))
    }

    /** baseDir/dataDir 的契约（__dirname 与 nw.gui.App.dataPath 的来源）。 */
    @Test
    fun exposesBaseAndDataDirectories() {
        val gameRoot = newRoot()
        val contentRoot = File(gameRoot, "www").apply { mkdirs() }
        val bridge = RpgMakerFsBridge(gameRoot, contentRoot)
        assertEquals(contentRoot.absolutePath, bridge.baseDir())
        assertEquals(File(gameRoot, "AppData").absolutePath, bridge.dataDir())
        // 相对路径按网页根解析
        assertTrue(bridge.writeText("data.txt", "x"))
        assertTrue(File(contentRoot, "data.txt").exists())
    }

    /** 控制字符必须被拒绝（NUL 注入）。 */
    @Test
    fun rejectsPathWithNulByte() {
        val root = newRoot()
        val bridge = RpgMakerFsBridge(root, root)
        assertFalse(bridge.writeText("evil\u0000.txt", "x"))
        assertFalse(bridge.exists("evil\u0000.txt"))
    }
}

/** 与 stdlib 同名函数冲突时的本地封装（保持测试可读）。 */
private fun createTempDir(prefix: String): File =
    java.nio.file.Files.createTempDirectory(prefix).toFile()
