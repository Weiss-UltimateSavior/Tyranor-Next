package com.tyranor.next.core.status

import com.tyranor.next.core.auth.BangumiAccessTokenResult
import com.tyranor.next.core.auth.BangumiAuthService
import com.tyranor.next.core.auth.BangumiAuthStore
import com.tyranor.next.core.auth.HikarinagiAuthError
import com.tyranor.next.core.auth.HikarinagiAuthService
import com.tyranor.next.core.auth.HikarinagiAuthStore
import com.tyranor.next.core.auth.NextMoeAuthStore
import com.tyranor.next.core.auth.SecureAuthStore
import com.tyranor.next.core.auth.VndbAuthError
import com.tyranor.next.core.auth.VndbAuthStore
import com.tyranor.next.core.net.HttpJsonResult
import com.tyranor.next.core.net.testHttpRoundTrip
import com.tyranor.next.testing.AuthAndCoverTestStubsRule
import com.tyranor.next.testing.InMemoryPreferencesContext
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * PlayStatusService HTTP 与解析矩阵回归（Gap 4 + 个人令牌 401 短路）：
 * 1. Bangumi 写路径 401 置位 needsReauth 且保留已存令牌、个人令牌后续调用零网络短路；403 不置 needsReauth；
 * 2. Hikarinagi/NextMoe 读路径 403 只置位 permissionDenied、不锁死 needsReauth；
 * 3. Hikarinagi/NextMoe 写路径 401/403 均不回写授权态（单侧约束）；
 * 4. writeNextMoeStatus 透传合法 completion，切到 wish 时剥离 completion；
 * 5. parseVndbUlistStatus 过滤 null / 非正整数，保留自定义 (>=10) 标签 id。
 */
class PlayStatusServiceHttpTest {

    @get:Rule
    val stubsRule = AuthAndCoverTestStubsRule(installInMemoryCipher = true)

    private lateinit var context: InMemoryPreferencesContext

    @Before
    fun setUp() {
        context = InMemoryPreferencesContext()
    }

    @Test
    fun bangumiWrite403DoesNotMarkNeedsReauthWhile401MarksNeedsReauthAndShortCircuitsPersonalToken() {
        // 个人令牌（expiresAt = 0L, refreshToken = ""）
        assertTrue(BangumiAuthStore.saveTokens(context, "bgm_personal_pat", "", expiresAtMillis = 0L))

        testHttpRoundTrip = { _, _, _, _ -> HttpJsonResult(403, null) }
        try {
            PlayStatusService.writeBangumiStatus(context, "4154", BangumiCollectTypes.DOING)
            fail("expected BANGUMI_TOKEN_PERMISSION on 403")
        } catch (e: PlayStatusException) {
            assertEquals(PlayStatusError.BANGUMI_TOKEN_PERMISSION, e.error)
        }
        assertFalse(BangumiAuthStore.getStatus(context).needsReauth)

        val httpCalls = AtomicInteger(0)
        testHttpRoundTrip = { _, _, _, _ ->
            httpCalls.incrementAndGet()
            HttpJsonResult(401, null)
        }
        try {
            PlayStatusService.writeBangumiStatus(context, "4154", BangumiCollectTypes.DOING)
            fail("expected BANGUMI_TOKEN_EXPIRED on 401")
        } catch (e: PlayStatusException) {
            assertEquals(PlayStatusError.BANGUMI_TOKEN_EXPIRED, e.error)
        }
        assertEquals(1, httpCalls.get())
        val statusAfter401 = BangumiAuthStore.getStatus(context)
        assertTrue(statusAfter401.authorized)
        assertTrue(statusAfter401.needsReauth)
        assertEquals("bgm_personal_pat", BangumiAuthStore.readAccessSnapshot(context).accessToken)

        // 后续读写/取令牌均被 needsReauth 短路，零网络重试
        assertEquals(BangumiAccessTokenResult.RefreshFailed, BangumiAuthService.getValidAccessTokenResult(context))
        try {
            PlayStatusService.writeBangumiStatus(context, "4154", BangumiCollectTypes.DONE)
            fail("expected BANGUMI_TOKEN_EXPIRED short-circuit")
        } catch (e: PlayStatusException) {
            assertEquals(PlayStatusError.BANGUMI_TOKEN_EXPIRED, e.error)
        }
        assertEquals(1, httpCalls.get())
    }

