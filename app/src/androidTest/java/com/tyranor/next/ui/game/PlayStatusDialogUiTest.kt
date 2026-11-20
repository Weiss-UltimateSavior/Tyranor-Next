package com.tyranor.next.ui.game

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.espresso.Espresso.pressBack
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
import com.tyranor.next.core.game.storage.GameLibraryFacade
import com.tyranor.next.core.i18n.AppLocaleController
import com.tyranor.next.core.net.HttpJsonResult
import com.tyranor.next.core.net.testHttpRoundTrip
import com.tyranor.next.theme.TyranorNextTheme
import com.tyranor.next.ui.cover.CredentialDialogOutcome
import com.tyranor.next.ui.cover.CredentialTokenDialog
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `PlayStatusDialog` 与 `CredentialTokenDialog` 交互写流程真机 Compose UI 回归：
 * 1. 未绑定来源搜索候选并点击绑定；
 * 2. 已绑定来源回读失败展示内联报错 + 重试按钮，点击重试恢复「当前」状态标记；
 * 3. 状态写入期间禁用返回和解绑，完成后关闭选择弹窗；
 * 4. 解绑条目同步回调清空绑定 ID；
 * 5. `CredentialTokenDialog` 无效令牌服务端失败保留弹窗并内联展示错误。
 */
