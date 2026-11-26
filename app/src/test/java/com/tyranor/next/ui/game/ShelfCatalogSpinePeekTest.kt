package com.tyranor.next.ui.game

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * 藏书阁「书脊立排」翻看纯几何与手势映射单测：
 * 封面宽高比与投影宽度、静止书格滞回选书与端点夹断、
 * 首本 / 末本 / 单本 / 满层在手机窄层与平板宽层下的邻书让位与整排左移不越界。
 */
class ShelfCatalogSpinePeekTest {

    @Test
    fun peekCoverWidthAndProjectedWidthMatchMainShelfGeometry() {
        val heightPx = 540f
        val spinePx = heightPx * SPINE_WIDTH_TO_HEIGHT_RATIO
        val coverPx = shelfCatalogPeekCoverWidthPx(heightPx)
        assertEquals(heightPx * SHELF_COVER_ASPECT_RATIO, coverPx, 1e-3f)

        assertEquals(90f, shelfCatalogPeekYawDegrees(0f), 1e-3f)
        assertEquals(HERO_YAW_DEGREES, shelfCatalogPeekYawDegrees(1f), 1e-3f)

        val closedW = shelfCatalogPeekProjectedWidthPx(spinePx, coverPx, 0f)
        assertEquals(spinePx, closedW, 1e-3f)
        assertEquals(0f, shelfCatalogPeekExtraWidthPx(spinePx, coverPx, 0f), 1e-3f)

        val openRad = Math.toRadians(HERO_YAW_DEGREES.toDouble()).toFloat()
        val expectedOpenW = spinePx * sin(openRad) + coverPx * cos(openRad)
        val openW = shelfCatalogPeekProjectedWidthPx(spinePx, coverPx, 1f)
        assertEquals(expectedOpenW, openW, 1e-2f)
        assertEquals(expectedOpenW - spinePx, shelfCatalogPeekExtraWidthPx(spinePx, coverPx, 1f), 1e-2f)

        var prevW = closedW
        for (step in 1..10) {
            val w = shelfCatalogPeekProjectedWidthPx(spinePx, coverPx, step / 10f)
            assertTrue("projected width should grow monotonically at step $step", w > prevW)
            prevW = w
        }
    }

    @Test
    fun resolveBookIndexAppliesHysteresisAndClampsToEndpoints() {
        val widths = listOf(90, 96, 88, 100)
        val gaps = listOf(6, 8, 6)
        val hysteresis = 12f

        // 书 1 在 [96, 192]，书 2 在 [200, 288]，交界中点 = 196
        assertEquals(0, resolveShelfCatalogPeekBookIndex(-40f, widths, gaps, currentIndex = -1, hysteresisPx = hysteresis))
        assertEquals(1, resolveShelfCatalogPeekBookIndex(150f, widths, gaps, currentIndex = -1, hysteresisPx = hysteresis))
        assertEquals(3, resolveShelfCatalogPeekBookIndex(600f, widths, gaps, currentIndex = -1, hysteresisPx = hysteresis))

        // 在交界 196 ± 12 范围内来回抖动，保持当前选中书 1
        assertEquals(1, resolveShelfCatalogPeekBookIndex(196f, widths, gaps, currentIndex = 1, hysteresisPx = hysteresis))
        assertEquals(1, resolveShelfCatalogPeekBookIndex(205f, widths, gaps, currentIndex = 1, hysteresisPx = hysteresis))
        assertEquals(1, resolveShelfCatalogPeekBookIndex(190f, widths, gaps, currentIndex = 1, hysteresisPx = hysteresis))
        assertEquals(1, resolveShelfCatalogPeekBookIndex(208f, widths, gaps, currentIndex = 1, hysteresisPx = hysteresis))

        // 越过 196 + 12 = 208 才切到书 2；切过去后在 184..208 间回抖也不跳回书 1
        assertEquals(2, resolveShelfCatalogPeekBookIndex(209f, widths, gaps, currentIndex = 1, hysteresisPx = hysteresis))
        assertEquals(2, resolveShelfCatalogPeekBookIndex(196f, widths, gaps, currentIndex = 2, hysteresisPx = hysteresis))
        assertEquals(2, resolveShelfCatalogPeekBookIndex(185f, widths, gaps, currentIndex = 2, hysteresisPx = hysteresis))
        assertEquals(1, resolveShelfCatalogPeekBookIndex(183f, widths, gaps, currentIndex = 2, hysteresisPx = hysteresis))

        // 滑出左右两端时停在端点，不越界
        assertEquals(0, resolveShelfCatalogPeekBookIndex(-120f, widths, gaps, currentIndex = 2, hysteresisPx = hysteresis))
        assertEquals(3, resolveShelfCatalogPeekBookIndex(999f, widths, gaps, currentIndex = 1, hysteresisPx = hysteresis))

        // 只有 1 本书时始终返回 0
        assertEquals(0, resolveShelfCatalogPeekBookIndex(-50f, listOf(90), emptyList(), currentIndex = -1, hysteresisPx = hysteresis))
        assertEquals(0, resolveShelfCatalogPeekBookIndex(500f, listOf(90), emptyList(), currentIndex = 0, hysteresisPx = hysteresis))
    }

