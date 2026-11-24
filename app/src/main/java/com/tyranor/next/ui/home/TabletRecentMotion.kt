package com.tyranor.next.ui.home

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.tyranor.next.ui.common.motion.MotionMode
import com.tyranor.next.ui.common.motion.PagerTickHaptics
import com.tyranor.next.ui.common.motion.overshootRoom
import com.tyranor.next.ui.common.motion.pageOffsetOf
import com.tyranor.next.ui.common.motion.rememberPagerSnapFling
import kotlin.math.abs

/** Keeps the pager stride constant while the covers grow towards the leading slot. */
@Composable
internal fun TabletRecentCoverPager(
    state: PagerState,
    availableWidth: Dp,
    thumbnailSize: Dp,
    previewSize: Dp,
    spacing: Dp,
    modifier: Modifier = Modifier,
    key: (Int) -> Any,
    cover: @Composable (page: Int, modifier: Modifier, emphasis: () -> Float) -> Unit,
) {
    val horizontalRoom = if (MotionMode.enhanced) 5.dp else 0.dp
    val verticalRoom = if (MotionMode.enhanced) 6.dp else 0.dp
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    // A full viewport of scroll room, less one page, lets the last cover reach the leading slot.
    val endPadding = with(LocalDensity.current) {
        val scrollRoom = (availableWidth.roundToPx() - thumbnailSize.roundToPx()).coerceAtLeast(0)
        (scrollRoom + horizontalRoom.roundToPx()).toDp()
    }
    PagerTickHaptics(state)
    HorizontalPager(
        state = state,
        modifier = modifier.overshootRoom(horizontalRoom, verticalRoom),
        contentPadding = PaddingValues(
            start = horizontalRoom,
            end = endPadding,
            top = verticalRoom,
            bottom = verticalRoom,
        ),
        pageSize = PageSize.Fixed(thumbnailSize),
        pageSpacing = spacing,
        verticalAlignment = Alignment.Bottom,
        flingBehavior = rememberPagerSnapFling(state),
        // The pager clamps its position at the start. Suppress the edge stretch there as well.
        overscrollEffect = if (state.canScrollBackward) rememberOverscrollEffect() else null,
        key = key,
    ) { page ->
        cover(
            page,
            Modifier.tabletRecentCoverTransform(state, page, previewSize, rtl),
            { recentCoverEmphasis(state.pageOffsetOf(page)) },
        )
    }
}

/** Fade the title at the page handoff and keep it clear of the arriving cover. */
@Composable
internal fun Modifier.tabletRecentTitleMotion(state: PagerState, thumbnailSize: Dp, spacing: Dp): Modifier {
    val direction = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f
    return graphicsLayer {
        val offset = state.currentPageOffsetFraction
        val stride = thumbnailSize.roundToPx() + spacing.roundToPx()
        translationX = -offset.coerceAtMost(0f) * stride * direction
        alpha = recentCoverEase((1f - 2f * abs(offset)).coerceIn(0f, 1f))
    }
}

private fun Modifier.tabletRecentCoverTransform(
    state: PagerState,
    page: Int,
    previewSize: Dp,
    rtl: Boolean,
): Modifier =
    graphicsLayer {
        if (size.width <= 0f) return@graphicsLayer
        val offset = state.pageOffsetOf(page)
        val extraWidth = previewSize.roundToPx() - size.width
        val scale = 1f + extraWidth / size.width * recentCoverEmphasis(offset)
        transformOrigin = TransformOrigin(if (rtl) 1f else 0f, 1f)
        scaleX = scale
        scaleY = scale
        // Complementary easing allocates exactly the growing cover's extra width to its neighbours.
        translationX = (if (rtl) -extraWidth else extraWidth) * recentCoverEase((-offset).coerceIn(0f, 1f))
    }

private fun recentCoverEmphasis(offset: Float): Float = recentCoverEase((1f - abs(offset)).coerceIn(0f, 1f))

private fun recentCoverEase(progress: Float): Float = progress * progress * (3f - 2f * progress)
