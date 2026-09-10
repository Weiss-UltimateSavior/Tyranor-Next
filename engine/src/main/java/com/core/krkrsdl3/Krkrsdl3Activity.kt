package com.core.krkrsdl3

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager
import com.core.engine.EngineThemeColors
import com.core.engine.LaunchContract
import org.libsdl3.app.SDLActivity
import org.tvp.krkrsdl3.KRKRActivity
import java.util.Locale

/**
 * krkrsdl3 引擎宿主（Rinne 集成独立入口）。
 *
 * 与 Kirikiroid2 引擎体系（KR2Activity + krkr_bridge hook）完全解耦：
 * - 复用 [KRKRActivity] 的原生库加载（SDL3 + krkrsdl3）、argv（"gameargs" extra）与
 *   符号名绑定的 JNI（`Java_org_tvp_krkrsdl3_KRKRActivity_setNativeAssetManager`），
 *   因此本类必须继承 KRKRActivity，不能把该 native 方法声明到别的类。
 * - 主题色 / 深色模式 / 语言 tag 均从 Intent extra 读取（LauncherUiBridge.appendEngineThemeExtras 写入），
 *   不依赖 app 模块资源，与 TyranoActivity 的跨模块取色先例一致。
 */
class Krkrsdl3Activity : KRKRActivity() {

