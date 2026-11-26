package com.tyranor.next.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import com.tyranor.next.theme.BookSerif
import com.tyranor.next.theme.BookSerifBold
import com.tyranor.next.theme.CurrentBookPaperPalette
import com.tyranor.next.theme.ItemChevronColor
import com.tyranor.next.theme.LocalShelfBookLeaf
import com.tyranor.next.ui.common.motion.shelfBookMarker
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentColors
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.ui.text.font.FontWeight
import com.tyranor.next.ui.common.motion.withRowPressSquish
import androidx.compose.animation.animateColorAsState
import com.tyranor.next.ui.common.motion.BlurText
import com.tyranor.next.ui.common.motion.MotionMode
import com.tyranor.next.ui.common.motion.MotionTokens

/**
 * 与 Miuix `ArrowPreference` 行为一致的条目组件，仅把右侧箭头替换为统一加粗的 [BoldChevronRight]。
 * 参数与 Miuix 版本保持同名同义，便于各页面直接替换 import。
 */
@Composable
fun ArrowPreference(
    title: String,
    modifier: Modifier = Modifier,
    titleColor: BasicComponentColors = BasicComponentDefaults.titleColor(),
    summary: String? = null,
    summaryColor: BasicComponentColors = BasicComponentDefaults.summaryColor(),
    startAction: @Composable (() -> Unit)? = null,
    endActions: @Composable RowScope.() -> Unit = {},
    bottomAction: (@Composable () -> Unit)? = null,
    insideMargin: PaddingValues = BasicComponentDefaults.InsideMargin,
    onClick: (() -> Unit)? = null,
    holdDownState: Boolean = false,
    enabled: Boolean = true,
    /** 右侧当前值（可选）：变化时只替换真正变化的片段并原地模糊，见 [BlurText]；放在 [endActions] 之前。 */
    value: String? = null,
) {
    // Q 弹按压（整行轻微形变）；不可点击时不加
    // 带底部控件（如滑条行）时不形变，避免拖动滑条时坐标随图层偏移
    val rowModifier = modifier.withRowPressSquish(enabled = enabled && onClick != null && bottomAction == null)
    if (LocalShelfBookLeaf.current) {
        BookArrowPreference(
            title = title,
            modifier = rowModifier,
            summary = summary,
            startAction = startAction,
            endActions = endActions,
            bottomAction = bottomAction,
            onClick = onClick,
            enabled = enabled,
            value = value,
        )
        return
    }
    val endContent: @Composable RowScope.() -> Unit = {
            Row(
                modifier = Modifier
                    .padding(end = 8.dp)
                    .align(Alignment.CenterVertically)
                    .weight(1f, fill = false),
            ) {
                if (value != null) {
                    BlurText(
                        value,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        // 条目右侧的选项值整段模糊替换（数值读数用 SliderValueHeader，只换变化的数字）
                        partial = false,
                    )
                }
                endActions()
            }
            val chevronColor = if (enabled) {
                ItemChevronColor
            } else {
                ItemChevronColor.copy(alpha = ItemChevronColor.alpha * 0.4f)
            }
            BoldChevronRight(
                // 可用 ↔ 不可用：「增强」档箭头颜色弹簧淡变，「普通」档直接切换
                color = if (MotionMode.enhanced) {
                    animateColorAsState(chevronColor, MotionTokens.fadeIn(), label = "arrowPrefChevron").value
                } else {
                    chevronColor
                },
                modifier = Modifier.align(Alignment.CenterVertically),
            )
    }
    if (MotionMode.enhanced && summary != null) {
        // 「增强」档：摘要（目录数量 / 检查中… / 绑定数等状态文案）变化时原位模糊替换。
        // 用 Miuix 的槽位重载自己画标题与摘要——字号 / 字重 / 颜色取与 Miuix 字符串重载完全相同的值，静止外观不变
        BasicComponent(
            modifier = rowModifier,
            insideMargin = insideMargin,
            startAction = startAction,
            endActions = endContent,
            bottomAction = bottomAction,
            onClick = onClick,
            holdDownState = holdDownState,
            enabled = enabled,
        ) {
            Text(
                text = title,
                fontSize = MiuixTheme.textStyles.headline1.fontSize,
                fontWeight = FontWeight.Medium,
                color = if (enabled) titleColor.color else titleColor.disabledColor,
            )
            BlurText(
                text = summary,
                style = MiuixTheme.textStyles.main.copy(fontSize = MiuixTheme.textStyles.body2.fontSize),
                color = if (enabled) summaryColor.color else summaryColor.disabledColor,
                partial = false,
            )
        }
    } else {
        BasicComponent(
            modifier = rowModifier,
            insideMargin = insideMargin,
            title = title,
            titleColor = titleColor,
            summary = summary,
            summaryColor = summaryColor,
            startAction = startAction,
            endActions = endContent,
            bottomAction = bottomAction,
            onClick = onClick,
            holdDownState = holdDownState,
            enabled = enabled,
        )
    }
}

