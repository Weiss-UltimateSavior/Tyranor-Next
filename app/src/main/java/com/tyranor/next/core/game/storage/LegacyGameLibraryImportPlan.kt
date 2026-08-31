package com.tyranor.next.core.game.storage

import com.tyranor.next.core.game.scan.GameRecordCodec

/**
 * 旧 game_scanner prefs → Room 三表的纯映射计划（迁移方案第九节）。
 * 只做数据变换、不做 I/O，便于在纯 JVM 单测中覆盖旧行式格式；
 * 根归属判定通过 isGameUnderRoot 注入（真实实现依赖 android.net.Uri）。
 *
 * 幂等语义：games / roots 由调用方用 INSERT OR IGNORE 落库（不覆盖 DB 中更新的行）；
 * recent 的 openTime 取「旧快照与旧游戏库记录中的较大值」合并进 games.last_opened_at；
 * quick_launch 仅保留库中存在的游戏，槽位按旧顺序重排为 0..2。
 */
internal object LegacyGameLibraryImportPlan {

    data class Plan(
        val roots: List<ScanRootEntity>,
        val games: List<GameEntity>,
        val quickLaunch: List<QuickLaunchEntity>,
    )

    fun build(
        rootsText: String?,
        gamesText: String?,
        recentText: String?,
        quickText: String?,
        now: Long,
        isGameUnderRoot: (rootUri: String, gameUri: String) -> Boolean,
    ): Plan {
        // 与 EngineScanner.saveRoot 相同的规范化：去首尾空白与尾部「/」，避免
        // 旧数据里的「/games/」与用户重新添加的「/games」导入成两个根。
        val roots = rootsText.orEmpty()
            .split("\n")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { it.trimEnd('/').let { key -> if (key.isEmpty()) "/" else key } }
            .distinct()
            .map { ScanRootEntity(uri = it, addedAt = now) }
        val rootUris = roots.map { it.uri }

        // 单条 parse 失败只跳过该条，不中断全局迁移（方案 9.2）。
        val scannedGames = GameRecordCodec.parse(gamesText.orEmpty())
        val recentOpenTimes = GameRecordCodec.parse(recentText.orEmpty())
            .associate { it.uri to it.openTime }
        val games = scannedGames.map { game ->
            val merged = game.copy(openTime = maxOf(game.openTime, recentOpenTimes[game.uri] ?: 0L))
            val rootUri = rootUris.firstOrNull { isGameUnderRoot(it, game.uri) }
            GameEntity.from(merged, discoveredAt = now, updatedAt = now, rootUri = rootUri)
        }
        val importedUris = games.mapTo(HashSet()) { it.uri }

        val quickLaunch = GameRecordCodec.parse(quickText.orEmpty())
            .map { it.uri }
            .distinct()
            .filter { it in importedUris }
            .take(GameLibraryDao.MAX_QUICK_LAUNCH)
            .mapIndexed { index, uri -> QuickLaunchEntity(gameUri = uri, slotIndex = index, updatedAt = now) }

        return Plan(roots, games, quickLaunch)
    }
}
