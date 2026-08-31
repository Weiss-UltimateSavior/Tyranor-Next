package com.tyranor.next.core.game.model

import org.junit.Assert.assertEquals
import org.junit.Test

/** 排序键回归：Room 预计算列与 UI 内存排序共用实现（迁移方案阶段 3）。 */
class GameSortKeysTest {

    @Test
    fun bracketTagExtractionMatchesLegacyUiRegex() {
        assertEquals("galgame", GameSortKeys.bracketTag("【galgame】Title"))
        assertEquals("RL", GameSortKeys.bracketTag("[RL] Title"))
        assertEquals("trimmed", GameSortKeys.bracketTag("【 trimmed 】x"))
        assertEquals("", GameSortKeys.bracketTag("No tag"))
        assertEquals("", GameSortKeys.bracketTag("unbalanced 【tag"))
    }

    @Test
    fun titleKeyIsLowercasedAndTrimmedWithRootLocale() {
        assertEquals("fate/stay night", GameSortKeys.titleKey("  Fate/Stay Night "))
        // Turkish-I 等 locale 特例不受系统 locale 影响
        assertEquals("i", GameSortKeys.titleKey("I"))
    }

    @Test
    fun tagKeyIsLowercased() {
        assertEquals("rl", GameSortKeys.tagKey("【RL】x"))
        assertEquals("", GameSortKeys.tagKey("no tag"))
    }

    @Test
    fun orderingWithBlankTagLastMatchesLegacyComparator() {
        data class Row(val title: String)

        val rows = listOf(
            Row("b tag"),      // 无标签 → 最后
            Row("【a】2"),      // 标签 a
            Row("【A】1"),      // 标签 a（大小写合并）
            Row("【a】0"),      // 标签 a
        )
        val sorted = rows.sortedWith(
            compareBy<Row> { GameSortKeys.bracketTag(it.title).isBlank() }
                .thenBy { GameSortKeys.tagKey(it.title) }
                .thenBy { GameSortKeys.titleKey(it.title) },
        )
        // 同标签组内按标题键排序：【A】1 在【a】0 之后
        assertEquals(
            listOf("【a】0", "【A】1", "【a】2", "b tag"),
            sorted.map { it.title },
        )
    }
}
