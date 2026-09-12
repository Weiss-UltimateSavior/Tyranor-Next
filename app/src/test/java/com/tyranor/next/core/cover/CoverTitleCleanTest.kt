package com.tyranor.next.core.cover

import org.junit.Assert.assertEquals
import org.junit.Test

class CoverTitleCleanTest {

    @Test
    fun keepsWordsContainingVersionKeywords() {
        // "Dispatch" 内含 "patch" 子串，整词匹配下不得被截断
        assertEquals("Dispatch", cleanTitle("Dispatch"))
        assertEquals("Incomplete", cleanTitle("Incomplete"))
    }

    @Test
    fun stripsVersionWordsAsWholeWords() {
        assertEquals("Game", cleanTitle("Game Trial"))
        assertEquals("Game Edition", cleanTitle("Game Complete Edition"))
    }

    @Test
    fun stripsBracketsAndLocalizedEditionWords() {
        assertEquals("Game Name", cleanTitle("[Group] Game Name 中文版"))
        assertEquals("Game Name", cleanTitle("【汉化】Game Name"))
    }
}
