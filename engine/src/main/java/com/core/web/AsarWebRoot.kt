package com.core.web

/**
 * asar 归档内「网页根」前缀探测，Tyrano / RPG Maker 各 Web 壳共用。
 *
 * 游戏可以按多种布局打包，网页根可能在归档根、`www/`、`app/` 或 `resources/app/`
 * 之下。此前该探测在三处各写一份（两个本地 HTTP 服务器 + RPG Maker 的 v2 文件系统桥），
 * 其中文件系统桥只认前两种，导致 `app/`、`resources/app/` 布局的 asar 出现
 * 「服务器读得到、fs 桥读不到」的分裂行为（插件读数据表/require 全部失败）。
 *
 * 收敛为单一实现，避免同类漂移：新增布局只需改这里。
 */
internal object AsarWebRoot {

    /** 待探测的候选布局，按优先级从「网页根即归档根」到嵌套最深的布局。 */
    private val CANDIDATES = listOf(
        "index.html" to "",
        "www/index.html" to "www/",
        "app/index.html" to "app/",
        "resources/app/index.html" to "resources/app/",
    )

    /**
     * 返回归档内网页根的前缀（含结尾 `/`；网页根即归档根时为空串）。
     *
     * [has] 为「归档内是否存在该条目」的判定，由调用方注入以便 Tyrano 与 RPG Maker
     * 各自的 AsarArchive 实现共用同一套规则；归档为 null 时调用方应直接返回空串。
     */
    fun prefixFor(has: (String) -> Boolean): String =
        CANDIDATES.firstOrNull { (probe, _) -> has(probe) }?.second ?: ""
}
