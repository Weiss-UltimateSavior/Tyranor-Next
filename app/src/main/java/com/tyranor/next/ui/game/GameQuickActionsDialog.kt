package com.tyranor.next.ui.game

import android.net.Uri
import android.util.Log
import android.widget.Toast
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
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
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
import com.tyranor.next.ui.common.motion.MotionHaptics
import com.tyranor.next.ui.common.motion.PressSquishDefaults
import com.tyranor.next.ui.common.motion.ProvideEntrance
import com.tyranor.next.ui.common.motion.pressSquish
import com.tyranor.next.ui.common.motion.shakeOnTrigger
import com.tyranor.next.ui.common.motion.skipEntranceOnDrag
import com.tyranor.next.ui.common.motion.staggeredEntrance
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.tyranor.next.ui.common.notice.AppNotice

private const val TAG = "GameQuickActions"

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
 * 长按操作弹窗的纯逻辑状态机（含单调递增的打开序号 [openSequence]）。
 *
 * 退场期间窗口直接放行触摸时，用户可能在旧退场尚未结束前再次长按同一张或另一张卡片：
 * - 宿主层用 [openSequence] 作为 `key(...)`，每次重开立刻销毁旧弹窗实例并挂载新实例；
 * - 旧实例在退出/销毁收尾时触发的关闭回调携带旧序号，只有 `sequence == openSequence` 时才清空 [targetUri]，
 *   过期的关闭回调直接忽略，防止把刚重开的新弹窗误关。
 */
internal class QuickActionsDialogSession(
    initialTargetUri: String? = null,
    initialSequence: Long = if (initialTargetUri != null) 1L else 0L,
) {
    var targetUri: String? = initialTargetUri
        private set
    var openSequence: Long = initialSequence
        private set

    fun open(uri: String): Long {
        targetUri = uri
        openSequence += 1L
        return openSequence
    }

    fun dismissIfCurrent(sequence: Long): Boolean {
        if (targetUri == null || sequence != openSequence) return false
        targetUri = null
        return true
    }

    fun clearIfTarget(uri: String): Boolean {
        if (targetUri != uri) return false
        targetUri = null
        return true
    }

    fun clearIfMissing(containsUri: (String) -> Boolean): Boolean {
        val current = targetUri ?: return false
        if (containsUri(current)) return false
        targetUri = null
        return true
    }
}

@Stable
internal class QuickActionsDialogHostState(
    initialTargetUri: String? = null,
    initialSequence: Long = if (initialTargetUri != null) 1L else 0L,
) {
    private val session = QuickActionsDialogSession(initialTargetUri, initialSequence)
    var targetUri by mutableStateOf(session.targetUri)
        private set
    var openSequence by mutableLongStateOf(session.openSequence)
        private set

    private fun sync() {
        targetUri = session.targetUri
        openSequence = session.openSequence
    }

    fun open(uri: String) {
        session.open(uri)
        sync()
    }

    fun dismissIfCurrent(sequence: Long) {
        if (session.dismissIfCurrent(sequence)) sync()
    }

    fun clearIfTarget(uri: String) {
        if (session.clearIfTarget(uri)) sync()
    }

    fun clearIfMissing(containsUri: (String) -> Boolean) {
        if (session.clearIfMissing(containsUri)) sync()
    }

    companion object {
        val Saver = listSaver<QuickActionsDialogHostState, Any?>(
            save = { listOf(it.targetUri, it.openSequence) },
            restore = { saved ->
                QuickActionsDialogHostState(
                    initialTargetUri = saved.getOrNull(0) as String?,
                    initialSequence = (saved.getOrNull(1) as? Number)?.toLong() ?: 0L,
                )
            },
        )
    }
}

@Composable
internal fun rememberQuickActionsDialogHostState(): QuickActionsDialogHostState =
    rememberSaveable(saver = QuickActionsDialogHostState.Saver) { QuickActionsDialogHostState() }

/**
 * 卡片管理动作（修改封面 / 首页快捷启动 / 桌面快捷方式 / 名称修改 / 删除游戏）的共享控制器：
 * 长按弹窗（[GameQuickActionsDialog]）与游戏盒翻开的书内「管理」分栏共用同一份状态机与业务流程。
 */
