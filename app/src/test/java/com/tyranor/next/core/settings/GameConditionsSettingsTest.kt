package com.tyranor.next.core.settings

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.tyranor.next.core.game.model.GameLibraryConditions
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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

    @Test
    fun changingAndRestoringConditionsClearsFocusWithoutAReplacementPageReport() {
        val values = mutableMapOf<String, String>()
        val preferences = settingsProxy<SharedPreferences> { method, args ->
            when (method) {
                "getAll" -> values.toMap()
                "getString" -> values[args[0]] ?: args[1]
                "edit" -> {
                    val changes = mutableMapOf<String, String?>()
                    lateinit var editor: SharedPreferences.Editor
                    editor = settingsProxy { operation, parameters ->
                        when (operation) {
                            "putString" -> editor.also { changes[parameters[0] as String] = parameters[1] as String? }
                            "remove" -> editor.also { changes[parameters[0] as String] = null }
                            "apply" -> changes.forEach { (key, value) ->
                                if (value == null) values.remove(key) else values[key] = value
                            }
                            else -> error(operation)
                        }
                    }
                    editor
                }
                else -> error(method)
            }
        }
        val context = object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) = preferences
        }

        AppSettingsStore.initGameConditions(context)
        try {
            val original = AppSettingsStore.gameConditions.value
            AppSettingsStore.setGameShelfFocusUri(context, "book-b", original)
            AppSettingsStore.resetGameConditions(context)
            assertEquals(original, AppSettingsStore.gameConditions.value)
            assertEquals("book-b", AppSettingsStore.gameShelfFocusUri(context))

            AppSettingsStore.setGameFilterPlayed(context, "played")
            assertNull(AppSettingsStore.gameShelfFocusUri(context))
            AppSettingsStore.resetGameConditions(context)
            val restored = AppSettingsStore.gameConditions.value
            assertEquals(original.conditions, restored.conditions)
            assertNotEquals(original, restored)

            AppSettingsStore.setGameShelfFocusUri(context, "book-b", original)
            assertNull(AppSettingsStore.gameShelfFocusUri(context))
            AppSettingsStore.setGameShelfFocusUri(context, "book-a", restored)
            assertEquals("book-a", AppSettingsStore.gameShelfFocusUri(context))
        } finally {
            AppSettingsStore.initGameConditions(context)
        }
    }

    private inline fun <reified T> settingsProxy(crossinline invoke: (String, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            invoke(method.name, args ?: emptyArray())
        } as T
}
