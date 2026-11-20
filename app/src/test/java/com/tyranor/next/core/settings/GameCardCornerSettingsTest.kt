package com.tyranor.next.core.settings

import com.tyranor.next.testing.InMemoryPreferencesContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class GameCardCornerSettingsTest {
    private val context = InMemoryPreferencesContext()

    @After
    fun resetState() {
        AppSettingsStore.initGameCardCorners(InMemoryPreferencesContext())
    }

    @Test
    fun independentRadiusSurvivesDisablingAndReloading() {
        AppSettingsStore.setAppCornerRadius(context, 25)
        AppSettingsStore.initGameCardCorners(context)
        assertEquals(GameCardCornerSettings(enabled = false, radiusDp = 15), AppSettingsStore.gameCardCorners.value)

        AppSettingsStore.updateGameCardCorners(context) { it.copy(enabled = true) }
        AppSettingsStore.updateGameCardCorners(context) { it.copy(radiusDp = 43) }
        AppSettingsStore.updateGameCardCorners(context) { it.copy(enabled = false) }

        resetState()
        AppSettingsStore.initGameCardCorners(context)
        assertEquals(GameCardCornerSettings(enabled = false, radiusDp = 45), AppSettingsStore.gameCardCorners.value)

        AppSettingsStore.updateGameCardCorners(context) { it.copy(enabled = true) }
        resetState()
        AppSettingsStore.initGameCardCorners(context)
        assertEquals(GameCardCornerSettings(enabled = true, radiusDp = 45), AppSettingsStore.gameCardCorners.value)
        assertEquals(25, AppSettingsStore.getAppCornerRadius(context))
    }
}
