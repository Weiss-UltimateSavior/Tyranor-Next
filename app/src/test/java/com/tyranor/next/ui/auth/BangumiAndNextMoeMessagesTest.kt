package com.tyranor.next.ui.auth

import com.tyranor.next.R
import com.tyranor.next.core.auth.BangumiAuthError
import com.tyranor.next.core.auth.NextMoeAuthError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BangumiAndNextMoeMessagesTest {

    @Test
    fun everyBangumiAuthErrorMapsToValidUniqueStringResource() {
        val resources = BangumiAuthError.entries.map { it.authErrorResource() }
        assertTrue(resources.all { it != 0 })
        assertEquals(BangumiAuthError.entries.size, resources.toSet().size)
        assertEquals(R.string.auth_bangumi_expired, BangumiAuthError.EXPIRED.authErrorResource())
        assertEquals(R.string.auth_bangumi_token_invalid, BangumiAuthError.TOKEN_INVALID.authErrorResource())
        assertEquals(R.string.auth_bangumi_network, BangumiAuthError.NETWORK.authErrorResource())
    }

    @Test
    fun everyNextMoeAuthErrorMapsToValidUniqueStringResource() {
        val resources = NextMoeAuthError.entries.map { it.authErrorResource() }
        assertTrue(resources.all { it != 0 })
        assertEquals(NextMoeAuthError.entries.size, resources.toSet().size)
        assertEquals(R.string.auth_nextmoe_expired, NextMoeAuthError.EXPIRED.authErrorResource())
        assertEquals(R.string.play_error_nextmoe_token_permission, NextMoeAuthError.PERMISSION.authErrorResource())
        assertEquals(R.string.auth_nextmoe_id_token_nonce_failed, NextMoeAuthError.ID_TOKEN_NONCE_FAILED.authErrorResource())
    }
}
