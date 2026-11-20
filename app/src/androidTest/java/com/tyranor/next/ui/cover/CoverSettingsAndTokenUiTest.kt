package com.tyranor.next.ui.cover

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tyranor.next.R
import com.tyranor.next.core.auth.BangumiAuthStore
import com.tyranor.next.core.auth.HikarinagiAuthStore
import com.tyranor.next.core.auth.NextMoeAuthStore
import com.tyranor.next.core.auth.SecureAuthStore
import com.tyranor.next.core.auth.VndbAuthStore
import com.tyranor.next.core.settings.AppSettingsStore
import java.util.concurrent.ConcurrentHashMap
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `CoverScraperSettingsActivity` 整页 UI 与 `CredentialTokenDialog` 空提交校验真机回归：
 * 1. 从封面设置页打开 Bangumi 令牌弹窗并验证空输入内联报错；
 * 2. 来源开关切换、下移重排以及 `BangumiAuthStore` 失效/取消授权状态流实时刷新。
 */
@RunWith(AndroidJUnit4::class)
class CoverSettingsAndTokenUiTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val memoryTokens = ConcurrentHashMap<String, String>()
    private lateinit var context: Context
    private lateinit var prevOrder: List<String>
    private var prevOnlyMissing = false
    private lateinit var prevEnabled: Map<String, Boolean>

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        prevOrder = AppSettingsStore.getCoverScraperSourceOrder(context)
        prevOnlyMissing = AppSettingsStore.isCoverScraperOnlyMissing(context)
        prevEnabled = prevOrder.associateWith { AppSettingsStore.isCoverScraperSourceEnabled(context, it) }
        memoryTokens.clear()
        SecureAuthStore.testCipherDelegate = { field, plain ->
            if (plain != null) {
                if (plain.isEmpty()) memoryTokens.remove(field) else memoryTokens[field] = plain
                null
            } else {
                memoryTokens[field]
            }
        }
        AppSettingsStore.setCoverScraperSourceOrder(
            context,
            listOf(
                AppSettingsStore.COVER_SOURCE_BANGUMI,
                AppSettingsStore.COVER_SOURCE_VNDB,
                AppSettingsStore.COVER_SOURCE_STEAM,
                AppSettingsStore.COVER_SOURCE_HIKARINAGI,
                AppSettingsStore.COVER_SOURCE_NEXTMOE,
            ),
        )
        BangumiAuthStore.clear(context)
        VndbAuthStore.clear(context)
        HikarinagiAuthStore.clear(context)
        NextMoeAuthStore.clear(context)
    }

    @After
    fun tearDown() {
        try {
            runCatching { AppSettingsStore.setCoverScraperSourceOrder(context, prevOrder) }
            runCatching { AppSettingsStore.setCoverScraperOnlyMissing(context, prevOnlyMissing) }
            runCatching {
                prevEnabled.forEach { (source, enabled) ->
                    AppSettingsStore.setCoverScraperSourceEnabled(context, source, enabled)
                }
            }
            runCatching { BangumiAuthStore.clear(context) }
            runCatching { VndbAuthStore.clear(context) }
            runCatching { HikarinagiAuthStore.clear(context) }
            runCatching { NextMoeAuthStore.clear(context) }
        } finally {
            SecureAuthStore.testCipherDelegate = null
            SecureAuthStore.clearKeyCacheForTest()
            memoryTokens.clear()
        }
    }

    @Test
    fun credentialTokenDialogRejectsEmptyInputWithInlineMissingError() {
        ActivityScenario.launch(CoverScraperSettingsActivity::class.java).use {
            val confirmLabel = context.getString(R.string.common_confirm)
            val tokenLabel = context.getString(R.string.cover_token)

            composeRule.waitUntil(5_000) {
                composeRule.onAllNodesWithText(tokenLabel).fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText(tokenLabel).performClick()
            composeRule.onNodeWithText(context.getString(R.string.auth_bangumi_token_title)).assertIsDisplayed()

            composeRule.onNodeWithText(confirmLabel).performClick()
            composeRule.onNodeWithText(context.getString(R.string.auth_bangumi_token_missing)).assertIsDisplayed()
        }
    }

    @Test
    fun coverScraperSettingsActivityTogglesReordersAndRefreshesAuthState() {
        assertTrue(BangumiAuthStore.saveTokens(context, "bgm_access", "bgm_refresh", System.currentTimeMillis() + 3_600_000L))
        BangumiAuthStore.saveUserIdentity(context, 42L, "TesterBgm")
        BangumiAuthStore.markNeedsReauth(context, "EXPIRED")

        ActivityScenario.launch(CoverScraperSettingsActivity::class.java).use {
            // 1) Bangumi needsReauth 下行内同时展示「授权」与「取消授权」（cover_logout）并刷新摘要
            composeRule.waitUntil(5_000) {
                composeRule.onAllNodesWithText("Bangumi (TesterBgm)").fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText(context.getString(R.string.cover_bangumi_reauth_summary)).assertIsDisplayed()
            composeRule.onNodeWithText(context.getString(R.string.cover_logout)).assertIsDisplayed()

            // 2) 点击行内「取消授权」后状态流刷新为未授权摘要
            composeRule.onNodeWithText(context.getString(R.string.cover_logout)).performClick()
            composeRule.waitUntil(5_000) {
                composeRule.onAllNodesWithText(context.getString(R.string.cover_bangumi_summary))
                    .fetchSemanticsNodes().isNotEmpty()
            }

            // 3) 点击首行「下移」重排后持久顺序更新
            val moveDownButtons = composeRule.onAllNodesWithText(context.getString(R.string.cover_move_down))
            moveDownButtons[0].performClick()
            composeRule.waitUntil(5_000) {
                AppSettingsStore.getCoverScraperSourceOrder(context).first() == AppSettingsStore.COVER_SOURCE_VNDB
            }

            // 4) 滚动到「仅处理缺少封面的游戏」开关并切换更新持久化设置
            val beforeOnlyMissing = AppSettingsStore.isCoverScraperOnlyMissing(context)
            composeRule.onNodeWithText(context.getString(R.string.cover_only_missing_title))
                .performScrollTo()
                .performClick()
            composeRule.waitUntil(5_000) {
                AppSettingsStore.isCoverScraperOnlyMissing(context) == !beforeOnlyMissing
            }
        }
    }
}
