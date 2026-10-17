package com.tyranor.next.ui.common

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.tyranor.next.R

/** 悬浮导航沿用原版图标；颜色、缩放与无障碍语义由导航按钮提供。 */
@Composable
internal fun FloatingNavIcon(
    @DrawableRes iconRes: Int,
    tint: () -> Color,
    modifier: Modifier = Modifier,
    closing: Boolean = false,
) {
    val source = painterResource(if (closing) R.drawable.ic_game_add else iconRes)
    val currentTint by rememberUpdatedState(tint)
    val painter = remember(source) {
        object : Painter() {
            override val intrinsicSize get() = source.intrinsicSize
            private var lastColor = Color.Unspecified
            private var filter: ColorFilter? = null

            override fun DrawScope.onDraw() {
                val color = currentTint()
                if (color != lastColor) {
                    lastColor = color
                    filter = ColorFilter.tint(color)
                }
                with(source) { draw(size = size, colorFilter = filter) }
            }
        }
    }
    Image(
        painter = painter,
        contentDescription = null,
        modifier = modifier.size(24.dp).rotate(if (closing) 45f else 0f),
    )
}
