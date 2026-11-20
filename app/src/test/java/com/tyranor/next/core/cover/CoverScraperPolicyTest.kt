package com.tyranor.next.core.cover

import com.tyranor.next.core.auth.HikarinagiAccessTokenResult
import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.net.HttpJsonResult
import com.tyranor.next.core.net.testHttpRoundTrip
import com.tyranor.next.core.settings.AppSettingsStore
import com.tyranor.next.testing.AuthAndCoverTestStubsRule
import com.tyranor.next.testing.InMemoryPreferencesContext
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CoverScraperPolicyTest {

    @get:Rule
    val stubsRule = AuthAndCoverTestStubsRule()

    @get:Rule
    val tempFolder = TemporaryFolder()

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

        val tempDir = tempFolder.newFolder("xs4-policy-test")
        val context = InMemoryPreferencesContext(filesDirProvider = { tempDir })
        AppSettingsStore.setCoverScraperOnlyMissing(context, true)
        AppSettingsStore.DEFAULT_COVER_SCRAPER_SOURCES.forEach { source ->
            AppSettingsStore.setCoverScraperSourceEnabled(context, source, source == AppSettingsStore.COVER_SOURCE_BANGUMI)
        }
        AppSettingsStore.setCoverScraperSourceOrder(context, listOf(AppSettingsStore.COVER_SOURCE_BANGUMI))
        var bangumiRequested = false
        testHttpRoundTrip = { url, _, _, _ ->
            if (url.contains("api.bgm.tv")) bangumiRequested = true
            HttpJsonResult(
                200,
                JSONObject().put(
                    "data",
                    JSONArray().put(
                        JSONObject()
                            .put("id", 4154)
                            .put("type", 4)
                            .put("name", "Rance")
                            .put("images", JSONObject().put("large", "https://bgm.example/4154.jpg")),
                    ),
                ),
            )
        }
        CoverImageCache.testDownloadResult = { "file:///cached/xs4_online.jpg" }
        val game = ScanGame(
            title = "Rance",
            uri = "content://games/xs4_online",
            engine = EngineType.XSYS4,
            launchTarget = "System40.exe",
        )
        val result = runBlocking { CoverScraperService.scrapeLibraryCovers(context, listOf(game)) }
        assertTrue(bangumiRequested)
        assertEquals(1, result.updatedCount)
        assertEquals("file:///cached/xs4_online.jpg", result.games.single().coverUri)
        assertEquals(AppSettingsStore.COVER_SOURCE_BANGUMI, result.games.single().coverSource)
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
