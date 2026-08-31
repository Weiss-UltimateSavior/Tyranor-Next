package com.tyranor.next.core.game.storage

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** 扫描根目录表，替代旧 game_scanner prefs 的 scan_roots 换行字符串（迁移方案 4.2）。 */
@Entity(tableName = "scan_roots")
internal data class ScanRootEntity(
    /** SAF tree URI 或规范化后的真实路径。 */
    @PrimaryKey val uri: String,
    /** 展示名缓存；null 表示未缓存，由 UI 自行解析。 */
    @ColumnInfo(name = "display_name") val displayName: String? = null,
    @ColumnInfo(name = "added_at") val addedAt: Long,
    /** 预留：单目录扫描深度覆盖；null 表示跟随全局设置。 */
    @ColumnInfo(name = "scan_depth") val scanDepth: Int? = null,
    @ColumnInfo(name = "last_scanned_at") val lastScannedAt: Long = 0,
)
