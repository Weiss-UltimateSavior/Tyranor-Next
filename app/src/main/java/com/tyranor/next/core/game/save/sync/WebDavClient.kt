package com.tyranor.next.core.game.save.sync

import kotlinx.coroutines.NonCancellable
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
import org.w3c.dom.Element
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.Charset
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** DAV requests are confined to one configured origin and this application's version namespace. */
class WebDavClient(
    config: WebDavConfig,
    private val temporaryDirectory: File? = null,
    private val enabledCheck: (() -> Boolean)? = null,
) : AutoCloseable {
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

    suspend fun upload(game: RemoteSaveGame, version: SaveVersion, source: File): Boolean {
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
        if (code != 412) return true
        val existing = list(game).firstOrNull { it.name == version.id && !it.legacy }
            ?: throw SaveSyncException(SaveSyncError.INVALID_RESPONSE)
        val verification = SaveSyncTemporaryFiles.create("dav_collision_", ".zip", temporaryDirectory)
        try {
            download(game, existing, verification)
            if (SaveContentHash.archive(verification) != version.contentHash) throw SaveSyncException(SaveSyncError.CORRUPT_ARCHIVE)
        } finally {
            SaveSyncTemporaryFiles.release(verification)
        }
        return false
    }

    /** Promote identical bytes with a server-side rename when DAV MOVE is available. */
    suspend fun refresh(game: RemoteSaveGame, previous: RemoteSaveVersion, publication: SaveVersion, source: File): Long {
        val parsed = SaveVersionNames.parse(publication.id)
            ?: throw SaveSyncException(SaveSyncError.INVALID_CONFIG)
        if (!parsed.restored || parsed.contentHash != previous.contentHash ||
            parsed.contentHash != publication.contentHash || parsed.createdAt != publication.createdAt
        ) throw SaveSyncException(SaveSyncError.INVALID_CONFIG)
        ensureGame(game)
        val destination = versionUrl(game, publication.id)
        val move = request(versionUrl(game, previous.name, previous.legacy))
            .method("MOVE", null).header("Destination", destination.toString()).header("Overwrite", "F").apply {
                previous.etag?.takeUnless { it.startsWith("W/") }?.let { header("If-Match", it) }
            }.build()
        val code = exchange(move) { response, _ ->
            if (response.code !in setOf(404, 405, 412, 501)) requireStatus(response, 201, 204)
            response.code
        }
        if (code == 201 || code == 204) return previous.size
        // Some DAV providers support PUT/DELETE but not MOVE. The caller confirms this new
        // immutable file before removing the old one, including after an interrupted MOVE.
        upload(game, publication, source)
        return source.length()
    }

    suspend fun download(game: RemoteSaveGame, remote: RemoteSaveVersion, destination: File) {
        if (remote.size < 0 || remote.size > SaveContentHash.MAX_BYTES) throw SaveSyncException(SaveSyncError.INVALID_RESPONSE)
        // Open before awaiting HTTP, so cancellation cannot recreate an already removed temp path.
        destination.outputStream().use { output ->
            exchange(request(versionUrl(game, remote.name, remote.legacy)).get().build()) { response, check ->
                requireStatus(response, 200)
                val body = response.body
                if (body.contentLength() > SaveContentHash.MAX_BYTES) throw SaveSyncException(SaveSyncError.CORRUPT_ARCHIVE)
                var total = 0L
                val buffer = ByteArray(64 * 1024)
                body.byteStream().use { input ->
                    while (true) {
                        check()
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > SaveContentHash.MAX_BYTES) throw SaveSyncException(SaveSyncError.CORRUPT_ARCHIVE)
                        output.write(buffer, 0, count)
                    }
                }
                if (total != remote.size) throw SaveSyncException(SaveSyncError.INVALID_RESPONSE)
            }
        }
    }

    suspend fun delete(game: RemoteSaveGame, remote: RemoteSaveVersion) {
        val request = request(versionUrl(game, remote.name, remote.legacy)).delete().apply {
            remote.etag?.takeUnless { it.startsWith("W/") }?.let { header("If-Match", it) }
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
    ): T = suspendCancellableCoroutine { continuation ->
        if (!cleanup && enabledCheck?.invoke() == false) {
            continuation.cancel(kotlinx.coroutines.CancellationException("WebDAV disabled"))
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
                        val check = { if (!continuation.isActive) throw kotlinx.coroutines.CancellationException() }
                        check()
                        val value = consume(response, check)
                        if (continuation.isActive) continuation.resume(value)
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        continuation.cancel(cancelled)
                    } catch (failure: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(
                            if (failure is SaveSyncException) failure else SaveSyncException(SaveSyncError.NETWORK, cause = failure),
                        )
                    }
                }
            }
        })
    }

    override fun close() {
        client.dispatcher.cancelAll()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    companion object {
        private val XML = "application/xml; charset=utf-8".toMediaType()
        private val ZIP = "application/zip".toMediaType()
        private const val PROPFIND = "<?xml version=\"1.0\" encoding=\"utf-8\"?><d:propfind xmlns:d=\"DAV:\"><d:prop><d:resourcetype/><d:getcontentlength/><d:getetag/></d:prop></d:propfind>"
        private const val MAX_XML_BYTES = 16 * 1024 * 1024

        // OkHttp leaves path brackets literal; strict DAV servers require their URI escapes.
        private fun escapePathBrackets(url: HttpUrl): HttpUrl = url.newBuilder()
            .encodedPath(url.encodedPath.replace("[", "%5B").replace("]", "%5D")).build()

        // DAV servers may escape brackets/unreserved characters or change percent-hex case.
        // Compare decoded segments, never a decoded whole path: %2F must remain inside one
        // segment rather than being interpreted as a separator into another directory.
        private fun sameCollection(left: HttpUrl, right: HttpUrl): Boolean =
            left.pathSegments.dropLastWhile { it.isEmpty() } == right.pathSegments.dropLastWhile { it.isEmpty() }

        internal fun parseListing(folder: HttpUrl, bytes: ByteArray): List<RemoteSaveVersion> {
            val root = parseXml(bytes)
            val out = LinkedHashMap<String, RemoteSaveVersion>()
            var collectionSeen = false
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
                if (SaveVersionNames.parse(name) == null) continue
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
