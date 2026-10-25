package com.tyranor.next.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import com.tyranor.next.R
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SearchBarDefaults
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.SearchCleanup
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 仅供 AppSearchField 在 API 26/27 内嵌使用：保留 Miuix 胶囊与键盘行为，
 * 不应用库 InputField 为展开式搜索页设计的禁用、自动聚焦或失焦清空逻辑。
 *
 * 另供安全输入（如 WebDAV 密码）使用：Miuix 0.9.2 的 `InputField` 不支持文本变换，
 * 掩码场景由 AppSearchField 统一改走本实现。清空按钮按原文判空与清空，展示文字由
 * [visualTransformation] 变换；键盘类型与 IME 动作由调用方决定（密码用
 * [KeyboardType.Password] + [ImeAction.Done]。本组件只设置键盘类型，不额外声明 Android
 * autofill 内容类型；普通文本粘贴由 BasicTextField 提供。
 */
@Composable
internal fun EmbeddedSearchInputField(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    textStyle: TextStyle,
    leadingIcon: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Search,
) {
    BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier,
        singleLine = true,
        textStyle = MiuixTheme.textStyles.main.copy(fontWeight = FontWeight.Medium)
            .merge(textStyle).copy(color = LocalContentColor.current),
        cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
        visualTransformation = visualTransformation,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
        // Done 与 Search 都映射到 onSearch：密码等场景以「完成」键触发确认（见调用方传参）
        keyboardActions = KeyboardActions(onSearch = { onSearch() }, onDone = { onSearch() }),
        decorationBox = { innerTextField ->
            Box(
                modifier = Modifier.background(MiuixTheme.colorScheme.surfaceContainerHigh, CircleShape),
                contentAlignment = Alignment.CenterStart,
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    leadingIcon()
                    Box(
                        modifier = Modifier.weight(1f).heightIn(min = SearchBarDefaults.InputFieldMinHeight),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        innerTextField()
                    }
                    if (query.isNotEmpty()) {
                        Box(
                            modifier = Modifier.padding(
                                start = SearchBarDefaults.TrailingIconStartPadding,
                                end = SearchBarDefaults.TrailingIconEndPadding,
                            ),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            Icon(
                                modifier = Modifier.clip(CircleShape).clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = { onQueryChange("") },
                                ),
                                imageVector = MiuixIcons.Basic.SearchCleanup,
                                tint = MiuixTheme.colorScheme.onSurfaceContainerHighest,
                                contentDescription = stringResource(R.string.common_clear_input_content_description),
                            )
                        }
                    }
                }
            }
        },
    )
}
