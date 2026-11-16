package com.tyranor.next.ui.onboarding

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeOut
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.tyranor.next.ui.common.motion.MotionMode
import com.tyranor.next.ui.common.motion.MotionTokens
import com.tyranor.next.ui.common.motion.bounceOnChange
import com.tyranor.next.ui.common.motion.breathing
import com.tyranor.next.ui.common.motion.shakeOnTrigger
import com.tyranor.next.ui.common.motion.staggeredEntrance
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/** Finishing or skipping fades the entire overlay only in enhanced mode, without an entry transition. */
@Composable
internal fun CoachOverlayVisibility(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = EnterTransition.None,
        exit = if (MotionMode.enhanced) fadeOut(MotionTokens.fadeOut()) else ExitTransition.None,
    ) { content() }
}

/** Let the title lead when steps change. The ordinary appearance always reads the live text. */
@Composable
internal fun coachBodyForStep(bodyRes: Int): Int {
    val enhanced = MotionMode.enhanced
    var shownBodyRes by remember { mutableIntStateOf(bodyRes) }
    LaunchedEffect(bodyRes) {
        if (shownBodyRes != bodyRes) {
            delay(COACH_BODY_STAGGER_MILLIS)
            shownBodyRes = bodyRes
        }
    }
    return if (enhanced) shownBodyRes else bodyRes
}

private const val COACH_BODY_STAGGER_MILLIS = 45L

/** Layout moves to its final anchor immediately; only the placement delta springs to zero. */
internal class CoachCardPlacement(private val fraction: Float, private val top: State<Float>) {
    fun position(modifier: Modifier, height: Dp, heightPx: Float): Modifier {
        val positioned = modifier.padding(top = height * fraction)
        return if (MotionMode.enhanced) positioned.offset {
            IntOffset(0, ((top.value - fraction) * heightPx).roundToInt())
        } else positioned
    }
}

@Composable
internal fun rememberCoachCardPlacement(step: Int): CoachCardPlacement {
    val fraction = if (step == 0) 0.40f else 0.54f
    val top = animateFloatAsState(fraction, MotionTokens.layout(0.0005f), label = "coachCardTop")
    return remember(fraction, top) { CoachCardPlacement(fraction, top) }
}

@Composable
internal fun Modifier.coachNextAction(stepIsLast: Boolean, saving: Boolean, failed: Boolean): Modifier =
    staggeredEntrance(4, distance = 12.dp)
        .shakeOnTrigger(failed, amplitude = 6.dp)
        .bounceOnChange(stepIsLast, depth = 0.08f)
        .breathing(active = saving, minAlpha = 0.6f)

@Composable
internal fun CoachProgressDot(index: Int, active: Boolean, primary: Color, inactive: Color) {
    val enhanced = MotionMode.enhanced
    if (enhanced) {
        // 当前步圆点弹簧胀大、上一步回缩，颜色同步过渡；尺寸只在布局阶段、颜色只在绘制阶段读取
        val dotSize = animateDpAsState(
            targetValue = if (active) 16.dp else 7.dp,
            animationSpec = MotionTokens.control(0.1.dp),
            label = "coachDotSize$index",
        )
        val dotColor = animateColorAsState(
            targetValue = if (active) primary else inactive,
            animationSpec = MotionTokens.fadeIn(),
            label = "coachDotColor$index",
        )
        Box(
            Modifier
                .coachDotSize(dotSize)
                .drawBehind {
                    drawOutline(CircleShape.createOutline(size, layoutDirection, this), dotColor.value)
                },
        )
    } else {
        Box(
            Modifier
                .size(if (active) 16.dp else 7.dp)
                .background(
                    if (active) primary else inactive,
                    CircleShape,
                ),
        )
    }
}

/** 进度点尺寸只在布局阶段读取动画值：尺寸弹簧逐帧只重新布局这一行，不重组整个引导层。 */
private fun Modifier.coachDotSize(size: State<Dp>): Modifier = layout { measurable, _ ->
    val px = size.value.roundToPx().coerceAtLeast(0)
    val placeable = measurable.measure(Constraints.fixed(px, px))
    layout(px, px) { placeable.place(0, 0) }
}
