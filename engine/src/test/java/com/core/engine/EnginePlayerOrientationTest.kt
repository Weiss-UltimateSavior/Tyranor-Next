package com.core.engine

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class EnginePlayerOrientationTest {
    @Test
    fun normalizeLandscapeOrientationAcceptsOnlyConcreteLandscapeValues() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            EnginePlayerOrientation.normalizeLandscapeOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE),
        )
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
            EnginePlayerOrientation.normalizeLandscapeOrientation(ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE),
        )
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            EnginePlayerOrientation.normalizeLandscapeOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE),
        )
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            EnginePlayerOrientation.normalizeLandscapeOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT),
        )
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            EnginePlayerOrientation.normalizeLandscapeOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED),
        )
    }

    @Test
    fun landscapeOrientationForDisplayMapsBothPhoneAndTabletRotations() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            EnginePlayerOrientation.landscapeOrientationForDisplay(
                Configuration.ORIENTATION_LANDSCAPE,
                Surface.ROTATION_90,
            ),
        )
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
            EnginePlayerOrientation.landscapeOrientationForDisplay(
                Configuration.ORIENTATION_LANDSCAPE,
                Surface.ROTATION_270,
            ),
        )
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            EnginePlayerOrientation.landscapeOrientationForDisplay(
                Configuration.ORIENTATION_LANDSCAPE,
                Surface.ROTATION_0,
            ),
        )
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
            EnginePlayerOrientation.landscapeOrientationForDisplay(
                Configuration.ORIENTATION_LANDSCAPE,
                Surface.ROTATION_180,
            ),
        )
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            EnginePlayerOrientation.landscapeOrientationForDisplay(
                Configuration.ORIENTATION_PORTRAIT,
                Surface.ROTATION_270,
            ),
        )
    }

    @Test
    fun resolveLockedTargetPinsFourLevelPriority() {
        // 恢复 > 缓存 > Intent extra > 显示兜底；命中高优先档时不查询显示（惰性兜底）。
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
            EnginePlayerOrientation.resolveLockedTarget(
                restored = ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
                cached = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
                intentValue = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
                displayFallback = { error("displayFallback must not be consulted when restored exists") },
            ),
        )
        // 恢复值同样归一化：非具体反向横屏一律落标准横屏（实例重建不得丢方向）。
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            EnginePlayerOrientation.resolveLockedTarget(
                restored = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
                cached = ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
                intentValue = ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
                displayFallback = { error("displayFallback must not be consulted when restored exists") },
            ),
        )
        // 缓存 > extra：同一实例会话期保持已锁定方向。
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
            EnginePlayerOrientation.resolveLockedTarget(
                restored = null,
                cached = ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
                intentValue = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
                displayFallback = { error("displayFallback must not be consulted when cached exists") },
            ),
        )
        // extra > 显示兜底：extra 归一化（非反向值落标准横屏）。
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            EnginePlayerOrientation.resolveLockedTarget(
                restored = null,
                cached = null,
                intentValue = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
                displayFallback = { error("displayFallback must not be consulted when intent extra exists") },
            ),
        )
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
            EnginePlayerOrientation.resolveLockedTarget(
                restored = null,
                cached = null,
                intentValue = ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
                displayFallback = { error("displayFallback must not be consulted when intent extra exists") },
            ),
        )
        // 全缺 → 显示兜底（如未透传 extra 的直接拉起路径）。
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
            EnginePlayerOrientation.resolveLockedTarget(
                restored = null,
                cached = null,
                intentValue = null,
                displayFallback = { ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE },
            ),
        )
    }

    @Test
    fun resolveLockedCachesSessionAndIgnoresSubsequentExtrasOrDisplay() {
        val hostA = Any()
        val applied = mutableListOf<Int>()
        val locked = EnginePlayerOrientation.lockOnCreate(
            key = hostA,
            restored = null,
            intentValue = ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
            displayFallback = { error("displayFallback must not be consulted when intent extra exists") },
            applySetter = { applied += it },
        )
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE, locked)
        assertEquals(listOf(ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE), applied)

        // 同一实例再次解析时优先读缓存，忽略后续变化的 extra 与显示方向。
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
            EnginePlayerOrientation.resolveLocked(
                key = hostA,
                intentValue = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
                displayFallback = { error("displayFallback must not be consulted when session is cached") },
            ),
        )

        // 首次直接走 resolveLocked 也会写入缓存并保持会话一致。
        val hostB = Any()
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            EnginePlayerOrientation.resolveLocked(
                key = hostB,
                intentValue = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
                displayFallback = { error("displayFallback must not be consulted when intent extra exists") },
            ),
        )
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            EnginePlayerOrientation.resolveLocked(
                key = hostB,
                intentValue = ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
                displayFallback = { ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE },
            ),
        )
    }

    @Test
    fun saveLockedStateRoundTripsThroughLockOnCreateOverIntentExtra() {
        val originalHost = Any()
        EnginePlayerOrientation.lockOnCreate(
            key = originalHost,
            restored = null,
            intentValue = ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
            displayFallback = { ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE },
            applySetter = {},
        )
        val savedBundle = mutableMapOf<String, Int>()
        val savedOrientation = EnginePlayerOrientation.saveLockedState(
            key = originalHost,
            intentValue = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            displayFallback = { error("displayFallback must not be consulted when cached exists") },
            writeState = { k, v -> savedBundle[k] = v },
        )
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE, savedOrientation)
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
            savedBundle[EnginePlayerOrientation.STATE_LOCKED_ORIENTATION],
        )

        // 模拟配置变更后新 Activity 实例恢复：恢复态优先于 Intent extra。
        val recreatedHost = Any()
        val appliedAfterRestore = mutableListOf<Int>()
        val restored = EnginePlayerOrientation.lockOnCreate(
            key = recreatedHost,
            restored = savedBundle[EnginePlayerOrientation.STATE_LOCKED_ORIENTATION],
            intentValue = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            displayFallback = { error("displayFallback must not be consulted when restored exists") },
            applySetter = { appliedAfterRestore += it },
        )
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE, restored)
        assertEquals(listOf(ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE), appliedAfterRestore)
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
            EnginePlayerOrientation.resolveLocked(
                key = recreatedHost,
                intentValue = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
                displayFallback = { ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE },
            ),
        )
    }

    @Test
    fun syncAndRefreshOnNewIntentMutateRealCacheOnlyWhenRelaunching() {
        val host = Any()
        EnginePlayerOrientation.lockOnCreate(
            key = host,
            restored = null,
            intentValue = ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
            displayFallback = { ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE },
            applySetter = {},
        )

        // 同会话再派发（relaunchNeeded=false）：保留旧锁并透传给 incoming Intent，不覆写缓存。
        val forwardedExtras = mutableMapOf<String, Int>()
        val kept = EnginePlayerOrientation.syncOnNewIntent(
            key = host,
            relaunchNeeded = false,
            activeIntentValue = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            newIntentValue = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            displayFallback = { error("displayFallback must not be consulted when cached exists") },
            forwardExtra = { k, v -> forwardedExtras[k] = v },
        )
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE, kept)
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
            forwardedExtras[LaunchContract.ORIENTATION],
        )
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
            EnginePlayerOrientation.resolveLocked(host, null) { ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE },
        )

        // 切换游戏需重建（relaunchNeeded=true）：真实改写缓存为新 Intent extra 归一化值，不依赖旧缓存。
        val switchedExtras = mutableMapOf<String, Int>()
        val refreshed = EnginePlayerOrientation.syncOnNewIntent(
            key = host,
            relaunchNeeded = true,
            activeIntentValue = ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
            newIntentValue = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            displayFallback = { error("displayFallback must not be consulted when new extra exists") },
            forwardExtra = { k, v -> switchedExtras[k] = v },
        )
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, refreshed)
        assertTrue(switchedExtras.isEmpty())
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            EnginePlayerOrientation.resolveLocked(host, ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE) {
                ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
            },
        )

        // 新 Intent 非具体横屏值落标准横屏；缺 extra 时忽略旧缓存并走显示兜底刷新缓存。
        EnginePlayerOrientation.refreshOnNewIntent(
            key = host,
            intentValue = null,
            displayFallback = { ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE },
        )
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
            EnginePlayerOrientation.resolveLocked(host, ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) {
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            },
        )
        EnginePlayerOrientation.refreshOnNewIntent(
            key = host,
            intentValue = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            displayFallback = { error("displayFallback must not be consulted when new extra exists") },
        )
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            EnginePlayerOrientation.resolveLocked(host, ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE) {
                ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
            },
        )
    }

    @Test
    fun applyOrientationSafelyFallsBackToStandardLandscapeBeforeGivingUp() {
        // 反向横屏锁定抛 RuntimeException 时，二次退回标准横屏锁定，避免窗口留在 sensorLandscape 自由旋转。
        val attempts = mutableListOf<Int>()
        val swallowed = mutableListOf<Pair<Int, RuntimeException>>()
        val reverseFailure = IllegalStateException("reverse rejected")
        EnginePlayerOrientation.applyOrientationSafely(
            orientation = ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
            setter = { target ->
                attempts += target
                if (target == ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE) {
                    throw reverseFailure
                }
            },
            onSwallowed = { orientation, failure -> swallowed += orientation to failure },
        )
        assertEquals(
            listOf(
                ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            ),
            attempts,
        )
        assertEquals(1, swallowed.size)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE, swallowed[0].first)
        assertSame(reverseFailure, swallowed[0].second)
    }

    @Test
    fun applyOrientationSafelySwallowsOnlyRuntimeExceptions() {
        // 反向与兜底均因受限窗口抛 RuntimeException 时都吞掉且不阻断启动；标准横屏失败不重复自试。
        val bothAttempts = mutableListOf<Int>()
        val bothSwallowed = mutableListOf<Int>()
        EnginePlayerOrientation.applyOrientationSafely(
            orientation = ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
            setter = { target ->
                bothAttempts += target
                throw IllegalStateException("restricted window: $target")
            },
            onSwallowed = { orientation, _ -> bothSwallowed += orientation },
        )
        assertEquals(
            listOf(
                ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            ),
            bothAttempts,
        )
        assertEquals(
            listOf(
                ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            ),
            bothSwallowed,
        )

        val landscapeAttempts = mutableListOf<Int>()
        val runtime = IllegalStateException("restricted window")
        var landscapeSwallowed: Throwable? = null
        EnginePlayerOrientation.applyOrientationSafely(
            orientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            setter = { target ->
                landscapeAttempts += target
                throw runtime
            },
            onSwallowed = { orientation, failure ->
                assertEquals(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, orientation)
                landscapeSwallowed = failure
            },
        )
        assertEquals(listOf(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE), landscapeAttempts)
        assertSame(runtime, landscapeSwallowed)

        // Error（包括回退尝试中抛出的 Error）必须原样传播，不掩盖进程级错误。
        var consulted = false
        val fatal = OutOfMemoryError("do not swallow")
        try {
            EnginePlayerOrientation.applyOrientationSafely(
                orientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
                setter = { throw fatal },
                onSwallowed = { _, _ -> consulted = true },
            )
            fail("Expected OutOfMemoryError to propagate")
        } catch (caught: OutOfMemoryError) {
            assertSame(fatal, caught)
            assertFalse(consulted)
        }

        val fallbackFatal = OutOfMemoryError("fatal on fallback")
        try {
            EnginePlayerOrientation.applyOrientationSafely(
                orientation = ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
                setter = { target ->
                    if (target == ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE) {
                        throw IllegalStateException("primary runtime")
                    }
                    throw fallbackFatal
                },
                onSwallowed = { _, _ -> },
            )
            fail("Expected OutOfMemoryError from fallback to propagate")
        } catch (caught: OutOfMemoryError) {
            assertSame(fallbackFatal, caught)
        }
    }
}
