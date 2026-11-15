package com.tyranor.next.ui.common.privacy

import android.app.Activity
import android.os.Build
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import com.tyranor.next.core.privacy.AppLockController
import com.tyranor.next.core.settings.AppSettingsStore
import com.tyranor.next.ui.common.collectSettingAsState

/**
 * 窗口级隐私效果（主界面与全部二级页各挂一次）：安全屏幕（FLAG_SECURE）。
 *
 * 开关开启或应用锁生效（解锁门含图案）时给宿主窗口设置 FLAG_SECURE：最近任务卡片、
 * 切换器抓帧、系统截屏全部由系统置空 / 拦截。开关关闭且未上锁时清除。
 */
@Composable
fun FlagSecureEffect(activity: Activity) {
    val preventScreenshot by AppSettingsStore.privacyPreventScreenshotState.collectSettingAsState()
    val locked by AppLockController.lockedState.collectSettingAsState()
    val effective = preventScreenshot || locked
    DisposableEffect(effective) {
        if (effective) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            // Android 13+：同时声明「最近任务不截取本应用」，占位卡用启动窗口绘制
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                activity.setRecentsScreenshotEnabled(false)
            }
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                activity.setRecentsScreenshotEnabled(true)
            }
        }
        onDispose { }
    }
}
