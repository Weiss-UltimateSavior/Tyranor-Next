package com.tyranor.next.core.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 游戏卡片角标默认关闭；卡片隐藏名称标签默认开启（两者同为应用设置项）。
 */
class GameCardBadgeSettingsTest {

    @Test
    fun engineBadgeDefaultsToOff() {
        assertFalse(AppSettingsStore.DEFAULT_GAME_CARD_BADGE)
        assertTrue(AppSettingsStore.KEY_GAME_CARD_BADGE.isNotBlank())
    }

    @Test
    fun hideTitleTagStillDefaultsToOn() {
        assertTrue(AppSettingsStore.DEFAULT_GAME_CARD_HIDE_TITLE_TAG)
    }
}
