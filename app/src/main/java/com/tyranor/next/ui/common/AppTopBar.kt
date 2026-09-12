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
import com.tyranor.next.theme.AppThemeColors
import com.tyranor.next.theme.GlassTopBarSurface

/**
 * 页面顶部栏统一组件（AGENT.md「页面顶部栏统一规范」的唯一入口）。
 *
 * 结构固定为：不透明背景容器 → statusBarsPadding → 64dp 标题行（标题居左 titleLarge Bold）：
 *
 * ```
 * Column(fillMaxWidth, background(background))        // 页面背景色容器（不透明）
 * ├── Column(statusBarsPadding)                        // 避开状态栏（背景延伸）
 * │   ├── Row(height 64dp, padding horizontal 16dp)    // 标题区 + 右侧图标
 * │   └── underTitle 附加区（如游戏页搜索框，可选）
 * ```
 *
 * 标题必须经本组件渲染（titleLarge + Bold + 页面内容色），右上角图标必须用 [TopBarIcon]。
 * 禁止任何页面自行手写该结构。
 *
 * @param title 标题文案
 * @param background 不透明背景色；默认 Material 页面背景色，Miuix 风格页面传 `MiuixTheme.colorScheme.background`，
 *   需要「色调切换」参与取色的页面传 `PageGrey`（见 AGENT.md 容器色约定）。
 *   玻璃外观风格下统一改用 [GlassTopBarSurface] 实色（MiuixScaffold 等内容会从顶栏下方滚过，
 *   透明会导致滚过的文字与标题重叠），此时本参数被忽略。
 * @param contentColor 标题前景色；默认 `colorScheme.onBackground`，与 [background] 配套传入
 * @param underTitle 标题行下方的附加内容（可选，如游戏页折叠的搜索框）；置于状态栏背景延伸区内
 * @param trailing 标题右侧的图标/内容（可选，必须用 [TopBarIcon] 等公共组件）
 */
@Composable
fun AppTopBar(
    title: String,
    modifier: Modifier = Modifier,
    background: Color = MaterialTheme.colorScheme.background,
    contentColor: Color = MaterialTheme.colorScheme.onBackground,
    underTitle: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    // 玻璃外观风格：顶栏必须不透明，否则滚动内容会从下方穿过与标题重叠
    val resolvedBackground = if (AppThemeColors.isGlass) GlassTopBarSurface else background
    Column(modifier.fillMaxWidth().background(resolvedBackground)) {
        Column(modifier = Modifier.fillMaxWidth().statusBarsPadding()) {
            Row(
                modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
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