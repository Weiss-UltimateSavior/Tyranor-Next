package com.tyranor.next.core.game.storage

import android.content.Context
import android.net.Uri
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.game.scan.GameRootMatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/**
 * 游戏库门面：对 UI / 启动编排暴露同步的内存缓存读与「缓存先行、FIFO 落库」写。
 *
 * 从 EngineScanner 拆出（架构优化 P0-1），Scanner 只保留扫描与引擎识别；职责边界：
 * - 读命中内存缓存，未命中阻塞读库一次（Application 启动已 [prewarmCaches] 预热）；
 * - 写同步更新缓存并按发起顺序在仓库单线程写调度器上落库，写失败后清除缓存并回源；
 * - 修订号广播（[libraryRevision] / [rootsRevision] / [quickLaunchRevision]）统一在本层维护。
 */
object GameLibraryFacade {

    /** 快捷启动槽位上限，供 UI 校验；唯一来源为 [GameLibraryDao.MAX_QUICK_LAUNCH]。 */
    const val MAX_QUICK_LAUNCH = GameLibraryDao.MAX_QUICK_LAUNCH

    // 主页面会在 Tab 动画中反复进入组合。将已解析的数据保留在进程内，避免每次切页都在
    // 主线程重新读库、解析并构造完整游戏列表。
    private val cacheLock = Any()
    private val gameWrites = GameLibraryWriteGate()
    // 每游戏逐源条目绑定修订号（进程内单调计数）：批量封面刮削跨异步周期完成时，用于识别
    // 「先绑定再解绑」使当前 id 回到 null 的场景，避免刮削写回绕过用户显式解绑重新自动回填。
    internal data class BindingRevisionSnapshot(
        val vndb: Long = 0L,
        val bangumi: Long = 0L,
        val hikarinagi: Long = 0L,
        val nextmoe: Long = 0L,
    )

    private val bindingRevisions = mutableMapOf<String, BindingRevisionSnapshot>()

    @Volatile
    private var gamesCache: List<ScanGame>? = null
    @Volatile
    private var recentGamesCache: List<ScanGame>? = null
    @Volatile
    private var quickLaunchCache: List<ScanGame>? = null
    @Volatile
    private var rootsCache: List<String>? = null

    // 快捷启动版本号：任何增删/刷新后自增，供首页实时感知改动后重新加载
    private val _quickLaunchRevision = MutableStateFlow(0)
    val quickLaunchRevision: StateFlow<Int> = _quickLaunchRevision.asStateFlow()
    private val _rootsRevision = MutableStateFlow(0)
    val rootsRevision: StateFlow<Int> = _rootsRevision.asStateFlow()
    private val _libraryRevision = MutableStateFlow(0)
    val libraryRevision: StateFlow<Int> = _libraryRevision.asStateFlow()

    @Volatile
    private var persistFailureListenerInstalled = false

    /** 持久化写失败后的自愈入口：清空派生自 DB 的缓存（roots 单独管理，不受影响）。 */
    private fun invalidatePersistenceCaches() {
        synchronized(cacheLock) {
            gamesCache = null
            recentGamesCache = null
            quickLaunchCache = null
        }
        // 单游戏覆盖同样走「缓存先行、post 落库」模式，一并回源
        GameOverridesRepository.invalidateRowCache()
    }

    /**
     * 注册落库失败自愈回调：预热或首次读库时显式安装，避免 object 间隐式加载顺序耦合。
     */
    private fun installPersistFailureListener() {
        if (persistFailureListenerInstalled) return
        synchronized(cacheLock) {
            if (persistFailureListenerInstalled) return
            GameLibraryRepository.onPersistFailure = ::invalidatePersistenceCaches
            persistFailureListenerInstalled = true
        }
    }

    /** Application 启动预热：回填全部同步门面缓存，避免主线程首读阻塞。 */
    internal fun prewarmCaches(context: Context) {
        installPersistFailureListener()
        loadGames(context)
        loadRecentGames(context)
        loadQuickLaunch(context)
        loadRoots(context)
    }

    // ============ 游戏库 ============

