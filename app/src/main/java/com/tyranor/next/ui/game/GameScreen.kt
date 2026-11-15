package com.tyranor.next.ui.game

import com.tyranor.next.ui.common.motion.CoverSurface
import com.tyranor.next.ui.common.motion.CoverReveal
import com.tyranor.next.ui.common.motion.listItemMotion
import com.tyranor.next.ui.common.motion.ListItemPlacement
import com.tyranor.next.ui.common.motion.rememberCoverReveal
import com.tyranor.next.ui.common.motion.startActivityWithPageTransition
import com.tyranor.next.ui.common.motion.rememberMotionSheetController
import com.tyranor.next.ui.common.motion.BlurCrossfade
import com.tyranor.next.ui.common.motion.BlurVisibility
import com.tyranor.next.ui.common.motion.ExpandVisibility
import com.tyranor.next.ui.common.motion.dissolve
import com.tyranor.next.ui.common.motion.dissolveTint
import com.tyranor.next.ui.common.motion.dissolvingGames
import com.tyranor.next.ui.common.motion.PressSquishDefaults
import com.tyranor.next.ui.common.motion.ProvideEntrance
import com.tyranor.next.ui.common.motion.skipEntranceOnDrag
import com.tyranor.next.ui.common.motion.pressSquish
import com.tyranor.next.ui.common.motion.staggeredEntrance
import androidx.compose.foundation.lazy.staggeredgrid.itemsIndexed as staggeredGridItemsIndexed
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
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
import com.tyranor.next.core.game.launch.EngineLauncher
import com.tyranor.next.core.game.manual.AndroidAppGames
import com.tyranor.next.core.game.storage.GameLibraryFacade
import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.engine.runtime.RuntimeModuleBindings
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.saveable.Saver
import com.tyranor.next.core.engine.external.EmulatorLaunchStyle
import com.tyranor.next.core.engine.external.ExternalEmulatorRegistry
import com.tyranor.next.core.engine.external.ExternalEngineModuleRegistry
import com.tyranor.next.core.game.save.RpgSaveFormat
import com.tyranor.next.core.game.save.sync.SaveSyncService
import com.tyranor.next.core.game.model.GameTitleTags
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.game.model.filterGames
import com.tyranor.next.core.game.model.GameSortKeys
import com.tyranor.next.core.game.model.sortGames
import com.tyranor.next.core.cover.persistentCoverDir
import com.tyranor.next.core.cover.stableKey
import com.tyranor.next.core.settings.AppSettingsStore
import com.tyranor.next.core.auth.HikarinagiAuthStore
import com.tyranor.next.core.settings.PerGameSettingsStore
import com.tyranor.next.theme.AppThemeColors
import com.tyranor.next.theme.DialogItemSurface
import com.tyranor.next.theme.GlassPanel
import com.tyranor.next.theme.MilkGlassPanelBackground
import com.tyranor.next.theme.MiuixSettingsTheme
import com.tyranor.next.theme.NavWhite
import com.tyranor.next.theme.NavWhiteOverlay
import com.tyranor.next.theme.PageSecondaryTextColor
import com.tyranor.next.theme.PageTextColor
import com.tyranor.next.theme.TextColor
import com.tyranor.next.theme.glassBorder
import com.tyranor.next.theme.AppComponentShape
import com.tyranor.next.theme.CoverBadgeText
import com.tyranor.next.theme.CoverBadgeBackground
import com.tyranor.next.ui.common.AppAlertDialog
import com.tyranor.next.ui.common.AppBottomSheet
import com.tyranor.next.ui.common.NoRippleButton
import com.tyranor.next.ui.common.AppDialogMaxWidth
import com.tyranor.next.ui.common.isTabletScreen
import com.tyranor.next.ui.common.AppNavItem
import com.tyranor.next.ui.common.AppSearchField
import com.tyranor.next.ui.common.AppTopBar
import com.tyranor.next.ui.common.DialogTextButton
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
import com.tyranor.next.ui.patch.KrkrOnlinePatchActivity
import com.tyranor.next.ui.save.SaveManagementActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.lazy.itemsIndexed as lazyItemsIndexed
import androidx.lifecycle.compose.LocalLifecycleOwner
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
    // 卡片长按的操作弹窗目标：只存 uri，每次从最新列表解析（改名/封面/启动文件更新后仍是新对象）
    var quickActionsGameUri by rememberSaveable { mutableStateOf<String?>(null) }
    val quickActionsGame = remember(games, quickActionsGameUri) {
        quickActionsGameUri?.let { uri -> games.firstOrNull { it.uri == uri } }
    }

    val scrapeTaskState by CoverScrapeTaskManager.state.collectAsState()

    LaunchedEffect(libraryState.loaded, games, selectedGameUri, quickActionsGameUri) {
        if (!libraryState.loaded) return@LaunchedEffect
        selectedGameUri?.let { uri -> if (games.none { it.uri == uri }) selectedGameUri = null }
        quickActionsGameUri?.let { uri -> if (games.none { it.uri == uri }) quickActionsGameUri = null }
    }

    LaunchedEffect(libraryState.scrapeEventId, libraryState.scrapeMessage) {
        val message = libraryState.scrapeMessage ?: return@LaunchedEffect
        AppNotice.makeText(context, message, android.widget.Toast.LENGTH_SHORT, AppNotice.Kind.Info).show()
        onScrapeEventShown(libraryState.scrapeEventId)
    }

    fun replaceGame(updated: ScanGame) {
        onGameUpdated(updated)
    }

    fun deleteGame(target: ScanGame) {
        if (selectedGameUri == target.uri) selectedGameUri = null
        if (quickActionsGameUri == target.uri) quickActionsGameUri = null
        scope.removeLibraryGame(target.uri) { onGameDeleted(target) }
    }

    fun syncMissingCovers() {
        if (libraryState.scanning || scrapeTaskState.running) return
        if (!CoverScrapeTaskManager.start(context, games)) {
            AppNotice.makeText(context, batchScrapeRunningMessage, android.widget.Toast.LENGTH_SHORT, AppNotice.Kind.Limit).show()
        }
    }

    // 扫描游戏库：每次按扫描目录全量重建，删除/改名/移动后的旧缓存条目会被清理。
    fun scanLibrary() {
        if (libraryState.scanning || scrapeTaskState.running) return
        onScanLibrary()
    }

    val dirPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { u ->
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    u,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            // 保存根目录后立即全量扫描
            GameLibraryFacade.saveRoot(context, u)
            scanLibrary()
        }
    }

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
        onGameLongClick = { game -> quickActionsGameUri = game.uri },
        dbSearchQuery = libraryState.searchQuery,
        dbSearchHitUris = libraryState.searchHitUris,
        confirmedEngineTypes = libraryState.confirmedEngineTypes,
        onAddManualGame = onAddManualGame,
        onCoachAnchorChanged = onCoachAnchorChanged,
        onSearchQueryChanged = onSearchQueryChanged,
        isActive = isActive,
    )

    // ===== 点击游戏卡片的底部抽屉栏（启动相关动作与设置/存档/补丁入口） =====
    selectedGame?.let { game ->
        key(game.uri) {
            GameActionsSheet(
                game = game,
                onDismiss = { selectedGameUri = null },
                onGameUpdated = { replaceGame(it) },
                onEngineSettings = {
                    openPerGameSettingsFromSheet(context, game) { selectedGameUri = null }
                },
            )
        }
    }

    // ===== 长按游戏卡片：统一操作弹窗（快捷启动 / 桌面快捷方式 / 改名 / 修改封面 / 删除） =====
    quickActionsGame?.let { game ->
        GameQuickActionsDialog(
            game = game,
            quickLaunched = libraryState.quickLaunch.any { it.uri == game.uri },
            onDismiss = { quickActionsGameUri = null },
            onGameUpdated = { replaceGame(it) },
            onDeleteGame = { deleteGame(game) },
            onQuickLaunchToggle = { onQuickLaunchToggle(game) },
        )
    }
}

