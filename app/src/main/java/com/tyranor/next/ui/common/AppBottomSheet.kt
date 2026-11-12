package com.tyranor.next.ui.common

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import com.tyranor.next.theme.AppSheetTopShape
import com.tyranor.next.theme.AppThemeColors
import com.tyranor.next.theme.GlassPanelSolid
import com.tyranor.next.theme.MilkGlassPanelBackground
import com.tyranor.next.theme.PageGrey
import com.tyranor.next.theme.SheetScrimColor
import com.tyranor.next.theme.TextColor

internal val LocalAppSheetContent = staticCompositionLocalOf { false }

/**
 * 带固定操作区的底部抽屉外壳。
 *
 * 收敛面板色、遮罩、顶部圆角、玻璃顶边描边、把手、最大高度与导航栏内边距；
 * 调用方只提供内容，需要「内容滚动 + 底部固定按钮」时用 [ColumnScope] 的 `weight(1f, fill = false)`
 * 给滚动区，按钮放在其后即可。
 *
 * 两处刻意关掉 M3 默认行为：
 * - **关闭手势固定关闭**：M3 默认「位移 > 56dp 或速度 > 125dp/s」即关闭，用户快速滑动内容时
 *   剩余手势/惯性会转交抽屉，极易误关（issue #27）。关闭统一走遮罩或返回键。
 * - **把手不可点**：M3 会给 `dragHandle` 槽套 clickable，Expanded 状态下点按直接关闭，
 *   与上一条冲突；这里置空槽位、在内容首位自绘把手。
 *
 * @param onDismissRequest 遮罩或返回键触发的关闭回调
 * @param content 抽屉内容（Column 作用域）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppBottomSheet(
    onDismissRequest: () -> Unit,
    confirmValueChange: (SheetValue) -> Boolean = { true },
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = rememberModalBottomSheetState(
            skipPartiallyExpanded = true,
            confirmValueChange = confirmValueChange,
        ),
        // 与统一弹窗共用最大宽度，避免横屏/大屏拉伸过宽
        sheetMaxWidth = AppDialogMaxWidth,
        sheetGesturesEnabled = false,
        // 复古玻璃用不透明面板色；白雾玻璃面板本身透明，由下方 Box 叠底图 + 白纱成面
        containerColor = when {
            AppThemeColors.isGlass -> GlassPanelSolid
            AppThemeColors.isMilkGlass -> Color.Transparent
            else -> PageGrey
        },
        contentColor = TextColor,
        scrimColor = SheetScrimColor,
        contentWindowInsets = { WindowInsets(0.dp) },
        // 顶部圆角与组件统一圆角保持一致
        shape = AppSheetTopShape,
        dragHandle = null,
    ) {
        // 竖屏保留更多页面露出；横屏按高度比例给内容留空间。短屏不强制抬高到最小值，
        // 避免内容超过可用视口；滚动区会在剩余高度内滚动。
        val configuration = LocalConfiguration.current
        val sheetMaxHeight = appBottomSheetMaxHeight(
            screenHeightDp = configuration.screenHeightDp,
            isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE,
        )
        Box(Modifier.fillMaxWidth().clip(AppSheetTopShape)) {
            if (AppThemeColors.isMilkGlass) {
                MilkGlassPanelBackground(Modifier.matchParentSize())
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = sheetMaxHeight)
                    // 导航栏间距统一在这里加，调用方内容尾部不要再加一次
                    .navigationBarsPadding()
                    .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal)),
            ) {
                AppSheetDragHandle(showGlassTopBorder = true)
                CompositionLocalProvider(LocalAppSheetContent provides true) { content() }
            }
        }
    }
}

/** Shared overall height policy for every app bottom sheet. Values are in dp, independent of density. */
internal fun appBottomSheetMaxHeight(screenHeightDp: Int, isLandscape: Boolean): androidx.compose.ui.unit.Dp {
    val screenHeight = screenHeightDp.dp
    val marginLimit = screenHeight - if (isLandscape) 96.dp else 120.dp
    val proportionalLimit = screenHeight * if (isLandscape) 0.86f else 0.75f
    return minOf(marginLimit, proportionalLimit, 560.dp).coerceAtLeast(0.dp)
}
