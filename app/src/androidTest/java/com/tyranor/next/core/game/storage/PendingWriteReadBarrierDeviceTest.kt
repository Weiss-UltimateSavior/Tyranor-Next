package com.tyranor.next.core.game.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PendingWriteReadBarrierDeviceTest {
    @Test
    fun readWaitsForCommitStartedWriteAfterItsCallerIsCancelled() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val persistedValue = AtomicReference("old")
        val commitStarted = CompletableDeferred<Unit>()
        val releaseCommit = CompletableDeferred<Unit>()
        val writer = launch {
            GameLibraryRepository.writeAwaited(context) { _, control ->
                control.beginCommit()
                commitStarted.complete(Unit)
                releaseCommit.await()
                persistedValue.set("new")
            }
        }

        try {
            withTimeout(10_000) { commitStarted.await() }
            writer.cancelAndJoin()

            val read = async(start = CoroutineStart.UNDISPATCHED) {
                GameLibraryRepository.awaitPendingWrites()
                persistedValue.get()
            }
            assertNull(
                "read passed the pending write before its commit finished",
                withTimeoutOrNull(100) { read.await() },
            )

            releaseCommit.complete(Unit)
            assertEquals("new", withTimeout(10_000) { read.await() })
        } finally {
            releaseCommit.complete(Unit)
            writer.cancelAndJoin()
            withTimeout(10_000) { GameLibraryRepository.awaitPendingWrites() }
        }
    }
}
