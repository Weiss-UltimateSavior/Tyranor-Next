package com.tyranor.next.ui.game

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tyranor.next.R
import com.tyranor.next.core.cover.CoverScrapeTaskManager
import com.tyranor.next.core.cover.VndbCoverService
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.game.shortcut.GameShortcutManager
import com.tyranor.next.core.game.shortcut.deleteShortcutCropBitmap
import com.tyranor.next.theme.AppComponentShape
import com.tyranor.next.theme.CoverBadgeBackground
import com.tyranor.next.theme.CoverBadgeText
import com.tyranor.next.theme.DangerFill
import com.tyranor.next.theme.DialogItemSurface
import com.tyranor.next.theme.OnDangerFill
import com.tyranor.next.theme.TextColor
import com.tyranor.next.theme.glassBorder
import com.tyranor.next.ui.common.AppAlertDialog
import com.tyranor.next.ui.common.AppDialogContentVerticalPadding
import com.tyranor.next.ui.common.AppNavItem
import com.tyranor.next.ui.common.AppSearchField
import com.tyranor.next.ui.common.DialogTextButton
import com.tyranor.next.ui.common.NoIndication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// 长按弹窗顶部超框封面的尺寸：封面从卡片上沿向上探出 Overhang，卡片内为封面预留剩余高度。
private val QuickActionCoverWidth = 100.dp
private val QuickActionCoverHeight = 132.dp
private val QuickActionCoverOverhang = 34.dp
private val QuickActionCoverTitleGap = 14.dp
private val QuickActionItemSpacing = 10.dp
// 封面在卡片内的可见高度去掉正文顶部内边距，即标题行最小高度（默认与封面底边对齐）。
private val QuickActionHeaderHeight =
    QuickActionCoverHeight - QuickActionCoverOverhang - AppDialogContentVerticalPadding

/**
 * 游戏卡片长按操作弹窗：游戏页瀑布流 / 封面流与首页快捷启动卡共用。
 *
 * 布局：顶部为超出面板上沿的游戏封面 + 游戏标题，封面右下角为更换封面入口（点击封面触发）；
 * 中部为动作项（首页快捷启动、桌面快捷方式、名称修改），每项独占一行，底部为整行删除长按钮。
 * 条目为「直接执行的动作」，点击后关闭弹窗或让位给二级确认弹窗：
 * 快捷启动（满 3 个时提示）、添加桌面（裁切图标后请求固定快捷方式）、
 * 名称修改、修改封面（点击封面）、删除游戏（二次确认）。
 *
 * 与「游戏操作抽屉」的分工：抽屉（点按卡片打开）只保留启动相关动作与设置/存档/补丁入口；
 * 卡片管理动作统一收口到本弹窗（SPEC.md「游戏库」）。
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

    AppAlertDialog(
        onDismissRequest = onDismiss,
        // 顶部：超出卡片上沿的封面 + 封面右侧空白区内的游戏标题
        title = {
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = QuickActionHeaderHeight),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.width(QuickActionCoverWidth + QuickActionCoverTitleGap))
                Text(
                    text = game.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    // 只在封面右侧的空白区（去掉 Spacer 后 Text 独占的宽度）内水平居中，
                    // 不是相对整个弹窗宽度居中。
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        },
        // 条目列表可能超过小屏横屏的正文高度上限，允许正文区域内滚动
        textScrollEnabled = true,
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(QuickActionItemSpacing)) {
                // 每个动作各占一整行；删除游戏为底部整行长按钮
                QuickActionItem(
                    title = if (quickLaunched) {
                        stringResource(R.string.game_remove_quick_launch)
                    } else {
                        stringResource(R.string.game_add_quick_launch)
                    },
                    leadingIcon = R.drawable.ic_home,
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
                QuickActionItem(
                    title = stringResource(R.string.game_add_desktop_shortcut),
                    leadingIcon = R.drawable.ic_sheet_desktop_shortcut,
                    onClick = { if (!shortcutRequestInFlight) openShortcutCrop() },
                )
                QuickActionItem(
                    title = stringResource(R.string.game_rename),
                    leadingIcon = R.drawable.ic_sheet_rename,
                    onClick = { showRenameDialog = true },
                )
                QuickActionDeleteButton(onClick = { showDeleteConfirm = true })
            }
        },
        confirmButton = {},
        // 删除游戏即最后一行条目，不再额外显示底部按钮；遮罩/返回键关闭
        showButtonRow = false,
        topOverhang = {
            QuickActionCover(
                game = game,
                // 点击封面与原先的「修改封面」条目一致：相册选图后走统一封面保存流程
                onClick = { if (!isBatchScrapingActive()) imagePicker.launch("image/*") },
            )
        },
        topOverhangHeight = QuickActionCoverOverhang,
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
 * 动作条目：沿用 [AppNavItem] 的图标 + 标题排版与动态底色，
 * 右侧统一显示加粗箭头（多数条目会进入二级弹窗/选择器）。
 */
