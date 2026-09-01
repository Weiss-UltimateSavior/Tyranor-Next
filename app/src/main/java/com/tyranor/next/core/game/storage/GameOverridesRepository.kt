package com.tyranor.next.core.game.storage

import android.content.Context
import android.util.Log
import com.core.engine.EnginePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 单游戏覆盖设置的 DB 仓库（迁移方案阶段 4）：game_overrides 表为 App 侧事实源，
 * 旧 tyranor_game_overrides prefs 保留为引擎子进程的同步镜像——
 * App 写路径双写（DB 异步落库 + prefs 同步镜像），启动时按 prefs 差异回灌
 * （覆盖旧数据导入与引擎 touchpad 写回二合一，方案阶段 4 任务 1/5）。
 */
object GameOverridesRepository {

    private const val TAG = "GameOverridesRepo"

    /** SQLite 绑定变量上限（旧系统 999）以下的安全分片大小。 */
    private const val SQL_CHUNK_SIZE = 500

    private val cacheLock = Any()

    /** 每游戏一行；value=null 表示已加载确认无覆盖，避免重复阻塞读。 */
    private val rowCache = HashMap<String, GameOverrideEntity?>()

    /** 缓存代数：失效时自增；读取期间的旧查询结果回填前必须核对代数，防止过期值写回。 */
    private var cacheGeneration = 0L

    private val syncMutex = Mutex()

    @Volatile
    private var syncDone = false

    /**
     * 启动同步：prefs 每条覆盖与 DB 组装结果按键比较（不敏感于键序），不一致以 prefs 为准
     * （DB 先写、prefs 后写的 App 正常路径两者恒等；差异只可能来自引擎 touchpad 写回
     * 或回滚版本期间的修改）。幂等可重入；首次读路径也会等待它完成，避免读到半迁移状态。
     */
    suspend fun ensureSynced(context: Context) {
        syncMutex.withLock {
            if (syncDone) return
            val startGeneration = synchronized(cacheLock) { cacheGeneration }
            withContext(Dispatchers.IO) { syncFromPrefsLocked(context) }
            synchronized(cacheLock) {
                // 同步期间若发生 invalidateRowCache（cacheGeneration 已递增），旧同步不能
                // 覆盖失效：保持 syncDone=false 让下次读取重新回灌，避免旧 DB 行复活
                if (cacheGeneration == startGeneration + 1) {
                    syncDone = true
                }
            }
        }
    }

    private suspend fun syncFromPrefsLocked(context: Context) {
        val prefs = context.getSharedPreferences(EnginePrefs.GAME_OVERRIDES_PREFS, Context.MODE_PRIVATE)
        val dao = GameLibraryDatabase.get(context).gameLibraryDao()
        val now = System.currentTimeMillis()
        var imported = 0
        val prefKeys = HashSet<String>()
        for ((gameId, raw) in prefs.all) {
            val blob = (raw as? String)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: continue
            prefKeys += gameId
            val existing = dao.getOverrideRow(gameId)
                ?.let { runCatching { GameOverridePartitions.assemble(it) }.getOrNull() }
            if (existing != null && sameContent(existing, blob)) continue
            dao.upsertOverrideRows(listOf(GameOverridePartitions.split(gameId, blob, now)))
            imported++
        }
        // 清理孤儿行：prefs 中已不存在的覆盖（如 clear() 的 DB 删除丢失）不回灌复活
        val orphans = dao.getAllOverrideRows().mapNotNull { if (it.gameUri in prefKeys) null else it.gameUri }
        orphans.chunked(SQL_CHUNK_SIZE).forEach { dao.deleteOverrideRowsByUris(it) }
        // 清缓存同时递增代数：使在途读取的旧查询结果回填前失效（与 invalidateRowCache 同款防护）
        synchronized(cacheLock) {
            cacheGeneration++
            rowCache.clear()
        }
        if (imported > 0 || orphans.isNotEmpty()) {
            Log.i(TAG, "Game overrides synced from prefs: imported=$imported pruned=${orphans.size}")
        }
    }

    /** 按键集合与值比较（顶层；值经 toString 比较，对象值来自同一序列化链路、键序稳定）。 */
    private fun sameContent(a: JSONObject, b: JSONObject): Boolean {
        if (a.length() != b.length()) return false
        for (key in a.keys()) {
            if (!b.has(key)) return false
            val av = a.opt(key)?.toString().orEmpty()
            val bv = b.opt(key)?.toString().orEmpty()
            if (av != bv) return false
        }
        return true
    }

    /** 阻塞读单行（App 设置页/启动器路径）：先等启动同步完成，再走缓存 → 单行索引读。 */
    internal fun loadRowBlocking(context: Context, gameId: String): GameOverrideEntity? =
        runBlocking {
            ensureSynced(context)
            withContext(Dispatchers.IO) { loadRowCached(context, gameId) }
        }

    private suspend fun loadRowCached(context: Context, gameId: String): GameOverrideEntity? {
        while (true) {
            ensureSynced(context)
            var generation = -1L
            var shouldRetry = false
            var hasCached = false
            var cached: GameOverrideEntity? = null
            synchronized(cacheLock) {
                if (!syncDone) {
                    shouldRetry = true
                    return@synchronized
                }
                if (rowCache.containsKey(gameId)) {
                    cached = rowCache[gameId]
                    hasCached = true
                    return@synchronized
                }
                generation = cacheGeneration
                if (!syncDone) shouldRetry = true
            }
            if (hasCached) return cached
            if (shouldRetry) continue
            // 缓存未命中：并发未命中会各自读一次单行索引查询，结果一致，无一致性风险
            val row = GameLibraryDatabase.get(context).gameLibraryDao().getOverrideRow(gameId)
            var shouldReturn = false
            synchronized(cacheLock) {
                if (syncDone && generation == cacheGeneration) {
                    rowCache[gameId] = row
                    shouldReturn = true
                }
            }
            if (shouldReturn) return row
            // 同步失效或代数变化均丢弃旧查询结果，重试并重新同步
        }
    }

    /** 更新整条记录：DB 异步落库（prefs 镜像由调用方同步写，保证引擎立即可读）。 */
    internal fun updateRecord(context: Context, gameId: String, record: JSONObject) {
        val row = GameOverridePartitions.split(gameId, record, System.currentTimeMillis())
        synchronized(cacheLock) {
            cacheGeneration++
            rowCache[gameId] = row
        }
        GameLibraryRepository.post(context) {
            GameLibraryDatabase.get(it).gameLibraryDao().upsertOverrideRows(listOf(row))
        }
    }

    /** 落库失败时的自愈入口：清空行缓存并重置同步标记——prefs 持有调用方同步写入的
     *  最新值，下次读取重新从 prefs 回灌 DB，避免 syncDone 仍为 true 而读到过期 DB 行。 */
    internal fun invalidateRowCache() {
        synchronized(cacheLock) {
            cacheGeneration++
            rowCache.clear()
            syncDone = false
        }
    }

    internal fun clearRow(context: Context, gameId: String) {
        synchronized(cacheLock) {
            cacheGeneration++
            // 保留 null tombstone 直到 DB 删除完成，防止并发 loadRowCached 在删除提交前
            // 读回旧行并以新代数重新写入缓存；失败时由 post 的 onPersistFailure 触发
            // invalidateRowCache 清空 tombstone 并重置同步，保留一致性
            rowCache[gameId] = null
        }
        GameLibraryRepository.post(context) {
            GameLibraryDatabase.get(it).gameLibraryDao().deleteOverrideRow(gameId)
        }
    }
}
