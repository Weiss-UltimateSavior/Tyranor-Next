package com.tyranor.next.core.game.storage

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * 游戏库 Room 数据库单例。主进程专用；引擎子进程不访问本库。
 * v2 新增 game_overrides / engine_detection_cache（迁移方案阶段 4/5）。
 * 降级（用户回滚旧版本后再次升级）无迁移路径可走时破坏性重建是安全的：games/scan_roots/
 * quick_launch 由 prefs 镜像 + 启动修复导入恢复，game_overrides 由 syncFromPrefs 恢复；
 * engine_detection_cache 为可重建缓存，Artemis 记忆丢失后经一次特征识别 + 一次成功启动
 * 自动重学（已知取舍）。
 *
 * 升级路径（m7）：已启用 exportSchema 并导出到 `app/schemas`。破坏性升级**仅对 v1→v2 历史
 * 过渡开放**（`.fallbackToDestructiveMigrationFrom(1)`，老安装直升 v2 时仍可安全重建）；
 * 未来任何 version+n 变更必须注册显式 Migration，缺失时启动即抛异常（在开发/CI 阶段暴露），
 * 不再静默清空整库。
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
    exportSchema = true,
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
                // 用户回滚到旧版本后再次升级（降级→升级）时，无迁移路径可走则重建（数据可从 prefs 恢复）。
                .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
                // 仅 v1→v2 历史过渡允许破坏性重建；未来版本缺 Migration 必须显式暴露（m7）。
                .fallbackToDestructiveMigrationFrom(dropAllTables = true, 1)
                .build()
                .also { instance = it }
        }
    }
}
