package bridge

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class KrMirrorReadSessionTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `real rename waits for inflight copy up and retains newly committed content`() {
        verifyCopyUpMutationInterleave(rename = true)
    }

    @Test
    fun `real unlink waits for inflight copy up and cannot resurrect removed file`() {
        verifyCopyUpMutationInterleave(rename = false)
    }

    @Test
    fun `copy up waits until real rename and ownership commit complete`() {
        val root = temporary.newFolder()
        val target = File(root, "asset").apply { createNewFile() }
        val replacement = File(root, "temporary").apply { writeText("new save") }
        val key = target.absolutePath.lowercase(Locale.ROOT)
        val state = KrMirrorLocalFiles(root)
        val session = KrMirrorReadSession(mapOf(key to "source"), state)
        val renamed = CountDownLatch(1)
        val releaseMutation = CountDownLatch(1)
        val copyStarted = CountDownLatch(1)
        val sourceOpened = AtomicBoolean(false)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val mutation = executor.submit<Long> {
                session.runMutation(replacement.absolutePath.lowercase(Locale.ROOT), replacement, key, target) {
                    Files.move(replacement.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    renamed.countDown()
                    assertTrue(releaseMutation.await(5, TimeUnit.SECONDS))
                    0L
                }
            }
            assertTrue(renamed.await(5, TimeUnit.SECONDS))
            val copy = executor.submit<File> {
                copyStarted.countDown()
                state.copyUp(target, 3) {
                    sourceOpened.set(true)
                    ByteArrayInputStream("old".toByteArray())
                }
            }
            assertTrue(copyStarted.await(5, TimeUnit.SECONDS))
            assertFalse(copy.isDone)
            releaseMutation.countDown()
            assertEquals(0L, mutation.get(5, TimeUnit.SECONDS))
            copy.get(5, TimeUnit.SECONDS)
            assertFalse(sourceOpened.get())
            assertEquals("new save", target.readText())
            assertTrue(state.isOverridden(target))
        } finally {
            releaseMutation.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    private fun verifyCopyUpMutationInterleave(rename: Boolean) {
        val root = temporary.newFolder()
        val target = File(root, "asset").apply { createNewFile() }
        val replacement = File(root, "temporary").apply { writeText("new save") }
        val key = target.absolutePath.lowercase(Locale.ROOT)
        val state = KrMirrorLocalFiles(root)
        val session = KrMirrorReadSession(mapOf(key to "source"), state)
        val copying = CountDownLatch(1)
        val releaseCopy = CountDownLatch(1)
        val mutationStarted = CountDownLatch(1)
        val syscallEntered = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val copy = executor.submit<File> {
                state.copyUp(target, 3) {
                    copying.countDown()
                    assertTrue(releaseCopy.await(5, TimeUnit.SECONDS))
                    ByteArrayInputStream("old".toByteArray())
                }
            }
            assertTrue(copying.await(5, TimeUnit.SECONDS))
            val mutation = executor.submit<Long> {
                mutationStarted.countDown()
                val from = if (rename) replacement else target
                val to = if (rename) target else null
                session.runMutation(
                    from.absolutePath.lowercase(Locale.ROOT), from,
                    to?.absolutePath?.lowercase(Locale.ROOT), to,
                ) {
                    syscallEntered.countDown()
                    if (rename) {
                        Files.move(replacement.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    } else {
                        Files.delete(target.toPath())
                    }
                    0L
                }
            }
            assertTrue(mutationStarted.await(5, TimeUnit.SECONDS))
            // The actual filesystem syscall, not merely its callback, is blocked.
            assertFalse(syscallEntered.await(150, TimeUnit.MILLISECONDS))
            releaseCopy.countDown()
            copy.get(5, TimeUnit.SECONDS)
            assertEquals(0L, mutation.get(5, TimeUnit.SECONDS))
            assertEquals(0L, syscallEntered.count)
            if (rename) {
                assertEquals("new save", target.readText())
                assertTrue(state.isOverridden(target))
            } else {
                assertFalse(target.exists())
                assertFalse(state.isOverridden(target))
                assertNull(session.sourceEntry(key))
            }
        } finally {
            releaseCopy.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `successful rename migrates empty override and unlink clears marker and source route`() {
        val root = temporary.newFolder()
        val from = File(root, "from").apply { createNewFile() }
        val to = File(root, "to")
        val fromKey = from.absolutePath.lowercase(Locale.ROOT)
        val toKey = to.absolutePath.lowercase(Locale.ROOT)
        val state = KrMirrorLocalFiles(root)
        state.markOverridden(from)
        val session = KrMirrorReadSession(mapOf(fromKey to "source", toKey to "other"), state)
        Files.move(from.toPath(), to.toPath())

        session.synchronizeMutation(fromKey, from, toKey, to)

        assertFalse(state.isOverridden(from))
        assertTrue(state.isOverridden(to))
        assertNull(session.sourceEntry(fromKey))
        assertNull(session.entry(toKey))
        assertTrue(to.delete())
        session.synchronizeMutation(toKey, to)
        assertFalse(state.isOverridden(to))
        assertNull(session.sourceEntry(toKey))
    }

    @Test
    fun `unindexed empty temporary rename replaces indexed source with local empty content`() {
        val root = temporary.newFolder()
        val from = File(root, "temporary").apply { createNewFile() }
        val to = File(root, "asset").apply { createNewFile() }
        val fromKey = from.absolutePath.lowercase(Locale.ROOT)
        val toKey = to.absolutePath.lowercase(Locale.ROOT)
        val state = KrMirrorLocalFiles(root)
        val session = KrMirrorReadSession(mapOf(toKey to "source"), state)
        Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)

        session.synchronizeMutation(fromKey, from, toKey, to)

        assertTrue(state.isOverridden(to))
        assertNull(session.entry(toKey))
        assertEquals(0L, to.length())
    }

    @Test
    fun `empty temporary rename from outside mirror still promotes valid local destination`() {
        val root = temporary.newFolder()
        val from = temporary.newFile("outside-temp")
        val to = File(root, "asset").apply { createNewFile() }
        val toKey = to.absolutePath.lowercase(Locale.ROOT)
        val state = KrMirrorLocalFiles(root)
        val session = KrMirrorReadSession(mapOf(toKey to "source"), state)
        Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)

        session.synchronizeMutation(null, null, toKey, to)

        assertTrue(state.isOverridden(to))
        assertNull(session.entry(toKey))
        assertEquals(0L, to.length())
    }

    @Test
    fun `no op rename leaves empty source placeholder unpromoted`() {
        val root = temporary.newFolder()
        val file = File(root, "asset").apply { createNewFile() }
        val key = file.absolutePath.lowercase(Locale.ROOT)
        val state = KrMirrorLocalFiles(root)
        val session = KrMirrorReadSession(mapOf(key to "source"), state)
        Files.move(file.toPath(), file.toPath())

        session.synchronizeMutation(key, file, key, file)

        assertFalse(state.isOverridden(file))
        assertEquals("source", session.entry(key))
        assertFalse(File(root, ".tyranor-mirror-state").exists())
    }

    @Test
    fun `directory rename destination is not promoted as ordinary local file`() {
        val root = temporary.newFolder()
        val destination = File(root, "directory").apply { mkdir() }
        val key = destination.absolutePath.lowercase(Locale.ROOT)
        val state = KrMirrorLocalFiles(root)
        val session = KrMirrorReadSession(mapOf(key to "source"), state)

        session.synchronizeMutation(null, null, key, destination)

        assertFalse(state.isOverridden(destination))
        assertEquals("source", session.entry(key))
    }

    @Test
    fun `session owns immutable snapshot independent of caller mutations`() {
        val documents = mutableMapOf("asset" to "source")
        val session = KrMirrorReadSession(documents)
        documents["asset"] = "replacement"
        documents.clear()
        assertEquals("source", session.entry("asset"))
    }

    @Test
    fun `promotion removes source route and stale read failure only for same key`() {
        val session = KrMirrorReadSession(mapOf("asset" to 1, "other" to 2))
        session.recordRead("asset", false)
        session.recordRead("other", false)
        session.promote("asset")
        assertNull(session.entry("asset"))
        assertFalse(session.readFailed("asset"))
        assertEquals(2, session.entry("other"))
        assertTrue(session.readFailed("other"))
    }

    @Test
    fun `successful retry clears failure without dropping source route`() {
        val session = KrMirrorReadSession(mapOf("asset" to "uri"))
        session.recordRead("asset", false)
        assertTrue(session.readFailed("asset"))
        session.recordRead("asset", true)
        assertFalse(session.readFailed("asset"))
        assertEquals("uri", session.entry("asset"))
    }
}
