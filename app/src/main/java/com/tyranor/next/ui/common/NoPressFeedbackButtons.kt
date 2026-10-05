package com.tyranor.next.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tyranor.next.theme.AppComponentShape

/**
 * 页内无按压反馈按钮（配合 AGENT.md「弹窗点击反馈统一规范」的同一意图）。
 *
 * `AppScreenScaffold` 的 `WithoutPressIndication` 只影响走 `LocalIndication` 的组件；
 * Material3 的 `Button` / `TextButton` 内部显式使用 `ripple()`，不经过 `LocalIndication`，
 * 因此页内需要「无按压反馈」的按钮必须使用本文件组件（`indication = null`）。
 */

/** 页内文本按钮：无涟漪/按压效果，颜色随可用态变化（正文档 bodyMedium）。 */
@Composable
fun NoRippleTextButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (enabled) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        },
        modifier = modifier
            .clip(AppComponentShape)
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

/**
 * 页内填充按钮：无涟漪/按压效果。
 * [tonal] = true 时为浅色底（主色 14%）+ 主色文字，用于次级动作（如「前往授权」）。
 */
@Composable
fun NoRippleButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tonal: Boolean = false,
    leadingIcon: Painter? = null,
    iconContentDescription: String? = null,
    onClick: () -> Unit,
) {
    val container = when {
        tonal -> MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
        enabled -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.primary.copy(alpha = 0.38f)
    }
    // 主题色实底上的文字/图标固定白色（不随主题色变化）；tonal 为浅底次级样式，文字用主题色
    val contentColor = if (tonal) MaterialTheme.colorScheme.primary else Color.White
    Row(
        modifier = modifier
            .clip(AppComponentShape)
            .background(container)
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 20.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leadingIcon?.let { painter ->
            Icon(
                painter = painter,
                contentDescription = iconContentDescription,
                tint = contentColor,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
