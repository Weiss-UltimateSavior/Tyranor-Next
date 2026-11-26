package com.tyranor.next.ui.game

import com.tyranor.next.ui.common.motion.CoverSurface
import com.tyranor.next.ui.common.motion.CoverReveal
import com.tyranor.next.ui.common.motion.listItemMotion
import com.tyranor.next.ui.common.motion.ListItemPlacement
import com.tyranor.next.ui.common.motion.rememberCoverReveal
import com.tyranor.next.ui.common.motion.rememberMotionSheetController
import com.tyranor.next.ui.common.motion.BlurCrossfade
import com.tyranor.next.ui.common.motion.BlurVisibility
import com.tyranor.next.ui.common.privacy.DismissOnAppLockEffect
import com.tyranor.next.ui.common.motion.ExpandVisibility
import com.tyranor.next.ui.common.motion.dissolve
import com.tyranor.next.ui.common.motion.dissolveTint
import com.tyranor.next.ui.common.motion.dissolvingGames
import com.tyranor.next.ui.common.motion.PressSquishDefaults
import com.tyranor.next.ui.common.motion.ProvideEntrance
import com.tyranor.next.ui.common.motion.skipEntranceOnDrag
import com.tyranor.next.ui.common.motion.pressSquish
import com.tyranor.next.ui.common.motion.staggeredEntrance
import com.tyranor.next.ui.common.NoRippleButton
import androidx.compose.foundation.lazy.staggeredgrid.itemsIndexed as staggeredGridItemsIndexed
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.Log
import android.util.LruCache
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import top.yukonga.miuix.kmp.basic.RadioButton
import com.tyranor.next.R
import com.tyranor.next.core.cover.CoverErrorCode
import com.tyranor.next.core.cover.CoverImageCache
import com.tyranor.next.core.cover.CoverScrapeTaskManager
import com.tyranor.next.core.cover.CoverSearchCandidate
import com.tyranor.next.core.cover.CoverSearchResult
import com.tyranor.next.core.cover.CoverScraperService
import com.tyranor.next.core.cover.decodeCoverSearchCandidate
import com.tyranor.next.core.cover.encodeCoverSearchCandidate
import com.tyranor.next.core.game.launch.EngineLauncher
import com.tyranor.next.core.game.manual.AndroidAppGames
import com.tyranor.next.core.game.storage.GameLibraryFacade
import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.engine.runtime.RuntimeModuleBindings
import com.tyranor.next.core.engine.runtime.RuntimeModuleRegistry
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.saveable.Saver
import com.tyranor.next.core.engine.external.EmulatorLaunchStyle
import com.tyranor.next.core.engine.external.ExternalEmulatorRegistry
import com.tyranor.next.core.engine.external.ExternalEngineLauncher
import com.tyranor.next.core.engine.external.ExternalEngineModuleRegistry
import com.tyranor.next.core.engine.external.ExternalEngineUpdate
import com.tyranor.next.core.engine.external.ExternalEngineUpdateChecker
import com.tyranor.next.core.game.save.RpgSaveFormat
import com.tyranor.next.core.game.save.sync.SaveSyncService
import com.tyranor.next.core.game.save.sync.SaveSyncRuntime
import com.tyranor.next.core.game.model.GameTitleTags
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.game.model.filterGames
import com.tyranor.next.core.game.model.GameSortKeys
import com.tyranor.next.core.game.model.sortGames
import com.tyranor.next.core.cover.stableKey
import com.tyranor.next.core.settings.AppSettingsStore
import com.tyranor.next.core.settings.GameCardColumnSettings
import com.tyranor.next.core.auth.BangumiAuthStore
import com.tyranor.next.core.auth.HikarinagiAuthStatus
import com.tyranor.next.core.auth.HikarinagiAuthStore
import com.tyranor.next.core.auth.NextMoeAuthStore
import com.tyranor.next.core.auth.VndbAuthError
import com.tyranor.next.core.auth.VndbAuthStore
import com.tyranor.next.core.i18n.AppLocaleController
import com.tyranor.next.theme.AppThemeColors
import com.tyranor.next.theme.DialogItemSurface
import com.tyranor.next.theme.GlassPanel
import com.tyranor.next.theme.LocalAppDialogContent
import com.tyranor.next.theme.MilkGlassPanelBackground
import com.tyranor.next.theme.MiuixSettingsTheme
import com.tyranor.next.theme.NavWhite
import com.tyranor.next.theme.NavWhiteOverlay
import com.tyranor.next.theme.PageSecondaryTextColor
import com.tyranor.next.theme.PageTextColor
import com.tyranor.next.theme.SecondaryTextColor
import com.tyranor.next.theme.TextColor
import com.tyranor.next.theme.glassBorder
import com.tyranor.next.theme.AppComponentShape
import com.tyranor.next.theme.gameCardShape
import com.tyranor.next.theme.CoverBadgeText
import com.tyranor.next.theme.CoverBadgeBackground
import com.tyranor.next.ui.common.AppAlertDialog
import com.tyranor.next.ui.common.AppBottomSheet
import com.tyranor.next.ui.common.isTabletScreen
import com.tyranor.next.ui.common.AppNavItem
import com.tyranor.next.ui.common.AppSearchField
import com.tyranor.next.ui.common.AppTopBar
import com.tyranor.next.ui.common.DialogTextButton
import com.tyranor.next.ui.common.NoIndication
import com.tyranor.next.ui.common.AppDialogSpec
import com.tyranor.next.ui.common.LocalAppDialogHost
import com.tyranor.next.ui.common.dialogListFixedHeight
import com.tyranor.next.ui.common.dialogListHeight
import com.tyranor.next.ui.common.LocalDialogExit
import com.tyranor.next.ui.common.SplitEndsActionRow
import com.tyranor.next.ui.common.TopBarIcon
import com.tyranor.next.ui.common.CoachAnchor
import com.tyranor.next.ui.common.coachAnchor
import com.tyranor.next.ui.common.glassNavBottomInset
import com.tyranor.next.ui.common.isWideScreen
import com.tyranor.next.ui.common.LaunchErrorDialog
import com.tyranor.next.ui.common.LaunchErrorState
import com.tyranor.next.ui.common.toErrorState
import com.tyranor.next.ui.cover.coverErrorMessage
import com.tyranor.next.ui.cover.coverSourceTitle
import com.tyranor.next.ui.main.MainLibraryUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import com.tyranor.next.ui.common.motion.rememberLastNonNull
import com.tyranor.next.ui.common.motion.bounceOnChange
import com.tyranor.next.ui.common.motion.breathing
import com.tyranor.next.ui.common.motion.shakeOnTrigger
import com.tyranor.next.ui.common.motion.shimmerSweep
import com.tyranor.next.ui.common.motion.scrollJelly
import com.tyranor.next.ui.common.motion.scrollJellyHost
import com.tyranor.next.ui.common.motion.rememberScrollJellyState
import com.tyranor.next.ui.common.motion.rememberEntranceState
import androidx.compose.foundation.lazy.itemsIndexed as lazyItemsIndexed
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.tyranor.next.ui.common.collectSettingAsState
import com.tyranor.next.ui.common.notice.NoticeHost
import com.tyranor.next.ui.common.notice.AppNotice
import com.tyranor.next.ui.common.motion.DelayedProgress

/**
 * Connects the latest game library to its content, launch sheet, and card-management dialog.
 * Card targets are resolved by URI so edits and removals are reflected by both entry points.
 */
@Composable
fun GameScreen(
    modifier: Modifier = Modifier,
    libraryState: MainLibraryUiState,
    onGameUpdated: (ScanGame) -> Unit,
    onGameCoverUpdated: (String?, ScanGame) -> Unit = { _, updated -> onGameUpdated(updated) },
    onGameRenamed: ((String, String) -> Unit)? = null,
    onGameLaunchFileUpdated: ((String, String?) -> Unit)? = null,
    onGameBindingUpdated: (String, ScanGame) -> Unit = { _, updated -> onGameUpdated(updated) },
    onGameDeleted: (ScanGame) -> Unit,
    onQuickLaunchToggle: (ScanGame) -> Boolean,
    onScanLibrary: () -> Unit,
    onScrapeEventShown: (Long) -> Unit,
    onSearchQueryChanged: (String) -> Unit,
    onAddManualGame: (ScanGame) -> Boolean,
    onCoachAnchorChanged: (Int, Rect) -> Unit = { _, _ -> },
    isActive: Boolean = true,
) {
    val context = LocalContext.current
    val batchScrapeRunningMessage = stringResource(R.string.game_batch_scraping_running)
    val scope = rememberCoroutineScope()
    val games = libraryState.games
    var selectedGameUri by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedGame = remember(games, selectedGameUri) {
        selectedGameUri?.let { uri -> games.firstOrNull { it.uri == uri } }
    }
    // 卡片长按的操作弹窗目标：只存 uri 与打开序号，每次从最新列表解析（改名/封面/启动文件更新后仍是新对象）
    val quickActions = rememberQuickActionsDialogHostState()
    val quickActionsGame = remember(games, quickActions.targetUri) {
        quickActions.targetUri?.let { uri -> games.firstOrNull { it.uri == uri } }
    }

    val scrapeTaskState by CoverScrapeTaskManager.state.collectAsStateWithLifecycle()
    // 「游戏盒」风格：点正中那一盒不弹抽屉，把它抽出来翻开（书架与书之间的交接）
    val cardStyle by AppSettingsStore.gameCardStyleState.collectSettingAsState()
    val shelfBook = remember { ShelfBookStage() }

    LaunchedEffect(libraryState.loaded, games, selectedGameUri, quickActions.targetUri) {
        if (!libraryState.loaded) return@LaunchedEffect
        selectedGameUri?.let { uri -> if (games.none { it.uri == uri }) selectedGameUri = null }
        quickActions.clearIfMissing { uri -> games.any { it.uri == uri } }
    }

    LaunchedEffect(libraryState.scrapeEventId, libraryState.scrapeMessage) {
        val message = libraryState.scrapeMessage ?: return@LaunchedEffect
        AppNotice.makeText(context, message, Toast.LENGTH_SHORT, AppNotice.Kind.Info).show()
        onScrapeEventShown(libraryState.scrapeEventId)
    }

    fun replaceGame(updated: ScanGame) {
        onGameUpdated(updated)
    }

    fun deleteGame(target: ScanGame) {
        if (selectedGameUri == target.uri) selectedGameUri = null
        quickActions.clearIfTarget(target.uri)
        PlayStatusSummaryCache.invalidate(target.uri)
        scope.removeLibraryGame(target.uri) { onGameDeleted(target) }
    }

    fun syncMissingCovers() {
        if (libraryState.scanning || scrapeTaskState.running) return
        if (!CoverScrapeTaskManager.start(context, games)) {
            AppNotice.makeText(context, batchScrapeRunningMessage, Toast.LENGTH_SHORT, AppNotice.Kind.Limit).show()
        }
    }

    // 扫描游戏库：每次按扫描目录全量重建，删除/改名/移动后的旧缓存条目会被清理。
    fun scanLibrary() {
        if (libraryState.scanning || scrapeTaskState.running) return
        onScanLibrary()
    }

    val dirPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { u ->
            scope.launch {
                withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.takePersistableUriPermission(
                            u,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                        )
                    }
                    // 保存根目录后立即全量扫描
                    GameLibraryFacade.saveRoot(context, u)
                }
                scanLibrary()
            }
        }
    }

    // 书签只挂在上次游玩的那一本上（封面、翻开的书、藏书阁）：按全部游戏算，不受筛选影响
    val lastPlayedUri = remember(games) { shelfLastPlayedUri(games) }
    CompositionLocalProvider(LocalShelfLastPlayedUri provides lastPlayedUri) {
        GameLibraryContent(
            modifier = modifier,
            games = games,
            loaded = libraryState.loaded,
            scanning = libraryState.scanning,
            scrapingCovers = scrapeTaskState.running,
            dirPickerLaunch = { dirPicker.launch(null) },
            syncMissingCovers = { syncMissingCovers() },
            refreshGames = { scanLibrary() },
            onGameClick = { selectedGameUri = it.uri },
            // 长按不再直接启动：打开统一操作弹窗（快捷启动 / 桌面快捷方式 / 改名 / 改封面 / 删除）
            onGameLongClick = { game -> quickActions.open(game.uri) },
            dbSearchQuery = libraryState.searchQuery,
            dbSearchHitUris = libraryState.searchHitUris,
            confirmedEngineTypes = libraryState.confirmedEngineTypes,
            onAddManualGame = onAddManualGame,
            onCoachAnchorChanged = onCoachAnchorChanged,
            onSearchQueryChanged = onSearchQueryChanged,
            isActive = isActive,
            shelfBook = shelfBook,
        )

        // ===== 点击游戏卡片：底部抽屉栏（启动相关动作与设置/存档/补丁入口）；「游戏盒」风格换成翻开的书 =====
        selectedGame?.let { game ->
            key(game.uri) {
                val onLaunchFileUpdated: (String, String?) -> Unit = { uri, name ->
                    val updater = onGameLaunchFileUpdated
                    if (updater != null) updater(uri, name) else replaceGame(game.copy(launchFile = name))
                }
                if (cardStyle == AppSettingsStore.GAME_CARD_STYLE_SHELF) {
                    ShelfOpenBook(
                        game = game,
                        stage = shelfBook,
                        quickLaunched = libraryState.quickLaunch.any { it.uri == game.uri },
                        onDismiss = { selectedGameUri = null },
                        onGameUpdated = { replaceGame(it) },
                        onGameCoverUpdated = onGameCoverUpdated,
                        onGameLaunchFileUpdated = onLaunchFileUpdated,
                        onGameBindingUpdated = onGameBindingUpdated,
                        onGameRenamed = onGameRenamed,
                        onDeleteGame = { deleteGame(game) },
                        onQuickLaunchToggle = { onQuickLaunchToggle(game) },
                    )
                } else {
                    GameActionsSheet(
                        game = game,
                        onDismiss = { selectedGameUri = null },
                        onGameUpdated = { replaceGame(it) },
                        onGameCoverUpdated = onGameCoverUpdated,
                        onGameLaunchFileUpdated = onLaunchFileUpdated,
                        onGameBindingUpdated = onGameBindingUpdated,
                        onEngineSettings = { openPerGameSettingsFromSheet(context, game) { selectedGameUri = null } },
                    )
                }
            }
        }

        // ===== 长按游戏卡片：统一操作弹窗（快捷启动 / 桌面快捷方式 / 改名 / 修改封面 / 删除） =====
        quickActionsGame?.let { game ->
            val openSeq = quickActions.openSequence
            key(openSeq) {
                GameQuickActionsDialog(
                    game = game,
                    quickLaunched = libraryState.quickLaunch.any { it.uri == game.uri },
                    onDismiss = { quickActions.dismissIfCurrent(openSeq) },
                    onGameUpdated = { replaceGame(it) },
                    onGameCoverUpdated = { updated -> onGameCoverUpdated(null, updated) },
                    onGameRenamed = onGameRenamed,
                    onDeleteGame = { deleteGame(game) },
                    onQuickLaunchToggle = { onQuickLaunchToggle(game) },
                )
            }
        }
    }
}

private const val TAG = "GameScreen"

/**
 * Renders the library's loading, empty, and populated states and its search and add-game controls.
 * A pending database search uses in-memory filtering until results match the current query.
 */
