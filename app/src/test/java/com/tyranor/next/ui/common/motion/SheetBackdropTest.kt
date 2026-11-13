package com.tyranor.next.ui.common.motion

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SheetBackdropTest {
    @Test
    fun removingOffscreenSheetCannotDeepenOrHoldTheDialog() = runTest {
        val backdrop = SheetBackdrop(Frames(this).scope)
        val sheet = SheetBackdrop.Source({ 0f }, { 1f })
        var dialogCover = 1f
        backdrop.add(sheet)
        backdrop.add(SheetBackdrop.Source({ dialogCover }, { DialogSinkStrength }))

        backdrop.remove(sheet)
        assertDepth(backdrop, 1f, 0.7f)
        // 弹窗自己的退场须跟手，已离屏的抽屉不能用弹窗的进度留下第二份残余。
        dialogCover = 0.4f
        assertDepth(backdrop, 0.4f, 0.28f)
    }

    @Test
    fun simultaneousRemovalKeepsDifferentWinnersForCoverAndSinkInEitherOrder() = runTest {
        val frames = Frames(this)
        for (sheetFirst in listOf(true, false)) {
            val backdrop = SheetBackdrop(frames.scope)
            var strength = 1f
            val sheet = SheetBackdrop.Source({ 0.8f }, { strength })
            val dialog = SheetBackdrop.Source({ 1f }, { DialogSinkStrength })
            backdrop.add(sheet)
            backdrop.add(dialog)
            val (first, second) = if (sheetFirst) sheet to dialog else dialog to sheet
            backdrop.remove(first)
            assertDepth(backdrop, 1f, 0.8f)
            backdrop.remove(second)
            strength = 0.5f // 离开组合后再改尺寸，不应改写当时的残余。
            assertDepth(backdrop, 1f, 0.8f)
            frames.advance(3)
            assertTrue(backdrop.cover in 0.01f..0.99f)
            assertEquals(0.8f * backdrop.cover, backdrop.sink, EPS)
            frames.advance(60)
            assertDepth(backdrop, 0f, 0f)
        }
    }

    @Test
    fun staggeredExitAndNewArrivalDoNotRestartTheFirstTail() = runTest {
        val frames = Frames(this)
        val backdrop = SheetBackdrop(frames.scope)
        val single = SheetBackdrop(frames.scope) // 单独退场，作为第一条弹簧的对照。
        val sheet = SheetBackdrop.Source({ 1f }, { 1f })
        val dialog = SheetBackdrop.Source({ 0.2f }, { DialogSinkStrength })
        single.add(sheet)
        backdrop.add(sheet)
        backdrop.add(dialog)
        single.remove(sheet)
        backdrop.remove(sheet)
        frames.advance(3)
        backdrop.remove(dialog)
        val newcomer = SheetBackdrop.Source({ 0.1f }, { 0.5f })
        backdrop.add(newcomer)
        frames.advance(1)
        assertTrue(single.cover > 0.2f)
        assertDepth(backdrop, single.cover, single.sink)
        frames.advance(60)
        assertDepth(backdrop, 0.1f, 0.05f)
    }

    @Test
    fun shortDragReboundsWithTheSheetAndOvershootDoesNotDeepenThePage() = runTest {
        val frames = Frames(this)
        val sheet = MotionSheetController(frames.scope, enhanced = true) { 0L }
        val backdrop = SheetBackdrop(frames.scope)
        backdrop.add(SheetBackdrop.Source({ sheet.cover }, { sheet.sinkStrength }))
        sheet.updateHeight(500f)
        sheet.windowHeightPx = 1000f
        sheet.offset.snapTo(0f)
        sheet.dragBy(100f)
        assertDepth(backdrop, 0.8f, 0.6f)
        sheet.settle()
        frames.advance(4)
        assertTrue(sheet.offset.value in 0f..99f)
        assertEquals(0.75f * sheet.cover, backdrop.sink, EPS)
        frames.advance(60)
        assertFalse(sheet.closing)
        assertEquals(0f, sheet.offset.value, EPS)
        assertDepth(backdrop, 1f, 0.75f)
        sheet.dragBy(-80f)
        assertTrue(sheet.offset.value < 0f && sheet.offset.value > -MotionSheetController.RUBBER)
        assertDepth(backdrop, 1f, 0.75f)
    }

    @Test
    fun downwardFlingCanBeGrabbedBeforeExitAndClosedOncePastTheLine() = runTest {
        val frames = Frames(this)
        var time = 0L
        val sheet = MotionSheetController(frames.scope, enhanced = true) { time }
        sheet.updateHeight(600f)
        sheet.offset.snapTo(0f)
        var closures = 0
        sheet.onClosed = { closures++ }
        // 还不到 30%，但这次连续快速下拉的松手速度足以收起。
        repeat(5) {
            time += 10
            sheet.dragBy(24f)
        }
        sheet.settle()
        assertTrue(sheet.closing)
        frames.advance(2)
        val beforeGrab = sheet.offset.value
        sheet.dragBy(-10f)
        assertFalse(sheet.closing)
        // 让被取消的离场动画释放 Animatable 的锁，不推进帧。
        testScheduler.runCurrent()
        assertEquals(beforeGrab - 10f, sheet.offset.value, EPS)
        frames.advance(60)
        assertEquals(0, closures)
        // 同一抽屉再次收起：到离屏线才移除，已提交的收起不能再被接回。
        sheet.close()
        frames.advance(60)
        assertEquals(1, closures)
        assertEquals(0f, sheet.cover, EPS)
        assertFalse(sheet.reopen())
    }

    @Test
    fun listKeepsItsFlingAfterReturningTheSheetToRest() = runTest {
        val frames = Frames(this)
        val sheet = MotionSheetController(frames.scope, enhanced = true) { 0L }
        sheet.updateHeight(600f)
        sheet.offset.snapTo(0f)
        val drag = SheetDragConnection(sheet)
        val down = Offset(0f, 90f)
        val fling = Velocity(0f, -1500f)
        assertEquals(Offset.Zero, drag.onPostScroll(Offset.Zero, down, NestedScrollSource.SideEffect))
        assertEquals(down, drag.onPostScroll(Offset.Zero, down, NestedScrollSource.UserInput))
        assertEquals(Offset(0f, -90f), drag.onPreScroll(Offset(0f, -200f), NestedScrollSource.UserInput))
        assertEquals(0f, sheet.offset.value, EPS)
        assertEquals(Velocity.Zero, drag.onPreFling(fling))
        // 后续点遮罩收起期间没有再拖过，列表惯性仍不应被截走。
        sheet.close()
        frames.advance(3)
        assertTrue(sheet.offset.value > 0f)
        assertEquals(Velocity.Zero, drag.onPreFling(fling))
        frames.advance(60)
    }

    @Test
    fun resizingDuringExitUsesTheCurrentOffscreenLine() = runTest {
        val frames = Frames(this)
        for (newHeight in listOf(1000f, 100f)) {
            val sheet = MotionSheetController(frames.scope, enhanced = true)
            sheet.updateHeight(500f)
            sheet.offset.snapTo(0f)
            var coverAtClose: Float? = null
            sheet.onClosed = { coverAtClose = sheet.cover }
            sheet.close()
            frames.advance(4)
            assertTrue(sheet.cover > 0f)
            sheet.updateHeight(newHeight)
            repeat(60) {
                frames.advance(1)
                if (sheet.cover == 0f) assertEquals("离屏当帧移除", 0f, coverAtClose)
            }
            assertEquals("不能在抽屉还露出时移除", 0f, coverAtClose)
        }
    }

    @Test
    fun sinkUsesWindowHeightForCoverageAndLayerWidthForTheInsetCap() = runTest {
        val frames = Frames(this)
        val sheet = MotionSheetController(frames.scope, enhanced = true)
        sheet.windowHeightPx = 1200f
        assertEquals(0f, sheet.cover, EPS) // 尚未量出高度
        sheet.updateHeight(600f)
        sheet.offset.snapTo(0f)
        assertEquals(0.75f, sheet.sinkStrength, EPS)
        sheet.windowHeightPx = 500f // 分屏缩小，抽屉高度暂时仍是上一帧的尺寸
        assertEquals(1f, sheet.sinkStrength, EPS)
        val layer = LayerParams()
        layer.width = 1080f // 360dp：全幅缩到约 0.942
        layer.applySheetSink(sheet.sinkStrength, density = 3f)
        assertEquals(0.942f, layer.scaleX, EPS)
        assertEquals(layer.scaleX, layer.scaleY, EPS)
        assertEquals(0.5f, layer.pivotFracX, EPS)
        assertEquals(0.5f, layer.pivotFracY, EPS)
        layer.applySheetSink(0.749f, density = 3f) // 仍按 1/64 档取到 0.75
        assertEquals(0.9565f, layer.scaleX, EPS)
        layer.width = 3000f // 1000dp：每侧最多缩进 20dp
        layer.applySheetSink(sheet.sinkStrength, density = 3f)
        assertEquals(60f, (1f - layer.scaleX) * layer.width / 2f, 0.001f)
        layer.reset()
        layer.applySheetSink(0f, density = 3f)
        assertEquals(1f, layer.scaleX, EPS)
    }

    @Test
    fun closeGuardBlocksBothModesAndReadsTheLatestDecision() = runTest {
        for (enhanced in listOf(false, true)) {
            val frames = Frames(this)
            val sheet = MotionSheetController(frames.scope, enhanced) { 0L }
            sheet.updateHeight(500f)
            sheet.offset.snapTo(0f)
            var allowed = false
            var closes = 0
            sheet.canClose = { allowed }
            sheet.onClosed = { closes++ }
            sheet.close()
            frames.advance(3)
            assertFalse(sheet.closing)
            assertEquals(0f, sheet.offset.value, EPS)
            assertEquals(0, closes)
            allowed = true
            sheet.close()
            if (enhanced) sheet.close() // 重复请求不能重复交付退出回调。
            frames.advance(60)
            assertEquals(1, closes)
        }
    }

    @Test
    fun guardedDragBeyondDismissLineReturnsToOpenInsteadOfClosing() = runTest {
        val frames = Frames(this)
        val sheet = MotionSheetController(frames.scope, enhanced = true) { 0L }
        sheet.updateHeight(500f)
        sheet.offset.snapTo(0f)
        var closes = 0
        sheet.canClose = { false }
        sheet.onClosed = { closes++ }
        sheet.dragBy(250f)
        sheet.settle()
        frames.advance(60)
        assertFalse(sheet.closing)
        assertEquals(0f, sheet.offset.value, EPS)
        assertEquals(0, closes)
    }

    private fun assertDepth(backdrop: SheetBackdrop, cover: Float, sink: Float) {
        assertEquals("cover", cover, backdrop.cover, EPS)
        assertEquals("sink", sink, backdrop.sink, EPS)
    }

    /** 所有同时退场的浮层共享同一帧；同步派发快照变更，供收起流程观察离屏线。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    private class Frames(private val test: TestScope) {
        private val clock = BroadcastFrameClock()
        val scope = CoroutineScope(test.coroutineContext + clock)
        private var nanos = 0L

        fun advance(count: Int) {
            repeat(count) {
                test.testScheduler.runCurrent()
                nanos += 16_000_000L
                clock.sendFrame(nanos)
                test.testScheduler.runCurrent()
                Snapshot.sendApplyNotifications()
                test.testScheduler.runCurrent()
            }
        }
    }

    private companion object {
        const val EPS = 0.0001f
    }
}
