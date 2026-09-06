package com.core.rpgmaker

import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.util.HashMap
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

// ASAR 条目走流式的体积阈值：低于此值一次性读取更简单高效
internal const val ASAR_STREAM_THRESHOLD = 256L * 1024L

internal class RpgMakerLocalHttpServer(
    root: File,
    asar: AsarArchive?,
    tyranoHook: ByteArray?,
    private val injectBeforeBody: Boolean = false,
    scriptAppends: Map<String, ByteArray> = emptyMap(),
    private val injectedHtml: String = "",
    internalResources: Map<String, ByteArray> = emptyMap(),
    private val earlyHook: ByteArray? = null,
    private val v12Compat: Boolean = true,
) : Runnable {
    private val root: File
    private val asar: AsarArchive?
    private val tyranoHook: ByteArray
    private val asarRootPrefix: String
    private val scriptAppends: Map<String, ByteArray> = scriptAppends.mapKeys { it.key.lowercase(Locale.ROOT) }
    private val internalResources: Map<String, ByteArray> = internalResources.mapKeys {
        it.key.trimStart('/').replace(Regex("""/\./"""), "/").replace(Regex("""//+"""), "/").lowercase(Locale.ROOT)
    }
    private val serverSocket: ServerSocket
    private val thread: Thread
    @Volatile
    private var running = true
    private val clients: ThreadPoolExecutor

    // 每实例随机鉴权 token：绑定 127.0.0.1 不限制同设备其他应用，端口扫描可读取
    // 游戏目录内文件（含存档，PR review 意见）。index 页为引导入口无条件下发
    // Set-Cookie，其余请求必须携带匹配 token 的 Cookie，否则 403。
    private val authToken: String = newAuthToken()

    init {
        this.root = root.canonicalFile
        this.asar = asar
        this.tyranoHook = tyranoHook ?: ByteArray(0)
        this.asarRootPrefix = when {
            asar == null || asar.has("index.html") -> ""
            asar.has("www/index.html") -> "www/"
            asar.has("app/index.html") -> "app/"
            asar.has("resources/app/index.html") -> "resources/app/"
            else -> ""
        }
        this.serverSocket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        this.thread = Thread(this, "YukiRpgMakerLocalHttpServer").apply { isDaemon = true }
        this.clients = ThreadPoolExecutor(
            2, 8, 30L, TimeUnit.SECONDS,
            ArrayBlockingQueue<Runnable>(64),
            { runnable: Runnable -> Thread(runnable, "YukiRpgMakerHttpClient").apply { isDaemon = true } },
            ThreadPoolExecutor.AbortPolicy(),
        )
    }

    constructor(
        root: File,
        tyranoHook: ByteArray?,
        injectBeforeBody: Boolean = false,
        scriptAppends: Map<String, ByteArray> = emptyMap(),
        injectedHtml: String = "",
        internalResources: Map<String, ByteArray> = emptyMap(),
        earlyHook: ByteArray? = null,
        v12Compat: Boolean = true,
    ) : this(root, null, tyranoHook, injectBeforeBody, scriptAppends, injectedHtml, internalResources, earlyHook, v12Compat)

    fun start() { thread.start() }
    val port: Int get() = serverSocket.localPort
    fun stop() {
        running = false
        try { serverSocket.close() } catch (_: Throwable) {}
        clients.shutdownNow()
    }

    override fun run() {
        Log.i(TAG, "local server started port=$port root=$root v12Compat=$v12Compat early=${earlyHook?.size ?: 0} late=${tyranoHook.size} internalRes=${internalResources.size}")
        while (running) {
            try {
                val socket = serverSocket.accept()
                try { clients.execute { handle(socket) } }
                catch (_: java.util.concurrent.RejectedExecutionException) { close(socket) }
            } catch (t: Throwable) {
                if (running) Log.w(TAG, "server accept failed", t)
            }
        }
    }

    companion object {
        private const val TAG = "YukiRpgMaker"
        private const val AUTH_COOKIE_NAME = "tyranorRpgMakerAuth"

        // 请求行/头有界读取上限（防本地恶意客户端 OOM，PR review 意见）
        private const val MAX_REQUEST_LINE_CHARS = 8 * 1024
        private const val MAX_HEADER_LINE_CHARS = 16 * 1024
        private const val MAX_HEADER_COUNT = 100

        private fun newAuthToken(): String {
            val bytes = ByteArray(16)
            java.security.SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }

    private class ResolvedFile(
        val file: File?,
        val data: ByteArray?,
        val stream: java.io.InputStream? = null,
        val streamLen: Long = 0L,
    )

    // 单行读取带长度上限：readLine 本身不限长，本地任意应用可发超长行触发 OOM
    // （PR review 意见）；超限时排空该行剩余内容并返回 null，调用方按失败处理
    private fun readBoundedLine(reader: BufferedReader, maxChars: Int): String? {
        val sb = StringBuilder(256)
        while (true) {
            val c = reader.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            if (sb.length >= maxChars) {
                while (true) {
                    val d = reader.read()
                    if (d < 0 || d == '\n'.code) break
                }
                return null
            }
            sb.append(c.toChar())
        }
    }

    private fun handle(socket: Socket) {
        try {
            socket.soTimeout = 15000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
            val requestLine = readBoundedLine(reader, MAX_REQUEST_LINE_CHARS)
            if (requestLine.isNullOrEmpty()) { close(socket); return }
            val headers = HashMap<String, String>()
            var headerCount = 0
            while (true) {
                val line = readBoundedLine(reader, MAX_HEADER_LINE_CHARS) ?: break
                if (line.isEmpty()) break
                if (++headerCount > MAX_HEADER_COUNT) {
                    sendText(socket, 431, "Request Header Fields Too Large", "headers too large")
                    return
                }
                val idx = line.indexOf(':')
                if (idx > 0) headers[line.substring(0, idx).trim().lowercase(Locale.ROOT)] = line.substring(idx + 1).trim()
            }
            val parts = requestLine.split(" ")
            if (parts.size < 2) { sendText(socket, 400, "Bad Request", "bad request"); return }
            val method = parts[0]
            var uri = parts[1]
            if (!method.equals("GET", true) && !method.equals("HEAD", true)) {
                sendText(socket, 405, "Method Not Allowed", "method not allowed")
                return
            }
            val q = uri.indexOf('?')
            if (q >= 0) uri = uri.substring(0, q)
            uri = decodeUriLenient(uri)
            if (uri == "/") uri = "/index.html"
            while (uri.startsWith("/")) uri = uri.substring(1)
            val normalizedLookup = uri.replace(Regex("""/\./"""), "/").replace(Regex("""//+"""), "/").lowercase(Locale.ROOT)
            // 鉴权门：仅 index 页作为引导入口豁免（响应下发 Set-Cookie），其余请求
            // 必须携带匹配 token 的 Cookie；同设备其他应用无 Cookie 一律 403
            val isEntryPage = uri.equals("index.html", ignoreCase = true) || uri.equals("index.htm", ignoreCase = true)
            if (!isEntryPage && !hasValidAuth(headers)) {
                Log.w(TAG, "403 unauthorized uri=$uri")
                sendText(socket, 403, "Forbidden", "forbidden")
                return
            }
            internalResources[normalizedLookup]?.let { resource ->
                sendBytes(socket, resource, uri, method.equals("HEAD", true))
                return
            }
            val resolved = resolveRequestedFile(uri)
            // ASAR 大条目（>256KiB，媒体为主）流式分支必须在 404 判空之前：
            // 流式结果 file/data 均为 null，放在后面会被判 404 且泄漏已打开的文件句柄
            // （PR review Critical 意见）
            if (resolved != null && resolved.stream != null) {
                sendStream(socket, resolved.stream, resolved.streamLen, uri, headers["range"], method.equals("HEAD", true))
                return
            }
            if (resolved == null || (resolved.file == null && resolved.data == null)) {
                Log.w(TAG, "404 uri=$uri")
                sendText(socket, 404, "Not Found", "not found: $uri")
                return
            }
            if (resolved.data != null) {
                if (isIndexHtml(uri)) sendInjectedIndex(socket, resolved.data, method.equals("HEAD", true))
                else if (hasScriptAppend(uri)) sendAppendedBytes(socket, resolved.data, uri, method.equals("HEAD", true))
                else sendBytes(socket, resolved.data, uri, method.equals("HEAD", true))
                return
            }
            if (isIndexHtml(uri, resolved.file)) {
                sendInjectedIndex(socket, resolved.file!!, method.equals("HEAD", true))
                return
            }
            if (hasScriptAppend(uri)) {
                sendAppendedFile(socket, resolved.file!!, uri, method.equals("HEAD", true))
                return
            }
            sendFile(socket, resolved.file, headers["range"], method.equals("HEAD", true))
        } catch (t: Throwable) {
            if (isExpectedClientDisconnect(t)) {
                Log.d(TAG, "client disconnected while serving local resource: ${t.javaClass.simpleName}")
            } else {
                try { sendText(socket, 500, "Internal Server Error", "server error") } catch (_: Throwable) {}
                Log.w(TAG, "handle request failed", t)
            }
        } finally {
            close(socket)
        }
    }

    private fun resolveRequestedFile(uri: String): ResolvedFile {
        var target = canonicalIfValid(uri)
        if (target != null) return ResolvedFile(target, null)
        val lower = uri.lowercase(Locale.ROOT)
        if (lower.endsWith(".m4a")) {
            val alt = replaceSuffix(uri, ".m4a", ".ogg")
            target = canonicalIfValid(alt)
            if (target != null) { Log.i(TAG, "resource fallback m4a->ogg $uri -> $alt"); return ResolvedFile(target, null) }
        }
        // 反向回退：游戏素材只发了 .m4a，而 MV 引擎按平台请求 .ogg（Android WebView
        // 不支持 m4a 时请求 ogg）。ogg 404 时尝试同名 .m4a，由 WebView 自带的 AAC
        // 解码器播放。
        if (lower.endsWith(".ogg")) {
            val alt = replaceSuffix(uri, ".ogg", ".m4a")
            target = canonicalIfValid(alt)
            if (target != null) { Log.i(TAG, "resource fallback ogg->m4a $uri -> $alt"); return ResolvedFile(target, null) }
        }
        if (lower.endsWith(".rpgmvm")) {
            val alt = replaceSuffix(uri, ".rpgmvm", ".rpgmvo")
            target = canonicalIfValid(alt)
            if (target != null) { Log.i(TAG, "resource fallback rpgmvm->rpgmvo $uri -> $alt"); return ResolvedFile(target, null) }
        }
        // 加密包以 .rpgmvp/.rpgmvo 落盘，页面可能直接请求 .png/.ogg，
        // 命中失败时回退同名加密扩展，由 WebView/Decrypter 侧处理。
        // v12Compat 门控：仅 v1/v2 兼容会话启用。
        if (v12Compat) {
            if (lower.endsWith(".png")) {
                val alt = replaceSuffix(uri, ".png", ".rpgmvp")
                target = canonicalIfValid(alt)
                if (target != null) { Log.i(TAG, "resource fallback png->rpgmvp $uri -> $alt"); return ResolvedFile(target, null) }
            }
            if (lower.endsWith(".ogg")) {
                val alt = replaceSuffix(uri, ".ogg", ".rpgmvo")
                target = canonicalIfValid(alt)
                if (target != null) { Log.i(TAG, "resource fallback ogg->rpgmvo $uri -> $alt"); return ResolvedFile(target, null) }
            }
        }
        if (asar != null) {
            val normalizedUri = uri.replace(Regex("/\\./"), "/").replace(Regex("//+"), "/")
            // 大条目优先流式：媒体需要 Range/seek，整段 ByteArray 有 OOM 风险（PR review 意见）
            for (p in listOf(asarRootPrefix + normalizedUri, normalizedUri,
                    asarRootPrefix + normalizedUri.lowercase(Locale.ROOT), normalizedUri.lowercase(Locale.ROOT))) {
                val streamed = asar.openStream(p) ?: continue
                if (streamed.second >= ASAR_STREAM_THRESHOLD) {
                    return ResolvedFile(null, null, streamed.first, streamed.second)
                }
                runCatching { streamed.first.close() }
            }
            val data = asar.read(asarRootPrefix + normalizedUri) ?: asar.read(normalizedUri)
                ?: asar.read(asarRootPrefix + normalizedUri.lowercase(Locale.ROOT)) ?: asar.read(normalizedUri.lowercase(Locale.ROOT))
            if (data != null) return ResolvedFile(null, data)
            if (uri.equals("index.html", true) || uri.equals("index.htm", true)) {
                var indexBytes = asar.read("index.html")
                if (indexBytes == null) indexBytes = asar.read("www/index.html")
                if (indexBytes == null) indexBytes = asar.read("app/index.html")
                if (indexBytes == null) indexBytes = asar.read("resources/app/index.html")
                if (indexBytes != null) return ResolvedFile(null, indexBytes)
            }
        }
        return ResolvedFile(resolveCaseInsensitive(uri), null)
    }

    private fun canonicalIfValid(uri: String?): File? {
        if (uri == null || uri.contains("\u0000")) return null
        val target = File(root, uri).canonicalFile
        return if (!isInsideRoot(target) || !target.isFile) null else target
    }

    private fun replaceSuffix(value: String?, oldSuffix: String, newSuffix: String): String? {
        if (value == null) return null
        return value.substring(0, value.length - oldSuffix.length) + newSuffix
    }

    private fun resolveCaseInsensitive(uri: String?): File? {
        if (uri == null || uri.isEmpty() || uri.contains("..")) return null
        val parts = uri.split("/")
        var current: File = root
        for (part in parts) {
            if (part.isEmpty()) continue
            val exact = File(current, part)
            if (exact.exists()) { current = exact; continue }
            val children = current.listFiles() ?: return null
            var matched: File? = null
            for (child in children) {
                if (child.name.equals(part, ignoreCase = true)) { matched = child; break }
            }
            if (matched == null) return null
            current = matched
        }
        val target = current.canonicalFile
        if (!isInsideRoot(target) || !target.isFile) return null
        Log.i(TAG, "resource fallback case-insensitive $uri -> ${target.path}")
        return target
    }

    private fun isInsideRoot(target: File?): Boolean {
        if (target == null) return false
        val rootPath = root.path
        val targetPath = target.path
        return targetPath == rootPath || targetPath.startsWith(rootPath + File.separator)
    }

    private fun isIndexHtml(uri: String?, target: File?): Boolean {
        if (target == null) return isIndexHtml(uri)
        val name = target.name.lowercase(Locale.ROOT)
        val path = uri?.lowercase(Locale.ROOT) ?: ""
        return (name == "index.html" || name == "index.htm") && (path.endsWith("index.html") || path.endsWith("index.htm"))
    }

    private fun isIndexHtml(uri: String?): Boolean {
        if (uri == null) return false
        val path = uri.lowercase(Locale.ROOT)
        return path.endsWith("index.html") || path.endsWith("index.htm")
    }

    private fun sendInjectedIndex(socket: Socket, file: File, headOnly: Boolean) {
        sendInjectedIndex(socket, readTextFile(file), headOnly)
    }

    private fun sendInjectedIndex(socket: Socket, htmlBytes: ByteArray?, headOnly: Boolean) {
        val text = if (htmlBytes == null) "" else String(htmlBytes, StandardCharsets.UTF_8)
        sendInjectedIndex(socket, text, headOnly)
    }

    // 引导入口响应附带 Set-Cookie：后续同源资源请求由 WebView 自动携带
    private fun authCookieHeader(): String =
        "Set-Cookie: $AUTH_COOKIE_NAME=$authToken; Path=/; HttpOnly\r\n"

    private fun hasValidAuth(headers: Map<String, String>): Boolean =
        headers["cookie"]?.contains("$AUTH_COOKIE_NAME=$authToken") == true

    private fun sendInjectedIndex(socket: Socket, html: String?, headOnly: Boolean) {
        val data = if (earlyHook != null && earlyHook.isNotEmpty()) {
            buildInjectedHtmlTwoPhase(
                html.orEmpty(), earlyHook, tyranoHook, injectedHtml, injectBeforeBody,
            )
        } else {
            buildInjectedHtml(html.orEmpty(), tyranoHook, injectedHtml, injectBeforeBody)
        }
        Log.i(TAG, "served injected index bytes=${data.size} hook=${tyranoHook.size}")
        val out = BufferedOutputStream(socket.getOutputStream())
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nCache-Control: no-cache\r\nAccess-Control-Allow-Origin: *\r\n" + authCookieHeader() + "Content-Length: ${data.size}\r\nConnection: close\r\n\r\n").toByteArray(StandardCharsets.UTF_8))
        if (!headOnly) out.write(data)
        out.flush()
    }

    /**
     * ASAR 大条目流式响应：与 [sendFile] 同一套 Range 语义（含后缀式 bytes=-N），
     * 跳过起始字节后分块复制；输入流由本函数负责关闭（PR review 意见）。
     */
    private fun sendStream(socket: Socket, input: java.io.InputStream, totalLen: Long, uri: String, rangeHeader: String?, headOnly: Boolean) {
        val spec = parseRangeHeader(rangeHeader, totalLen) ?: RangeSpec(0, (totalLen - 1).coerceAtLeast(0), false)
        val len = Math.max(0, spec.end - spec.start + 1)
        val status = if (spec.partial) "206 Partial Content" else "200 OK"
        try {
            val raw = BufferedOutputStream(socket.getOutputStream())
            val h = StringBuilder()
            h.append("HTTP/1.1 ").append(status).append("\r\n")
            h.append("Accept-Ranges: bytes\r\n")
            h.append("Content-Type: ").append(mime(uri)).append("\r\n")
            h.append("Cache-Control: no-cache\r\n")
            h.append("Access-Control-Allow-Origin: *\r\n")
            h.append("Content-Length: ").append(len).append("\r\n")
            if (spec.partial) h.append("Content-Range: bytes ").append(spec.start).append('-').append(spec.end).append('/').append(totalLen).append("\r\n")
            h.append("Connection: close\r\n\r\n")
            raw.write(h.toString().toByteArray(StandardCharsets.UTF_8))
            if (!headOnly) {
                var skipped = 0L
                while (skipped < spec.start) {
                    val s = input.skip(spec.start - skipped)
                    if (s <= 0) break
                    skipped += s
                }
                val buf = ByteArray(64 * 1024)
                var left = len
                while (left > 0) {
                    val read = input.read(buf, 0, Math.min(buf.size.toLong(), left).toInt())
                    if (read < 0) break
                    raw.write(buf, 0, read)
                    left -= read
                }
            }
            raw.flush()
        } finally {
            try { input.close() } catch (_: Throwable) {}
        }
    }

    private fun hasScriptAppend(uri: String): Boolean = scriptAppends.containsKey(uri.lowercase(Locale.ROOT))

    private fun sendAppendedFile(socket: Socket, file: File, uri: String, headOnly: Boolean) {
        sendAppendedBytes(socket, file.readBytes(), uri, headOnly)
    }

    private fun sendAppendedBytes(socket: Socket, original: ByteArray, uri: String, headOnly: Boolean) {
        val append = scriptAppends[uri.lowercase(Locale.ROOT)] ?: ByteArray(0)
        val data = ByteArray(original.size + append.size).also {
            original.copyInto(it)
            append.copyInto(it, original.size)
        }
        Log.i(TAG, "served patched script uri=$uri original=${original.size} append=${append.size}")
        sendBytes(socket, data, uri, headOnly)
    }

    private fun readTextFile(file: File): String {
        val inStream = BufferedInputStream(FileInputStream(file))
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        try {
            var read: Int
            while (inStream.read(buf).also { read = it } >= 0) out.write(buf, 0, read)
        } finally {
            try { inStream.close() } catch (_: Throwable) {}
        }
        return String(out.toByteArray(), StandardCharsets.UTF_8)
    }

    private fun sendFile(socket: Socket, file: File?, rangeHeader: String?, headOnly: Boolean) {
        if (file == null) { sendText(socket, 404, "Not Found", "file missing"); return }
        val fileLen = file.length()
        val spec = parseRangeHeader(rangeHeader, fileLen) ?: RangeSpec(0, (fileLen - 1).coerceAtLeast(0), false)
        val start = spec.start
        val end = spec.end
        val partial = spec.partial
        val len = Math.max(0, end - start + 1)
        val status = if (partial) "206 Partial Content" else "200 OK"
        val raw = BufferedOutputStream(socket.getOutputStream())
        val h = StringBuilder()
        h.append("HTTP/1.1 ").append(status).append("\r\n")
        h.append("Accept-Ranges: bytes\r\n")
        h.append("Content-Type: ").append(mime(file.name)).append("\r\n")
        h.append("Cache-Control: no-cache\r\n")
        h.append("Access-Control-Allow-Origin: *\r\n")
        h.append("Content-Length: ").append(len).append("\r\n")
        if (partial) h.append("Content-Range: bytes ").append(start).append('-').append(end).append('/').append(fileLen).append("\r\n")
        h.append("Connection: close\r\n\r\n")
        raw.write(h.toString().toByteArray(StandardCharsets.UTF_8))
        if (!headOnly) {
            val inStream = BufferedInputStream(FileInputStream(file))
            try {
                var skipped = 0L
                while (skipped < start) {
                    val s = inStream.skip(start - skipped)
                    if (s <= 0) break
                    skipped += s
                }
                val buf = ByteArray(64 * 1024)
                var left = len
                while (left > 0) {
                    val read = inStream.read(buf, 0, Math.min(buf.size.toLong(), left).toInt())
                    if (read < 0) break
                    raw.write(buf, 0, read)
                    left -= read
                }
            } finally {
                try { inStream.close() } catch (_: Throwable) {}
            }
        }
        raw.flush()
    }

    private fun sendBytes(socket: Socket, data: ByteArray?, uri: String, headOnly: Boolean) {
        if (data == null) { sendText(socket, 404, "Not Found", "data missing"); return }
        val raw = BufferedOutputStream(socket.getOutputStream())
        val h = StringBuilder()
        h.append("HTTP/1.1 200 OK\r\n")
        h.append("Content-Type: ").append(mime(uri)).append("\r\n")
        h.append("Cache-Control: no-cache\r\n")
        h.append("Access-Control-Allow-Origin: *\r\n")
        h.append("Content-Length: ").append(data.size).append("\r\n")
        h.append("Connection: close\r\n\r\n")
        raw.write(h.toString().toByteArray(StandardCharsets.UTF_8))
        if (!headOnly) raw.write(data)
        raw.flush()
    }

    private fun sendText(socket: Socket, code: Int, reason: String, body: String) {
        val data = body.toByteArray(StandardCharsets.UTF_8)
        val out = BufferedOutputStream(socket.getOutputStream())
        out.write(("HTTP/1.1 $code $reason\r\nContent-Type: text/plain; charset=utf-8\r\nCache-Control: no-cache\r\nAccess-Control-Allow-Origin: *\r\nContent-Length: ${data.size}\r\nConnection: close\r\n\r\n").toByteArray(StandardCharsets.UTF_8))
        out.write(data)
        out.flush()
    }

    private fun close(socket: Socket) { try { socket.close() } catch (_: Throwable) {} }

    private fun isExpectedClientDisconnect(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            if (current is SocketException) {
                val message = current.message
                if (message == null || message.lowercase(Locale.ROOT).contains("reset") || message.lowercase(Locale.ROOT).contains("broken pipe")) return true
            }
            current = current.cause
        }
        return false
    }

    private fun mime(name: String?): String {
        val n = name?.lowercase(Locale.ROOT) ?: ""
        if (n.endsWith(".html") || n.endsWith(".htm")) return "text/html; charset=utf-8"
        if (n.endsWith(".js")) return "application/javascript; charset=utf-8"
        if (n.endsWith(".css")) return "text/css; charset=utf-8"
        if (n.endsWith(".json")) return "application/json; charset=utf-8"
        if (n.endsWith(".png")) return "image/png"
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg"
        if (n.endsWith(".gif")) return "image/gif"
        if (n.endsWith(".webp")) return "image/webp"
        if (n.endsWith(".svg")) return "image/svg+xml"
        if (n.endsWith(".mp3")) return "audio/mpeg"
        if (n.endsWith(".ogg")) return "audio/ogg"
        if (n.endsWith(".m4a")) return "audio/mp4"
        if (n.endsWith(".aac")) return "audio/aac"
        if (n.endsWith(".flac")) return "audio/flac"
        if (n.endsWith(".wav")) return "audio/wav"
        if (n.endsWith(".mp4") || n.endsWith(".m4v")) return "video/mp4"
        if (n.endsWith(".webm")) return "video/webm"
        if (n.endsWith(".ttf")) return "font/ttf"
        if (n.endsWith(".otf")) return "font/otf"
        if (n.endsWith(".woff")) return "font/woff"
        if (n.endsWith(".woff2")) return "font/woff2"
        if (n.endsWith(".wasm")) return "application/wasm"
        if (n.endsWith(".xml")) return "application/xml; charset=utf-8"
        if (n.endsWith(".txt")) return "text/plain; charset=utf-8"
        return "application/octet-stream"
    }
}