@Composable
private fun GameLibraryContent(
    modifier: Modifier,
    games: List<ScanGame>,
    loaded: Boolean,
    scanning: Boolean,
    scrapingCovers: Boolean,
    dirPickerLaunch: () -> Unit,
    syncMissingCovers: () -> Unit,
    refreshGames: () -> Unit,
    onGameClick: (ScanGame) -> Unit,
    onGameLongClick: (ScanGame) -> Unit,
    dbSearchQuery: String,
    dbSearchHitUris: Set<String>?,
    confirmedEngineTypes: Set<EngineType>?,
    onSearchQueryChanged: (String) -> Unit,
    onAddManualGame: (ScanGame) -> Boolean,
    onCoachAnchorChanged: (Int, Rect) -> Unit,
    isActive: Boolean,
    shelfBook: ShelfBookStage,
) {
    // 「添加游戏」分支流程：null=关闭；CHOICE=分支弹窗，PC/ANDROID=对应二级添加弹窗
    var addGameStep by rememberSaveable { mutableStateOf<AddGameStep?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    // 动效：扫描 / 刮削在忙时再点被拒绝 → 图标抖一下（只改绘制图层）
    var scanRejects by remember { mutableIntStateOf(0) }
    var coverRejects by remember { mutableIntStateOf(0) }
    var showFilterSheet by rememberSaveable { mutableStateOf(false) }
    val shelfCatalog = remember { ShelfCatalogStage() }
    val context = LocalContext.current
    // 藏书阁开合会话：退场期间再长按书架时登记重开、旧覆盖层收尾后立刻换新实例（见 ShelfCatalogSession）
    val catalogSession = rememberShelfCatalogSessionState()
    var catalogAnchorUri by rememberSaveable { mutableStateOf<String?>(null) }
    var catalogLayoutMode by rememberSaveable { mutableStateOf(ShelfCatalogLayoutMode.SPINES) }
    // 进入藏书阁前书架上显示的那一本：只有它带「选定」效果（之后书架因筛选换了页也不改）
    var catalogSelectedUri by rememberSaveable { mutableStateOf<String?>(null) }
    val focusManager = LocalFocusManager.current
    val conditionSelection = AppSettingsStore.gameConditions.collectSettingAsState().value
    val conditions = conditionSelection.conditions
    val shelfCurrentUri = remember(conditionSelection) { mutableStateOf(AppSettingsStore.gameShelfFocusUri(context)) }
    // 条件变化时重新创建滚动状态，避免 Lazy 布局按旧首卡键锚定到排序后的其它位置。
    val gridState = key(conditions) { rememberLazyStaggeredGridState() }
    val uniformGridState = key(conditions) { rememberLazyGridState() }
    val sortedGames = remember(games, conditions.sortMode) { sortGames(games, conditions.sortMode) }
    LaunchedEffect(query) { onSearchQueryChanged(query) }
    val hits = dbSearchHitUris.takeIf { dbSearchQuery == query }
    val searchedGames = remember(sortedGames, hits, query) {
        val term = query.trim()
        val titleKey = GameSortKeys.titleKey(term)
        when {
            term.isEmpty() -> sortedGames
            hits != null -> sortedGames.filter { it.uri in hits }
            // 等待 Room 时使用相同的标题键，避免两条路径匹配结果不同。
            else -> sortedGames.filter { it.title.contains(term) || GameSortKeys.titleKey(it.title).contains(titleKey) }
        }
    }
    val filteredGames = remember(searchedGames, conditions) {
        filterGames(searchedGames, conditions.engineFilter, conditions.playedFilter)
    }
    val pendingShelfUri = shelfCatalog.pendingFocusUri
    val pendingShelfToken = shelfCatalog.focusRequestToken
    LaunchedEffect(loaded, filteredGames, pendingShelfUri, pendingShelfToken) {
        if (loaded && pendingShelfUri != null && filteredGames.none { it.uri == pendingShelfUri }) {
            shelfCatalog.clearPendingFocus(pendingShelfToken)
        }
    }
    val availableEngines = remember(games) { games.map { it.engine }.distinct().sortedBy { it.ordinal } }
    val selectedEngine = conditions.engineFilter
    LaunchedEffect(loaded, scanning, confirmedEngineTypes, availableEngines, selectedEngine) {
        if (loaded && !scanning &&
            conditions.isEngineFilterMissing(availableEngines, confirmedEngineTypes) &&
            AppSettingsStore.gameConditions.value.conditions.engineFilter == selectedEngine
        ) {
            AppSettingsStore.setGameFilterEngine(context, AppSettingsStore.GAME_FILTER_ENGINE_ALL)
        }
    }

    val scrapingCoversDescription = stringResource(R.string.game_scraping_covers_content_description)
    val cardStyle by AppSettingsStore.gameCardStyleState.collectSettingAsState()
    val wideScreen = isWideScreen()
    val columnSettings by AppSettingsStore.gameCardColumns.collectSettingAsState()
    val columns = columnSettings.forScreen(wideScreen)
    LaunchedEffect(cardStyle, loaded, games.isEmpty()) {
        if (cardStyle != AppSettingsStore.GAME_CARD_STYLE_SHELF || (loaded && games.isEmpty())) {
            catalogSession.closeNow()
            shelfCatalog.reopenRequested = false
            shelfCatalog.release()
            shelfCatalog.clearPendingFocus()
        }
    }
    // 搜索栏仅在列表顶部显示：离开顶部立即收起，向上滚动过程中不提前展开，滚回顶部才重新出现。
    // 封面流与书架无纵向滚动，始终显示。
    var searchBarVisible by remember { mutableStateOf(true) }
    val searchAndFilterVisible = searchBarVisible || (loaded && !scanning && filteredGames.isEmpty())
    LaunchedEffect(cardStyle, gridState, uniformGridState) {
        searchBarVisible = true
        if (cardStyle == AppSettingsStore.GAME_CARD_STYLE_COVER_FLOW ||
            cardStyle == AppSettingsStore.GAME_CARD_STYLE_SHELF
        ) return@LaunchedEffect
        val positions = when (cardStyle) {
            AppSettingsStore.GAME_CARD_STYLE_GRID -> snapshotFlow {
                uniformGridState.firstVisibleItemIndex to uniformGridState.firstVisibleItemScrollOffset
            }
            else -> snapshotFlow {
                gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset
            }
        }
        positions.collect { (index, offset) ->
            searchBarVisible = index == 0 && offset == 0
        }
    }

    Column(modifier.fillMaxSize()) {
        // ===== 顶部栏：统一 AppTopBar（标题居左 + 可折叠搜索行） =====
        AppTopBar(
            title = stringResource(R.string.game_title),
            underTitle = {
                // 搜索栏仅在列表顶部显示；离开顶部收起，滚回顶部才展开
                ExpandVisibility(searchAndFilterVisible, ordinaryExpandFade = true) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            // 筛选按钮固定在搜索栏右侧，并与顶栏刷新按钮右对齐。
                            .padding(start = 4.dp, end = 16.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .weight(1f)
                                .coachAnchor(isActive) {
                                    onCoachAnchorChanged(CoachAnchor.GAME_SEARCH, it)
                                },
                        ) {
                            AppSearchField(
                                query = query,
                                onQueryChange = { query = it },
                                iconContentDescription = stringResource(R.string.game_search_content_description),
                            )
                        }
                        GameFilterActionIcon(conditions.isActive) {
                            focusManager.clearFocus()
                            showFilterSheet = true
                        }
                    }
                }
            },
            trailing = {
                // 顶栏图标的页面入场由 TopBarIcon 自带（标题之后依次升起）；这里只保留引导锚点外壳
                Box(
                    Modifier.coachAnchor(isActive) {
                        onCoachAnchorChanged(CoachAnchor.GAME_ADD, it)
                    },
                ) {
                    TopBarIcon(
                        painterResource(R.drawable.ic_game_add),
                        stringResource(R.string.game_add_content_description),
                        MaterialTheme.colorScheme.primary,
                    ) { addGameStep = AddGameStep.CHOICE }
                }
                // 批量刮削进度圈 <-> 刮削图标：模糊替换；引导锚点由稳定外壳报告。
                Box(
                    Modifier.coachAnchor(isActive) {
                        onCoachAnchorChanged(CoachAnchor.GAME_COVER, it)
                    },
                ) {
                    // 扫描中再点刮削被拒绝：图标水平抖一下
                    Box(Modifier.shakeOnTrigger(coverRejects, amplitude = 4.dp)) {
                    BlurCrossfade(scrapingCovers, contentAlignment = Alignment.Center) { scraping ->
                        if (scraping) {
                            Box(
                                modifier = Modifier.padding(start = 2.dp).size(34.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier
                                        .size(22.dp)
                                        .semantics { contentDescription = scrapingCoversDescription },
                                    color = MaterialTheme.colorScheme.primary,
                                    strokeWidth = 2.dp,
                                )
                            }
                        } else {
                            TopBarIcon(painterResource(R.drawable.ic_game_cover), stringResource(R.string.game_scrape_covers_content_description), MaterialTheme.colorScheme.primary) {
                                if (scanning) coverRejects++
                                syncMissingCovers()
                            }
                        }
                    }
                    }
                }
                Box(
                    Modifier.coachAnchor(isActive) {
                        onCoachAnchorChanged(CoachAnchor.GAME_SCAN, it)
                    },
                ) {
                    // 扫描进行中：扫描图标缓缓呼吸；忙时再点被拒绝 → 抖一下
                    Box(
                        Modifier
                            .shakeOnTrigger(scanRejects, amplitude = 4.dp)
                            .breathing(scanning),
                    ) {
                    TopBarIcon(painterResource(R.drawable.ic_game_scan), stringResource(R.string.game_scan_content_description), MaterialTheme.colorScheme.primary) {
                        if (scanning || scrapingCovers) scanRejects++
                        refreshGames()
                    }
                    }
                }
            },
        )

        // ===== 内容区 =====
        Box(Modifier.fillMaxSize()) {
            // 加载中 / 空库 / 无匹配 / 列表 四种状态之间模糊替换
            val pane = when {
                scanning || !loaded -> LibraryPane.Loading
                games.isEmpty() -> LibraryPane.Empty
                filteredGames.isEmpty() -> LibraryPane.NoMatch
                else -> LibraryPane.Content
            }
            // 退场列表仍参与绘制，但扫描/空态已接管页面时不得再打开卡片或启动游戏。
            val contentInteractive by rememberUpdatedState(pane == LibraryPane.Content)
            BlurCrossfade(pane, modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { current ->
                when (current) {
                    LibraryPane.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        DelayedProgress { CircularProgressIndicator() }
                    }
                    LibraryPane.Empty -> EmptyGameLibrary(
                        onAddFolder = { dirPickerLaunch() },
                        // 等空态的缩放入场结束后再测量，避免缓存动画中的控件位置。
                        isActive = isActive && pane == LibraryPane.Empty && !transition.isRunning,
                        onCoachAnchorChanged = { onCoachAnchorChanged(CoachAnchor.GAME_FOLDER, it) },
                    )
                    LibraryPane.NoMatch -> Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            stringResource(R.string.game_no_match),
                            style = MaterialTheme.typography.bodyMedium,
                            color = PageSecondaryTextColor,
                        )
                        if (query.isNotBlank() || conditions.isActive) {
                            NoRippleButton(
                                text = stringResource(R.string.game_filter_reset),
                                onClick = {
                                    query = ""
                                    AppSettingsStore.resetGameConditions(context)
                                },
                                modifier = Modifier.padding(top = 12.dp),
                            )
                        }
                    }
                    LibraryPane.Content -> {
                        val corners by AppSettingsStore.gameCardCorners.collectSettingAsState()
                        val cardShape = gameCardShape(corners.radiusDp.takeIf { corners.enabled })
                        // 卡片风格切换（瀑布 / 网格 / 封面流 / 书架）：整体模糊替换（大面积内容自动不做模糊，只淡入淡出与缩放）
                        BlurCrossfade(cardStyle, modifier = Modifier.fillMaxSize()) { style ->
                        when (style) {
                            // 「列表」风格：封面流横向轮播
                            AppSettingsStore.GAME_CARD_STYLE_COVER_FLOW -> key(conditions) {
                                GameCoverFlow(
                                    games = filteredGames,
                                    cardShape = cardShape,
                                    onGameClick = { if (contentInteractive) onGameClick(it) },
                                    onGameLongClick = { if (contentInteractive) onGameLongClick(it) },
                                )
                            }
                            // 「书架」风格：游戏盒立在搁板上，居中正面、两侧露书脊；长按进入藏书阁全景检索筛选
                            AppSettingsStore.GAME_CARD_STYLE_SHELF -> key(conditionSelection) {
                                GameShelf(
                                    games = filteredGames,
                                    isInteractive = { contentInteractive && cardStyle == AppSettingsStore.GAME_CARD_STYLE_SHELF },
                                    onGameClick = { if (contentInteractive) onGameClick(it) },
                                    onGameLongClick = { target ->
                                        if (contentInteractive && games.isNotEmpty()) {
                                            focusManager.clearFocus()
                                            // 旧藏书阁还在退场收尾时返回 false：已登记，由旧覆盖层交付收尾（onDismiss）后接着重开。
                                            // 收尾可能正挂着等这根手指抬起（见 dismissWhenShelfReleased）：长按已经成立，不用再等，立刻交付
                                            if (catalogSession.requestOpen(target.uri)) {
                                                catalogAnchorUri = target.uri
                                                catalogSelectedUri = shelfCurrentUri.value ?: target.uri
                                            } else {
                                                shelfCatalog.reopenRequested = true
                                                shelfCatalog.flushDeferredDismiss()
                                            }
                                        }
                                    },
                                    book = shelfBook,
                                    catalog = shelfCatalog,
                                    initialUri = { shelfCurrentUri.value },
                                    onCurrentGameChanged = { shelfCurrentUri.value = it.uri },
                                    onSettledGameChanged = { uri ->
                                        AppSettingsStore.setGameShelfFocusUri(context, uri, conditionSelection)
                                    },
                                )
                            }
                            // 「网格」风格：恢复瀑布流之前的等高网格
                            AppSettingsStore.GAME_CARD_STYLE_GRID -> UniformGameGrid(
                                games = filteredGames,
                                cardShape = cardShape,
                                columns = columns,
                                gridState = uniformGridState,
                                onGameClick = { if (contentInteractive) onGameClick(it) },
                                onGameLongClick = { if (contentInteractive) onGameLongClick(it) },
                            )
                            // 「瀑布」风格（默认）
                            else -> GameGrid(
                                games = filteredGames,
                                cardShape = cardShape,
                                columns = columns,
                                gridState = gridState,
                                onGameClick = { if (contentInteractive) onGameClick(it) },
                                onGameLongClick = { if (contentInteractive) onGameLongClick(it) },
                            )
                        }
                        }
                    }
                }
            }
        }
    }

    when (addGameStep) {
        AddGameStep.CHOICE -> AddGameDialog(
            onDismiss = { addGameStep = null },
            onAddPc = { addGameStep = AddGameStep.PC },
            onAddAndroid = { addGameStep = AddGameStep.ANDROID },
        )

        AddGameStep.PC -> PcGameAddDialog(
            onDismiss = { addGameStep = null },
            onAdd = onAddManualGame,
        )

        AddGameStep.ANDROID -> AndroidGameAddDialog(
            existingUris = remember(games) { games.mapTo(HashSet()) { it.uri } },
            onDismiss = { addGameStep = null },
            onAdd = onAddManualGame,
        )

        null -> Unit
    }

    if (showFilterSheet) {
        GameFilterSheet(
            sortMode = conditions.sortMode,
            engineFilter = selectedEngine,
            playedFilter = conditions.playedFilter,
            availableEngines = availableEngines,
            searchQuery = query,
            cardStyle = cardStyle,
            cardColumns = columns,
            columnRange = GameCardColumnSettings.range(wideScreen),
            onSortChange = { AppSettingsStore.setGameSort(context, it) },
            onEngineChange = { AppSettingsStore.setGameFilterEngine(context, it) },
            onPlayedChange = { AppSettingsStore.setGameFilterPlayed(context, it) },
            onColumnsChange = { AppSettingsStore.setGameCardColumns(context, wideScreen, it) },
            onReset = { AppSettingsStore.resetGameConditions(context) },
            onDismiss = { showFilterSheet = false },
        )
    }

    if (catalogSession.isOpen && games.isNotEmpty() && cardStyle == AppSettingsStore.GAME_CARD_STYLE_SHELF) {
        // 打开序号当 key：退场期间再长按登记的重开，在旧覆盖层交付收尾后换成全新的实例（见 ShelfCatalogSession）
        val openSequence = catalogSession.openSequence
        key(openSequence) {
            val anchorGame = remember(games, filteredGames, catalogAnchorUri) {
                catalogAnchorUri?.let { uri ->
                    filteredGames.firstOrNull { it.uri == uri } ?: games.firstOrNull { it.uri == uri }
                }
            }
            val focusUri = shelfCurrentUri.value
            val currentFocusGame = remember(filteredGames, focusUri, anchorGame) {
                focusUri?.let { uri -> filteredGames.firstOrNull { it.uri == uri } } ?: anchorGame
            }
            ShelfCatalogOverlay(
                allGames = games,
                filteredGames = filteredGames,
                anchorGame = anchorGame,
                currentFocusGame = currentFocusGame,
                selectedGame = remember(filteredGames, catalogSelectedUri) {
                    catalogSelectedUri?.let { uri -> filteredGames.firstOrNull { it.uri == uri } }
                },
                layoutMode = catalogLayoutMode,
                onLayoutModeChange = { catalogLayoutMode = it },
                stage = shelfCatalog,
                query = query,
                conditions = conditions,
                onQueryChange = { query = it },
                onOpenFilter = { showFilterSheet = true },
                onResetAll = {
                    query = ""
                    AppSettingsStore.resetGameConditions(context)
                },
                onPickGame = { picked ->
                    when {
                        filteredGames.none { it.uri == picked.uri } -> 0
                        scanning || !loaded -> {
                            shelfCurrentUri.value = picked.uri
                            AppSettingsStore.setGameShelfFocusUri(context, picked.uri, conditionSelection)
                            0
                        }
                        else -> shelfCatalog.requestFocus(picked.uri)
                    }
                },
                onDismiss = {
                    // 退场期间用户又长按了书架：收尾后立刻接着开新一轮，锚点与「选定」按此刻书架上的那一本重新取
                    catalogSession.dismiss(openSequence)?.let { reopenAnchorUri ->
                        catalogAnchorUri = reopenAnchorUri
                        catalogSelectedUri = shelfCurrentUri.value ?: reopenAnchorUri
                    }
                    shelfCatalog.reopenRequested = false
                },
            )
        }
    }
}

