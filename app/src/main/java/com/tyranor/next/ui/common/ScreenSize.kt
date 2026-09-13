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
