package com.core.engine

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.Surface
import org.junit.Assert.assertEquals
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
}
