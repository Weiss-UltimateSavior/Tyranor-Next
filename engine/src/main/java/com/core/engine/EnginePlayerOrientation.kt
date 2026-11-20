package com.core.engine

import android.app.Activity
import android.content.Context
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
import android.view.WindowManager
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
    internal const val STATE_LOCKED_ORIENTATION = "com.core.engine.LOCKED_ORIENTATION"

    private val activityLocks: MutableMap<Any, Int> =
        Collections.synchronizedMap(WeakHashMap())

    /** 将传入的方向值归一化为具体单向横屏常量（`0` 或 `8`）。 */
    @JvmStatic
    fun normalizeLandscapeOrientation(orientation: Int): Int =
        if (orientation == ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE) {
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }

    private fun Intent?.orientationExtraOrNull(): Int? =
        this?.takeIf { it.hasExtra(LaunchContract.ORIENTATION) }
            ?.getIntExtra(LaunchContract.ORIENTATION, ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)

    /**
     * 会话方向锁定的四级优先纯决策（抽出为可 JVM 测试的纯函数钉住行为）：
     *
     * 1. [restored] 非 null：`onSaveInstanceState` 恢复的方向最高优先，归一化后直接返回——
     *    实例因外设/配置重建时不得丢失原方向；
     * 2. [cached] 非 null：当前实例已锁定的缓存方向次之，同一会话期保持不变；
     * 3. [intentValue] 非 null：启动 Intent 携带的 [LaunchContract.ORIENTATION] 原始值，
     *    归一化为具体单向横屏（非反向值一律回落标准横屏）；
     * 4. 以上全无：调用 [displayFallback] 按当前显示旋转角兜底。惰性求值——命中前三档时
     *    不执行显示查询。
     */
    internal fun resolveLockedTarget(
        restored: Int?,
        cached: Int?,
        intentValue: Int?,
        displayFallback: () -> Int,
    ): Int =
        when {
            restored != null -> normalizeLandscapeOrientation(restored)
            cached != null -> cached
            intentValue != null -> normalizeLandscapeOrientation(intentValue)
            else -> displayFallback()
        }

    internal fun resolveLocked(
        key: Any,
        intentValue: Int?,
        displayFallback: () -> Int,
    ): Int =
        synchronized(activityLocks) {
            val resolved = resolveLockedTarget(
                restored = null,
                cached = activityLocks[key],
                intentValue = intentValue,
                displayFallback = displayFallback,
            )
            activityLocks[key] = resolved
            resolved
        }

    internal fun lockOnCreate(
        key: Any,
        restored: Int?,
        intentValue: Int?,
        displayFallback: () -> Int,
        applySetter: (Int) -> Unit,
    ): Int {
        val target = synchronized(activityLocks) {
            val resolved = resolveLockedTarget(
                restored = restored,
                cached = activityLocks[key],
                intentValue = intentValue,
                displayFallback = displayFallback,
            )
            activityLocks[key] = resolved
            resolved
        }
        applySetter(target)
        return target
    }

    internal fun saveLockedState(
        key: Any,
        intentValue: Int?,
        displayFallback: () -> Int,
        writeState: (String, Int) -> Unit,
    ): Int {
        val locked = resolveLocked(key, intentValue, displayFallback)
        writeState(STATE_LOCKED_ORIENTATION, locked)
        return locked
    }

    internal fun refreshOnNewIntent(
        key: Any,
        intentValue: Int?,
        displayFallback: () -> Int,
    ): Int {
        // 刷新决策 = 同一四级决策去掉恢复与缓存两档：新 Intent 携带 extra 时按 extra 刷新，
        // 否则按当前显示兜底重判，绝不沿用旧缓存方向。
        val updated = resolveLockedTarget(
            restored = null,
            cached = null,
            intentValue = intentValue,
            displayFallback = displayFallback,
        )
        synchronized(activityLocks) {
            activityLocks[key] = updated
        }
        return updated
    }

    internal fun syncOnNewIntent(
        key: Any,
        relaunchNeeded: Boolean,
        activeIntentValue: Int?,
        newIntentValue: Int?,
        displayFallback: () -> Int,
        forwardExtra: (String, Int) -> Unit,
    ): Int =
        if (relaunchNeeded) {
            refreshOnNewIntent(key, newIntentValue, displayFallback)
        } else {
            resolveLocked(key, activeIntentValue, displayFallback).also {
                forwardExtra(LaunchContract.ORIENTATION, it)
            }
        }

    /**
     * 解析并缓存宿主 Activity 本次会话应锁定的具体横屏方向。
     *
     * 优先返回当前实例已锁定的方向；其次读取启动 Intent 下发的 [LaunchContract.ORIENTATION]；
     * 若未携带该 extra（如直接测试拉起或未透传路径），再按当前显示横屏旋转角兜底。
     */
    @JvmStatic
    fun resolveForActivity(activity: Activity): Int =
        resolveLocked(
            key = activity,
            intentValue = activity.intent.orientationExtraOrNull(),
            displayFallback = { currentDisplayLandscapeOrientation(activity) },
        )

    /**
     * 在宿主 `onCreate` 中初始化或恢复并安全锁定方向；必须先于 `super.onCreate` 调用
     * （多数宿主为首行，个别宿主在早退预检之后、仍保证先于 `super.onCreate`）。
     * `savedInstanceState` 携带已锁定方向时恢复优先于启动 Intent 与显示兜底。
     */
    @JvmStatic
    @JvmOverloads
    fun lockOnCreate(activity: Activity, savedInstanceState: Bundle? = null): Int =
        lockOnCreate(
            key = activity,
            restored = savedInstanceState
                ?.takeIf { it.containsKey(STATE_LOCKED_ORIENTATION) }
                ?.getInt(STATE_LOCKED_ORIENTATION),
            intentValue = activity.intent.orientationExtraOrNull(),
            displayFallback = { currentDisplayLandscapeOrientation(activity) },
            applySetter = { target ->
                applySafely(activity, target) { activity.setRequestedOrientation(it) }
            },
        )

    /** 保存当前实例已锁定的方向，供系统因外设/配置重建时原样恢复。 */
    @JvmStatic
    fun saveState(activity: Activity, outState: Bundle) {
        saveLockedState(
            key = activity,
            intentValue = activity.intent.orientationExtraOrNull(),
            displayFallback = { currentDisplayLandscapeOrientation(activity) },
            writeState = outState::putInt,
        )
    }

    /** 热切换不同游戏触发 `recreate()` 前刷新实例缓存，避免 `onSaveInstanceState` 写回旧方向。 */
    @JvmStatic
    fun updateForNewIntent(activity: Activity, newIntent: Intent) {
        refreshOnNewIntent(
            key = activity,
            intentValue = newIntent.orientationExtraOrNull(),
            displayFallback = { currentDisplayLandscapeOrientation(activity) },
        )
    }

    /** 同步 `onNewIntent` 的方向状态：同游戏继续沿用并透传已锁定方向；切换/重建前按新 Intent 刷新缓存。 */
    @JvmStatic
    fun syncOnNewIntent(activity: Activity, newIntent: Intent, relaunchNeeded: Boolean) {
        syncOnNewIntent(
            key = activity,
            relaunchNeeded = relaunchNeeded,
            activeIntentValue = activity.intent.orientationExtraOrNull(),
            newIntentValue = newIntent.orientationExtraOrNull(),
            displayFallback = { currentDisplayLandscapeOrientation(activity) },
            forwardExtra = newIntent::putExtra,
        )
    }

    /** 在非 `setRequestedOrientation` 回调中安全重新断言已锁定的方向，并返回该值供宿主日志/透传复用。 */
    @JvmStatic
    fun applyLocked(activity: Activity): Int {
        val target = resolveForActivity(activity)
        applySafely(activity, target) { activity.setRequestedOrientation(it) }
        return target
    }

    /**
     * 供各宿主覆盖 `setRequestedOrientation` 时统一调用，拦截运行期外部/SDL 方向切换请求并捕获受限窗口异常。
     *
     * 本入口仅接受并维持启动期锁定的具体单向横屏方向（`0` 或 `8`），其余一切方向请求（如竖屏、
     * `SENSOR`、视频/原生 SDK 重置）一律收敛为当前会话方向；若未来需支持竖屏玩法或可变方向，
     * 必须新增显式白名单策略，不可直接放行传入参数。
     */
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

    /**
     * 纯判定：把显示方向配置与显示旋转角映射为具体单向横屏常量。
     *
     * 与 Android 自然方向约定一致（手机自然竖屏、平板自然横屏）：配置为横屏且旋转角为
     * 180°/270°（相对自然方向已越过半圈/逆时针四分之一圈）判定为反向横屏，其余（含
     * 配置为竖屏或旋转角不可得）一律回落标准横屏。
     */
    @JvmStatic
    fun landscapeOrientationForDisplay(configOrientation: Int, displayRotation: Int): Int {
        if (configOrientation == Configuration.ORIENTATION_LANDSCAPE &&
            (displayRotation == Surface.ROTATION_180 || displayRotation == Surface.ROTATION_270)
        ) {
            return ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
        }
        return ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    }

    /**
     * 解析用于方向判定的默认显示；`activity.display`（R+）优先，缺失或失败时回落
     * [DisplayManager] 的默认显示。app 侧方向监测与本类共用该实现（P3-2 收口）。
     */
    @JvmStatic
    fun resolveDisplay(context: Context): Display? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && context is Activity) {
            runCatching { context.display }.getOrNull()?.let { return it }
        }
        return runCatching {
            context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
        }.getOrNull()
    }

    /**
     * 判定默认显示的物理横竖（真实显示宽 > 高）；先取 `maximumWindowMetrics`（R+），
     * 失败再退 `getRealMetrics`，两者都不可信时返回 null（调用方回落 configuration 判定）。
     * app 侧方向监测与本类共用该实现（P3-2 收口）。
     */
    @JvmStatic
    fun isPhysicalDisplayLandscape(context: Context, display: Display?): Boolean? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val wmContext = if (context is Activity) context else (context.applicationContext ?: context)
            val bounds = runCatching {
                wmContext.getSystemService(WindowManager::class.java)?.maximumWindowMetrics?.bounds
            }.getOrNull()
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

    /**
     * 安全执行方向设置：只吞 [RuntimeException] 家族（受限窗口/非全屏/宿主覆写实现的失败
     * 均属该家族，方向锁定失败不阻断宿主启动）；若首次以反向横屏锁定失败，二次退回
     * [ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE] 重试一次，防止在清单已放开 `sensorLandscape`
     * 的受限 ROM 上退化为运行期随传感器自由旋转。[Error] 原样传播。
     */
    @JvmStatic
    internal fun applyOrientationSafely(
        orientation: Int,
        setter: (Int) -> Unit,
        onSwallowed: (Int, RuntimeException) -> Unit,
    ) {
        try {
            setter(orientation)
        } catch (failure: RuntimeException) {
            onSwallowed(orientation, failure)
            if (orientation != ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) {
                try {
                    setter(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
                } catch (fallbackFailure: RuntimeException) {
                    onSwallowed(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, fallbackFailure)
                }
            }
        }
    }

    private fun applySafely(activity: Activity, orientation: Int, setter: (Int) -> Unit) {
        applyOrientationSafely(orientation, setter) { target, failure ->
            Log.w(TAG, "apply orientation ($target) failed for ${activity.javaClass.simpleName}", failure)
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
}
