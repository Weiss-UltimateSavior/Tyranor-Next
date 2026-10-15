package com.tyranor.next.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tyranor.next.R
import com.tyranor.next.ui.common.SidePanelGestureGuard
import com.tyranor.next.core.game.launch.EngineLauncher
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.game.save.RpgSaveFormat
import com.tyranor.next.core.settings.AppSettingsStore
import com.tyranor.next.ui.common.AppAlertDialog
import com.tyranor.next.ui.common.AppTopBar
import com.tyranor.next.ui.common.TopBarIcon
import com.tyranor.next.ui.common.glassNavBottomInset
import com.tyranor.next.ui.common.LaunchErrorDialog
import com.tyranor.next.ui.common.LaunchErrorState
import com.tyranor.next.ui.common.toErrorState
import com.tyranor.next.ui.game.GameActionsSheet
import com.tyranor.next.ui.game.RpgSaveFormatDialog
import com.tyranor.next.ui.game.dialogArgs
import com.tyranor.next.ui.game.rpgConvertResultMessage
import com.tyranor.next.ui.game.startActivityWithPageTransition
import com.tyranor.next.ui.main.MainLibraryUiState
import com.tyranor.next.ui.settings.PerGameSettingsActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

@Composable
internal fun HomeScreen(
    modifier: Modifier = Modifier,
    libraryState: MainLibraryUiState,
    onGameUpdated: (ScanGame) -> Unit,
    onGameDeleted: (ScanGame) -> Unit,
    onQuickLaunchToggle: (ScanGame) -> Boolean,
    /** 首页 Tab 是否处于前台（四页常驻：Web 首页据此暂停/恢复，避免后台耗电）。 */
    isActive: Boolean = true,
    /** 侧栏右滑手势的让位登记处：海报轮播能往回翻时，起点落在轮播上的右滑归轮播，不唤出侧栏。 */
    sidePanelGuard: SidePanelGestureGuard? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val quickLaunch = libraryState.quickLaunch
    val homeStyle by AppSettingsStore.homeStyleState.collectAsState()
    val homeWebUrl by AppSettingsStore.homeWebUrlState.collectAsState()
    var webReloadSignal by remember { mutableIntStateOf(0) }
    var selectedGame by remember { mutableStateOf<ScanGame?>(null) }
    var launchError by remember { mutableStateOf<LaunchErrorState?>(null) }
    var patchLaunchTarget by remember { mutableStateOf<ScanGame?>(null) }
    // MV/MZ 存档格式转化确认：待转化检测结果 + 目标游戏 + 已选补丁策略（Artemis 选择后串联）
    var saveFormatTarget by remember { mutableStateOf<ScanGame?>(null) }
    var saveFormatDetection by remember { mutableStateOf<RpgSaveFormat.Detection?>(null) }
    var pendingPatchChoice by remember { mutableStateOf<EngineLauncher.ArtemisPatchChoice?>(null) }
    val saveFormatConvertedFormat = stringResource(R.string.save_format_converted_count)
    val saveFormatConvertedWithFailuresFormat = stringResource(R.string.save_format_converted_with_failures)
    val saveFormatConvertFailedMessage = stringResource(R.string.save_format_convert_failed)
    val saveBusyEngineRunningMessage = stringResource(R.string.save_busy_engine_running)

    LaunchedEffect(libraryState.games) {
        selectedGame = selectedGame?.let { selected ->
            libraryState.games.firstOrNull { it.uri == selected.uri }
        }
    }

    fun replaceGame(updated: ScanGame) {
        selectedGame = selectedGame?.let { if (it.uri == updated.uri) updated else it }
        onGameUpdated(updated)
    }

    fun deleteGame(target: ScanGame) {
        selectedGame = null
        onGameDeleted(target)
    }

    /** Artemis 选择（或无需补丁）后，再检查 MV/MZ 存档格式；有标准存档则弹窗，否则直接启动。
     *  开启存档互通时跳过弹窗——启动前同步已覆盖其语义。 */
    fun launchWithSaveFormatGate(game: ScanGame, patchChoice: EngineLauncher.ArtemisPatchChoice?) {
        scope.launch {
            if (EngineLauncher.isRpgSaveInteropEnabled(context, game)) {
                launchError = EngineLauncher.launch(context, game, patchChoice).toErrorState(context)
                return@launch
            }
            val pending = EngineLauncher.rpgSaveFormatPending(context, game)
            if (pending != null) {
                saveFormatTarget = game
                saveFormatDetection = pending
                pendingPatchChoice = patchChoice
            } else {
                launchError = EngineLauncher.launch(context, game, patchChoice).toErrorState(context)
            }
        }
    }

    // 点按直接启动游戏；Artemis 按既有策略弹出补丁确认（与游戏页长按启动一致）。
    fun launchGame(game: ScanGame) {
        scope.launch {
            if (EngineLauncher.needsArtemisPatchConfirm(context, game)) {
                patchLaunchTarget = game
            } else {
                launchWithSaveFormatGate(game, null)
            }
        }
    }

    /** 用户确认转化后执行转化（best-effort），随后按既定策略启动。 */
    fun resolveSaveFormat(target: ScanGame, convert: Boolean) {
        val detection = saveFormatDetection
        val patchChoice = pendingPatchChoice
        saveFormatTarget = null
        saveFormatDetection = null
        pendingPatchChoice = null
        scope.launch {
            if (convert && detection != null) {
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

    // ===== Web 首页：应用设置「首页样式切换」为网页时，整页替换为 WebView（保留顶栏） =====
    if (homeStyle == AppSettingsStore.HOME_STYLE_WEB) {
        Column(modifier.fillMaxSize()) {
            AppTopBar(
                title = stringResource(R.string.nav_home),
                trailing = {
                    TopBarIcon(
                        painterResource(R.drawable.ic_refresh),
                        stringResource(R.string.home_web_refresh_content_description),
                        MaterialTheme.colorScheme.primary,
                    ) { webReloadSignal++ }
                },
            )
            HomeWebView(
                url = homeWebUrl,
                isActive = isActive,
                reloadSignal = webReloadSignal,
                // 悬浮玻璃底栏不占布局：网页底部留白避免内容被永久遮挡（侧栏布局该函数返回 0）
                modifier = Modifier.fillMaxSize().padding(bottom = glassNavBottomInset()),
            )
        }
        return
    }

    // ===== 原生 / 分类仓库：按应用设置「首页样式」二选一（Web 已在上面分支返回） =====
    if (homeStyle == AppSettingsStore.HOME_STYLE_CATEGORY) {
        CategoryWarehouseContent(
            modifier = modifier,
            libraryState = libraryState,
            onGameClick = { selectedGame = it },
            onGameLongClick = { launchGame(it) },
        )
    } else {
        NativeHomeContent(
            modifier = modifier,
            libraryState = libraryState,
            onGameClick = { selectedGame = it },
            onGameLongClick = { launchGame(it) },
            // 首页不在前台时不登记，避免挡住其他页的侧栏手势
            sidePanelGuard = sidePanelGuard.takeIf { isActive },
        )
    }

    // ===== 与游戏页统一：点按打开操作抽屉，长按直接启动 =====
    selectedGame?.let { game ->
        key(game.uri) {
            GameActionsSheet(
                game = game,
                onDismiss = { selectedGame = null },
                onGameUpdated = { replaceGame(it) },
                onDeleteGame = { deleteGame(game) },
                quickLaunched = quickLaunch.any { it.uri == game.uri },
                onQuickLaunchToggle = { onQuickLaunchToggle(game) },
                onEngineSettings = {
                    startActivityWithPageTransition(context, PerGameSettingsActivity.createIntent(context, game))
                    selectedGame = null
                },
            )
        }
    }

    // ===== Artemis 首次启动补丁确认（与游戏页一致） =====
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
                        target?.let { launchWithSaveFormatGate(it, EngineLauncher.ArtemisPatchChoice.ALWAYS) }
                    },
                ) { Text(stringResource(R.string.game_patch_always)) }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = {
                            val target = patchLaunchTarget
                            patchLaunchTarget = null
                            target?.let { launchWithSaveFormatGate(it, EngineLauncher.ArtemisPatchChoice.NEVER) }
                        },
                    ) { Text(stringResource(R.string.game_patch_never)) }
                    TextButton(
                        onClick = {
                            val target = patchLaunchTarget
                            patchLaunchTarget = null
                            target?.let { launchWithSaveFormatGate(it, EngineLauncher.ArtemisPatchChoice.ONCE) }
                        },
                    ) { Text(stringResource(R.string.game_patch_once)) }
                }
            },
        )
    }

    // ===== MV/MZ 存档格式转化确认（标准 → Tyranor）；点遮罩 = 保持原样启动 =====
    saveFormatTarget?.let { target ->
        saveFormatDetection?.let { detection ->
            val (standardCount, hashedCount) = detection.dialogArgs()
            RpgSaveFormatDialog(
                standardCount = standardCount,
                hashedCount = hashedCount,
                onChoice = { convert -> resolveSaveFormat(target, convert) },
            )
        }
    }

    launchError?.let { state ->
        LaunchErrorDialog(state = state, onDismiss = { launchError = null })
    }
}

