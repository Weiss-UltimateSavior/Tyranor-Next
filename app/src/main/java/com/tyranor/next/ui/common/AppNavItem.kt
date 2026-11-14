package com.tyranor.next.ui.common

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.Indication
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tyranor.next.R
import com.tyranor.next.theme.AppThemeColors
import com.tyranor.next.theme.DialogItemSurface
import com.tyranor.next.theme.ItemChevronColor
import com.tyranor.next.theme.NavWhite
import com.tyranor.next.theme.TextColor
import com.tyranor.next.theme.glassBorder
import com.tyranor.next.theme.AppComponentShape
import com.tyranor.next.ui.common.motion.pressSquish
import com.tyranor.next.ui.common.motion.PressSquishDefaults
import androidx.compose.animation.core.animateFloatAsState
import com.tyranor.next.ui.common.motion.BlurCrossfade
import com.tyranor.next.ui.common.motion.ExpandHorizontallyVisibility
import com.tyranor.next.ui.common.motion.ExpandVisibility
import com.tyranor.next.ui.common.motion.MotionMode
import com.tyranor.next.ui.common.motion.MotionTokens
import com.tyranor.next.ui.common.motion.rememberLastNonNull

/**
 * 功能跳转条目统一组件：左侧图标 + 标题/摘要 + 右侧指示箭头。
 *
 * 统一规范（详见 AGENTS.md「功能跳转条目统一规范」）：
 * - 所有「功能跳转列」（点击后进入/跳转/打开下一级的条目，如封面来源列表、群聊/频道项等）
 *   必须使用本组件，禁止用手写 Row/Column 拼装、禁止混用 Material 的 ListItem 等。
 * - 排版：圆角统一用 [com.tyranor.next.theme.AppComponentShape]（取「应用圆角调整」值）+ 背景 [containerColor]（默认 [NavWhite]）+ 内边距（纵向 12dp / 横向 16dp）+ 左侧图标 24dp + 右侧箭头，
 *   标题用 [TextColor]、摘要用半透明辅助色；进入跳转的 icon 一律 `BoldChevronRight`（加粗）。
 * - 背景色约定：页面上条目默认 [NavWhite]（页面背景 PageGrey 灰底白卡）；弹窗内条目传 [DialogItemSurface]，
 *   与弹窗背景形成层次反差（AGENTS.md 界面规范，勿回退 PageGrey——玻璃外观下 PageGrey 透明会丢失卡片层次）。
 * - 容器色约定：背景引用 `theme/Color.kt` 常量，前景取 [TextColor]，
 *   不依赖 `colorScheme.surface*`，保证弹窗作用域内外一致。
 *
 * @param title 条目标题
 * @param onClick 点击回调；传 null 表示不可用（灰色显示且不可点击）
 * @param modifier 外部修饰
 * @param summary 可选摘要
 * @param leadingIcon 左侧图标 drawable；null 时使用默认占位图标 [DEFAULT_LEADING_ICON]
 * @param showLeadingIcon 是否展示左侧图标；纯动作条目（如存档导出/导入/删除）可传 false 隐藏图标位
 * @param containerColor 条目容器背景色；默认 [NavWhite]（页面灰底上的白卡），弹窗内应传 [DialogItemSurface]
 * @param showArrow 是否显示右侧跳转箭头；「进入下一级」的跳转条目保持 true，「执行动作」条目传 false
 * @param leadingIconTint 显式覆盖左侧图标颜色（如主题色 [MaterialTheme.colorScheme.primary] / 危险色 error）；
 *   null 时保持默认行为（深色染白、浅色用原图）
 * @param titleMaxLines 标题最大行数；长预设名可允许换行，默认保持单行
 * @param titleColor 显式覆盖标题颜色（如危险条目用 error 色）；null 时用默认 [TextColor]
 * @param drawSurface 是否绘制条目自身的圆角背景与描边；位于已有卡片表面的子条目可传 false
 */