    @Volatile
    internal var skipPersistForTest: Boolean = false

    fun loadGames(context: Context): List<ScanGame> =
        gamesCache ?: synchronized(cacheLock) {
            loadGamesLocked(context)
        }

    /** 在同一 `cacheLock` 下原子读取游戏列表与各条目当前绑定版本快照，消除刮削启动时的交错竞态。 */
    internal fun loadGamesWithBindingRevisions(
        context: Context,
        overrideGames: List<ScanGame>? = null,
    ): Pair<List<ScanGame>, Map<String, BindingRevisionSnapshot>> = synchronized(cacheLock) {
        val currentGames = loadGamesLocked(context)
        // 按选定 URI 从当前权威列表取快照，避免旧列表与锁内较新修订号错配（如先绑后解再写回）
        val snapshot = if (overrideGames == null) {
            currentGames.toList()
        } else {
            val currentByUri = currentGames.associateBy { it.uri }
            overrideGames.mapNotNull { candidate -> currentByUri[candidate.uri] }
        }
        val revisions = snapshot.associate { game ->
            game.uri to (bindingRevisions[game.uri] ?: BindingRevisionSnapshot())
        }
        snapshot to revisions
    }

    @androidx.annotation.VisibleForTesting
    internal fun seedGamesForTest(games: List<ScanGame>?) {
        synchronized(cacheLock) {
            gamesCache = games?.toList()
            bindingRevisions.clear()
        }
    }

    /** Use [registerGames] when adding URI references. Existing entries can be edited or removed here. */
    fun updateGames(context: Context, transform: (List<ScanGame>) -> List<ScanGame>): List<ScanGame> =
        synchronized(cacheLock) {
            val current = loadGamesLocked(context)
            val updated = transform(current).toList()
            if (updated != current) {
                gamesCache = updated
                recordBindingRevisionsLocked(current, updated)
                if (!skipPersistForTest) {
                    // 差量落库：仅 upsert 变更行、删除消失行，封面单字段变化不再触发整库序列化。
                    GameLibraryRepository.post(context) { GameLibraryRepository.applyDiff(it, current, updated) }
                }
            }
            updated
        }

    internal suspend fun registerGames(context: Context, transform: (List<ScanGame>) -> List<ScanGame>): List<ScanGame> =
        withContext(Dispatchers.IO) {
            val coroutine = currentCoroutineContext()
            gameWrites.update {
                coroutine.ensureActive()
                updateGames(context, transform)
            }
        }

    /** The caller has already obtained any save-sync admission. The callback only evicts resources. */
    internal fun evictUnusedSystemNnnCopy(
        context: Context,
        sourceId: String,
        excludingUri: String? = null,
        evict: () -> Boolean,
    ): Boolean = gameWrites.evictIfUnused(
        referenced = {
            loadGames(context).any { game ->
                game.uri != excludingUri && SystemNnnGameDirectory.sourceIdentity(game.uri) == sourceId
            }
        },
        evict = evict,
    )

    private fun loadGamesLocked(context: Context): List<ScanGame> {
        installPersistFailureListener()
        return gamesCache ?: GameLibraryRepository.readBlocking(context) { GameLibraryRepository.loadGames(it) }
            .also { gamesCache = it }
    }

