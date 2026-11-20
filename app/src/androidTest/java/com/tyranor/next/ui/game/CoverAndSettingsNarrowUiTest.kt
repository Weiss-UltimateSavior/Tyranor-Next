package com.tyranor.next.ui.game

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import com.tyranor.next.R
import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.input.InputRemapRepository
import com.tyranor.next.core.settings.AppSettingsStore
import com.tyranor.next.theme.MiuixSettingsTheme
import com.tyranor.next.theme.TyranorNextTheme
import com.tyranor.next.ui.common.AppNavItem
import com.tyranor.next.ui.common.DialogTextButton
import com.tyranor.next.ui.common.SplitEndsActionRow
import com.tyranor.next.ui.cover.CredentialDialogOutcome
import com.tyranor.next.ui.cover.CredentialTokenDialog
import com.tyranor.next.ui.settings.ProfileRow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 真机/模拟器 Compose 窄屏与禁用态 UI 回归：
 * 1. `InputSettingsScreen.ProfileRow` 在 360dp 窄屏下 5 按钮 `FlowRow` 自动折行且全部可见不被截断；
 * 2. `CoverSearchDialog` 在 `binding = true` 期间禁用「关闭」与搜索钮。
 */
@RunWith(AndroidJUnit4::class)
class CoverAndSettingsNarrowUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun inputSettingsProfileButtonsWrapCleanlyAt360DpWidth() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val profile = InputRemapRepository.ProfileSummary(
            id = "custom_1",
            name = "Test Profile",
            isDefault = false,
            isLegacyMain = false,
        )
        composeRule.setContent {
            TyranorNextTheme {
                MiuixSettingsTheme {
                    Box(Modifier.width(360.dp)) {
                        ProfileRow(
                            profile = profile,
                            selected = false,
                            onUse = {},
                            onEdit = {},
                            onRename = {},
                            onCopy = {},
                            onDelete = {},
                            onExport = {},
                        )
                    }
                }
            }
        }
        val labels = listOf(
            R.string.input_settings_profile_use,
            R.string.input_settings_profile_rename,
            R.string.input_settings_profile_copy,
            R.string.input_settings_profile_export,
            R.string.input_settings_profile_delete,
        ).map(context::getString)
        val bounds = labels.map { label ->
            val node = composeRule.onNodeWithText(label)
            node.assertIsDisplayed()
            node.getUnclippedBoundsInRoot()
        }
        bounds.forEach { rect ->
            assertTrue("button must stay within 360dp width: $rect", rect.left >= 0.dp && rect.right <= 360.dp)
        }
    }

    @Test
    fun coverSearchDialogDisablesActionsWhileBinding() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val game = ScanGame(
            title = "Sakura",
            uri = "content://games/ui_test",
            engine = EngineType.KIRIKIRI,
            launchTarget = "data.xp3",
        )
        composeRule.setContent {
            TyranorNextTheme {
                CoverSearchDialog(
                    game = game,
                    source = AppSettingsStore.COVER_SOURCE_VNDB,
                    binding = true,
                    bindError = null,
                    onDismiss = {},
                    onBind = { _, _ -> },
                )
            }
        }
        composeRule.onNodeWithText(context.getString(R.string.common_close)).assertIsNotEnabled()
        composeRule.onNodeWithText(context.getString(R.string.game_search)).assertIsNotEnabled()
    }

    @Test
    fun credentialTokenDialogClearLocksButtonsDuringAsyncClearAndDismissesOnce() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val clearStarted = CountDownLatch(1)
        val allowClearFinish = CountDownLatch(1)
        val dismissCount = AtomicInteger(0)

        composeRule.setContent {
            TyranorNextTheme {
                CredentialTokenDialog(
                    titleRes = R.string.auth_vndb_token_title,
                    instructionsRes = R.string.auth_vndb_token_instructions,
                    missingRes = R.string.auth_vndb_token_missing,
                    savedRes = R.string.auth_vndb_token_saved,
                    authVersion = 1L,
                    readStoredToken = { "stored_token" },
                    hasStoredCredential = { true },
                    clearCredential = {
                        clearStarted.countDown()
                        allowClearFinish.await(5, TimeUnit.SECONDS)
                    },
                    confirmToken = { _, _ -> CredentialDialogOutcome.Saved },
                    onDismiss = { dismissCount.incrementAndGet() },
                )
            }
        }

        val clearLabel = context.getString(R.string.auth_token_clear)
        val cancelLabel = context.getString(R.string.common_cancel)
        val confirmLabel = context.getString(R.string.common_confirm)
        try {
            composeRule.onNodeWithText(clearLabel).performClick()
            assertTrue(clearStarted.await(5, TimeUnit.SECONDS))

            // 清空超过 80ms 期间仍处于 busy，所有按钮保持禁用且不提前关闭
            Thread.sleep(120)
            composeRule.waitForIdle()
            composeRule.onNodeWithText(clearLabel).assertIsNotEnabled()
            composeRule.onNodeWithText(cancelLabel).assertIsNotEnabled()
            composeRule.onNodeWithText(confirmLabel).assertIsNotEnabled()
            assertEquals(0, dismissCount.get())
        } finally {
            allowClearFinish.countDown()
        }
        composeRule.waitUntil(5_000) { dismissCount.get() == 1 }
    }

    @Test
    fun splitEndsDialogAndAppNavItemWithTrailingTextStayReadableOn320DpWidth() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.setContent {
            TyranorNextTheme {
                val baseDensity = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(baseDensity.density, fontScale = 1.5f),
                ) {
                    Box(Modifier.width(320.dp)) {
                        AppNavItem(
                            title = context.getString(R.string.game_play_status),
                            trailingText = "VNDB · Playing · Bangumi · Bound to 4154",
                            onClick = {},
                        )
                    }
                }
            }
        }
        val titleNode = composeRule.onNodeWithText(context.getString(R.string.game_play_status))
        titleNode.assertIsDisplayed()
        val titleBounds = titleNode.getUnclippedBoundsInRoot()
        assertTrue("title width must stay readable: $titleBounds", (titleBounds.right - titleBounds.left) > 40.dp)
    }

    @Test
    fun splitEndsActionRowWrapsCleanlyWithin320DpWidth() {
        composeRule.setContent {
            TyranorNextTheme {
                Box(Modifier.width(320.dp)) {
                    SplitEndsActionRow(
                        leading = {
                            DialogTextButton(text = "Very Long Leading Action Button", onClick = {})
                        },
                        trailing = {
                            DialogTextButton(text = "Confirm Action", onClick = {})
                        },
                    )
                }
            }
        }
        val leadingNode = composeRule.onNodeWithText("Very Long Leading Action Button")
        val confirmNode = composeRule.onNodeWithText("Confirm Action")
        leadingNode.assertIsDisplayed()
        confirmNode.assertIsDisplayed()
        val leadingBounds = leadingNode.getUnclippedBoundsInRoot()
        val confirmBounds = confirmNode.getUnclippedBoundsInRoot()
        assertTrue("leading button must fit 320dp: $leadingBounds", leadingBounds.left >= 0.dp && leadingBounds.right <= 320.dp)
        assertTrue("confirm button must fit 320dp: $confirmBounds", confirmBounds.left >= 0.dp && confirmBounds.right <= 320.dp)
        assertTrue("wrapped trailing button must sit below leading without overlap", confirmBounds.top >= leadingBounds.bottom)
    }

    @Test
    fun playStatusDialogDisplaysAllFourSources() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val game = ScanGame(
            title = "Sakura",
            uri = "content://games/play_status_ui",
            engine = EngineType.KIRIKIRI,
            launchTarget = "data.xp3",
        )
        composeRule.setContent {
            TyranorNextTheme {
                PlayStatusDialog(
                    game = game,
                    onDismiss = {},
                    onBindingUpdated = { _, _ -> },
                )
            }
        }
        composeRule.onNodeWithText(context.getString(R.string.game_play_status_title)).assertIsDisplayed()
        composeRule.onNodeWithText("Bangumi").assertIsDisplayed()
        composeRule.onNodeWithText("VNDB").assertIsDisplayed()
        composeRule.onNodeWithText("Hikarinagi").assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.cover_source_nextmoe)).assertIsDisplayed()
    }
}
