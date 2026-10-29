package com.tyranor.next.core.game.save.sync

import androidx.work.NetworkType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SaveSyncWorkerPolicyTest {
    @Test
    fun automaticWorkUsesUnmeteredNetwork() {
        assertEquals(NetworkType.UNMETERED, SaveSyncWorker.automaticWorkConstraints().requiredNetworkType)
    }

    @Test
    fun cleanupPendingRetriesEvenWhenAnotherGameIsOutOfSpace() {
        val mixed = SaveSyncReport(
            failures = listOf(SaveSyncFailure("out-of-space", SaveSyncError.STORAGE_FULL)),
            cleanupPending = 1,
        )

        assertTrue(SaveSyncWorkPolicy.shouldScheduleFallback(mixed))
        assertEquals(SaveSyncWorkOutcome.RETRY, SaveSyncWorkPolicy.outcome(mixed, fallback = true))
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
    fun localStateChangeIsRetryable() {
        val changed = SaveSyncReport(failures = listOf(SaveSyncFailure("game", SaveSyncError.LOCAL_STATE_CHANGED)))

        assertTrue(SaveSyncWorkPolicy.shouldScheduleFallback(changed))
        assertEquals(SaveSyncWorkOutcome.RETRY, SaveSyncWorkPolicy.outcome(changed, fallback = true))
    }

    @Test
    fun storageFullDoesNotScheduleRetry() {
        val storageFull = SaveSyncReport(failures = listOf(SaveSyncFailure("game", SaveSyncError.STORAGE_FULL)))

        assertFalse(SaveSyncWorkPolicy.shouldScheduleFallback(storageFull))
        assertEquals(SaveSyncWorkOutcome.FAILURE, SaveSyncWorkPolicy.outcome(storageFull, fallback = true))
    }

    @Test
    fun cleanupPendingStillSchedulesFallbackWhenAnotherGameFailsPermanently() {
        val mixed = SaveSyncReport(
            cleanupPending = 1,
            failures = listOf(SaveSyncFailure("game", SaveSyncError.IDENTITY_CONFLICT)),
        )

        assertTrue(SaveSyncWorkPolicy.shouldScheduleFallback(mixed))
        assertEquals(SaveSyncWorkOutcome.RETRY, SaveSyncWorkPolicy.outcome(mixed, fallback = true))
        assertEquals(SaveSyncWorkOutcome.FAILURE, SaveSyncWorkPolicy.outcome(mixed, fallback = false))
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