@Composable
internal fun GameFilterActionIcon(
    isActive: Boolean,
    onClick: () -> Unit,
) {
    Box(contentAlignment = Alignment.TopEnd) {
        TopBarIcon(
            painterResource(R.drawable.ic_game_filter),
            stringResource(R.string.game_filter_content_description),
            MaterialTheme.colorScheme.primary,
            stateDescription = if (isActive) stringResource(R.string.game_filter_state_active) else null,
            onClick = onClick,
        )
        if (isActive) {
            Box(
                Modifier
                    .padding(top = 1.dp, end = 1.dp)
                    .size(6.dp)
                    .background(MaterialTheme.colorScheme.primary, AppComponentShape),
            )
        }
    }
}

@Composable
private fun EmptyGameLibrary(
    onAddFolder: () -> Unit,
    isActive: Boolean,
    onCoachAnchorChanged: (Rect) -> Unit,
) {
    DisposableEffect(isActive) {
        onDispose { onCoachAnchorChanged(Rect.Zero) }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // 空态每次出现都自带一轮入场：标题 → 说明 → 主按钮依次升起（主按钮行程更长，作为落点）
        ProvideEntrance {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    stringResource(R.string.game_empty_title),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.staggeredEntrance(0, distance = 12.dp, stepMillis = 55L),
                )
                Text(
                    stringResource(R.string.game_empty_message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = PageSecondaryTextColor,
                    modifier = Modifier.staggeredEntrance(1, distance = 12.dp, stepMillis = 55L).padding(top = 8.dp),
                )
                NoRippleButton(
                    text = stringResource(R.string.game_add_folder),
                    onClick = onAddFolder,
                    modifier = Modifier
                        .padding(top = 16.dp)
                        .coachAnchor(isActive, onCoachAnchorChanged)
                        .staggeredEntrance(2, distance = 20.dp, stepMillis = 55L),
                )
            }
        }
    }
}

/** 「添加游戏」流程弹窗步骤（游戏页顶栏入口 → 分支 → 二级添加弹窗）。 */
private enum class AddGameStep { CHOICE, PC, ANDROID }

/** 游戏页内容区的状态类别（仅用于状态切换动画）。 */
private enum class LibraryPane { Loading, Empty, NoMatch, Content }

/**
 * 游戏操作条目的交互种类：决定在抽屉与「翻开的书」两种宿主中的呈现形态。
 * - [Dialog]：原本即以弹窗/书页呈现（例如启动文件、搜索封面、游玩状态）。
 * - [Detail]：二级详情界面，经 [GameActionsHost.openDetail] 路由——在抽屉内跳转独立 Activity 并显示箭头，
 *   在「翻开的书」内自动翻开书页（[ShelfBookLeaf]）且隐藏箭头，无需条目各自判断当前宿主。
 * - [Inline]：就地动作或外部链接（例如引擎模块下载、运行时刷新）。
 */
internal enum class GameActionKind {
    Dialog,
    Detail,
    Inline,
}

/**
 * 游戏操作条目描述（操作抽屉与书架翻开的书共用）；[key] 供列表 item key 使用。
 * 箭头与书内翻页行为由 [kind] 与宿主是否「页内呈现详情」统一派生：书内 [Detail] 条目自动隐藏箭头并翻开书页。
 * [abandonsLaunch]：点这一条是否放弃还在等待的 SystemNNN 启动结果（启动文件选择不放弃）。
 */
internal data class GameActionItem(
    val key: String,
    val title: String,
    val summary: String? = null,
    val icon: Int,
    val kind: GameActionKind = GameActionKind.Inline,
    val iconTint: Color? = null,
    /** 行尾内联文本：与标题同行右对齐单行显示（不新增条目高度）。 */
    val trailingText: String? = null,
    val abandonsLaunch: Boolean = true,
    val onClick: () -> Unit,
) {
    fun showArrow(detailsInLeaf: Boolean): Boolean = kind == GameActionKind.Detail && !detailsInLeaf

    fun opensBookLeaf(detailsInLeaf: Boolean): Boolean =
        kind == GameActionKind.Dialog || (kind == GameActionKind.Detail && detailsInLeaf)
}

/**
 * 一位游戏的全部操作，交给宿主（操作抽屉 / 书架翻开的书）去摆：[launchFile] 是启动文件条目（只有 KRKR 与
 * Winlator 系引擎有），[items] 是其余条目，[presentsDetailsInLeaf] 指示宿主是否将详情页留在书内，
 * [launch] 发起启动，[perform] 执行某一条（宿主正在离开时被拒绝）。
 */
internal class GameActionsUi(
    val launchFile: GameActionItem?,
    val items: List<GameActionItem>,
    val presentsDetailsInLeaf: Boolean,
    val launch: () -> Unit,
    val perform: (GameActionItem) -> Unit,
)

/**
 * 承载游戏操作的界面（操作抽屉、书架翻开的书）。业务逻辑只经这几个口子与宿主打交道：
 * 点击前问 [interact]（离开途中拒绝）；[leaving] 时迟到的启动结果不再导航；二级详情统一经 [openDetail] 路由，
 * 书内宿主结构上不开放全局 Activity 跳转入口，确保未来新增详情默认适配书页。
 */
internal interface GameActionsHost {
    fun interact(): Boolean
    val leaving: Boolean
    val presentsDetailsInLeaf: Boolean
    fun openDetail(destination: GameDetailDestination)
    fun onGameLaunched()
    fun close()
}

@Composable
internal fun rememberSystemNnnSheetRequest(game: ScanGame): SystemNnnSheetRequest {
    val request = rememberSaveable(game.uri, saver = SystemNnnSheetRequestSaver) { SystemNnnSheetRequest() }
    DisposableEffect(request) { onDispose { request.leave() } }
    return request
}

/** One presentation of the drawer can consume its own launch result, including after rotation. */
internal class SystemNnnSheetRequest(var awaitingResult: Boolean = false) {
    private var attached = true

    fun launch(busy: Boolean, pendingResult: Boolean, dispatch: () -> Unit) {
        if (!attached || busy || (awaitingResult && pendingResult)) return
        awaitingResult = true
        dispatch()
    }

    fun takeResult(): Boolean = (attached && awaitingResult).also { awaitingResult = false }

    fun leave() {
        attached = false
        awaitingResult = false
    }
}

private val SystemNnnSheetRequestSaver = Saver<SystemNnnSheetRequest, Boolean>(
    save = { it.awaitingResult }, restore = { SystemNnnSheetRequest(it) },
)

