package com.tyranor.next.core.game.model

import java.util.Locale

/**
 * 游戏标题排序键的唯一实现：Room 预计算列（games.sort_title / games.sort_tag，见
 * core/game/storage）与 UI 内存排序（GameScreen.sortGames）共用，保证两条排序路径结果一致。
 * 键值在写入时即小写化，SQL 侧用二进制比较即可与 UI 的 Kotlin 比较完全等价。
 */
object GameSortKeys {

    /** 标题排序键：小写 + 去首尾空白（Locale.ROOT）。 */
    fun titleKey(title: String): String = title.lowercase(Locale.ROOT).trim()

    /** 标题中的 【】/[] 标签内容；无标签返回空串。 */
    fun bracketTag(title: String): String {
        val match = TAG_RE.find(title) ?: return ""
        return (match.groups[1]?.value ?: match.groups[2]?.value).orEmpty().trim()
    }

    /** 标签排序键：小写化后的标签内容。 */
    fun tagKey(title: String): String = bracketTag(title).lowercase(Locale.ROOT)

    private val TAG_RE = Regex("""【([^】]+)】|\[([^\]]+)]""")
}