    /**
     * 封面/绑定元数据单行更新（迁移方案阶段 2）：DB 只 UPDATE 封面与绑定相关列，封面刮削
     * 等高频路径不再触发整库序列化。返回更新后的游戏；游戏不在库中或封面/绑定七列均无变化
     * 时返回 null。（BK-51：原名单提「封面」，实际比较与落库含绑定 id 与 metadataTitle，
     * 更名 [updateGameCoverMetadata] 名副其实。）
     */
    fun updateGameCoverMetadata(
        context: Context,
        uri: String,
        transform: (ScanGame) -> ScanGame,
    ): ScanGame? = synchronized(cacheLock) {
        val current = loadGamesLocked(context)
        val before = current.firstOrNull { it.uri == uri } ?: return null
        val after = transform(before)
        if (after.coverUri == before.coverUri && after.coverSource == before.coverSource &&
            after.vndbId == before.vndbId && after.bangumiId == before.bangumiId &&
            after.hikarinagiId == before.hikarinagiId && after.nextmoeId == before.nextmoeId &&
            after.metadataTitle == before.metadataTitle
        ) {
            return null
        }
        gamesCache = current.map { if (it.uri == uri) after else it }
        recentGamesCache = recentGamesCache?.map { recent ->
            if (recent.uri == uri) after.copy(openTime = recent.openTime) else recent
        }
        quickLaunchCache = quickLaunchCache?.map { quick ->
            if (quick.uri == uri) after else quick
        }
        recordBindingRevisionLocked(before, after)
        if (!skipPersistForTest) {
            GameLibraryRepository.post(context) {
                GameLibraryRepository.updateCover(
                    it,
                    uri,
                    after.coverUri,
                    after.coverSource,
                    after.vndbId,
                    after.bangumiId,
                    after.hikarinagiId,
                    after.nextmoeId,
                    after.metadataTitle,
                )
            }
        }
        after
    }

    internal fun bindingRevision(uri: String): BindingRevisionSnapshot = synchronized(cacheLock) {
        bindingRevisions[uri] ?: BindingRevisionSnapshot()
    }

    private fun recordBindingRevisionsLocked(before: List<ScanGame>, after: List<ScanGame>) {
        val beforeByUri = before.associateBy { it.uri }
        val afterByUri = after.associateBy { it.uri }
        for (uri in beforeByUri.keys + afterByUri.keys) {
            val prev = beforeByUri[uri]
            val next = afterByUri[uri]
            val replacedOrToggled = prev == null || next == null
            recordBindingRevisionLocked(
                uri = uri,
                vndbChanged = replacedOrToggled || prev.vndbId != next.vndbId,
                bangumiChanged = replacedOrToggled || prev.bangumiId != next.bangumiId,
                hikarinagiChanged = replacedOrToggled || prev.hikarinagiId != next.hikarinagiId,
                nextmoeChanged = replacedOrToggled || prev.nextmoeId != next.nextmoeId,
            )
        }
    }

    private fun recordBindingRevisionLocked(before: ScanGame, after: ScanGame) {
        recordBindingRevisionLocked(
            uri = after.uri,
            vndbChanged = before.vndbId != after.vndbId,
            bangumiChanged = before.bangumiId != after.bangumiId,
            hikarinagiChanged = before.hikarinagiId != after.hikarinagiId,
            nextmoeChanged = before.nextmoeId != after.nextmoeId,
        )
    }

    private fun recordBindingRevisionLocked(
        uri: String,
        vndbChanged: Boolean,
        bangumiChanged: Boolean,
        hikarinagiChanged: Boolean,
        nextmoeChanged: Boolean,
    ) {
        if (!vndbChanged && !bangumiChanged && !hikarinagiChanged && !nextmoeChanged) return
        val current = bindingRevisions[uri] ?: BindingRevisionSnapshot()
        bindingRevisions[uri] = current.copy(
            vndb = if (vndbChanged) current.vndb + 1L else current.vndb,
            bangumi = if (bangumiChanged) current.bangumi + 1L else current.bangumi,
            hikarinagi = if (hikarinagiChanged) current.hikarinagi + 1L else current.hikarinagi,
            nextmoe = if (nextmoeChanged) current.nextmoe + 1L else current.nextmoe,
        )
    }

    /** 从持久游戏库中移除指定游戏（在游戏页或首页删除游戏时调用，保证库与最近列表一致）。 */
    fun removeGame(context: Context, uri: String) {
        updateGames(context) { games -> games.filterNot { it.uri == uri } }
    }

    // ============ 最近打开 ============

