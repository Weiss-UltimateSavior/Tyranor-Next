package com.tyranor.next.core.cover

import com.tyranor.next.core.net.HttpJsonResult
import com.tyranor.next.core.settings.AppSettingsStore
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 未萌 NextMoe 候选解析与检索回归：
 * 1. 一级作品解析携带 nextmoeId 与有效中文名，且仅在带 VNDB work ref 时携带 vndbId；
 * 2. 二级多封面映射继承父候选的 nextmoeId（work id），不得把 cover.id 误写入 nextmoeId；
 * 3. searchCandidates（refs=vndb: 精确反查 vs 关键词模糊检索清空 vndbId）与 listCovers（portrait_pinned 置顶排序）。
 */
class NextMoeCoverServiceTest {

    @After
    fun tearDown() {
        NextMoeCoverService.testHttpRoundTrip = null
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
}
