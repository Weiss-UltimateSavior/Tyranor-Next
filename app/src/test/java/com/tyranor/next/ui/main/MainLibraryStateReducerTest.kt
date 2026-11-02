package com.tyranor.next.ui.main

import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.game.model.ScanGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainLibraryStateReducerTest {
    private val first = game("first", "旧标题")
    private val second = game("second", "第二个")

    @Test
    fun acceptGamesRemovesMissingDerivedEntries() {
        val state = MainLibraryUiState(
            games = listOf(first, second),
            recentGames = listOf(first.copy(openTime = 100L), second.copy(openTime = 200L)),
            quickLaunch = listOf(first, second),
        )

        val result = MainLibraryStateReducer.acceptGames(state, listOf(second))

        assertEquals(listOf(second), result.games)
        assertEquals(listOf(second.uri), result.recentGames.map { it.uri })
        assertEquals(listOf(second), result.quickLaunch)
        assertTrue(result.loaded)
    }

    @Test
    fun replaceGameUpdatesEveryPageAndKeepsRecentOpenTime() {
        val recent = first.copy(openTime = 456L)
        val state = MainLibraryUiState(
            games = listOf(first),
            recentGames = listOf(recent),
            quickLaunch = listOf(first),
            loaded = true,
        )
        val renamed = first.copy(title = "新标题", coverUri = "file:///cover.jpg")

        val result = MainLibraryStateReducer.replaceGame(state, renamed)

        assertEquals(renamed, result.games.single())
        assertEquals("新标题", result.recentGames.single().title)
        assertEquals(456L, result.recentGames.single().openTime)
        assertEquals(renamed, result.quickLaunch.single())
    }

    @Test
    fun quickLaunchRecentAndDeleteMutationsStayIndependent() {
        val state = MainLibraryUiState(
            games = listOf(first, second),
            recentGames = listOf(first, second),
            quickLaunch = listOf(first),
            loaded = true,
        )

        val added = MainLibraryStateReducer.toggleQuickLaunch(state, second)
        assertEquals(listOf(first, second), added.quickLaunch)

        val removed = MainLibraryStateReducer.toggleQuickLaunch(added, first)
        assertEquals(listOf(second), removed.quickLaunch)

        val withoutRecent = MainLibraryStateReducer.removeRecent(removed, second.uri)
        assertTrue(withoutRecent.recentGames.none { it.uri == second.uri })
        assertTrue(withoutRecent.games.any { it.uri == second.uri })

        val deleted = MainLibraryStateReducer.deleteGame(withoutRecent, first.uri)
        assertFalse(deleted.games.any { it.uri == first.uri })
        assertFalse(deleted.recentGames.any { it.uri == first.uri })
        assertFalse(deleted.quickLaunch.any { it.uri == first.uri })
    }

    @Test
    fun mergeChangedFieldsKeepsConcurrentCoverWhenOnlyTitleChanged() {
        val before = first.copy(coverUri = "file:///old.jpg", coverSource = "old")
        val scraped = before.copy(coverUri = "file:///new.jpg", coverSource = "vndb")
        val renamedFromOldSnapshot = before.copy(title = "重命名")

        val merged = mergeChangedGameFields(scraped, before, renamedFromOldSnapshot)

        assertEquals("重命名", merged.title)
        assertEquals("file:///new.jpg", merged.coverUri)
        assertEquals("vndb", merged.coverSource)
    }

    @Test
    fun mergeChangedFieldsKeepsExternalModuleAliasWhenUnchangedByUpdate() {
        val before = first.copy(externalModuleAlias = "internal.rpgmxp")
        val base = before.copy(coverUri = "file:///new.jpg")
        val renamedFromOldSnapshot = before.copy(title = "重命名")

        val merged = mergeChangedGameFields(base, before, renamedFromOldSnapshot)

        assertEquals("internal.rpgmxp", merged.externalModuleAlias)
    }

    private fun game(id: String, title: String) = ScanGame(
        title = title,
        uri = "content://games/$id",
        engine = EngineType.KIRIKIRI,
        launchTarget = "data.xp3",
    )
}
