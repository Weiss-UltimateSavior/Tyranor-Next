package com.tyranor.next.ui.common.motion

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

internal enum class CoverSurface(val label: String, val immediateInOrdinaryMode: Boolean = false) {
    Waterfall("coverFadeIn"), Grid("coverFadeIn"), Recent("recentCoverFadeIn"),
    Flow("coverFlowFadeIn", true), QuickLaunchBackdrop("quickLaunchBgFadeIn", true),
}

/** One readiness clock also supplies the engine badge or the background scrim. */
internal class CoverReveal(private val surface: CoverSurface, private val progress: State<Float>) {
    val alpha: Float
        get() = if (surface.immediateInOrdinaryMode && !MotionMode.enhanced) 1f else progress.value

    fun image(modifier: Modifier): Modifier {
        if (surface == CoverSurface.Flow) {
            return modifier.graphicsLayer { this.alpha = this@CoverReveal.alpha }
                .motionBlur { (1f - this@CoverReveal.alpha).coerceIn(0f, 1f) * 8.dp.toPx() }
        }
        val sized = if (surface == CoverSurface.Waterfall && MotionMode.enhanced) modifier.graphicsLayer {
            val scale = 1f - 0.06f * (1f - progress.value).coerceIn(0f, 1f)
            scaleX = scale
            scaleY = scale
        } else modifier
        return sized.motionBlurAlpha(
            alpha = { alpha },
            radius = {
                val missing = 1f - progress.value
                (if (surface == CoverSurface.Recent) missing else missing.coerceIn(0f, 1f)) * 8.dp.toPx()
            },
        )
    }
}

/** Observe readiness before the image's conditional composition; cache hits start opaque. */
@Composable
internal fun rememberCoverReveal(ready: Boolean, surface: CoverSurface): CoverReveal {
    val progress = animateFloatAsState(
        targetValue = if (ready) 1f else 0f,
        animationSpec = if (surface.immediateInOrdinaryMode || MotionMode.enhanced) MotionTokens.fadeIn(0.001f) else tween(300),
        label = surface.label,
    )
    return remember(surface, progress) { CoverReveal(surface, progress) }
}
