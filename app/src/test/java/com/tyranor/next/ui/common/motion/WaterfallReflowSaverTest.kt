package com.tyranor.next.ui.common.motion

import androidx.compose.runtime.saveable.SaverScope
import com.tyranor.next.core.settings.AppSettingsStore
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
        // 载荷首字段故意取「不是默认值」的列数，否则回落与照读会得到同一个 4、断言没有分辨力。
        val wrongSize = restore(listOf(6, false, 7))
        assertEquals(AppSettingsStore.DEFAULT_GAME_WATERFALL_PORTRAIT_COLUMNS, wrongSize.layoutColumns)
        assertFalse("A 3-field payload must not reopen a restore", wrongSize.isRestoring)

        // 首字段类型不对就整体回落：后面的字段根本不会被读到，所以这里只放 7 个合法位置。
        val wrongTypes = restore(listOf("four", false, 7, 120, true, true, "game-7"))
        assertEquals(AppSettingsStore.DEFAULT_GAME_WATERFALL_PORTRAIT_COLUMNS, wrongTypes.layoutColumns)
        assertFalse(wrongTypes.isRestoring)
    }

    @Test
    fun generationFieldsFallBackToCurrentLayoutInsteadOfDiscardingTheAnchor() {
        // 九字段里只有代际两项类型坏掉：锚点本身可信，应保留索引与偏移，代际回落成当前布局，
        // 而不是像整体损坏那样从头开始。
        val brokenGeneration = restore(listOf<Any>(4, false, 7, 120, true, true, "game-7", "wide", "nope"))
        assertEquals(4, brokenGeneration.layoutColumns)
        assertTrue("A readable anchor must still reopen the restore", brokenGeneration.isRestoring)
        assertEquals(
            listOf<Any>(4, false, 7, 120, true, true, "game-7", 4, false),
            save(brokenGeneration).toList(),
        )
    }

    @Test
    fun negativeIndexDropsTheAnchorWhileColumnsAreStillClamped() {
        // 索引 —— 列表还没测量时的兜底捕获会写 -1，这类锚点不能拿去恢复（会落到列表头）。
        // 列数越界仍然要夹回设置范围，否则会流向 StaggeredGridCells.Fixed。
        val restored = restore(listOf(99, false, -1, 0, false, false, "", 0, true))
        assertEquals(AppSettingsStore.MAX_GAME_CARD_COLUMNS, restored.layoutColumns)
        assertFalse("A negative index must not reopen the restore", restored.isRestoring)
        val resaved = save(restored)
        assertEquals("The clamped column count must survive a re-save", 8, resaved[0])
        assertEquals(-1, resaved[2])
        // 锚点没构造出来（index < 0），代际只能回填当前布局列数。这里刻意与 resaved[0] 比较
        // 而不是写死 8：要验的是「回填来源」，夹取本身由下面那条索引合法的用例负责。
        assertEquals("A missing anchor must fall back to the layout columns", resaved[0], resaved[7])
    }

    @Test
    fun outOfRangeGenerationIsClampedWhenTheAnchorSurvives() {
        // 与上一条的区别：索引合法 → Anchor 真的构造出来，resaved[7] 走的是 anchor.columns 的
        // coerceIn 分支，与 resaved[0]（layoutColumns 的 coerceIn）是两条独立路径。
        val restored = restore(listOf(3, false, 12, 40, false, true, "game-12", 99, true))
        assertEquals(3, restored.layoutColumns)
        assertTrue("A valid index must still reopen the restore", restored.isRestoring)
        val resaved = save(restored)
        assertEquals("The kept layout columns must survive a re-save", 3, resaved[0])
        assertEquals("The generation must be clamped to the settings range", 8, resaved[7])
    }
}
