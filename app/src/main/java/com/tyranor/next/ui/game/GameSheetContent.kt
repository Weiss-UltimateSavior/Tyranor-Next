package com.tyranor.next.ui.game

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tyranor.next.R
import com.tyranor.next.ui.common.NoRippleButton

/** 短窗内操作与条目一起滚动，保留完整测量高度；空间充足时启动按钮固定在底部。 */
@Composable
internal fun GameSheetContent(
    onLaunch: () -> Unit,
    modifier: Modifier = Modifier,
    content: LazyListScope.() -> Unit,
) {
    BoxWithConstraints(modifier) {
        // 按字体比例为标题、至少一个条目和完整操作区留空间，避免大字模式挤压按钮。
        val pinLaunch = maxHeight >= 240.dp * LocalDensity.current.fontScale
        Column(Modifier.fillMaxWidth()) {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                contentPadding = PaddingValues(start = 16.dp, top = 6.dp, end = 16.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                content()
                if (!pinLaunch) {
                    item(key = "launch_action") {
                        GameSheetLaunchAction(onLaunch, horizontalInset = 0.dp)
                    }
                }
            }
            if (pinLaunch) GameSheetLaunchAction(onLaunch, horizontalInset = 16.dp)
        }
    }
}

@Composable
private fun GameSheetLaunchAction(onLaunch: () -> Unit, horizontalInset: Dp) {
    NoRippleButton(
        text = stringResource(R.string.game_launch_action),
        modifier = Modifier
            .gameSheetEntrance(1)
            .fillMaxWidth()
            .padding(start = horizontalInset, end = horizontalInset, top = 10.dp, bottom = 22.dp),
        onClick = onLaunch,
    )
}