@Stable
internal class GameQuickActionsController(
    val feedback: QuickActionFeedback,
    private val quickLaunchShown: () -> Boolean,
    private val shortcutPending: () -> Boolean,
    private val deleteConfirmShown: () -> Boolean,
    private val pickCoverAction: () -> Unit,
    private val toggleQuickLaunchAction: () -> Unit,
    private val addDesktopShortcutAction: () -> Unit,
    private val openRenameAction: () -> Unit,
    private val openDeleteAction: () -> Unit,
    private val hostDialogs: @Composable () -> Unit,
) {
    val shownQuickLaunched: Boolean get() = quickLaunchShown()
    val shortcutRequestInFlight: Boolean get() = shortcutPending()
    val showDeleteConfirm: Boolean get() = deleteConfirmShown()

    fun pickCover() = pickCoverAction()
    fun toggleQuickLaunch() = toggleQuickLaunchAction()
    fun addDesktopShortcut() = addDesktopShortcutAction()
    fun openRename() = openRenameAction()
    fun openDelete() = openDeleteAction()

    @Composable
    fun HostDialogs() = hostDialogs()
}

/**
 * 构建卡片管理共享控制器；[closeOnQuickAction] 为 true 时沿用长按弹窗行为（快捷启动 / 改封面 / 添加桌面后收起弹窗），
 * 为 false 时留在当前宿主（如翻开的书内）就地更新状态。
 */
