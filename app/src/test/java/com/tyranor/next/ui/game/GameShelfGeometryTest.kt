package com.tyranor.next.ui.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.game.model.ScanGame
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.math.abs

/**
 * 游戏页「书架」卡片风格的纯几何：盒子位置、朝向、翻页途中互不穿插、整排平移、自适应转角判定、书脊竖排文字、
 * 西文书名判定、每本书的个性与进度旋钮的浓度。
 * 只测数值性质（对称、单调、停稳间距、连续、不重叠），不涉及绘制。
 */
class GameShelfGeometryTest {

    @Test
    fun phoneLandscapeGivesTheStagePriorityOverInfoAndScrubber() {
        val afterTopBarsAndNavigation = 160.dp
        val chrome = shelfChromeForHeight(afterTopBarsAndNavigation, fontScale = 1f, multipleGames = true)
        assertEquals(0.dp, chrome.infoHeight)
        assertFalse(chrome.showScrubber)
        val coverWidth = shelfCoverWidthForHeight(afterTopBarsAndNavigation)
        assertTrue(coverWidth > 60.dp)
        assertTrue(coverWidth * (4f / 3f) < afterTopBarsAndNavigation)
    }

    @Test
    fun tallerWindowsGraduallyRestoreShelfChromeWithoutShrinkingTheStage() {
        val short = shelfChromeForHeight(285.dp, fontScale = 1f, multipleGames = true)
        assertTrue(short.showScrubber)
        assertEquals(0.dp, short.infoHeight)
        val regular = shelfChromeForHeight(510.dp, fontScale = 1f, multipleGames = true)
        assertTrue(regular.showScrubber)
        assertTrue(regular.infoHeight >= 150.dp)
        assertEquals(0.dp, shelfChromeForHeight(510.dp, fontScale = 2f, multipleGames = true).infoHeight)
        assertFalse(shelfChromeForHeight(510.dp, fontScale = 1f, multipleGames = false).showScrubber)
    }

    @Test
    fun liftedHeroRetainsReflectionAtMinimumStageWithoutMovingNormalReflections() {
        val depth = 128f * 0.14f
        for (scale in listOf(0.5f, 1f)) {
            val frontEdge = depth * scale
            val lift = 18f * scale
            val reflectedFoot = lift - shelfReflectionOverflow(lift, frontEdge)
            assertEquals(3.584f * scale, frontEdge - reflectedFoot, 0.001f)
            assertEquals(0f, shelfReflectionOverflow(0f, frontEdge), 0f)
        }
        assertEquals(0f, shelfReflectionOverflow(18f, 22.5f), 0f)
        assertEquals(0f, shelfReflectionOverflow(18f, 260f * 0.14f), 0f)
    }

    private fun geometry(maxTurn: Float = SHELF_SPINE_TURN) = ShelfGeometry(
        coverWidth = 500f,
        coverHeight = 667f,
        spineDepth = 75f,
        gap = 8f,
        restGap = 30f,
        maxTurn = maxTurn,
    )

    @Test
    fun centerIsZeroAndLayoutIsSymmetric() {
        val g = geometry()
        assertEquals(0f, g.centerX(0f), 1e-4f)
        for (d in listOf(0.2f, 0.5f, 1f, 1.7f, 4f)) {
            assertEquals(-g.centerX(d), g.centerX(-d), 1e-3f)
        }
    }

    @Test
    fun positionsIncreaseMonotonically() {
        val g = geometry()
        var previous = g.centerX(-6f)
        var d = -6f + 0.01f
        while (d <= 6f) {
            val x = g.centerX(d)
            assertTrue("centerX must increase at d=$d", x > previous)
            previous = x
            d += 0.01f
        }
    }

    @Test
    fun settledNeighbourKeepsRestGapAndFarSpinesUsePitch() {
        val g = geometry()
        // 停稳时：居中盒子与第一本书脊之间 = 书脊缝 + 呼吸位
        val edgeGap = g.centerX(1f) - g.coverWidth / 2f - g.spineVisible / 2f
        assertEquals(8f + 30f, edgeGap, 0.5f)
        // 更远处按书脊间距等距排布
        for (d in 1..5) {
            assertEquals(g.pitch, g.centerX(d + 1f) - g.centerX(d.toFloat()), 1e-2f)
        }
    }

    @Test
    fun dragPitchKeepsTheActualCenterToNeighbourDistanceAcrossShelfTurns() {
        for (turn in listOf(Math.toRadians(58.0).toFloat(), SHELF_SPINE_TURN)) {
            val geometry = geometry(maxTurn = turn)
            val neighbourDistance = geometry.centerX(1f) - geometry.centerX(0f)
            assertEquals(neighbourDistance, geometry.dragPitch, 1e-3f)
            assertTrue(geometry.dragPitch >= geometry.pitch)
        }
    }

    @Test
    fun slopeIsContinuousWhereTurningMeetsSpineRow() {
        val g = geometry()
        val e = 1e-3f
        val inner = (g.centerX(1f) - g.centerX(1f - e)) / e
        val outer = (g.centerX(1f + e) - g.centerX(1f)) / e
        assertEquals(g.pitch, inner, g.pitch * 0.02f)
        assertEquals(g.pitch, outer, g.pitch * 0.02f)
    }

    @Test
    fun restPosesShowHeroYawAndSpines() {
        val g = geometry()
        assertEquals(g.heroYaw, g.yaw(0f), 1e-4f)
        assertEquals(g.maxTurn, g.yaw(1f), 1e-4f)
        assertEquals(-g.maxTurn, g.yaw(-1f), 1e-4f)
        assertEquals(g.maxTurn, g.yaw(3f), 1e-4f)
        assertEquals(-g.maxTurn, g.yaw(-3f), 1e-4f)
        assertEquals(g.spineVisible, g.visibleWidth(abs(g.yaw(-1f))), 1e-3f)
    }

