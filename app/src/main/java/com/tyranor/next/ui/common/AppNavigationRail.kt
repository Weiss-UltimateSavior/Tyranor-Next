package com.tyranor.next.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.tyranor.next.theme.AppComponentCornerRadius
import com.tyranor.next.theme.AppThemeColors
import com.tyranor.next.theme.GlassNavSurface
import com.tyranor.next.theme.NavWhite
import com.tyranor.next.theme.UnselectedGrey
import com.tyranor.next.theme.glassBorder

/**
 * 平板侧栏统一显示图标与文字标签。复古玻璃使用独立的栏体底色与描边。
 */
@Composable
internal fun AppNavigationRail(
    selectedIndex: Int,
    items: List<LiquidGlassNavItem>,
    onItemClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    flushWithContent: Boolean = false,
) {
    val cornerRadius = AppComponentCornerRadius
    val shape = RoundedCornerShape(topEnd = cornerRadius, bottomEnd = cornerRadius)
    val selectedColor = MaterialTheme.colorScheme.primary
    val unselectedColor = UnselectedGrey
    val itemColors = NavigationRailItemDefaults.colors(
        selectedIconColor = selectedColor,
        selectedTextColor = selectedColor,
        indicatorColor = Color.Transparent,
        unselectedIconColor = unselectedColor,
        unselectedTextColor = unselectedColor,
    )
    CompositionLocalProvider(LocalRippleConfiguration provides null) {
        Surface(
            modifier = modifier
                .fillMaxHeight()
                // 横屏三键导航可能位于侧面，栏体需避开起始侧系统栏。
                .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Start))
                .padding(top = 30.dp, end = if (flushWithContent) 0.dp else 12.dp, bottom = 30.dp),
            shape = shape,
            color = if (AppThemeColors.isGlass) GlassNavSurface else NavWhite,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(min = 80.dp)
                    .glassBorder(shape)
                    .padding(vertical = 4.dp)
                    .selectableGroup()
                    .semantics { isTraversalGroup = true },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
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
                                unselectedColor = unselectedColor,
                                selectedColor = selectedColor,
                                animationLabel = "railIconFill$index",
                            )
                        },
                        label = { Text(item.label) },
                        colors = itemColors,
                    )
                }
            }
        }
    }
}
