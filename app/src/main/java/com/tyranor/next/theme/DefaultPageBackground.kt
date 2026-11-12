package com.tyranor.next.theme

import android.animation.ValueAnimator
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/** 粒子数量：小尺寸 + 较高密度，保持轻盈但可见；全屏背景逐帧绘制仍然很轻。 */
private const val ParticleCount = 42

/** 固定随机种子：进程内位置/速度稳定，重组或切换主题色时不会跳动。 */
private const val ParticleSeed = 20261103

/** 粒子刷新间隔：约 30fps，慢速漂浮无需跟随 60/120Hz。 */
private const val ParticleFrameIntervalNanos = 33_000_000L

/** 单个粒子的静态参数（位置/速度归一化到屏幕比例）。 */
private data class FloatingParticle(
    val startX: Float,
    val startY: Float,
    val radiusDp: Float,
    /** 每秒上升的屏幕高度比例。 */
    val riseSpeed: Float,
    /** 水平摆动的屏幕宽度比例。 */
    val swayAmplitude: Float,
    /** 摆动角速度（弧度/秒）。 */
    val swaySpeed: Float,
    val phase: Float,
    val color: Color,
    val alpha: Float,
)

/**
 * 默认外观风格的页面背景：纯色底（取主题 `background`，即 `PageGrey` 档）+ 主题色粒子缓慢漂浮。
 *
 * - 仅**默认外观风格**生效；复古玻璃与白雾玻璃有独立的页面背景实现（[GlassBackground]），本组件为 no-op。
 * - 粒子在绘制阶段读取动画时间，只失效绘制、不触发重组；约 30fps。
 * - 系统关闭动画（开发者选项/无障碍）时停止动画，保留静态粒子。
 * - 背景层必须是**独立空层**（不含页面内容），由页面根作为第一个子节点挂载。
 */
@Composable
fun DefaultPageBackgroundLayer(modifier: Modifier = Modifier) {
    if (AppThemeColors.isGlass || AppThemeColors.isMilkGlass) return
    val base = MaterialTheme.colorScheme.background
    val primary = AppThemeColors.primary
    val particles = remember(primary) { buildFloatingParticles(primary) }
    var elapsedSeconds by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(Unit) {
        if (!ValueAnimator.areAnimatorsEnabled()) return@LaunchedEffect
        var startNanos = 0L
        var lastUpdateNanos = 0L
        while (true) {
            withFrameNanos { now ->
                if (startNanos == 0L) startNanos = now
                if (lastUpdateNanos == 0L || now - lastUpdateNanos >= ParticleFrameIntervalNanos) {
                    lastUpdateNanos = now
                    elapsedSeconds = (now - startNanos) / 1_000_000_000f
                }
            }
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .background(base)
            .drawBehind { drawFloatingParticles(particles, elapsedSeconds) },
    )
}

/** 以主题色为基准生成同色系粒子（色相偏移 +36° / -36°），保证随「色调轮盘」整体换色。 */
private fun buildFloatingParticles(primary: Color): List<FloatingParticle> {
    val random = Random(ParticleSeed)
    val palette = listOf(
        primary,
        shiftHue(primary, 36f),
        shiftHue(primary, -36f),
    )
    return List(ParticleCount) {
        FloatingParticle(
            startX = random.nextFloat(),
            startY = random.nextFloat(),
            radiusDp = 2.5f + random.nextFloat() * 5.5f,
            riseSpeed = 0.012f + random.nextFloat() * 0.024f,
            swayAmplitude = 0.02f + random.nextFloat() * 0.05f,
            swaySpeed = 0.20f + random.nextFloat() * 0.40f,
            phase = random.nextFloat() * (2f * PI.toFloat()),
            color = palette[random.nextInt(palette.size)],
            alpha = 0.12f + random.nextFloat() * 0.12f,
        )
    }
}

private fun DrawScope.drawFloatingParticles(particles: List<FloatingParticle>, timeSeconds: Float) {
    particles.forEach { particle ->
        var normalizedY = (particle.startY - timeSeconds * particle.riseSpeed) % 1f
        if (normalizedY < 0f) normalizedY += 1f
        val normalizedX = particle.startX +
            sin(timeSeconds * particle.swaySpeed + particle.phase) * particle.swayAmplitude
        // 上下边缘淡入淡出，避免循环回到顶部时突然出现
        val fade = when {
            normalizedY < 0.12f -> normalizedY / 0.12f
            normalizedY > 0.88f -> (1f - normalizedY) / 0.12f
            else -> 1f
        }.coerceIn(0f, 1f)
        val alpha = particle.alpha * fade
        if (alpha <= 0.002f) return@forEach

        val center = Offset(normalizedX * size.width, normalizedY * size.height)
        val radius = particle.radiusDp.dp.toPx()
        // 三层同心圆模拟柔和光斑，避免逐帧创建径向渐变 Brush
        drawCircle(particle.color, radius * 1.7f, center, alpha = alpha * 0.25f)
        drawCircle(particle.color, radius * 1.25f, center, alpha = alpha * 0.45f)
        drawCircle(particle.color, radius, center, alpha = alpha)
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