    @Test
    fun hikarinagiRead403SetsPermissionDeniedOnlyAndWrite401Or403NeverMutatesAuthState() {
        assertTrue(
            HikarinagiAuthStore.saveTokens(
                context,
                accessToken = "hkn_valid",
                refreshToken = "hkn_refresh",
                expiresAtMillis = System.currentTimeMillis() + 3_600_000L,
            ),
        )

        // 写路径 401 / 403 均保持单侧约束，不写 needsReauth / permissionDenied
        testHttpRoundTrip = { _, _, _, _ -> HttpJsonResult(401, null) }
        try {
            PlayStatusService.writeHikarinagiStatus(context, "gal-1", HikarinagiStatuses.GOING)
            fail("expected HIKARINAGI_TOKEN_EXPIRED")
        } catch (e: PlayStatusException) {
            assertEquals(PlayStatusError.HIKARINAGI_TOKEN_EXPIRED, e.error)
        }
        assertFalse(HikarinagiAuthStore.getStatus(context).needsReauth)

        testHttpRoundTrip = { _, _, _, _ -> HttpJsonResult(403, null) }
        try {
            PlayStatusService.writeHikarinagiStatus(context, "gal-1", HikarinagiStatuses.GOING)
            fail("expected HIKARINAGI_TOKEN_PERMISSION")
        } catch (e: PlayStatusException) {
            assertEquals(PlayStatusError.HIKARINAGI_TOKEN_PERMISSION, e.error)
        }
        assertFalse(HikarinagiAuthStore.getStatus(context).permissionDenied)
        assertFalse(HikarinagiAuthStore.getStatus(context).needsReauth)

        // 读路径 403 仅置位 permissionDenied，不置位 needsReauth
        try {
            PlayStatusService.readHikarinagiStatus(context, "gal-1")
            fail("expected HIKARINAGI_TOKEN_PERMISSION")
        } catch (e: PlayStatusException) {
            assertEquals(PlayStatusError.HIKARINAGI_TOKEN_PERMISSION, e.error)
        }
        val status = HikarinagiAuthStore.getStatus(context)
        assertTrue(status.permissionDenied)
        assertFalse(status.needsReauth)
    }

    @Test
    fun nextMoeRead403Write401And403AndBodyPreservationMatchProtocol() {
        assertTrue(
            NextMoeAuthStore.saveTokens(
                context,
                accessToken = "nm_valid",
                refreshToken = "nm_refresh",
                expiresAtMillis = System.currentTimeMillis() + 3_600_000L,
            ),
        )

        val sentBodies = mutableListOf<String?>()
        testHttpRoundTrip = { _, _, body, _ ->
            sentBodies.add(body)
            HttpJsonResult(204, null)
        }
        PlayStatusService.writeNextMoeStatus(context, "207379", NextMoeWorkStates.DOING, preservedCompletion = "all")
        PlayStatusService.writeNextMoeStatus(context, "207379", NextMoeWorkStates.WISH, preservedCompletion = "all")
        assertEquals("all", JSONObject(sentBodies[0]!!).getString("completion"))
        assertFalse(JSONObject(sentBodies[1]!!).has("completion"))

        // 写路径 401/403 不修改授权状态
        testHttpRoundTrip = { _, _, _, _ -> HttpJsonResult(401, null) }
        try {
            PlayStatusService.writeNextMoeStatus(context, "207379", NextMoeWorkStates.DONE)
            fail("expected NEXTMOE_TOKEN_EXPIRED")
        } catch (e: PlayStatusException) {
            assertEquals(PlayStatusError.NEXTMOE_TOKEN_EXPIRED, e.error)
        }
        assertFalse(NextMoeAuthStore.getStatus(context).needsReauth)

        testHttpRoundTrip = { _, _, _, _ -> HttpJsonResult(403, null) }
        try {
            PlayStatusService.writeNextMoeStatus(context, "207379", NextMoeWorkStates.DONE)
            fail("expected NEXTMOE_TOKEN_PERMISSION")
        } catch (e: PlayStatusException) {
            assertEquals(PlayStatusError.NEXTMOE_TOKEN_PERMISSION, e.error)
        }
        assertFalse(NextMoeAuthStore.getStatus(context).permissionDenied)

        // 读路径 403 仅置位 permissionDenied
        try {
            PlayStatusService.readNextMoeStatus(context, "207379")
            fail("expected NEXTMOE_TOKEN_PERMISSION")
        } catch (e: PlayStatusException) {
            assertEquals(PlayStatusError.NEXTMOE_TOKEN_PERMISSION, e.error)
        }
        val status = NextMoeAuthStore.getStatus(context)
        assertTrue(status.permissionDenied)
        assertFalse(status.needsReauth)
    }