    /** 盒子在屏幕上真实画出来的宽度：朝向决定的投影宽度 × 姿态缩放（与 shelfBoxPose 同一个函数）。 */
    private fun ShelfGeometry.drawnWidth(offset: Float): Float =
        visibleWidth(abs(yaw(offset))) * shelfPoseScale(offset)

    @Test
    fun neighboursNeverOverlapWhileTurning() {
        // 翻页途中任意位置，相邻两盒真实画出来的宽度都不重叠（曾在折叠到一半时互相压住约 28dp）
        for (degrees in listOf(58.0, 64.0, 70.0, 74.0, 80.0, 86.0)) {
            val g = geometry(maxTurn = Math.toRadians(degrees).toFloat())
            var position = 0f
            while (position <= 1f) {
                for (k in -3..3) {
                    val right = k - position
                    val left = right - 1f
                    val clearance = (g.centerX(right) - g.drawnWidth(right) / 2f) -
                        (g.centerX(left) + g.drawnWidth(left) / 2f)
                    assertTrue("books at $left / $right overlap by ${-clearance} at $degrees°", clearance > 0f)
                }
                position += 0.005f
            }
        }
    }

    @Test
    fun turningIsContinuous() {
        // 左侧的书换边时朝向会从 +90° 跳到 -90°（两种画法完全一样），但画出来的宽度与位置都是连续的
        for (degrees in listOf(58.0, 86.0)) {
            val g = geometry(maxTurn = Math.toRadians(degrees).toFloat())
            var d = -1.5f
            var previousWidth = g.drawnWidth(d)
            var previousX = g.centerX(d)
            d += 0.001f
            while (d <= 1.5f) {
                val width = g.drawnWidth(d)
                val x = g.centerX(d)
                assertTrue("width jumps at d=$d ($degrees°)", abs(width - previousWidth) < g.coverWidth * 0.02f)
                assertTrue("position jumps at d=$d ($degrees°)", abs(x - previousX) < g.coverWidth * 0.02f)
                previousWidth = width
                previousX = x
                d += 0.001f
            }
        }
    }

    @Test
    fun rowShiftCentersARowThatFits() {
        val g = geometry(maxTurn = Math.toRadians(60.0).toFloat())
        val endPad = 40f
        val viewport = g.maxRowWidth(2, endPad) + 100f
        for (position in listOf(0f, 0.5f, 1f)) {
            val shift = g.rowShift(position, 2, viewport, endPad)
            val left = g.centerX(-position) - g.halfWidth(-position) - endPad + shift
            val right = g.centerX(1f - position) + g.halfWidth(1f - position) + endPad + shift
            assertEquals("row must be centred at $position", 0f, left + right, 0.5f)
        }
    }

    @Test
    fun rowShiftPinsEndsOfALongRowAndStaysContinuous() {
        val g = geometry()
        val endPad = 40f
        val count = 30
        val viewport = 1080f
        // 第一本时左端贴边：第一本左缘（含书立）正好落在屏幕左缘
        val shift = g.rowShift(0f, count, viewport, endPad)
        val left = g.centerX(0f) - g.halfWidth(0f) - endPad + shift
        assertEquals(-viewport / 2f, left, 0.5f)
        // 中段不平移
        assertEquals(0f, g.rowShift(count / 2f, count, viewport, endPad), 1e-3f)
        // 全程连续
        var previous = g.rowShift(0f, count, viewport, endPad)
        var position = 0.01f
        while (position <= count - 1f) {
            val current = g.rowShift(position, count, viewport, endPad)
            assertTrue("rowShift jumps at $position", abs(current - previous) < g.pitch * 0.2f)
            previous = current
            position += 0.01f
        }
    }

    @Test
    fun shallowTurnNeedsMoreRoomThanSpines() {
        val endPad = 40f
        val open = geometry(maxTurn = Math.toRadians(58.0).toFloat()).maxRowWidth(3, endPad)
        val spines = geometry().maxRowWidth(3, endPad)
        assertTrue(open > spines)
    }

    @Test
    fun spineTitleStacksCharactersAndTruncates() {
        assertEquals("夏\n空", stackSpineTitle("夏空", 5))
        assertEquals("a\n \nb", stackSpineTitle("  a   b  ", 5))
        assertEquals("1\n2\n⋮", stackSpineTitle("12345", 3))
        assertEquals("⋮", stackSpineTitle("12345", 1))
    }

    @Test
    fun bookTraitIsStableAndBounded() {
        val a = shelfBookTrait("content://games/a")
        val again = shelfBookTrait("content://games/a")
        assertEquals(a.sizeScale, again.sizeScale, 0f)
        assertEquals(a.restLeanDegrees, again.restLeanDegrees, 0f)
        for (i in 0 until 200) {
            val trait = shelfBookTrait("content://games/$i")
            assertTrue(trait.sizeScale in 0.95f..1f)
            assertTrue(trait.restLeanDegrees in setOf(0f, 1.3f, -1.3f))
        }
    }

    @Test
    fun latinTitlesAreDetected() {
        assertTrue(isMostlyLatin("Crystal Rondo"))
        assertTrue(isMostlyLatin("[汉化] Summer Pockets"))
        assertFalse(isMostlyLatin("樱花季节"))
        assertFalse(isMostlyLatin("光雨回响 HD"))
        assertFalse(isMostlyLatin("   "))
    }