@RunWith(AndroidJUnit4::class)
class PlayStatusDialogUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val memoryTokens = ConcurrentHashMap<String, String>()
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        memoryTokens.clear()
        SecureAuthStore.testCipherDelegate = { field, plain ->
            if (plain != null) {
                if (plain.isEmpty()) memoryTokens.remove(field) else memoryTokens[field] = plain
                null
            } else {
                memoryTokens[field]
            }
        }
        GameLibraryFacade.skipPersistForTest = true
        PlayStatusSummaryCache.clearForTest()
        runCatching { BangumiAuthStore.clear(context) }
        runCatching { VndbAuthStore.clear(context) }
        runCatching { HikarinagiAuthStore.clear(context) }
        runCatching { NextMoeAuthStore.clear(context) }
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
            runCatching { GameLibraryFacade.seedGamesForTest(null) }
            runCatching { GameLibraryFacade.skipPersistForTest = false }
        } finally {
            SecureAuthStore.testCipherDelegate = null
            SecureAuthStore.clearKeyCacheForTest()
            memoryTokens.clear()
        }
    }

    @Test
    fun playStatusDialogCoversBindSearchRetryWriteLockAndUnbind() {
        val baseGame = ScanGame(
            title = "Sakura",
            uri = "content://games/ui_flow_1",
            engine = EngineType.KIRIKIRI,
            launchTarget = "data.xp3",
            vndbId = "v17",
        )
        GameLibraryFacade.seedGamesForTest(listOf(baseGame))
        assertTrue(VndbAuthStore.saveToken(context, "vndb_tok", "u1", "alice", listOf("listread", "listwrite")))
        assertTrue(BangumiAuthStore.saveTokens(context, "bgm_tok", "", 0L))
        BangumiAuthStore.saveUserIdentity(context, 1L, "alice")

        var currentGame by mutableStateOf(baseGame)
        val vndbReads = AtomicInteger(0)
        val writeStarted = CountDownLatch(1)
        val allowWriteFinish = CountDownLatch(1)
        val dismissCount = AtomicInteger(0)

        testHttpRoundTrip = { url, method, _, _ ->
            when {
                url.contains("api.bgm.tv/v0/search/subjects") && method == "POST" ->
                    HttpJsonResult(
                        200,
                        JSONObject().put(
                            "data",
                            JSONArray().put(
                                JSONObject()
                                    .put("id", 4154)
                                    .put("name", "CLANNAD")
                                    .put("name_cn", "CLANNAD")
                                    .put("type", 4)
                                    .put("date", "2004-04-28"),
                            ),
                        ),
                    )
                url.contains("api.vndb.org/kana/ulist") && method == "POST" -> {
                    if (vndbReads.getAndIncrement() == 0) {
                        HttpJsonResult(500, null)
                    } else {
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
                    }
                }
                url.contains("api.vndb.org/kana/ulist/v17") && method == "PATCH" -> {
                    writeStarted.countDown()
                    allowWriteFinish.await(15, TimeUnit.SECONDS)
                    HttpJsonResult(204, null)
                }
                else -> HttpJsonResult(404, null)
            }
        }

        try {
            composeRule.setContent {
                TyranorNextTheme {
                    PlayStatusDialog(
                        game = currentGame,
                        onDismiss = { dismissCount.incrementAndGet() },
                        onBindingUpdated = { _, updated -> currentGame = updated },
                    )
                }
            }

            // 1) 未绑定 Bangumi：替换搜索词并绑定候选条目
            composeRule.onNodeWithText("Bangumi").performClick()
            composeRule.onNodeWithText(context.getString(R.string.game_bind_subject_title, "Bangumi")).assertIsDisplayed()
            composeRule.onNode(hasSetTextAction()).performTextReplacement("CLANNAD")
            composeRule.onNodeWithText(context.getString(R.string.game_search)).performClick()
            composeRule.waitUntil(5_000) {
                composeRule.onAllNodes(hasText("CLANNAD", substring = true))
                    .fetchSemanticsNodes().size >= 2
            }
            composeRule.onAllNodes(hasText("CLANNAD", substring = true))[1].performClick()
            composeRule.waitUntil(5_000) { currentGame.bangumiId == "4154" }

            // 2) 已绑定 VNDB：首次回读 500 展示内联报错 + 重试；点击重试后显示「当前」
            val cancelText = context.getString(R.string.common_cancel)
            val cancelNodes = composeRule.onAllNodes(hasText(cancelText))
            val parentCancelCount = cancelNodes.fetchSemanticsNodes().size
            composeRule.onNodeWithText("VNDB").performClick()
            val pickerTitle = context.getString(R.string.game_play_status_pick_title, "VNDB")
            composeRule.onNodeWithText(pickerTitle).assertIsDisplayed()
            assertEquals(parentCancelCount, cancelNodes.fetchSemanticsNodes().size)
            val retryLabel = context.getString(R.string.common_retry)
            val errorLabel = AppLocaleController.wrap(context).getString(R.string.play_error_vndb_network)
            val unbindText = context.getString(R.string.game_play_status_unbind)
            val statusList = composeRule.onNode(hasScrollToIndexAction())
            composeRule.waitUntil(5_000) {
                composeRule.onAllNodes(hasText(errorLabel)).fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText(errorLabel).assertIsDisplayed()
            val retry = composeRule.onNodeWithText(retryLabel).assertIsDisplayed()
            val unbind = composeRule.onNodeWithText(unbindText).assertIsDisplayed()
            val retryBounds = retry.fetchSemanticsNode().boundsInRoot
            val unbindBounds = unbind.fetchSemanticsNode().boundsInRoot
            assertTrue(retryBounds.bottom < unbindBounds.top)
            val vndbDropped = context.getString(R.string.play_status_vndb_dropped)
            statusList.performScrollToNode(hasText(vndbDropped))
            assertEquals(retryBounds, retry.fetchSemanticsNode().boundsInRoot)
            assertEquals(unbindBounds, unbind.fetchSemanticsNode().boundsInRoot)
            retry.performClick()
            val currentLabel = context.getString(R.string.game_play_status_current)
            val vndbPlaying = context.getString(R.string.play_status_vndb_playing)
            statusList.performScrollToNode(hasText(vndbPlaying))
            composeRule.waitUntil(5_000) {
                composeRule.onAllNodes(hasText(currentLabel))
                    .fetchSemanticsNodes().isNotEmpty()
            }

            // 3) 写入期间确认返回和解绑均被阻止，放行后完成写入并退出选择弹窗
            val vndbFinished = context.getString(R.string.play_status_vndb_finished)
            statusList.performScrollToNode(hasText(vndbFinished))
            composeRule.onNodeWithText(vndbFinished).performClick()
            assertTrue(writeStarted.await(5, TimeUnit.SECONDS))
            pressBack()
            composeRule.onNodeWithText(pickerTitle).assertIsDisplayed()
            unbind.assertIsNotEnabled()
        } finally {
            allowWriteFinish.countDown()
        }

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodes(hasText(context.getString(R.string.game_play_status_pick_title, "VNDB")))
                .fetchSemanticsNodes().isEmpty()
        }

        // 4) 直接点击底部解绑 VNDB，回调清空 vndbId
        composeRule.onNodeWithText("VNDB").performClick()
        val unbindText = context.getString(R.string.game_play_status_unbind)
        composeRule.onNodeWithText(unbindText).performClick()
        composeRule.waitUntil(5_000) { currentGame.vndbId == null }
        assertNull(currentGame.vndbId)
        assertEquals(0, dismissCount.get())
    }

    @Test
    fun credentialTokenDialogShowsInlineErrorOnFailedOutcomeAndPreservesDialog() {
        val dismissCount = AtomicInteger(0)
        val confirmCalls = AtomicInteger(0)
        composeRule.setContent {
            TyranorNextTheme {
                CredentialTokenDialog(
                    titleRes = R.string.auth_vndb_token_title,
                    instructionsRes = R.string.auth_vndb_token_instructions,
                    missingRes = R.string.auth_vndb_token_missing,
                    savedRes = R.string.auth_vndb_token_saved,
                    authVersion = 1L,
                    readStoredToken = { "" },
                    hasStoredCredential = { false },
                    clearCredential = {},
                    confirmToken = { _, token ->
                        confirmCalls.incrementAndGet()
                        assertEquals("bad_tok_123", token)
                        CredentialDialogOutcome.Failed("Invalid token test error")
                    },
                    onDismiss = { dismissCount.incrementAndGet() },
                )
            }
        }
        composeRule.onNode(hasSetTextAction()).performTextInput("bad_tok_123")
        composeRule.onNodeWithText(context.getString(R.string.common_confirm)).performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodes(hasText("Invalid token test error")).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(1, confirmCalls.get())
        assertEquals(0, dismissCount.get())
    }
}
