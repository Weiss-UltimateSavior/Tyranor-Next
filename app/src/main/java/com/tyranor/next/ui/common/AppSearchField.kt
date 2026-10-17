package com.tyranor.next.ui.common

import android.os.Build
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import com.tyranor.next.theme.glassShadow
import com.tyranor.next.theme.glassBorder
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.SearchBar
import top.yukonga.miuix.kmp.basic.SearchBarDefaults
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Miuix 风格统一搜索/单行输入框（非展开内嵌式）。
 *
 * 统一规范（详见 AGENTS.md「搜索/输入框统一规范」）：
 * - 全 App 的搜索过滤框与弹窗内单行文本输入一律使用本组件，禁止直接拼装 Miuix
 *   `SearchBar`/`InputField`，禁止使用 Material 的 `TextField`/`OutlinedTextField`。
 * - 前导图标固定 26dp、tint 取 `MiuixTheme.colorScheme.primary`、内边距取
 *   `SearchBarDefaults.LeadingIcon*`，均由组件内部处理，调用方不要传色值/尺寸。
 * - `expanded` 恒为 false（非展开内嵌式），不使用展开式全屏搜索页形态。
 * - API 26/27 在本组件内兼容 Miuix 0.9.2 的折叠输入禁用逻辑；其他版本仍走 InputField。
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
    iconContentDescription: String? = null,
    textStyle: TextStyle? = null,
) {
    val resolvedIconContentDescription = iconContentDescription
        ?: androidx.compose.ui.res.stringResource(R.string.common_search_content_description)
    MiuixSettingsTheme {
        // miuix 无 controller 的 MiuixTheme 重载不提供 LocalContentColor（默认黑色），
        // InputField 内部强制以 LocalContentColor.current 作为输入文字颜色，
        // 此处显式提供主题 onBackground（深色=白色系 / 浅色=深灰），保证深浅色下文字正确。
        CompositionLocalProvider(LocalContentColor provides MiuixTheme.colorScheme.onBackground) {
            SearchBar(
                inputField = {
                    val fieldModifier = Modifier.fillMaxWidth().glassShadow(CircleShape).glassBorder(CircleShape)
                    val fieldLeadingIcon: @Composable () -> Unit = {
                        Icon(
                            modifier = Modifier
                                .padding(start = SearchBarDefaults.LeadingIconStartPadding, end = SearchBarDefaults.LeadingIconEndPadding)
                                .size(26.dp),
                            painter = leadingIcon ?: painterResource(R.drawable.ic_game_search),
                            tint = MiuixTheme.colorScheme.primary,
                            contentDescription = resolvedIconContentDescription,
                        )
                    }
                    if (Build.VERSION.SDK_INT in Build.VERSION_CODES.O..Build.VERSION_CODES.O_MR1) {
                        // 局部兼容豁免：Miuix 0.9.2 在 API 26/27 禁用折叠态 InputField。
                        // 仅公共组件内部用兼容输入保持可编辑，沿用 Miuix 配色、胶囊与玻璃材质；
                        // 不以展开启用输入（会自动聚焦和拉起键盘），也不在失焦时清空内容。
                        // API 28+ 仍走原 InputField，页面不自行拼装此兼容分支。
                        EmbeddedSearchInputField(
                            query = query,
                            onQueryChange = onQueryChange,
                            onSearch = onSearch,
                            textStyle = textStyle ?: MiuixTheme.textStyles.main,
                            leadingIcon = fieldLeadingIcon,
                            modifier = fieldModifier,
                        )
                    } else {
                        InputField(
                            query = query,
                            onQueryChange = onQueryChange,
                            onSearch = { onSearch() },
                            textStyle = textStyle ?: MiuixTheme.textStyles.main,
                            expanded = false,
                            onExpandedChange = { },
                            leadingIcon = fieldLeadingIcon,
                            // 描边位于 InputField 胶囊上，而不是外层 SearchBar 的布局间距。
                            modifier = fieldModifier,
                        )
                    }
                },
                expanded = false,
                onExpandedChange = { },
                modifier = modifier.fillMaxWidth(),
                content = { },
            )
        }
    }
}
