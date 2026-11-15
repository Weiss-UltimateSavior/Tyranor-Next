package com.tyranor.next.ui.game

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.ui.common.motion.MotionMode
import com.tyranor.next.ui.common.motion.MotionTokens
import com.tyranor.next.ui.common.motion.bounceOnChange
import com.tyranor.next.ui.common.motion.pageOffsetOf
import com.tyranor.next.ui.common.motion.staggeredEntrance
import kotlin.math.absoluteValue
import kotlin.math.min

/** First composition is not a batch change. The arrival window uses uptime, not the frame clock. */
@Composable
internal fun rememberCoverBatchChange(games: List<ScanGame>): Long {
    val gamesKey = remember(games) { games.map { it.uri } }
    val listGeneration = remember { intArrayOf(-1) }
    return remember(gamesKey) {
        listGeneration[0]++
        if (listGeneration[0] == 0) 0L else SystemClock.uptimeMillis()
    }
}

@Composable
internal fun Modifier.coverBatchArrival(changedAt: Long): Modifier {
    val enhanced = MotionMode.enhanced
    val arriveAfterListChange = enhanced && changedAt != 0L &&
        SystemClock.uptimeMillis() - changedAt < COVER_ARRIVAL_WINDOW_MILLIS
    val arrival = remember { Animatable(if (arriveAfterListChange) 0f else 1f) }
    LaunchedEffect(arrival) {
        if (arrival.value < 1f) arrival.animateTo(1f, MotionTokens.content(0.001f))
    }
    return if (!enhanced) this else graphicsLayer {
        val a = arrival.value
        if (a != 1f) {
            alpha = a.coerceIn(0f, 1f)
            val s = 0.9f + 0.1f * a
            scaleX = s
            scaleY = s
        }
    }
}

internal fun Modifier.coverFlowPage(
    pagerState: PagerState,
    page: Int,
    velocity: State<Float>,
): Modifier {
    val settledPage = pagerState.settledPage
    return this
        .layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            layout(placeable.width, placeable.height) {
                val offset = pagerState.pageOffsetOf(page)
                placeable.place(0, 0, zIndex = 1f - min(offset.absoluteValue, 2f))
            }
        }
        // 首屏入场：离停稳页越近越先升起（放在层级放置之内，不影响 zIndex）
        .staggeredEntrance((page - settledPage).absoluteValue, stepMillis = 45L)
        .graphicsLayer {
            val offset = pagerState.pageOffsetOf(page)
            val absOffset = min(offset.absoluteValue, 2f)
            cameraDistance = 16f * density
            rotationY = (-offset.coerceIn(-1f, 1f)) * 38f
            val scale = 1f - 0.22f * min(absOffset, 1f)
            // 甩动顺势侧倾：额外的 rotationY / 纵向压缩随速度（页 / 秒）出现，停下归零
            val lean = (velocity.value / LEAN_FULL_SPEED).coerceIn(-1f, 1f)
            rotationY -= lean * LEAN_DEGREES
            scaleX = scale
            scaleY = scale * (1f - LEAN_SQUASH * lean.absoluteValue)
            alpha = 1f - 0.45f * min(absOffset, 1f)
        }
        // 选中落点：翻到 / 点到某张并吸附停稳时，那张卡轻轻一压再 Q 弹回原样（首次组合不弹）
        .bounceOnChange(settledPage == page, depth = 0.05f)
}

internal suspend fun PagerState.centerCover(page: Int) {
    if (MotionMode.enhanced) animateScrollToPage(page, animationSpec = MotionTokens.layout(0.5f))
    else animateScrollToPage(page)
}

/** 甩动侧倾：速度达到这么多页 / 秒时倾斜满格；满格时额外倾斜的角度与纵向压缩。 */
private const val LEAN_FULL_SPEED = 6f
private const val LEAN_DEGREES = 6f
private const val LEAN_SQUASH = 0.02f

/** 列表换批后，新组合的卡片在这段时间内播放「到达」动效；之后滑入的卡片直接显示。 */
private const val COVER_ARRIVAL_WINDOW_MILLIS = 450L
