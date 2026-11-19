package com.tyranor.next.core.game.launch

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.OrientationEventListener
import android.view.Surface
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
}
