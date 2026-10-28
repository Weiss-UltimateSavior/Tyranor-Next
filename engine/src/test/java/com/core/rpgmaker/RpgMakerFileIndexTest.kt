package com.core.rpgmaker

import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.AccessDeniedException
import java.nio.file.NoSuchFileException
import java.nio.file.DirectoryStream
import java.nio.file.DirectoryIteratorException
import java.nio.file.Path
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeNoException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RpgMakerFileIndexTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun languageDirectoriesAreIndexedWithoutReadingTheirContents() {
        val root = temporary.newFolder("www")
        File(root, "locales/zh-CN").mkdirs()
        File(root, "locales/zh-CN/Map_zh-CN.json").writeText("not JSON: metadata only")
        val index = JSONObject(String(RpgMakerFileIndex(root).build(), Charsets.UTF_8))
        val language = index.getJSONObject("locales").getJSONObject("zh-CN")
        assertEquals("directory", language.getJSONObject("__stats__").getString("type"))
        assertEquals("file", language.getJSONObject("Map_zh-CN.json").getJSONObject("__stats__").getString("type"))
        assertFalse(index.toString().contains("not JSON"))
        assertFalse(File(root, "data/Stamp.json").exists())
    }

    @Test fun inaccessibleChildDoesNotHideReadableArchiveAndDiskResources() {
        val root = temporary.newFolder("partial")
        File(root, "denied").mkdir()
        File(root, "good.json").writeText("{}")
        val file = temporary.newFile("partial.asar")
        writeTestAsar(file, packed = mapOf("denied/from-archive.json" to "{}"), emptyDirectories = setOf("denied"))
        AsarArchive(file).use { archive ->
            val bytes = RpgMakerFileIndex(root, archive, directoryReader = { directory ->
                if (directory.name == "denied") throw AccessDeniedException(directory.path)
                Files.newDirectoryStream(directory.toPath())
            }).build()
            val index = JSONObject(String(bytes))
            assertTrue(index.has("good.json"))
            assertTrue(index.getJSONObject("denied").has("from-archive.json"))
        }
    }

    @Test fun disappearingChildDoesNotAbortOtherResources() {
        val root = temporary.newFolder("disappearing")
        File(root, "gone").mkdir()
        File(root, "good.json").writeText("{}")
        val bytes = RpgMakerFileIndex(root, directoryReader = { directory ->
            if (directory.name == "gone") throw NoSuchFileException(directory.path)
            Files.newDirectoryStream(directory.toPath())
        }).build()
        assertTrue(JSONObject(String(bytes)).has("good.json"))
    }

    @Test fun midIterationDenialClosesStreamAndKeepsArchiveFallback() {
        val root = temporary.newFolder("iteration")
        File(root, "partial").mkdir()
        File(root, "partial/first.json").writeText("{}")
        File(root, "good.json").writeText("{}")
        val file = temporary.newFile("iteration.asar")
        writeTestAsar(file, packed = mapOf("partial/archive.json" to "{}"), emptyDirectories = setOf("partial"))
        var closed = false
        AsarArchive(file).use { archive ->
            val bytes = RpgMakerFileIndex(root, archive, directoryReader = { directory ->
                val real = Files.newDirectoryStream(directory.toPath())
                if (directory.name != "partial") real else object : DirectoryStream<Path> {
                    override fun iterator(): MutableIterator<Path> {
                        val iterator = real.iterator()
                        var yielded = false
                        return object : MutableIterator<Path> {
                            override fun hasNext(): Boolean {
                                if (yielded) throw DirectoryIteratorException(AccessDeniedException(directory.path))
                                return iterator.hasNext()
                            }
                            override fun next(): Path = iterator.next().also { yielded = true }
                            override fun remove() = throw UnsupportedOperationException()
                        }
                    }
                    override fun close() { closed = true; real.close() }
                }
            }).build()
            val index = JSONObject(String(bytes))
            assertTrue(closed)
            assertTrue(index.has("good.json"))
            assertTrue(index.getJSONObject("partial").has("first.json"))
            assertTrue(index.getJSONObject("partial").has("archive.json"))
        }
    }

    @Test(expected = AccessDeniedException::class) fun unreadableRootStillFails() {
        val root = temporary.newFolder("unreadable-root")
        RpgMakerFileIndex(root, directoryReader = { throw AccessDeniedException(it.path) }).build()
    }

    @Test fun securityDeniedChildDoesNotAbortReadableResources() {
        val root = temporary.newFolder("security-denied")
        File(root, "denied").mkdir()
        File(root, "good.json").writeText("{}")
        val bytes = RpgMakerFileIndex(root, directoryReader = { directory ->
            if (directory.name == "denied") throw SecurityException("denied")
            Files.newDirectoryStream(directory.toPath())
        }).build()
        assertTrue(JSONObject(String(bytes)).has("good.json"))
    }

    @Test fun bothHostsRetryFailedBuildAndCacheOnlySuccessfulIndex() {
        for (legacy in listOf(false, true)) {
            val root = temporary.newFolder(if (legacy) "retry-legacy" else "retry-modern")
            File(root, "index.html").writeText("<html></html>")
            val deep = File(root, "deep")
            File(deep, (1..66).joinToString("/") { "d" }).mkdirs()
            val appends = mapOf("js/plugins/dktools.js" to "\n// hook".toByteArray())
            val modernServer = if (!legacy) RpgMakerLocalHttpServer(root, null, scriptAppends = appends) else null
            val legacyServer = if (legacy) com.core.tyrano.TyranoLocalHttpServer(root, null, scriptAppends = appends) else null
            modernServer?.start()
            legacyServer?.start()
            val port = modernServer?.port ?: requireNotNull(legacyServer).port
            try {
                val entry = connection(port, "index.html")
                val cookie = try {
                    entry.inputStream.use { it.readBytes() }
                    entry.getHeaderField("Set-Cookie").substringBefore(';')
                } finally { entry.disconnect() }
                assertEquals(500, request(port, RpgMakerFileIndex.RESOURCE_PATH, cookie).first)
                assertTrue(deep.renameTo(File(root.parentFile, root.name + "-deep-backup")))
                File(root, "ready.json").writeText("{}")
                val recovered = request(port, RpgMakerFileIndex.RESOURCE_PATH, cookie)
                assertEquals(200, recovered.first)
                assertTrue(JSONObject(recovered.second).has("ready.json"))
                File(root, "later.json").writeText("{}")
                assertFalse(JSONObject(request(port, RpgMakerFileIndex.RESOURCE_PATH, cookie).second).has("later.json"))
            } finally {
                modernServer?.stop()
                legacyServer?.stop()
            }
        }
    }

    @Test fun skipsPrototypeKeysAndDevelopmentDirectories() {
        val root = temporary.newFolder("www")
        listOf("node_modules", ".git", "__proto__", "constructor", "prototype", "__stats__").forEach {
            File(root, it).mkdir()
        }
        assertEquals(0, JSONObject(String(RpgMakerFileIndex(root).build())).length())
    }

    @Test fun linksOutsideRootAndDirectoryCyclesAreNotFollowed() {
        val root = temporary.newFolder("www")
        val outside = temporary.newFolder("outside")
        File(outside, "private.txt").writeText("private")
        try {
            Files.createSymbolicLink(File(root, "outside").toPath(), outside.toPath())
            Files.createSymbolicLink(File(root, "loop").toPath(), root.toPath())
        } catch (error: Exception) {
            assumeNoException(error)
        }
        val index = JSONObject(String(RpgMakerFileIndex(root).build()))
        assertFalse(index.has("outside"))
        assertFalse(index.has("loop"))
    }

    @Test(expected = IOException::class) fun entryLimitFailsInsteadOfReturningPartialIndex() {
        val root = temporary.newFolder("www")
        File(root, "a").writeText("")
        File(root, "b").writeText("")
        RpgMakerFileIndex(root, maxEntries = 2).build()
    }

    @Test(expected = IOException::class) fun depthLimitIsEnforced() {
        val root = temporary.newFolder("www")
        File(root, "a/b").mkdirs()
        RpgMakerFileIndex(root, maxDepth = 1).build()
    }

    @Test(expected = IOException::class) fun encodedSizeIsBounded() {
        val root = temporary.newFolder("www")
        File(root, "a").writeText("")
        RpgMakerFileIndex(root, maxBytes = 100).build()
    }

    @Test fun diskFilesOverrideAsarAndMissingDiskChildrenFallBackToArchive() {
        val root = temporary.newFolder("www")
        File(root, "locales/en").mkdirs()
        File(root, "locales/en/override.json").writeText("disk")
        val entries = mapOf(
            "www/index.html" to "<html></html>",
            "www/locales/en/override.json" to "from archive",
            "www/locales/en/archive.json" to "archive",
            "www/locales/blocked/child.json" to "blocked",
        )
        File(root, "locales/blocked").writeText("disk file shadows archive directory")
        val archiveFile = temporary.newFile("app.asar")
        writeArchive(archiveFile, entries)
        AsarArchive(archiveFile).use { archive ->
            val index = JSONObject(String(RpgMakerFileIndex(root, archive, "www/").build()))
            val locales = index.getJSONObject("locales")
            val english = locales.getJSONObject("en")
            assertEquals(4L, english.getJSONObject("override.json").getJSONObject("__stats__").getLong("size"))
            assertTrue(english.has("archive.json"))
            val blocked = locales.getJSONObject("blocked")
            assertEquals("file", blocked.getJSONObject("__stats__").getString("type"))
            assertFalse(blocked.has("child.json"))
            assertFalse(index.has("www"))
        }
    }

    @Test fun packagedScriptsReceiveHooksAndFileIndexRequiresAuthentication() {
        val root = temporary.newFolder("www")
        val archiveFile = temporary.newFile("packaged.asar")
        val script = "// large script\n" + " ".repeat((ASAR_STREAM_THRESHOLD + 1).toInt())
        writeArchive(archiveFile, mapOf(
            "www/index.html" to "<html><body></body></html>",
            "www/js/plugins/DKTools.js" to script,
            "www/locales/zh-CN/Map.json" to "{}",
        ))
        val archive = AsarArchive(archiveFile)
        val index = JSONObject(String(RpgMakerFileIndex(root, archive, "www/").build()))
        assertTrue(index.getJSONObject("locales").getJSONObject("zh-CN").has("Map.json"))
        val marker = "\n/* auto-index-hook */"
        val server = RpgMakerLocalHttpServer(root, archive, null,
            scriptAppends = mapOf("js/plugins/dktools.js" to marker.toByteArray()))
        server.start()
        try {
            assertEquals(403, request(server.port, RpgMakerFileIndex.RESOURCE_PATH).first)
            val entry = connection(server.port, "index.html")
            val cookie = try {
                entry.inputStream.use { it.readBytes() }
                entry.getHeaderField("Set-Cookie").substringBefore(';')
            } finally { entry.disconnect() }
            val response = request(server.port, "js/plugins/DKTools.js", cookie)
            assertEquals(200, response.first)
            assertTrue(response.second.endsWith(marker))
            assertTrue(request(server.port, RpgMakerFileIndex.RESOURCE_PATH, cookie).second.contains("Map.json"))
            File(root, "data").mkdir()
            File(root, "data/Stamp.json").writeText("original even if malformed")
            assertEquals("null", request(server.port, RpgMakerFileIndex.RESOURCE_PATH, cookie).second)
        } finally {
            server.stop()
            archive.close()
        }
    }

    private fun connection(port: Int, path: String, cookie: String? = null): HttpURLConnection =
        (URL("http://127.0.0.1:$port/$path").openConnection() as HttpURLConnection).apply {
            connectTimeout = 5_000
            readTimeout = 5_000
            if (cookie != null) setRequestProperty("Cookie", cookie)
        }

    @Test fun originalWebHostLoadsPluginAndIndexFromDisk() {
        val root = temporary.newFolder("legacy-www")
        File(root, "index.html").writeText("<html><body></body></html>")
        File(root, "locales/en").mkdirs()
        File(root, "locales/en/Map_en.json").writeText("{}")
        File(root, "js/plugins").mkdirs()
        File(root, "js/plugins/DKTools.js").writeText("// game plugin")
        legacyHostRoundTrip(root, null)
    }

    @Test fun originalWebHostLoadsPluginAndIndexFromAsar() {
        val root = temporary.newFolder("legacy-asar-www")
        val archiveFile = temporary.newFile("legacy.asar")
        writeArchive(archiveFile, mapOf(
            "www/index.html" to "<html><body></body></html>",
            "www/locales/en/Map_en.json" to "{}",
            "www/js/plugins/DKTools.js" to ("// packaged plugin\n" + " ".repeat(600_000)),
        ))
        com.core.tyrano.AsarArchive(archiveFile).use { archive ->
            legacyHostRoundTrip(root, archive)
        }
    }

    @Test fun unrelatedWebGamesHaveNoPluginIndexEndpointOrCookie() {
        val root = temporary.newFolder("ordinary-web")
        File(root, "index.html").writeText("<html></html>")
        val server = com.core.tyrano.TyranoLocalHttpServer(root, null)
        server.start()
        try {
            val entry = connection(server.port, "index.html")
            try {
                assertEquals(200, entry.responseCode)
                entry.inputStream.use { it.readBytes() }
                assertNull(entry.getHeaderField("Set-Cookie"))
            } finally { entry.disconnect() }
            assertEquals(404, request(server.port, RpgMakerFileIndex.RESOURCE_PATH).first)
        } finally { server.stop() }
    }

    private fun legacyHostRoundTrip(root: File, archive: com.core.tyrano.AsarArchive?) {
        val marker = "\n/* legacy-auto-index-hook */"
        val server = com.core.tyrano.TyranoLocalHttpServer(root, archive, null,
            scriptAppends = mapOf("js/plugins/dktools.js" to marker.toByteArray()))
        server.start()
        try {
            assertEquals(403, request(server.port, RpgMakerFileIndex.RESOURCE_PATH).first)
            val entry = connection(server.port, "index.html")
            val cookie = try {
                assertEquals(200, entry.responseCode)
                entry.inputStream.use { it.readBytes() }
                entry.getHeaderField("Set-Cookie").substringBefore(';')
            } finally { entry.disconnect() }
            val script = request(server.port, "js/plugins/DKTools.js", cookie)
            assertEquals(200, script.first)
            assertTrue(script.second.endsWith(marker))
            val indexed = request(server.port, RpgMakerFileIndex.RESOURCE_PATH, cookie)
            assertEquals(200, indexed.first)
            val english = JSONObject(indexed.second).getJSONObject("locales").getJSONObject("en")
            assertEquals("directory", english.getJSONObject("__stats__").getString("type"))
            assertEquals("file", english.getJSONObject("Map_en.json").getJSONObject("__stats__").getString("type"))
            assertFalse(File(root, "data/Stamp.json").exists())
            File(root, "data").mkdir()
            File(root, "data/Stamp.json").writeText("original")
            assertEquals("null", request(server.port, RpgMakerFileIndex.RESOURCE_PATH, cookie).second)
        } finally { server.stop() }
    }

    private fun request(port: Int, path: String, cookie: String? = null): Pair<Int, String> {
        val connection = connection(port, path, cookie)
        return try {
            val status = connection.responseCode
            status to (if (status == 200) connection.inputStream else connection.errorStream).bufferedReader().use { it.readText() }
        } finally { connection.disconnect() }
    }

    private fun writeArchive(file: File, entries: Map<String, String>) {
        val files = JSONObject()
        var offset = 0
        val payload = entries.mapValues { it.value.toByteArray(Charsets.UTF_8) }
        payload.forEach { (name, bytes) ->
            val parts = name.split('/')
            var parent = files
            parts.dropLast(1).forEach { part ->
                if (!parent.has(part)) parent.put(part, JSONObject().put("files", JSONObject()))
                parent = parent.getJSONObject(part).getJSONObject("files")
            }
            parent.put(parts.last(), JSONObject().put("size", bytes.size).put("offset", offset.toString()))
            offset += bytes.size
        }
        val header = JSONObject().put("files", files).toString().toByteArray(Charsets.UTF_8)
        val headerSize = ((8 + header.size + 3) / 4) * 4
        DataOutputStream(file.outputStream()).use { out ->
            fun little(value: Int) { repeat(4) { out.write(value ushr (8 * it) and 255) } }
            little(4); little(headerSize); little(4); little(header.size)
            out.write(header)
            repeat(8 + headerSize - 16 - header.size) { out.write(0) }
            payload.values.forEach { out.write(it) }
        }
    }
}
