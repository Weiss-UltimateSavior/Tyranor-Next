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
    @Volatile
    private var running = true
    private val clients: ThreadPoolExecutor

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
    ) : this(root, null, tyranoHook, injectBeforeBody, scriptAppends, injectedHtml, internalResources)

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
    }

    private class ResolvedFile(val file: File?, val data: ByteArray?)

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
            val resolved = resolveRequestedFile(uri)
            if (resolved == null || (resolved.file == null && resolved.data == null)) {
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
        if (lower.endsWith(".rpgmvm")) {
            val alt = replaceSuffix(uri, ".rpgmvm", ".rpgmvo")
            target = canonicalIfValid(alt)
            if (target != null) { Log.i(TAG, "resource fallback rpgmvm->rpgmvo $uri -> $alt"); return ResolvedFile(target, null) }
        }
        if (asar != null) {
            val data = asar.read(asarRootPrefix + uri) ?: asar.read(uri)
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
        var start = 0L
        var end = fileLen - 1
        var partial = false
        if (rangeHeader != null && rangeHeader.lowercase(Locale.ROOT).startsWith("bytes=")) {
            val range = rangeHeader.substring(6).trim()
            val dash = range.indexOf('-')
            if (dash >= 0) {
                val a = range.substring(0, dash).trim()
                val b = range.substring(dash + 1).trim()
                if (a.isNotEmpty()) start = a.toLong()
                if (b.isNotEmpty()) end = b.toLong()
                if (end >= fileLen) end = fileLen - 1
                if (start < 0) start = 0
                if (start <= end) partial = true
            }
        }
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