/** 随机轮播的数量。 */
private const val RANDOM_CAROUSEL_SIZE = 10

/**
 * 随机来源：按「日期 + 游戏 uri」的混合哈希排序取前 [limit] 个。同一天结果固定（刷新、重进、改封面都不变），
 * 次日自动换一批；库里增删游戏只影响该游戏自己是否入选，不会整体重排。（纯函数，便于单测）
 */
internal fun pickDailyRandom(games: List<ScanGame>, epochDay: Long, limit: Int): List<ScanGame> =
    games.map { it to mixHash(epochDay * 31 + it.uri.hashCode()) }.sortedBy { it.second }.take(limit).map { it.first }

/** splitmix64 终结函数：让相邻输入（相邻日期、相似路径）得到彼此无关的排序键。 */
private fun mixHash(x: Long): Long {
    var z = x + -7046029254386353131L
    z = (z xor (z ushr 30)) * -4658895280553007687L
    z = (z xor (z ushr 27)) * -7723592293110705685L
    return z xor (z ushr 31)
}

/**
 * 原生首页（应用设置「首页样式」= 默认时挂载）：海报叠放轮播 + 当前游戏信息条，整页上半部分铺当前海报的模糊光幕。
 * 轮播来源由应用设置「首页轮播来源」决定（最近打开 / 快捷启动 / 随机）。「最近」尚无记录时先用当日随机兜底，
 * 避免新装用户看到空首页。
 * 交互与游戏页统一：点按当前海报开操作抽屉（改设置、加入快捷启动等）、长按直接启动；点按其他海报只滑到该海报。
 */
