package com.tyranor.next.core.game.save.sync

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
            val createdAt = 1_700_000_000_789L
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

                val firstUpload = client.upload(GAME, version, archive)
                assertTrue(firstUpload.uploaded)
                assertEquals(archive.length(), firstUpload.size)
                val duplicateUpload = client.upload(GAME, version, archive)
                assertFalse(duplicateUpload.uploaded)
                assertEquals(archive.length(), duplicateUpload.size)
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
        val previousAt = 1_700_000_000_000L
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
            val verifiedSource = methods.indexOf("GET" to previousPath)
            val movedSource = methods.indexOf("MOVE" to previousPath)
            assertTrue(verifiedSource >= 0)
            assertTrue(movedSource > verifiedSource)
            assertFalse(previousPath in files)
            assertArrayEquals(archive.readBytes(), files.getValue(targetPath))
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
        val previousAt = 1_700_000_100_000L
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
            assertTrue(methods.indexOf("GET" to previousPath) < methods.indexOf("MOVE" to previousPath))
            assertFalse(methods.any { it.first == "DELETE" })
        } finally { server.stop(0) }
    }

    @Test fun cloudRefreshRepairsCorruptTargetWithStrongEtagAndPreservesSource() = runBlocking {
        val folders = HashSet<String>()
        val files = HashMap<String, ByteArray>()
        val methods = ArrayList<Pair<String, String>>()
        val moves = ArrayList<MoveRequest>()
        val puts = ArrayList<PutRequest>()
        var sourcePreservedAtRepair = false
        var targetCorruptAtRepair = false
        val engineFolder = "/dav/TYN-saves/${GAME.engine}/"
        val gameFolder = "$engineFolder${GAME.folderName}/"
        folders += listOf("/dav/TYN-saves/", engineFolder, gameFolder)

        val archive = temp.newFile()
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("save.bin")); zip.write("repair progress".toByteArray()); zip.closeEntry()
        }
        val sourceBytes = archive.readBytes()
        val corruptBytes = "corrupt target".toByteArray()
        val hash = SaveContentHash.archive(archive)
        val previousAt = 1_700_000_200_000L
        val previousName = SaveVersionNames.createRemote(previousAt, hash, ZoneOffset.UTC)
        val previousPath = gameFolder + previousName
        val publicationAt = previousAt + 60_000
        val publicationName = SaveVersionNames.createRemote(publicationAt, hash, ZoneOffset.UTC, restored = true)
        val targetPath = gameFolder + publicationName
        files[previousPath] = sourceBytes
        files[targetPath] = corruptBytes

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
                            val etag = if (name == targetPath) "\"target-v1\"" else "\"source-v1\""
                            response(encodedHref(name), false, content.size, etag)
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
                        destination in files -> 412
                        else -> {
                            files[destination] = content
                            files.remove(path)
                            201
                        }
                    }
                }
                "GET" -> if (path !in files) 404 else { body = files.getValue(path); 200 }
                "PUT" -> {
                    val ifNoneMatch = exchange.requestHeaders.getFirst("If-None-Match")
                    val ifMatch = exchange.requestHeaders.getFirst("If-Match")
                    val uploaded = exchange.requestBody.readBytes()
                    puts += PutRequest(path, ifNoneMatch, ifMatch)
                    when {
                        ifNoneMatch == "*" && path in files -> 412
                        path == targetPath && ifMatch == "\"target-v1\"" -> {
                            sourcePreservedAtRepair = files[previousPath]?.contentEquals(sourceBytes) == true
                            targetCorruptAtRepair = files[targetPath]?.contentEquals(corruptBytes) == true
                            files[path] = uploaded
                            204
                        }
                        else -> 412
                    }
                }
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
            val publication = SaveVersion(publicationName, hash, publicationAt, archive.length(), restored = true)
            WebDavClient(
                WebDavConfig("http://127.0.0.1:${server.address.port}/dav/", "user", "password"),
            ).use { client ->
                val previous = client.list(GAME).single { it.name == previousName }
                assertEquals(archive.length(), client.refresh(GAME, previous, publication, archive))
            }
            assertEquals(1, moves.size)
            assertEquals(previousPath, moves.single().source)
            assertEquals(targetPath, moves.single().destination)
            assertEquals("\"source-v1\"", moves.single().ifMatch)
            assertEquals(
                listOf(PutRequest(targetPath, "*", null), PutRequest(targetPath, null, "\"target-v1\"")),
                puts,
            )
            assertTrue(sourcePreservedAtRepair)
            assertTrue(targetCorruptAtRepair)
            assertArrayEquals(sourceBytes, files.getValue(previousPath))
            assertArrayEquals(sourceBytes, files.getValue(targetPath))
            assertFalse(methods.any { it.first == "DELETE" })
        } finally { server.stop(0) }
    }

    @Test fun corruptSourceSkipsMoveAndUploadsVerifiedLocalBytes() = runBlocking {
        val folders = HashSet<String>()
        val files = HashMap<String, ByteArray>()
        val methods = ArrayList<Pair<String, String>>()
        val moves = ArrayList<MoveRequest>()
        val puts = ArrayList<PutRequest>()
        val engineFolder = "/dav/TYN-saves/${GAME.engine}/"
        val gameFolder = "$engineFolder${GAME.folderName}/"
        folders += listOf("/dav/TYN-saves/", engineFolder, gameFolder)

        val archive = temp.newFile()
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("save.bin")); zip.write("verified local progress".toByteArray()); zip.closeEntry()
        }
        val sourceBytes = archive.readBytes()
        val corruptBytes = "corrupt source".toByteArray()
        val hash = SaveContentHash.archive(archive)
        val previousAt = 1_700_000_300_000L
        val previousName = SaveVersionNames.createRemote(previousAt, hash, ZoneOffset.UTC)
        val previousPath = gameFolder + previousName
        val publicationAt = previousAt + 60_000
        val publicationName = SaveVersionNames.createRemote(publicationAt, hash, ZoneOffset.UTC, restored = true)
        val targetPath = gameFolder + publicationName
        files[previousPath] = corruptBytes

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
                            response(encodedHref(name), false, content.size, "\"source-v1\"")
                        }.joinToString("")
                    } else ""
                    body = multistatus(response(encodedHref(path), true) + children).toByteArray()
                    207
                }
                "MOVE" -> {
                    moves += MoveRequest(
                        path,
                        exchange.requestHeaders.getFirst("Destination")?.let(::decodedPath),
                        exchange.requestHeaders.getFirst("Overwrite"),
                        exchange.requestHeaders.getFirst("If-Match"),
                    )
                    405
                }
                "GET" -> if (path !in files) 404 else { body = files.getValue(path); 200 }
                "PUT" -> {
                    val ifNoneMatch = exchange.requestHeaders.getFirst("If-None-Match")
                    val ifMatch = exchange.requestHeaders.getFirst("If-Match")
                    val uploaded = exchange.requestBody.readBytes()
                    puts += PutRequest(path, ifNoneMatch, ifMatch)
                    if (ifNoneMatch == "*" && path in files) 412 else {
                        files[path] = uploaded
                        201
                    }
                }
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
            val publication = SaveVersion(publicationName, hash, publicationAt, archive.length(), restored = true)
            WebDavClient(
                WebDavConfig("http://127.0.0.1:${server.address.port}/dav/", "user", "password"),
            ).use { client ->
                val previous = client.list(GAME).single()
                assertEquals(archive.length(), client.refresh(GAME, previous, publication, archive))
            }
            assertTrue(methods.indexOf("GET" to previousPath) < methods.indexOf("PUT" to targetPath))
            assertEquals(emptyList<MoveRequest>(), moves)
            assertEquals(listOf(PutRequest(targetPath, "*", null)), puts)
            assertArrayEquals(sourceBytes, files.getValue(targetPath))
            assertArrayEquals(corruptBytes, files.getValue(previousPath))
            assertFalse(methods.any { it.first == "DELETE" })
        } finally { server.stop(0) }
    }

    @Test fun corruptTargetWithoutStrongEtagFailsClosedWithoutDestructiveWrites() = runBlocking {
        val archive = temp.newFile()
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("save.bin")); zip.write("safe local progress".toByteArray()); zip.closeEntry()
        }
        val hash = SaveContentHash.archive(archive)
        val createdAt = 1_700_000_400_000L
        val version = SaveVersion(
            SaveVersionNames.createRemote(createdAt, hash, ZoneOffset.UTC),
            hash,
            createdAt,
            archive.length(),
        )
        val engineFolder = "/dav/TYN-saves/${GAME.engine}/"
        val gameFolder = "$engineFolder${GAME.folderName}/"
        val targetPath = gameFolder + version.id
        val corruptBytes = "corrupt target".toByteArray()

        listOf<String?>("W/\"weak\"", null).forEach { etag ->
            val folders = hashSetOf("/dav/TYN-saves/", engineFolder, gameFolder)
            val files = hashMapOf(targetPath to corruptBytes)
            val methods = ArrayList<Pair<String, String>>()
            val moves = ArrayList<MoveRequest>()
            val puts = ArrayList<PutRequest>()
            var deleted = false
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
                                response(encodedHref(name), false, content.size, etag)
                            }.joinToString("")
                        } else ""
                        body = multistatus(response(encodedHref(path), true) + children).toByteArray()
                        207
                    }
                    "GET" -> if (path !in files) 404 else { body = files.getValue(path); 200 }
                    "PUT" -> {
                        val ifNoneMatch = exchange.requestHeaders.getFirst("If-None-Match")
                        val ifMatch = exchange.requestHeaders.getFirst("If-Match")
                        val uploaded = exchange.requestBody.readBytes()
                        puts += PutRequest(path, ifNoneMatch, ifMatch)
                        if (ifNoneMatch == "*" && path in files) 412 else {
                            files[path] = uploaded
                            201
                        }
                    }
                    "MOVE" -> {
                        moves += MoveRequest(
                            path,
                            exchange.requestHeaders.getFirst("Destination")?.let(::decodedPath),
                            exchange.requestHeaders.getFirst("Overwrite"),
                            exchange.requestHeaders.getFirst("If-Match"),
                        )
                        201
                    }
                    "DELETE" -> { deleted = true; files.remove(path); 204 }
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
                WebDavClient(
                    WebDavConfig("http://127.0.0.1:${server.address.port}/dav/", "user", "password"),
                ).use { client ->
                    try {
                        client.upload(GAME, version, archive)
                        throw AssertionError("Corrupt target without a strong ETag was repaired")
                    } catch (expected: SaveSyncException) {
                        assertEquals(SaveSyncError.CORRUPT_ARCHIVE, expected.code)
                    }
                }
                assertTrue(methods.any { it.first == "GET" && it.second == targetPath })
                assertEquals(listOf(PutRequest(targetPath, "*", null)), puts)
                assertTrue(moves.isEmpty())
                assertFalse(deleted)
                assertArrayEquals(corruptBytes, files.getValue(targetPath))
            } finally { server.stop(0) }
        }
    }

    @Test fun discardCorruptPublicationRequiresStrongEtagAndConditionalDelete() = runBlocking {
        val createdAt = 1_700_000_500_000L
        val contentHash = "c".repeat(64)
        val name = SaveVersionNames.createRemote(createdAt, contentHash, ZoneOffset.UTC)
        val gameFolder = "/dav/TYN-saves/${GAME.engine}/${GAME.folderName}/"
        val targetPath = gameFolder + name
        val corruptBytes = "corrupt publication".toByteArray()

        suspend fun exercise(etag: String?, expectDeleted: Boolean) {
            val files = hashMapOf(targetPath to corruptBytes)
            val deletes = ArrayList<String?>()
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/dav/") { exchange ->
                val path = exchange.requestURI.path
                var body = ByteArray(0)
                val status = when (exchange.requestMethod) {
                    "GET" -> if (path !in files) 404 else { body = files.getValue(path); 200 }
                    "DELETE" -> {
                        deletes += exchange.requestHeaders.getFirst("If-Match")
                        files.remove(path)
                        204
                    }
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
                val remote = RemoteSaveVersion(name, contentHash, createdAt, corruptBytes.size.toLong(), etag)
                WebDavClient(
                    WebDavConfig("http://127.0.0.1:${server.address.port}/dav/", "user", "password"),
                ).use { client ->
                    if (expectDeleted) {
                        assertTrue(client.discardCorruptPublication(GAME, remote))
                    } else {
                        try {
                            client.discardCorruptPublication(GAME, remote)
                            throw AssertionError("Corrupt publication without a strong ETag was deleted")
                        } catch (expected: SaveSyncException) {
                            assertEquals(SaveSyncError.CORRUPT_ARCHIVE, expected.code)
                        }
                    }
                }
                if (expectDeleted) {
                    assertEquals(listOf("\"strong\""), deletes)
                    assertFalse(targetPath in files)
                } else {
                    assertTrue(deletes.isEmpty())
                    assertArrayEquals(corruptBytes, files.getValue(targetPath))
                }
            } finally { server.stop(0) }
        }

        exercise("\"strong\"", expectDeleted = true)
        exercise(null, expectDeleted = false)
    }

    @Test fun disabledCheckReturnsTypedErrorBeforeAndDuringResponse() = runBlocking {
        var requests = 0
        val idleServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        idleServer.createContext("/") { exchange ->
            requests++
            exchange.sendResponseHeaders(503, -1)
            exchange.close()
        }
        idleServer.start()
        try {
            WebDavClient(
                WebDavConfig("http://127.0.0.1:${idleServer.address.port}/dav/", "user", "password"),
                enabledCheck = { false },
            ).use { client ->
                try {
                    client.list(GAME)
                    throw AssertionError("Disabled client made a request")
                } catch (expected: SaveSyncException) {
                    assertEquals(SaveSyncError.DISABLED, expected.code)
                }
            }
            assertEquals(0, requests)
        } finally { idleServer.stop(0) }

        requests = 0
        var checks = 0
        val respondingServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        respondingServer.createContext("/") { exchange ->
            requests++
            exchange.sendResponseHeaders(207, -1)
            exchange.close()
        }
        respondingServer.start()
        try {
            WebDavClient(
                WebDavConfig("http://127.0.0.1:${respondingServer.address.port}/dav/", "user", "password"),
                enabledCheck = { ++checks == 1 },
            ).use { client ->
                try {
                    client.list(GAME)
                    throw AssertionError("Response check accepted a disabled client")
                } catch (expected: SaveSyncException) {
                    assertEquals(SaveSyncError.DISABLED, expected.code)
                }
            }
            assertEquals(1, requests)
            assertEquals(2, checks)
        } finally { respondingServer.stop(0) }
    }

    @Test fun handlerThrowableAlwaysResolvesExchange() = runBlocking {
        var checks = 0
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val body = multistatus(response("/dav/TYN-saves/${GAME.engine}/", true)).toByteArray()
            exchange.sendResponseHeaders(207, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
            exchange.close()
        }
        server.start()
        try {
            WebDavClient(
                WebDavConfig("http://127.0.0.1:${server.address.port}/dav/", "user", "password"),
                enabledCheck = {
                    checks++
                    if (checks > 1) throw AssertionError("handler failure")
                    true
                },
            ).use { client ->
                val failure = try {
                    withTimeout(5_000) { client.list(GAME) }
                    null
                } catch (caught: Throwable) {
                    caught
                }
                assertTrue("Exchange did not resume after a handler Error", failure is AssertionError)
                assertEquals("handler failure", failure?.message)
            }
            assertEquals(2, checks)
        } finally {
            server.stop(0)
        }
    }

    @Test fun actualCancellationPropagatesInsteadOfBecomingDisabled() = runBlocking {
        val requestStarted = CountDownLatch(1)
        val releaseResponse = CountDownLatch(1)
        var requests = 0
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            requests++
            requestStarted.countDown()
            releaseResponse.await(5, TimeUnit.SECONDS)
            exchange.sendResponseHeaders(503, -1)
            exchange.close()
        }
        server.start()
        try {
            WebDavClient(
                WebDavConfig("http://127.0.0.1:${server.address.port}/dav/", "user", "password"),
            ).use { client ->
                var observed: Throwable? = null
                val job = launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        client.list(GAME)
                    } catch (failure: Throwable) {
                        observed = failure
                        throw failure
                    }
                }
                assertTrue(requestStarted.await(5, TimeUnit.SECONDS))
                job.cancelAndJoin()
                assertTrue(job.isCancelled)
                assertTrue(observed is CancellationException)
                assertFalse(observed is SaveSyncException)
            }
            assertEquals(1, requests)
        } finally {
            releaseResponse.countDown()
            server.stop(0)
        }
    }

    @Test fun downloadHttpFailurePreservesExistingDestinationAndReleasesTempFile() = runBlocking {
        val oldBytes = "existing local save".toByteArray()
        val destination = temp.newFile().apply { writeBytes(oldBytes) }
        val createdAt = 1_700_000_600_000L
        val remote = RemoteSaveVersion(
            SaveVersionNames.createRemote(createdAt, "a".repeat(64), ZoneOffset.UTC),
            "a".repeat(64),
            createdAt,
            "replacement".toByteArray().size.toLong(),
            "\"immutable\"",
        )
        var bytesDuringRequest: ByteArray? = null
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            bytesDuringRequest = destination.readBytes()
            exchange.sendResponseHeaders(503, -1)
            exchange.close()
        }
        server.start()
        try {
            WebDavClient(
                WebDavConfig("http://127.0.0.1:${server.address.port}/dav/", "user", "password"),
            ).use { client ->
                try {
                    client.download(GAME, remote, destination)
                    throw AssertionError("HTTP failure was accepted")
                } catch (expected: SaveSyncException) {
                    assertEquals(SaveSyncError.SERVER, expected.code)
                }
            }
            assertArrayEquals(oldBytes, bytesDuringRequest)
            assertArrayEquals(oldBytes, destination.readBytes())
            assertTrue(checkNotNull(destination.parentFile).listFiles().orEmpty().none {
                it.name.startsWith("dav_download_") && it.name.endsWith(".part")
            })
        } finally {
            server.stop(0)
        }
    }

    @Test fun cancelledDownloadPreservesExistingDestinationAndReleasesTempFile() = runBlocking {
        val oldBytes = "existing local save".toByteArray()
        val downloadedBytes = "partial replacement".toByteArray()
        val destination = temp.newFile().apply { writeBytes(oldBytes) }
        val createdAt = 1_700_000_700_000L
        val remote = RemoteSaveVersion(
            SaveVersionNames.createRemote(createdAt, "b".repeat(64), ZoneOffset.UTC),
            "b".repeat(64),
            createdAt,
            downloadedBytes.size.toLong(),
            "\"immutable\"",
        )
        val partialBodySent = CountDownLatch(1)
        val releaseResponse = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.sendResponseHeaders(200, remote.size)
            exchange.responseBody.use { output ->
                output.write(downloadedBytes, 0, downloadedBytes.size / 2)
                output.flush()
                partialBodySent.countDown()
                releaseResponse.await(5, TimeUnit.SECONDS)
                runCatching { output.write(downloadedBytes, downloadedBytes.size / 2, downloadedBytes.size - downloadedBytes.size / 2) }
            }
            exchange.close()
        }
        server.start()
        try {
            WebDavClient(
                WebDavConfig("http://127.0.0.1:${server.address.port}/dav/", "user", "password"),
            ).use { client ->
                var observed: Throwable? = null
                val job = launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        client.download(GAME, remote, destination)
                    } catch (failure: Throwable) {
                        observed = failure
                        throw failure
                    }
                }
                assertTrue(partialBodySent.await(5, TimeUnit.SECONDS))
                job.cancelAndJoin()
                assertTrue(job.isCancelled)
                assertTrue(observed is CancellationException)
                assertFalse(observed is SaveSyncException)
            }
            assertArrayEquals(oldBytes, destination.readBytes())
            assertTrue(checkNotNull(destination.parentFile).listFiles().orEmpty().none {
                it.name.startsWith("dav_download_") && it.name.endsWith(".part")
            })
        } finally {
            releaseResponse.countDown()
            server.stop(0)
        }
    }

    @Test fun listingIgnoresCreatedAtOutsideNowThroughNextDay() {
        val now = 1_700_000_800_000L
        val tolerance = TimeUnit.DAYS.toMillis(1)
        val folder = "https://example.com/dav/TYN-saves/${GAME.engine}/${GAME.folderName}/".toHttpUrl()
        val historical = SaveVersionNames.createRemote(now - 1, "a".repeat(64), ZoneOffset.UTC)
        val boundary = SaveVersionNames.createRemote(now + tolerance, "b".repeat(64), ZoneOffset.UTC)
        val beyondTolerance = SaveVersionNames.createRemote(now + tolerance + 1, "c".repeat(64), ZoneOffset.UTC)
        val absurdFuture = "9000000000000000000_${"d".repeat(64)}.zip"
        val bytes = multistatus(
            response(folder.encodedPath, true) +
                response(folder.encodedPath + historical, false, 1) +
                response(folder.encodedPath + boundary, false, 1) +
                response(folder.encodedPath + beyondTolerance, false, 1) +
                response(folder.encodedPath + absurdFuture, false, 1).replace(
                    "<d:getcontentlength>1</d:getcontentlength>",
                    "<d:getcontentlength>not-a-number</d:getcontentlength>",
                ),
        ).toByteArray()

        val versions = WebDavClient.parseListing(folder, bytes, now)

        assertEquals(listOf(boundary, historical), versions.map { it.name })
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

    @Test fun listingRejectsExcessiveXmlResponseCountBeforeDom() {
        val folder = "https://example.com/dav/TYN-saves/${GAME.engine}/${GAME.folderName}/".toHttpUrl()
        val responses = "<d:response/>".repeat(WebDavClient.MAX_XML_RESPONSES + 1)
        assertInvalidListing(folder, multistatus(responses).toByteArray())
    }

    @Test fun listingRejectsExcessiveXmlDepthBeforeDom() {
        val folder = "https://example.com/dav/TYN-saves/${GAME.engine}/${GAME.folderName}/".toHttpUrl()
        val nested = "<d:nested>".repeat(WebDavClient.MAX_XML_DEPTH) +
            "</d:nested>".repeat(WebDavClient.MAX_XML_DEPTH)
        assertInvalidListing(folder, multistatus(nested).toByteArray())
    }

    @Test fun listingRejectsExcessiveTotalXmlElementsBeforeDom() {
        val folder = "https://example.com/dav/TYN-saves/${GAME.engine}/${GAME.folderName}/".toHttpUrl()
        val siblings = "<x:item/>".repeat(WebDavClient.MAX_XML_ELEMENTS)
        val xml = "<d:multistatus xmlns:d=\"DAV:\" xmlns:x=\"urn:test\">$siblings</d:multistatus>"
        assertInvalidListing(folder, xml.toByteArray())
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
        private data class PutRequest(
            val path: String,
            val ifNoneMatch: String?,
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
        private fun assertInvalidListing(folder: okhttp3.HttpUrl, bytes: ByteArray) {
            try {
                WebDavClient.parseListing(folder, bytes)
                throw AssertionError("Invalid listing accepted")
            } catch (expected: SaveSyncException) {
                assertEquals(SaveSyncError.INVALID_RESPONSE, expected.code)
            }
        }
        private fun response(href: String, collection: Boolean, size: Int = 0, etag: String? = "\"immutable\"") =
            "<d:response><d:href>$href</d:href><d:propstat><d:prop><d:resourcetype>" +
                (if (collection) "<d:collection/>" else "") + "</d:resourcetype><d:getcontentlength>$size</d:getcontentlength>" +
                (etag?.let { "<d:getetag>${it.replace("\"", "&quot;")}</d:getetag>" }.orEmpty()) +
                "</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"
    }
}
