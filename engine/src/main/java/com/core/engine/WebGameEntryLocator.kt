package com.core.engine

import java.io.File

/**
 * 网页形态游戏（RPG Maker MV/MZ、Tyrano、WebOther）的启动入口定位。
 *
 * 抽取自 RpgMakerActivity.findGameEntry 与 TyranoActivity.findTyranoEntry——两个宿主此前各自
 * 维护一份逐字节相同的实现与子目录常量。应用层的存档目录解析（GameSaveManager / RpgSaveFormat）
 * 需要与引擎写入端得到同一个 contentRoot（存档目录 = contentRoot/save），为避免多处漂移收敛为
 * 单一实现；两类宿主改为此处委托。
 *
 * 定位顺序保持原逻辑：app.asar → resources/app.asar → 目录型 app.asar（内含 index.html）
 * → resources/app.asar 目录型 → index.html；当前目录未命中时按 [searchSubdirs] 递归，
 * 达到 [MAX_SEARCH_DEPTH] 后停止。
 */
object WebGameEntryLocator {
    /**
     * @property contentRoot 包含 index.html 或 app.asar 的目录，将作为本地 HTTP 服务器的 root。
     * @property asarPath 命中的 app.asar 绝对路径；非空表示 asar 模式，空表示散文件模式。
     */
    data class Entry(val contentRoot: File, val asarPath: String?)

    /** 入口递归搜索的最大深度（根目录为 0）。 */
    const val MAX_SEARCH_DEPTH = 2

    /** 入口递归搜索的子目录列表，与启动器侧的引擎特征探测子目录保持一致。 */
    @JvmField
    val searchSubdirs: Array<String> = arrayOf(
        "www", "resources", "app.asar", "app", "tyrano", "data", "scenario", "system", "game",
    )

    /**
     * 递归查找游戏入口（index.html 或 app.asar）。
     *
     * @param dir 当前搜索目录。
     * @param depth 当前递归深度，根目录传入 0。
     * @return 入口定位结果；未找到返回 null。
     */
    fun locate(dir: File, depth: Int = 0): Entry? {
        dir.resolve("app.asar").takeIf { it.isFile }?.let {
            return Entry(dir, it.absolutePath)
        }
        dir.resolve("resources/app.asar").takeIf { it.isFile }?.let {
            return Entry(dir, it.absolutePath)
        }
        dir.resolve("app.asar").takeIf { it.isDirectory && it.resolve("index.html").isFile }?.let {
            return Entry(it, null)
        }
        dir.resolve("resources/app.asar").takeIf { it.isDirectory && it.resolve("index.html").isFile }?.let {
            return Entry(it, null)
        }
        dir.resolve("index.html").takeIf { it.isFile }?.let {
            return Entry(dir, null)
        }
        if (depth >= MAX_SEARCH_DEPTH) return null
        for (name in searchSubdirs) {
            val sub = dir.resolve(name)
            if (!sub.isDirectory) continue
            locate(sub, depth + 1)?.let { return it }
        }
        return null
    }
}
