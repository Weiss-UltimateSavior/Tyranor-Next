package com.tyranor.next.core.game.save.sync

import androidx.work.NetworkType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SaveSyncWorkerPolicyTest {
    @Test
    fun automaticWorkRequiresUnmeteredNetwork() {
        assertEquals(NetworkType.UNMETERED, SaveSyncWorkPolicy.AUTOMATIC_NETWORK_TYPE)
    }

    @Test
    fun fallbackStopsAtRunAttemptLimitWhileImmediateWorkIsUnaffected() {
        assertFalse(SaveSyncWorkPolicy.fallbackAttemptsExhausted(fallback = true, runAttemptCount = 0))
        assertFalse(SaveSyncWorkPolicy.fallbackAttemptsExhausted(fallback = true, runAttemptCount = 4))
        assertTrue(SaveSyncWorkPolicy.fallbackAttemptsExhausted(fallback = true, runAttemptCount = 5))
        assertTrue(SaveSyncWorkPolicy.fallbackAttemptsExhausted(fallback = true, runAttemptCount = 6))
        assertFalse(SaveSyncWorkPolicy.fallbackAttemptsExhausted(fallback = false, runAttemptCount = 5))
    }

    @Test
    fun fallbackStillRetriesTransientResultsBeforeLimit() {
        val transient = SaveSyncReport(failures = listOf(SaveSyncFailure("game", SaveSyncError.NETWORK)))

        assertEquals(SaveSyncWorkOutcome.RETRY, SaveSyncWorkPolicy.outcome(transient, fallback = true))
    }

    @Test
    fun permanentFailureDoesNotCancelAnotherGamesTransientOrBusyRetry() {
        val permanent = SaveSyncFailure("A", SaveSyncError.IDENTITY_CONFLICT)
        val mixed = SaveSyncReport(failures = listOf(permanent, SaveSyncFailure("B", SaveSyncError.NETWORK)))
        assertEquals(SaveSyncWorkOutcome.RETRY, SaveSyncWorkPolicy.outcome(mixed, fallback = true))
        assertEquals(SaveSyncWorkOutcome.FAILURE, SaveSyncWorkPolicy.outcome(mixed, fallback = false))
        val busy = SaveSyncReport(busy = 1, failures = listOf(permanent))
        assertEquals(SaveSyncWorkOutcome.RETRY, SaveSyncWorkPolicy.outcome(busy, fallback = true))
        assertEquals(SaveSyncWorkOutcome.FAILURE,
            SaveSyncWorkPolicy.outcome(SaveSyncReport(failures = listOf(permanent)), fallback = true))
    }
}
