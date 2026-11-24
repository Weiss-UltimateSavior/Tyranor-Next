package com.tyranor.next.ui.game

import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.tyranor.next.theme.Amber40
import com.tyranor.next.theme.Blue40
import com.tyranor.next.theme.Teal40
import com.tyranor.next.theme.shelfBookDeleteStyle
import com.tyranor.next.theme.shelfBookLaunchStyle
import com.tyranor.next.theme.shelfBookPaperSurface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 「游戏盒」翻开的书：书在屏幕上的摆放（手机竖屏 / 平板 / 横屏 / 很矮的窗口），与目录随条目增删的排列
 * （新插进来的章、正在收起的章、章号重排、收起完撤走、撤到一半又回来）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ShelfBookLayoutTest {

    private fun TestScope.withBookFrameClock(): CoroutineScope = CoroutineScope(
        coroutineContext + object : MonotonicFrameClock {
            override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
                delay(16)
                return onFrame(testScheduler.currentTime * 1_000_000L).also { Snapshot.sendApplyNotifications() }
            }
        },
    )

    @Test
    fun cancellingCoverDragPastCloseThresholdReopensWithoutDismissing() = runTest {
        val motion = ShelfBookMotion(withBookFrameClock(), true, ShelfBookStage(), "book", false)
        var dismissals = 0
        motion.dismissAction = { dismissals++ }
        motion.dragBy(50f, 100f)
        assertEquals(0.5f, motion.cover.value, 0f)

        motion.dragCancel()
        advanceUntilIdle()

        assertEquals(1f, motion.cover.value, 0f)
        assertFalse(motion.closing)
        assertEquals(0, dismissals)
    }

    @Test
    fun cancellingLeafDragRestoresTheTurnWithoutClosingTheLeaf() = runTest {
        val pages = ShelfBookPages(withBookFrameClock(), enhanced = true)
        val leaf = ShelfBookLeaf(null)
        pages.push(leaf)
        advanceUntilIdle()
        pages.dragStart()
        pages.dragBy(50f, 100f)
        assertEquals(0.5f, pages.toc.value, 0f)

        pages.dragCancel()
        advanceUntilIdle()

        assertEquals(listOf(leaf), pages.leaves)
        assertEquals(1f, pages.toc.value, 0f)
    }

    @Test
    fun removingTheDraggedLeafDoesNotRetargetTheRemainingDragToItsParent() = runTest {
        val pages = ShelfBookPages(withBookFrameClock(), enhanced = true)
        val parent = ShelfBookLeaf(null)
        val child = ShelfBookLeaf(null)
        pages.push(parent)
        pages.push(child)
        advanceUntilIdle()
        pages.dragStart()
        pages.dragBy(50f, 100f)
        assertEquals(0.5f, parent.turn.value, 0f)

        pages.remove(child)
        pages.dragBy(100f, 100f)
        pages.dragEnd(4_000f, 100f)
        advanceUntilIdle()

        assertEquals(listOf(parent), pages.leaves)
        assertEquals(0f, parent.turn.value, 0f)
        assertEquals(1f, pages.toc.value, 0f)
    }

    @Test
    fun previousCloseCannotDeliverAfterReopenAndSecondClose() = runTest {
        val motion = ShelfBookMotion(withBookFrameClock(), true, ShelfBookStage(), "book", false)
        var dismissals = 0
        motion.dismissAction = { dismissals++ }
        motion.close()
        runCurrent()
        assertTrue(motion.reopen())
        motion.close()
        runCurrent()

        assertEquals(0, dismissals)
        advanceUntilIdle()
        assertEquals(1, dismissals)
    }

    @Test
    fun rotationPaperFitsEachOuterSlotWithoutStretchingAndReturnsToOneToOne() {
        val finalSize = IntSize(720, 1080)
        assertEquals(0.5f, shelfBookPaperScale(finalSize, IntSize(360, 900)), 0.0001f)
        assertEquals(0.75f, shelfBookPaperScale(finalSize, IntSize(650, 810)), 0.0001f)
        assertEquals(1f, shelfBookPaperScale(finalSize, finalSize), 0f)
    }

    private fun assertFits(frame: ShelfBookFrame, width: Dp, height: Dp, top: Dp, bottom: Dp) {
        // 2:3，很矮的窗口里最多矮胖到正方形
        assertTrue(frame.pageHeight.value <= frame.pageWidth.value * 1.5f + 0.01f)
        assertTrue(frame.pageHeight.value >= frame.pageWidth.value - 0.01f)
        assertTrue("page leaves the screen on the right", frame.gutterX + frame.pageWidth <= width + 0.01.dp)
        assertTrue("page above the top inset", frame.top >= top - 0.01.dp)
        assertTrue("page below the bottom inset", frame.top + frame.pageHeight <= height - bottom + 0.01.dp)
        assertTrue(frame.pageWidth > 0.dp)
    }

    @Test
    fun phonePortraitFillsTheWidthAndShowsASliverOfTheLeftPage() {
        val frame = shelfBookFrame(361.dp, 800.dp, 40.dp, 24.dp)
        assertFalse(frame.spread)
        assertFits(frame, 361.dp, 800.dp, 40.dp, 24.dp)
        assertEquals(frame.pageWidth.value * 1.5f, frame.pageHeight.value, 0.01f)
        // 右页贴着右边的留白，左页露出靠书脊的一窄条
        assertEquals(361f - 10f, (frame.gutterX + frame.pageWidth).value, 0.01f)
        assertTrue(frame.gutterX >= 22.dp)
        assertTrue("right page should take most of the width", frame.pageWidth.value > 361f * 0.85f)
    }

    @Test
    fun wideScreensOpenAsACenteredSpread() {
        val frame = shelfBookFrame(1280.dp, 800.dp, 24.dp, 24.dp)
        assertTrue(frame.spread)
        assertEquals(640f, frame.gutterX.value, 0.01f)
        assertFits(frame, 1280.dp, 800.dp, 24.dp, 24.dp)
        assertTrue("left page fits too", frame.gutterX - frame.pageWidth >= 0.dp)
    }

    @Test
    fun shortWindowsShrinkTheBookInsteadOfOverflowing() {
        val frame = shelfBookFrame(361.dp, 420.dp, 24.dp, 24.dp)
        assertFits(frame, 361.dp, 420.dp, 24.dp, 24.dp)
        assertTrue("page stays comfortably wide", frame.pageWidth >= 280.dp)
    }

    @Test
    fun landscapePhoneOpensASpreadOfWiderPages() {
        val frame = shelfBookFrame(800.dp, 361.dp, 0.dp, 24.dp)
        assertTrue(frame.spread)
        assertFits(frame, 800.dp, 361.dp, 0.dp, 24.dp)
        assertTrue("pages do not shrink into narrow strips", frame.pageWidth >= 280.dp)
    }

    @Test
    fun tabletPortraitCapsThePageWidth() {
        val frame = shelfBookFrame(800.dp, 1280.dp, 24.dp, 24.dp)
        assertFits(frame, 800.dp, 1280.dp, 24.dp, 24.dp)
        assertTrue(frame.pageWidth <= 440.dp)
    }

    @Test
    fun phone19By9PortraitFitsComfortablyWithoutSpread() {
        val frame = shelfBookFrame(393.dp, 829.dp, 32.dp, 20.dp)
        assertFalse(frame.spread)
        assertFits(frame, 393.dp, 829.dp, 32.dp, 20.dp)
        assertEquals(frame.pageWidth.value * 1.5f, frame.pageHeight.value, 0.01f)
        assertTrue(frame.pageWidth >= 340.dp)
    }

    @Test
    fun tabletLandscapeSpreadFitsBothPages() {
        val frame = shelfBookFrame(1097.dp, 686.dp, 24.dp, 20.dp)
        assertTrue(frame.spread)
        assertEquals(ShelfBookLayoutMode.Spread, frame.layoutMode)
        assertFits(frame, 1097.dp, 686.dp, 24.dp, 20.dp)
        assertTrue(frame.gutterX - frame.pageWidth >= 0.dp)
    }

    @Test
    fun squareIshSpecialRatioOpensFullSpreadWithoutCuttingCover() {
        // 1264x1400 @ 316dpi -> 640x709dp 特殊近方形屏幕：完整摊开双页，不半截裁切封面
        val frame = shelfBookFrame(640.dp, 709.dp, 24.dp, 20.dp)
        assertTrue(frame.spread)
        assertEquals(ShelfBookLayoutMode.Spread, frame.layoutMode)
        assertFits(frame, 640.dp, 709.dp, 24.dp, 20.dp)
        assertTrue("left page must fit completely on screen", frame.gutterX - frame.pageWidth >= 0.dp)
    }

    @Test
    fun tabletPortraitOpensFullCenteredSpread() {
        for ((w, h) in listOf(800.dp to 1280.dp, 768.dp to 1024.dp, 600.dp to 960.dp, 500.dp to 820.dp, 480.dp to 760.dp)) {
            val frame = shelfBookFrame(w, h, 24.dp, 24.dp, tablet = true)
            assertTrue("tablet portrait $w x $h should open full two-page spread", frame.spread)
            assertEquals(ShelfBookLayoutMode.Spread, frame.layoutMode)
            assertFits(frame, w, h, 24.dp, 24.dp)
            assertTrue("left page fits completely on screen for $w x $h", frame.gutterX - frame.pageWidth >= 0.dp)
        }
        // 平板极窄分屏（每页不足 220dp）安全回落单页而不挤压重叠
        val tinySplit = shelfBookFrame(380.dp, 800.dp, 24.dp, 24.dp, tablet = true)
        assertFalse(tinySplit.spread)
        assertFits(tinySplit, 380.dp, 800.dp, 24.dp, 24.dp)
    }

    @Test
    fun nonTabletIntermediateRatiosUseSingleCenteredWithoutHalfPage() {
        for ((w, h) in listOf(480.dp to 560.dp, 480.dp to 640.dp, 440.dp to 480.dp, 420.dp to 620.dp)) {
            val frame = shelfBookFrame(w, h, 24.dp, 20.dp, tablet = false)
            assertFalse("intermediate viewport $w x $h should stay single page", frame.spread)
            assertEquals("no half-page mode; $w x $h must center single page", ShelfBookLayoutMode.SingleCentered, frame.layoutMode)
            assertEquals(22f, frame.leftPageWidth.value, 0.01f)
            assertFits(frame, w, h, 24.dp, 20.dp)
            val leftSpace = frame.gutterX - 22.dp
            val rightSpace = w - (frame.gutterX + frame.pageWidth)
            assertEquals("visible book should be horizontally centered for $w x $h", leftSpace.value, rightSpace.value, 0.1f)
        }
    }

    @Test
    fun rotationTransitionsCoverSingleAndSpreadPairs() {
        // 居中单页 <-> 双页（480x640 <-> 640x480）
        val centeredPortrait = shelfBookFrame(480.dp, 640.dp, 24.dp, 20.dp, tablet = false)
        val spreadLandscape = shelfBookFrame(640.dp, 480.dp, 0.dp, 20.dp, tablet = false)
        assertEquals(ShelfBookLayoutMode.SingleCentered, centeredPortrait.layoutMode)
        assertEquals(ShelfBookLayoutMode.Spread, spreadLandscape.layoutMode)

        // 手机竖屏单页 <-> 横屏双页（393x829 <-> 829x393）
        val phonePortrait = shelfBookFrame(393.dp, 829.dp, 32.dp, 20.dp, tablet = false)
        val phoneLandscape = shelfBookFrame(829.dp, 393.dp, 0.dp, 20.dp, tablet = false)
        assertEquals(ShelfBookLayoutMode.SinglePeek, phonePortrait.layoutMode)
        assertEquals(ShelfBookLayoutMode.Spread, phoneLandscape.layoutMode)

        // 平板竖屏双页 <-> 平板横屏双页（600x960 <-> 960x600）
        val tabletPortrait = shelfBookFrame(600.dp, 960.dp, 24.dp, 20.dp, tablet = true)
        val tabletLandscape = shelfBookFrame(960.dp, 600.dp, 0.dp, 20.dp, tablet = true)
        assertEquals(ShelfBookLayoutMode.Spread, tabletPortrait.layoutMode)
        assertEquals(ShelfBookLayoutMode.Spread, tabletLandscape.layoutMode)
    }

    @Test
    fun layoutMorphCurvesAreMonotonicAndBounded() {
        assertEquals(1f, shelfBookMorphExitAlpha(0f), 0.0001f)
        assertEquals(0f, shelfBookMorphExitAlpha(0.36f), 0.0001f)
        assertEquals(0f, shelfBookMorphExitAlpha(1f), 0.0001f)
        var prevExit = 1f
        for (i in 1..20) {
            val cur = shelfBookMorphExitAlpha(i / 20f)
            assertTrue("exit alpha must monotonically decay", cur <= prevExit + 0.0001f)
            prevExit = cur
        }

        assertEquals(0f, shelfBookMorphEnterAlpha(0f), 0.0001f)
        assertEquals(0f, shelfBookMorphEnterAlpha(0.32f), 0.0001f)
        assertEquals(1f, shelfBookMorphEnterAlpha(1f), 0.0001f)
        var prevEnter = 0f
        for (i in 1..20) {
            val cur = shelfBookMorphEnterAlpha(i / 20f)
            assertTrue("enter alpha must monotonically grow", cur >= prevEnter - 0.0001f)
            prevEnter = cur
        }

        assertEquals(0f, shelfBookMorphHingeBreath(0.5f, active = false), 0.0001f)
        assertEquals(0f, shelfBookMorphHingeBreath(0f, active = true), 0.0001f)
        assertEquals(0f, shelfBookMorphHingeBreath(1f, active = true), 0.0001f)
        val peak = shelfBookMorphHingeBreath(0.45f, active = true)
        assertTrue("default hinge breath should peak smoothly around 15..16.5deg ($peak)", peak in 15.0f..16.5f)
        val largePeak = shelfBookMorphHingeBreath(0.45f, active = true, peakDeg = 22f)
        assertTrue("large hinge breath should exceed default ($largePeak > $peak)", largePeak > peak)
    }

    @Test
    fun reprojectedStartFitsInsideRotatedViewportAcrossAllModes() {
        // 1) 手机竖屏单页 -> 手机横屏双页（高度大幅压低）
        val phonePortrait = shelfBookFrame(393.dp, 829.dp, 32.dp, 20.dp)
        val reprojectedToLandscape = shelfBookMorphReprojectedStart(phonePortrait, 829.dp, 393.dp)
        assertTrue("top inside landscape viewport", reprojectedToLandscape.top >= -0.01.dp)
        assertTrue(
            "bottom clipped inside landscape viewport: ${reprojectedToLandscape.top + reprojectedToLandscape.pageHeight}",
            reprojectedToLandscape.top + reprojectedToLandscape.pageHeight <= 393.01.dp,
        )
        assertEquals(
            phonePortrait.leftPageWidth.value / phonePortrait.pageWidth.value,
            reprojectedToLandscape.leftPageWidth.value / reprojectedToLandscape.pageWidth.value,
            0.01f,
        )

        // 2) 横屏双页 -> 半页竖屏（总宽度大幅收窄）
        val landscapeSpread = shelfBookFrame(640.dp, 480.dp, 0.dp, 20.dp)
        val reprojectedToSide = shelfBookMorphReprojectedStart(landscapeSpread, 480.dp, 640.dp)
        assertTrue("left fits inside portrait viewport", reprojectedToSide.gutterX - reprojectedToSide.leftPageWidth >= -0.01.dp)
        assertTrue("right fits inside portrait viewport", reprojectedToSide.gutterX + reprojectedToSide.pageWidth <= 480.01.dp)
        assertTrue("bottom fits inside portrait viewport", reprojectedToSide.top + reprojectedToSide.pageHeight <= 640.01.dp)
        assertEquals(1f, reprojectedToSide.leftPageWidth.value / reprojectedToSide.pageWidth.value, 0.01f)

        // 3) 完整落在当前视口内时不改变起势几何
        val untouched = shelfBookMorphReprojectedStart(landscapeSpread, 800.dp, 600.dp)
        assertTrue("in-bounds frame should return exact reference", untouched === landscapeSpread)
    }

    @Test
    fun tabMotionPhasesAreMonotonicAndConvergeBeforeFinish() {
        assertEquals(0f, shelfBookTabExitPhase(0f), 0.0001f)
        assertEquals(1f, shelfBookTabExitPhase(1f), 0.0001f)
        for (order in 0..6) {
            assertEquals(0f, shelfBookTabRowExitPhase(0f, order), 0.0001f)
            assertEquals(1f, shelfBookTabRowExitPhase(1f, order), 0.0001f)
            var prevExit = 0f
            for (i in 1..20) {
                val curExit = shelfBookTabRowExitPhase(i / 20f, order)
                assertTrue("exit phase regressed for order=$order at step $i", curExit >= prevExit)
                prevExit = curExit
            }
        }
        assertTrue(shelfBookTabRowExitPhase(0.16f, 0) > shelfBookTabRowExitPhase(0.16f, 4))

        for (order in 0..6) {
            assertEquals(0f, shelfBookTabRevealPhase(0f, order), 0.0001f)
            assertEquals(1f, shelfBookTabRevealPhase(1f, order), 0.0001f)
            assertEquals(1f, shelfBookTabRevealPhase(1f, order, subDelay = 0.12f), 0.0001f)
            assertEquals(0f, shelfBookTabLeaderPhase(0f, order), 0.0001f)
            assertEquals(1f, shelfBookTabLeaderPhase(1f, order), 0.0001f)
            var prev = 0f
            var prevLeader = 0f
            for (i in 1..20) {
                val p = i / 20f
                val cur = shelfBookTabRevealPhase(p, order)
                val curLeader = shelfBookTabLeaderPhase(p, order)
                assertTrue("reveal phase regressed for order=$order at step $i", cur >= prev)
                assertTrue("leader phase regressed for order=$order at step $i", curLeader >= prevLeader)
                prev = cur
                prevLeader = curLeader
            }
        }
        assertTrue(shelfBookTabRevealPhase(0.45f, 0) > shelfBookTabRevealPhase(0.45f, 4))
    }

    @Test
    fun charRevealStaggersLeftToRightAndConvergesBeforeFinish() {
        val count = 8
        for (order in listOf(0, 3, 6)) {
            for (charIdx in 0 until count) {
                assertEquals(0f, shelfBookTabCharPhase(0f, order, charIdx, count), 0.0001f)
                assertEquals(1f, shelfBookTabCharPhase(1f, order, charIdx, count, subDelay = 0.14f), 0.0001f)
                var prev = 0f
                for (i in 1..20) {
                    val cur = shelfBookTabCharPhase(i / 20f, order, charIdx, count)
                    assertTrue("char phase regressed for order=$order char=$charIdx", cur >= prev)
                    prev = cur
                }
            }
            val mid = if (order == 0) 0.36f else if (order == 3) 0.56f else 0.72f
            assertTrue(
                "earlier char should lead later char at order=$order mid=$mid",
                shelfBookTabCharPhase(mid, order, 0, count) > shelfBookTabCharPhase(mid, order, count - 1, count),
            )
        }
    }

    @Test
    fun splitInkSegmentsPreservesAllCodePointsWithinBudget() {
        val cjk = "轻点更换自定义封面"
        val cjkSegs = splitInkSegments(cjk)
        assertEquals(9, cjkSegs.size)
        assertEquals(cjk, cjkSegs.joinToString(""))

        val latin = "Tap to choose a custom cover image"
        val latinSegs = splitInkSegments(latin, maxSegments = 12)
        assertTrue("segment count should be capped", latinSegs.size in 2..12)
        assertEquals(latin, latinSegs.joinToString(""))

        val astral = "书签\uD83D\uDCD6页"
        val astralSegs = splitInkSegments(astral)
        assertEquals(4, astralSegs.size)
        assertEquals(astral, astralSegs.joinToString(""))
        assertTrue(splitInkSegments("").isEmpty())
    }

    @Test
    fun exitingTabNeverFlashesBackAtEndOfTransition() {
        assertEquals(1f, shelfBookTabExitingContainerAlpha(0f, enhanced = true, isTransitioning = true), 0.0001f)
        assertEquals(1f, shelfBookTabExitingContainerAlpha(0f, enhanced = false, isTransitioning = true), 0.0001f)
        for (p in listOf(0.40f, 0.999f, 1.0f)) {
            assertEquals(0f, shelfBookTabExitingContainerAlpha(p, enhanced = true, isTransitioning = true), 0.0001f)
        }
        for (p in listOf(0f, 0.5f, 0.999f, 1.0f)) {
            assertEquals(0f, shelfBookTabExitingContainerAlpha(p, enhanced = true, isTransitioning = false), 0.0001f)
            assertEquals(0f, shelfBookTabExitingContainerAlpha(p, enhanced = false, isTransitioning = false), 0.0001f)
        }

        for (order in 0..6) {
            assertEquals(1f, shelfBookTabExitingRowAlpha(0f, order, isTransitioning = true), 0.0001f)
            for (p in listOf(0.999f, 1.0f)) {
                assertEquals(0f, shelfBookTabExitingRowAlpha(p, order, isTransitioning = true), 0.0001f)
            }
            for (p in listOf(0f, 0.5f, 0.999f, 1.0f)) {
                assertEquals(0f, shelfBookTabExitingRowAlpha(p, order, isTransitioning = false), 0.0001f)
            }
        }
    }

    private fun item(key: String) = GameActionItem(key = key, title = key, icon = 0, onClick = {})

    private fun List<ShelfBookTocRow>.keys() = map { it.item.key }

    @Test
    fun rowsPresentWhenTheBookOpensAreNotFresh() {
        val toc = ShelfBookToc()
        val rows = toc.rows(listOf(item("a"), item("b"), item("c")))
        assertEquals(listOf("a", "b", "c"), rows.keys())
        assertTrue(rows.all { it.present && !it.fresh })
        assertEquals(listOf(1, 2, 3), rows.map { it.number })
    }

    @Test
    fun insertedRowIsFreshAndLaterChaptersAreRenumbered() {
        val toc = ShelfBookToc()
        toc.rows(listOf(item("a"), item("b"), item("c")))
        val rows = toc.rows(listOf(item("a"), item("x"), item("b"), item("c")))
        assertEquals(listOf("a", "x", "b", "c"), rows.keys())
        assertEquals(listOf(false, true, false, false), rows.map { it.fresh })
        assertEquals(listOf(1, 2, 3, 4), rows.map { it.number })
    }

    @Test
    fun removedRowStaysInPlaceUntilItHasCollapsed() {
        val toc = ShelfBookToc()
        val all = listOf(item("a"), item("b"), item("c"))
        toc.rows(all)
        val shrinking = toc.rows(listOf(all[0], all[2]))
        assertEquals(listOf("a", "b", "c"), shrinking.keys())
        val b = shrinking[1]
        assertFalse(b.present)
        assertEquals("the collapsing chapter keeps its last number", 2, b.number)
        assertEquals(2, shrinking[2].number)

        val before = toc.settled
        toc.gone("b")
        assertEquals(before + 1, toc.settled)
        assertEquals(listOf("a", "c"), toc.rows(listOf(all[0], all[2])).keys())
        // 重复交付不会再触发重排
        toc.gone("b")
        assertEquals(before + 1, toc.settled)
    }

    @Test
    fun rowThatComesBackWhileCollapsingIsNotDuplicated() {
        val toc = ShelfBookToc()
        val all = listOf(item("a"), item("b"), item("c"))
        toc.rows(all)
        toc.rows(listOf(all[0], all[2]))
        val back = toc.rows(all)
        assertEquals(listOf("a", "b", "c"), back.keys())
        assertTrue(back.all { it.present })
        assertFalse("a chapter that was there when the book opened is not fresh", back[1].fresh)
    }

    @Test
    fun clippedInsideBoardWidthExpandsSmoothlyWithoutMovingLeftward() {
        val base = 66f
        val page = 1080f
        val cam = 28f * 72f * 3f
        assertEquals(base, shelfBookEffectiveClippedWidthPx(base, page, -180f, cam), 0.01f)
        assertEquals(page, shelfBookEffectiveClippedWidthPx(base, page, -90f, cam), 0.01f)
        assertEquals(page, shelfBookEffectiveClippedWidthPx(base, page, -75f, cam), 0.01f)

        var prev = base
        for (deg in -180..-90 step 5) {
            val w = shelfBookEffectiveClippedWidthPx(base, page, deg.toFloat(), cam)
            assertTrue("width must grow monotonically toward -90 deg ($deg: $w < $prev)", w >= prev - 0.01f)
            assertTrue("width must stay within [base, page] ($deg: $w)", w in base..page)
            val rad = Math.toRadians((-deg).toDouble())
            val cosAbs = (-kotlin.math.cos(rad)).coerceAtLeast(0.0)
            val sinPos = kotlin.math.sin(rad).coerceAtLeast(0.0)
            val projectedLeft = (w * cosAbs * cam) / (cam - w * sinPos).coerceAtLeast(1.0)
            assertTrue("screen projection must not protrude left of base ($deg: $projectedLeft > $base)", projectedLeft <= base + 0.5)
            prev = w
        }
    }

    @Test
    fun bindingOverlayAlphaFadesSmoothlyBeforeVerticalCover() {
        assertEquals(1f, shelfBookBindingAlpha(-180f), 0.0001f)
        assertEquals(1f, shelfBookBindingAlpha(-132f), 0.0001f)
        assertEquals(0f, shelfBookBindingAlpha(-94f), 0.0001f)
        assertEquals(0f, shelfBookBindingAlpha(-90f), 0.0001f)
        assertEquals(0f, shelfBookBindingAlpha(0f), 0.0001f)

        var prev = 1f
        for (deg in -132..-94 step 2) {
            val a = shelfBookBindingAlpha(deg.toFloat())
            assertTrue("binding alpha must decrease monotonically toward -94 deg ($deg: $a > $prev)", a <= prev + 0.0001f)
            assertTrue("binding alpha must stay within [0, 1] ($deg: $a)", a in 0f..1f)
            prev = a
        }
    }

    @Test
    fun coverRotationSideSwitchAlignsExactlyWithVerticalPlane() {
        // 含飞行侧转 yaw=12° 与形变折角 hinge=8° 时，真实竖直分界应在合到 cover=0.5 之后（-90°）
        val rotAtHalf = shelfBookCoverRotationY(yawDeg = 12f, coverProgress = 0.5f, hingeAngle = 8f)
        assertEquals(-70f, rotAtHalf, 0.001f)
        assertTrue("at yaw=12, hinge=8, cover=0.5 front cover is already facing viewer", rotAtHalf > -90f)
        val rotInside = shelfBookCoverRotationY(yawDeg = 12f, coverProgress = 0.65f, hingeAngle = 8f)
        assertTrue("at cover=0.65 inside face is still facing viewer", rotInside < -90f)
        assertEquals(-90f, shelfBookCoverRotationY(yawDeg = 0f, coverProgress = 0.5f, hingeAngle = 0f), 0.001f)
    }

    private fun wcagContrast(fg: Color, bg: Color): Float {
        val l1 = fg.luminance()
        val l2 = bg.luminance()
        val hi = maxOf(l1, l2)
        val lo = minOf(l1, l2)
        return (hi + 0.05f) / (lo + 0.05f)
    }

    private fun testHslColor(hueDeg: Float, sat: Float, light: Float): Color {
        val h = ((hueDeg % 360f) + 360f) % 360f
        val c = (1f - abs(2f * light - 1f)) * sat
        val x = c * (1f - abs((h / 60f) % 2f - 1f))
        val m = light - c * 0.5f
        val (r1, g1, b1) = when {
            h < 60f -> Triple(c, x, 0f)
            h < 120f -> Triple(x, c, 0f)
            h < 180f -> Triple(0f, c, x)
            h < 240f -> Triple(0f, x, c)
            h < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        return Color(r1 + m, g1 + m, b1 + m, 1f)
    }

    @Test
    fun footerButtonStylesMeetContrastAcrossAllFourThemes() {
        data class ThemeCase(
            val name: String,
            val isGlass: Boolean,
            val isMilkGlass: Boolean,
            val isDark: Boolean,
            val backdrops: List<Color>,
        )
        val themes = listOf(
            ThemeCase("default_light", isGlass = false, isMilkGlass = false, isDark = false, listOf(Color(0xFFF2F3F5))),
            ThemeCase("default_dark", isGlass = false, isMilkGlass = false, isDark = true, listOf(Color(0xFF17191C))),
            ThemeCase("retro_glass", isGlass = true, isMilkGlass = false, isDark = true, listOf(Color(0xFF17191D))),
            ThemeCase("milk_glass", isGlass = false, isMilkGlass = true, isDark = false, listOf(Color(0xFF55504A), Color(0xFFB8B0A4))),
        )
        val hueSamples = buildList {
            for (deg in 0..345 step 15) {
                for (sat in listOf(0.25f, 0.6f, 1.0f)) {
                    add("h${deg}_s$sat" to testHslColor(deg.toFloat(), sat, 0.5f))
                }
            }
            add("black" to Color(0xFF000000))
            add("gray" to Color(0xFF808080))
            add("white" to Color(0xFFFFFFFF))
            add("yellow_peak" to Color(0xFFFFE600))
        }
        val featuredColors = listOf(
            "Blue40" to Blue40,
            "PinkPurple" to Color(0xFFDF78D8),
            "Teal40" to Teal40,
            "Amber40" to Amber40,
        )

        for (t in themes) {
            val rawPaper = shelfBookPaperSurface(t.isGlass, t.isMilkGlass, t.isDark)
            val delete = shelfBookDeleteStyle(t.isGlass, t.isMilkGlass, t.isDark)
            assertTrue("${t.name}/delete outerBorder must be visible", delete.outerBorder.alpha > 0f)
            assertTrue("${t.name}/delete radialSheen must be visible", delete.radialSheen.alpha > 0f)
            assertEquals("${t.name}/delete should keep smooth paper-like fill without cloth grain", 0f, delete.clothAlpha, 0.0001f)
            for (backdrop in t.backdrops) {
                val paper = rawPaper.compositeOver(backdrop)
                val avgFill = Color(
                    red = (delete.fillTop.red + delete.fillBottom.red) * 0.5f,
                    green = (delete.fillTop.green + delete.fillBottom.green) * 0.5f,
                    blue = (delete.fillTop.blue + delete.fillBottom.blue) * 0.5f,
                    alpha = (delete.fillTop.alpha + delete.fillBottom.alpha) * 0.5f,
                ).compositeOver(paper)
                val ratio = wcagContrast(delete.content, avgFill)
                assertTrue("${t.name}/delete contrast=$ratio on $backdrop must be >= 5.0", ratio >= 5.0f)
            }

            for ((sampleName, primary) in hueSamples + featuredColors) {
                val launch = shelfBookLaunchStyle(primary, t.isGlass, t.isMilkGlass, t.isDark)
                assertTrue("${t.name}/launch[$sampleName] outerBorder must be visible", launch.outerBorder.alpha > 0f)
                assertTrue("${t.name}/launch[$sampleName] radialSheen must be visible", launch.radialSheen.alpha > 0f)
                assertTrue("${t.name}/launch[$sampleName] clothAlpha must be > 0", launch.clothAlpha > 0f)
                for (backdrop in t.backdrops) {
                    val paper = rawPaper.compositeOver(backdrop)
                    val avgFill = Color(
                        red = (launch.fillTop.red + launch.fillBottom.red) * 0.5f,
                        green = (launch.fillTop.green + launch.fillBottom.green) * 0.5f,
                        blue = (launch.fillTop.blue + launch.fillBottom.blue) * 0.5f,
                        alpha = (launch.fillTop.alpha + launch.fillBottom.alpha) * 0.5f,
                    ).compositeOver(paper)
                    val ratio = wcagContrast(launch.content, avgFill)
                    assertTrue("${t.name}/launch[$sampleName] contrast=$ratio on $backdrop must be >= 5.8", ratio >= 5.8f)
                    if (sampleName in listOf("Blue40", "PinkPurple", "Teal40")) {
                        assertTrue("${t.name}/launch[$sampleName] featured contrast=$ratio on $backdrop must be >= 6.5", ratio >= 6.5f)
                    }
                }
            }
        }
    }

    @Test
    fun spreadLeftPageTracksCoverClosingRotationAndFadesBeforeVertical() {
        assertEquals(0f, shelfBookSpreadLeftRotationY(yawDeg = 0f, coverProgress = 1f, hingeAngle = 0f), 0.001f)
        assertEquals(1f, shelfBookSpreadLeftRevealAlpha(coverProgress = 1f, coverRotationYDeg = -180f), 0.0001f)
        assertEquals(16f, shelfBookSpreadLeftRotationY(yawDeg = 0f, coverProgress = 1f, hingeAngle = 16f), 0.001f)

        var prevRot = -1f
        var prevAlpha = 2f
        for (step in 10 downTo 5) {
            val cover = step / 10f
            val coverRotY = shelfBookCoverRotationY(yawDeg = 0f, coverProgress = cover, hingeAngle = 0f)
            val leftRotY = shelfBookSpreadLeftRotationY(yawDeg = 0f, coverProgress = cover, hingeAngle = 0f)
            val reveal = shelfBookSpreadLeftRevealAlpha(coverProgress = cover, coverRotationYDeg = coverRotY)
            assertEquals(coverRotY - BOOK_COVER_TURN, leftRotY, 0.001f)
            assertTrue("leftRotY should grow monotonically toward 90 deg as book closes", leftRotY >= prevRot - 0.001f)
            assertTrue("left reveal alpha should decay monotonically as book closes", reveal <= prevAlpha + 0.0001f)
            if (cover <= 0.60f || coverRotY >= -90f) {
                assertEquals(0f, reveal, 0.0001f)
            }
            prevRot = leftRotY
            prevAlpha = reveal
        }
        assertEquals(90f, shelfBookSpreadLeftRotationY(yawDeg = 0f, coverProgress = 0.5f, hingeAngle = 0f), 0.001f)
        assertEquals(0f, shelfBookSpreadLeftRevealAlpha(coverProgress = 0.7f, coverRotationYDeg = -90f), 0.0001f)
    }

    @Test
    fun flightTiltMatchesShelfHeroAtRestAndFlattensSmoothly() {
        assertEquals(HERO_TILT_DEGREES, shelfBookTiltDegrees(0f, hasOrigin = true), 0.0001f)
        assertEquals(0f, shelfBookTiltDegrees(0.65f, hasOrigin = true), 0.0001f)
        assertEquals(0f, shelfBookTiltDegrees(1f, hasOrigin = true), 0.0001f)
        assertEquals(0f, shelfBookTiltDegrees(0f, hasOrigin = false), 0.0001f)
        var prev = HERO_TILT_DEGREES
        for (i in 0..13) {
            val f = i * 0.05f
            val deg = shelfBookTiltDegrees(f, hasOrigin = true)
            assertTrue("tilt must monotonically decrease at f=$f ($deg > $prev)", deg <= prev + 0.0001f)
            assertTrue("tilt must stay within [0, HERO_TILT_DEGREES]", deg in 0f..HERO_TILT_DEGREES)
            prev = deg
        }
    }

    @Test
    fun flightTranslationPinsSpineBottomCornerAlongStraightLine() {
        val originLeft = 290f
        val originBottom = 940f
        val bookLeft = 640f
        val bookWidth = 520f
        val bookTop = 180f
        val bookHeight = 780f
        val bookBottom = bookTop + bookHeight
        val startScaleX = 320f / bookWidth
        for (step in 0..20) {
            val f = step / 20f
            val sx = startScaleX + (1f - startScaleX) * f
            val tx = shelfBookFlightTranslationX(originLeft, bookLeft, bookWidth, f, sx)
            val ty = shelfBookFlightTranslationY(originBottom, bookBottom, f)
            // 以 TransformOrigin(0.5f, 1f) 变换书脊底角 (x=0, y=bookHeight)：
            // rotationX 不影响底边点 (y=bookHeight) 与横坐标缩放
            val cornerX = bookLeft + bookWidth * 0.5f + (0f - bookWidth * 0.5f) * sx + tx
            val cornerY = bookBottom + ty
            val expectedX = originLeft * (1f - f) + bookLeft * f
            val expectedY = originBottom * (1f - f) + bookBottom * f
            assertEquals("cornerX mismatch at f=$f", expectedX, cornerX, 1e-4f)
            assertEquals("cornerY mismatch at f=$f", expectedY, cornerY, 1e-4f)
            if (step == 20) {
                assertEquals(0f, tx, 1e-4f)
                assertEquals(0f, ty, 1e-4f)
            }
        }
    }

    @Test
    fun lentGroundAlphaIsContinuousFromAirToShelfRest() {
        assertEquals(1f, shelfLentGroundAlpha(0f), 1e-4f)
        assertEquals(0.5f, shelfLentGroundAlpha(0.24f), 1e-4f)
        assertEquals(0f, shelfLentGroundAlpha(0.48f), 1e-4f)
        assertEquals(0f, shelfLentGroundAlpha(1f), 1e-4f)
        var prev = 1f
        for (i in 1..20) {
            val f = i / 20f
            val a = shelfLentGroundAlpha(f)
            assertTrue("ground alpha must stay within [0, 1]", a in 0f..1f)
            assertTrue("ground alpha must monotonically decrease at f=$f ($a > $prev)", a <= prev + 1e-5f)
            prev = a
        }
    }

    @Test
    fun clippedBoardWidthStaysFiniteWithinBoundsForNarrowInputs() {
        for (total in listOf(1f, 12f, 240f, 800f)) {
            for (base in listOf(0.5f, 1f, 8f, total)) {
                val safeBase = base.coerceIn(1f, total)
                for (rotY in listOf(-180f, -135f, -90f, -45f, 0f)) {
                    val width = shelfBookEffectiveClippedWidthPx(
                        baseWidthPx = safeBase,
                        pageWidthPx = total,
                        rotationYDeg = rotY,
                        cameraDistancePx = 2400f,
                    )
                    assertTrue("clipped width not finite ($total, $safeBase, $rotY)", width.isFinite())
                    assertTrue(
                        "clipped width $width out of [1f, $total] for ($safeBase, $rotY)",
                        width >= minOf(1f, total) - 1e-4f && width <= total + 1e-4f,
                    )
                }
            }
        }
    }
}