    /**
     * 启动加载期方向锁（与 KirikiroidLauncherBaseActivity 同款防护）：
     * sensorLandscape 允许 180° 翻转，用户在引擎加载 XP3/TJS 期间快速反转屏幕会触发
     * surface 销毁/重建与 native 初始化竞态导致闪退。进入游戏前冻结为固定横屏方向，
     * 原生游戏窗口创建（[setOrientationBis]）+ 缓冲后解除，恢复用户请求的传感器方向。
     */
    private var launchOrientationGuardEnabled = true
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        // 先落方向锁再走 super：super 链中 KRKRActivity.onCreate → fullscreen() 会设置方向
        applyEngineRequestedOrientation()
        applyLauncherWindowTone()
        super.onCreate(savedInstanceState)
        // 绝对超时兜底：即使原生窗口创建回调异常缺失，超时后也解除方向锁
        scheduleGuardRelease(GUARD_FALLBACK_RELEASE_MS)
    }

    override fun onResume() {
        super.onResume()
        // 覆盖 KRKRActivity 固定的竖屏强制：按启动器传入的方向（默认 sensorLandscape=6），
        // 与 KirikiroidLauncherBaseActivity.onResume 行为一致。
        applyEngineRequestedOrientation()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 旋转/重配置后系统可能清除沉浸全屏（状态栏/挖孔露出白底），重新断言横屏 + 沉浸。
        applyEngineRequestedOrientation()
        applySystemUiVisibility()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        val forceFocus = intent?.getStringExtra(LaunchContract.FOCUS)?.toBoolean() ?: false
        super.onWindowFocusChanged(hasFocus || forceFocus)
        if (hasFocus || forceFocus) applySystemUiVisibility()
    }

    /**
     * 拦截 SDL 原生方向回调（[SDLActivity.setOrientation] JNI → setOrientationBis）。
     *
     * SDL 原生层在 **SDL 线程**回调本方法（Android_CreateWindow → Android_JNI_SetOrientation）：
     * 默认无 `SDL_HINT_ORIENTATIONS` hint 时原生按窗口 w/h 推断方向，而初始 surface 为设备竖屏
     * 尺寸（h > w）→ 会把窗口改回竖屏。仅靠 onResume/onConfigurationChanged 重新断言横屏无法对抗
     * 这种晚到的回调，且方向被改回竖屏时系统会清除沉浸，状态栏/挖孔露出白底。
     *
     * 因此直接在此覆盖：无论原生 hint 如何，一律强制为启动器传入方向（默认 sensorLandscape=6），
     * 并重新应用沉浸全屏标志。注意 `setRequestedOrientation` 为 binder 调用任意线程安全，但
     * `applySystemUiVisibility` 操作 View（requestLayout）必须回到 UI 线程，否则
     * ViewRootImpl 抛 CalledFromWrongThreadException 导致闪退。
     *
     * 该回调同时标志 native 游戏窗口已创建（事件循环开始泵事件）：加载最脆弱的 bootstrap
     * 阶段已过，留一小段缓冲等待 TVP 首帧后解除方向锁。
     */
    override fun setOrientationBis(w: Int, h: Int, resizable: Boolean, hint: String?) {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            applyEngineRequestedOrientation()
            applySystemUiVisibility()
            scheduleGuardRelease(GUARD_RELEASE_GRACE_MS)
        }
    }

    /**
     * 覆盖 [KRKRActivity.fullscreen]（在 onCreate/onResume/onWindowFocusChanged 的 super 链中触发）：
     * 强制横屏 + 沉浸全屏，使状态栏/药丸挖孔区域由游戏画面直接遮盖。
     */
    override fun fullscreen() {
        try {
            supportActionBar?.hide()
        } catch (ignored: Exception) {
            // ActionBar 不存在可安全忽略
        }
        applyEngineRequestedOrientation()
        applySystemUiVisibility()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    /** 统一方向入口：加载期走固定横屏，进入游戏后恢复启动器请求方向（默认 sensorLandscape）。 */
    private fun applyEngineRequestedOrientation() {
        try {
            setRequestedOrientation(currentEngineRequestedOrientation())
        } catch (t: Throwable) {
            // 个别 ROM 对非全屏窗口调用会抛 IllegalStateException；保持当前方向即可
            Log.w(TAG, "apply orientation failed", t)
        }
    }

    private fun currentEngineRequestedOrientation(): Int {
        val requested = intent?.getIntExtra(LaunchContract.ORIENTATION, 6) ?: 6
        if (!launchOrientationGuardEnabled) return requested
        // 加载期冻结 180° 传感器翻转：固定到请求方向对应的单一横屏
        return if (requested == ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE) {
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
    }

    /** 延迟解除方向锁（先到先得；已解除或宿主销毁时不动作）。 */
    private fun scheduleGuardRelease(delayMs: Long) {
        if (!launchOrientationGuardEnabled || isFinishing || isDestroyed) return
        mainHandler.postDelayed({
            if (launchOrientationGuardEnabled && !isFinishing && !isDestroyed) {
                launchOrientationGuardEnabled = false
                applyEngineRequestedOrientation()
                Log.i(TAG, "release launch orientation guard after ${delayMs}ms")
            }
        }, delayMs)
    }

    companion object {
        private const val TAG = "Krkrsdl3Activity"

        /** 原生窗口创建后的缓冲：等待 TVP 完成首帧渲染再恢复传感器方向。 */
        private const val GUARD_RELEASE_GRACE_MS = 2_000L

        /** 绝对超时兜底（与 KirikiroidLauncherBaseActivity.SAFE_FALLBACK_REVEAL_MS 对齐）。 */
        private const val GUARD_FALLBACK_RELEASE_MS = 20_000L
    }

    /**
     * 拦截 SDL 原生窗口样式命令（[org.libsdl3.app.SDLActivity.setWindowStyle] JNI → sendCommand）。
     *
     * krkrsdl3 原生创建窗口时不带 `SDL_WINDOW_FULLSCREEN`，SDL 会回调 `COMMAND_CHANGE_WINDOW_STYLE=0`，
     * 其处理分支会置 `SYSTEM_UI_FLAG_VISIBLE` + `FLAG_FORCE_NOT_FULLSCREEN`（API 26+ 优先于
     * `FLAG_FULLSCREEN`）并清除全屏标志，导致状态栏/挖孔区域恢复白底、沉浸失效。
     *
     * 引擎壳必须全屏遮盖：把该命令参数恒改为 1（全屏分支会重设沉浸 flags + `FLAG_FULLSCREEN` +
     * 清除 `FLAG_FORCE_NOT_FULLSCREEN` + 挖孔 ALWAYS）。该方法在 UI 线程消息循环中被处理，
     * 不涉及跨线程 View 操作。
     */
    override fun sendCommand(command: Int, data: Any?): Boolean {
        if (command == SDLActivity.COMMAND_CHANGE_WINDOW_STYLE && (data as? Int) == 0) {
            return super.sendCommand(command, 1)
        }
        return super.sendCommand(command, data)
    }

    private fun applyLauncherWindowTone() {
        // 主题色板单一来源：窗口/系统栏底色统一走 EngineThemeColors.background，避免散落硬编码色值
        val themeBackground = EngineThemeColors.fromIntent(intent).background
        // 窗口级全屏标志：即使 SDL 原生视图标志被重置，WindowManager 层仍保持全屏遮盖。
        window.addFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS
        )
        // 系统栏底色固定黑色 + 深色对比度关闭：任何短暂露出系统栏的场景都不是白底。
        window.statusBarColor = themeBackground
        window.navigationBarColor = themeBackground
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        // 药丸/刘海：R+ 内容绘制进所有挖孔区域（ALWAYS）；P~R 回退短边挖孔（SHORT_EDGES）。
        val cutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        } else {
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
        }
        window.attributes = window.attributes.apply { layoutInDisplayCutoutMode = cutoutMode }
        // SurfaceView 未覆盖的过渡区域（状态栏/挖孔）一律黑底，杜绝白底。
        window.decorView.setBackgroundColor(themeBackground)
        applySystemUiVisibility()
    }

    private fun applySystemUiVisibility() {
        // 全屏沉浸：内容绘制到状态栏/导航栏后面，系统栏隐藏且可滑动临时唤出（IMMERSIVE_STICKY）。
        var flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        if (!isLauncherDarkMode()) {
            flags = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
        window.decorView.systemUiVisibility = flags
    }

    private fun isLauncherDarkMode(): Boolean = intent?.getBooleanExtra(LaunchContract.DARK_MODE, false) ?: false

    override fun attachBaseContext(newBase: Context) {
        // 引擎壳层与启动器显示语言解耦：KRKRActivity 原生 shell 仅含 zh-CN 场景资源，
        // 固定 zh-CN 防止启动器切 EN/JA 时引擎原生界面缺失文件选择器。
        Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
        val configuration = Configuration(newBase.resources.configuration)
        configuration.setLocale(Locale.SIMPLIFIED_CHINESE)
        super.attachBaseContext(newBase.createConfigurationContext(configuration))
    }
}
