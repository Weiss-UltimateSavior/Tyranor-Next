package com.tyranor.next.core.game.model

/**
 * 游戏库排序 / 过滤的取值常量（issue #118）。
 *
 * 放在 model 层而不是设置层：排序/过滤的纯函数（[sortGames] / [filterGames]）与
 * `AppSettingsStore` 共用同一套取值，谁都不必反向 import 对方，core 内部保持
 * `settings → model`、`engine → model` 的单向依赖。
 */
object GameFilterOptions {

    /** 排序：按标题字母/字符顺序。 */
    const val GAME_SORT_ALPHA = "alpha"

    /** 排序：按标题中 【】/[] 标签内容分组。 */
    const val GAME_SORT_BRACKET_TAG = "bracket_tag"

    /** 排序：按引擎类型分组（同组内按标题）。 */
    const val GAME_SORT_ENGINE = "engine"

    /** 排序：最近游玩在前（从未打开过的排在最后，同序按标题）。 */
    const val GAME_SORT_RECENT = "recent"

    /** 引擎过滤的「全部」：空串，与 `EngineType.name` 的取值域互斥。 */
    const val GAME_FILTER_ENGINE_ALL = ""

    /** 游玩状态过滤：全部。 */
    const val GAME_FILTER_PLAYED_ALL = "all"

    /** 游玩状态过滤：仅已游玩（`openTime > 0`）。 */
    const val GAME_FILTER_PLAYED_ONLY = "played"

    /** 游玩状态过滤：仅未游玩（`openTime <= 0`）。 */
    const val GAME_FILTER_PLAYED_NONE = "unplayed"
}
