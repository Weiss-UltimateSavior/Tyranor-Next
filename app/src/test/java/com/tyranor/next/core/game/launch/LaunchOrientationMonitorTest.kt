package com.tyranor.next.core.game.launch

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.OrientationEventListener
import android.view.Surface
import com.core.engine.EnginePlayerOrientation
import com.tyranor.next.core.engine.external.ExternalEngineContract
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LaunchOrientationMonitorTest {
    @Before
    fun setUp() {
        LaunchOrientationMonitor.resetForTest()
    }

    @After
    fun tearDown() {
        LaunchOrientationMonitor.resetForTest()
    }

    @Test
    fun displayAlreadyInLandscapeOverridesSensorReading() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
            LaunchOrientationMonitor.resolveOrientationFromSignals(
                displayOrientation = Configuration.ORIENTATION_LANDSCAPE,
                displayRotation = Surface.ROTATION_270,
                sensorDegrees = 270,
            ),
        )
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            LaunchOrientationMonitor.resolveOrientationFromSignals(
                displayOrientation = Configuration.ORIENTATION_LANDSCAPE,
                displayRotation = Surface.ROTATION_90,
                sensorDegrees = 90,
            ),
        )
        // Landscape-natural tablet
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
            LaunchOrientationMonitor.resolveOrientationFromSignals(
                displayOrientation = Configuration.ORIENTATION_LANDSCAPE,
                displayRotation = Surface.ROTATION_180,
                sensorDegrees = null,
            ),
        )
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            LaunchOrientationMonitor.resolveOrientationFromSignals(
                displayOrientation = Configuration.ORIENTATION_LANDSCAPE,
                displayRotation = Surface.ROTATION_0,
                sensorDegrees = null,
            ),
        )
    }

    @Test
    fun portraitUiUsesSensorDegreesToDistinguishLandscapeSides() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
            LaunchOrientationMonitor.resolveOrientationFromSignals(
                displayOrientation = Configuration.ORIENTATION_PORTRAIT,
                displayRotation = Surface.ROTATION_0,
                sensorDegrees = 90,
            ),
        )
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            LaunchOrientationMonitor.resolveOrientationFromSignals(
                displayOrientation = Configuration.ORIENTATION_PORTRAIT,
                displayRotation = Surface.ROTATION_0,
                sensorDegrees = 270,
            ),
        )
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            LaunchOrientationMonitor.resolveOrientationFromSignals(
                displayOrientation = Configuration.ORIENTATION_PORTRAIT,
                displayRotation = Surface.ROTATION_0,
                sensorDegrees = null,
            ),
        )
    }

    @Test
    fun sectorAndDeadzoneBoundariesMaintainHysteresisWithoutOverlap() {
        // Portrait-natural landscape sectors: [45, 135] and [225, 315]
        assertFalse(LaunchOrientationMonitor.isLandscapeSector(44, naturalIsLandscape = false))
        assertTrue(LaunchOrientationMonitor.isLandscapeSector(45, naturalIsLandscape = false))
        assertTrue(LaunchOrientationMonitor.isLandscapeSector(135, naturalIsLandscape = false))
        assertFalse(LaunchOrientationMonitor.isLandscapeSector(136, naturalIsLandscape = false))
        assertFalse(LaunchOrientationMonitor.isLandscapeSector(224, naturalIsLandscape = false))
        assertTrue(LaunchOrientationMonitor.isLandscapeSector(225, naturalIsLandscape = false))
        assertTrue(LaunchOrientationMonitor.isLandscapeSector(315, naturalIsLandscape = false))
        assertFalse(LaunchOrientationMonitor.isLandscapeSector(316, naturalIsLandscape = false))

        // Portrait-natural deadzones: [0, 25], [335, 359], [155, 205]
        assertTrue(LaunchOrientationMonitor.isPortraitDeadzone(25, naturalIsLandscape = false))
        assertFalse(LaunchOrientationMonitor.isPortraitDeadzone(26, naturalIsLandscape = false))
        assertFalse(LaunchOrientationMonitor.isPortraitDeadzone(334, naturalIsLandscape = false))
        assertTrue(LaunchOrientationMonitor.isPortraitDeadzone(335, naturalIsLandscape = false))
        assertFalse(LaunchOrientationMonitor.isPortraitDeadzone(154, naturalIsLandscape = false))
        assertTrue(LaunchOrientationMonitor.isPortraitDeadzone(155, naturalIsLandscape = false))
        assertTrue(LaunchOrientationMonitor.isPortraitDeadzone(205, naturalIsLandscape = false))
        assertFalse(LaunchOrientationMonitor.isPortraitDeadzone(206, naturalIsLandscape = false))
    }

    @Test
    fun sensorCacheHoldsBriefTiltAndRespectsExactTtlBoundary() {
        LaunchOrientationMonitor.recordSensorSample(degrees = 92, nowMs = 1_000L, naturalIsLandscape = false)
        assertEquals(92, LaunchOrientationMonitor.recentLandscapeDegrees(1_500L))

        // Exact TTL boundary: 3000ms inclusive is still valid; 3001ms expires.
        assertEquals(92, LaunchOrientationMonitor.recentLandscapeDegrees(4_000L))
        assertNull(LaunchOrientationMonitor.recentLandscapeDegrees(4_001L))

        // Unknown (lying flat) or slight tilt in hysteresis band (35 deg) preserves recent landscape side briefly.
        LaunchOrientationMonitor.recordSensorSample(degrees = 92, nowMs = 5_000L, naturalIsLandscape = false)
        LaunchOrientationMonitor.recordSensorSample(
            degrees = OrientationEventListener.ORIENTATION_UNKNOWN,
            nowMs = 5_200L,
            naturalIsLandscape = false,
        )
        LaunchOrientationMonitor.recordSensorSample(degrees = 35, nowMs = 5_400L, naturalIsLandscape = false)
        assertEquals(92, LaunchOrientationMonitor.recentLandscapeDegrees(5_500L))

        // Returning upright clears cached landscape reading immediately.
        LaunchOrientationMonitor.recordSensorSample(degrees = 5, nowMs = 5_600L, naturalIsLandscape = false)
        assertNull(LaunchOrientationMonitor.recentLandscapeDegrees(5_700L))

        // Background stop clears cached orientation state.
        LaunchOrientationMonitor.recordSensorSample(degrees = 90, nowMs = 6_000L, naturalIsLandscape = false)
        assertEquals(90, LaunchOrientationMonitor.recentLandscapeDegrees(6_100L))
        LaunchOrientationMonitor.onBackgroundStop()
        assertNull(LaunchOrientationMonitor.recentLandscapeDegrees(6_200L))
    }

    @Test
    fun landscapeNaturalDeviceInPortraitUsesTabletSensorSectors() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
            LaunchOrientationMonitor.resolveOrientationFromSignals(
                displayOrientation = Configuration.ORIENTATION_PORTRAIT,
                displayRotation = Surface.ROTATION_90,
                sensorDegrees = 180,
            ),
        )
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            LaunchOrientationMonitor.resolveOrientationFromSignals(
                displayOrientation = Configuration.ORIENTATION_PORTRAIT,
                displayRotation = Surface.ROTATION_90,
                sensorDegrees = 350,
            ),
        )
        LaunchOrientationMonitor.recordSensorSample(degrees = 180, nowMs = 1_000L, naturalIsLandscape = true)
        assertEquals(180, LaunchOrientationMonitor.recentLandscapeDegrees(1_100L))
        LaunchOrientationMonitor.recordSensorSample(degrees = 90, nowMs = 1_200L, naturalIsLandscape = true)
        assertNull(LaunchOrientationMonitor.recentLandscapeDegrees(1_300L))
    }

    @Test
    fun accelerometerVectorConvertsAndRejectsInvalidOrFlatVectors() {
        assertEquals(0, LaunchOrientationMonitor.degreesFromAccelerometer(0f, 9.8f, 0f))
        assertEquals(180, LaunchOrientationMonitor.degreesFromAccelerometer(0f, -9.8f, 0f))
        assertEquals(270, LaunchOrientationMonitor.degreesFromAccelerometer(9.8f, 0f, 0f))
        assertEquals(90, LaunchOrientationMonitor.degreesFromAccelerometer(-9.8f, 0f, 0f))
        assertEquals(
            OrientationEventListener.ORIENTATION_UNKNOWN,
            LaunchOrientationMonitor.degreesFromAccelerometer(0f, 0f, 9.8f),
        )
        assertEquals(
            OrientationEventListener.ORIENTATION_UNKNOWN,
            LaunchOrientationMonitor.degreesFromAccelerometer(0f, 0f, -9.8f),
        )
        assertEquals(
            OrientationEventListener.ORIENTATION_UNKNOWN,
            LaunchOrientationMonitor.degreesFromAccelerometer(0f, 0f, 0f),
        )
        assertEquals(
            OrientationEventListener.ORIENTATION_UNKNOWN,
            LaunchOrientationMonitor.degreesFromAccelerometer(Float.NaN, 0f, 0f),
        )
        assertEquals(
            OrientationEventListener.ORIENTATION_UNKNOWN,
            LaunchOrientationMonitor.degreesFromAccelerometer(0f, Float.POSITIVE_INFINITY, 0f),
        )
    }

    @Test
    fun externalContractNormalizesOnlyConcreteLandscapeValues() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            ExternalEngineContract.normalizeOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE),
        )
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
            ExternalEngineContract.normalizeOrientation(ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE),
        )
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            ExternalEngineContract.normalizeOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE),
        )
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            ExternalEngineContract.normalizeOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT),
        )
    }

    @Test
    fun externalContractNormalizationDelegatesToEnginePureFunction() {
        // P3-2 收口：app 侧外置契约与 engine 纯函数必须恒一致，防止两份归一化漂移。
        val values = listOf(
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT,
            ActivityInfo.SCREEN_ORIENTATION_FULL_USER,
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
        )
        values.forEach { value ->
            assertEquals(
                EnginePlayerOrientation.normalizeLandscapeOrientation(value),
                ExternalEngineContract.normalizeOrientation(value),
            )
        }
    }

    @Test
    fun displayStateCacheIsStaleWhenClearedOrPastTtl() {
        // P2-2 缓存失效语义：缓存被显示事件/退后台清理（null）→ 必须重算。
        assertTrue(
            LaunchOrientationMonitor.isDisplayCacheStale(capturedAtMs = null, nowMs = 10_000L, ttlMs = 1_000L),
        )
        // TTL 内复用缓存（回调路径零显示查询）。
        assertFalse(
            LaunchOrientationMonitor.isDisplayCacheStale(capturedAtMs = 9_500L, nowMs = 10_000L, ttlMs = 1_000L),
        )
        // 精确到 TTL 边界过期（>= ttl 即失效）；超过 TTL 过期。
        assertTrue(
            LaunchOrientationMonitor.isDisplayCacheStale(capturedAtMs = 9_000L, nowMs = 10_000L, ttlMs = 1_000L),
        )
        assertTrue(
            LaunchOrientationMonitor.isDisplayCacheStale(capturedAtMs = 8_999L, nowMs = 10_000L, ttlMs = 1_000L),
        )
    }

    @Test
    fun probeConditionCoversFreshSnapshotWithoutLandscapeSample() {
        // LOGIC-03 窗口：刚记录过竖屏死区样本但无横屏样本时，
        // sensorDegrees 为 null 仍允许后台线程的一次瞬时探测。
        LaunchOrientationMonitor.recordSensorSample(degrees = 5, nowMs = 1_000L, naturalIsLandscape = false)
        val degreesAfterFreshPortraitSnapshot = LaunchOrientationMonitor.recentLandscapeDegrees(1_100L)
        assertNull(degreesAfterFreshPortraitSnapshot)
        assertTrue(
            LaunchOrientationMonitor.shouldProbeAccelerometer(
                sensorDegrees = degreesAfterFreshPortraitSnapshot,
                installed = true,
                onMainThread = false,
            ),
        )
        // 主线程绝不探测。
        assertFalse(
            LaunchOrientationMonitor.shouldProbeAccelerometer(
                sensorDegrees = null,
                installed = true,
                onMainThread = true,
            ),
        )
        // 未安装方向监听的进程不探测（如引擎子进程或监听安装失败时）。
        assertFalse(
            LaunchOrientationMonitor.shouldProbeAccelerometer(
                sensorDegrees = null,
                installed = false,
                onMainThread = false,
            ),
        )
        // 已有可用横屏样本时不重复探测。
        LaunchOrientationMonitor.recordSensorSample(degrees = 90, nowMs = 1_200L, naturalIsLandscape = false)
        val degreesWithLandscapeSample = LaunchOrientationMonitor.recentLandscapeDegrees(1_300L)
        assertEquals(90, degreesWithLandscapeSample)
        assertFalse(
            LaunchOrientationMonitor.shouldProbeAccelerometer(
                sensorDegrees = degreesWithLandscapeSample,
                installed = true,
                onMainThread = false,
            ),
        )
    }

    @Test
    fun accelerometerFlatnessBoundaryAcceptsAtEqualityLikeAosp() {
        // P3-9：平放拒绝边界对齐 AOSP OrientationEventListener（接受条件 magnitude * 4 >= Z*Z）。
        // x=1,y=0,z=2 时 |z|² 恰等于 4·|xy|²，等值点接受（修复前为 UNKNOWN），角度归一化为 270；
        // 反向同构造归一化为 90。
        assertEquals(270, LaunchOrientationMonitor.degreesFromAccelerometer(1f, 0f, 2f))
        assertEquals(90, LaunchOrientationMonitor.degreesFromAccelerometer(-1f, 0f, 2f))
        // 严格超过阈值仍拒绝（接近平放、屏幕方向无意义）。
        assertEquals(
            OrientationEventListener.ORIENTATION_UNKNOWN,
            LaunchOrientationMonitor.degreesFromAccelerometer(1f, 0f, 3f),
        )
        assertEquals(
            OrientationEventListener.ORIENTATION_UNKNOWN,
            LaunchOrientationMonitor.degreesFromAccelerometer(0f, 0f, 5f),
        )
    }
}
