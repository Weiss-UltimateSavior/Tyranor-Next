package com.tyranor.next.core.game.storage

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import androidx.room.withTransaction
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.game.scan.EngineScanner
import com.tyranor.next.core.game.scan.GameRecordCodec
import com.tyranor.next.core.game.scan.GameRootMatcher
import com.tyranor.next.core.settings.AppSettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * 游戏库持久化仓库（迁移方案第八节）：Room 是唯一数据源，旧 game_scanner prefs 仅作两用——
 * 1) 首次启动一次性导入（storage_migration_v1_done 标记，导入后保留旧数据至少一个版本便于回滚）；
 * 2) 过渡期镜像写回（防抖合并），保证用户回滚旧版本（只读 prefs）时数据基本可用。
 * 标记在库之外：若恢复备份后出现「标记在但库为空」的快照不一致（WAL 未入备份），
 * 启动时会按旧镜像幂等重导入兜底。
 *
 * 线程模型：所有挂起 API 供 IO 线程调用；EngineScanner 同步 API 的写入经 [post]
 * （单线程 FIFO 调度 + 写互斥）串行落库，保证先发写不被后发写覆盖；镜像写回排在同一队列之后。
 */
object GameLibraryRepository {

    private const val TAG = "GameLibraryRepo"

    private const val LEGACY_PREFS = "game_scanner"
    private const val LEGACY_KEY_ROOTS = "scan_roots"
    private const val LEGACY_KEY_GAMES = "scan_games"
    private const val LEGACY_KEY_RECENT = "recent_games"
    private const val LEGACY_KEY_QUICK = "quick_launch"
    private const val KEY_MIGRATION_FLAG = "storage_migration_v1_done"

    /** prefs 镜像防抖窗口：批量刮削期间多次单行更新合并为一次整库镜像写。 */
    private const val PREFS_MIRROR_DELAY_MS = 1500L

    /** SQLite 绑定变量上限（旧系统 999）以下的安全分片大小。 */
    private const val SQL_CHUNK_SIZE = 500

