package com.tyranor.next.core.status

import com.tyranor.next.core.auth.BangumiAccessTokenResult
import com.tyranor.next.core.auth.BangumiAuthError
import com.tyranor.next.core.auth.BangumiAuthService
import com.tyranor.next.core.auth.BangumiAuthStore
import com.tyranor.next.core.auth.SecureAuthStore
import com.tyranor.next.core.net.HttpJsonResult
import com.tyranor.next.core.net.testHttpRoundTrip
import com.tyranor.next.testing.AuthAndCoverTestStubsRule
import com.tyranor.next.testing.InMemoryPreferencesContext
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Bangumi 游玩状态读回回归（R11 症状①②修复）：
 * 1. 写成功 → 重开读回：`GET /v0/users/{username}/collections/{subject_id}` 返回真实收藏状态
 *    （不再「已绑定」兜底），写路径 POST 基址不变；
 * 2. 读 404（JSON 错误体）= 未收藏独立状态而非错误（双清错误标记）；非 JSON 404 按网络档；
 * 3. 读 401 → needsReauth（EXPIRED）且后续读/写零网络短路；
 * 4. 读 403 → 独立 permission_error 位（R9-A 分位语义：不置 needsReauth、不锁刷新）；
 *    写 403 维持 BK-05A 只抛错不落档；
 * 5. 读成功双清 lastError + permission_error（仅令牌匹配；Keystore 哨兵不可擦除）；
 * 6. 身份完全未解析（无 username 且无 uid）按未授权档前置拒绝（零网络）；username 为数字
 *    uid 占位（身份回查一次性失败）时读回前置身份门——/v0/me 回查失败按「身份未解析」
 *    挂起（不以 uid 发起收藏 GET、不双清，消除 M2 假自愈），回查恢复后以真实用户名读回，
 *    未设用户名账号（回查确认）保留 uid 路径形态。
 */
class PlayStatusBangumiReadBackTest {

    @get:Rule
    val stubsRule = AuthAndCoverTestStubsRule(installInMemoryCipher = true)

    private lateinit var context: InMemoryPreferencesContext

    @Before
    fun setUp() {
        context = InMemoryPreferencesContext()
    }

    private fun saveAuthorizedTokens(access: String = "bgm_a1") {
        assertTrue(BangumiAuthStore.saveTokens(context, access, "bgm_r1", System.currentTimeMillis() + 3_600_000L))
        BangumiAuthStore.saveUserIdentity(context, 42L, "tester")
    }

    @Test
    fun bangumiWriteThenReopenReadBackShowsRealServerState() {
        saveAuthorizedTokens()
        val seen = mutableListOf<Triple<String, String, String?>>() // (method, url, body)
        testHttpRoundTrip = { url, method, body, headers ->
            seen.add(Triple(method, url, body))
            when {
                method == "POST" -> HttpJsonResult(204, null)
                // 写「玩过」成功后重开弹窗/抽屉：读回返回远端真实收藏类型（type=2 玩过）
                method == "GET" -> HttpJsonResult(200, JSONObject().put("type", BangumiCollectTypes.DONE))
                else -> HttpJsonResult(500, null)
            }.also { assertEquals("Bearer bgm_a1", headers["Authorization"]) }
        }

        // 写路径 POST 基址不变（`-` 通配仅写路径合法）
        PlayStatusService.writeBangumiStatus(context, "4154", BangumiCollectTypes.DONE)
        val write = seen.single { it.first == "POST" }
        assertEquals("https://api.bgm.tv/v0/users/-/collections/4154", write.second)
        assertEquals(JSONObject().put("type", BangumiCollectTypes.DONE).toString(), write.third)

        // 读回端点：真实用户名路径段 + Authorization 头（已持久化令牌），解析出真实状态
        val status = PlayStatusService.readBangumiStatus(context, "4154")
        val read = seen.single { it.first == "GET" }
        assertEquals("https://api.bgm.tv/v0/users/tester/collections/4154", read.second)
        assertNull(read.third)
        assertTrue(status.exists)
        assertEquals(BangumiCollectTypes.DONE, status.type)
        // 读成功即清（BK-23 对偶）：此刻无错误标记，读后依旧为空
        assertFalse(BangumiAuthStore.getStatus(context).needsReauth)
        assertFalse(BangumiAuthStore.getStatus(context).permissionDenied)
    }

