package com.tyranor.next.ui.common

import android.content.res.Configuration
import androidx.compose.foundation.LocalIndication
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
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tyranor.next.core.privacy.AppLockController
import com.tyranor.next.theme.AppSheetTopShape
import com.tyranor.next.ui.common.privacy.DismissOnAppLockEffect
import com.tyranor.next.theme.AppThemeColors
import com.tyranor.next.theme.GlassPanelSolid
import com.tyranor.next.theme.MilkGlassPanelBackground
import com.tyranor.next.theme.PageGrey
import com.tyranor.next.theme.SheetScrimColor
import com.tyranor.next.theme.TextColor
import com.tyranor.next.ui.common.motion.BindBackdrop
import com.tyranor.next.ui.common.motion.BindWindowScrim
import com.tyranor.next.ui.common.motion.LocalSheetBackdrop
import com.tyranor.next.ui.common.motion.MotionSheetController
import com.tyranor.next.ui.common.motion.MotionSheetEffects
import com.tyranor.next.ui.common.motion.ProvideEntrance
import com.tyranor.next.ui.common.motion.motionSheetDrag
import com.tyranor.next.ui.common.motion.motionSheetSurface
import com.tyranor.next.ui.common.motion.nativeScrim
import com.tyranor.next.ui.common.motion.rememberMotionSheetController
import com.tyranor.next.ui.common.motion.rememberMotionSheetState
import com.tyranor.next.ui.common.motion.skipEntranceOnDrag
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter

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
 *   剩余手势/惯性会转交抽屉，极易误关（issue #27）。普通档走遮罩或返回键；
 *   增强档由统一控制器处理跟手下拉，且所有关闭请求均先经过业务守卫。
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
    controller: MotionSheetController = rememberMotionSheetController(),
    /**
     * 应用锁生效时的强制清理路径；默认走 [onDismissRequest]。
     * 调用方若在 onDismissRequest 里有「运行中只能重建」之类的守卫，必须传入真正清状态的路径，
     * 否则窗口虽然被移除，解锁后调用方状态仍会让抽屉原地复活。
     */
    onLockDismiss: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    // 抽屉是独立窗口，层级在解锁门之上：应用锁生效时立即把窗口从组合里移除（不播退场动画），
    // 只让 effect 通知调用方清理业务状态——只调 onDismissRequest 时调用方的退场动画仍可能
    // 把窗口留在锁门上方数百毫秒。
    val locked by AppLockController.lockedState.collectSettingAsState()
    DismissOnAppLockEffect(onLockDismiss ?: onDismissRequest)
    if (locked) return
    val sheetState = rememberMotionSheetState(controller, confirmValueChange)
    LocalSheetBackdrop.current?.let { controller.BindBackdrop(it) }
    ModalBottomSheet(
        onDismissRequest = {
            if (controller.canClose()) {
                if (controller.enhanced) controller.close() else onDismissRequest()
            }
        },
        modifier = Modifier.motionSheetSurface(controller, SheetScrimColor),
        sheetState = sheetState,
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = !controller.enhanced),
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
        scrimColor = controller.nativeScrim(SheetScrimColor),
        contentWindowInsets = { WindowInsets(0.dp) },
        // 顶部圆角与组件统一圆角保持一致
        shape = AppSheetTopShape,
        dragHandle = null,
    ) {
        controller.BindWindowScrim(SheetScrimColor)
        // 常规窗口保留页面露出；短窗优先给内容留空间，但上限始终不超过可用视口。
        val configuration = LocalConfiguration.current
        val sheetMaxHeight = appBottomSheetMaxHeight(
            screenHeightDp = configuration.screenHeightDp,
            isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE,
        )
        MotionSheetEffects(controller, onDismissRequest)
        // Material3 只在没有动画持有拖动锁时才把面板吸到新的展开位：内容在展开途中变高 / 变矮（例如游戏抽屉里
        // 异步多出「确认已退出外部游戏」一行）时，面板停在按旧高度算出的位置，变高的那一截连同底部固定的启动按钮
        // 被推到屏幕外，之后也不会自己回来。内容高度一变（首次测量之后）就重新对齐到展开位。
        var contentHeight by remember { mutableIntStateOf(0) }
        LaunchedEffect(sheetState) {
            snapshotFlow { contentHeight }
                .filter { it > 0 }
                .drop(1)
                .collectLatest {
                    if (sheetState.currentValue == SheetValue.Expanded &&
                        sheetState.targetValue == SheetValue.Expanded &&
                        !controller.closing
                    ) {
                        sheetState.expand()
                    }
                }
        }
        ProvideEntrance {
            Box(
                Modifier
                    .fillMaxWidth()
                    .onSizeChanged { contentHeight = it.height }
                    .skipEntranceOnDrag()
                    .motionSheetDrag(controller)
                    .clip(AppSheetTopShape),
            ) {
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
                    CompositionLocalProvider(
                        LocalAppSheetContent provides true,
                        LocalIndication provides NoIndication,
                    ) { content() }
                }
            }
        }
    }
}

/** Shared overall height policy for every app bottom sheet. Values are in dp, independent of density. */
internal fun appBottomSheetMaxHeight(screenHeightDp: Int, isLandscape: Boolean): Dp {
    val screenHeight = screenHeightDp.dp
    val marginLimit = screenHeight - if (isLandscape) 96.dp else 120.dp
    val proportionalLimit = screenHeight * if (isLandscape) 0.86f else 0.75f
    val preferredLimit = minOf(marginLimit, proportionalLimit, 560.dp)
    // 仅放宽最大高度，不强制内容撑满。短窗不能再固定扣掉 96/120dp，挤没底部操作。
    val shortWindowLimit = minOf(320.dp, screenHeight - 24.dp)
    return maxOf(preferredLimit, shortWindowLimit).coerceIn(0.dp, screenHeight.coerceAtLeast(0.dp))
}