@Composable
fun AppNavItem(
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    /** 行尾内联文本（与标题同行、右对齐单行）；与 [summary] 独立并存，两者可同时渲染。 */
    trailingText: String? = null,
    @DrawableRes leadingIcon: Int? = null,
    showLeadingIcon: Boolean = true,
    containerColor: Color = NavWhite,
    verticalPadding: Dp = 12.dp,
    showArrow: Boolean = true,
    leadingIconTint: Color? = null,
    titleColor: Color? = null,
    titleMaxLines: Int = 1,
    indication: Indication? = LocalIndication.current,
    drawSurface: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    val enabled = onClick != null
    val targetContentAlpha = if (enabled) 1f else 0.4f
    // 可用 ↔ 不可用：「增强」档内容透明度弹簧过渡（标题 / 图标 / 箭头一起淡入淡出），「普通」档直接切换
    val contentAlpha = if (MotionMode.enhanced) {
        animateFloatAsState(targetContentAlpha, MotionTokens.fadeIn(0.001f), label = "navItemAlpha").value
    } else {
        targetContentAlpha
    }
    // 摘要 / 尾注退场期间仍显示最后的内容（而不是收起一个空框）
    val shownSummary = rememberLastNonNull(summary?.takeIf { it.isNotBlank() })
    val shownTrailing = rememberLastNonNull(trailingText?.takeIf { it.isNotBlank() })
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .fillMaxWidth()
            // Q 弹按压：有底卡时整卡形变，卡内平面条目缩得更少
            .pressSquish(
                enabled = enabled,
                scale = if (drawSurface) PressSquishDefaults.CardScale else PressSquishDefaults.RowScale,
                refuse = true,
            )
            .clip(AppComponentShape)
            .clickable(
                interactionSource = interactionSource,
                indication = indication,
                enabled = enabled,
                onClick = { onClick?.invoke() },
            )
            .let { rowModifier ->
                if (drawSurface) rowModifier.background(containerColor).glassBorder() else rowModifier
            }
            .padding(horizontal = 16.dp, vertical = verticalPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showLeadingIcon) {
            Image(
                painter = painterResource(leadingIcon ?: DEFAULT_LEADING_ICON),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                // 显式 tint 优先；否则深色模式下染白，保持低亮度背景上的可读性
                colorFilter = when {
                    leadingIconTint != null -> ColorFilter.tint(leadingIconTint.copy(alpha = contentAlpha))
                    AppThemeColors.isDark -> ColorFilter.tint(Color.White.copy(alpha = contentAlpha))
                    else -> null
                },
            )
        }
        Column(
            modifier = Modifier.weight(1f).padding(start = if (showLeadingIcon) 12.dp else 0.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = (titleColor ?: TextColor).copy(alpha = contentAlpha),
                maxLines = titleMaxLines,
                overflow = TextOverflow.Ellipsis,
            )
            // 摘要出现 / 消失：高度弹簧展开、模糊清晰；文案变化：模糊替换（「普通」档均为瞬时）；固定两行截断
            ExpandVisibility(visible = !summary.isNullOrBlank(), label = "navItemSummary") {
                BlurCrossfade(targetState = shownSummary.orEmpty(), label = "navItemSummaryText") { text ->
                    Text(
                        text,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextColor.copy(alpha = 0.55f * contentAlpha),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        ExpandHorizontallyVisibility(
            visible = !trailingText.isNullOrBlank(),
            modifier = Modifier.layout { measurable, constraints ->
                // Row 中先测非 weighted 子项，限制尾注最大占宽以免挤压左侧标题 Column 到零宽
                val cappedMaxWidth = if (constraints.hasBoundedWidth) {
                    (constraints.maxWidth * 0.5f).toInt().coerceAtLeast(1)
                } else {
                    constraints.maxWidth
                }
                val placeable = measurable.measure(
                    constraints.copy(
                        minWidth = constraints.minWidth.coerceAtMost(cappedMaxWidth),
                        maxWidth = cappedMaxWidth,
                    ),
                )
                layout(placeable.width, placeable.height) {
                    placeable.placeRelative(0, 0)
                }
            },
            label = "navItemTrailing",
        ) {
            BlurCrossfade(
                targetState = shownTrailing.orEmpty(),
                modifier = Modifier.padding(start = 8.dp),
                label = "navItemTrailingText",
            ) { text ->
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextColor.copy(alpha = 0.55f * contentAlpha),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (showArrow) {
            BoldChevronRight(
                color = ItemChevronColor.copy(alpha = ItemChevronColor.alpha * contentAlpha),
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
}

/** 功能条目的默认占位图标：调用方未提供 [AppNavItem.leadingIcon] 时使用。 */
private val DEFAULT_LEADING_ICON: Int = R.drawable.ic_engine_item
