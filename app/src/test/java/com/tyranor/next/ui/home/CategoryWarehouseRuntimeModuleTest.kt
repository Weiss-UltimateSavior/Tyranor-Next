package com.tyranor.next.ui.home

import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.game.model.ScanGame
import org.junit.Assert.assertEquals
import org.junit.Test

/** 首页分类栏的第三方运行时分类：名称由调用方传入，缺省参数保持旧调用可编译。 */
class CategoryWarehouseRuntimeModuleTest {

    private fun game(title: String, engine: EngineType) = ScanGame(
        title = title,
        uri = "file:///games/$title",
        engine = engine,
        launchTarget = "DIR",
    )

    @Test
    fun runtimeModuleCategoryUsesProvidedName() {
        val games = listOf(
            game("a", EngineType.RUNTIME_MODULE),
            game("b", EngineType.KIRIKIRI),
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
            runtimeModuleName = "My Runtime",
        )

        val category = categories.single { it.key == "engine:RUNTIME_MODULE" }
        assertEquals("My Runtime", category.label)
        assertEquals(listOf(games[0]), category.games)
    }

    @Test
    fun runtimeModuleCategoryUsesProvidedFallbackName() {
        val categories = buildHomeCategories(
            sortedGames = listOf(game("a", EngineType.RUNTIME_MODULE)),
            quickLaunch = emptyList(),
            recentGames = emptyList(),
            allLabel = "all",
            quickLabel = "quick",
            recentLabel = "recent",
            unknownEngineName = "unknown",
            switchEngineName = "switch",
            runtimeModuleName = "Runtime",
        )

        assertEquals(
            "Runtime",
            categories.single { it.key == "engine:RUNTIME_MODULE" }.label,
        )
    }
}