/**
 * Presents launch and settings actions for one game. Enhanced mode keeps the sheet through page
 * navigation or animates dismissal; ordinary mode retains immediate dismissal. Closing or navigating
 * sheets reject further business actions before dispatching callbacks.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GameActionsSheet(
    game: ScanGame,
    onDismiss: () -> Unit,
    onGameUpdated: (ScanGame) -> Unit,
    onGameCoverUpdated: (String?, ScanGame) -> Unit = { _, updated -> onGameUpdated(updated) },
    onGameLaunchFileUpdated: ((String, String?) -> Unit)? = null,
    onGameBindingUpdated: (String, ScanGame) -> Unit = { _, updated -> onGameUpdated(updated) },
    onEngineSettings: () -> Unit,
) {
    // 「增强」动效档由控制器接管抽屉的展开 / 收起 / 跟手下拉（见 ui/common/motion/SheetMotion.kt）
    val systemNnnRequest = rememberSystemNnnSheetRequest(game)
    val sheetMotion = rememberMotionSheetController()
    fun dismissSheet() {
        systemNnnRequest.leave()
        onDismiss()
    }
    val context = LocalContext.current
    val navigation = GameSheetNavigation(sheetMotion, LocalLifecycleOwner.current, context, game, ::dismissSheet, onEngineSettings)
    // 抽屉条目遵循页面灰底取色；白雾玻璃面板内改用与页面条目同亮度的面板条目色。
    val drawerItemSurface = when {
        AppThemeColors.isMilkGlass -> DialogItemSurface
        else -> NavWhite
    }
    // 档案卡里的封面与游戏列表用同一个圆角（游戏卡片单独圆角；关闭时跟随应用圆角）
    val cardCorners by AppSettingsStore.gameCardCorners.collectSettingAsState()
    val cardShape = gameCardShape(cardCorners.radiusDp.takeIf { cardCorners.enabled })
    GameActions(
        game = game,
        host = navigation,
        systemNnnRequest = systemNnnRequest,
        onGameUpdated = onGameUpdated,
        onGameCoverUpdated = onGameCoverUpdated,
        onGameLaunchFileUpdated = onGameLaunchFileUpdated,
        onGameBindingUpdated = onGameBindingUpdated,
    ) { ui ->
        AppBottomSheet(
            onDismissRequest = ::dismissSheet,
            controller = sheetMotion,
        ) {
            GameSheetContent(
                modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                onLaunch = ui.launch,
            ) {
                // 抽屉头部「档案卡」：游戏封面 + 引擎名牌、游戏名与三格数据（所有卡片风格与首页共用）
                item {
                    GameSheetHeader(game, cardShape, Modifier.gameSheetEntrance(0).padding(top = 4.dp))
                }
                ui.launchFile?.let { row ->
                    item {
                        AppNavItem(
                            title = row.title,
                            summary = row.summary,
                            // 启动文件选定后（摘要换成新文件名）整行轻弹一下作为落点反馈
                            modifier = Modifier
                                .gameSheetEntrance(2)
                                .bounceOnChange(game.launchFile ?: "", depth = 0.05f),
                            leadingIcon = row.icon,
                            containerColor = drawerItemSurface,
                            verticalPadding = 17.dp,
                            showArrow = false,
                            leadingIconTint = row.iconTint,
                            indication = null,
                            onClick = { ui.perform(row) },
                        )
                    }
                }
                ui.items.forEachIndexed { index, action ->
                    item(key = "drawer_row:" + action.key) {
                        AppNavItem(
                            title = action.title,
                            summary = action.summary,
                            modifier = Modifier.gameSheetEntrance(3 + index),
                            trailingText = action.trailingText,
                            leadingIcon = action.icon,
                            containerColor = drawerItemSurface,
                            verticalPadding = 17.dp,
                            showArrow = action.showArrow(ui.presentsDetailsInLeaf),
                            leadingIconTint = action.iconTint,
                            indication = null,
                            onClick = { ui.perform(action) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * 一位游戏的操作逻辑与它弹出的全部弹窗：启动（Artemis 补丁确认 → MV/MZ 存档格式确认 → 拉起，SystemNNN 走自己的
 * 交接）、启动文件、搜索封面、游玩状态、存档 / 补丁 / 引擎设置入口。[content] 只负责把 [GameActionsUi] 摆出来；
 * 抽屉与书架翻开的书共用这一份逻辑，不复制行为。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GameActions(
    game: ScanGame,
    host: GameActionsHost,
    systemNnnRequest: SystemNnnSheetRequest,
    onGameUpdated: (ScanGame) -> Unit,
    onGameCoverUpdated: (String?, ScanGame) -> Unit,
    onGameLaunchFileUpdated: ((String, String?) -> Unit)?,
    onGameBindingUpdated: (String, ScanGame) -> Unit,
    content: @Composable (GameActionsUi) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val runtimeModules by RuntimeModuleRegistry.state.collectAsStateWithLifecycle()
    val runtimeModule = remember(runtimeModules.modules, game.runtimeModulePackage) {
        runtimeModules.modules.firstOrNull { it.packageName == game.runtimeModulePackage }
    }
    var runtimeRefreshRunning by remember(game.uri) { mutableStateOf(false) }
    var runtimeRefreshFailed by remember(game.uri) { mutableStateOf(false) }
    LaunchedEffect(game.uri, game.runtimeModulePackage) {
        if (game.engine == EngineType.RUNTIME_MODULE && !game.runtimeModulePackage.isNullOrBlank()) {
            runtimeRefreshRunning = true
            try {
                runtimeRefreshFailed = withContext(Dispatchers.IO) {
                    RuntimeModuleRegistry.refresh(context) !is com.tyranor.next.core.engine.runtime.RuntimeModuleDiscoveryResult.Success
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                runtimeRefreshFailed = true
            } finally {
                runtimeRefreshRunning = false
            }
        }
    }
    var hasPendingExternalExit by remember(game.uri) { mutableStateOf(false) }
    val sheetLifecycleOwner = LocalLifecycleOwner.current
    val externalModule = ExternalEngineModuleRegistry.moduleForEngine(game.engine)
    var moduleUpdate by remember(game.uri, externalModule) {
        mutableStateOf<ExternalEngineUpdate?>(null)
    }
    LaunchedEffect(game.uri, externalModule, sheetLifecycleOwner) {
        if (externalModule?.updateSource != null) {
            sheetLifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                moduleUpdate = ExternalEngineUpdateChecker.check(context, externalModule)
            }
        }
    }
    LaunchedEffect(game.uri, sheetLifecycleOwner) {
        sheetLifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            try {
                hasPendingExternalExit = withContext(Dispatchers.IO) {
                    SaveSyncRuntime.pendingExternalExitConfirmations(context).any { it.uri == game.uri }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                hasPendingExternalExit = false
            }
        }
    }
    val systemNnnLaunch: SystemNnnLaunchViewModel = viewModel()
    val navigation = host
    var launchError by remember(game.uri) { mutableStateOf<LaunchErrorState?>(null) }
    var showCoverSourcePicker by rememberSaveable(game.uri) { mutableStateOf(false) }
    var coverSearchSource by rememberSaveable(game.uri) { mutableStateOf<String?>(null) }
    var coverSearchSessionId by remember(game.uri) { mutableLongStateOf(0L) }
    var coverBinding by remember { mutableStateOf(false) }
    var coverBindError by rememberSaveable(game.uri) { mutableStateOf<String?>(null) }
    var showLaunchFilePicker by rememberSaveable(game.uri) { mutableStateOf(false) }
    var showPatchConfirm by rememberSaveable(game.uri) { mutableStateOf(false) }
    var showPlayStatusDialog by rememberSaveable(game.uri) { mutableStateOf(false) }
    // 游玩状态抽屉副文本：逐源授权门——已绑定但未授权的来源不发起回读、不产出文案行；
    // TTL 缓存 5 分钟只存全部来源回读成功的汇总（失败文案即时展示、不入缓存），写后失效
    val bangumiSessionEpoch by BangumiAuthStore.sessionEpoch.collectAsStateWithLifecycle()
    val vndbSessionEpoch by VndbAuthStore.sessionEpoch.collectAsStateWithLifecycle()
    val hikarinagiSessionEpoch by HikarinagiAuthStore.sessionEpoch.collectAsStateWithLifecycle()
    val nextmoeSessionEpoch by NextMoeAuthStore.sessionEpoch.collectAsStateWithLifecycle()
    val bangumiStatusVersion by BangumiAuthStore.statusVersion.collectAsStateWithLifecycle()
    val vndbStatusVersion by VndbAuthStore.statusVersion.collectAsStateWithLifecycle()
    val hikarinagiStatusVersion by HikarinagiAuthStore.statusVersion.collectAsStateWithLifecycle()
    val nextmoeStatusVersion by NextMoeAuthStore.statusVersion.collectAsStateWithLifecycle()
    var playStatusSummary by remember(game.uri) { mutableStateOf<String?>(null) }
    var playStatusRefresh by remember(game.uri) { mutableIntStateOf(0) }
    var activeFetchSeq by remember(game.uri) { mutableIntStateOf(0) }
    var fetchEpoch by remember(game.uri) { mutableIntStateOf(0) }
    var statusVersionChangedWhileFetching by remember(game.uri) { mutableStateOf(false) }
    var lastPlayStatusGates by remember(game.uri) { mutableStateOf<List<Boolean>?>(null) }
    LaunchedEffect(
        game.uri,
        game.bangumiId,
        game.vndbId,
        game.hikarinagiId,
        game.nextmoeId,
        playStatusRefresh,
        bangumiSessionEpoch,
        vndbSessionEpoch,
        hikarinagiSessionEpoch,
        nextmoeSessionEpoch,
    ) {
        val mySeq = ++fetchEpoch
        activeFetchSeq = 0
        statusVersionChangedWhileFetching = false
        if (game.bangumiId.isNullOrBlank() && game.vndbId.isNullOrBlank() &&
            game.hikarinagiId.isNullOrBlank() && game.nextmoeId.isNullOrBlank()
        ) {
            playStatusSummary = null
            lastPlayStatusGates = emptyList()
            PlayStatusSummaryCache.invalidate(game.uri)
            return@LaunchedEffect
        }
        // 逐源授权门下沉至 Dispatchers.IO：避免在主线程调度器上执行 Keystore 解密读取
        val localeTag = AppLocaleController.wrap(context).resources.configuration.locales[0].toLanguageTag()
        val cachedOrMiss = withContext(Dispatchers.IO) {
            val gates = resolvePlayStatusGates(context, game)
            val bangumiReady = gates[0]
            val vndbReady = gates[1]
            val hikarinagiReady = gates[2]
            val nextmoeReady = gates[3]
            if (!bangumiReady && !vndbReady && !hikarinagiReady && !nextmoeReady) {
                return@withContext Triple(null, null, gates)
            }
            val cacheKey = PlayStatusSummaryCache.key(
                game.uri,
                game.bangumiId,
                game.vndbId,
                game.hikarinagiId,
                bangumiReady,
                vndbReady,
                hikarinagiReady,
                localeTag,
                bangumiAuthEpoch = bangumiSessionEpoch,
                vndbAuthEpoch = vndbSessionEpoch,
                hikarinagiAuthEpoch = hikarinagiSessionEpoch,
                nextmoeId = game.nextmoeId,
                nextmoeReady = nextmoeReady,
                nextmoeAuthEpoch = nextmoeSessionEpoch,
            )
            val cached = PlayStatusSummaryCache.get(cacheKey)
            Triple(cacheKey, cached, gates)
        }
        val (cacheKey, cached, gates) = cachedOrMiss
        lastPlayStatusGates = gates
        if (cacheKey == null) {
            playStatusSummary = null
            return@LaunchedEffect
        }
        if (cached != null) {
            playStatusSummary = cached
            return@LaunchedEffect
        }
        playStatusSummary = null
        val readGeneration = PlayStatusSummaryCache.generation(game.uri)
        activeFetchSeq = mySeq
        var gatesChangedDuringFetch = false
        val summary = try {
            val fetched = withContext(Dispatchers.IO) {
                playStatusDrawerSummary(
                    context,
                    game,
                    gates[0],
                    gates[1],
                    gates[2],
                    nextmoeAuthorized = gates[3],
                )
            }
            var postGates: List<Boolean>
            do {
                statusVersionChangedWhileFetching = false
                postGates = withContext(Dispatchers.IO) {
                    resolvePlayStatusGates(context, game)
                }
            } while (statusVersionChangedWhileFetching)
            gatesChangedDuringFetch = postGates != gates
            if (!gatesChangedDuringFetch) {
                lastPlayStatusGates = postGates
            }
            fetched
        } finally {
            if (activeFetchSeq == mySeq) {
                activeFetchSeq = 0
            }
        }
        if (gatesChangedDuringFetch) {
            playStatusRefresh += 1
        } else if (PlayStatusSummaryCache.generation(game.uri) == readGeneration) {
            playStatusSummary = summary.line
            PlayStatusSummaryCache.putIfCacheable(cacheKey, summary, expectedGeneration = readGeneration)
        } else {
            playStatusRefresh += 1
        }
    }
    LaunchedEffect(
        game.uri,
        game.bangumiId,
        game.vndbId,
        game.hikarinagiId,
        game.nextmoeId,
        bangumiStatusVersion,
        vndbStatusVersion,
        hikarinagiStatusVersion,
        nextmoeStatusVersion,
    ) {
        val previousGates = lastPlayStatusGates ?: return@LaunchedEffect
        if (activeFetchSeq != 0) {
            statusVersionChangedWhileFetching = true
            return@LaunchedEffect
        }
        val currentGates = withContext(Dispatchers.IO) { resolvePlayStatusGates(context, game) }
        if (currentGates != previousGates) {
            playStatusRefresh += 1
        }
    }
    // MV/MZ 存档格式转化确认：待转化检测结果 + 已选定的 Artemis 补丁策略（两弹窗串联时保留）
    var rpgSaveDetection by remember(game.uri) { mutableStateOf<RpgSaveFormat.Detection?>(null) }
    var pendingPatchChoice by remember(game.uri) { mutableStateOf<EngineLauncher.ArtemisPatchChoice?>(null) }
    val launchPreparation = remember(game.uri) { GameLaunchPreparation() }
    val batchScrapeRunningMessage = stringResource(R.string.game_batch_scraping_running)
    val coverDownloadFailedMessage = stringResource(R.string.game_cover_download_failed)
    // 抽屉动作条目的文案/颜色：buildList 的 lambda 内不允许 @Composable 调用，先在组合体取值
    val drawerSearchCoverTitle = stringResource(R.string.game_search_cover)
    val drawerPlayStatusTitle = stringResource(R.string.game_play_status)
    val drawerSaveManagementTitle = stringResource(R.string.game_save_management)
    val drawerExternalExitTitle = stringResource(R.string.save_external_exit_title)
    val runtimeCapabilityLoadingTitle = stringResource(R.string.runtime_capability_loading)
    val runtimeCapabilityRetryTitle = stringResource(R.string.runtime_capability_retry)
    val runtimeCapabilityUnavailableSummary = stringResource(R.string.runtime_capability_unavailable)
    val drawerOnlinePatchTitle = stringResource(R.string.game_online_patch)
    val drawerEngineSettingsTitle = stringResource(R.string.settings_engine_settings)
    val drawerPrimaryColor = MaterialTheme.colorScheme.primary
    val saveFormatConvertedFormat = stringResource(R.string.save_format_converted_count)
    val saveFormatConvertedWithFailuresFormat = stringResource(R.string.save_format_converted_with_failures)
    val saveFormatConvertFailedMessage = stringResource(R.string.save_format_convert_failed)
    val saveBusyEngineRunningMessage = stringResource(R.string.save_busy_engine_running)

    val completion = systemNnnLaunch.completion?.takeIf { it.uri == game.uri }
    LaunchedEffect(completion) {
        if (completion == null || systemNnnLaunch.completion !== completion || !systemNnnRequest.takeResult()) return@LaunchedEffect
        systemNnnLaunch.dismissCompletion(completion)
        // A late result must never reverse a dismissal or navigate out of another action.
        if (host.leaving) return@LaunchedEffect
        val result = completion.result ?: return@LaunchedEffect
        launchError = result.toErrorState(context)
        if (launchError == null) navigation.onGameLaunched()
    }

    /** Blocks per-game cover actions while a batch scrape is running. */
    fun isBatchScrapingActive(): Boolean {
        if (!CoverScrapeTaskManager.state.value.running) return false
        AppNotice.makeText(context, batchScrapeRunningMessage, Toast.LENGTH_SHORT, AppNotice.Kind.Limit).show()
        return true
    }

    // 发起启动；Artemis 需要 PFS 基础补丁且策略为“启动时询问”时，先弹窗确认再带选择启动
    /** Launches the selected game, optionally applying an explicit Artemis policy. */
    fun startLaunch(patchChoice: EngineLauncher.ArtemisPatchChoice? = null) {
        scope.launch {
            try {
                launchError = EngineLauncher.launch(context, game, patchChoice).toErrorState(context)
                if (launchError == null) navigation.onGameLaunched()
            } finally {
                launchPreparation.finish()
            }
        }
    }

    /** Artemis 选择落地后，再检查 MV/MZ 存档格式；有标准存档则弹窗，否则直接启动。
     *  开启存档互通时跳过弹窗——启动前同步已覆盖其语义。 */
    fun launchWithSaveFormatGate(patchChoice: EngineLauncher.ArtemisPatchChoice?) {
        scope.launch {
            launchPreparation.step {
                if (EngineLauncher.isRpgSaveInteropEnabled(context, game)) {
                    startLaunch(patchChoice)
                } else {
                    val pending = EngineLauncher.rpgSaveFormatPending(context, game)
                    if (pending != null) {
                        pendingPatchChoice = patchChoice
                        rpgSaveDetection = pending
                    } else {
                        startLaunch(patchChoice)
                    }
                }
            }
        }
    }

    /** 启动前统一门：先 Artemis 补丁确认，再 MV/MZ 存档格式确认，最后才真正拉起。 */
    fun beginLaunch() {
        if (RuntimeModuleBindings.builtInSystemNnn(game).engine == EngineType.SYSTEMNNN) {
            // Neither Artemis nor RPG confirmation applies. Only a live user activation can hand off this launch.
            systemNnnRequest.launch(systemNnnLaunch.activeUri != null, systemNnnLaunch.completion?.uri == game.uri) {
                systemNnnLaunch.launch(context, game)
            }
            return
        }
        if (showPatchConfirm || rpgSaveDetection != null || !launchPreparation.tryBegin()) return
        scope.launch {
            launchPreparation.step {
                if (EngineLauncher.needsArtemisPatchConfirm(context, game)) {
                    showPatchConfirm = true
                } else {
                    launchWithSaveFormatGate(null)
                }
            }
        }
    }

    /** 用户确认转化后执行转化（best-effort），随后按既定策略启动。 */
    fun resolveSaveFormat(convert: Boolean) {
        if (rpgSaveDetection == null) return
        rpgSaveDetection = null
        val patchChoice = pendingPatchChoice
        pendingPatchChoice = null
        scope.launch {
            launchPreparation.step {
                if (convert) {
                    val op = try {
                        withContext(Dispatchers.IO) { EngineLauncher.convertRpgSaveFormat(context, game) }
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (_: Throwable) {
                        null
                    }
                    val message = rpgConvertResultMessage(
                        op,
                        saveFormatConvertedFormat,
                        saveFormatConvertedWithFailuresFormat,
                        saveFormatConvertFailedMessage,
                        saveBusyEngineRunningMessage,
                    )
                    AppNotice.makeText(context, message, Toast.LENGTH_LONG, AppNotice.Kind.Info).show()
                }
                startLaunch(patchChoice)
            }
        }
    }

    val launchFileTitle = stringResource(R.string.game_launch_file)
    val launchFileAutoSummary = stringResource(R.string.game_launch_file_auto_summary)
    val launchTint = MaterialTheme.colorScheme.primary
    // KRKR 与所有 Winlator 系引擎（YU-RIS / CatSystem2 / PC）支持启动文件切换
    val launchFileRow = if (
        game.engine == EngineType.KIRIKIRI ||
        ExternalEmulatorRegistry.forEngine(game.engine)?.launchStyle == EmulatorLaunchStyle.WINLATOR_EXTERNAL
    ) {
        GameActionItem(
            key = "launch_file",
            title = launchFileTitle,
            summary = game.launchFile ?: launchFileAutoSummary,
            icon = R.drawable.ic_sheet_launch_file,
            kind = GameActionKind.Dialog,
            iconTint = launchTint,
            abandonsLaunch = false,
            onClick = { showLaunchFilePicker = true },
        )
    } else {
        null
    }
    // 所有动作条目统一单个占满一行；卡片管理动作（快捷启动 / 桌面快捷方式 / 改名 /
    // 改封面 / 删除）已统一收口到长按操作弹窗，这里只保留启动相关动作与二级页面入口。
    val moduleOpenFailedMessage = stringResource(R.string.engine_open_download_failed)
    val moduleDownloadTitle = stringResource(R.string.engine_module_download_latest)
    val moduleUpdateSummary = moduleUpdate?.let {
        stringResource(R.string.engine_module_update_versions, it.installedVersion, it.latestVersion)
    }
    val drawerActions = buildList {
        moduleUpdate?.let { update ->
            add(
                GameActionItem(
                    key = "engine_module_update",
                    title = moduleDownloadTitle,
                    summary = moduleUpdateSummary,
                    icon = R.drawable.ic_update_download,
                    iconTint = drawerPrimaryColor,
                    onClick = {
                        if (!ExternalEngineLauncher.openInstallPage(context, update.releasesUrl)) {
                            AppNotice.makeText(context, moduleOpenFailedMessage, Toast.LENGTH_SHORT, AppNotice.Kind.Failure).show()
                        }
                    },
                ),
            )
        }
        add(
            GameActionItem(
                key = "search_cover",
                title = drawerSearchCoverTitle,
                icon = R.drawable.ic_sheet_search_cover,
                kind = GameActionKind.Dialog,
                iconTint = drawerPrimaryColor,
                onClick = { if (!isBatchScrapingActive()) showCoverSourcePicker = true },
            ),
        )
        add(
            GameActionItem(
                key = "play_status",
                title = drawerPlayStatusTitle,
                icon = R.drawable.ic_sheet_play_status_entry,
                kind = GameActionKind.Dialog,
                iconTint = drawerPrimaryColor,
                trailingText = playStatusSummary,
                onClick = { showPlayStatusDialog = true },
            ),
        )
        val hasRuntimeSaves = game.engine == EngineType.RUNTIME_MODULE &&
            runtimeModule?.enabled == true && runtimeModule.saves != null
        if (hasRuntimeSaves || shouldShowSaveManagement(game)) {
            add(
                GameActionItem(
                    key = "save_management",
                    title = drawerSaveManagementTitle,
                    icon = R.drawable.ic_sheet_saves,
                    kind = GameActionKind.Detail,
                    iconTint = drawerPrimaryColor,
                    onClick = { navigation.openDetail(GameDetailDestination.SaveManagement) },
                ),
            )
        }
        if (hasPendingExternalExit) {
            add(
                GameActionItem(
                    key = "confirm_external_exit",
                    title = drawerExternalExitTitle,
                    icon = R.drawable.ic_sheet_saves,
                    kind = GameActionKind.Detail,
                    iconTint = drawerPrimaryColor,
                    onClick = { navigation.openDetail(GameDetailDestination.SaveManagement) },
                ),
            )
        }
        if (game.engine == EngineType.RUNTIME_MODULE && (runtimeRefreshRunning || runtimeRefreshFailed)) {
            add(
                GameActionItem(
                    key = "runtime_capability_status",
                    title = if (runtimeRefreshRunning) runtimeCapabilityLoadingTitle else runtimeCapabilityRetryTitle,
                    summary = if (runtimeRefreshFailed) runtimeCapabilityUnavailableSummary else null,
                    icon = R.drawable.ic_sheet_settings,
                    iconTint = drawerPrimaryColor,
                    onClick = {
                        if (!runtimeRefreshRunning) scope.launch {
                            runtimeRefreshRunning = true
                            runtimeRefreshFailed = try {
                                withContext(Dispatchers.IO) {
                                    RuntimeModuleRegistry.refresh(context) !is com.tyranor.next.core.engine.runtime.RuntimeModuleDiscoveryResult.Success
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                true
                            } finally {
                                runtimeRefreshRunning = false
                            }
                        }
                    },
                ),
            )
        }
        if (game.engine == EngineType.KIRIKIRI) {
            add(
                GameActionItem(
                    key = "online_patch",
                    title = drawerOnlinePatchTitle,
                    icon = R.drawable.ic_sheet_patch,
                    kind = GameActionKind.Detail,
                    iconTint = drawerPrimaryColor,
                    onClick = { navigation.openDetail(GameDetailDestination.OnlinePatch) },
                ),
            )
        }
        if ((game.engine == EngineType.RUNTIME_MODULE && runtimeModule?.enabled == true &&
                (runtimeModule.settings?.items?.isNotEmpty() == true || runtimeModule.fileImports.isNotEmpty())) ||
            (game.engine != EngineType.RUNTIME_MODULE && game.engine != EngineType.UNKNOWN)
        ) {
            add(
                GameActionItem(
                    key = "engine_settings",
                    title = drawerEngineSettingsTitle,
                    icon = R.drawable.ic_sheet_settings,
                    kind = GameActionKind.Detail,
                    iconTint = drawerPrimaryColor,
                    onClick = { navigation.openDetail(GameDetailDestination.EngineSettings) },
                ),
            )
        }
    }
    content(
        GameActionsUi(
            launchFile = launchFileRow,
            items = drawerActions,
            presentsDetailsInLeaf = host.presentsDetailsInLeaf,
            launch = {
                if (!(systemNnnLaunch.activeUri != null && RuntimeModuleBindings.builtInSystemNnn(game).engine == EngineType.SYSTEMNNN)
                    && host.interact()
                ) beginLaunch()
            },
            perform = { action ->
                if (host.interact()) {
                    if (action.abandonsLaunch) systemNnnRequest.takeResult()
                    action.onClick()
                }
            },
        ),
    )

    // ===== Artemis 自动补丁确认：总是（记住 auto）/ 本次 / 不再（记住 off）；点遮罩取消 = 不启动 =====
    if (showPatchConfirm) {
        ArtemisPatchChoiceDialog(
            game = game,
            onChoice = { choice ->
                if (showPatchConfirm) {
                    showPatchConfirm = false
                    if (choice == null) {
                        launchPreparation.finish()
                    } else {
                        // A restored confirmation has no in-memory owner yet; resume the same one-shot flow.
                        launchPreparation.tryBegin()
                        launchWithSaveFormatGate(choice)
                    }
                }
            },
        )
    }

    // ===== MV/MZ 存档格式转化确认（标准 → Tyranor）；点遮罩 = 保持原样启动 =====
    rpgSaveDetection?.let { detection ->
        val (standardCount, hashedCount) = detection.dialogArgs()
        RpgSaveFormatDialog(
            standardCount = standardCount,
            hashedCount = hashedCount,
            onChoice = { convert -> resolveSaveFormat(convert) },
        )
    }

    if (showCoverSourcePicker) {
        CoverSourcePickerDialog(
            onDismiss = { showCoverSourcePicker = false },
            onSelect = { source ->
                if (!isBatchScrapingActive()) {
                    showCoverSourcePicker = false
                    coverBindError = null
                    coverSearchSessionId += 1L
                    coverSearchSource = source
                }
            },
        )
    }

    coverSearchSource?.let { source ->
        CoverSearchDialog(
            game = game,
            source = source,
            binding = coverBinding,
            bindError = coverBindError,
            onDismiss = {
                coverSearchSessionId += 1L
                coverBindError = null
                coverSearchSource = null
            },
            onBind = { candidate, beforeClose ->
                if (!coverBinding && !isBatchScrapingActive()) {
                    val bindSessionId = coverSearchSessionId
                    coverBinding = true
                    coverBindError = null
                    // The completion releases an Android Window before publishing the cover.
                    // Resume on the UI thread even when the composition uses a test dispatcher.
                    scope.launch(Dispatchers.Main.immediate) {
                        val updated = withContext(Dispatchers.IO) {
                            try {
                                CoverScraperService.bindCoverCandidate(context, game, candidate)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (e: Throwable) {
                                // 与 HttpJson 同口径：脱敏 Log.w 留排障线索，UI 仅展示失败文案
                                Log.w(TAG, "bind cover candidate failed", e)
                                null
                            }
                        }
                        coverBinding = false
                        if (updated != null) {
                            onGameCoverUpdated(source, updated)
                            if (coverSearchSource == source && coverSearchSessionId == bindSessionId) {
                                beforeClose()
                                coverSearchSource = null
                                navigation.close()
                            }
                        } else if (coverSearchSource == source && coverSearchSessionId == bindSessionId) {
                            coverBindError = coverDownloadFailedMessage
                        }
                    }
                }
            },
        )
    }

    if (showLaunchFilePicker) {
        LaunchFileDialog(
            game = game,
            onDismiss = { showLaunchFilePicker = false },
            onConfirm = { name ->
                showLaunchFilePicker = false
                val updater = onGameLaunchFileUpdated
                if (updater != null) updater(game.uri, name) else onGameUpdated(game.copy(launchFile = name))
            },
        )
    }

    launchError?.let { state ->
        LaunchErrorDialog(state = state, onDismiss = { launchError = null })
    }

    if (showPlayStatusDialog) {
        PlayStatusDialog(
            game = game,
            onDismiss = { showPlayStatusDialog = false; playStatusRefresh++ },
            onBindingUpdated = onGameBindingUpdated,
        )
    }

}

private fun resolvePlayStatusGates(context: Context, game: ScanGame): List<Boolean> {
    // Bangumi 与 Hikarinagi/NextMoe 要求 authorized 且未待重授权；VNDB 额外允许 PERMISSION
    // （仅缺少列表权限），支持用户在网页端补勾权限后打开抽屉直接自愈。
    // Bangumi 读回接入后同样携带独立 permission_error 位（读 403 置位，R9-A 分位语义）：
    // 门控只排 needsReauth 不排权限位——权限缺失时 Bangumi 段仍参与摘要并以错误文案即时
    // 展示（不入 TTL 缓存），读 2xx 双清自愈，与 Hikarinagi/NextMoe 一致
    val bangumiReady = game.bangumiId?.takeIf { it.isNotBlank() }?.let {
        val status = BangumiAuthStore.getStatus(context)
        status.authorized && !status.needsReauth
    } == true
    val vndbReady = game.vndbId?.takeIf { it.isNotBlank() }?.let {
        val status = VndbAuthStore.getStatus(context)
        status.authorized && (!status.needsReauth || status.lastErrorKind == VndbAuthError.PERMISSION)
    } == true
    val hikarinagiReady = game.hikarinagiId?.takeIf { it.isNotBlank() }?.let {
        val status = HikarinagiAuthStore.getStatus(context)
        status.authorized && !status.needsReauth
    } == true
    val nextmoeReady = game.nextmoeId?.takeIf { it.isNotBlank() }?.let {
        val status = NextMoeAuthStore.getStatus(context)
        status.authorized && !status.needsReauth
    } == true
    return listOf(bangumiReady, vndbReady, hikarinagiReady, nextmoeReady)
}

@Composable
private fun CoverSourcePickerDialog(
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    val context = LocalContext.current
    val authVersion by HikarinagiAuthStore.statusVersion.collectAsStateWithLifecycle()
    val scraperSettingsVersion by AppSettingsStore.coverScraperSettingsVersion.collectSettingAsState()
    val sources = remember(scraperSettingsVersion) {
        AppSettingsStore.getCoverScraperSourceOrder(context)
    }
    val authStatus by produceState<HikarinagiAuthStatus?>(initialValue = null, authVersion) {
        value = withContext(Dispatchers.IO) { HikarinagiAuthStore.getStatus(context) }
    }
    // 等首次 IO 读取 Hikarinagi 授权态就绪再打开弹窗，避免首帧短暂置灰闪烁
    val loadedAuth = authStatus ?: return

    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.game_select_cover_source), style = MaterialTheme.typography.titleMedium) },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().dialogListHeight(320.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                lazyItemsIndexed(
                    sources,
                    key = { _, source -> source },
                    contentType = { _, _ -> "cover_source" },
                ) { index, source ->
                    val enabled = AppSettingsStore.isCoverScraperSourceEnabled(context, source)
                    val needsHikarinagiLogin = source == AppSettingsStore.COVER_SOURCE_HIKARINAGI &&
                        (!loadedAuth.authorized || loadedAuth.needsReauth)
                    val selectable = enabled && !needsHikarinagiLogin
                    AppNavItem(
                        title = coverSourceTitle(source),
                        summary = coverSourcePickerSummary(source, enabled, needsHikarinagiLogin),
                        // 来源条目在弹窗正文块内再逐条错峰升起（行程短于正文块，避免叠加过猛）
                        modifier = Modifier.staggeredEntrance(1 + index, distance = 8.dp, stepMillis = 45L),
                        onClick = if (selectable) ({ onSelect(source) }) else null,
                        leadingIcon = R.drawable.ic_cover_source,
                        // 弹窗条目自足禁用按压反馈（AGENTS.md 弹窗点击反馈统一规范）
                        indication = null,
                    )
                }
            }
        },
        confirmButton = { DialogTextButton(text = stringResource(R.string.common_cancel), onClick = onDismiss) },
    )
}

