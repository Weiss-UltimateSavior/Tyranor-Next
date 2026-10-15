package com.tyranor.next.ui.home

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.theme.AppComponentShape
import com.tyranor.next.ui.common.SidePanelGestureGuard
import com.tyranor.next.ui.game.coverColor
import com.tyranor.next.ui.game.rememberCoverBitmap
import kotlin.math.abs
import kotlinx.coroutines.launch

/** 海报宽度占可用宽度的比例；大屏另有 [POSTER_MAX_WIDTH] 上限。 */
private const val POSTER_WIDTH_FRACTION = 0.57f
private val POSTER_MAX_WIDTH = 300.dp

/** 海报高宽比 3:2（影视海报常用 2:3）。 */
private const val POSTER_ASPECT = 1.5f

/** 靠左态：每往后一层，叠在后面的海报多露出的宽度（占海报宽）与缩放步长。 */
private const val STACK_PEEK = 0.13f
private const val STACK_STEP = 0.1f

/** 居中态：两侧海报随距离的缩放步长（并排在旁边，间隙约 0.08 海报宽）。 */
private const val SIDE_STEP = 0.154f

private const val MIN_SCALE = 0.5f

/** 点按其他海报滑过去的动画：相邻一张的时长，以及每多跳一张增加的时长。 */
private const val JUMP_BASE_MS = 550
private const val JUMP_STEP_MS = 120

/** 靠左态最多叠出的条数。 */
private const val MAX_STACK = 4

/** 当前位置（浮点页号）：滑动中随手指连续变化。 */
internal fun PagerState.position(): Float = (currentPage + currentPageOffsetFraction).coerceAtLeast(0f)

