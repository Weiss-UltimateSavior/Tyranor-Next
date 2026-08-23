package com.tyranor.next.ui.pages

import android.app.Activity
import android.app.ActivityOptions
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.graphics.BitmapFactory
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.RadioButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tyranor.next.R
import com.tyranor.next.scanner.EngineLauncher
import com.tyranor.next.scanner.EngineScanner
import com.tyranor.next.scanner.EngineType
import com.tyranor.next.scanner.GameSaveManager
import com.tyranor.next.scanner.ScanGame
import com.tyranor.next.scanner.VndbCandidate
import com.tyranor.next.scanner.VndbCoverService
import com.tyranor.next.settings.AppSettingsStore
import com.tyranor.next.settings.PerGameSettingsStore
import com.tyranor.next.theme.NavWhite
import com.tyranor.next.theme.MiuixSettingsTheme
import com.tyranor.next.ui.common.glassNavBottomInset
import com.tyranor.next.ui.common.isWideScreen
import com.tyranor.next.theme.PageGrey
import com.tyranor.next.ui.common.TopBarIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.SearchBar
import top.yukonga.miuix.kmp.basic.SearchBarDefaults
import java.io.File
import java.util.Locale

@Composable
fun GameScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    AppSettingsStore.initGameSort(context)

    var games by remember { mutableStateOf(EngineScanner.loadGames(context)) }
    var scanning by remember { mutableStateOf(false) }
    var selectedGame by remember { mutableStateOf<ScanGame?>(null) }
    var launchError by remember { mutableStateOf<String?>(null) }
    var patchLaunchTarget by remember { mutableStateOf<ScanGame?>(null) }

    val gridState = rememberLazyGridState()

    fun replaceGame(updated: ScanGame) {
        val nextGames = games.map { if (it.uri == updated.uri) updated else it }
        games = nextGames
        selectedGame = selectedGame?.let { if (it.uri == updated.uri) updated else it }
        EngineScanner.saveGames(context, nextGames)
    }

    fun deleteGame(target: ScanGame) {
        val nextGames = games.filterNot { it.uri == target.uri }
        games = nextGames
        selectedGame = null
        EngineScanner.saveGames(context, nextGames)
        // 最近记录/快捷启动同步持久化移除，避免切页取消 IO 清理协程后残留脏数据
        EngineScanner.removeRecentGame(context, target.uri)
        EngineScanner.removeQuickLaunch(context, target.uri)
        // 仅清理应用内数据（每游戏设置、最近记录、封面缓存、应用内存档镜像）；不触碰游戏文件
        scope.launch(Dispatchers.IO) {
            cleanupDeletedGame(context, target)
        }
    }

    fun syncMissingCovers() {
        if (scanning) return
        scope.launch {
            scanning = true
            val current = games
            val updated = withContext(Dispatchers.IO) {
                current.map { game ->
                    val local = runCatching { EngineScanner.applyLocalCover(context, game) }.getOrDefault(game)
                    val next = runCatching { VndbCoverService.fetchBestCover(context, local) }.getOrNull()
                    if (next != null && next.coverUri != game.coverUri) {
                        next
                    } else if (local.coverUri != game.coverUri) {
                        local
                    } else {
                        game
                    }
                }
            }
            games = updated
            EngineScanner.saveGames(context, updated)
            scanning = false
        }
    }

    // 扫描游戏库：每次按扫描目录全量重建，删除/改名/移动后的旧缓存条目会被清理。
    fun scanLibrary() {
        if (scanning) return
        scope.launch {
            scanning = true
            val roots = EngineScanner.loadRoots(context)
            if (roots.isNotEmpty()) {
                val updated = EngineScanner.rescanLibrary(context)
                games = updated
                selectedGame = selectedGame?.let { selected ->
                    updated.firstOrNull { it.uri == selected.uri }
                }
            }
            scanning = false
        }
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
            // 保存根目录后立即扫描；重复/嵌套根提示
            if (EngineScanner.isDuplicateOrNestedRoot(context, u)) {
                android.widget.Toast.makeText(
                    context,
                    "该目录与已有扫描目录重复或互为子目录，可能产生重复条目",
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            }
            EngineScanner.saveRoot(context, u)
            scanLibrary()
        }
    }

    GameLibraryContent(
        modifier = modifier,
        games = games,
        scanning = scanning,
        gridState = gridState,
        dirPickerLaunch = { dirPicker.launch(null) },
        syncMissingCovers = { syncMissingCovers() },
        refreshGames = { scanLibrary() },
        onGameClick = { selectedGame = it },
        onGameLongClick = { game ->
            if (EngineLauncher.needsArtemisPatchConfirm(context, game)) {
                patchLaunchTarget = game
            } else {
                launchError = EngineLauncher.launch(context, game)
            }
        },
    )

    // ===== 点击游戏卡片的底部抽屉栏 =====
    selectedGame?.let { game ->
        GameActionsSheet(
            game = game,
            onDismiss = { selectedGame = null },
            onGameUpdated = { replaceGame(it) },
            onDeleteGame = { deleteGame(game) },
            onEngineSettings = {
                startActivityWithPageTransition(context, PerGameSettingsActivity.createIntent(context, game))
                selectedGame = null
            },
        )
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
                        launchError = EngineLauncher.launch(context, game, EngineLauncher.ArtemisPatchChoice.ALWAYS)
                    },
                ) { Text("总是") }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = {
                            patchLaunchTarget = null
                            launchError = EngineLauncher.launch(context, game, EngineLauncher.ArtemisPatchChoice.NEVER)
                        },
                    ) { Text("不再") }
                    TextButton(
                        onClick = {
                            patchLaunchTarget = null
                            launchError = EngineLauncher.launch(context, game, EngineLauncher.ArtemisPatchChoice.ONCE)
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
    scanning: Boolean,
    gridState: LazyGridState,
    dirPickerLaunch: () -> Unit,
    syncMissingCovers: () -> Unit,
    refreshGames: () -> Unit,
    onGameClick: (ScanGame) -> Unit,
    onGameLongClick: (ScanGame) -> Unit,
) {
    var showSearch by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
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
                    TopBarIcon(painterResource(R.drawable.ic_game_cover), "自动获取封面", MaterialTheme.colorScheme.primary) {
                        syncMissingCovers()
                    }
                    TopBarIcon(painterResource(R.drawable.ic_game_scan), "扫描游戏", MaterialTheme.colorScheme.primary) {
                        refreshGames()
                    }
                }
                // 搜索框：点击搜索按钮后出现在顶部栏下方
                if (showSearch) {
                    MiuixSettingsTheme {
                        SearchBar(
                            inputField = {
                                InputField(
                                    query = query,
                                    onQueryChange = { query = it },
                                    onSearch = { },
                                    expanded = false,
                                    onExpandedChange = { },
                                    leadingIcon = {
                                        Icon(
                                            modifier = Modifier
                                                .padding(start = SearchBarDefaults.LeadingIconStartPadding, end = SearchBarDefaults.LeadingIconEndPadding)
                                                .size(26.dp),
                                            painter = painterResource(R.drawable.ic_game_search),
                                            tint = MaterialTheme.colorScheme.primary,
                                            contentDescription = "Search",
                                        )
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            },
                            expanded = false,
                            onExpandedChange = { },
                            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 10.dp),
                            content = {},
                        )
                    }
                }
            }
        }

        // ===== 内容区 =====
        Box(Modifier.fillMaxSize()) {
            when {
                scanning -> {
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
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var launchError by remember { mutableStateOf<String?>(null) }
    var showVndbSearch by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showLaunchFilePicker by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showPatchConfirm by remember { mutableStateOf(false) }

    // 发起启动；Artemis 需要 PFS 基础补丁且策略为“启动时询问”时，先弹窗确认再带选择启动
    fun startLaunch(patchChoice: EngineLauncher.ArtemisPatchChoice? = null) {
        launchError = EngineLauncher.launch(context, game, patchChoice)
        if (launchError == null) onDismiss()
    }

    // 打开相册选择自定义封面
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
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
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = GameActionsSheetMaxHeight),
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
                        subtitle = game.launchFile ?: "自动",
                    ) { showLaunchFilePicker = true }
                }
            }
            item {
                val quickLaunched = EngineScanner.isQuickLaunched(context, game.uri)
                GameActionRow(
                    iconRes = R.drawable.ic_home,
                    label = if (quickLaunched) "移除快捷启动" else "添加快捷启动",
                ) {
                    if (quickLaunched) {
                        EngineScanner.removeQuickLaunch(context, game.uri)
                        onDismiss()
                    } else if (EngineScanner.addQuickLaunch(context, game)) {
                        onDismiss()
                    } else {
                        android.widget.Toast.makeText(context, "首页快捷启动已满（最多 3 个）", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
            item { GameActionRow(R.drawable.ic_sheet_search_cover, "搜索封面") { showVndbSearch = true } }
            item { GameActionRow(R.drawable.ic_sheet_edit_cover, "修改封面") { imagePicker.launch("image/*") } }
            item { GameActionRow(R.drawable.ic_sheet_rename, "名称修改") { showRenameDialog = true } }
            item {
                GameActionRow(R.drawable.ic_sheet_saves, "存档管理") {
                    startActivityWithPageTransition(context, SaveManagementActivity.createIntent(context, game))
                    onDismiss()
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
            // PSP 暂无引擎级/单游戏设置项，隐藏入口避免打开空白页
            if (game.engine != EngineType.PSP) {
                item { GameActionRow(R.drawable.ic_sheet_settings, "引擎设置", onClick = onEngineSettings) }
            }
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

    if (showVndbSearch) {
        VndbSearchDialog(
            game = game,
            onDismiss = { showVndbSearch = false },
            onBind = { candidate ->
                scope.launch {
                    launchError = "正在绑定封面…"
                    val updated = withContext(Dispatchers.IO) {
                        runCatching { VndbCoverService.bindCandidate(context, game, candidate) }.getOrNull()
                    }
                    if (updated != null) {
                        onGameUpdated(updated)
                        launchError = null
                        showVndbSearch = false
                        onDismiss()
                    } else {
                        launchError = "封面下载失败"
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

private val GameActionsSheetMaxHeight: Dp = 560.dp

@Composable
private fun RenameGameDialog(
    game: ScanGame,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var title by remember(game.uri, game.title) { mutableStateOf(game.title) }
    val normalizedTitle = title.trim()
    val canConfirm = normalizedTitle.isNotEmpty() && normalizedTitle != game.title

    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("名称修改", style = MaterialTheme.typography.titleMedium) },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                singleLine = true,
                label = { Text("游戏名称") },
                modifier = Modifier.fillMaxWidth(),
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
private fun VndbSearchDialog(
    game: ScanGame,
    onDismiss: () -> Unit,
    onBind: (VndbCandidate) -> Unit,
) {
    var keyword by remember { mutableStateOf(game.title) }
    var searching by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var candidates by remember { mutableStateOf<List<VndbCandidate>>(emptyList()) }
    val scope = rememberCoroutineScope()

    fun search() {
        val query = keyword.trim()
        if (query.isEmpty() || searching) return
        scope.launch {
            searching = true
            error = null
            val result = withContext(Dispatchers.IO) {
                runCatching { VndbCoverService.searchCandidates(query, 8) }
            }
            candidates = result.getOrDefault(emptyList())
            result.exceptionOrNull()?.let { error = it.message ?: "VNDB 搜索失败" }
            if (candidates.isEmpty() && error == null) error = "未找到匹配结果"
            searching = false
        }
    }

    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("搜索 VNDB 封面", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column {
                MiuixSettingsTheme {
                    SearchBar(
                        inputField = {
                            InputField(
                                query = keyword,
                                onQueryChange = { keyword = it },
                                onSearch = { search() },
                                expanded = false,
                                onExpandedChange = { },
                                leadingIcon = {
                                    Icon(
                                        modifier = Modifier
                                            .padding(start = SearchBarDefaults.LeadingIconStartPadding, end = SearchBarDefaults.LeadingIconEndPadding)
                                            .size(26.dp),
                                        painter = painterResource(R.drawable.ic_game_search),
                                        tint = MaterialTheme.colorScheme.primary,
                                        contentDescription = "Search",
                                    )
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        },
                        expanded = false,
                        onExpandedChange = { },
                        modifier = Modifier.fillMaxWidth(),
                        content = {},
                    )
                }
                Button(
                    onClick = { search() },
                    enabled = !searching,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                ) {
                    Text(if (searching) "搜索中…" else "搜索", style = MaterialTheme.typography.bodyMedium)
                }
                error?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                if (candidates.isNotEmpty()) {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().height(220.dp).padding(top = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        lazyItems(candidates, key = { it.id }) { candidate ->
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(PageGrey)
                                    .clickable { onBind(candidate) }
                                    .padding(10.dp),
                            ) {
                                Text(candidate.title.ifBlank { candidate.originalTitle }, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (candidate.originalTitle.isNotBlank()) {
                                    Text(candidate.originalTitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Text(
                                    listOf(candidate.id, candidate.released, candidate.developer).filter { it.isNotBlank() }.joinToString(" · "),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
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
                else -> LazyColumn(
                    modifier = Modifier.fillMaxWidth().height(260.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    lazyItems(files) { name ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { selected = name }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = selected == name,
                                onClick = { selected = name },
                            )
                            Text(
                                name,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = 10.dp),
                            )
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
        gridItems(games, key = { it.uri }) { game ->
            GameCard(
                game = game,
                onClick = { onGameClick(game) },
                onLongClick = { onGameLongClick(game) },
            )
        }
    }
}

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
    return produceState<ImageBitmap?>(initialValue = null, coverUri) {
        value = withContext(Dispatchers.IO) {
            if (coverUri.isNullOrBlank()) return@withContext null
            runCatching {
                context.contentResolver.openInputStream(android.net.Uri.parse(coverUri))?.use { input ->
                    BitmapFactory.decodeStream(input)?.asImageBitmap()
                }
            }.getOrNull()
        }
    }
}

internal fun EngineType.coverColor(): Color = when (this) {
    EngineType.KIRIKIRI -> Color(0xFF3B5998)
    EngineType.ONS -> Color(0xFF43A047)
    EngineType.TYRANO -> Color(0xFFC6443C)
    EngineType.ARTEMIS -> Color(0xFF7E57C2)
    EngineType.PSP -> Color(0xFF37474F)
    EngineType.UNKNOWN -> Color(0xFF607D8B)
}
