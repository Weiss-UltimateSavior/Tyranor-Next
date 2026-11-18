package com.tyranor.next.theme

import android.animation.ValueAnimator
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.tyranor.next.core.settings.AppSettingsStore
import com.tyranor.next.core.settings.DefaultThemeParticleStyle
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/** 粒子数量：小尺寸 + 较高密度，保持轻盈但可见；全屏背景逐帧绘制仍然很轻。 */
private const val ParticleCount = 42

/** 固定随机种子：进程内位置/速度稳定，重组或切换主题色时不会跳动。 */
private const val ParticleSeed = 20261103

/** 气泡缓慢漂浮，维持约 30fps。按键瀑布使用每一帧的时间。 */
private const val BubbleFrameIntervalNanos = 33_000_000L

/** 单个粒子的静态参数（位置/速度归一化到屏幕比例）。 */
private data class FloatingParticle(
    val startX: Float,
    val startY: Float,
    val radiusDp: Float,
    /** 每秒移动的屏幕高度比例，正值向下。 */
    val verticalSpeed: Float,
    /** 水平摆动的屏幕宽度比例。 */
    val swayAmplitude: Float,
    /** 摆动角速度（弧度/秒）。 */
    val swaySpeed: Float,
    val phase: Float,
    val color: Color,
    val alpha: Float,
)

/**
 * 默认外观风格的页面背景：纯色底（`PageGrey`）加随主题色变化的背景粒子。
 *
 * - 仅**默认外观风格**生效；复古玻璃与白雾玻璃有独立的页面背景实现（[GlassBackground]），本组件为 no-op。
 * - 粒子在绘制阶段读取动画时间，只失效绘制、不触发重组。
 * - 系统关闭动画（开发者选项/无障碍）时停止动画，保留静态粒子。
 * - 应用设置关闭「默认主题粒子」时只绘制纯色底。
 * - 背景层必须是**独立空层**（不含页面内容），由页面根作为第一个子节点挂载。
 */
@Composable
fun DefaultPageBackgroundLayer(modifier: Modifier = Modifier) {
    if (AppThemeColors.isGlass || AppThemeColors.isMilkGlass) return
    val background = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
    val particlesEnabled by AppSettingsStore.defaultThemeParticlesEnabled.collectAsState()
    if (!particlesEnabled) {
        Box(background)
        return
    }
    val style by AppSettingsStore.defaultThemeParticleStyle.collectAsState()
    val primary = AppThemeColors.primary
    val buttonWaterfall = style == DefaultThemeParticleStyle.BUTTON_WATERFALL
    val particles = remember(primary, buttonWaterfall) { buildFloatingParticles(primary, buttonWaterfall) }
    var elapsedSeconds by remember(style) { mutableFloatStateOf(0f) }

    LaunchedEffect(style) {
        if (!ValueAnimator.areAnimatorsEnabled()) return@LaunchedEffect
        val frameInterval = if (buttonWaterfall) 0L else BubbleFrameIntervalNanos
        var startNanos = 0L
        var lastUpdateNanos = 0L
        while (true) {
            withFrameNanos { now ->
                if (startNanos == 0L) startNanos = now
                if (lastUpdateNanos == 0L || now - lastUpdateNanos >= frameInterval) {
                    lastUpdateNanos = now
                    elapsedSeconds = (now - startNanos) / 1_000_000_000f
                }
            }
        }
    }

    // 瀑布每帧只更新自己的空图层，页面内容与纯色背景可以复用已录制的绘制指令。
    val particleLayer = if (buttonWaterfall) background.graphicsLayer() else background
    Box(particleLayer.drawBehind { drawFloatingParticles(particles, elapsedSeconds, buttonWaterfall) })
}

/** 以主题色为基准生成同色系粒子（色相偏移 +36° / -36°），保证随「色调轮盘」整体换色。 */
private fun buildFloatingParticles(primary: Color, buttonWaterfall: Boolean): List<FloatingParticle> {
    val random = Random(ParticleSeed)
    val palette = listOf(
        primary,
        shiftHue(primary, 36f),
        shiftHue(primary, -36f),
    )
    val radiusScale = if (buttonWaterfall) 1.65f else 1f
    val verticalSpeedScale = if (buttonWaterfall) 2.8f else -1f
    val swayAmplitudeScale = if (buttonWaterfall) 1.65f else 1f
    val swaySpeedScale = if (buttonWaterfall) 1.8f else 1f
    return List(ParticleCount) {
        FloatingParticle(
            startX = random.nextFloat(),
            startY = random.nextFloat(),
            radiusDp = (2.5f + random.nextFloat() * 5.5f) * radiusScale,
            verticalSpeed = (0.012f + random.nextFloat() * 0.024f) * verticalSpeedScale,
            swayAmplitude = (0.02f + random.nextFloat() * 0.05f) * swayAmplitudeScale,
            swaySpeed = (0.20f + random.nextFloat() * 0.40f) * swaySpeedScale,
            phase = random.nextFloat() * (2f * PI.toFloat()),
            color = palette[random.nextInt(palette.size)],
            alpha = 0.12f + random.nextFloat() * 0.12f,
        )
    }
}

