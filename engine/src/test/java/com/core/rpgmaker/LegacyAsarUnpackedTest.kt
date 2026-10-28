package com.core.rpgmaker

import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LegacyAsarUnpackedTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun externalReadAndRangeUseActualLengthInsteadOfPackedOffset() {
        val file = temporary.newFile("app.asar")
        writeTestAsar(file, unpacked = mapOf("script.js" to "RIGHT"), declaredSizes = mapOf("script.js" to 1))
        com.core.tyrano.AsarArchive(file).use { archive ->
            assertEquals(5L, archive.fileSize("script.js"))
            assertEquals("RIGHT", archive.read("script.js")?.toString(Charsets.UTF_8))
            val out = ByteArrayOutputStream()
            assertTrue(archive.writeRange("script.js", 1, 3, out))
            assertEquals("IGH", out.toString("UTF-8"))
            assertFalse(archive.writeRange("script.js", Long.MAX_VALUE, 2, out))
            assertFalse(archive.writeRange("script.js", 3, Long.MAX_VALUE, out))
            assertFalse(archive.writeRange("script.js", 6, 0, out))
        }
    }

    @Test fun missingExternalFileHasNeitherContentNorSize() {
        val file = temporary.newFile("missing.asar")
        writeTestAsar(file, unpacked = mapOf("missing.js" to "RIGHT"))
        assertTrue(File(file.path + ".unpacked/missing.js").delete())
        com.core.tyrano.AsarArchive(file).use { archive ->
            assertNull(archive.fileSize("missing.js"))
            assertNull(archive.read("missing.js"))
            assertFalse(archive.writeRange("missing.js", 0, 0, ByteArrayOutputStream()))
        }
    }

    @Test fun externalTraversalCannotReadSiblingFiles() {
        val file = temporary.newFile("traversal.asar")
        writeTestAsar(file, unpacked = mapOf("../private.js" to "SECRET"))
        File(file.path + ".unpacked").mkdirs()
        File(file.parentFile, "private.js").writeText("SECRET")
        com.core.tyrano.AsarArchive(file).use { archive ->
            assertNull(archive.fileSize("../private.js"))
            assertNull(archive.read("../private.js"))
            assertFalse(archive.writeRange("../private.js", 0, 1, ByteArrayOutputStream()))
        }
    }

    @Test fun externalContentCanExceedPackedPayloadAndDeclaredSize() {
        val file = temporary.newFile("long.asar")
        writeTestAsar(file, packed = mapOf("other.txt" to "X"),
            unpacked = mapOf("script.js" to "RIGHT"), declaredSizes = mapOf("script.js" to 1_000_000))
        com.core.tyrano.AsarArchive(file).use { archive ->
            assertEquals(5L, archive.fileSize("script.js"))
            assertEquals("RIGHT", archive.read("script.js")?.toString(Charsets.UTF_8))
        }
    }

    @Test fun legacyHttpAppendsExternalPluginAndStreamsExternalRanges() {
        val root = temporary.newFolder("game")
        val file = temporary.newFile("http.asar")
        writeTestAsar(file, packed = mapOf("www/index.html" to "<html></html>"),
            unpacked = mapOf("www/js/plugins/DKTools.js" to "RIGHT", "www/movie.webm" to "0123456789"),
            declaredSizes = mapOf("www/movie.webm" to 1))
        com.core.tyrano.AsarArchive(file).use { archive ->
            val server = com.core.tyrano.TyranoLocalHttpServer(root, archive, null,
                scriptAppends = mapOf("js/plugins/dktools.js" to "\nHOOK".toByteArray()))
            server.start()
            try {
                val plugin = connect(server.port, "js/plugins/DKTools.js")
                try {
                    assertEquals(200, plugin.responseCode)
                    assertEquals("RIGHT\nHOOK", plugin.inputStream.bufferedReader().use { it.readText() })
                } finally { plugin.disconnect() }
                val movie = connect(server.port, "movie.webm").apply { setRequestProperty("Range", "bytes=3-6") }
                try {
                    assertEquals(206, movie.responseCode)
                    assertEquals("bytes 3-6/10", movie.getHeaderField("Content-Range"))
                    assertEquals(4L, movie.contentLengthLong)
                    assertEquals("3456", movie.inputStream.bufferedReader().use { it.readText() })
                } finally { movie.disconnect() }
            } finally { server.stop() }
        }
    }

    @Test fun modernEntrySizeUsesExternalLengthAndRejectsMissingFile() {
        val file = temporary.newFile("modern.asar")
        writeTestAsar(file, unpacked = mapOf("script.js" to "RIGHT"), declaredSizes = mapOf("script.js" to 1))
        AsarArchive(file).use { archive ->
            assertEquals(5L, archive.entrySize("script.js"))
            assertTrue(File(file.path + ".unpacked/script.js").delete())
            assertNull(archive.entrySize("script.js"))
        }
    }

    private fun connect(port: Int, path: String) =
        (URL("http://127.0.0.1:$port/$path").openConnection() as HttpURLConnection).apply {
            connectTimeout = 5_000
            readTimeout = 5_000
        }
}