@Composable
internal fun rememberGameQuickActionsController(
    game: ScanGame,
    quickLaunched: Boolean,
    closeOnQuickAction: Boolean,
    onDismiss: () -> Unit,
    onGameUpdated: (ScanGame) -> Unit,
    onGameCoverUpdated: (ScanGame) -> Unit = onGameUpdated,
    onGameRenamed: ((String, String) -> Unit)? = null,
    onDeleteGame: () -> Unit,
    onQuickLaunchToggle: () -> Boolean,
    exitHandle: GameDialogExitHandle? = null,
    canInteract: () -> Boolean = { true },
    onShortcutCropOpened: () -> Unit = {},
): GameQuickActionsController {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showRenameDialog by rememberSaveable(game.uri) { mutableStateOf(false) }
    var showDeleteConfirm by rememberSaveable(game.uri) { mutableStateOf(false) }
    var shortcutRequestInFlight by remember(game.uri) { mutableStateOf(false) }
    var shortcutCropUriText by rememberSaveable(game.uri) { mutableStateOf<String?>(null) }
    var shortcutPickerForNoCover by rememberSaveable(game.uri) { mutableStateOf(false) }
    val feedback = remember(game.uri) { QuickActionFeedback() }
    val latestGame by rememberUpdatedState(game)
    val latestQuickLaunched by rememberUpdatedState(quickLaunched)
    val latestOnDismiss by rememberUpdatedState(onDismiss)
    val latestOnGameUpdated by rememberUpdatedState(onGameUpdated)
    val latestOnGameCoverUpdated by rememberUpdatedState(onGameCoverUpdated)
    val latestOnGameRenamed by rememberUpdatedState(onGameRenamed)
    val latestOnDeleteGame by rememberUpdatedState(onDeleteGame)
    val latestOnQuickLaunchToggle by rememberUpdatedState(onQuickLaunchToggle)
    val latestCanInteract by rememberUpdatedState(canInteract)
    val latestOnShortcutCropOpened by rememberUpdatedState(onShortcutCropOpened)

    val batchScrapeRunningMessage by rememberUpdatedState(stringResource(R.string.game_batch_scraping_running))
    val coverSetFailedMessage = stringResource(R.string.game_cover_set_failed)
    val quickLaunchFullMessage by rememberUpdatedState(stringResource(R.string.game_quick_launch_full))
    val shortcutRequestedMessage by rememberUpdatedState(stringResource(R.string.game_desktop_shortcut_requested))
    val shortcutUpdatedMessage by rememberUpdatedState(stringResource(R.string.game_desktop_shortcut_updated))
    val shortcutUnsupportedMessage by rememberUpdatedState(stringResource(R.string.game_desktop_shortcut_unsupported))
    val shortcutFailedMessage by rememberUpdatedState(stringResource(R.string.game_desktop_shortcut_failed))

    fun finishQuickAction() {
        if (!closeOnQuickAction) return
        if (exitHandle != null) exitHandle.closeThen(latestOnDismiss) else latestOnDismiss()
    }

    /** Blocks destructive cover/shortcut actions while a batch scrape is running. */
    fun isBatchScrapingActive(): Boolean {
        if (!CoverScrapeTaskManager.state.value.running) return false
        AppNotice.makeText(context, batchScrapeRunningMessage, Toast.LENGTH_SHORT, AppNotice.Kind.Limit).show()
        // 仅「修改封面」走这道拦截：被拒绝时该条目抖一下
        feedback.rejectCover()
        return true
    }

    /** Requests a new shortcut or updates the existing shortcut for this game. */
    fun requestDesktopShortcut(customIconUri: Uri? = null) {
        val targetGame = latestGame
        val appContext = context.applicationContext
        if (shortcutRequestInFlight) {
            if (customIconUri != null) {
                scope.launch(NonCancellable + Dispatchers.IO) {
                    deleteShortcutCropBitmap(appContext, customIconUri)
                }
            }
            return
        }
        shortcutRequestInFlight = true
        scope.launch {
            val result = try {
                GameShortcutManager.requestPinShortcut(
                    context = context,
                    game = targetGame,
                    launchIntent = GameShortcutActivity.createIntent(context, targetGame.uri),
                    customIconUri = customIconUri,
                    pinResultIntent = GameShortcutResultReceiver.createIntent(context),
                )
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                GameShortcutManager.RequestResult.FAILED
            } finally {
                shortcutRequestInFlight = false
                if (customIconUri != null) {
                    withContext(NonCancellable + Dispatchers.IO) {
                        deleteShortcutCropBitmap(appContext, customIconUri)
                    }
                }
            }
            val message = when (result) {
                GameShortcutManager.RequestResult.REQUESTED -> shortcutRequestedMessage
                GameShortcutManager.RequestResult.UPDATED -> shortcutUpdatedMessage
                GameShortcutManager.RequestResult.UNSUPPORTED -> shortcutUnsupportedMessage
                GameShortcutManager.RequestResult.FAILED -> shortcutFailedMessage
            }
            val kind = when (result) {
                GameShortcutManager.RequestResult.REQUESTED -> AppNotice.Kind.Info
                GameShortcutManager.RequestResult.UPDATED -> AppNotice.Kind.Success
                GameShortcutManager.RequestResult.UNSUPPORTED, GameShortcutManager.RequestResult.FAILED -> AppNotice.Kind.Failure
            }
            AppNotice.makeText(
                context, message,
                if (result == GameShortcutManager.RequestResult.REQUESTED) Toast.LENGTH_LONG else Toast.LENGTH_SHORT,
                kind,
            ).show()
            if (kind != AppNotice.Kind.Failure) {
                finishQuickAction()
            } else {
                feedback.rejectShortcut()
            }
        }
    }

    val shortcutIconPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            shortcutPickerForNoCover = false
            latestOnShortcutCropOpened()
            shortcutCropUriText = uri.toString()
        } else if (shortcutPickerForNoCover) {
            shortcutPickerForNoCover = false
            requestDesktopShortcut()
        }
    }

    /** Opens cover cropping, falling back to custom-image selection when no cover exists. */
    fun openShortcutCrop() {
        latestOnShortcutCropOpened()
        val coverUri = latestGame.coverUri?.takeIf { it.isNotBlank() }
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
        val targetGame = latestGame
        scope.launch {
            val appContext = context.applicationContext
            val updated = withContext(Dispatchers.IO) {
                try {
                    VndbCoverService.saveCustomCover(appContext, targetGame, uri)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Throwable) {
                    // 与 HttpJson 同口径：脱敏 Log.w 留排障线索，UI 仅展示失败文案
                    Log.w(TAG, "save custom cover failed", e)
                    null
                }
            }
            if (updated != null) {
                latestOnGameCoverUpdated(updated)
                if (closeOnQuickAction) {
                    finishQuickAction()
                } else {
                    MotionHaptics.perform(MotionHaptics.Kind.Confirm, requireTouch = false)
                }
            } else {
                AppNotice.makeText(context, coverSetFailedMessage, Toast.LENGTH_SHORT, AppNotice.Kind.Failure)
                    .show()
                feedback.rejectCover()
            }
        }
    }

    fun pickCover() {
        if (latestCanInteract() && !isBatchScrapingActive()) imagePicker.launch("image/*")
    }
    fun toggleQuickLaunch() {
        if (latestCanInteract()) {
            if (closeOnQuickAction) {
                feedback.toggle(latestOnQuickLaunchToggle, close = ::finishQuickAction) {
                    AppNotice.makeText(context, quickLaunchFullMessage, Toast.LENGTH_SHORT, AppNotice.Kind.Limit)
                        .show()
                }
            } else {
                feedback.toggleInPlace(
                    change = latestOnQuickLaunchToggle,
                    confirmed = { MotionHaptics.perform(MotionHaptics.Kind.Confirm) },
                    rejected = {
                        AppNotice.makeText(context, quickLaunchFullMessage, Toast.LENGTH_SHORT, AppNotice.Kind.Limit)
                            .show()
                    },
                )
            }
        }
    }
    fun addDesktopShortcut() {
        if (latestCanInteract()) {
            if (!shortcutRequestInFlight) openShortcutCrop() else feedback.rejectShortcut()
        }
    }
    fun openRename() {
        if (latestCanInteract()) showRenameDialog = true
    }
    fun openDelete() {
        if (latestCanInteract()) showDeleteConfirm = true
    }
    val dialogs: @Composable () -> Unit = {
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
            val targetGame = latestGame
            RenameGameDialog(
                game = targetGame,
                onDismiss = { showRenameDialog = false },
                onConfirm = { title ->
                    showRenameDialog = false
                    val renamer = latestOnGameRenamed
                    if (renamer != null) renamer(targetGame.uri, title) else latestOnGameUpdated(targetGame.copy(title = title))
                },
            )
        }

        if (showDeleteConfirm) {
            val targetGame = latestGame
            // 确认删除：确认弹窗（或书内确认页）先退场/翻回，再由外层收起后删除
            val deleteExitHandle = rememberGameDialogExitHandle()
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
                        stringResource(R.string.game_delete_message, targetGame.title),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
                confirmButton = {
                    DialogTextButton(
                        text = stringResource(R.string.common_delete),
                        contentColor = MaterialTheme.colorScheme.error,
                        onClick = {
                            deleteExitHandle.closeThen {
                                showDeleteConfirm = false
                                if (closeOnQuickAction) {
                                    val complete = {
                                        PlayStatusSummaryCache.invalidate(targetGame.uri)
                                        latestOnDeleteGame()
                                        latestOnDismiss()
                                    }
                                    if (exitHandle != null) exitHandle.closeThen(complete) else complete()
                                } else {
                                    PlayStatusSummaryCache.invalidate(targetGame.uri)
                                    latestOnDeleteGame()
                                }
                            }
                        },
                    )
                },
                dismissButton = {
                    deleteExitHandle.capture()
                    DialogTextButton(
                        text = stringResource(R.string.common_cancel),
                        onClick = { showDeleteConfirm = false },
                    )
                },
            )
        }
    }
    return remember(game.uri, feedback, context, scope, closeOnQuickAction, exitHandle, imagePicker, shortcutIconPicker) {
        GameQuickActionsController(
            feedback = feedback,
            quickLaunchShown = {
                if (closeOnQuickAction) feedback.shownQuickLaunch(latestQuickLaunched) else latestQuickLaunched
            },
            shortcutPending = { shortcutRequestInFlight },
            deleteConfirmShown = { showDeleteConfirm },
            pickCoverAction = ::pickCover,
            toggleQuickLaunchAction = ::toggleQuickLaunch,
            addDesktopShortcutAction = ::addDesktopShortcut,
            openRenameAction = ::openRename,
            openDeleteAction = ::openDelete,
            hostDialogs = dialogs,
        )
    }
}

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
    onGameCoverUpdated: (ScanGame) -> Unit = onGameUpdated,
    onGameRenamed: ((String, String) -> Unit)? = null,
    onDeleteGame: () -> Unit,
    onQuickLaunchToggle: () -> Boolean,
) {
    val exitHandle = rememberGameDialogExitHandle()
    val controller = rememberGameQuickActionsController(
        game = game,
        quickLaunched = quickLaunched,
        closeOnQuickAction = true,
        onDismiss = onDismiss,
        onGameUpdated = onGameUpdated,
        onGameCoverUpdated = onGameCoverUpdated,
        onGameRenamed = onGameRenamed,
        onDeleteGame = onDeleteGame,
        onQuickLaunchToggle = onQuickLaunchToggle,
        exitHandle = exitHandle,
    )

    AppAlertDialog(
        onDismissRequest = onDismiss,
        dismissEnabled = !controller.showDeleteConfirm,
        passTouchesDuringExit = true,
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
            exitHandle.capture()
            // 动作条目自上而下依次弹入；正文可滚动，手指一拖未完成的入场立即收尾
            ProvideEntrance {
                Column(
                    modifier = Modifier.skipEntranceOnDrag(),
                    verticalArrangement = Arrangement.spacedBy(QuickActionItemSpacing),
                ) {
                    QuickActionItem(
                        modifier = controller.feedback.toggleRow(),
                        title = if (controller.shownQuickLaunched) {
                            stringResource(R.string.game_remove_quick_launch)
                        } else {
                            stringResource(R.string.game_add_quick_launch)
                        },
                        leadingIcon = R.drawable.ic_home,
                        onClick = controller::toggleQuickLaunch,
                    )
                    QuickActionItem(
                        modifier = controller.feedback.shortcutRow(controller.shortcutRequestInFlight),
                        title = stringResource(R.string.game_add_desktop_shortcut),
                        leadingIcon = R.drawable.ic_sheet_desktop_shortcut,
                        onClick = controller::addDesktopShortcut,
                    )
                    QuickActionItem(
                        modifier = Modifier.staggeredEntrance(2),
                        title = stringResource(R.string.game_rename),
                        leadingIcon = R.drawable.ic_sheet_rename,
                        onClick = controller::openRename,
                    )
                    QuickActionDeleteButton(modifier = Modifier.staggeredEntrance(3), onClick = controller::openDelete)
                }
            }
        },
        confirmButton = {},
        // 删除游戏即最后一行条目，不再额外显示底部按钮；遮罩/返回键关闭
        showButtonRow = false,
        topOverhang = {
            QuickActionCover(
                game = game,
                modifier = controller.feedback.coverEntry(),
                // 点击封面与原先的「修改封面」条目一致：相册选图后走统一封面保存流程
                onClick = controller::pickCover,
            )
        },
        topOverhangHeight = QuickActionCoverOverhang,
    )

    controller.HostDialogs()
}

