package com.tyranor.next.ui.game

import android.app.Activity
import android.app.ActivityOptions
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
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
import com.tyranor.next.core.game.scan.EngineScanner
import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.engine.external.ExternalEngineModuleRegistry
import com.tyranor.next.core.game.save.GameSaveManager
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.cover.VndbCoverService
import com.tyranor.next.core.cover.stableKey
import com.tyranor.next.core.settings.AppSettingsStore
import com.tyranor.next.core.auth.HikarinagiAuthStore
import com.tyranor.next.core.settings.PerGameSettingsStore
import com.tyranor.next.theme.MiuixSettingsTheme
import com.tyranor.next.theme.NavWhite
import com.tyranor.next.theme.PageGrey
import com.tyranor.next.theme.TextColor
import com.tyranor.next.ui.common.AppAlertDialog
import com.tyranor.next.ui.common.AppNavItem
import com.tyranor.next.ui.common.AppSearchField
import com.tyranor.next.ui.common.TopBarIcon
import com.tyranor.next.ui.common.glassNavBottomInset
import com.tyranor.next.ui.common.isWideScreen
import com.tyranor.next.ui.cover.coverSourceTitle
import com.tyranor.next.ui.main.MainLibraryUiState
import com.tyranor.next.ui.patch.KrkrOnlinePatchActivity
import com.tyranor.next.ui.save.SaveManagementActivity
import com.tyranor.next.ui.settings.PerGameSettingsActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.Locale
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
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val games = libraryState.games
    var selectedGameUri by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedGame = remember(games, selectedGameUri) {
        selectedGameUri?.let { uri -> games.firstOrNull { it.uri == uri } }
    }
    var launchError by remember { mutableStateOf<String?>(null) }
    var patchLaunchTarget by remember { mutableStateOf<ScanGame?>(null) }

    val gridState = rememberLazyGridState()
    val scrapeTaskState = CoverScrapeTaskManager.state.value

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

    fun syncMissingCovers() {
        if (libraryState.scanning || scrapeTaskState.running) return
        if (!CoverScrapeTaskManager.start(context, games)) {
            android.widget.Toast.makeText(context, "批量刮削正在进行", android.widget.Toast.LENGTH_SHORT).show()
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
            EngineScanner.saveRoot(context, u)
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
            if (EngineLauncher.needsArtemisPatchConfirm(context, game)) {
                patchLaunchTarget = game
            } else {
                scope.launch { launchError = EngineLauncher.launch(context, game) }
            }
        },
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
                    "应用自动补丁",
                    style = MaterialTheme.typography.titleMedium,
                )
            },
            text = {
                Text(
                    "「${game.title}」的启动文件打包在 .pfs 归档内，首次启动需要解出少量基础文件" +
                        "（system.ini、窗口配置与视频）并适配 Android 平台。是否应用补丁？",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(
                        onClick = {
                            patchLaunchTarget = null
                            scope.launch {
                                launchError = EngineLauncher.launch(context, game, EngineLauncher.ArtemisPatchChoice.ALWAYS)
                            }
                    },
                ) { Text("总是") }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = {
                            patchLaunchTarget = null
                            scope.launch {
                                launchError = EngineLauncher.launch(context, game, EngineLauncher.ArtemisPatchChoice.NEVER)
                            }
                        },
                    ) { Text("不再") }
                    TextButton(
                        onClick = {
                            patchLaunchTarget = null
                            scope.launch {
                                launchError = EngineLauncher.launch(context, game, EngineLauncher.ArtemisPatchChoice.ONCE)
                            }
                        },
                    ) { Text("本次") }
                }
            },
        )
    }

    launchError?.let { message ->
        AppAlertDialog(
            onDismissRequest = { launchError = null },
            title = { Text("启动失败", style = MaterialTheme.typography.titleMedium) },
            text = { Text(message, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(onClick = { launchError = null }) { Text("确定") }
            },
        )
    }
}

private fun sortGames(games: List<ScanGame>, sortMode: String): List<ScanGame> {
    return when (sortMode) {
        AppSettingsStore.GAME_SORT_BRACKET_TAG -> games.sortedWith(
            compareBy<ScanGame> { bracketTag(it.title).isBlank() }
                .thenBy { bracketTag(it.title).lowercase(Locale.ROOT) }
                .thenBy { titleSortKey(it.title) },
        )
        else -> games.sortedBy { titleSortKey(it.title) }
    }
}

