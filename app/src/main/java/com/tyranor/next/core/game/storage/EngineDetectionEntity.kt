package com.tyranor.next.core.game.storage

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 自动引擎识别缓存表（迁移方案 4.5）：统一 Artemis / Ren'Py / RPGM / mkxp-z 的
 * 识别结果与「记忆 + 指纹失效」逻辑。Artemis 成功版本由引擎子进程写 prefs、
 * 本表经 EngineDetectionRepository 消费归一（引擎无法访问 Room）。
 */
@Entity(tableName = "engine_detection_cache", indices = [Index("engine")])
internal data class EngineDetectionEntity(
    @PrimaryKey @ColumnInfo(name = "game_uri") val gameUri: String,
    val engine: String,
    /** 游戏目录特征 hash（GameDirFingerprint）；变化即失效重识别；null 表示无指纹的历史记录。 */
    @ColumnInfo(name = "fingerprint_hash") val fingerprintHash: String?,
    @ColumnInfo(name = "artemis_version") val artemisVersion: String?,
    @ColumnInfo(name = "artemis_success_version") val artemisSuccessVersion: String?,
    @ColumnInfo(name = "renpy_version") val renpyVersion: String?,
    @ColumnInfo(name = "rpgm_runtime") val rpgmRuntime: String?,
    @ColumnInfo(name = "mkxpz_supported") val mkxpzSupported: Boolean?,
    val confidence: Int,
    val reason: String?,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