internal fun buildInjectedHtml(
    html: String,
    hook: ByteArray,
    injectedHtml: String,
    beforeBody: Boolean,
): ByteArray {
    if (hook.isEmpty() && injectedHtml.isBlank()) return html.toByteArray(StandardCharsets.UTF_8)
    val script = String(hook, StandardCharsets.UTF_8)
    val hookTag = if (script.isBlank()) "" else "\n<script type='text/javascript'>\n$script\n</script>\n"
    val injected = hookTag + injectedHtml
    val marker = if (beforeBody) "</body>" else "</head>"
    val position = html.lowercase(Locale.ROOT).indexOf(marker)
    val result = if (position >= 0) {
        html.substring(0, position) + injected + html.substring(position)
    } else {
        injected + html
    }
    return result.toByteArray(StandardCharsets.UTF_8)
}

/**
 * 两段式注入（v1/v2 会话专用）：earlyHook（NW.js polyfill）注入在 `</head>` 处，
 * lateHook（引擎 hook + 修改器 HTML）注入在 `</body>` 处，保证 polyfill 先于游戏
 * 脚本与引擎补丁执行。
 *
 * 与“先 early 后 late 各调一次 [buildInjectedHtml]”的朴素两段拼接不同：当页面没有
 * `</body>` 标记时，[buildInjectedHtml] 会前插——late 段将插到已前插的 early 段
 * 之前，导致引擎 hook 先于 polyfill 执行（顺序反转）。本函数在 `</body>` 缺失时
 * 改为**尾插** late 段，任何 HTML 形态下都维持 early < late 的执行顺序。
 */
