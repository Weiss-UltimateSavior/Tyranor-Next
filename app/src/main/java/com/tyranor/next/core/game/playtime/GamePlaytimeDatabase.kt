package com.tyranor.next.core.game.playtime

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.Index
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import android.content.Context
import com.tyranor.next.core.process.AppProcess
import com.tyranor.next.core.settings.AppStorageNames
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "playtime_records", indices = [Index(value = ["gameUri"])])
internal data class PlaytimeRecordEntity(
    @PrimaryKey val recordId: String,
    val sessionId: String,
    val gameUri: String,
    val durationMillis: Long,
    val startedAtMillis: Long,
)

@Dao
internal abstract class PlaytimeRecordDao {
    @Query("SELECT * FROM playtime_records WHERE gameUri = :gameUri")
    abstract fun observeGame(gameUri: String): Flow<List<PlaytimeRecordEntity>>

    @Query("SELECT * FROM playtime_records")
    abstract suspend fun all(): List<PlaytimeRecordEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertIfAbsent(record: PlaytimeRecordEntity)

    @Query(
        "UPDATE playtime_records SET durationMillis = MAX(durationMillis, :durationMillis), " +
            "startedAtMillis = MIN(startedAtMillis, :startedAtMillis) WHERE recordId = :recordId",
    )
    abstract suspend fun advance(recordId: String, durationMillis: Long, startedAtMillis: Long)

    open suspend fun merge(record: PlaytimeRecordEntity) {
        insertIfAbsent(record)
        advance(record.recordId, record.durationMillis.coerceAtLeast(0), record.startedAtMillis)
    }
}

@Database(entities = [PlaytimeRecordEntity::class], version = 1, exportSchema = true)
internal abstract class GamePlaytimeDatabase : RoomDatabase() {
    abstract fun records(): PlaytimeRecordDao

    companion object {
        @Volatile private var instance: GamePlaytimeDatabase? = null

        fun get(context: Context): GamePlaytimeDatabase {
            check(AppProcess.isMain(context)) { "Playtime database is main-process only" }
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    GamePlaytimeDatabase::class.java,
                    AppStorageNames.GAME_PLAYTIME_DATABASE,
                )
                    .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
                    .build()
                    .also { instance = it }
            }
        }
    }
}

internal fun PlaytimeRecordEntity.toSnapshot(closed: Boolean = false, processId: Int = 0) =
    PlaytimeRecordSnapshot(recordId, sessionId, gameUri, durationMillis, startedAtMillis, closed, processId)

