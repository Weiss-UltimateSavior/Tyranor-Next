package com.tyranor.next.core.game.model

import com.tyranor.next.core.engine.EngineType

/** 游戏库排序与筛选条件的持久化取值。 */
internal object GameFilterOptions {
    const val GAME_SORT_ALPHA = "alpha"
    const val GAME_SORT_BRACKET_TAG = "bracket_tag"
    const val GAME_SORT_ENGINE = "engine"
    const val GAME_SORT_RECENT = "recent"
    const val GAME_FILTER_ENGINE_ALL = ""
    const val GAME_FILTER_PLAYED_ALL = "all"
    const val GAME_FILTER_PLAYED_ONLY = "played"
    const val GAME_FILTER_PLAYED_NONE = "unplayed"

    fun normalizeEngineFilter(value: String?): String {
        val name = value?.trim()
        return EngineType.entries.firstOrNull { it.name == name }?.name ?: GAME_FILTER_ENGINE_ALL
    }
}

/** 游戏库的排序与筛选条件；搜索词由游戏页单独持有。 */
internal data class GameLibraryConditions(
    val sortMode: String = GameFilterOptions.GAME_SORT_ALPHA,
    val engineFilter: String = GameFilterOptions.GAME_FILTER_ENGINE_ALL,
    val playedFilter: String = GameFilterOptions.GAME_FILTER_PLAYED_ALL,
) {
    val isActive: Boolean
        get() = sortMode != GameFilterOptions.GAME_SORT_ALPHA ||
            engineFilter != GameFilterOptions.GAME_FILTER_ENGINE_ALL ||
            playedFilter != GameFilterOptions.GAME_FILTER_PLAYED_ALL

    fun isEngineFilterMissing(
        availableEngines: Collection<EngineType>,
        confirmedEngines: Set<EngineType>?,
    ): Boolean = engineFilter != GameFilterOptions.GAME_FILTER_ENGINE_ALL &&
        availableEngines.none { it.name == engineFilter } &&
        confirmedEngines?.none { it.name == engineFilter } == true
}

/** 按游戏标题、括号标签、引擎枚举顺序或最近打开时间排序。 */
internal fun sortGames(games: List<ScanGame>, sortMode: String): List<ScanGame> = when (sortMode) {
    GameFilterOptions.GAME_SORT_BRACKET_TAG -> games.sortedWith(
        compareBy<ScanGame> { GameSortKeys.bracketTag(it.title).isBlank() }
            .thenBy { GameSortKeys.tagKey(it.title) }
            .thenBy { GameSortKeys.titleKey(it.title) },
    )
    GameFilterOptions.GAME_SORT_ENGINE -> games.sortedWith(
        compareBy<ScanGame> { it.engine.ordinal }
            .thenBy { GameSortKeys.titleKey(it.title) },
    )
    GameFilterOptions.GAME_SORT_RECENT -> games.sortedWith(
        compareByDescending<ScanGame> { it.openTime }
            .thenBy { GameSortKeys.titleKey(it.title) },
    )
    else -> games.sortedBy { GameSortKeys.titleKey(it.title) }
}

/** 按引擎类型和游玩状态筛选，保留输入顺序；已游玩指最近打开时间大于 0。 */
internal fun filterGames(
    games: List<ScanGame>,
    engineFilter: String,
    playedFilter: String,
): List<ScanGame> {
    if (engineFilter == GameFilterOptions.GAME_FILTER_ENGINE_ALL &&
        playedFilter == GameFilterOptions.GAME_FILTER_PLAYED_ALL
    ) return games

    return games.filter { game ->
        val engineMatched = engineFilter == GameFilterOptions.GAME_FILTER_ENGINE_ALL ||
            game.engine.name == engineFilter
        val playedMatched = when (playedFilter) {
            GameFilterOptions.GAME_FILTER_PLAYED_ONLY -> game.openTime > 0
            GameFilterOptions.GAME_FILTER_PLAYED_NONE -> game.openTime <= 0
            else -> true
        }
        engineMatched && playedMatched
    }
}
