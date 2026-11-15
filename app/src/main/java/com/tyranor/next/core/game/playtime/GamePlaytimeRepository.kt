package com.tyranor.next.core.game.playtime

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.process.AppProcess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Reads and imports playtime recorded by built-in engine host processes. */
object GamePlaytimeRepository {
    private const val TAG = "GamePlaytimeRepository"
    private const val ORDER_PREFS = "game_playtime_order"
    private const val KEY_LAST_SESSION_ORDER = "last_session_order"
    private val supportedEngines = setOf(
        EngineType.KIRIKIRI,
        EngineType.ONS,
        EngineType.TYRANO,
        EngineType.RPG_MV,
        EngineType.RPG_MZ,
        EngineType.VN,
        EngineType.WEB_OTHER,
        EngineType.ARTEMIS,
        EngineType.SIGLUS,
        EngineType.REALLIVE,
        EngineType.AVG32,
        EngineType.UK2,
        EngineType.FVP,
        EngineType.ETHORNELL,
        EngineType.CMVS,
        EngineType.SYSTEMNNN,
        EngineType.XSYS35,
        EngineType.XSYS4,
    )
    private val refreshMutex = Mutex()
    private val sessionOrderLock = Any()
    private var lastSessionOrderInProcess = 0L

    fun supports(engine: EngineType): Boolean = engine in supportedEngines

    /** Returns a persisted ordering key; wall-clock rollback cannot move a new session backwards. */
    fun nextSessionStartedAt(context: Context): Long = synchronized(sessionOrderLock) {
        val preferences = try {
            context.applicationContext.getSharedPreferences(ORDER_PREFS, Context.MODE_PRIVATE)
        } catch (failure: Exception) {
            Log.w(TAG, "Could not open playtime session order preferences", failure)
            null
        }
        val storedOrder = preferences?.let { prefs ->
            try {
                prefs.getLong(KEY_LAST_SESSION_ORDER, 0L)
            } catch (failure: Exception) {
                Log.w(TAG, "Could not read playtime session order", failure)
                0L
            }
        } ?: 0L
        val previous = maxOf(storedOrder, lastSessionOrderInProcess)
        val next = nextSessionOrder(System.currentTimeMillis(), previous)
        lastSessionOrderInProcess = next
        if (preferences != null) {
            try {
                if (!preferences.edit().putLong(KEY_LAST_SESSION_ORDER, next).commit()) {
                    Log.w(TAG, "Could not persist playtime session order")
                }
            } catch (failure: Exception) {
                Log.w(TAG, "Could not persist playtime session order", failure)
            }
        }
        next
    }

    fun observe(context: Context, gameUri: String): Flow<GamePlaytimeStats> {
        if (gameUri.isBlank() || !AppProcess.isMain(context)) return flowOf(GamePlaytimeStats())
        return flow {
            val database = withContext(Dispatchers.IO) { GamePlaytimeDatabase.get(context) }
            emitAll(database.records().observeGame(gameUri).map { rows ->
                PlaytimeRecordLogic.statsFor(gameUri, rows.map { it.toSnapshot() })
            })
        }.catch { failure ->
            if (failure is CancellationException || failure !is Exception) throw failure
            Log.w(TAG, "Could not observe playtime; reporting empty statistics", failure)
            emit(GamePlaytimeStats())
        }
    }

    suspend fun refresh(context: Context) {
        try {
            if (!AppProcess.isMain(context)) return
            withContext(Dispatchers.IO) {
                refreshMutex.withLock {
                    val store = PlaytimeCheckpointStore(context.applicationContext.noBackupFilesDir)
                    val checkpoints = PlaytimeRecordLogic.merge(store.readAll())
                    if (checkpoints.isEmpty()) return@withLock
                    val database = GamePlaytimeDatabase.get(context)
                    database.withTransaction {
                        checkpoints.forEach { snapshot ->
                            database.records().merge(
                                PlaytimeRecordEntity(
                                    recordId = snapshot.recordId,
                                    sessionId = snapshot.sessionId,
                                    gameUri = snapshot.gameUri,
                                    durationMillis = snapshot.durationMillis,
                                    startedAtMillis = snapshot.startedAtMillis,
                                ),
                            )
                        }
                    }
                    checkpoints.filter(PlaytimeRecordSnapshot::closed)
                        .forEach { store.deleteClosed(it.recordId) }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // Playtime is auxiliary: retain journal checkpoints and never fail app startup/foregrounding.
            Log.w(TAG, "Could not refresh playtime checkpoints", failure)
        }
    }

}
