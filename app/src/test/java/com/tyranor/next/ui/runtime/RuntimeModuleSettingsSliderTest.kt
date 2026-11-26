package com.tyranor.next.ui.runtime

import com.tyranor.next.core.engine.runtime.RuntimeModuleSettingItemSpec
import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimeModuleSettingsSliderTest {

    @Test
    fun decimalStepUsesDecimalArithmeticForValuesAndDisplay() {
        val item = RuntimeModuleSettingItemSpec(
            key = "slider",
            type = RuntimeModuleSettingItemSpec.Type.SLIDER,
            title = "Slider",
            defaultValue = 0.5,
            sliderMin = 0.5,
            sliderMax = 2.0,
            sliderStep = 0.1,
        )

        val selectedValue = runtimeModuleSliderValueAtIndex(item, 7)

        assertEquals(1.2, selectedValue, 0.0)
        assertEquals("1.2", formatRuntimeModuleSliderValue(item, 1.2000000000000002))
    }

    @Test
    fun steppedSliderMapsLargeValuesThroughTickIndices() {
        assertTickRoundTrip(min = 16_777_217.0, max = 16_777_223.0, step = 2.0)
        assertTickRoundTrip(min = 100_000_004.0, max = 100_000_084.0, step = 8.0)
    }

    private fun assertTickRoundTrip(min: Double, max: Double, step: Double) {
        val item = RuntimeModuleSettingItemSpec(
            key = "slider",
            type = RuntimeModuleSettingItemSpec.Type.SLIDER,
            title = "Slider",
            defaultValue = min,
            sliderMin = min,
            sliderMax = max,
            sliderStep = step,
        )
        val intervalCount = (max - min).div(step).toInt()

        assertEquals(intervalCount, runtimeModuleSliderIntervalCount(item))
        for (index in 0..intervalCount) {
            val expectedValue = min + index * step
            val value = runtimeModuleSliderValueAtIndex(item, index)
            assertEquals(expectedValue, value, 0.0)
            assertEquals(index, runtimeModuleSliderIndex(item, value))
        }
    }
}