internal fun buildInjectedHtmlTwoPhase(
    html: String,
    earlyHook: ByteArray,
    lateHook: ByteArray,
    injectedHtml: String,
    injectBeforeBody: Boolean,
): ByteArray {
    val withEarly = String(buildInjectedHtml(html, earlyHook, "", false), StandardCharsets.UTF_8)
    if (lateHook.isEmpty() && injectedHtml.isBlank()) return withEarly.toByteArray(StandardCharsets.UTF_8)
    val script = String(lateHook, StandardCharsets.UTF_8)
    val hookTag = if (script.isBlank()) "" else "\n<script type='text/javascript'>\n$script\n</script>\n"
    val injected = hookTag + injectedHtml
    if (injected.isBlank()) return withEarly.toByteArray(StandardCharsets.UTF_8)
    val position = withEarly.lowercase(Locale.ROOT).indexOf("</body>")
    val result = if (position >= 0) {
        withEarly.substring(0, position) + injected + withEarly.substring(position)
    } else {
        withEarly + injected
    }
    return result.toByteArray(StandardCharsets.UTF_8)
}

// 宽松 URI 解码：文件名可含字面 '%'（如 "xiclotlan_s_128%.rpgmvp"）。
// java.net.URLDecoder.decode 遇到非法 % 序列（% 后非两个 hex）会抛
// IllegalArgumentException（旧引擎宿主侧则产生 U+FFFD 替换字符），把合法
// 文件名破坏成找不到的乱码（战斗图片 404 卡加载）。
// 这里字节级解码：%XX 收集为原始字节（多字节 UTF-8 序列保持连贯），
// 最后整体按 UTF-8 组装；非法 % 序列保留 '%' 原字符。
// 注意：不能按单字节 toChar 逐个追加——那会把 %E9%BB%91 这类 UTF-8
// 多字节序列解成三个 Latin-1 字符（é»）而非“黑”。
/**
 * Range 头解析（纯函数，可单测）。三重防御（与 feat 版 TyranoLocalHttpServer 逐行一致，
 * 支撑媒体回退链路触发的 Range 请求）：
 * 1) 多段 Range（bytes=0-1,4-5）只取第一段，避免 "," 进入 toLong 抛异常走 500；
 * 2) 越界起点（start >= fileLen 或 start > end）回退全量 200，避免 Content-Length: 0 卡死媒体；
 * 3) 整体 try/catch，畸形输入一律回退全量 200；
 * 4) 后缀式 bytes=-N → 末尾 N 字节（此前被错误解析为开头 N+1 字节，PR review 意见）。
 * 无 Range 头或 fileLen<=0 时返回 null（调用方按全量 200 处理）。
 */