/**
 * 动作条目：沿用 [AppNavItem] 的图标 + 标题排版与动态底色，
 * 右侧统一显示加粗箭头（多数条目会进入二级弹窗/选择器）。
 */
@Composable
private fun QuickActionItem(
    title: String,
    @DrawableRes leadingIcon: Int,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    AppNavItem(
        modifier = modifier,
        title = title,
        leadingIcon = leadingIcon,
        leadingIconTint = MaterialTheme.colorScheme.primary,
        indication = null,
        onClick = onClick,
    )
}

/** 删除游戏：整行纯色长按钮，尺寸对齐游戏操作抽屉底部的主操作长按钮。 */
@Composable
private fun QuickActionDeleteButton(modifier: Modifier, onClick: () -> Unit) {
    // 实底危险按钮取固定深红 + 白字：深色 scheme 的 error/onError 是浅粉 + 深褐红，
    // 在复古玻璃的深色面板上会发白失焦，与其它外观风格割裂（见 theme/Color.kt）。
    Row(
        modifier = modifier
            .pressSquish()
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
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val coverBitmap by rememberCoverBitmap(game.coverUri)
    val editCoverLabel = stringResource(R.string.game_edit_cover)
    Box(
        modifier = modifier
            .pressSquish(scale = PressSquishDefaults.CardScale)
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
    // 键盘「完成」时内容无效（空 / 未改动）：输入框抖一下，告诉用户这次没有保存
    var invalidShake by remember { mutableIntStateOf(0) }
    // 「保存」位于确认槽、原本没有退场控制器：借取消槽捕获的控制器，保存时先沉下再回到长按弹窗
    val exitHandle = rememberGameDialogExitHandle()

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
                    onSearch = {
                        if (canConfirm) {
                            exitHandle.closeThen { onConfirm(normalizedTitle) }
                        } else {
                            invalidShake++
                        }
                    },
                    modifier = Modifier.shakeOnTrigger(invalidShake),
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
                // 变为可保存的一刻轻轻弹一下（颜色本身由公共按钮决定）
                modifier = Modifier
                    .gameDialogAction(4, confirmEnabled = canConfirm),
                enabled = canConfirm,
                onClick = { exitHandle.closeThen { onConfirm(normalizedTitle) } },
            )
        },
        dismissButton = {
            exitHandle.capture()
            DialogTextButton(
                onClick = onDismiss,
                text = stringResource(R.string.common_cancel),
                modifier = Modifier.gameDialogAction(3),
            )
        },
    )
}
