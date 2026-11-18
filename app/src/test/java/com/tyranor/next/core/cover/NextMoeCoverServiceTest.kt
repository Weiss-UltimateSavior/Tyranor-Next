package com.tyranor.next.core.cover

import com.tyranor.next.core.auth.NextMoeAuthStore
import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.net.HttpJsonResult
import com.tyranor.next.core.settings.AppSettingsStore
import com.tyranor.next.testing.AuthAndCoverTestStubsRule
import com.tyranor.next.testing.InMemoryPreferencesContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * 未萌 NextMoe 候选解析与检索回归：
 * 1. 一级作品解析携带 nextmoeId 与有效中文名，且仅在带 VNDB work ref 时携带 vndbId；
 * 2. 二级多封面映射继承父候选的 nextmoeId（work id），不得把 cover.id 误写入 nextmoeId；
 * 3. searchCandidates（refs=vndb: 精确反查 vs 关键词模糊检索清空 vndbId）与 listCovers（portrait_pinned 置顶排序）。
 */
class NextMoeCoverServiceTest {

    @get:Rule
    val stubsRule = AuthAndCoverTestStubsRule(installInMemoryCipher = true)

    private lateinit var context: InMemoryPreferencesContext

    @Before
    fun setUp() {
        context = InMemoryPreferencesContext()
    }

    @Test
    fun parseWorkCarriesNextMoeIdAndVndbWorkRefOnly() {
        val work = JSONObject()
            .put("id", "207379")
            .put("display_name", "サクラノ詩")
            .put("latin", "Sakura no Uta")
            .put("release_date", "2015-10-23")
            .put(
                "localized",
                JSONObject().put(
                    "zh-Hans",
                    JSONObject().put("value", "樱之诗").put("is_machine", false),
                ),
            )
            .put(
                "cover",
                JSONObject().put("url", "https://img.nextmoe.dev/cover.webp").put("width", 600).put("height", 800),
            )
            .put(
                "refs",
                JSONArray()
                    .put(JSONObject().put("source", "vndb").put("external_id", "r12345"))
                    .put(JSONObject().put("source", "vndb").put("external_id", "v562")),
            )

        val candidate = NextMoeCoverService.parseWork(work)
        assertEquals(AppSettingsStore.COVER_SOURCE_NEXTMOE, candidate.source)
        assertEquals("207379", candidate.id)
        assertEquals("207379", candidate.nextmoeId)
        assertEquals("v562", candidate.vndbId)
        assertEquals("樱之诗", candidate.title)
        assertEquals("樱之诗", candidate.metadataTitle)
    }

    @Test
    fun listCoverMappingPreservesParentWorkIdInsteadOfCoverId() {
        val parent = CoverSearchCandidate(
            source = AppSettingsStore.COVER_SOURCE_NEXTMOE,
            id = "207379",
            title = "樱之诗",
            subtitle = "サクラノ詩",
            detail = "207379",
            coverUrl = "https://img.nextmoe.dev/main.webp",
            vndbId = "v562",
            nextmoeId = "207379",
            metadataTitle = "樱之诗",
        )
        val cover = NextMoeCover(
            id = "998877",
            kind = "pkgfront",
            url = "https://img.nextmoe.dev/pkgfront.webp",
            width = 800,
            height = 1100,
            sexual = "safe",
            pinned = false,
            votes = 12,
        )

        val mapped = NextMoeCoverService.mapCoverToCandidate(parent, cover)
        assertEquals("998877", mapped.id)
        assertEquals("207379", mapped.nextmoeId)
        assertEquals("v562", mapped.vndbId)
        assertEquals("樱之诗", mapped.metadataTitle)
    }

    @Test
    fun parseWorkIgnoresMachineTranslatedLocalizedTitle() {
        val work = JSONObject()
            .put("id", "123")
            .put("display_name", "Original Title")
            .put(
                "localized",
                JSONObject().put(
                    "zh-Hans",
                    JSONObject().put("value", "机翻标题").put("is_machine", true),
                ),
            )

        val candidate = NextMoeCoverService.parseWork(work)
        assertEquals("Original Title", candidate.title)
        assertEquals("123", candidate.nextmoeId)
        assertNull(candidate.vndbId)
    }