    @Test
    fun longPressHitsOnlyTheSelectedBookHeightWhileDraggingCanStartInEmptySpace() {
        val widths = listOf(90, 96)
        val gaps = listOf(6)
        fun hit(fingerX: Float, fingerY: Float) = hitShelfCatalogSpineIndex(
            fingerX, fingerY, widths, gaps, floatArrayOf(285f, 250f), intArrayOf(0, -6),
            tierBookBottomPx = 300f, edgePadPx = 12f,
        )

        assertEquals(0, hit(40f, 30f))
        assertEquals(-1, hit(140f, 30f))
        assertEquals(1, hit(140f, 44f))
        assertEquals(1, hit(140f, 120f))
        assertEquals(-1, hit(140f, 296f))
        assertEquals(-1, hit(440f, 120f))
        assertEquals(-1, hit(140f, 340f))
        assertEquals(1, resolveShelfCatalogPeekBookIndex(440f, widths, gaps, currentIndex = -1, hysteresisPx = 0f))
    }

    @Test
    fun diagonalDragKeepsTrackingAcrossConsumedMovesInEitherDirection() {
        for (sign in listOf(-1f, 1f)) {
            val tracker = ShelfCatalogPeekGestureTracker(slop = 10f)
            assertEquals(ShelfCatalogPeekDirection.UNDECIDED, tracker.move(Offset(sign * 3f, 7f), consumed = true))
            assertFalse(tracker.canLongPress)
            assertEquals(ShelfCatalogPeekDirection.UNDECIDED, tracker.move(Offset(sign * 3f, 4f), consumed = false))
            assertEquals(ShelfCatalogPeekDirection.PEEK, tracker.move(Offset(sign * 3f, 4f), consumed = true))
        }
    }

    @Test
    fun stationaryJitterRemainsEligibleForLongPressButVerticalDriftScrolls() {
        val tracker = ShelfCatalogPeekGestureTracker(slop = 10f)
        assertEquals(ShelfCatalogPeekDirection.UNDECIDED, tracker.move(Offset(2f, -4f), consumed = false))
        assertTrue(tracker.canLongPress)
        assertEquals(ShelfCatalogPeekDirection.UNDECIDED, tracker.move(Offset(1f, -8f), consumed = false))
        assertFalse(tracker.canLongPress)
        assertEquals(ShelfCatalogPeekDirection.SCROLL, tracker.move(Offset(2f, -12f), consumed = true))
    }

