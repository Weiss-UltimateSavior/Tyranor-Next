package com.core.krkrsdl3

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.AssetManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import com.core.engine.EngineThemeColors
import com.core.engine.EngineSurfaceStartupController
import com.core.engine.LaunchContract
import com.core.engine.R
import com.core.gl.ArtemisPerformanceOverlay
import com.core.nativeplugin.NativeRuntimePack
import org.libsdl3.app.SDLActivity
import org.tvp.krkrsdl3.KRKRActivity
import java.io.File
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

    @Volatile private var graphics: Krkrsdl3Graphics? = null
    private var graphicsOverlay: ArtemisPerformanceOverlay? = null
    private var startupController: EngineSurfaceStartupController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        // 先应用标准横屏再走 super；SDL/KRKR 的后续方向请求也由本 Activity 固定为横屏。
        applyEngineRequestedOrientation()
        applyLauncherWindowTone()
        // 引擎主体由启动编排解压到私有目录；缺失说明安装包不完整、解压失败，
        // 或应用升级后宿主从最近任务恢复而没有再经过 EngineLauncher.ensure。
        val engineLibrary = NativeRuntimePack.libraryPath(
            this,
            NativeRuntimePack.PACK_KRKRSDL3,
            "libkrkrsdl3.so",
        )
        if (engineLibrary == null) {
            // 不能在此 finish：本类是 SDL 宿主，super.onCreate 才加载运行库，
            // 未调用 super 就结束会抛 SuperNotCalledException。保留 null，交给
            // KRKRActivity.loadLibraries 抛 UnsatisfiedLinkError，由 SDL 的
            // mBrokenLibraries 错误路径收场。
            Toast.makeText(this, getString(R.string.engine_krkrsdl3_runtime_pack_missing), Toast.LENGTH_LONG).show()
            Log.w(TAG, "krkrsdl3 runtime pack not ready")
        }
        // 无条件注入（含 null）：静态字段可能保留上一实例的旧路径，必须清空才能让 loadLibraries 抛错。
        setEngineLibraryPath(engineLibrary)
        graphics = Krkrsdl3Graphics(this, intent).apply {
            // 引擎库在私有目录、SDL3 仍在 lib/，都按绝对路径做 NOLOAD 查找。
            setLibraryPaths(engineLibrary, sdlLibraryPath())
        }
        super.onCreate(savedInstanceState)
        if (!SDLActivity.mBrokenLibraries) {
            graphics?.let { graphicsOverlay = ArtemisPerformanceOverlay(this, it) }
            attachStartupOverlay()
        }
    }

    /** `libSDL3.so` 仍随 APK 的 `lib/`，图形桥按该绝对路径做 NOLOAD 查找。 */
    private fun sdlLibraryPath(): String =
        File(applicationInfo.nativeLibraryDir, "libSDL3.so").absolutePath

    private fun attachStartupOverlay() {
        val surfaceView = SDLActivity.mSurface ?: return
        val layout = SDLActivity.mLayout ?: return
        val content = findViewById<ViewGroup>(android.R.id.content)
        startupController = EngineSurfaceStartupController(this) { graphicsOverlay?.show() }.also {
            it.attachTo(content ?: layout, surfaceView.holder)
        }
    }

    /**
     * SDL3 运行库加载失败时只弹错误框并返回，但 KRKRActivity.onCreate 之后仍会调用本 native
     * 方法（实现在未加载的 libkrkrsdl3.so）。此处跳过，避免 UnsatisfiedLinkError 盖掉 SDL
     * 错误路径；正常加载时转交原生实现。
     */
    override fun setNativeAssetManager(assetManager: AssetManager) {
        if (SDLActivity.mBrokenLibraries) return
        super.setNativeAssetManager(assetManager)
    }

    override fun onResume() {
        super.onResume()
        // 保持标准横屏，覆盖运行库或启动参数可能带来的方向请求。
        applyEngineRequestedOrientation()
        if (startupController?.isLoading() != true) graphicsOverlay?.show()
        startupController?.onResume()
    }

    override fun onPause() {
        startupController?.onPause()
        graphicsOverlay?.hide()
        super.onPause()
    }

    /** SDL owns this thread and all GLES work; registration precedes native main. */
    override fun main() {
        val session = graphics
        try {
            session?.install()
            super.main()
        } finally {
            session?.close()
            runOnUiThread { graphicsOverlay?.hide() }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 重配置后重新断言标准横屏与沉浸全屏。
        applyEngineRequestedOrientation()
        applySystemUiVisibility()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        val forceFocus = intent?.getStringExtra(LaunchContract.FOCUS)?.toBoolean() ?: false
        super.onWindowFocusChanged(hasFocus || forceFocus)
        startupController?.onWindowFocusChanged(hasFocus)
        if (hasFocus || forceFocus) applySystemUiVisibility()
        if (hasFocus || forceFocus) {
            if (startupController?.isLoading() != true) graphicsOverlay?.show()
        } else {
            graphicsOverlay?.hide()
        }
    }

    /**
     * 拦截 SDL 原生方向回调（[SDLActivity.setOrientation] JNI → setOrientationBis），
     * 固定标准横屏并在 UI 线程恢复沉浸标志。
     */
    override fun setOrientationBis(w: Int, h: Int, resizable: Boolean, hint: String?) {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
            applySystemUiVisibility()
        }
    }

    override fun setRequestedOrientation(requestedOrientation: Int) {
        super.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
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
        startupController?.dispose()
        startupController = null
        graphicsOverlay?.hide()
        graphicsOverlay = null
        super.onDestroy()
    }

    /** 统一方向入口：播放器始终使用标准横屏。 */
    private fun applyEngineRequestedOrientation() {
        try {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
        } catch (t: Throwable) {
            // 个别 ROM 对非全屏窗口调用会抛 IllegalStateException；保持当前方向即可
            Log.w(TAG, "apply orientation failed", t)
        }
    }

    companion object {
        private const val TAG = "Krkrsdl3Activity"
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
