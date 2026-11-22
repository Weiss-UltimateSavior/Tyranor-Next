package com.tyranor.next.core.settings

import android.content.Context
import com.tyranor.next.testing.InMemoryPreferencesContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class GameCardColumnSettingsTest {
    private val context = InMemoryPreferencesContext()

    @Before
    fun initialize() {
        AppSettingsStore.initGameCardColumns(context)
    }

    @After
    fun resetState() {
        AppSettingsStore.initGameCardColumns(InMemoryPreferencesContext())
    }

    @Test
    fun keepsExistingDefaultsWithoutSavedColumns() {
        assertEquals(3, AppSettingsStore.gameCardColumns.value.forScreen(wideScreen = false))
        assertEquals(6, AppSettingsStore.gameCardColumns.value.forScreen(wideScreen = true))
    }

    @Test
    fun restoresSeparateScreenWidthsAfterResettingFilters() {
        AppSettingsStore.setGameCardColumns(context, wideScreen = false, columns = 4)
        assertEquals(GameCardColumnSettings(narrow = 4, wide = 6), AppSettingsStore.gameCardColumns.value)
        AppSettingsStore.setGameCardColumns(context, wideScreen = true, columns = 7)
        AppSettingsStore.resetGameConditions(context)
        assertEquals(GameCardColumnSettings(narrow = 4, wide = 7), AppSettingsStore.gameCardColumns.value)

        resetState()
        initialize()

        assertEquals(GameCardColumnSettings(narrow = 4, wide = 7), AppSettingsStore.gameCardColumns.value)
    }

    @Test
    fun clampsColumnsToTheScreenWidthRange() {
        AppSettingsStore.setGameCardColumns(context, wideScreen = false, columns = 9)
        AppSettingsStore.setGameCardColumns(context, wideScreen = true, columns = 1)

        assertEquals(GameCardColumnSettings(narrow = 6, wide = 2), AppSettingsStore.gameCardColumns.value)
    }

    @Test
    fun clampsOutOfRangeColumnsOnStartup() {
        context.getSharedPreferences(AppStorageNames.APP_SETTINGS_PREFS, Context.MODE_PRIVATE).edit()
            .putInt("game_card_columns_narrow", 12)
            .putInt("game_card_columns_wide", 99)
            .apply()

        initialize()

        assertEquals(GameCardColumnSettings(narrow = 6, wide = 8), AppSettingsStore.gameCardColumns.value)
    }
}
