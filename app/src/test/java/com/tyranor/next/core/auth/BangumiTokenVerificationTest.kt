package com.tyranor.next.core.auth

import com.tyranor.next.core.net.HttpJsonResult
import com.tyranor.next.core.net.testHttpRoundTrip
import com.tyranor.next.testing.AuthAndCoverTestStubsRule
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test

/**
 * Bangumi 个人令牌校验的分档映射与 /v0/me HTTP 校验流程：服务端明确拒绝（401/403 → TOKEN_INVALID）与
 * 网络/服务异常（传输失败与其余非 2xx → NETWORK）必须区分，避免离线或服务波动时
 * 把校验失败一律误报成「令牌无效」、误导用户反复重粘令牌。
 */
class BangumiTokenVerificationTest {

    @get:Rule
    val stubsRule = AuthAndCoverTestStubsRule()

    @Test
    fun bangumiMeStatusToAuthErrorMapsExplicitRejectionToTokenInvalid() {
        assertEquals(BangumiAuthError.TOKEN_INVALID, bangumiMeStatusToAuthError(401))
        assertEquals(BangumiAuthError.TOKEN_INVALID, bangumiMeStatusToAuthError(403))
    }

    @Test
    fun bangumiMeStatusToAuthErrorFallsBackToNetworkForUndecidableStatuses() {
        // 传输失败（status=0）与其余非 2xx 保守按网络/服务异常处理
        assertEquals(BangumiAuthError.NETWORK, bangumiMeStatusToAuthError(0))
        assertEquals(BangumiAuthError.NETWORK, bangumiMeStatusToAuthError(400))
        assertEquals(BangumiAuthError.NETWORK, bangumiMeStatusToAuthError(429))
        assertEquals(BangumiAuthError.NETWORK, bangumiMeStatusToAuthError(500))
        assertEquals(BangumiAuthError.NETWORK, bangumiMeStatusToAuthError(503))
    }

    @Test
    fun tokenInvalidAndNetworkVerificationFailuresAreDistinct() {
        val rejected = BangumiTokenVerification.Failure(BangumiAuthError.TOKEN_INVALID)
        val unreachable = BangumiTokenVerification.Failure(BangumiAuthError.NETWORK)
        val success = BangumiTokenVerification.Success(BangumiAuthService.BangumiTokenIdentity(987654L, "bgm_user"))

        assertNotEquals(rejected, unreachable)
        assertNotEquals(rejected, success)
        assertEquals(BangumiAuthError.TOKEN_INVALID, rejected.error)
        assertEquals(BangumiAuthError.NETWORK, unreachable.error)
        assertEquals(987654L, success.identity.id)
    }

    @Test
    fun verifyPersonalTokenExercisesHttpSuccessRejectionAndNetworkBranches() {
        assertEquals(
            BangumiTokenVerification.Failure(BangumiAuthError.TOKEN_INVALID),
            BangumiAuthService.verifyPersonalToken("   "),
        )

        var seenAuth: String? = null
        testHttpRoundTrip = { url, method, _, headers ->
            assertEquals("https://api.bgm.tv/v0/me", url)
            assertEquals("GET", method)
            seenAuth = headers["Authorization"]
            HttpJsonResult(200, JSONObject().put("id", 42L).put("username", "bgm_tester").put("nickname", "Nick"))
        }
        val ok = BangumiAuthService.verifyPersonalToken("pat_secret")
        assertEquals("Bearer pat_secret", seenAuth)
        assertEquals(
            BangumiTokenVerification.Success(BangumiAuthService.BangumiTokenIdentity(42L, "bgm_tester")),
            ok,
        )

        testHttpRoundTrip = { _, _, _, _ -> HttpJsonResult(401, null) }
        assertEquals(
            BangumiTokenVerification.Failure(BangumiAuthError.TOKEN_INVALID),
            BangumiAuthService.verifyPersonalToken("pat_bad"),
        )

        testHttpRoundTrip = { _, _, _, _ -> HttpJsonResult(502, null) }
        assertEquals(
            BangumiTokenVerification.Failure(BangumiAuthError.NETWORK),
            BangumiAuthService.verifyPersonalToken("pat_net"),
        )

        testHttpRoundTrip = { _, _, _, _ -> HttpJsonResult(200, JSONObject().put("username", "missing_id")) }
        assertEquals(
            BangumiTokenVerification.Failure(BangumiAuthError.NETWORK),
            BangumiAuthService.verifyPersonalToken("pat_no_id"),
        )
    }
}
