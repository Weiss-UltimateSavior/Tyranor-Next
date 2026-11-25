package com.tyranor.next.ui.game

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 书架背景色晕的基准页索引与混合采样回归测试：
 * 确认组合态基准页与绘制期实时位置之间保持单调连续，跨整页且尚未重组的一帧不会回跳到上一本封面色晕。
 */
class ShelfBackdropBlendTest {

    @Test
    fun baseIndexClampsToValidGameRange() {
        assertEquals(0, shelfBackdropBaseIndex(position = -0.35f, gameCount = 9))
        assertEquals(0, shelfBackdropBaseIndex(position = -0.0001f, gameCount = 9))
        assertEquals(3, shelfBackdropBaseIndex(position = 3.99f, gameCount = 9))
        assertEquals(4, shelfBackdropBaseIndex(position = 4.0f, gameCount = 9))
        assertEquals(8, shelfBackdropBaseIndex(position = 8.4f, gameCount = 9))
        assertEquals(0, shelfBackdropBaseIndex(position = 2.5f, gameCount = 0))
        assertEquals(0, shelfBackdropBaseIndex(position = Float.NaN, gameCount = 9))
    }

    @Test
    fun forwardCrossingBeforeRecompositionStaysOnNextBookInsteadOfFlashingPrevious() {
        // 复现录屏 4.464s / 7.034s：组合快照停留在 base=5（baseBmp=wash[5], nextBmp=wash[6]），
        // 绘制瞬间位置先跨过 6.0，若继续用已绑定的 composedBase=5 计算，比例必须钳制到 1f 并绘制 NextStatic。
        val beforeCrossing = shelfBackdropBlendSample(position = 5.99f, composedBase = 5, hasNext = true)
        assertEquals(ShelfBackdropDrawMode.Blend, beforeCrossing.mode)
        assertEquals(0.99f, beforeCrossing.fraction, 1e-4f)

        val rightAtBoundary = shelfBackdropBlendSample(position = 6.0005f, composedBase = 5, hasNext = true)
        assertEquals(ShelfBackdropDrawMode.NextStatic, rightAtBoundary.mode)
        assertEquals(1f, rightAtBoundary.fraction, 1e-4f)

        val overshootForward = shelfBackdropBlendSample(position = 6.18f, composedBase = 5, hasNext = true)
        assertEquals(ShelfBackdropDrawMode.NextStatic, overshootForward.mode)
        assertEquals(1f, overshootForward.fraction, 1e-4f)
    }

    @Test
    fun backwardCrossingBeforeRecompositionStaysOnComposedBaseInsteadOfFlashingAhead() {
        // 组合快照停留在 base=6（baseBmp=wash[6], nextBmp=wash[7]），位置在重组前倒回 5.99：
        // 混合比例必须钳制到 0f 并绘制 BaseStatic（wash[6]），不能跳到 wash[7]。
        val sample = shelfBackdropBlendSample(position = 5.99f, composedBase = 6, hasNext = true)
        assertEquals(ShelfBackdropDrawMode.BaseStatic, sample.mode)
        assertEquals(0f, sample.fraction, 1e-4f)
    }

    @Test
    fun staticThresholdsPickStaticAtBothEndsAndZeroWhenNoNextWash() {
        val lowStatic = shelfBackdropBlendSample(position = 2.001f, composedBase = 2, hasNext = true)
        assertEquals(ShelfBackdropDrawMode.BaseStatic, lowStatic.mode)

        val highStatic = shelfBackdropBlendSample(position = 2.999f, composedBase = 2, hasNext = true)
        assertEquals(ShelfBackdropDrawMode.NextStatic, highStatic.mode)

        val noNext = shelfBackdropBlendSample(position = 8.4f, composedBase = 8, hasNext = false)
        assertEquals(ShelfBackdropDrawMode.BaseStatic, noNext.mode)
        assertEquals(0f, noNext.fraction, 1e-4f)
    }
}
