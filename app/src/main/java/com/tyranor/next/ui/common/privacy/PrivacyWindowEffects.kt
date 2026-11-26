package com.tyranor.next.ui.common.privacy

import android.app.Activity
import android.os.Build
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import com.tyranor.next.core.privacy.AppLockController
import com.tyranor.next.core.settings.AppSettingsStore
import com.tyranor.next.ui.common.collectSettingAsState

/**
 * 窗口级隐私效果（主界面与全部二级页各挂一次）：安全屏幕（FLAG_SECURE）。
 *
 * 开关开启或应用锁生效（解锁门含图案）时给宿主窗口设置 FLAG_SECURE：最近任务卡片、
 * 切换器抓帧、系统截屏全部由系统置空 / 拦截。开关关闭且未上锁时清除。
 *
 * 独立窗口（Dialog / BottomSheet / 侧栏 / 下拉浮层）不会继承宿主 Activity 的窗口标志，
 * 由各自宿主另行同步：Dialog 系经 `syncDialogWindow` 内的 [SecureWindowEffect]，
 * Miuix `WindowListPopup` 经 [SecureContainingDialogWindowEffect]。
 * API 33+ 的 `setRecentsScreenshotEnabled` 只存在于 Activity，任务快照口径由本 effect 统一处理。
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

/**
 * 给一个独立窗口同步 `FLAG_SECURE`（与 Activity 窗口同一判定：安全屏幕开关或应用锁生效）。
 *
 * 由 `syncDialogWindow` 在取得 Dialog 平台窗口后调用，覆盖统一弹窗、侧栏、底部抽屉与
 * 游戏浮层；Miuix `WindowListPopup` 这类不经 `syncDialogWindow` 的 Dialog 用
 * [SecureContainingDialogWindowEffect]。调用方传入的窗口生命周期由宿主管理；
 * 窗口移除时无需清标志（平台会随窗口销毁），开关变化时 [DisposableEffect] 会重新同步。
 */
@Composable
fun SecureWindowEffect(window: Window?) {
    val preventScreenshot by AppSettingsStore.privacyPreventScreenshotState.collectSettingAsState()
    val locked by AppLockController.lockedState.collectSettingAsState()
    val secure = preventScreenshot || locked
    DisposableEffect(window, secure) {
        val target = window ?: return@DisposableEffect onDispose { }
        if (secure) {
            target.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            target.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        onDispose { }
    }
}

/**
 * 在独立 Dialog 内容的 Compose 根里调用：取得当前内容所在的 Dialog 平台窗口并同步
 * `FLAG_SECURE`。非 Dialog 宿主（根内 overlay / 普通页面）取不到窗口时为空操作。
 */
@Composable
fun SecureContainingDialogWindowEffect() {
    val view = LocalView.current
    val window = (view.parent as? DialogWindowProvider)?.window
    SecureWindowEffect(window)
}

/**
 * 应用锁生效期间拦截键盘 / 手柄按键：无障碍屏蔽（`clearAndSetSemantics`）只挡 TalkBack，
 * 已聚焦的控件仍可能被 Enter 等按键激活。
 *
 * 只拦「会操作页面」的键；音量、媒体、亮度、电源、耳机等系统键原样放行——它们不由应用
 * 页面消费，播放器 / 音量面板在锁定时仍需可用。Back 也放行，交给锁门的 `BackHandler`
 * 退到后台并保持锁定。
 */
internal fun Modifier.blockAppInteractionKeys(locked: Boolean): Modifier =
    if (!locked) {
        this
    } else {
        onPreviewKeyEvent { event ->
            event.key != Key.Back && event.key !in AppLockPassThroughKeys
        }
    }

/** 不由应用页面消费、锁定时应继续可用的系统 / 媒体键。 */
private val AppLockPassThroughKeys: Set<Key> = setOf(
    Key.VolumeUp,
    Key.VolumeDown,
    Key.VolumeMute,
    Key.MediaPlay,
    Key.MediaPause,
    Key.MediaPlayPause,
    Key.MediaStop,
    Key.MediaRecord,
    Key.MediaNext,
    Key.MediaPrevious,
    Key.MediaRewind,
    Key.MediaFastForward,
    Key.MediaClose,
    Key.MediaAudioTrack,
    Key.MediaEject,
    Key.MediaTopMenu,
    Key.MediaSkipForward,
    Key.MediaSkipBackward,
    Key.MediaStepForward,
    Key.MediaStepBackward,
    Key.BrightnessUp,
    Key.BrightnessDown,
    Key.Camera,
    Key.Power,
    Key.Sleep,
    Key.SoftSleep,
    Key.HeadsetHook,
)
