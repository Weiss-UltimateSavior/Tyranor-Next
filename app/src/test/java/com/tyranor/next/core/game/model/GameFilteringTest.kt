package com.tyranor.next.core.game.model

import com.tyranor.next.core.engine.EngineType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 游戏页排序 / 过滤的纯函数回归（issue #118）。
 *
 * 只断言行为：排序结果顺序、过滤命中集、以及「无过滤时返回原引用」这个被调用方依赖的前提。
 */
class GameFilteringTest {
    private val krkrA = game("A 游戏", EngineType.KIRIKIRI, openTime = 0)
    private val krkrB = game("B 游戏", EngineType.KIRIKIRI, openTime = 300)
    private val onsC = game("C 游戏", EngineType.ONS, openTime = 100)

    @Test
    fun engineFilterExpiresOnlyAfterLibraryConfirmsItsAbsence() {
        val conditions = GameLibraryConditions(engineFilter = EngineType.KIRIKIRI.name)
        val otherEngines = setOf(EngineType.ONS)
        assertFalse(conditions.isEngineFilterMissing(otherEngines, null))
        assertFalse(conditions.isEngineFilterMissing(otherEngines, otherEngines + EngineType.KIRIKIRI))
        assertFalse(conditions.isEngineFilterMissing(otherEngines + EngineType.KIRIKIRI, otherEngines))
        assertTrue(conditions.isEngineFilterMissing(otherEngines, otherEngines))
        assertFalse(GameLibraryConditions().isEngineFilterMissing(emptySet(), emptySet()))
    }

    @Test
    fun alphaSortsByTitle() {
        val sorted = sortGames(listOf(onsC, krkrB, krkrA), GameFilterOptions.GAME_SORT_ALPHA)

        assertEquals(listOf("A 游戏", "B 游戏", "C 游戏"), sorted.map { it.title })
    }

    @Test
    fun engineSortGroupsByEngineOrderThenTitle() {
        val onsA = game("A ONS", EngineType.ONS, openTime = 0)
        val krkrZ = game("Z Kirikiri", EngineType.KIRIKIRI, openTime = 0)
        val sorted = sortGames(listOf(krkrZ, onsA, krkrB), GameFilterOptions.GAME_SORT_ENGINE)

        assertEquals(listOf(krkrB, krkrZ, onsA), sorted)
    }

    @Test
    fun bracketTagSortGroupsBothBracketStylesAndLeavesUntaggedLast() {
        val untagged = game("A 游戏", EngineType.KIRIKIRI, openTime = 0)
        val laterTag = game("[Beta] A", EngineType.KIRIKIRI, openTime = 0)
        val firstTag = game("【alpha】 Z", EngineType.ONS, openTime = 0)
        val sorted = sortGames(listOf(laterTag, untagged, firstTag), GameFilterOptions.GAME_SORT_BRACKET_TAG)

        assertEquals(listOf(firstTag, laterTag, untagged), sorted)
    }

    @Test
    fun recentSortPutsNeverPlayedLast() {
        val sorted = sortGames(listOf(krkrA, onsC, krkrB), GameFilterOptions.GAME_SORT_RECENT)

        // openTime 300 > 100 > 0；未游玩（0）自然落到末尾
        assertEquals(listOf("B 游戏", "C 游戏", "A 游戏"), sorted.map { it.title })
    }

    @Test
    fun recentSortTieBreaksByTitleWhenOpenTimeIsEqual() {
        val earlier = game("Z 游戏", EngineType.KIRIKIRI, openTime = 500)
        val later = game("Y 游戏", EngineType.KIRIKIRI, openTime = 500)

        val sorted = sortGames(listOf(earlier, later), GameFilterOptions.GAME_SORT_RECENT)

        assertEquals(listOf("Y 游戏", "Z 游戏"), sorted.map { it.title })
    }

    @Test
    fun unknownSortModeFallsBackToAlpha() {
        val sorted = sortGames(listOf(onsC, krkrA), "bogus")

        assertEquals(listOf("A 游戏", "C 游戏"), sorted.map { it.title })
    }

    @Test
    fun noFilterReturnsTheSameListInstance() {
        // 无过滤时返回原引用，调用方不必复制列表；别改成总返回新列表
        val games = listOf(krkrA, onsC)

        val filtered = filterGames(
            games,
            GameFilterOptions.GAME_FILTER_ENGINE_ALL,
            GameFilterOptions.GAME_FILTER_PLAYED_ALL,
        )

        assertSame(games, filtered)
    }

    @Test
    fun engineFilterKeepsOnlyThatEngine() {
        val filtered = filterGames(
            listOf(krkrA, onsC, krkrB),
            EngineType.KIRIKIRI.name,
            GameFilterOptions.GAME_FILTER_PLAYED_ALL,
        )

        assertEquals(listOf("A 游戏", "B 游戏"), filtered.map { it.title })
    }

    @Test
    fun playedFilterUsesOpenTime() {
        val noOpenTime = game("未记录", EngineType.ONS, openTime = -1)
        val games = listOf(krkrA, onsC, noOpenTime, krkrB)
        val played = filterGames(
            games,
            GameFilterOptions.GAME_FILTER_ENGINE_ALL,
            GameFilterOptions.GAME_FILTER_PLAYED_ONLY,
        )
        val unplayed = filterGames(
            games,
            GameFilterOptions.GAME_FILTER_ENGINE_ALL,
            GameFilterOptions.GAME_FILTER_PLAYED_NONE,
        )

        assertEquals(listOf("C 游戏", "B 游戏"), played.map { it.title })
        assertEquals(listOf(krkrA, noOpenTime), unplayed)
    }

    @Test
    fun filtersCombine() {
        val filtered = filterGames(
            listOf(krkrA, onsC, krkrB),
            EngineType.KIRIKIRI.name,
            GameFilterOptions.GAME_FILTER_PLAYED_ONLY,
        )

        assertEquals(listOf("B 游戏"), filtered.map { it.title })
    }

    @Test
    fun filterPreservesInputOrder() {
        // 无搜索时调用方直接复用过滤结果、不再二次排序，前提是 filter 保序
        val sorted = sortGames(listOf(krkrA, onsC, krkrB), GameFilterOptions.GAME_SORT_RECENT)
        val filtered = filterGames(
            sorted,
            EngineType.KIRIKIRI.name,
            GameFilterOptions.GAME_FILTER_PLAYED_ALL,
        )

        assertEquals(listOf("B 游戏", "A 游戏"), filtered.map { it.title })
    }

    private fun game(title: String, engine: EngineType, openTime: Long) = ScanGame(
        title = title,
        uri = "content://games/$title",
        engine = engine,
        launchTarget = "data.xp3",
        openTime = openTime,
    )
}