    fun recordRecentGame(context: Context, game: ScanGame) {
        synchronized(cacheLock) {
            // 同步回填主库缓存：applyDiff 全行 upsert 以缓存快照为准，不同步会让后续
            // 改名/重扫等整行写把 DB 的 last_opened_at 回滚到启动时的旧值（最近列表退化）。
            val games = loadGamesLocked(context)
            val recent = loadRecentGamesLocked(context)
            val openTime = System.currentTimeMillis()
            val touched = game.copy(openTime = openTime)
            gamesCache = games.map { if (it.uri == game.uri) it.copy(openTime = openTime) else it }
            recentGamesCache = (listOf(touched) + recent.filterNot { it.uri == game.uri }).take(GameLibraryDao.RECENT_LIMIT)
            GameLibraryRepository.post(context) { GameLibraryRepository.restoreRecent(it, listOf(touched)) }
        }
        notifyGamesChanged()
    }

    fun loadRecentGames(context: Context): List<ScanGame> =
        recentGamesCache ?: synchronized(cacheLock) {
            loadRecentGamesLocked(context)
        }

    private fun loadRecentGamesLocked(context: Context): List<ScanGame> =
        recentGamesCache ?: GameLibraryRepository.readBlocking(context) { GameLibraryRepository.loadRecentGames(it) }
            .also { recentGamesCache = it }

    /** 清除用户选中的打开记录；删除游戏时不传时间，清除该游戏的记录。 */
    fun removeRecentGame(context: Context, uri: String, expectedOpenedAt: Long? = null) {
        fun matches(game: ScanGame) = game.uri == uri && (expectedOpenedAt == null || game.openTime == expectedOpenedAt)
        synchronized(cacheLock) {
            val games = loadGamesLocked(context)
            val recent = loadRecentGamesLocked(context)
            recentGamesCache = recent.filterNot(::matches)
            // 主库缓存同步清零，避免后续整行 upsert 复活已移除的最近记录。
            gamesCache = games.map { if (matches(it)) it.copy(openTime = 0) else it }
            GameLibraryRepository.post(context) { GameLibraryRepository.clearRecent(it, uri, expectedOpenedAt) }
        }
    }

    internal fun updateRecentGames(
        context: Context,
        transform: (List<ScanGame>) -> List<ScanGame>,
    ): List<ScanGame> = synchronized(cacheLock) {
        val current = loadRecentGamesLocked(context)
        val updated = transform(current).toList()
        if (updated != current) {
            recentGamesCache = updated
            // 最近打开为派生视图：只回写保留条目的 openTime，被过滤条目对应游戏行已不在库中。
            GameLibraryRepository.post(context) { GameLibraryRepository.restoreRecent(it, updated) }
        }
        updated
    }

    // ============ 首页快捷启动（最多 3 个） ============

    fun loadQuickLaunch(context: Context): List<ScanGame> =
        quickLaunchCache ?: synchronized(cacheLock) {
            loadQuickLaunchLocked(context)
        }

    private fun loadQuickLaunchLocked(context: Context): List<ScanGame> =
        quickLaunchCache ?: GameLibraryRepository.readBlocking(context) { GameLibraryRepository.loadQuickLaunch(it) }
            .also { quickLaunchCache = it }

    fun isQuickLaunched(context: Context, uri: String): Boolean =
        loadQuickLaunch(context).any { it.uri == uri }

    /** 加入快捷启动。已存在视为成功；槽位满（MAX_QUICK_LAUNCH=3）返回 false。 */
    fun addQuickLaunch(context: Context, game: ScanGame): Boolean = synchronized(cacheLock) {
        val current = loadQuickLaunch(context)
        if (current.any { it.uri == game.uri }) return@synchronized true
        if (current.size >= GameLibraryDao.MAX_QUICK_LAUNCH) return@synchronized false
        saveQuickLaunch(context, current + game)
        true
    }

    fun removeQuickLaunch(context: Context, uri: String) {
        synchronized(cacheLock) {
            saveQuickLaunch(context, loadQuickLaunch(context).filterNot { it.uri == uri })
        }
    }

    /**
     * 用主游戏库最新数据刷新快捷启动快照（游戏页修改封面等后首页实时同步），并回写存储。
     * 快捷启动为 games 的关联视图（JOIN），标题/封面更新自动生效；不存在孤儿快照。
     */
    fun refreshQuickLaunch(context: Context): List<ScanGame> = synchronized(cacheLock) {
        val library = loadGames(context).associateBy { it.uri }
        val current = loadQuickLaunch(context)
        val refreshed = current.mapNotNull { library[it.uri] ?: it }
        if (refreshed != current) saveQuickLaunch(context, refreshed)
        refreshed
    }

