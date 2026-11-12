package com.tyranor.next.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/** 条目右侧的统一加粗 chevron（>）。描边宽度固定，RTL 下自动镜像。 */
@Composable
fun BoldChevronRight(
    color: Color,
    modifier: Modifier = Modifier,
    width: Dp = 10.dp,
    height: Dp = 16.dp,
    strokeWidth: Dp = 2.dp,
) {
    val layoutDirection = LocalLayoutDirection.current
    Canvas(
        modifier
            .size(width = width, height = height)
            .graphicsLayer {
                scaleX = if (layoutDirection == LayoutDirection.Rtl) -1f else 1f
            },
    ) {
        val path = Path().apply {
            moveTo(size.width * 0.22f, size.height * 0.12f)
            lineTo(size.width * 0.78f, size.height * 0.5f)
            lineTo(size.width * 0.22f, size.height * 0.88f)
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(
                width = strokeWidth.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
        )
    }
}