    @Test
    fun trackKnobAlphaRisesWithPresence() {
        assertEquals(1f, trackKnobAlpha(1f), 1e-4f)
        val idle = trackKnobAlpha(0f)
        assertTrue(idle in 0.5f..0.99f)
        var previous = idle
        var presence = 0f
        while (presence <= 1f) {
            val alpha = trackKnobAlpha(presence)
            assertTrue("knob alpha must not drop at presence=$presence", alpha >= previous - 1e-6f)
            previous = alpha
            presence += 0.05f
        }
    }

    @Test
    fun rubberBandGivesBackExactlyWhatItTook() {
        // 假书架：位置 0..1000，往正方向拖时位置减小（到 0 就是第一本，再拖就越界）
        var position = 50f
        val scroll: (Offset) -> Offset = { delta ->
            val next = (position - delta.x).coerceIn(0f, 1000f)
            val consumed = position - next
            position = next
            Offset(consumed, 0f)
        }
        val band = ShelfRubberBand(maxPull = 300f, scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined))
        repeat(5) { band.applyToScroll(Offset(30f, 0f), NestedScrollSource.UserInput, scroll) }
        assertEquals(0f, position, 1e-3f)
        assertTrue(band.isInProgress)
        // 原路拖回同样的距离：越界的那段先原样还回去，书架回到原位，不会多翻也不会停在两页之间
        repeat(5) { band.applyToScroll(Offset(-30f, 0f), NestedScrollSource.UserInput, scroll) }
        assertEquals(50f, position, 1e-3f)
        assertFalse(band.isInProgress)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun cancelledRecoveryCannotStealInwardDragFromItsReplacement() = runTest {
        val clock = BroadcastFrameClock()
        val band = ShelfRubberBand(maxPull = 300f, scope = backgroundScope + clock)
        band.applyToScroll(Offset(120f, 0f), NestedScrollSource.UserInput) { Offset.Zero }
        val inward = Velocity(-400f, 0f)
        val firstFling = backgroundScope.launch { band.applyToFling(inward) { awaitCancellation() } }
        runCurrent()
        assertTrue(clock.hasAwaiters)

        firstFling.cancel()
        backgroundScope.launch { band.applyToFling(inward) { awaitCancellation() } }
        runCurrent()
        assertTrue(clock.hasAwaiters)

        var handedToPager = Offset.Zero
        val drag = Offset(-20f, 0f)
        band.applyToScroll(drag, NestedScrollSource.UserInput) { delta ->
            handedToPager = delta
            delta
        }
        assertEquals(drag, handedToPager)
        assertTrue(band.isInProgress)
    }

    @Test
    fun shelfFocusSurvivesReorderingAndFallsBackOnlyWhenMissing() {
        val recent = ScanGame("A", "a", EngineType.UNKNOWN, "", openTime = 10L)
        val focused = recent.copy(title = "B", uri = "b", openTime = 0L)
        val other = focused.copy(title = "C", uri = "c")
        assertEquals(2, shelfFocusPage(listOf(other, recent, focused), focused.uri))
        assertEquals(1, shelfFocusPage(listOf(other, recent), focused.uri))
        assertEquals(0, shelfFocusPage(listOf(other), focused.uri))
    }

    @Test
    fun shelfListRefreshOnlyAdjustsWhenTheMeasuredBookMovesOrIsRemoved() {
        val recent = ScanGame("A", "a", EngineType.UNKNOWN, "", openTime = 10L)
        val current = recent.copy(title = "B", uri = "b", openTime = 0L)
        val other = current.copy(title = "C", uri = "c")
        assertEquals(null, shelfPageAdjustment(listOf(recent, current.copy(title = "New title"), other), 1, current.uri))
        assertEquals(202, shelfPageAdjustment(listOf(recent, other) + List(200) { other.copy(uri = "other-$it") } + current, 1, current.uri))
        assertEquals(0, shelfPageAdjustment(listOf(recent, other), 1, current.uri))
        assertEquals(null, shelfPageAdjustment(emptyList(), 1, current.uri))
    }

    @Test
    fun titlesAreLimitedByCharacterCount() {
        assertEquals("樱花季节", limitShelfTitle("樱花季节", 8))
        assertEquals("哥哥，早上起床…", limitShelfTitle("哥哥，早上起床之前都要抱紧我哦！FD", 8))
        assertEquals("Crystal Rondo", limitShelfTitle("  Crystal Rondo ", 16))
        assertEquals("Midnight Café…", limitShelfTitle("Midnight Café Encore Edition", 15))
        assertEquals("…", limitShelfTitle("abc", 1))
    }

    @Test
    fun glideLandsOnABookAtLeastOneStepAhead() {
        // 至少往甩的方向走一本；滑行不到一本半时就只走这一本（与松手前拖过多少无关），更远才按终点就近取整
        assertEquals(4, shelfGlideTarget(position = 3f, travelPages = 0.2f, last = 20))
        assertEquals(5, shelfGlideTarget(position = 3f, travelPages = 2.4f, last = 20))
        assertEquals(6, shelfGlideTarget(position = 3f, travelPages = 2.6f, last = 20))
        assertEquals(2, shelfGlideTarget(position = 3f, travelPages = -0.2f, last = 20))
        assertEquals(4, shelfGlideTarget(position = 3.3f, travelPages = 0.1f, last = 20))
        assertEquals(3, shelfGlideTarget(position = 3.3f, travelPages = -0.1f, last = 20))
        assertEquals(12, shelfGlideTarget(position = 3f, travelPages = 9f, last = 20))
        assertEquals(4, shelfGlideTarget(position = 3.45f, travelPages = 1.2f, last = 20))
        assertEquals(4, shelfGlideTarget(position = 3.7f, travelPages = 1.4f, last = 20))
        assertEquals(3, shelfGlideTarget(position = 3.6f, travelPages = -1.4f, last = 20))
        // 轻滑翻到最后一本：落在它上面（Q 弹），不当成撞边
        assertEquals(9, shelfGlideTarget(position = 8f, travelPages = 1.2f, last = 9))
    }