    @Test
    fun parseVndbUlistStatusFiltersNonPositiveNullAndCustomLabelIds() {
        val json = JSONObject().put(
            "results",
            JSONArray().put(
                JSONObject()
                    .put("id", "v562")
                    .put(
                        "labels",
                        JSONArray()
                            .put(JSONObject().put("id", JSONObject.NULL))
                            .put(JSONObject().put("id", 0))
                            .put(JSONObject().put("id", -3))
                            .put(JSONObject().put("id", 7))
                            .put(JSONObject().put("id", 15))
                            .put(JSONObject().put("id", VndbLabels.FINISHED)),
                    ),
            ),
        )

        val parsed = parseVndbUlistStatus(json, "v562")
        assertEquals(setOf(VndbLabels.FINISHED, 7, 15), parsed.labelIds)

        val onlyInvalid = JSONObject().put(
            "results",
            JSONArray().put(
                JSONObject()
                    .put("id", "v562")
                    .put(
                        "labels",
                        JSONArray()
                            .put(JSONObject().put("id", 0))
                            .put(JSONObject().put("id", -1))
                            .put(JSONObject().put("id", JSONObject.NULL)),
                    ),
            ),
        )
        val cleared = parseVndbUlistStatus(onlyInvalid, "v562")
        assertTrue(cleared.labelIds.isEmpty())
    }

    @Test
    fun vndbBlocksTokenInvalidAndSecureStorageErrorPreRequestAndAllowsPermissionRetry() {
        assertTrue(
            com.tyranor.next.core.auth.VndbAuthStore.saveToken(
                context = context,
                token = "vndb_pat_1",
                userId = "u123",
                username = "tester",
                permissions = listOf("listread", "listwrite"),
            ),
        )
        var calls = 0
        testHttpRoundTrip = { _, _, _, _ ->
            calls++
            HttpJsonResult(200, JSONObject().put("results", JSONArray()))
        }

        // 1. TOKEN_INVALID 与 SECURE_STORAGE_ERROR 均在请求前阻断，零网络且不覆盖 SECURE_STORAGE_ERROR
        for (reason in listOf(
            com.tyranor.next.core.auth.VndbAuthError.TOKEN_INVALID.name,
            com.tyranor.next.core.auth.SecureAuthStore.SECURE_STORAGE_ERROR,
        )) {
            com.tyranor.next.core.auth.VndbAuthStore.markLastError(context, reason)

            for (action in listOf(
                { PlayStatusService.readVndbStatus(context, "v562") },
                { PlayStatusService.writeVndbStatus(context, "v562", VndbLabels.FINISHED) },
            )) {
                try {
                    action()
                    fail("expected VNDB_TOKEN_INVALID pre-request block for $reason")
                } catch (e: PlayStatusException) {
                    assertEquals(PlayStatusError.VNDB_TOKEN_INVALID, e.error)
                }
            }
            org.junit.Assert.assertNull(PlayStatusService.checkVndbPermissions(context))
            assertEquals(reason, com.tyranor.next.core.auth.VndbAuthStore.getStatus(context).lastError)
        }
        assertEquals(0, calls)

        // 空 reason 不得抹除 SECURE_STORAGE_ERROR 哨兵
        com.tyranor.next.core.auth.VndbAuthStore.markLastErrorIfTokenMatches(context, "vndb_pat_1", "")
        assertEquals(
            com.tyranor.next.core.auth.SecureAuthStore.SECURE_STORAGE_ERROR,
            com.tyranor.next.core.auth.VndbAuthStore.getStatus(context).lastError,
        )

        // 2. PERMISSION 是唯一允许直接重试的路径：成功重新保存新令牌清哨兵后，模拟 403 写入 PERMISSION 并重试自愈
        assertTrue(
            com.tyranor.next.core.auth.VndbAuthStore.saveToken(
                context = context,
                token = "vndb_pat_1",
                userId = "u123",
                username = "tester",
                permissions = listOf("listread", "listwrite"),
            ),
        )
        com.tyranor.next.core.auth.VndbAuthStore.markLastError(
            context,
            com.tyranor.next.core.auth.VndbAuthError.PERMISSION.name,
        )
        val status = PlayStatusService.readVndbStatus(context, "v562")
        assertTrue(status.labelIds.isEmpty())
        assertEquals(1, calls)
        assertEquals("", com.tyranor.next.core.auth.VndbAuthStore.getStatus(context).lastError)
    }

