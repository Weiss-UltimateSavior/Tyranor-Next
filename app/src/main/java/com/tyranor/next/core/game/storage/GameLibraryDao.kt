package com.tyranor.next.core.game.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert

/** 游戏库 DAO：games / scan_roots / quick_launch 三表的查询（迁移方案第四、六节）。 */
@Dao
internal interface GameLibraryDao {

    // ---------- games ----------

    @Query("SELECT * FROM games")
    suspend fun getAllGames(): List<GameEntity>

    @Query("SELECT * FROM games WHERE uri IN (:uris)")
    suspend fun getGamesByUris(uris: List<String>): List<GameEntity>

    @Query("SELECT COUNT(*) FROM games")
    suspend fun countGames(): Int

    @Upsert
    suspend fun upsertGames(games: List<GameEntity>)

    /** 幂等导入：已存在的行不覆盖（保护 DB 中更新的封面/标题）。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertGamesIfAbsent(games: List<GameEntity>)

    @Query("DELETE FROM games WHERE uri IN (:uris)")
    suspend fun deleteGamesByUris(uris: Collection<String>)

    /** 封面元数据单行更新（迁移方案阶段 2），避免整库重写。 */
    @Query(
        "UPDATE games SET cover_uri = :coverUri, cover_source = :coverSource, " +
            "vndb_id = :vndbId, metadata_title = :metadataTitle, updated_at = :updatedAt " +
            "WHERE uri = :uri",
    )
    suspend fun updateCover(
        uri: String,
        coverUri: String?,
        coverSource: String?,
        vndbId: String?,
        metadataTitle: String?,
        updatedAt: Long,
    ): Int

    @Query("UPDATE games SET last_opened_at = :openedAt, updated_at = :updatedAt WHERE uri = :uri")
    suspend fun updateLastOpened(uri: String, openedAt: Long, updatedAt: Long)

    // ---------- games 搜索（迁移方案阶段 3，排序键为预计算列） ----------

    /** LIKE 通配符由调用方转义；排序键为预计算小写列，二进制比较与 UI 内存排序等价。 */
    @Query(
        "SELECT * FROM games WHERE title LIKE '%' || :escapedQuery || '%' ESCAPE '\\' " +
            "ORDER BY sort_title",
    )
    suspend fun searchGamesSortedByTitle(escapedQuery: String): List<GameEntity>

    @Query(
        "SELECT * FROM games WHERE title LIKE '%' || :escapedQuery || '%' ESCAPE '\\' " +
            "ORDER BY (sort_tag = ''), sort_tag, sort_title",
    )
    suspend fun searchGamesSortedByBracketTag(escapedQuery: String): List<GameEntity>

    /** 最近打开列表：last_opened_at 的派生视图，替代旧 recent_games 快照（迁移方案 4.1）。 */
    @Query("SELECT * FROM games WHERE last_opened_at > 0 ORDER BY last_opened_at DESC LIMIT :limit")
    suspend fun getRecentGames(limit: Int): List<GameEntity>

    // ---------- scan_roots ----------

    @Query("SELECT * FROM scan_roots")
    suspend fun getRoots(): List<ScanRootEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRootsIfAbsent(roots: List<ScanRootEntity>)

    @Query("DELETE FROM scan_roots WHERE uri = :uri")
    suspend fun deleteRoot(uri: String): Int

    // ---------- quick_launch ----------

    @Query(
        "SELECT games.* FROM quick_launch INNER JOIN games ON games.uri = quick_launch.game_uri " +
            "ORDER BY quick_launch.slot_index",
    )
    suspend fun getQuickLaunchGames(): List<GameEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertQuickLaunchIfAbsent(rows: List<QuickLaunchEntity>)

    @Query("DELETE FROM quick_launch")
    suspend fun clearQuickLaunch()

    // ---------- game_overrides（迁移方案阶段 4） ----------

    @Query("SELECT * FROM game_overrides")
    suspend fun getAllOverrideRows(): List<GameOverrideEntity>

    @Query("SELECT * FROM game_overrides WHERE game_uri = :uri")
    suspend fun getOverrideRow(uri: String): GameOverrideEntity?

    @Upsert
    suspend fun upsertOverrideRows(rows: List<GameOverrideEntity>)

    @Query("DELETE FROM game_overrides WHERE game_uri = :uri")
    suspend fun deleteOverrideRow(uri: String)

    @Query("DELETE FROM game_overrides WHERE game_uri IN (:uris)")
    suspend fun deleteOverrideRowsByUris(uris: Collection<String>)

    // ---------- engine_detection_cache（迁移方案阶段 5） ----------

    @Query("SELECT * FROM engine_detection_cache WHERE game_uri = :uri")
    suspend fun getDetectionRow(uri: String): EngineDetectionEntity?

    @Upsert
    suspend fun upsertDetectionRow(row: EngineDetectionEntity)

    // ---------- 组合事务 ----------

    /** 全量替换快捷启动：槽位按传入顺序重排为 0..n-1。 */
    @Transaction
    suspend fun replaceQuickLaunch(uris: List<String>, updatedAt: Long, validUris: Set<String>) {
        clearQuickLaunch()
        val rows = uris.filter { it in validUris }.take(MAX_QUICK_LAUNCH)
            .mapIndexed { index, uri -> QuickLaunchEntity(uri, index, updatedAt) }
        if (rows.isNotEmpty()) insertQuickLaunchIfAbsent(rows)
    }

    companion object {
        const val MAX_QUICK_LAUNCH = 3
        const val RECENT_LIMIT = 20
    }
}