/**
 * 海报叠放轮播。每张海报的位置在两种布局间随滑动位置 [PagerState.position] 连续过渡：
 * - 靠左态（position=0）：第一张靠左，后面最多叠 4 条（各露出右边缘、依次缩小）；整组水平居中，所以数量少时自动居中。
 * - 居中态（position≥1）：当前海报居中，前后海报并排在旁边（缩小、留小缝）。
 * 层级固定为「序号小的在上」，滑动中不翻转，避免两张重叠时出现瞬间跳变。
 * 点按当前海报开操作抽屉、长按直启；点按其他海报只滑到该海报。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PosterCarousel(
    games: List<ScanGame>,
    pagerState: PagerState,
    onGameClick: (ScanGame) -> Unit,
    onGameLongClick: (ScanGame) -> Unit,
    sidePanelGuard: SidePanelGestureGuard?,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    // 还能往回翻时，起点落在海报区的右滑归轮播（往回翻上一张），不唤出侧栏；停在第一张时仍可右滑唤出侧栏。
    // 登记放在 DisposableEffect 里而不是只放在 onGloballyPositioned 回调里：首页切到别的 Tab 再回来、或切换轮播来源
    // 换了新的 PagerState 时布局没变、不会再触发定位回调，只靠回调会丢登记或沿用旧 pager 的状态。
    var pagerCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    DisposableEffect(sidePanelGuard, pagerState, pagerCoordinates) {
        val coordinates = pagerCoordinates
        if (sidePanelGuard != null && coordinates != null) {
            sidePanelGuard.register(coordinates) { pagerState.canScrollBackward }
        }
        onDispose { sidePanelGuard?.unregister() }
    }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val density = LocalDensity.current
        val posterWidth = minOf(maxWidth * POSTER_WIDTH_FRACTION, POSTER_MAX_WIDTH)
        val posterHeight = posterWidth * POSTER_ASPECT
        val widthPx = with(density) { posterWidth.toPx() }
        val screenPx = with(density) { maxWidth.toPx() }
        // 靠左态整组（主海报 + 后面叠的条）水平居中后的左边距
        val stackCount = (games.size - 1).coerceIn(0, MAX_STACK)
        val edgePx = (screenPx - widthPx * (1f + STACK_PEEK * stackCount)) / 2f
        val sidePad = (maxWidth - posterWidth) / 2

        Column {
            HorizontalPager(
                state = pagerState,
                pageSize = PageSize.Fixed(posterWidth),
                contentPadding = PaddingValues(horizontal = sidePad),
                beyondViewportPageCount = MAX_STACK,
                // 页面按 (uri, 封面) 做 key（默认按序号）：切换来源/列表变化后同一序号换了游戏、或同一游戏换了封面，
                // 否则 rememberCoverBitmap 会复用上一个封面的状态
                key = { index -> games.getOrNull(index)?.let { "${it.uri}|${it.coverUri}" } ?: index },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(posterHeight)
                    .onGloballyPositioned { pagerCoordinates = it },
            ) { page ->
                val game = games[page]
                PosterCard(
                    game = game,
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(-page.toFloat())
                        .graphicsLayer {
                            val pos = pagerState.position()
                            val d = page - pos
                            translationX = slotCenterX(page, pos, widthPx, screenPx, edgePx) - (screenPx / 2f + d * widthPx)
                            val scale = slotScale(page, pos)
                            scaleX = scale
                            scaleY = scale
                            alpha = 1f - (abs(d) - MAX_STACK).coerceIn(0f, 1f)
                        }
                        .combinedClickable(
                            onClick = {
                                // alpha=0 的远端页仍在组合里、仍可命中：忽略点它们，避免在没有可见海报处触发长距离滚动
                                if (abs(page - pagerState.position()) > MAX_STACK) return@combinedClickable
                                if (page == pagerState.currentPage) {
                                    onGameClick(game)
                                } else {
                                    scope.launch {
                                        // 不用 animateScrollToPage：目标超过 3 页时它会先瞬间跳到目标附近再动画，
                                        // 靠左叠放态点后面几条就会「瞬移成展开态」。这里按像素距离从头到尾连续滚动。
                                        val distance = abs(page - pagerState.currentPage)
                                        pagerState.animateScrollBy(
                                            value = (page - pagerState.position()) * widthPx,
                                            animationSpec = tween(
                                                durationMillis = JUMP_BASE_MS + JUMP_STEP_MS * (distance - 1).coerceIn(0, 3),
                                                easing = FastOutSlowInEasing,
                                            ),
                                        )
                                        // 消除浮点残差，确保精确停在目标页（差值极小，视觉上无变化）
                                        pagerState.scrollToPage(page)
                                    }
                                }
                            },
                            onLongClick = { if (page == pagerState.currentPage) onGameLongClick(game) },
                        ),
                )
            }
            // 每张海报一个两行文字块（标题 + 引擎/上次游玩），跟着海报中心走并随距离淡出
            val titleWidth = posterWidth * 1.2f
            Box(Modifier.fillMaxWidth().height(64.dp)) {
                games.forEachIndexed { page, game ->
                    key(game.uri) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .width(titleWidth)
                                .padding(top = 12.dp)
                                .graphicsLayer {
                                    val pos = pagerState.position()
                                    translationX = slotCenterX(page, pos, widthPx, screenPx, edgePx) - with(density) { titleWidth.toPx() } / 2f
                                    alpha = (1f - abs(page - pos) * 1.6f).coerceAtLeast(0f)
                                },
                        ) {
                            Text(
                                game.title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                gameInfoLine(game),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---- 布局：第 page 张在位置 pos 时的中心 X / 缩放。居中态中心距 = 海报宽；靠左态后面的海报按右边缘逐层外推 ----

private fun centeredScale(d: Float): Float = (1f - SIDE_STEP * abs(d)).coerceAtLeast(MIN_SCALE)

private fun stackedScale(i: Int): Float = if (i == 0) 1f else (1f - STACK_STEP * i).coerceAtLeast(MIN_SCALE)

private fun stackedCenterX(i: Int, widthPx: Float, edgePx: Float): Float =
    if (i == 0) {
        edgePx + widthPx / 2f
    } else {
        // 右边缘 = 主海报右边缘 + i 条；中心 = 右边缘 - 缩放后半宽（左侧被前一张盖住）
        edgePx + widthPx + i * STACK_PEEK * widthPx - stackedScale(i) * widthPx / 2f
    }

private fun slotCenterX(page: Int, pos: Float, widthPx: Float, screenPx: Float, edgePx: Float): Float =
    if (pos >= 1f) {
        screenPx / 2f + (page - pos) * widthPx
    } else {
        // pos∈[0,1)：靠左态 → 居中态（以第 1 张为中心）线性过渡
        val from = stackedCenterX(page, widthPx, edgePx)
        val to = screenPx / 2f + (page - 1) * widthPx
        from + (to - from) * pos
    }

private fun slotScale(page: Int, pos: Float): Float =
    if (pos >= 1f) {
        centeredScale(page - pos)
    } else {
        val from = stackedScale(page)
        val to = centeredScale((page - 1).toFloat())
        from + (to - from) * pos
    }

/** 海报：封面铺满裁切；无封面时用引擎色底 + 游戏名占位。 */
@Composable
private fun PosterCard(game: ScanGame, modifier: Modifier = Modifier) {
    val cover by rememberCoverBitmap(game.coverUri)
    val bitmap = cover
    Box(
        modifier = modifier.clip(AppComponentShape).background(game.engine.coverColor()),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = game.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                game.title,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                textAlign = TextAlign.Center,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(12.dp),
            )
        }
    }
}