    @Test
    fun peekLayoutHandlesPhoneNarrowAndTabletWideTiers() {
        val phoneDensity = Density(3f)
        assertTierPeekBounds(widthDp = 326.dp, density = phoneDensity, expectPhoneInnerAround300Dp = true)
        assertTierPeekBounds(widthDp = 390.dp, density = phoneDensity, expectPhoneInnerAround300Dp = false)

        val tabletDensity = Density(2f)
        assertTierPeekBounds(widthDp = 840.dp, density = tabletDensity, expectPhoneInnerAround300Dp = false)
        assertTierPeekBounds(widthDp = 1120.dp, density = tabletDensity, expectPhoneInnerAround300Dp = false)
    }

    private fun assertTierPeekBounds(
        widthDp: androidx.compose.ui.unit.Dp,
        density: Density,
        expectPhoneInnerAround300Dp: Boolean,
    ) {
        val cells = with(density) { shelfCatalogGridCells(widthDp, ShelfCatalogLayoutMode.SPINES) }
        val innerWidthPx = with(density) {
            (widthDp.roundToPx() - 2 * cells.cabinetInset.roundToPx() - 2 * (CABINET_STILE_WIDTH + CABINET_INTERIOR_INSET).roundToPx())
                .coerceAtLeast(1)
        }
        if (expectPhoneInnerAround300Dp) {
            val innerDp = innerWidthPx / density.density
            assertTrue("expected ~300dp inner width, got $innerDp", innerDp in 275f..305f)
        }
        val maxHeight = shelfCatalogSpineHeight(widthDp)
        val maxHeightPx = with(density) { maxHeight.toPx() }
        val baseWidthPx = with(density) { cells.cellWidth.toPx() }
        val leanReservePx = shelfCatalogEndBookLeanShiftPx(
            leanDegrees = -6.5f,
            bookHeightPx = maxHeightPx,
            bookWidthPx = baseWidthPx * SPINE_MAX_THICKNESS_FACTOR,
        )

        // 1) 只有 1 本书
        run {
            val uris = listOf("content://games/solo")
            val widths = allocateShelfCatalogSpineWidths(uris, cells.columns, baseWidthPx, innerWidthPx, 0, leanReservePx)
            val heights = FloatArray(1) { maxHeightPx * shelfCatalogSpineHeightFactor(uris[0]) }
            val layout = computeShelfCatalogPeekLayout(widths, emptyList(), heights, floatArrayOf(1f), innerWidthPx)
            val coverW = shelfCatalogPeekCoverWidthPx(heights[0])
            val projW = shelfCatalogPeekProjectedWidthPx(widths[0].toFloat(), coverW, 1f)
            assertEquals(0f, layout.rowShiftPx, 1e-2f)
            assertEquals(0f, layout.bookShiftsPx[0], 1e-2f)
            assertTrue(projW <= innerWidthPx + 0.5f)
        }

        // 2) 满层：首本 / 中间本 / 末本
        val fullUris = List(cells.columns) { "content://games/tier-$widthDp-$it" }
        val gaps = allocateShelfCatalogSpineGapsPx(fullUris, density.density)
        val widths = allocateShelfCatalogSpineWidths(
            uris = fullUris,
            maxColumns = cells.columns,
            baseCellWidthPx = baseWidthPx,
            innerWidthPx = innerWidthPx,
            gapsSumPx = gaps.sum(),
            trailingLeanReservePx = leanReservePx,
        )
        val heights = FloatArray(fullUris.size) { i -> maxHeightPx * shelfCatalogSpineHeightFactor(fullUris[i]) }
        val staticLefts = FloatArray(fullUris.size)
        var x = 0f
        for (i in fullUris.indices) {
            staticLefts[i] = x
            x += widths[i] + if (i < gaps.size) gaps[i] else 0
        }

        val testIndices = listOf(0, fullUris.size / 2, fullUris.lastIndex)
        for (target in testIndices) {
            val opens = FloatArray(fullUris.size) { if (it == target) 1f else 0f }
            val layout = computeShelfCatalogPeekLayout(widths, gaps, heights, opens, innerWidthPx)
            val coverW = shelfCatalogPeekCoverWidthPx(heights[target])
            val projW = shelfCatalogPeekProjectedWidthPx(widths[target].toFloat(), coverW, 1f)
            val extra = projW - widths[target]
            val targetRightEdge = staticLefts[target] + layout.bookShiftsPx[target] + projW

            // 翻开那本封面右缘绝不超出柜内右边界
            assertTrue(
                "target $target right edge $targetRightEdge exceeds innerWidth $innerWidthPx at $widthDp",
                targetRightEdge <= innerWidthPx + 0.05f,
            )

            // 左侧不位移额外间距、右侧整体右移 extra
            for (i in fullUris.indices) {
                val expectedShift = layout.rowShiftPx + if (i > target) extra else 0f
                assertEquals(expectedShift, layout.bookShiftsPx[i], 0.05f)
            }

            // 首本肯定贴左不左移
            if (target == 0) {
                assertEquals(0f, layout.rowShiftPx, 0.05f)
            }
            // 末本翻开时整排左移，右缘刚好贴齐柜内右边界
            if (target == fullUris.lastIndex) {
                assertTrue("last book should shift row left at $widthDp", layout.rowShiftPx < -10f)
                assertEquals(innerWidthPx.toFloat(), targetRightEdge, 0.1f)
            }
        }
    }

