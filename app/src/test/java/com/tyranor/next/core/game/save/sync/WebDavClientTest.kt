package com.tyranor.next.core.game.save.sync

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetSocketAddress
import java.net.URI
import java.time.ZoneOffset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class WebDavClientTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun immutableUploadCollisionAndLegacyPullPreserveArchives() = runBlocking {
        val folders = HashSet<String>()
        val files = HashMap<String, ByteArray>()
        val methods = ArrayList<Pair<String, String>>()
        val probes = ArrayList<Pair<String, String?>>()
        val legacyFolder = "/dav/TYN-saves/v1/${GAME.key}/"
        val legacyName = SaveVersionNames.create(1, "b".repeat(64))
        val legacyPath = legacyFolder + legacyName
        val legacyBytes = "legacy progress".toByteArray()
        folders += legacyFolder
        files[legacyPath] = legacyBytes
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/dav/") { exchange ->
            val path = exchange.requestURI.path
            val method = exchange.requestMethod
            methods += method to path
            var body = ByteArray(0)
            val status = when (method) {
                "MKCOL" -> if (folders.add(path)) 201 else 405
                "PROPFIND" -> if (path !in folders) 404 else {
                    val depth = exchange.requestHeaders.getFirst("Depth")
                    probes += path to depth
                    val children = if (depth == "1") {
                        files.filterKeys { it.substringBeforeLast('/') + "/" == path }.map { (name, content) ->
                            response(alternateHref(name), false, content.size)
                        }.joinToString("")
                    } else ""
                    body = multistatus(response(alternateHref(path), true) + children).toByteArray()
                    207
                }
                "PUT" -> if (path in files && exchange.requestHeaders.getFirst("If-None-Match") == "*") 412 else {
                    files[path] = exchange.requestBody.readBytes(); 201
                }
                "GET" -> if (path !in files) 404 else { body = files.getValue(path); 200 }
                "DELETE" -> { files.remove(path); 204 }
                else -> 405
            }
            if (body.isNotEmpty()) {
                exchange.sendResponseHeaders(status, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            } else exchange.sendResponseHeaders(status, -1)
            exchange.close()
        }
        server.start()
        try {
            val config = WebDavConfig("http://127.0.0.1:${server.address.port}/dav/", "user", "password")
            val archive = temp.newFile()
            ZipOutputStream(archive.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("save.bin")); zip.write("progress".toByteArray()); zip.closeEntry()
            }
            val hash = SaveContentHash.archive(archive)
            val createdAt = 1_800_000_000_789L
            val version = SaveVersion(
                SaveVersionNames.createRemote(createdAt, hash, ZoneOffset.UTC),
                hash,
                createdAt,
                archive.length(),
            )
            WebDavClient(config).use { client ->
                val legacy = client.list(GAME).single()
                assertEquals(legacyName, legacy.name)
                assertTrue(legacy.legacy)
                val pulledLegacy = temp.newFile()
                client.download(GAME, legacy, pulledLegacy)
                assertArrayEquals(legacyBytes, pulledLegacy.readBytes())

                assertTrue(client.upload(GAME, version, archive))
                assertFalse(client.upload(GAME, version, archive))
                val remotes = client.list(GAME)
                assertEquals(2, remotes.size)
                val remote = remotes.single { !it.legacy }
                assertEquals(version.id, remote.name)
                assertEquals(hash, remote.contentHash)
                val pulled = temp.newFile()
                client.download(GAME, remote, pulled)
                assertArrayEquals(archive.readBytes(), pulled.readBytes())
                client.delete(GAME, remote)
                val remainingLegacy = client.list(GAME).single()
                assertTrue(remainingLegacy.legacy)
                client.delete(GAME, remainingLegacy)
                assertTrue(client.list(GAME).isEmpty())
            }
            val engineFolder = "/dav/TYN-saves/ONS/"
            val gameFolder = "$engineFolder${GAME.folderName}/"
            val expectedUpload = "/dav/TYN-saves/ONS/星と月[kr]+--${GAME.key.take(12)}/${version.id}"
            assertEquals(2, methods.count { it.first == "PUT" })
            assertEquals(listOf(expectedUpload, expectedUpload), methods.filter { it.first == "PUT" }.map { it.second })
            assertTrue(methods.any { it.first == "PROPFIND" && it.second == legacyFolder })
            assertTrue((gameFolder to "0") in probes)
            assertTrue((gameFolder to "1") in probes)
            assertFalse(methods.any {
                it.first == "MKCOL" && (it.second == "/dav/TYN-saves/v1" || it.second.startsWith("/dav/TYN-saves/v1/"))
            })
            assertEquals(2, methods.count { it.first == "DELETE" })
        } finally { server.stop(0) }
    }

    @Test fun cloudRefreshMovesSameArchiveToRestoredNewName() = runBlocking {
        val folders = HashSet<String>()
        val files = HashMap<String, ByteArray>()
        val methods = ArrayList<Pair<String, String>>()
        val moves = ArrayList<MoveRequest>()
        val engineFolder = "/dav/TYN-saves/${GAME.engine}/"
        val gameFolder = "$engineFolder${GAME.folderName}/"
        folders += listOf("/dav/TYN-saves/", engineFolder, gameFolder)

        val archive = temp.newFile()
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("save.bin")); zip.write("restored progress".toByteArray()); zip.closeEntry()
        }
        val hash = SaveContentHash.archive(archive)
        val previousAt = 1_800_000_000_000L
        val previousName = SaveVersionNames.createRemote(previousAt, hash, ZoneOffset.UTC)
        val previousPath = gameFolder + previousName
        files[previousPath] = archive.readBytes()

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/dav/") { exchange ->
            val path = exchange.requestURI.path
            val method = exchange.requestMethod
            methods += method to path
            var body = ByteArray(0)
            val status = when (method) {
                "MKCOL" -> if (folders.add(path)) 201 else 405
                "PROPFIND" -> if (path !in folders) 404 else {
                    val children = if (exchange.requestHeaders.getFirst("Depth") == "1") {
                        files.filterKeys { it.substringBeforeLast('/') + "/" == path }.map { (name, content) ->
                            response(encodedHref(name), false, content.size)
                        }.joinToString("")
                    } else ""
                    body = multistatus(response(encodedHref(path), true) + children).toByteArray()
                    207
                }
                "MOVE" -> {
                    val destination = exchange.requestHeaders.getFirst("Destination")?.let(::decodedPath)
                    moves += MoveRequest(
                        path,
                        destination,
                        exchange.requestHeaders.getFirst("Overwrite"),
                        exchange.requestHeaders.getFirst("If-Match"),
                    )
                    val content = files[path]
                    when {
                        destination == null -> 400
                        content == null -> 404
                        else -> {
                            files[destination] = content
                            files.remove(path)
                            201
                        }
                    }
                }
                "GET" -> if (path !in files) 404 else { body = files.getValue(path); 200 }
                else -> 405
            }
            if (body.isNotEmpty()) {
                exchange.sendResponseHeaders(status, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            } else exchange.sendResponseHeaders(status, -1)
            exchange.close()
        }
        server.start()
        try {
            val restoredAt = previousAt + 60_000
            val publication = SaveVersion(
                SaveVersionNames.createRemote(restoredAt, hash, ZoneOffset.UTC, restored = true),
                hash,
                restoredAt,
                archive.length(),
            )
            val targetPath = gameFolder + publication.id
            WebDavClient(
                WebDavConfig("http://127.0.0.1:${server.address.port}/dav/", "user", "password"),
            ).use { client ->
                val previous = client.list(GAME).single()
                assertFalse(previous.restored)

                assertEquals(archive.length(), client.refresh(GAME, previous, publication, archive))

                val refreshed = client.list(GAME).single()
                assertEquals(publication.id, refreshed.name)
                assertTrue(refreshed.restored)
                val pulled = temp.newFile()
                client.download(GAME, refreshed, pulled)
                assertArrayEquals(archive.readBytes(), pulled.readBytes())
            }
            assertEquals(
                listOf(MoveRequest(previousPath, targetPath, "F", "\"immutable\"")),
                moves,
            )
            assertFalse(methods.any { it.first in setOf("PUT", "DELETE", "COPY") })
        } finally { server.stop(0) }
    }

    @Test fun cloudRefreshFallsBackToImmutablePutWhenMoveIsUnsupported() = runBlocking {
        val folders = HashSet<String>()
        val files = HashMap<String, ByteArray>()
        val methods = ArrayList<Pair<String, String>>()
        val puts = ArrayList<Pair<String, String?>>()
        val engineFolder = "/dav/TYN-saves/${GAME.engine}/"
        val gameFolder = "$engineFolder${GAME.folderName}/"
        folders += listOf("/dav/TYN-saves/", engineFolder, gameFolder)

        val archive = temp.newFile()
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("save.bin")); zip.write("fallback progress".toByteArray()); zip.closeEntry()
        }
        val hash = SaveContentHash.archive(archive)
        val previousAt = 1_800_000_100_000L
        val previousName = SaveVersionNames.createRemote(previousAt, hash, ZoneOffset.UTC)
        val previousPath = gameFolder + previousName
        files[previousPath] = archive.readBytes()

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/dav/") { exchange ->
            val path = exchange.requestURI.path
            val method = exchange.requestMethod
            methods += method to path
            var body = ByteArray(0)
            val status = when (method) {
                "MKCOL" -> if (folders.add(path)) 201 else 405
                "PROPFIND" -> if (path !in folders) 404 else {
                    val children = if (exchange.requestHeaders.getFirst("Depth") == "1") {
                        files.filterKeys { it.substringBeforeLast('/') + "/" == path }.map { (name, content) ->
                            response(encodedHref(name), false, content.size)
                        }.joinToString("")
                    } else ""
                    body = multistatus(response(encodedHref(path), true) + children).toByteArray()
                    207
                }
                "MOVE" -> 405
                "PUT" -> {
                    puts += path to exchange.requestHeaders.getFirst("If-None-Match")
                    if (path in files && exchange.requestHeaders.getFirst("If-None-Match") == "*") 412 else {
                        files[path] = exchange.requestBody.readBytes()
                        201
                    }
                }
                "GET" -> if (path !in files) 404 else { body = files.getValue(path); 200 }
                "DELETE" -> { files.remove(path); 204 }
                else -> 405
            }
            if (body.isNotEmpty()) {
                exchange.sendResponseHeaders(status, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            } else exchange.sendResponseHeaders(status, -1)
            exchange.close()
        }
        server.start()
        try {
            val restoredAt = previousAt + 60_000
            val publication = SaveVersion(
                SaveVersionNames.createRemote(restoredAt, hash, ZoneOffset.UTC, restored = true),
                hash,
                restoredAt,
                archive.length(),
            )
            val targetPath = gameFolder + publication.id
            WebDavClient(
                WebDavConfig("http://127.0.0.1:${server.address.port}/dav/", "user", "password"),
            ).use { client ->
                val previous = client.list(GAME).single()
                assertEquals(archive.length(), client.refresh(GAME, previous, publication, archive))

                val remotes = client.list(GAME)
                assertEquals(2, remotes.size)
                val unchangedOld = remotes.single { it.name == previousName }
                val refreshed = remotes.single { it.name == publication.id }
                assertFalse(unchangedOld.restored)
                assertTrue(refreshed.restored)

                val pulledOld = temp.newFile()
                client.download(GAME, unchangedOld, pulledOld)
                assertArrayEquals(archive.readBytes(), pulledOld.readBytes())
                val pulledNew = temp.newFile()
                client.download(GAME, refreshed, pulledNew)
                assertArrayEquals(archive.readBytes(), pulledNew.readBytes())
            }
            assertEquals(listOf(targetPath to "*"), puts)
            assertTrue(methods.any { it.first == "MOVE" && it.second == previousPath })
            assertFalse(methods.any { it.first == "DELETE" })
        } finally { server.stop(0) }
    }

    @Test fun listingRejectsForeignPathsAndDtdsIncludingUtf16() {
        val folder = "https://example.com/dav/TYN-saves/${GAME.engine}/${GAME.folderName}/".toHttpUrl()
        val name = SaveVersionNames.createRemote(1, "a".repeat(64), ZoneOffset.UTC)
        val self = response(folder.encodedPath, true)
        listOf(
            multistatus(self + response("https://other.example/$name", false, 2)).toByteArray(),
            multistatus(self + response("/outside/$name", false, 2)).toByteArray(),
            multistatus(
                response("/dav/TYN-saves/${GAME.engine}%2f${GAME.folderName}/", true) +
                    response(folder.encodedPath + name, false, 2),
            ).toByteArray(),
            multistatus(self + response("/dav/TYN-saves/${GAME.engine}/${GAME.folderName}%2fchild/$name", false, 2)).toByteArray(),
            "<!DOCTYPE x [<!ENTITY a 'oops'>]><d:multistatus xmlns:d=\"DAV:\"/>".toByteArray(Charsets.UTF_16),
            multistatus("").toByteArray(),
        ).forEach { invalid ->
            try {
                WebDavClient.parseListing(folder, invalid)
                throw AssertionError("Invalid listing accepted")
            } catch (expected: SaveSyncException) { assertEquals(SaveSyncError.INVALID_RESPONSE, expected.code) }
        }
    }

    @Test fun listingAcceptsEquivalentPercentEncodedSelfAndFileHrefs() {
        val gameFolder = "/dav/TYN-saves/${GAME.engine}/${GAME.folderName}/"
        val folder = "https://example.com$gameFolder".toHttpUrl()
        val name = SaveVersionNames.createRemote(1, "a".repeat(64), ZoneOffset.UTC)
        val selfHref = alternateHref(gameFolder)
        val fileHref = alternateHref(gameFolder + name)

        assertTrue(selfHref.contains("%e6"))
        assertTrue(selfHref.contains("[kr]"))
        assertTrue(fileHref.contains("%2b"))
        val versions = WebDavClient.parseListing(
            folder,
            multistatus(response(selfHref, true) + response(fileHref, false, 3)).toByteArray(),
        )
        assertEquals(listOf(name), versions.map { it.name })
    }

    @Test fun authenticatedRequestsDoNotFollowRedirects() = runBlocking {
        var requests = 0
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            requests++
            exchange.responseHeaders.add("Location", "http://127.0.0.1:${server.address.port}/outside/")
            exchange.sendResponseHeaders(307, -1)
            exchange.close()
        }
        server.start()
        try {
            WebDavClient(WebDavConfig("http://127.0.0.1:${server.address.port}/dav/", "user", "secret")).use { client ->
                try {
                    client.list(GAME)
                    throw AssertionError("Redirect followed")
                } catch (expected: SaveSyncException) { assertEquals(SaveSyncError.SERVER, expected.code) }
            }
            assertEquals(1, requests)
        } finally { server.stop(0) }
    }

    companion object {
        private val GAME = RemoteSaveGame("ONS", "星と月[kr]+")
        private data class MoveRequest(
            val source: String,
            val destination: String?,
            val overwrite: String?,
            val ifMatch: String?,
        )
        private fun encodedHref(path: String): String = URI(null, null, path, null).toASCIIString()
        private fun alternateHref(path: String): String = Regex("%[0-9A-F]{2}")
            .replace(encodedHref(path)) { it.value.lowercase() }
            .replace("%5b", "[")
            .replace("%5d", "]")
            .replace("+", "%2b")
        private fun decodedPath(value: String): String = URI(value).path
        private fun multistatus(inner: String) = "<d:multistatus xmlns:d=\"DAV:\">$inner</d:multistatus>"
        private fun response(href: String, collection: Boolean, size: Int = 0) =
            "<d:response><d:href>$href</d:href><d:propstat><d:prop><d:resourcetype>" +
                (if (collection) "<d:collection/>" else "") + "</d:resourcetype><d:getcontentlength>$size</d:getcontentlength>" +
                "<d:getetag>&quot;immutable&quot;</d:getetag></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"
    }
}