@Composable
private fun NativeHomeContent(
    modifier: Modifier,
    libraryState: MainLibraryUiState,
    onGameClick: (ScanGame) -> Unit,
    onGameLongClick: (ScanGame) -> Unit,
    sidePanelGuard: SidePanelGestureGuard?,
) {
    val mode by AppSettingsStore.homeCarouselModeState.collectAsState()
    val epochDay = LocalDate.now().toEpochDay()
    // 按需才计算：整库排序只在「随机」或「最近」为空兜底时需要，避免大库每次游戏列表变化都白排一遍
    val dailyRandom = remember(libraryState.games, epochDay) {
        lazy { pickDailyRandom(libraryState.games, epochDay, RANDOM_CAROUSEL_SIZE) }
    }
    val shown = when (mode) {
        AppSettingsStore.HOME_CAROUSEL_QUICK -> libraryState.quickLaunch
        AppSettingsStore.HOME_CAROUSEL_RANDOM -> dailyRandom.value
        else -> libraryState.recentGames.ifEmpty { dailyRandom.value }
    }
    // 切换来源时从第一张开始
    val pagerState = key(mode) { rememberPagerState { shown.size } }

    Box(modifier.fillMaxSize()) {
        PosterBackdrop(
            games = shown,
            pagerState = pagerState,
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().fillMaxHeight(0.6f),
        )
        Column(Modifier.fillMaxSize()) {
            AppTopBar(title = stringResource(R.string.nav_home))
            when {
                !libraryState.loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                shown.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(
                            if (mode == AppSettingsStore.HOME_CAROUSEL_QUICK) R.string.home_quick_launch_empty
                            else R.string.game_empty_title,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                }
                else -> Column(
                    // 内容放不下时可纵向滚动；底部给悬浮导航留白
                    Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(top = 12.dp, bottom = 16.dp + glassNavBottomInset()),
                ) {
                    PosterCarousel(
                        games = shown,
                        pagerState = pagerState,
                        onGameClick = onGameClick,
                        onGameLongClick = onGameLongClick,
                        sidePanelGuard = sidePanelGuard,
                    )
                }
            }
        }
    }
}
