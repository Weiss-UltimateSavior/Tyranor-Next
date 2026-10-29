package com.tyranor.next.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tyranor.next.theme.AdvancedGlassBorder
import com.tyranor.next.theme.AppSheetTopShape
import com.tyranor.next.theme.AppThemeColors
import com.tyranor.next.theme.GlassBorder
import com.tyranor.next.theme.GlassPanelSolid
import com.tyranor.next.theme.rememberAdvancedGlassPanelSurface

/**
 * 底部抽屉内容区的最大高度。
 *
 * 小平板横屏下屏幕高度可能 < 560dp，硬编码会让抽屉填满屏幕、`SwipeableState` 无法区分
 * 滚动与收起，快速滑动时高速振荡（issue #27）。调用方把它传给内容区的 `heightIn(max = …)`。
 */
@Composable
fun appSheetMaxHeight(): Dp = with(LocalConfiguration.current) {
    (screenHeightDp - 120).dp.coerceIn(200.dp, 560.dp)
}

/**
 * 底部抽屉统一外壳：外观风格分派（面板色 / 遮罩 / 顶部圆角 / 玻璃描边）+ 关闭手势策略。
 *
 * 外观规则：
 * - 高级玻璃：面板透明，取色渐变画在内容层（[Modifier] 由内部施加在 `Column` 上）；
 * - 复古玻璃：面板用不透明 [GlassPanelSolid]，顶部补 0.5dp 描边；
 * - 默认：面板取 `colorScheme.background`。
 *
 * 关闭手势固定关闭（`sheetGesturesEnabled = false`）：抽屉内可滚动内容快速滑动时，剩余手势/
 * 惯性会转交抽屉导致误关（issue #27），关闭统一走遮罩/返回键。
 * 顶部把手同样不可点击：Material3 的 `dragHandle` 槽内部自带 clickable（Expanded 时点击即关闭），
 * 因此本组件不传该槽，把手自绘在内容首位。
 *
 * 内容区不自带滚动与内边距：调用方按需自行包 `LazyColumn` / `verticalScroll` 并
 * 用 [appSheetMaxHeight] 限制高度，避免「可滚容器套可滚容器」。
 * 面板本身延伸到屏幕底边（`contentWindowInsets` 归零），内容区底部由组件补
 * `navigationBarsPadding()`，调用方无需再处理导航栏间距。
 *
 * @param onDismissRequest 遮罩点击/返回键回调
 * @param content 抽屉内容（纵向 [ColumnScope]，已铺好玻璃渐变底）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    // 高级玻璃：面板渐变从页面背景取色（独立窗口采不到 backdrop，用跨窗口取色替代）
    val advancedPanelSurface = if (AppThemeColors.isAdvancedGlass) rememberAdvancedGlassPanelSurface() else null

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        sheetGesturesEnabled = false,
        containerColor = when {
            AppThemeColors.isAdvancedGlass -> Color.Transparent
            AppThemeColors.isGlass -> GlassPanelSolid
            else -> MaterialTheme.colorScheme.background
        },
        scrimColor = when {
            AppThemeColors.isAdvancedGlass -> Color.Black.copy(alpha = 0.6f)
            AppThemeColors.isGlass -> Color.Black.copy(alpha = 0.6f)
            else -> Color.Black.copy(alpha = 0.32f)
        },
        contentWindowInsets = { WindowInsets(0.dp) },
        shape = AppSheetTopShape,
        // 不用 dragHandle 槽：Material3 1.4 会给该槽套一层 clickable，Expanded 状态下点击把手
        // 直接 animateToDismiss()（ModalBottomSheet.kt 的 DragHandleWithTooltip 分支）。把手满宽，
        // 等于点抽屉顶部任意位置就关闭，与「只允许遮罩/返回键关闭」冲突。
        // 改为把把手自绘在内容首位：视觉不变，且不可点击。
        dragHandle = null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (advancedPanelSurface != null) {
                        Modifier.background(advancedPanelSurface.brush)
                    } else {
                        Modifier
                    },
                )
                // 面板本身延伸到屏幕底边（contentWindowInsets 归零），内容必须自己避开导航栏，
                // 否则最后一行会压在导航条下面（真机实测：游玩状态那一行被导航栏盖住 31px）。
                .navigationBarsPadding(),
        ) {
            // 自绘把手：玻璃描边只能画在抽屉真实顶边，放在内容首位才能与面板渐变对齐。
            // clearAndSetSemantics：M3 的 DragHandle 自带 contentDescription，而本抽屉手势已关闭、
            // 把手也没有点击动作，留着只会让 TalkBack 聚焦一个双击无反应的控件。
            Column(
                modifier = Modifier.fillMaxWidth().clearAndSetSemantics { },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (AppThemeColors.isGlass) {
                    Box(
                        Modifier.fillMaxWidth().height(0.5.dp).background(
                            if (AppThemeColors.isAdvancedGlass) AdvancedGlassBorder else GlassBorder,
                        ),
                    )
                }
                BottomSheetDefaults.DragHandle()
            }
            content()
        }
    }
}
