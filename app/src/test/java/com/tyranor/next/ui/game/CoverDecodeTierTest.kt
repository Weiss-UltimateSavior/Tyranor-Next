package com.tyranor.next.ui.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 封面解码档位 / 几何钉住：缓存 key 必须带档位与 0.05 分桶的宽高比，
 * 避免同一 URI 的网格缩略图、封面流大图与 1:1 首页卡互相覆盖；
 * 裁剪必须按消费方盒子居中，不能把所有卡都预裁成 3:4。
 */
class CoverDecodeTierTest {

    @Test
    fun `按卡片宽度选择解码档位`() {
        assertEquals(CoverDecodeTier.GRID, CoverDecodeTier.forWidth(180))
        assertEquals(CoverDecodeTier.GRID, CoverDecodeTier.forWidth(384))
        assertEquals(CoverDecodeTier.CARD, CoverDecodeTier.forWidth(500))
        assertEquals(CoverDecodeTier.CARD, CoverDecodeTier.forWidth(512))
        assertEquals(CoverDecodeTier.FLOW, CoverDecodeTier.forWidth(648))
        assertEquals(CoverDecodeTier.FLOW, CoverDecodeTier.forWidth(768))
        assertEquals(CoverDecodeTier.FLOW_LARGE, CoverDecodeTier.forWidth(1182))
    }

    @Test
    fun `缓存 key 按档位与宽高比分桶且保留原 uri`() {
        val uri = "content://cover/test-tier"
        assertNotEquals(CoverDecodeTier.GRID.cacheKey(uri), CoverDecodeTier.FLOW.cacheKey(uri))
        assertTrue(CoverDecodeTier.GRID.cacheKey(uri).endsWith(uri))
        assertTrue(CoverDecodeTier.FLOW.cacheKey(uri).startsWith("flow:a0.75:"))
        assertNotEquals(CoverDecodeTier.GRID.cacheKey(uri, 1f), CoverDecodeTier.GRID.cacheKey(uri))
        assertTrue(CoverDecodeTier.GRID.cacheKey(uri, 1f).contains(":a1.00:"))
        // 0.05 分桶：0.74 与 0.76 都落到 0.75，避免浮点噪声撑大缓存 key 空间
        assertEquals(
            CoverDecodeTier.GRID.cacheKey(uri, 0.74f),
            CoverDecodeTier.GRID.cacheKey(uri, 0.76f),
        )
        assertNotEquals(
            CoverDecodeTier.GRID.cacheKey(uri, 0.75f),
            CoverDecodeTier.GRID.cacheKey(uri, 0.80f),
        )
    }

    @Test
    fun `目标比例按 0_05 分桶`() {
        assertEquals(0.75f, quantizedCoverAspect(0.7496f), 0.0001f)
        assertEquals(0.75f, quantizedCoverAspect(0.76f), 0.0001f)
        assertEquals(0.80f, quantizedCoverAspect(0.78f), 0.0001f)
        assertEquals(1f, quantizedCoverAspect(1f), 0.0001f)
        assertEquals(0.75f, quantizedCoverAspect(0f), 0.0001f)
    }

    @Test
    fun `裁剪几何按目标比例居中且不越界`() {
        val same = coverCropRect(1000, 1333, 0.75f)!!
        assertEquals(0, same.left)
        assertEquals(0, same.top)
        assertEquals(1000, same.width)
        assertEquals(1333, same.height)

        // 60000×600 的横幅裁成 3:4：保留中间 450 宽
        val wide = coverCropRect(60000, 600, 0.75f)!!
        assertEquals(450, wide.width)
        assertEquals(600, wide.height)
        assertEquals((60000 - 450) / 2, wide.left)
        assertEquals(0, wide.top)

        // 3:4 竖图裁成 1:1：保留中间 1000 高
        val square = coverCropRect(1000, 1500, 1f)!!
        assertEquals(1000, square.width)
        assertEquals(1000, square.height)
        assertEquals(0, square.left)
        assertEquals(250, square.top)

        assertNull(coverCropRect(0, 100, 0.75f))
        assertNull(coverCropRect(100, 0, 0.75f))
    }

    @Test
    fun `目标尺寸适配档位盒`() {
        assertEquals(384 to 512, coverTargetSize(CoverDecodeTier.GRID, 0.75f))
        assertEquals(307 to 512, coverTargetSize(CoverDecodeTier.GRID, 0.6f))
        assertEquals(512 to 512, coverTargetSize(CoverDecodeTier.CARD, 1f))
    }

    @Test
    fun `采样同时受目标尺寸与字节预算约束`() {
        // 常见 3:4 图：采样 2 后仍大于目标，但下一档会低于目标，取 2
        assertEquals(2, coverSampleSize(1000, 1333, CoverDecodeTier.GRID))
        // 小于目标：不主动降采样
        assertEquals(1, coverSampleSize(400, 600, CoverDecodeTier.GRID))
        // 60000×600 横幅：尺寸条件只能取 1（高 600 < 2×512），字节预算迫使继续降到 4
        assertEquals(4, coverSampleSize(60000, 600, CoverDecodeTier.GRID))
        assertTrue(60000L / 4 * (600L / 4) * 4 <= CoverDecodeBudgetBytes)
    }
}
