package com.core.rpgmaker

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AsarUnpackedStreamTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun streamReadsExternalScriptRatherThanPackedPayload() {
        val file = temporary.newFile("app.asar")
        writeTestAsar(file, unpacked = mapOf("script.js" to "RIGHT"))
        AsarArchive(file).use { archive ->
            val stream = requireNotNull(archive.openStream("script.js"))
            assertEquals(5L, stream.second)
            assertEquals("RIGHT", stream.first.use { it.readBytes().toString(Charsets.UTF_8) })
        }
    }

    @Test fun missingExternalFileDoesNotReturnUnrelatedPackedBytes() {
        val file = temporary.newFile("app.asar")
        writeTestAsar(file, unpacked = mapOf("missing.js" to "RIGHT"))
        assertTrue(File(file.path + ".unpacked/missing.js").delete())
        AsarArchive(file).use { archive ->
            val stream = archive.openStream("missing.js")
            stream?.first?.close()
            assertNull(stream)
        }
    }

    @Test fun traversalOutsideUnpackedRootIsRejected() {
        val file = temporary.newFile("app.asar")
        writeTestAsar(file, unpacked = mapOf("../escape.js" to "RIGHT"))
        File(file.path + ".unpacked").mkdirs()
        File(file.parentFile, "escape.js").writeText("OUTSIDE")
        AsarArchive(file).use { archive ->
            val stream = archive.openStream("../escape.js")
            stream?.first?.close()
            assertNull(stream)
        }
    }

    @Test fun actualFileLengthWinsOverHeaderLength() {
        val file = temporary.newFile("app.asar")
        writeTestAsar(file, unpacked = mapOf("script.js" to "RIGHT"), declaredSizes = mapOf("script.js" to 1))
        AsarArchive(file).use { archive ->
            val stream = requireNotNull(archive.openStream("script.js"))
            assertEquals(5L, stream.second)
            assertEquals("RIGHT", stream.first.use { it.readBytes().toString(Charsets.UTF_8) })
        }
    }

    @Test fun httpAppendsHookToTheExternalPluginContent() {
        val root = temporary.newFolder("www")
        val file = temporary.newFile("app.asar")
        writeTestAsar(file, packed = mapOf("www/index.html" to "<html></html>"),
            unpacked = mapOf("www/js/plugins/DKTools.js" to "RIGHT"))
        AsarArchive(file).use { archive ->
            val server = RpgMakerLocalHttpServer(root, archive, null,
                scriptAppends = mapOf("js/plugins/dktools.js" to "\nHOOK".toByteArray()))
            server.start()
            try {
                val entry = connect(server.port, "index.html")
                val cookie = try {
                    entry.inputStream.use { it.readBytes() }
                    entry.getHeaderField("Set-Cookie").substringBefore(';')
                } finally { entry.disconnect() }
                val response = connect(server.port, "js/plugins/DKTools.js", cookie)
                try {
                    assertEquals(200, response.responseCode)
                    assertEquals("RIGHT\nHOOK", response.inputStream.bufferedReader().use { it.readText() })
                } finally { response.disconnect() }
            } finally { server.stop() }
        }
    }

    @Test fun smallDeclaredSizeCannotBypassHttpBufferingLimit() {
        val root = temporary.newFolder("large-www")
        val file = temporary.newFile("large.asar")
        val content = "X".repeat(8 * 1024 * 1024 + 1)
        val key = "www/js/plugins/DKTools.js"
        writeTestAsar(file, packed = mapOf("www/index.html" to "<html></html>"),
            unpacked = mapOf(key to content), declaredSizes = mapOf(key to 1))
        AsarArchive(file).use { archive ->
            val server = RpgMakerLocalHttpServer(root, archive, null,
                scriptAppends = mapOf("js/plugins/dktools.js" to "\nHOOK".toByteArray()))
            server.start()
            try {
                val entry = connect(server.port, "index.html")
                val cookie = try {
                    entry.inputStream.use { it.readBytes() }
                    entry.getHeaderField("Set-Cookie").substringBefore(';')
                } finally { entry.disconnect() }
                val response = connect(server.port, "js/plugins/DKTools.js", cookie)
                try {
                    assertEquals(content.length.toLong(), response.contentLengthLong)
                    assertEquals(content, response.inputStream.bufferedReader().use { it.readText() })
                } finally { response.disconnect() }
            } finally { server.stop() }
        }
    }

    private fun connect(port: Int, path: String, cookie: String? = null) =
        (URL("http://127.0.0.1:$port/$path").openConnection() as HttpURLConnection).apply {
            connectTimeout = 5_000
            readTimeout = 5_000
            if (cookie != null) setRequestProperty("Cookie", cookie)
        }
}
