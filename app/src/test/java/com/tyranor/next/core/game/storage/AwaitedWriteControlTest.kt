package com.tyranor.next.core.game.storage

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AwaitedWriteControlTest {

    @Test
    fun cancellationBeforeCommitAbandonsQueuedWrite() {
        val caller = Job()
        val control = AwaitedWriteControl(caller)
        caller.cancel()

        assertTrue(control.cancelBeforeCommit())
        assertThrows(CancellationException::class.java) { control.checkBeforeCommit() }
        assertThrows(CancellationException::class.java) { control.beginCommit() }
    }

    @Test
    fun cancellationAfterCommitStartsAllowsConsistencyCompletion() {
        val caller = Job()
        val control = AwaitedWriteControl(caller)
        control.beginCommit()
        caller.cancel()

        assertFalse(control.cancelBeforeCommit())
        // Once commit begins, the independent writer may finish DB/mirror reconciliation.
        control.checkBeforeCommit()
    }
}