/**
 * 清理应用内关联数据，不触碰游戏目录。清理失败不再阻拦删除：调用方先移除游戏库条目，
 * 这里的异常仅用于记录日志；各清理步骤相互独立，单项失败不影响其余项。
 */
internal suspend fun cleanupDeletedGame(context: android.content.Context, target: ScanGame) {
    val failures = ArrayList<Exception>()
    suspend fun attempt(action: suspend () -> Unit) {
        try {
            action()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            failures += failure
        }
    }
    attempt { SaveSyncService(context).clearGame(target) }
    withContext(NonCancellable) {
        attempt { PerGameSettingsStore.clear(context, target.uri) }
        attempt { GameLibraryFacade.removeRecentGame(context, target.uri) }
        attempt { GameLibraryFacade.removeQuickLaunch(context, target.uri) }
        attempt { deleteCoverFile(context, target.coverUri) }
    }
    if (failures.isNotEmpty()) {
        throw failures.first().also { first -> failures.drop(1).forEach(first::addSuppressed) }
    }
}

private fun deleteCoverFile(context: android.content.Context, coverUri: String?) {
    if (coverUri.isNullOrBlank()) return
    val file = runCatching { File(android.net.Uri.parse(coverUri).path ?: return) }.getOrNull() ?: return
    val coverDir = persistentCoverDir(context).canonicalPath
    if (runCatching { file.canonicalPath }.getOrNull()?.startsWith(coverDir) == true) {
        file.delete()
    }
}

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
) {
    // 「添加游戏」分支流程：null=关闭；CHOICE=分支弹窗，PC/ANDROID=对应二级添加弹窗
    var addGameStep by rememberSaveable { mutableStateOf<AddGameStep?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    // 动效：扫描 / 刮削在忙时再点被拒绝 → 图标抖一下（只改绘制图层）
    var scanRejects by remember { mutableIntStateOf(0) }
    var coverRejects by remember { mutableIntStateOf(0) }
    var showFilterSheet by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val conditions by AppSettingsStore.gameConditions.collectSettingAsState()
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
    val availableEngines = remember(games) { games.map { it.engine }.distinct().sortedBy { it.ordinal } }
    val selectedEngine = conditions.engineFilter
    LaunchedEffect(loaded, scanning, confirmedEngineTypes, availableEngines, selectedEngine) {
        if (loaded && !scanning &&
            conditions.isEngineFilterMissing(availableEngines, confirmedEngineTypes) &&
            AppSettingsStore.gameConditions.value.engineFilter == selectedEngine
        ) {
            AppSettingsStore.setGameFilterEngine(context, AppSettingsStore.GAME_FILTER_ENGINE_ALL)
        }
    }

    val scrapingCoversDescription = stringResource(R.string.game_scraping_covers_content_description)
    val cardStyle by AppSettingsStore.gameCardStyleState.collectSettingAsState()
    // 搜索栏仅在列表顶部显示：离开顶部立即收起，向上滚动过程中不提前展开，滚回顶部才重新出现。
    // 封面流无纵向滚动，始终显示。
    var searchBarVisible by remember { mutableStateOf(true) }
    val searchAndFilterVisible = searchBarVisible || (loaded && !scanning && filteredGames.isEmpty())
    LaunchedEffect(cardStyle, gridState, uniformGridState) {
        searchBarVisible = true
        if (cardStyle == AppSettingsStore.GAME_CARD_STYLE_COVER_FLOW) return@LaunchedEffect
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
                        // 卡片风格切换（瀑布 / 网格 / 封面流）：整体模糊替换（大面积内容自动不做模糊，只淡入淡出与缩放）
                        BlurCrossfade(cardStyle, modifier = Modifier.fillMaxSize()) { style ->
                        when (style) {
                            // 「列表」风格：封面流横向轮播
                            AppSettingsStore.GAME_CARD_STYLE_COVER_FLOW -> key(conditions) {
                                GameCoverFlow(
                                    games = filteredGames,
                                    onGameClick = { if (contentInteractive) onGameClick(it) },
                                    onGameLongClick = { if (contentInteractive) onGameLongClick(it) },
                                )
                            }
                            // 「网格」风格：恢复瀑布流之前的等高网格
                            AppSettingsStore.GAME_CARD_STYLE_GRID -> UniformGameGrid(
                                games = filteredGames,
                                gridState = uniformGridState,
                                onGameClick = { if (contentInteractive) onGameClick(it) },
                                onGameLongClick = { if (contentInteractive) onGameLongClick(it) },
                            )
                            // 「瀑布」风格（默认）
                            else -> GameGrid(
                                games = filteredGames,
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
            onSortChange = { AppSettingsStore.setGameSort(context, it) },
            onEngineChange = { AppSettingsStore.setGameFilterEngine(context, it) },
            onPlayedChange = { AppSettingsStore.setGameFilterPlayed(context, it) },
            onReset = { AppSettingsStore.resetGameConditions(context) },
            onDismiss = { showFilterSheet = false },
        )
    }
}

@Composable
private fun GameFilterActionIcon(
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
                com.tyranor.next.ui.common.NoRippleButton(
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

/** 抽屉内单行动作条目描述；[key] 供 LazyColumn item key 使用。 */
private data class DrawerAction(
    val key: String,
    val title: String,
    val icon: Int,
    val showArrow: Boolean = false,
    val iconTint: Color? = null,
    val onClick: () -> Unit,
)

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
    onEngineSettings: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 「增强」动效档由控制器接管抽屉的展开 / 收起 / 跟手下拉（见 ui/common/motion/SheetMotion.kt）
    val systemNnnLaunch: SystemNnnLaunchViewModel = viewModel()
    val systemNnnRequest = rememberSaveable(game.uri, saver = SystemNnnSheetRequestSaver) { SystemNnnSheetRequest() }
    DisposableEffect(systemNnnRequest) { onDispose { systemNnnRequest.leave() } }
    val sheetMotion = rememberMotionSheetController()
    fun dismissSheet() {
        systemNnnRequest.leave()
        onDismiss()
    }
    val navigation = GameSheetNavigation(sheetMotion, LocalLifecycleOwner.current, ::dismissSheet)
    var launchError by remember(game.uri) { mutableStateOf<LaunchErrorState?>(null) }
    var showCoverSourcePicker by rememberSaveable(game.uri) { mutableStateOf(false) }
    var coverSearchSource by rememberSaveable(game.uri) { mutableStateOf<String?>(null) }
    var coverBinding by remember { mutableStateOf(false) }
    var coverBindError by rememberSaveable(game.uri) { mutableStateOf<String?>(null) }
    var showLaunchFilePicker by rememberSaveable(game.uri) { mutableStateOf(false) }
    var showPatchConfirm by rememberSaveable(game.uri) { mutableStateOf(false) }
    // MV/MZ 存档格式转化确认：待转化检测结果 + 已选定的 Artemis 补丁策略（两弹窗串联时保留）
    var rpgSaveDetection by remember(game.uri) { mutableStateOf<RpgSaveFormat.Detection?>(null) }
    var pendingPatchChoice by remember(game.uri) { mutableStateOf<EngineLauncher.ArtemisPatchChoice?>(null) }
    val launchPreparation = remember(game.uri) { GameLaunchPreparation() }
    val batchScrapeRunningMessage = stringResource(R.string.game_batch_scraping_running)
    val coverDownloadFailedMessage = stringResource(R.string.game_cover_download_failed)
    // 抽屉动作条目的文案/颜色：buildList 的 lambda 内不允许 @Composable 调用，先在组合体取值
    val drawerSearchCoverTitle = stringResource(R.string.game_search_cover)
    val drawerSaveManagementTitle = stringResource(R.string.game_save_management)
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
        if (sheetMotion.closing || sheetMotion.busy ||
            (sheetMotion.sheetState?.let { it.isVisible && it.targetValue == androidx.compose.material3.SheetValue.Hidden } == true)
        ) return@LaunchedEffect
        val result = completion.result ?: return@LaunchedEffect
        launchError = result.toErrorState(context)
        if (launchError == null) navigation.onGameLaunched()
    }

    /** Blocks per-game cover actions while a batch scrape is running. */
    fun isBatchScrapingActive(): Boolean {
        if (!CoverScrapeTaskManager.state.value.running) return false
        AppNotice.makeText(context, batchScrapeRunningMessage, android.widget.Toast.LENGTH_SHORT, AppNotice.Kind.Limit).show()
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
                    AppNotice.makeText(context, message, android.widget.Toast.LENGTH_LONG, AppNotice.Kind.Info).show()
                }
                startLaunch(patchChoice)
            }
        }
    }

    // 抽屉条目遵循页面灰底取色；白雾玻璃面板内改用与页面条目同亮度的面板条目色。
    val drawerItemSurface = when {
        AppThemeColors.isMilkGlass -> DialogItemSurface
        else -> NavWhite
    }

    AppBottomSheet(
        onDismissRequest = ::dismissSheet,
        controller = sheetMotion,
    ) {
        Column(Modifier.fillMaxWidth().weight(1f, fill = false)) {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                contentPadding = PaddingValues(start = 16.dp, top = 6.dp, end = 16.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Text(
                        game.title,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.gameSheetEntrance(0)
                            .fillMaxWidth().padding(start = 4.dp, top = 4.dp, end = 4.dp, bottom = 8.dp),
                    )
                }

                // KRKR 与所有 Winlator 系引擎（YU-RIS / CatSystem2 / PC）支持启动文件切换
                if (
                    game.engine == EngineType.KIRIKIRI ||
                    ExternalEmulatorRegistry.forEngine(game.engine)?.launchStyle == EmulatorLaunchStyle.WINLATOR_EXTERNAL
                ) {
                    item {
                        AppNavItem(
                            title = stringResource(R.string.game_launch_file),
                            summary = game.launchFile ?: stringResource(R.string.game_launch_file_auto_summary),
                            // 启动文件选定后（摘要换成新文件名）整行轻弹一下作为落点反馈
                            modifier = Modifier
                                .gameSheetEntrance(2)
                                .bounceOnChange(game.launchFile ?: "", depth = 0.05f),
                            leadingIcon = R.drawable.ic_sheet_launch_file,
                            containerColor = drawerItemSurface,
                            verticalPadding = 17.dp,
                            showArrow = false,
                            leadingIconTint = MaterialTheme.colorScheme.primary,
                            onClick = { if (sheetMotion.interact()) showLaunchFilePicker = true },
                        )
                    }
                }
                // 所有动作条目统一单个占满一行；卡片管理动作（快捷启动 / 桌面快捷方式 / 改名 /
                // 改封面 / 删除）已统一收口到长按操作弹窗，抽屉只保留启动相关动作与二级页面入口。
                val drawerActions = buildList {
                    add(
                        DrawerAction(
                            key = "search_cover",
                            title = drawerSearchCoverTitle,
                            icon = R.drawable.ic_sheet_search_cover,
                            iconTint = drawerPrimaryColor,
                            onClick = { if (!isBatchScrapingActive()) showCoverSourcePicker = true },
                        ),
                    )
                    if (shouldShowSaveManagement(game)) {
                        add(
                            DrawerAction(
                                key = "save_management",
                                title = drawerSaveManagementTitle,
                                icon = R.drawable.ic_sheet_saves,
                                showArrow = true,
                                iconTint = drawerPrimaryColor,
                                onClick = {
                                    navigation.openDetails {
                                        startActivityWithPageTransition(context, SaveManagementActivity.createIntent(context, game))
                                    }
                                },
                            ),
                        )
                    }
                    if (game.engine == EngineType.KIRIKIRI) {
                        add(
                            DrawerAction(
                                key = "online_patch",
                                title = drawerOnlinePatchTitle,
                                icon = R.drawable.ic_sheet_patch,
                                showArrow = true,
                                iconTint = drawerPrimaryColor,
                                onClick = {
                                    navigation.openDetails {
                                        startActivityWithPageTransition(context, KrkrOnlinePatchActivity.createIntent(context, game))
                                    }
                                },
                            ),
                        )
                    }
                    // 第三方运行时模块没有主 App 侧设置；未知引擎也不展示设置。
                    if (game.engine != EngineType.RUNTIME_MODULE && game.engine != EngineType.UNKNOWN) {
                        add(
                            DrawerAction(
                                key = "engine_settings",
                                title = drawerEngineSettingsTitle,
                                icon = R.drawable.ic_sheet_settings,
                                showArrow = true,
                                iconTint = drawerPrimaryColor,
                                onClick = {
                                    // The caller clears selection in ordinary mode.
                                    navigation.openDetails(ordinaryDismiss = {}, open = onEngineSettings)
                                },
                            ),
                        )
                    }
                }
                drawerActions.forEachIndexed { index, action ->
                    item(key = "drawer_row:" + action.key) {
                        AppNavItem(
                            title = action.title,
                            modifier = Modifier.gameSheetEntrance(3 + index),
                            leadingIcon = action.icon,
                            containerColor = drawerItemSurface,
                            verticalPadding = 17.dp,
                            showArrow = action.showArrow,
                            leadingIconTint = action.iconTint,
                            onClick = {
                                if (sheetMotion.interact()) {
                                    systemNnnRequest.takeResult()
                                    action.onClick()
                                }
                            },
                        )
                    }
                }
            }
            // 主操作固定在抽屉底部：整行长按钮，「启动游戏」文案居中
            NoRippleButton(
                text = stringResource(R.string.game_launch_action),
                modifier = Modifier
                    .gameSheetEntrance(1)
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 22.dp),
                onClick = {
                    if (!(systemNnnLaunch.activeUri != null && RuntimeModuleBindings.builtInSystemNnn(game).engine == EngineType.SYSTEMNNN)
                        && sheetMotion.interact()
                    ) beginLaunch()
                },
            )
        }
    }

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
            onDismiss = { coverSearchSource = null },
            onBind = { candidate, beforeClose ->
                if (!coverBinding && !isBatchScrapingActive()) {
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
                            } catch (_: Throwable) {
                                null
                            }
                        }
                        coverBinding = false
                        if (updated != null) {
                            beforeClose()
                            onGameUpdated(updated)
                            coverSearchSource = null
                            navigation.close()
                        } else {
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
                onGameUpdated(game.copy(launchFile = name))
            },
        )
    }

    launchError?.let { state ->
        LaunchErrorDialog(state = state, onDismiss = { launchError = null })
    }

}

