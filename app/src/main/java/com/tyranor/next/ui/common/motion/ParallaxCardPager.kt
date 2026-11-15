package com.tyranor.next.ui.common.motion

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt

/** Position-driven layers for a pager card. Offset is sampled only during placement/drawing. */
internal class PagerCardLayers private constructor(private val offset: (() -> Float)?) {
    fun depth(modifier: Modifier): Modifier = if (offset == null) modifier else modifier.graphicsLayer {
        val depth = 1f - 0.06f * abs(offset()).coerceAtMost(1f)
        scaleX = depth
        scaleY = depth
    }

    fun backdrop(modifier: Modifier, opacity: () -> Float): Modifier = modifier.graphicsLayer {
        alpha = opacity()
        offset?.let { pagerBackdropLayer(it()) }
    }

    fun foreground(modifier: Modifier): Modifier = if (offset == null) modifier else modifier.graphicsLayer {
        pagerForegroundLayer(offset())
    }

    companion object {
        val Static = PagerCardLayers(null)
        fun forPage(state: PagerState, page: Int): PagerCardLayers =
            if (MotionMode.enhanced) PagerCardLayers { state.pageOffsetOf(page) } else Static
    }
}

/** The extra pager bounds and matching content padding must change together to leave the cards in place. */
@Composable
internal fun ParallaxCardPager(
    state: PagerState,
    modifier: Modifier = Modifier,
    content: @Composable (page: Int, layers: PagerCardLayers) -> Unit,
) {
    val enhanced = MotionMode.enhanced
    val horizontalRoom = 9.dp
    val verticalRoom = 6.dp
    PagerTickHaptics(state)
    HorizontalPager(
        state = state,
        modifier = if (enhanced) modifier.overshootRoom(horizontalRoom, verticalRoom) else modifier,
        contentPadding = if (enhanced) PaddingValues(horizontal = horizontalRoom, vertical = verticalRoom) else PaddingValues(0.dp),
        pageSpacing = 10.dp,
        verticalAlignment = Alignment.Top,
        flingBehavior = rememberPagerSnapFling(state),
    ) { page ->
        content(page, PagerCardLayers.forPage(state, page))
    }
}

/**
 * 快捷启动翻页指示：当前页为加宽胶囊（主题色实底 + 同色柔光），其余为半透明小圆点。
 * 只在可滑动（2–3 项）的小屏单卡形态下绘制于卡片下方；大屏三卡并排与 0/1 项不显示。
 */
@Composable
internal fun CardPagerIndicator(
    pageCount: Int,
    pagerState: PagerState,
    modifier: Modifier = Modifier,
) {
    if (pageCount <= 1) return
    val activePage = pagerState.currentPage.coerceIn(0, pageCount - 1)
    val primary = MaterialTheme.colorScheme.primary
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(pageCount) { index ->
            if (MotionMode.enhanced) {
                // 增强档：每个点的宽度 / 颜色 / 光晕随翻页位置连续变化（布局 / 绘制阶段读取，翻页途中 0 重组）
                val inactive = primary.copy(alpha = 0.32f)
                Box(
                    modifier = Modifier
                        .layout { measurable, _ ->
                            val t = pagerState.dotCloseness(index)
                            val w = (8.dp.toPx() + (24.dp - 8.dp).toPx() * t).roundToInt()
                            val h = 8.dp.roundToPx()
                            val placeable = measurable.measure(Constraints.fixed(w, h))
                            layout(w, h) { placeable.place(0, 0) }
                        }
                        .graphicsLayer {
                            val t = pagerState.dotCloseness(index)
                            shadowElevation = 6.dp.toPx() * t
                            shape = CircleShape
                            clip = false
                            ambientShadowColor = primary
                            spotShadowColor = primary
                        }
                        .drawBehind {
                            val t = pagerState.dotCloseness(index)
                            drawRoundRect(
                                color = lerp(inactive, primary, t),
                                cornerRadius = CornerRadius(size.height / 2f, size.height / 2f),
                            )
                        },
                )
                return@repeat
            }
            val selected = index == activePage
            val width = animateDpAsState(
                targetValue = if (selected) 24.dp else 8.dp,
                animationSpec = tween(durationMillis = 200),
                label = "quickLaunchIndicatorWidth$index",
            )
            val color = if (selected) primary else primary.copy(alpha = 0.32f)
            Box(
                modifier = Modifier
                    .height(8.dp)
                    .layout { measurable, constraints ->
                        val w = constraints.constrainWidth(width.value.roundToPx())
                        val placeable = measurable.measure(constraints.copy(minWidth = w, maxWidth = w))
                        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                    }
                    .then(
                        if (selected) {
                            Modifier.shadow(
                                elevation = 6.dp,
                                shape = CircleShape,
                                clip = false,
                                ambientColor = color,
                                spotColor = color,
                            )
                        } else {
                            Modifier
                        },
                    )
                    .clip(CircleShape)
                    .background(color),
            )
        }
    }
}
