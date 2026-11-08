package com.tyranor.next.ui.game

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.tyranor.next.R
import com.tyranor.next.core.cover.CoverScrapeTaskManager
import com.tyranor.next.core.cover.VndbCoverService
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.game.shortcut.GameShortcutManager
import com.tyranor.next.core.game.shortcut.deleteShortcutCropBitmap
import com.tyranor.next.theme.DialogItemSurface
import com.tyranor.next.ui.common.AppAlertDialog
import com.tyranor.next.ui.common.AppNavItem
import com.tyranor.next.ui.common.AppSearchField
import com.tyranor.next.ui.common.DialogTextButton
import com.tyranor.next.ui.common.NoIndication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 游戏卡片长按操作弹窗：游戏页瀑布流 / 封面流与首页快捷启动卡共用。
 *
 * 条目为「直接执行的动作」，点击后关闭弹窗或让位给二级确认弹窗：
 * 快捷启动（满 3 个时提示）、添加到桌面（裁切图标后请求固定快捷方式）、
 * 名称修改、修改封面（相册选图）、删除游戏（二次确认）。
 *
 * 与「游戏操作抽屉」的分工：抽屉（点按卡片打开）只保留启动相关动作与设置/存档/补丁入口；
 * 卡片管理动作统一收口到本弹窗（SPEC.md「游戏库」）。
 *
 * 条目统一展示右侧指示箭头：本弹窗多数条目会进入二级弹窗，为保持排版一致不按「执行动作」隐藏箭头。
 */
@Composable
internal fun GameQuickActionsDialog(
    game: ScanGame,
    quickLaunched: Boolean,
    onDismiss: () -> Unit,
    onGameUpdated: (ScanGame) -> Unit,
    onDeleteGame: () -> Unit,
    onQuickLaunchToggle: () -> Boolean,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showRenameDialog by rememberSaveable(game.uri) { mutableStateOf(false) }
    var showDeleteConfirm by rememberSaveable(game.uri) { mutableStateOf(false) }
    var shortcutRequestInFlight by remember(game.uri) { mutableStateOf(false) }
    var shortcutCropUriText by rememberSaveable(game.uri) { mutableStateOf<String?>(null) }
    var shortcutPickerForNoCover by rememberSaveable(game.uri) { mutableStateOf(false) }

    val batchScrapeRunningMessage = stringResource(R.string.game_batch_scraping_running)
    val coverSetFailedMessage = stringResource(R.string.game_cover_set_failed)
    val quickLaunchFullMessage = stringResource(R.string.game_quick_launch_full)
    val shortcutRequestedMessage = stringResource(R.string.game_desktop_shortcut_requested)
    val shortcutUpdatedMessage = stringResource(R.string.game_desktop_shortcut_updated)
    val shortcutUnsupportedMessage = stringResource(R.string.game_desktop_shortcut_unsupported)
    val shortcutFailedMessage = stringResource(R.string.game_desktop_shortcut_failed)

    /** Blocks destructive cover/shortcut actions while a batch scrape is running. */
    fun isBatchScrapingActive(): Boolean {
        if (!CoverScrapeTaskManager.state.value.running) return false
        android.widget.Toast.makeText(context, batchScrapeRunningMessage, android.widget.Toast.LENGTH_SHORT).show()
        return true
    }

    /** Requests a new shortcut or updates the existing shortcut for this game. */
    fun requestDesktopShortcut(customIconUri: Uri? = null) {
        if (shortcutRequestInFlight) {
            // 上一次请求（系统确认框）尚未结束：丢弃新生成的临时图标，避免孤儿文件
            deleteShortcutCropBitmap(context.applicationContext, customIconUri)
            return
        }
        shortcutRequestInFlight = true
        scope.launch {
            val result = try {
                GameShortcutManager.requestPinShortcut(
                    context = context,
                    game = game,
                    launchIntent = GameShortcutActivity.createIntent(context, game.uri),
                    customIconUri = customIconUri,
                )
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                GameShortcutManager.RequestResult.FAILED
            } finally {
                shortcutRequestInFlight = false
                deleteShortcutCropBitmap(context.applicationContext, customIconUri)
            }
            val message = when (result) {
                GameShortcutManager.RequestResult.REQUESTED -> shortcutRequestedMessage
                GameShortcutManager.RequestResult.UPDATED -> shortcutUpdatedMessage
                GameShortcutManager.RequestResult.UNSUPPORTED -> shortcutUnsupportedMessage
                GameShortcutManager.RequestResult.FAILED -> shortcutFailedMessage
            }
            android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
            if (result == GameShortcutManager.RequestResult.REQUESTED || result == GameShortcutManager.RequestResult.UPDATED) {
                onDismiss()
            }
        }
    }

    val shortcutIconPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            shortcutPickerForNoCover = false
            shortcutCropUriText = uri.toString()
        } else if (shortcutPickerForNoCover) {
            shortcutPickerForNoCover = false
            requestDesktopShortcut()
        }
    }

    /** Opens cover cropping, falling back to custom-image selection when no cover exists. */
    fun openShortcutCrop() {
        val coverUri = game.coverUri?.takeIf { it.isNotBlank() }
        if (coverUri != null) {
            shortcutCropUriText = coverUri
        } else {
            shortcutPickerForNoCover = true
            shortcutIconPicker.launch("image/*")
        }
    }

    // 打开相册选择自定义封面
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        if (isBatchScrapingActive()) return@rememberLauncherForActivityResult
        scope.launch {
            val updated = withContext(Dispatchers.IO) {
                try {
                    VndbCoverService.saveCustomCover(context, game, uri)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    null
                }
            }
            if (updated != null) {
                onGameUpdated(updated)
                onDismiss()
            } else {
                android.widget.Toast
                    .makeText(context, coverSetFailedMessage, android.widget.Toast.LENGTH_SHORT)
                    .show()
            }
        }
    }

    val primaryColor = MaterialTheme.colorScheme.primary
    val dangerColor = MaterialTheme.colorScheme.error

    AppAlertDialog(
        onDismissRequest = onDismiss,
        // 卡片本身已展示游戏名，弹窗只承载动作条目，不再重复标题
        title = {},
        // 条目列表可能超过小屏横屏的正文高度上限，允许正文区域内滚动
        textScrollEnabled = true,
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AppNavItem(
                    title = if (quickLaunched) {
                        stringResource(R.string.game_remove_quick_launch)
                    } else {
                        stringResource(R.string.game_add_quick_launch)
                    },
                    leadingIcon = R.drawable.ic_home,
                    containerColor = DialogItemSurface,
                    leadingIconTint = primaryColor,
                    indication = null,
                    onClick = {
                        if (onQuickLaunchToggle()) {
                            onDismiss()
                        } else {
                            android.widget.Toast
                                .makeText(context, quickLaunchFullMessage, android.widget.Toast.LENGTH_SHORT)
                                .show()
                        }
                    },
                )
                AppNavItem(
                    title = stringResource(R.string.game_add_desktop_shortcut),
                    leadingIcon = R.drawable.ic_sheet_desktop_shortcut,
                    containerColor = DialogItemSurface,
                    leadingIconTint = primaryColor,
                    indication = null,
                    onClick = { if (!shortcutRequestInFlight) openShortcutCrop() },
                )
                AppNavItem(
                    title = stringResource(R.string.game_rename),
                    leadingIcon = R.drawable.ic_sheet_rename,
                    containerColor = DialogItemSurface,
                    leadingIconTint = primaryColor,
                    indication = null,
                    onClick = { showRenameDialog = true },
                )
                AppNavItem(
                    title = stringResource(R.string.game_edit_cover),
                    leadingIcon = R.drawable.ic_sheet_edit_cover,
                    containerColor = DialogItemSurface,
                    leadingIconTint = primaryColor,
                    indication = null,
                    onClick = { if (!isBatchScrapingActive()) imagePicker.launch("image/*") },
                )
                AppNavItem(
                    title = stringResource(R.string.game_delete_title),
                    leadingIcon = R.drawable.ic_sheet_delete,
                    containerColor = DialogItemSurface,
                    leadingIconTint = dangerColor,
                    titleColor = dangerColor,
                    indication = null,
                    onClick = { showDeleteConfirm = true },
                )
            }
        },
        confirmButton = {
            DialogTextButton(
                text = stringResource(R.string.common_close),
                onClick = onDismiss,
            )
        },
    )

    shortcutCropUriText?.let { uriText ->
        GameShortcutCropDialog(
            imageUri = Uri.parse(uriText),
            onDismiss = { shortcutCropUriText = null },
            onPickImage = {
                if (!shortcutRequestInFlight) {
                    shortcutPickerForNoCover = false
                    shortcutIconPicker.launch("image/*")
                }
            },
            onConfirm = { croppedUri ->
                shortcutCropUriText = null
                requestDesktopShortcut(croppedUri)
            },
        )
    }

    if (showRenameDialog) {
        RenameGameDialog(
            game = game,
            onDismiss = { showRenameDialog = false },
            onConfirm = { title ->
                showRenameDialog = false
                onGameUpdated(game.copy(title = title))
            },
        )
    }

    if (showDeleteConfirm) {
        AppAlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = {
                Text(
                    stringResource(R.string.game_delete_title),
                    style = MaterialTheme.typography.titleMedium,
                )
            },
            text = {
                Text(
                    stringResource(R.string.game_delete_message, game.title),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                DialogTextButton(
                    text = stringResource(R.string.common_delete),
                    contentColor = MaterialTheme.colorScheme.error,
                    onClick = {
                        showDeleteConfirm = false
                        onDeleteGame()
                        onDismiss()
                    },
                )
            },
            dismissButton = {
                DialogTextButton(
                    text = stringResource(R.string.common_cancel),
                    onClick = { showDeleteConfirm = false },
                )
            },
        )
    }

}

