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
            failures = listOf(SaveSyncFailure("out-of-space", SaveSyncError.STORAGE_FULL, "content://full")),
            cleanupPending = 1,
            cleanupPendingGames = setOf("content://other"),
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
        val storageFull = SaveSyncReport(
            failures = listOf(SaveSyncFailure("game", SaveSyncError.STORAGE_FULL, "content://full")),
        )

        assertFalse(SaveSyncWorkPolicy.shouldScheduleFallback(storageFull))
        assertEquals(SaveSyncWorkOutcome.FAILURE, SaveSyncWorkPolicy.outcome(storageFull, fallback = true))
    }

    @Test
    fun cleanupSkipsOnlyTheStorageFullGame() {
        val onlyFull = SaveSyncReport(
            cleanupPending = 1,
            cleanupPendingGames = setOf("content://full"),
            failures = listOf(SaveSyncFailure("full", SaveSyncError.STORAGE_FULL, "content://full")),
        )
        assertFalse(SaveSyncWorkPolicy.shouldScheduleFallback(onlyFull))
        assertEquals(SaveSyncWorkOutcome.FAILURE, SaveSyncWorkPolicy.outcome(onlyFull, fallback = true))

        val otherPending = SaveSyncReport(
            cleanupPending = 1,
            cleanupPendingGames = setOf("content://other"),
            failures = listOf(SaveSyncFailure("full", SaveSyncError.STORAGE_FULL, "content://full")),
        )
        assertTrue(SaveSyncWorkPolicy.shouldScheduleFallback(otherPending))
        assertEquals(SaveSyncWorkOutcome.RETRY, SaveSyncWorkPolicy.outcome(otherPending, fallback = true))
    }

    @Test
    fun sameTitleDoesNotMakeCleanupMatchAnotherGameUri() {
        val report = SaveSyncReport(
            cleanupPending = 1,
            cleanupPendingGames = setOf("content://other-uri"),
            failures = listOf(SaveSyncFailure("Same title", SaveSyncError.STORAGE_FULL, "content://full-uri")),
        )

        assertTrue(SaveSyncWorkPolicy.shouldScheduleFallback(report))
        assertEquals(SaveSyncWorkOutcome.RETRY, SaveSyncWorkPolicy.outcome(report, fallback = true))
    }

    @Test
    fun sameUriStorageFullDoesNotSuppressItsOwnCleanup() {
        val report = SaveSyncReport(
            cleanupPending = 1,
            cleanupPendingGames = setOf("content://same-uri"),
            failures = listOf(SaveSyncFailure("Full game", SaveSyncError.STORAGE_FULL, "content://same-uri")),
        )

        assertFalse(SaveSyncWorkPolicy.shouldScheduleFallback(report))
        assertEquals(SaveSyncWorkOutcome.FAILURE, SaveSyncWorkPolicy.outcome(report, fallback = true))
    }

    @Test
    fun missingUriStorageFailureDoesNotSuppressOtherGames() {
        val report = SaveSyncReport(
            cleanupPending = 1,
            cleanupPendingGames = setOf("content://other"),
            failures = listOf(SaveSyncFailure("Legacy full game", SaveSyncError.STORAGE_FULL)),
        )

        assertTrue(SaveSyncWorkPolicy.shouldScheduleFallback(report))
        assertEquals(emptySet<String>(), SaveSyncWorkPolicy.storageFullUris(report))
        assertEquals(SaveSyncWorkOutcome.RETRY, SaveSyncWorkPolicy.outcome(report, fallback = true))
    }

    @Test
    fun networkFailureForAnotherUriRetriesAfterStorageFullGameIsExcluded() {
        val report = SaveSyncReport(
            failures = listOf(
                SaveSyncFailure("Same title", SaveSyncError.STORAGE_FULL, "content://full"),
                SaveSyncFailure("Same title", SaveSyncError.NETWORK, "content://network"),
            ),
        )

        assertTrue(SaveSyncWorkPolicy.shouldScheduleFallback(report))
        assertEquals(setOf("content://full"), SaveSyncWorkPolicy.storageFullUris(report))
        assertEquals(SaveSyncWorkOutcome.RETRY, SaveSyncWorkPolicy.outcome(report, fallback = true))
    }

    @Test
    fun busyOtherWorkRetriesAfterStorageFullGameIsExcluded() {
        val report = SaveSyncReport(
            busy = 1,
            failures = listOf(SaveSyncFailure("Full game", SaveSyncError.STORAGE_FULL, "content://full")),
        )

        assertTrue(SaveSyncWorkPolicy.shouldScheduleFallback(report))
        assertEquals(SaveSyncWorkOutcome.RETRY, SaveSyncWorkPolicy.outcome(report, fallback = true))
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
