package com.tyranor.next.ui.common

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration

/**
 * 宽屏判定：横屏或宽设备（`screenWidthDp` / `smallestScreenWidthDp` ≥ 600），
 * 用于大屏布局适配（游戏页 6 列网格、液态玻璃底栏拉伸等）。
 *
 * 抽到独立文件的原因：它同时被经典底栏与 `ui/common/glass/` 下的液态玻璃 · 透镜底栏使用，
 * 放在导航组件文件里会让「底栏实现」与「通用宽度判定」混在一起（同包内不构成编译期循环，
 * 但会让改动面耦合）；`ui.common` 与 `ui.common.glass` 之间真正未消除的反向依赖是导航项模型
 * `LiquidGlassNavItem`，见方案文档 §6 P5。
 */
@Composable
fun isWideScreen(): Boolean {
    val configuration = LocalConfiguration.current
    return configuration.orientation == Configuration.ORIENTATION_LANDSCAPE ||
        configuration.screenWidthDp >= 600 ||
        configuration.smallestScreenWidthDp >= 600
}

/**
 * 平板侧栏布局判定：以 **sw600dp 平板**（含竖屏）或宽度 ≥ 840dp 的大窗口为准，
 * 命中时主导航从底部移到侧边（见 `ui/common/AppNavigationRail.kt`）。
 *
 * 与 [isWideScreen] 的区别：后者含「任何横屏」——横屏手机只有 360–420dp 高，
 * 放 4 项竖排侧栏会过挤，因此侧栏单独用更严格的平板判定。
 */
@Composable
fun isSideRailLayout(): Boolean {
    val configuration = LocalConfiguration.current
    return configuration.smallestScreenWidthDp >= 600 ||
        configuration.screenWidthDp >= 840
}