/**
 * Searches one cover source and, where supported, drills into a work's alternate covers.
 * Releases the dialog's touch interception before dismissing or via `beforeClose` once a selected cover is persisted.
 * 在「游戏盒」风格翻开的书里（组合树提供了 [LocalAppDialogHost]）不另开窗口：标题、搜索区、结果与按钮翻到书的下一页上，
 * 「关闭」先把这一页翻回去再关；其余（搜索、作品多封面、绑定与等待语义）与窗口版完全相同。
 */
@Composable
internal fun CoverSearchDialog(
    game: ScanGame,
    source: String,
    binding: Boolean,
    bindError: String?,
    onDismiss: () -> Unit,
    onBind: (candidate: CoverSearchCandidate, beforeClose: () -> Unit) -> Unit,
) {
    val context = LocalContext.current
    val bookHost = LocalAppDialogHost.current
    val coverNoMatchMessage = stringResource(R.string.game_cover_no_match)
    var keyword by rememberSaveable(source, game.uri) { mutableStateOf(game.title) }
    var searching by remember { mutableStateOf(false) }
    var noMatch by rememberSaveable(source, game.uri) { mutableStateOf(false) }
    var errorCode by rememberSaveable(source, game.uri) { mutableStateOf<CoverErrorCode?>(null) }
    var candidates by rememberSaveable(source, game.uri, stateSaver = CoverSearchCandidatesSaver) {
        mutableStateOf(emptyList<CoverSearchCandidate>())
    }
    // 二级（未萌 NextMoe）：作品 → 该作品可获取的封面；其它来源恒为 null
    var drillDownWork by rememberSaveable(source, game.uri, stateSaver = CoverSearchCandidateNullableSaver) {
        mutableStateOf<CoverSearchCandidate?>(null)
    }
    var workCovers by rememberSaveable(source, game.uri, stateSaver = CoverSearchCandidatesSaver) {
        mutableStateOf(emptyList<CoverSearchCandidate>())
    }
    var coversLoaded by rememberSaveable(source, game.uri) { mutableStateOf(false) }
    var loadingCovers by remember(source, game.uri) { mutableStateOf(false) }
    var coversErrorCode by rememberSaveable(source, game.uri) { mutableStateOf<CoverErrorCode?>(null) }
    var coversJob by remember(source, game.uri) { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    // 书里没有自己的窗口，也就没有窗口的进退场
    val overlay = if (bookHost == null) rememberGameOverlayMotion(GameOverlay.CoverSearch) else null
    // 空关键词时按键盘搜索被拒绝：输入框抖一下
    var searchRejects by remember { mutableIntStateOf(0) }
    // 遮罩 pointerInput(Unit) 不随绑定态重启：经 state 读取最新绑定态，避免闭包读到旧值
    val bindingActive by rememberUpdatedState(binding)

    fun isClosing(): Boolean = overlay?.isClosing == true

    fun dismiss() {
        if (!binding && !isClosing()) {
            if (overlay != null) overlay.dismiss(onDismiss) else onDismiss()
        }
    }

    /** 离开二级：取消在飞请求并清干净二级状态，避免残留状态或迟到写入影响一级。 */
    fun leaveDrillDown() {
        coversJob?.cancel()
        coversJob = null
        drillDownWork = null
        workCovers = emptyList()
        coversLoaded = false
        coversErrorCode = null
        loadingCovers = false
    }

    fun search() {
        val query = keyword.trim()
        if (query.isEmpty() && !searching && !binding && !isClosing()) searchRejects++
        if (query.isEmpty() || searching || binding || isClosing()) return
        scope.launch {
            searching = true
            errorCode = null
            noMatch = false
            coversErrorCode = null
            candidates = emptyList()
            try {
                when (val result = withContext(Dispatchers.IO) {
                    // 关键词未被用户改动（仍等于游戏自身标题）时才按 VNDB id 精确反查，改了关键词就纯关键词搜
                    CoverScraperService.searchCoverCandidates(
                        context = context,
                        source = source,
                        keyword = query,
                        limit = 8,
                        vndbId = game.vndbId?.takeIf { query == game.title.trim() },
                    )
                }) {
                    is CoverSearchResult.Success -> {
                        candidates = result.candidates.distinctBy { "${it.source}:${it.id}:${it.coverUrl}" }
                        if (candidates.isEmpty()) noMatch = true
                    }
                    is CoverSearchResult.Failure -> {
                        errorCode = result.error
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                errorCode = CoverErrorCode.UNKNOWN
            } finally {
                searching = false
            }
        }
    }

    // 二级：拉取所选作品可获取的封面（只有支持「一作品多封面」的来源会走到这里）
    fun openWork(work: CoverSearchCandidate) {
        if (loadingCovers || binding || isClosing()) return
        // 上一个二级请求让位：其 finally 不再回写 loading 态（见下方 isActive 守卫）
        coversJob?.cancel()
        coversJob = null
        drillDownWork = work
        workCovers = emptyList()
        coversLoaded = false
        coversErrorCode = null
        loadingCovers = true
        coversJob = scope.launch {
            try {
                when (val result = withContext(Dispatchers.IO) {
                    CoverScraperService.searchWorkCovers(context, source, work, 100)
                }) {
                    is CoverSearchResult.Success -> {
                        workCovers = result.candidates.distinctBy { "${it.source}:${it.id}:${it.coverUrl}" }
                        coversLoaded = true
                    }
                    is CoverSearchResult.Failure -> {
                        coversErrorCode = result.error
                        coversLoaded = true
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                coversErrorCode = CoverErrorCode.UNKNOWN
                coversLoaded = true
            } finally {
                // 取消路径的收尾由 leaveDrillDown/新请求负责，避免迟到的 finally 清掉新一轮 loading 态
                if (isActive) loadingCovers = false
            }
        }
    }

    LaunchedEffect(source, game.uri) {
        val pendingWork = drillDownWork
        if (pendingWork != null && !coversLoaded && !loadingCovers) {
            openWork(pendingWork)
        }
    }

    val onCandidateClick: (CoverSearchCandidate) -> Unit = { candidate ->
        if (!binding && !isClosing()) {
            if (drillDownWork == null && CoverScraperService.supportsCoverDrillDown(candidate.source)) {
                openWork(candidate)
            } else {
                onBind(candidate) { overlay?.releaseWindow() }
            }
        }
    }

    val drillDown = drillDownWork
    val visibleCandidates = if (drillDown != null) workCovers else candidates
    val canSearch = keyword.trim().isNotEmpty() && !searching && !binding
    val infoMessage: String? = when {
        // 两级各自的提示严格分离：离开二级时已清空二级状态，这里的判定只作双保险
        drillDown == null && noMatch -> coverNoMatchMessage
        drillDown == null && errorCode != null -> errorCode?.coverErrorMessage(context)
        drillDown != null && coversErrorCode != null -> coversErrorCode?.coverErrorMessage(context)
        // 二级已加载完仍无封面：与一级同一条「未找到匹配结果」
        drillDown != null && !loadingCovers && visibleCandidates.isEmpty() -> coverNoMatchMessage
        else -> null
    }
    // 结果宫格：出现 / 清空时整体模糊替换（退场期间保留最后一批结果、且不可点）
    val resultsShown = visibleCandidates.isNotEmpty()
    val resultsInteractive by rememberUpdatedState(resultsShown)
    val shownCandidates = rememberLastNonNull(visibleCandidates.takeIf { it.isNotEmpty() }) ?: visibleCandidates

    // 标题：一级「来源搜索」<-> 二级「作品多封面」模糊替换（窗口里居中，书页上与其它章名一样靠左）
    val title: @Composable (Boolean) -> Unit = { centered ->
        BlurCrossfade(drillDown, contentAlignment = if (centered) Alignment.Center else Alignment.CenterStart) { titleWork ->
            Text(
                titleWork?.let { stringResource(R.string.cover_multi_cover_title, it.title) }
                    ?: stringResource(R.string.game_cover_search_title, coverSourceTitle(source)),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = if (centered) TextAlign.Center else TextAlign.Start,
            )
        }
    }
    // 搜索区：输入框（二级沿用一级关键词，不再展示）、等待 / 归属 / 绑定提示、错误与结果提示
    val inputs: @Composable (Modifier) -> Unit = { modifier ->
        Column(modifier) {
            // 二级沿用一级关键词，不再展示输入框
            ExpandVisibility(drillDown == null) {
                AppSearchField(
                    query = keyword,
                    onQueryChange = { keyword = it },
                    onSearch = { search() },
                    textStyle = MaterialTheme.typography.bodyMedium,
                    // 空关键词按键盘搜索被拒绝：水平抖一下
                    modifier = Modifier.shakeOnTrigger(searchRejects, amplitude = 6.dp),
                )
            }
            // 收起期间保持最后的提示文字，不在退场时跳成「加载中」
            val searchHintRes = rememberLastNonNull(
                when {
                    searching -> R.string.game_cover_searching
                    loadingCovers -> R.string.common_loading
                    else -> null
                },
            ) ?: R.string.common_loading
            ExpandVisibility(searching || loadingCovers) {
                Text(
                    stringResource(searchHintRes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = SecondaryTextColor,
                    // 等待中的提示文字缓缓明灭（收起后即停止）
                    modifier = Modifier
                        .breathing(searching || loadingCovers)
                        .padding(top = 8.dp),
                )
            }
            ExpandVisibility(
                source == AppSettingsStore.COVER_SOURCE_NEXTMOE &&
                    (drillDown != null || candidates.isNotEmpty()),
            ) {
                // 归属要求：封面数据来自未萌 NextMoe（鲲 Galgame 论坛）
                Text(
                    stringResource(R.string.cover_nextmoe_attribution),
                    style = MaterialTheme.typography.bodyMedium,
                    color = SecondaryTextColor,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            ExpandVisibility(binding) {
                Text(
                    stringResource(R.string.game_cover_binding),
                    style = MaterialTheme.typography.bodyMedium,
                    color = SecondaryTextColor,
                    modifier = Modifier
                        .breathing(binding)
                        .padding(top = 8.dp),
                )
            }
            // 错误 / 提示文案出现、消失、替换时模糊过渡；绑定失败（新错误出现）时抖一下
            BlurCrossfade(bindError, modifier = Modifier.shakeOnTrigger(bindError, amplitude = 6.dp)) { error ->
                if (error != null) {
                    Text(
                        error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
            BlurCrossfade(infoMessage) { message ->
                if (message != null) {
                    Text(
                        message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }
    // 结果宫格：每批结果到达时自带一轮入场，候选卡依次升起；滚动时卡片轻微果冻拉伸
    val results: @Composable (Modifier, GridCells) -> Unit = { modifier, columns ->
        Box(modifier) {
            BlurCrossfade(resultsShown, modifier = Modifier.fillMaxSize()) { hasResults ->
            if (hasResults) {
                val resultsJelly = rememberScrollJellyState()
                ProvideEntrance(rememberEntranceState()) {
                LazyVerticalGrid(
                    columns = columns,
                    modifier = Modifier.fillMaxSize().scrollJellyHost(resultsJelly),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    gridItemsIndexed(
                        items = shownCandidates,
                        key = { _, candidate -> "${candidate.source}:${candidate.id}:${candidate.coverUrl}" },
                        contentType = { _, _ -> "cover_candidate" },
                    ) { index, candidate ->
                        CoverCandidateCard(
                            modifier = listItemMotion()
                                .staggeredEntrance(index, distance = 16.dp, stepMillis = 30L)
                                .scrollJelly(resultsJelly, strength = 0.03f),
                            candidate = candidate,
                            onClick = { if (resultsInteractive) onCandidateClick(candidate) },
                            // 二级：标注封面类型 / 尺寸 / 分级，便于区分同一作品的多张封面
                            caption = if (drillDown != null) candidate.detail else null,
                        )
                    }
                }
                }
            }
            }
        }
    }
    // 按钮行：一级「关闭 / 搜索」<-> 二级「返回 / 关闭」整行模糊替换，按钮不再原地跳位。
    // 只有「关闭」随弹窗（书页）的退场控制器先退场再执行；「搜索」「返回」不关闭，不随退场
    val actions: @Composable (Modifier, PaddingValues) -> Unit = { modifier, padding ->
        BlurCrossfade(drillDown != null, modifier = modifier.fillMaxWidth()) { inDrillDown ->
            if (inDrillDown) {
                SplitEndsActionRow(
                    modifier = Modifier.padding(padding),
                    leading = {
                        CompositionLocalProvider(LocalDialogExit provides null) {
                            DialogTextButton(
                                text = stringResource(R.string.common_back),
                                enabled = !binding && !isClosing(),
                                onClick = { if (!binding && !isClosing()) leaveDrillDown() },
                            )
                        }
                    },
                    trailing = {
                        // 绑定进行中与遮罩/返回键一致禁用（等待语义见下方 Dialog 注释）
                        DialogTextButton(
                            text = stringResource(R.string.common_close),
                            enabled = !binding && !isClosing(),
                            onClick = ::dismiss,
                        )
                    },
                )
            } else {
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(padding),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    // 绑定进行中与遮罩/返回键一致禁用（等待语义见下方 Dialog 注释）
                    DialogTextButton(
                        text = stringResource(R.string.common_close),
                        enabled = !binding && !isClosing(),
                        onClick = ::dismiss,
                    )
                    // 「搜索」<->「搜索中」文案模糊替换
                    CompositionLocalProvider(LocalDialogExit provides null) {
                        BlurCrossfade(searching, contentAlignment = Alignment.Center) { isSearching ->
                            DialogTextButton(
                                text = if (isSearching) stringResource(R.string.game_searching) else stringResource(R.string.game_search),
                                enabled = canSearch && !isClosing(),
                                onClick = { search() },
                            )
                        }
                    }
                }
            }
        }
    }

    if (overlay == null) {
        bookHost?.Present(
            AppDialogSpec(
                onDismissRequest = { if (!binding) onDismiss() },
                title = { CompositionLocalProvider(LocalContentColor provides TextColor) { title(false) } },
                text = {
                    // 书页在书的窗口里：二级（作品多封面）的返回键「先回一级」注册在那个窗口上
                    BackHandler(enabled = drillDown != null && !binding) { leaveDrillDown() }
                    CompositionLocalProvider(LocalIndication provides NoIndication) {
                        Column(Modifier.fillMaxSize()) {
                            inputs(Modifier.fillMaxWidth())
                            results(Modifier.fillMaxWidth().weight(1f).padding(top = 12.dp), GridCells.Fixed(CoverSearchBookColumns))
                        }
                    }
                },
                confirmButton = { actions(Modifier, PaddingValues(0.dp)) },
                dismissButton = null,
                buttonArrangement = Arrangement.End,
                textScrollEnabled = false,
                dismissEnabled = !binding,
                showButtonRow = true,
            ),
        )
        return
    }

    // 等待语义（BK-56 同口径，与 SubjectBindDialog / CredentialTokenDialog 一致）：绑定进行中
    // 遮罩点击 / 返回键 / 「关闭」按钮一致禁用——底层下载为阻塞 IO 且旧图回收与落库已收敛到
    // core 原子段，中途关闭会导致用户误以为绑定已放弃，而完成后又会把刚回到的抽屉整个关掉
    Dialog(
        onDismissRequest = { if (!binding && !isClosing()) dismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        overlay.BindWindow()
        // 弹窗是独立 Window，层级在解锁门之上：应用锁生效时立即强制收起（不播退场动画），
        // 不能把封面搜索结果留在门上方继续可点
        DismissOnAppLockEffect { overlay.dismissImmediately(onDismiss) }
        // Dialog 是独立窗口：返回键必须在窗口内注册，才能「二级先回一级」而不是直接关掉弹窗
        BackHandler(enabled = drillDownWork != null && !binding && !isClosing()) { leaveDrillDown() }
        val tablet = isTabletScreen()
        val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
        Box(
            modifier = overlay.searchScrim(Modifier.fillMaxSize()),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) { detectTapGestures { if (!bindingActive) dismiss() } },
            )
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (tablet) Modifier.safeDrawingPadding() else Modifier.statusBarsPadding().navigationBarsPadding().imePadding())
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                val dialogHeight = if (imeVisible) maxHeight else minOf(maxHeight, CoverSearchDialogMaxHeight)
                Box(
                    modifier = Modifier
                        .then(if (tablet) Modifier.widthIn(max = CoverSearchDialogMaxWidth) else Modifier)
                        .fillMaxWidth()
                        .height(dialogHeight)
                        .then(overlay.panel(Modifier))
                        .clip(AppComponentShape)
                        // 玻璃风格用高不透明度面板；白雾玻璃用页面底图 + 白纱的面板层
                        .background(
                            when {
                                AppThemeColors.isGlass -> GlassPanel
                                AppThemeColors.isMilkGlass -> Color.Transparent
                                else -> NavWhite
                            },
                        )
                        .glassBorder()
                        .pointerInput(Unit) { detectTapGestures { } },
                ) {
                    if (AppThemeColors.isMilkGlass) {
                        MilkGlassPanelBackground(Modifier.matchParentSize())
                    }
                    // 弹窗是自绘的亮面板，不在 AppAlertDialog 的 contentColor 上下文里；
                    // 显式提供条目正文色、弹窗子组件取色上下文与无涟漪反馈，与 AppAlertDialog 对齐。
                    CompositionLocalProvider(
                        LocalContentColor provides TextColor,
                        LocalAppDialogContent provides true,
                        LocalIndication provides NoIndication,
                    ) {
                        // 面板内部编排：标题 → 搜索区 → 结果区 → 按钮行依次升起（与统一弹窗同一节拍）
                        ProvideEntrance(rememberEntranceState()) {
                        Column(Modifier.fillMaxSize().skipEntranceOnDrag()) {
                            Box(
                                modifier = Modifier
                                    .enterGamePanel(GameOverlay.CoverSearch, GamePanelPart.Title)
                                    .fillMaxWidth()
                                    .padding(horizontal = 18.dp, vertical = 16.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                title(true)
                            }
                            inputs(
                                Modifier
                                    .enterGamePanel(GameOverlay.CoverSearch, GamePanelPart.Input)
                                    .fillMaxWidth()
                                    // 横屏输入法和多行错误提示同时出现时，仍给结果与底部按钮留出空间。
                                    .then(if (tablet) Modifier
                                        .heightIn(max = dialogHeight / 2)
                                        .verticalScroll(rememberScrollState()) else Modifier)
                                    .padding(horizontal = 18.dp),
                            )
                            results(
                                Modifier
                                    .enterGamePanel(GameOverlay.CoverSearch, GamePanelPart.Content)
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .padding(horizontal = 18.dp, vertical = 12.dp),
                                GridCells.Adaptive(CoverSearchCandidateMinWidth),
                            )
                            actions(
                                Modifier.enterGamePanel(GameOverlay.CoverSearch, GamePanelPart.Actions),
                                PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                            )
                        }
                        }
                    }
                }
            }
            NoticeHost(Modifier.matchParentSize())
        }
    }
}

private val CoverSearchDialogMaxWidth: Dp = 720.dp
private val CoverSearchDialogMaxHeight: Dp = 620.dp
private val CoverSearchCandidateMinWidth: Dp = 150.dp

/** 书页上的封面候选排几列（书页比窗口窄得多，按最小宽度自适应只能排一列）。 */
private const val CoverSearchBookColumns = 2

internal val CoverSearchCandidatesSaver = listSaver<List<CoverSearchCandidate>, String>(
    save = { candidates -> candidates.map { encodeCoverSearchCandidate(it) } },
    restore = { savedCandidates -> savedCandidates.mapNotNull { decodeCoverSearchCandidate(it) } },
)

internal val CoverSearchCandidateNullableSaver = Saver<CoverSearchCandidate?, String>(
    save = { candidate -> candidate?.let(::encodeCoverSearchCandidate).orEmpty() },
    restore = { encoded -> encoded.takeIf { it.isNotEmpty() }?.let(::decodeCoverSearchCandidate) },
)

/**
 * Displays a selectable cover preview and optional alternate-cover caption. Loading, ready, and
 * failed previews transition by state category so bitmap replacement alone does not restart motion.
 */
@Composable
private fun CoverCandidateCard(
    candidate: CoverSearchCandidate,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** 二级（同作品多封面）用的补充标签：封面类型 · 尺寸 · 分级；一级候选不传。 */
    caption: String? = null,
) {
    val previewState by rememberCandidateCoverPreview(candidate)
    Column(
        modifier
            .pressSquish(scale = PressSquishDefaults.CardScale)
            .fillMaxWidth()
            // 弹窗正文内的可点击组件禁用点击反馈（AGENTS.md 弹窗点击反馈统一规范）
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .clip(AppComponentShape)
                // 占位底色：书外 PageGrey / 亮玻璃面，书页内纸墨淡底
                .background(com.tyranor.next.ui.common.appDialogSurfaceColor())
                // 预览图下载中：占位块上扫过一道流光；到达 / 失败即停止（只画在加载中的少量候选上）
                .shimmerSweep(active = previewState is CoverPreviewState.Loading),
            contentAlignment = Alignment.Center,
        ) {
            // 加载中 -> 预览图 / 失败：模糊替换（按状态类别切换，位图本身变化不触发）
            BlurCrossfade(
                previewState,
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
                contentKey = { it::class },
            ) { state ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    when (state) {
                        CoverPreviewState.Failed -> Text(
                            stringResource(R.string.game_no_preview),
                            style = MaterialTheme.typography.bodyMedium,
                            color = SecondaryTextColor,
                        )
                        CoverPreviewState.Loading -> DelayedProgress {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                strokeWidth = 2.dp,
                            )
                        }
                        is CoverPreviewState.Ready -> Image(
                            bitmap = state.bitmap,
                            contentDescription = candidate.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
            CoverCandidateOverlay(candidate)
        }
        Text(
            candidate.title.ifBlank { coverSourceTitle(candidate.source) },
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
        if (!caption.isNullOrBlank()) {
            Text(
                caption,
                style = MaterialTheme.typography.bodyMedium,
                color = SecondaryTextColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun CoverCandidateOverlay(candidate: CoverSearchCandidate) {
    Column(
        modifier = Modifier.fillMaxSize().padding(6.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                coverSourceTitle(candidate.source),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                modifier = Modifier
                    .clip(AppComponentShape)
                    .background(NavWhiteOverlay)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
            candidate.score?.takeIf { it > 0 }?.let { score ->
                Text(
                    stringResource(R.string.game_votes, score),
                    style = MaterialTheme.typography.bodyMedium,
                    color = CoverBadgeText,
                    maxLines = 1,
                    modifier = Modifier
                        .clip(AppComponentShape)
                        .background(CoverBadgeBackground)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        Text(
            stringResource(R.string.game_use),
            style = MaterialTheme.typography.bodyMedium,
            color = CoverBadgeText,
            maxLines = 1,
            modifier = Modifier
                .align(Alignment.End)
                .clip(AppComponentShape)
                .background(CoverBadgeBackground)
                .padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun coverSourcePickerSummary(source: String, enabled: Boolean, needsHikarinagiLogin: Boolean): String = when {
    !enabled -> stringResource(R.string.game_cover_source_disabled)
    needsHikarinagiLogin -> stringResource(R.string.game_cover_source_requires_login)
    source == AppSettingsStore.COVER_SOURCE_HIKARINAGI -> stringResource(R.string.game_cover_source_hikarinagi_summary)
    source == AppSettingsStore.COVER_SOURCE_BANGUMI -> stringResource(R.string.game_cover_source_bangumi_summary)
    source == AppSettingsStore.COVER_SOURCE_STEAM -> stringResource(R.string.game_cover_source_steam_summary)
    source == AppSettingsStore.COVER_SOURCE_VNDB -> stringResource(R.string.game_cover_source_vndb_summary)
    source == AppSettingsStore.COVER_SOURCE_NEXTMOE -> stringResource(R.string.game_cover_source_nextmoe_summary)
    else -> stringResource(R.string.game_cover_source_generic_summary)
}

/** KRKR 专属：选择游戏启动入口文件（目录内 xp3 / exe）。 */
@Composable
private fun LaunchFileDialog(
    game: ScanGame,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val context = LocalContext.current
    var loading by remember { mutableStateOf(true) }
    var files by remember { mutableStateOf<List<String>>(emptyList()) }
    var selected by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(game.uri) {
        val options = withContext(Dispatchers.IO) { EngineLauncher.launchFileOptions(context, game) }
        files = options.names
        selected = options.current?.takeIf { options.names.contains(it) }
        loading = false
    }

    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.game_launch_file), style = MaterialTheme.typography.titleMedium) },
        text = {
            // 加载中 / 空 / 列表 之间模糊替换（0 / 1 / 2）
            BlurCrossfade(if (loading) 0 else if (files.isEmpty()) 1 else 2) { pane ->
            when (pane) {
                0 -> Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                    DelayedProgress { CircularProgressIndicator() }
                }
                1 -> Text(
                    stringResource(R.string.game_launch_file_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = SecondaryTextColor,
                )
                else -> MiuixSettingsTheme {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().dialogListFixedHeight(260.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        lazyItemsIndexed(files) { index, name ->
                            com.tyranor.next.ui.common.AppRadioRow(
                                selected = selected == name,
                                label = name,
                                onClick = { selected = name },
                                modifier = Modifier.staggeredEntrance(1 + index, distance = 8.dp, stepMillis = 40L),
                            )
                        }
                    }
                }
            }
            }
        },
        confirmButton = {
            DialogTextButton(
                text = stringResource(R.string.common_confirm),
                enabled = selected != null,
                onClick = { selected?.let(onConfirm) },
            )
        },
        dismissButton = {
            DialogTextButton(
                text = stringResource(R.string.common_cancel),
                onClick = onDismiss,
            )
        },
    )
}

private val GameGridSideInset = 12.dp
private val GameGridColumnGap = 10.dp

/** 瀑布流先合并两侧留白再取整；等高网格对两侧留白分别取整。 */
@Composable
private fun GameGridCoverWidth(columns: Int, staggered: Boolean, content: @Composable (Int) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val coverWidthPx = with(density) {
            val horizontalPadding = if (staggered) (GameGridSideInset * 2).roundToPx() else GameGridSideInset.roundToPx() * 2
            gameGridCoverWidthPx(constraints.maxWidth, columns, horizontalPadding, GameGridColumnGap.roundToPx())
        }
        content(coverWidthPx)
    }
}

/**
 * 游戏页默认瀑布流（列数、封面加载优化、卡片名称标签与角标设置均按游戏页设置生效）。
 */
@Composable
internal fun GameGrid(
    games: List<ScanGame>,
    cardShape: Shape,
    columns: Int,
    gridState: LazyStaggeredGridState,
    onGameClick: (ScanGame) -> Unit,
    onGameLongClick: (ScanGame) -> Unit,
) = GameGridCoverWidth(columns, staggered = true) { coverWidthPx ->
    // 液态玻璃导航悬浮时不占布局：列表底部预留导航高度，滚动到底时最后一行可完全露出不被遮挡；
    // 滚动过程中内容仍可经过玻璃后面（沉浸）
    val glassBottomInset = glassNavBottomInset()
    // 应用设置「卡片隐藏名称标签」：游戏页卡片名称去掉【】/[] 标签（默认开）
    val hideTitleTag by AppSettingsStore.gameCardTitleTagState.collectSettingAsState()
    // 应用设置「卡片引擎角标」：左上角引擎类型角标（默认关）
    val showEngineBadge by AppSettingsStore.gameCardBadgeState.collectSettingAsState()
    // 滚动果冻：滚得越快卡片沿滚动方向拉长越多，停下 Q 弹回原样（只在图层里读，不触发重组；「普通」档不挂）
    val gridJelly = rememberScrollJellyState()
    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Fixed(columns),
        state = gridState,
        modifier = Modifier.fillMaxSize().scrollJellyHost(gridJelly),
        contentPadding = PaddingValues(
            start = GameGridSideInset, end = GameGridSideInset, top = 12.dp, bottom = 12.dp + glassBottomInset,
        ),
        horizontalArrangement = Arrangement.spacedBy(GameGridColumnGap),
        verticalItemSpacing = 12.dp,
    ) {
        staggeredGridItemsIndexed(
            items = games,
            // 封面批量任务逐项更新时强制重建对应卡片的封面状态；否则列表可能继续复用
            // 以 uri 为身份的旧 item，直到卡片滚出屏幕后才重新读取新的 coverUri。
            key = { _, game -> gameCardItemKey(game) },
            contentType = { _, _ -> "game_card" },
        ) { index, game ->
            GameCard(
                // 搜索 / 排序 / 增删时卡片位置弹簧过渡；首屏依次弹入
                modifier = listItemMotion(ListItemPlacement.Reflow).staggeredEntrance(index)
                    .scrollJelly(gridJelly, strength = 0.03f),
                game = game,
                cardShape = cardShape,
                coverWidthPx = coverWidthPx,
                onClick = { onGameClick(game) },
                onLongClick = { onGameLongClick(game) },
                hideTitleTag = hideTitleTag,
                showEngineBadge = showEngineBadge,
            )
        }
    }
}

internal fun gameCardItemKey(game: ScanGame): String =
    "${game.uri}\u0000${game.coverUri.orEmpty()}\u0000${game.coverSource.orEmpty()}"

/**
 * Renders a stable-aspect library card with optional long press and engine badge.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun GameCard(
    game: ScanGame,
    cardShape: Shape,
    coverWidthPx: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    /** 卡片名称隐藏【】/[] 标签（应用设置「卡片隐藏名称标签」）。 */
    hideTitleTag: Boolean = false,
    /** 左上角引擎类型角标（应用设置「卡片引擎角标」，默认关）。 */
    showEngineBadge: Boolean = false,
) {
    val engineName = gameCardEngineName(game)
    // 瀑布流：按游戏 uri 稳定取 0.60–0.80 的封面宽高比，让同一列数下的卡片高低错落。
    // 解码按这个比例裁剪，避免先把图裁成 3:4 再被卡片盒二次裁切。
    val coverAspect = remember(game.uri) {
        val hash = game.uri.hashCode().let { if (it == Int.MIN_VALUE) 0 else kotlin.math.abs(it) }
        0.60f + (hash % 21) / 100f
    }
    val coverTier = remember(coverWidthPx, coverAspect) { CoverDecodeTier.forCoverBox(coverWidthPx, coverAspect) }
    val coverBitmap by rememberCoverBitmap(
        coverUri = game.coverUri,
        tier = coverTier,
        targetAspect = coverAspect,
    )
    // 删除消散中：登记在 dissolvingGames 里的卡片播放消散效果，期间不再响应点击
    val dissolve = rememberLastNonNull(dissolvingGames[game.uri])
    val pressModifier = if (onLongClick != null) {
        Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
    } else {
        Modifier.clickable(onClick = onClick)
    }
    val bmp = coverBitmap
    val reveal = rememberCoverReveal(bmp != null, CoverSurface.Waterfall)
    Column(
        modifier
            .gameRemovalDust(dissolve)
            .pressSquish(scale = PressSquishDefaults.CardScale, charge = onLongClick != null)
            .clip(cardShape)
            .dissolveTint(dissolve, MaterialTheme.colorScheme.error)
            .background(NavWhite)
            .glassBorder(shape = cardShape)
            .then(pressModifier),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(coverAspect)
                .background(game.engine.coverColor()),
            contentAlignment = Alignment.Center,
        ) {
            // 位图被 LRU 逐出后重新进入视口：已知该封面存在，先留底色等重解码，不再闪占位文字
            if (bmp == null && !isCoverKnown(game.coverUri)) GameCoverPlaceholder(engineName)
            if (bmp != null) {
                Image(
                    bitmap = bmp,
                    contentDescription = game.title,
                    contentScale = ContentScale.Crop,
                    // 封面加载完成：淡入 + 由糊变清（已缓存的封面一开始就是 1，不会闪）
                    modifier = reveal.image(Modifier.fillMaxSize()),
                )
                // 左上角引擎类型角标：开关开启且实际渲染封面的卡片才显示（无封面占位卡不显示）
                BlurVisibility(showEngineBadge, modifier = Modifier.align(Alignment.TopStart)) {
                    GameCoverEngineBadge(engine = game.engine, reveal = reveal)
                }
            }
        }
        // 封面下方延伸的白色信息区（不覆盖封面）：最多两行标题，小号字体、水平居中；
        // 字号缩小后同步收紧行高与纵向内边距，避免白色区域显得过高
        val displayTitle = if (hideTitleTag) GameTitleTags.stripAll(game.title) else game.title
        Text(
            displayTitle,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = 12.sp,
                lineHeight = 15.sp,
            ),
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            // 改名 / 切换「隐藏名称标签」后标题轻弹一下作为落点（首次组合不弹；常数开销的修饰符节点）
            modifier = Modifier
                .bounceOnChange(displayTitle, depth = 0.06f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/**
 * 游戏页「网格」卡片风格：恢复瀑布流之前的等高网格（3:4 封面 + 封面下方单行标题）。
 * 列数、封面加载优化、名称标签与角标设置与瀑布流一致。
 */
@Composable
internal fun UniformGameGrid(
    games: List<ScanGame>,
    cardShape: Shape,
    columns: Int,
    gridState: LazyGridState,
    onGameClick: (ScanGame) -> Unit,
    onGameLongClick: (ScanGame) -> Unit,
) = GameGridCoverWidth(columns, staggered = false) { coverWidthPx ->
    // 液态玻璃导航悬浮时不占布局：列表底部预留导航高度（与瀑布流一致）
    val glassBottomInset = glassNavBottomInset()
    val hideTitleTag by AppSettingsStore.gameCardTitleTagState.collectSettingAsState()
    val showEngineBadge by AppSettingsStore.gameCardBadgeState.collectSettingAsState()
    // 滚动果冻：快速滚动时卡片沿滚动方向轻微拉长，停下 Q 弹复原（「普通」档不挂）
    val gridJelly = rememberScrollJellyState()
    val coverTier = CoverDecodeTier.forWidth(coverWidthPx)
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        state = gridState,
        modifier = Modifier.fillMaxSize().scrollJellyHost(gridJelly),
        contentPadding = PaddingValues(
            start = GameGridSideInset, end = GameGridSideInset, top = 12.dp, bottom = 12.dp + glassBottomInset,
        ),
        horizontalArrangement = Arrangement.spacedBy(GameGridColumnGap),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        gridItemsIndexed(
            items = games,
            key = { _, game -> gameCardItemKey(game) },
            contentType = { _, _ -> "game_card" },
        ) { index, game ->
            UniformGameCard(
                // 搜索 / 排序 / 增删时卡片位置弹簧过渡；首屏依次弹入（与瀑布流一致）
                modifier = listItemMotion(ListItemPlacement.Reflow).staggeredEntrance(index)
                    .scrollJelly(gridJelly, strength = 0.03f),
                game = game,
                cardShape = cardShape,
                coverTier = coverTier,
                onClick = { onGameClick(game) },
                onLongClick = { onGameLongClick(game) },
                hideTitleTag = hideTitleTag,
                showEngineBadge = showEngineBadge,
            )
        }
    }
}

/**
 * 「网格」卡片：3:4 封面卡片 + 封面下方单行标题（标题在卡片表面外，沿页面背景）；
 * 点按/长按作用于整张卡片（封面 + 标题）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun UniformGameCard(
    game: ScanGame,
    cardShape: Shape,
    coverTier: CoverDecodeTier,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    /** 卡片名称隐藏【】/[] 标签（应用设置「卡片隐藏名称标签」）。 */
    hideTitleTag: Boolean = false,
    /** 左上角引擎类型角标（应用设置「卡片引擎角标」，默认关）。 */
    showEngineBadge: Boolean = false,
) {
    val engineName = gameCardEngineName(game)
    val coverBitmap by rememberCoverBitmap(game.coverUri, tier = coverTier)
    // 删除消散中：登记在 dissolvingGames 里的卡片播放消散效果，期间不再响应点击
    val dissolve = rememberLastNonNull(dissolvingGames[game.uri])
    val pressModifier = if (onLongClick != null) {
        Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
    } else {
        Modifier.clickable(onClick = onClick)
    }
    val bmp = coverBitmap
    val reveal = rememberCoverReveal(bmp != null, CoverSurface.Grid)
    val displayTitle = if (hideTitleTag) GameTitleTags.stripAll(game.title) else game.title
    // 整列参与点击：封面与下方标题都可打开操作抽屉 / 长按弹窗；整列一起 Q 弹按压
    Column(
        modifier
            .gameRemovalDust(dissolve)
            .pressSquish(scale = PressSquishDefaults.CardScale, charge = onLongClick != null)
            .then(pressModifier),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .clip(cardShape)
                .dissolveTint(dissolve, MaterialTheme.colorScheme.error)
                .background(game.engine.coverColor())
                .glassBorder(shape = cardShape),
            contentAlignment = Alignment.Center,
        ) {
            // 位图被 LRU 逐出后重新进入视口：已知该封面存在，先留底色等重解码，不再闪占位文字
            if (bmp == null && !isCoverKnown(game.coverUri)) GameCoverPlaceholder(engineName)
            if (bmp != null) {
                Image(
                    bitmap = bmp,
                    contentDescription = game.title,
                    contentScale = ContentScale.Crop,
                    // 封面加载完成：淡入 + 由糊变清（已缓存的封面一开始就是 1，不会闪）
                    modifier = reveal.image(Modifier.fillMaxSize()),
                )
                BlurVisibility(showEngineBadge, modifier = Modifier.align(Alignment.TopStart)) {
                    GameCoverEngineBadge(engine = game.engine, reveal = reveal)
                }
            }
        }
        // 旧网格风格：标题在封面下方单行显示，不使用白色信息区
        Text(
            displayTitle,
            style = MaterialTheme.typography.bodyMedium,
            color = PageTextColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            // 改名 / 切换「隐藏名称标签」后标题轻弹一下作为落点（首次组合不弹）
            modifier = Modifier
                .bounceOnChange(displayTitle, depth = 0.06f)
                .fillMaxWidth()
                .padding(top = 4.dp),
        )
    }
}

/** 卡片无封面占位使用的引擎显示名：Switch / Plugin 等短名处理，两种卡片共用。 */
@Composable
internal fun gameCardEngineName(game: ScanGame): String = when (game.engine) {
    EngineType.UNKNOWN -> stringResource(R.string.engine_name_unknown)
    // 无封面占位卡用短名「Switch」，避免「Nintendo Switch」过长
    EngineType.NINTENDO_SWITCH -> stringResource(R.string.engine_name_switch)
    // 第三方运行时游戏在游戏库中统一用 Plugin 表示引擎；模块名只在「引擎模块管理」页展示。
    EngineType.RUNTIME_MODULE -> stringResource(R.string.engine_name_runtime_module)
    else -> game.engine.displayName
}

/** 无封面占位（Tyranor + 引擎名）：瀑布流、网格与操作抽屉档案卡共用。 */
@Composable
internal fun GameCoverPlaceholder(engineName: String) {
    Column(
        modifier = Modifier.padding(horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Tyranor",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            engineName,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** 封面左上角引擎类型角标：瀑布流与网格两种卡片共用（labelSmall 属 AGENTS.md 明文豁免）。 */
@Composable
private fun GameCoverEngineBadge(
    engine: EngineType,
    reveal: CoverReveal,
    modifier: Modifier = Modifier,
) {
    Text(
        engine.abbr,
        style = MaterialTheme.typography.labelSmall,
        // 角标样式固定（半透明黑底 + 白字，见 theme/Color.kt），不随主题色变化
        color = CoverBadgeText,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .padding(3.dp)
            .graphicsLayer { alpha = reveal.alpha }
            .clip(AppComponentShape)
            .background(CoverBadgeBackground)
            .padding(horizontal = 4.dp, vertical = 1.dp),
    )
}

/**
 * 「该封面曾经成功解码并显示过」的轻量标记（只存 uri，不存位图，不受 LRU 逐出影响）。
 * 滚动回来、位图已被 LRU 挤出时用它避免卡片先闪回占位兜底再等重新解码。
 *
 * 每个 uri 持有独立的快照状态：卡片在组合期只读自己那一个，`forget` 只令对应卡片立即重组；
 * 若换成全局可观察集合，任意一张封面解码成功都会让整屏卡片一起重组。
 */
internal object CoverAvailability {
    private val known = java.util.concurrent.ConcurrentHashMap<String, MutableState<Boolean>>()

    fun markKnown(uri: String) {
        if (uri.isBlank()) return
        known.computeIfAbsent(uri) { mutableStateOf(true) }.value = true
    }

    /** 撤销登记：封面文件被删 / 损坏、解码返回 null 时调用，让对应卡片立即重组回占位兜底。 */
    fun forget(uri: String) {
        if (uri.isBlank()) return
        // 同一 uri 可能被不同档位 / 宽高比的消费者并发解码：另一个消费者刚成功写入缓存时，
        // 本次失败不能撤销它的登记。已有任一份解码结果就保持「已显示过」。
        if (CoverDecodedKeys.hasAnyDecoded(uri)) return
        // 先写 false 通知当前卡片重组（快照失效已入队），再移除条目，避免失败 uri 无界累积；
        // 之后重新解码成功时 markKnown 会重建状态。
        known[uri]?.value = false
        known.remove(uri)
    }

    fun isKnown(uri: String): Boolean = known[uri]?.value == true
}

/**
 * 已解码封面的「缓存 key 存在过」标记（纯 Kotlin，不触碰 Android LruCache）：
 * `CoverAvailability.forget` 用它确认同一 uri 的其它档位 / 宽高比是否刚解码成功，避免一次失败
 * 撤销另一个消费者刚建立的可用登记；LruCache 逐出时同步撤销。
 */
private object CoverDecodedKeys {
    // uri → 该 uri 当前已解码的 cacheKey 集合。按 uri 精确索引，避免用 endsWith 后缀
    // 判断时把「另一个 uri 恰好以本 uri 结尾」误判成同一张图。
    private val keysByUri = java.util.concurrent.ConcurrentHashMap<String, MutableSet<String>>()

    fun mark(cacheKey: String) {
        val uri = uriOf(cacheKey) ?: return
        keysByUri.compute(uri) { _, existing ->
            (existing ?: java.util.concurrent.ConcurrentHashMap.newKeySet<String>()).apply {
                add(cacheKey)
            }
        }
    }

    fun unmark(cacheKey: String) {
        val uri = uriOf(cacheKey) ?: return
        keysByUri.computeIfPresent(uri) { _, existing ->
            existing.remove(cacheKey)
            existing.ifEmpty { null }
        }
    }

    fun hasAnyDecoded(uri: String): Boolean = keysByUri[uri]?.isNotEmpty() == true

    /** cacheKey 格式固定为 `tier:a<aspect>:<uri>`；uri 自身含 `:`，只切前两段。 */
    private fun uriOf(cacheKey: String): String? {
        val first = cacheKey.indexOf(':')
        if (first <= 0) return null
        val second = cacheKey.indexOf(':', first + 1)
        if (second <= first) return null
        return cacheKey.substring(second + 1).takeIf { it.isNotEmpty() }
    }
}

/** 该封面是否已成功显示过（调用方据此跳过占位兜底，避免"显示过又变回兜底"）。 */
internal fun isCoverKnown(coverUri: String?): Boolean =
    coverUri != null && CoverAvailability.isKnown(coverUri)

/** 不感知滚动的默认查询（首页/封面流等非网格调用方复用，避免默认参数每次新建 lambda）。 */
/** 读取游戏封面。缓存命中时直接出图，未命中时在后台解码并在完成后更新状态。 */
@Composable
internal fun rememberCoverBitmap(
    coverUri: String?,
    tier: CoverDecodeTier = CoverDecodeTier.GRID,
    /** 消费方盒子的宽高比（宽/高）；默认按档位的 3:4。会按 0.05 分桶后进入缓存 key。 */
    targetAspect: Float? = null,
): androidx.compose.runtime.State<ImageBitmap?> {
    val context = LocalContext.current
    val aspect = quantizedCoverAspect(targetAspect ?: tier.defaultAspect)
    val cacheKey = coverUri?.let { tier.cacheKey(it, aspect) }
    val cached = cacheKey?.let(CoverBitmapCache::get)
    val cachedImage = remember(cached) { cached?.asImageBitmap() }
    return produceState<ImageBitmap?>(initialValue = cachedImage, coverUri, tier, aspect) {
        val uri = coverUri
        if (uri.isNullOrBlank()) {
            value = null
            return@produceState
        }
        if (cachedImage != null) {
            // 缓存命中也要登记并显式写入当前 state value：当 coverUri 由 null 变为已缓存非空 URI 时，
            // produceState 的 MutableState 沿用旧值而不会因 initialValue 自动重置
            CoverAvailability.markKnown(uri)
            value = cachedImage
            return@produceState
        }
        val bitmap = CoverThumbnailLoader.load(context.applicationContext, uri, tier, aspect)
        if (bitmap == null) {
            // 已登记过的封面文件可能被删 / 损坏：撤销登记，否则卡片只剩底色、
            // 占位文字在重启前不会再出现；下次仍会重新尝试解码
            CoverAvailability.forget(uri)
            value = null
            return@produceState
        }
        CoverAvailability.markKnown(uri)
        value = bitmap.asImageBitmap()
    }
}

@Composable
private fun rememberCandidateCoverPreview(candidate: CoverSearchCandidate): androidx.compose.runtime.State<CoverPreviewState> {
    val context = LocalContext.current
    val cacheKey = candidate.coverUrl
    // 搜索结果使用 GRID 档；缓存 key 带档位与宽高比，避免与其它消费方互相覆盖
    val cacheKeyWithTier = cacheKey.takeIf { it.isNotBlank() }?.let { uri ->
        CoverDecodeTier.GRID.cacheKey(uri)
    }
    val cached = cacheKeyWithTier?.let(CoverBitmapCache::get)
    val initialState = cached?.asImageBitmap()?.let(CoverPreviewState::Ready)
        ?: CoverPreviewState.Loading
    return produceState<CoverPreviewState>(initialValue = initialState, cacheKey, candidate.source) {
        if (cached != null) return@produceState
        if (cacheKeyWithTier == null) {
            value = CoverPreviewState.Failed
            return@produceState
        }
        value = withContext(Dispatchers.IO) {
            val uri = CoverImageCache.download(
                context = context,
                imageUrl = cacheKey,
                prefix = "preview_${stableKey("${candidate.source}:$cacheKey")}",
                source = candidate.source,
                persistent = false,
            )
            val bitmap = uri?.let { decodeCoverThumbnail(context, it, CoverDecodeTier.GRID, CoverDecodeTier.GRID.defaultAspect) }
            if (bitmap != null) {
                CoverBitmapCache.putDecoded(cacheKeyWithTier, bitmap)
                CoverPreviewState.Ready(bitmap.asImageBitmap())
            } else {
                CoverPreviewState.Failed
            }
        }
    }
}

private sealed interface CoverPreviewState {
    data object Loading : CoverPreviewState
    data object Failed : CoverPreviewState
    data class Ready(val bitmap: ImageBitmap) : CoverPreviewState
}

/** 封面只按消费档位与盒子宽高比解码，避免切页时上传原始大图；已解码缩略图跨页面复用。 */
private fun decodeCoverThumbnail(
    context: android.content.Context,
    uriText: String,
    tier: CoverDecodeTier,
    targetAspect: Float,
): Bitmap? = runCatching {
    val uri = android.net.Uri.parse(uriText)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

    // 采样同时受「不缩到档位目标以下」与「解码字节 ≤ 预算」约束；精确尺寸由 scaleCoverToAspect 完成。
    // 采样估算按 4 字节/像素，实际 byteCount 还含行对齐：解码后再兜底检查，超出预算就再降一档。
    var sampleSize = coverSampleSize(bounds.outWidth, bounds.outHeight, tier)
    var attempts = 0
    var decoded: Bitmap? = null
    while (true) {
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val candidate = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: return@runCatching null
        if (candidate.byteCount <= CoverDecodeBudgetBytes) {
            decoded = candidate
            break
        }
        candidate.recycle()
        if (++attempts > CoverResampleAttempts) return@runCatching null
        sampleSize *= 2
    }
    scaleCoverToAspect(decoded, tier, targetAspect)
}.getOrNull()

/** 解码后按真实 byteCount 超预算时的最大重采样次数。 */
private const val CoverResampleAttempts = 3

/**
 * 把采样后的位图按消费方盒子的宽高比居中裁剪，再缩放到档位盒内（小图不放大）。
 * 卡片本身用 `ContentScale.Crop`，预先按盒子比例裁剪不会丢失可见区域，同时让 LRU
 * 容量估算与真实显示尺寸一致。
 *
 * 中间位图在不再被目标引用后立即 `recycle()`：采样结果与裁剪副本最多同时存在一帧，
 * 之后只保留最终位图。
 */
private fun scaleCoverToAspect(decoded: Bitmap, tier: CoverDecodeTier, targetAspect: Float): Bitmap {
    val crop = coverCropRect(decoded.width, decoded.height, targetAspect) ?: return decoded
    val cropped = if (crop.width == decoded.width && crop.height == decoded.height) {
        decoded
    } else {
        Bitmap.createBitmap(decoded, crop.left, crop.top, crop.width, crop.height)
    }
    val (targetWidth, targetHeight) = coverTargetSize(tier, targetAspect)
    val scaled = if (cropped.width <= targetWidth && cropped.height <= targetHeight) {
        // 小图不放大：保留原生尺寸（仍 ≤ 目标盒），避免无意义的纹理上传与模糊放大
        cropped
    } else {
        Bitmap.createScaledBitmap(cropped, targetWidth, targetHeight, true)
    }
    // 目标是唯一保留的位图：所有未被目标引用的中间位图（裁剪副本 / 采样源）都回收
    if (cropped !== scaled) cropped.recycle()
    if (decoded !== scaled && decoded !== cropped) decoded.recycle()
    return scaled
}

/**
 * 封面缩略图内存缓存。
 *
 * 容量按进程可用堆的 1/6 计算（下限 32MB、上限 128MB）：固定的 24MB 在 5 列以上或几十款游戏时
 * 不足两屏，`LruCache` 会把"刚显示过"的封面挤出去，滚回来只能重新解码——表现为封面反复闪回占位。
 * `sizeOf` 用真实分配字节数（含行对齐），避免估算偏差导致实际占用远超预算。
 */
private object CoverBitmapCache : LruCache<String, Bitmap>(coverCacheBytes()) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount

    /** 登记并写入 key：先登记，若 put 因超预算立即逐出，entryRemoved 会同步撤销。 */
    fun putDecoded(key: String, value: Bitmap) {
        CoverDecodedKeys.mark(key)
        put(key, value)
    }

    override fun entryRemoved(evicted: Boolean, key: String, oldValue: Bitmap, newValue: Bitmap?) {
        // 只有逐出 / 显式移除（newValue == null）才撤销；put 替换同一 key 时标记继续有效
        if (newValue == null) CoverDecodedKeys.unmark(key)
    }
}

private fun coverCacheBytes(): Int {
    val heap = Runtime.getRuntime().maxMemory()
    return (heap / 6).coerceIn(32L * 1024 * 1024, 128L * 1024 * 1024).toInt()
}

/**
 * 限制并发解码，避免游戏页首次组合时多个大图同时抢占 CPU/内存；相同 URI 共用一个任务。
 * 第二批等待者在获得许可后会再次检查缓存，进一步避免排队期间的重复解码。
 */
private object CoverThumbnailLoader {
    private val coordinator = BoundedKeyedLoader<String>(parallelism = 4)

    suspend fun load(
        context: android.content.Context,
        uriText: String,
        tier: CoverDecodeTier,
        targetAspect: Float,
    ): Bitmap? {
        val cacheKey = tier.cacheKey(uriText, targetAspect)
        return coordinator.load(
            key = cacheKey,
            cached = { CoverBitmapCache.get(cacheKey) },
        ) {
            withContext(Dispatchers.IO) {
                decodeCoverThumbnail(context, uriText, tier, targetAspect)?.also {
                    ensureActive()
                    // 带 mipmap：封面在书架上经 3D 透视、侧转缩小显示，没有 mipmap 时细密的线稿 / 网点会出现闪烁的噪点（摩尔纹）
                    it.setHasMipMap(true)
                    CoverBitmapCache.putDecoded(cacheKey, it)
                }
            }
        }
    }
}

/**
 * 每个 key 串行、不同 key 最多 [parallelism] 路并发。调用者取消只释放自己的锁，后续等待者
 * 会重新检查缓存并继续加载，不共享由首个 UI 协程拥有的 Deferred，因此不会被取消污染。
 */
internal class BoundedKeyedLoader<K : Any>(parallelism: Int) {
    private val permits = Semaphore(parallelism)
    private val keyLocks = ConcurrentHashMap<K, Mutex>()

    suspend fun <V> load(key: K, cached: () -> V?, loader: suspend () -> V?): V? {
        val keyLock = keyLocks.getOrPut(key) { Mutex() }
        return keyLock.withLock {
            cached() ?: permits.withPermit {
                cached() ?: loader()
            }
        }
    }
}

internal fun EngineType.coverColor(): Color = when (this) {
    EngineType.KIRIKIRI -> Color(0xFF3B5998)
    EngineType.ONS -> Color(0xFF43A047)
    EngineType.TYRANO -> Color(0xFFC6443C)
    EngineType.RPGMAKER -> Color(0xFF8D6E63)
    EngineType.RPG_MV -> Color(0xFF2E7D6E)
    EngineType.RPG_MZ -> Color(0xFF1976D2)
    EngineType.VN -> Color(0xFF8E5A9E)
    EngineType.WEB_OTHER -> Color(0xFF546E7A)
    EngineType.ARTEMIS -> Color(0xFF7E57C2)
    EngineType.SIGLUS -> Color(0xFF00838F)
    EngineType.REALLIVE -> Color(0xFF00695C)
    EngineType.AVG32 -> Color(0xFF8E7CC3)
    EngineType.XSYS35 -> Color(0xFF8B3A5E)
    EngineType.XSYS4 -> Color(0xFF00A176)
    EngineType.UK2 -> Color(0xFF4E6E81)
    EngineType.FVP -> Color(0xFFB05A2A)
    EngineType.CMVS -> Color(0xFF5B4B8A)
    EngineType.ETHORNELL -> com.tyranor.next.theme.EthornellCoverColor
    EngineType.YURIS -> Color(0xFF558B2F)
    EngineType.CATSYSTEM2 -> Color(0xFF6D4C41)
    EngineType.PC -> Color(0xFF455A64)
    EngineType.ANDROID_APP -> Color(0xFF2E7D32)
    EngineType.RENPY -> Color(0xFFE35B84)
    EngineType.RUFFLE -> Color(0xFFC62828)
    EngineType.IMPACTO -> Color(0xFFB3123A)
    EngineType.EASYRPG -> Color(0xFF1B7A43)
    EngineType.RUNTIME_MODULE -> Color(0xFF5C6BC0)
    EngineType.PSP -> Color(0xFF6D4C9F)
    EngineType.NINTENDO_SWITCH -> Color(0xFFD32F2F)
    EngineType.SYSTEMNNN, EngineType.UNKNOWN -> Color(0xFF607D8B)
}

internal fun shouldShowSaveManagement(engine: EngineType, sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
    // YU-RIS / EasyRPG 虽经外置应用启动，但存档落在游戏目录（save/ 或 saves/），纳入统一存档管理；
    // 手动添加的类型（PC / 安卓游戏）无统一存档接口，显式排除。
    engine == EngineType.YURIS ||
        engine == EngineType.EASYRPG ||
        (engine == EngineType.RENPY &&
            com.tyranor.next.core.game.save.GameSaveManager.supportsFileSaves(engine, sdkInt)) ||
        (!engine.isManual &&
            engine != EngineType.RUNTIME_MODULE &&
            engine != EngineType.UNKNOWN &&
            (engine != EngineType.ETHORNELL ||
                com.tyranor.next.core.game.save.EthornellSaveCapability.available) &&
            !ExternalEngineModuleRegistry.isExternalEngine(engine) &&
            ExternalEmulatorRegistry.forEngine(engine) == null)

/** 记录级判定：engine 字段损坏的安卓条目（uri 仍为 androidapp://）同样不显示存档管理。 */
internal fun shouldShowSaveManagement(game: ScanGame): Boolean =
    !AndroidAppGames.isAndroidApp(game) && shouldShowSaveManagement(game.engine)
