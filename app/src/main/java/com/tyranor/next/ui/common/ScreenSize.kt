package com.tyranor.next.ui.common

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalConfiguration
import com.tyranor.next.core.settings.AppSettingsStore

/**
 * 宽屏判定：横屏或宽设备（`screenWidthDp` / `smallestScreenWidthDp` ≥ 600），
 * 用于首页、游戏页和引擎页的宽屏布局适配。
 */
@Composable
fun isWideScreen(): Boolean {
    val configuration = LocalConfiguration.current
    return configuration.orientation == Configuration.ORIENTATION_LANDSCAPE ||
        configuration.screenWidthDp >= 600 ||
        configuration.smallestScreenWidthDp >= 600
}

/**
 * 平板/大窗口判定：以 **sw600dp 平板**（含竖屏）或宽高至少为 840×600dp 的大窗口为准。
 *
 * 与 [isWideScreen] 的区别：后者含「任何横屏」——横屏手机只有 360–420dp 高，
 * 即使宽度超过 840dp，也不能命中大窗口分支；放 4 项竖排侧栏会过挤。
 */
@Composable
fun isTabletScreen(): Boolean {
    val configuration = LocalConfiguration.current
    return configuration.smallestScreenWidthDp >= 600 ||
        (configuration.screenWidthDp >= 840 && configuration.screenHeightDp >= 600)
}

/**
 * 平板侧栏是否生效：设备命中 [isTabletScreen] **且**应用设置「平板侧边栏」开关开启
 * （`AppSettingsStore.sideRailState`，默认开）。
 *
 * 命中时主导航从底部移到侧边（见 `ui/common/AppNavigationRail.kt`）；开关关闭则平板也保持底部导航。
 * 开关是内存态 StateFlow，设置页切换后主界面即时重组。
 */
@Composable
fun isSideRailLayout(): Boolean {
    val enabled by AppSettingsStore.sideRailState.collectSettingAsState()
    return isTabletScreen() && enabled
}
