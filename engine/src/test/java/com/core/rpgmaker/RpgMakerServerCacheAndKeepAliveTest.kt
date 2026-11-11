package com.core.rpgmaker

import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.net.Socket
import java.nio.charset.StandardCharsets
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 本地服务器的传输层行为：keep-alive 复用、ETag/304、静态资源缓存策略与模糊匹配阈值。
 *
 * 这些是「加载期变快」的直接契约，且无法用高层 HTTP 客户端验证（它们会自行复用连接），
 * 因此这里直接开裸 socket 发 HTTP/1.1 请求，逐个字节读响应。
 */
class RpgMakerServerCacheAndKeepAliveTest {

    private var server: RpgMakerLocalHttpServer? = null
    private var root: File? = null

    @After
    fun tearDown() {
        server?.stop()
        server = null
        root?.deleteRecursively()
        root = null
    }

    private fun newRoot(): File = java.nio.file.Files.createTempDirectory("srvcache").toFile().also { root = it }

    private fun start(root: File, fuzzy: Boolean = true): Int {
        val srv = RpgMakerLocalHttpServer(
            root = root,
            tyranoHook = ByteArray(0),
            v12Compat = true,
            fuzzyNames = fuzzy,
        )
        srv.start()
        server = srv
        return srv.port
    }

    private class Response(val code: Int, val headers: Map<String, String>)