    @Test
    fun searchCandidatesUsesRefsWhenVndbIdMatchesAndClearsVndbIdOnFuzzyFallback() {
        val requestedUrls = mutableListOf<String>()
        NextMoeCoverService.testHttpRoundTrip = { url, _ ->
            requestedUrls.add(url)
            when {
                url.contains("refs=vndb%3Av562") -> HttpJsonResult(
                    200,
                    JSONObject().put(
                        "items",
                        JSONArray().put(
                            JSONObject()
                                .put("id", "207379")
                                .put("display_name", "サクラノ詩")
                                .put(
                                    "refs",
                                    JSONArray().put(JSONObject().put("source", "vndb").put("external_id", "v562")),
                                ),
                        ),
                    ),
                )
                url.contains("refs=") -> HttpJsonResult(200, JSONObject().put("items", JSONArray()))
                else -> HttpJsonResult(
                    200,
                    JSONObject().put(
                        "items",
                        JSONArray().put(
                            JSONObject()
                                .put("id", "999999")
                                .put("display_name", "Fuzzy Match")
                                .put(
                                    "refs",
                                    JSONArray().put(JSONObject().put("source", "vndb").put("external_id", "v8888")),
                                ),
                        ),
                    ),
                )
            }
        }

        // 1. refs=vndb:v562 精确反查命中时保留 vndbId
        val byRef = NextMoeCoverService.searchCandidates("樱之诗", "v562", limit = 5)
        assertEquals(1, byRef.size)
        assertEquals("207379", byRef.single().nextmoeId)
        assertEquals("v562", byRef.single().vndbId)
        assertTrue(requestedUrls.last().contains("refs=vndb%3Av562"))

        // 2. refs 未命中回退关键词模糊搜索，或无 vndbId 直接模糊搜索时必须清空 vndbId
        val fuzzy = NextMoeCoverService.searchCandidates("樱之诗", "v0000", limit = 5)
        assertEquals(1, fuzzy.size)
        assertEquals("999999", fuzzy.single().nextmoeId)
        assertNull(fuzzy.single().vndbId)
    }

    @Test
    fun listCoversSortsPortraitPinnedFirstPreservingRelativeOrder() {
        NextMoeCoverService.testHttpRoundTrip = { url, _ ->
            assertTrue(url.contains("/works/207379/covers"))
            HttpJsonResult(
                200,
                JSONObject().put(
                    "items",
                    JSONArray()
                        .put(JSONObject().put("id", "c1").put("url", "https://img.nextmoe.dev/1.webp").put("portrait_pinned", false))
                        .put(JSONObject().put("id", "c2").put("url", "https://img.nextmoe.dev/2.webp").put("portrait_pinned", true))
                        .put(JSONObject().put("id", "c3").put("url", "https://img.nextmoe.dev/3.webp").put("portrait_pinned", false))
                        .put(JSONObject().put("id", "c4").put("url", "https://img.nextmoe.dev/4.webp").put("portrait_pinned", true)),
                ),
            )
        }

        val parent = CoverSearchCandidate(
            source = AppSettingsStore.COVER_SOURCE_NEXTMOE,
            id = "207379",
            title = "樱之诗",
            subtitle = "",
            detail = "",
            coverUrl = "",
            nextmoeId = "207379",
            vndbId = "v562",
            metadataTitle = "樱之诗",
        )
        val covers = NextMoeCoverService.listCovers(parent, limit = 10)
        assertEquals(listOf("c2", "c4", "c1", "c3"), covers.map { it.id })
        assertTrue(covers.all { it.nextmoeId == "207379" })
    }

    @Test
    fun fetchBestCoverUsesIdsThenSearchFallbackAndPreservesExistingBindings() {
        val urls = mutableListOf<String>()
        NextMoeCoverService.testHttpRoundTrip = { url, _ ->
            urls.add(url)
            if (url.contains("ids=207379")) {
                HttpJsonResult(200, JSONObject().put("items", JSONArray()))
            } else {
                HttpJsonResult(
                    200,
                    JSONObject().put(
                        "items",
                        JSONArray()
                            .put(
                                JSONObject()
                                    .put("id", "300001")
                                    .put("display_name", "Fallback First Fail")
                                    .put("cover", JSONObject().put("url", "https://img.nextmoe.dev/also-fail.webp")),
                            )
                            .put(
                                JSONObject()
                                    .put("id", "300002")
                                    .put("display_name", "Fallback Winner")
                                    .put("cover", JSONObject().put("url", "https://img.nextmoe.dev/win.webp")),
                            ),
                    ),
                )
            }
        }
        CoverImageCache.testDownloadResult = { url ->
            if (url == "https://img.nextmoe.dev/win.webp") "file:///cached/nextmoe_win.jpg" else null
        }

        val game = ScanGame(
            title = "Sakura",
            uri = "content://games/sakura",
            engine = EngineType.KIRIKIRI,
            launchTarget = "data.xp3",
            vndbId = "v562",
            nextmoeId = "207379",
            metadataTitle = "手工绑定标题",
        )
        val updated = NextMoeCoverService.fetchBestCover(context, game)
        assertNotNull(updated)
        assertEquals("file:///cached/nextmoe_win.jpg", updated!!.coverUri)
        assertEquals(AppSettingsStore.COVER_SOURCE_NEXTMOE, updated.coverSource)
        assertEquals("v562", updated.vndbId)
        assertEquals("207379", updated.nextmoeId)
        assertEquals("手工绑定标题", updated.metadataTitle)
        assertTrue(urls.any { it.contains("ids=207379") })
    }

