package com.tyranor.next.core.game.model

/**
 * 游戏标题中的【】/[] 标签解析（唯一实现）。
 *
 * 标签口径与封面搜索清洗（`core/cover` cleanTitle 的括号处理）一致；排序键
 * （[GameSortKeys] → `games.sort_tag`）取**首个**标签，游戏页卡片展示则去掉**全部**标签。
 */
object GameTitleTags {

    private val TAG_RE = Regex("""【([^】]*)】|\[([^\]]*)]""")

    /** 首个【】/[] 标签内容（去首尾空白）；无标签返回空串。 */
    fun firstTag(title: String): String {
        val match = TAG_RE.find(title) ?: return ""
        return (match.groups[1]?.value ?: match.groups[2]?.value).orEmpty().trim()
    }

    /**
     * 去掉全部【】/[] 标签并折叠多余空白（卡片展示用）；
     * 去掉后为空时回退去空白后的原标题，仍为空则原样返回。
     */
    fun stripAll(title: String): String {
        val stripped = title.replace(TAG_RE, " ")
            .replace("""\s+""".toRegex(), " ")
            .trim()
        return stripped.ifEmpty { title.trim() }
    }
}