@Composable
private fun CoverSourcePickerDialog(
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    val context = LocalContext.current
    val authVersion by HikarinagiAuthStore.statusVersion.collectAsState()
    val scraperSettingsVersion by AppSettingsStore.coverScraperSettingsVersion.collectSettingAsState()
    val sources = remember(scraperSettingsVersion) {
        AppSettingsStore.getCoverScraperSourceOrder(context)
    }
    val authStatus = remember(authVersion) { HikarinagiAuthStore.getStatus(context) }

    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.game_select_cover_source), style = MaterialTheme.typography.titleMedium) },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                lazyItemsIndexed(
                    sources,
                    key = { _, source -> source },
                    contentType = { _, _ -> "cover_source" },
                ) { index, source ->
                    val enabled = AppSettingsStore.isCoverScraperSourceEnabled(context, source)
                    val needsHikarinagiLogin = source == AppSettingsStore.COVER_SOURCE_HIKARINAGI &&
                        (!authStatus.authorized || authStatus.needsReauth)
                    val selectable = enabled && !needsHikarinagiLogin
                    AppNavItem(
                        title = coverSourceTitle(source),
                        summary = coverSourcePickerSummary(source, enabled, needsHikarinagiLogin),
                        // 来源条目在弹窗正文块内再逐条错峰升起（行程短于正文块，避免叠加过猛）
                        modifier = Modifier.staggeredEntrance(1 + index, distance = 8.dp, stepMillis = 45L),
                        onClick = if (selectable) ({ onSelect(source) }) else null,
                        leadingIcon = R.drawable.ic_cover_source,
                        // 弹窗内条目必须与亮面板形成反差（AGENTS.md 弹窗条目约定）
                        containerColor = DialogItemSurface,
                    )
                }
            }
        },
        confirmButton = { DialogTextButton(text = stringResource(R.string.common_cancel), onClick = onDismiss) },
    )
}

