package com.core.rpgmaker

import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.net.Socket
import java.nio.charset.StandardCharsets
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `*_` 后缀只在 MZ 会话里改变语义。
 *
 * MZ 的加密素材名是「原文件名 + 一个 `_`」；MV 与 Tyrano-2.3.4 的会话不认识这个约定，
 * 那里出现的 `x.png_` 只是一个普通文件。CHANGELOG 声称「MV 三条链路行为零变化」，
 * 所以剥 `_` 的缓存判定与 MIME 判定都必须按 `mzSession` 门控，而不是无条件生效。
 */
class RpgMakerServerUnderscoreScopeTest {

    private var server: RpgMakerLocalHttpServer? = null
    private var root: File? = null

    @After
    fun tearDown() {
        server?.stop()
        server = null
        root?.deleteRecursively()
        root = null
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

    private fun fetch(mzSession: Boolean, path: String): Response {
        val dir = java.nio.file.Files.createTempDirectory("underscore").toFile().also { root = it }
        File(dir, "index.html").writeText("<html><head></head><body></body></html>")
        File(dir, path).apply { parentFile?.mkdirs() }.writeText("DATA")
        val srv = RpgMakerLocalHttpServer(
            root = dir,
            tyranoHook = ByteArray(0),
            v12Compat = true,
            fuzzyNames = true,
            mzSession = mzSession,
        )
        srv.start()
        server = srv
        Socket("127.0.0.1", srv.port).use { socket ->
            socket.soTimeout = 5_000
            val out = socket.getOutputStream()
            val input = BufferedInputStream(socket.getInputStream())
            out.write("GET /index.html HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
            out.flush()
            val cookie = readResponse(input).headers["set-cookie"]?.substringBefore(';')
            out.write(
                ("GET /$path HTTP/1.1\r\nHost: 127.0.0.1\r\n" +
                    (if (cookie != null) "Cookie: $cookie\r\n" else "") + "\r\n").toByteArray(StandardCharsets.US_ASCII),
            )
            out.flush()
            return readResponse(input)
        }
    }

    @Test
    fun mzSessionTreatsTheSuffixAsAnEncryptedAsset() {
        val response = fetch(mzSession = true, path = "img/a.png_")
        assertEquals(200, response.code)
        assertEquals("max-age=3600", response.headers["cache-control"])
        assertEquals("image/png", response.headers["content-type"])
    }

    /**
     * MV / Tyrano 会话不认识 `_` 约定：`x.png_` 就是普通文件，
     * 缓存策略与 MIME 都不该因为尾随 `_` 而改变。
     */
    @Test
    fun nonMzSessionLeavesTheSuffixAlone() {
        val response = fetch(mzSession = false, path = "img/a.png_")
        assertEquals(200, response.code)
        assertEquals("no-cache", response.headers["cache-control"])
        assertEquals("application/octet-stream", response.headers["content-type"])
    }
}