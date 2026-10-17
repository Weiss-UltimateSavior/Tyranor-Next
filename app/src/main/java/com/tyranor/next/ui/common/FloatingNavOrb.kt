package com.tyranor.next.ui.common

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow
import com.tyranor.next.theme.FloatingNavGlassSurface
import com.tyranor.next.theme.FloatingNavGlassSurfaceDark
import com.tyranor.next.ui.common.glass.GlassShaderSupport
import kotlin.math.atan2

@Stable
internal class FloatingNavOrbMotion {
    val expansion = Animatable(0f)
    var rawScale: State<Float> = mutableFloatStateOf(1f)
    var visualScale: State<Float> = mutableFloatStateOf(1f)
    var maximumScale: () -> Float = { 1.42f }
    var proximity by mutableFloatStateOf(0f)
    var direction by mutableStateOf(Offset(0f, -1f))
    var touching by mutableStateOf(false)
    var pulseKey by mutableIntStateOf(0)
}

/** This navigation's springs and optical feedback are read directly by drawing layers. */
@Composable
internal fun FloatingNavOrb(
    motion: FloatingNavOrbMotion,
    backdrop: Backdrop?,
    primary: Color,
    iconTint: Color,
    dark: Boolean,
    modifier: Modifier = Modifier,
    main: Boolean = false,
    pressed: Boolean = false,
    dragging: Boolean = false,
    committed: Boolean = false,
    content: @Composable BoxScope.(() -> Color) -> Unit,
) {
    val pulse = remember { Animatable(0f) }
    LaunchedEffect(motion.pulseKey) {
        if (motion.pulseKey > 0) {
            pulse.animateTo(1f, tween(110))
            pulse.animateTo(0f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMediumLow))
        }
    }
    val scale = animateFloatAsState(
        targetValue = if (main) {
            1f - (if (pressed) 0.065f else 0f) + (if (dragging) 0.12f else 0f)
        } else {
            1f + (if (committed) 1f else motion.proximity) * 0.38f
        },
        animationSpec = spring(
            dampingRatio = if (dragging) 0.68f else 0.54f,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "floatingOrbScale",
    )
    // A pulse no longer changes the target of a second animation on every frame.
    val rawScale = remember(scale, main, pulse) {
        derivedStateOf {
            (scale.value + pulse.value * if (main) 0.14f else 0.12f)
                .coerceAtMost(if (main) 1.3f else 1.42f)
        }
    }
    motion.rawScale = rawScale
    val visualScale = remember(rawScale, main, motion) {
        derivedStateOf {
            rawScale.value.coerceAtMost(if (main) 1.3f else motion.maximumScale())
        }
    }
    motion.visualScale = visualScale
    // Keep fading, spring overshoot and approach scaling on the material's own layer.
    // Auto alpha compositing would crop the enlarged orb/shadow to the unscaled square.
    val orbLayer = remember(main, motion, visualScale) {
        val block: GraphicsLayerScope.() -> Unit = {
            val progress = if (main) 1f else motion.expansion.value.coerceIn(0f, 1f)
            alpha = progress
            scaleX = visualScale.value * (if (main) 1f else 0.8f + progress * 0.2f)
            scaleY = scaleX
            rotationZ = if (main) 0f else -12f * (1f - progress)
            compositingStrategy = CompositingStrategy.ModulateAlpha
        }
        block
    }
    val density = LocalDensity.current
    val surfaceColor = if (dark) FloatingNavGlassSurfaceDark else FloatingNavGlassSurface
    val backdropAvailable = backdrop != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val surface = remember(backdrop, backdropAvailable, density, surfaceColor, orbLayer) {
        if (backdropAvailable) {
            Modifier.drawBackdrop(
                backdrop = backdrop,
                shape = { CircleShape },
                effects = { vibrancy(); blur(with(density) { 6.dp.toPx() }) },
                highlight = if (GlassShaderSupport.highlightAllowed) {
                    { Highlight.Default.copy(alpha = 0.72f) }
                } else null,
                shadow = { Shadow.Default.copy(alpha = 0.9f) },
                // The backdrop also uses this block to undo scale/rotation when sampling.
                layerBlock = orbLayer,
                onDrawSurface = { drawCircle(surfaceColor.copy(alpha = 0.58f)) },
            )
        } else {
            Modifier.graphicsLayer(orbLayer)
                .shadow(7.dp, CircleShape, clip = false).clip(CircleShape)
                .background(surfaceColor.copy(alpha = 0.96f))
        }
    }
    val light = animateFloatAsState(
        targetValue = if (main && pressed) 1f else motion.proximity,
        animationSpec = spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow),
        label = "floatingOrbLight",
    )
    val reveal = animateFloatAsState(
        targetValue = if (committed || motion.touching || (main && pressed)) 1f else 0f,
        animationSpec = tween(220),
        label = "floatingOrbTheme",
    )
    val tint = remember(iconTint, primary, reveal) {
        { lerp(iconTint, primary, reveal.value.coerceIn(0f, 1f)) }
    }
    val feedback = remember(primary, dark, motion, light, reveal, pulse) {
        Modifier.drawWithCache {
            val radius = size.minDimension / 2f
            val circle = Path().apply { addOval(Rect(Offset.Zero, size)) }
            val edgeInset = 0.75.dp.toPx()
            val rimInset = 1.dp.toPx()
            val edgeStroke = Stroke(1.35.dp.toPx())
            val white = FloatingNavGlassSurface
            val themeGlass = Brush.linearGradient(
                colors = listOf(
                    primary.copy(alpha = if (dark) 0.28f else 0.22f),
                    primary.copy(alpha = if (dark) 0.38f else 0.32f),
                    primary.copy(alpha = if (dark) 0.24f else 0.22f),
                ),
                start = Offset.Zero,
                end = Offset(size.width, size.height),
            )
            // One unit-radius shader is translated/scaled with the approached edge.
            // Factoring the common alpha preserves the internal illumination colours.
            val glow = Brush.radialGradient(
                colors = listOf(
                    white.copy(alpha = 0.52f),
                    primary.copy(alpha = 0.38f),
                    Color.Transparent,
                ),
                center = Offset.Zero,
                radius = 1f,
            )
            val rimLight = lerp(primary, white, 0.55f)
            var edgeAmount = Float.NaN
            var edge: Brush = Brush.linearGradient(listOf(Color.Transparent, Color.Transparent))
            var rimAmount = Float.NaN
            var rim: Brush = Brush.sweepGradient(listOf(Color.Transparent, Color.Transparent))
            var rimStroke = Stroke(0.7.dp.toPx())

            onDrawBehind {
                val amount = (light.value + pulse.value * 0.65f).coerceIn(0f, 1f)
                val themeReveal = reveal.value.coerceIn(0f, 1f)
                val direction = motion.direction
                val unit = direction / direction.getDistance().coerceAtLeast(0.001f)
                val origin = center + unit * radius
                // Rotation/scale-only redraws reuse the gradients, contour and strokes.
                if (edgeAmount != amount) {
                    edgeAmount = amount
                    edge = Brush.linearGradient(
                        colors = listOf(
                            white.copy(alpha = if (dark) 0.40f else 0.94f),
                            primary.copy(alpha = 0.10f + amount * 0.38f),
                            white.copy(alpha = if (dark) 0.12f else 0.52f),
                        ),
                        start = Offset.Zero,
                        end = Offset(size.width, size.height),
                    )
                }
                if (amount > 0.001f && rimAmount != amount) {
                    rimAmount = amount
                    val halfSpan = (40f + amount * 30f) / 360f
                    rim = Brush.sweepGradient(
                        0f to Color.Transparent,
                        (0.5f - halfSpan) to Color.Transparent,
                        (0.5f - halfSpan * 0.78f) to rimLight.copy(alpha = amount * 0.05f),
                        (0.5f - halfSpan * 0.42f) to rimLight.copy(alpha = amount * 0.29f),
                        0.5f to rimLight.copy(alpha = amount * 0.48f),
                        (0.5f + halfSpan * 0.42f) to rimLight.copy(alpha = amount * 0.29f),
                        (0.5f + halfSpan * 0.78f) to rimLight.copy(alpha = amount * 0.05f),
                        (0.5f + halfSpan) to Color.Transparent,
                        1f to Color.Transparent,
                        center = center,
                    )
                    rimStroke = Stroke((0.7f + amount * 0.35f).dp.toPx())
                }
                clipPath(circle) {
                    drawCircle(themeGlass, radius = radius, alpha = themeReveal)
                    if (amount > 0.001f) {
                        val glowRadius = radius * (0.15f + amount * 2.15f)
                        withTransform({
                            translate(origin.x, origin.y)
                            scale(glowRadius, glowRadius, pivot = Offset.Zero)
                        }) {
                            drawCircle(
                                brush = glow,
                                radius = radius * 3f / glowRadius,
                                center = Offset.Zero,
                                alpha = amount * (1f - themeReveal * 0.6f),
                            )
                        }
                    }
                    drawCircle(edge, radius = radius - edgeInset, style = edgeStroke)
                    if (amount > 0.001f) {
                        val angle = Math.toDegrees(atan2(unit.y, unit.x).toDouble()).toFloat()
                        rotate(angle - 180f) {
                            drawCircle(rim, radius = radius - rimInset, style = rimStroke)
                        }
                    }
                }
            }
        }
    }
    Box(
        modifier.then(surface),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.matchParentSize().then(feedback))
        content(tint)
    }
}
