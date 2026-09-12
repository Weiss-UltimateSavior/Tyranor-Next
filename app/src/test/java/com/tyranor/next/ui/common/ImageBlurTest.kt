package com.tyranor.next.ui.common

import org.junit.Assert.assertTrue
import org.junit.Test

class ImageBlurTest {

    private fun argb(a: Int, r: Int, g: Int, b: Int): Int = (a shl 24) or (r shl 16) or (g shl 8) or b

    private fun redOf(color: Int): Int = (color ushr 16) and 0xFF

    private fun alphaOf(color: Int): Int = (color ushr 24) and 0xFF

    @Test
    fun uniformImageStaysUniform() {
        val pixels = IntArray(6 * 4) { argb(255, 128, 128, 128) }
        boxBlurArgb(pixels, 6, 4, radius = 2)
        assertTrue(pixels.all { it == argb(255, 128, 128, 128) })
    }

    @Test
    fun radiusZeroIsNoOp() {
        val pixels = IntArray(3 * 3) { argb(255, it * 10, 0, 0) }
        val copy = pixels.copyOf()
        boxBlurArgb(pixels, 3, 3, radius = 0)
        assertTrue(pixels.contentEquals(copy))
    }

    @Test
    fun singleBrightPixelSpreadsToNeighbors() {
        val width = 9
        val height = 9
        val pixels = IntArray(width * height) { argb(255, 0, 0, 0) }
        pixels[4 * width + 4] = argb(255, 255, 255, 255)

        boxBlurArgb(pixels, width, height, radius = 2)

        val center = redOf(pixels[4 * width + 4])
        val neighbor = redOf(pixels[4 * width + 3])
        assertTrue("center should dim after blur: $center", center < 255)
        assertTrue("neighbor should receive light: $neighbor", neighbor > 0)
        assertTrue("alpha must stay opaque", pixels.all { alphaOf(it) == 255 })
    }
}
