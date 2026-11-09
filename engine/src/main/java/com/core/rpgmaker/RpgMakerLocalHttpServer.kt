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
import com.core.web.AsarWebRoot
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
    private val fuzzyNames: Boolean = false,
    /** 期望的固定端口（0 = 随机）；被占用时回退随机并由 [usedFallbackPort] 标记。 */
    preferredPort: Int = com.core.web.WebShellServerSocket.RANDOM_PORT,
    /**
     * 注入到 `</head>` 前的**原始 HTML**（v1/v2 起兼容层改为外链 `<script src>`，
     * 首页不再内联 276 KB 脚本）。与 [earlyHook] 并存，earlyHtml 先于内联 earlyHook 执行。
     */
    private val earlyHtml: String = "",
) : Runnable {
    private val root: File
    private val asar: AsarArchive?
    private val tyranoHook: ByteArray
    private val asarRootPrefix: String
    private val scriptAppends: Map<String, ByteArray> = scriptAppends.mapKeys { it.key.lowercase(Locale.ROOT) }
    private val internalResources: Map<String, ByteArray> = internalResources.mapKeys {
        it.key.trimStart('/').replace(DOT_SEGMENT, "/").replace(DOUBLE_SLASH, "/").lowercase(Locale.ROOT)
    }
    private val serverSocket: ServerSocket
    private val thread: Thread
    /** 期望端口被占用而回退随机端口（调用方据此提示用户）。 */
    val usedFallbackPort: Boolean
    @Volatile
    private var running = true
    private val clients: ThreadPoolExecutor

    /**
     * 目录名索引：fuzzy 名匹配的目录枚举按「目录 + mtime」缓存。
     *
     * 服务器本身不写盘，但同进程的 fs 桥会写 `contentRoot` 内的文件——靠 mtime 兜底刷新；
     * 同毫秒窗口被「匹配结果只是候选名、最终仍 `exists()` 复核」挡住。
     */
    private val dirIndex = RpgMakerDirIndex()

    /** 活跃/排队中的客户端 socket：`stop()` 时逐个关闭，避免线程与 FD 滞留。 */
    private val clientSockets: MutableSet<Socket> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    // 每实例随机鉴权 token：绑定 127.0.0.1 不限制同设备其他应用，端口扫描可读取
    // 游戏目录内文件（含存档，PR review 意见）。index 页为引导入口无条件下发
    // Set-Cookie，其余请求必须携带匹配 token 的 Cookie，否则 403。
    private val authToken: String = newAuthToken()

    init {
        this.root = root.canonicalFile
        this.asar = asar
        this.tyranoHook = tyranoHook ?: ByteArray(0)
        // 探测规则与 fs 桥、tyrano 服务器共用（com.core.web.AsarWebRoot），
        // 避免「服务器读得到、fs 桥读不到」的分裂行为
        this.asarRootPrefix = if (asar == null) "" else AsarWebRoot.prefixFor { asar.has(it) }
        val bound = com.core.web.WebShellServerSocket.bind(preferredPort)
        this.serverSocket = bound.socket
        this.usedFallbackPort = bound.usedFallbackPort
        this.thread = Thread(this, "YukiRpgMakerLocalHttpServer").apply { isDaemon = true }
        // keep-alive 下 worker 会阻塞在「等下一个请求」上，core=2 会让有效并发恒为 2
        // （ThreadPoolExecutor 只在队列满时才扩线程）：首页并发取 3 个脚本就会排队。
        // 因此 core = max = 8（Chromium 同源最多 6 条连接），并允许核心线程空闲回收。
        this.clients = ThreadPoolExecutor(
            8, 8, 30L, TimeUnit.SECONDS,
            ArrayBlockingQueue<Runnable>(64),
            { runnable: Runnable -> Thread(runnable, "YukiRpgMakerHttpClient").apply { isDaemon = true } },
            ThreadPoolExecutor.AbortPolicy(),
        ).apply { allowCoreThreadTimeOut(true) }
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
        fuzzyNames: Boolean = false,
        preferredPort: Int = com.core.web.WebShellServerSocket.RANDOM_PORT,
        earlyHtml: String = "",
    ) : this(root, null, tyranoHook, injectBeforeBody, scriptAppends, injectedHtml, internalResources, earlyHook, v12Compat, fuzzyNames, preferredPort, earlyHtml)

    fun start() { thread.start() }
    val port: Int get() = serverSocket.localPort
    fun stop() {
        running = false
        try { serverSocket.close() } catch (_: Throwable) {}
        // 关闭所有已接受/排队中的连接：shutdownNow 只中断 worker，阻塞在 socket 读上的
        // 线程与尚未开始的任务都还握着 FD，必须显式 close 才能立刻回收
        val queued = clients.shutdownNow()
        for (socket in clientSockets) close(socket)
        clientSockets.clear()
        runCatching { clients.awaitTermination(200L, TimeUnit.MILLISECONDS) }
        if (queued.isNotEmpty()) Log.d(TAG, "dropped ${queued.size} queued request(s) on stop")
    }

    override fun run() {
        Log.i(TAG, "local server started port=$port root=$root v12Compat=$v12Compat earlyHtml=${earlyHtml.length} early=${earlyHook?.size ?: 0} late=${tyranoHook.size} internalRes=${internalResources.size}")
        while (running) {
            try {
                val socket = serverSocket.accept()
                // 先登记再入池：排队中的连接也必须能被 stop() 关掉
                clientSockets.add(socket)
                try { clients.execute { handle(socket) } }
                catch (_: java.util.concurrent.RejectedExecutionException) {
                    clientSockets.remove(socket)
                    close(socket)
                }
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

        /** 单连接最多复用的请求数（防止异常客户端长期占用工作线程）。 */
        private const val MAX_KEEPALIVE_REQUESTS = 96

        /** 首个请求的读取超时（含请求头）。 */
        private const val REQUEST_READ_TIMEOUT_MS = 15_000

        /** keep-alive 空闲等待上限；同时写进 Keep-Alive 响应头。 */
        private const val KEEPALIVE_IDLE_TIMEOUT_MS = 1_500

        /** 可改写文件用内容哈希做 ETag 的体积上限（超出只看 mtime，避免为大文件多读一遍）。 */
        private const val MAX_CONTENT_ETAG_BYTES = 1L * 1024L * 1024L

        /**
         * 只读素材/脚本的缓存策略：允许 WebView 在会话内直接复用（旧实现一律 `no-cache`，
         * 切图/读档时几百个请求全量重传）。
         */
        private const val ASSET_CACHE_CONTROL = "max-age=3600"

        /**
         * 可被插件原地改写的目录必须每次重新验证：v2 的 fs 桥会让插件改完 `data/` 里的
         * JSON 再让引擎重新读取，命中 `max-age` 就会读到旧值。ETag 让这次重新验证只花一个 304。
         */
        private const val REWRITABLE_CACHE_CONTROL = "no-cache"

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
            socket.soTimeout = REQUEST_READ_TIMEOUT_MS
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
            var served = 0
            var idleTimeout = REQUEST_READ_TIMEOUT_MS
            // 连接复用：Chromium 默认 HTTP/1.1 keep-alive，逐个请求复用同一 socket。
            // 旧实现每个响应都带 `Connection: close`，一次切图就要重建几百条 TCP 连接。
            while (running && served < MAX_KEEPALIVE_REQUESTS) {
                socket.soTimeout = idleTimeout
                val requestLine = readBoundedLine(reader, MAX_REQUEST_LINE_CHARS)
                if (requestLine.isNullOrEmpty()) return
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
                if (!method.equals("GET", true) && !method.equals("HEAD", true)) {
                    sendText(socket, 405, "Method Not Allowed", "method not allowed")
                    return
                }
                var uri = parts[1]
                val q = uri.indexOf('?')
                if (q >= 0) uri = uri.substring(0, q)
                uri = decodeUriLenient(uri)
                if (uri == "/") uri = "/index.html"
                while (uri.startsWith("/")) uri = uri.substring(1)
                val normalizedLookup = uri.replace(DOT_SEGMENT, "/").replace(DOUBLE_SLASH, "/").lowercase(Locale.ROOT)
                // 最后一个可复用名额用显式 close 收尾：否则客户端会把下一个请求发进我们将要关闭的连接
                val keepAlive = wantsKeepAlive(parts.getOrNull(2), headers) && served + 1 < MAX_KEEPALIVE_REQUESTS
                val keepOpen = serveOne(
                    socket = socket,
                    headOnly = method.equals("HEAD", true),
                    uri = uri,
                    normalizedLookup = normalizedLookup,
                    headers = headers,
                    keepAlive = keepAlive,
                )
                if (!keepOpen) return
                served++
                idleTimeout = KEEPALIVE_IDLE_TIMEOUT_MS
            }
        } catch (t: Throwable) {
            if (t is java.net.SocketTimeoutException) {
                // keep-alive 空闲超时（或客户端发了半截请求）：正常回收连接，不算错误
                Log.d(TAG, "connection idle timeout, closing")
            } else if (isExpectedClientDisconnect(t)) {
                Log.d(TAG, "client disconnected while serving local resource: ${t.javaClass.simpleName}")
            } else if (t is java.io.IOException) {
                // 响应可能已经写出部分 body：再补 500 会拼进残缺响应，直接关连接即可
                Log.w(TAG, "I/O failure while serving request: ${t.javaClass.simpleName}")
            } else {
                try { sendText(socket, 500, "Internal Server Error", "server error") } catch (_: Throwable) {}
                Log.w(TAG, "handle request failed", t)
            }
        } finally {
            clientSockets.remove(socket)
            close(socket)
        }
    }

    /**
     * 处理一个已解析的请求。返回 true 表示连接可以继续复用（keep-alive），false 应立即关闭。
     *
     * 拆出来是为了让 [handle] 只负责请求行/头解析与连接生命周期，并把「复用还是关闭」
     * 的判定收敛到返回值上：错误响应一律关闭，避免把异常状态带进下一个请求；
     * 实际写出字节数少于声明长度时也必须关闭（否则下个响应会被当成这个响应的 body）。
     */
    private fun serveOne(
        socket: Socket,
        headOnly: Boolean,
        uri: String,
        normalizedLookup: String,
        headers: Map<String, String>,
        keepAlive: Boolean,
    ): Boolean {
        // 鉴权门：仅 index 页作为引导入口豁免（响应下发 Set-Cookie），其余请求
        // 必须携带匹配 token 的 Cookie；同设备其他应用无 Cookie 一律 403
        val isEntryPage = uri.equals("index.html", ignoreCase = true) || uri.equals("index.htm", ignoreCase = true)
        if (!isEntryPage && !hasValidAuth(headers)) {
            Log.w(TAG, "403 unauthorized uri=$uri")
            sendText(socket, 403, "Forbidden", "forbidden")
            return false
        }
        val ifNoneMatch = headers["if-none-match"]
        // 每请求只算一次缓存策略：200 与 304 必须是同一个值。若 200 走请求路径、304 走
        // 磁盘相对路径，`./data/x.json` 这类写法会让 304 把可改写文件钉成 max-age。
        val cacheControl = cacheControlFor(uri)

        internalResources[normalizedLookup]?.let { resource ->
            // 内存响应用整块 write：写失败必抛 IOException 走关闭分支，不需要像
            // sendFile/sendStream 那样记账「是否写满」（磁盘文件可能在声明长度后被截断）
            val etag = contentEtag(resource)
            if (matchesEtag(ifNoneMatch, etag)) sendNotModified(socket, etag, cacheControl, keepAlive)
            else sendBytes(socket, resource, uri, headOnly, keepAlive, cacheControl, etag)
            return keepAlive
        }

        val resolved = resolveRequestedFile(uri)
        // ASAR 大条目（>256KiB，媒体为主）流式分支必须在 404 判空之前：
        // 流式结果 file/data 均为 null，放在后面会被判 404 且泄漏已打开的文件句柄
        // （PR review Critical 意见）
        if (resolved != null && resolved.stream != null) {
            val sent = sendStream(
                socket, resolved.stream, resolved.streamLen, uri, headers["range"], headOnly, keepAlive, cacheControl,
            )
            return sent && keepAlive
        }
        if (resolved == null || (resolved.file == null && resolved.data == null)) {
            Log.w(TAG, "404 uri=$uri")
            sendText(socket, 404, "Not Found", "not found: $uri")
            return false
        }
        val data = resolved.data
        if (data != null) {
            when {
                isIndexHtml(uri) -> sendInjectedIndex(socket, data, headOnly, keepAlive)
                hasScriptAppend(uri) -> {
                    val patched = appendedBytes(data, uri)
                    val etag = contentEtag(patched)
                    Log.i(TAG, "served patched script uri=$uri original=${data.size} append=${patched.size - data.size}")
                    if (matchesEtag(ifNoneMatch, etag)) sendNotModified(socket, etag, cacheControl, keepAlive)
                    else sendBytes(socket, patched, uri, headOnly, keepAlive, cacheControl, etag)
                }
                else -> {
                    val etag = contentEtag(data)
                    if (matchesEtag(ifNoneMatch, etag)) sendNotModified(socket, etag, cacheControl, keepAlive)
                    else sendBytes(socket, data, uri, headOnly, keepAlive, cacheControl, etag)
                }
            }
            return keepAlive
        }
        val file = resolved.file
        if (file != null && isIndexHtml(uri, file)) {
            sendInjectedIndex(socket, file, headOnly, keepAlive)
            return keepAlive
        }
        if (file != null && hasScriptAppend(uri)) {
            val patched = appendedBytes(file.readBytes(), uri)
            val etag = contentEtag(patched)
            Log.i(TAG, "served patched script uri=$uri append=${patched.size}")
            if (matchesEtag(ifNoneMatch, etag)) sendNotModified(socket, etag, cacheControl, keepAlive)
            else sendBytes(socket, patched, uri, headOnly, keepAlive, cacheControl, etag)
            return keepAlive
        }
        if (file == null) {
            sendText(socket, 404, "Not Found", "file missing")
            return false
        }
        // 缓存策略按**解析后的磁盘相对路径**判定：`./data/x.json`、`a/../data/x.json`
        // 这类写法同样要落到 no-cache，否则插件改完同会话内会读到旧内容。
        val fileCacheControl = cacheControlFor(relativeToRoot(file))
        val rewritable = fileCacheControl == REWRITABLE_CACHE_CONTROL
        // 可改写文件用内容哈希做 ETag：length+mtime 在粗粒度时间戳（exFAT 2s）或
        // 「同长度同毫秒改写」下不变，会 304 出旧 JSON。大文件不读内容，退回 mtime。
        val etag = fileEtag(file, contentHash = rewritable)
        if (matchesEtag(ifNoneMatch, etag)) {
            sendNotModified(socket, etag, fileCacheControl, keepAlive)
            return keepAlive
        }
        val sent = sendFile(socket, file, headers["range"], headOnly, keepAlive, fileCacheControl, etag)
        return sent && keepAlive
    }

    /** 是否复用连接：HTTP/1.1 默认复用，`Connection: close` 一律关闭，HTTP/1.0 需显式请求。 */
    private fun wantsKeepAlive(version: String?, headers: Map<String, String>): Boolean {
        if (!running) return false
        val conn = headers["connection"]?.lowercase(Locale.ROOT)
        if (conn != null && conn.contains("close")) return false
        if (conn != null && conn.contains("keep-alive")) return true
        return version != null && version.equals("HTTP/1.1", ignoreCase = true)
    }

    /** `If-None-Match` 与当前 ETag 是否等价（支持 `*`、逗号列表与弱校验前缀 `W/`）。 */
    private fun matchesEtag(ifNoneMatch: String?, etag: String): Boolean {
        if (ifNoneMatch.isNullOrEmpty() || etag.isEmpty()) return false
        val header = ifNoneMatch.trim()
        if (header == "*") return true
        return header.split(',').any { part -> part.trim().removePrefix("W/").trim() == etag }
    }

    /**
     * 按路径决定缓存策略。
     *
     * 会被插件原地改写的资源必须每次重新验证：`data/`、`save/`（v2 的 fs 桥会让插件改完
     * 数据表/配置再让引擎重读）以及任何 `.json`（插件常用 JSON 当配置/缓存）。
     * 其余（img/audio/fonts/css/脚本）按会话内可复用处理，ETag 兜住改动后的重新验证。
     */
    private fun cacheControlFor(uri: String): String {
        val path = uri.lowercase(Locale.ROOT)
        return if (path.startsWith("data/") || path.startsWith("save/") || path.endsWith(".json")) {
            REWRITABLE_CACHE_CONTROL
        } else {
            ASSET_CACHE_CONTROL
        }
    }

    /** 磁盘文件 → 相对网页根的路径（root 与 file 都已 canonicalize，纯字符串运算）。 */
    private fun relativeToRoot(file: File): String {
        val rootPath = root.path
        val path = file.path
        return if (path == rootPath) "" else path.removePrefix(rootPath + File.separator)
    }

    /** 内存内容（内部资源/补丁脚本）的强校验：内容 CRC + 长度，内容一变即失效。 */
    private fun contentEtag(data: ByteArray): String {
        val crc = java.util.zip.CRC32()
        crc.update(data, 0, data.size)
        return "\"${data.size.toString(16)}-${crc.value.toString(16)}\""
    }

    /**
     * 磁盘文件的校验：默认「长度 + mtime」（不读内容，图片/音频零成本）；
     * [contentHash] 为真（可被插件改写的 JSON）时改用内容哈希，避免同长度同时间刻度改写
     * 命中 304 拿到旧内容。超过 [MAX_CONTENT_ETAG_BYTES] 不读内容，退回 mtime。
     */
    private fun fileEtag(file: File, contentHash: Boolean = false): String {
        if (contentHash && file.length() <= MAX_CONTENT_ETAG_BYTES) {
            return runCatching { contentEtag(file.readBytes()) }
                .getOrElse { mtimeEtag(file) }
        }
        return mtimeEtag(file)
    }

    private fun mtimeEtag(file: File): String =
        "\"${file.length().toString(16)}-${file.lastModified().toString(16)}\""

    private fun connectionHeader(keepAlive: Boolean): String =
        if (keepAlive) "Connection: keep-alive\r\nKeep-Alive: timeout=${KEEPALIVE_IDLE_TIMEOUT_MS / 1000}\r\n"
        else "Connection: close\r\n"

    private fun sendNotModified(socket: Socket, etag: String, cacheControl: String, keepAlive: Boolean) {
        val out = BufferedOutputStream(socket.getOutputStream())
        out.write(
            ("HTTP/1.1 304 Not Modified\r\n" +
                "ETag: $etag\r\n" +
                "Cache-Control: $cacheControl\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                connectionHeader(keepAlive) +
                "\r\n").toByteArray(StandardCharsets.UTF_8),
        )
        out.flush()
    }

    /** 兼容层补丁脚本：把注入片段追加到原脚本末尾（返回新数组，不修改入参）。 */
    private fun appendedBytes(original: ByteArray, uri: String): ByteArray {
        val append = scriptAppends[uri.lowercase(Locale.ROOT)] ?: ByteArray(0)
        if (append.isEmpty()) return original
        return ByteArray(original.size + append.size).also {
            original.copyInto(it)
            append.copyInto(it, original.size)
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
        return ResolvedFile(resolveByName(uri, allowWhitespaceTolerance = fuzzyNames), null)
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

    /**
     * 文件名回退解析。[allowWhitespaceTolerance] 为真（仅 v2 会话）时在大小写不敏感
     * 之外再启用空白类别/尾随空白容忍，修「素材文件名被规范化后与 data 引用不一致」
     * 导致的整批 404（见 [RpgMakerNameMatcher]）；为假时保持 v0/v1 的历史语义。
     *
     * 目录枚举走 [dirIndex]（按「目录 + mtime」缓存）：旧实现每次 miss 都对每一级
     * `listFiles()` 并构造 `File` 列表，素材名被规范化时会在同一目录上反复扫描。
     * 条目上限在 [RpgMakerNameMatcher] 内部统一处理（fs 桥用同一函数，两端结论必须一致）。
     */
    private fun resolveByName(uri: String?, allowWhitespaceTolerance: Boolean): File? {
        if (uri == null || uri.isEmpty() || uri.contains("..")) return null
        val parts = uri.split("/")
        var current: File = root
        for (part in parts) {
            if (part.isEmpty()) continue
            if (!allowWhitespaceTolerance) {
                // v0/v1 历史路径：精确命中直接用；否则大小写不敏感取首个命中
                val exact = File(current, part)
                if (exact.exists()) { current = exact; continue }
                val children = dirIndex.children(current) ?: return null
                val matched = children.firstOrNull { it.equals(part, ignoreCase = true) } ?: return null
                current = File(current, matched)
                continue
            }
            val children = dirIndex.children(current) ?: return null
            // 条目上限由 [RpgMakerNameMatcher] 统一实现：fs 桥走同一个函数，
            // 保证「同一路径经 XHR 与经 fs 得到同一结论」这个不变量不被两边各写一套打破
            val matchedName = RpgMakerNameMatcher.match(children, part) ?: return null
            current = File(current, matchedName)
        }
        val target = current.canonicalFile
        if (!isInsideRoot(target) || !target.isFile) return null
        if (target.name != parts.lastOrNull()) {
            if (target.name.equals(parts.lastOrNull(), ignoreCase = true)) {
                Log.i(TAG, "resource fallback case-insensitive $uri -> ${target.path}")
            } else {
                Log.i(TAG, "resource fallback loose-name $uri -> ${target.path}")
            }
        }
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

    private fun sendInjectedIndex(socket: Socket, file: File, headOnly: Boolean, keepAlive: Boolean) {
        sendInjectedIndex(socket, readTextFile(file), headOnly, keepAlive)
    }

    private fun sendInjectedIndex(socket: Socket, htmlBytes: ByteArray?, headOnly: Boolean, keepAlive: Boolean) {
        val text = if (htmlBytes == null) "" else String(htmlBytes, StandardCharsets.UTF_8)
        sendInjectedIndex(socket, text, headOnly, keepAlive)
    }

    // 引导入口响应附带 Set-Cookie：后续同源资源请求由 WebView 自动携带
    private fun authCookieHeader(): String =
        "Set-Cookie: $AUTH_COOKIE_NAME=$authToken; Path=/; HttpOnly\r\n"

    private fun hasValidAuth(headers: Map<String, String>): Boolean =
        headers["cookie"]?.contains("$AUTH_COOKIE_NAME=$authToken") == true

    private fun sendInjectedIndex(socket: Socket, html: String?, headOnly: Boolean, keepAlive: Boolean) {
        val hasEarly = earlyHtml.isNotBlank() || (earlyHook != null && earlyHook.isNotEmpty())
        val data = if (hasEarly) {
            buildInjectedHtmlTwoPhase(
                html.orEmpty(), earlyHook ?: ByteArray(0), tyranoHook, injectedHtml, injectBeforeBody, earlyHtml,
            )
        } else {
            buildInjectedHtml(html.orEmpty(), tyranoHook, injectedHtml, injectBeforeBody)
        }
        Log.i(TAG, "served injected index bytes=${data.size} hook=${tyranoHook.size} earlyHtml=${earlyHtml.length}")
        val out = BufferedOutputStream(socket.getOutputStream())
        out.write(
            ("HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/html; charset=utf-8\r\n" +
                // 首页每次都下发鉴权 Cookie，不能进缓存
                "Cache-Control: no-cache\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                authCookieHeader() +
                "Content-Length: ${data.size}\r\n" +
                connectionHeader(keepAlive) +
                "\r\n").toByteArray(StandardCharsets.UTF_8),
        )
        if (!headOnly) out.write(data)
        out.flush()
    }

    /**
     * ASAR 大条目流式响应：与 [sendFile] 同一套 Range 语义（含后缀式 bytes=-N），
     * 跳过起始字节后分块复制；输入流由本函数负责关闭（PR review 意见）。
     *
     * 返回值 = 声明的 `Content-Length` 是否写满。提前 EOF 说明实际长度与声明不符，
     * 调用方必须关闭连接：复用会让客户端把下一个响应的字节当成这个响应的 body。
     */
    private fun sendStream(
        socket: Socket,
        input: java.io.InputStream,
        totalLen: Long,
        uri: String,
        rangeHeader: String?,
        headOnly: Boolean,
        keepAlive: Boolean,
        cacheControl: String,
    ): Boolean {
        val spec = parseRangeHeader(rangeHeader, totalLen) ?: RangeSpec(0, (totalLen - 1).coerceAtLeast(0), false)
        // 空条目必须发 0 字节：(end - start + 1) 在 totalLen=0 时会算出 1
        val len = if (totalLen <= 0L) 0L else Math.max(0L, spec.end - spec.start + 1)
        val status = if (spec.partial) "206 Partial Content" else "200 OK"
        var complete = true
        try {
            val raw = BufferedOutputStream(socket.getOutputStream())
            val h = StringBuilder()
            h.append("HTTP/1.1 ").append(status).append("\r\n")
            h.append("Accept-Ranges: bytes\r\n")
            h.append("Content-Type: ").append(mime(uri)).append("\r\n")
            h.append("Cache-Control: ").append(cacheControl).append("\r\n")
            h.append("Access-Control-Allow-Origin: *\r\n")
            h.append("Content-Length: ").append(len).append("\r\n")
            if (spec.partial) h.append("Content-Range: bytes ").append(spec.start).append('-').append(spec.end).append('/').append(totalLen).append("\r\n")
            h.append(connectionHeader(keepAlive))
            h.append("\r\n")
            raw.write(h.toString().toByteArray(StandardCharsets.UTF_8))
            if (!headOnly && len > 0L) {
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
                complete = left <= 0L
            }
            raw.flush()
        } finally {
            try { input.close() } catch (_: Throwable) {}
        }
        return complete
    }

    private fun hasScriptAppend(uri: String): Boolean = scriptAppends.containsKey(uri.lowercase(Locale.ROOT))

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

    /**
     * 磁盘文件响应。返回值 = `Content-Length` 是否写满（false 时调用方必须关连接）。
     *
     * 空文件要按 0 字节发：`fileLen = 0` 时 `(end - start + 1)` 会算出 1，旧实现靠
     * 每响应 `Connection: close` 掩盖了截断，启用 keep-alive 后会变成协议错位。
     */
    private fun sendFile(
        socket: Socket,
        file: File,
        rangeHeader: String?,
        headOnly: Boolean,
        keepAlive: Boolean,
        cacheControl: String,
        etag: String?,
    ): Boolean {
        val fileLen = file.length()
        val spec = parseRangeHeader(rangeHeader, fileLen) ?: RangeSpec(0, (fileLen - 1).coerceAtLeast(0), false)
        val start = spec.start
        val end = spec.end
        val partial = spec.partial
        val len = if (fileLen <= 0L) 0L else Math.max(0L, end - start + 1)
        val status = if (partial) "206 Partial Content" else "200 OK"
        val raw = BufferedOutputStream(socket.getOutputStream())
        val h = StringBuilder()
        h.append("HTTP/1.1 ").append(status).append("\r\n")
        h.append("Accept-Ranges: bytes\r\n")
        h.append("Content-Type: ").append(mime(file.name)).append("\r\n")
        h.append("Cache-Control: ").append(cacheControl).append("\r\n")
        h.append("Access-Control-Allow-Origin: *\r\n")
        h.append("Content-Length: ").append(len).append("\r\n")
        if (!etag.isNullOrEmpty()) h.append("ETag: ").append(etag).append("\r\n")
        if (partial) h.append("Content-Range: bytes ").append(start).append('-').append(end).append('/').append(fileLen).append("\r\n")
        h.append(connectionHeader(keepAlive))
        h.append("\r\n")
        raw.write(h.toString().toByteArray(StandardCharsets.UTF_8))
        var complete = true
        if (!headOnly && len > 0L) {
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
                complete = left <= 0L
            } finally {
                try { inStream.close() } catch (_: Throwable) {}
            }
        }
        raw.flush()
        return complete
    }

    private fun sendBytes(
        socket: Socket,
        data: ByteArray,
        uri: String,
        headOnly: Boolean,
        keepAlive: Boolean,
        cacheControl: String,
        etag: String?,
    ) {
        val raw = BufferedOutputStream(socket.getOutputStream())
        val h = StringBuilder()
        h.append("HTTP/1.1 200 OK\r\n")
        h.append("Content-Type: ").append(mime(uri)).append("\r\n")
        h.append("Cache-Control: ").append(cacheControl).append("\r\n")
        h.append("Access-Control-Allow-Origin: *\r\n")
        h.append("Content-Length: ").append(data.size).append("\r\n")
        if (!etag.isNullOrEmpty()) h.append("ETag: ").append(etag).append("\r\n")
        h.append(connectionHeader(keepAlive))
        h.append("\r\n")
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
    return insertBeforeMarker(html, marker, injected).toByteArray(StandardCharsets.UTF_8)
}

/** 在 [marker] 之前插入 [block]；页面没有该标记时前插（与历史行为一致）。 */
private fun insertBeforeMarker(html: String, marker: String, block: String): String {
    val position = html.lowercase(Locale.ROOT).indexOf(marker)
    return if (position >= 0) html.substring(0, position) + block + html.substring(position) else block + html
}

/**
 * early 段：外链 HTML 在前、内联脚本在后，统一插入 `</head>` 之前（无标记时前插）。
 *
 * 外链 `<script src>` 是经典脚本，按文档顺序同步执行——所以三个兼容层文件只要按
 * base → v1 → v2 的顺序写进 [earlyHtml]，执行顺序与过去内联拼接完全一致。
 */
private fun insertEarlyHtml(html: String, earlyHook: ByteArray, earlyHtml: String): String {
    val script = String(earlyHook, StandardCharsets.UTF_8)
    val inline = if (script.isBlank()) "" else "\n<script type='text/javascript'>\n$script\n</script>\n"
    val early = if (earlyHtml.isBlank()) inline else earlyHtml + inline
    if (early.isEmpty()) return html
    return insertBeforeMarker(html, "</head>", early)
}

/**
 * 两段式注入（v1/v2 会话专用）：early 段注入在 `</head>` 处，late 段注入在
 * `</body>` 处，保证兼容层先于游戏脚本与引擎补丁执行。
 *
 * early 段有两种形态，[earlyHtml] 是**原始 HTML**（v1/v2 起为外链 `<script src>`，
 * 首页不再内联 276 KB 兼容层），[earlyHook] 是内联 JS（历史形态，保留兼容）。
 * 两者并存时 [earlyHtml] 先执行（外链脚本按文档顺序同步执行）。
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
    earlyHtml: String = "",
): ByteArray {
    val withEarly = insertEarlyHtml(html, earlyHook, earlyHtml)
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
