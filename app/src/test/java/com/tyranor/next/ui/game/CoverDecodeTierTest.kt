package com.tyranor.next.ui.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 封面解码档位钉住：3/6 列网格与首页卡片复用网格档，封面流按实际卡片宽度升档，
 * 缓存 key 必须带档位，避免同一 URI 的网格缩略图与封面流大图互相覆盖。
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
    fun `缓存 key 按档位隔离且保留原 uri`() {
        val uri = "content://cover/test-tier"
        assertNotEquals(CoverDecodeTier.GRID.cacheKey(uri), CoverDecodeTier.FLOW.cacheKey(uri))
        assertTrue(CoverDecodeTier.GRID.cacheKey(uri).endsWith(uri))
        assertTrue(CoverDecodeTier.FLOW.cacheKey(uri).startsWith("flow:"))
    }
}
