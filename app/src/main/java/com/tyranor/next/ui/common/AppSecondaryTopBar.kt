package com.tyranor.next.ui.common

import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.tyranor.next.R
import com.tyranor.next.theme.PageTextColor

/**
 * 二级页面统一顶部栏：加粗返回箭头 + 居左加粗标题，可保留右侧保存等操作。
 * 返回交给宿主的系统返回分发器，复用页面现有的返回处理与退出转场。
 * 顶栏与返回按钮均无底色，箭头跟随标题前景色，保留 48dp 触控范围。
 */
@Composable
fun AppSecondaryTopBar(
    title: String,
    modifier: Modifier = Modifier,
    background: Color = Color.Transparent,
    contentColor: Color = PageTextColor,
    underTitle: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val backDispatcher = checkNotNull(LocalOnBackPressedDispatcherOwner.current) {
        "AppSecondaryTopBar requires an OnBackPressedDispatcherOwner"
    }.onBackPressedDispatcher
    AppTopBarLayout(
        title = title,
        modifier = modifier,
        background = background,
        contentColor = contentColor,
        underTitle = underTitle,
        trailing = trailing,
        leading = {
            Box(
                modifier = Modifier.padding(end = 9.dp)
                    .size(48.dp)
                    .clickable(role = Role.Button) { backDispatcher.onBackPressed() },
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_top_bar_back),
                    contentDescription = stringResource(R.string.common_back),
                    colorFilter = ColorFilter.tint(contentColor),
                    modifier = Modifier.size(24.dp),
                )
            }
        },
    )
}
