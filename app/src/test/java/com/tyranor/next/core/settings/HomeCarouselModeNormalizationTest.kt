package com.tyranor.next.core.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 首页轮播来源归一：quick / random 精确接受，其余（含空/未知/大小写差异）回退最近打开。
 */
class HomeCarouselModeNormalizationTest {

    @Test
    fun quickAndRandomAreAccepted() {
        assertEquals(AppSettingsStore.HOME_CAROUSEL_QUICK, AppSettingsStore.normalizeHomeCarouselMode("quick"))
        assertEquals(AppSettingsStore.HOME_CAROUSEL_RANDOM, AppSettingsStore.normalizeHomeCarouselMode("random"))
    }

    @Test
    fun recentIsAcceptedAndIsTheFallback() {
        assertEquals(AppSettingsStore.HOME_CAROUSEL_RECENT, AppSettingsStore.normalizeHomeCarouselMode("recent"))
        assertEquals(AppSettingsStore.HOME_CAROUSEL_RECENT, AppSettingsStore.normalizeHomeCarouselMode(null))
        assertEquals(AppSettingsStore.HOME_CAROUSEL_RECENT, AppSettingsStore.normalizeHomeCarouselMode(""))
        assertEquals(AppSettingsStore.HOME_CAROUSEL_RECENT, AppSettingsStore.normalizeHomeCarouselMode("QUICK"))
        assertEquals(AppSettingsStore.HOME_CAROUSEL_RECENT, AppSettingsStore.DEFAULT_HOME_CAROUSEL_MODE)
    }
}
