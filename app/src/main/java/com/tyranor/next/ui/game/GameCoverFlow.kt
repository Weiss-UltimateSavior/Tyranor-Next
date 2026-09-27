package com.tyranor.next.ui.game

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.tyranor.next.R
import com.tyranor.next.core.game.model.GameTitleTags
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.settings.AppSettingsStore
import com.tyranor.next.theme.AppComponentShape
import com.tyranor.next.theme.glassBorder
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue
import kotlin.math.min

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
    val hideTitleTag by AppSettingsStore.gameCardTitleTagState.collectAsState()
    val pagerState = rememberPagerState(pageCount = { games.size })
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    // 过滤/搜索导致列表缩短时收敛到最后一页
    LaunchedEffect(games.size) {
        if (games.isEmpty()) return@LaunchedEffect
        if (pagerState.currentPage > games.lastIndex) pagerState.scrollToPage(games.lastIndex)
    }

    // 背景随页面（不单独铺色）
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val cardWidth = maxWidth * 0.60f
        val horizontalPadding = (maxWidth - cardWidth) / 2
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                pageSize = PageSize.Fixed(cardWidth),
                contentPadding = PaddingValues(horizontal = horizontalPadding),
                pageSpacing = 14.dp,
            ) { page ->
                val game = games.getOrNull(page) ?: return@HorizontalPager
                val offset = (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction
                val absOffset = min(offset.absoluteValue, 2f)
                key(game.uri) {
                    CoverFlowCard(
                        game = game,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(3f / 4f)
                            .zIndex(1f - absOffset)
                            .graphicsLayer {
                                cameraDistance = 16f * density.density
                                rotationY = (-offset.coerceIn(-1f, 1f)) * 38f
                                val scale = 1f - 0.22f * min(absOffset, 1f)
                                scaleX = scale
                                scaleY = scale
                                alpha = 1f - 0.45f * min(absOffset, 1f)
                            }
                            .pointerInput(page) {
                                detectTapGestures(
                                    onTap = {
                                        if (page == pagerState.currentPage) {
                                            onGameClick(game)
                                        } else {
                                            scope.launch { pagerState.animateScrollToPage(page) }
                                        }
                                    },
                                    onLongPress = { onGameLongClick(game) },
                                )
                            },
                    )
                }
            }
            CoverFlowCaption(
                game = games.getOrNull(pagerState.currentPage),
                hideTitleTag = hideTitleTag,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp, start = 24.dp, end = 24.dp),
            )
        }
    }
}

@Composable
private fun CoverFlowCard(
    game: ScanGame,
    modifier: Modifier = Modifier,
) {
    val cover by rememberCoverBitmap(game.coverUri)
    Box(
        modifier = modifier
            .clip(AppComponentShape)
            .background(game.engine.coverColor())
            .glassBorder(),
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
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun CoverFlowCaption(
    game: ScanGame?,
    hideTitleTag: Boolean,
    modifier: Modifier = Modifier,
) {
    if (game == null) return
    val displayTitle = if (hideTitleTag) GameTitleTags.stripAll(game.title) else game.title
    Text(
        stringResource(R.string.game_cover_flow_caption, game.engine.displayName, displayTitle),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
        modifier = modifier,
    )
}
