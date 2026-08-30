package com.tyranor.next.core.game.storage

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 首页快捷启动表，替代旧 quick_launch 完整游戏快照（迁移方案 4.3）：
 * 只保存 game_uri + 槽位，标题/封面随 games 表更新自动生效，游戏删除时级联清理。
 */
@Entity(
    tableName = "quick_launch",
    indices = [Index(value = ["slot_index"], unique = true)],
    foreignKeys = [
        ForeignKey(
            entity = GameEntity::class,
            parentColumns = ["uri"],
            childColumns = ["game_uri"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
internal data class QuickLaunchEntity(
    @PrimaryKey @ColumnInfo(name = "game_uri") val gameUri: String,
    @ColumnInfo(name = "slot_index") val slotIndex: Int,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
