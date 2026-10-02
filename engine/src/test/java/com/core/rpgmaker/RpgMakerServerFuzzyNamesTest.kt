package com.core.rpgmaker

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 本地 HTTP 服务器的 `fuzzyNames` 分支（此前零覆盖）。
 *
 * 真实起一个服务器并走 HTTP 请求，验证「宽松文件名匹配」只在 v2 会话（fuzzyNames=true）
 * 生效、且 v0/v1（false）保持严格语义——这是 `resolveByName` 双分支的关键行为，
 * 只测 matcher 无法覆盖调用链与门控。
 */
class RpgMakerServerFuzzyNamesTest {

    private var server: RpgMakerLocalHttpServer? = null

    @After
    fun tearDown() {
        server?.stop()
        server = null
    }

    private fun newRoot(): File = java.nio.file.Files.createTempDirectory("srvt").toFile()

    /** 启动服务器并返回端口。 */
    private fun start(root: File, fuzzyNames: Boolean): Int {
        val srv = RpgMakerLocalHttpServer(
            root = root,
            tyranoHook = ByteArray(0),
            v12Compat = true,
            fuzzyNames = fuzzyNames,
        )
        srv.start()
        server = srv
        return srv.port
    }

    /** 发起请求，返回 HTTP 状态码（带鉴权 Cookie）。404 时也读完整响应以复用连接。 */
    private fun get(port: Int, path: String): Pair<Int, String?> {
        // 先取 index.html 拿鉴权 Cookie（服务器对非入口页要求 token）
        val cookie = fetchCookie(port)
        val conn = (URL("http://127.0.0.1:$port/$path").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5_000
            readTimeout = 5_000
            if (cookie != null) setRequestProperty("Cookie", cookie)
        }
        val code = conn.responseCode
        val body = runCatching {
            (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }
        }.getOrNull()
        conn.disconnect()
        return code to body
    }

    private fun fetchCookie(port: Int): String? {
        val conn = (URL("http://127.0.0.1:$port/index.html").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5_000
            readTimeout = 5_000
        }
        val cookies = conn.headerFields.entries
            .filter { it.key.equals("Set-Cookie", ignoreCase = true) }
            .flatMap { it.value.orEmpty() }
            .mapNotNull { it.substringBefore(';').takeIf { c -> c.contains('=') } }
            .joinToString("; ")
        runCatching { conn.inputStream?.close() }
        conn.disconnect()
        return cookies.ifEmpty { null }
    }

    /** 磁盘上是半角空格版本，请求用全角空格（真实游戏的情形）。 */
    private fun seedPictures(root: File): File {
        val dir = File(root, "img/pictures").apply { mkdirs() }
        File(dir, "ホーム画面 朝   .png").writeText("PNG")
        File(root, "index.html").writeText("<html></html>")
        return dir
    }

    @Test
    fun fuzzyDisabledKeepsStrictSemantics() {
        val root = newRoot()
        seedPictures(root)
        val port = start(root, fuzzyNames = false)

        val (code, _) = get(port, "img/pictures/%E3%83%9B%E3%83%BC%E3%83%A0%E7%94%BB%E9%9D%A2%E3%80%80%E6%9C%9D%20%20%20.png")
        assertEquals("v0/v1（fuzzyNames=false）必须保持严格匹配：全角空格请求应 404", 404, code)
    }

    @Test
    fun fuzzyEnabledServesWhitespaceVariant() {
        val root = newRoot()
        seedPictures(root)
        val port = start(root, fuzzyNames = true)

        val (code, _) = get(port, "img/pictures/%E3%83%9B%E3%83%BC%E3%83%A0%E7%94%BB%E9%9D%A2%E3%80%80%E6%9C%9D%20%20%20.png")
        assertEquals("v2（fuzzyNames=true）应通过空白容忍命中磁盘上的半角版本", 200, code)
    }

    @Test
    fun fuzzyEnabledStillServesExactNames() {
        val root = newRoot()
        seedPictures(root)
        val port = start(root, fuzzyNames = true)

        // 精确命中不受影响（宽松匹配只在精确/大小写都不中时才启用）
        val (code, _) = get(port, "img/pictures/%E3%83%9B%E3%83%BC%E3%83%A0%E7%94%BB%E9%9D%A2%20%E6%9C%9D%20%20%20.png")
        assertEquals("精确匹配必须正常", 200, code)
    }

    @Test
    fun fuzzyEnabledStillRejectsAmbiguousNames() {
        val root = newRoot()
        val dir = seedPictures(root)
        // 与目标在「忽略尾随空白」后同键的兄弟文件：歧义时必须拒绝，避免画错图
        File(dir, "ステ画面フキダシ .png").writeText("a")
        File(dir, "ステ画面フキダシ.png").writeText("b")
        val port = start(root, fuzzyNames = true)

        val (code, _) = get(port, "img/pictures/%E3%82%B9%E3%83%86%E7%94%BB%E9%9D%A2%E3%83%95%E3%82%AD%E3%83%80%E3%82%B7%20%20.png")
        assertEquals("歧义名字必须拒绝（404）而不是猜一个", 404, code)
    }

    @Test
    fun fuzzyEnabledDoesNotEscapeRoot() {
        val root = newRoot()
        seedPictures(root)
        // 根目录之外放一个同名文件，越界请求不得被宽松匹配放行
        File(root.parentFile, "escaped.png").writeText("x")
        val port = start(root, fuzzyNames = true)

        val (code, _) = get(port, "img/pictures/..%2F..%2F..%2Fescaped.png")
        assertTrue("越界请求必须被拒绝（不得 200）", code != 200)
    }

    @Test
    fun missingFileIsStill404WithFuzzyEnabled() {
        val root = newRoot()
        seedPictures(root)
        val port = start(root, fuzzyNames = true)

        val (code, _) = get(port, "img/pictures/definitely-missing.png")
        assertEquals("真正缺失的文件仍应 404", 404, code)
    }
}
