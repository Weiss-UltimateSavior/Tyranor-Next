package com.tyranor.next.ui.home

import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.game.model.ScanGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 首页「随机」来源：同一天固定、次日换一批、数量受限、增删无关游戏不会整体重排。 */
class DailyRandomTest {

    private fun games(n: Int) = (1..n).map {
        ScanGame(title = "g$it", uri = "/storage/emulated/0/Games/game$it", engine = EngineType.TYRANO, launchTarget = "")
    }

    @Test
    fun sameDayIsStableRegardlessOfListOrder() {
        val all = games(30)
        val a = pickDailyRandom(all, epochDay = 20000, limit = 10).map { it.uri }
        val b = pickDailyRandom(all.reversed(), epochDay = 20000, limit = 10).map { it.uri }
        assertEquals(a, b)
    }

    @Test
    fun differentDaysPickDifferentGames() {
        val all = games(30)
        val days = (20000L..20009L).map { pickDailyRandom(all, it, 10).map { g -> g.uri } }
        assertTrue("10 天里不应都一样", days.toSet().size > 1)
        assertNotEquals(days[0], days[1])
    }

    @Test
    fun limitAndSmallLibrary() {
        assertEquals(10, pickDailyRandom(games(30), 20000, 10).size)
        assertEquals(3, pickDailyRandom(games(3), 20000, 10).size)
        assertEquals(0, pickDailyRandom(emptyList(), 20000, 10).size)
    }

    @Test
    fun addingAGameOnlyAffectsItsOwnSelection() {
        val all = games(30)
        val before = pickDailyRandom(all, 20000, 10).map { it.uri }
        val extra = ScanGame(title = "new", uri = "/storage/emulated/0/Games/new", engine = EngineType.TYRANO, launchTarget = "")
        val after = pickDailyRandom(all + extra, 20000, 10).map { it.uri }
        // 新游戏最多挤掉一个，其余入选者保持不变
        assertTrue(before.count { it in after } >= 9)
    }
}
