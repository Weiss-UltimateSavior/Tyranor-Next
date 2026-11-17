package com.tyranor.next.core.cover

import com.tyranor.next.core.auth.HikarinagiAuthStore
import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.game.storage.GameLibraryFacade
import com.tyranor.next.core.net.HttpJsonResult
import com.tyranor.next.core.net.testHttpRoundTrip
import com.tyranor.next.core.settings.AppSettingsStore
import com.tyranor.next.testing.AuthAndCoverTestStubsRule
import com.tyranor.next.testing.InMemoryPreferencesContext
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Gap 3 回归：
 * 1. bindCoverCandidate 对跨来源 ref 只补缺不覆写（例如 NextMoe 候选附带 vndbId 不得覆盖已绑 VNDB ID，
 *    选来源自身条目时更新该来源 ID，二级多封面选择保留父候选 nextmoeId）；
 * 2. HikarinagiCoverSource 对 401（置位 needsReauth + HIKARINAGI_AUTH_EXPIRED）与 429（HIKARINAGI_RETRY，不置位 needsReauth）的处理。
 */
class CoverScraperBindAndHikarinagiTest {

    @get:Rule
    val stubsRule = AuthAndCoverTestStubsRule(installInMemoryCipher = true)

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: InMemoryPreferencesContext

    @Before
    fun setUp() {
        val rootDir = tempFolder.newFolder("cover-bind-test")
        context = InMemoryPreferencesContext(filesDirProvider = { rootDir })
        CoverImageCache.testDownloadResult = { "file:///cached/bound_cover.jpg" }
        // bindCoverCandidate 现经 GameLibraryFacade 单行落库：种子空库（非 null）避免桩 context
        // 触发真实 Room 回源；空库使 bindCoverCandidate 走「游戏不在库」的纯内存合并分支
        GameLibraryFacade.skipPersistForTest = true
        GameLibraryFacade.seedGamesForTest(emptyList())
    }

    @Test
    fun bindCoverCandidatePreservesExistingCrossSourceIdsAndUpdatesSelectedSource() {
        val baseGame = ScanGame(
            title = "Sakura",
            uri = "content://games/sakura",
            engine = EngineType.KIRIKIRI,
            launchTarget = "data.xp3",
            vndbId = "v17",
            nextmoeId = "100001",
            metadataTitle = "手工绑定标题",
        )

        val nextMoeCandidate = CoverSearchCandidate(
            source = AppSettingsStore.COVER_SOURCE_NEXTMOE,
            id = "207379",
            title = "樱之诗",
            subtitle = "",
            detail = "",
            coverUrl = "https://img.nextmoe.dev/cover.webp",
            vndbId = "v999",
            nextmoeId = "207379",
            metadataTitle = "樱之诗",
        )

        val boundNextMoe = CoverScraperService.bindCoverCandidate(context, baseGame, nextMoeCandidate)
        assertNotNull(boundNextMoe)
        assertEquals("v17", boundNextMoe!!.vndbId)
        assertEquals("207379", boundNextMoe.nextmoeId)

        // 若本地 vndbId 为空，跨来源关联 ref 可补缺
        val unboundVndbGame = baseGame.copy(vndbId = null)
        val backfilled = CoverScraperService.bindCoverCandidate(context, unboundVndbGame, nextMoeCandidate)
        assertEquals("v999", backfilled!!.vndbId)
        assertEquals("207379", backfilled.nextmoeId)

        // 手工选 VNDB 来源时，VNDB ID 更新而 nextmoeId 保留
        val vndbCandidate = CoverSearchCandidate(
            source = AppSettingsStore.COVER_SOURCE_VNDB,
            id = "v562",
            title = "Sakura no Uta",
            subtitle = "",
            detail = "",
            coverUrl = "https://t.vndb.org/cv/562.jpg",
            vndbId = "v562",
        )
        val boundVndb = CoverScraperService.bindCoverCandidate(context, boundNextMoe, vndbCandidate)
        assertEquals("v562", boundVndb!!.vndbId)
        assertEquals("207379", boundVndb.nextmoeId)

        // 二级封面下钻返回的候选保留父候选的 work id 作为 nextmoeId
        NextMoeCoverService.testHttpRoundTrip = { _, _ ->
            HttpJsonResult(
                200,
                JSONObject().put(
                    "items",
                    JSONArray().put(
                        JSONObject()
                            .put("id", "cover-row-99")
                            .put("url", "https://img.nextmoe.dev/portrait.webp")
                            .put("portrait_pinned", true),
                    ),
                ),
            )
        }
        AppSettingsStore.setCoverScraperSourceEnabled(context, AppSettingsStore.COVER_SOURCE_NEXTMOE, true)
        val drillDown = CoverScraperService.searchWorkCovers(context, AppSettingsStore.COVER_SOURCE_NEXTMOE, nextMoeCandidate, limit = 5)
        assertTrue(drillDown is CoverSearchResult.Success)
        val coverCandidate = (drillDown as CoverSearchResult.Success).candidates.single()
        assertEquals("207379", coverCandidate.nextmoeId)
        val boundDrillDown = CoverScraperService.bindCoverCandidate(context, unboundVndbGame, coverCandidate)
        assertEquals("207379", boundDrillDown!!.nextmoeId)
    }

