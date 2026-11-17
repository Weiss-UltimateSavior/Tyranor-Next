package com.tyranor.next.ui.cover

import com.tyranor.next.R
import com.tyranor.next.core.auth.BangumiAuthStatus
import com.tyranor.next.core.auth.HikarinagiAuthStatus
import com.tyranor.next.core.auth.NextMoeAuthStatus
import com.tyranor.next.core.auth.SecureAuthStore
import com.tyranor.next.core.auth.VndbAuthStatus
import com.tyranor.next.core.cover.CoverErrorCode
import com.tyranor.next.core.settings.AppSettingsStore
import com.tyranor.next.core.status.PlayStatusError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverAndPlayStatusMessagesTest {

    @Test
    fun everyCoverErrorCodeMapsToValidUniqueStringResource() {
        val resources = CoverErrorCode.entries.map { it.coverErrorResource() }
        assertTrue(resources.all { it != 0 })
        assertEquals(CoverErrorCode.entries.size, resources.toSet().size)
        assertEquals(R.string.auth_hikarinagi_expired, CoverErrorCode.HIKARINAGI_AUTH_EXPIRED.coverErrorResource())
        assertEquals(R.string.cover_error_nextmoe_rate_limited, CoverErrorCode.NEXTMOE_RATE_LIMITED.coverErrorResource())
    }

    @Test
    fun everyPlayStatusErrorMapsToValidUniqueStringResource() {
        val resources = PlayStatusError.entries.map { it.playStatusErrorResource() }
        assertTrue(resources.all { it != 0 })
        assertEquals(PlayStatusError.entries.size, resources.toSet().size)
        assertEquals(R.string.play_error_auth_session_changed, PlayStatusError.AUTH_SESSION_CHANGED.playStatusErrorResource())
        assertEquals(R.string.play_error_nextmoe_rate_limited, PlayStatusError.NEXTMOE_RATE_LIMITED.playStatusErrorResource())
    }

    @Test
    fun coverSourceSummaryResCoversSecureStorageStaleAuthAndPermissions() {
        assertEquals(
            R.string.cover_hikarinagi_secure_storage_summary,
            coverSourceSummaryRes(
                source = AppSettingsStore.COVER_SOURCE_HIKARINAGI,
                hikarinagiStatus = hikarinagi(
                    authorized = true,
                    needsReauth = true,
                    lastError = SecureAuthStore.SECURE_STORAGE_ERROR,
                ),
            ),
        )
        assertEquals(
            R.string.cover_hikarinagi_reauth_summary,
            coverSourceSummaryRes(
                source = AppSettingsStore.COVER_SOURCE_HIKARINAGI,
                hikarinagiStatus = hikarinagi(authorized = true, needsReauth = true),
            ),
        )
        assertEquals(
            R.string.play_error_hikarinagi_token_permission,
            coverSourceSummaryRes(
                source = AppSettingsStore.COVER_SOURCE_HIKARINAGI,
                hikarinagiStatus = hikarinagi(authorized = true, permissionDenied = true),
            ),
        )
        assertEquals(
            R.string.cover_bangumi_reauth_summary,
            coverSourceSummaryRes(
                source = AppSettingsStore.COVER_SOURCE_BANGUMI,
                bangumiStatus = bangumi(authorized = true, needsReauth = true),
            ),
        )
        assertEquals(
            R.string.play_error_bangumi_token_permission,
            coverSourceSummaryRes(
                source = AppSettingsStore.COVER_SOURCE_BANGUMI,
                bangumiStatus = bangumi(authorized = true, permissionDenied = true),
            ),
        )
        assertEquals(
            R.string.auth_bangumi_auth_stale,
            coverSourceSummaryRes(
                source = AppSettingsStore.COVER_SOURCE_BANGUMI,
                bangumiStatus = bangumi(authorized = false),
                bangumiAuthStale = true,
            ),
        )
        assertEquals(
            R.string.cover_nextmoe_reauth_summary,
            coverSourceSummaryRes(
                source = AppSettingsStore.COVER_SOURCE_NEXTMOE,
                nextmoeStatus = nextmoe(authorized = true, needsReauth = true),
            ),
        )
        assertEquals(
            R.string.auth_nextmoe_auth_stale,
            coverSourceSummaryRes(
                source = AppSettingsStore.COVER_SOURCE_NEXTMOE,
                nextmoeStatus = nextmoe(authorized = false),
                nextmoeAuthStale = true,
            ),
        )
        assertEquals(
            R.string.play_error_nextmoe_token_permission,
            coverSourceSummaryRes(
                source = AppSettingsStore.COVER_SOURCE_NEXTMOE,
                nextmoeStatus = nextmoe(authorized = true, permissionDenied = true),
            ),
        )
        assertEquals(
            R.string.cover_vndb_summary,
            coverSourceSummaryRes(
                source = AppSettingsStore.COVER_SOURCE_VNDB,
                vndbStatus = VndbAuthStatus(
                    authorized = false,
                    needsReauth = false,
                    username = "",
                    lastError = "",
                ),
            ),
        )
    }

    private fun hikarinagi(
        authorized: Boolean = false,
        needsReauth: Boolean = false,
        permissionDenied: Boolean = false,
        lastError: String = "",
    ) = HikarinagiAuthStatus(
        authorized = authorized,
        needsReauth = needsReauth,
        permissionDenied = permissionDenied,
        expiresAtMillis = 0L,
        lastError = lastError,
    )

    private fun bangumi(
        authorized: Boolean = false,
        needsReauth: Boolean = false,
        permissionDenied: Boolean = false,
        lastError: String = "",
    ) = BangumiAuthStatus(
        authorized = authorized,
        needsReauth = needsReauth,
        permissionDenied = permissionDenied,
        expiresAtMillis = 0L,
        lastError = lastError,
    )

    private fun nextmoe(
        authorized: Boolean = false,
        needsReauth: Boolean = false,
        permissionDenied: Boolean = false,
        lastError: String = "",
    ) = NextMoeAuthStatus(
        authorized = authorized,
        needsReauth = needsReauth,
        permissionDenied = permissionDenied,
        expiresAtMillis = 0L,
        lastError = lastError,
    )
}
