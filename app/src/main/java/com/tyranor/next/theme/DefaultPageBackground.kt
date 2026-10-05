package com.tyranor.next.theme

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import com.tyranor.next.core.settings.AppSettingsStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 默认外观风格的页面背景：
 * - 浅色模式：纯色底 + 主题色与主题近似色（色相偏移）柔光渐变 + 高斯模糊；
 * - 深色模式：以黑为主体的**颜色渐变**（纯色底与主题色/近似色低比例混色，无光晕、无模糊）。
 *
 * - 仅**默认外观风格**生效；玻璃系外观风格各自有页面背景实现（[GlassBackground]），本组件为 no-op。
 * - 模糊用 [Modifier.blur]（Android 12+ 生效，低版本自动降级为无模糊的柔光渐变）。
 * - 背景层必须是**独立空层**（不含页面内容），否则模糊会连内容一起糊掉。
 */

/** 柔光模糊半径：只求氛围，不糊形状。 */
private val DefaultPageBackgroundBlur: Dp = 96.dp

/** 主题色主光斑（左上）透明度：浅色模式。 */
private const val AccentGlowAlpha = 0.22f

/** 主题近似色副光斑（右下）透明度：浅色模式。 */
private const val AccentNearGlowAlpha = 0.18f


/** 近似色色相偏移（度）：同色系微变调，避免与主光斑同色叠死。 */
private const val AccentNearHueShift = 32f

/** 深色模式渐变：底色与主题色的混色比例（短端）。 */
private const val DarkAccentMix = 0.16f

/** 深色模式渐变：底色与近似色的混色比例（长端）。 */
private const val DarkAccentNearMix = 0.12f

/** 默认风格页面背景层：作为页面根的第一个子节点（空层）使用。 */
@Composable
fun DefaultPageBackgroundLayer(modifier: Modifier = Modifier) {
    if (AppThemeColors.isGlass || AppThemeColors.isAdvancedGlass) return
    val base = MaterialTheme.colorScheme.background
    // 应用设置「默认主题渐变」关闭时回退纯色页面背景（浅色/深色一致）
    val gradientEnabled by AppSettingsStore.defaultThemeGradientState.collectAsState()
    if (!gradientEnabled) {
        Box(modifier.fillMaxSize().background(base))
        return
    }
    val accent = AppThemeColors.primary
    val accentNear = remember(accent) { shiftHue(accent, AccentNearHueShift) }
    // 深色模式：以黑为主体，对角颜色渐变（底色与主题色/近似色低比例混色），无光晕、无模糊
    if (AppThemeColors.isDark) {
        Box(
            modifier
                .fillMaxSize()
                .drawBehind {
                    drawRect(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                lerp(base, accent, DarkAccentMix),
                                base,
                                lerp(base, accentNear, DarkAccentNearMix),
                            ),
                            start = Offset.Zero,
                            end = Offset(size.width, size.height),
                        ),
                    )
                },
        )
        return
    }
    Box(
        modifier
            .fillMaxSize()
            .blur(DefaultPageBackgroundBlur, edgeTreatment = BlurredEdgeTreatment.Unbounded)
            .drawBehind { drawDefaultPageBackground(base, accent, accentNear) },
    )
}

private fun DrawScope.drawDefaultPageBackground(base: Color, accent: Color, accentNear: Color) {
    drawRect(color = base)
    val maxDim = size.maxDimension
    drawGlow(center = Offset(size.width * 0.18f, size.height * 0.08f), radius = maxDim * 0.62f, color = accent, alpha = AccentGlowAlpha)
    drawGlow(center = Offset(size.width * 0.88f, size.height * 0.92f), radius = maxDim * 0.58f, color = accentNear, alpha = AccentNearGlowAlpha)
}

private fun DrawScope.drawGlow(center: Offset, radius: Float, color: Color, alpha: Float) {
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(color.copy(alpha = alpha), Color.Transparent),
            center = center,
            radius = radius,
        ),
        radius = radius,
        center = center,
    )
}

/** 色相偏移的近似色（略降饱和，避免抢主题色）。 */
private fun shiftHue(color: Color, degrees: Float): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(color.toArgb(), hsv)
    hsv[0] = (hsv[0] + degrees + 360f) % 360f
    hsv[1] = (hsv[1] * 0.88f).coerceIn(0f, 1f)
    return Color(android.graphics.Color.HSVToColor(hsv))
}
