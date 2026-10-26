package com.tyranor.next.ui.common

import com.tyranor.next.R
import com.tyranor.next.core.game.save.sync.SaveSyncError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SaveSyncMessagesTest {

    @Test
    fun everyErrorHasMessageResource() {
        assertTrue(SaveSyncError.entries.all { it.messageResource() != 0 })
    }

    @Test
    fun specificErrorsUseDistinctMessages() {
        assertEquals(R.string.webdav_error_not_configured, SaveSyncError.NOT_CONFIGURED.messageResource())
        assertEquals(R.string.webdav_error_disabled, SaveSyncError.DISABLED.messageResource())
        assertEquals(R.string.webdav_error_storage_full, SaveSyncError.STORAGE_FULL.messageResource())
        assertNotEquals(
            SaveSyncError.NOT_CONFIGURED.messageResource(),
            SaveSyncError.DISABLED.messageResource(),
        )
        assertNotEquals(
            SaveSyncError.LOCAL_IO.messageResource(),
            SaveSyncError.STORAGE_FULL.messageResource(),
        )
    }
}
