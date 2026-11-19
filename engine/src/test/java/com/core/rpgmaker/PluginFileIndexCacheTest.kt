package com.core.rpgmaker

import com.core.web.PluginFileIndexCache
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class PluginFileIndexCacheTest {
    @Test fun rejectedSubmissionCanBeRetriedAndPendingRequestsNeverWait() {
        val cache = PluginFileIndexCache(build = { byteArrayOf(9) }, isLimitFailure = { false })
        try {
            cache.request { throw java.util.concurrent.RejectedExecutionException() }
            fail("rejection must be visible")
        } catch (_: java.util.concurrent.RejectedExecutionException) { }
        val tasks = mutableListOf<Runnable>()
        assertNull(cache.request { tasks.add(it) })
        assertNull(cache.request { tasks.add(it) })
        assertEquals(1, tasks.size)
        tasks.single().run()
        assertArrayEquals(byteArrayOf(9), cache.request { fail("ready cache must not resubmit") })
        cache.stop()
        try { cache.request { fail("stopped cache must not submit") }; fail("stopped") }
        catch (_: InterruptedIOException) { }
    }

    @Test fun anotherReaderDoesNotWaitForAnActiveScan() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        val cache = PluginFileIndexCache(build = {
            started.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            byteArrayOf(1)
        }, isLimitFailure = { false })
        try {
            val first = executor.submit<ByteArray> { cache.get() }
            assertTrue(started.await(2, TimeUnit.SECONDS))
            val other = executor.submit<Boolean> {
                try { cache.get(); false } catch (_: IOException) { true }
            }
            assertTrue("A busy cache must report pending instead of waiting for the scan", other.get(500, TimeUnit.MILLISECONDS))
            release.countDown()
            assertArrayEquals(byteArrayOf(1), first.get(2, TimeUnit.SECONDS))
        } finally { release.countDown(); cache.stop(); executor.shutdownNow() }
    }

    @Test fun focusedTransientFailureCanRetryWithoutWaitingForBackoff() {
        var calls = 0
        val cache = PluginFileIndexCache(build = { focused ->
            calls++
            if (!focused) throw RpgMakerFileIndex.LimitExceeded("entry")
            if (calls == 2) throw IOException("temporary focused read")
            byteArrayOf(7)
        }, isLimitFailure = { it is RpgMakerFileIndex.LimitExceeded })
        try { cache.get(); fail("first focused attempt must fail") } catch (_: IOException) { }
        assertArrayEquals(byteArrayOf(7), cache.get())
        assertEquals(4, calls)
    }

    @Test fun quotaFallbackIsStableDuringBackoffAndFullIndexCanRecover() {
        var now = 0L
        var calls = 0
        var large = true
        val cache = PluginFileIndexCache(build = { focused ->
            calls++
            if (!focused && large) throw RpgMakerFileIndex.LimitExceeded("entry")
            (if (focused) "locales-only" else "full").toByteArray()
        }, isLimitFailure = { it is RpgMakerFileIndex.LimitExceeded }, nanoTime = { now }, retryNanos = 10)
        assertEquals("locales-only", String(cache.get()))
        assertEquals("locales-only", String(cache.get()))
        assertEquals(2, calls)
        large = false
        now = 11
        assertEquals("full", String(cache.get()))
        now = 100
        assertEquals("full", String(cache.get()))
        assertEquals(3, calls)
    }

    @Test fun deterministicFailureDoesNotContinuouslyRescan() {
        var calls = 0
        val cache = PluginFileIndexCache(build = { calls++; throw RpgMakerFileIndex.LimitExceeded("depth") },
            isLimitFailure = { it is RpgMakerFileIndex.LimitExceeded })
        repeat(2) {
            try { cache.get(); fail("must fail") } catch (_: RpgMakerFileIndex.LimitExceeded) { }
        }
        assertEquals(2, calls)
    }

    @Test fun transientFailureCanRetryImmediately() {
        var calls = 0
        val cache = PluginFileIndexCache(build = {
            if (++calls == 1) throw IOException("temporary")
            byteArrayOf(1)
        }, isLimitFailure = { it is RpgMakerFileIndex.LimitExceeded })
        try { cache.get(); fail("first attempt must fail") } catch (_: IOException) { }
        assertArrayEquals(byteArrayOf(1), cache.get())
        assertEquals(2, calls)
    }

    @Test fun persistentNonLimitFailuresBecomeTerminalAndBackOff() {
        var now = 0L
        var calls = 0
        val cache = PluginFileIndexCache(build = {
            calls++
            throw IOException("unreadable disk root")
        }, isLimitFailure = { it is RpgMakerFileIndex.LimitExceeded }, nanoTime = { now }, retryNanos = 10)
        repeat(3) {
            try { cache.get(); fail("attempt ${it + 1} must fail") } catch (_: IOException) { }
        }
        assertEquals(3, calls)
        try { cache.get(); fail("terminal failure must be cached during backoff") } catch (_: IOException) { }
        assertEquals(3, calls)
        now = 11L
        try { cache.get(); fail("backoff expiry allows a fresh retry") } catch (_: IOException) { }
        assertEquals(4, calls)
    }

    @Test(expected = InterruptedIOException::class) fun stoppedSessionDoesNotBuildOrServeCachedIndex() {
        val cache = PluginFileIndexCache(build = { byteArrayOf(1) }, isLimitFailure = { false })
        cache.get()
        cache.stop()
        cache.get()
    }
}
