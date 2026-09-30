package com.core.rpgmaker

import java.io.File
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * fs 桥读路径与 HTTP 服务器的**容忍度一致性**。
 *
 * 服务器承载引擎的全部资源加载（XHR 取 img/audio/data），其 `fuzzyNames` 分支对
 * 空白类别差异（U+3000 ↔ U+0020）做宽松匹配。若 fs 桥只做精确匹配，就会出现
 * 「引擎能加载素材、插件读不到同一文件」的分裂——本测试锁定两者一致。
 *
 * 同时锁定：宽松匹配**不得**放宽写入（写请求应作用于它明确指定的名字）。
 */
class RpgMakerFsBridgeFuzzyReadTest {

    private fun newBridge(): Pair<File, RpgMakerFsBridge> {
        val gameRoot = java.nio.file.Files.createTempDirectory("fuzzyread").toFile()
        val contentRoot = File(gameRoot, "www").apply { mkdirs() }
        return gameRoot to RpgMakerFsBridge(gameRoot, contentRoot)
    }

    /** 磁盘用半角空格、请求用全角空格——真实游戏（素材名被规范化）的情形。 */
    @Test
    fun readToleratesWhitespaceVariantLikeServer() {
        val (gameRoot, bridge) = newBridge()
        val dir = File(gameRoot, "www/data").apply { mkdirs() }
        File(dir, "table 1.json").writeText("CONTENT", StandardCharsets.UTF_8)

        // 请求用全角空格（U+3000）+ 不同空白数量
        val requested = "data/table\u30001.json"

        assertTrue("exists 应与服务器一样容忍空白差异", bridge.exists(requested))
        assertTrue("isFile 同上", bridge.isFile(requested))
        assertEquals("readText 必须能读到（此前模糊命中后重新精确解析会落空）", "CONTENT", bridge.readText(requested))
        val b64 = bridge.readBase64(requested)
        assertTrue("readBase64 必须能读到", b64 != null && b64.isNotEmpty())
        assertTrue("stat 应给出真实 size", bridge.stat(requested).contains("\"size\""))
    }

    /** 目录层级的空白差异同样容忍（逐段匹配）。 */
    @Test
    fun readToleratesWhitespaceVariantInDirectorySegments() {
        val (gameRoot, bridge) = newBridge()
        File(gameRoot, "www/img/pictures").mkdirs()
        File(gameRoot, "www/img/pictures/a b.png").writeText("PNG")

        assertTrue(bridge.exists("img/pictures/a\u3000b.png"))
        assertTrue(bridge.readText("img/pictures/a\u3000b.png") == "PNG")
    }

    /** 精确命中优先，不受宽松逻辑影响。 */
    @Test
    fun exactMatchStillWins() {
        val (gameRoot, bridge) = newBridge()
        val dir = File(gameRoot, "www/data").apply { mkdirs() }
        File(dir, "x.json").writeText("EXACT")
        File(dir, "x .json").writeText("SPACED")

        assertEquals("精确命中必须优先", "EXACT", bridge.readText("data/x.json"))
    }

    /** 歧义时拒绝（与 matcher 的既有契约一致），不猜。 */
    @Test
    fun ambiguousVariantIsRejected() {
        val (gameRoot, bridge) = newBridge()
        val dir = File(gameRoot, "www/data").apply { mkdirs() }
        File(dir, "ステ画面フキダシ .png").writeText("A")
        File(dir, "ステ画面フキダシ.png").writeText("B")

        assertFalse("仅差空白数量的歧义请求必须拒绝", bridge.exists("data/ステ画面フキダシ  .png"))
    }

    /**
     * 大小写歧义必须拒绝（与服务端一致）。
     *
     * 此前桥自留「大小写首中优先」分支：目录内同时存在 `A.json` 与 `a.json` 时会任选
     * 其一命中，而服务器对同一请求返回 404 —— 两边结论不一致会导致
     * 「引擎加载得到、插件读到另一个或读不到」。
     */
    @Test
    fun caseAmbiguityIsRejectedLikeServer() {
        val (gameRoot, bridge) = newBridge()
        val dir = File(gameRoot, "www/data").apply { mkdirs() }
        if (!File(dir, "Case.json").let { it.writeText("UPPER"); File(dir, "case.json").writeText("lower"); it.isFile }) {
            return   // 大小写不敏感文件系统上无法构造该情形
        }
        // 大小写不敏感文件系统会把后来者视作同一文件；仅在能构造出两个文件时断言
        val upper = File(dir, "Case.json")
        val lower = File(dir, "case.json")
        if (upper.canonicalPath == lower.canonicalPath) return

        assertFalse("大小写歧义请求必须拒绝（与服务端一致）", bridge.exists("data/CASE.json"))
    }

    /** 越界仍被拒绝，宽松匹配不得成为逃逸通道。 */
    @Test
    fun fuzzyReadDoesNotEscapeRoot() {
        val (gameRoot, bridge) = newBridge()
        // 放在游戏目录**之外**（gameRoot 的父目录）才算越界
        val secret = File(gameRoot.parentFile, "outside.txt").apply { writeText("SECRET") }
        assertTrue(secret.isFile)

        // 注意 `../x` 相对 www 解析到 gameRoot/x —— 那在游戏目录内、理应可访问；
        // 真正的越界必须跨出游戏根
        assertFalse(bridge.exists("../outside.txt"))
        assertFalse("跨出游戏根必须拒绝", bridge.exists("../../outside.txt"))
        assertNull(bridge.readText("../../outside.txt"))
        assertNull(bridge.readText(secret.absolutePath))
    }

    /** 真正缺失的文件仍不可见（宽松匹配不是「随便命中」）。 */
    @Test
    fun missingFileStaysInvisible() {
        val (_, bridge) = newBridge()
        assertFalse(bridge.exists("data/definitely-missing.json"))
        assertNull(bridge.readText("data/definitely-missing.json"))
    }

    /** 写入保持精确：不得因为存在「宽松可命中」的相邻文件就改写它。 */
    @Test
    fun writeStaysExactAndDoesNotTouchFuzzyNeighbour() {
        val (gameRoot, bridge) = newBridge()
        val dir = File(gameRoot, "www/data").apply { mkdirs() }
        val neighbour = File(dir, "config .json").apply { writeText("NEIGHBOUR") }

        assertTrue(bridge.writeText("data/config.json", "NEW"))

        assertEquals("相邻文件不得被改写", "NEIGHBOUR", neighbour.readText())
        assertEquals("应按精确名新建文件", "NEW", File(dir, "config.json").readText())
    }
}