    @Test
    fun glidePastEitherEndRunsIntoTheEdge() {
        assertEquals(null, shelfGlideTarget(position = 7.5f, travelPages = 5f, last = 9))
        assertEquals(null, shelfGlideTarget(position = 8f, travelPages = 2f, last = 9))
        assertEquals(null, shelfGlideTarget(position = 0.2f, travelPages = -2f, last = 9))
        // 已经在头上还往外甩：交给撞边（橡皮筋顶一下）
        assertEquals(null, shelfGlideTarget(position = 9f, travelPages = 0.4f, last = 9))
        assertEquals(null, shelfGlideTarget(position = 0f, travelPages = -0.4f, last = 9))
        assertEquals(8, shelfGlideTarget(position = 7f, travelPages = 1.2f, last = 9))
    }

    @Test
    fun glideStartsAtFingerSpeedAndStopsExactly() {
        for ((distance, speed) in listOf(1200f to 4000f, 40f to 3000f, 30000f to 9000f, 400f to 1500f)) {
            val curve = ShelfGlideCurve(distance, speed)
            // 起步速度连续（夹住时长时改幂次保证这一点），到站时正好走完、速度归零
            assertEquals(speed, curve.velocityAt(0f), speed * 1e-3f)
            assertEquals(distance, curve.offsetAt(curve.seconds), distance * 1e-5f)
            assertEquals(0f, curve.velocityAt(curve.seconds), 1e-3f)
            var previous = 0f
            var t = 0f
            while (t <= curve.seconds) {
                val x = curve.offsetAt(t)
                assertTrue("offset must not go backwards at t=$t", x >= previous - 1e-3f)
                assertTrue("never overshoots", x <= distance + 1e-3f)
                previous = x
                t += 1f / 120f
            }
        }
    }

    @Test
    fun glideSlowsDownMonotonically() {
        val curve = ShelfGlideCurve(2000f, 5000f)
        var previous = Float.MAX_VALUE
        var t = 0f
        while (t <= curve.seconds) {
            val v = curve.velocityAt(t)
            assertTrue("speed must only decrease at t=$t", v <= previous + 1e-3f)
            previous = v
            t += 1f / 120f
        }
    }

    @Test
    fun glideKeepsALittleSpeedForTheLandingBounce() {
        for ((distance, speed) in listOf(1200f to 4000f, 420f to 1500f, 60f to 2500f, 9000f to 12000f)) {
            val curve = ShelfGlideCurve(distance, speed, arrival = speed * 0.25f)
            assertTrue(curve.arrival > 0f)
            assertTrue("arrival never exceeds half the start speed", curve.arrival <= speed * 0.5f + 1e-3f)
            // 起步速度仍等于手指速度，到站时正好走完、只剩到站速度（交给吸附弹簧做 Q 弹）
            assertEquals(speed, curve.velocityAt(0f), speed * 1e-3f)
            assertEquals(distance, curve.offsetAt(curve.seconds), distance * 1e-4f)
            assertEquals(curve.arrival, curve.velocityAt(curve.seconds), 1e-2f)
            var previousX = 0f
            var previousV = Float.MAX_VALUE
            var t = 0f
            while (t <= curve.seconds) {
                val x = curve.offsetAt(t)
                val v = curve.velocityAt(t)
                assertTrue("offset must not go backwards at t=$t", x >= previousX - 1e-3f)
                assertTrue("speed must only decrease at t=$t", v <= previousV + 1e-3f)
                previousX = x
                previousV = v
                t += 1f / 120f
            }
        }
    }

    @Test
    fun lightFlicksMoveOneBookStrongFlingsRunFar() {
        // 与真机一致：3.5x 密度、一本 = 119dp 的拖动距离。系统样条惯性的滑行距离按 Android OverScroller 的公式算
        // （SplineBasedFloatDecayAnimationSpec 要读平台的 ViewConfiguration，JVM 单测里拿不到）
        val density = 3.5f
        val page = 119f * density
        val decelRate = (kotlin.math.ln(0.78) / kotlin.math.ln(0.9)).toFloat()
        val physical = 9.80665f * 39.37f * density * 160f * 0.84f * 0.015f
        fun splineDistance(velocity: Float): Float {
            val l = kotlin.math.ln(0.35f * velocity / physical)
            return physical * kotlin.math.exp(decelRate / (decelRate - 1f) * l)
        }
        fun booksFor(speedDp: Float, dragged: Float = 0f): Int {
            val velocity = speedDp * density
            val travel = splineDistance(velocity) * shelfGlideReach(speedDp) / page
            return shelfGlideTarget(position = dragged, travelPages = travel, last = 500)!!
        }
        // 很轻到中等力度：都只翻一本，松手前拖过多少都一样
        for (speed in listOf(250f, 450f, 700f, 1000f, 1200f, 1500f, 1800f, 2000f)) {
            for (dragged in listOf(0f, 0.3f, 0.6f, 0.9f)) assertEquals("speed $speed dragged $dragged", 1, booksFor(speed, dragged))
        }
        // 再用力一点两三本，用力甩一下子滑出去很远
        assertTrue(booksFor(2400f) in 2..4)
        assertTrue(booksFor(3000f) >= 6)
        assertTrue(booksFor(6000f) >= 30)
        // 越用力滑得越远（不会出现更快反而更近）
        var previous = 0
        var speed = 250f
        while (speed <= 8000f) {
            val books = booksFor(speed)
            assertTrue("books must not drop at $speed", books >= previous)
            previous = books
            speed += 50f
        }
    }