    internal fun saveQuickLaunch(context: Context, games: List<ScanGame>) {
        synchronized(cacheLock) {
            val snapshot = games.toList()
            quickLaunchCache = snapshot
            GameLibraryRepository.post(context) {
                GameLibraryRepository.replaceQuickLaunch(it, snapshot.map { game -> game.uri })
            }
            _quickLaunchRevision.value++
        }
    }

    /**
     * 快捷启动的原子「读-改-写」：加载、变换、保存同一把缓存锁内完成，
     * 避免并发路径（设置页直写 vs VM 命令队列）互相丢失更新。
     */
    internal fun updateQuickLaunch(context: Context, transform: (List<ScanGame>) -> List<ScanGame>) {
        synchronized(cacheLock) {
            saveQuickLaunch(context, transform(loadQuickLaunch(context)))
        }
    }

    // ============ 扫描根目录 ============

    fun saveRoot(context: Context, uri: Uri): List<String> = saveRoot(context, uri.toString())

    /**
     * 保存扫描根目录（支持 SAF URI 与真实路径）。
     * 真实路径会规范化：去除首尾空白与尾部路径分隔符（保留根目录 "/"），
     * 避免「/games」与「/games/」作为两个根重复保存、删除其一误清整目录游戏。
     */
    fun saveRoot(context: Context, rootPath: String): List<String> {
        val key = rootPath.trim().trimEnd('/').let { if (it.isEmpty()) "/" else it }
        synchronized(cacheLock) {
            val existing = loadRootsLocked(context).toMutableList()
            val added = !existing.contains(key)
            if (!added) return existing
            existing.add(key)
            rootsCache = existing.toList()
            GameLibraryRepository.post(context) { GameLibraryRepository.saveRoot(it, key) }
            _rootsRevision.value++
            return existing
        }
    }

    fun removeRoot(context: Context, uri: Uri) {
        synchronized(cacheLock) {
            val current = loadRootsLocked(context)
            val existing = current.filterNot { it == uri.toString() }
            if (existing.size == current.size) return
            rootsCache = existing
            GameLibraryRepository.post(context) { GameLibraryRepository.removeRoot(it, uri.toString()) }
            _rootsRevision.value++
        }
    }

    fun removeRootAndGames(context: Context, uri: Uri): List<ScanGame> {
        removeRoot(context, uri)
        val root = uri.toString()
        var removed = emptyList<ScanGame>()
        updateGames(context) { games ->
            removed = games.filter { isGameUnderRoot(root, it.uri) }
            val removedUris = removed.mapTo(HashSet()) { it.uri }
            games.filterNot { it.uri in removedUris }
        }
        if (removed.isEmpty()) return emptyList()
        val removedUris = removed.mapTo(HashSet()) { it.uri }
        // 最近打开/快捷启动的 DB 行已随游戏行删除（派生视图 + 级联），这里只需同步内存缓存。
        synchronized(cacheLock) {
            recentGamesCache = recentGamesCache?.filterNot { it.uri in removedUris }
        }
        updateQuickLaunch(context) { list -> list.filterNot { it.uri in removedUris } }
        notifyGamesChanged()
        return removed
    }

    /** 外部逻辑（如运行时模块降级）直接改库后通知 UI 重新读取派生列表。 */
    fun notifyGamesChanged() {
        _libraryRevision.update { it + 1 }
    }

    fun loadRoots(context: Context): List<String> =
        rootsCache ?: synchronized(cacheLock) {
            loadRootsLocked(context)
        }

    private fun loadRootsLocked(context: Context): List<String> =
        rootsCache ?: GameLibraryRepository.readBlocking(context) { GameLibraryRepository.loadRoots(it) }
            .also { rootsCache = it }

    private fun isGameUnderRoot(rootUriText: String, gameUriText: String): Boolean =
        GameRootMatcher.isGameUnderRoot(rootUriText, gameUriText)
}
