package com.tyranor.next.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.dp

/**
 * 顶部栏图标按钮统一规范（排版对齐游戏页顶部栏）：
 * Image 渲染 + 34dp 触控区 + 6dp 圆角裁剪 + clickable + 4dp 内边距；
 * 左侧自带 2dp 间距，保证相邻图标之间留白统一。
 * tint 由调用方传当前主题色（Material 页面传 MaterialTheme.colorScheme.primary，
 * Miuix 页面传 MiuixTheme.colorScheme.primary）。
 * 全 App 顶部栏图标按钮必须使用本组件，禁止自行用 IconButton/Icon 拼装。
 */
@Composable
fun TopBarIcon(
    painter: Painter,
    contentDescription: String?,
    tint: Color,
    onClick: () -> Unit,
) {
    Image(
        painter = painter,
        contentDescription = contentDescription,
        colorFilter = ColorFilter.tint(tint),
        modifier = Modifier
            .padding(start = 2.dp)
            .size(34.dp)
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(4.dp),
    )
}
