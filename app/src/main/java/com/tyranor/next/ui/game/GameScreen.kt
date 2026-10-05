package com.tyranor.next.ui.game

import android.app.Activity
import android.app.ActivityOptions
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.Button
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import top.yukonga.miuix.kmp.basic.RadioButton
import com.tyranor.next.R
import com.tyranor.next.core.cover.CoverImageCache
import com.tyranor.next.core.cover.CoverScrapeTaskManager
import com.tyranor.next.core.cover.CoverSearchCandidate
import com.tyranor.next.core.cover.CoverSearchResult
import com.tyranor.next.core.cover.CoverScraperService
import com.tyranor.next.core.game.launch.EngineLauncher
import com.tyranor.next.core.game.manual.AndroidAppGames
import com.tyranor.next.core.game.storage.GameLibraryFacade
import com.tyranor.next.core.game.shortcut.deleteShortcutCropBitmap
import com.tyranor.next.core.game.shortcut.GameShortcutManager
import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.engine.external.EmulatorLaunchStyle
import com.tyranor.next.core.engine.external.ExternalEmulatorRegistry
import com.tyranor.next.core.engine.external.ExternalEngineModuleRegistry
import com.tyranor.next.core.game.save.GameSaveManager
import com.tyranor.next.core.game.save.RpgSaveFormat
import com.tyranor.next.core.game.model.GameSortKeys
import com.tyranor.next.core.game.model.GameTitleTags
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.cover.VndbCoverService
import com.tyranor.next.core.cover.stableKey
import com.tyranor.next.core.i18n.AppLocaleController
import com.tyranor.next.core.settings.AppSettingsStore
import com.tyranor.next.core.auth.HikarinagiAuthStore
import com.tyranor.next.core.settings.PerGameSettingsStore
import com.tyranor.next.theme.AdvancedGlassBorder
import com.tyranor.next.theme.glassShadow
import com.tyranor.next.theme.AdvancedGlassSurfaceHigh
import com.tyranor.next.theme.AppThemeColors
import com.tyranor.next.theme.DialogItemSurface
import com.tyranor.next.theme.GlassBorder
import com.tyranor.next.theme.GlassPanel
import com.tyranor.next.theme.GlassPanelSolid
import com.tyranor.next.theme.GlassSurfaceSolid
import com.tyranor.next.theme.MiuixSettingsTheme
import com.tyranor.next.theme.NavWhite
import com.tyranor.next.theme.TextColor
import com.tyranor.next.theme.glassBorder
import com.tyranor.next.theme.rememberAdvancedGlassPanelSurface
import com.tyranor.next.theme.AppComponentShape
import com.tyranor.next.theme.CoverBadgeText
import com.tyranor.next.theme.CoverBadgeBackground
import com.tyranor.next.theme.AppSheetTopShape
import com.tyranor.next.ui.common.AppAlertDialog
import com.tyranor.next.ui.common.AppNavItem
import com.tyranor.next.ui.common.AppSearchField
import com.tyranor.next.ui.common.AppTopBar
import com.tyranor.next.ui.common.TopBarIcon
import com.tyranor.next.ui.common.glassNavBottomInset
import com.tyranor.next.ui.common.isWideScreen
import com.tyranor.next.ui.common.LaunchErrorDialog
import com.tyranor.next.ui.common.LaunchErrorState
import com.tyranor.next.ui.common.toErrorState
import com.tyranor.next.ui.common.userMessage
import com.tyranor.next.ui.cover.coverSourceTitle
import com.tyranor.next.ui.main.MainLibraryUiState
import com.tyranor.next.ui.patch.KrkrOnlinePatchActivity
import com.tyranor.next.ui.save.SaveManagementActivity
import com.tyranor.next.ui.settings.PerGameSettingsActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

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
) {
    val context = LocalContext.current
    val batchScrapeRunningMessage = stringResource(R.string.game_batch_scraping_running)
    val saveFormatConvertedFormat = stringResource(R.string.save_format_converted_count)
    val saveFormatConvertedWithFailuresFormat = stringResource(R.string.save_format_converted_with_failures)
    val saveFormatConvertFailedMessage = stringResource(R.string.save_format_convert_failed)
    val saveBusyEngineRunningMessage = stringResource(R.string.save_busy_engine_running)
    val scope = rememberCoroutineScope()
    val games = libraryState.games
    var selectedGameUri by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedGame = remember(games, selectedGameUri) {
        selectedGameUri?.let { uri -> games.firstOrNull { it.uri == uri } }
    }
    var launchError by remember { mutableStateOf<LaunchErrorState?>(null) }
    var patchLaunchTarget by remember { mutableStateOf<ScanGame?>(null) }
    // 网格长按启动路径的 MV/MZ 存档格式确认状态（与抽屉内 sheet 的同名状态各自独立）
    var longPressSaveTarget by remember { mutableStateOf<ScanGame?>(null) }
    var longPressSaveDetection by remember { mutableStateOf<RpgSaveFormat.Detection?>(null) }
    var longPressPatchChoice by remember { mutableStateOf<EngineLauncher.ArtemisPatchChoice?>(null) }

    val gridState = rememberLazyGridState()
    val scrapeTaskState by CoverScrapeTaskManager.state.collectAsState()

    LaunchedEffect(libraryState.loaded, games, selectedGameUri) {
        val uri = selectedGameUri ?: return@LaunchedEffect
        if (libraryState.loaded && games.none { it.uri == uri }) selectedGameUri = null
    }

    LaunchedEffect(libraryState.scrapeEventId, libraryState.scrapeMessage) {
        val message = libraryState.scrapeMessage ?: return@LaunchedEffect
        android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
        onScrapeEventShown(libraryState.scrapeEventId)
    }

    fun replaceGame(updated: ScanGame) {
        onGameUpdated(updated)
    }

    fun deleteGame(target: ScanGame) {
        if (selectedGameUri == target.uri) selectedGameUri = null
        onGameDeleted(target)
    }

    /** 网格长按启动的统一门：Artemis 选择（或无需补丁）后，再检查 MV/MZ 存档格式，最后拉起。
     *  开启存档互通时跳过弹窗——启动前同步已覆盖其语义。 */
    fun launchLongPress(game: ScanGame, patchChoice: EngineLauncher.ArtemisPatchChoice?) {
        scope.launch {
            if (EngineLauncher.isRpgSaveInteropEnabled(context, game)) {
                launchError = EngineLauncher.launch(context, game, patchChoice).toErrorState(context)
                return@launch
            }
            val pending = EngineLauncher.rpgSaveFormatPending(context, game)
            if (pending != null) {
                longPressSaveTarget = game
                longPressSaveDetection = pending
                longPressPatchChoice = patchChoice
            } else {
                launchError = EngineLauncher.launch(context, game, patchChoice).toErrorState(context)
            }
        }
    }

    fun syncMissingCovers() {
        if (libraryState.scanning || scrapeTaskState.running) return
        if (!CoverScrapeTaskManager.start(context, games)) {
            android.widget.Toast.makeText(context, batchScrapeRunningMessage, android.widget.Toast.LENGTH_SHORT).show()
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
        gridState = gridState,
        dirPickerLaunch = { dirPicker.launch(null) },
        syncMissingCovers = { syncMissingCovers() },
        refreshGames = { scanLibrary() },
        onGameClick = { selectedGameUri = it.uri },
        onGameLongClick = { game ->
            scope.launch {
                if (EngineLauncher.needsArtemisPatchConfirm(context, game)) {
                    patchLaunchTarget = game
                } else {
                    launchLongPress(game, null)
                }
            }
        },
        dbSearchQuery = libraryState.searchQuery,
        dbSearchResults = libraryState.searchResults,
        onAddManualGame = onAddManualGame,
                onSearchQueryChanged = onSearchQueryChanged,
    )

    // ===== 点击游戏卡片的底部抽屉栏 =====
    selectedGame?.let { game ->
        key(game.uri) {
            GameActionsSheet(
                game = game,
                onDismiss = { selectedGameUri = null },
                onGameUpdated = { replaceGame(it) },
                onDeleteGame = { deleteGame(game) },
                quickLaunched = libraryState.quickLaunch.any { it.uri == game.uri },
                onQuickLaunchToggle = { onQuickLaunchToggle(game) },
                onEngineSettings = {
                    startActivityWithPageTransition(context, PerGameSettingsActivity.createIntent(context, game))
                    selectedGameUri = null
                },
            )
        }
    }

    // ===== 长按游戏卡片：启动游戏；Artemis 按既有策略弹出补丁确认 =====
    patchLaunchTarget?.let { game ->
        AppAlertDialog(
            onDismissRequest = { patchLaunchTarget = null },
            title = {
                Text(
                    stringResource(R.string.game_auto_patch_title),
                    style = MaterialTheme.typography.titleMedium,
                )
            },
            text = {
                Text(
                    stringResource(R.string.game_auto_patch_message, game.title),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(
                        onClick = {
                            val target = patchLaunchTarget
                            patchLaunchTarget = null
                            target?.let { launchLongPress(it, EngineLauncher.ArtemisPatchChoice.ALWAYS) }
                    },
                ) { Text(stringResource(R.string.game_patch_always)) }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = {
                            val target = patchLaunchTarget
                            patchLaunchTarget = null
                            target?.let { launchLongPress(it, EngineLauncher.ArtemisPatchChoice.NEVER) }
                        },
                    ) { Text(stringResource(R.string.game_patch_never)) }
                    TextButton(
                        onClick = {
                            val target = patchLaunchTarget
                            patchLaunchTarget = null
                            target?.let { launchLongPress(it, EngineLauncher.ArtemisPatchChoice.ONCE) }
                        },
                    ) { Text(stringResource(R.string.game_patch_once)) }
                }
            },
        )
    }

    // 网格长按启动：MV/MZ 存档格式转化确认（标准 → Tyranor）
    longPressSaveDetection?.let { detection ->
        val (standardCount, hashedCount) = detection.dialogArgs()
        RpgSaveFormatDialog(
            standardCount = standardCount,
            hashedCount = hashedCount,
            onChoice = { convert ->
                val target = longPressSaveTarget
                val patchChoice = longPressPatchChoice
                longPressSaveDetection = null
                longPressSaveTarget = null
                longPressPatchChoice = null
                if (target != null) {
                    scope.launch {
                        if (convert) {
                            val op = try {
                                withContext(Dispatchers.IO) { EngineLauncher.convertRpgSaveFormat(context, target) }
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
                            android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
                        }
                        launchError = EngineLauncher.launch(context, target, patchChoice).toErrorState(context)
                    }
                }
            },
        )
    }

    launchError?.let { state ->
        LaunchErrorDialog(state = state, onDismiss = { launchError = null })
    }
}

// 排序键与 Room 预计算列共用 GameSortKeys，保证 SQL 排序与内存排序结果一致（迁移方案阶段 3）。
private fun sortGames(games: List<ScanGame>, sortMode: String): List<ScanGame> {
    return when (sortMode) {
        AppSettingsStore.GAME_SORT_BRACKET_TAG -> games.sortedWith(
            compareBy<ScanGame> { GameSortKeys.bracketTag(it.title).isBlank() }
                .thenBy { GameSortKeys.tagKey(it.title) }
                .thenBy { GameSortKeys.titleKey(it.title) },
        )
        else -> games.sortedBy { GameSortKeys.titleKey(it.title) }
    }
}

/** 删除游戏后清理应用内关联数据（设置/最近记录/快捷启动/封面/存档镜像），绝不触碰游戏文件。 */
internal fun cleanupDeletedGame(context: android.content.Context, target: ScanGame) {
    PerGameSettingsStore.clear(context, target.uri)
    GameLibraryFacade.removeRecentGame(context, target.uri)
    GameLibraryFacade.removeQuickLaunch(context, target.uri)
    deleteCoverFile(context, target.coverUri)
    GameSaveManager(context).cleanupAppData(target)
}

private fun deleteCoverFile(context: android.content.Context, coverUri: String?) {
    if (coverUri.isNullOrBlank()) return
    val file = runCatching { File(android.net.Uri.parse(coverUri).path ?: return) }.getOrNull() ?: return
    val coverDir = File(context.filesDir, "covers_remote").canonicalPath
    if (runCatching { file.canonicalPath }.getOrNull()?.startsWith(coverDir) == true) {
        file.delete()
    }
}

internal fun startActivityWithPageTransition(context: android.content.Context, intent: android.content.Intent) {
    val activity = AppLocaleController.findActivity(context)
    if (activity != null) {
        val options = ActivityOptions.makeCustomAnimation(
            activity,
            R.anim.page_slide_in_from_bottom,
            R.anim.page_slide_out_to_top,
        )
        activity.startActivity(intent, options.toBundle())
    } else {
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}

@Composable
private fun GameLibraryContent(
    modifier: Modifier,
    games: List<ScanGame>,
    loaded: Boolean,
    scanning: Boolean,
    scrapingCovers: Boolean,
    gridState: LazyGridState,
    dirPickerLaunch: () -> Unit,
    syncMissingCovers: () -> Unit,
    refreshGames: () -> Unit,
    onGameClick: (ScanGame) -> Unit,
    onGameLongClick: (ScanGame) -> Unit,
    dbSearchQuery: String,
    dbSearchResults: List<ScanGame>?,
    onSearchQueryChanged: (String) -> Unit,
    onAddManualGame: (ScanGame) -> Boolean,
) {
    var showSearch by rememberSaveable { mutableStateOf(false) }
    // 「添加游戏」分支流程：null=关闭；CHOICE=分支弹窗，PC/ANDROID=对应二级添加弹窗
    var addGameStep by remember { mutableStateOf<AddGameStep?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    val gameSort by AppSettingsStore.gameSortState.collectAsState()
    val sortedGames = remember(games, gameSort) { sortGames(games, gameSort) }
    val fallbackFiltered = remember(sortedGames, query) {
        val q = query.trim()
        if (q.isEmpty()) sortedGames else sortedGames.filter { it.title.contains(q, ignoreCase = true) }
    }
    // 搜索走 Room DAO（迁移方案阶段 3）：预计算排序键排序 + LIKE 转义；
    // DB 结果未返回（防抖窗口内）时先用内存过滤兜底，行为与旧版一致。
    // 已知差异：SQLite LIKE 仅对 ASCII 折叠大小写，全角拉丁/带音符字符的命中可能与
    // 内存过滤（Unicode）略有出入；后续如需完全对齐可引入 FTS5（方案阶段 3 的预留路径）。
    LaunchedEffect(query, gameSort) { onSearchQueryChanged(query) }
    val dbResults = if (query.isNotBlank() && dbSearchQuery == query) dbSearchResults else null
    val filteredGames = when {
        query.isBlank() -> sortedGames
        dbResults != null -> dbResults
        else -> fallbackFiltered
    }
    val scrapingCoversDescription = stringResource(R.string.game_scraping_covers_content_description)

    Column(modifier.fillMaxSize()) {
        // ===== 顶部栏：统一 AppTopBar（标题居左 + 右侧图标 + 折叠搜索框） =====
        AppTopBar(
            title = stringResource(R.string.game_title),
            underTitle = {
                if (showSearch) {
                    AppSearchField(
                        query = query,
                        onQueryChange = { query = it },
                        modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 10.dp),
                    )
                }
            },
            trailing = {
                TopBarIcon(
                    painterResource(R.drawable.ic_game_add),
                    stringResource(R.string.game_add_content_description),
                    MaterialTheme.colorScheme.primary,
                ) { addGameStep = AddGameStep.CHOICE }
                TopBarIcon(painterResource(R.drawable.ic_game_search), stringResource(R.string.game_search_content_description), MaterialTheme.colorScheme.primary) {
                    showSearch = !showSearch
                    if (!showSearch) query = ""
                }
                if (scrapingCovers) {
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
                        syncMissingCovers()
                    }
                }
                TopBarIcon(painterResource(R.drawable.ic_game_scan), stringResource(R.string.game_scan_content_description), MaterialTheme.colorScheme.primary) {
                    refreshGames()
                }
            },
        )

        // ===== 内容区 =====
        Box(Modifier.fillMaxSize()) {
            when {
                scanning || !loaded -> {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                }
                games.isEmpty() -> {
                    Column(
                        Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(stringResource(R.string.game_empty_title), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            stringResource(R.string.game_empty_message),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        Button(
                            onClick = { dirPickerLaunch() },
                            modifier = Modifier.padding(top = 16.dp),
                        ) { Text(stringResource(R.string.game_add_folder)) }
                    }
                }
                else -> {
                    if (filteredGames.isEmpty()) {
                        Text(
                            stringResource(R.string.game_no_match),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.Center),
                        )
                    } else {
                        val cardStyle by AppSettingsStore.gameCardStyleState.collectAsState()
                        if (cardStyle == AppSettingsStore.GAME_CARD_STYLE_COVER_FLOW) {
                            // 「列表」风格：封面流横向轮播（列表/搜索/排序数据源相同）
                            GameCoverFlow(
                                games = filteredGames,
                                onGameClick = onGameClick,
                                onGameLongClick = onGameLongClick,
                            )
                        } else {
                            GameGrid(
                                games = filteredGames,
                                gridState = gridState,
                                onGameClick = onGameClick,
                                onGameLongClick = onGameLongClick,
                            )
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

}

/** 「添加游戏」流程弹窗步骤（游戏页顶栏入口 → 分支 → 二级添加弹窗）。 */
private enum class AddGameStep { CHOICE, PC, ANDROID }

/** 抽屉内「一行两个」动作条目描述；[key] 供 LazyColumn item key 使用。 */
private data class DrawerAction(
    val key: String,
    val title: String,
    val icon: Int,
    val showArrow: Boolean = false,
    val iconTint: Color? = null,
    val titleColor: Color? = null,
    val onClick: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GameActionsSheet(
    game: ScanGame,
    onDismiss: () -> Unit,
    onGameUpdated: (ScanGame) -> Unit,
    onDeleteGame: () -> Unit,
    onEngineSettings: () -> Unit,
    quickLaunched: Boolean,
    onQuickLaunchToggle: () -> Boolean,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var launchError by remember(game.uri) { mutableStateOf<LaunchErrorState?>(null) }
    var showCoverSourcePicker by rememberSaveable(game.uri) { mutableStateOf(false) }
    var coverSearchSource by rememberSaveable(game.uri) { mutableStateOf<String?>(null) }
    var coverBinding by remember { mutableStateOf(false) }
    var coverBindError by rememberSaveable(game.uri) { mutableStateOf<String?>(null) }
    var showDeleteConfirm by rememberSaveable(game.uri) { mutableStateOf(false) }
    var showLaunchFilePicker by rememberSaveable(game.uri) { mutableStateOf(false) }
    var showRenameDialog by rememberSaveable(game.uri) { mutableStateOf(false) }
    var showPatchConfirm by rememberSaveable(game.uri) { mutableStateOf(false) }
    // MV/MZ 存档格式转化确认：待转化检测结果 + 已选定的 Artemis 补丁策略（两弹窗串联时保留）
    var rpgSaveDetection by remember(game.uri) { mutableStateOf<RpgSaveFormat.Detection?>(null) }
    var pendingPatchChoice by remember(game.uri) { mutableStateOf<EngineLauncher.ArtemisPatchChoice?>(null) }
    var shortcutRequestInFlight by remember(game.uri) { mutableStateOf(false) }
    var shortcutCropUriText by rememberSaveable(game.uri) { mutableStateOf<String?>(null) }
    var shortcutPickerForNoCover by rememberSaveable(game.uri) { mutableStateOf(false) }
    val batchScrapeRunningMessage = stringResource(R.string.game_batch_scraping_running)
    val settingCoverMessage = stringResource(R.string.game_setting_cover)
    val coverSetFailedMessage = stringResource(R.string.game_cover_set_failed)
    val quickLaunchFullMessage = stringResource(R.string.game_quick_launch_full)
    val coverDownloadFailedMessage = stringResource(R.string.game_cover_download_failed)
    val shortcutRequestedMessage = stringResource(R.string.game_desktop_shortcut_requested)
    val shortcutUpdatedMessage = stringResource(R.string.game_desktop_shortcut_updated)
    val shortcutUnsupportedMessage = stringResource(R.string.game_desktop_shortcut_unsupported)
    // 抽屉「一行两个」动作条目的文案/颜色：buildList 的 lambda 内不允许 @Composable 调用，先在组合体取值
    val drawerQuickLaunchTitle = if (quickLaunched) stringResource(R.string.game_remove_quick_launch)
    else stringResource(R.string.game_add_quick_launch)
    val drawerDesktopShortcutTitle = stringResource(R.string.game_add_desktop_shortcut)
    val drawerSearchCoverTitle = stringResource(R.string.game_search_cover)
    val drawerEditCoverTitle = stringResource(R.string.game_edit_cover)
    val drawerRenameTitle = stringResource(R.string.game_rename)
    val drawerSaveManagementTitle = stringResource(R.string.game_save_management)
    val drawerOnlinePatchTitle = stringResource(R.string.game_online_patch)
    val drawerEngineSettingsTitle = stringResource(R.string.settings_engine_settings)
    val drawerDeleteTitle = stringResource(R.string.game_delete_title)
    val drawerPrimaryColor = MaterialTheme.colorScheme.primary
    val drawerDangerColor = MaterialTheme.colorScheme.error
    val shortcutFailedMessage = stringResource(R.string.game_desktop_shortcut_failed)
    val saveFormatConvertedFormat = stringResource(R.string.save_format_converted_count)
    val saveFormatConvertedWithFailuresFormat = stringResource(R.string.save_format_converted_with_failures)
    val saveFormatConvertFailedMessage = stringResource(R.string.save_format_convert_failed)
    val saveBusyEngineRunningMessage = stringResource(R.string.save_busy_engine_running)

    /** Blocks destructive cover/shortcut actions while a batch scrape is running. */
    fun isBatchScrapingActive(): Boolean {
        if (!CoverScrapeTaskManager.state.value.running) return false
        android.widget.Toast.makeText(context, batchScrapeRunningMessage, android.widget.Toast.LENGTH_SHORT).show()
        return true
    }

    // 发起启动；Artemis 需要 PFS 基础补丁且策略为“启动时询问”时，先弹窗确认再带选择启动
    /** Launches the selected game, optionally applying an explicit Artemis policy. */
    fun startLaunch(patchChoice: EngineLauncher.ArtemisPatchChoice? = null) {
        scope.launch {
            launchError = EngineLauncher.launch(context, game, patchChoice).toErrorState(context)
            if (launchError == null) onDismiss()
        }
    }

    /** Artemis 选择落地后，再检查 MV/MZ 存档格式；有标准存档则弹窗，否则直接启动。
     *  开启存档互通时跳过弹窗——启动前同步已覆盖其语义。 */
    fun launchWithSaveFormatGate(patchChoice: EngineLauncher.ArtemisPatchChoice?) {
        scope.launch {
            if (EngineLauncher.isRpgSaveInteropEnabled(context, game)) {
                startLaunch(patchChoice)
                return@launch
            }
            val pending = EngineLauncher.rpgSaveFormatPending(context, game)
            if (pending != null) {
                pendingPatchChoice = patchChoice
                rpgSaveDetection = pending
            } else {
                startLaunch(patchChoice)
            }
        }
    }

    /** 启动前统一门：先 Artemis 补丁确认，再 MV/MZ 存档格式确认，最后才真正拉起。 */
    fun beginLaunch() {
        scope.launch {
            if (EngineLauncher.needsArtemisPatchConfirm(context, game)) {
                showPatchConfirm = true
            } else {
                launchWithSaveFormatGate(null)
            }
        }
    }

    /** 用户确认转化后执行转化（best-effort），随后按既定策略启动。 */
    fun resolveSaveFormat(convert: Boolean) {
        val detection = rpgSaveDetection
        rpgSaveDetection = null
        val patchChoice = pendingPatchChoice
        pendingPatchChoice = null
        scope.launch {
            if (convert && detection != null) {
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
                android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
            }
            startLaunch(patchChoice)
        }
    }

    /** Requests a new shortcut or updates the existing shortcut for this game. */
    fun requestDesktopShortcut(customIconUri: android.net.Uri? = null) {
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
            launchError = LaunchErrorState(settingCoverMessage)
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
                launchError = null
                onDismiss()
            } else {
                launchError = LaunchErrorState(coverSetFailedMessage)
            }
        }
    }

    // 玻璃系风格抽屉：高级玻璃用页面背景取色渐变；复古玻璃用不透明色，任何一层都不透底
    val drawerItemSurface = when {
        AppThemeColors.isAdvancedGlass -> AdvancedGlassSurfaceHigh
        AppThemeColors.isGlass -> GlassSurfaceSolid
        else -> NavWhite
    }
    // 高级玻璃：面板渐变从页面背景取色（独立窗口采不到 backdrop，用跨窗口取色替代）
    val advancedPanelSurface = if (AppThemeColors.isAdvancedGlass) rememberAdvancedGlassPanelSurface() else null

    ModalBottomSheet(
        onDismissRequest = {
            // 关闭抽屉时一并清除裁切弹窗状态，避免 rememberSaveable 在下一次打开时残留旧弹窗
            shortcutCropUriText = null
            shortcutPickerForNoCover = false
            onDismiss()
        },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        // 关闭抽屉拖拽手势：M3 默认「位移 > 56dp 或速度 > 125dp/s」即关闭，用户快速滑动内容时
        // 极易误关（列表到顶后的剩余手势/惯性会转交抽屉）。关闭后仅能通过遮罩/返回键关闭，
        // 内容滚动与点击不受影响。参见 AGENT.md「游戏操作抽屉」约定。
        sheetGesturesEnabled = false,
        // 高级玻璃面板底色透明，取色渐变画在内容层（不能挂 Surface 外层 modifier：
        // 抽屉位置由内部 anchors 布局偏移决定，外层绘制会落在未偏移位置，与玻璃描边踩过同一个坑）；
        // 复古玻璃用不透明面板色（GlassPanel 带 10% 透明度会透出底层内容）
        containerColor = when {
            AppThemeColors.isAdvancedGlass -> Color.Transparent
            AppThemeColors.isGlass -> GlassPanelSolid
            else -> MaterialTheme.colorScheme.background
        },
        // 玻璃系风格加深化背景，避免抽屉与底层内容混在一起被看成半透明
        scrimColor = when {
            AppThemeColors.isAdvancedGlass -> Color.Black.copy(alpha = 0.6f)
            AppThemeColors.isGlass -> Color.Black.copy(alpha = 0.6f)
            else -> Color.Black.copy(alpha = 0.32f)
        },
        contentWindowInsets = { WindowInsets(0.dp) },
        // 顶部圆角与弹窗内条目圆角（AppNavItem 8dp）保持一致
        shape = AppSheetTopShape,
        // 玻璃描边只能画在抽屉真实顶边（dragHandle 槽首位）；不能挂 Surface 外层 modifier，
        // 否则描边会按未偏移的布局位置落到背景里形成一条白线。
        // 高级玻璃的把手条用面板渐变的顶端取色铺底，与下方内容层的渐变无缝衔接。
        dragHandle = {
            // Column 默认水平 Start 对齐会让把手贴左；需显式居中，描边线仍铺满整宽
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (advancedPanelSurface != null) {
                            Modifier.background(advancedPanelSurface.topColor)
                        } else {
                            Modifier
                        },
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (AppThemeColors.isGlass) {
                    Box(
                        Modifier.fillMaxWidth().height(0.5.dp).background(
                            if (AppThemeColors.isAdvancedGlass) AdvancedGlassBorder else GlassBorder,
                        ),
                    )
                }
                BottomSheetDefaults.DragHandle()
            }
        },
    ) {
        // 小平板横屏下屏幕高度可能 < 560dp，硬编码会导致抽屉填满屏幕，
        // SwipeableState 无法区分滚动/收起，快速滑动时高速振荡（issue #27）。
        val sheetMaxHeight = with(LocalConfiguration.current) {
            val available = (screenHeightDp - 120).dp
            available.coerceIn(200.dp, 560.dp)
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = sheetMaxHeight)
                // 高级玻璃取色渐变画在内容层（随抽屉偏移一起移动）
                .then(
                    if (advancedPanelSurface != null) {
                        Modifier.background(advancedPanelSurface.brush)
                    } else {
                        Modifier
                    },
                ),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(
                    game.title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
                )
            }

            item {
                AppNavItem(
                    title = stringResource(R.string.game_launch_action),
                    leadingIcon = R.drawable.ic_sheet_launch,
                    containerColor = drawerItemSurface,
                        verticalPadding = 17.dp,
                    showArrow = false,
                    leadingIconTint = MaterialTheme.colorScheme.primary,
                    onClick = { beginLaunch() },
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
                        leadingIcon = R.drawable.ic_sheet_launch_file,
                        containerColor = drawerItemSurface,
                        verticalPadding = 17.dp,
                        showArrow = false,
                        leadingIconTint = MaterialTheme.colorScheme.primary,
                        onClick = { showLaunchFilePicker = true },
                    )
                }
            }
            // 「启动游戏 / 启动文件」保持整行；其余动作条目一行两个（末行单个保持半宽）。
            val drawerActions = buildList {
                add(
                    DrawerAction(
                        key = "quick_launch",
                        title = drawerQuickLaunchTitle,
                        icon = R.drawable.ic_home,
                        iconTint = drawerPrimaryColor,
                        onClick = {
                            if (onQuickLaunchToggle()) {
                                onDismiss()
                            } else {
                                android.widget.Toast.makeText(context, quickLaunchFullMessage, android.widget.Toast.LENGTH_SHORT).show()
                            }
                        },
                    ),
                )
                add(
                    DrawerAction(
                        key = "desktop_shortcut",
                        title = drawerDesktopShortcutTitle,
                        icon = R.drawable.ic_sheet_desktop_shortcut,
                        iconTint = drawerPrimaryColor,
                        onClick = { if (!shortcutRequestInFlight) openShortcutCrop() },
                    ),
                )
                add(
                    DrawerAction(
                        key = "search_cover",
                        title = drawerSearchCoverTitle,
                        icon = R.drawable.ic_sheet_search_cover,
                        iconTint = drawerPrimaryColor,
                        onClick = { if (!isBatchScrapingActive()) showCoverSourcePicker = true },
                    ),
                )
                add(
                    DrawerAction(
                        key = "edit_cover",
                        title = drawerEditCoverTitle,
                        icon = R.drawable.ic_sheet_edit_cover,
                        iconTint = drawerPrimaryColor,
                        onClick = { if (!isBatchScrapingActive()) imagePicker.launch("image/*") },
                    ),
                )
                add(
                    DrawerAction(
                        key = "rename",
                        title = drawerRenameTitle,
                        icon = R.drawable.ic_sheet_rename,
                        iconTint = drawerPrimaryColor,
                        onClick = { showRenameDialog = true },
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
                                startActivityWithPageTransition(context, SaveManagementActivity.createIntent(context, game))
                                onDismiss()
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
                                startActivityWithPageTransition(context, KrkrOnlinePatchActivity.createIntent(context, game))
                                onDismiss()
                            },
                        ),
                    )
                }
                add(
                    DrawerAction(
                        key = "engine_settings",
                        title = drawerEngineSettingsTitle,
                        icon = R.drawable.ic_sheet_settings,
                        showArrow = true,
                        iconTint = drawerPrimaryColor,
                        onClick = onEngineSettings,
                    ),
                )
                add(
                    DrawerAction(
                        key = "delete",
                        title = drawerDeleteTitle,
                        icon = R.drawable.ic_sheet_delete,
                        iconTint = drawerDangerColor,
                        titleColor = drawerDangerColor,
                        onClick = { showDeleteConfirm = true },
                    ),
                )
            }
            drawerActions.chunked(2).forEach { rowActions ->
                item(key = "drawer_row:" + rowActions.first().key) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        rowActions.forEach { action ->
                            AppNavItem(
                                title = action.title,
                                modifier = Modifier.weight(1f),
                                leadingIcon = action.icon,
                                containerColor = drawerItemSurface,
                                verticalPadding = 17.dp,
                                showArrow = action.showArrow,
                                leadingIconTint = action.iconTint,
                                titleColor = action.titleColor,
                                onClick = action.onClick,
                            )
                        }
                        if (rowActions.size == 1) {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }

            // 底部安全区留白
            item { Box(Modifier.fillMaxWidth().navigationBarsPadding().height(16.dp)) }
        }
    }

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

    // ===== Artemis 自动补丁确认：总是（记住 auto）/ 本次 / 不再（记住 off）；点遮罩取消 = 不启动 =====
    if (showPatchConfirm) {
        ArtemisPatchChoiceDialog(
            game = game,
            onChoice = { choice ->
                showPatchConfirm = false
                if (choice != null) launchWithSaveFormatGate(choice)
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
            onBind = { candidate ->
                if (!coverBinding && !isBatchScrapingActive()) {
                    coverBinding = true
                    coverBindError = null
                    scope.launch {
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
                            onGameUpdated(updated)
                            coverSearchSource = null
                            onDismiss()
                        } else {
                            coverBindError = coverDownloadFailedMessage
                        }
                    }
                }
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

    if (showDeleteConfirm) {
        AppAlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.game_delete_title), style = MaterialTheme.typography.titleMedium) },
            text = {
                Text(
                    stringResource(R.string.game_delete_message, game.title),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    onDeleteGame()
                }) { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

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
            // 统一 Miuix 风格输入框（AppSearchField）；键盘“搜索/完成”动作直接保存（内容有效时）
            AppSearchField(
                query = title,
                onQueryChange = { title = it },
                onSearch = { if (canConfirm) onConfirm(normalizedTitle) },
                leadingIcon = painterResource(R.drawable.ic_sheet_rename),
                iconContentDescription = stringResource(R.string.game_rename),
                textStyle = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(normalizedTitle) },
                enabled = canConfirm,
            ) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

@Composable
private fun CoverSourcePickerDialog(
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    val context = LocalContext.current
    val authVersion by HikarinagiAuthStore.statusVersion.collectAsState()
    val scraperSettingsVersion by AppSettingsStore.coverScraperSettingsVersion.collectAsState()
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
                lazyItems(sources, key = { it }) { source ->
                    val enabled = AppSettingsStore.isCoverScraperSourceEnabled(context, source)
                    val needsHikarinagiLogin = source == AppSettingsStore.COVER_SOURCE_HIKARINAGI &&
                        (!authStatus.authorized || authStatus.needsReauth)
                    val selectable = enabled && !needsHikarinagiLogin
                    AppNavItem(
                        title = coverSourceTitle(source),
                        summary = coverSourcePickerSummary(source, enabled, needsHikarinagiLogin),
                        onClick = if (selectable) ({ onSelect(source) }) else null,
                        leadingIcon = R.drawable.ic_cover_source,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
private fun CoverSearchDialog(
    game: ScanGame,
    source: String,
    binding: Boolean,
    bindError: String?,
    onDismiss: () -> Unit,
    onBind: (CoverSearchCandidate) -> Unit,
) {
    val context = LocalContext.current
    val coverNoMatchMessage = stringResource(R.string.game_cover_no_match)
    val coverSearchFailedMessage = stringResource(R.string.game_cover_search_failed)
    var keyword by rememberSaveable(source, game.uri) { mutableStateOf(game.title) }
    var searching by remember { mutableStateOf(false) }
    var error by rememberSaveable(source, game.uri) { mutableStateOf<String?>(null) }
    var candidates by rememberSaveable(source, game.uri, stateSaver = CoverSearchCandidatesSaver) {
        mutableStateOf(emptyList<CoverSearchCandidate>())
    }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    // 高级玻璃：面板渐变从页面背景取色（独立窗口采不到 backdrop，用跨窗口取色替代）
    val advancedPanelSurface = if (AppThemeColors.isAdvancedGlass) rememberAdvancedGlassPanelSurface() else null

    fun search() {
        val query = keyword.trim()
        if (query.isEmpty() || searching || binding) return
        scope.launch {
            searching = true
            error = null
            candidates = emptyList()
            try {
                when (val result = withContext(Dispatchers.IO) {
                    CoverScraperService.searchCoverCandidates(context, source, query, 8)
                }) {
                    is CoverSearchResult.Success -> {
                        candidates = result.candidates.distinctBy { "${it.source}:${it.id}:${it.coverUrl}" }
                        if (candidates.isEmpty()) error = coverNoMatchMessage
                    }
                    is CoverSearchResult.Failure -> {
                        error = result.message
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: coverSearchFailedMessage
            } finally {
                searching = false
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                // 高级玻璃面板为较高不透明度的灰玻璃膜，遮罩略加深即可
                .background(Color.Black.copy(alpha = if (AppThemeColors.isAdvancedGlass) 0.6f else 0.5f)),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) { detectTapGestures { onDismiss() } },
            )
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .then(if (imeVisible) Modifier.imePadding() else Modifier.navigationBarsPadding())
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                val dialogHeightModifier = if (imeVisible || maxHeight < CoverSearchDialogMaxHeight) {
                    Modifier.fillMaxHeight()
                } else {
                    Modifier.height(CoverSearchDialogMaxHeight)
                }
                val canSearch = keyword.trim().isNotEmpty() && !searching && !binding

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = CoverSearchDialogMaxWidth)
                        .then(dialogHeightModifier)
                        .glassShadow()
                        .clip(AppComponentShape)
                        // 高级玻璃用页面背景取色渐变，其余玻璃档用高不透明度玻璃面板
                        .then(
                            if (advancedPanelSurface != null) {
                                Modifier.background(advancedPanelSurface.brush)
                            } else {
                                Modifier.background(if (AppThemeColors.isGlass) GlassPanel else NavWhite)
                            },
                        )
                        .glassBorder()
                        .pointerInput(Unit) { detectTapGestures { } },
                ) {
                    Column(Modifier.fillMaxSize()) {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                stringResource(R.string.game_cover_search_title, coverSourceTitle(source)),
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp)) {
                            AppSearchField(
                                query = keyword,
                                onQueryChange = { keyword = it },
                                onSearch = { search() },
                                textStyle = MaterialTheme.typography.bodyMedium,
                            )
                            if (searching) {
                                Text(
                                    stringResource(R.string.game_cover_searching),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 8.dp),
                                )
                            }
                            if (binding) {
                                Text(
                                    stringResource(R.string.game_cover_binding),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 8.dp),
                                )
                            }
                            bindError?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(top = 8.dp),
                                )
                            }
                            error?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(top = 8.dp),
                                )
                            }
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .padding(horizontal = 18.dp, vertical = 12.dp),
                        ) {
                            if (candidates.isNotEmpty()) {
                                LazyVerticalGrid(
                                    columns = GridCells.Adaptive(CoverSearchCandidateMinWidth),
                                    modifier = Modifier.fillMaxSize(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    gridItems(candidates, key = { "${it.source}:${it.id}:${it.coverUrl}" }) { candidate ->
                                        CoverCandidateCard(
                                            candidate = candidate,
                                            onClick = { if (!binding) onBind(candidate) },
                                        )
                                    }
                                }
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(onClick = onDismiss) {
                                Text(stringResource(R.string.common_close), style = MaterialTheme.typography.bodyMedium)
                            }
                            TextButton(
                                onClick = { search() },
                                enabled = canSearch,
                            ) {
                                Text(if (searching) stringResource(R.string.game_searching) else stringResource(R.string.game_search), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
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

@Composable
private fun CoverCandidateCard(
    candidate: CoverSearchCandidate,
    onClick: () -> Unit,
) {
    val previewState by rememberCandidateCoverPreview(candidate)
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .clip(AppComponentShape)
                // 占位底色：默认风格 PageGrey，玻璃风格亮玻璃面（避免透明占位不可见）
                .background(DialogItemSurface),
            contentAlignment = Alignment.Center,
        ) {
            when (val state = previewState) {
                CoverPreviewState.Failed -> Text(
                    stringResource(R.string.game_no_preview),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                CoverPreviewState.Loading -> CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                )
                is CoverPreviewState.Ready -> Image(
                    bitmap = state.bitmap,
                    contentDescription = candidate.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
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
                    .background(NavWhite.copy(alpha = 0.9f))
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
        val (names, current) = withContext(Dispatchers.IO) {
            val names = EngineLauncher.listLaunchFiles(context, game)
            val current = EngineLauncher.currentLaunchFileName(context, game)
            names to current
        }
        files = names
        selected = current?.takeIf { names.contains(it) }
        loading = false
    }

    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.game_launch_file), style = MaterialTheme.typography.titleMedium) },
        text = {
            when {
                loading -> Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                files.isEmpty() -> Text(
                    stringResource(R.string.game_launch_file_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> MiuixSettingsTheme {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().height(260.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        lazyItems(files) { name ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(AppComponentShape)
                                    // 弹窗内条目底色：默认风格 PageGrey，玻璃风格亮玻璃面
                                    .background(DialogItemSurface)
                                    .clickable { selected = name }
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
        },
        confirmButton = {
            TextButton(
                onClick = { selected?.let(onConfirm) },
                enabled = selected != null,
            ) { Text(stringResource(R.string.common_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

@Composable
private fun GameGrid(
    games: List<ScanGame>,
    gridState: LazyGridState,
    onGameClick: (ScanGame) -> Unit,
    onGameLongClick: (ScanGame) -> Unit,
) {
    // 液态玻璃导航悬浮时不占布局：列表底部预留导航高度，滚动到底时最后一行可完全露出不被遮挡；
    // 滚动过程中内容仍可经过玻璃后面（沉浸）
    val glassBottomInset = glassNavBottomInset()
    // 应用设置「卡片隐藏名称标签」：游戏页卡片名称去掉【】/[] 标签（默认开）
    val hideTitleTag by AppSettingsStore.gameCardTitleTagState.collectAsState()
    // 应用设置「卡片引擎角标」：左上角引擎类型角标（默认关）
    val showEngineBadge by AppSettingsStore.gameCardBadgeState.collectAsState()
    // 大屏（横屏/平板）一行六个卡片，避免卡片被撑得过大；窄屏保持一行三个
    val columns = if (isWideScreen()) 6 else 3
    // 滚动状态只以 provider 形式下发，由封面加载协程读取（见 rememberCoverBitmap）：
    // 若在 item 组合内直接读 gridState.isScrollInProgress，滑动开始/结束会让整屏卡片一并重组。
    val isScrolling = remember(gridState) { { gridState.isScrollInProgress } }
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        state = gridState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 12.dp + glassBottomInset),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        gridItems(
            items = games,
            // 封面批量任务逐项更新时强制重建对应卡片的封面状态；否则 LazyGrid 可能继续复用
            // 以 uri 为身份的旧 item，直到卡片滚出屏幕后才重新读取新的 coverUri。
            key = ::gameCardItemKey,
            contentType = { "game_card" },
        ) { game ->
            GameCard(
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
    Column(modifier) {
        val engineName = when (game.engine) {
            EngineType.UNKNOWN -> stringResource(R.string.engine_name_unknown)
            // 无封面占位卡用短名「Switch」，避免「Nintendo Switch」过长
            EngineType.NINTENDO_SWITCH -> stringResource(R.string.engine_name_switch)
            else -> game.engine.displayName
        }
        val coverBitmap by rememberCoverBitmap(game.coverUri, isScrolling)
        val pressModifier = if (onLongClick != null) {
            Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
        } else {
            Modifier.clickable(onClick = onClick)
        }
        // 卡片 1:3（高:宽 = 4:3 立式封面，一行三列）
        // 封面加载完成后从 0 渐显到 1；占位（引擎色 + 文字）始终在底层，加载前不可见差异
        val bmp = coverBitmap
        val coverAlpha by animateFloatAsState(
            targetValue = if (bmp != null) 1f else 0f,
            animationSpec = tween(durationMillis = 300),
            label = "coverFadeIn",
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .glassShadow()
                .clip(AppComponentShape)
                .background(game.engine.coverColor())
                .glassBorder()
                .then(pressModifier),
            contentAlignment = Alignment.Center,
        ) {
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
            if (bmp != null) {
                Image(
                    bitmap = bmp,
                    contentDescription = game.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().graphicsLayer { alpha = coverAlpha },
                )
                // 左上角引擎类型角标：开关开启且实际渲染封面的卡片才显示（无封面占位卡不显示）；
                // 尺寸为标注规格（labelSmall 11sp + 边距减半，AGENT.md 明文豁免）
                if (showEngineBadge) Text(
                    game.engine.abbr,
                    style = MaterialTheme.typography.labelSmall,
                    // 角标样式固定（半透明黑底 + 白字，见 theme/Color.kt），不随主题色/色调切换变化
                    color = CoverBadgeText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(3.dp)
                        .graphicsLayer { alpha = coverAlpha }
                        .clip(AppComponentShape)
                        .background(CoverBadgeBackground)
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
        }
        Text(
            if (hideTitleTag) GameTitleTags.stripAll(game.title) else game.title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
    }
}

/** 不感知滚动的默认查询（首页/封面流等非网格调用方复用，避免默认参数每次新建 lambda）。 */
private val neverScrolling: () -> Boolean = { false }

/**
 * 读取游戏封面。
 *
 * [isScrolling] 为「是否正在滑动/惯性滚动」的只读查询，只在 producer 协程内读取：
 * 若在组合期读取（例如以 boolean 参数下发）会让每张卡片订阅 `LazyGridState.isScrollInProgress`，
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
    EngineType.UK2 -> Color(0xFF4E6E81)
    EngineType.FVP -> Color(0xFFB05A2A)
    EngineType.YURIS -> Color(0xFF558B2F)
    EngineType.CATSYSTEM2 -> Color(0xFF6D4C41)
    EngineType.PC -> Color(0xFF455A64)
    EngineType.ANDROID_APP -> Color(0xFF2E7D32)
    EngineType.RENPY -> Color(0xFFE35B84)
    EngineType.PSP -> Color(0xFF6D4C9F)
    EngineType.NINTENDO_SWITCH -> Color(0xFFD32F2F)
    EngineType.UNKNOWN -> Color(0xFF607D8B)
}

internal fun shouldShowSaveManagement(engine: EngineType): Boolean =
    // YU-RIS 虽经外置 Winlator 启动，但存档落在游戏目录 save/，纳入统一存档管理；
    // 手动添加的类型（PC / 安卓游戏）无统一存档接口，显式排除。
    engine == EngineType.YURIS ||
        (!engine.isManual &&
            !ExternalEngineModuleRegistry.isExternalEngine(engine) &&
            ExternalEmulatorRegistry.forEngine(engine) == null)

/** 记录级判定：engine 字段损坏的安卓条目（uri 仍为 androidapp://）同样不显示存档管理。 */
internal fun shouldShowSaveManagement(game: ScanGame): Boolean =
    !AndroidAppGames.isAndroidApp(game) && shouldShowSaveManagement(game.engine)
