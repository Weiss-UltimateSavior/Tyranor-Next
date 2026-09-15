package com.tyranor.next.ui.common.glass

import androidx.compose.runtime.MonotonicFrameClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 松手路径分流测试。
 *
 * 上一轮的 High 回归不是控制器算错，而是**接线**：`beginPressAt` 已把压力目标置为 1，只有
 * `settleAt` / `endPress` / `cancelPress` 会把它收回。控制器单测驱动不了调用方，发现不了
 * 「少调了一次」。
 *
 * **覆盖边界（如实说明）**：
 * - 覆盖：纯决策 [glassPressRelease]（含脏输入与退化槽位），以及接线函数
 *   [applyGlassPressRelease] → 真实控制器的效果——用例走的是**生产代码同一个函数**，
 *   把它的分支删掉/改错，这些用例就会失败；
 * - **不覆盖**：Compose 回调层（`onUp` / `onCancel` / `detectBottomBarPress`）本身。
 *   如果有人在 `onUp` 里漏调 `applyGlassPressRelease`，本文件**测不出来**——那需要设备或
 *   Compose 测试宿主（`androidx.compose.ui.test`），当前环境不具备。
 */
class GlassBottomBarGestureTest {

    // ---- 纯决策 ----

    @Test
    fun tapOnOtherSlot_pulsesAndRounds() {
        val release = glassPressRelease(grabbed = false, pointerIndex = 2.4f, selectedIndex = 0, tabs = 4)
        assertEquals(GlassPressRelease.Tap(index = 2, pulse = true), release)
    }

    @Test
    fun tapOnCurrentSlot_doesNotPulse() {
        // 点在当前选中槽：滑块本来就在那里，起胀只会「白闪一下」
        val release = glassPressRelease(grabbed = false, pointerIndex = 1.2f, selectedIndex = 1, tabs = 4)
        assertEquals(GlassPressRelease.Tap(index = 1, pulse = false), release)
    }

    @Test
    fun grabbedRelease_commitsWithoutPulse() {
        // 抓取过（按住到阈值 / 真拖动）：正常尺寸吸附，绝不允许补膨胀
        val release = glassPressRelease(grabbed = true, pointerIndex = 3f, selectedIndex = 0, tabs = 4)
        assertEquals(GlassPressRelease.Commit(index = 3), release)
    }

    @Test
    fun grabbedReleaseIgnoresSelection() {
        // 即使落点就是当前选中槽，抓取路径也不能退化成「轻点不起胀」以外的行为
        val release = glassPressRelease(grabbed = true, pointerIndex = 0f, selectedIndex = 0, tabs = 4)
        assertTrue(release is GlassPressRelease.Commit)
        assertEquals(0, release.index)
    }

    @Test
    fun releaseIndex_isClampedIntoSlots() {
        assertEquals(0, glassPressRelease(false, -5f, 0, 4).index)
        assertEquals(3, glassPressRelease(false, 99f, 0, 4).index)
        assertEquals(3, glassPressRelease(true, 99f, 0, 4).index)
    }

