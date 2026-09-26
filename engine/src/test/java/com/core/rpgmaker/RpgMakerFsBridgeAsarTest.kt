package com.core.rpgmaker

import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * asar 会话的「磁盘覆盖层」语义。
 *
 * 游戏打包成 app.asar 时，资源在压缩包内、磁盘上通常不存在同名文件。但磁盘上仍可能
 * 出现同名文件：用户打的补丁、插件自己写的数据、以及任何写回游戏目录的内容。
 *
 * 关键约束：本地 HTTP 服务器（承载引擎全部资源加载：XHR 取 data/、图片、音频）是
 * **磁盘优先**（`canonicalIfValid` 命中即返回，asar 仅作兜底）。若 fs 桥改成 asar 优先，
 * 同一个路径经由 XHR 与经由 fs 会解析出**不同内容**，且插件写出的文件会被 asar 遮蔽、
 * 读回旧值——表现为「配置/数据表改了却不生效」。
 *
 * 因此桥必须与服务器保持同一优先级：磁盘存在即用磁盘，否则回退 asar。
 */
class RpgMakerFsBridgeAsarTest {

    private val asarContent = """{"v":"from-asar"}"""
    private val diskContent = """{"v":"from-disk"}"""

    /** 构造最小可解析的 asar（格式见 AsarArchive 的头部解析）。 */
    private fun buildAsar(dir: File, entries: Map<String, ByteArray>): File {
        val filesJson = JSONObject()
        var offset = 0L
        for ((path, bytes) in entries) {
            filesJson.put(
                path,
                JSONObject().put("size", bytes.size).put("offset", offset.toString()),
            )
            offset += bytes.size
        }
        val root = JSONObject().put("files", filesJson)
        val jsonBytes = root.toString().toByteArray(StandardCharsets.UTF_8)
        // headerSize 需满足 headerSize >= 8 + jsonLen，且使数据区对齐
        val headerSize = ((8 + jsonBytes.size + 3) / 4) * 4

        val out = File(dir, "app.asar")
        DataOutputStream(FileOutputStream(out)).use { stream ->
            writeLe32(stream, 4)             // magic
            writeLe32(stream, headerSize)    // [4..8)
            writeLe32(stream, 4)             // [8..12) 跳过段
            writeLe32(stream, jsonBytes.size) // [12..16) json 长度
            stream.write(jsonBytes)
            repeat((8 + headerSize) - 16 - jsonBytes.size) { stream.write(0) }  // 对齐填充
            entries.values.forEach { stream.write(it) }
        }
        return out
    }

    private fun writeLe32(stream: DataOutputStream, value: Int) {
        stream.write(value and 0xFF)
        stream.write((value ushr 8) and 0xFF)
        stream.write((value ushr 16) and 0xFF)
        stream.write((value ushr 24) and 0xFF)
    }

    /** 建一个 asar 会话：压缩包内有资源，磁盘上另有一份同名文件（用户补丁/插件写入）。 */
    private fun newAsarSession(withDiskOverride: Boolean): Pair<File, RpgMakerFsBridge> {
        val gameRoot = File(java.nio.file.Files.createTempDirectory("asar-test").toFile(), "game").apply { mkdirs() }
        val contentRoot = File(gameRoot, "www").apply { mkdirs() }
        val asar = AsarArchive(
            buildAsar(
                gameRoot,
                mapOf(
                    "www/index.html" to "<html></html>".toByteArray(StandardCharsets.UTF_8),
                    "www/data/table.json" to asarContent.toByteArray(StandardCharsets.UTF_8),
                ),
            ),
        )
        if (withDiskOverride) {
            File(contentRoot, "data").mkdirs()
            File(contentRoot, "data/table.json").writeText(diskContent)
        }
        return gameRoot to RpgMakerFsBridge(gameRoot, contentRoot, asar)
    }

    /** 无磁盘覆盖时仍应从 asar 读到内容（覆盖层不能把包内资源挡住）。 */
    @Test
    fun readsFromAsarWhenNoDiskFileExists() {
        val (_, bridge) = newAsarSession(withDiskOverride = false)
        assertEquals(asarContent, bridge.readText("data/table.json"))
        assertTrue("asar 内的文件应可见", bridge.exists("data/table.json"))
        assertTrue("asar 内的文件应是文件而非目录", bridge.isFile("data/table.json"))
    }

    /** 磁盘同名文件必须优先（与 HTTP 服务器的解析顺序一致）。 */
    @Test
    fun diskFileTakesPrecedenceOverAsarEntry() {
        val (_, bridge) = newAsarSession(withDiskOverride = true)
        assertEquals("磁盘同名文件必须优先于 asar 条目", diskContent, bridge.readText("data/table.json"))
        val encoded = java.util.Base64.getEncoder().encodeToString(diskContent.toByteArray(StandardCharsets.UTF_8))
        assertEquals("readBase64 同样磁盘优先", encoded, bridge.readBase64("data/table.json"))
    }

    /** 写入后必须能读回自己写的内容（写盘被 asar 遮蔽会让插件以为「改了不生效」）。 */
    @Test
    fun writeIsReadableAfterwards() {
        val (gameRoot, bridge) = newAsarSession(withDiskOverride = false)
        val written = """{"v":"plugin-written"}"""
        assertTrue(bridge.writeText("data/table.json", written))
        assertTrue("写入应真的落到磁盘", File(gameRoot, "www/data/table.json").exists())
        assertEquals("写入后必须读回写入的内容，而不是 asar 里的旧值", written, bridge.readText("data/table.json"))
    }

