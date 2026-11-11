package com.tyranor.next.ui.game

import com.tyranor.next.ui.common.motion.CoverSurface
import com.tyranor.next.ui.common.motion.rememberCoverReveal
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tyranor.next.R
import com.tyranor.next.core.game.model.GameTitleTags
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.settings.AppSettingsStore
import com.tyranor.next.theme.AppComponentShape
import com.tyranor.next.theme.PageSecondaryTextColor
import com.tyranor.next.theme.glassBorder
import com.tyranor.next.ui.common.motion.BlurCrossfade
import com.tyranor.next.ui.common.motion.MotionMode
import com.tyranor.next.ui.common.motion.PressSquishDefaults
import com.tyranor.next.ui.common.motion.ProvideEntrance
import com.tyranor.next.ui.common.motion.pressSquish
import com.tyranor.next.ui.common.motion.rememberPagerSnapFling
import com.tyranor.next.ui.common.motion.rememberPagerVelocity
import com.tyranor.next.ui.common.motion.skipEntranceOnDrag
import com.tyranor.next.ui.common.motion.staggeredEntrance
import kotlinx.coroutines.launch
import com.tyranor.next.ui.common.collectSettingAsState
import com.tyranor.next.ui.common.motion.dissolve
import com.tyranor.next.ui.common.motion.dissolveTint
import com.tyranor.next.ui.common.motion.dissolvingGames

/**
 * 游戏页「列表」卡片风格（封面流）：
 * 横向轮播，居中卡片放大正面显示，两侧卡片按距中心距离做透视旋转、缩小与降透明；
 * 底部显示「引擎 - 游戏名」（游戏名遵循应用设置「卡片隐藏名称标签」）。
 */
@Composable
internal fun GameCoverFlow(
    games: List<ScanGame>,
    onGameClick: (ScanGame) -> Unit,
    onGameLongClick: (ScanGame) -> Unit,
) {
    val hideTitleTag by AppSettingsStore.gameCardTitleTagState.collectSettingAsState()
    val pagerState = rememberPagerState(pageCount = { games.size })
    val scope = rememberCoroutineScope()

    // 过滤/搜索导致列表缩短时收敛到最后一页
    LaunchedEffect(games.size) {
        if (games.isEmpty()) return@LaunchedEffect
        if (pagerState.currentPage > games.lastIndex) pagerState.scrollToPage(games.lastIndex)
    }

    // 搜索 / 过滤 / 排序换了一批游戏：这一刻起短时间内新组合的卡片（换上来的新游戏）从略小、透明处弹到原位，
    // 位置没变的旧卡片不动；之后滑动进来的卡片直接显示。首次组合不算（首屏由入场节拍负责）。
    val listChangedAt = rememberCoverBatchChange(games)

    // 首屏入场：居中卡片与两侧卡片先后升起，标题随后（停稳后与原样一致；窗口外再组合的页面直接显示）
    ProvideEntrance {
    // 背景随页面（不单独铺色）
    Box(modifier = Modifier.fillMaxSize().skipEntranceOnDrag()) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 分页区高度自适应卡片（weight fill=false，标题因此始终贴在卡片下方）。
            // 区内可用高度参与卡片宽度计算：竖版封面 3:4，若只按「容器宽度 60%」取宽，
            // 横屏/平板大屏时卡片高度会远超可用高度，整张卡被裁切且标题压到卡片上。
            BoxWithConstraints(
                modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                contentAlignment = Alignment.Center,
            ) {
                // 宽度取「容器宽度的 60%」与「可用高度换算宽度（预留 24dp 上下呼吸位）」的较小值
                val cardWidth = minOf(maxWidth * 0.60f, (maxHeight - 24.dp) * 3f / 4f)
                    .coerceAtLeast(96.dp)
                    .coerceAtMost(maxWidth)
                val horizontalPadding = ((maxWidth - cardWidth) / 2).coerceAtLeast(0.dp)
                // 甩动速度（页 / 秒）：只在图层块里读取，卡片顺势侧倾；停下归零，静止外观不变
                val pagerVelocity = rememberPagerVelocity(pagerState)
                // 「增强」档：滑动 / 甩动中暂缓封面解码（读取放在解码协程里，不订阅滚动状态）；「普通」档与原版一致，不暂缓
                val enhancedFlow = MotionMode.enhanced
                val isScrolling: () -> Boolean = remember(pagerState, enhancedFlow) {
                    if (enhancedFlow) ({ pagerState.isScrollInProgress }) else ({ false })
                }
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxWidth(),
                    pageSize = PageSize.Fixed(cardWidth),
                    contentPadding = PaddingValues(horizontal = horizontalPadding),
                    pageSpacing = 14.dp,
                    // 翻页吸附用带一点回弹的弹簧（普通档为原版默认吸附）
                    flingBehavior = rememberPagerSnapFling(pagerState),
                ) { page ->
                    val game = games.getOrNull(page) ?: return@HorizontalPager
                    key(game.uri) {
                        CoverFlowCard(
                            game = game,
                            batchChangedAt = listChangedAt,
                            onClick = {
                                if (page == pagerState.currentPage) {
                                    onGameClick(game)
                                } else {
                                    scope.launch {
                                        pagerState.centerCover(page)
                                    }
                                }
                            },
                            onLongClick = { onGameLongClick(game) },
                            isScrolling = isScrolling,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(3f / 4f)
                                .coverFlowPage(pagerState, page, pagerVelocity),
                        )
                    }
                }
            }
            CoverFlowCaption(
                game = games.getOrNull(pagerState.currentPage),
                hideTitleTag = hideTitleTag,
                modifier = Modifier
                    .fillMaxWidth()
                    .staggeredEntrance(2, distance = 14.dp, stepMillis = 45L)
                    .padding(top = 16.dp, start = 24.dp, end = 24.dp),
            )
        }
    }
    }
}

