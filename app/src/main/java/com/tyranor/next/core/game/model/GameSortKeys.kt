package com.tyranor.next.core.game.model

import java.util.Locale

/**
 * 游戏标题排序键的唯一实现：Room 预计算列（games.sort_title / games.sort_tag，见
 * core/game/storage）与游戏库内存排序（[sortGames]）共用。键值在写入时即小写化。
 */
object GameSortKeys {

    /** 标题排序键：小写 + 去首尾空白（Locale.ROOT）。 */
    fun titleKey(title: String): String = title.lowercase(Locale.ROOT).trim()

    /** 标题中的 【】/[] 标签内容（首个）；无标签返回空串。解析口径见 [GameTitleTags]。 */
    fun bracketTag(title: String): String = GameTitleTags.firstTag(title)

    /** 标签排序键：小写化后的标签内容。 */
    fun tagKey(title: String): String = bracketTag(title).lowercase(Locale.ROOT)
}
