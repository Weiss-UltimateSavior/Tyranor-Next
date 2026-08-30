package com.tyranor.next.core.game.scan

import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.game.model.ScanGame

/**
 * 旧版 game_scanner prefs 行式格式的编解码：一行一条游戏，字段以 \u0001 分隔。
 * 旧数据导入（GameLibraryRepository）与 prefs 镜像写回共用；纯字符串操作，无 Android 依赖。
 */
internal object GameRecordCodec {

    fun serialize(games: List<ScanGame>): String = games.joinToString("\n") { serializeGame(it) }

    fun parse(text: String): List<ScanGame> = text.split("\n").mapNotNull { parseGame(it) }

    fun serializeGame(g: ScanGame): String {
        // 标题/元数据可能来自 VNDB，含 \n 或 \u0001 会把整个持久化文件解析错乱，需清洗。
        fun clean(s: String): String = s.replace("\n", " ").replace("\u0001", " ")
        return listOf(
            clean(g.title),
            g.uri,
            g.engine.name,
            g.launchTarget,
            g.coverUri.orEmpty(),
            g.vndbId.orEmpty(),
            clean(g.metadataTitle.orEmpty()),
            g.launchFile.orEmpty(),
            g.openTime.toString(),
            g.coverSource.orEmpty(),
            g.externalModuleAlias.orEmpty(),
            g.detectedRenpyVersion.orEmpty(),
        ).joinToString("\u0001")
    }

    fun parseGame(line: String): ScanGame? {
        val p = line.split("\u0001")
        if (p.size < 3) return null
        return ScanGame(
            title = p[0],
            uri = p[1],
            engine = runCatching { EngineType.valueOf(p[2]) }.getOrDefault(EngineType.UNKNOWN),
            launchTarget = p.getOrElse(3) { "" },
            coverUri = p.getOrElse(4) { "" }.takeIf { it.isNotBlank() },
            vndbId = p.getOrElse(5) { "" }.takeIf { it.isNotBlank() },
            metadataTitle = p.getOrElse(6) { "" }.takeIf { it.isNotBlank() },
            launchFile = p.getOrElse(7) { "" }.takeIf { it.isNotBlank() },
            openTime = p.getOrElse(8) { "" }.toLongOrNull() ?: 0,
            coverSource = p.getOrElse(9) { "" }.takeIf { it.isNotBlank() },
            externalModuleAlias = p.getOrElse(10) { "" }.takeIf { it.isNotBlank() },
            detectedRenpyVersion = p.getOrElse(11) { "" }.takeIf { it.isNotBlank() },
        )
    }
}
