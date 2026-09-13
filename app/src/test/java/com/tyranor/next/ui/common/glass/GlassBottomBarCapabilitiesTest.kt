package com.tyranor.next.ui.common.glass

import org.junit.Assert.assertEquals
import org.junit.Test

/** 能力分档：低版本一律降级，且能力判定与「用户是否开启」完全独立（报告 §8.9）。 */
class GlassBottomBarCapabilitiesTest {

    @Test
    fun api26to30_hasNeitherBlurNorRefraction() {
        for (sdk in listOf(26, 28, 30)) {
            val caps = GlassBottomBarCapabilities.of(sdk)
            assertEquals(false, caps.supportsBlur)
            assertEquals(false, caps.supportsRefraction)
        }
    }

    @Test
    fun api31to32_hasBlurButNoRefraction() {
        for (sdk in listOf(31, 32)) {
            val caps = GlassBottomBarCapabilities.of(sdk)
            assertEquals(true, caps.supportsBlur)
            assertEquals(false, caps.supportsRefraction)
        }
    }

    @Test
    fun api33AndAbove_hasBoth() {
        for (sdk in listOf(33, 34, 36)) {
            val caps = GlassBottomBarCapabilities.of(sdk)
            assertEquals(true, caps.supportsBlur)
            assertEquals(true, caps.supportsRefraction)
        }
    }
}
