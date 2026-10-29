package com.tyranor.next.core.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 游戏页排序 / 过滤取值的归一化（issue #118）。
 *
 * 这些取值会写进 `app_settings` 长期保存，旧版本残留、手改偏好或从备份恢复都可能带进未知值；
 * 归一化决定界面会不会落到「排序不生效 / 列表被清空」这种无意义状态，属于必须钉住的行为。
 */
class GameFilterSettingsTest {

    @Test
    fun sortKeepsTheFourSupportedModes() {
        assertEquals(
            AppSettingsStore.GAME_SORT_ALPHA,
            AppSettingsStore.normalizeGameSort(AppSettingsStore.GAME_SORT_ALPHA),
        )
        assertEquals(
            AppSettingsStore.GAME_SORT_BRACKET_TAG,
            AppSettingsStore.normalizeGameSort(AppSettingsStore.GAME_SORT_BRACKET_TAG),
        )
        assertEquals(
            AppSettingsStore.GAME_SORT_ENGINE,
            AppSettingsStore.normalizeGameSort(AppSettingsStore.GAME_SORT_ENGINE),
        )
        assertEquals(
            AppSettingsStore.GAME_SORT_RECENT,
            AppSettingsStore.normalizeGameSort(AppSettingsStore.GAME_SORT_RECENT),
        )
    }

    @Test
    fun unknownOrMissingSortFallsBackToAlpha() {
        assertEquals(AppSettingsStore.GAME_SORT_ALPHA, AppSettingsStore.normalizeGameSort(null))
        assertEquals(AppSettingsStore.GAME_SORT_ALPHA, AppSettingsStore.normalizeGameSort(""))
        // 旧实现用过的编号式取值不应被当成有效排序
        assertEquals(AppSettingsStore.GAME_SORT_ALPHA, AppSettingsStore.normalizeGameSort("v2"))
    }

    @Test
    fun playedFilterKeepsTheTwoFilters() {
        assertEquals(
            AppSettingsStore.GAME_FILTER_PLAYED_ONLY,
            AppSettingsStore.normalizeGameFilterPlayed(AppSettingsStore.GAME_FILTER_PLAYED_ONLY),
        )
        assertEquals(
            AppSettingsStore.GAME_FILTER_PLAYED_NONE,
            AppSettingsStore.normalizeGameFilterPlayed(AppSettingsStore.GAME_FILTER_PLAYED_NONE),
        )
    }

    @Test
    fun unknownOrMissingPlayedFilterFallsBackToAll() {
        assertEquals(
            AppSettingsStore.GAME_FILTER_PLAYED_ALL,
            AppSettingsStore.normalizeGameFilterPlayed(AppSettingsStore.GAME_FILTER_PLAYED_ALL),
        )
        assertEquals(AppSettingsStore.GAME_FILTER_PLAYED_ALL, AppSettingsStore.normalizeGameFilterPlayed(null))
        assertEquals(AppSettingsStore.GAME_FILTER_PLAYED_ALL, AppSettingsStore.normalizeGameFilterPlayed(""))
        // 损坏的取值必须回落成「全部」，否则列表会被整片过滤掉
        assertEquals(AppSettingsStore.GAME_FILTER_PLAYED_ALL, AppSettingsStore.normalizeGameFilterPlayed("playedOnly"))
    }
}