    @Test
    fun nonFinitePointerIndex_doesNotThrow() {
        // 非有限值不能让索引变成 NaN 传到绘制层（钳到合法槽位即可）
        listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY).forEach { bad ->
            val release = glassPressRelease(grabbed = false, pointerIndex = bad, selectedIndex = 0, tabs = 4)
            assertTrue("脏输入 ${bad} 应钳到合法槽位，实得 ${release.index}", release.index in 0..3)
        }
    }

    @Test
    fun degenerateTabCount_doesNotCrash() {
        // 槽位数为 0/1 时不能抛（空列表 + coerceIn 空区间是经典崩溃点）
        assertEquals(0, glassPressRelease(false, 2f, 0, 0).index)
        assertEquals(0, glassPressRelease(true, 2f, 0, 1).index)
    }

    // ---- 接回真实控制器：两条路径都必须让压力收敛 ----

    @Test
    fun tapPath_collapsesPressure() = runBlocking {
        withController { controller ->
            // 按在当前槽（未抓取）→ 松手得到 Tap(index = 当前槽, pulse = false)
            controller.beginPressAt(0f)
            awaitPressureAbove(controller, 0.5f)
            val release = glassPressRelease(grabbed = false, pointerIndex = 0f, selectedIndex = 0, tabs = 4)
            // 走生产代码的接线函数（而不是在测试里复述 when），这样接线被改坏时用例会失败
            applyGlassPressRelease(controller, release)
            awaitSettled(controller)
            assertTrue(
                "轻点也必须把压力收回（少调一次就会永久停在按下态——上轮回归）",
                controller.pressure < 0.05f,
            )
            assertFalse("收敛后帧循环应自行退出", controller.isAnimating)
        }
    }

    @Test
    fun commitPath_collapsesPressure() = runBlocking {
        withController { controller ->
            controller.beginPressAt(3f)
            awaitPressureAbove(controller, 0.5f)
            val release = glassPressRelease(grabbed = true, pointerIndex = 3f, selectedIndex = 0, tabs = 4)
            applyGlassPressRelease(controller, release)
            awaitSettled(controller)
            assertTrue("抓取路径松手后材质必须收回", controller.pressure < 0.05f)
            assertEquals("吸附到整数槽位", 3f, controller.index, 0.02f)
        }
    }

    @Test
    fun tapOnCurrentSlot_afterPress_returnsToRestSize() = runBlocking {
        // 回归的最短复现：点在已选中槽 → 松手 → 尺寸必须回到 1，而不是停在 pressedScale
        withController { controller ->
            controller.beginPressAt(0f)
            awaitPressureAbove(controller, 0.5f)
            assertTrue("按下时应当已经膨胀", controller.scaleX > 1.05f)
            applyGlassPressRelease(controller, glassPressRelease(false, 0f, 0, 4))
            awaitSettled(controller)
            assertEquals("松手后横向尺寸回到静止值", 1f, controller.scaleX, 0.02f)
            assertEquals("松手后纵向尺寸回到静止值", 1f, controller.scaleY, 0.02f)
        }
    }

    // ---- 高光 gate（第五轮审核抓到的 Android 12 回归）----

    @Test
    fun highlightGate_isAlwaysAllowedBelowApi33() {
        // API 31–32：没有 RuntimeShader，库走「描边 + BlurMaskFilter」，高光本来就有、不会崩。
        // 这里若返回 false，Android 12 经典档的高光会被无条件抹掉（正是上一版引入的回归）。
        assertTrue("API 31 必须允许高光", highlightAllowedFor(sdkInt = 31, runtimeShaderUsable = false))
        assertTrue("API 32 必须允许高光", highlightAllowedFor(sdkInt = 32, runtimeShaderUsable = false))
    }

    @Test
    fun highlightGate_followsProbeFromApi33() {
        // API 33+ 才有 RuntimeShader：探测失败就必须避让（否则回退到经典档也会崩）
        assertTrue("API 33 探测可用 → 允许", highlightAllowedFor(sdkInt = 33, runtimeShaderUsable = true))
        assertFalse("API 33 探测失败 → 避让", highlightAllowedFor(sdkInt = 33, runtimeShaderUsable = false))
        assertFalse("API 34 探测失败 → 避让", highlightAllowedFor(sdkInt = 34, runtimeShaderUsable = false))
        assertTrue("API 34 探测可用 → 允许", highlightAllowedFor(sdkInt = 34, runtimeShaderUsable = true))
    }

    // ---- 脚手架 ----

    private suspend fun withController(
        frameNanos: Long = NANOS_60HZ,
        block: suspend (LensMotionController) -> Unit,
    ) = coroutineScope {
        val scope = CoroutineScope(coroutineContext + ManualFrameClock(frameNanos))
        val controller = LensMotionController(
            scope = scope,
            initialIndex = 0f,
            indexRange = 0f..3f,
            spec = GlassBottomBarSpec.Default,
        )
        block(controller)
    }

    private suspend fun awaitPressureAbove(controller: LensMotionController, threshold: Float) {
        withTimeout(SettleTimeoutMillis) {
            while (controller.pressure < threshold) delay(1)
        }
    }

    /** 等到帧循环自己结束：这正是「动画已收敛」的权威条件。 */
    private suspend fun awaitSettled(controller: LensMotionController) {
        withTimeout(SettleTimeoutMillis) {
            while (controller.isAnimating) delay(1)
        }
    }

    private class ManualFrameClock(private val frameNanos: Long) : MonotonicFrameClock {
        private var nanos = 0L

        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
            delay(1)
            nanos += frameNanos
            return onFrame(nanos)
        }
    }

    private companion object {
        const val NANOS_60HZ = 16_666_667L
        const val SettleTimeoutMillis = 10_000L
    }
}
