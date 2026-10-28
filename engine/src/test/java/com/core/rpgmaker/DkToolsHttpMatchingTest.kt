package com.core.rpgmaker

import com.core.tyrano.TyranoLocalHttpServer
import com.core.web.DkToolsCompat
import com.core.web.DkToolsResourceResolver
import com.core.web.AsarChildrenIndex
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DkToolsHttpMatchingTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun exactArchiveResourcesDoNotBuildDirectoryMetadata() {
        val root = temporary.newFolder("exact-no-tree")
        val resource = DkToolsResourceResolver.resolve(root, "locales/en.json", "www/",
            { throw AssertionError("Exact resources must not build the archive tree") },
            { it == "www/locales/en.json" })
        assertEquals("www/locales/en.json", resource?.archivePath)
    }

    @Test fun optionalNameFallbackDoesNotTurnMissingOriginalStampIntoServerFailure() {
        val root = temporary.newFolder("over-budget-tree")
        val resource = DkToolsResourceResolver.resolve(root, "data/Stamp.json", "www/",
            { throw AsarChildrenIndex.LimitExceeded() }, { false })
        assertNull(resource)
    }

    @Test fun exactArchiveLookupKeepsDiskFileAndDirectoryShadowing() {
        val root = temporary.newFolder("shadowing")
        File(root, "locales").writeText("disk file")
        File(root, "config.json").mkdir()
        val isFile: (String) -> Boolean = { it == "locales/en.json" || it == "config.json" }
        assertNull(DkToolsResourceResolver.resolve(root, "locales/en.json", "", { emptyList() }, isFile))
        assertNull(DkToolsResourceResolver.resolve(root, "config.json", "", { emptyList() }, isFile))
    }

    @Test fun originalStampAndExactHttpResourcesWorkWithoutOptionalAsarDirectoryIndex() {
        for (legacy in listOf(false, true)) {
            val root = temporary.newFolder("exact-http-$legacy")
            val file = temporary.newFile("exact-http-$legacy.asar")
            // This flat key exceeds the directory-tree node budget if a request builds it.
            val hugeKey = "unrelated/" + "d/".repeat(250_001) + "unused.txt"
            writeTestAsar(file, packed = mapOf("www/index.html" to "<html>exact</html>",
                "www/data/Stamp.json" to "{\"original\":true}",
                "www/js/plugins/DKTools.js" to "// plugin", hugeKey to ""))
            val modernArchive = if (!legacy) AsarArchive(file) else null
            val oldArchive = if (legacy) com.core.tyrano.AsarArchive(file) else null
            val modern = if (!legacy) RpgMakerLocalHttpServer(root, modernArchive, null,
                scriptAppends = mapOf(DkToolsCompat.SCRIPT_PATH to "\n// hook".toByteArray())) else null
            val old = if (legacy) TyranoLocalHttpServer(root, oldArchive, null,
                scriptAppends = mapOf(DkToolsCompat.SCRIPT_PATH to "\n// hook".toByteArray())) else null
            modern?.start(); old?.start()
            val port = modern?.port ?: requireNotNull(old).port
            try {
                val cookie = bootstrap(port)
                assertEquals("// plugin\n// hook", request(port, "js/plugins/DKTools.js", cookie).second)
                assertEquals("{\"original\":true}", request(port, "data/Stamp.json", cookie).second)
                assertEquals("null", request(port, RpgMakerFileIndex.RESOURCE_PATH, cookie).second)
            } finally { modern?.stop(); old?.stop(); modernArchive?.close(); oldArchive?.close() }
        }
    }

    @Test fun bothHostsResolveGeneratedIndexNamesOnDiskAndInFlatArchives() {
        for (legacy in listOf(false, true)) for (packaged in listOf(false, true)) {
            val root = temporary.newFolder("names-$legacy-$packaged")
            val content = linkedMapOf(
                "index.html" to "<html></html>",
                "LoCaLeS/Message　Name .json" to "space-class",
                "LoCaLeS/Another .json" to "trailing-space",
                "LoCaLeS/exact.json" to "exact",
                "LoCaLeS/ΟΣ.json" to "greek",
                "LoCaLeS/I.json" to "turkish",
            )
            val archiveFile = if (packaged) temporary.newFile("names-$legacy.asar").also {
                writeTestAsar(it, packed = content)
            } else null
            if (!packaged) content.forEach { (name, value) ->
                File(root, name).apply { parentFile.mkdirs(); writeText(value) }
            }
            val modernArchive = if (!legacy && archiveFile != null) AsarArchive(archiveFile) else null
            val oldArchive = if (legacy && archiveFile != null) com.core.tyrano.AsarArchive(archiveFile) else null
            val modern = if (!legacy) RpgMakerLocalHttpServer(root, modernArchive, null,
                scriptAppends = mapOf(DkToolsCompat.SCRIPT_PATH to byteArrayOf())) else null
            val old = if (legacy) TyranoLocalHttpServer(root, oldArchive, null,
                scriptAppends = mapOf(DkToolsCompat.SCRIPT_PATH to byteArrayOf())) else null
            modern?.start(); old?.start()
            val port = modern?.port ?: requireNotNull(old).port
            try {
                val cookie = bootstrap(port)
                assertEquals("space-class", request(port, "locales/message Name .json", cookie).second)
                assertEquals("trailing-space", request(port, "locales/another.json", cookie).second)
                assertEquals("exact", request(port, "LoCaLeS/exact.json", cookie).second)
                assertEquals("greek", request(port, "LoCaLeS/ος.json", cookie).second)
                assertEquals("turkish", request(port, "LoCaLeS/ı.json", cookie).second)
                assertEquals(404, request(port, "../outside.json", cookie).first)
            } finally { modern?.stop(); old?.stop(); modernArchive?.close(); oldArchive?.close() }
        }
    }

    @Test fun ambiguousArchiveNamesDoNotSelectAnArbitraryResource() {
        for (legacy in listOf(false, true)) {
            val root = temporary.newFolder("ambiguous-$legacy")
            val file = temporary.newFile("ambiguous-$legacy.asar")
            writeTestAsar(file, packed = mapOf("index.html" to "<html></html>",
                "locales/EN.json" to "UPPER", "locales/en.json" to "lower"))
            val modernArchive = if (!legacy) AsarArchive(file) else null
            val oldArchive = if (legacy) com.core.tyrano.AsarArchive(file) else null
            val modern = if (!legacy) RpgMakerLocalHttpServer(root, modernArchive, null,
                scriptAppends = mapOf(DkToolsCompat.SCRIPT_PATH to byteArrayOf())) else null
            val old = if (legacy) TyranoLocalHttpServer(root, oldArchive, null,
                scriptAppends = mapOf(DkToolsCompat.SCRIPT_PATH to byteArrayOf())) else null
            modern?.start(); old?.start()
            val port = modern?.port ?: requireNotNull(old).port
            try {
                val cookie = bootstrap(port)
                assertEquals(404, request(port, "locales/En.json", cookie).first)
                assertEquals("UPPER", request(port, "locales/EN.json", cookie).second)
                assertEquals("lower", request(port, "locales/en.json", cookie).second)
            } finally { modern?.stop(); old?.stop(); modernArchive?.close(); oldArchive?.close() }
        }
    }

    @Test fun twoSessionsKeepTheirCookiesInOneHostCookieJar() {
        for (legacy in listOf(false, true)) {
            val root = temporary.newFolder("cookies-$legacy")
            File(root, "index.html").writeText("<html></html>")
            val appends = mapOf(DkToolsCompat.SCRIPT_PATH to byteArrayOf())
            val modern = if (!legacy) List(2) { RpgMakerLocalHttpServer(root, null, scriptAppends = appends) } else emptyList()
            val old = if (legacy) List(2) { TyranoLocalHttpServer(root, null, scriptAppends = appends) } else emptyList()
            modern.forEach { it.start() }; old.forEach { it.start() }
            val ports = modern.map { it.port } + old.map { it.port }
            try {
                // Browsers scope host-only cookies by name/path, without the TCP port.
                val jar = linkedMapOf<String, String>()
                ports.forEach { port -> val pair = bootstrap(port).split('=', limit = 2); jar[pair[0]] = pair[1] }
                val cookie = jar.entries.joinToString("; ") { "${it.key}=${it.value}" }
                ports.forEach { assertEquals(200, request(it, RpgMakerFileIndex.RESOURCE_PATH, cookie).first) }
                assertEquals(2, jar.size)
            } finally { modern.forEach { it.stop() }; old.forEach { it.stop() } }
        }
    }

    private fun connection(port: Int, path: String, cookie: String? = null): HttpURLConnection =
        (URI("http", null, "127.0.0.1", port, "/$path", null, null).toURL().openConnection() as HttpURLConnection).apply {
            connectTimeout = 5_000; readTimeout = 5_000
            if (cookie != null) setRequestProperty("Cookie", cookie)
        }

    private fun bootstrap(port: Int): String {
        val connection = connection(port, "index.html")
        return try {
            assertEquals(200, connection.responseCode)
            connection.inputStream.use { it.readBytes() }
            connection.getHeaderField("Set-Cookie").substringBefore(';')
        } finally { connection.disconnect() }
    }

    private fun request(port: Int, path: String, cookie: String): Pair<Int, String> {
        val connection = connection(port, path, cookie)
        return try {
            val status = connection.responseCode
            status to (if (status == 200) connection.inputStream else connection.errorStream).bufferedReader().use { it.readText() }
        } finally { connection.disconnect() }
    }
}
