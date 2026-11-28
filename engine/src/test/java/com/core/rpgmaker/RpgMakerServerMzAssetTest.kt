package com.core.rpgmaker

import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.net.Socket
import java.nio.charset.StandardCharsets
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MZ 加密素材（`<原文件名>_`）的缓存策略、MIME 与明文回退。
 *
 * MZ 的加密资源在 URL 末尾追加下划线：`img/pictures/a.png_`、`audio/bgm/x.ogg_`。
 * 它们是**只读素材**，必须与 `.rpgmvp/.rpgmvo/.rpgmvm` 同档（`max-age` + ETag）；
 * 旧实现按最后一个 `.` 取后缀得到 `".png_"`，不在白名单里 → 落到 `no-cache` 且不发
 * ETag → 每张加密图、每段加密音频每次请求整块重传（切场景明显卡顿）。
 *
 * 判定必须**只剥一层**尾随 `_`：`a.png__` 不是合法的 MZ 加密名，不能被误放行。
 */
class RpgMakerServerMzAssetTest {

    private var server: RpgMakerLocalHttpServer? = null
    private var root: File? = null

    @After
    fun tearDown() {
        server?.stop()
        server = null
        root?.deleteRecursively()
        root = null
    }

    private fun newRoot(): File = java.nio.file.Files.createTempDirectory("mzasset").toFile().also { root = it }

    private fun start(root: File, mzSession: Boolean): Int {
        val srv = RpgMakerLocalHttpServer(
            root = root,
            tyranoHook = ByteArray(0),
            v12Compat = true,
            fuzzyNames = true,
            mzSession = mzSession,
        )
        srv.start()
        server = srv
        return srv.port
    }

    private class Response(val code: Int, val headers: Map<String, String>)

