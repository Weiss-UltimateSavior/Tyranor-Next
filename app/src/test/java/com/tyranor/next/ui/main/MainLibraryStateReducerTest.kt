package com.tyranor.next.ui.main

import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.settings.AppSettingsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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

        val withoutRecent = MainLibraryStateReducer.removeRecent(removed, second)
        assertTrue(withoutRecent.recentGames.none { it.uri == second.uri })
        assertTrue(withoutRecent.games.any { it.uri == second.uri })

        val deleted = MainLibraryStateReducer.deleteGame(withoutRecent, first.uri)
        assertFalse(deleted.games.any { it.uri == first.uri })
        assertFalse(deleted.recentGames.any { it.uri == first.uri })
        assertFalse(deleted.quickLaunch.any { it.uri == first.uri })
    }

    @Test
    fun removeRecentClearsOnlyThatGamesOpenTimeAndKeepsSearchHits() {
        val other = first.copy(openTime = 120L)
        val played = second.copy(openTime = 300L)
        val state = MainLibraryUiState(
            games = listOf(other, played),
            recentGames = listOf(played, other),
            searchQuery = "第二个",
            searchHitUris = setOf(played.uri),
        )

        val result = MainLibraryStateReducer.removeRecent(state, played)

        assertEquals(listOf(other), result.recentGames)
        assertEquals(listOf(other, played.copy(openTime = 0L)), result.games)
        assertEquals(setOf(played.uri), result.searchHitUris)
        assertSame(result, MainLibraryStateReducer.removeRecent(result, played))
    }

    @Test
    fun clearingAnOlderRecentEntryPreservesANewerLaunch() {
        val previous = first.copy(openTime = 100L)
        val relaunched = first.copy(openTime = 200L)
        val state = MainLibraryUiState(games = listOf(relaunched), recentGames = listOf(relaunched))

        assertSame(state, MainLibraryStateReducer.removeRecent(state, previous))
    }

    @Test
    fun titleChangesInvalidateSearchHitsWithTheNewGameSnapshot() {
        val state = MainLibraryUiState(
            games = listOf(first, second),
            searchQuery = "旧标题",
            searchHitUris = setOf(first.uri),
        )
        val renamed = first.copy(title = "新标题")
        val covered = first.copy(coverUri = "file:///cover.jpg")

        val localRename = MainLibraryStateReducer.replaceGame(state, renamed)
        assertEquals("旧标题", localRename.searchQuery)
        assertNull(localRename.searchHitUris)
        assertNull(MainLibraryStateReducer.acceptGames(state, listOf(renamed, second)).searchHitUris)
        assertNull(MainLibraryStateReducer.acceptStorageSnapshot(
            state, listOf(renamed, second), emptyList(), emptyList(), false, null,
        ).searchHitUris)
        assertEquals(state.searchHitUris, MainLibraryStateReducer.replaceGame(state, covered).searchHitUris)
    }

    @Test
    fun staleMetadataDoesNotRestoreAClearedPlayTime() {
        val cleared = MainLibraryUiState(games = listOf(first))
        val scraped = first.copy(coverUri = "file:///cover.jpg", openTime = 300L)
        val expected = scraped.copy(openTime = 0L)

        assertEquals(expected, MainLibraryStateReducer.replaceGame(cleared, scraped).games.single())
        assertEquals(expected, MainLibraryStateReducer.acceptGames(cleared, listOf(scraped)).games.single())
        assertEquals(expected, mergeChangedGameFields(first, first, scraped))
    }

    @Test
    fun optimisticDeletionDoesNotRemoveTheEngineUntilStorageConfirms() {
        val state = MainLibraryUiState(games = listOf(first), confirmedEngineTypes = setOf(first.engine))

        val pending = MainLibraryStateReducer.deleteGame(state, first.uri)

        assertTrue(pending.games.isEmpty())
        assertEquals(setOf(first.engine), pending.confirmedEngineTypes)

        val failed = MainLibraryStateReducer.acceptStorageSnapshot(
            pending, listOf(first), emptyList(), emptyList(), false, setOf(first.engine),
        )
        val succeeded = MainLibraryStateReducer.acceptStorageSnapshot(
            pending, emptyList(), emptyList(), emptyList(), false, emptySet(),
        )

        assertEquals(listOf(first), failed.games)
        assertEquals(setOf(first.engine), failed.confirmedEngineTypes)
        assertEquals(emptySet<EngineType>(), succeeded.confirmedEngineTypes)
    }

    @Test
    fun deletingAnUnconfirmedNewGameWaitsForAStorageSnapshot() {
        val state = MainLibraryUiState(games = listOf(first), confirmedEngineTypes = emptySet())

        val result = MainLibraryStateReducer.deleteGame(state, first.uri)

        assertNull(result.confirmedEngineTypes)
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

    @Test
    fun applyBindingUpdatePreservesConcurrentScrapedCoverAndOtherSourceBindings() {
        val scrapedInUi = first.copy(
            coverUri = "file:///covers/scraped.jpg",
            coverSource = AppSettingsStore.COVER_SOURCE_VNDB,
            vndbId = "v562",
            metadataTitle = "VNDB Title",
            openTime = 999L,
        )
        val state = MainLibraryUiState(
            games = listOf(scrapedInUi),
            recentGames = listOf(scrapedInUi.copy(openTime = 1234L)),
            quickLaunch = listOf(scrapedInUi),
        )
        // 绑定操作基于旧快照完成落库（仍带 coverUri = null, vndbId = null）
        val boundFromStaleSnapshot = first.copy(
            bangumiId = "4154",
            metadataTitle = "Bangumi Title",
        )

        val boundState = MainLibraryStateReducer.applyBindingUpdate(
            state,
            AppSettingsStore.COVER_SOURCE_BANGUMI,
            boundFromStaleSnapshot,
        )
        assertEquals("file:///covers/scraped.jpg", boundState.games.single().coverUri)
        assertEquals(AppSettingsStore.COVER_SOURCE_VNDB, boundState.games.single().coverSource)
        assertEquals("v562", boundState.games.single().vndbId)
        assertEquals("4154", boundState.games.single().bangumiId)
        assertEquals("Bangumi Title", boundState.games.single().metadataTitle)
        assertEquals(1234L, boundState.recentGames.single().openTime)
        assertEquals("4154", boundState.quickLaunch.single().bangumiId)

        // 解绑操作若从旧快照带回 null metadataTitle，不得擦除并发刮削已写入的 metadataTitle
        val unboundFromStaleSnapshot = first.copy(bangumiId = null, metadataTitle = null)
        val unboundState = MainLibraryStateReducer.applyBindingUpdate(
            boundState,
            AppSettingsStore.COVER_SOURCE_BANGUMI,
            unboundFromStaleSnapshot,
        )
        assertNull(unboundState.games.single().bangumiId)
        assertEquals("Bangumi Title", unboundState.games.single().metadataTitle)
        assertEquals("file:///covers/scraped.jpg", unboundState.games.single().coverUri)

        // 批量刮削旧快照迟到到达时，仅更新封面与缺失绑定，不得抹除已绑定的他源 ID 或改名标题
        val renamedAndBound = boundState.games.single().copy(title = "用户改名")
        val stateAfterRename = MainLibraryStateReducer.replaceGame(boundState, renamedAndBound)
        val lateScraped = first.copy(
            coverUri = "file:///covers/scraped2.jpg",
            coverSource = AppSettingsStore.COVER_SOURCE_VNDB,
            vndbId = "v999",
            bangumiId = null,
        )
        val afterScrape = MainLibraryStateReducer.applyScrapedGame(stateAfterRename, lateScraped)
        assertEquals("用户改名", afterScrape.games.single().title)
        assertEquals("4154", afterScrape.games.single().bangumiId)
        assertEquals("v562", afterScrape.games.single().vndbId)
        assertEquals("file:///covers/scraped2.jpg", afterScrape.games.single().coverUri)
    }

    @Test
    fun lateScrapedEventDoesNotResurrectUnboundIdWhenRevisionAdvanced() {
        // R5 中#1：批量刮削通知自「落库时刻」携带逐源绑定修订号；收集前用户并发解绑使修订号
        // 推进，该来源的缺失回填必须作废——否则迟到的通知会把刚解绑的条目 ID 重新填回 _uiState
        val state = MainLibraryUiState(games = listOf(first), recentGames = listOf(first), quickLaunch = listOf(first))
        val scraped = first.copy(
            coverUri = "file:///covers/scraped.jpg",
            coverSource = AppSettingsStore.COVER_SOURCE_VNDB,
            vndbId = "v10",
            metadataTitle = "候选标题",
        )

        val gated = MainLibraryStateReducer.applyScrapedGame(state, scraped, ScrapedBindingUnchanged(vndb = false))
        assertNull(gated.games.single().vndbId)
        assertNull(gated.recentGames.single().vndbId)
        assertNull(gated.quickLaunch.single().vndbId)
        // 封面列与 metadataTitle 不受绑定修订门控影响（解绑不写封面列）
        assertEquals("file:///covers/scraped.jpg", gated.games.single().coverUri)
        assertEquals("候选标题", gated.games.single().metadataTitle)

        // 修订号未推进时保持既有回填行为
        val unchanged = MainLibraryStateReducer.applyScrapedGame(state, scraped)
        assertEquals("v10", unchanged.games.single().vndbId)

        // 逐源独立：其它来源修订推进不影响本来源回填
        val partial = MainLibraryStateReducer.applyScrapedGame(
            state,
            scraped.copy(bangumiId = "4154"),
            ScrapedBindingUnchanged(bangumi = false),
        )
        assertEquals("v10", partial.games.single().vndbId)
        assertNull(partial.games.single().bangumiId)
    }

    @Test
    fun partialCoverRenameAndLaunchFileReducersPreserveConcurrentFields() {
        val concurrent = first.copy(
            title = "并发改名",
            launchFile = "app.exe",
            vndbId = "v562",
            bangumiId = "4154",
            openTime = 777L,
        )
        val state = MainLibraryUiState(
            games = listOf(concurrent),
            recentGames = listOf(concurrent),
            quickLaunch = listOf(concurrent),
            searchQuery = "并发",
            searchHitUris = setOf(concurrent.uri),
        )

        // 自定义封面（source = null）只更新封面字段，不得回滚并发标题/绑定/启动文件
        val customFromOld = first.copy(coverUri = "file:///custom.png", coverSource = AppSettingsStore.COVER_SOURCE_CUSTOM)
        val afterCustom = MainLibraryStateReducer.applyCoverUpdate(state, null, customFromOld)
        assertEquals("并发改名", afterCustom.games.single().title)
        assertEquals("app.exe", afterCustom.games.single().launchFile)
        assertEquals("v562", afterCustom.games.single().vndbId)
        assertEquals("4154", afterCustom.games.single().bangumiId)
        assertEquals("file:///custom.png", afterCustom.games.single().coverUri)

        // 候选封面绑定仅更新目标来源与封面，不覆盖他源既有绑定及并发标题
        val candidateFromOld = first.copy(
            coverUri = "file:///nextmoe.webp",
            coverSource = AppSettingsStore.COVER_SOURCE_NEXTMOE,
            nextmoeId = "207379",
            vndbId = "v999",
            metadataTitle = "Sakura",
        )
        val afterCandidate = MainLibraryStateReducer.applyCoverUpdate(
            afterCustom,
            AppSettingsStore.COVER_SOURCE_NEXTMOE,
            candidateFromOld,
        )
        assertEquals("并发改名", afterCandidate.games.single().title)
        assertEquals("v562", afterCandidate.games.single().vndbId)
        assertEquals("207379", afterCandidate.games.single().nextmoeId)
        assertEquals("Sakura", afterCandidate.games.single().metadataTitle)

        // 改名局部更新仅改 title 并失效 searchHitUris；启动文件局部更新仅改 launchFile
        val afterRename = MainLibraryStateReducer.renameGame(afterCandidate, first.uri, "最终标题")
        assertEquals("最终标题", afterRename.games.single().title)
        assertEquals("file:///nextmoe.webp", afterRename.games.single().coverUri)
        assertNull(afterRename.searchHitUris)

        val afterLaunchFile = MainLibraryStateReducer.updateLaunchFile(afterRename, first.uri, "boot.xp3")
        assertEquals("boot.xp3", afterLaunchFile.games.single().launchFile)
        assertEquals("最终标题", afterLaunchFile.games.single().title)
        assertEquals("207379", afterLaunchFile.games.single().nextmoeId)
    }

    private fun game(id: String, title: String) = ScanGame(
        title = title,
        uri = "content://games/$id",
        engine = EngineType.KIRIKIRI,
        launchTarget = "data.xp3",
    )
}