@Composable
private fun QuickActionItem(
    title: String,
    @DrawableRes leadingIcon: Int,
    onClick: () -> Unit,
) {
    AppNavItem(
        title = title,
        leadingIcon = leadingIcon,
        containerColor = DialogItemSurface,
        leadingIconTint = MaterialTheme.colorScheme.primary,
        indication = null,
        onClick = onClick,
    )
}

/** 删除游戏：整行纯色长按钮，尺寸对齐游戏操作抽屉底部的主操作长按钮。 */
@Composable
private fun QuickActionDeleteButton(onClick: () -> Unit) {
    // 实底危险按钮取固定深红 + 白字：深色 scheme 的 error/onError 是浅粉 + 深褐红，
    // 在复古玻璃的深色面板上会发白失焦，与其它外观风格割裂（见 theme/Color.kt）。
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(AppComponentShape)
            .background(DangerFill)
            // 弹窗点击反馈规范：长按钮不使用涟漪
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            // 与游戏操作抽屉底部 NoRippleButton 相同的 20/10 内边距（内容行高 20dp → 按钮高 40dp）
            .padding(horizontal = 20.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_sheet_delete),
            contentDescription = null,
            colorFilter = ColorFilter.tint(OnDangerFill),
            modifier = Modifier.size(20.dp),
        )
        Text(
            stringResource(R.string.game_delete_title),
            style = MaterialTheme.typography.bodyMedium,
            color = OnDangerFill,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

/** 弹窗顶部超出卡片上沿的游戏封面；点击封面修改封面，右下角图标作为入口提示。 */
@Composable
private fun QuickActionCover(
    game: ScanGame,
    onClick: () -> Unit,
) {
    val coverBitmap by rememberCoverBitmap(game.coverUri)
    val editCoverLabel = stringResource(R.string.game_edit_cover)
    Box(
        modifier = Modifier
            .size(width = QuickActionCoverWidth, height = QuickActionCoverHeight)
            .clip(AppComponentShape)
            .background(game.engine.coverColor())
            .glassBorder()
            // 弹窗点击反馈规范：封面入口不显示涟漪
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = coverBitmap
        if (bmp != null) {
            Image(
                bitmap = bmp,
                // 封面是装饰性配图，游戏名已由右侧标题提供，避免读屏重复播报
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            // 对话框封面较窄，只保留品牌占位；引擎全名在 100dp 宽度内会折成多行
            Text(
                "Tyranor",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.7f),
            )
        }
        // 右下角修改封面入口：固定半透明黑底 + 白图标（与封面角标样式一致）
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(4.dp)
                .size(24.dp)
                .clip(AppComponentShape)
                .background(CoverBadgeBackground),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(R.drawable.ic_sheet_edit_cover),
                contentDescription = editCoverLabel,
                colorFilter = ColorFilter.tint(CoverBadgeText),
                modifier = Modifier.size(16.dp),
            )
        }
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
