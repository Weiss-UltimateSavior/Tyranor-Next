package com.tyranor.next.ui.common

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tyranor.next.theme.ItemChevronColor
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentColors
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import com.tyranor.next.ui.common.motion.pressSquish
import com.tyranor.next.ui.common.motion.PressSquishDefaults

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
) {
    BasicComponent(
        // Q 弹按压（整行轻微形变）；不可点击时不加
        modifier = modifier.pressSquish(
            // 带底部控件（如滑条行）时不形变，避免拖动滑条时坐标随图层偏移
            enabled = enabled && onClick != null && bottomAction == null,
            scale = PressSquishDefaults.RowScale,
        ),
        insideMargin = insideMargin,
        title = title,
        titleColor = titleColor,
        summary = summary,
        summaryColor = summaryColor,
        startAction = startAction,
        endActions = {
            Row(
                modifier = Modifier
                    .padding(end = 8.dp)
                    .align(Alignment.CenterVertically)
                    .weight(1f, fill = false),
            ) {
                endActions()
            }
            BoldChevronRight(
                color = if (enabled) {
                    ItemChevronColor
                } else {
                    ItemChevronColor.copy(alpha = ItemChevronColor.alpha * 0.4f)
                },
                modifier = Modifier.align(Alignment.CenterVertically),
            )
        },
        bottomAction = bottomAction,
        onClick = onClick,
        holdDownState = holdDownState,
        enabled = enabled,
    )
}