    @Test
    fun nextMoeNonJson404ThrowsNetworkErrorAndPathSegmentsPercentEncodeSpaces() {
        assertTrue(
            NextMoeAuthStore.saveTokens(
                context = context,
                accessToken = "nm_valid",
                refreshToken = "nm_refresh",
                expiresAtMillis = System.currentTimeMillis() + 3_600_000L,
            ),
        )
        assertTrue(
            HikarinagiAuthStore.saveTokens(
                context = context,
                accessToken = "hkn_valid",
                refreshToken = "hkn_refresh",
                expiresAtMillis = System.currentTimeMillis() + 3_600_000L,
            ),
        )

        val seenUrls = mutableListOf<String>()
        val seenAuths = mutableListOf<Pair<String, String?>>()
        testHttpRoundTrip = { url, _, _, headers ->
            seenUrls.add(url)
            seenAuths.add(url to headers["Authorization"])
            when {
                url.contains("nextmoe") -> HttpJsonResult(404, null)
                else -> HttpJsonResult(
                    200,
                    JSONObject().put("success", true).put("data", JSONObject().put("status", 0)),
                )
            }
        }

        try {
            PlayStatusService.readNextMoeStatus(context, "id 1")
            fail("expected NEXTMOE_NETWORK on non-JSON 404")
        } catch (e: PlayStatusException) {
            assertEquals(PlayStatusError.NEXTMOE_NETWORK, e.error)
        }
        PlayStatusService.readHikarinagiStatus(context, "id 1")
        assertTrue(seenUrls.any { it.contains("nextmoe") && it.endsWith("/id%201") })
        assertTrue(seenUrls.any { !it.contains("nextmoe") && it.endsWith("/id%201") })
        assertEquals("Bearer nm_valid", seenAuths.first { it.first.contains("nextmoe") }.second)
        assertEquals("Bearer hkn_valid", seenAuths.first { !it.first.contains("nextmoe") }.second)
    }

    @Test
    fun secureStorageSentinelIsPreservedAcrossConcurrentReadClearsAndVndbErrors() {
        val future = System.currentTimeMillis() + 3_600_000L
        assertTrue(HikarinagiAuthStore.saveTokens(context, "hkn_tok", "hkn_ref", future))
        HikarinagiAuthStore.markNeedsReauth(context, SecureAuthStore.SECURE_STORAGE_ERROR)
        assertFalse(HikarinagiAuthStore.clearLastErrorIfAccessTokenMatches(context, "hkn_tok"))
        assertEquals(SecureAuthStore.SECURE_STORAGE_ERROR, HikarinagiAuthStore.getStatus(context).lastError)
        PlayStatusService.finishHikarinagiStatusRead(
            context,
            "hkn_tok",
            JSONObject().put("success", true).put("data", JSONObject.NULL),
        )
        assertEquals(SecureAuthStore.SECURE_STORAGE_ERROR, HikarinagiAuthStore.getStatus(context).lastError)

        assertTrue(NextMoeAuthStore.saveTokens(context, "nm_tok", "nm_ref", future))
        NextMoeAuthStore.markNeedsReauth(context, SecureAuthStore.SECURE_STORAGE_ERROR)
        assertFalse(NextMoeAuthStore.clearLastErrorIfAccessTokenMatches(context, "nm_tok"))
        assertEquals(SecureAuthStore.SECURE_STORAGE_ERROR, NextMoeAuthStore.getStatus(context).lastError)
        PlayStatusService.finishNextMoeStatusRead(
            context,
            "nm_tok",
            JSONObject().put("state", NextMoeWorkStates.DOING),
        )
        assertEquals(SecureAuthStore.SECURE_STORAGE_ERROR, NextMoeAuthStore.getStatus(context).lastError)

        assertTrue(VndbAuthStore.saveToken(context, "vndb_pat_2", "u1", "tester", listOf("listread")))
        VndbAuthStore.markLastError(context, SecureAuthStore.SECURE_STORAGE_ERROR)
        VndbAuthStore.markLastErrorIfTokenMatches(context, "vndb_pat_2", VndbAuthError.PERMISSION.name)
        VndbAuthStore.markLastErrorIfTokenMatches(context, "vndb_pat_2", VndbAuthError.TOKEN_INVALID.name)
        VndbAuthStore.markLastErrorIfTokenMatches(context, "vndb_pat_2", "")
        assertEquals(SecureAuthStore.SECURE_STORAGE_ERROR, VndbAuthStore.getStatus(context).lastError)
    }