    @Test
    fun infoPageStaysLockedToSeekTargetAcrossIntermediatePages() {
        // 从第 8 页点击底部控制条跳转到第 1 页：滑过中间页、停稳第 1 页、最后交回实时跟随，
        // 卡片显示的页码序列严格只从 8 变为 1，不冒出任何中间页
        val steps = listOf(
            // (currentPage, seekTarget, isScrollInProgress)
            Triple(8, null, false),
            Triple(8, 1, false),
            Triple(3, 1, false), // scrollToPage 瞬移到目标附近时 isScrollInProgress 可能短时为 false
            Triple(2, 1, true),  // animateScrollToPage 滑过中间页
            Triple(1, 1, true),
            Triple(1, 1, false),
            Triple(1, null, false), // 跳转收尾交接回 pagerState
        )
        val distinctShown = mutableListOf<Int>()
        for ((index, step) in steps.withIndex()) {
            val (currentPage, seekTarget, scrolling) = step
            val resolved = requireNotNull(resolveShelfInfoPage(
                currentPage = currentPage,
                seekTarget = seekTarget,
                pageCount = 10,
                isScrollInProgress = scrolling,
                isDragged = false,
            ))
            if (index == 0) {
                assertEquals(8, resolved.page)
            } else {
                assertEquals("step $index ($step) must lock onto target page 1", 1, resolved.page)
                assertEquals(if (seekTarget != null) ShelfInfoUpdateMode.Seek else ShelfInfoUpdateMode.Settled, resolved.mode)
            }
            if (distinctShown.lastOrNull() != resolved.page) {
                distinctShown += resolved.page
            }
        }
        assertEquals(listOf(8, 1), distinctShown)
    }

    @Test
    fun infoPageSwitchesDirectlyToLatestTargetOnRapidTaps() {
        // 连续快速点击控制条：先从 8 点去 1，中途在第 3 页时又点去 6，期间经历瞬移 4、滑过 5、到达 6，
        // 只依次出现 [8, 1, 6]，绝不闪烁经过任何中间页
        val steps = listOf(
            Triple(8, null, false),
            Triple(8, 1, false),
            Triple(3, 1, true),
            Triple(3, 6, true),  // 连点切换目标为 6
            Triple(4, 6, false), // 瞬移到目标前 2 本
            Triple(5, 6, true),
            Triple(6, 6, true),
            Triple(6, null, false),
        )
        val distinctShown = mutableListOf<Int>()
        for ((index, step) in steps.withIndex()) {
            val (currentPage, seekTarget, scrolling) = step
            val resolved = requireNotNull(resolveShelfInfoPage(
                currentPage = currentPage,
                seekTarget = seekTarget,
                pageCount = 10,
                isScrollInProgress = scrolling,
                isDragged = false,
            ))
            if (index in 1..2) assertEquals(1, resolved.page)
            if (index >= 3) assertEquals(6, resolved.page)
            if (distinctShown.lastOrNull() != resolved.page) {
                distinctShown += resolved.page
            }
        }
        assertEquals(listOf(8, 1, 6), distinctShown)
    }

