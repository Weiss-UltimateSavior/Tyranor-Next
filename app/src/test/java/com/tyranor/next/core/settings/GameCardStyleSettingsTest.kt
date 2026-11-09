package com.tyranor.next.core.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 游戏卡片风格归一：接受 waterfall / uniform_grid / cover_flow；
 * 历史值 grid（当时渲染瀑布流）、空值、未知值与大小写/空白差异按默认瀑布处理。
 */
class GameCardStyleSettingsTest {

    @Test
    fun explicitStylesAreAccepted() {
        assertEquals(
            AppSettingsStore.GAME_CARD_STYLE_WATERFALL,
            AppSettingsStore.normalizeGameCardStyle("waterfall"),
        )
        assertEquals(
            AppSettingsStore.GAME_CARD_STYLE_GRID,
            AppSettingsStore.normalizeGameCardStyle("uniform_grid"),
        )
        assertEquals(
            AppSettingsStore.GAME_CARD_STYLE_COVER_FLOW,
            AppSettingsStore.normalizeGameCardStyle("cover_flow"),
        )
        assertEquals(
            AppSettingsStore.GAME_CARD_STYLE_COVER_FLOW,
            AppSettingsStore.normalizeGameCardStyle(" COVER_FLOW "),
        )
    }

    @Test
    fun legacyGridAndInvalidValuesFallBackToWaterfall() {
        // 历史持久化值 "grid" 在当时渲染瀑布流；升级后必须保持瀑布，不能跳变为网格。
        assertEquals(
            AppSettingsStore.GAME_CARD_STYLE_WATERFALL,
            AppSettingsStore.normalizeGameCardStyle("grid"),
        )
        assertEquals(
            AppSettingsStore.GAME_CARD_STYLE_WATERFALL,
            AppSettingsStore.normalizeGameCardStyle(null),
        )
        assertEquals(
            AppSettingsStore.GAME_CARD_STYLE_WATERFALL,
            AppSettingsStore.normalizeGameCardStyle(""),
        )
        assertEquals(
            AppSettingsStore.GAME_CARD_STYLE_WATERFALL,
            AppSettingsStore.normalizeGameCardStyle("unknown"),
        )
        assertEquals(
            AppSettingsStore.GAME_CARD_STYLE_WATERFALL,
            AppSettingsStore.DEFAULT_GAME_CARD_STYLE,
        )
    }
}
