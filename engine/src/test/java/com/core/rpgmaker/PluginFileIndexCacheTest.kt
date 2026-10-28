package com.core.rpgmaker

import com.core.web.PluginFileIndexCache
import java.io.IOException
import java.io.InterruptedIOException
import org.junit.Assert.*
import org.junit.Test

class PluginFileIndexCacheTest {
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

    @Test(expected = InterruptedIOException::class) fun stoppedSessionDoesNotBuildOrServeCachedIndex() {
        val cache = PluginFileIndexCache(build = { byteArrayOf(1) }, isLimitFailure = { false })
        cache.get()
        cache.stop()
        cache.get()
    }
}