    @Test
    fun extremeNarrowSingleBookTierClampsRightEdgeInsideInnerWidth() {
        val widths = listOf(80)
        val heights = floatArrayOf(500f)
        val innerWidthPx = 240
        val layout = computeShelfCatalogPeekLayout(widths, emptyList(), heights, floatArrayOf(1f), innerWidthPx)
        val projW = shelfCatalogPeekProjectedWidthPx(80f, shelfCatalogPeekCoverWidthPx(500f), 1f)
        assertTrue(projW > innerWidthPx)
        assertEquals(innerWidthPx - projW, layout.rowShiftPx, 0.05f)
        assertEquals(innerWidthPx.toFloat(), layout.bookShiftsPx[0] + projW, 0.05f)
    }

    @Test
    fun simultaneousCrossSwitchPreservesGapsAndStaysContinuous() {
        val widths = List(8) { 90 }
        val gaps = List(7) { 8 }
        val heights = FloatArray(8) { 500f }
        val innerWidthPx = 800

        val staticLefts = FloatArray(8)
        var cursor = 0f
        for (i in 0 until 8) {
            staticLefts[i] = cursor
            cursor += widths[i] + if (i < gaps.size) gaps[i] else 0
        }

        // 从末本 (7) 切到倒数第二本 (6)：7 合回、6 翻开同时进行
        var prevRowShift = Float.NaN
        for (step in 0..10) {
            val t = step / 10f
            val opens = FloatArray(8)
            opens[7] = 1f - t
            opens[6] = t
            val layout = computeShelfCatalogPeekLayout(widths, gaps, heights, opens, innerWidthPx)
            if (!prevRowShift.isNaN()) {
                assertTrue("rowShift should remain smooth across switch", kotlin.math.abs(layout.rowShiftPx - prevRowShift) < 60f)
            }
            prevRowShift = layout.rowShiftPx
            for (i in 0 until 7) {
                val projI = shelfCatalogPeekProjectedWidthPx(
                    widths[i].toFloat(),
                    shelfCatalogPeekCoverWidthPx(heights[i]),
                    opens[i],
                )
                val rightI = staticLefts[i] + layout.bookShiftsPx[i] + projI
                val leftNext = staticLefts[i + 1] + layout.bookShiftsPx[i + 1]
                assertEquals("gap after $i broken at step $step", gaps[i].toFloat(), leftNext - rightI, 0.05f)
            }
        }
    }
}
