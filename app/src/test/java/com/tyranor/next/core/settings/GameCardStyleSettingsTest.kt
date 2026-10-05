package com.tyranor.next.core.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 游戏卡片风格归一：仅接受 cover_flow / grid，其余（含空/大小写/空白）回退网格。
 */
class GameCardStyleSettingsTest {

    @Test
    fun coverFlowIsAccepted() {
        assertEquals(AppSettingsStore.GAME_CARD_STYLE_COVER_FLOW, AppSettingsStore.normalizeGameCardStyle("cover_flow"))
        assertEquals(AppSettingsStore.GAME_CARD_STYLE_COVER_FLOW, AppSettingsStore.normalizeGameCardStyle(" COVER_FLOW "))
    }

    @Test
    fun gridIsAcceptedAndIsTheFallback() {
        assertEquals(AppSettingsStore.GAME_CARD_STYLE_GRID, AppSettingsStore.normalizeGameCardStyle("grid"))
        assertEquals(AppSettingsStore.GAME_CARD_STYLE_GRID, AppSettingsStore.normalizeGameCardStyle(null))
        assertEquals(AppSettingsStore.GAME_CARD_STYLE_GRID, AppSettingsStore.normalizeGameCardStyle(""))
        assertEquals(AppSettingsStore.GAME_CARD_STYLE_GRID, AppSettingsStore.normalizeGameCardStyle("unknown"))
        assertEquals(AppSettingsStore.GAME_CARD_STYLE_GRID, AppSettingsStore.DEFAULT_GAME_CARD_STYLE)
    }
}
