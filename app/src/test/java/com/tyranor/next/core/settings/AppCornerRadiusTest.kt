package com.tyranor.next.core.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 应用统一圆角：十档取值与吸附归一（纯函数）。 */
class AppCornerRadiusTest {

    @Test
    fun stepsAreTenValuesFromFiveToFifty() {
        assertEquals(listOf(5, 10, 15, 20, 25, 30, 35, 40, 45, 50), AppSettingsStore.APP_CORNER_RADIUS_STEPS)
        assertEquals(10, AppSettingsStore.APP_CORNER_RADIUS_STEPS.size)
    }

    @Test
    fun defaultIsFifteen() {
        assertEquals(15, AppSettingsStore.DEFAULT_APP_CORNER_RADIUS)
        assertTrue(AppSettingsStore.DEFAULT_APP_CORNER_RADIUS in AppSettingsStore.APP_CORNER_RADIUS_STEPS)
    }

    @Test
    fun normalizeHandlesIntBounds() {
        assertEquals(5, AppSettingsStore.normalizeAppCornerRadius(Int.MIN_VALUE))
        assertEquals(50, AppSettingsStore.normalizeAppCornerRadius(Int.MAX_VALUE))
        assertEquals(5, AppSettingsStore.normalizeAppCornerRadius(-2147483603))
        assertEquals(50, AppSettingsStore.normalizeAppCornerRadius(51))
        assertEquals(50, AppSettingsStore.normalizeAppCornerRadius(52))
    }

    @Test
    fun everyInputNormalizesIntoSteps() {
        (-1000..1000).forEach { value ->
            assertTrue(
                "normalize($value) out of steps",
                AppSettingsStore.normalizeAppCornerRadius(value) in AppSettingsStore.APP_CORNER_RADIUS_STEPS,
            )
        }
    }

    @Test
    fun normalizeSnapsToNearestStep() {
        assertEquals(5, AppSettingsStore.normalizeAppCornerRadius(0))
        assertEquals(5, AppSettingsStore.normalizeAppCornerRadius(7))
        assertEquals(10, AppSettingsStore.normalizeAppCornerRadius(8))
        assertEquals(10, AppSettingsStore.normalizeAppCornerRadius(12))
        assertEquals(15, AppSettingsStore.normalizeAppCornerRadius(13))
        assertEquals(45, AppSettingsStore.normalizeAppCornerRadius(47))
        assertEquals(50, AppSettingsStore.normalizeAppCornerRadius(999))
        assertEquals(5, AppSettingsStore.normalizeAppCornerRadius(-3))
    }

    @Test
    fun everyStepRoundTrips() {
        AppSettingsStore.APP_CORNER_RADIUS_STEPS.forEach { step ->
            assertEquals(step, AppSettingsStore.normalizeAppCornerRadius(step))
        }
    }
}