internal fun parseRangeHeader(rangeHeader: String?, fileLen: Long): RangeSpec? {
    if (rangeHeader == null || fileLen <= 0) return null
    var start = 0L
    var end = fileLen - 1
    var partial = false
    try {
        if (!rangeHeader.lowercase(Locale.ROOT).startsWith("bytes=")) return null
        // 多段 Range（bytes=0-1,4-5）：本服务只支持单段，静默截取首段会返回非法的
        // 单段 206——检测到逗号直接返回 null，让调用方按全量 200 响应（PR review 意见）
        if (rangeHeader.substring(6).indexOf(',') >= 0) return null
        val range = rangeHeader.substring(6).trim()
        val dash = range.indexOf('-')
        if (dash >= 0) {
            val a = range.substring(0, dash).trim()
            val b = range.substring(dash + 1).trim()
            if (a.isEmpty()) {
                // 后缀式：bytes=-N → 文件末尾 N 字节
                if (b.isNotEmpty()) {
                    val suffix = b.toLong()
                    if (suffix > 0) {
                        start = (fileLen - suffix).coerceAtLeast(0L)
                        end = fileLen - 1
                        partial = true
                    }
                }
            } else {
                if (a.isNotEmpty()) start = a.toLong()
                if (b.isNotEmpty()) end = b.toLong()
                if (end >= fileLen) end = fileLen - 1
                if (start < 0) start = 0
                if (start >= fileLen || start > end) {
                    start = 0; end = fileLen - 1; partial = false
                } else if (start <= end) partial = true
            }
        }
    } catch (_: Throwable) {
        start = 0; end = fileLen - 1; partial = false
    }
    return RangeSpec(start, end, partial)
}

internal class RangeSpec(val start: Long, val end: Long, val partial: Boolean)

internal fun decodeUriLenient(uri: String): String {
    if (!uri.contains('%')) return uri
    val bytes = java.io.ByteArrayOutputStream(uri.length)
    val plain = StringBuilder(uri.length)
    var hasDecoded = false
    var i = 0
    val n = uri.length
    while (i < n) {
        val c = uri[i]
        if (c == '%' && i + 2 < n) {
            val h = uri[i + 1]
            val l = uri[i + 2]
            if (h.isHexDigit() && l.isHexDigit()) {
                bytes.write(plain.toString().toByteArray(Charsets.UTF_8))
                plain.setLength(0)
                bytes.write(((Character.digit(h, 16) shl 4) or Character.digit(l, 16)))
                hasDecoded = true
                i += 3
                continue
            }
        }
        plain.append(c)
        i++
    }
    if (!hasDecoded) return uri
    bytes.write(plain.toString().toByteArray(Charsets.UTF_8))
    return bytes.toString("UTF-8")
}

private fun Char.isHexDigit(): Boolean =
    (this in '0'..'9') || (this in 'a'..'f') || (this in 'A'..'F')
