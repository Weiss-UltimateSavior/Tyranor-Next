package com.tyranor.next.core.game.storage

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 单游戏覆盖设置表（迁移方案 4.4 第一阶段）：按引擎域分区的结构化 JSON，
 * 替代旧 tyranor_game_overrides prefs 的 JSON-in-XML 整条读写。
 * 引擎子进程（TyranoActivity/TouchPadSaveBridge）仍直读 prefs 镜像，
 * App 写路径由 PerGameSettingsStore 同步写镜像（方案阶段 4 的过渡策略）。
 * 分区映射见 [GameOverridePartitions]。
 */
@Entity(tableName = "game_overrides")
internal data class GameOverrideEntity(
    @PrimaryKey @ColumnInfo(name = "game_uri") val gameUri: String,
    @ColumnInfo(name = "kr_json") val krJson: String?,
    @ColumnInfo(name = "artemis_json") val artemisJson: String?,
    @ColumnInfo(name = "ons_json") val onsJson: String?,
    @ColumnInfo(name = "tyrano_json") val tyranoJson: String?,
    @ColumnInfo(name = "renpy_json") val renpyJson: String?,
    @ColumnInfo(name = "touchpad_json") val touchpadJson: String?,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
