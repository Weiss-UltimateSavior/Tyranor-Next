package com.tyranor.next.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 应用根背景统一组件：玻璃外观风格为黑灰渐变 + 环境光层（三档竖向渐变：
 * 冷灰顶 → 中灰过渡 → 近黑底，叠加白色顶部光晕 + 主题色对角环境光——
 * 右上为主、左下为辅，随「色调轮盘」实时变色，让玻璃面有颜色可透、避免叠在纯黑上没质感）；
 * 默认风格沿用主题页面背景色。所有顶层组合（MainActivity / AppScreenScaffold）必须经本组件
 * 包裹，否则透明页面背景会露出窗口底色。内部保留 Surface 以维持 contentColor 语义。
 */
@Composable
fun GlassBackground(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        val accent = AppThemeColors.primary
        val backgroundModifier = if (AppThemeColors.isGlass) {
            Modifier.glassPageBackground(accent)
        } else {
            Modifier.background(MaterialTheme.colorScheme.background)
        }
        Box(Modifier.fillMaxSize().then(backgroundModifier)) {
            content()
        }
    }
}

/**
 * 玻璃外观风格的页面背景绘制层（黑灰竖向渐变 + 白色顶部光晕 + 主题色对角环境光）。
 *
 * 单独抽出的原因（分析报告 §8.4 第 1 条 / §5.3 采样边界）：
 * 背景原本只画在 `GlassBackground`（Activity 根部），而液态玻璃底栏采样的是 `MainScreen`
 * 内容层的 `layerBackdrop` 录制结果——在采样层**之外**。玻璃风格下 `PageGrey` 为透明色，
 * 于是底栏会采到“没有背景”的内容（透镜边缘出现空采样/暗孔）。
 * 抽出后由需要被采样的内容层复用同一份绘制，保证背景与正文处于同一坐标原点的同一采样源，
 * 也避免两处各写一份渐变导致颜色漂移。
 *
 * [accent] 由调用方在组合期读取 `AppThemeColors.primary` 后传入（不在绘制期读取快照），
 * 保证「色调轮盘」换色时随重组刷新——与原实现把 accent 读在 `GlassBackground` 函数体内一致。
 * 注意：本 Modifier 不做风格判断，调用方需自行确认处于玻璃外观风格。
 */
fun Modifier.glassPageBackground(accent: Color): Modifier = this.drawWithCache {
    val baseBrush = Brush.verticalGradient(
        colorStops = arrayOf(
            0f to GlassBgTop,
            0.52f to GlassBgMid,
            1f to GlassBgBottom,
        ),
    )
    // 顶部白色环境光：圆心略高于屏幕顶端，半径约 55% 屏高，只做微弱层次
    val topGlow = Brush.radialGradient(
        colors = listOf(GlassBgGlow, Color.Transparent),
        center = Offset(size.width * 0.5f, -size.height * 0.08f),
        radius = size.height * 0.55f,
    )
    // 主题色环境光：右上（主，位于顶栏下方、卡片区内，透过玻璃可见）+ 左下（辅，补对角深度）
    val accentTopGlow = Brush.radialGradient(
        colors = listOf(accent.copy(alpha = 0.26f), Color.Transparent),
        center = Offset(size.width * 0.85f, size.height * 0.22f),
        radius = size.width * 1.05f,
    )
    val accentBottomGlow = Brush.radialGradient(
        colors = listOf(accent.copy(alpha = 0.12f), Color.Transparent),
        center = Offset(size.width * 0.10f, size.height * 0.78f),
        radius = size.width,
    )
    onDrawBehind {
        drawRect(baseBrush)
        drawRect(topGlow)
        drawRect(accentTopGlow)
        drawRect(accentBottomGlow)
    }
}

/**
 * 玻璃风格 0.5dp 发丝描边；默认风格原样返回。
 * 颜色/显隐由 [AppThemeColors] 快照驱动，风格切换自动重组刷新。
 * 用 drawWithContent 在内容之后绘制，保证描边盖在卡片/条目背景之上。
 */
fun Modifier.glassBorder(
    shape: Shape = AppComponentShape,
    width: Dp = 0.5.dp,
    color: Color = GlassBorder,
): Modifier =
    if (!AppThemeColors.isGlass) {
        this
    } else {
        this.drawWithContent {
            drawContent()
            drawOutline(
                outline = shape.createOutline(size, layoutDirection, this),
                color = color,
                style = Stroke(width = width.toPx()),
            )
        }
    }