@Composable
private fun BookArrowPreference(
    title: String,
    modifier: Modifier,
    summary: String?,
    startAction: @Composable (() -> Unit)?,
    endActions: @Composable RowScope.() -> Unit,
    bottomAction: (@Composable () -> Unit)?,
    onClick: (() -> Unit)?,
    enabled: Boolean,
    value: String?,
) {
    val palette = CurrentBookPaperPalette
    val interaction = remember { MutableInteractionSource() }
    val chevronTarget = if (enabled) palette.inkSecondary else palette.inkMuted
    val chevronColor = if (MotionMode.enhanced) {
        animateColorAsState(chevronTarget, MotionTokens.fadeIn(), label = "bookArrowPrefChevron").value
    } else {
        chevronTarget
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .shelfBookMarker(interaction, palette.marker)
            .drawBehind {
                drawLine(
                    color = palette.ruleSubtle,
                    start = Offset(0f, size.height),
                    end = Offset(size.width, size.height),
                    strokeWidth = 0.6.dp.toPx(),
                )
            }
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = interaction,
                        indication = null,
                        enabled = enabled,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 4.dp, vertical = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (startAction != null) {
                Box(Modifier.padding(end = 10.dp), contentAlignment = Alignment.Center) { startAction() }
            }
            Column(Modifier.weight(1f)) {
                androidx.compose.material3.Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontFamily = BookSerifBold,
                        fontWeight = FontWeight.Medium,
                    ),
                    color = if (enabled) palette.ink else palette.inkMuted,
                )
                if (summary != null) {
                    if (MotionMode.enhanced) {
                        BlurText(
                            text = summary,
                            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = BookSerif),
                            color = if (enabled) palette.inkSecondary else palette.inkMuted,
                            partial = false,
                        )
                    } else {
                        androidx.compose.material3.Text(
                            text = summary,
                            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = BookSerif),
                            color = if (enabled) palette.inkSecondary else palette.inkMuted,
                        )
                    }
                }
            }
            Row(
                modifier = Modifier
                    .padding(start = 8.dp, end = 8.dp)
                    .weight(1f, fill = false),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (value != null) {
                    val underlineColor = if (enabled) palette.foilAccent.copy(alpha = 0.72f) else palette.ruleSubtle
                    BlurText(
                        value,
                        modifier = Modifier.drawBehind {
                            val y = size.height - 1.dp.toPx()
                            drawLine(underlineColor, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                        },
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = BookSerif),
                        color = if (enabled) palette.ink else palette.inkMuted,
                        partial = false,
                    )
                }
                endActions()
            }
            Spacer(Modifier.width(2.dp))
            BookInkChevron(color = chevronColor)
        }
        if (bottomAction != null) {
            Box(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                bottomAction()
            }
        }
    }
}
