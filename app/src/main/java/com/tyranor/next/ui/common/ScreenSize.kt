package com.tyranor.next.ui.common

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration

/**
 * 宽屏判定：横屏或宽设备（`screenWidthDp` / `smallestScreenWidthDp` ≥ 600），
 * 用于大屏布局适配（游戏页 6 列网格、液态玻璃底栏拉伸等）。
 *
 * 放在中性的工具文件里：经典底栏与 `ui/common/glass/` 下的液态玻璃 · 透镜底栏都要用，
 * 若留在经典导航组件文件里会让两个包互相依赖。
 */
@Composable
fun isWideScreen(): Boolean {
    val configuration = LocalConfiguration.current
    return configuration.orientation == Configuration.ORIENTATION_LANDSCAPE ||
        configuration.screenWidthDp >= 600 ||
        configuration.smallestScreenWidthDp >= 600
}
