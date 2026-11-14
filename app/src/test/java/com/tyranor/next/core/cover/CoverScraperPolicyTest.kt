package com.tyranor.next.core.cover

import com.tyranor.next.core.auth.HikarinagiAccessTokenResult
import com.tyranor.next.core.engine.EngineType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverScraperPolicyTest {

    @Test
    fun onlyMissingKeepsExistingLocalCoverOnly() {
        assertTrue(keepsLocalCover(onlyMissing = true, localCoverUri = "file:///covers/a.jpg"))
        assertFalse(keepsLocalCover(onlyMissing = true, localCoverUri = null))
        assertFalse(keepsLocalCover(onlyMissing = true, localCoverUri = " "))
        assertFalse(keepsLocalCover(onlyMissing = false, localCoverUri = "file:///covers/a.jpg"))
    }

    @Test
    fun xs4IconFallbackOnlyWhenNoCoverAndXs4() {
        assertTrue(Xs4IconCover.needsFallback(EngineType.XSYS4, null))
        assertTrue(Xs4IconCover.needsFallback(EngineType.XSYS4, ""))
        assertFalse(Xs4IconCover.needsFallback(EngineType.XSYS4, "file:///covers/a.jpg"))
        assertFalse(Xs4IconCover.needsFallback(EngineType.XSYS35, null))
    }

    /** 只补缺失时 XSYS4 无封面的游戏必须先走在线来源：此时 keepsLocalCover 为 false，图标兜底只在其后调用。 */
    @Test
    fun xs4WithoutCoverStillSearchesOnlineFirst() {
        assertFalse(keepsLocalCover(onlyMissing = true, localCoverUri = null))
        assertTrue(Xs4IconCover.needsFallback(EngineType.XSYS4, null))
    }

    @Test
    fun hikarinagiTokenFailureMapsNetworkSeparatelyFromAuthExpired() {
        assertEquals(
            CoverErrorCode.HIKARINAGI_NETWORK,
            CoverScraperService.hikarinagiTokenFailureToCoverError(
                HikarinagiAccessTokenResult.RefreshFailed,
                needsReauth = false,
            ),
        )
        assertEquals(
            CoverErrorCode.HIKARINAGI_AUTH_EXPIRED,
            CoverScraperService.hikarinagiTokenFailureToCoverError(
                HikarinagiAccessTokenResult.RefreshFailed,
                needsReauth = true,
            ),
        )
        assertEquals(
            CoverErrorCode.HIKARINAGI_AUTH_EXPIRED,
            CoverScraperService.hikarinagiTokenFailureToCoverError(
                HikarinagiAccessTokenResult.ConfigMissing,
                needsReauth = false,
            ),
        )
    }
}
