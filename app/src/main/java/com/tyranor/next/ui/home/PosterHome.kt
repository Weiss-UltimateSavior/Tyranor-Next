package com.tyranor.next.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tyranor.next.R
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.settings.AppSettingsStore
import com.tyranor.next.theme.AppComponentShape
import com.tyranor.next.theme.NavWhite
import com.tyranor.next.ui.common.AppTopBar
import com.tyranor.next.ui.common.SidePanelGestureGuard
import com.tyranor.next.ui.common.glassNavBottomInset
import com.tyranor.next.ui.main.MainLibraryUiState
import java.time.LocalDate

/** 「推荐」来源的数量。 */
private const val RECOMMENDED_CAROUSEL_SIZE = 10

/**
 * 推荐来源：按「日期 + 游戏 uri」的混合哈希排序取前 [limit] 个。同一天结果固定（刷新、重进、改封面都不变），
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
 * 海报轮播首页（应用设置「首页样式」= 海报轮播时挂载）：海报叠放轮播 + 当前游戏信息条 + 统计卡片，
 * 整页上半部分铺当前海报的模糊光幕。轮播来源由应用设置「首页轮播来源」决定（最近打开 / 快捷启动 / 推荐）。
 * 「最近」尚无记录时先用当日推荐兜底，避免新装用户看到空首页。
 * 交互与游戏页统一：点按当前海报开操作抽屉（改设置、加入快捷启动等）、长按直接启动；点按其他海报只滑到该海报。
 */
@Composable
internal fun PosterHomeContent(
    modifier: Modifier,
    libraryState: MainLibraryUiState,
    onGameClick: (ScanGame) -> Unit,
    onGameLongClick: (ScanGame) -> Unit,
    sidePanelGuard: SidePanelGestureGuard?,
) {
    val mode by AppSettingsStore.homeCarouselModeState.collectAsState()
    val epochDay = LocalDate.now().toEpochDay()
    // 按需才计算：整库排序只在「推荐」或「最近」为空兜底时需要，避免大库每次游戏列表变化都白排一遍
    val dailyPick = remember(libraryState.games, epochDay) {
        lazy { pickDailyRandom(libraryState.games, epochDay, RECOMMENDED_CAROUSEL_SIZE) }
    }
    val shown = when (mode) {
        AppSettingsStore.HOME_CAROUSEL_QUICK -> libraryState.quickLaunch
        AppSettingsStore.HOME_CAROUSEL_RANDOM -> dailyPick.value
        else -> libraryState.recentGames.ifEmpty { dailyPick.value }
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
                    HomeStatsCard(
                        libraryState = libraryState,
                        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 24.dp),
                    )
                }
            }
        }
    }
}

/** 统计卡片：游戏数量 / 最后一次运行的游戏 / 引擎数量，三项均取自已有数据。 */
@Composable
private fun HomeStatsCard(libraryState: MainLibraryUiState, modifier: Modifier = Modifier) {
    val lastPlayed = libraryState.recentGames.firstOrNull()?.title
    val engineCount = remember(libraryState.games) { libraryState.games.map { it.engine }.distinct().size }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(NavWhite, AppComponentShape)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        StatItem(stringResource(R.string.home_stats_games), libraryState.games.size.toString(), Modifier.weight(1f))
        StatItem(stringResource(R.string.home_stats_last_played), lastPlayed ?: "-", Modifier.weight(2f))
        StatItem(stringResource(R.string.home_stats_engines), engineCount.toString(), Modifier.weight(1f))
    }
}

@Composable
private fun StatItem(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(end = 8.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
