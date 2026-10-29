package com.tyranor.next.core.game.save.sync

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.xml.sax.Attributes
import org.xml.sax.EntityResolver
import org.w3c.dom.Element
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.SAXParserFactory
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** DAV requests are confined to one configured origin and this application's version namespace. */
class WebDavClient private constructor(
    config: WebDavConfig,
    private val temporaryDirectory: File?,
    private val enabledCheck: (() -> Boolean)?,
    private val downloadFileSystem: WebDavDownloadFileSystem,
) : AutoCloseable {
    constructor(
        config: WebDavConfig,
        temporaryDirectory: File? = null,
        enabledCheck: (() -> Boolean)? = null,
    ) : this(config, temporaryDirectory, enabledCheck, WebDavDownloadFileSystem())

    internal constructor(
        config: WebDavConfig,
        downloadFileSystem: WebDavDownloadFileSystem,
        temporaryDirectory: File? = null,
        enabledCheck: (() -> Boolean)? = null,
    ) : this(config, temporaryDirectory, enabledCheck, downloadFileSystem)

    data class UploadResult(val uploaded: Boolean, val size: Long)
    private val config = WebDavSettingsStore.validated(config)
    private val base: HttpUrl = this.config.serverUrl.takeIf { it.isNotBlank() }?.toHttpUrl()?.let(::escapePathBrackets)
        ?: throw SaveSyncException(SaveSyncError.NOT_CONFIGURED)
    private val home = base.newBuilder().addPathSegment("TYN-saves").addPathSegment("").build()
    private val legacyHome = home.newBuilder().addPathSegment("v1").addPathSegment("").build()
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).writeTimeout(60, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.MINUTES).retryOnConnectionFailure(false).build()
    private var homeReady = false
    private val knownFolders = HashSet<String>()
    private var requestAdmission: suspend (() -> Unit) -> Unit = { dispatch -> dispatch() }

    internal fun setRequestAdmission(admit: suspend (() -> Unit) -> Unit) {
        requestAdmission = admit
    }

    suspend fun list(game: RemoteSaveGame): List<RemoteSaveVersion> {
        val current = listFolder(folder(game))
        if (current != null) knownFolders.add(game.key)
        val legacy = listFolder(legacyFolder(game)).orEmpty().map { it.copy(legacy = true) }
        return (current.orEmpty() + legacy).sortedWith(
            compareByDescending<RemoteSaveVersion> { it.createdAt }.thenByDescending { it.contentHash },
        )
    }

    private suspend fun listFolder(folder: HttpUrl): List<RemoteSaveVersion>? {
        return exchange(request(folder).method("PROPFIND", PROPFIND.toRequestBody(XML)).header("Depth", "1").build()) { response, check ->
            if (response.code == 404) null else {
                requireStatus(response, 207)
                parseListing(folder, readSmall(response, check))
            }
        }
    }

    suspend fun upload(game: RemoteSaveGame, version: SaveVersion, source: File): UploadResult {
        val parsed = SaveVersionNames.parse(version.id) ?: throw SaveSyncException(SaveSyncError.INVALID_CONFIG)
        if (parsed.contentHash != version.contentHash || parsed.createdAt != version.createdAt || source.length() > SaveContentHash.MAX_BYTES) {
            throw SaveSyncException(SaveSyncError.INVALID_CONFIG)
        }
        ensureGame(game)
        val code = exchange(request(versionUrl(game, version.id)).header("If-None-Match", "*")
            .put(source.asRequestBody(ZIP)).build()) { response, _ ->
            if (response.code != 412) requireStatus(response, 200, 201, 204)
            response.code
        }
        if (code != 412) return UploadResult(true, source.length())
        val existing = list(game).firstOrNull { it.name == version.id && !it.legacy }
            ?: throw SaveSyncException(SaveSyncError.INVALID_RESPONSE)
        return if (verifyArchive(game, existing)) UploadResult(false, existing.size) else {
            repairArchive(game, version, source, existing)
            UploadResult(true, source.length())
        }
    }

    /** Repairs a proven bad immutable object only while its strong ETag is unchanged. */
    private suspend fun repairArchive(game: RemoteSaveGame, version: SaveVersion, source: File, existing: RemoteSaveVersion) {
        val etag = strongEtag(existing.etag) ?: throw SaveSyncException(SaveSyncError.CORRUPT_ARCHIVE)
        exchange(request(versionUrl(game, version.id)).header("If-Match", etag)
            .put(source.asRequestBody(ZIP)).build()) { response, _ -> requireStatus(response, 200, 201, 204) }
    }

    private suspend fun verifyArchive(game: RemoteSaveGame, remote: RemoteSaveVersion): Boolean {
        val verification = SaveSyncTemporaryFiles.create("dav_collision_", ".zip", temporaryDirectory)
        try {
            download(game, remote, verification)
            val job = kotlinx.coroutines.currentCoroutineContext()
            return SaveContentHash.archive(verification) { job.ensureActive() } == remote.contentHash
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: SaveSyncException) {
            if (failure.code == SaveSyncError.CORRUPT_ARCHIVE || failure.status == 404) return false
            throw failure
        } finally {
            SaveSyncTemporaryFiles.release(verification)
        }
    }

    internal suspend fun verifyVersion(game: RemoteSaveGame, remote: RemoteSaveVersion): Boolean = verifyArchive(game, remote)

    /** Only a proven corrupt target owned by this pending publication may be retired. */
    internal suspend fun discardCorruptPublication(
        game: RemoteSaveGame,
        remote: RemoteSaveVersion,
        beforeDelete: suspend () -> Unit = {},
    ): Boolean {
        if (remote.legacy) throw SaveSyncException(SaveSyncError.INVALID_CONFIG)
        if (verifyArchive(game, remote)) return false
        if (strongEtag(remote.etag) == null) throw SaveSyncException(SaveSyncError.CORRUPT_ARCHIVE)
        beforeDelete()
        delete(game, remote)
        return true
    }

    /** Promote identical bytes with a server-side rename when DAV MOVE is available. */
    suspend fun refresh(game: RemoteSaveGame, previous: RemoteSaveVersion, publication: SaveVersion, source: File): Long {
        val parsed = SaveVersionNames.parse(publication.id)
            ?: throw SaveSyncException(SaveSyncError.INVALID_CONFIG)
        if (!parsed.restored || parsed.contentHash != previous.contentHash ||
            parsed.contentHash != publication.contentHash || parsed.createdAt != publication.createdAt
        ) throw SaveSyncException(SaveSyncError.INVALID_CONFIG)
        ensureGame(game)
        if (!previous.legacy && previous.name == publication.id) {
            if (verifyArchive(game, previous)) return previous.size
            repairArchive(game, publication, source, previous)
            return source.length()
        }
        if (!verifyArchive(game, previous)) return upload(game, publication, source).size
        val destination = versionUrl(game, publication.id)
        val move = request(versionUrl(game, previous.name, previous.legacy))
            .method("MOVE", null).header("Destination", destination.toString()).header("Overwrite", "F").apply {
                strongEtag(previous.etag)?.let { header("If-Match", it) }
            }.build()
        val code = exchange(move) { response, _ ->
            if (response.code !in setOf(404, 405, 412, 501)) requireStatus(response, 201, 204)
            response.code
        }
        if (code == 201 || code == 204) return previous.size
        // Some DAV providers support PUT/DELETE but not MOVE. The caller confirms this new
        // immutable file before removing the old one, including after an interrupted MOVE.
        return upload(game, publication, source).size
    }

    suspend fun download(game: RemoteSaveGame, remote: RemoteSaveVersion, destination: File) {
        if (remote.size < 0 || remote.size > SaveContentHash.MAX_BYTES) throw SaveSyncException(SaveSyncError.INVALID_RESPONSE)
        val target = destination.absoluteFile
        val directory = target.parentFile ?: throw SaveSyncException(SaveSyncError.LOCAL_IO)
        if (!directory.isDirectory) throw SaveSyncException(SaveSyncError.LOCAL_IO)
        val temp = SaveSyncTemporaryFiles.create("dav_download_", ".part", directory)
        try {
            requireSaveSyncTransientSpace(remote.size, directory, downloadFileSystem.usableSpace)
            val output = runSaveSyncStorageOperation(directory, remote.size, downloadFileSystem.usableSpace) {
                downloadFileSystem.openOutput(temp)
            }
            var primaryFailure: Throwable? = null
            var writtenBytes = 0L
            try {
                exchange(request(versionUrl(game, remote.name, remote.legacy)).get().build()) { response, check ->
                    requireStatus(response, 200)
                    val body = response.body
                    val declaredLength = body.contentLength()
                    if (declaredLength > SaveContentHash.MAX_BYTES) throw SaveSyncException(SaveSyncError.CORRUPT_ARCHIVE)
                    if (declaredLength >= 0L && declaredLength != remote.size) {
                        throw SaveSyncException(SaveSyncError.INVALID_RESPONSE)
                    }
                    var total = 0L
                    val buffer = ByteArray(64 * 1024)
                    body.byteStream().use { input ->
                        while (true) {
                            check()
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            if (total > SaveContentHash.MAX_BYTES) throw SaveSyncException(SaveSyncError.CORRUPT_ARCHIVE)
                            // Never write past the size the listing reserved space for.
                            if (total > remote.size) throw SaveSyncException(SaveSyncError.INVALID_RESPONSE)
                            writeDownloadOutput(output, buffer, count, directory, remote.size - writtenBytes)
                            writtenBytes += count
                        }
                    }
                    if (total != remote.size) throw SaveSyncException(SaveSyncError.INVALID_RESPONSE)
                }
                flushDownloadOutput(output, directory, remote.size - writtenBytes)
            } catch (failure: Throwable) {
                primaryFailure = failure
                throw failure
            } finally {
                closeDownloadOutput(output, directory, remote.size - writtenBytes, primaryFailure)
            }
            currentCoroutineContext().ensureActive()
            runSaveSyncStorageOperation(directory, 0, downloadFileSystem.usableSpace) {
                Files.move(
                    temp.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } finally {
            SaveSyncTemporaryFiles.release(temp)
        }
    }

    private fun writeDownloadOutput(
        output: OutputStream,
        bytes: ByteArray,
        count: Int,
        directory: File,
        remainingBytes: Long,
    ) {
        runSaveSyncStorageOperation(directory, remainingBytes, downloadFileSystem.usableSpace) {
            output.write(bytes, 0, count)
        }
    }

    private fun flushDownloadOutput(output: OutputStream, directory: File, remainingBytes: Long) {
        runSaveSyncStorageOperation(directory, remainingBytes, downloadFileSystem.usableSpace) {
            output.flush()
        }
    }

    private fun closeDownloadOutput(
        output: OutputStream,
        directory: File,
        remainingBytes: Long,
        primaryFailure: Throwable?,
    ) {
        try {
            runSaveSyncStorageOperation(directory, remainingBytes, downloadFileSystem.usableSpace) {
                output.close()
            }
        } catch (failure: SaveSyncException) {
            handleDownloadCloseFailure(primaryFailure, failure)
        }
    }

    private fun handleDownloadCloseFailure(
        primaryFailure: Throwable?,
        localFailure: SaveSyncException,
    ) {
        if (primaryFailure == null) throw localFailure
        primaryFailure.addSuppressed(localFailure)
    }

    suspend fun delete(game: RemoteSaveGame, remote: RemoteSaveVersion) {
        val request = request(versionUrl(game, remote.name, remote.legacy)).delete().apply {
            strongEtag(remote.etag)?.let { header("If-Match", it) }
        }.build()
        exchange(request) { response, _ -> requireStatus(response, 200, 204, 404) }
    }

    suspend fun testConnection() {
        ensureHome()
        val probe = home.newBuilder().addPathSegment("probe-${UUID.randomUUID()}.tmp").build()
        val bytes = "TYN WebDAV connection".toByteArray(Charsets.UTF_8)
        var cleanupAllowed = true
        var primaryFailure: Exception? = null
        try {
            exchange(request(probe).header("If-None-Match", "*").put(bytes.toRequestBody(null)).build()) { response, _ ->
                // A pre-existing random token is never ours to delete. On a lost response,
                // however, the PUT may have committed and must still get a cleanup attempt.
                if (response.code == 412) cleanupAllowed = false
                requireStatus(response, 200, 201, 204)
            }
            exchange(request(probe).get().build()) { response, check ->
                requireStatus(response, 200)
                if (!readSmall(response, check).contentEquals(bytes)) throw SaveSyncException(SaveSyncError.INVALID_RESPONSE)
            }
        } catch (failure: Exception) {
            primaryFailure = failure
            throw failure
        } finally {
            if (cleanupAllowed) {
                try {
                    withContext(NonCancellable) {
                        exchange(request(probe).delete().build(), cleanup = true) { response, _ -> requireStatus(response, 200, 204, 404) }
                    }
                } catch (cleanupFailure: Exception) {
                    val original = primaryFailure
                    if (original == null) throw cleanupFailure
                    original.addSuppressed(cleanupFailure)
                }
            }
        }
    }

    private suspend fun ensureHome() {
        if (homeReady) return
        ensureCollection(home)
        homeReady = true
    }

    private suspend fun ensureGame(game: RemoteSaveGame) {
        if (game.key in knownFolders) return
        ensureHome()
        ensureCollection(engineFolder(game))
        ensureCollection(folder(game))
        knownFolders.add(game.key)
    }

    private suspend fun ensureCollection(url: HttpUrl) {
        exchange(request(url).method("MKCOL", null).build()) { response, _ -> requireStatus(response, 201, 405) }
        exchange(request(url).method("PROPFIND", PROPFIND.toRequestBody(XML)).header("Depth", "0").build()) { response, check ->
            requireStatus(response, 207)
            val doc = parseXml(readSmall(response, check))
            val responses = children(doc, "response")
            val self = responses.singleOrNull { response ->
                children(response, "href").singleOrNull()?.textContent?.trim()?.let { href ->
                    url.resolve(href)?.let { it.scheme == url.scheme && it.host == url.host && it.port == url.port &&
                        sameCollection(it, url) }
                } == true
            } ?: throw SaveSyncException(SaveSyncError.INVALID_RESPONSE)
            if (!properties(self).any { prop -> children(prop, "resourcetype").any { children(it, "collection").isNotEmpty() } }) {
                throw SaveSyncException(SaveSyncError.INVALID_RESPONSE)
            }
        }
    }

    private fun request(url: HttpUrl): Request.Builder {
        if (url.scheme != base.scheme || url.host != base.host || url.port != base.port ||
            !url.encodedPath.startsWith(base.encodedPath)
        ) throw SaveSyncException(SaveSyncError.INVALID_CONFIG)
        return Request.Builder().url(url).apply {
            if (config.username.isNotEmpty() || config.password.isNotEmpty()) {
                header("Authorization", Credentials.basic(config.username, config.password, Charsets.UTF_8))
            }
        }.header("Cache-Control", "no-cache").header("User-Agent", "TYN-SaveSync/1")
    }

    private fun engineFolder(game: RemoteSaveGame): HttpUrl {
        if (!game.engine.matches(Regex("[A-Z0-9_]+"))) throw SaveSyncException(SaveSyncError.INVALID_CONFIG)
        return home.newBuilder().addPathSegment(game.engine).addPathSegment("").build()
    }

    private fun folder(game: RemoteSaveGame): HttpUrl = escapePathBrackets(engineFolder(game).newBuilder()
        .addPathSegment(game.folderName).addPathSegment("").build())

    private fun legacyFolder(game: RemoteSaveGame): HttpUrl = legacyHome.newBuilder()
        .addPathSegment(game.key).addPathSegment("").build()

    private fun versionUrl(game: RemoteSaveGame, name: String, legacy: Boolean = false): HttpUrl {
        if (SaveVersionNames.parse(name) == null) throw SaveSyncException(SaveSyncError.INVALID_RESPONSE)
        return (if (legacy) legacyFolder(game) else folder(game)).newBuilder().addPathSegment(name).build()
    }

    private suspend fun <T> exchange(
        request: Request,
        cleanup: Boolean = false,
        consume: (Response, () -> Unit) -> T,
    ): T = supervisorScope {
        currentCoroutineContext().ensureActive()
        var response: Deferred<T>? = null
        requestAdmission {
            check(response == null)
            // UNDISPATCHED enqueues while the admission locks are held; await is outside.
            response = async(start = CoroutineStart.UNDISPATCHED) {
                suspendCancellableCoroutine { continuation ->
                    if (!continuation.isActive) return@suspendCancellableCoroutine
                    if (!cleanup && enabledCheck?.invoke() == false) {
                        continuation.resumeWithException(SaveSyncException(SaveSyncError.DISABLED))
                        return@suspendCancellableCoroutine
                    }
                    val call = client.newCall(request)
                    if (cleanup) call.timeout().timeout(5, TimeUnit.SECONDS)
                    continuation.invokeOnCancellation { call.cancel() }
                    call.enqueue(object : Callback {
                        override fun onFailure(call: Call, e: IOException) {
                            if (continuation.isActive) continuation.resumeWithException(SaveSyncException(SaveSyncError.NETWORK, cause = e))
                        }
                        override fun onResponse(call: Call, response: Response) {
                            response.use {
                                try {
                                    val check = {
                                        if (!continuation.isActive) throw kotlinx.coroutines.CancellationException()
                                        if (!cleanup && enabledCheck?.invoke() == false) throw SaveSyncException(SaveSyncError.DISABLED)
                                    }
                                    check()
                                    val value = consume(response, check)
                                    if (continuation.isActive) continuation.resume(value)
                                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                    continuation.cancel(cancelled)
                                } catch (failure: Throwable) {
                                    if (continuation.isActive) {
                                        val terminal = when {
                                            failure is SaveSyncException -> failure
                                            failure is Exception -> SaveSyncException(SaveSyncError.NETWORK, cause = failure)
                                            else -> failure
                                        }
                                        continuation.resumeWithException(terminal)
                                    }
                                }
                            }
                        }
                    })
                }
            }
        }
        checkNotNull(response).await()
    }

    override fun close() {
        client.dispatcher.cancelAll()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    companion object {
        private fun strongEtag(value: String?): String? = value?.takeIf { isStrongEtag(it) }
        private val XML = "application/xml; charset=utf-8".toMediaType()
        private val ZIP = "application/zip".toMediaType()
        private const val PROPFIND = "<?xml version=\"1.0\" encoding=\"utf-8\"?><d:propfind xmlns:d=\"DAV:\"><d:prop><d:resourcetype/><d:getcontentlength/><d:getetag/></d:prop></d:propfind>"
        private const val MAX_XML_BYTES = 16 * 1024 * 1024
        internal const val MAX_XML_ELEMENTS = 32_768
        internal const val MAX_XML_RESPONSES = 4_096
        internal const val MAX_XML_DEPTH = 64
        private const val REMOTE_FUTURE_TOLERANCE_MS = 24L * 60 * 60 * 1000

        // OkHttp leaves path brackets literal; strict DAV servers require their URI escapes.
        private fun escapePathBrackets(url: HttpUrl): HttpUrl = url.newBuilder()
            .encodedPath(url.encodedPath.replace("[", "%5B").replace("]", "%5D")).build()

        // DAV servers may escape brackets/unreserved characters or change percent-hex case.
        // Compare decoded segments, never a decoded whole path: %2F must remain inside one
        // segment rather than being interpreted as a separator into another directory.
        private fun sameCollection(left: HttpUrl, right: HttpUrl): Boolean =
            left.pathSegments.dropLastWhile { it.isEmpty() } == right.pathSegments.dropLastWhile { it.isEmpty() }

        internal fun parseListing(
            folder: HttpUrl,
            bytes: ByteArray,
            nowMillis: Long = System.currentTimeMillis(),
        ): List<RemoteSaveVersion> {
            val root = parseXml(bytes)
            val out = LinkedHashMap<String, RemoteSaveVersion>()
            var collectionSeen = false
            val now = nowMillis.coerceAtLeast(0)
            val maxCreatedAt = if (now >= Long.MAX_VALUE - REMOTE_FUTURE_TOLERANCE_MS) {
                Long.MAX_VALUE
            } else {
                now + REMOTE_FUTURE_TOLERANCE_MS
            }
            for (response in children(root, "response")) {
                val href = children(response, "href").singleOrNull()?.textContent?.trim()
                    ?: throw SaveSyncException(SaveSyncError.INVALID_RESPONSE)
                val url = folder.resolve(href) ?: throw SaveSyncException(SaveSyncError.INVALID_RESPONSE)
                if (url.scheme != folder.scheme || url.host != folder.host || url.port != folder.port ||
                    url.query != null || url.fragment != null || url.username.isNotEmpty() || url.password.isNotEmpty()
                ) throw SaveSyncException(SaveSyncError.INVALID_RESPONSE)
                if (sameCollection(url, folder)) {
                    if (!properties(response).any { prop -> children(prop, "resourcetype").any { children(it, "collection").isNotEmpty() } }) {
                        throw SaveSyncException(SaveSyncError.INVALID_RESPONSE)
                    }
                    collectionSeen = true
                    continue
                }
                if (url.pathSegments.size != folder.pathSegments.size ||
                    url.pathSegments.dropLast(1) != folder.pathSegments.dropLast(1)
                ) throw SaveSyncException(SaveSyncError.INVALID_RESPONSE)
                val name = url.pathSegments.last()
                val named = SaveVersionNames.parse(name) ?: continue
                if (named.createdAt !in 0..maxCreatedAt) continue
                val props = properties(response)
                if (props.any { children(it, "resourcetype").any { type -> children(type, "collection").isNotEmpty() } }) continue
                val lengths = props.flatMap { children(it, "getcontentlength") }
                val size = lengths.singleOrNull()?.textContent?.trim()?.toLongOrNull()
                    ?.takeIf { it in 0..SaveContentHash.MAX_BYTES } ?: throw SaveSyncException(SaveSyncError.INVALID_RESPONSE)
                val etag = props.flatMap { children(it, "getetag") }.singleOrNull()?.textContent?.trim()?.takeIf { it.isNotBlank() }
                if (etag != null && (etag.length > 1024 || etag.any { it.code !in 32..126 })) {
                    throw SaveSyncException(SaveSyncError.INVALID_RESPONSE)
                }
                val parsed = SaveVersionNames.parse(name, size, etag) ?: throw SaveSyncException(SaveSyncError.INVALID_RESPONSE)
                if (out.put(name, parsed) != null) throw SaveSyncException(SaveSyncError.INVALID_RESPONSE)
            }
            if (!collectionSeen) throw SaveSyncException(SaveSyncError.INVALID_RESPONSE)
            return out.values.sortedWith(compareByDescending<RemoteSaveVersion> { it.createdAt }.thenByDescending { it.contentHash })
        }

        private fun properties(response: Element): List<Element> = children(response, "propstat").flatMap { stat ->
            val status = children(stat, "status").singleOrNull()?.textContent.orEmpty()
            if (Regex("^HTTP/\\S+ 200(?: .*)?$").matches(status.trim())) children(stat, "prop") else emptyList()
        }

        private fun parseXml(bytes: ByteArray): Element {
            try {
                if (bytes.size > MAX_XML_BYTES) throw SaveSyncException(SaveSyncError.INVALID_RESPONSE)
                val charset = when {
                    bytes.size >= 4 && bytes[0] == 0.toByte() && bytes[1] == 0.toByte() -> Charset.forName("UTF-32BE")
                    bytes.size >= 4 && bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte() && bytes[2] == 0.toByte() && bytes[3] == 0.toByte() -> Charset.forName("UTF-32LE")
                    bytes.size >= 2 && bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte() -> Charsets.UTF_16BE
                    bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte() -> Charsets.UTF_16LE
                    bytes.size >= 2 && bytes[0] == 0.toByte() && bytes[1] == '<'.code.toByte() -> Charsets.UTF_16BE
                    bytes.size >= 2 && bytes[0] == '<'.code.toByte() && bytes[1] == 0.toByte() -> Charsets.UTF_16LE
                    else -> Charsets.UTF_8
                }
                // Decode once before rejecting DTDs, including UTF-16/32. Supplying a Reader
                // prevents an XML encoding declaration from changing that security decision.
                val xml = charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
                if (xml.contains("<!DOCTYPE", ignoreCase = true)) throw SaveSyncException(SaveSyncError.INVALID_RESPONSE)
                validateXmlStructure(xml)
                val factory = DocumentBuilderFactory.newInstance().apply {
                    isNamespaceAware = true
                    isExpandEntityReferences = false
                }
                val parser = factory.newDocumentBuilder().apply {
                    setEntityResolver { _, _ -> throw SAXException("External entity") }
                    setErrorHandler(object : org.xml.sax.helpers.DefaultHandler() {
                        override fun error(error: org.xml.sax.SAXParseException) { throw error }
                        override fun fatalError(error: org.xml.sax.SAXParseException) { throw error }
                    })
                }
                val root = parser.parse(InputSource(StringReader(xml))).documentElement
                if (root.namespaceURI != "DAV:" || root.localName != "multistatus") throw SaveSyncException(SaveSyncError.INVALID_RESPONSE)
                return root
            } catch (failure: SaveSyncException) {
                throw failure
            } catch (failure: Exception) {
                throw SaveSyncException(SaveSyncError.INVALID_RESPONSE, cause = failure)
            }
        }

        /**
         * Bound attacker-controlled tree size before the DOM is materialized. The streaming
         * pass also rejects the same DTD and external-entity surface as the DOM parser.
         */
        private fun validateXmlStructure(xml: String) {
            var depth = 0
            var elements = 0
            var responses = 0
            val handler = object : DefaultHandler() {
                override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
                    elements++
                    if (elements > MAX_XML_ELEMENTS) throw SaveSyncException(SaveSyncError.INVALID_RESPONSE)
                    depth++
                    if (depth > MAX_XML_DEPTH) throw SaveSyncException(SaveSyncError.INVALID_RESPONSE)
                    if (uri == "DAV:" && localName == "response") {
                        responses++
                        if (responses > MAX_XML_RESPONSES) throw SaveSyncException(SaveSyncError.INVALID_RESPONSE)
                    }
                }

                override fun endElement(uri: String?, localName: String?, qName: String?) {
                    depth--
                }

                override fun error(exception: SAXParseException) {
                    throw exception
                }

                override fun fatalError(exception: SAXParseException) {
                    throw exception
                }
            }
            try {
                val factory = SAXParserFactory.newInstance().apply {
                    isNamespaceAware = true
                    isValidating = false
                }
                factory.newSAXParser().xmlReader.apply {
                    contentHandler = handler
                    errorHandler = handler
                    entityResolver = EntityResolver { _, _ -> throw SAXException("External entity") }
                }.parse(InputSource(StringReader(xml)))
            } catch (failure: SaveSyncException) {
                throw failure
            } catch (failure: Exception) {
                throw SaveSyncException(SaveSyncError.INVALID_RESPONSE, cause = failure)
            }
        }

        private fun children(element: Element, name: String): List<Element> = buildList {
            val nodes = element.childNodes
            for (index in 0 until nodes.length) {
                val node = nodes.item(index)
                if (node is Element && node.namespaceURI == "DAV:" && node.localName == name) add(node)
            }
        }

        private fun readSmall(response: Response, check: () -> Unit): ByteArray {
            if (response.body.contentLength() > MAX_XML_BYTES) throw SaveSyncException(SaveSyncError.INVALID_RESPONSE)
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            response.body.byteStream().use { input ->
                while (true) {
                    check()
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > MAX_XML_BYTES) throw SaveSyncException(SaveSyncError.INVALID_RESPONSE)
                    output.write(buffer, 0, count)
                }
            }
            return output.toByteArray()
        }

        private fun requireStatus(response: Response, vararg allowed: Int) {
            if (response.code in allowed) return
            throw SaveSyncException(if (response.code == 401 || response.code == 403) SaveSyncError.AUTHENTICATION else SaveSyncError.SERVER, response.code)
        }
    }
}

/** File operations are injectable so download rollback and I/O classification stay JVM-testable. */
internal class WebDavDownloadFileSystem(
    val usableSpace: (File) -> Long = { it.usableSpace },
    val openOutput: (File) -> OutputStream = { it.outputStream() },
)
