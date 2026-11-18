package com.core.rpgmaker

import com.core.tyrano.TyranoLocalHttpServer
import com.core.web.AsarChildrenIndex
import com.core.web.DkToolsCompat
import com.core.web.DkToolsResourceResolver
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DkToolsRootFallbackTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun exactRootArchiveResourceDoesNotRequireDirectoryMetadata() {
        val root = temporary.newFolder("root-resource")
        val resource = DkToolsResourceResolver.resolve(root, "js/shared.js", "www/",
            { throw AsarChildrenIndex.LimitExceeded() }, { it == "js/shared.js" })
        assertEquals("js/shared.js", resource?.archivePath)
    }

    @Test fun prefixedExactResourceWinsOverExactRootResource() {
        val root = temporary.newFolder("prefix-priority")
        val resource = DkToolsResourceResolver.resolve(root, "js/shared.js", "www/",
            { throw AssertionError("Exact resources do not need a directory tree") },
            { it == "js/shared.js" || it == "www/js/shared.js" })
        assertEquals("www/js/shared.js", resource?.archivePath)
    }

    @Test fun exactRootFallbackKeepsDiskShadowingAndPathValidation() {
        val root = temporary.newFolder("shadow")
        val archiveIsFile: (String) -> Boolean = { it == "js/shared.js" || it == "config.json" }
        val directory = File(root, "js").apply { mkdir() }
        val diskFile = File(directory, "shared.js").apply { writeText("disk overlay") }
        assertEquals(diskFile.canonicalFile, DkToolsResourceResolver.resolve(root, "js/shared.js", "www/",
            { emptyList() }, archiveIsFile)?.file)
        diskFile.delete()
        directory.delete()
        File(root, "js").writeText("file shadows archive directory")
        File(root, "config.json").mkdir()
        assertNull(DkToolsResourceResolver.resolve(root, "js/shared.js", "www/", { emptyList() }, archiveIsFile))
        assertNull(DkToolsResourceResolver.resolve(root, "config.json", "www/", { emptyList() }, archiveIsFile))
        assertNull(DkToolsResourceResolver.resolve(root, "../shared.js", "www/", { emptyList() }, { true }))
        assertNull(DkToolsResourceResolver.resolve(root, "js/\u0000shared.js", "www/", { emptyList() }, { true }))
    }

    @Test fun rootFallbackDoesNotChooseCaseOrWhitespaceAliases() {
        val root = temporary.newFolder("root-case")
        val resource = DkToolsResourceResolver.resolve(root, "js/shared.js", "www/",
            { emptyList() }, { it == "js/SHARED.js" || it == "js/shared .js" })
        assertNull(resource)
    }

    @Test fun archiveDirectoryLimitKeepsUniqueLooseDiskMatches() {
        val root = temporary.newFolder("disk-fallback")
        val file = File(root, "LoCaLeS/Message　Name .json").apply { parentFile.mkdirs(); writeText("disk") }
        val resource = DkToolsResourceResolver.resolve(root, "locales/message Name .json", "www/",
            { throw AsarChildrenIndex.LimitExceeded() }, { false })
        assertEquals(file.canonicalFile, resource?.file)
    }

    @Test fun archiveDirectoryLimitKeepsAmbiguousDiskMatchesRejected() {
        val root = temporary.newFolder("disk-ambiguous")
        File(root, "locales").mkdir()
        File(root, "locales/a　b.json").writeText("wide space")
        File(root, "locales/a b.json").writeText("normal space")
        assertNull(DkToolsResourceResolver.resolve(root, "locales/A B.json", "www/",
            { throw AsarChildrenIndex.LimitExceeded() }, { false }))
    }

    @Test fun bothHttpHostsServeArchiveRootScriptsAndKeepDiskOverlays() {
        for (legacy in listOf(false, true)) {
            val root = temporary.newFolder("host-$legacy")
            val archiveFile = temporary.newFile("host-$legacy.asar")
            writeTestAsar(archiveFile, packed = mapOf("www/index.html" to "<html></html>",
                "js/shared.js" to "root script", "js/disk.js" to "archive script"))
            File(root, "js/disk.js").apply { parentFile.mkdirs(); writeText("disk script") }
            val archive = if (!legacy) AsarArchive(archiveFile) else null
            val oldArchive = if (legacy) com.core.tyrano.AsarArchive(archiveFile) else null
            val append = mapOf(DkToolsCompat.SCRIPT_PATH to byteArrayOf())
            val server = if (!legacy) RpgMakerLocalHttpServer(root, archive, null, scriptAppends = append) else null
            val oldServer = if (legacy) TyranoLocalHttpServer(root, oldArchive, null, scriptAppends = append) else null
            server?.start(); oldServer?.start()
            val port = server?.port ?: requireNotNull(oldServer).port
            try {
                val bootstrap = connection(port, "index.html")
                val cookie = try {
                    assertEquals(200, bootstrap.responseCode)
                    bootstrap.inputStream.use { it.readBytes() }
                    bootstrap.getHeaderField("Set-Cookie").substringBefore(';')
                } finally { bootstrap.disconnect() }
                for ((path, expected) in listOf("js/shared.js" to "root script", "js/disk.js" to "disk script")) {
                    val request = connection(port, path).apply { setRequestProperty("Cookie", cookie) }
                    try {
                        assertEquals("legacy=$legacy path=$path", 200, request.responseCode)
                        assertEquals(expected, request.inputStream.bufferedReader().use { it.readText() })
                    } finally { request.disconnect() }
                }
            } finally { server?.stop(); oldServer?.stop(); archive?.close(); oldArchive?.close() }
        }
    }

    @Test fun bothHttpHostsIndexArchiveRootFallbackAndKeepOverridePriority() {
        for (legacy in listOf(false, true)) {
            val root = temporary.newFolder("index-root-$legacy")
            val archiveFile = temporary.newFile("index-root-$legacy.asar")
            writeTestAsar(archiveFile, packed = mapOf(
                "www/index.html" to "<html></html>",
                "www/locales/prefixed.json" to "1234",
                "www/locales/override.json" to "prefixed-win",
                "locales/override.json" to "root",
                "locales/en.json" to "root-lang",
                "locales/disk.json" to "root",
            ))
            File(root, "locales/disk.json").apply { parentFile.mkdirs(); writeText("disk-wins") }
            val archive = if (!legacy) AsarArchive(archiveFile) else null
            val oldArchive = if (legacy) com.core.tyrano.AsarArchive(archiveFile) else null
            val append = mapOf(DkToolsCompat.SCRIPT_PATH to byteArrayOf())
            val server = if (!legacy) RpgMakerLocalHttpServer(root, archive, null, scriptAppends = append) else null
            val oldServer = if (legacy) TyranoLocalHttpServer(root, oldArchive, null, scriptAppends = append) else null
            server?.start(); oldServer?.start()
            val port = server?.port ?: requireNotNull(oldServer).port
            try {
                val bootstrap = connection(port, "index.html")
                val cookie = try {
                    assertEquals(200, bootstrap.responseCode)
                    bootstrap.inputStream.use { it.readBytes() }
                    bootstrap.getHeaderField("Set-Cookie").substringBefore(';')
                } finally { bootstrap.disconnect() }
                val indexJson = requestIndex(port, cookie)
                val index = org.json.JSONObject(indexJson)
                assertFalse("legacy=$legacy web-root container must not leak into Stamp root", index.has("www"))
                val locales = index.getJSONObject("locales")
                assertTrue("legacy=$legacy missing root fallback en.json", locales.has("en.json"))
                assertTrue(locales.has("prefixed.json"))
                assertEquals(12L, locales.getJSONObject("override.json").getJSONObject("__stats__").getLong("size"))
                assertEquals(9L, locales.getJSONObject("disk.json").getJSONObject("__stats__").getLong("size"))
            } finally { server?.stop(); oldServer?.stop(); archive?.close(); oldArchive?.close() }
        }
    }

    @Test fun focusedIndexKeepsArchiveRootFallbackWhenGlobalArchiveExceedsNodeLimit() {
        val file = temporary.newFile("oversized-root-fallback.asar")
        writeTestAsar(file, packed = mapOf(
            "www/index.html" to "<html></html>",
            "www/img/" + "d/".repeat(250_000) + "leaf.png" to "",
            "locales/en.json" to "{}",
        ))
        AsarArchive(file).use { modern ->
            com.core.tyrano.AsarArchive(file).use { legacy ->
                for (builder in listOf(
                    RpgMakerFileIndex(temporary.newFolder(), modern, "www/"),
                    RpgMakerFileIndex(temporary.newFolder(), asarPrefix = "www/", legacyArchive = legacy),
                )) {
                    val index = org.json.JSONObject(String(builder.build(setOf("locales", "data"))))
                    assertTrue(index.getJSONObject("locales").has("en.json"))
                    assertFalse(index.has("www"))
                }
            }
        }
    }

    private fun requestIndex(port: Int, cookie: String): String {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (true) {
            val request = connection(port, RpgMakerFileIndex.RESOURCE_PATH).apply { setRequestProperty("Cookie", cookie) }
            try {
                val code = request.responseCode
                val body = (if (code < 400) request.inputStream else request.errorStream).bufferedReader().use { it.readText() }
                if (code != 202) {
                    assertEquals(200, code)
                    return body
                }
            } finally { request.disconnect() }
            check(System.nanoTime() < deadline) { "index did not become ready" }
            Thread.sleep(10)
        }
    }

    private fun connection(port: Int, path: String): HttpURLConnection =
        (URI("http", null, "127.0.0.1", port, "/$path", null, null).toURL().openConnection() as HttpURLConnection).apply {
            connectTimeout = 5_000; readTimeout = 5_000
        }
}
