package com.tyranor.next.ui.game

import com.tyranor.next.core.engine.EngineType
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.ui.common.BOOK_SLIDER_EDGE_INSET
import com.tyranor.next.ui.common.sliderFractionFromPosition
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun staleCompositionSeesTheFinishedMorphInsteadOfTheResetProgress() {
        // 接力切换：新一轮把进度归零（0f），但还没重组的旧组合 / 上一轮的卡片必须按「已放完」画，否则封面书柜会闪一帧
        assertEquals(1f, shelfCatalogMorphProgressSeen(seenKey = 2, currentKey = 3, progress = 0f), 0f)
        assertEquals(1f, shelfCatalogMorphProgressSeen(seenKey = 1, currentKey = 3, progress = 0.4f), 0f)
        // 同一轮（新组合、本轮的卡片）读真实进度
        assertEquals(0f, shelfCatalogMorphProgressSeen(seenKey = 3, currentKey = 3, progress = 0f), 0f)
        assertEquals(0.37f, shelfCatalogMorphProgressSeen(seenKey = 3, currentKey = 3, progress = 0.37f), 0f)
    }

    @Test
    fun handoffWaitsUntilTheFlyingBookIsWithinAFewPixelsOfItsLanding() {
        // 近距离的书（行程不长）仍从 92% 起浮现，与原来一致
        assertEquals(0.92f, shelfCatalogHandoffStartFor(travelPx = 40f, residualPx = 4.3f), 1e-4f)
        // 远距离 / 形变大的书（书脊长成封面，几百 px）等到剩余距离降到 residualPx 以内才开始浮现，不再带着重影
        val far = shelfCatalogHandoffStartFor(travelPx = 650f, residualPx = 4.3f)
        assertTrue(far > 0.99f)
        assertEquals(1f - 4.3f / 650f, far, 1e-4f)
        // 行程再大也至少留一点交接的行程，不会等到 1 才一下子冒出来
        assertEquals(0.997f, shelfCatalogHandoffStartFor(travelPx = 100000f, residualPx = 4.3f), 1e-4f)
        // 没有有效行程（异常输入）退回默认起点
        assertEquals(0.92f, shelfCatalogHandoffStartFor(travelPx = 0f, residualPx = 4.3f), 1e-4f)
        assertEquals(0.92f, shelfCatalogHandoffStartFor(travelPx = Float.NaN, residualPx = 4.3f), 1e-4f)
    }

    @Test
    fun handoffDistanceCountsTheFlightArcNotJustTheEndpoints() {
        // 审查给的例子：density 3、中心横向差 40px、纵向与尺寸都相同、书高 1200px、无错峰
        fun flight(arcLiftPx: Float): ShelfCatalogBookFlight {
            val origin = ShelfCatalogBookOrigin(
                uri = "u",
                pageIndex = 0,
                frontRect = Rect(0f, 0f, 800f, 1200f),
                spineRect = Rect(500f, 100f, 560f, 1300f),
                spineThicknessPx = 60f,
                boxHeightPx = 1200f,
                coverWidthPx = 800f,
                yawDegrees = 0f,
                tiltDegrees = 0f,
                tint = Color.Gray,
                isHero = false,
            )
            return ShelfCatalogBookFlight(
                game = game(0),
                spineIndex = 0,
                shelfOrigin = origin,
                // 与 spineRect 同高（1200）、中心横向差 40px，纵向中心相同
                catalogRect = Rect(540f, 100f, 600f, 1300f),
                catalogMode = ShelfCatalogLayoutMode.SPINES,
                delayFraction = 0f,
                arcLiftPx = arcLiftPx,
                returningToShelf = true,
                isCurrentInCatalog = false,
                zPriority = 0f,
            )
        }
        // 没有弧线的短行程保持原有的 92%
        assertEquals(0.92f, shelfCatalogHandoffStart(listOf(flight(0f)), density = 3f), 1e-4f)
        // 弧线 60px：用实际飞行姿态量——交接起点处飞行的书离落点不超过阈值（4.5px），而原来固定的 92% 处还差得很远
        val withArcFlight = flight(60f)
        val withArc = shelfCatalogHandoffStart(listOf(withArcFlight), density = 3f)
        val landing = shelfCatalogBookPose(1f, withArcFlight)
        fun distanceAt(progress: Float): Float {
            val pose = shelfCatalogBookPose(progress, withArcFlight)
            return kotlin.math.hypot(pose.spineCenterX - landing.spineCenterX, pose.spineCenterY - landing.spineCenterY)
        }
        assertTrue("交接起点处残余 ${distanceAt(withArc)}px 超过阈值", distanceAt(withArc) <= 4.5f + 0.05f)
        assertTrue("固定 92% 时残余 ${distanceAt(0.92f)}px 本应远超阈值（回归保护）", distanceAt(0.92f) > 4.5f * 2)
        // 也不能晚到几乎落位才开始交接
        assertTrue(withArc < 0.999f)
    }

    @Test
    fun handoffAlphaRampsFromTheGivenStartAndKeepsTheDefaultBehaviour() {
        val start = 0.99f
        assertEquals(0f, shelfCatalogHandoffAlpha(0.95f, start), 0f)
        assertEquals(0f, shelfCatalogHandoffAlpha(start, start), 0f)
        assertEquals(0.5f, shelfCatalogHandoffAlpha(0.995f, start), 1e-4f)
        assertEquals(1f, shelfCatalogHandoffAlpha(1f, start), 1e-4f)
        // 弹簧略冲过 1 时仍是 1
        assertEquals(1f, shelfCatalogHandoffAlpha(1.02f, start), 0f)
        // 不传起点就是原来的 92%
        assertEquals(shelfCatalogHandoffAlpha(0.96f, 0.92f), shelfCatalogHandoffAlpha(0.96f), 0f)
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
        val rows = tiers(count = 40, columns = 4)
        val anchor = "content://games/22"
        val result = shelfCatalogAlignedScroll(rows, anchor, anchorY = 300f, cellCenterPx = 60f, topPaddingPx = 6)
        assertEquals(5 to -234, result)
    }

    @Test
    fun alignedScrollNeverScrollsAboveTheTop() {
        val rows = tiers(count = 40, columns = 4)
        // 锚点在第一层、要求落得很靠下：不能滚到负数
        val result = shelfCatalogAlignedScroll(rows, "content://games/1", anchorY = 900f, cellCenterPx = 60f, topPaddingPx = 6)
        assertEquals(0 to 0, result)
    }

    @Test
    fun alignedScrollRequiresBothAnchorAndHeight() {
        val rows = tiers(8, 4)
        assertNull(shelfCatalogAlignedScroll(rows, null, 300f, 60f, 6))
        assertNull(shelfCatalogAlignedScroll(rows, "content://games/4", null, 60f, 6))
    }

    @Test
    fun extrapolateRectUsesPassedBatchSnapshotInsteadOfUpdatedFilteredGames() = runTest {
        val initialBatch = (0 until 12).map(::game)
        val stage = ShelfCatalogStage()
        val motion = ShelfCatalogMotion(
            scope = this,
            enhanced = true,
            stage = stage,
            initialAnchorUri = initialBatch.first().uri,
            initialLayoutMode = ShelfCatalogLayoutMode.COVERS,
        )
        motion.syncCatalogGames(initialBatch, initialBatch)
        motion.reportGrid(
            ShelfCatalogLayoutMode.COVERS,
            ShelfCatalogGridMetrics(
                columns = 4,
                pitchX = 100f,
                pitchY = 200f,
                grouped = false,
                maxBookHeightPx = 150f,
                density = 1f,
            ),
        )
        val indexByUri = initialBatch.withIndex().associate { (i, g) -> g.uri to i }
        val targetIdx = 8
        val targetUri = initialBatch[targetIdx].uri
        val refRect = Rect(0f, 0f, 90f, 150f * shelfCatalogCoverHeightFactor(initialBatch[0].uri))
        val measured = mapOf(
            initialBatch[0].uri to CatalogSlotSnapshot(
                rect = refRect,
                layoutMode = ShelfCatalogLayoutMode.COVERS,
                rowGridIndex = 0,
                colIndex = 0,
                leanDegrees = 0f,
            ),
        )

        val expectedBefore = motion.extrapolateRect(
            measured = measured,
            mode = ShelfCatalogLayoutMode.COVERS,
            index = targetIdx,
            indexMap = indexByUri,
            games = initialBatch,
        )!!

        // 搜索/筛选移除了首项，原第 8 项移动到了新列表的下标 7
        val shiftedBatch = initialBatch.drop(1)
        motion.syncCatalogGames(shiftedBatch, initialBatch)

        val afterShift = motion.extrapolateRect(
            measured = measured,
            mode = ShelfCatalogLayoutMode.COVERS,
            index = targetIdx,
            indexMap = indexByUri,
            games = initialBatch,
        )!!
        assertEquals(expectedBefore, afterShift)
        assertEquals(150f * shelfCatalogCoverHeightFactor(targetUri), afterShift.height, 1e-3f)
        assertFalse(motion.morphing)
    }

    @Test
    fun bookSliderHitPositionMatchesDrawBookSliderInset() {
        // 宽度 210dp、书页 7dp 内缩、fraction=0.25 时绘制圆钮中心位于 7 + 0.25 * (210 - 14) = 56dp
        val inset = BOOK_SLIDER_EDGE_INSET.value
        val width = 210f
        val thumbCenterAtQuarter = inset + 0.25f * (width - 2f * inset)
        assertEquals(56f, thumbCenterAtQuarter, 1e-4f)
        assertEquals(0.25f, sliderFractionFromPosition(thumbCenterAtQuarter, width, inset, reverse = false), 1e-5f)

        // 书页离散滑条：宽 200dp、steps=19（0.1 刻度在 7 + 0.1 * 186 = 25.6dp）
        val tickWidth = 200f
        val tickAtTenth = inset + 0.1f * (tickWidth - 2f * inset)
        assertEquals(25.6f, tickAtTenth, 1e-4f)
        assertEquals(0.1f, sliderFractionFromPosition(tickAtTenth, tickWidth, inset, reverse = false), 1e-5f)

        // 书外分支（14dp 半高内缩）保持原有几何
        val outsideInset = 14f
        val outsideThumbCenter = outsideInset + 0.25f * (width - 2f * outsideInset)
        assertEquals(0.25f, sliderFractionFromPosition(outsideThumbCenter, width, outsideInset, reverse = false), 1e-5f)
    }

}