    @Test
    fun bangumiRead404MeansNotCollectedIndependentStateAndStillClearsPermissionBit() {
        saveAuthorizedTokens()
        BangumiAuthStore.markPermissionDenied(context, BangumiAuthError.PERMISSION.name)

        // JSON 404（官方 spec：用户不存在 / 未收藏 / 私有收藏统一 404）= 未收藏，不算错误
        testHttpRoundTrip = { _, _, _, _ ->
            HttpJsonResult(404, JSONObject().put("title", "Not Found"))
        }
        val uncollected = PlayStatusService.readBangumiStatus(context, "4154")
        assertFalse(uncollected.exists)
        assertNull(uncollected.type)
        // 未收藏同样是「读成功」：双清错误标记（lastError 本就为空，权限位被清）
        assertFalse(BangumiAuthStore.getStatus(context).permissionDenied)
        assertFalse(BangumiAuthStore.getStatus(context).needsReauth)

        // 非 JSON 404（CDN/反代 HTML 错误页）不得误报未收藏：网络档且保留既有标记
        BangumiAuthStore.markPermissionDenied(context, BangumiAuthError.PERMISSION.name)
        testHttpRoundTrip = { _, _, _, _ -> HttpJsonResult(404, null) }
        try {
            PlayStatusService.readBangumiStatus(context, "4154")
            fail("expected BANGUMI_NETWORK on non-json 404")
        } catch (e: PlayStatusException) {
            assertEquals(PlayStatusError.BANGUMI_NETWORK, e.error)
        }
        assertTrue(BangumiAuthStore.getStatus(context).permissionDenied)
    }

    @Test
    fun bangumiRead401MarksNeedsReauthAndShortCircuitsSubsequentRequests() {
        saveAuthorizedTokens()
        val httpCalls = AtomicInteger(0)
        testHttpRoundTrip = { _, _, _, _ ->
            httpCalls.incrementAndGet()
            HttpJsonResult(401, null)
        }
        try {
            PlayStatusService.readBangumiStatus(context, "4154")
            fail("expected BANGUMI_TOKEN_EXPIRED on read 401")
        } catch (e: PlayStatusException) {
            assertEquals(PlayStatusError.BANGUMI_TOKEN_EXPIRED, e.error)
        }
        val status = BangumiAuthStore.getStatus(context)
        assertTrue(status.authorized)
        assertTrue(status.needsReauth)
        assertEquals("bgm_a1", BangumiAuthStore.readAccessSnapshot(context).accessToken)

        // 置位后读/写取令牌均被刷新短路，零网络重试（自愈途径 = 重新授权 / 重贴令牌）
        assertEquals(BangumiAccessTokenResult.RefreshFailed, BangumiAuthService.getValidAccessTokenResult(context))
        try {
            PlayStatusService.readBangumiStatus(context, "4154")
            fail("expected BANGUMI_TOKEN_EXPIRED short-circuit")
        } catch (e: PlayStatusException) {
            assertEquals(PlayStatusError.BANGUMI_TOKEN_EXPIRED, e.error)
        }
        assertEquals(1, httpCalls.get())
    }

    @Test
    fun bangumiRead403SetsPermissionBitOnlyWhileWrite403StaysUnmarked() {
        saveAuthorizedTokens()

        // 写路径 403：BK-05A 维持只抛错不落档（不触碰权限位）
        testHttpRoundTrip = { _, method, _, _ -> HttpJsonResult(403, null) }
        try {
            PlayStatusService.writeBangumiStatus(context, "4154", BangumiCollectTypes.DONE)
            fail("expected BANGUMI_TOKEN_PERMISSION on write 403")
        } catch (e: PlayStatusException) {
            assertEquals(PlayStatusError.BANGUMI_TOKEN_PERMISSION, e.error)
        }
        assertFalse(BangumiAuthStore.getStatus(context).permissionDenied)
        assertFalse(BangumiAuthStore.getStatus(context).needsReauth)

        // 读路径 403：R9-A 分位语义——置位独立 permission_error 位，不置 needsReauth
        try {
            PlayStatusService.readBangumiStatus(context, "4154")
            fail("expected BANGUMI_TOKEN_PERMISSION on read 403")
        } catch (e: PlayStatusException) {
            assertEquals(PlayStatusError.BANGUMI_TOKEN_PERMISSION, e.error)
        }
        val status = BangumiAuthStore.getStatus(context)
        assertTrue(status.permissionDenied)
        assertFalse(status.needsReauth)

        // 权限位不锁刷新短路：令牌仍在有效期，取令牌直接成功（与写 401 置位后的 RefreshFailed 对照）
        val tokenResult = BangumiAuthService.getValidAccessTokenResult(context)
        assertTrue(tokenResult is BangumiAccessTokenResult.Success)
    }