    /** stat 的 size/mtime 必须反映磁盘文件，否则插件按旧 size 判断会误判。 */
    @Test
    fun statReflectsDiskFileWhenPresent() {
        val (gameRoot, bridge) = newAsarSession(withDiskOverride = false)
        val written = "0123456789"
        assertTrue(bridge.writeText("data/table.json", written))
        val stat = JSONObject(bridge.stat("data/table.json"))
        assertEquals("size 应来自磁盘文件", written.toByteArray(StandardCharsets.UTF_8).size.toLong(), stat.getLong("size"))
        assertTrue("mtime 应来自磁盘文件（非 0）", stat.getLong("mtime") > 0)
        assertTrue(File(gameRoot, "www/data/table.json").exists())
    }

    /**
     * 磁盘上的**目录**不遮蔽 asar 文件条目。
     *
     * 服务器的命中条件是「磁盘且 isFile」（`canonicalIfValid`），目录会继续走 asar；
     * 桥必须一致，否则同一路径在 XHR 与 fs 之间出现分歧。
     */
    @Test
    fun diskDirectoryDoesNotShadowAsarFileEntry() {
        val gameRoot = File(java.nio.file.Files.createTempDirectory("asar-dir").toFile(), "game").apply { mkdirs() }
        val contentRoot = File(gameRoot, "www").apply { mkdirs() }
        val asar = AsarArchive(
            buildAsar(
                gameRoot,
                mapOf(
                    "www/index.html" to "<html></html>".toByteArray(StandardCharsets.UTF_8),
                    "www/data/table.json" to asarContent.toByteArray(StandardCharsets.UTF_8),
                ),
            ),
        )
        // 磁盘上放一个同名目录（罕见但会让「目录优先」的实现把 asar 内容挡住）
        File(contentRoot, "data/table.json").mkdirs()
        val bridge = RpgMakerFsBridge(gameRoot, contentRoot, asar)

        assertTrue("磁盘同名目录仍应被视为目录", bridge.isDir("data/table.json"))
        assertFalse("此时它不是文件", bridge.isFile("data/table.json"))
        assertEquals("目录不应遮蔽 asar 文件条目", asarContent, bridge.readText("data/table.json"))
    }

    /**
     * 磁盘上的**文件**遮蔽 asar 同名**目录**时，readdir 不得回退 asar 索引。
     *
     * 覆盖层规则必须在 exists/isFile/isDir/stat/readdir 之间完全一致，否则同一路径
     * 会「既是文件又是目录」（isDir=false 但 readdir 列出子项），JS 消费方据此分支会错乱。
     */
    @Test
    fun diskFilePreventsAsarDirectoryFallbackInReaddir() {
        val gameRoot = File(java.nio.file.Files.createTempDirectory("asar-mask").toFile(), "game").apply { mkdirs() }
        val contentRoot = File(gameRoot, "www").apply { mkdirs() }
        val asar = AsarArchive(
            buildAsar(
                gameRoot,
                mapOf(
                    "www/index.html" to "<html></html>".toByteArray(StandardCharsets.UTF_8),
                    // asar 内 data 是目录，含一个子项
                    "www/data/table.json" to asarContent.toByteArray(StandardCharsets.UTF_8),
                ),
            ),
        )
        // 磁盘上把同名 `data` 造成**文件**（与 asar 的目录同名）
        File(contentRoot, "data").writeText("i am a file")
        val bridge = RpgMakerFsBridge(gameRoot, contentRoot, asar)

        assertTrue("磁盘条目是文件", bridge.isFile("data"))
        assertFalse("同一路径不应同时是目录", bridge.isDir("data"))
        assertEquals("磁盘文件存在时不得回退 asar 目录索引", "[]", bridge.readdir("data"))
    }

    /**
     * asar 条目超过读上限时必须在**读取之前**拒绝。
     *
     * AsarArchive 允许单条目至 256MiB，而 fs 桥的读上限是 16MiB；若先 read() 再判，
     * 就会为必然被拒的请求白占最多 256MiB 内存（且 errorFor 需给出 E2BIG）。
     * 注：asar 的条目偏移/大小要与文件实际内容一致（parse 阶段会校验范围），
     * 因此这里写入真实的超限数据。
     */
    @Test
    fun oversizedAsarEntryIsRejectedBeforeReading() {
        val gameRoot = File(java.nio.file.Files.createTempDirectory("asar-big").toFile(), "game").apply { mkdirs() }
        val contentRoot = File(gameRoot, "www").apply { mkdirs() }
        val oversized = ByteArray(17 * 1024 * 1024)   // 17MiB > 16MiB 上限
        val asar = AsarArchive(
            buildAsar(
                gameRoot,
                mapOf(
                    "www/index.html" to "<html></html>".toByteArray(StandardCharsets.UTF_8),
                    "www/big.bin" to oversized,
                ),
            ),
        )
        val bridge = RpgMakerFsBridge(gameRoot, contentRoot, asar)
        assertTrue("条目存在", bridge.exists("big.bin"))
        assertNull("超限 asar 条目读应返回 null", bridge.readText("big.bin"))
        assertEquals("原因码必须是 E2BIG", "E2BIG", bridge.errorFor("big.bin"))
    }

    /** asar 内独有的文件仍应能 stat（无磁盘对应物时回退 asar）。 */
    @Test
    fun statFallsBackToAsarForArchiveOnlyEntries() {
        val (_, bridge) = newAsarSession(withDiskOverride = false)
        val stat = JSONObject(bridge.stat("data/table.json"))
        assertTrue(stat.getBoolean("file"))
        assertFalse(stat.getBoolean("dir"))
    }
}
