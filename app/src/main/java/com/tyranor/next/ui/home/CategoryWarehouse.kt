package com.tyranor.next.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tyranor.next.R
import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.settings.AppSettingsStore
import com.tyranor.next.ui.common.AppTopBar
import com.tyranor.next.ui.common.SlidingTabRow
import com.tyranor.next.ui.game.GameGrid
import com.tyranor.next.ui.game.sortGames
import com.tyranor.next.ui.main.MainLibraryUiState

/** 分类栏「全部」分类的 key。 */
private const val HOME_CATEGORY_ALL = "all"

/** 分类栏「快捷」分类的 key。 */
private const val HOME_CATEGORY_QUICK = "quick"

/** 分类栏「最近」分类的 key。 */
private const val HOME_CATEGORY_RECENT = "recent"

/** 引擎分类 key 前缀，后接 [EngineType.name]（如 engine:KIRIKIRI）。 */
private const val HOME_CATEGORY_ENGINE_PREFIX = "engine:"

/**
 * 分类仓库首页（应用设置「首页样式」= 分类仓库时挂载）：
 * 顶栏下方固定横向可滚动分类栏（全部/快捷/最近/各引擎类型），内容区复用游戏页默认网格
 * （列数、封面加载优化、卡片名称标签/引擎角标设置均与游戏页一致；卡片风格恒为默认网格，
 * 不跟随游戏页的「列表（封面流）」设置）。交互同为点按开抽屉、长按启动。
 */
@Composable
internal fun CategoryWarehouseContent(
    modifier: Modifier = Modifier,
    libraryState: MainLibraryUiState,
    onGameClick: (ScanGame) -> Unit,
    onGameLongClick: (ScanGame) -> Unit,
) {
    val gameSort by AppSettingsStore.gameSortState.collectAsState()
    // 全部/引擎分类与游戏页共用排序（排序设置变化即时生效）
    val sortedGames = remember(libraryState.games, gameSort) {
        sortGames(libraryState.games, gameSort)
    }
    val allLabel = stringResource(R.string.home_category_all)
    val quickLabel = stringResource(R.string.home_category_quick)
    val recentLabel = stringResource(R.string.home_category_recent)
    val unknownEngineName = stringResource(R.string.engine_name_unknown)
    val switchEngineName = stringResource(R.string.engine_name_switch)
    val categories = remember(
        sortedGames,
        libraryState.quickLaunch,
        libraryState.recentGames,
        allLabel,
        quickLabel,
        recentLabel,
        unknownEngineName,
        switchEngineName,
    ) {
        buildHomeCategories(
            sortedGames = sortedGames,
            quickLaunch = libraryState.quickLaunch,
            recentGames = libraryState.recentGames,
            allLabel = allLabel,
            quickLabel = quickLabel,
            recentLabel = recentLabel,
            unknownEngineName = unknownEngineName,
            switchEngineName = switchEngineName,
        )
    }
    var selectedKey by rememberSaveable { mutableStateOf(HOME_CATEGORY_ALL) }
    // 分类随游戏库变化消失（如某引擎游戏被删光）时回退「全部」；加载完成前不校验，
    // 避免进程重建时把尚未来得及恢复的引擎分类误判为已消失。
    LaunchedEffect(categories, libraryState.loaded) {
        if (!libraryState.loaded) return@LaunchedEffect
        if (categories.none { it.key == selectedKey }) selectedKey = HOME_CATEGORY_ALL
    }
    // 同步派生的有效选中项：失效瞬间即回退「全部」，避免分类栏与网格出现一帧无选中/错内容
    val effectiveKey = if (categories.any { it.key == selectedKey }) selectedKey else HOME_CATEGORY_ALL
    val selected = categories.firstOrNull { it.key == effectiveKey } ?: categories.first()

    Column(modifier.fillMaxSize()) {
        AppTopBar(title = stringResource(R.string.nav_home))
        when {
            libraryState.scanning || !libraryState.loaded -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            libraryState.games.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(R.string.game_empty_title),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            else -> {
                SlidingTabRow(
                    tabs = remember(categories) { categories.map { it.label } },
                    selectedIndex = categories.indexOfFirst { it.key == effectiveKey }.coerceAtLeast(0),
                    onTabSelected = { index ->
                        categories.getOrNull(index)?.let { selectedKey = it.key }
                    },
                    modifier = Modifier.padding(top = 6.dp, bottom = 6.dp),
                    scrollable = true,
                )
                if (selected.games.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            stringResource(R.string.home_category_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    // key(effectiveKey)：切换分类即重建网格状态，保证新分类从顶部开始展示，
                    // 同时避免「先重置旧列表、后换入新列表」的键位恢复竞态；
                    // 旋转/进程重建时同一分类的滚动位置仍由 rememberLazyGridState 恢复
                    key(effectiveKey) {
                        GameGrid(
                            games = selected.games,
                            gridState = rememberLazyGridState(),
                            onGameClick = onGameClick,
                            onGameLongClick = onGameLongClick,
                        )
                    }
                }
            }
        }
    }
}

/** 分类仓库的一个分类；[games] 已按展示顺序排好（快捷/最近保持各自语义顺序）。internal：便于单测。 */
internal data class HomeCategory(
    val key: String,
    val label: String,
    val games: List<ScanGame>,
)

/**
 * 引擎分类只列游戏库中实际存在的引擎，顺序与 [EngineType] 声明一致
 * （纯函数，便于 JVM 单测）。
 */
internal fun categoryEngineTypes(games: List<ScanGame>): List<EngineType> =
    EngineType.entries.filter { engine -> games.any { it.engine == engine } }

/** 组装分类栏：全部/快捷/最近恒在，其后按库中存在的引擎追加（internal：便于单测）。 */
internal fun buildHomeCategories(
    sortedGames: List<ScanGame>,
    quickLaunch: List<ScanGame>,
    recentGames: List<ScanGame>,
    allLabel: String,
    quickLabel: String,
    recentLabel: String,
    unknownEngineName: String,
    switchEngineName: String,
): List<HomeCategory> = buildList {
    add(HomeCategory(HOME_CATEGORY_ALL, allLabel, sortedGames))
    // 快捷/最近为空时也保留分类（点击后由空态文案说明），保证分类栏结构稳定
    add(HomeCategory(HOME_CATEGORY_QUICK, quickLabel, quickLaunch))
    add(HomeCategory(HOME_CATEGORY_RECENT, recentLabel, recentGames))
    categoryEngineTypes(sortedGames).forEach { engine ->
        add(
            HomeCategory(
                key = "$HOME_CATEGORY_ENGINE_PREFIX${engine.name}",
                label = when (engine) {
                    EngineType.UNKNOWN -> unknownEngineName
                    // 短名「Switch」，避免「Nintendo Switch」占宽过长（与卡片占位一致）
                    EngineType.NINTENDO_SWITCH -> switchEngineName
                    else -> engine.displayName
                },
                games = sortedGames.filter { it.engine == engine },
            ),
        )
    }
}