    @Test
    fun hikarinagiSearch401MarksNeedsReauthAnd429MapsToRetry() {
        HikarinagiAuthStore.saveTokens(
            context,
            accessToken = "hkn_access_valid",
            refreshToken = "hkn_refresh",
            expiresAtMillis = System.currentTimeMillis() + 3_600_000L,
        )

        AppSettingsStore.setCoverScraperSourceEnabled(context, AppSettingsStore.COVER_SOURCE_HIKARINAGI, true)
        testHttpRoundTrip = { _, _, _, _ -> HttpJsonResult(429, null) }
        val result429 = CoverScraperService.searchCoverCandidates(
            context,
            AppSettingsStore.COVER_SOURCE_HIKARINAGI,
            "Sakura",
            limit = 5,
        )
        assertEquals(CoverSearchResult.Failure(CoverErrorCode.HIKARINAGI_RETRY), result429)
        assertFalse(HikarinagiAuthStore.getStatus(context).needsReauth)

        testHttpRoundTrip = { _, _, _, _ -> HttpJsonResult(401, null) }
        try {
            CoverScraperService.searchCandidatesForBinding(
                context,
                AppSettingsStore.COVER_SOURCE_HIKARINAGI,
                "Sakura",
                limit = 5,
            )
            fail("expected HIKARINAGI_AUTH_EXPIRED on 401")
        } catch (e: CoverSearchException) {
            assertEquals(CoverErrorCode.HIKARINAGI_AUTH_EXPIRED, e.error)
        }
        assertTrue(HikarinagiAuthStore.getStatus(context).needsReauth)
    }

    @Test
    fun bangumiAndVndbSearchFilterOutBlankOrJsonNullIds() {
        testHttpRoundTrip = { _, _, _, _ ->
            HttpJsonResult(
                200,
                JSONObject().put(
                    "data",
                    JSONArray()
                        .put(JSONObject().put("id", JSONObject.NULL).put("type", 4).put("name", "NullId"))
                        .put(JSONObject().put("id", "").put("type", 4).put("name", "EmptyId"))
                        .put(JSONObject().put("id", "   ").put("type", 4).put("name", "BlankId"))
                        .put(
                            JSONObject()
                                .put("id", 4154)
                                .put("type", 4)
                                .put("name", "CLANNAD")
                                .put("images", JSONObject().put("large", "https://bgm.example/4154.jpg")),
                        ),
                ),
            )
        }
        val bangumiCandidates = BangumiCoverService.searchCandidates("CLANNAD", limit = 10)
        assertEquals(listOf("4154"), bangumiCandidates.map { it.id })

        val vndbPayload = JSONObject().put(
            "results",
            JSONArray()
                .put(JSONObject().put("id", JSONObject.NULL).put("title", "NullId").put("image", JSONObject().put("url", "https://v.example/0.jpg")))
                .put(JSONObject().put("id", "  ").put("title", "BlankId").put("image", JSONObject().put("url", "https://v.example/1.jpg")))
                .put(
                    JSONObject()
                        .put("id", "v17")
                        .put("title", "Ever17")
                        .put("image", JSONObject().put("url", "https://v.example/17.jpg").put("thumbnail", "https://v.example/17s.jpg")),
                ),
        ).toString()
        val vndbCandidates = VndbCoverService.parseSearchResults(vndbPayload, preferLarge = false)
        assertEquals(listOf("v17"), vndbCandidates.map { it.id })
    }

