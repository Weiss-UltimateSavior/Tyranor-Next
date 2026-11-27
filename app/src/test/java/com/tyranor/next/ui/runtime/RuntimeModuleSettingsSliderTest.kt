package com.tyranor.next.ui.runtime

import com.tyranor.next.core.engine.runtime.RuntimeModuleSettingItemSpec
import com.tyranor.next.core.engine.runtime.RuntimeModuleSettingsParser
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

        val selectedValue = item.sliderValueAtIndex(7)

        assertEquals(15, item.sliderIntervalCount())
        assertEquals(1.2, selectedValue, 0.0)
        assertEquals("1.2", formatRuntimeModuleSliderValue(item, 1.2000000000000002))
    }

    @Test
    fun oneIntervalStepUsesTwoDiscreteEndpoints() {
        val item = requireNotNull(RuntimeModuleSettingsParser.parseItem(JSONObject(
            """{"key":"scale","type":"slider","title":"Scale","default":0,"min":0,"max":1,"step":1}""",
        )))

        assertEquals(1, item.sliderIntervalCount())
        assertEquals(0, item.sliderIndex(0.0))
        assertEquals(1, item.sliderIndex(1.0))
        assertEquals(0.0, item.sliderValueAtIndex(0), 0.0)
        assertEquals(1.0, item.sliderValueAtIndex(1), 0.0)
    }

    @Test
    fun continuousProgressKeepsFloatDecimalAndRejectsNonFiniteValues() {
        val item = requireNotNull(RuntimeModuleSettingsParser.parseItem(JSONObject(
            """{"key":"scale","type":"slider","title":"Scale","default":0.3,"min":0,"max":1}""",
        )))

        assertEquals(0.3, requireNotNull(runtimeModuleSliderValueFromProgress(item, 0.3f)), 0.0)
        assertEquals("0.3", formatRuntimeModuleSliderValue(item, requireNotNull(runtimeModuleSliderValueFromProgress(item, 0.3f))))
        assertNull(runtimeModuleSliderValueFromProgress(item, Float.NaN))
        assertNull(runtimeModuleSliderValueFromProgress(item, Float.POSITIVE_INFINITY))
    }

    @Test
    fun steppedSliderMapsLargeValuesThroughTickIndices() {
        assertTickRoundTrip(min = 16_777_217.0, max = 16_777_223.0, step = 2.0)
        assertTickRoundTrip(min = 100_000_004.0, max = 100_000_084.0, step = 8.0)
    }

    @Test
    fun largeValueSmallDecimalStepValidatesGeneratedTicksAndRejectsOffGridValues() {
        val item = RuntimeModuleSettingsParser.parseItem(JSONObject(
            """{"key":"scale","type":"slider","title":"Scale","default":1000000000,"min":1000000000,"max":1000000001,"step":0.001}""",
        ))
        assertNotNull(item)
        val slider = requireNotNull(item)
        val selectedValue = slider.sliderValueAtIndex(1)

        assertEquals(1_000_000_000.001, selectedValue, 0.0)
        assertTrue(RuntimeModuleSettingsParser.isValidValue(slider, selectedValue))
        assertFalse(
            RuntimeModuleSettingsParser.isValidValue(slider, 1_000_000_000.0015),
        )

        assertEquals(null, RuntimeModuleSettingsParser.parseItem(JSONObject(
            """{"key":"scale","type":"slider","title":"Scale","default":1000000000,"min":1000000000,"max":1000000001,"step":0.0003}""",
        )))

        for (index in 0..slider.sliderIntervalCount()!!) {
            val tick = slider.sliderValueAtIndex(index)
            assertTrue("generated tick $index must remain valid", RuntimeModuleSettingsParser.isValidValue(slider, tick))
        }
    }

    @Test
    fun steppedSliderUsesDecimalMathForWideRangesAndRejectsUnroundtrippableTicks() {
        val duplicateTicks = RuntimeModuleSettingsParser.parseItem(JSONObject(
            """{"key":"scale","type":"slider","title":"Scale","default":10000000000000000,"min":10000000000000000,"max":10000000000000010,"step":0.1}""",
        ))
        val generatedTicksOffGridAfterDoubleConversion = RuntimeModuleSettingsParser.parseItem(JSONObject(
            """{"key":"scale","type":"slider","title":"Scale","default":10000000000000000,"min":10000000000000000,"max":10000000000000012,"step":3}""",
        ))
        val rangeWhoseDoubleSpanOverflows = requireNotNull(RuntimeModuleSettingsParser.parseItem(JSONObject(
            """{"key":"scale","type":"slider","title":"Scale","default":-1e308,"min":-1e308,"max":1e308,"step":1e308}""",
        )))

        assertEquals(null, duplicateTicks)
        assertEquals(null, generatedTicksOffGridAfterDoubleConversion)
        assertEquals(2, rangeWhoseDoubleSpanOverflows.sliderIntervalCount())
        assertEquals(-1e308, rangeWhoseDoubleSpanOverflows.sliderValueAtIndex(0), 0.0)
        assertEquals(0.0, rangeWhoseDoubleSpanOverflows.sliderValueAtIndex(1), 0.0)
        assertEquals(1e308, rangeWhoseDoubleSpanOverflows.sliderValueAtIndex(2), 0.0)
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

        assertEquals(intervalCount, item.sliderIntervalCount())
        for (index in 0..intervalCount) {
            val expectedValue = min + index * step
            val value = item.sliderValueAtIndex(index)
            assertEquals(expectedValue, value, 0.0)
            assertEquals(index, item.sliderIndex(value))
        }
    }
}
