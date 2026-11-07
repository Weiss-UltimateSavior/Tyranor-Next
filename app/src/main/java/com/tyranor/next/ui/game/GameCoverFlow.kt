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
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
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
import com.tyranor.next.ui.common.motion.MotionTokens
import com.tyranor.next.ui.common.motion.PressSquishDefaults
import com.tyranor.next.ui.common.motion.motionBlur
import com.tyranor.next.ui.common.motion.pressSquish
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
    Box(modifier = Modifier.fillMaxSize()) {
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
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxWidth(),
                    pageSize = PageSize.Fixed(cardWidth),
                    contentPadding = PaddingValues(horizontal = horizontalPadding),
                    pageSpacing = 14.dp,
                    // 翻页吸附用带一点回弹的弹簧（普通档为原版默认吸附）
                    flingBehavior = if (MotionMode.enhanced) {
                        PagerDefaults.flingBehavior(
                            state = pagerState,
                            snapAnimationSpec = MotionTokens.layout(0.5f),
                        )
                    } else {
                        PagerDefaults.flingBehavior(state = pagerState)
                    },
                ) { page ->
                    val game = games.getOrNull(page) ?: return@HorizontalPager
                    // 距中心的页偏移不在组合里读取（否则每个滚动帧所有页都重组）：
                    // 层级（zIndex）在布局放置块、形变在图层块里从 pagerState 现读
                    key(game.uri) {
                        CoverFlowCard(
                            game = game,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(3f / 4f)
                                .layout { measurable, constraints ->
                                    val placeable = measurable.measure(constraints)
                                    layout(placeable.width, placeable.height) {
                                        val offset = (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction
                                        placeable.place(0, 0, zIndex = 1f - min(offset.absoluteValue, 2f))
                                    }
                                }
                                .graphicsLayer {
                                    val offset = (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction
                                    val absOffset = min(offset.absoluteValue, 2f)
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
                                                scope.launch {
                                                    if (MotionMode.enhanced) {
                                                        pagerState.animateScrollToPage(page, animationSpec = MotionTokens.layout(0.5f))
                                                    } else {
                                                        pagerState.animateScrollToPage(page)
                                                    }
                                                }
                                            }
                                        },
                                        onLongPress = { onGameLongClick(game) },
                                    )
                                },
                        )
                    }
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

/**
 * Displays an engine placeholder until its cover is decoded. Enhanced mode fades and sharpens the
 * image in the drawing layers; ordinary mode reveals it immediately when ready.
 */
@Composable
private fun CoverFlowCard(
    game: ScanGame,
    modifier: Modifier = Modifier,
) {
    val cover by rememberCoverBitmap(game.coverUri)
    // 封面加载完成：淡入 + 由糊变清（已缓存的封面一开始就是 1，不会闪）
    val animatedCoverAlpha = animateFloatAsState(
        targetValue = if (cover != null) 1f else 0f,
        animationSpec = MotionTokens.fadeIn(0.001f),
        label = "coverFlowFadeIn",
    )
    // 普通档：上游无淡入，封面一就绪即不透明
    // 动画值不在组合里读取：图层块 / 模糊半径块里现读，淡入期间只失效图层
    val enhanced = MotionMode.enhanced
    Box(
        modifier = modifier
            .pressSquish(scale = PressSquishDefaults.CardScale)
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
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = if (enhanced) animatedCoverAlpha.value else 1f }
                    .motionBlur {
                        val coverAlpha = if (enhanced) animatedCoverAlpha.value else 1f
                        (1f - coverAlpha).coerceIn(0f, 1f) * 8.dp.toPx()
                    },
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
