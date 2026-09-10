package com.tyranor.next.core.game.storage

import android.content.Context
import android.net.Uri
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.game.scan.GameRootMatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 游戏库门面：对 UI / 启动编排暴露同步的内存缓存读与「缓存先行、FIFO 落库」写。
 *
 * 从 EngineScanner 拆出（架构优化 P0-1），Scanner 只保留扫描与引擎识别；职责边界：
 * - 读命中内存缓存，未命中阻塞读库一次（Application 启动已 [prewarmCaches] 预热）；
 * - 写同步更新缓存并按发起顺序在仓库单线程写调度器上落库（失败仅记日志，不回滚缓存）；
 * - 修订号广播（[libraryRevision] / [rootsRevision] / [quickLaunchRevision]）统一在本层维护。
 */
object GameLibraryFacade {

    /** 快捷启动槽位上限，供 UI 校验；唯一来源为 [GameLibraryDao.MAX_QUICK_LAUNCH]。 */
    const val MAX_QUICK_LAUNCH = GameLibraryDao.MAX_QUICK_LAUNCH

    // 主页面会在 Tab 动画中反复进入组合。将已解析的数据保留在进程内，避免每次切页都在
    // 主线程重新读库、解析并构造完整游戏列表。
    private val cacheLock = Any()
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
     * 注册落库失败自愈回调：由 [prewarmCaches] 显式安装，替代原先 object init 块的
     * 反向注册（P0-1），消除 object 间隐式加载顺序耦合。
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

    fun loadGames(context: Context): List<ScanGame> =
        gamesCache ?: synchronized(cacheLock) {
            loadGamesLocked(context)
        }

    fun updateGames(context: Context, transform: (List<ScanGame>) -> List<ScanGame>): List<ScanGame> =
        synchronized(cacheLock) {
            val current = loadGamesLocked(context)
            val updated = transform(current).toList()
            if (updated != current) {
                gamesCache = updated
                // 差量落库：仅 upsert 变更行、删除消失行，封面单字段变化不再触发整库序列化。
                GameLibraryRepository.post(context) { GameLibraryRepository.applyDiff(it, current, updated) }
            }
            updated
        }

    private fun loadGamesLocked(context: Context): List<ScanGame> =
        gamesCache ?: GameLibraryRepository.readBlocking(context) { GameLibraryRepository.loadGames(it) }
            .also { gamesCache = it }

    /**
     * 封面元数据单行更新（迁移方案阶段 2）：DB 只 UPDATE 封面相关列，封面刮削等高频路径
     * 不再触发整库序列化。返回更新后的游戏；游戏不在库中或封面四列均无变化时返回 null。
     */
    fun updateGameCover(
        context: Context,
        uri: String,
        transform: (ScanGame) -> ScanGame,
    ): ScanGame? = synchronized(cacheLock) {
        val current = loadGamesLocked(context)
        val before = current.firstOrNull { it.uri == uri } ?: return null
        val after = transform(before)
        if (after.coverUri == before.coverUri && after.coverSource == before.coverSource &&
            after.vndbId == before.vndbId && after.metadataTitle == before.metadataTitle
        ) {
            return null
        }
        gamesCache = current.map { if (it.uri == uri) after else it }
        GameLibraryRepository.post(context) {
            GameLibraryRepository.updateCover(
                it,
                uri,
                after.coverUri,
                after.coverSource,
                after.vndbId,
                after.metadataTitle,
            )
        }
        after
    }

    /** 从持久游戏库中移除指定游戏（在游戏页或首页删除游戏时调用，保证库与最近列表一致）。 */
    fun removeGame(context: Context, uri: String) {
        updateGames(context) { games -> games.filterNot { it.uri == uri } }
    }

    // ============ 最近打开 ============

    fun recordRecentGame(context: Context, game: ScanGame) {
        val openTime = System.currentTimeMillis()
        synchronized(cacheLock) {
            // 同步回填主库缓存：applyDiff 全行 upsert 以缓存快照为准，不同步会让后续
            // 改名/重扫等整行写把 DB 的 last_opened_at 回滚到启动时的旧值（最近列表退化）。
            gamesCache = gamesCache?.map { if (it.uri == game.uri) it.copy(openTime = openTime) else it }
        }
        val touched = game.copy(openTime = openTime)
        updateRecentGames(context) { current ->
            (listOf(touched) + current.filterNot { it.uri == game.uri }).take(GameLibraryDao.RECENT_LIMIT)
        }
    }

    fun loadRecentGames(context: Context): List<ScanGame> =
        recentGamesCache ?: synchronized(cacheLock) {
            loadRecentGamesLocked(context)
        }

    private fun loadRecentGamesLocked(context: Context): List<ScanGame> =
        recentGamesCache ?: GameLibraryRepository.readBlocking(context) { GameLibraryRepository.loadRecentGames(it) }
            .also { recentGamesCache = it }

    /** 删除游戏时从最近打开列表移除（最近打开为 games.last_opened_at 的派生视图，写 0 即移除）。 */
    fun removeRecentGame(context: Context, uri: String) {
        synchronized(cacheLock) {
            recentGamesCache = recentGamesCache?.filterNot { it.uri == uri }
            // 主库缓存同步清零，避免后续整行 upsert 复活已移除的最近记录。
            gamesCache = gamesCache?.map { if (it.uri == uri) it.copy(openTime = 0) else it }
        }
        GameLibraryRepository.post(context) { GameLibraryRepository.clearRecent(it, uri) }
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

    fun removeRootAndGames(context: Context, uri: Uri) {
        removeRoot(context, uri)
        val root = uri.toString()
        var removedUris = emptySet<String>()
        updateGames(context) { games ->
            removedUris = games
                .filter { isGameUnderRoot(root, it.uri) }
                .mapTo(HashSet()) { it.uri }
            games.filterNot { it.uri in removedUris }
        }
        if (removedUris.isEmpty()) return
        // 最近打开/快捷启动的 DB 行已随游戏行删除（派生视图 + 级联），这里只需同步内存缓存。
        synchronized(cacheLock) {
            recentGamesCache = recentGamesCache?.filterNot { it.uri in removedUris }
        }
        saveQuickLaunch(context, loadQuickLaunch(context).filterNot { it.uri in removedUris })
        _libraryRevision.value++
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
