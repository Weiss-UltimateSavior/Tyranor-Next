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
 * 应用根背景统一组件：玻璃外观风格为黑灰渐变 + 顶部环境光晕（三档竖向渐变：
 * 冷灰顶 → 中灰过渡 → 近黑底，叠加一层自顶向下的低透明度白色径向光晕，避免纯色渐变发闷）；
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
        val backgroundModifier = if (AppThemeColors.isGlass) {
            Modifier.drawWithCache {
                val baseBrush = Brush.verticalGradient(
                    colorStops = arrayOf(
                        0f to GlassBgTop,
                        0.52f to GlassBgMid,
                        1f to GlassBgBottom,
                    ),
                )
                // 顶部环境光：圆心略高于屏幕顶端，半径约 55% 屏高，只做微弱层次不做提亮
                val glowBrush = Brush.radialGradient(
                    colors = listOf(GlassBgGlow, Color.Transparent),
                    center = Offset(size.width * 0.5f, -size.height * 0.08f),
                    radius = size.height * 0.55f,
                )
                onDrawBehind {
                    drawRect(baseBrush)
                    drawRect(glowBrush)
                }
            }
        } else {
            Modifier.background(MaterialTheme.colorScheme.background)
        }
        Box(Modifier.fillMaxSize().then(backgroundModifier)) {
            content()
        }
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
