package com.core.engine

import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.Surface
import java.util.Collections
import java.util.WeakHashMap
import java.util.function.IntConsumer

/**
 * 内置引擎播放器会话方向锁工具。
 *
 * 主进程在拉起子进程前判定当前握持对应的具体横屏值（[ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE] 或
 * [ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE]）并通过 [LaunchContract.ORIENTATION] 传入；
 * 子进程在初始化阶段锁定该值并在整个会话期拦截后续方向请求，从而既能按入场朝向显示，
 * 又不在运行期内发生旋转或重建生命周期。
 */
object EnginePlayerOrientation {
    private const val TAG = "EngineOrientation"
    private const val STATE_LOCKED_ORIENTATION = "com.core.engine.LOCKED_ORIENTATION"

    private val activityLocks: MutableMap<Activity, Int> =
        Collections.synchronizedMap(WeakHashMap())

    /** 将传入的方向值归一化为具体单向横屏常量（`0` 或 `8`）。 */
    @JvmStatic
    fun normalizeLandscapeOrientation(orientation: Int): Int =
        if (orientation == ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE) {
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }

    /** 从启动 Intent 解析具体横屏常量；缺失或非具体反向值时回落标准横屏。 */
    @JvmStatic
    fun resolveFixedLandscape(intent: Intent?): Int {
        val requested = intent?.getIntExtra(
            LaunchContract.ORIENTATION,
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
        ) ?: ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        return normalizeLandscapeOrientation(requested)
    }

    /**
     * 解析并缓存宿主 Activity 本次会话应锁定的具体横屏方向。
     *
     * 优先返回当前实例已锁定的方向；其次读取启动 Intent 下发的 [LaunchContract.ORIENTATION]；
     * 若未携带该 extra（如直接测试拉起或未透传路径），再按当前显示横屏旋转角兜底。
     */
    @JvmStatic
    fun resolveForActivity(activity: Activity): Int {
        synchronized(activityLocks) {
            activityLocks[activity]?.let { return it }
            val intent = activity.intent
            val resolved = if (intent != null && intent.hasExtra(LaunchContract.ORIENTATION)) {
                resolveFixedLandscape(intent)
            } else {
                currentDisplayLandscapeOrientation(activity)
            }
            activityLocks[activity] = resolved
            return resolved
        }
    }

    /** 在 `onCreate`（`super.onCreate` 之前、早退预检之后）初始化或恢复并安全锁定方向。 */
    @JvmStatic
    @JvmOverloads
    fun lockOnCreate(activity: Activity, savedInstanceState: Bundle? = null): Int {
        val target = synchronized(activityLocks) {
            if (savedInstanceState != null && savedInstanceState.containsKey(STATE_LOCKED_ORIENTATION)) {
                val restored = normalizeLandscapeOrientation(
                    savedInstanceState.getInt(STATE_LOCKED_ORIENTATION),
                )
                activityLocks[activity] = restored
                restored
            } else {
                resolveForActivity(activity)
            }
        }
        applySafely(activity, target) { activity.setRequestedOrientation(it) }
        return target
    }

    /** 保存当前实例已锁定的方向，供系统因外设/配置重建时原样恢复。 */
    @JvmStatic
    fun saveState(activity: Activity, outState: Bundle) {
        outState.putInt(STATE_LOCKED_ORIENTATION, resolveForActivity(activity))
    }

    /** 热切换不同游戏触发 `recreate()` 前刷新实例缓存，避免 `onSaveInstanceState` 写回旧方向。 */
    @JvmStatic
    fun updateForNewIntent(activity: Activity, newIntent: Intent) {
        val updated = if (newIntent.hasExtra(LaunchContract.ORIENTATION)) {
            resolveFixedLandscape(newIntent)
        } else {
            currentDisplayLandscapeOrientation(activity)
        }
        synchronized(activityLocks) {
            activityLocks[activity] = updated
        }
    }

    /** 在非 `setRequestedOrientation` 回调中安全重新断言已锁定的方向。 */
    @JvmStatic
    fun applyLocked(activity: Activity) {
        val target = resolveForActivity(activity)
        applySafely(activity, target) { activity.setRequestedOrientation(it) }
    }

    /** 供各宿主覆盖 `setRequestedOrientation` 时统一调用，捕获受限窗口/非全屏异常。 */
    @JvmStatic
    fun applyWithSuper(activity: Activity, superSetter: IntConsumer) {
        val target = resolveForActivity(activity)
        applySafely(activity, target) { superSetter.accept(it) }
    }

    /** 将宿主当前锁定的具体横屏方向透传给同任务的派生或回退 Intent。 */
    @JvmStatic
    fun forwardToIntent(activity: Activity, target: Intent) {
        target.putExtra(LaunchContract.ORIENTATION, resolveForActivity(activity))
    }

    @JvmStatic
    fun landscapeOrientationForDisplay(configOrientation: Int, displayRotation: Int): Int {
        if (configOrientation == Configuration.ORIENTATION_LANDSCAPE &&
            (displayRotation == Surface.ROTATION_180 || displayRotation == Surface.ROTATION_270)
        ) {
            return ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
        }
        return ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    }

    private fun applySafely(activity: Activity, orientation: Int, setter: (Int) -> Unit) {
        try {
            setter(orientation)
        } catch (t: Throwable) {
            Log.w(TAG, "apply orientation ($orientation) failed for ${activity.javaClass.simpleName}", t)
        }
    }

    private fun currentDisplayLandscapeOrientation(activity: Activity): Int {
        val display = resolveDisplay(activity)
        val rotation = runCatching { display?.rotation }.getOrNull() ?: Surface.ROTATION_0
        val isDisplayLandscape = isPhysicalDisplayLandscape(activity, display)
            ?: (activity.resources?.configuration?.orientation == Configuration.ORIENTATION_LANDSCAPE)
        val configOrientation = if (isDisplayLandscape) {
            Configuration.ORIENTATION_LANDSCAPE
        } else {
            Configuration.ORIENTATION_PORTRAIT
        }
        return landscapeOrientationForDisplay(configOrientation, rotation)
    }

    private fun resolveDisplay(activity: Activity): Display? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { activity.display }.getOrNull()?.let { return it }
        }
        return runCatching {
            activity.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
        }.getOrNull()
    }

    private fun isPhysicalDisplayLandscape(activity: Activity, display: Display?): Boolean? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = runCatching { activity.windowManager?.maximumWindowMetrics?.bounds }.getOrNull()
            if (bounds != null && bounds.width() > 0 && bounds.height() > 0) {
                return bounds.width() > bounds.height()
            }
        }
        if (display == null) return null
        return runCatching {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            display.getRealMetrics(metrics)
            if (metrics.widthPixels > 0 && metrics.heightPixels > 0) {
                metrics.widthPixels > metrics.heightPixels
            } else {
                null
            }
        }.getOrNull()
    }
}