/**
 * 名称修改弹窗：输入框走统一 [AppSearchField]，键盘「完成」动作在内容有效时直接保存。
 * 与长按操作弹窗串行展示（弹窗内再弹窗），保存后回到长按弹窗。
 */
@Composable
private fun RenameGameDialog(
    game: ScanGame,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var title by rememberSaveable(game.uri, game.title) { mutableStateOf(game.title) }
    val normalizedTitle = title.trim()
    val canConfirm = normalizedTitle.isNotEmpty() && normalizedTitle != game.title

    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.game_rename), style = MaterialTheme.typography.titleMedium) },
        text = {
            // 弹窗正文内的输入框禁用点击反馈（AGENTS.md「弹窗点击反馈统一规范」）
            CompositionLocalProvider(LocalIndication provides NoIndication) {
                // 统一 Miuix 风格输入框（AppSearchField）；键盘“搜索/完成”动作直接保存（内容有效时）
                AppSearchField(
                    query = title,
                    onQueryChange = { title = it },
                    onSearch = { if (canConfirm) onConfirm(normalizedTitle) },
                    imeAction = ImeAction.Done,
                    leadingIcon = painterResource(R.drawable.ic_sheet_rename),
                    iconContentDescription = stringResource(R.string.game_rename),
                    textStyle = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        confirmButton = {
            DialogTextButton(
                text = stringResource(R.string.common_save),
                enabled = canConfirm,
                onClick = { onConfirm(normalizedTitle) },
            )
        },
        dismissButton = {
            DialogTextButton(onClick = onDismiss, text = stringResource(R.string.common_cancel))
        },
    )
}
