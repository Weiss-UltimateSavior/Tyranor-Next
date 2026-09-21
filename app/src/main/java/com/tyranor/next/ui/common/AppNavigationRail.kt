package com.tyranor.next.ui.common

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.shadow.Shadow
import com.tyranor.next.theme.AdvancedGlassNavSurface
import com.tyranor.next.theme.AppComponentShape
import com.tyranor.next.theme.AppThemeColors
import com.tyranor.next.theme.GlassNavSurface
import com.tyranor.next.theme.NavWhite
import com.tyranor.next.theme.UnselectedGrey
import com.tyranor.next.theme.glassBorder
import com.tyranor.next.theme.glassShadow

/** 悬浮玻璃侧栏宽度（与悬浮默认导航条同高，视觉体量一致）。 */
private val RailWidth = 64.dp

/** 侧栏单个导航项的方形触控区。 */
private val RailItemSize = 56.dp

/** 侧栏高级玻璃采样的模糊半径（与悬浮默认导航条一致）。 */
private val RailBlurRadius = 18.dp

/**
 * 平板/大窗口的主导航侧栏：**按外观风格取该主题「默认导航栏」的形态**——
 * - 默认外观：Material3 [NavigationRail]（与默认底栏同款选中态、同款图标填充动画）；
 * - 复古玻璃：悬浮玻璃柱（`GlassNavSurface` + 渐变描边）；
 * - 高级玻璃：悬浮真玻璃柱（`drawBackdrop` 采样背景层 + vibrancy + 投影 + 与组件同款受光内描边）。
 *
 * 液态玻璃 · 经典 / 透镜两档是底部横排的专用实现（透镜更是三层采样 + 折射轨迹），
 * **在平板上不生效**，直接落到本侧栏的主题默认形态（由调用方保证：平板模式下不渲染液态底栏）。
 *
 * [backdrop] 由宿主录制（高级玻璃下为「纯背景层」，坐标与窗口同源；
 * 侧栏位于内容层之外，直接采样内容层会因坐标越界采不到东西）。
 */
@Composable
internal fun AppNavigationRail(
    selectedIndex: Int,
    items: List<LiquidGlassNavItem>,
    onItemClick: (Int) -> Unit,
    backdrop: Backdrop?,
    modifier: Modifier = Modifier,
) {
    if (!AppThemeColors.isGlass) {
        DefaultNavigationRail(selectedIndex, items, onItemClick, modifier)
    } else {
        FloatingGlassNavigationRail(selectedIndex, items, onItemClick, backdrop, modifier)
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
        }
    }
}

/**
 * 玻璃系外观的悬浮玻璃柱：复古 = 实色玻璃底；高级 = 真 backdrop 采样（vibrancy + blur）
 * + 柔光投影 + 受光内描边，光学组合与悬浮默认导航条完全一致。
 */
@Composable
private fun FloatingGlassNavigationRail(
    selectedIndex: Int,
    items: List<LiquidGlassNavItem>,
    onItemClick: (Int) -> Unit,
    backdrop: Backdrop?,
    modifier: Modifier = Modifier,
) {
    val advanced = AppThemeColors.isAdvancedGlass
    val density = LocalDensity.current
    val shape = AppComponentShape
    val activeBackdrop = if (
        advanced && backdrop != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    ) {
        backdrop
    } else {
        null
    }
    Box(
        modifier = modifier
            .fillMaxHeight()
            .windowInsetsPadding(
                WindowInsets.systemBars.only(WindowInsetsSides.Vertical + WindowInsetsSides.Start),
            )
            .padding(horizontal = 12.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        val surfaceModifier = if (activeBackdrop != null) {
            Modifier
                .drawBackdrop(
                    backdrop = activeBackdrop,
                    shape = { shape },
                    effects = {
                        vibrancy()
                        blur(with(density) { RailBlurRadius.toPx() })
                    },
                    // 描边统一由 glassBorder 的受光内描边绘制（与页面组件同款）
                    highlight = null,
                    shadow = { Shadow.Default.copy(alpha = 0.85f) },
                    onDrawSurface = { drawRect(AdvancedGlassNavSurface) },
                )
                .glassBorder(shape)
        } else {
            Modifier
                .clip(shape)
                .background(if (advanced) AdvancedGlassNavSurface else GlassNavSurface)
                .glassBorder(shape)
        }
        Column(
            modifier = Modifier
                .width(RailWidth)
                // 投影挂在背景之前，透明玻璃膜不会被投影压暗
                .then(if (advanced) Modifier.glassShadow(shape) else Modifier)
                .then(surfaceModifier)
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
