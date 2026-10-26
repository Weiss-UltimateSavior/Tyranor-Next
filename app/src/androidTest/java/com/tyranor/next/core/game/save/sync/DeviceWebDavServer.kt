package com.tyranor.next.core.game.save.sync

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URI
import java.net.URISyntaxException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.Base64
import java.util.Locale
import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bounded, loopback-only WebDAV fixture for device acceptance tests.
 *
 * Paths in [Entry] and [RequestRecord] are wire-encoded paths beginning with [ROOT_PATH].
 * Seed helpers accept decoded paths and encode them before storage.
 */
class DeviceWebDavServer(
    enforceAuth: Boolean = true,
    private val username: String = DEFAULT_USERNAME,
    private val password: String = DEFAULT_PASSWORD,
) : AutoCloseable {
    data class RequestRecord(
        val method: String,
        val target: String,
        val path: String,
        val headers: Map<String, String>,
        val bodySize: Int,
        val status: Int,
    ) {
        fun header(name: String): String? = headers[name.lowercase(Locale.ROOT)]
    }

    class Entry internal constructor(
        val path: String,
        val collection: Boolean,
        private val content: ByteArray,
        val etag: String?,
    ) {
        val bytes: ByteArray get() = content.copyOf()
        val size: Int get() = content.size
    }

    class RequestGate internal constructor() {
        private val entered = CountDownLatch(1)
        private val released = CountDownLatch(1)

        fun awaitEntered(timeoutMillis: Long = 5_000L): Boolean =
            entered.await(timeoutMillis, TimeUnit.MILLISECONDS)

        fun release() {
            released.countDown()
        }

        internal fun blockRequest() {
            entered.countDown()
            try {
                if (!released.await(SOCKET_TIMEOUT_MILLIS.toLong(), TimeUnit.MILLISECONDS)) {
                    throw ProtocolFailure(503, "Request gate timed out")
                }
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw ProtocolFailure(503, "Request gate interrupted")
            }
        }
    }

    private data class ParsedRequest(
        val method: String,
        val target: String,
        val path: String,
        val headers: Map<String, String>,
        val body: ByteArray,
    )

    private data class StoredEntry(
        val collection: Boolean,
        val content: ByteArray,
        val etag: String?,
    )

    private data class HttpResponse(
        val status: Int,
        val headers: List<Pair<String, String>> = emptyList(),
        val body: ByteArray = EMPTY_BYTES,
    )

    private class ProtocolFailure(val status: Int, message: String) : Exception(message)

    private val stateLock = Any()
    private val gateLock = Any()
    private val failureLock = Any()
    private val storedEntries = LinkedHashMap<String, StoredEntry>()
    private val requestRecords = ArrayList<RequestRecord>()
    private val nextGates = HashMap<String, ArrayDeque<RequestGate>>()
    private val outstandingGates = HashSet<RequestGate>()
    private val nextFailures = HashMap<String, ArrayDeque<Int>>()
    private val activeSockets = ConcurrentHashMap.newKeySet<Socket>()
    private val workers = ConcurrentHashMap.newKeySet<Thread>()
    private val closed = AtomicBoolean(false)
    private var etagSequence = 0L
    private val expectedAuthorization =
        if (enforceAuth) basicAuthorization(username, password) else null

    private val serverSocket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val port: Int = serverSocket.localPort
    val serverUrl: String = "http://127.0.0.1:$port$ROOT_PATH"

    @Volatile
    var failure: Throwable? = null
        private set

    private val acceptThread = Thread({ acceptLoop() }, "DeviceWebDavServer-accept").apply {
        isDaemon = true
    }

    init {
        storedEntries[ROOT_PATH] = StoredEntry(collection = true, content = EMPTY_BYTES, etag = null)
        acceptThread.start()
    }

    val entries: List<Entry>
        get() = synchronized(stateLock) {
            storedEntries.map { (path, entry) ->
                Entry(path, entry.collection, entry.content.copyOf(), entry.etag)
            }.sortedBy { it.path }
        }

    val requests: List<RequestRecord>
        get() = synchronized(stateLock) { requestRecords.toList() }

    /** Number of completed requests using [method], including failed HTTP responses. */
    fun requestCount(method: String): Int {
        val normalized = normalizeMethod(method)
        return synchronized(stateLock) { requestRecords.count { it.method == normalized } }
    }

    /** Number of stored files. Collections, including [ROOT_PATH], are excluded. */
    fun objectCount(): Int = synchronized(stateLock) {
        storedEntries.values.count { !it.collection }
    }

    /** Gates the next request with [method] after its bounded body has been read. */
    fun gateNext(method: String): RequestGate {
        val gate = RequestGate()
        val normalized = normalizeMethod(method)
        synchronized(gateLock) {
            nextGates.getOrPut(normalized) { ArrayDeque() }.add(gate)
            outstandingGates.add(gate)
        }
        return gate
    }

    /** Returns [status] once for the next request with [method], without mutating the object. */
    fun failNext(method: String, status: Int) {
        require(status in 400..599) { "Forced failure status must be 400..599" }
        val normalized = normalizeMethod(method)
        synchronized(failureLock) {
            nextFailures.getOrPut(normalized) { ArrayDeque() }.add(status)
        }
    }

    /** Seeds a file using a decoded path and copies the supplied bytes. */
    fun putFile(path: String, bytes: ByteArray, etag: String? = null): Entry =
        synchronized(stateLock) {
            require(bytes.size <= MAX_BODY_BYTES) { "Fixture file exceeds $MAX_BODY_BYTES bytes" }
            val wirePath = seedPath(path)
            require(!wirePath.endsWith('/')) { "A file path must not end with '/'" }
            ensureParentsLocked(wirePath)
            require(storedEntries[wirePath]?.collection != true) { "Path is a collection" }
            val strongEtag = etag?.also(::requireStrongEtag) ?: nextEtagLocked()
            storedEntries[wirePath] = StoredEntry(false, bytes.copyOf(), strongEtag)
            Entry(wirePath, false, bytes.copyOf(), strongEtag)
        }

    /** Seeds a collection using a decoded path. Missing parents are created. */
    fun putCollection(path: String): Entry = synchronized(stateLock) {
        val wirePath = collectionSeedPath(path)
        if (wirePath != ROOT_PATH) ensureParentsLocked(wirePath)
        storedEntries.getOrPut(wirePath) {
            StoredEntry(collection = true, content = EMPTY_BYTES, etag = null)
        }.also {
            require(it.collection) { "Path is a file" }
        }
        Entry(wirePath, true, EMPTY_BYTES, null)
    }

    fun clearObjects() {
        synchronized(stateLock) {
            storedEntries.clear()
            storedEntries[ROOT_PATH] = StoredEntry(collection = true, content = EMPTY_BYTES, etag = null)
        }
    }

    fun clearRequests() {
        synchronized(stateLock) { requestRecords.clear() }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { serverSocket.close() }
        synchronized(gateLock) {
            outstandingGates.forEach { it.release() }
            outstandingGates.clear()
            nextGates.clear()
        }
        activeSockets.toList().forEach { socket -> runCatching { socket.close() } }
        workers.toList().forEach { it.interrupt() }
        if (Thread.currentThread() !== acceptThread) {
            runCatching { acceptThread.join(1_000L) }
        }
        for (worker in workers.toList()) {
            if (worker !== Thread.currentThread()) runCatching { worker.join(1_000L) }
        }
        activeSockets.clear()
        workers.clear()
    }

    private fun acceptLoop() {
        while (!closed.get()) {
            try {
                val socket = serverSocket.accept()
                if (closed.get()) {
                    runCatching { socket.close() }
                    return
                }
                activeSockets.add(socket)
                val worker = Thread(
                    { handleSocket(socket) },
                    "DeviceWebDavServer-${socket.remoteSocketAddress}",
                ).apply { isDaemon = true }
                workers.add(worker)
                worker.start()
            } catch (failure: SocketException) {
                if (!closed.get()) this.failure = failure
                return
            } catch (failure: IOException) {
                if (!closed.get()) this.failure = failure
                return
            }
        }
    }

    private fun handleSocket(socket: Socket) {
        try {
            socket.soTimeout = SOCKET_TIMEOUT_MILLIS
            val request = readRequest(socket.getInputStream())
            takeGate(request.method)?.blockRequest()
            writeResponse(socket, execute(request))
        } catch (failure: ProtocolFailure) {
            runCatching { writeResponse(socket, protocolResponse(failure)) }
        } catch (_: EOFException) {
            // The client closed before sending a complete request.
        } catch (_: SocketException) {
            // Cancellation and fixture shutdown are expected connection outcomes.
        } catch (failure: IOException) {
            if (!closed.get()) {
                runCatching { writeResponse(socket, HttpResponse(500)) }
            }
        } catch (failure: Throwable) {
            if (!closed.get()) {
                this.failure = failure
                runCatching { writeResponse(socket, HttpResponse(500)) }
            }
        } finally {
            activeSockets.remove(socket)
            workers.remove(Thread.currentThread())
            runCatching { socket.close() }
        }
    }

    private fun readRequest(stream: InputStream): ParsedRequest {
        val input = BufferedInputStream(stream)
        val headerBudget = intArrayOf(MAX_REQUEST_HEADER_BYTES)
        val requestLine = readHeaderLine(input, headerBudget)
            ?: throw EOFException("Connection closed before request line")
        val parts = requestLine.split(' ', limit = 3)
        if (parts.size != 3 || parts[0].isBlank() || parts[1].isBlank()) {
            throw ProtocolFailure(400, "Malformed request line")
        }
        if (parts[2] != "HTTP/1.1" && parts[2] != "HTTP/1.0") {
            throw ProtocolFailure(505, "Unsupported HTTP version")
        }

        val headers = LinkedHashMap<String, String>()
        while (true) {
            val line = readHeaderLine(input, headerBudget)
                ?: throw ProtocolFailure(400, "Incomplete headers")
            if (line.isEmpty()) break
            val separator = line.indexOf(':')
            if (separator <= 0 || !HEADER_NAME.matches(line.substring(0, separator))) {
                throw ProtocolFailure(400, "Malformed request header")
            }
            if (headers.size >= MAX_HEADER_COUNT) {
                throw ProtocolFailure(431, "Too many request headers")
            }
            val name = line.substring(0, separator).lowercase(Locale.ROOT)
            val value = line.substring(separator + 1).trim()
            if (headers.put(name, value) != null) {
                throw ProtocolFailure(400, "Duplicate request header: $name")
            }
        }
        if (headers["transfer-encoding"] != null) {
            throw ProtocolFailure(501, "Transfer-Encoding is not supported")
        }
        val contentLength = headers["content-length"]?.let { raw ->
            raw.toLongOrNull()?.takeIf { it >= 0 }
                ?: throw ProtocolFailure(400, "Invalid Content-Length")
        } ?: 0L
        if (contentLength > MAX_BODY_BYTES) {
            throw ProtocolFailure(413, "Request body exceeds $MAX_BODY_BYTES bytes")
        }
        val body = ByteArray(contentLength.toInt())
        var offset = 0
        while (offset < body.size) {
            val count = input.read(body, offset, body.size - offset)
            if (count < 0) throw ProtocolFailure(400, "Incomplete request body")
            offset += count
        }
        val method = normalizeMethod(parts[0])
        val target = parts[1]
        return ParsedRequest(
            method = method,
            target = target,
            path = requestPath(target),
            headers = headers,
            body = body,
        )
    }

    private fun readHeaderLine(input: InputStream, remaining: IntArray): String? {
        val line = ByteArrayOutputStream()
        var sawAny = false
        while (true) {
            val value = input.read()
            if (value < 0) {
                if (!sawAny) return null
                throw ProtocolFailure(400, "Incomplete header line")
            }
            sawAny = true
            remaining[0]--
            if (remaining[0] < 0) {
                throw ProtocolFailure(431, "Request headers exceed $MAX_REQUEST_HEADER_BYTES bytes")
            }
            if (value == '\n'.code) break
            if (value != '\r'.code) line.write(value)
        }
        return line.toString(StandardCharsets.ISO_8859_1.name())
    }

    private fun takeGate(method: String): RequestGate? = synchronized(gateLock) {
        val gates = nextGates[method] ?: return@synchronized null
        val gate = gates.pollFirst()
        if (gates.isEmpty()) nextGates.remove(method)
        gate
    }

    private fun takeFailure(method: String): Int? = synchronized(failureLock) {
        val failures = nextFailures[method] ?: return@synchronized null
        val status = failures.pollFirst()
        if (failures.isEmpty()) nextFailures.remove(method)
        status
    }

    private fun execute(request: ParsedRequest): HttpResponse = synchronized(stateLock) {
        val forcedStatus = takeFailure(request.method)
        val response = try {
            when {
                !authorized(request.headers) -> HttpResponse(
                    401,
                    listOf(
                        "WWW-Authenticate" to "Basic realm=\"TYN device fixture\", charset=\"UTF-8\"",
                        "Content-Type" to "text/plain; charset=utf-8",
                    ),
                    "Authentication required\n".toByteArray(StandardCharsets.UTF_8),
                )
                forcedStatus != null -> HttpResponse(
                    forcedStatus,
                    listOf("Content-Type" to "text/plain; charset=utf-8"),
                    if (forcedStatus == 204) EMPTY_BYTES else "Forced failure\n".toByteArray(StandardCharsets.UTF_8),
                )
                else -> dispatch(request)
            }
        } catch (failure: ProtocolFailure) {
            protocolResponse(failure)
        } catch (failure: Exception) {
            this.failure = failure
            HttpResponse(
                500,
                listOf("Content-Type" to "text/plain; charset=utf-8"),
                "Fixture failure\n".toByteArray(StandardCharsets.UTF_8),
            )
        }
        requestRecords.add(
            RequestRecord(
                method = request.method,
                target = request.target,
                path = request.path,
                headers = TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER).apply {
                    putAll(request.headers)
                },
                bodySize = request.body.size,
                status = response.status,
            ),
        )
        response
    }

    private fun authorized(headers: Map<String, String>): Boolean {
        val expected = expectedAuthorization ?: return true
        val actual = headers["authorization"] ?: return false
        return MessageDigest.isEqual(
            actual.toByteArray(StandardCharsets.ISO_8859_1),
            expected.toByteArray(StandardCharsets.ISO_8859_1),
        )
    }

    private fun dispatch(request: ParsedRequest): HttpResponse = when (request.method) {
        "MKCOL" -> handleMkcol(request)
        "PROPFIND" -> handlePropfind(request)
        "PUT" -> handlePut(request)
        "GET" -> handleGet(request)
        "DELETE" -> handleDelete(request)
        "MOVE" -> handleMove(request)
        "OPTIONS" -> HttpResponse(
            200,
            listOf(
                "Allow" to "OPTIONS, PROPFIND, MKCOL, GET, PUT, DELETE, MOVE",
                "DAV" to "1",
            ),
        )
        else -> methodNotAllowed()
    }

    private fun handleMkcol(request: ParsedRequest): HttpResponse {
        if (!request.path.endsWith('/')) throw ProtocolFailure(400, "Collection path must end with '/'")
        val existing = storedEntries[request.path]
        if (existing != null) {
            if (!existing.collection) throw ProtocolFailure(405, "Path is a file")
            return HttpResponse(405, listOf("Allow" to "MKCOL, PROPFIND"))
        }
        requireCollection(parentPath(request.path))
        storedEntries[request.path] = StoredEntry(collection = true, content = EMPTY_BYTES, etag = null)
        return HttpResponse(201)
    }

    private fun handlePropfind(request: ParsedRequest): HttpResponse {
        val depth = request.headers["depth"]?.trim() ?: "1"
        if (depth != "0" && depth != "1") throw ProtocolFailure(400, "Only Depth 0 and 1 are supported")
        val self = storedEntries[request.path] ?: throw ProtocolFailure(404, "Collection not found")
        if (!self.collection) throw ProtocolFailure(409, "PROPFIND target is not a collection")
        val responses = ArrayList<String>()
        responses += propertyResponse(request.path, self)
        if (depth == "1") {
            directChildren(request.path).forEach { path ->
                storedEntries[path]?.let { responses += propertyResponse(path, it) }
            }
        }
        val body = buildString {
            append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
            append("<d:multistatus xmlns:d=\"DAV:\">")
            responses.forEach { append(it) }
            append("</d:multistatus>")
        }.toByteArray(StandardCharsets.UTF_8)
        if (body.size > MAX_BODY_BYTES) throw ProtocolFailure(507, "Listing exceeds fixture body limit")
        return HttpResponse(
            207,
            listOf("Content-Type" to "application/xml; charset=utf-8"),
            body,
        )
    }

    private fun handlePut(request: ParsedRequest): HttpResponse {
        if (request.path.endsWith('/')) throw ProtocolFailure(409, "PUT target is a collection")
        requireCollection(parentPath(request.path))
        val existing = storedEntries[request.path]
        if (existing?.collection == true) throw ProtocolFailure(409, "PUT target is a collection")
        val ifNoneMatch = request.headers["if-none-match"]
        if (ifNoneMatch != null && ifNoneMatches(ifNoneMatch, existing?.etag)) {
            throw ProtocolFailure(412, "If-None-Match rejected PUT")
        }
        val ifMatch = request.headers["if-match"]
        if (ifMatch != null && !etagMatches(ifMatch, existing?.etag)) {
            throw ProtocolFailure(412, "If-Match rejected PUT")
        }
        val etag = nextEtagLocked()
        storedEntries[request.path] = StoredEntry(false, request.body.copyOf(), etag)
        return HttpResponse(
            if (existing == null) 201 else 204,
            listOf("ETag" to etag),
        )
    }

    private fun handleGet(request: ParsedRequest): HttpResponse {
        val entry = storedEntries[request.path] ?: throw ProtocolFailure(404, "File not found")
        if (entry.collection) throw ProtocolFailure(405, "GET target is a collection")
        val headers = ArrayList<Pair<String, String>>()
        headers += "Content-Type" to "application/zip"
        entry.etag?.let { headers += "ETag" to it }
        return HttpResponse(200, headers, entry.content.copyOf())
    }

    private fun handleDelete(request: ParsedRequest): HttpResponse {
        val entry = storedEntries[request.path] ?: throw ProtocolFailure(404, "Path not found")
        val ifMatch = request.headers["if-match"]
        if (ifMatch != null && !etagMatches(ifMatch, entry.etag)) {
            throw ProtocolFailure(412, "If-Match rejected DELETE")
        }
        if (entry.collection) {
            if (request.path == ROOT_PATH) throw ProtocolFailure(403, "Fixture root cannot be deleted")
            if (directChildren(request.path).isNotEmpty()) throw ProtocolFailure(409, "Collection is not empty")
        }
        storedEntries.remove(request.path)
        return HttpResponse(204)
    }

    private fun handleMove(request: ParsedRequest): HttpResponse {
        val source = storedEntries[request.path] ?: throw ProtocolFailure(404, "Move source not found")
        if (source.collection) throw ProtocolFailure(409, "Collection moves are not supported")
        val ifMatch = request.headers["if-match"]
        if (ifMatch != null && !etagMatches(ifMatch, source.etag)) {
            throw ProtocolFailure(412, "If-Match rejected MOVE")
        }
        val destinationHeader = request.headers["destination"]
            ?: throw ProtocolFailure(400, "Destination header is required")
        val destination = destinationPath(destinationHeader)
        if (destination.endsWith('/')) throw ProtocolFailure(409, "Move destination is a collection")
        requireCollection(parentPath(destination))
        val overwrite = request.headers["overwrite"]?.uppercase(Locale.ROOT) ?: "T"
        if (overwrite != "F" && overwrite != "T") throw ProtocolFailure(400, "Invalid Overwrite header")
        val existingDestination = storedEntries[destination]
        if (existingDestination?.collection == true) throw ProtocolFailure(409, "Move destination is a collection")
        if (existingDestination != null && overwrite == "F") {
            throw ProtocolFailure(412, "Destination already exists")
        }
        if (destination != request.path) {
            storedEntries.remove(request.path)
            storedEntries[destination] = source
        }
        val headers = source.etag?.let { listOf("ETag" to it) }.orEmpty()
        return HttpResponse(if (existingDestination == null) 201 else 204, headers)
    }

    private fun propertyResponse(path: String, entry: StoredEntry): String = buildString {
        append("<d:response><d:href>")
        append(xmlEscape(path))
        append("</d:href><d:propstat><d:prop><d:resourcetype>")
        if (entry.collection) append("<d:collection/>")
        append("</d:resourcetype><d:getcontentlength>")
        append(entry.content.size)
        append("</d:getcontentlength>")
        entry.etag?.let {
            append("<d:getetag>")
            append(xmlEscape(it))
            append("</d:getetag>")
        }
        append("</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>")
    }

    private fun directChildren(path: String): List<String> = storedEntries.keys
        .asSequence()
        .filter { it != path && parentPath(it) == path }
        .sorted()
        .toList()

    private fun ensureParentsLocked(path: String) {
        val missing = ArrayList<String>()
        var parent = parentPath(path)
        while (parent != ROOT_PATH) {
            val entry = storedEntries[parent]
            if (entry != null) {
                require(entry.collection) { "Parent path is a file: $parent" }
                break
            }
            missing += parent
            parent = parentPath(parent)
        }
        for (directory in missing.asReversed()) {
            storedEntries[directory] = StoredEntry(collection = true, content = EMPTY_BYTES, etag = null)
        }
    }

    private fun requireCollection(path: String) {
        val entry = storedEntries[path] ?: throw ProtocolFailure(409, "Parent collection does not exist")
        if (!entry.collection) throw ProtocolFailure(409, "Parent path is not a collection")
    }

    private fun nextEtagLocked(): String {
        etagSequence++
        return "\"device-$etagSequence\""
    }

    private fun writeResponse(socket: Socket, response: HttpResponse) {
        val bodySize = if (response.status == 204 || response.status == 304) 0 else response.body.size
        if (bodySize > MAX_BODY_BYTES) throw ProtocolFailure(507, "Response body exceeds fixture limit")
        val output = BufferedOutputStream(socket.getOutputStream())
        val writer = StringBuilder()
        writer.append("HTTP/1.1 ").append(response.status).append(' ')
            .append(reasonPhrase(response.status)).append("\r\n")
        response.headers.forEach { (name, value) ->
            require(name.none { it == '\r' || it == '\n' })
            require(value.none { it == '\r' || it == '\n' })
            writer.append(name).append(": ").append(value).append("\r\n")
        }
        writer.append("Content-Length: ").append(bodySize).append("\r\n")
        writer.append("Connection: close\r\n")
        writer.append("\r\n")
        output.write(writer.toString().toByteArray(StandardCharsets.ISO_8859_1))
        if (bodySize > 0) output.write(response.body, 0, bodySize)
        output.flush()
    }

    private fun protocolResponse(failure: ProtocolFailure): HttpResponse = HttpResponse(
        failure.status,
        listOf("Content-Type" to "text/plain; charset=utf-8"),
        (failure.message ?: "Request failed").toByteArray(StandardCharsets.UTF_8),
    )

    private fun methodNotAllowed(): HttpResponse = HttpResponse(
        405,
        listOf("Allow" to "OPTIONS, PROPFIND, MKCOL, GET, PUT, DELETE, MOVE"),
    )

    private fun requestPath(target: String): String {
        val uri = parseUri(target, "request target")
        if (uri.isAbsolute) validateLoopbackAuthority(uri)
        return wirePath(uri)
    }

    private fun destinationPath(value: String): String {
        val uri = parseUri(value, "Destination")
        validateLoopbackAuthority(uri)
        return wirePath(uri)
    }

    private fun parseUri(value: String, label: String): URI = try {
        URI(value)
    } catch (_: URISyntaxException) {
        throw ProtocolFailure(400, "Malformed $label")
    }

    private fun validateLoopbackAuthority(uri: URI) {
        val host = uri.host
        if (!uri.scheme.equals("http", ignoreCase = true) ||
            (host != "127.0.0.1" && host != "localhost") ||
            (uri.port != -1 && uri.port != port)
        ) {
            throw ProtocolFailure(400, "Destination must remain on the fixture server")
        }
    }

    private fun wirePath(uri: URI): String {
        if (uri.rawQuery != null || uri.rawFragment != null) {
            throw ProtocolFailure(400, "Query strings and fragments are not supported")
        }
        val decoded = uri.path ?: throw ProtocolFailure(400, "Path is required")
        if (decoded != ROOT_PATH.trimEnd('/') && !decoded.startsWith(ROOT_PATH)) {
            throw ProtocolFailure(403, "Path is outside ${ROOT_PATH.trimEnd('/')}")
        }
        if (decoded.split('/').any { it == "." || it == ".." }) {
            throw ProtocolFailure(400, "Path traversal is not supported")
        }
        return uri.rawPath ?: throw ProtocolFailure(400, "Raw path is required")
    }

    private fun seedPath(path: String): String {
        val decoded = if (path.startsWith('/')) path else "/$path"
        val uri = URI(null, null, null, -1, decoded, null, null)
        return wirePath(uri)
    }

    private fun collectionSeedPath(path: String): String {
        val normalized = seedPath(path)
        return if (normalized.endsWith('/')) normalized else "$normalized/"
    }

    private fun parentPath(path: String): String {
        val trimmed = path.trimEnd('/')
        val separator = trimmed.lastIndexOf('/')
        return if (separator <= 0) "/" else trimmed.substring(0, separator + 1)
    }

    private fun ifNoneMatches(header: String, currentEtag: String?): Boolean {
        if (header.trim() == "*") return currentEtag != null
        if (currentEtag == null) return false
        return parseEtags(header).any { it == currentEtag }
    }

    private fun etagMatches(header: String, currentEtag: String?): Boolean {
        if (header.trim() == "*") return currentEtag != null
        if (currentEtag == null) return false
        return parseEtags(header).any { it == currentEtag }
    }

    private fun parseEtags(value: String): List<String> = value.split(',').map { it.trim() }

    private fun requireStrongEtag(etag: String) {
        require(etag.length >= 2 && etag.startsWith('"') && etag.endsWith('"')) {
            "ETag must be strong"
        }
        require(etag.none { it.code < 32 || it.code == 127 }) { "ETag contains control characters" }
    }

    private fun xmlEscape(value: String): String = buildString(value.length) {
        value.forEach { character ->
            when (character) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(character)
            }
        }
    }

    private fun reasonPhrase(status: Int): String = when (status) {
        200 -> "OK"
        201 -> "Created"
        204 -> "No Content"
        207 -> "Multi-Status"
        304 -> "Not Modified"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        409 -> "Conflict"
        412 -> "Precondition Failed"
        413 -> "Content Too Large"
        431 -> "Request Header Fields Too Large"
        500 -> "Internal Server Error"
        501 -> "Not Implemented"
        503 -> "Service Unavailable"
        505 -> "HTTP Version Not Supported"
        507 -> "Insufficient Storage"
        else -> "Status"
    }

    companion object {
        const val ROOT_PATH = "/acceptance/"
        const val DEFAULT_USERNAME = "acceptance"
        const val DEFAULT_PASSWORD = "synthetic-only"
        const val MAX_BODY_BYTES = 8 * 1024 * 1024
        const val MAX_REQUEST_HEADER_BYTES = 32 * 1024
        const val SOCKET_TIMEOUT_MILLIS = 10_000

        private const val MAX_HEADER_COUNT = 128
        private val EMPTY_BYTES = ByteArray(0)
        private val HEADER_NAME = Regex("[!#\$%&'*+.^_`|~0-9A-Za-z-]+")

        private fun normalizeMethod(method: String): String =
            method.trim().uppercase(Locale.ROOT).also {
                require(it.isNotEmpty() && HEADER_NAME.matches(it)) { "Invalid HTTP method" }
            }

        private fun basicAuthorization(username: String, password: String): String {
            val bytes = "$username:$password".toByteArray(StandardCharsets.UTF_8)
            return "Basic " + Base64.getEncoder().encodeToString(bytes)
        }
    }
}
