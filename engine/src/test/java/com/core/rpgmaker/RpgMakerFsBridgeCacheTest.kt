package com.core.rpgmaker

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * fs 桥缓存与单次解析的行为锁定（纯 JVM）。
 *
 * 关键不变量：宽松名解析可以用「目录 + mtime」缓存，但**写操作必须让它立刻失效**，
 * 且未命中不得被缓存成永久不存在——否则插件「先写后读」会读到自己刚写的数据为 null。
 *
 * 注意：删除后能否命中是自证的（[RpgMakerFsBridge.fuzzyEntry] 末尾会 `exists()` 复核），
 * 所以这里锁的是更严格的那半：**新写入的名字**必须立刻可被宽松名命中。
 */
class RpgMakerFsBridgeCacheTest {

    private var gameRoot: File? = null

    @After
    fun tearDown() {
        gameRoot?.deleteRecursively()
        gameRoot = null
    }

    private fun newBridge(): Pair<File, RpgMakerFsBridge> {
        val root = java.nio.file.Files.createTempDirectory("fsbridge-cache").toFile()
        gameRoot = root
        val contentRoot = File(root, "www").apply { mkdirs() }
        return root to RpgMakerFsBridge(root, contentRoot)
    }

    /**
     * 缓存过目录索引后，经桥写入的文件必须能被同一次会话的宽松解析命中。
     *
     * 写入会把目录 mtime 顶到「现在」，因此这里把 mtime **冻回**首次 miss 时的值：
     * 没有 invalidate 的话索引会认为目录没变、继续给出缺项，测试才有意义。
     */
    @Test
    fun bridgeWriteInvalidatesDirectoryIndexForFuzzyRead() {
        val (gameRoot, bridge) = newBridge()
        val dataDir = File(gameRoot, "www/data").apply { mkdirs() }

        // 先制造一次 miss：这一步会把 data/ 的条目索引（不含目标名）缓存起来
        assertFalse(bridge.exists("data/table\u30001.json"))
        val frozen = dataDir.lastModified()
        assertTrue(bridge.writeText("data/table 1.json", "CONTENT"))
        dataDir.setLastModified(frozen)

        assertTrue("写操作必须让目录索引失效", bridge.exists("data/table\u30001.json"))
        assertEquals("CONTENT", bridge.readText("data/table\u30001.json"))
    }

    /** 目录 mtime 变化（外部增删）同样让索引失效。 */
    @Test
    fun directoryMtimeChangeInvalidatesIndex() {
        val (gameRoot, bridge) = newBridge()
        val dataDir = File(gameRoot, "www/data").apply { mkdirs() }

        assertFalse(bridge.exists("data/ext\u30001.json"))
        File(dataDir, "ext 1.json").writeText("EXTERNAL")
        // 目录 mtime 与条目变更落在同一毫秒时无法靠 mtime 区分，测试里显式推进时间戳
        dataDir.setLastModified(dataDir.lastModified() + 1_000)

        assertEquals("EXTERNAL", bridge.readText("data/ext\u30001.json"))
    }

    /** 未命中不缓存：同名文件稍后出现（外部写入）立即可见，不需要等缓存过期。 */
    @Test
    fun missingFileIsNotCachedPermanently() {
        val (gameRoot, bridge) = newBridge()
        val dataDir = File(gameRoot, "www/data").apply { mkdirs() }

        assertFalse(bridge.exists("data/late.json"))
        File(dataDir, "late.json").writeText("LATE")
        assertTrue(bridge.exists("data/late.json"))
        assertEquals("LATE", bridge.readText("data/late.json"))
    }

    /**
     * `stat` 对「磁盘与 asar 都不存在」返回空串，由兼容层兜底成
     * `{file:false,dir:false,size:0}`（见 `__nwjs_polyfill_v2.js` 的 `statObject`）。
     */
    @Test
    fun statOfMissingPathIsEmptyString() {
        val (_, bridge) = newBridge()
        assertEquals("", bridge.stat("nope.json"))
        assertEquals("", bridge.stat("no/such/dir/nope.json"))
    }
}
