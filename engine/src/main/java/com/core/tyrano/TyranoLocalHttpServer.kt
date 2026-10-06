package com.core.tyrano

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
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.HashMap
import com.core.rpgmaker.parseRangeHeader
import com.core.web.AsarWebRoot
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal class TyranoLocalHttpServer(
    root: File,
    asar: AsarArchive?,
    tyranoHook: ByteArray?,
    private val injectBeforeBody: Boolean = false,
    scriptAppends: Map<String, ByteArray> = emptyMap(),
    private val injectedHtml: String = "",
    internalResources: Map<String, ByteArray> = emptyMap(),
    /** 期望的固定端口（0 = 随机）；被占用时回退随机并由 [usedFallbackPort] 标记。 */
    preferredPort: Int = com.core.web.WebShellServerSocket.RANDOM_PORT,
) : Runnable {
    private val root: File
    private val asar: AsarArchive?
    private val tyranoHook: ByteArray
    private val asarRootPrefix: String
    private val scriptAppends: Map<String, ByteArray> = scriptAppends.mapKeys { it.key.lowercase(Locale.ROOT) }
    private val internalResources: Map<String, ByteArray> = internalResources.mapKeys {
        it.key.trimStart('/').lowercase(Locale.ROOT)
    }
    private val serverSocket: ServerSocket
    private val thread: Thread
    /** 期望端口被占用而回退随机端口（调用方据此提示用户）。 */
    val usedFallbackPort: Boolean
    @Volatile
    private var running = true
    private val clients: ThreadPoolExecutor

    init {
        this.root = root.canonicalFile
        this.asar = asar
        this.tyranoHook = tyranoHook ?: ByteArray(0)
        // 与 rpgmaker 服务器/fs 桥共用同一探测规则（com.core.web.AsarWebRoot）
        this.asarRootPrefix = if (asar == null) "" else AsarWebRoot.prefixFor { asar.has(it) }
        val bound = com.core.web.WebShellServerSocket.bind(preferredPort)
        this.serverSocket = bound.socket
        this.usedFallbackPort = bound.usedFallbackPort
        this.thread = Thread(this, "YukiTyranoLocalHttpServer").apply { isDaemon = true }
        this.clients = ThreadPoolExecutor(
            2, 8, 30L, TimeUnit.SECONDS,
            ArrayBlockingQueue<Runnable>(64),
            { runnable: Runnable -> Thread(runnable, "YukiTyranoHttpClient").apply { isDaemon = true } },
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
        preferredPort: Int = com.core.web.WebShellServerSocket.RANDOM_PORT,
    ) : this(root, null, tyranoHook, injectBeforeBody, scriptAppends, injectedHtml, internalResources, preferredPort)

    fun start() { thread.start() }
    val port: Int get() = serverSocket.localPort
    fun stop() {
        running = false
        try { serverSocket.close() } catch (_: Throwable) {}
        clients.shutdownNow()
    }

    override fun run() {
        Log.i(TAG, "local server started port=$port root=$root")
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
        private const val TAG = "YukiTyrano"
        /** asar 模式下的 index 入口回退路径，与 asarRootPrefix 探测保持一致。 */
        private val ASAR_INDEX_CANDIDATES = arrayOf(
            "index.html", "www/index.html", "app/index.html", "resources/app/index.html",
        )
    }

    /** 命中结果：散文件 [file] 或 asar 内条目 [asarPath]，二者至多其一非空。 */
    private class ResolvedFile(val file: File?, val asarPath: String?)

    private fun handle(socket: Socket) {
        try {
            socket.soTimeout = 15000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
            val requestLine = reader.readLine()
            if (requestLine.isNullOrEmpty()) { close(socket); return }
            val headers = HashMap<String, String>()
            var line: String?
            while (reader.readLine().also { line = it } != null && line!!.isNotEmpty()) {
                val idx = line!!.indexOf(':')
                if (idx > 0) headers[line!!.substring(0, idx).trim().lowercase(Locale.ROOT)] = line!!.substring(idx + 1).trim()
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
            uri = URLDecoder.decode(uri, StandardCharsets.UTF_8.name())
            if (uri == "/") uri = "/index.html"
            while (uri.startsWith("/")) uri = uri.substring(1)
            internalResources[uri.lowercase(Locale.ROOT)]?.let { resource ->
                sendBytes(socket, resource, uri, method.equals("HEAD", true))
                return
            }
            val headOnly = method.equals("HEAD", true)
            val resolved = resolveRequestedFile(uri)
            val asarEntry = resolved.asarPath
            if (asarEntry != null) {
                // 仅 index 注入与脚本追加需要整体字节；其余（含视频）按 Range 流式读取，避免大文件整体入内存。
                if (isIndexHtml(uri)) sendInjectedIndex(socket, asar?.read(asarEntry), headOnly)
                else if (hasScriptAppend(uri)) sendAppendedBytes(socket, asar?.read(asarEntry) ?: ByteArray(0), uri, headOnly)
                else sendAsarResource(socket, asarEntry, headers["range"], headOnly)
                return
            }
            val file = resolved.file
            if (file == null) {
                sendText(socket, 404, "Not Found", "not found: $uri")
                return
            }
            if (isIndexHtml(uri, file)) {
                sendInjectedIndex(socket, file, headOnly)
                return
            }
            if (hasScriptAppend(uri)) {
                sendAppendedFile(socket, file, uri, headOnly)
                return
            }
            sendFile(socket, file, headers["range"], headOnly)
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
        if (lower.endsWith(".rpgmvm")) {
            val alt = replaceSuffix(uri, ".rpgmvm", ".rpgmvo")
            target = canonicalIfValid(alt)
            if (target != null) { Log.i(TAG, "resource fallback rpgmvm->rpgmvo $uri -> $alt"); return ResolvedFile(target, null) }
        }
        resolveAsarEntry(uri)?.let { return ResolvedFile(null, it) }
        return ResolvedFile(resolveCaseInsensitive(uri), null)
    }

    /** 在 asar 中定位 [uri]：先按包内根前缀，再按原路径；仅 index 请求额外回退到常见入口路径。 */
    private fun resolveAsarEntry(uri: String): String? {
        val archive = asar ?: return null
        if (archive.fileSize(asarRootPrefix + uri) != null) return asarRootPrefix + uri
        if (archive.fileSize(uri) != null) return uri
        if (uri.equals("index.html", true) || uri.equals("index.htm", true)) {
            for (candidate in ASAR_INDEX_CANDIDATES) {
                if (archive.fileSize(candidate) != null) return candidate
            }
        }
        return null
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

    private fun sendInjectedIndex(socket: Socket, html: String?, headOnly: Boolean) {
        val data = buildInjectedHtml(html.orEmpty(), tyranoHook, injectedHtml, injectBeforeBody)
        Log.i(TAG, "served injected index bytes=${data.size} hook=${tyranoHook.size}")
        val out = BufferedOutputStream(socket.getOutputStream())
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nCache-Control: no-cache\r\nAccess-Control-Allow-Origin: *\r\nContent-Length: ${data.size}\r\nConnection: close\r\n\r\n").toByteArray(StandardCharsets.UTF_8))
        if (!headOnly) out.write(data)
        out.flush()
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
        // 复用 rpgmaker 宿主已单测的 Range 解析（同为 engine 模块的 internal 工具）。
        // 本文件此前内联了一份简化实现，且已漂移出三处行为差异：
        //   bytes=-100（后缀式）→ 旧实现当作 0..100（返回错误字节段而非末尾 100 字节）
        //   bytes=999999-（起点越界）→ 旧实现得 len=0（Content-Length: 0，播放器卡死）
        //   多段/畸形 Range → 旧实现抛 NumberFormatException（500）
        // 共享实现把这三类分别处理为：末尾 N 字节 / 回退全量 200 / 回退全量 200。
        val range = parseRangeHeader(rangeHeader, fileLen)
        val start = range?.start ?: 0L
        val end = range?.end ?: (fileLen - 1)
        val partial = range?.partial == true
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

    /**
     * asar 内资源的 Range 响应：按区间从归档流式读取，不整体载入内存。
     * 视频等大文件必须走此路径，否则单次请求会分配上百 MB 大对象并因缺少分段支持导致
     * 播放器报 `net::ERR_FAILED`。
     */
    private fun sendAsarResource(socket: Socket, entryPath: String, rangeHeader: String?, headOnly: Boolean) {
        val archive = asar
        val fileLen = archive?.fileSize(entryPath)
        if (archive == null || fileLen == null) {
            sendText(socket, 404, "Not Found", "asar entry missing: $entryPath")
            return
        }
        // 与 sendFile 共用 rpgmaker 宿主的 parseRangeHeader，避免同规则两份实现漂移。
        val range = parseRangeHeader(rangeHeader, fileLen)
        val start = range?.start ?: 0L
        val end = range?.end ?: (fileLen - 1)
        val partial = range?.partial == true
        val len = Math.max(0, end - start + 1)
        val raw = BufferedOutputStream(socket.getOutputStream())
        val h = StringBuilder()
        h.append("HTTP/1.1 ").append(if (partial) "206 Partial Content" else "200 OK").append("\r\n")
        h.append("Accept-Ranges: bytes\r\n")
        h.append("Content-Type: ").append(mime(entryPath)).append("\r\n")
        h.append("Cache-Control: no-cache\r\n")
        h.append("Access-Control-Allow-Origin: *\r\n")
        h.append("Content-Length: ").append(len).append("\r\n")
        if (partial) h.append("Content-Range: bytes ").append(start).append('-').append(end).append('/').append(fileLen).append("\r\n")
        h.append("Connection: close\r\n\r\n")
        raw.write(h.toString().toByteArray(StandardCharsets.UTF_8))
        if (!headOnly) archive.writeRange(entryPath, start, len, raw)
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
    // 首选标记缺失时回退到另一个，避免缺少 </body> 的页面被退化为「整体前置」而触发怪异模式
    val lower = html.lowercase(Locale.ROOT)
    val primary = if (beforeBody) "</body>" else "</head>"
    val secondary = if (beforeBody) "</head>" else "</body>"
    val position = lower.indexOf(primary).takeIf { it >= 0 } ?: lower.indexOf(secondary)
    val result = if (position >= 0) {
        html.substring(0, position) + injected + html.substring(position)
    } else {
        injected + html
    }
    return result.toByteArray(StandardCharsets.UTF_8)
}