    @Test
    fun persistScrapedCoverDeletesOrphanDownloadedFileWhenConcurrentCoverWon() {
        val coversDir = persistentCoverDir(context).apply { mkdirs() }
        val manualFile = File(coversDir, "manual_cover.jpg").apply { writeText("manual") }
        val orphanFile = File(coversDir, "scraped_orphan.jpg").apply { writeText("orphan") }
        val original = ScanGame(
            title = "Sakura",
            uri = "content://games/orphan",
            engine = EngineType.KIRIKIRI,
            launchTarget = "data.xp3",
            coverUri = null,
        )
        // 并发手动换封面已落库：当前库里 coverUri 与 original 不再一致
        val manualUpdated = original.copy(
            coverUri = manualFile.toURI().toString(),
            coverSource = AppSettingsStore.COVER_SOURCE_LOCAL,
        )
        GameLibraryFacade.skipPersistForTest = true
        GameLibraryFacade.seedGamesForTest(listOf(manualUpdated))

        val scraped = original.copy(
            coverUri = orphanFile.toURI().toString(),
            coverSource = AppSettingsStore.COVER_SOURCE_VNDB,
        )
        val (persisted, _) = CoverScrapeTaskManager.persistScrapedCover(context, original, scraped)
        org.junit.Assert.assertNull(persisted)
        assertFalse("orphan downloaded file should be deleted", orphanFile.exists())
        assertTrue("currently referenced manual cover must remain", manualFile.exists())
    }

    @Test
    fun bindCoverCandidateInLibraryUpdatesCachesNotifiesRevisionAndDeletesOldCover() {
        val coversDir = persistentCoverDir(context).apply { mkdirs() }
        val oldCover = File(coversDir, "vndb_old.jpg").apply { writeText("old-bytes") }
        val newCover = File(coversDir, "bangumi_new.jpg").apply { writeText("new-bytes") }
        val existing = ScanGame(
            title = "Sakura",
            uri = "content://games/in_lib",
            engine = EngineType.KIRIKIRI,
            launchTarget = "data.xp3",
            coverUri = oldCover.toURI().toString(),
            coverSource = AppSettingsStore.COVER_SOURCE_VNDB,
            openTime = 12345L,
        )
        GameLibraryFacade.seedGamesForTest(listOf(existing))
        GameLibraryFacade.seedDerivedCachesForTest(
            recent = listOf(existing),
            quickLaunch = listOf(existing),
        )
        val revisionBefore = GameLibraryFacade.libraryRevision.value
        CoverImageCache.testDownloadResult = { newCover.toURI().toString() }

        val candidate = CoverSearchCandidate(
            source = AppSettingsStore.COVER_SOURCE_BANGUMI,
            id = "4154",
            title = "CLANNAD",
            subtitle = "",
            detail = "",
            coverUrl = "https://bgm.example/4154.jpg",
            bangumiId = "4154",
            metadataTitle = "CLANNAD",
        )
        val persisted = CoverScraperService.bindCoverCandidate(context, existing, candidate)

        assertNotNull(persisted)
        assertEquals(newCover.toURI().toString(), persisted!!.coverUri)
        assertEquals(AppSettingsStore.COVER_SOURCE_BANGUMI, persisted.coverSource)
        assertEquals("4154", persisted.bangumiId)
        assertEquals(revisionBefore + 1, GameLibraryFacade.libraryRevision.value)
        assertEquals(newCover.toURI().toString(), GameLibraryFacade.loadGames(context).single().coverUri)
        assertEquals(newCover.toURI().toString(), GameLibraryFacade.loadRecentGames(context).single().coverUri)
        assertEquals(12345L, GameLibraryFacade.loadRecentGames(context).single().openTime)
        assertEquals(newCover.toURI().toString(), GameLibraryFacade.loadQuickLaunch(context).single().coverUri)
        assertFalse("replaced old cover in covers_remote must be deleted", oldCover.exists())
        assertTrue("new bound cover must remain", newCover.exists())
    }
}
