package com.core.rpgmaker

import java.io.BufferedInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.Socket
import java.nio.charset.StandardCharsets
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * MZ 加密素材在 **asar 会话**里的解析。
 *
 * asar 会话的内容根只是 `contentRoot`，资源在压缩包内：MZ 的 `<文件名>_` 请求既不在
 * 磁盘上（`canonicalIfValid` 不命中），也不一定在包内以带 `_` 的名字存在（混合加密
 * 工程的补丁素材在包里是明文）。所以 MZ 的回退必须在**两处**都生效——磁盘与 asar，
 * 否则「游戏目录打成 asar」这一类作品会整批素材 404。
 */
class RpgMakerServerMzAsarTest {

    private var asarFile: File? = null
    private var gameRoot: File? = null
    private var server: RpgMakerLocalHttpServer? = null

    @After
    fun tearDown() {
        server?.stop()
        server = null
        asarFile?.parentFile?.deleteRecursively()
        asarFile = null
        gameRoot?.deleteRecursively()
        gameRoot = null
    }

    private fun writeLe32(stream: DataOutputStream, value: Int) {
        stream.write(value and 0xFF)
        stream.write((value ushr 8) and 0xFF)
        stream.write((value ushr 16) and 0xFF)
        stream.write((value ushr 24) and 0xFF)
    }

    /** 最小可解析 asar（格式见 AsarArchive 的头部解析）。 */
    private fun buildAsar(dir: File, entries: Map<String, ByteArray>): File {
        val filesJson = JSONObject()
        var offset = 0L
        for ((path, bytes) in entries) {
            filesJson.put(path, JSONObject().put("size", bytes.size).put("offset", offset.toString()))
            offset += bytes.size
        }
        val rootJson = JSONObject().put("files", filesJson)
        val jsonBytes = rootJson.toString().toByteArray(StandardCharsets.UTF_8)
        val headerSize = ((8 + jsonBytes.size + 3) / 4) * 4
        val out = File(dir, "app.asar")
        DataOutputStream(FileOutputStream(out)).use { stream ->
            writeLe32(stream, 4)
            writeLe32(stream, headerSize)
            writeLe32(stream, 4)
            writeLe32(stream, jsonBytes.size)
            stream.write(jsonBytes)
            repeat((8 + headerSize) - 16 - jsonBytes.size) { stream.write(0) }
            entries.values.forEach { stream.write(it) }
        }
        return out
    }

    private fun startWithAsar(entries: Map<String, ByteArray>, mzSession: Boolean): Int {
        val root = File(java.nio.file.Files.createTempDirectory("mzasar").toFile(), "game").apply { mkdirs() }
        gameRoot = root
        // 真实 asar 会话：入口页也在包里（AsarWebRoot.prefixFor 靠它判定包内根前缀，
        // 包内没有 index.html 时会探测出空前缀，那是与真机不同的构造）
        val asarEntries = entries + ("www/index.html" to "<html><head></head><body></body></html>".toByteArray(StandardCharsets.UTF_8))
        val asar = buildAsar(root, asarEntries)
        asarFile = asar
        val srv = RpgMakerLocalHttpServer(
            root = root,
            asar = AsarArchive(asar),
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

    private fun request(out: java.io.OutputStream, path: String, cookie: String?) {
        val sb = StringBuilder()
        sb.append("GET /").append(path).append(" HTTP/1.1\r\nHost: 127.0.0.1\r\n")
        if (cookie != null) sb.append("Cookie: ").append(cookie).append("\r\n")
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(StandardCharsets.US_ASCII))
        out.flush()
    }

    private fun fetch(port: Int, paths: List<String>): Map<String, Response> {
        val out = HashMap<String, Response>()
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5_000
            val stream = socket.getOutputStream()
            val input = BufferedInputStream(socket.getInputStream())
            request(stream, "index.html", null)
            val cookie = readResponse(input).headers["set-cookie"]?.substringBefore(';')
            for (path in paths) {
                request(stream, path, cookie)
                out[path] = readResponse(input)
            }
        }
        return out
    }

    /** 包内的加密素材（名字带 `_`）必须能直接命中，且走素材缓存策略。 */
    @Test
    fun encryptedEntryInsideTheAsarResolves() {
        val port = startWithAsar(
            mapOf("www/img/a.png_" to "ENC".toByteArray(StandardCharsets.UTF_8)),
            mzSession = true,
        )
        val response = fetch(port, listOf("img/a.png_")).getValue("img/a.png_")
        assertEquals("asar 包内的加密素材不能 404", 200, response.code)
        assertEquals("max-age=3600", response.headers["cache-control"])
        assertEquals("image/png", response.headers["content-type"])
    }

    /** 包内只有明文（混合加密工程的补丁素材）时，带 `_` 的请求要回退到明文条目。 */
    @Test
    fun encryptedRequestFallsBackToThePlainAsarEntry() {
        val port = startWithAsar(
            mapOf("www/img/plain.png" to "PLAIN".toByteArray(StandardCharsets.UTF_8)),
            mzSession = true,
        )
        assertEquals(
            "asar 包内的明文补丁素材不能 404",
            200,
            fetch(port, listOf("img/plain.png_")).getValue("img/plain.png_").code,
        )
    }

    /** 非 MZ 会话保持原行为：回退不生效。 */
    @Test
    fun plainFallbackInsideAsarIsMzOnly() {
        val port = startWithAsar(
            mapOf("www/img/plain.png" to "PLAIN".toByteArray(StandardCharsets.UTF_8)),
            mzSession = false,
        )
        assertEquals(404, fetch(port, listOf("img/plain.png_")).getValue("img/plain.png_").code)
    }
}