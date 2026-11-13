package com.tyranor.next.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tyranor.next.theme.PageTextColor

/**
 * 一级页面（首页 / 游戏 / 引擎 / 设置）的统一顶部栏。
 *
 * 透明背景 → statusBarsPadding → 64dp 标题行；标题居左、titleLarge Bold，
 * 默认使用页面前景色 [PageTextColor]。背景由页面根层提供，不能用不透明色覆盖。
 * 二级页面使用 [AppSecondaryTopBar]，不在一级顶部栏添加返回按钮。
 *
 * @param background 背景色，保持默认 [Color.Transparent]，露出页面根背景。
 * @param contentColor 标题前景色，默认 [PageTextColor]，适配深浅色与玻璃外观。
 * @param underTitle 标题下方附加区，如游戏页可折叠搜索框。
 * @param trailing 右侧操作区，图标使用 [TopBarIcon]。
 */
@Composable
fun AppTopBar(
    title: String,
    modifier: Modifier = Modifier,
    // 根背景透出，适配默认与玻璃外观。
    background: Color = Color.Transparent,
    contentColor: Color = PageTextColor,
    underTitle: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    AppTopBarLayout(title, modifier, background, contentColor, underTitle, trailing)
}

/** 两类顶栏共用状态栏留白、标题排版与附加区布局。 */
@Composable
internal fun AppTopBarLayout(
    title: String,
    modifier: Modifier,
    background: Color,
    contentColor: Color,
    underTitle: (@Composable () -> Unit)?,
    trailing: (@Composable () -> Unit)?,
    leading: (@Composable () -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth().background(background)) {
        Column(modifier = Modifier.fillMaxWidth().statusBarsPadding()) {
            Row(
                modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                leading?.invoke()
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = contentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                trailing?.invoke()
            }
            underTitle?.invoke()
        }
    }
}
