package com.tyranor.next.ui.common

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tyranor.next.R
import com.tyranor.next.theme.AppThemeColors
import com.tyranor.next.theme.NavWhite
import com.tyranor.next.theme.TextColor
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 功能跳转条目统一组件：左侧图标 + 标题/摘要 + 右侧指示箭头。
 *
 * 统一规范（详见 AGENT.md「功能跳转条目统一规范」）：
 * - 所有「功能跳转列」（点击后进入/跳转/打开下一级的条目，如封面来源列表、群聊/频道项等）
 *   必须使用本组件，禁止用手写 Row/Column 拼装、禁止混用 Material 的 ListItem 等。
 * - 排版：圆角 8dp + 背景 [containerColor]（默认 [NavWhite]）+ 内边距（纵向 12dp / 横向 16dp）+ 左侧图标 24dp + 右侧箭头，
 *   标题用 [TextColor]、摘要用半透明辅助色；进入跳转的 icon 一律 `KeyboardArrowRight`。
 * - 背景色约定：页面上条目默认 [NavWhite]（页面背景 PageGrey 灰底白卡）；弹窗内条目传 `PageGrey`（弹窗背景 NavWhite 白底灰卡），
 *   与弹窗背景形成反色反差。两者互为对偶，且「色调切换」时同步互换，层次关系不变。
 * - 「色调切换」遵循统一规范：背景引用 `theme/Color.kt` 常量，前景取 [TextColor]，
 *   不依赖 `colorScheme.surface*`，保证弹窗作用域内外一致。
 *
 * @param title 条目标题
 * @param onClick 点击回调；传 null 表示不可用（灰色显示且不可点击）
 * @param modifier 外部修饰
 * @param summary 可选摘要
 * @param leadingIcon 左侧图标 drawable；null 时使用默认占位图标 [DEFAULT_LEADING_ICON]
 * @param containerColor 条目容器背景色；默认 [NavWhite]（页面灰底上的白卡），弹窗内应传 `PageGrey` 与白底弹窗形成反差
 */
@Composable
fun AppNavItem(
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    @DrawableRes leadingIcon: Int? = null,
    containerColor: Color = NavWhite,
    onClick: (() -> Unit)? = null,
) {
    val enabled = onClick != null
    val contentAlpha = if (enabled) 1f else 0.4f
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = { onClick?.invoke() })
            .background(containerColor)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(leadingIcon ?: DEFAULT_LEADING_ICON),
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            // 深色模式下左侧图标染白，保持低亮度背景上的可读性
            colorFilter = if (AppThemeColors.isDark) ColorFilter.tint(Color.White) else null,
        )
        Column(
            modifier = Modifier.weight(1f).padding(start = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = TextColor.copy(alpha = contentAlpha),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!summary.isNullOrBlank()) {
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextColor.copy(alpha = 0.55f * contentAlpha),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            modifier = Modifier.padding(start = 12.dp),
            // 深色模式下右侧箭头染白，与左侧图标保持一致
            tint = if (AppThemeColors.isDark) {
                Color.White.copy(alpha = contentAlpha)
            } else {
                MiuixTheme.colorScheme.onBackground.copy(alpha = contentAlpha)
            },
        )
    }
}

/** 功能条目的默认占位图标：调用方未提供 [AppNavItem.leadingIcon] 时使用。 */
private val DEFAULT_LEADING_ICON: Int = R.drawable.ic_engine_item