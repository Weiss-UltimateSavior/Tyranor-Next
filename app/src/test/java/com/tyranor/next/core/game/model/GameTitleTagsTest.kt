package com.tyranor.next.core.game.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 卡片展示标题的标签处理：去掉全部【】/[] 标签、折叠空白、空结果回退原标题。
 */
class GameTitleTagsTest {

    @Test
    fun stripsAllBracketTags() {
        assertEquals("Game A", GameTitleTags.stripAll("【汉化】Game A [krkr]"))
        assertEquals("Game B", GameTitleTags.stripAll("[とわソフト]Game B【体験版】"))
        assertEquals("Game C", GameTitleTags.stripAll("【x】【y】Game C"))
    }

    @Test
    fun keepsTitleWithoutTags() {
        assertEquals("Plain Title", GameTitleTags.stripAll("Plain Title"))
    }

    @Test
    fun collapsesWhitespaceLeftByStripping() {
        assertEquals("Game D", GameTitleTags.stripAll("Game 【tag】 D"))
        assertEquals("Game D", GameTitleTags.stripAll("  [a]  Game D  "))
    }

    @Test
    fun fallsBackToTrimmedOriginalWhenResultEmpty() {
        assertEquals("【汉化】", GameTitleTags.stripAll("【汉化】"))
        assertEquals("[krkr]", GameTitleTags.stripAll("  [krkr] "))
    }

    @Test
    fun firstTagMatchesSortingKeySource() {
        assertEquals("汉化", GameTitleTags.firstTag("【汉化】Game A [krkr]"))
        assertEquals("krkr", GameTitleTags.firstTag("Game A [krkr]"))
        assertEquals("", GameTitleTags.firstTag("Game A"))
        // 排序键与标签解析同源
        assertEquals("汉化", GameSortKeys.bracketTag("【汉化】Game A"))
    }
}