    @Test
    fun infoPageDistinguishesDraggingSettledAndFling() {
        assertEquals(
            ShelfInfoResolution(4, ShelfInfoUpdateMode.Settled),
            resolveShelfInfoPage(currentPage = 4, seekTarget = null, pageCount = 10, isScrollInProgress = false, isDragged = false),
        )
        assertEquals(
            ShelfInfoResolution(4, ShelfInfoUpdateMode.Dragging),
            resolveShelfInfoPage(currentPage = 4, seekTarget = null, pageCount = 10, isScrollInProgress = true, isDragged = true),
        )
        // 手松开后惯性滑行 / Q 弹途中也跟着当前页走（不再等到停稳）
        assertEquals(
            ShelfInfoResolution(4, ShelfInfoUpdateMode.Gliding),
            resolveShelfInfoPage(currentPage = 4, seekTarget = null, pageCount = 10, isScrollInProgress = true, isDragged = false),
        )
        // 越界夹紧
        assertEquals(
            ShelfInfoResolution(9, ShelfInfoUpdateMode.Seek),
            resolveShelfInfoPage(currentPage = 3, seekTarget = 99, pageCount = 10, isScrollInProgress = false, isDragged = false),
        )
        assertEquals(
            ShelfInfoResolution(0, ShelfInfoUpdateMode.Settled),
            resolveShelfInfoPage(currentPage = -2, seekTarget = null, pageCount = 10, isScrollInProgress = false, isDragged = false),
        )
        assertEquals(
            ShelfInfoResolution(0, ShelfInfoUpdateMode.Seek),
            resolveShelfInfoPage(currentPage = 3, seekTarget = 5, pageCount = 0, isScrollInProgress = true, isDragged = false),
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun infoPageFollowsDragAndGlideInsteadOfWaitingForTheShelfToSettle() = runTest {
        val candidate = mutableStateOf<ShelfInfoResolution?>(null)
        val info = ShelfInfoPage(initialPage = 0)
        backgroundScope.launch { info.follow({ testScheduler.currentTime }) { candidate.value } }
        fun update(page: Int, dragging: Boolean, scrolling: Boolean = true) {
            candidate.value = resolveShelfInfoPage(page, null, 10, scrolling, dragging)
            Snapshot.sendApplyNotifications()
            runCurrent()
        }

        // 手指拖着书架：第一次变化立刻跟上（不用先停够一段时间）
        update(page = 1, dragging = true)
        assertEquals(1, info.shown)
        // 间隔不到最短间隔时先不换，到点换成「最新」的一本，不是排队里的旧页
        update(page = 2, dragging = true)
        update(page = 3, dragging = true)
        advanceTimeBy(79L)
        runCurrent()
        assertEquals(1, info.shown)
        advanceTimeBy(1L)
        runCurrent()
        assertEquals(3, info.shown)

        // 手松开后惯性滑行 / 回弹途中同样跟着走，不冻结到停稳
        update(page = 6, dragging = false, scrolling = true)
        advanceTimeBy(80L)
        runCurrent()
        assertEquals(6, info.shown)
        update(page = 6, dragging = false, scrolling = false)
        assertEquals(6, info.shown)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun infoPageKeepsUpEvenWhenThePageChangesFasterThanTheMinimumInterval() = runTest {
        val candidate = mutableStateOf<ShelfInfoResolution?>(null)
        val info = ShelfInfoPage(initialPage = 0)
        backgroundScope.launch { info.follow({ testScheduler.currentTime }) { candidate.value } }
        fun update(page: Int) {
            candidate.value = resolveShelfInfoPage(page, null, 20, isScrollInProgress = true, isDragged = false)
            Snapshot.sendApplyNotifications()
            runCurrent()
        }
        // 快速甩过去：每 30ms 换一本。限速不能是「消抖」——一直在变就永远不更新
        var page = 0
        val shownPages = mutableListOf<Int>()
        repeat(20) {
            page++
            update(page)
            advanceTimeBy(30L)
            runCurrent()
            if (shownPages.lastOrNull() != info.shown) shownPages += info.shown
        }
        assertTrue("信息行在快速滑行期间应该多次更新，实际只显示了 $shownPages", shownPages.size >= 4)
        assertTrue("信息行不应落后当前页太多：shown=${info.shown}", page - info.shown <= 3)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun infoPageThrottlesSettledUpdatesButPreviewsExplicitSeeksImmediately() = runTest {
        val candidate = mutableStateOf<ShelfInfoResolution?>(null)
        val info = ShelfInfoPage(initialPage = 0)
        backgroundScope.launch { info.follow({ testScheduler.currentTime }) { candidate.value } }
        fun update(page: Int, seekTarget: Int? = null, scrolling: Boolean = false) {
            candidate.value = resolveShelfInfoPage(page, seekTarget, 10, scrolling, isDragged = false)
            Snapshot.sendApplyNotifications()
            runCurrent()
        }

        update(page = 1)
        assertEquals(1, info.shown)
        update(page = 2)
        advanceTimeBy(79L)
        runCurrent()
        assertEquals(1, info.shown)
        advanceTimeBy(1L)
        runCurrent()
        assertEquals(2, info.shown)

        update(page = 3)
        update(page = 3, seekTarget = 7)
        assertEquals(7, info.shown)
        update(page = 4, seekTarget = 8, scrolling = true)
        assertEquals(8, info.shown)
        update(page = 8)
        advanceTimeBy(80L)
        runCurrent()
        assertEquals(8, info.shown)
    }

    @Test
    fun shelfEntranceOrderStartsWithCaseAndHeroThenSpinesInfoScrubber() {
        assertEquals(SHELF_CASE_ENTRANCE_INDEX, shelfBookEntranceIndex(4, 4))
        assertEquals(shelfBookEntranceIndex(3, 4), shelfBookEntranceIndex(5, 4))
        assertTrue(shelfBookEntranceIndex(2, 4) > shelfBookEntranceIndex(3, 4))
        assertTrue(SHELF_CASE_ENTRANCE_INDEX < shelfBookEntranceIndex(5, 4))
        assertTrue(shelfBookEntranceIndex(5, 4) < SHELF_INFO_ENTRANCE_INDEX)
        assertTrue(SHELF_INFO_ENTRANCE_INDEX < SHELF_SCRUBBER_ENTRANCE_INDEX)
    }

    @Test
    fun scrubberRubberPullIsOddMonotonicAndBounded() {
        val maxPull = 42f
        assertEquals(0f, shelfScrubberRubberPull(0f, maxPull), 1e-6f)
        assertEquals(0f, shelfScrubberRubberPull(100f, 0f), 1e-6f)
        var prev = -maxPull
        for (raw in listOf(-500f, -120f, -40f, -5f, 0f, 5f, 40f, 120f, 500f)) {
            val pulled = shelfScrubberRubberPull(raw, maxPull)
            assertTrue("pulled=$pulled must stay strictly within (-$maxPull, $maxPull)", abs(pulled) < maxPull)
            assertTrue("pulled=$pulled must be strictly monotonic from $prev at raw=$raw", pulled > prev)
            assertEquals(-pulled, shelfScrubberRubberPull(-raw, maxPull), 1e-5f)
            prev = pulled
        }
    }

    @Test
    fun rulerVisualXMatchesLinearInsideTrackAndCushionsOvershootAtEnds() {
        val width = 1000f
        val inset = 100f
        val maxOver = 40f
        val count = 10
        for (pos in listOf(0f, 2.3f, 5f, 9f)) {
            assertEquals(
                shelfRulerX(pos, count, width, inset),
                shelfRulerVisualX(pos, count, width, inset, rubberPx = 0f, maxOverpullPx = maxOver),
                1e-4f,
            )
        }
        val under = shelfRulerVisualX(-0.8f, count, width, inset, rubberPx = 0f, maxOverpullPx = maxOver)
        assertTrue(under < inset && under >= inset - maxOver)
        val over = shelfRulerVisualX(9.8f, count, width, inset, rubberPx = 0f, maxOverpullPx = maxOver)
        assertTrue(over > width - inset && over <= width - inset + maxOver)

        val cappedLeft = shelfRulerVisualX(0f, count, width, inset, rubberPx = -200f, maxOverpullPx = maxOver)
        assertEquals(inset - maxOver, cappedLeft, 1e-4f)
        val cappedRight = shelfRulerVisualX(9f, count, width, inset, rubberPx = 200f, maxOverpullPx = maxOver)
        assertEquals(width - inset + maxOver, cappedRight, 1e-4f)
    }

    @Test
    fun knobScaleStaysUniformBoundedAndIdentityAtRest() {
        assertEquals(1f, shelfKnobScale(lift = 0f, liftScale = 0.22f), 1e-6f)
        assertEquals(1.22f, shelfKnobScale(lift = 1f, liftScale = 0.22f), 1e-6f)
        // 过冲 / 反向的 lift 也不会把旋钮缩得太小或鼓得太大
        assertTrue(shelfKnobScale(lift = -4f, liftScale = 0.22f) >= 0.9f)
        assertTrue(shelfKnobScale(lift = 9f, liftScale = 0.22f) <= 1.5f)
    }

    @Test
    fun railAndRowShiftRemainFiniteAtZeroViewportAndExtremeCoordinates() {
        val g = geometry()
        assertEquals(0f, g.rowShift(0f, count = 0, viewport = 1080f, endPad = 40f), 0f)
        assertEquals(0f, g.rowShift(0f, count = 5, viewport = 0f, endPad = 40f), 0f)
        assertEquals(0f, g.rowShift(0f, count = 5, viewport = -100f, endPad = 40f), 0f)

        val zeroRail = ShelfRail(viewport = 0f, eyeHeight = 180f)
        assertEquals(1f, zeroRail.scale(-500f), 0f)
        assertEquals(-500f, zeroRail.railX(-500f), 0f)
        assertTrue(zeroRail.screenX(200f).isFinite())
        assertTrue(zeroRail.lift(200f).isFinite())

        val rail = ShelfRail(viewport = 1080f, eyeHeight = 180f)
        for (x in listOf(-100_000f, -1_000f, 0f, 1_000f, 100_000f)) {
            val s = rail.scale(x)
            assertTrue("scale not finite at $x", s.isFinite() && s > 0f)
            assertTrue("screenX not finite at $x", rail.screenX(x).isFinite())
            assertTrue("lift not finite at $x", rail.lift(x).isFinite())
            assertTrue("railX not finite at $x", rail.railX(x).isFinite())
        }
    }

    @Test
    fun shelfBoardTopSpansBehindAndInFrontOfContactLineWithThinFrontEdge() {
        val g = geometry()
        val viewport = 1400f
        val floorY = 900f
        val floorDepth = g.coverHeight * 0.14f
        val backY = shelfBoardBackY(
            floorY = floorY,
            bookHeight = g.coverHeight,
            coverWidth = g.coverWidth,
            viewport = viewport,
        )
        val frontY = floorY + floorDepth
        assertTrue("backEdgeY=$backY must be behind/above contact floorY=$floorY", backY < floorY)
        assertTrue("frontEdgeY=$frontY must extend in front of contact floorY=$floorY", frontY > floorY)
        assertTrue("board surface ahead of contact line must stay substantial", frontY - floorY > 50f)
        assertEquals(4f, SHELF_BOARD_EDGE, 1e-4f)
    }

    @Test
    fun viewportReflectionCoversAllVisibleBooksWithoutThreeBookCutoff() {
        val phoneViewport = 1080f
        val tabletViewport = 2560f
        // 不论手机还是平板宽屏，只要书在视口内（含远处第 5、第 8 本及边缘部分可见书）都有倒影且平滑衰减
        for (vp in listOf(phoneViewport, tabletViewport)) {
            val prevAlphas = mutableListOf<Float>()
            for (step in 0..8) {
                val center = vp * 0.5f + step * (vp * 0.06f)
                val alpha = shelfViewportReflectionAlpha(
                    centerScreenX = center,
                    minScreenX = center - 40f,
                    maxScreenX = center + 40f,
                    viewport = vp,
                    baseAlpha = 0.36f,
                    hero = if (step == 0) 1f else 0f,
                    groundAlpha = 1f,
                )
                assertTrue("visible book at center=$center in vp=$vp must have reflection", alpha > 0.15f)
                if (step >= 1 && prevAlphas.size >= 2) {
                    assertTrue("edge attenuation must be smooth and non-increasing", alpha <= prevAlphas.last() + 1e-5f)
                }
                prevAlphas += alpha
            }
            // 部分露在视口右缘内（maxScreenX > vp 但 minScreenX < vp）依然有倒影
            val partialAlpha = shelfViewportReflectionAlpha(
                centerScreenX = vp + 10f,
                minScreenX = vp - 25f,
                maxScreenX = vp + 45f,
                viewport = vp,
                baseAlpha = 0.36f,
                hero = 0f,
                groundAlpha = 1f,
            )
            assertTrue("partially visible edge book must keep reflection", partialAlpha > 0.05f)
            // 完全离开视口外缘后停止绘制倒影
            val outsideAlpha = shelfViewportReflectionAlpha(
                centerScreenX = vp + 500f,
                minScreenX = vp + 460f,
                maxScreenX = vp + 540f,
                viewport = vp,
                baseAlpha = 0.36f,
                hero = 0f,
                groundAlpha = 1f,
            )
            assertEquals(0f, outsideAlpha, 0f)
        }
    }

    @Test
    fun heroReflectionIsClearestEvenAtPinnedEdgesAndSideBooksStayVisible() {
        val g = geometry()
        val viewport = 1264f
        val darkBaseAlpha = 0.34f
        val lightBaseAlpha = 0.42f

        for (baseAlpha in listOf(darkBaseAlpha, lightBaseAlpha)) {
            // 正中书停在视口中心或贴边位置时均保持基准清晰度
            val centerHeroAlpha = shelfViewportReflectionAlpha(
                centerScreenX = viewport * 0.5f,
                minScreenX = viewport * 0.5f - g.coverWidth * 0.5f,
                maxScreenX = viewport * 0.5f + g.coverWidth * 0.5f,
                viewport = viewport,
                baseAlpha = baseAlpha,
                hero = 1f,
                groundAlpha = 1f,
            )
            val edgeHeroAlpha = shelfViewportReflectionAlpha(
                centerScreenX = g.coverWidth * 0.55f,
                minScreenX = g.coverWidth * 0.05f,
                maxScreenX = g.coverWidth * 1.05f,
                viewport = viewport,
                baseAlpha = baseAlpha,
                hero = 1f,
                groundAlpha = 1f,
            )
            assertEquals(baseAlpha, centerHeroAlpha, 1e-5f)
            assertEquals(baseAlpha, edgeHeroAlpha, 1e-5f)

            for (offset in listOf(-4f, -2f, 2f, 4f)) {
                val centerX = viewport * 0.5f + g.centerX(offset)
                val halfW = g.visibleWidth(abs(g.yaw(offset))) * shelfPoseScale(offset) * 0.5f
                val sideAlpha = shelfViewportReflectionAlpha(
                    centerScreenX = centerX,
                    minScreenX = centerX - halfW,
                    maxScreenX = centerX + halfW,
                    viewport = viewport,
                    baseAlpha = baseAlpha,
                    hero = 0f,
                    groundAlpha = 1f,
                )
                assertTrue("edge book offset=$offset must have visible reflection alpha=$sideAlpha", sideAlpha > 0.1f)
                assertTrue("hero reflection must remain clearer than edge offset=$offset", centerHeroAlpha > sideAlpha)
            }
        }
    }

    @Test
    fun spineTypographyScalesWithSizeAndMaintainsContrast() {
        val landscapeShort = shelfSpineTypography(spineWidthDp = 22f, coverHeightDp = 128f)
        val phone = shelfSpineTypography(spineWidthDp = 29f, coverHeightDp = 260f)
        val tablet = shelfSpineTypography(spineWidthDp = 42f, coverHeightDp = 550f)

        assertTrue(landscapeShort.fontSizeDp < phone.fontSizeDp)
        assertTrue(phone.fontSizeDp < tablet.fontSizeDp)
        // 与原来手机书架一样宽（32dp）的书脊字号、行高与原来完全一致（11 / 13），只随书脊宽度等比缩放
        val reference = shelfSpineTypography(spineWidthDp = 32f, coverHeightDp = 260f)
        assertEquals(11f, reference.fontSizeDp, 0.001f)
        assertEquals(13f, reference.lineHeightDp, 0.001f)
        assertEquals(210.8f, reference.latinLengthDp, 0.001f)
        assertEquals(11f * 42f / 32f, tablet.fontSizeDp, 0.001f)
        assertEquals(phone.fontSizeDp / phone.lineHeightDp, tablet.fontSizeDp / tablet.lineHeightDp, 0.001f)

        // 暗底、中间灰、亮底与高反差裂色底均保证 >= 4.5:1 对比度
        val tones = listOf(
            SpineToneMetrics(avgLuminance = 0.05f, minLuminance = 0.02f, maxLuminance = 0.09f),
            SpineToneMetrics(avgLuminance = 0.19f, minLuminance = 0.18f, maxLuminance = 0.21f),
            SpineToneMetrics(avgLuminance = 0.68f, minLuminance = 0.55f, maxLuminance = 0.85f),
            SpineToneMetrics(avgLuminance = 0.38f, minLuminance = 0.06f, maxLuminance = 0.82f),
        )
        for (tone in tones) {
            val appearance = resolveShelfSpineTitleAppearance(tone)
            assertTrue(
                "contrast ${appearance.effectiveContrast} must be >= 4.5 for $tone",
                appearance.effectiveContrast >= 4.5f,
            )
        }
        assertTrue(resolveShelfSpineTitleAppearance(tones[0]).isLightText)
        assertFalse(resolveShelfSpineTitleAppearance(tones[2]).isLightText)
    }

    @Test
    fun darkSpineOnlyStrengthensTheVeilForFailingHighlights() {
        val stillReadable = resolveShelfSpineTitleAppearance(SpineToneMetrics(0.08f, 0.02f, 0.73f))
        assertEquals(Color.Black.copy(alpha = 0.52f), stillReadable.veilColor)
        assertTrue(stillReadable.effectiveContrast >= 4.5f)
        for (peak in listOf(0.78f, 0.95f, 1f)) {
            val appearance = resolveShelfSpineTitleAppearance(SpineToneMetrics(0.08f, 0.02f, peak))
            assertTrue(appearance.isLightText)
            assertTrue(appearance.needsStroke)
            assertTrue(appearance.effectiveContrast >= 4.5f)
            assertTrue(appearance.veilColor.alpha > stillReadable.veilColor.alpha)
        }
    }
}
