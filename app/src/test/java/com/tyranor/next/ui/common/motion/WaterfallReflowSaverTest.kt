package com.tyranor.next.ui.common.motion

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 保存态格式的兼容契约：7 字段是本 PR 加上布局代之前的旧格式，9 字段带采样时的列数与方向。
 * 两种都要能恢复，坏载荷必须回落到新状态而不是把异常抛进恢复阶段。
 */
class WaterfallReflowSaverTest {
    private val saver = WaterfallReflowState.saver { it }

    @Suppress("UNCHECKED_CAST")
    private fun save(state: WaterfallReflowState): List<Any> {
        val saved = with(saver) { SaverScope { true }.save(state) }
        return requireNotNull(saved) { "a live state must be saveable" } as List<Any>
    }

    private fun restore(values: List<Any>): WaterfallReflowState =
        requireNotNull(saver.restore(values)) { "restore must never return null" }

    @Test
    fun sevenFieldPayloadKeepsAnchorAndFillsGenerationFromLayout() {
        val restored = restore(listOf(4, false, 7, 120, true, true, "game-7"))
        assertEquals(4, restored.layoutColumns)
        assertFalse(restored.layoutLandscape)
        assertTrue("A saved index must reopen the pending restore", restored.isRestoring)
        // 旧格式没有采样代：补齐为当前布局，回读时九字段与采样时同代，y 可直接采信。
        assertEquals(
            listOf<Any>(4, false, 7, 120, true, true, "game-7", 4, false),
            save(restored),
        )
    }

    @Test
    fun nineFieldPayloadKeepsSampledGeneration() {
        val restored = restore(listOf(3, true, 9, 40, false, true, "game-9", 5, false))
        assertEquals(3, restored.layoutColumns)
        assertTrue(restored.layoutLandscape)
        assertTrue(restored.isRestoring)
        // 采样代（5 列 / 竖屏）与当前布局（3 列 / 横屏）不同，恢复时要按对齐视口顶端处理。
        assertEquals(
            listOf<Any>(3, true, 9, 40, false, true, "game-9", 5, false),
            save(restored),
        )
    }

    @Test
    fun outOfRangeColumnCountIsClampedToTheSettingsRange() {
        val restored = restore(listOf(0, false, 3, 10, false, false, "", 99, true))
        assertEquals(2, restored.layoutColumns)
        assertEquals(listOf<Any>(2, false, 3, 10, false, false, "", 8, true), save(restored))
    }

    @Test
    fun corruptedPayloadFallsBackToAFreshState() {
        val wrongSize = restore(listOf(4, false, 7))
        assertEquals(4, wrongSize.layoutColumns)
        assertFalse("A 3-field payload must not reopen a restore", wrongSize.isRestoring)

        val wrongTypes = restore(listOf("four", false, 7, 120, true, true, "game-7", 4, false))
        assertEquals(4, wrongTypes.layoutColumns)
        assertFalse(wrongTypes.isRestoring)
    }
}
