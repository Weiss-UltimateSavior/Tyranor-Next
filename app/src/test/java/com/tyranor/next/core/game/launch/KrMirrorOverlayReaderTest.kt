package com.tyranor.next.core.game.launch

import bridge.KrSafMirror
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class KrMirrorOverlayReaderTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun listedBootstrapSourceMetadataReachesTheProductionReaderWithoutReadingPlaceholders() {
        val root = temporary.newFolder()
        val payloads = mapOf(
            "patch.tjs" to "Plugins.link(\"game-plugin.dll\");".toByteArray(),
            "ds.ini" to "Language=english\nOtherSetting=keep\n".toByteArray(),
        )
        val descriptors = payloads.mapValues { (name, bytes) ->
            val placeholder = File(root, name).apply { createNewFile() }
            requireNotNull(KrSafMirror.bootstrapFromListedEntry(placeholder, "content://source/$name", bytes.size.toLong(), false))
        }
        val prepared = KrSafMirror.Prepared(root, File(root, "unused.idx"), payloads.size, descriptors)
        val opened = mutableListOf<String>()
        val reader = KrMirrorOverlayReader.fromPrepared(prepared) { uri ->
            opened.add(uri)
            val name = uri.removePrefix("content://source/")
            requireNotNull(payloads[name]) { "Must read the listed source, not its empty placeholder: $uri" }.inputStream()
        }
        payloads.forEach { (name, bytes) ->
            val result = reader.read(name, 1024)
            assertTrue(result is KrMirrorOverlayReader.Result.Content)
            val content = (result as KrMirrorOverlayReader.Result.Content).bytes
            assertArrayEquals(bytes, content)
            if (name == "patch.tjs") {
                // The production AUTO-overlay decision must see real user content,
                // not synthesize a basic patch over an empty local placeholder.
                assertTrue(EngineLauncher.hasMeaningfulPatchContent(content))
            }
            assertEquals(0L, File(root, name).length())
            assertEquals(false, descriptors.getValue(name).localOverride)
        }
        assertEquals(payloads.keys.map { "content://source/$it" }, opened)
        assertEquals(false, prepared.indexFile.exists())
    }

    @Test
    fun intentionallyEmptyLocalBootstrapDoesNotFallBackToTheListedSource() {
        val root = temporary.newFolder()
        val local = File(root, "patch.tjs").apply { createNewFile() }
        val descriptor = requireNotNull(KrSafMirror.bootstrapFromListedEntry(local, "content://source/patch.tjs", 128L, true))
        val prepared = KrSafMirror.Prepared(root, File(root, "unused.idx"), 1, mapOf("patch.tjs" to descriptor))
        val reader = KrMirrorOverlayReader.fromPrepared(prepared) { uri ->
            assertTrue(uri.startsWith("file:"))
            File(java.net.URI(uri)).inputStream()
        }
        val result = reader.read("patch.tjs", 1024)
        assertArrayEquals(byteArrayOf(), (result as KrMirrorOverlayReader.Result.Content).bytes)
    }

    @Test
    fun nonemptyLocalBootstrapRemainsPreferredOverItsListedSource() {
        val root = temporary.newFolder()
        val local = File(root, "ds.ini").apply { writeText("Language=local\nKeep=1") }
        val descriptor = requireNotNull(KrSafMirror.bootstrapFromListedEntry(local, "content://source/ds.ini", 128L, false))
        val prepared = KrSafMirror.Prepared(root, File(root, "unused.idx"), 1, mapOf("ds.ini" to descriptor))
        val reader = KrMirrorOverlayReader.fromPrepared(prepared) { uri ->
            assertTrue(uri.startsWith("file:"))
            File(java.net.URI(uri)).inputStream()
        }
        val result = reader.read("ds.ini", 1024)
        assertArrayEquals(local.readBytes(), (result as KrMirrorOverlayReader.Result.Content).bytes)
    }

    @Test fun emptySourceIsSuccessfulContent() {
        val reader = reader(size = 0) { ByteArrayInputStream(byteArrayOf()) }
        val result = reader.read("patch.tjs", 4)
        assertTrue(result is KrMirrorOverlayReader.Result.Content)
        assertArrayEquals(byteArrayOf(), (result as KrMirrorOverlayReader.Result.Content).bytes)
    }

    @Test fun sizeMismatchIsUnavailableEvenForZeroMetadata() {
        // Declared (0) and actual (2) sizes disagree — the source changed after prepare;
        // content cannot be verified, so the overlay must not shadow it (R5-#5/#6).
        assertEquals(KrMirrorOverlayReader.Result.Unavailable, reader(size = 0) {
            ByteArrayInputStream(byteArrayOf(1, 2))
        }.read("patch.tjs", 4))
    }

    @Test fun confirmedAbsenceDoesNotOpenSource() {
        val reader = KrMirrorOverlayReader(mapOf("patch.tjs" to KrMirrorOverlayReader.Source(null, confirmedAbsent = true))) {
            error("Absent source must not be opened")
        }
        assertEquals(KrMirrorOverlayReader.Result.Absent, reader.read("patch.tjs", 4))
    }

    @Test fun missingIndexIsUnavailableRatherThanAbsent() {
        val reader = KrMirrorOverlayReader(emptyMap()) { error("No indexed source") }
        assertEquals(KrMirrorOverlayReader.Result.Unavailable, reader.read("patch.tjs", 4))
    }

    @Test fun deniedNullAndFailedStreamsAreUnavailable() {
        assertEquals(KrMirrorOverlayReader.Result.Unavailable, reader { throw SecurityException() }.read("patch.tjs", 4))
        assertEquals(KrMirrorOverlayReader.Result.Unavailable, reader { null }.read("patch.tjs", 4))
        assertEquals(KrMirrorOverlayReader.Result.Unavailable, reader { throw IOException() }.read("patch.tjs", 4))
    }

    @Test fun declaredAndActualLimitsBothApplyAndCloseStreams() {
        assertEquals(KrMirrorOverlayReader.Result.Unavailable, reader(size = 5) { error("Oversized source must not open") }.read("patch.tjs", 4))
        var closed = false
        val reader = reader { object : ByteArrayInputStream(ByteArray(5)) {
            override fun close() { closed = true; super.close() }
        } }
        assertEquals(KrMirrorOverlayReader.Result.Unavailable, reader.read("patch.tjs", 4))
        assertTrue(closed)
    }

    @Test(expected = CancellationException::class)
    fun cancellationPropagates() {
        reader { throw CancellationException() }.read("patch.tjs", 4)
    }

    @Test fun shortReadOfKnownSizeIsUnavailableInsteadOfShadowing() {
        // Provider EOF (or source shrank after prepare): a short/empty read must never
        // pass as content — an empty short read would synthesize the basic overlay over
        // the real patch.tjs (#129's plugin-loss path).
        assertEquals(KrMirrorOverlayReader.Result.Unavailable, reader(size = 10) {
            ByteArrayInputStream("part".toByteArray())
        }.read("patch.tjs", 16))
        assertEquals(KrMirrorOverlayReader.Result.Unavailable, reader(size = 10) {
            ByteArrayInputStream(byteArrayOf())
        }.read("patch.tjs", 16))
    }

    @Test fun exactSizeReadIsContent() {
        val result = reader(size = 4) { ByteArrayInputStream("abcd".toByteArray()) }.read("patch.tjs", 16)
        assertArrayEquals("abcd".toByteArray(), (result as KrMirrorOverlayReader.Result.Content).bytes)
    }

    private fun reader(size: Long = -1, open: (String) -> java.io.InputStream?) =
        KrMirrorOverlayReader(mapOf("patch.tjs" to KrMirrorOverlayReader.Source("source", size)), open)
}
