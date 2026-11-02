package com.tyranor.next.core.game.save.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class SaveSyncFeedbackTest {
    @Test
    fun cleanupOnlyReportIsPublished() {
        SaveSyncFeedback.publish(SaveSyncReport(cleanupPending = 1))

        val notice = SaveSyncFeedback.notice.value
        assertEquals(1, notice?.report?.cleanupPending)
        notice?.let(SaveSyncFeedback::consume)
    }
}
