package com.tyranor.next.ui.game

import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.settings.AppSettingsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** 游戏页过滤器/排序的纯函数回归（issue #118）。 */
class GameFilterTest {
    private fun game(
        title: String,
        engine: EngineType = EngineType.KIRIKIRI,
        openTime: Long = 0,
    ) = ScanGame(
        title = title,
        uri = "content://games/$title",
        engine = engine,
        launchTarget = "data.xp3",
        openTime = openTime,
    )

    private val library = listOf(
        game("B 游戏", EngineType.KIRIKIRI, openTime = 0),
        game("A 游戏", EngineType.ONS, openTime = 1_000),
        game("C 游戏", EngineType.KIRIKIRI, openTime = 2_000),
    )

    @Test
    fun engineSortGroupsByEnumOrderThenTitle() {
        // 同引擎样本故意按 C→B 输入：若丢掉「组内按标题」的 tie-break，稳定排序会原样保留 C、B
        val engineLibrary = listOf(
            game("C 游戏", EngineType.KIRIKIRI, openTime = 2_000),
            game("A 游戏", EngineType.ONS, openTime = 1_000),
            game("B 游戏", EngineType.KIRIKIRI, openTime = 0),
        )
        val sorted = sortGames(engineLibrary, AppSettingsStore.GAME_SORT_ENGINE)

        // KIRIKIRI 在枚举中先于 ONS：同引擎内按标题，跨引擎按枚举声明顺序
        assertEquals(listOf("B 游戏", "C 游戏", "A 游戏"), sorted.map { it.title })
    }

    @Test
    fun recentSortPutsNeverOpenedLast() {
        val sorted = sortGames(library, AppSettingsStore.GAME_SORT_RECENT)

        assertEquals(listOf("C 游戏", "A 游戏", "B 游戏"), sorted.map { it.title })
    }

    @Test
    fun alphaSortIsDefaultForUnknownMode() {
        val sorted = sortGames(library, "unknown_mode")

        assertEquals(listOf("A 游戏", "B 游戏", "C 游戏"), sorted.map { it.title })
    }

    @Test
    fun engineFilterKeepsOnlyMatchingEngine() {
        val filtered = filterGames(library, EngineType.KIRIKIRI.name, AppSettingsStore.GAME_FILTER_PLAYED_ALL)

        assertEquals(listOf("B 游戏", "C 游戏"), filtered.map { it.title })
    }

    @Test
    fun playedFilterSplitsByOpenTime() {
        val played = filterGames(library, "", AppSettingsStore.GAME_FILTER_PLAYED_ONLY)
        val unplayed = filterGames(library, "", AppSettingsStore.GAME_FILTER_PLAYED_NONE)

        assertEquals(listOf("A 游戏", "C 游戏"), played.map { it.title })
        assertEquals(listOf("B 游戏"), unplayed.map { it.title })
    }

    @Test
    fun unknownPlayedFilterKeepsEverything() {
        // 旧版本残留或损坏的取值回落为「全部」，不能把列表清空
        val filtered = filterGames(library, "", "bogus")

        assertEquals(library.map { it.title }, filtered.map { it.title })
    }

    @Test
    fun filterPreservesSortedOrder() {
        // 无搜索时筛选结果直接复用、不再二次排序，前提是 filter 保持「当前排序」而不是标题序。
        // 用 recent（C,A,B）与 engine（B,C,A）样本：若 filterGames 改成按标题重排，断言即失败。
        val byRecent = sortGames(library, AppSettingsStore.GAME_SORT_RECENT)
        assertEquals(
            listOf("C 游戏", "A 游戏"),
            filterGames(byRecent, "", AppSettingsStore.GAME_FILTER_PLAYED_ONLY).map { it.title },
        )

        val byEngine = sortGames(library, AppSettingsStore.GAME_SORT_ENGINE)
        assertEquals(
            listOf("B 游戏", "C 游戏"),
            filterGames(byEngine, EngineType.KIRIKIRI.name, AppSettingsStore.GAME_FILTER_PLAYED_ALL)
                .map { it.title },
        )
    }

    @Test
    fun recentSortTieBreaksByTitle() {
        val ties = listOf(
            game("B 游戏", EngineType.KIRIKIRI, openTime = 500),
            game("A 游戏", EngineType.ONS, openTime = 500),
        )

        assertEquals(
            listOf("A 游戏", "B 游戏"),
            sortGames(ties, AppSettingsStore.GAME_SORT_RECENT).map { it.title },
        )
    }

    @Test
    fun filtersCombine() {
        val filtered = filterGames(library, EngineType.KIRIKIRI.name, AppSettingsStore.GAME_FILTER_PLAYED_ONLY)

        assertEquals(listOf("C 游戏"), filtered.map { it.title })
    }

    @Test
    fun noActiveFilterReturnsSameList() {
        // 无条件时直接返回原列表，避免每次重组都复制整个游戏库
        assertSame(library, filterGames(library, "", AppSettingsStore.GAME_FILTER_PLAYED_ALL))
    }
}
