package com.tyranor.next.core.game.save.sync

import com.tyranor.next.core.engine.EngineType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SaveSyncRuntimePolicyTest {
    @Test
    fun hostRecordRequiresLivePidAndKnownUris() {
        assertEquals(HostRecordRelation.UNKNOWN, classifyHostRecord(20, 19, listOf("game"), "game"))
        assertEquals(HostRecordRelation.UNKNOWN, classifyHostRecord(20, 20, emptyList(), "game"))
        assertEquals(HostRecordRelation.MATCHING, classifyHostRecord(20, 20, listOf("other", "game"), "game"))
        assertEquals(HostRecordRelation.UNRELATED, classifyHostRecord(20, 20, listOf("other"), "game"))
    }

    @Test
    fun externalYurisReturnUsesBackgroundOrOwnerEvidence() {
        assertTrue(shouldMarkExternalYurisReturned(EngineType.YURIS, true, false, 7, 7))
        assertTrue(shouldMarkExternalYurisReturned(EngineType.YURIS, false, true, 7, 7))
        assertTrue(shouldMarkExternalYurisReturned(EngineType.YURIS, false, false, 7, 8))
        assertTrue(shouldMarkExternalYurisReturned(EngineType.YURIS, false, false, -1, 8))
        assertFalse(shouldMarkExternalYurisReturned(EngineType.YURIS, false, false, 8, 8))
        assertFalse(shouldMarkExternalYurisReturned(EngineType.TYRANO, true, true, 7, 8))
    }

    @Test
    fun immediateAndFallbackWorkNamesStayDistinct() {
        val names = setOf(
            SaveSyncWorker.WORK_NAME_OPEN_NOW,
            SaveSyncWorker.WORK_NAME_EXIT_NOW,
            SaveSyncWorker.WORK_NAME_OPEN_FALLBACK,
            SaveSyncWorker.WORK_NAME_EXIT_FALLBACK,
        )

        assertEquals(
            setOf(
                "webdav_save_open_now",
                "webdav_save_exit_now",
                "webdav_save_open_fallback",
                "webdav_save_exit_fallback",
            ),
            names,
        )
    }

    @Test
    fun immediateBusyCompletesWhileFallbackRetries() {
        val busy = SaveSyncReport(busy = 1)

        assertEquals(SaveSyncWorkOutcome.SUCCESS, SaveSyncWorkPolicy.outcome(busy, fallback = false))
        assertTrue(SaveSyncWorkPolicy.shouldScheduleFallback(busy))
        assertEquals(SaveSyncWorkOutcome.RETRY, SaveSyncWorkPolicy.outcome(busy, fallback = true))
    }

    @Test
    fun immediateTransientFailureReturnsFailureAndLeavesRetryToFallback() {
        val transient = SaveSyncReport(failures = listOf(SaveSyncFailure("game", SaveSyncError.NETWORK)))

        assertEquals(SaveSyncWorkOutcome.FAILURE, SaveSyncWorkPolicy.outcome(transient, fallback = false))
        assertTrue(SaveSyncWorkPolicy.shouldScheduleFallback(transient))
        assertEquals(SaveSyncWorkOutcome.RETRY, SaveSyncWorkPolicy.outcome(transient, fallback = true))
    }

    @Test
    fun permanentFailureDoesNotCreateFallbackAndDisabledIsSilentSuccess() {
        val permanent = SaveSyncReport(failures = listOf(SaveSyncFailure("game", SaveSyncError.INVALID_CONFIG)))
        val disabled = SaveSyncReport(failures = listOf(SaveSyncFailure("game", SaveSyncError.DISABLED)))

        assertEquals(SaveSyncWorkOutcome.FAILURE, SaveSyncWorkPolicy.outcome(permanent, fallback = false))
        assertFalse(SaveSyncWorkPolicy.shouldScheduleFallback(permanent))
        assertEquals(SaveSyncWorkOutcome.SUCCESS, SaveSyncWorkPolicy.outcome(disabled, fallback = false))
        assertFalse(SaveSyncWorkPolicy.shouldScheduleFallback(disabled))
    }
}