    /**
     * 读一个响应（状态行 + 头 + 跳过 body）。
     *
     * [hasBody] 必须按**请求方法**给：HEAD 响应会带 `Content-Length` 但**没有 body**
     * （RFC 9110 §9.3.2），照 Content-Length 去读会把下一个响应的字节吃掉。
     */
    private fun readResponse(input: InputStream, hasBody: Boolean = true): Response {
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
        var left = if (hasBody) (headers["content-length"]?.toIntOrNull() ?: 0) else 0
        val buf = ByteArray(8 * 1024)
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size, left))
            if (n < 0) break
            left -= n
        }
        return Response(code, headers)
    }

    private fun request(out: java.io.OutputStream, path: String, cookie: String? = null, extraHeaders: String = "") {
        val sb = StringBuilder()
        sb.append("GET /").append(path).append(" HTTP/1.1\r\n")
        sb.append("Host: 127.0.0.1\r\n")
        if (cookie != null) sb.append("Cookie: ").append(cookie).append("\r\n")
        sb.append(extraHeaders)
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(StandardCharsets.US_ASCII))
        out.flush()
    }

    @Test
    fun keepAliveReusesSocketAndServesEtagRevalidation() {
        val root = newRoot()
        File(root, "index.html").writeText("<html><head></head><body></body></html>")
        File(root, "img").mkdirs()
        File(root, "img/pic.png").writeText("PNG")
        File(root, "data").mkdirs()
        File(root, "data/table.json").writeText("{}")
        val port = start(root)

        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5_000
            val out = socket.getOutputStream()
            val input = BufferedInputStream(socket.getInputStream())

            request(out, "index.html")
            val index = readResponse(input)
            assertEquals(200, index.code)
            assertEquals("HTTP/1.1 默认必须复用连接", "keep-alive", index.headers["connection"])
            assertEquals("首页不能进缓存（每次要下发鉴权 Cookie）", "no-cache", index.headers["cache-control"])
            val cookie = index.headers["set-cookie"]?.substringBefore(';')
            assertTrue("首页响应必须下发鉴权 Cookie", !cookie.isNullOrBlank())

            request(out, "img/pic.png", cookie)
            val first = readResponse(input)
            assertEquals("同一 socket 上的第二个请求必须成功（keep-alive 生效）", 200, first.code)
            // 故意写死字面量而不是复用 ASSET_CACHE_CONTROL：否则实现把策略改坏时测试会同步漂移
            assertEquals("max-age=3600", first.headers["cache-control"])
            val etag = first.headers["etag"]
            assertTrue("静态资源必须带 ETag", !etag.isNullOrBlank())

            // data/ 会被 v2 插件原地改写：必须每次重新验证，不能命中 max-age 读到旧值
            request(out, "data/table.json", cookie)
            val dataResponse = readResponse(input)
            assertEquals(200, dataResponse.code)
            assertEquals("no-cache", dataResponse.headers["cache-control"])
            // 可改写路径不发 ETag：mtime 粒度内同长度改写会 304 出旧内容
            assertNull("可改写路径不得带 ETag", dataResponse.headers["etag"])

            // 路径拼写不同（`./data/...`）也必须落到 no-cache：判定按解析后的磁盘相对路径
            request(out, "./data/table.json", cookie)
            assertEquals("no-cache", readResponse(input).headers["cache-control"])

            request(out, "img/pic.png", cookie, "If-None-Match: $etag\r\n")
            val second = readResponse(input)
            assertEquals("命中 ETag 必须返回 304", 304, second.code)
            assertEquals("304 的缓存策略必须与 200 一致", "max-age=3600", second.headers["cache-control"])
        }
    }

    /** 0 字节文件不能算出 `Content-Length: 1`：keep-alive 下会把下个响应的首字节吃掉。 */
    @Test
    fun emptyFileSendsZeroLengthAndKeepsConnectionUsable() {
        val root = newRoot()
        File(root, "index.html").writeText("<html></html>")
        File(root, "img").mkdirs()
        File(root, "img/empty.png").writeBytes(ByteArray(0))
        val port = start(root)

        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5_000
            val out = socket.getOutputStream()
            val input = BufferedInputStream(socket.getInputStream())
            request(out, "index.html")
            val cookie = readResponse(input).headers["set-cookie"]?.substringBefore(';')

            request(out, "img/empty.png", cookie)
            val empty = readResponse(input)
            assertEquals(200, empty.code)
            assertEquals("0", empty.headers["content-length"])

            // 连接仍可用：同一个 socket 上再取一次 index
            request(out, "index.html", cookie)
            assertEquals(200, readResponse(input).code)
        }
    }

    /** HEAD 只回头部：Content-Length 与实际长度一致，且不写 body（否则会毒化连接）。 */
    @Test
    fun headReturnsHeadersWithoutBodyAndKeepsConnectionUsable() {
        val root = newRoot()
        File(root, "index.html").writeText("<html></html>")
        File(root, "img").mkdirs()
        File(root, "img/pic.png").writeText("PNG")
        val port = start(root)

        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5_000
            val out = socket.getOutputStream()
            val input = BufferedInputStream(socket.getInputStream())
            request(out, "index.html")
            val cookie = readResponse(input).headers["set-cookie"]?.substringBefore(';')

            out.write(("HEAD /img/pic.png HTTP/1.1\r\nHost: 127.0.0.1\r\nCookie: $cookie\r\n\r\n").toByteArray(StandardCharsets.US_ASCII))
            out.flush()
            // HEAD 没有 body：不能按 Content-Length 去读，否则会吃掉下一个响应的前 3 字节
            val head = readResponse(input, hasBody = false)
            assertEquals(200, head.code)
            assertEquals("3", head.headers["content-length"])

            request(out, "img/pic.png", cookie)
            val body = readResponse(input)
            assertEquals("HEAD 之后同一连接必须还能正常取 body", 200, body.code)
            assertEquals("3", body.headers["content-length"])
        }
    }

    /** Range 206 + keep-alive：返回分段长度后连接仍可继续使用。 */
    @Test
    fun rangeResponseKeepsConnectionUsable() {
        val root = newRoot()
        File(root, "index.html").writeText("<html></html>")
        File(root, "img").mkdirs()
        File(root, "img/data.bin").writeText("0123456789")
        val port = start(root)

        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5_000
            val out = socket.getOutputStream()
            val input = BufferedInputStream(socket.getInputStream())
            request(out, "index.html")
            val cookie = readResponse(input).headers["set-cookie"]?.substringBefore(';')

            request(out, "img/data.bin", cookie, "Range: bytes=2-5\r\n")
            val partial = readResponse(input)
            assertEquals(206, partial.code)
            assertEquals("4", partial.headers["content-length"])
            assertEquals("bytes 2-5/10", partial.headers["content-range"])

            request(out, "index.html", cookie)
            assertEquals("206 之后同一连接必须仍可用", 200, readResponse(input).code)
        }
    }


    /**
     * keep-alive 下 worker 会阻塞在「等下一个请求」上，若线程池 core=max=2，第 3 条及之后的
     * 连接会排队到空闲超时才被服务（首页并发取 3 个兼容层脚本就会命中）。这里同时开 6 条
     * 连接（Chromium 同源上限）并断言都在 1200ms 内返回。
     *
     * 上界必须明显小于服务端空闲超时（1500ms）：否则「排队等到超时」这条回退路径
     * （约 1500ms）也能通过，断言就失去区分度。
     */
    @Test
    fun sixConcurrentConnectionsAreServedInParallel() {
        val root = newRoot()
        File(root, "index.html").writeText("<html><body></body></html>")
        val port = start(root)
        val sockets = (1..6).map {
            Socket("127.0.0.1", port).apply { soTimeout = 5_000 }
        }
        try {
            val outs = sockets.map { it.getOutputStream() }
            outs.forEach { out ->
                out.write("GET /index.html HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
                out.flush()
            }
            val startedAt = System.nanoTime()
            val codes = sockets.map { readResponse(BufferedInputStream(it.getInputStream())).code }
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
            assertTrue("6 条并发连接必须全部成功，实际 $codes", codes.all { it == 200 })
            assertTrue(
                "并发请求不得排队到空闲超时（实际 ${elapsedMs}ms，上界 1200ms 必须小于服务端 1500ms 空闲超时）",
                elapsedMs < 1_200L,
            )
        } finally {
            sockets.forEach { runCatching { it.close() } }
        }
    }
}
