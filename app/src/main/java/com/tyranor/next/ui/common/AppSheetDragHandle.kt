package com.tyranor.next.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.tyranor.next.theme.AppThemeColors
import com.tyranor.next.theme.GlassBorder
import com.tyranor.next.theme.PageSecondaryTextColor

/**
 * `ModalBottomSheet` 抽屉的顶部装饰把手（所有抽屉共用）。
 *
 * 抽屉已禁用拖拽手势，故用紧凑内边距替代 M3 默认把手的 22dp 垂直留白：
 * 默认外观/白雾玻璃为 32×4dp 圆角条；[showGlassTopBorder] 为 true 时，复古玻璃在
 * 抽屉真实顶边补 0.5dp 发丝描边（不能挂到 Surface 外层 modifier，否则会按未偏移的
 * 布局位置落到背景里形成白线）。
 */
@Composable
internal fun AppSheetDragHandle(showGlassTopBorder: Boolean = false) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (showGlassTopBorder && AppThemeColors.isGlass) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(0.5.dp)
                    .background(GlassBorder),
            )
        }
        Box(
            Modifier
                .padding(top = 13.dp, bottom = 6.dp)
                .size(width = 32.dp, height = 4.dp)
                .clip(CircleShape)
                .background(PageSecondaryTextColor),
        )
    }
}
