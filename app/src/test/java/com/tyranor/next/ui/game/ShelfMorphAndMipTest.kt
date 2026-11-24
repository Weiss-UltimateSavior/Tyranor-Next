package com.tyranor.next.ui.game

import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.game.model.ScanGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 书架封面的横向预缩档位（转得越侧越要用更窄的预缩图）与藏书阁切换陈列后首屏的对齐滚动位置：
 * 只测数值性质（单调、边界、按规则换算），不涉及绘制。
 */
class ShelfMorphAndMipTest {

    @Test
    fun coverMipLevelGrowsAsTheFaceTurnsAwayAndIsCapped() {
        val levels = listOf(0f, 20f, 45f, 62f, 78f, 86f, 89.9f)
            .map { shelfCoverMipLevel(Math.toRadians(it.toDouble()).toFloat()) }
        assertEquals(0, levels.first())
        for (i in 1 until levels.size) assertTrue(levels[i] >= levels[i - 1])
        assertEquals(SHELF_COVER_MIPS, levels.last())
        // 左右转向一样
        assertEquals(
            shelfCoverMipLevel(Math.toRadians(70.0).toFloat()),
            shelfCoverMipLevel(Math.toRadians(-70.0).toFloat()),
        )
    }

    @Test
    fun coverMipLevelPicksTheChainStepBelowTwiceTheProjectedWidth() {
        // 正面压到略窄于一半宽（62°）落在第 1 档：预缩图宽 = 原宽 / 2，横向缩小不到 2 倍
        assertEquals(1, shelfCoverMipLevel(Math.toRadians(62.0).toFloat()))
        // 压到约四分之一宽（75.5°）落在第 2 档
        assertEquals(2, shelfCoverMipLevel(Math.toRadians(76.0).toFloat()))
        // 还没压到一半宽，用原图
        assertEquals(0, shelfCoverMipLevel(Math.toRadians(55.0).toFloat()))
    }

    @Test
    fun coverCropRectFillsTheFaceAndCentersTheOverflow() {
        // 同宽高比：恰好铺满
        val exact = shelfCoverCropRect(300, 400, 120f, 160f)
        assertEquals(0f, exact.left, 0.01f)
        assertEquals(0f, exact.top, 0.01f)
        assertEquals(120f, exact.width, 0.01f)
        assertEquals(160f, exact.height, 0.01f)
        // 图比封面面更宽：高度铺满，左右均分溢出
        val wide = shelfCoverCropRect(400, 400, 120f, 160f)
        assertEquals(160f, wide.height, 0.01f)
        assertEquals(160f, wide.width, 0.01f)
        assertEquals(-20f, wide.left, 0.01f)
        // 图比封面面更窄：宽度铺满，上下均分溢出
        val tall = shelfCoverCropRect(300, 600, 120f, 160f)
        assertEquals(120f, tall.width, 0.01f)
        assertEquals(240f, tall.height, 0.01f)
        assertEquals(-40f, tall.top, 0.01f)
    }

    private fun game(i: Int) = ScanGame(
        title = "Game $i",
        uri = "content://games/$i",
        engine = EngineType.KIRIKIRI,
        launchTarget = "data.xp3",
    )

    private fun tiers(count: Int, columns: Int): List<ShelfCatalogRowItem> =
        buildShelfCatalogVerticalRows((0 until count).map(::game), columns, groupByEngine = false)

    @Test
    fun alignedScrollPutsTheAnchorCellCenterAtTheRequestedHeight() {
        val rows = tiers(count = 40, columns = 4) // 10 层，每层 100px
        val anchor = "content://games/22" // 第 5 层（下标 5）
        val (index, offset) = shelfCatalogAlignedScroll(rows, anchor, anchorY = 300f, pitchPx = 100f, cellCenterPx = 60f)!!
        // 锚点层顶部在视口里的位置 = (层号 - 首个可见层) * 100 - 偏移 + 0；中心在 +60，应落在 300
        val centerY = (5 - index) * 100f - offset + 60f
        assertEquals(300f, centerY, 0.5f)
    }

    @Test
    fun alignedScrollNeverScrollsAboveTheTop() {
        val rows = tiers(count = 40, columns = 4)
        // 锚点在第一层、要求落得很靠下：不能滚到负数
        val result = shelfCatalogAlignedScroll(rows, "content://games/1", anchorY = 900f, pitchPx = 100f, cellCenterPx = 60f)!!
        assertEquals(0 to 0, result)
    }

    @Test
    fun alignedScrollGivesUpWhenSectionHeadersMakeRowHeightsIrregular() {
        val games = (0 until 6).map { game(it).copy(engine = if (it < 3) EngineType.KIRIKIRI else EngineType.RENPY) }
        val grouped = buildShelfCatalogVerticalRows(games, columns = 4, groupByEngine = true)
        assertTrue(grouped.any { it is ShelfCatalogRowItem.SectionHeader })
        assertNull(shelfCatalogAlignedScroll(grouped, "content://games/4", 300f, 100f, 60f))
        assertNotNull(shelfCatalogAlignedScroll(tiers(8, 4), "content://games/4", 300f, 100f, 60f))
        assertNull(shelfCatalogAlignedScroll(tiers(8, 4), null, 300f, 100f, 60f))
        assertNull(shelfCatalogAlignedScroll(tiers(8, 4), "content://games/4", null, 100f, 60f))
    }

}
