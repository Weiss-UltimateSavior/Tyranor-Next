package com.core.engine

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
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
    fun resolveFixedLandscapeDefaultsToStandardLandscapeOnNullIntent() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            EnginePlayerOrientation.resolveFixedLandscape(null),
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
    fun updateForNewIntentRefreshesFromExtraOrDisplayAndIgnoresPreviousCache() {
        // updateForNewIntent 以 (restored=null, cached=null) 复用同一四级决策：
        // 有 extra 按归一化 extra 刷新（反向值保持反向、非具体横屏值落标准横屏），
        // 无 extra 走显示兜底，绝不沿用旧缓存方向。
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
            EnginePlayerOrientation.resolveLockedTarget(
                restored = null,
                cached = null,
                intentValue = ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
                displayFallback = { error("displayFallback must not be consulted when intent extra exists") },
            ),
        )
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            EnginePlayerOrientation.resolveLockedTarget(
                restored = null,
                cached = null,
                intentValue = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
                displayFallback = { error("displayFallback must not be consulted when intent extra exists") },
            ),
        )
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
    fun applyOrientationSafelySwallowsOnlyRuntimeExceptions() {
        // P3-13：方向锁定的安全应用只吞 RuntimeException 家族（受限窗口/非全屏/宿主覆写失败），
        // Error 原样传播，不掩盖进程级错误。
        var swallowed: Throwable? = null
        val runtime = IllegalStateException("restricted window")
        EnginePlayerOrientation.applyOrientationSafely(
            orientation = ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
            setter = { throw runtime },
            onSwallowed = { orientation, failure ->
                assertEquals(ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE, orientation)
                swallowed = failure
            },
        )
        assertSame(runtime, swallowed)

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
    }
}