/**
 * Searches one cover source and, where supported, drills into a work's alternate covers.
 * Releases the dialog's touch interception before dismissing or handing a selected cover to its caller.
 */
@Composable
private fun CoverSearchDialog(
    game: ScanGame,
    source: String,
    binding: Boolean,
    bindError: String?,
    onDismiss: () -> Unit,
    onBind: (candidate: CoverSearchCandidate, beforeClose: () -> Unit) -> Unit,
) {
    val context = LocalContext.current
    val coverNoMatchMessage = stringResource(R.string.game_cover_no_match)
    var keyword by rememberSaveable(source, game.uri) { mutableStateOf(game.title) }
    var searching by remember { mutableStateOf(false) }
    var noMatch by rememberSaveable(source, game.uri) { mutableStateOf(false) }
    var errorCode by rememberSaveable(source, game.uri) { mutableStateOf<CoverErrorCode?>(null) }
    var candidates by rememberSaveable(source, game.uri, stateSaver = CoverSearchCandidatesSaver) {
        mutableStateOf(emptyList<CoverSearchCandidate>())
    }
    // 二级（未萌 NextMoe）：作品 → 该作品可获取的封面；其它来源恒为 null
    var drillDownWork by remember(source, game.uri) { mutableStateOf<CoverSearchCandidate?>(null) }
    var workCovers by remember(source, game.uri) { mutableStateOf(emptyList<CoverSearchCandidate>()) }
    var loadingCovers by remember(source, game.uri) { mutableStateOf(false) }
    var coversErrorCode by remember(source, game.uri) { mutableStateOf<CoverErrorCode?>(null) }
    var coversJob by remember(source, game.uri) { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val overlay = rememberGameOverlayMotion(GameOverlay.CoverSearch)
    // 空关键词时按键盘搜索被拒绝：输入框抖一下
    var searchRejects by remember { mutableIntStateOf(0) }

    fun dismiss() = overlay.dismiss(onDismiss)

    /** 离开二级：取消在飞请求并清干净二级状态，避免残留状态或迟到写入影响一级。 */
    fun leaveDrillDown() {
        coversJob?.cancel()
        coversJob = null
        drillDownWork = null
        workCovers = emptyList()
        coversErrorCode = null
        loadingCovers = false
    }

    fun search() {
        val query = keyword.trim()
        if (query.isEmpty() && !searching && !binding) searchRejects++
        if (query.isEmpty() || searching || binding) return
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
        if (loadingCovers || binding) return
        // 上一个二级请求让位：其 finally 不再回写 loading 态（见下方 isActive 守卫）
        coversJob?.cancel()
        coversJob = null
        drillDownWork = work
        workCovers = emptyList()
        coversErrorCode = null
        loadingCovers = true
        coversJob = scope.launch {
            try {
                when (val result = withContext(Dispatchers.IO) {
                    CoverScraperService.searchWorkCovers(context, source, work, 100)
                }) {
                    is CoverSearchResult.Success -> {
                        workCovers = result.candidates.distinctBy { "${it.source}:${it.id}:${it.coverUrl}" }
                    }
                    is CoverSearchResult.Failure -> coversErrorCode = result.error
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                coversErrorCode = CoverErrorCode.UNKNOWN
            } finally {
                // 取消路径的收尾由 leaveDrillDown/新请求负责，避免迟到的 finally 清掉新一轮 loading 态
                if (isActive) loadingCovers = false
            }
        }
    }

    val onCandidateClick: (CoverSearchCandidate) -> Unit = { candidate ->
        if (!binding) {
            if (drillDownWork == null && CoverScraperService.supportsCoverDrillDown(candidate.source)) {
                openWork(candidate)
            } else {
                onBind(candidate, overlay::releaseWindow)
            }
        }
    }

    Dialog(
        onDismissRequest = ::dismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        overlay.BindWindow()
        // Dialog 是独立窗口：返回键必须在窗口内注册，才能「二级先回一级」而不是直接关掉弹窗
        BackHandler(enabled = drillDownWork != null) { leaveDrillDown() }
        val tablet = isTabletScreen()
        val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
        Box(
            modifier = overlay.searchScrim(Modifier.fillMaxSize()),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) { detectTapGestures { dismiss() } },
            )
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (tablet) Modifier.safeDrawingPadding() else Modifier.statusBarsPadding().navigationBarsPadding().imePadding())
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                val dialogHeight = if (imeVisible) maxHeight else minOf(maxHeight, CoverSearchDialogMaxHeight)
                val canSearch = keyword.trim().isNotEmpty() && !searching && !binding
                val drillDown = drillDownWork
                val visibleCandidates = if (drillDown != null) workCovers else candidates
                val infoMessage: String? = when {
                    // 两级各自的提示严格分离：离开二级时已清空二级状态，这里的判定只作双保险
                    drillDown == null && noMatch -> coverNoMatchMessage
                    drillDown == null && errorCode != null -> errorCode?.coverErrorMessage(context)
                    drillDown != null && coversErrorCode != null -> coversErrorCode?.coverErrorMessage(context)
                    // 二级已加载完仍无封面：与一级同一条「未找到匹配结果」
                    drillDown != null && !loadingCovers && visibleCandidates.isEmpty() -> coverNoMatchMessage
                    else -> null
                }

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
                    // 显式提供条目正文色，避免白雾面板上继承页面级白字。
                    CompositionLocalProvider(LocalContentColor provides TextColor) {
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
                                // 一级「来源搜索」<-> 二级「作品多封面」标题：模糊替换
                                BlurCrossfade(drillDown, contentAlignment = Alignment.Center) { titleWork ->
                                Text(
                                    titleWork?.let { stringResource(R.string.cover_multi_cover_title, it.title) }
                                        ?: stringResource(R.string.game_cover_search_title, coverSourceTitle(source)),
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Center,
                                )
                                }
                            }
                            Column(
                                Modifier
                                    .enterGamePanel(GameOverlay.CoverSearch, GamePanelPart.Input)
                                    .fillMaxWidth()
                                    // 横屏输入法和多行错误提示同时出现时，仍给结果与底部按钮留出空间。
                                    .then(if (tablet) Modifier
                                        .heightIn(max = dialogHeight / 2)
                                        .verticalScroll(rememberScrollState()) else Modifier)
                                    .padding(horizontal = 18.dp),
                            ) {
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
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        // 等待中的提示文字缓缓明灭（收起后即停止）
                                        modifier = Modifier
                                            .breathing(searching || loadingCovers)
                                            .padding(top = 8.dp),
                                    )
                                }
                                ExpandVisibility(drillDown != null) {
                                    // 归属要求：封面数据来自未萌 NextMoe（鲲 Galgame 论坛）
                                    Text(
                                        stringResource(R.string.cover_nextmoe_attribution),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(top = 8.dp),
                                    )
                                }
                                ExpandVisibility(binding) {
                                    Text(
                                        stringResource(R.string.game_cover_binding),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                            // 结果宫格：出现 / 清空时整体模糊替换（退场期间保留最后一批结果、且不可点）；
                            // 每批结果到达时自带一轮入场，候选卡依次升起；滚动时卡片轻微果冻拉伸
                            val resultsShown = visibleCandidates.isNotEmpty()
                            val resultsInteractive by rememberUpdatedState(resultsShown)
                            val shownCandidates = rememberLastNonNull(visibleCandidates.takeIf { it.isNotEmpty() })
                                ?: visibleCandidates
                            val shownDrillDown = drillDown
                            Box(
                                modifier = Modifier
                                    .enterGamePanel(GameOverlay.CoverSearch, GamePanelPart.Content)
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .padding(horizontal = 18.dp, vertical = 12.dp),
                            ) {
                                BlurCrossfade(resultsShown, modifier = Modifier.fillMaxSize()) { hasResults ->
                                if (hasResults) {
                                    val resultsJelly = rememberScrollJellyState()
                                    ProvideEntrance(rememberEntranceState()) {
                                    LazyVerticalGrid(
                                        columns = GridCells.Adaptive(CoverSearchCandidateMinWidth),
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
                                                caption = if (shownDrillDown != null) candidate.detail else null,
                                            )
                                        }
                                    }
                                    }
                                }
                                }
                            }
                            // 按钮行：一级「关闭 / 搜索」<-> 二级「返回 / 关闭」整行模糊替换，按钮不再原地跳位
                            BlurCrossfade(
                                drillDown != null,
                                modifier = Modifier
                                    .enterGamePanel(GameOverlay.CoverSearch, GamePanelPart.Actions)
                                    .fillMaxWidth(),
                            ) { inDrillDown ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                                horizontalArrangement = if (inDrillDown) {
                                    Arrangement.SpaceBetween
                                } else {
                                    Arrangement.spacedBy(8.dp, Alignment.End)
                                },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (inDrillDown) {
                                    DialogTextButton(
                                        text = stringResource(R.string.common_back),
                                        onClick = { leaveDrillDown() },
                                    )
                                }
                                DialogTextButton(text = stringResource(R.string.common_close), onClick = ::dismiss)
                                if (!inDrillDown) {
                                    // 「搜索」<->「搜索中」文案模糊替换
                                    BlurCrossfade(searching, contentAlignment = Alignment.Center) { isSearching ->
                                    DialogTextButton(
                                        text = if (isSearching) stringResource(R.string.game_searching) else stringResource(R.string.game_search),
                                        enabled = canSearch,
                                        onClick = { search() },
                                    )
                                    }
                                }
                            }
                            }
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

