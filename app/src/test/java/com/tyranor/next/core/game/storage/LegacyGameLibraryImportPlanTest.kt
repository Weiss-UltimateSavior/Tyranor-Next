package com.tyranor.next.core.game.storage

import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.game.model.ScanGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 旧 game_scanner prefs → Room 导入计划的纯映射回归（迁移方案第九节）：
 * recent 合并、quick 槽位重排、孤儿快照丢弃、单条损坏跳过。
 */
class LegacyGameLibraryImportPlanTest {

    private val now = 1725000000000L

    private fun line(vararg fields: String) = fields.joinToString("\u0001")

    @Test
    fun importMergesRecentOpenTimeAndAssignsQuickLaunchSlots() {
        val roots = listOf("/storage/emulated/0/games").joinToString("\n")
        val games = listOf(
            line("Game A", "/games/a", "KIRIKIRI", "a.xp3", "", "", "", "", "100"),
            line("Game B", "/games/b", "ONS", "", "", "", "", "", "0"),
        ).joinToString("\n")
        val recent = listOf(
            line("Game B", "/games/b", "ONS", "", "", "", "", "", "555"),
        ).joinToString("\n")
        val quick = listOf(
            line("Game B", "/games/b", "ONS"),
            line("Game A", "/games/a", "KIRIKIRI"),
        ).joinToString("\n")

        val plan = LegacyGameLibraryImportPlan.build(
            rootsText = roots,
            gamesText = games,
            recentText = recent,
            quickText = quick,
            now = now,
            isGameUnderRoot = { root, _ -> root == "/storage/emulated/0/games" },
        )

        assertEquals(listOf("/storage/emulated/0/games"), plan.roots.map { it.uri })
        assertEquals(now, plan.roots.single().addedAt)

        val a = plan.games.first { it.uri == "/games/a" }
        val b = plan.games.first { it.uri == "/games/b" }
        assertEquals(100L, a.lastOpenedAt)
        // recent 的 openTime 与游戏库记录取较大值合并进 last_opened_at
        assertEquals(555L, b.lastOpenedAt)
        assertEquals("/storage/emulated/0/games", a.rootUri)
        assertEquals(now, a.discoveredAt)
        assertEquals(now, a.updatedAt)
        assertEquals("game a", a.sortTitle)
        assertEquals("KIRIKIRI", a.engine)
        assertEquals(EngineType.ONS, b.toModel().engine)

        // quick 按旧顺序重排槽位 0..2
        assertEquals(2, plan.quickLaunch.size)
        assertEquals("/games/b", plan.quickLaunch[0].gameUri)
        assertEquals(0, plan.quickLaunch[0].slotIndex)
        assertEquals("/games/a", plan.quickLaunch[1].gameUri)
        assertEquals(1, plan.quickLaunch[1].slotIndex)
    }

    @Test
    fun orphanQuickLaunchAndRecentEntriesAreDropped() {
        val games = line("Game A", "/games/a", "ONS")
        val quick = listOf(
            line("Ghost", "/games/ghost", "ONS"),
            line("Game A", "/games/a", "ONS"),
        ).joinToString("\n")

        val plan = LegacyGameLibraryImportPlan.build(
            rootsText = null,
            gamesText = games,
            recentText = line("Ghost", "/games/ghost", "ONS", "", "", "", "", "", "999"),
            quickText = quick,
            now = now,
            isGameUnderRoot = { _, _ -> false },
        )

        // 孤儿不建行（recent 的 openTime 无从合并、quick 不保留快照）
        assertEquals(1, plan.games.size)
        assertNull(plan.games.single().rootUri)
        assertEquals(1, plan.quickLaunch.size)
        assertEquals("/games/a", plan.quickLaunch.single().gameUri)
    }

    @Test
    fun corruptSingleLineIsSkippedWithoutAbortingImport() {
        val games = listOf(
            "broken-line",
            "",
            line("Game A", "/games/a", "TYRANO"),
        ).joinToString("\n")

        val plan = LegacyGameLibraryImportPlan.build(
            rootsText = null,
            gamesText = games,
            recentText = null,
            quickText = null,
            now = now,
            isGameUnderRoot = { _, _ -> false },
        )

        assertEquals(1, plan.games.size)
        assertEquals(ScanGame(title = "Game A", uri = "/games/a", engine = EngineType.TYRANO, launchTarget = "").uri, plan.games.single().uri)
        assertEquals(0, plan.quickLaunch.size)
    }

    @Test
    fun quickLaunchSlotsCappedAtThree() {
        val games = listOf("a", "b", "c", "d").joinToString("\n") { name ->
            line(name, "/games/$name", "ONS")
        }
        val quick = listOf("d", "c", "b", "a").joinToString("\n") { name ->
            line(name, "/games/$name", "ONS")
        }

        val plan = LegacyGameLibraryImportPlan.build(
            rootsText = null,
            gamesText = games,
            recentText = null,
            quickText = quick,
            now = now,
            isGameUnderRoot = { _, _ -> false },
        )

        assertEquals(listOf("/games/d", "/games/c", "/games/b"), plan.quickLaunch.map { it.gameUri })
        assertEquals(listOf(0, 1, 2), plan.quickLaunch.map { it.slotIndex })
    }
}
