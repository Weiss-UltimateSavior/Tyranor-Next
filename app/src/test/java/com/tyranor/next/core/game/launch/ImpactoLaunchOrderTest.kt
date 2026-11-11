package com.tyranor.next.core.game.launch

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImpactoLaunchOrderTest {
    @Test fun slowColdInstallCannotAgeTheDispatchLease() = runBlocking {
        var now = 0L
        var expiresAt: Long? = null
        val events = mutableListOf<String>()
        val prepared = prepareImpactoAfterRuntime(
            ensureRuntime = {
                events += "install"
                // Global pack lock plus extraction can exceed the host's 60-second pending lease.
                now += 120_000L
                assertNull(expiresAt)
                true
            },
            prepare = {
                events += "publish"
                expiresAt = now + 60_000L
                "prepared session"
            },
        )
        events += "dispatch"
        now += 5L
        assertEquals("prepared session", prepared)
        assertTrue(now < requireNotNull(expiresAt))
        assertEquals(listOf("install", "publish", "dispatch"), events)
    }

    @Test fun unavailableRuntimeNeverPublishesALease() = runBlocking {
        var prepared = false
        assertNull(prepareImpactoAfterRuntime(
            ensureRuntime = { false },
            prepare = { prepared = true; "unused" },
        ))
        assertFalse(prepared)
    }

    @Test fun cancellationDuringInstallationDoesNotPublishALease() {
        var prepared = false
        var cancelled = false
        try {
            runBlocking(Job()) {
                prepareImpactoAfterRuntime(
                    ensureRuntime = {
                        currentCoroutineContext().cancel()
                        true
                    },
                    prepare = { prepared = true; "unused" },
                )
            }
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
        assertFalse(prepared)
    }
}