    @Test
    fun hikarinagiRead404DistinguishesGatewayHtmlFromStructuredNotFound() {
        assertTrue(
            HikarinagiAuthStore.saveTokens(
                context,
                accessToken = "hkn_valid",
                refreshToken = "hkn_refresh",
                expiresAtMillis = System.currentTimeMillis() + 3_600_000L,
            ),
        )
        HikarinagiAuthStore.markPermissionDenied(context, HikarinagiAuthError.PERMISSION.name)

        // 非 JSON 404（网关 HTML 错误页）-> 网络错，且不清除既有的权限标记
        testHttpRoundTrip = { _, _, _, _ -> HttpJsonResult(404, null) }
        try {
            PlayStatusService.readHikarinagiStatus(context, "gal-404")
            fail("expected HIKARINAGI_NETWORK on non-JSON 404")
        } catch (e: PlayStatusException) {
            assertEquals(PlayStatusError.HIKARINAGI_NETWORK, e.error)
        }
        assertTrue(HikarinagiAuthStore.getStatus(context).permissionDenied)

        // 结构化 JSON 404（GALGAME_NOT_FOUND）-> 条目不存在，且不清除既有的权限标记
        testHttpRoundTrip = { _, _, _, _ ->
            HttpJsonResult(404, JSONObject().put("success", false).put("err", "GALGAME_NOT_FOUND"))
        }
        try {
            PlayStatusService.readHikarinagiStatus(context, "gal-404")
            fail("expected HIKARINAGI_SUBJECT_NOT_FOUND on JSON 404")
        } catch (e: PlayStatusException) {
            assertEquals(PlayStatusError.HIKARINAGI_SUBJECT_NOT_FOUND, e.error)
        }
        assertTrue(HikarinagiAuthStore.getStatus(context).permissionDenied)
    }

    @Test
    fun nextmoe2xxProblemRateLimitCodesMapToNextmoeRateLimited() {
        assertTrue(
            NextMoeAuthStore.saveTokens(
                context,
                accessToken = "nm_valid",
                refreshToken = "nm_refresh",
                expiresAtMillis = System.currentTimeMillis() + 3_600_000L,
            ),
        )
        for (code in listOf("RATE_LIMITED", "QUOTA_EXCEEDED")) {
            testHttpRoundTrip = { _, _, _, _ ->
                HttpJsonResult(200, JSONObject().put("code", code))
            }
            try {
                PlayStatusService.readNextMoeStatus(context, "work-1")
                fail("expected NEXTMOE_RATE_LIMITED for 2xx code=$code")
            } catch (e: PlayStatusException) {
                assertEquals(PlayStatusError.NEXTMOE_RATE_LIMITED, e.error)
            }
        }
    }

    @Test
    fun readPathConcurrentLogoutDuringRefreshMapsToAuthMissing() {
        assertTrue(
            HikarinagiAuthStore.saveTokens(
                context,
                accessToken = "hkn_expired",
                refreshToken = "hkn_refresh",
                expiresAtMillis = 1_000L,
            ),
        )
        HikarinagiAuthService.testClientId = "test-client"
        HikarinagiAuthService.testRefreshHttpRoundTrip = { _, _ ->
            HikarinagiAuthStore.clear(context)
            400 to """{"error":"invalid_grant"}"""
        }
        try {
            PlayStatusService.readHikarinagiStatus(context, "gal-logout")
            fail("expected HIKARINAGI_AUTH_MISSING on concurrent logout during read refresh")
        } catch (e: PlayStatusException) {
            assertEquals(PlayStatusError.HIKARINAGI_AUTH_MISSING, e.error)
        }
    }
}
