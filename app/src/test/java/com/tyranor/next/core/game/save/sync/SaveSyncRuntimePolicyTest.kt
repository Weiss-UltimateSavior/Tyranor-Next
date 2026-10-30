package com.tyranor.next.core.game.save.sync

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SaveSyncRuntimePolicyTest {
    @Test
    fun explicitRestoreBookkeepingDoesNotRequireAHostThatWasNeverLaunched() {
        assertFalse(sessionRequiresHostEvidence(true, 0L, true))
        assertTrue(sessionRequiresHostEvidence(true, 1_700_000_000_000L, true))
        assertTrue(sessionRequiresHostEvidence(true, 0L, false))
        assertTrue(sessionRequiresHostEvidence(true, -1L, true))
        assertFalse(sessionRequiresHostEvidence(false, -1L, false))
    }

    @Test
    fun unknownActivityCannotMakeAnotherGameAppearIdle() {
        val registry = HostActivityUriRegistry<String>()
        registry.register("known", "game-a")
        registry.register("unknown", null)
        assertEquals(HostActivityUriSnapshot(2, emptyList()), registry.snapshot())
        registry.unregister("unknown")
        assertEquals(HostActivityUriSnapshot(1, listOf("game-a")), registry.snapshot())
    }

    @Test
    fun hostRecordRequiresLivePidAndKnownUris() {
        assertEquals(HostRecordRelation.UNKNOWN, classifyHostRecord(20, 19, 1, listOf("game"), "game"))
        assertEquals(HostRecordRelation.UNKNOWN, classifyHostRecord(-1, -1, 1, listOf("game"), "game"))
        assertEquals(HostRecordRelation.UNKNOWN, classifyHostRecord(20, -1, 1, listOf("game"), "game"))
        assertEquals(HostRecordRelation.UNKNOWN, classifyHostRecord(20, 20, -1, listOf("game"), "game"))
        assertEquals(HostRecordRelation.UNKNOWN, classifyHostRecord(20, 20, 1, emptyList(), "game"))
        assertEquals(HostRecordRelation.MATCHING, classifyHostRecord(20, 20, 1, listOf("other", "game"), "game"))
        assertEquals(HostRecordRelation.UNRELATED, classifyHostRecord(20, 20, 1, listOf("other"), "game"))
    }

    @Test
    fun zeroActivitiesRequiresIdleGraceBeforeUnrelated() {
        assertEquals(HostRecordRelation.UNRELATED, classifyHostRecord(20, 20, 0, listOf("game"), "game"))
        assertEquals(HostRecordRelation.UNRELATED, classifyHostRecord(20, 20, 0, emptyList(), "game", 1_500L))
        assertEquals(HostRecordRelation.UNKNOWN, classifyHostRecord(20, 20, 0, listOf("game"), "game", 1_499L))
    }

    @Test
    fun hostUriRegistryDropsDestroyedActivityUri() {
        val registry = HostActivityUriRegistry<String>()
        registry.register("activity-a", "game-a")
        registry.register("activity-b", "game-b")

        registry.unregister("activity-a")

        assertEquals(HostActivityUriSnapshot(1, listOf("game-b")), registry.snapshot())
    }

    @Test
    fun hostUriRegistryKeepsSharedUriUntilLastActivityIsDestroyed() {
        val registry = HostActivityUriRegistry<String>()
        registry.register("activity-a", "shared")
        registry.register("activity-b", "shared")

        registry.unregister("activity-a")
        assertEquals(HostActivityUriSnapshot(1, listOf("shared")), registry.snapshot())

        registry.unregister("activity-b")
        assertEquals(HostActivityUriSnapshot(0, emptyList()), registry.snapshot())
    }

    @Test
    fun externalYurisRequiresConfirmationDespiteLegacyForegroundAndOwnerHints() {
        val session = JSONObject().put("token", "launch").put("startedAt", 1000L)
        assertTrue(externalSessionNeedsExitConfirmation(session))
        session.put("leftApp", true).put("returned", true).put("ownerPid", -1)
        assertTrue(externalSessionNeedsExitConfirmation(session))
        session.put("externalExitConfirmedToken", "older-launch")
        assertTrue(externalSessionNeedsExitConfirmation(session))
        session.put("externalExitConfirmedToken", "launch")
        assertFalse(externalSessionNeedsExitConfirmation(session))
        session.put("token", "new-launch")
        assertTrue(externalSessionNeedsExitConfirmation(session))
    }

    @Test
    fun explicitRestoreDoesNotNeedExternalExitConfirmationButUnknownLegacyLaunchDoes() {
        assertFalse(externalSessionNeedsExitConfirmation(JSONObject()))
        assertTrue(externalSessionNeedsExitConfirmation(JSONObject().put("token", "legacy").put("returned", true)))
        assertFalse(externalSessionNeedsExitConfirmation(JSONObject().put("token", "restore")
            .put("startedAt", 0L).put("returned", true)))
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