    @Test
    fun finishBangumiStatusReadClearsFlagsOnlyForMatchingTokenAndKeepsSentinel() {
        saveAuthorizedTokens(access = "bgm_tok")
        val okJson = JSONObject().put("type", BangumiCollectTypes.ON_HOLD)
        BangumiAuthStore.markNeedsReauth(context, BangumiAuthError.EXPIRED.name)
        BangumiAuthStore.markPermissionDenied(context, BangumiAuthError.PERMISSION.name)

        // 令牌不匹配（并发换号/登出）：双标记保留，防跨号误清
        val stale = PlayStatusService.finishBangumiStatusRead(context, "bgm_old", okJson)
        assertTrue(stale.exists)
        assertEquals(BangumiCollectTypes.ON_HOLD, stale.type)
        assertTrue(BangumiAuthStore.getStatus(context).needsReauth)
        assertTrue(BangumiAuthStore.getStatus(context).permissionDenied)

        // Keystore 落盘失败哨兵仅允许成功加密写盘或 clear() 清除，读回成功不得擦除
        BangumiAuthStore.clear(context)
        saveAuthorizedTokens(access = "bgm_tok")
        BangumiAuthStore.markPermissionDenied(context, BangumiAuthError.PERMISSION.name)
        BangumiAuthStore.markNeedsReauth(context, SecureAuthStore.SECURE_STORAGE_ERROR)
        PlayStatusService.finishBangumiStatusRead(context, "bgm_tok", okJson)
        assertEquals(SecureAuthStore.SECURE_STORAGE_ERROR, BangumiAuthStore.getStatus(context).lastError)
        assertFalse(BangumiAuthStore.getStatus(context).permissionDenied)

        // 令牌匹配的读成功：双清 lastError（needsReauth 位随之复位）与 permission_error
        BangumiAuthStore.clear(context)
        saveAuthorizedTokens(access = "bgm_tok")
        BangumiAuthStore.markNeedsReauth(context, BangumiAuthError.EXPIRED.name)
        BangumiAuthStore.markPermissionDenied(context, BangumiAuthError.PERMISSION.name)
        val cleared = PlayStatusService.finishBangumiStatusRead(context, "bgm_tok", okJson)
        assertTrue(cleared.exists)
        assertEquals(BangumiCollectTypes.ON_HOLD, cleared.type)
        assertFalse(BangumiAuthStore.getStatus(context).needsReauth)
        assertFalse(BangumiAuthStore.getStatus(context).permissionDenied)

        // 404 归一的未收藏（json == null）同样算读成功并双清
        BangumiAuthStore.markNeedsReauth(context, BangumiAuthError.EXPIRED.name)
        BangumiAuthStore.markPermissionDenied(context, BangumiAuthError.PERMISSION.name)
        val notCollected = PlayStatusService.finishBangumiStatusRead(context, "bgm_tok", null)
        assertFalse(notCollected.exists)
        assertNull(notCollected.type)
        assertFalse(BangumiAuthStore.getStatus(context).needsReauth)
        assertFalse(BangumiAuthStore.getStatus(context).permissionDenied)
    }

    @Test
    fun bangumiReadRejectsFullyMissingIdentityBeforeHttpWithoutNetwork() {
        val httpCalls = AtomicInteger(0)
        testHttpRoundTrip = { _, _, _, _ ->
            httpCalls.incrementAndGet()
            HttpJsonResult(200, JSONObject().put("type", BangumiCollectTypes.WISH))
        }

        // 身份完全未解析（无 username 且无 uid）：按未授权档前置拒绝，零网络
        assertTrue(BangumiAuthStore.saveTokens(context, "bgm_a1", "bgm_r1", System.currentTimeMillis() + 3_600_000L))
        try {
            PlayStatusService.readBangumiStatus(context, "4154")
            fail("expected BANGUMI_AUTH_MISSING when identity unresolved")
        } catch (e: PlayStatusException) {
            assertEquals(PlayStatusError.BANGUMI_AUTH_MISSING, e.error)
        }
        assertEquals(0, httpCalls.get())
    }