private fun DrawScope.drawFloatingParticles(
    particles: List<FloatingParticle>,
    timeSeconds: Float,
    buttonWaterfall: Boolean,
) {
    particles.forEachIndexed { index, particle ->
        val swayAngle = timeSeconds * particle.swaySpeed + particle.phase
        // 微调下落速度与侧向轨迹，各粒子仍连续向下移动。
        val verticalDrift = if (buttonWaterfall) 0.018f * sin(swayAngle + 1f) else 0f
        val horizontalDrift = if (buttonWaterfall) 0.025f * sin(swayAngle * 1.6f + particle.phase) else 0f
        var normalizedY = (particle.startY + timeSeconds * particle.verticalSpeed + verticalDrift) % 1f
        if (normalizedY < 0f) normalizedY += 1f
        val normalizedX = particle.startX + sin(swayAngle) * particle.swayAmplitude + horizontalDrift
        // 上下边缘淡入淡出，让粒子越过边界后自然出现
        val fade = when {
            normalizedY < 0.12f -> normalizedY / 0.12f
            normalizedY > 0.88f -> (1f - normalizedY) / 0.12f
            else -> 1f
        }.coerceIn(0f, 1f)
        val alpha = particle.alpha * fade
        if (alpha <= 0.002f) return@forEachIndexed

        val center = Offset(normalizedX * size.width, normalizedY * size.height)
        val radius = particle.radiusDp.dp.toPx()
        if (buttonWaterfall) {
            val tilt = 18f * sin(swayAngle + 1f) + 9f * sin(particle.phase)
            drawButtonSymbol(index, particle.color, center, radius, alpha, tilt)
        } else {
            // 三层同心圆模拟柔和光斑，避免逐帧创建径向渐变 Brush
            drawCircle(particle.color, radius * 1.7f, center, alpha = alpha * 0.25f)
            drawCircle(particle.color, radius * 1.25f, center, alpha = alpha * 0.45f)
            drawCircle(particle.color, radius, center, alpha = alpha)
        }
    }
}

private fun DrawScope.drawButtonSymbol(
    index: Int,
    color: Color,
    center: Offset,
    radius: Float,
    alpha: Float,
    tilt: Float,
) {
    val strokeWidth = maxOf(1.dp.toPx(), radius * 0.17f)
    val opacity = alpha * 1.25f
    val symbol = index % 4
    if (symbol == 1) {
        drawCircle(color, radius, center, alpha = opacity, style = Stroke(strokeWidth))
        return
    }
    rotate(tilt, pivot = center) {
        val topLeft = center - Offset(radius, radius)
        val bottomRight = center + Offset(radius, radius)
        when (symbol) {
            0 -> {
                val top = Offset(center.x, topLeft.y)
                val left = Offset(topLeft.x, bottomRight.y)
                drawLine(color, top, left, strokeWidth, StrokeCap.Round, alpha = opacity)
                drawLine(color, left, bottomRight, strokeWidth, StrokeCap.Round, alpha = opacity)
                drawLine(color, bottomRight, top, strokeWidth, StrokeCap.Round, alpha = opacity)
            }
            2 -> drawRect(color, topLeft, Size(radius * 2f, radius * 2f), alpha = opacity, style = Stroke(strokeWidth))
            3 -> {
                drawLine(color, topLeft, bottomRight, strokeWidth, StrokeCap.Round, alpha = opacity)
                drawLine(
                    color,
                    Offset(bottomRight.x, topLeft.y),
                    Offset(topLeft.x, bottomRight.y),
                    strokeWidth,
                    StrokeCap.Round,
                    alpha = opacity,
                )
            }
        }
    }
}

/** 色相偏移（略降饱和），用于生成同色系粒子。 */
private fun shiftHue(color: Color, degrees: Float): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(color.toArgb(), hsv)
    hsv[0] = (hsv[0] + degrees + 360f) % 360f
    hsv[1] = (hsv[1] * 0.9f).coerceIn(0f, 1f)
    return Color(android.graphics.Color.HSVToColor(hsv))
}
