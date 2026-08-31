package com.tyranor.next.core.game.storage

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.game.model.GameSortKeys
import com.tyranor.next.core.game.model.ScanGame

/**
 * 游戏库主表，替代旧 game_scanner prefs 的 scan_games 行式字符串（迁移方案 4.1）。
 * sortTitle / sortTag 为预计算排序键（方案 PR4），与 UI 内存排序共用 GameSortKeys 实现。
 */
@Entity(
    tableName = "games",
    indices = [
        Index("root_uri"),
        Index("engine"),
        Index("sort_title"),
        Index("last_opened_at"),
    ],
)
internal data class GameEntity(
    @PrimaryKey val uri: String,
    @ColumnInfo(name = "root_uri") val rootUri: String?,
    val title: String,
    val engine: String,
    @ColumnInfo(name = "launch_target") val launchTarget: String,
    @ColumnInfo(name = "launch_file") val launchFile: String?,
    @ColumnInfo(name = "cover_uri") val coverUri: String?,
    @ColumnInfo(name = "cover_source") val coverSource: String?,
    @ColumnInfo(name = "vndb_id") val vndbId: String?,
    @ColumnInfo(name = "metadata_title") val metadataTitle: String?,
    @ColumnInfo(name = "external_module_alias") val externalModuleAlias: String?,
    @ColumnInfo(name = "detected_renpy_version") val detectedRenpyVersion: String?,
    @ColumnInfo(name = "last_opened_at") val lastOpenedAt: Long,
    @ColumnInfo(name = "discovered_at") val discoveredAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "sort_title") val sortTitle: String,
    @ColumnInfo(name = "sort_tag") val sortTag: String,
) {
    fun toModel(): ScanGame = ScanGame(
        title = title,
        uri = uri,
        engine = runCatching { EngineType.valueOf(engine) }.getOrDefault(EngineType.UNKNOWN),
        launchTarget = launchTarget,
        coverUri = coverUri,
        coverSource = coverSource,
        vndbId = vndbId,
        metadataTitle = metadataTitle,
        externalModuleAlias = externalModuleAlias,
        detectedRenpyVersion = detectedRenpyVersion,
        launchFile = launchFile,
        openTime = lastOpenedAt,
    )

    companion object {
        /**
         * ScanGame → 表行。discoveredAt/updatedAt 由调用方决定（首次发现时间与最近修改时间）；
         * rootUri 未解析时为 null，由 Repository 在写入批量中按扫描根归属补齐。
         */
        fun from(
            game: ScanGame,
            discoveredAt: Long,
            updatedAt: Long,
            rootUri: String? = null,
        ): GameEntity = GameEntity(
            uri = game.uri,
            rootUri = rootUri,
            title = game.title,
            engine = game.engine.name,
            launchTarget = game.launchTarget,
            launchFile = game.launchFile,
            coverUri = game.coverUri,
            coverSource = game.coverSource,
            vndbId = game.vndbId,
            metadataTitle = game.metadataTitle,
            externalModuleAlias = game.externalModuleAlias,
            detectedRenpyVersion = game.detectedRenpyVersion,
            lastOpenedAt = game.openTime,
            discoveredAt = discoveredAt,
            updatedAt = updatedAt,
            sortTitle = GameSortKeys.titleKey(game.title),
            sortTag = GameSortKeys.tagKey(game.title),
        )
    }
}
