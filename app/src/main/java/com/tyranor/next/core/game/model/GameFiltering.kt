package com.tyranor.next.core.game.model

import com.tyranor.next.core.settings.AppSettingsStore

/**
 * 游戏库的内存排序 / 过滤（issue #118）。
 *
 * 放在 core 而不是游戏页：首页「分类仓库」与游戏页共用同一套排序，页面之间不该互相 import
 * （否则会出现 `ui/home → ui/game` 这种横向依赖）。两者都是纯函数，不依赖 Compose，
 * 也不读写任何状态，测试可以直接在 JVM 上跑。
 */

/** 按 [sortMode]（`AppSettingsStore.GAME_SORT_*`）排序；未知模式回落为字母序。 */
fun sortGames(games: List<ScanGame>, sortMode: String): List<ScanGame> {
    return when (sortMode) {
        AppSettingsStore.GAME_SORT_BRACKET_TAG -> games.sortedWith(
            compareBy<ScanGame> { GameSortKeys.bracketTag(it.title).isBlank() }
                .thenBy { GameSortKeys.tagKey(it.title) }
                .thenBy { GameSortKeys.titleKey(it.title) },
        )
        // 按引擎分组：组序 = EngineType 的枚举声明顺序（GAL 家族在前，PC/安卓/PSP 等手动与外置类别
        // 夹在中段，UNKNOWN 在最后），组内按标题。改枚举顺序会改变分组顺序，需同步确认展示预期。
        AppSettingsStore.GAME_SORT_ENGINE -> games.sortedWith(
            compareBy<ScanGame> { it.engine.ordinal }
                .thenBy { GameSortKeys.titleKey(it.title) },
        )
        // 最近游玩在前；从未打开过（openTime = 0）自然排在末尾，同序按标题。
        AppSettingsStore.GAME_SORT_RECENT -> games.sortedWith(
            compareByDescending<ScanGame> { it.openTime }
                .thenBy { GameSortKeys.titleKey(it.title) },
        )
        else -> games.sortedBy { GameSortKeys.titleKey(it.title) }
    }
}

/**
 * 引擎类型 + 游玩状态过滤。
 *
 * [engineFilter] 为 `EngineType.name`，[AppSettingsStore.GAME_FILTER_ENGINE_ALL] 表示全部；
 * 已游玩判据为 `openTime > 0`（与「最近游玩」排序同源，见 SPEC「游戏库」一节）。
 */
fun filterGames(
    games: List<ScanGame>,
    engineFilter: String,
    playedFilter: String,
): List<ScanGame> {
    if (engineFilter.isEmpty() && playedFilter == AppSettingsStore.GAME_FILTER_PLAYED_ALL) return games
    return games.filter { game ->
        val engineMatched = engineFilter.isEmpty() || game.engine.name == engineFilter
        val playedMatched = when (playedFilter) {
            AppSettingsStore.GAME_FILTER_PLAYED_ONLY -> game.openTime > 0
            AppSettingsStore.GAME_FILTER_PLAYED_NONE -> game.openTime <= 0
            else -> true
        }
        engineMatched && playedMatched
    }
}
