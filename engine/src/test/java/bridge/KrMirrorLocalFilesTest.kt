package bridge

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeNoException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class KrMirrorLocalFilesTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `savedata paths bypass resource mutation state but nested asset names do not`() {
        val root = temporary.newFolder("resource-classification")
        val state = KrMirrorLocalFiles(root)
        assertEquals(null, state.resourceFileForPath(File(root, "savedata/slot.ksd").path))
        assertEquals(null, state.resourceFileForPath(File(root, "SAVEDATA").path))
        assertEquals(null, state.resourceFileForPath(temporary.newFolder("external-save").path))
        assertEquals(File(root, "data/savedata/asset"), state.resourceFileForPath(File(root, "data/savedata/asset").path))
        assertEquals(File(root, "asset"), state.resourceFileForPath(File(root, "asset").path))
        assertFalse(File(root, ".tyranor-mirror-state").exists())
    }

    @Test
    fun `resource lock failure never executes unprotected filesystem mutation`() {
        val root = temporary.newFolder("lock-failure")
        val file = File(root, "asset").apply { writeText("keep") }
        File(root, ".tyranor-mirror-state").writeText("blocks resource lock directory")
        var syscallExecuted = false
        expectIOException {
            KrMirrorLocalFiles(root).withFileLocks(listOf(file)) {
                syscallExecuted = true
                Files.delete(file.toPath())
            }
        }
        assertFalse(syscallExecuted)
        assertEquals("keep", file.readText())
    }

    @Test
    fun `copy temporary stays in private state and same stripe retry cleans orphan`() {
        val root = temporary.newFolder("temporary-state")
        val target = File(root, "asset").apply { createNewFile() }
        val state = KrMirrorLocalFiles(root)
        var temporaryFile: File? = null
        state.copyUp(target, 4) {
            temporaryFile = File(root, ".tyranor-mirror-state/tmp").walkTopDown()
                .first { it.isFile && it.name.startsWith("copy-") }
            assertFalse(root.listFiles().orEmpty().any { it.name.startsWith("copy-") })
            ByteArrayInputStream("data".toByteArray())
        }
        val completed = requireNotNull(temporaryFile)
        assertFalse(completed.exists())
        assertTrue(target.delete())
        assertTrue(state.clearMissingOverride(target))
        assertTrue(target.createNewFile())
        val orphan = File(completed.parentFile, "copy-crashed.tmp").apply { writeText("partial") }
        val legacy = File(root, ".mirror-copy-legacy.tmp").apply { writeText("retain conservatively") }

        state.copyUp(target, 4) { ByteArrayInputStream("next".toByteArray()) }

        assertFalse(orphan.exists())
        assertTrue(legacy.exists())
        assertEquals("next", target.readText())
    }

    @Test
    fun `same stripe nested copy cannot clean a live outer temporary`() {
        val root = temporary.newFolder("active-temporary")
        val outer = File(root, "outer").apply { createNewFile() }
        val stripeBits = outer.name.hashCode() and 63
        val name = (0..1000).map { "inner-$it" }.first { (it.hashCode() and 63) == stripeBits }
        val inner = File(root, name).apply { createNewFile() }
        val state = KrMirrorLocalFiles(root)

        state.copyUp(outer, 5) {
            object : ByteArrayInputStream("outer".toByteArray()) {
                var copiedInner = false
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (!copiedInner) {
                        copiedInner = true
                        state.copyUp(inner, 5) { ByteArrayInputStream("inner".toByteArray()) }
                    }
                    return super.read(buffer, offset, length)
                }
            }
        }

        assertEquals("outer", outer.readText())
        assertEquals("inner", inner.readText())
        assertFalse(File(root, ".tyranor-mirror-state/tmp").walkTopDown().any { it.isFile })
    }

    @Test
    fun `copy up publishes complete source before writable local access`() {
        val root = temporary.newFolder("mirror")
        val target = File(root, "plugin.dll").apply { createNewFile() }
        val source = ByteArray(130_007) { (it % 251).toByte() }
        val local = KrMirrorLocalFiles(root)

        local.copyUp(target, source.size.toLong()) { ByteArrayInputStream(source) }

        assertArrayEquals(source, target.readBytes())
        assertTrue(local.isOverridden(target))
        target.writeText("local edit")
        local.copyUp(target, source.size.toLong()) { fail("Must not reread source"); ByteArrayInputStream(source) }
        assertEquals("local edit", target.readText())
    }

    @Test
    fun `truncated local override survives new helper and root relocation`() {
        val root = temporary.newFolder("original")
        val target = File(root, "config.ksd").apply { createNewFile() }
        KrMirrorLocalFiles(root).copyUp(target, 4) { ByteArrayInputStream("data".toByteArray()) }
        target.writeBytes(byteArrayOf())
        val restoredRoot = File(temporary.root, "restored")
        assertTrue(root.copyRecursively(restoredRoot))
        val restoredTarget = File(restoredRoot, target.name)
        val restored = KrMirrorLocalFiles(restoredRoot)

        assertTrue(restored.isOverridden(restoredTarget))
        restored.copyUp(restoredTarget, 4) { fail("Restored empty override must remain empty"); ByteArrayInputStream(byteArrayOf()) }
        assertEquals(0L, restoredTarget.length())
    }

    @Test
    fun `folded index key restores real uppercase legacy root without widening path boundary`() {
        val root = temporary.newFolder("OldTitle-Key")
        val target = File(root, "config.ksd").apply { createNewFile() }
        val local = KrMirrorLocalFiles(root)
        local.markOverridden(target)
        val key = target.absolutePath.lowercase(java.util.Locale.ROOT)
        val resolved = local.fileForIndexKey(key)
        assertEquals(target, resolved)
        assertTrue(local.isOverridden(requireNotNull(resolved)))
        assertEquals(null, local.fileForIndexKey(root.absolutePath + "-other/config.ksd"))
        assertEquals(null, local.fileForIndexKey(root.absolutePath + "/../outside"))
        expectIOException { local.isOverridden(temporary.newFile("outside-marker")) }
    }

    @Test
    fun `root cleanup also removes local ownership state`() {
        val root = temporary.newFolder("recreated")
        val target = File(root, "config.ksd").apply { createNewFile() }
        KrMirrorLocalFiles(root).markOverridden(target)
        assertTrue(root.deleteRecursively())
        assertTrue(root.mkdirs())
        target.createNewFile()
        assertFalse(KrMirrorLocalFiles(root).isOverridden(target))
    }

    @Test
    fun `grown source size publishes complete actual content and reports mismatch`() {
        // A source that grew past its indexed size is still fully copied: publish the
        // complete actual bytes and report the mismatch as a diagnostic.
        val root = temporary.newFolder("mismatch")
        val local = KrMirrorLocalFiles(root)
        val target = File(root, "long").apply { createNewFile() }
        var reported: Pair<Long, Long>? = null
        local.copyUp(target, 2L, { before, after -> reported = before to after }) {
            ByteArrayInputStream("data".toByteArray())
        }
        assertEquals("data", target.readText())
        assertEquals(2L to 4L, reported)
        assertTrue(local.isOverridden(target))
    }

    @Test
    fun `short provider read fails the copy up without publishing truncated content`() {
        // R10 #3: a provider stream that ends early would otherwise be published as a
        // permanent override — the ownership marker stops later copy-ups from ever
        // re-reading the source, so a short read must fail like the overlay reader.
        val root = temporary.newFolder("short-read")
        val target = File(root, "asset").apply { createNewFile() }
        val local = KrMirrorLocalFiles(root)
        expectIOException {
            local.copyUp(target, 9L) { ByteArrayInputStream("data".toByteArray()) }
        }
        assertEquals(0L, target.length())
        assertFalse(local.isOverridden(target))
    }

    @Test
    fun `stale ownership marker with a missing file re-copies the source instead of returning it`() {
        // Audit of fe3d057, FM1: copyUp's ownership early-return used to hand back the
        // MISSING file when only the marker existed, and the caller's
        // RandomAccessFile("rw") would silently create empty content. The stale marker
        // must fall through to a fresh copy from the source instead.
        val root = temporary.newFolder("stale-marker-copy")
        val target = File(root, "asset")
        val local = KrMirrorLocalFiles(root)
        local.markOverridden(target)
        check(!target.exists())
        val published = local.copyUp(target, 4L) { ByteArrayInputStream("data".toByteArray()) }
        assertEquals(target, published)
        assertEquals("data", target.readText())
        assertTrue(local.isOverridden(target))
    }

    @Test
    fun `known oversized source is refused before source IO`() {
        val root = temporary.newFolder("known-limit")
        val target = File(root, "asset").apply { createNewFile() }
        val local = KrMirrorLocalFiles(root)
        expectIOException {
            local.copyUp(target, KrMirrorLocalFiles.MAX_COPY_BYTES + 1) {
                fail("Oversized source must not be opened")
                ByteArrayInputStream(byteArrayOf())
            }
        }
        assertEquals(0L, target.length())
        assertFalse(local.isOverridden(target))
    }

    @Test
    fun `unknown oversized source leaves no partial copy or ownership marker`() {
        val root = temporary.newFolder("unknown-limit")
        val target = File(root, "asset").apply { createNewFile() }
        val local = KrMirrorLocalFiles(root)
        expectIOException {
            local.copyUp(target, -1) {
                object : InputStream() {
                    var remaining = KrMirrorLocalFiles.MAX_COPY_BYTES + 1
                    override fun read(): Int = if (remaining-- > 0) 0 else -1
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        if (remaining <= 0) return -1
                        val count = minOf(length.toLong(), remaining).toInt()
                        remaining -= count
                        return count
                    }
                }
            }
        }
        assertEquals(0L, target.length())
        assertFalse(local.isOverridden(target))
        assertFalse(File(root, ".tyranor-mirror-state/tmp").walkTopDown().any { it.isFile })
    }

    @Test
    fun `index alias resolves through symlink root and rejects traversal`() {
        val root = temporary.newFolder("real-root")
        val target = File(root, "config.ksd").apply { createNewFile() }
        val alias = File(temporary.root, "alias-root")
        try {
            Files.createSymbolicLink(alias.toPath(), root.toPath())
        } catch (error: Exception) {
            assumeNoException("Symbolic links unavailable on this host", error)
        }
        val local = KrMirrorLocalFiles(alias)
        local.markOverridden(target)
        val aliasKey = File(alias, target.name).absolutePath.lowercase(java.util.Locale.ROOT)
        val realKey = target.absolutePath.lowercase(java.util.Locale.ROOT)
        assertEquals(target.canonicalFile, local.fileForIndexKey(aliasKey)?.canonicalFile)
        assertEquals(target.canonicalFile, local.fileForIndexKey(realKey)?.canonicalFile)
        // snapshotOverrideKeys returns root-relative keys; both spellings resolve to the
        // same relative remainder (the real root here is reached through a second
        // symlink, /var → /private/var, so only the canonical fallback finds it).
        assertEquals(setOf("config.ksd"), local.snapshotOverrideKeys())
        assertEquals(null, local.fileForIndexKey(alias.absolutePath + "/../outside"))
    }

    @Test
    fun `missing target clears ownership but live empty override remains`() {
        val root = temporary.newFolder("deleted-override")
        val target = File(root, "asset").apply { createNewFile() }
        val local = KrMirrorLocalFiles(root)
        local.markOverridden(target)
        assertFalse(local.clearMissingOverride(target))
        assertTrue(local.isOverridden(target))
        assertTrue(target.delete())
        assertTrue(local.clearMissingOverride(target))
        target.createNewFile()
        assertFalse(local.isOverridden(target))
    }

    @Test
    fun `legacy marker snapshot preserves empty overrides with candidate index keys`() {
        val root = temporary.newFolder("legacy-marker")
        val target = File(root, "asset").apply { createNewFile() }
        val local = KrMirrorLocalFiles(root)
        val marker = local.ownershipMarker(target)
        marker.parentFile!!.mkdirs()
        marker.writeBytes(byteArrayOf(1))
        val key = target.absolutePath.lowercase(java.util.Locale.ROOT)
        assertTrue(local.isOverridden(target))
        // Relative-key semantics: the legacy (1-byte) marker resolves through the
        // candidate index key to "asset".
        assertEquals(setOf("asset"), local.snapshotOverrideKeys(listOf(key)))
    }

    @Test
    fun `source read exception never publishes partial local contents`() {
        val root = temporary.newFolder("read-failure")
        val target = File(root, "asset").apply { createNewFile() }
        val local = KrMirrorLocalFiles(root)
        expectIOException {
            local.copyUp(target, -1) {
                object : InputStream() {
                    override fun read(): Int = throw IOException("provider disconnected")
                }
            }
        }
        assertEquals(0L, target.length())
        assertFalse(local.isOverridden(target))
    }

    @Test
    fun `marker publication failure rolls back copied placeholder`() {
        val root = temporary.newFolder("marker-failure")
        val target = File(root, "asset").apply { createNewFile() }
        val state = File(root, ".tyranor-mirror-state").apply { mkdirs() }
        File(state, "local-overrides").writeText("blocks marker directory")
        val local = KrMirrorLocalFiles(root)

        expectIOException { local.copyUp(target, 4) { ByteArrayInputStream("data".toByteArray()) } }

        assertEquals(0L, target.length())
        assertFalse(local.isOverridden(target))
    }

    @Test
    fun `copy up refuses paths outside selected mirror root`() {
        val root = temporary.newFolder("contained")
        val outside = temporary.newFile("outside")
        expectIOException { KrMirrorLocalFiles(root).copyUp(outside, 4) { ByteArrayInputStream("data".toByteArray()) } }
        assertEquals(0L, outside.length())
    }

    @Test
    fun `concurrent same key copy up opens source only once across instances`() {
        val root = temporary.newFolder("same-key")
        val target = File(root, "asset").apply { createNewFile() }
        val opens = AtomicInteger()
        runConcurrently(8) {
            KrMirrorLocalFiles(root).copyUp(target, 4) {
                opens.incrementAndGet()
                ByteArrayInputStream("data".toByteArray())
            }
        }
        assertEquals(1, opens.get())
        assertEquals("data", target.readText())
    }

    @Test
    fun `concurrent distinct first keys share newly created state directories safely`() {
        val root = temporary.newFolder("distinct-keys")
        runConcurrently(12) { index ->
            val target = File(root, "asset-$index").apply { createNewFile() }
            val local = KrMirrorLocalFiles(root)
            local.copyUp(target, 4) { ByteArrayInputStream("data".toByteArray()) }
            assertTrue(local.isOverridden(target))
            assertEquals("data", target.readText())
        }
    }

    @Test
    fun `interrupted copy preserves interruption and leaves original placeholder`() {
        val root = temporary.newFolder("interrupted")
        val target = File(root, "asset").apply { createNewFile() }
        val local = KrMirrorLocalFiles(root)
        try {
            Thread.currentThread().interrupt()
            expectIOException { local.copyUp(target, 4) { ByteArrayInputStream("data".toByteArray()) } }
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
        assertEquals(0L, target.length())
        assertFalse(local.isOverridden(target))
    }

    private fun expectIOException(action: () -> Unit) {
        try {
            action()
            fail("Expected IOException")
        } catch (_: IOException) {
        }
    }

    private fun runConcurrently(count: Int, action: (Int) -> Unit) {
        val executor = Executors.newFixedThreadPool(count)
        val start = CountDownLatch(1)
        try {
            val futures = (0 until count).map { index ->
                executor.submit {
                    assertTrue(start.await(5, TimeUnit.SECONDS))
                    action(index)
                }
            }
            start.countDown()
            futures.forEach { it.get(15, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }
}
