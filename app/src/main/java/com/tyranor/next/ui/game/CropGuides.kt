package com.tyranor.next.ui.game

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.tyranor.next.ui.common.motion.MotionMode
import com.tyranor.next.ui.common.motion.MotionTokens

/** Observes contact without consuming it; the editor remains the owner of pan/zoom. */
internal class CropGuides(
    private val gesturing: MutableState<Boolean>,
    private val gridAlpha: State<Float>,
) {
    fun observe(modifier: Modifier): Modifier = if (!MotionMode.enhanced) modifier else modifier.pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            gesturing.value = true
            try {
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                } while (event.changes.any { it.pressed })
            } finally {
                gesturing.value = false
            }
        }
    }

    /** Draw between the bitmap and the crop border. */
    fun DrawScope.drawThirds() {
        val grid = gridAlpha.value
        if (grid > 0.001f) {
            val lineColor = Color.White.copy(alpha = 0.5f * grid.coerceIn(0f, 1f))
            val lineWidth = 1.dp.toPx()
            for (i in 1..2) {
                val x = size.width * i / 3f
                val y = size.height * i / 3f
                drawLine(lineColor, Offset(x, 0f), Offset(x, size.height), lineWidth)
                drawLine(lineColor, Offset(0f, y), Offset(size.width, y), lineWidth)
            }
        }
    }
}

@Composable
internal fun rememberCropGuides(): CropGuides {
    val enhanced = MotionMode.enhanced
    val gesturing = remember { mutableStateOf(false) }
    val gridAlpha = animateFloatAsState(
        targetValue = if (enhanced && gesturing.value) 1f else 0f,
        animationSpec = if (gesturing.value) MotionTokens.fadeIn(0.001f) else MotionTokens.fadeOut(0.001f),
        label = "cropGridAlpha",
    )
    return remember(gesturing, gridAlpha) { CropGuides(gesturing, gridAlpha) }
}
