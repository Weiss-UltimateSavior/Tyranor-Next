package com.tyranor.next.ui.common

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 导航项图标（选中态染色动画）：底层铺未选中色，上层选中色图标用渐变遮罩自下而上填充
 * （fill 0→1 时分界线从底边升到顶边），取消选中时自上而下退色。
 *
 * 底栏（`DefaultBottomNavigationBar`）与平板侧栏（`AppNavigationRail`）共用同一实现，
 * 保证两种导航形态的选中动效完全一致。
 */
@Composable
internal fun NavigationTabIcon(
    @DrawableRes iconRes: Int,
    contentDescription: String?,
    selected: Boolean,
    unselectedColor: Color,
    selectedColor: Color,
    modifier: Modifier = Modifier,
    size: Dp = 28.dp,
    animationLabel: String = "navIconFill",
) {
    val fill by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = tween(durationMillis = 700),
        label = animationLabel,
    )
    Box(modifier.size(size)) {
        Image(
            painter = painterResource(iconRes),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            colorFilter = ColorFilter.tint(unselectedColor),
        )
        Image(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    // 离屏合成，保证 DstIn 遮罩只作用于本层图标
                    compositingStrategy = CompositingStrategy.Offscreen
                    clip = true
                }
                .drawWithCache {
                    onDrawWithContent {
                        // fill=0 → 分界线在底边（全隐藏）；fill=1 → 分界线在顶边（全显示）
                        val edge = 1f - fill
                        val mask = Brush.verticalGradient(
                            colorStops = arrayOf(edge to Color.Transparent, edge to Color.White),
                        )
                        drawContent()
                        drawRect(brush = mask, blendMode = BlendMode.DstIn)
                    }
                },
            colorFilter = ColorFilter.tint(selectedColor),
        )
    }
}
