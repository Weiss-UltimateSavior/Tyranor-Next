package com.tyranor.next.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 背景图采样档位钉住：长边预算与解码字节预算必须同时满足。
 *
 * 背景图会常驻进程级缓存并参与整屏模糊，超预算的位图在 API 29 的绘制上限 /
 * Android 16 的硬件位图上限下会直接绘制失败（Canvas: trying to draw too large）。
 */
class BackgroundSampleSizeTest {

    private val maxDecodedBytes = 64L * 1024 * 1024
    private val bytesPerPixel = 4L

    @Test
    fun `大图按字节预算继续降采样`() {
        // 3200 长边屏幕上 6000×6000 的长边仍在 2× 以内，但原尺寸是 144MiB；
        // 必须再降一档到 3000×3000（36MiB）
        assertEquals(2, backgroundSampleSize(width = 6000, height = 6000, maxEdge = 3200))
    }

    @Test
    fun `极端宽高比按长边降采样`() {
        // 原实现要求宽高同时超限，30000×2000 会原尺寸解码（约 229MiB）；
        // 按长边预算降到 3750×250（约 3.8MiB）
        assertEquals(8, backgroundSampleSize(width = 30000, height = 2000, maxEdge = 2400))
    }

    @Test
    fun `常见的屏幕尺寸图不降采样`() {
        assertEquals(1, backgroundSampleSize(width = 1080, height = 1920, maxEdge = 1080))
        assertEquals(1, backgroundSampleSize(width = 1440, height = 3200, maxEdge = 3200))
    }

    @Test
    fun `采样结果同时满足长边与字节预算`() {
        val cases = listOf(
            Triple(6000, 6000, 3200),
            Triple(30000, 2000, 2400),
            Triple(4000, 4000, 1080),
            Triple(20000, 1000, 1440),
            Triple(1080, 1920, 1080),
        )
        for ((width, height, maxEdge) in cases) {
            val sample = backgroundSampleSize(width, height, maxEdge)
            val sampledWidth = width.toLong() / sample
            val sampledHeight = height.toLong() / sample
            assertTrue(
                "长边超预算: $width×$height @$maxEdge sample=$sample",
                maxOf(sampledWidth, sampledHeight) < 2L * maxEdge,
            )
            assertTrue(
                "字节超预算: $width×$height @$maxEdge sample=$sample",
                sampledWidth * sampledHeight * bytesPerPixel <= maxDecodedBytes,
            )
        }
    }
}
