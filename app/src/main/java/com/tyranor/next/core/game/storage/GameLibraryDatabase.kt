package com.tyranor.next.core.game.storage

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * 游戏库 Room 数据库单例。主进程专用；引擎子进程不访问本库。
 * v2 新增 game_overrides / engine_detection_cache（迁移方案阶段 4/5）。
 * 缺失迁移路径时破坏性重建是安全的：games/scan_roots/quick_launch 由 prefs 镜像 +
 * 启动修复导入恢复，game_overrides 由 syncFromPrefs 恢复；engine_detection_cache 为
 * 可重建缓存，Artemis 记忆丢失后经一次特征识别 + 一次成功启动自动重学（已知取舍）。
 * 后续版本升级时必须同时启用 exportSchema 并配置 schema 目录以编写 Migration。
 */
@Database(
    entities = [
        GameEntity::class,
        ScanRootEntity::class,
        QuickLaunchEntity::class,
        GameOverrideEntity::class,
        EngineDetectionEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
internal abstract class GameLibraryDatabase : RoomDatabase() {

    abstract fun gameLibraryDao(): GameLibraryDao

    companion object {
        private const val DB_NAME = "game_library.db"

        @Volatile
        private var instance: GameLibraryDatabase? = null

        fun get(context: Context): GameLibraryDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                GameLibraryDatabase::class.java,
                DB_NAME,
            )
                // 用户回滚到旧版本后再次升级时，无迁移路径可走则重建（旧数据可从 prefs 重新导入）。
                .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
                // v1→v2 无迁移路径：破坏性重建，库数据经启动修复从 prefs 镜像恢复。
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
                .also { instance = it }
        }
    }
}
