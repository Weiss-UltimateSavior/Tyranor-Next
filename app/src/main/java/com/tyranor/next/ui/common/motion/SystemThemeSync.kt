package com.tyranor.next.ui.common.motion

import android.app.Activity
import android.content.res.Configuration
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.tyranor.next.core.settings.AppSettingsStore
import com.tyranor.next.theme.AppThemeColors

/**
 * 系统深浅色变化（下拉通知栏切换、定时切换）引起的自动换皮：页面在清单里声明处理 `uiMode` 而不再被销毁重建，
 * 在 `onConfigurationChanged` 里调用本类：
 *
 * 1. 旧画面此时还在屏幕上（下一帧才会按新主题绘制）：立刻让主题保持旧值（[AppThemeColors.systemDarkHold]）；
 * 2. 截下旧画面，再释放保持并应用新主题，播放与主动切换同款的揭开过渡（起点为屏幕顶部中央）。
 *
 * 只在「跟随系统」且不是固定配色的外观（复古 / 白雾玻璃不随系统变化）时才有变化；不在前台的页面不播放过渡。
 * 每个 Activity 持有一个实例（在 `onCreate` 里创建，以记录创建时的深浅色）。
 */
class SystemThemeSync(private val activity: Activity) {
    private var night = activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK

    fun onConfigurationChanged(newConfig: Configuration) {
        val now = newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK
        if (now == night) return
        night = now
        if (AppSettingsStore.getThemeMode(activity) != AppSettingsStore.THEME_MODE_SYSTEM) return
        if (AppThemeColors.appearanceStyle.hasFixedContentPalette) return
        val resumed = (activity as? LifecycleOwner)?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) == true
        if (!resumed) {
            // 不在前台（返回栈里的页面）：直接应用，看不见，也不用截屏
            AppThemeColors.refresh(activity)
            return
        }
        AppThemeColors.systemDarkHold = AppThemeColors.isDark
        runThemeReveal(activity) {
            AppThemeColors.systemDarkHold = null
            AppThemeColors.refresh(activity)
        }
    }
}