private val CoverSearchCandidatesSaver = listSaver<List<CoverSearchCandidate>, String>(
    save = { candidates -> candidates.map { encodeCoverSearchCandidate(it) } },
    restore = { savedCandidates -> savedCandidates.mapNotNull { decodeCoverSearchCandidate(it) } },
)

private fun encodeCoverSearchCandidate(candidate: CoverSearchCandidate): String =
    JSONObject()
        .put("source", candidate.source)
        .put("id", candidate.id)
        .put("title", candidate.title)
        .put("subtitle", candidate.subtitle)
        .put("detail", candidate.detail)
        .put("score", candidate.score)
        .put("coverUrl", candidate.coverUrl)
        .put("downloadUrl", candidate.downloadUrl)
        .put("vndbId", candidate.vndbId)
        .put("metadataTitle", candidate.metadataTitle)
        .toString()

private fun decodeCoverSearchCandidate(encoded: String): CoverSearchCandidate? = runCatching {
    val json = JSONObject(encoded)
    CoverSearchCandidate(
        source = json.optString("source"),
        id = json.optString("id"),
        title = json.optString("title"),
        subtitle = json.optString("subtitle"),
        detail = json.optString("detail"),
        score = if (json.has("score") && !json.isNull("score")) json.optInt("score") else null,
        coverUrl = json.optString("coverUrl"),
        downloadUrl = json.optString("downloadUrl").takeIf { it.isNotBlank() && !json.isNull("downloadUrl") },
        vndbId = json.optString("vndbId").takeIf { it.isNotBlank() && !json.isNull("vndbId") },
        metadataTitle = json.optString("metadataTitle").takeIf { it.isNotBlank() && !json.isNull("metadataTitle") },
    )
}.getOrNull()