/**
 * Displays an engine placeholder until its cover is decoded. Enhanced mode fades and sharpens the
 * image in the drawing layers; ordinary mode reveals it immediately when ready.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CoverFlowCard(
    game: ScanGame,
    batchChangedAt: Long,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    isScrolling: () -> Boolean,
    modifier: Modifier = Modifier,
) {
    val cover by rememberCoverBitmap(game.coverUri, isScrolling)
    // 删除消散：游戏库删除确认后登记在 dissolvingGames 里；封面流的卡也要播放消散（与瀑布 / 网格卡一致），期间触摸由 dissolve 吞掉
    val dissolve = dissolvingGames[game.uri]
    // 封面加载完成：淡入 + 由糊变清（已缓存的封面一开始就是 1，不会闪）
    val reveal = rememberCoverReveal(cover != null, CoverSurface.Flow)
    Box(
        modifier = modifier
            .gameRemovalDust(dissolve)
            .coverBatchArrival(batchChangedAt)
            .pressSquish(scale = PressSquishDefaults.CardScale, charge = true)
            .clip(AppComponentShape)
            .dissolveTint(dissolve, MaterialTheme.colorScheme.error)
            .background(game.engine.coverColor())
            .glassBorder()
            // 点按：居中卡打开操作抽屉，侧卡先居中；长按：打开统一操作弹窗。
            // 用 combinedClickable 而非 pointerInput，保证改名/封面更新后手势读取最新游戏对象，
            // 并与瀑布流卡片共享长按触感与无障碍语义。
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        contentAlignment = Alignment.Center,
    ) {
        // 无封面：引擎色底 + 引擎名占位（与网格卡片一致）
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Tyranor", style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.7f))
            Text(
                game.engine.displayName,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        cover?.let { bitmap ->
            Image(
                bitmap = bitmap,
                contentDescription = game.title,
                contentScale = ContentScale.Crop,
                modifier = reveal.image(Modifier.fillMaxSize()),
            )
        }
    }
}

/** Shows the selected game's engine and optional stripped title, transitioning when the caption changes. */
@Composable
private fun CoverFlowCaption(
    game: ScanGame?,
    hideTitleTag: Boolean,
    modifier: Modifier = Modifier,
) {
    if (game == null) return
    val displayTitle = if (hideTitleTag) GameTitleTags.stripAll(game.title) else game.title
    val caption = stringResource(R.string.game_cover_flow_caption, game.engine.displayName, displayTitle)
    // 翻到另一张时标题模糊替换
    BlurCrossfade(caption, modifier = modifier, contentAlignment = Alignment.Center) { text ->
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = PageSecondaryTextColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
