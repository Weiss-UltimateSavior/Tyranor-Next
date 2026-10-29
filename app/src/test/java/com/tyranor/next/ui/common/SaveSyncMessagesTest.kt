package com.tyranor.next.ui.common

import com.tyranor.next.R
import com.tyranor.next.core.game.save.sync.SaveSyncError
import com.tyranor.next.core.game.save.sync.SaveSyncException
import com.tyranor.next.core.game.save.sync.SaveSyncFailure
import com.tyranor.next.core.game.save.sync.SaveSyncReport
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SaveSyncMessagesTest {

    @Test
    fun everyErrorHasMessageResource() {
        assertTrue(SaveSyncError.entries.all { it.syncErrorResource() != 0 })
    }

    @Test
    fun specificErrorsUseDistinctMessages() {
        assertEquals(R.string.webdav_error_not_configured, SaveSyncError.NOT_CONFIGURED.syncErrorResource())
        assertEquals(R.string.webdav_error_disabled, SaveSyncError.DISABLED.syncErrorResource())
        assertEquals(R.string.webdav_error_storage_full, SaveSyncError.STORAGE_FULL.syncErrorResource())
        assertEquals(R.string.webdav_error_game_running, SaveSyncError.GAME_RUNNING.syncErrorResource())
        assertEquals(R.string.webdav_error_local_io, SaveSyncError.LOCAL_IO.syncErrorResource())
        assertEquals(
            R.string.webdav_error_save_location_changed,
            SaveSyncError.SAVE_LOCATION_CHANGED.syncErrorResource(),
        )
        assertEquals(R.string.save_error_selection_incomplete, SaveSyncError.SELECTION_INCOMPLETE.syncErrorResource())
        assertEquals(
            R.string.webdav_error_local_state_changed,
            SaveSyncError.LOCAL_STATE_CHANGED.syncErrorResource(),
        )
        assertNotEquals(
            SaveSyncError.NOT_CONFIGURED.syncErrorResource(),
            SaveSyncError.DISABLED.syncErrorResource(),
        )
        assertNotEquals(
            SaveSyncError.LOCAL_IO.syncErrorResource(),
            SaveSyncError.STORAGE_FULL.syncErrorResource(),
        )
        assertNotEquals(
            SaveSyncError.LOCAL_STATE_CHANGED.syncErrorResource(),
            SaveSyncError.GAME_RUNNING.syncErrorResource(),
        )
    }

    @Test
    fun cleanupPreservesTypedSyncFailure() {
        val typed = SaveSyncException(SaveSyncError.SELECTION_INCOMPLETE)

        assertSame(typed, typed.toSaveSyncCleanupException())
    }

    @Test
    fun cleanupNormalizesUnrelatedFailureToLocalIo() {
        val cause = IllegalStateException("cleanup failed")

        val failure = cause.toSaveSyncCleanupException()

        assertEquals(SaveSyncError.LOCAL_IO, failure.code)
        assertSame(cause, failure.cause)
    }

    @Test
    fun cleanupMappingPropagatesCancellation() {
        val cancelled = CancellationException("cancelled")

        assertSame(
            cancelled,
            assertThrows(CancellationException::class.java) {
                cancelled.toSaveSyncCleanupException()
            },
        )
    }

    @Test
    fun failedReportUsesFailureTemplateWithMappedErrorResource() {
        val report = SaveSyncReport(
            failures = listOf(SaveSyncFailure("Game", SaveSyncError.LOCAL_STATE_CHANGED)),
        )

        assertEquals(R.string.webdav_sync_failure, report.syncMessageResource())
        assertEquals(
            R.string.webdav_error_local_state_changed,
            report.failures.single().error.syncErrorResource(),
        )
    }

    @Test
    fun partialFailureReportUsesPartialTemplate() {
        val report = SaveSyncReport(
            uploaded = 1,
            failures = listOf(SaveSyncFailure("Game", SaveSyncError.LOCAL_IO)),
        )

        assertEquals(R.string.webdav_sync_partial_failure, report.syncMessageResource())
    }

    @Test
    fun cleanupPendingDoesNotUseBusyMessage() {
        val report = SaveSyncReport(busy = 1, cleanupPending = 1)

        assertEquals(R.string.webdav_sync_cleanup_pending, report.syncMessageResource())
        assertNotEquals(R.string.webdav_error_game_running, report.syncMessageResource())
    }

    @Test
    fun transferredCleanupPendingUsesPartialResultTemplate() {
        val report = SaveSyncReport(uploaded = 1, cleanupPending = 1)

        assertEquals(R.string.webdav_sync_partial_cleanup_pending, report.syncMessageResource())
    }

    @Test
    fun failureTakesPrecedenceOverCleanupPending() {
        val report = SaveSyncReport(
            failures = listOf(SaveSyncFailure("Game", SaveSyncError.LOCAL_STATE_CHANGED)),
            cleanupPending = 1,
        )

        assertEquals(R.string.webdav_sync_failure, report.syncMessageResource())
    }
}