    @Test
    fun bangumiReadSuspendsWithIdentityUnresolvedInsteadOfNormalizing404AsUncollected() {
        // M2 假自愈消除：uid 占位（授权完成时身份回查一次性失败的账号）+ /v0/me 仍失败 →
        // 读回按「身份未解析」档挂起：不以 uid 发起收藏 GET，不把 404 归一「未收藏」并双清
        assertTrue(BangumiAuthStore.saveTokens(context, "bgm_a1", "bgm_r1", System.currentTimeMillis() + 3_600_000L))
        // saveUserIdentity 的 BK-19 兜底让 username 落为数字 uid 占位串
        BangumiAuthStore.saveUserIdentity(context, 42L, "")
        BangumiAuthStore.markPermissionDenied(context, BangumiAuthError.PERMISSION.name)
        val meCalls = AtomicInteger(0)
        val collectionCalls = AtomicInteger(0)
        testHttpRoundTrip = { url, _, _, _ ->
            if (url.contains("/v0/me")) {
                meCalls.incrementAndGet()
                HttpJsonResult(500, null) // 身份回查失败（服务异常，可重试）
            } else {
                collectionCalls.incrementAndGet()
                HttpJsonResult(404, JSONObject().put("title", "Not Found"))
            }
        }
        try {
            PlayStatusService.readBangumiStatus(context, "4154")
            fail("expected BANGUMI_IDENTITY_UNRESOLVED when identity lookup keeps failing")
        } catch (e: PlayStatusException) {
            assertEquals(PlayStatusError.BANGUMI_IDENTITY_UNRESOLVED, e.error)
        }
        assertEquals(1, meCalls.get())
        assertEquals(0, collectionCalls.get())
        // 假自愈消除：permission_error 未被双清，needsReauth 未被触碰
        assertTrue(BangumiAuthStore.getStatus(context).permissionDenied)
        assertFalse(BangumiAuthStore.getStatus(context).needsReauth)
    }

    @Test
    fun bangumiReadRecoversViaIdentityLookupAndThenReadsWithResolvedUsername() {
        // 回查恢复场景：uid 占位 + /v0/me 成功（真实用户名）→ 读回以解析后的用户名发起，
        // 404 JSON 归一未收藏并双清；解析结果落库后后续读回零 /v0/me 重查
        assertTrue(BangumiAuthStore.saveTokens(context, "bgm_a1", "bgm_r1", System.currentTimeMillis() + 3_600_000L))
        BangumiAuthStore.saveUserIdentity(context, 42L, "")
        BangumiAuthStore.markPermissionDenied(context, BangumiAuthError.PERMISSION.name)
        val meCalls = AtomicInteger(0)
        val readUrls = mutableListOf<String>()
        testHttpRoundTrip = { url, _, _, _ ->
            if (url.contains("/v0/me")) {
                meCalls.incrementAndGet()
                HttpJsonResult(200, JSONObject().put("id", 42).put("username", "tester"))
            } else {
                readUrls.add(url)
                HttpJsonResult(404, JSONObject().put("title", "Not Found"))
            }
        }
        val status = PlayStatusService.readBangumiStatus(context, "4154")
        assertFalse(status.exists)
        assertNull(status.type)
        assertEquals(listOf("https://api.bgm.tv/v0/users/tester/collections/4154"), readUrls)
        assertEquals(1, meCalls.get())
        // 读成功（含未收藏）双清照常收敛
        assertFalse(BangumiAuthStore.getStatus(context).permissionDenied)
        assertFalse(BangumiAuthStore.getStatus(context).needsReauth)
        assertEquals("tester", BangumiAuthStore.getUsername(context))

        // 身份已解析：再次读回走快速路径，零 /v0/me
        PlayStatusService.readBangumiStatus(context, "4154")
        assertEquals(1, meCalls.get())
        assertEquals(2, readUrls.size)
    }

    @Test
    fun bangumiReadKeepsUidPathForConfirmedUsernamelessAccount() {
        // 未设用户名账号：/v0/me 回查成功且 username 缺失（解析层回退为 uid 串）→
        // 数字 uid 是该账号唯一合法读回形态，读回照常发起
        assertTrue(BangumiAuthStore.saveTokens(context, "bgm_a1", "bgm_r1", System.currentTimeMillis() + 3_600_000L))
        BangumiAuthStore.saveUserIdentity(context, 42L, "")
        testHttpRoundTrip = { url, _, _, _ ->
            if (url.contains("/v0/me")) {
                HttpJsonResult(200, JSONObject().put("id", 42).put("username", ""))
            } else {
                assertTrue(url.endsWith("/v0/users/42/collections/4154"))
                HttpJsonResult(200, JSONObject().put("type", BangumiCollectTypes.WISH))
            }
        }
        val status = PlayStatusService.readBangumiStatus(context, "4154")
        assertTrue(status.exists)
        assertEquals(BangumiCollectTypes.WISH, status.type)
    }

    @Test
    fun bangumiReadNonJson2xxBodyThrowsNetworkInsteadOfUnknownState() {
        saveAuthorizedTokens()
        // 2xx 空体/非 JSON（网关截断响应）：网络档，不得误报「未收藏」或「状态未知」
        testHttpRoundTrip = { _, _, _, _ -> HttpJsonResult(200, null) }
        try {
            PlayStatusService.readBangumiStatus(context, "4154")
            fail("expected BANGUMI_NETWORK on 2xx non-json body")
        } catch (e: PlayStatusException) {
            assertEquals(PlayStatusError.BANGUMI_NETWORK, e.error)
        }
    }
}
