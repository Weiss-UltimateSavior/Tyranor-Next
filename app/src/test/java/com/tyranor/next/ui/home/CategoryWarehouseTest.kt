package com.tyranor.next.ui.home

import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.game.model.ScanGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分类仓库引擎分类纯函数：只列库中实际存在的引擎，顺序跟随 [EngineType] 声明顺序。
 */
class CategoryWarehouseTest {

    private fun game(title: String, engine: EngineType) = ScanGame(
        title = title,
        uri = "file:///games/$title",
        engine = engine,
        launchTarget = "target",
    )

    @Test
    fun engineCategoriesOnlyContainPresentEnginesInEnumOrder() {
        val games = listOf(
            game("a", EngineType.RENPY),
            game("b", EngineType.KIRIKIRI),
            game("c", EngineType.KIRIKIRI),
            game("d", EngineType.RPG_MZ),
            game("e", EngineType.ANDROID_APP),
        )
        assertEquals(
            listOf(
                EngineType.KIRIKIRI,
                EngineType.RPG_MZ,
                EngineType.RENPY,
                EngineType.ANDROID_APP,
            ),
            categoryEngineTypes(games),
        )
    }

    @Test
    fun emptyLibraryHasNoEngineCategories() {
        assertEquals(emptyList<EngineType>(), categoryEngineTypes(emptyList()))
    }

    @Test
    fun categoriesKeepAllQuickRecentAndAppendPresentEngines() {
        val games = listOf(
            game("a", EngineType.KIRIKIRI),
            game("b", EngineType.UNKNOWN),
            game("c", EngineType.NINTENDO_SWITCH),
        )
        val categories = buildHomeCategories(
            sortedGames = games,
            quickLaunch = emptyList(),
            recentGames = emptyList(),
            allLabel = "all",
            quickLabel = "quick",
            recentLabel = "recent",
            unknownEngineName = "unknown",
            switchEngineName = "switch",
        )
        assertEquals(
            listOf("all", "quick", "recent", "engine:KIRIKIRI", "engine:NINTENDO_SWITCH", "engine:UNKNOWN"),
            categories.map { it.key },
        )
        // 快捷/最近为空也保留分类（结构稳定），空内容由页面空态文案处理
        assertTrue(categories.first { it.key == "quick" }.games.isEmpty())
        assertTrue(categories.first { it.key == "recent" }.games.isEmpty())
        // 引擎标签：UNKNOWN/Switch 走本地化文案，其余用引擎展示名
        assertEquals("unknown", categories.first { it.key == "engine:UNKNOWN" }.label)
        assertEquals("switch", categories.first { it.key == "engine:NINTENDO_SWITCH" }.label)
        assertEquals(EngineType.KIRIKIRI.displayName, categories.first { it.key == "engine:KIRIKIRI" }.label)
        assertEquals(listOf(games[0]), categories.first { it.key == "engine:KIRIKIRI" }.games)
    }
}
