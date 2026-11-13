package com.tyranor.next.ui.common

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tyranor.next.theme.ItemChevronColor
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