/**
 * Displays a selectable cover preview and optional alternate-cover caption. Loading, ready, and
 * failed previews transition by state category so bitmap replacement alone does not restart motion.
 */
@Composable
private fun CoverCandidateCard(
    candidate: CoverSearchCandidate,
    onClick: () -> Unit,
    /** 二级（同作品多封面）用的补充标签：封面类型 · 尺寸 · 分级；一级候选不传。 */
    caption: String? = null,
    modifier: Modifier = Modifier,
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
                // 占位底色：默认风格 PageGrey，玻璃风格亮玻璃面（避免透明占位不可见）
                .background(DialogItemSurface)
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
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    color = NavWhite,
                    maxLines = 1,
                    modifier = Modifier
                        .clip(AppComponentShape)
                        .background(TextColor.copy(alpha = 0.56f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        Text(
            stringResource(R.string.game_use),
            style = MaterialTheme.typography.bodyMedium,
            color = NavWhite,
            maxLines = 1,
            modifier = Modifier
                .align(Alignment.End)
                .clip(AppComponentShape)
                .background(TextColor.copy(alpha = 0.56f))
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> MiuixSettingsTheme {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().height(260.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        lazyItemsIndexed(files) { index, name ->
                            Row(
                                modifier = Modifier
                                    // 列表在弹窗入场窗口内到达时逐行错峰升起（行程短于正文块）
                                    .staggeredEntrance(1 + index, distance = 8.dp, stepMillis = 40L)
                                    .pressSquish(scale = PressSquishDefaults.RowScale)
                                    .fillMaxWidth()
                                    .clip(AppComponentShape)
                                    // 弹窗内条目底色：默认风格 PageGrey，玻璃风格亮玻璃面
                                    .background(DialogItemSurface)
                                    // 弹窗点击反馈规范：条目禁用涟漪（indication = null）
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                    ) { selected = name }
                                    .padding(horizontal = 12.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                RadioButton(
                                    selected = selected == name,
                                    onClick = { selected = name },
                                )
                            }
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

/**
 * 游戏页默认瀑布流（列数、封面加载优化、卡片名称标签与角标设置均按游戏页设置生效）。
 */
@Composable
internal fun GameGrid(
    games: List<ScanGame>,
    gridState: LazyStaggeredGridState,
    onGameClick: (ScanGame) -> Unit,
    onGameLongClick: (ScanGame) -> Unit,
) {
    // 液态玻璃导航悬浮时不占布局：列表底部预留导航高度，滚动到底时最后一行可完全露出不被遮挡；
    // 滚动过程中内容仍可经过玻璃后面（沉浸）
    val glassBottomInset = glassNavBottomInset()
    // 应用设置「卡片隐藏名称标签」：游戏页卡片名称去掉【】/[] 标签（默认开）
    val hideTitleTag by AppSettingsStore.gameCardTitleTagState.collectSettingAsState()
    // 应用设置「卡片引擎角标」：左上角引擎类型角标（默认关）
    val showEngineBadge by AppSettingsStore.gameCardBadgeState.collectSettingAsState()
    // 大屏（横屏/平板）一行六个卡片，避免卡片被撑得过大；窄屏保持一行三个
    val columns = if (isWideScreen()) 6 else 3
    // 滚动状态只以 provider 形式下发，由封面加载协程读取（见 rememberCoverBitmap）：
    // 若在 item 组合内直接读 gridState.isScrollInProgress，滑动开始/结束会让整屏卡片一并重组。
    val isScrolling = remember(gridState) { { gridState.isScrollInProgress } }
    // 滚动果冻：滚得越快卡片沿滚动方向拉长越多，停下 Q 弹回原样（只在图层里读，不触发重组；「普通」档不挂）
    val gridJelly = rememberScrollJellyState()
    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Fixed(columns),
        state = gridState,
        modifier = Modifier.fillMaxSize().scrollJellyHost(gridJelly),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 12.dp + glassBottomInset),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
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
                    // 窄卡片（3/6 列）拉伸略收一点，避免高速滚动时显得过软
                    .scrollJelly(gridJelly, strength = 0.03f),
                game = game,
                onClick = { onGameClick(game) },
                onLongClick = { onGameLongClick(game) },
                // 滚动/惯性中暂缓封面解码，滚动停止后按帧回填（见 rememberCoverBitmap）
                isScrolling = isScrolling,
                hideTitleTag = hideTitleTag,
                showEngineBadge = showEngineBadge,
            )
        }
    }
}

internal fun gameCardItemKey(game: ScanGame): String =
    "${game.uri}\u0000${game.coverUri.orEmpty()}\u0000${game.coverSource.orEmpty()}"

/**
 * Renders a stable-aspect library card with optional long press and engine badge. Cover decoding
 * follows the supplied scrolling query, while the chosen motion mode controls its reveal animation.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun GameCard(
    game: ScanGame,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    /** 当前是否正在滑动/惯性滚动的只读查询；在 producer 协程内读取，避免组合期订阅滚动状态。 */
    isScrolling: () -> Boolean = neverScrolling,
    /** 卡片名称隐藏【】/[] 标签（应用设置「卡片隐藏名称标签」）。 */
    hideTitleTag: Boolean = false,
    /** 左上角引擎类型角标（应用设置「卡片引擎角标」，默认关）。 */
    showEngineBadge: Boolean = false,
) {
    val engineName = gameCardEngineName(game)
    val coverBitmap by rememberCoverBitmap(game.coverUri, isScrolling)
    // 删除消散中：登记在 dissolvingGames 里的卡片播放消散效果，期间不再响应点击
    val dissolve = dissolvingGames[game.uri]
    val pressModifier = if (onLongClick != null) {
        Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
    } else {
        Modifier.clickable(onClick = onClick)
    }
    // 瀑布流：按游戏 uri 稳定取 0.60–0.80 的封面宽高比，让卡片高低错落（列数不变）
    val coverAspect = remember(game.uri) {
        val hash = game.uri.hashCode().let { if (it == Int.MIN_VALUE) 0 else kotlin.math.abs(it) }
        0.60f + (hash % 21) / 100f
    }
    val bmp = coverBitmap
    val reveal = rememberCoverReveal(bmp != null, CoverSurface.Waterfall)
    Column(
        modifier
            .gameRemovalDust(dissolve)
            .pressSquish(scale = PressSquishDefaults.CardScale, charge = onLongClick != null)
            .clip(AppComponentShape)
            .dissolveTint(dissolve, MaterialTheme.colorScheme.error)
            .background(NavWhite)
            .glassBorder()
            .then(pressModifier),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(coverAspect)
                .background(game.engine.coverColor()),
            contentAlignment = Alignment.Center,
        ) {
            GameCoverPlaceholder(engineName)
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
    gridState: LazyGridState,
    onGameClick: (ScanGame) -> Unit,
    onGameLongClick: (ScanGame) -> Unit,
) {
    // 液态玻璃导航悬浮时不占布局：列表底部预留导航高度（与瀑布流一致）
    val glassBottomInset = glassNavBottomInset()
    val hideTitleTag by AppSettingsStore.gameCardTitleTagState.collectSettingAsState()
    val showEngineBadge by AppSettingsStore.gameCardBadgeState.collectSettingAsState()
    // 大屏（横屏/平板）一行六个卡片，窄屏保持一行三个
    val columns = if (isWideScreen()) 6 else 3
    val isScrolling = remember(gridState) { { gridState.isScrollInProgress } }
    // 滚动果冻：快速滚动时卡片沿滚动方向轻微拉长，停下 Q 弹复原（「普通」档不挂）
    val gridJelly = rememberScrollJellyState()
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        state = gridState,
        modifier = Modifier.fillMaxSize().scrollJellyHost(gridJelly),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 12.dp + glassBottomInset),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
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
                onClick = { onGameClick(game) },
                onLongClick = { onGameLongClick(game) },
                isScrolling = isScrolling,
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
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    /** 当前是否正在滑动/惯性滚动的只读查询；在 producer 协程内读取，避免组合期订阅滚动状态。 */
    isScrolling: () -> Boolean = neverScrolling,
    /** 卡片名称隐藏【】/[] 标签（应用设置「卡片隐藏名称标签」）。 */
    hideTitleTag: Boolean = false,
    /** 左上角引擎类型角标（应用设置「卡片引擎角标」，默认关）。 */
    showEngineBadge: Boolean = false,
) {
    val engineName = gameCardEngineName(game)
    val coverBitmap by rememberCoverBitmap(game.coverUri, isScrolling)
    // 删除消散中：登记在 dissolvingGames 里的卡片播放消散效果，期间不再响应点击
    val dissolve = dissolvingGames[game.uri]
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
                .clip(AppComponentShape)
                .dissolveTint(dissolve, MaterialTheme.colorScheme.error)
                .background(game.engine.coverColor())
                .glassBorder(),
            contentAlignment = Alignment.Center,
        ) {
            GameCoverPlaceholder(engineName)
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
private fun gameCardEngineName(game: ScanGame): String = when (game.engine) {
    EngineType.UNKNOWN -> stringResource(R.string.engine_name_unknown)
    // 无封面占位卡用短名「Switch」，避免「Nintendo Switch」过长
    EngineType.NINTENDO_SWITCH -> stringResource(R.string.engine_name_switch)
    // 第三方运行时游戏在游戏库中统一用 Plugin 表示引擎；模块名只在「引擎模块管理」页展示。
    EngineType.RUNTIME_MODULE -> stringResource(R.string.engine_name_runtime_module)
    else -> game.engine.displayName
}

/** 无封面占位（Tyranor + 引擎名）：瀑布流与网格两种卡片共用。 */
@Composable
private fun GameCoverPlaceholder(engineName: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "Tyranor",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.7f),
        )
        Text(
            engineName,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
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

/** 不感知滚动的默认查询（首页/封面流等非网格调用方复用，避免默认参数每次新建 lambda）。 */
private val neverScrolling: () -> Boolean = { false }

/**
 * 读取游戏封面。
 *
 * [isScrolling] 为「是否正在滑动/惯性滚动」的只读查询，只在 producer 协程内读取：
 * 若在组合期读取（例如以 boolean 参数下发）会让每张卡片订阅 `LazyStaggeredGridState.isScrollInProgress`，
 * 滑动开始/结束瞬间整屏 item 一起重组，这是滑动起止掉帧的直接来源。
 *
 * 时序：滑动中不启动新解码（保持 [EngineType.coverColor] 占位）→ 静止后解码 → 解码完成再经
 * [CoverPublishGate] 等静止并对齐帧时钟回填。滑动停止时一屏封面往往同时解码完成，集中写
 * State 会在同一帧触发多张卡重组与多块纹理上传（RenderThread 上传风暴），逐帧回填将其摊平。
 * 注：组合期直接命中内存缓存（initialValue）仍即时出图、不过闸门——复用已解码位图，无新解码成本。
 */
@Composable
internal fun rememberCoverBitmap(
    coverUri: String?,
    isScrolling: () -> Boolean = neverScrolling,
): androidx.compose.runtime.State<ImageBitmap?> {
    val context = LocalContext.current
    val cached = coverUri?.let(CoverBitmapCache::get)
    return produceState<ImageBitmap?>(initialValue = cached?.asImageBitmap(), coverUri) {
        if (cached != null || coverUri.isNullOrBlank()) return@produceState
        // 滑动/惯性中不启动解码，避免解码完成 → 重组/纹理上传挤占滑动帧
        awaitScrollIdle(isScrolling)
        val bitmap = CoverThumbnailLoader.load(context.applicationContext, coverUri) ?: return@produceState
        // 解码期间可能又开始了新的滑动：等再次静止后逐帧回填，滑动帧不承担纹理上传
        CoverPublishGate.awaitTurn(isScrolling)
        value = bitmap.asImageBitmap()
    }
}

/** 挂起直到 [isScrolling] 返回 false；已静止则立即返回。 */
private suspend fun awaitScrollIdle(isScrolling: () -> Boolean) {
    if (!isScrolling()) return
    snapshotFlow { isScrolling() }.first { !it }
}

/**
 * 封面回填闸门：串行化 + 对齐帧时钟，保证滑动停止后的批量回填最多每帧落地一张。
 * 等待期间若重新开始滑动，则继续等待再次静止（滑动帧不承担解码完成的纹理上传）。
 * [withFrameNanos] 使用 produceState 协程上下文自带的 MonotonicFrameClock；
 * 等待者随卡片离开组合被取消时仅释放锁，不影响其他卡片继续回填。
 *
 * 约束：调用方同属单一窗口的 Compose 树。持锁者可能在应用退后台（帧时钟暂停）期间
 * 一直持锁到回前台——单窗口下没有其它消费者，属可接受行为；引入第二窗口前需改为
 * 锁只保护「取号 + 一帧」的发布节拍，静止等待放到锁外。
 */
private object CoverPublishGate {
    private val mutex = Mutex()

    suspend fun awaitTurn(isScrolling: () -> Boolean) {
        mutex.withLock {
            awaitScrollIdle(isScrolling)
            withFrameNanos { }
        }
    }
}

@Composable
private fun rememberCandidateCoverPreview(candidate: CoverSearchCandidate): androidx.compose.runtime.State<CoverPreviewState> {
    val context = LocalContext.current
    val cacheKey = candidate.coverUrl
    val cached = cacheKey.takeIf { it.isNotBlank() }?.let(CoverBitmapCache::get)
    val initialState = cached?.asImageBitmap()?.let(CoverPreviewState::Ready)
        ?: CoverPreviewState.Loading
    return produceState<CoverPreviewState>(initialValue = initialState, cacheKey, candidate.source) {
        if (cached != null) return@produceState
        if (cacheKey.isBlank()) {
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
            val bitmap = uri?.let { decodeCoverThumbnail(context, it) }
            if (bitmap != null) {
                CoverBitmapCache.put(cacheKey, bitmap)
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

/** 封面只按卡片实际需要的尺寸解码，避免切页时上传原始大图；已解码缩略图跨页面复用。 */
private fun decodeCoverThumbnail(context: android.content.Context, uriText: String): Bitmap? = runCatching {
    val uri = android.net.Uri.parse(uriText)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

    var sampleSize = 1
    while (bounds.outWidth / (sampleSize * 2) >= CoverDecodeMaxWidthPx &&
        bounds.outHeight / (sampleSize * 2) >= CoverDecodeMaxHeightPx
    ) {
        sampleSize *= 2
    }
    val options = BitmapFactory.Options().apply {
        inSampleSize = sampleSize
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
}.getOrNull()

private const val CoverDecodeMaxWidthPx = 512
private const val CoverDecodeMaxHeightPx = 683

private object CoverBitmapCache : LruCache<String, Bitmap>(24 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
}

/**
 * 限制并发解码，避免游戏页首次组合时多个大图同时抢占 CPU/内存；相同 URI 共用一个任务。
 * 第二批等待者在获得许可后会再次检查缓存，进一步避免排队期间的重复解码。
 */
private object CoverThumbnailLoader {
    // 解码本身在 IO 线程不占主线程，主线程瓶颈已由「滚动感知解码」缓解；
    // 这里适度提高并行度以加速滚动停止后的批量回填
    private val coordinator = BoundedKeyedLoader<String>(parallelism = 4)

    suspend fun load(context: android.content.Context, uriText: String): Bitmap? = coordinator.load(
        key = uriText,
        cached = { CoverBitmapCache.get(uriText) },
    ) {
        withContext(Dispatchers.IO) {
            decodeCoverThumbnail(context, uriText)?.also { CoverBitmapCache.put(uriText, it) }
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
    EngineType.RUNTIME_MODULE -> Color(0xFF5C6BC0)
    EngineType.PSP -> Color(0xFF6D4C9F)
    EngineType.NINTENDO_SWITCH -> Color(0xFFD32F2F)
    EngineType.SYSTEMNNN, EngineType.UNKNOWN -> Color(0xFF607D8B)
}

internal fun shouldShowSaveManagement(engine: EngineType, sdkInt: Int = android.os.Build.VERSION.SDK_INT): Boolean =
    // YU-RIS 虽经外置 Winlator 启动，但存档落在游戏目录 save/，纳入统一存档管理；
    // 手动添加的类型（PC / 安卓游戏）无统一存档接口，显式排除。
    engine == EngineType.YURIS ||
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
