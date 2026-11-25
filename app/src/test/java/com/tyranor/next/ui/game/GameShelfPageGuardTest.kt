package com.tyranor.next.ui.game

import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.game.model.GameFilterOptions
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.game.model.sortGames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 书架与筛选抽屉在游戏库数量变化（如删除第三方模块游戏并重新扫描）时的索引边界防护测试。
 */
class GameShelfPageGuardTest {

    private fun game(
        index: Int,
        engine: EngineType = EngineType.KIRIKIRI,
        openTime: Long = 0L,
    ) = ScanGame(
        title = "Game $index",
        uri = "content://games/$index",
        engine = engine,
        launchTarget = "data.xp3",
        openTime = openTime,
    )

    @Test
    fun shelfPageKeyReturnsUriWithinBoundsAndFallsBackToIndexWhenListShrunk() {
        // sortGames 在 JVM 上通过 Arrays.asList 返回定长 java.util.Arrays$ArrayList。
        val threeGames = sortGames(
            listOf(
                game(2),
                game(0),
                game(1),
            ),
            GameFilterOptions.GAME_SORT_ALPHA,
        )

        assertEquals("content://games/0", shelfPageKey(threeGames, 0))
        assertEquals("content://games/1", shelfPageKey(threeGames, 1))
        assertEquals("content://games/2", shelfPageKey(threeGames, 2))
        // 子组合更新前 pageCount 先涨到 4 时，旧列表下标 3 不得越界。
        assertEquals(3, shelfPageKey(threeGames, 3))
        assertEquals(-1, shelfPageKey(threeGames, -1))
        assertEquals(0, shelfPageKey(emptyList(), 0))
    }

    @Test
    fun shelfPageAdjustmentClampsOutOfBoundsPageWhenMeasuredKeyIsMissing() {
        val threeGames = sortGames(
            listOf(
                game(0),
                game(1),
                game(2),
            ),
            GameFilterOptions.GAME_SORT_ALPHA,
        )

        assertNull(shelfPageAdjustment(threeGames, page = 2, measuredUri = null))
        assertEquals(2, shelfPageAdjustment(threeGames, page = 3, measuredUri = null))
        assertEquals(0, shelfPageAdjustment(threeGames, page = -1, measuredUri = null))
        assertNull(shelfPageAdjustment(emptyList(), page = 3, measuredUri = null))
    }

    @Test
    fun shelfPageAdjustmentReanchorsWhenDeletedLastModuleGameRemovesTailPage() {
        val remaining = sortGames(
            listOf(
                game(0, openTime = 100L),
                game(1, openTime = 500L),
                game(2, openTime = 200L),
            ),
            GameFilterOptions.GAME_SORT_ALPHA,
        )

        // 删除了下标 3 的第三方模块游戏后，测量键已不在新列表内：回落到最近游玩的那一本。
        assertEquals(1, shelfPageAdjustment(remaining, page = 3, measuredUri = "content://games/deleted-module"))
        // 若当前显示的游戏在新列表里移到了新下标，则对齐到新下标。
        assertEquals(2, shelfPageAdjustment(remaining, page = 3, measuredUri = "content://games/2"))
        // 下标与 URI 已一致时无需调整。
        assertNull(shelfPageAdjustment(remaining, page = 1, measuredUri = "content://games/1"))
    }

    @Test
    fun filterEngineChoicesRetainRemovedEngineUntilClearedAndClampSelection() {
        val availableWithoutRuntime = listOf(EngineType.KIRIKIRI, EngineType.ONS, EngineType.TYRANO)

        val retained = resolveFilterEngineChoices(availableWithoutRuntime, EngineType.RUNTIME_MODULE.name)
        assertEquals(
            listOf(EngineType.KIRIKIRI, EngineType.ONS, EngineType.TYRANO, EngineType.RUNTIME_MODULE),
            retained,
        )

        val keysAfterReset = listOf(GameFilterOptions.GAME_FILTER_ENGINE_ALL) +
            resolveFilterEngineChoices(availableWithoutRuntime, GameFilterOptions.GAME_FILTER_ENGINE_ALL)
                .map { it.name }
        assertEquals(4, keysAfterReset.size)
        // 选中项已从列表消失且筛选回到全部/未知值时，索引收敛到合法范围。
        assertEquals(0, resolveFilterSelectionIndex(keysAfterReset, EngineType.RUNTIME_MODULE.name))
        assertEquals(0, resolveFilterSelectionIndex(emptyList(), EngineType.RUNTIME_MODULE.name))
    }

    @Test
    fun shelfPageAdjustmentGuardAppliesImmediatelyOnceWhenInteractive() {
        val guard = ShelfPageAdjustmentGuard(2 to 0.25f)

        assertFalse(guard.applied)
        assertEquals(2 to 0.25f, guard.consumeIfReady(interactive = true, hasPendingFocus = false))
        assertTrue(guard.applied)
        // 已应用后即使再次检查也不重放。
        assertNull(guard.consumeIfReady(interactive = true, hasPendingFocus = false))
    }

    @Test
    fun shelfPageAdjustmentGuardDefersWhileNonInteractiveThenAppliesOnce() {
        val guard = ShelfPageAdjustmentGuard(1 to 0f)

        // 重排发生时处于不可交互（如抽屉/藏书阁遮挡）：暂不应用也不消耗机会。
        assertNull(guard.consumeIfReady(interactive = false, hasPendingFocus = false))
        assertFalse(guard.applied)
        // 存在 pendingFocusUri 时同样暂不应用。
        assertNull(guard.consumeIfReady(interactive = true, hasPendingFocus = true))
        assertFalse(guard.applied)

        // 恢复交互且无聚焦请求时应用一次。
        assertEquals(1 to 0f, guard.consumeIfReady(interactive = true, hasPendingFocus = false))
        assertTrue(guard.applied)

        // 用户翻页后再经历 interactive false -> true，不再重放旧调整。
        assertNull(guard.consumeIfReady(interactive = false, hasPendingFocus = false))
        assertNull(guard.consumeIfReady(interactive = true, hasPendingFocus = false))
    }

    @Test
    fun shelfPageAdjustmentGuardAllowsNewAdjustmentWhenGamesChange() {
        val initialGames = listOf(game(0), game(1), game(2))
        val firstPage = shelfPageAdjustment(initialGames, page = 3, measuredUri = "content://games/1")
        val firstGuard = ShelfPageAdjustmentGuard(firstPage?.let { it to 0f })

        assertEquals(1 to 0f, firstGuard.consumeIfReady(interactive = true, hasPendingFocus = false))
        assertNull(firstGuard.consumeIfReady(interactive = false, hasPendingFocus = false))
        assertNull(firstGuard.consumeIfReady(interactive = true, hasPendingFocus = false))

        // games 再次变化（如删除当前页并重扫）后，重建守卫重新允许应用一次。
        val updatedGames = listOf(game(0, openTime = 100L), game(2, openTime = 300L))
        val secondPage = shelfPageAdjustment(updatedGames, page = 1, measuredUri = "content://games/1")
        val secondGuard = ShelfPageAdjustmentGuard(secondPage?.let { it to 0f })

        assertEquals(1 to 0f, secondGuard.consumeIfReady(interactive = true, hasPendingFocus = false))
        assertTrue(secondGuard.applied)
        assertNull(secondGuard.consumeIfReady(interactive = true, hasPendingFocus = false))
    }
}
