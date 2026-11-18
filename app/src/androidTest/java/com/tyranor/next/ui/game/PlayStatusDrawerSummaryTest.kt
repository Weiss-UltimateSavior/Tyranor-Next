package com.tyranor.next.ui.game

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tyranor.next.R
import com.tyranor.next.core.auth.BangumiAuthStore
import com.tyranor.next.core.auth.HikarinagiAuthStore
import com.tyranor.next.core.auth.NextMoeAuthStore
import com.tyranor.next.core.auth.SecureAuthStore
import com.tyranor.next.core.auth.VndbAuthStore
import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.net.HttpJsonResult
import com.tyranor.next.core.net.testHttpRoundTrip
import com.tyranor.next.core.settings.AppSettingsStore
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 游玩状态抽屉四源摘要聚合真机回归（使用真实 Android Resources 与多语言 strings.xml）：
 * 1. 四源同时绑定且均已授权时按 VNDB / Hikarinagi / NextMoe / Bangumi 汇总并允许写入 TTL 缓存；
 * 2. 任一来源回读失败时摘要仍展示错误信息，但 `cacheable = false` 不入缓存；
 * 3. 未授权来源不发起回读也不产出摘要段。
 */
@RunWith(AndroidJUnit4::class)
class PlayStatusDrawerSummaryTest {

    private val memoryTokens = ConcurrentHashMap<String, String>()
    private lateinit var context: Context
    private lateinit var previousLanguage: String

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        previousLanguage = AppSettingsStore.getLanguage(context)
        memoryTokens.clear()
        SecureAuthStore.testCipherDelegate = { field, plain ->
            if (plain != null) {
                if (plain.isEmpty()) memoryTokens.remove(field) else memoryTokens[field] = plain
                null
            } else {
                memoryTokens[field]
            }
        }
        AppSettingsStore.setLanguage(context, AppSettingsStore.LANGUAGE_ZH)
    }

    @After
    fun tearDown() {
        try {
            runCatching { testHttpRoundTrip = null }
            runCatching { PlayStatusSummaryCache.clearForTest() }
            runCatching { BangumiAuthStore.clear(context) }
            runCatching { VndbAuthStore.clear(context) }
            runCatching { HikarinagiAuthStore.clear(context) }
            runCatching { NextMoeAuthStore.clear(context) }
            if (::previousLanguage.isInitialized) {
                runCatching { AppSettingsStore.setLanguage(context, previousLanguage) }
            }
        } finally {
            SecureAuthStore.testCipherDelegate = null
            SecureAuthStore.clearKeyCacheForTest()
            memoryTokens.clear()
        }
    }

    @Test
    fun playStatusDrawerSummaryAggregatesAllFourSourcesAndMarksCacheability() {
        val future = System.currentTimeMillis() + 3_600_000L
        assertTrue(VndbAuthStore.saveToken(context, "vndb_tok", "u1", "alice", listOf("listread")))
        assertTrue(HikarinagiAuthStore.saveTokens(context, "hika_tok", "hika_ref", future))
        assertTrue(NextMoeAuthStore.saveTokens(context, "nm_tok", "nm_ref", future))

        val game = ScanGame(
            title = "Sakura",
            uri = "content://games/summary_all",
            engine = EngineType.KIRIKIRI,
            launchTarget = "data.xp3",
            vndbId = "v17",
            hikarinagiId = "hkn-17",
            nextmoeId = "207379",
            bangumiId = "4154",
        )

        testHttpRoundTrip = { url, _, _, _ ->
            when {
                url.contains("api.vndb.org/kana/ulist") ->
                    HttpJsonResult(
                        200,
                        JSONObject().put(
                            "results",
                            JSONArray().put(
                                JSONObject()
                                    .put("id", "v17")
                                    .put("labels", JSONArray().put(JSONObject().put("id", 1))),
                            ),
                        ),
                    )
                url.contains("api.hikarinagi.org") ->
                    HttpJsonResult(200, JSONObject().put("success", true).put("data", JSONObject().put("status", "COMPLETED")))
                url.contains("api.nextmoe.dev") ->
                    HttpJsonResult(200, JSONObject().put("state", "doing"))
                url.contains("api.bgm.tv") ->
                    // 单条收藏 GET 404（JSON 错误体）= 未收藏（读回独立状态而非错误）
                    HttpJsonResult(404, JSONObject().put("title", "Not Found"))
                else -> HttpJsonResult(404, null)
            }
        }

        val allOk = playStatusDrawerSummary(
            context = context,
            game = game,
            bangumiAuthorized = true,
            vndbAuthorized = true,
            hikarinagiAuthorized = true,
            nextmoeAuthorized = true,
        )
        assertTrue(allOk.cacheable)
        val expected = listOf(
            "VNDB · " + context.getString(R.string.play_status_vndb_playing),
            "Hikarinagi · " + context.getString(R.string.play_status_hikarinagi_completed),
            context.getString(R.string.cover_source_nextmoe) + " · " + context.getString(R.string.play_status_nextmoe_doing),
            "Bangumi · " + context.getString(R.string.game_play_status_bangumi_not_collected),
        ).joinToString(" · ")
        assertEquals(expected, allOk.line)

        testHttpRoundTrip = { url, _, _, _ ->
            when {
                url.contains("api.vndb.org/kana/ulist") ->
                    HttpJsonResult(200, JSONObject().put("results", JSONArray()))
                url.contains("api.hikarinagi.org") ->
                    HttpJsonResult(500, null)
                else -> HttpJsonResult(404, null)
            }
        }
        val partialFail = playStatusDrawerSummary(
            context = context,
            game = game,
            bangumiAuthorized = false,
            vndbAuthorized = true,
            hikarinagiAuthorized = true,
            nextmoeAuthorized = false,
        )
        assertFalse(partialFail.cacheable)
        assertNotNull(partialFail.line)
        assertTrue(partialFail.line!!.contains(context.getString(R.string.game_play_status_not_listed)))
        assertTrue(partialFail.line!!.contains(context.getString(R.string.play_error_hikarinagi_network)))

        val noneAuthorized = playStatusDrawerSummary(
            context = context,
            game = game,
            bangumiAuthorized = false,
            vndbAuthorized = false,
            hikarinagiAuthorized = false,
            nextmoeAuthorized = false,
        )
        assertNull(noneAuthorized.line)
        assertTrue(noneAuthorized.cacheable)
    }
}
