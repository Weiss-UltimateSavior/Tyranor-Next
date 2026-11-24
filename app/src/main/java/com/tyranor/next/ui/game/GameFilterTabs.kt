package com.tyranor.next.ui.game

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.tyranor.next.theme.AppComponentShape
import com.tyranor.next.theme.AppThemeColors
import com.tyranor.next.theme.MilkDivider
import com.tyranor.next.theme.PageGrey
import com.tyranor.next.ui.common.LocalSlidingTabLightPanel

/** 分栏的底槽：一道比所在面板深一档的圆角槽，让白色指示器在白色 / 浅色面板上也看得清（玻璃系外观里透明，白雾玻璃浅底托盘用微暗凹槽）。 */
@Composable
internal fun GameFilterTabTrack(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val trackColor = if (LocalSlidingTabLightPanel.current && AppThemeColors.isMilkGlass) MilkDivider else PageGrey
    Box(
        modifier
            .clip(AppComponentShape)
            .background(trackColor, AppComponentShape)
            .padding(FILTER_TRACK_PAD),
    ) { content() }
}

private val FILTER_TRACK_PAD = 2.dp
