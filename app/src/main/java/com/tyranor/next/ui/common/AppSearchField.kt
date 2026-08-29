package com.tyranor.next.ui.common

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.tyranor.next.R
import com.tyranor.next.theme.MiuixSettingsTheme
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.SearchBar
import top.yukonga.miuix.kmp.basic.SearchBarDefaults
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Miuix 风格统一搜索/单行输入框（非展开内嵌式）。
 *
 * 统一规范（详见 AGENT.md「搜索/输入框统一规范」）：
 * - 全 App 的搜索过滤框与弹窗内单行文本输入一律使用本组件，禁止直接拼装 Miuix
 *   `SearchBar`/`InputField`，禁止使用 Material 的 `TextField`/`OutlinedTextField`。
 * - 前导图标固定 26dp、tint 取 `MiuixTheme.colorScheme.primary`、内边距取
 *   `SearchBarDefaults.LeadingIcon*`，均由组件内部处理，调用方不要传色值/尺寸。
 * - `expanded` 恒为 false（非展开内嵌式），不使用展开式全屏搜索页形态。
 *
 * @param query 输入内容，状态由调用方持有
 * @param onQueryChange 输入变化回调（即时过滤场景在此更新过滤条件）
 * @param onSearch 键盘 IME 动作回调；即时过滤场景可不传（默认空实现）
 * @param leadingIcon 前导图标 drawable；null 用默认搜索图标，非搜索语义必须传对应图标
 * @param iconContentDescription 无障碍描述，跟随图标语义
 * @param textStyle 输入文字样式；null 用 miuix 默认 `main`（17sp），需要其他字号可传（如弹窗场景传 `bodyMedium`）
 */
@Composable
fun AppSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    onSearch: () -> Unit = { },
    leadingIcon: Painter? = null,
    iconContentDescription: String = "Search",
    textStyle: TextStyle? = null,
) {
    MiuixSettingsTheme {
        // miuix 无 controller 的 MiuixTheme 重载不提供 LocalContentColor（默认黑色），
        // InputField 内部强制以 LocalContentColor.current 作为输入文字颜色，
        // 此处显式提供主题 onBackground（深色=白色系 / 浅色=深灰），保证深浅色下文字正确。
        CompositionLocalProvider(LocalContentColor provides MiuixTheme.colorScheme.onBackground) {
            SearchBar(
            inputField = {
                InputField(
                    query = query,
                    onQueryChange = onQueryChange,
                    onSearch = { onSearch() },
                    textStyle = textStyle ?: MiuixTheme.textStyles.main,
                    expanded = false,
                    onExpandedChange = { },
                    leadingIcon = {
                        Icon(
                            modifier = Modifier
                                .padding(start = SearchBarDefaults.LeadingIconStartPadding, end = SearchBarDefaults.LeadingIconEndPadding)
                                .size(26.dp),
                            painter = leadingIcon ?: painterResource(R.drawable.ic_game_search),
                            tint = MiuixTheme.colorScheme.primary,
                            contentDescription = iconContentDescription,
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            expanded = false,
            onExpandedChange = { },
            modifier = modifier.fillMaxWidth(),
            content = { },
            )
        }
    }
}