    private fun readResponse(input: InputStream): Response {
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) {
            val b = input.read()
            if (b < 0) break
            head.append(b.toChar())
        }
        val lines = head.toString().split("\r\n").filter { it.isNotEmpty() }
        val code = lines.firstOrNull()?.split(" ")?.getOrNull(1)?.toIntOrNull() ?: -1
        val headers = lines.drop(1).mapNotNull { line ->
            val idx = line.indexOf(':')
            if (idx <= 0) null else line.substring(0, idx).trim().lowercase(java.util.Locale.ROOT) to line.substring(idx + 1).trim()
        }.toMap()
        var left = headers["content-length"]?.toIntOrNull() ?: 0
        val buf = ByteArray(8 * 1024)
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size, left))
            if (n < 0) break
            left -= n
        }
        return Response(code, headers)
    }

    private fun request(out: java.io.OutputStream, path: String, cookie: String? = null) {
        val sb = StringBuilder()
        sb.append("GET /").append(path).append(" HTTP/1.1\r\n")
        sb.append("Host: 127.0.0.1\r\n")
        if (cookie != null) sb.append("Cookie: ").append(cookie).append("\r\n")
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(StandardCharsets.US_ASCII))
        out.flush()
    }

    /** 在同一个 keep-alive 连接上依次取一组路径，返回 (路径 → 响应)。 */
    private fun fetchAll(mzSession: Boolean, paths: List<String>, prepare: (File) -> Unit): Map<String, Response> {
        val root = newRoot()
        File(root, "index.html").writeText("<html><head></head><body></body></html>")
        prepare(root)
        val port = start(root, mzSession)
        val out = HashMap<String, Response>()
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5_000
            val stream = socket.getOutputStream()
            val input = BufferedInputStream(socket.getInputStream())
            request(stream, "index.html")
            val cookie = readResponse(input).headers["set-cookie"]?.substringBefore(';')
            assertTrue("首页必须下发鉴权 Cookie", !cookie.isNullOrBlank())
            for (path in paths) {
                request(stream, path, cookie)
                out[path] = readResponse(input)
            }
        }
        return out
    }

    @Test
    fun encryptedImageSuffixUsesTheAssetCachePolicy() {
        val responses = fetchAll(true, listOf("img/a.png_")) { root ->
            File(root, "img").mkdirs()
            File(root, "img/a.png_").writeText("ENC")
        }
        val response = responses.getValue("img/a.png_")
        assertEquals(200, response.code)
        // 写死字面量而不是复用常量：实现把策略改坏时测试不会跟着漂移
        assertEquals("MZ 加密图必须与 .rpgmvp 同档", "max-age=3600", response.headers["cache-control"])
        assertTrue("加密素材必须带 ETag（否则重新验证仍是整块重传）", !response.headers["etag"].isNullOrBlank())
    }

    @Test
    fun encryptedAudioSuffixUsesTheAssetCachePolicy() {
        val responses = fetchAll(true, listOf("audio/bgm/x.ogg_")) { root ->
            File(root, "audio/bgm").mkdirs()
            File(root, "audio/bgm/x.ogg_").writeText("ENC")
        }
        assertEquals("max-age=3600", responses.getValue("audio/bgm/x.ogg_").headers["cache-control"])
    }

    /** 基础扩展名不在白名单时不能被误放行：`a.txt_` 仍可能是插件会改写的文本。 */
    @Test
    fun encryptedSuffixOnAWritableExtensionStaysRewritable() {
        val responses = fetchAll(true, listOf("img/a.txt_")) { root ->
            File(root, "img").mkdirs()
            File(root, "img/a.txt_").writeText("TXT")
        }
        val response = responses.getValue("img/a.txt_")
        assertEquals("no-cache", response.headers["cache-control"])
        assertNull("可改写路径不得带 ETag", response.headers["etag"])
    }

    /** 只剥一层 `_`：`a.png__` 不是 MZ 合法加密名，不能靠递归剥成 `.png` 蒙混过关。 */
    @Test
    fun doubleUnderscoreIsNotTreatedAsAnEncryptedAsset() {
        val responses = fetchAll(true, listOf("img/a.png__")) { root ->
            File(root, "img").mkdirs()
            File(root, "img/a.png__").writeText("ENC")
        }
        assertEquals("no-cache", responses.getValue("img/a.png__").headers["cache-control"])
    }

    @Test
    fun encryptedSuffixResolvesToTheBaseMimeType() {
        val responses = fetchAll(true, listOf("img/a.png_", "audio/bgm/x.ogg_", "img/b.png__")) { root ->
            File(root, "img").mkdirs()
            File(root, "audio/bgm").mkdirs()
            File(root, "img/a.png_").writeText("ENC")
            File(root, "audio/bgm/x.ogg_").writeText("ENC")
            File(root, "img/b.png__").writeText("ENC")
        }
        assertEquals("image/png", responses.getValue("img/a.png_").headers["content-type"])
        assertEquals("audio/ogg", responses.getValue("audio/bgm/x.ogg_").headers["content-type"])
        assertEquals(
            "双下划线不是合法加密名，MIME 同样只能剥一层",
            "application/octet-stream",
            responses.getValue("img/b.png__").headers["content-type"],
        )
    }

    /**
     * 混合加密工程：游戏只加密了 `img/`，后加的补丁素材是明文，MZ 仍按 `_` 请求。
     * 直接命中失败后必须回退到去掉尾随 `_` 的明文文件。
     */
    @Test
    fun encryptedRequestFallsBackToThePlainFileInMzSessions() {
        val responses = fetchAll(true, listOf("img/plain.png_")) { root ->
            File(root, "img").mkdirs()
            File(root, "img/plain.png").writeText("PLAIN")
        }
        assertEquals("明文补丁素材不能 404", 200, responses.getValue("img/plain.png_").code)
    }

    /** 两者都在时优先磁盘上真实的加密文件，不能被回退抢先。 */
    @Test
    fun encryptedFileWinsOverThePlainFallback() {
        val responses = fetchAll(true, listOf("img/both.png_")) { root ->
            File(root, "img").mkdirs()
            File(root, "img/both.png_").writeText("ENC")
            File(root, "img/both.png").writeText("PLAIN")
        }
        val response = responses.getValue("img/both.png_")
        assertEquals(200, response.code)
        assertEquals("加密文件（3 字节 ENC）必须优先于明文 5 字节 PLAIN", "3", response.headers["content-length"])
    }

    /** 回退只在 MZ 会话生效：v0/Tyrano 的解析链不能因为这次改动而多出一次命中。 */
    @Test
    fun plainFallbackDoesNotApplyOutsideMzSessions() {
        val responses = fetchAll(false, listOf("img/plain.png_")) { root ->
            File(root, "img").mkdirs()
            File(root, "img/plain.png").writeText("PLAIN")
        }
        assertEquals("非 MZ 会话保持原行为（404）", 404, responses.getValue("img/plain.png_").code)
    }

    /** 回归：MV 的三个加密扩展名策略不变（它们本来就是 `max-age`）。 */
    @Test
    fun legacyEncryptedExtensionsKeepTheirPolicy() {
        val paths = listOf("img/a.rpgmvp", "audio/bgm/x.rpgmvo", "audio/bgm/y.rpgmvm")
        val responses = fetchAll(true, paths) { root ->
            File(root, "img").mkdirs()
            File(root, "audio/bgm").mkdirs()
            File(root, "img/a.rpgmvp").writeText("ENC")
            File(root, "audio/bgm/x.rpgmvo").writeText("ENC")
            File(root, "audio/bgm/y.rpgmvm").writeText("ENC")
        }
        for (path in paths) {
            assertEquals("$path 策略不得改变", "max-age=3600", responses.getValue(path).headers["cache-control"])
        }
    }
}