    /** 单线程写调度：同步 API 发出的 DB 写按发起顺序执行。 */
    private val writeExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "game-library-writer")
    }
    private val writeDispatcher = writeExecutor.asCoroutineDispatcher()
    private val writeScope = CoroutineScope(SupervisorJob() + writeDispatcher)

    /**
     * 写互斥：缓存门面发出的 DB 写与一致性搜索共用，保证搜索不会读到未落库的中间状态。
     * 搜索额外先切到同一线程排队（见 [searchGamesConsistent]），mutex 只兜挂起恢复后的乱序。
     */
    private val writeMutex = Mutex()

    private val migrationMutex = Mutex()

    @Volatile
    private var migrationDone = false

    /** 应用启动预热（仅主进程）：旧数据迁移检查 + 单游戏覆盖 prefs 同步 + EngineScanner
     * 同步门面缓存回填，避免主线程首次读库阻塞。引擎子进程直接跳过。 */
    fun init(context: Context) {
        if (!isMainProcess(context)) return
        val app = context.applicationContext
        writeScope.launch {
            try {
                ensureMigrated(app)
            } catch (t: Throwable) {
                Log.e(TAG, "Game library migration failed", t)
            }
            try {
                GameOverridesRepository.ensureSynced(app)
            } catch (t: Throwable) {
                Log.e(TAG, "Game overrides sync failed", t)
            }
            try {
                EngineScanner.prewarmCaches(app)
            } catch (t: Throwable) {
                Log.e(TAG, "Game library prewarm failed", t)
            }
        }
    }

    // ============ 迁移 ============

    /**
     * 首次访问前把旧 prefs 导入 DB；标记写在导入完成之后，中途崩溃会在下次启动重跑幂等导入。
     * 恢复备份可能出现「标记在但库为空」（WAL 不进备份）：此时按旧镜像幂等重导入兜底。
     */
    suspend fun ensureMigrated(context: Context) {
        if (migrationDone) return
        val prefs = context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
        val flagSet = prefs.getBoolean(KEY_MIGRATION_FLAG, false)
        val db = GameLibraryDatabase.get(context)
        migrationMutex.withLock {
            if (migrationDone) return
            val alreadyFlagged = prefs.getBoolean(KEY_MIGRATION_FLAG, false)
            val repairNeeded = alreadyFlagged &&
                dao(context).countGames() == 0 &&
                !prefs.getString(LEGACY_KEY_GAMES, null).isNullOrBlank()
            if (alreadyFlagged && !repairNeeded) {
                migrationDone = true
                return
            }
            try {
                runImport(context, prefs)
                if (!alreadyFlagged) {
                    prefs.edit().putBoolean(KEY_MIGRATION_FLAG, true).commit()
                }
                migrationDone = true
                Log.i(TAG, "Legacy game_scanner prefs imported into Room (repair=$repairNeeded)")
            } catch (t: Throwable) {
                // 导入失败不阻断启动：DB 保持空/旧状态，下次启动重试；旧 prefs 未删，可整体回滚。
                Log.e(TAG, "Legacy game library import failed", t)
            }
        }
    }

    private suspend fun runImport(context: Context, prefs: android.content.SharedPreferences) {
        val db = GameLibraryDatabase.get(context)
        val dao = dao(context)
        db.withTransaction {
            val plan = LegacyGameLibraryImportPlan.build(
                rootsText = prefs.getString(LEGACY_KEY_ROOTS, null),
                gamesText = prefs.getString(LEGACY_KEY_GAMES, null),
                recentText = prefs.getString(LEGACY_KEY_RECENT, null),
                quickText = prefs.getString(LEGACY_KEY_QUICK, null),
                now = System.currentTimeMillis(),
                isGameUnderRoot = GameRootMatcher::isGameUnderRoot,
            )
            dao.insertRootsIfAbsent(plan.roots)
            dao.insertGamesIfAbsent(plan.games)
            dao.clearQuickLaunch()
            if (plan.quickLaunch.isNotEmpty()) dao.insertQuickLaunchIfAbsent(plan.quickLaunch)
        }
    }

    // ============ games ============

    suspend fun loadGames(context: Context): List<ScanGame> = withContext(Dispatchers.IO) {
        ensureMigrated(context)
        dao(context).getAllGames().map { it.toModel() }
    }

    /** 权威差量落库：变更行单行 upsert（保留首次发现时间），消失行删除（级联清理快捷启动）。 */
    suspend fun applyDiff(context: Context, previous: List<ScanGame>, updated: List<ScanGame>) =
        withContext(Dispatchers.IO) {
            ensureMigrated(context)
            val db = GameLibraryDatabase.get(context)
            val prevByUri = previous.associateBy { it.uri }
            val updatedByUri = updated.associateBy { it.uri }
            val changed = updated.filter { prevByUri[it.uri] != it }
            val removed = previous.mapNotNull { if (updatedByUri.containsKey(it.uri)) null else it.uri }
            if (changed.isEmpty() && removed.isEmpty()) return@withContext

            val now = System.currentTimeMillis()
            db.withTransaction {
                val dao = dao(context)
                removed.chunked(SQL_CHUNK_SIZE).forEach { dao.deleteGamesByUris(it) }
                if (changed.isNotEmpty()) {
                    // 首次发现时间取自现有行；扫描根归属在事务内按当前 roots 解析。
                    val existing = dao.getGamesChunked(changed.map { it.uri }).associateBy { it.uri }
                    val rootUris = dao.getRoots().map { it.uri }
                    dao.upsertGames(
                        changed.map { game ->
                            GameEntity.from(
                                game,
                                discoveredAt = existing[game.uri]?.discoveredAt ?: now,
                                updatedAt = now,
                                rootUri = rootUris.firstOrNull { GameRootMatcher.isGameUnderRoot(it, game.uri) },
                            )
                        },
                    )
                }
            }
        }

    /** 封面元数据单行更新（方案阶段 2）：封面刮削不再触碰整库。返回是否有行被更新。 */
    suspend fun updateCover(
        context: Context,
        uri: String,
        coverUri: String?,
        coverSource: String?,
        vndbId: String?,
        metadataTitle: String?,
    ): Boolean = withContext(Dispatchers.IO) {
        ensureMigrated(context)
        dao(context).updateCover(uri, coverUri, coverSource, vndbId, metadataTitle, System.currentTimeMillis()) > 0
    }

    /** 最近打开列表：games.last_opened_at 的派生视图（方案 4.1），替代旧 recent_games 快照。 */
    suspend fun loadRecentGames(
        context: Context,
        limit: Int = GameLibraryDao.RECENT_LIMIT,
    ): List<ScanGame> = withContext(Dispatchers.IO) {
        ensureMigrated(context)
        dao(context).getRecentGames(limit).map { it.toModel() }
    }

    /** 从最近打开列表移除：派生视图写 0 即移除。 */
    suspend fun clearRecent(context: Context, uri: String) = withContext(Dispatchers.IO) {
        ensureMigrated(context)
        dao(context).updateLastOpened(uri, 0L, System.currentTimeMillis())
    }

    /** 最近打开时间回写：仅更新 openTime 实际变化的行（方案 4.1 的 recent 派生模型）。 */
    suspend fun restoreRecent(context: Context, entries: List<ScanGame>) = withContext(Dispatchers.IO) {
        if (entries.isEmpty()) return@withContext
        ensureMigrated(context)
        val dao = dao(context)
        val current = dao.getGamesChunked(entries.map { it.uri }).associateBy { it.uri }
        val now = System.currentTimeMillis()
        val changed = entries.filter { entry ->
            current[entry.uri]?.let { it.lastOpenedAt != entry.openTime } == true
        }
        if (changed.isEmpty()) return@withContext
        GameLibraryDatabase.get(context).withTransaction {
            changed.forEach { dao.updateLastOpened(it.uri, it.openTime, now) }
        }
    }

    // ============ 搜索 / 排序（方案阶段 3） ============

    /**
     * 与写入串行化的一致性搜索：先切到单线程写调度器排队（严格晚于已排队的写），
     * 再经写互斥兜住挂起恢复后的乱序；返回已按 sortMode 排序的结果。
     */
    suspend fun searchGamesConsistent(context: Context, query: String, sortMode: String): List<ScanGame> =
        withContext(writeDispatcher) {
            writeMutex.withLock { searchGames(context, query, sortMode) }
        }

    suspend fun searchGames(context: Context, query: String, sortMode: String): List<ScanGame> =
        withContext(Dispatchers.IO) {
            ensureMigrated(context)
            val escaped = escapeLikeQuery(query.trim())
            if (escaped.isEmpty()) return@withContext emptyList()
            val dao = dao(context)
            val entities = if (sortMode == AppSettingsStore.GAME_SORT_BRACKET_TAG) {
                dao.searchGamesSortedByBracketTag(escaped)
            } else {
                dao.searchGamesSortedByTitle(escaped)
            }
            entities.map { it.toModel() }
        }

    /** LIKE 通配符转义，保证用户输入按字面匹配（与 UI contains 行为一致）。 */
    private fun escapeLikeQuery(query: String): String =
        query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    // ============ scan_roots ============

    suspend fun loadRoots(context: Context): List<String> = withContext(Dispatchers.IO) {
        ensureMigrated(context)
        dao(context).getRoots().map { it.uri }
    }

    /** 幂等新增扫描根；返回是否新增（供 EngineScanner 决定是否广播修订号）。 */
    suspend fun saveRoot(context: Context, rootKey: String): Boolean = withContext(Dispatchers.IO) {
        ensureMigrated(context)
        val dao = dao(context)
        if (dao.getRoots().any { it.uri == rootKey }) return@withContext false
        GameLibraryDatabase.get(context).withTransaction {
            dao.insertRootsIfAbsent(listOf(ScanRootEntity(uri = rootKey, addedAt = System.currentTimeMillis())))
        }
        true
    }

    suspend fun removeRoot(context: Context, rootKey: String): Boolean = withContext(Dispatchers.IO) {
        ensureMigrated(context)
        dao(context).deleteRoot(rootKey) > 0
    }

    // ============ quick_launch ============

    /** 快捷启动为 games 的关联视图（JOIN）；槽位顺序即旧列表顺序。 */
    suspend fun loadQuickLaunch(context: Context): List<ScanGame> = withContext(Dispatchers.IO) {
        ensureMigrated(context)
        dao(context).getQuickLaunchGames().map { it.toModel() }
    }

    suspend fun replaceQuickLaunch(context: Context, orderedUris: List<String>) = withContext(Dispatchers.IO) {
        ensureMigrated(context)
        val db = GameLibraryDatabase.get(context)
        val dao = dao(context)
        val validUris = if (orderedUris.isEmpty()) {
            emptySet()
        } else {
            dao.getGamesChunked(orderedUris).mapTo(HashSet()) { it.uri }
        }
        db.withTransaction { dao.replaceQuickLaunch(orderedUris, System.currentTimeMillis(), validUris) }
    }

    // ============ prefs 镜像（回滚兼容，方案第九/十二节） ============

    private val mirrorLock = Any()
    private var mirrorJob: Job? = null

    /** 在写队列尾部追加防抖镜像写：批量单行更新只产生一次整库 prefs 序列化。 */
    private fun schedulePrefsMirror(context: Context) {
        val app = context.applicationContext
        synchronized(mirrorLock) {
            mirrorJob?.cancel()
            mirrorJob = writeScope.launch {
                delay(PREFS_MIRROR_DELAY_MS)
                runCatching { writePrefsMirror(app) }
                    .onFailure { Log.e(TAG, "Prefs mirror write failed", it) }
            }
        }
    }

    private suspend fun writePrefsMirror(context: Context) {
        val dao = dao(context)
        val games = dao.getAllGames()
        val roots = dao.getRoots().map { it.uri }
        val recent = dao.getRecentGames(GameLibraryDao.RECENT_LIMIT)
        val quick = dao.getQuickLaunchGames()
        val prefs = context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(LEGACY_KEY_ROOTS, roots.joinToString("\n"))
            .putString(LEGACY_KEY_GAMES, GameRecordCodec.serialize(games.map { it.toModel() }))
            .putString(LEGACY_KEY_RECENT, GameRecordCodec.serialize(recent.map { it.toModel() }))
            .putString(LEGACY_KEY_QUICK, GameRecordCodec.serialize(quick.map { it.toModel() }))
            .apply()
    }

    // ============ 缓存门面接入 ============

    /**
     * 把一次持久化写追加到单线程写队列：FIFO 启动、互斥完成、失败仅记日志（缓存已先行更新）；
     * 成功后追加防抖 prefs 镜像。
     */
    fun post(context: Context, block: suspend (Context) -> Unit) {
        val app = context.applicationContext
        writeScope.launch {
            try {
                writeMutex.withLock { block(app) }
                schedulePrefsMirror(app)
            } catch (t: Throwable) {
                Log.e(TAG, "Game library persistence failed", t)
            }
        }
    }

    /**
     * 缓存未命中的一次性阻塞读：与旧实现的首读 SharedPreferences XML 成本相当，
     * 由 [init] 在启动时预热把主线程首读概率降到最低。禁止在 Room 事务/写互斥内调用。
     */
    fun <T> readBlocking(context: Context, block: suspend (Context) -> T): T {
        val app = context.applicationContext
        return runBlocking {
            ensureMigrated(app)
            withContext(Dispatchers.IO) { block(app) }
        }
    }

    /** IN 查询分片，规避旧系统（SQLite <3.32）999 绑定变量上限。 */
    private suspend fun GameLibraryDao.getGamesChunked(uris: Collection<String>): List<GameEntity> =
        uris.chunked(SQL_CHUNK_SIZE).flatMap { chunk -> getGamesByUris(chunk) }

    private fun dao(context: Context): GameLibraryDao = GameLibraryDatabase.get(context).gameLibraryDao()

    /** 引擎子进程（:tyrano/:kirikiri2 等）复用同一 Application 类，本仓库只在主进程初始化。 */
    private fun isMainProcess(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return Application.getProcessName() == context.packageName
        }
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        return am.runningAppProcesses
            ?.firstOrNull { it.pid == Process.myPid() }
            ?.processName == context.packageName
    }
}
