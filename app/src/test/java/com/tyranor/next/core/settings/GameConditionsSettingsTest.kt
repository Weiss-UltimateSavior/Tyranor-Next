package com.tyranor.next.core.settings

import com.tyranor.next.core.game.model.GameLibraryConditions
import org.junit.Assert.assertEquals
import org.junit.Test

class GameConditionsSettingsTest {
    @Test
    fun retainsSortFromInstallationsWithoutFilters() {
        val restored = AppSettingsStore.decodeGameConditions(mapOf("game_sort" to "bracket_tag"))

        assertEquals(GameLibraryConditions(sortMode = "bracket_tag"), restored)
    }

    @Test
    fun restoresSavedSortAndFiltersTogether() {
        val restored = AppSettingsStore.decodeGameConditions(
            mapOf(
                "game_sort" to "recent",
                "game_filter_engine" to " KIRIKIRI ",
                "game_filter_played" to "played",
            ),
        )

        assertEquals(GameLibraryConditions("recent", "KIRIKIRI", "played"), restored)
    }

    @Test
    fun invalidSavedValuesFallBackIndependently() {
        val restored = AppSettingsStore.decodeGameConditions(
            mapOf(
                "game_sort" to 3,
                "game_filter_engine" to "REMOVED_ENGINE",
                "game_filter_played" to "unplayed",
            ),
        )

        assertEquals(GameLibraryConditions(playedFilter = "unplayed"), restored)
    }

    @Test
    fun invalidPlayedFilterDoesNotDiscardValidSortAndEngine() {
        val restored = AppSettingsStore.decodeGameConditions(
            mapOf(
                "game_sort" to "engine",
                "game_filter_engine" to "CMVS",
                "game_filter_played" to "unknown",
            ),
        )

        assertEquals(GameLibraryConditions(sortMode = "engine", engineFilter = "CMVS"), restored)
    }
}
