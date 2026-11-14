package com.tyranor.next.ui.auth

import android.content.ContextWrapper
import com.tyranor.next.R
import com.tyranor.next.core.auth.HikarinagiAuthError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HikarinagiAuthMessagesTest {

    @Test
    fun everyHikarinagiAuthErrorMapsToValidStringResource() {
        val resources = HikarinagiAuthError.entries.map { it.authErrorResource() }
        assertTrue(resources.all { it != 0 })
        assertEquals(HikarinagiAuthError.entries.size, resources.toSet().size)
        assertEquals(R.string.auth_hikarinagi_failed, HikarinagiAuthError.FAILED.authErrorResource())
        assertEquals(R.string.auth_hikarinagi_id_token_nonce_failed, HikarinagiAuthError.ID_TOKEN_NONCE_FAILED.authErrorResource())
        assertEquals(R.string.play_error_hikarinagi_token_permission, HikarinagiAuthError.PERMISSION.authErrorResource())
    }

    @Test
    fun customDetailTakesPrecedenceWhenNonBlank() {
        val dummyContext = object : ContextWrapper(null) {}
        assertEquals(
            "server rejected scope",
            HikarinagiAuthError.FAILED.authErrorMessage(dummyContext, "  server rejected scope  "),
        )
    }
}