private fun bracketTag(title: String): String {
    val match = Regex("""【([^】]+)】|\[([^\]]+)]""").find(title) ?: return ""
    return (match.groups[1]?.value ?: match.groups[2]?.value).orEmpty().trim()
}

private fun titleSortKey(title: String): String =
    title.lowercase(Locale.ROOT).trim()

/** 删除游戏后清理应用内关联数据（设置/最近记录/快捷启动/封面/存档镜像），绝不触碰游戏文件。 */
internal fun cleanupDeletedGame(context: android.content.Context, target: ScanGame) {
    PerGameSettingsStore.clear(context, target.uri)
    EngineScanner.removeRecentGame(context, target.uri)
    EngineScanner.removeQuickLaunch(context, target.uri)
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
    if (context is Activity) {
        val options = ActivityOptions.makeCustomAnimation(
            context,
            R.anim.page_slide_in_from_bottom,
            R.anim.page_slide_out_to_top,
        )
        context.startActivity(intent, options.toBundle())
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
) {
    var showSearch by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val gameSort = AppSettingsStore.gameSortState.value
    val sortedGames = remember(games, gameSort) { sortGames(games, gameSort) }
    val filteredGames = remember(sortedGames, query) {
        val q = query.trim()
        if (q.isEmpty()) sortedGames else sortedGames.filter { it.title.contains(q, ignoreCase = true) }
    }

    Column(modifier.fillMaxSize()) {
        // ===== 顶部栏：页面背景色，标题居左 + 右侧四个图标按钮 =====
        Column(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)) {
            Column(modifier = Modifier.fillMaxWidth().statusBarsPadding()) {
                Row(
                    modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "游戏",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.weight(1f),
                    )
                    TopBarIcon(painterResource(R.drawable.ic_game_search), "搜索游戏", MaterialTheme.colorScheme.primary) {
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
                                    .semantics { contentDescription = "正在批量刮削封面" },
                                color = MaterialTheme.colorScheme.primary,
                                strokeWidth = 2.dp,
                            )
                        }
                    } else {
                        TopBarIcon(painterResource(R.drawable.ic_game_cover), "批量刮削封面", MaterialTheme.colorScheme.primary) {
                            syncMissingCovers()
                        }
                    }
                    TopBarIcon(painterResource(R.drawable.ic_game_scan), "扫描游戏", MaterialTheme.colorScheme.primary) {
                        refreshGames()
                    }
                }
                // 搜索框：点击搜索按钮后出现在顶部栏下方
                if (showSearch) {
                    AppSearchField(
                        query = query,
                        onQueryChange = { query = it },
                        modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 10.dp),
                    )
                }
            }
        }

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
                        Text("暂无游戏", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "点击添加文件夹并扫描",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        Button(
                            onClick = { dirPickerLaunch() },
                            modifier = Modifier.padding(top = 16.dp),
                        ) { Text("添加文件夹") }
                    }
                }
                else -> {
                    if (filteredGames.isEmpty()) {
                        Text(
                            "未找到匹配的游戏",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.Center),
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
    var launchError by remember(game.uri) { mutableStateOf<String?>(null) }
    var showCoverSourcePicker by rememberSaveable(game.uri) { mutableStateOf(false) }
    var coverSearchSource by rememberSaveable(game.uri) { mutableStateOf<String?>(null) }
    var coverBinding by remember { mutableStateOf(false) }
    var coverBindError by rememberSaveable(game.uri) { mutableStateOf<String?>(null) }
    var showDeleteConfirm by rememberSaveable(game.uri) { mutableStateOf(false) }
    var showLaunchFilePicker by rememberSaveable(game.uri) { mutableStateOf(false) }
    var showRenameDialog by rememberSaveable(game.uri) { mutableStateOf(false) }
    var showPatchConfirm by rememberSaveable(game.uri) { mutableStateOf(false) }

    fun isBatchScrapingActive(): Boolean {
        if (!CoverScrapeTaskManager.state.value.running) return false
        android.widget.Toast.makeText(context, "批量刮削正在进行", android.widget.Toast.LENGTH_SHORT).show()
        return true
    }

    // 发起启动；Artemis 需要 PFS 基础补丁且策略为“启动时询问”时，先弹窗确认再带选择启动
    fun startLaunch(patchChoice: EngineLauncher.ArtemisPatchChoice? = null) {
        scope.launch {
            launchError = EngineLauncher.launch(context, game, patchChoice)
            if (launchError == null) onDismiss()
        }
    }

    // 打开相册选择自定义封面
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        if (isBatchScrapingActive()) return@rememberLauncherForActivityResult
        scope.launch {
            launchError = "正在设置封面…"
            val updated = withContext(Dispatchers.IO) {
                runCatching { VndbCoverService.saveCustomCover(context, game, uri) }.getOrNull()
            }
            if (updated != null) {
                onGameUpdated(updated)
                launchError = null
                onDismiss()
            } else {
                launchError = "封面设置失败"
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = { WindowInsets(0.dp) },
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
                .heightIn(max = sheetMaxHeight),
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
                GameActionRow(R.drawable.ic_sheet_launch, "启动游戏") {
                    if (EngineLauncher.needsArtemisPatchConfirm(context, game)) {
                        showPatchConfirm = true
                    } else {
                        startLaunch()
                    }
                }
            }
            if (game.engine == EngineType.KIRIKIRI) {
                item {
                    GameActionRow(
                        iconRes = R.drawable.ic_sheet_launch_file,
                        label = "启动文件",
                        subtitle = game.launchFile ?: "自动 - 如有错误请手动选择启动文件",
                    ) { showLaunchFilePicker = true }
                }
            }
            item {
                GameActionRow(
                    iconRes = R.drawable.ic_home,
                    label = if (quickLaunched) "移除快捷启动" else "添加快捷启动",
                ) {
                    if (onQuickLaunchToggle()) {
                        onDismiss()
                    } else {
                        android.widget.Toast.makeText(context, "首页快捷启动已满（最多 3 个）", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
            item {
                GameActionRow(R.drawable.ic_sheet_search_cover, "搜索封面") {
                    if (!isBatchScrapingActive()) showCoverSourcePicker = true
                }
            }
            item {
                GameActionRow(R.drawable.ic_sheet_edit_cover, "修改封面") {
                    if (!isBatchScrapingActive()) imagePicker.launch("image/*")
                }
            }
            item { GameActionRow(R.drawable.ic_sheet_rename, "名称修改") { showRenameDialog = true } }
            if (shouldShowSaveManagement(game.engine)) {
                item {
                    GameActionRow(R.drawable.ic_sheet_saves, "存档管理") {
                        startActivityWithPageTransition(context, SaveManagementActivity.createIntent(context, game))
                        onDismiss()
                    }
                }
            }
            if (game.engine == EngineType.KIRIKIRI) {
                item {
                    GameActionRow(R.drawable.ic_sheet_patch, "在线补丁") {
                        startActivityWithPageTransition(context, KrkrOnlinePatchActivity.createIntent(context, game))
                        onDismiss()
                    }
                }
            }
            item { GameActionRow(R.drawable.ic_sheet_settings, "引擎设置", onClick = onEngineSettings) }
            item { GameActionRow(R.drawable.ic_sheet_delete, "删除游戏", danger = true) { showDeleteConfirm = true } }

            launchError?.let {
                item {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                    )
                }
            }

            // 底部安全区留白
            item { Box(Modifier.fillMaxWidth().navigationBarsPadding().height(16.dp)) }
        }
    }

    // ===== Artemis 自动补丁确认：总是（记住 auto）/ 本次 / 不再（记住 off）；点遮罩取消 = 不启动 =====
    if (showPatchConfirm) {
        AppAlertDialog(
            onDismissRequest = { showPatchConfirm = false },
            title = { Text("应用自动补丁", style = MaterialTheme.typography.titleMedium) },
            text = {
                Text(
                    "「${game.title}」的启动文件打包在 .pfs 归档内，首次启动需要解出少量基础文件" +
                        "（system.ini、窗口配置与视频）并适配 Android 平台。是否应用补丁？",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showPatchConfirm = false
                        startLaunch(EngineLauncher.ArtemisPatchChoice.ALWAYS)
                    },
                ) { Text("总是") }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = {
                            showPatchConfirm = false
                            startLaunch(EngineLauncher.ArtemisPatchChoice.NEVER)
                        },
                    ) { Text("不再") }
                    TextButton(
                        onClick = {
                            showPatchConfirm = false
                            startLaunch(EngineLauncher.ArtemisPatchChoice.ONCE)
                        },
                    ) { Text("本次") }
                }
            },
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
                            runCatching { CoverScraperService.bindCoverCandidate(context, game, candidate) }.getOrNull()
                        }
                        coverBinding = false
                        if (updated != null) {
                            onGameUpdated(updated)
                            coverSearchSource = null
                            onDismiss()
                        } else {
                            coverBindError = "封面下载失败"
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

    if (showDeleteConfirm) {
        AppAlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("删除游戏", style = MaterialTheme.typography.titleMedium) },
            text = {
                Text(
                    "将移除「${game.title}」的应用内记录、设置与缓存，不会删除游戏文件。",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    onDeleteGame()
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") }
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
        title = { Text("名称修改", style = MaterialTheme.typography.titleMedium) },
        text = {
            // 统一 Miuix 风格输入框（AppSearchField）；键盘“搜索/完成”动作直接保存（内容有效时）
            AppSearchField(
                query = title,
                onQueryChange = { title = it },
                onSearch = { if (canConfirm) onConfirm(normalizedTitle) },
                leadingIcon = painterResource(R.drawable.ic_sheet_rename),
                iconContentDescription = "Rename",
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(normalizedTitle) },
                enabled = canConfirm,
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun CoverSourcePickerDialog(
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    val context = LocalContext.current
    val authVersion = HikarinagiAuthStore.statusVersion.value
    val sources = remember(AppSettingsStore.coverScraperSettingsVersion.value) {
        AppSettingsStore.getCoverScraperSourceOrder(context)
    }
    val authStatus = remember(authVersion) { HikarinagiAuthStore.getStatus(context) }

    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择封面来源", style = MaterialTheme.typography.titleMedium) },
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
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } },
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
    var keyword by rememberSaveable(source, game.uri) { mutableStateOf(game.title) }
    var searching by remember { mutableStateOf(false) }
    var error by rememberSaveable(source, game.uri) { mutableStateOf<String?>(null) }
    var candidates by rememberSaveable(source, game.uri, stateSaver = CoverSearchCandidatesSaver) {
        mutableStateOf(emptyList<CoverSearchCandidate>())
    }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val imeVisible = WindowInsets.ime.getBottom(density) > 0

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
                        if (candidates.isEmpty()) error = "未找到匹配结果"
                    }
                    is CoverSearchResult.Failure -> {
                        error = result.message
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "封面搜索失败"
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
                .background(Color.Black.copy(alpha = 0.5f)),
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
                        .clip(RoundedCornerShape(8.dp))
                        .background(NavWhite)
                        .pointerInput(Unit) { detectTapGestures { } },
                ) {
                    Column(Modifier.fillMaxSize()) {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "搜索 ${coverSourceTitle(source)} 封面",
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
                                    "正在搜索封面…",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 8.dp),
                                )
                            }
                            if (binding) {
                                Text(
                                    "正在绑定封面…",
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
                                Text("关闭", style = MaterialTheme.typography.bodyMedium)
                            }
                            TextButton(
                                onClick = { search() },
                                enabled = canSearch,
                            ) {
                                Text(if (searching) "搜索中…" else "搜索", style = MaterialTheme.typography.bodyMedium)
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
                .clip(RoundedCornerShape(8.dp))
                .background(PageGrey),
            contentAlignment = Alignment.Center,
        ) {
            when (val state = previewState) {
                CoverPreviewState.Failed -> Text(
                    "无预览",
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
                    .clip(RoundedCornerShape(8.dp))
                    .background(NavWhite.copy(alpha = 0.9f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
            candidate.score?.takeIf { it > 0 }?.let { score ->
                Text(
                    "票数 $score",
                    style = MaterialTheme.typography.bodyMedium,
                    color = NavWhite,
                    maxLines = 1,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(TextColor.copy(alpha = 0.56f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        Text(
            "使用",
            style = MaterialTheme.typography.bodyMedium,
            color = NavWhite,
            maxLines = 1,
            modifier = Modifier
                .align(Alignment.End)
                .clip(RoundedCornerShape(8.dp))
                .background(TextColor.copy(alpha = 0.56f))
                .padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

private fun coverSourcePickerSummary(source: String, enabled: Boolean, needsHikarinagiLogin: Boolean): String = when {
    !enabled -> "已在封面刮削设置中关闭"
    needsHikarinagiLogin -> "需要先在封面刮削设置中登录"
    source == AppSettingsStore.COVER_SOURCE_HIKARINAGI -> "使用已授权账号搜索 Hikarinagi 封面"
    source == AppSettingsStore.COVER_SOURCE_BANGUMI -> "搜索 Bangumi 条目封面"
    source == AppSettingsStore.COVER_SOURCE_STEAM -> "搜索 Steam 商店竖版封面"
    source == AppSettingsStore.COVER_SOURCE_VNDB -> "搜索 VNDB 封面"
    else -> "搜索此来源"
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
            val names = EngineLauncher.listKrLaunchFiles(context, game)
            val current = EngineLauncher.currentKrLaunchFileName(context, game)
            names to current
        }
        files = names
        selected = current?.takeIf { names.contains(it) }
        loading = false
    }

    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("启动文件", style = MaterialTheme.typography.titleMedium) },
        text = {
            when {
                loading -> Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                files.isEmpty() -> Text(
                    "目录中未找到 xp3 或 exe 文件",
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
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(NavWhite)
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
            ) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun GameActionRow(
    iconRes: Int,
    label: String,
    subtitle: String? = null,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(NavWhite)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 16.5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(iconRes),
            contentDescription = null,
            colorFilter = ColorFilter.tint(if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary),
            modifier = Modifier.size(24.dp),
        )
        Column(Modifier.padding(start = 20.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (danger) MaterialTheme.colorScheme.error else Color.Unspecified,
            )
            subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
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
    // 大屏（横屏/平板）一行六个卡片，避免卡片被撑得过大；窄屏保持一行三个
    val columns = if (isWideScreen()) 6 else 3
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
) {
    Column(modifier) {
        val coverBitmap by rememberCoverBitmap(game.coverUri)
        val pressModifier = if (onLongClick != null) {
            Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
        } else {
            Modifier.clickable(onClick = onClick)
        }
        // 卡片 1:3（高:宽 = 4:3 立式封面，一行三列）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .clip(RoundedCornerShape(8.dp))
                .background(game.engine.coverColor())
                .then(pressModifier),
            contentAlignment = Alignment.Center,
        ) {
            val bmp = coverBitmap
            if (bmp != null) {
                Image(
                    bitmap = bmp,
                    contentDescription = game.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "Tyranor",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.7f),
                    )
                    Text(
                        game.engine.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
        Text(
            game.title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
    }
}

@Composable
internal fun rememberCoverBitmap(coverUri: String?): androidx.compose.runtime.State<ImageBitmap?> {
    val context = LocalContext.current
    val cached = coverUri?.let(CoverBitmapCache::get)
    return produceState<ImageBitmap?>(initialValue = cached?.asImageBitmap(), coverUri) {
        if (cached != null || coverUri.isNullOrBlank()) return@produceState
        value = CoverThumbnailLoader.load(context.applicationContext, coverUri)?.asImageBitmap()
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
    private val coordinator = BoundedKeyedLoader<String>(parallelism = 2)

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
    EngineType.RPG_MV -> Color(0xFF2E7D6E)
    EngineType.RPG_MZ -> Color(0xFF1976D2)
    EngineType.VN -> Color(0xFF8E5A9E)
    EngineType.WEB_OTHER -> Color(0xFF546E7A)
    EngineType.ARTEMIS -> Color(0xFF7E57C2)
    EngineType.RENPY -> Color(0xFFE35B84)
    EngineType.UNKNOWN -> Color(0xFF607D8B)
}

internal fun shouldShowSaveManagement(engine: EngineType): Boolean =
    !ExternalEngineModuleRegistry.isExternalEngine(engine)
