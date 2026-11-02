package com.tyranor.next.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.tyranor.next.theme.AppComponentShape
import com.tyranor.next.theme.AppThemeColors
import com.tyranor.next.theme.GlassNavSurface
import com.tyranor.next.theme.NavWhite
import com.tyranor.next.theme.UnselectedGrey
import com.tyranor.next.theme.glassBorder

/** 悬浮玻璃侧栏宽度（与悬浮默认导航条同高，视觉体量一致）。 */
private val RailWidth = 64.dp

/** 侧栏单个导航项的方形触控区。 */
private val RailItemSize = 56.dp

/**
 * 平板/大窗口的主导航侧栏：**按外观风格取该主题「默认导航栏」的形态**——
 * - 默认外观：Material3 [NavigationRail]（与默认底栏同款选中态、同款图标填充动画）；
 * - 复古玻璃：悬浮玻璃柱（`GlassNavSurface` + 渐变描边）。
 *
 * 液态玻璃 · 经典 / 透镜两档是底部横排的专用实现（透镜更是三层采样 + 折射轨迹），
 * **在平板上不生效**，直接落到本侧栏的主题默认形态（由调用方保证：平板模式下不渲染液态底栏）。
 */
@Composable
internal fun AppNavigationRail(
    selectedIndex: Int,
    items: List<LiquidGlassNavItem>,
    onItemClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!AppThemeColors.isGlass) {
        DefaultNavigationRail(selectedIndex, items, onItemClick, modifier)
    } else {
        FloatingGlassNavigationRail(selectedIndex, items, onItemClick, modifier)
    }
}

/** 默认外观的 Material3 侧栏：与默认底栏同布局同配色，去掉 ripple 与选中指示器。 */
@Composable
private fun DefaultNavigationRail(
    selectedIndex: Int,
    items: List<LiquidGlassNavItem>,
    onItemClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 与 DefaultBottomNavigationBar 一致：去掉点击 ripple（material3 1.4 起读取 LocalRippleConfiguration）
    CompositionLocalProvider(LocalRippleConfiguration provides null) {
        NavigationRail(
            modifier = modifier,
            containerColor = NavWhite,
            contentColor = MaterialTheme.colorScheme.onSurface,
            // 横屏三键导航在侧面：start 侧 inset 必须一并避开
            windowInsets = WindowInsets.systemBars.only(
                WindowInsetsSides.Vertical + WindowInsetsSides.Start,
            ),
        ) {
            // M3 NavigationRail 默认顶对齐：上下配重让整组导航项垂直居中，
            // 与玻璃系悬浮柱（contentAlignment = Center）的视觉一致。
            Spacer(Modifier.weight(1f))
            items.forEachIndexed { index, item ->
                val selected = index == selectedIndex
                NavigationRailItem(
                    selected = selected,
                    onClick = { onItemClick(index) },
                    icon = {
                        NavigationTabIcon(
                            iconRes = item.iconRes,
                            contentDescription = item.label,
                            selected = selected,
                            unselectedColor = UnselectedGrey,
                            selectedColor = MaterialTheme.colorScheme.primary,
                            animationLabel = "railIconFill$index",
                        )
                    },
                    label = { Text(item.label) },
                    colors = NavigationRailItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        // 去掉选中高亮：仅图标填充动画与文字颜色区分选中态
                        indicatorColor = Color.Transparent,
                        unselectedIconColor = UnselectedGrey,
                        unselectedTextColor = UnselectedGrey,
                    ),
                )
            }
            Spacer(Modifier.weight(1f))
        }
    }
}

/** 复古玻璃外观的悬浮玻璃柱：实色玻璃底 + 0.5dp 渐变描边。 */
@Composable
private fun FloatingGlassNavigationRail(
    selectedIndex: Int,
    items: List<LiquidGlassNavItem>,
    onItemClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = AppComponentShape
    Box(
        modifier = modifier
            .fillMaxHeight()
            .windowInsetsPadding(
                WindowInsets.systemBars.only(WindowInsetsSides.Vertical + WindowInsetsSides.Start),
            )
            .padding(horizontal = 12.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(RailWidth)
                .clip(shape)
                .background(GlassNavSurface)
                .glassBorder(shape)
                .padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items.forEachIndexed { index, item ->
                val selected = index == selectedIndex
                Box(
                    modifier = Modifier
                        .size(RailItemSize)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onItemClick(index) },
                    contentAlignment = Alignment.Center,
                ) {
                    NavigationTabIcon(
                        iconRes = item.iconRes,
                        contentDescription = item.label,
                        selected = selected,
                        unselectedColor = UnselectedGrey,
                        selectedColor = MaterialTheme.colorScheme.primary,
                        animationLabel = "railIconFill$index",
                    )
                }
            }
        }
    }
}