    @Test
    fun catalog401FallsBackAnonymouslyWithoutMarkingNeedsReauth() {
        NextMoeAuthStore.saveTokens(
            context,
            accessToken = "stale_nm_token",
            refreshToken = "nm_refresh",
            expiresAtMillis = System.currentTimeMillis() + 3_600_000L,
        )
        val authHeadersSeen = mutableListOf<String?>()
        NextMoeCoverService.testHttpRoundTrip = { _, headers ->
            val auth = headers["Authorization"]
            authHeadersSeen.add(auth)
            if (auth != null) {
                HttpJsonResult(401, JSONObject().put("code", "INVALID_CREDENTIAL"))
            } else {
                HttpJsonResult(
                    200,
                    JSONObject().put(
                        "items",
                        JSONArray().put(
                            JSONObject()
                                .put("id", "207379")
                                .put("display_name", "樱之诗")
                                .put("cover", JSONObject().put("url", "https://img.nextmoe.dev/ok.webp")),
                        ),
                    ),
                )
            }
        }

        val candidates = NextMoeCoverService.searchCandidates("樱之诗", null, 5, context)
        assertEquals(1, candidates.size)
        assertEquals(listOf("Bearer stale_nm_token", null), authHeadersSeen)
        assertFalse(NextMoeAuthStore.getStatus(context).needsReauth)
    }

    @Test
    fun rateLimitResponsesMapToNextMoeRateLimited() {
        val payloads = listOf(
            HttpJsonResult(429, JSONObject()),
            HttpJsonResult(403, JSONObject().put("code", "RATE_LIMITED")),
            HttpJsonResult(403, JSONObject().put("code", "QUOTA_EXCEEDED")),
        )
        for (payload in payloads) {
            NextMoeCoverService.testHttpRoundTrip = { _, _ -> payload }
            try {
                NextMoeCoverService.searchCandidates("樱之诗", null, 5)
                fail("expected NEXTMOE_RATE_LIMITED for $payload")
            } catch (e: CoverSearchException) {
                assertEquals(CoverErrorCode.NEXTMOE_RATE_LIMITED, e.error)
            }
        }
    }

    @Test
    fun listCoversJson404ReturnsEmptyWhileNonJson404AndSearch404ThrowNetworkAndDotIdsRejected() {
        val parent = CoverSearchCandidate(
            source = AppSettingsStore.COVER_SOURCE_NEXTMOE,
            id = "207379",
            title = "樱之诗",
            subtitle = "",
            detail = "",
            coverUrl = "",
            nextmoeId = "207379",
        )
        // 结构化 JSON 404 -> listCovers 归为空列表
        NextMoeCoverService.testHttpRoundTrip = { _, _ -> HttpJsonResult(404, JSONObject().put("code", "NOT_FOUND")) }
        assertTrue(NextMoeCoverService.listCovers(parent, limit = 5).isEmpty())

        // 非 JSON 404（网关/反代 HTML 页）-> listCovers 与 searchCandidates 均按网络失败抛出
        NextMoeCoverService.testHttpRoundTrip = { _, _ -> HttpJsonResult(404, null) }
        try {
            NextMoeCoverService.listCovers(parent, limit = 5)
            fail("expected NEXTMOE_NETWORK when listCovers returns non-JSON 404")
        } catch (e: CoverSearchException) {
            assertEquals(CoverErrorCode.NEXTMOE_NETWORK, e.error)
        }

        // "." / ".." 路径段直接拦截，不发请求
        assertTrue(NextMoeCoverService.listCovers(parent.copy(id = "..", nextmoeId = ".."), limit = 5).isEmpty())
        assertTrue(NextMoeCoverService.listCovers(parent.copy(id = ".", nextmoeId = "."), limit = 5).isEmpty())

        try {
            NextMoeCoverService.searchCandidates("missing", null, 5)
            fail("expected NEXTMOE_NETWORK when /works returns 404")
        } catch (e: CoverSearchException) {
            assertEquals(CoverErrorCode.NEXTMOE_NETWORK, e.error)
        }
    }
}
