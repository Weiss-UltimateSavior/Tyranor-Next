package com.tyranor.next.core.game.scan

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.tyranor.next.R
import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.game.storage.EngineDetectionRepository
import com.tyranor.next.core.game.storage.GameLibraryDao
import com.tyranor.next.core.game.storage.GameLibraryRepository
import com.tyranor.next.core.i18n.AppLocaleController
import com.tyranor.next.core.settings.AppSettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.abs

/**
 * 精简版游戏扫描器，识别逻辑移植自 RinneMobile 的 EngineDetector/GameScanner。
 * 支持引擎：Kirikiri、ONS、Tyrano、RPG Maker XP/VX/VX Ace、RPG Maker MV/MZ、VN、WebOther、Artemis、Ren'Py。
 */
object EngineScanner {

    private const val TAG = "EngineScanner"

    private val PFS_PATCH_NAME_RE = Regex("""^[^.]+\.pfs\.\d{3}$""")
    private val OBB_NAME_RE = Regex("""^(main|patch)\.\d+\..+\.obb$""")

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

    /**
     * 将 SAF tree/document URI 映射为真实文件路径（用于引擎 native 启动）。
     * 移植自 RinneMobile ScriptEngineLaunchers.uriToFilePath：
     * documentId 形如 "primary:path" → /storage/emulated/0/path；其他卷 → /storage/<volume>/path。
     * 适用于 Android 内置存储；非 primary 卷映射到 /storage/<volume>。
     */
    fun safUriToPath(uriText: String?): String? {
        if (uriText.isNullOrBlank() || uriText.startsWith('/')) return uriText
        return try {
            val uri = Uri.parse(uriText)
            if (uri.scheme.equals("file", ignoreCase = true)) return uri.path
            if (!uri.scheme.equals("content", ignoreCase = true)) return null

            var documentId: String? = null
            val encodedPath = uri.encodedPath
            val documentMarker = encodedPath?.indexOf("/document/")
            if (documentMarker != null && documentMarker >= 0) {
                // 兼容 tree/document 混合 URI：取 /document/ 之后的编码段解码得子文档 id
                documentId = runCatching {
                    Uri.decode(encodedPath.substring(documentMarker + "/document/".length))
                }.getOrNull()
            }
            if (documentId.isNullOrEmpty()) {
                documentId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
            }
            if (documentId.isNullOrEmpty()) {
                documentId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
            }
            documentId?.let { id ->
                val colon = id.indexOf(':')
                val volume = if (colon >= 0) id.substring(0, colon) else id
                val relative = if (colon >= 0) id.substring(colon + 1) else ""
                if (volume.equals("primary", ignoreCase = true)) {
                    return if (relative.isEmpty()) "/storage/emulated/0" else "/storage/emulated/0/$relative"
                }
                if (volume.isNotEmpty()) {
                    return if (relative.isEmpty()) "/storage/$volume" else "/storage/$volume/$relative"
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    fun isRemovableStoragePath(path: String): Boolean {
        val normalized = path.replace('\\', '/')
        return normalized.matches(Regex("""^/storage/(?!emulated/0(?:/|$))[^/]+(/.*)?$"""))
    }

    // ============ 游戏结果持久化 ============

    // 持久化已迁移到 GameLibraryRepository（Room，见 docs/应用持久化存储迁移与性能优化计划方案.md）。
    // 本类保留同步缓存门面：读命中内存缓存，未命中阻塞读库一次（Application 启动已预热）；
    // 写同步更新缓存并按发起顺序在仓库单线程写调度器上落库（失败仅记日志，不回滚缓存）。

    /** Application 启动预热：回填全部同步门面缓存，避免主线程首读阻塞。 */
    internal fun prewarmCaches(context: Context) {
        loadGames(context)
        loadRecentGames(context)
        loadQuickLaunch(context)
        loadRoots(context)
    }

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

    /** 从持久游戏库中移除指定游戏（在游戏页或首页删除游戏时调用，保证库与最近列表一致）。 */
    fun removeGame(context: Context, uri: String) {
        updateGames(context) { games -> games.filterNot { it.uri == uri } }
    }

/** 目录名 → 安全文件名（用于应用内镜像/独立存档目录），非法字符替换为下划线。 */
    fun safeSaveName(rootPath: String): String {
        val name = runCatching { File(rootPath).name.takeIf { it.isNotBlank() } }.getOrNull()
            ?: abs(rootPath.hashCode()).toString()
        return name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifEmpty { "default" }
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
    fun addQuickLaunch(context: Context, game: ScanGame): Boolean {
        val current = loadQuickLaunch(context)
        if (current.any { it.uri == game.uri }) return true
        if (current.size >= GameLibraryDao.MAX_QUICK_LAUNCH) return false
        saveQuickLaunch(context, current + game)
        return true
    }

    fun removeQuickLaunch(context: Context, uri: String) {
        saveQuickLaunch(context, loadQuickLaunch(context).filterNot { it.uri == uri })
    }

    /**
     * 用主游戏库最新数据刷新快捷启动快照（游戏页修改封面等后首页实时同步），并回写存储。
     * 快捷启动为 games 的关联视图（JOIN），标题/封面更新自动生效；不存在孤儿快照。
     */
    fun refreshQuickLaunch(context: Context): List<ScanGame> {
        val library = loadGames(context).associateBy { it.uri }
        val current = loadQuickLaunch(context)
        val refreshed = current.mapNotNull { library[it.uri] ?: it }
        if (refreshed != current) saveQuickLaunch(context, refreshed)
        return refreshed
    }

    internal fun saveQuickLaunch(context: Context, games: List<ScanGame>) {
        val snapshot = games.toList()
        quickLaunchCache = snapshot
        GameLibraryRepository.post(context) {
            GameLibraryRepository.replaceQuickLaunch(it, snapshot.map { game -> game.uri })
        }
        _quickLaunchRevision.value++
    }

    // ============ 扫描根目录持久化 ============

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

    // ============ 扫描游戏 ============

    /** 全量扫描所有根目录（结果以本次扫描为准，用于首次/无数据场景）。 */
    suspend fun scanAll(context: Context): List<ScanGame> = withContext(Dispatchers.IO) {
        val startedAt = SystemClock.elapsedRealtime()
        val maxDepth = AppSettingsStore.getScanDepth(context)
        val roots = loadRoots(context)
        // 多个存储卷可以并行扫描，但限制为 2，避免同时向 DocumentsProvider 发起过多查询。
        val gate = Semaphore(2)
        val all = coroutineScope {
            roots.map { root ->
                async {
                    gate.withPermit { scanRootInternal(context.applicationContext, root, maxDepth) }
                }
            }.awaitAll().flatten()
        }
        val seen = mutableSetOf<String>()
        all.filter { seen.add(it.uri) }.also { games ->
            Log.i(
                TAG,
                "scanAll roots=${roots.size} games=${games.size} depth=$maxDepth " +
                    "elapsedMs=${SystemClock.elapsedRealtime() - startedAt}",
            )
        }
    }

    /** 全量刷新游戏库：以当前扫描结果为准，移除已删除/改名路径的旧缓存条目。 */
    suspend fun rescanLibrary(context: Context): List<ScanGame> = withContext(Dispatchers.IO) {
        val scanned = scanAll(context)
        // 扫描可能耗时较长；提交结果前再次读取当前 roots，避免扫描期间设置页删除目录后，
        // 旧 root 的扫描结果在任务结束时被重新写回游戏库。
        val activeRoots = loadRoots(context)
        val activeScanned = scanned.filter { game ->
            activeRoots.any { root -> isGameUnderRoot(root, game.uri) }
        }
        val refreshed = updateGames(context) { currentGames ->
            val existingByUri = currentGames.associateBy { it.uri }
            activeScanned.map { current ->
                existingByUri[current.uri]?.let { previous ->
                    current.copy(
                        coverUri = previous.coverUri ?: current.coverUri,
                        coverSource = previous.coverSource
                            ?: current.coverSource?.takeIf {
                                previous.coverUri == null || previous.coverUri == current.coverUri
                            },
                        vndbId = previous.vndbId,
                        metadataTitle = previous.metadataTitle,
                        externalModuleAlias = previous.externalModuleAlias ?: current.externalModuleAlias,
                        launchFile = previous.launchFile,
                        openTime = previous.openTime,
                    )
                } ?: current
            }
        }
        val validUris = refreshed.mapTo(HashSet()) { it.uri }
        // 最近打开/快捷启动为 games 派生视图，消失的游戏行已随差量删除，这里同步内存缓存即可。
        synchronized(cacheLock) {
            recentGamesCache = recentGamesCache?.filter { it.uri in validUris }
        }
        saveQuickLaunch(context, loadQuickLaunch(context).filter { it.uri in validUris })
        // 扫描识别结果入缓存（迁移方案阶段 5）：Ren'Py 版本建议与 RPGM 子运行时。
        GameLibraryRepository.post(context) { EngineDetectionRepository.recordScanDetections(it, refreshed) }
        refreshed
    }

    /**
     * 增量扫描（游戏库已有数据时调用）：遍历根目录时对已识别游戏目录剪枝跳过，
     * 只发现新游戏；返回 现有游戏 + 新发现游戏（已删除游戏保留，不主动移除）。
     */
    suspend fun incrementalScan(context: Context): List<ScanGame> = withContext(Dispatchers.IO) {
        val existing = loadGames(context)
        val known = existing.mapTo(HashSet()) { it.uri }
        val seen = HashSet<String>()
        val found = mutableListOf<ScanGame>()
        val maxDepth = AppSettingsStore.getScanDepth(context)
        loadRoots(context).forEach { root ->
            val beforeCount = found.size
            val rootUri = Uri.parse(root)
            val safSession = SafScanSession(context.applicationContext, rootUri)
            val safRoot = safSession.root()
            safRoot?.let { rootNode ->
                scanRootIncremental(context, safSession, rootNode, 0, maxDepth, known, found)
            }
            // 只有 SAF 不可用时才走真实路径兜底；正常的“没有新游戏”不再重复扫描整棵目录树。
            if (found.size == beforeCount && (safRoot == null || safSession.queryFailed)) safUriToPath(root)?.let { path ->
                scanRootIncrementalFile(context, FileScanSession(), File(path), 0, maxDepth, known, found)
            }
        }
        existing + found.filter { seen.add(it.uri) }
    }

    /** 增量遍历：目录已在库中（已知游戏）→ 剪枝；识别为新游戏 → 记录并停止下钻。 */
    private fun scanRootIncremental(
        context: Context,
        session: SafScanSession,
        dir: SafNode,
        level: Int,
        maxDepth: Int,
        known: HashSet<String>,
        out: MutableList<ScanGame>,
    ) {
        if (level > maxDepth) return
        if (dir.uri.toString() in known) return
        val children = session.children(dir)

        val detected = detectEngine(children, session::children)
        if (detected.engine != EngineType.UNKNOWN) {
            val coverUri = findLocalCoverUri(children)
            out.add(
                ScanGame(
                    title = dir.name.takeIf { it.isNotBlank() } ?: localizedText(context, R.string.scan_unnamed_game),
                    uri = dir.uri.toString(),
                    engine = detected.engine,
                    launchTarget = detected.launchTarget,
                    externalModuleAlias = detected.externalModuleAlias,
                    detectedRenpyVersion = detectRenpyVersionIfNeeded(detected, children, session),
                    coverUri = coverUri,
                    coverSource = if (coverUri.isNullOrBlank()) null else AppSettingsStore.COVER_SOURCE_LOCAL,
                )
            )
            return
        }
        for (child in children) {
            if (child.isDirectory) {
                scanRootIncremental(context, session, child, level + 1, maxDepth, known, out)
            }
        }
    }

    suspend fun scanRoot(context: Context, rootUriStr: String, maxDepth: Int = 3): List<ScanGame> = withContext(Dispatchers.IO) {
        scanRootInternal(context.applicationContext, rootUriStr, maxDepth)
    }

    private fun scanRootInternal(context: Context, rootUriStr: String, maxDepth: Int): List<ScanGame> {
        val rootUri = Uri.parse(rootUriStr)
        val results = mutableListOf<ScanGame>()
        val safSession = SafScanSession(context, rootUri)
        val safRoot = safSession.root()
        safRoot?.let { root ->
            traverseDirectories(context, safSession, root, 0, maxDepth, results)
        }
        // SAF 成功但未发现游戏是正常结果，不重复用 File API 扫一遍。
        // 查询异常/权限失效时仍保留 SD 卡真实路径兼容兜底。
        if (results.isEmpty() && (safRoot == null || safSession.queryFailed)) safUriToPath(rootUriStr)?.let { path ->
            traverseFileDirectories(context, FileScanSession(), File(path), 0, maxDepth, results)
        }
        val seen = HashSet<String>()
        return results.filter { seen.add(it.uri) }
    }

    private fun traverseDirectories(
        context: Context,
        session: SafScanSession,
        dir: SafNode,
        level: Int,
        maxDepth: Int,
        out: MutableList<ScanGame>,
    ) {
        if (level > maxDepth) return
        val children = session.children(dir)

        // 1) 本级目录本身可能是游戏（含引擎特征文件）
        val detected = detectEngine(children, session::children)
        if (detected.engine != EngineType.UNKNOWN) {
            val coverUri = findLocalCoverUri(children)
            out.add(
                ScanGame(
                    title = dir.name.takeIf { it.isNotBlank() } ?: localizedText(context, R.string.scan_unnamed_game),
                    uri = dir.uri.toString(),
                    engine = detected.engine,
                    launchTarget = detected.launchTarget,
                    externalModuleAlias = detected.externalModuleAlias,
                    detectedRenpyVersion = detectRenpyVersionIfNeeded(detected, children, session),
                    coverUri = coverUri,
                    coverSource = if (coverUri.isNullOrBlank()) null else AppSettingsStore.COVER_SOURCE_LOCAL,
                )
            )
            // 已识别为游戏，其子目录多为引擎内部资源，仅扫描直接文件层，不再深挖
            return
        }

        // 2) 否则递归子目录
        for (child in children) {
            if (child.isDirectory) {
                traverseDirectories(context, session, child, level + 1, maxDepth, out)
            }
        }
    }

    /**
     * 一次 ContentResolver.query 取得一个目录的全部子项名称和类型。
     * 相比 DocumentFile.listFiles 后逐个读取 name/isDirectory，可显著减少 SAF Binder 往返。
     */
    private class SafScanSession(private val context: Context, private val treeUri: Uri) {
        private val resolver = context.contentResolver
        private val childrenCache = HashMap<String, List<SafNode>>()
        var queryFailed: Boolean = false
            private set

        fun root(): SafNode? {
            val documentId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
                ?: return null
            val uri = runCatching {
                DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
            }.getOrNull() ?: return null
            val name = queryDisplayName(uri)
                ?: documentId.substringAfterLast('/').substringAfterLast(':').ifBlank { localizedText(context, R.string.scan_unnamed_directory) }
            return SafNode(uri, documentId, name, isDirectory = true)
        }

        fun children(dir: SafNode): List<SafNode> = childrenCache.getOrPut(dir.documentId) {
            val childrenUri = runCatching {
                DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, dir.documentId)
            }.getOrElse { return@getOrPut emptyList() }
            runCatching {
                val cursor = resolver.query(childrenUri, SAF_PROJECTION, null, null, null)
                if (cursor == null) {
                    queryFailed = true
                    return@runCatching emptyList()
                }
                cursor.use {
                    buildList {
                        while (it.moveToNext()) {
                            val documentId = it.getString(0) ?: continue
                            val name = it.getString(1)
                                ?: documentId.substringAfterLast('/').substringAfterLast(':')
                            val mimeType = it.getString(2)
                            val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
                            add(
                                SafNode(
                                    uri = uri,
                                    documentId = documentId,
                                    name = name,
                                    isDirectory = mimeType == DocumentsContract.Document.MIME_TYPE_DIR,
                                )
                            )
                        }
                    }
                }
            }.onFailure { error ->
                queryFailed = true
                Log.w(TAG, "Unable to query SAF directory ${dir.uri}", error)
            }.getOrDefault(emptyList())
        }

        private fun queryDisplayName(uri: Uri): String? = runCatching {
            resolver.query(
                uri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull()

        fun readText(node: SafNode, maxBytes: Int = 64 * 1024): String? = runCatching {
            resolver.openInputStream(node.uri)?.use { input ->
                val buffer = ByteArray(maxBytes)
                val count = input.read(buffer)
                if (count <= 0) "" else String(buffer, 0, count, Charsets.UTF_8)
            }
        }.getOrNull()
    }

    private data class SafNode(
        val uri: Uri,
        val documentId: String,
        val name: String,
        val isDirectory: Boolean,
    )

    private val SAF_PROJECTION = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
    )

    fun applyLocalCover(context: Context, game: ScanGame): ScanGame {
        if (!game.coverUri.isNullOrBlank()) return game
        val dir = DocumentFile.fromTreeUri(context.applicationContext, Uri.parse(game.uri)) ?: return game
        val coverUri = findLocalCoverUri(dir.listFiles())
        return if (coverUri.isNullOrBlank()) game else game.copy(
            coverUri = coverUri,
            coverSource = AppSettingsStore.COVER_SOURCE_LOCAL,
        )
    }

    private fun findLocalCoverUri(children: Array<DocumentFile>): String? {
        return LOCAL_COVER_NAMES.firstNotNullOfOrNull { expected ->
            children.firstOrNull { child ->
                !child.isDirectory && child.name.equals(expected, ignoreCase = true)
            }?.uri?.toString()
        }
    }

    private fun findLocalCoverUri(children: List<SafNode>): String? {
        return LOCAL_COVER_NAMES.firstNotNullOfOrNull { expected ->
            children.firstOrNull { child ->
                !child.isDirectory && child.name.equals(expected, ignoreCase = true)
            }?.uri?.toString()
        }
    }

    private fun detectRenpyVersionIfNeeded(
        detection: Detection,
        children: List<SafNode>,
        session: SafScanSession,
    ): String? {
        if (detection.engine != EngineType.RENPY) return null
        val gameDir = children.firstOrNull { it.isDirectory && it.name.equals("game", ignoreCase = true) }
        val gameChildren = gameDir?.let(session::children).orEmpty()
        val scriptVersionTxt = gameChildren
            .firstOrNull { !it.isDirectory && it.name.equals("script_version.txt", ignoreCase = true) }
            ?.let(session::readText)
        val scriptVersionRpy = gameChildren
            .firstOrNull { !it.isDirectory && it.name.equals("script_version.rpy", ignoreCase = true) }
            ?.let(session::readText)
        val libDir = children.firstOrNull { it.isDirectory && it.name.equals("lib", ignoreCase = true) }
        val hasPython27 = libDir?.let(session::children)
            ?.any { it.name.equals("pythonlib2.7", ignoreCase = true) } == true
        return RenPyVersionDetector.detect(scriptVersionTxt, scriptVersionRpy, hasPython27)
    }

    private fun detectRenpyVersionIfNeeded(detection: Detection, dir: File): String? {
        if (detection.engine != EngineType.RENPY) return null
        return RenPyVersionDetector.detect(dir)
    }

    private class FileScanSession {
        private val childrenCache = HashMap<String, Array<File>>()

        fun children(dir: File): Array<File> = childrenCache.getOrPut(dir.absolutePath) {
            dir.listFiles() ?: emptyArray()
        }
    }

    private fun scanRootIncrementalFile(
        context: Context,
        session: FileScanSession,
        dir: File,
        level: Int,
        maxDepth: Int,
        known: HashSet<String>,
        out: MutableList<ScanGame>,
    ) {
        if (level > maxDepth || !dir.isDirectory) return
        if (dir.absolutePath in known) return
        val children = session.children(dir)

        val detected = detectEngine(dir, session)
        if (detected.engine != EngineType.UNKNOWN) {
            val coverUri = findLocalCoverUri(children)
            out.add(
                ScanGame(
                    title = dir.name.takeIf { it.isNotBlank() } ?: localizedText(context, R.string.scan_unnamed_game),
                    uri = dir.absolutePath,
                    engine = detected.engine,
                    launchTarget = detected.launchTarget,
                    externalModuleAlias = detected.externalModuleAlias,
                    detectedRenpyVersion = detectRenpyVersionIfNeeded(detected, dir),
                    coverUri = coverUri,
                    coverSource = if (coverUri.isNullOrBlank()) null else AppSettingsStore.COVER_SOURCE_LOCAL,
                )
            )
            return
        }
        children.filter { it.isDirectory }.forEach { child ->
            scanRootIncrementalFile(context, session, child, level + 1, maxDepth, known, out)
        }
    }

    private fun traverseFileDirectories(
        context: Context,
        session: FileScanSession,
        dir: File,
        level: Int,
        maxDepth: Int,
        out: MutableList<ScanGame>,
    ) {
        if (level > maxDepth || !dir.isDirectory) return
        val children = session.children(dir)

        val detected = detectEngine(dir, session)
        if (detected.engine != EngineType.UNKNOWN) {
            val coverUri = findLocalCoverUri(children)
            out.add(
                ScanGame(
                    title = dir.name.takeIf { it.isNotBlank() } ?: localizedText(context, R.string.scan_unnamed_game),
                    uri = dir.absolutePath,
                    engine = detected.engine,
                    launchTarget = detected.launchTarget,
                    externalModuleAlias = detected.externalModuleAlias,
                    detectedRenpyVersion = detectRenpyVersionIfNeeded(detected, dir),
                    coverUri = coverUri,
                    coverSource = if (coverUri.isNullOrBlank()) null else AppSettingsStore.COVER_SOURCE_LOCAL,
                )
            )
            return
        }
        children.filter { it.isDirectory }.forEach { child ->
            traverseFileDirectories(context, session, child, level + 1, maxDepth, out)
        }
    }

    private fun findLocalCoverUri(children: Array<File>): String? {
        return LOCAL_COVER_NAMES.firstNotNullOfOrNull { expected ->
            children.firstOrNull { child ->
                child.isFile && child.name.equals(expected, ignoreCase = true)
            }?.let { Uri.fromFile(it).toString() }
        }
    }

    private val LOCAL_COVER_NAMES = listOf(
        "cover.jpg",
        "cover.png",
        "cover.webp",
        "cover.jpeg",
        "cover.bmp",
        "icon.png",
    )

    // ============ 引擎识别（移植自 EngineDetector） ============

    data class Detection(
        val engine: EngineType,
        val confidence: Int,
        val launchTarget: String,
        val externalModuleAlias: String? = null,
    )

    fun detectEngine(dir: DocumentFile): Detection {
        if (!dir.isDirectory) return UNKNOWN_DETECTION
        return detectEngine(
            children = dir.listFiles().asIterable(),
            nameOf = { it.name.orEmpty() },
            isDirectory = { it.isDirectory },
            childrenOf = { it.listFiles().asIterable() },
        )
    }

    fun detectEngine(dir: File): Detection {
        if (!dir.isDirectory) return UNKNOWN_DETECTION
        return detectEngine(dir, FileScanSession())
    }

    private fun detectEngine(dir: File, session: FileScanSession): Detection = detectEngine(
        children = session.children(dir).asIterable(),
        nameOf = { it.name },
        isDirectory = { it.isDirectory },
        childrenOf = { session.children(it).asIterable() },
    )

    private fun detectEngine(
        children: List<SafNode>,
        childrenOf: (SafNode) -> List<SafNode>,
    ): Detection = detectEngine(
        children = children,
        nameOf = { it.name },
        isDirectory = { it.isDirectory },
        childrenOf = childrenOf,
    )

    private fun <T> detectEngine(
        children: Iterable<T>,
        nameOf: (T) -> String,
        isDirectory: (T) -> Boolean,
        childrenOf: (T) -> Iterable<T>,
    ): Detection {

        val xp3Files = mutableListOf<String>()
        var hasStartupTjs = false
        var hasConfigTjs = false
        var hasIndex = false
        var hasAppAsar = false
        var hasTyranoDir = false
        var hasRpgMvCore = false
        var hasRpgMzCore = false
        var hasVnData = false
        var hasSystemIni = false
        var hasFirstIet = false
        var hasBootIni = false
        var hasRootPfs = false
        var hasPatchPfs = false
        var hasAnyPfs = false
        var hasObbLikeFile = false
        var hasOnsScript = false
        var hasOnsArchive = false
        var hasRenpyDir = false
        var hasGameDir = false
        var hasRpa = false
        var hasRpy = false
        var hasRpyc = false
        var hasGameScriptRpy = false
        var hasGameOptionsRpy = false
        var firstRgssad: String? = null
        var firstRgss2a: String? = null
        var firstRgss3a: String? = null
        var hasGameIni = false
        var hasRxdata = false
        var hasRvdata = false
        var hasRvdata2 = false
        var hasMkxpZRubyRuntime = false

        fun collect(entry: T, rel: String) {
            val lower = nameOf(entry).lowercase(Locale.ROOT)
            if (lower.isEmpty()) return
            val childRel = if (rel.isEmpty()) lower else "$rel/$lower"
            if (isDirectory(entry)) {
                if (lower == "tyrano") hasTyranoDir = true
                if (lower == "renpy") hasRenpyDir = true
                if (lower == "game") hasGameDir = true
                if (lower == "app.asar" || childRel.endsWith("/app.asar")) hasAppAsar = true
                if (lower in ENGINE_SEARCH_DIRECTORIES) {
                    childrenOf(entry).forEach { collect(it, childRel) }
                }
                return
            }
            when {
                lower == "index.html" || lower == "index.htm" -> hasIndex = true
                childRel == "js/rpg_core.js" || childRel.endsWith("/js/rpg_core.js") -> hasRpgMvCore = true
                childRel == "js/rmmz_core.js" || childRel.endsWith("/js/rmmz_core.js") -> hasRpgMzCore = true
                lower == "globaldata.vndata" -> hasVnData = true
                lower == "game.ini" -> hasGameIni = true
                lower == "app.asar" || childRel.endsWith("/app.asar") -> hasAppAsar = true
                lower == "startup.tjs" -> hasStartupTjs = true
                lower == "config.tjs" -> hasConfigTjs = true
                lower == "boot.ini" -> hasBootIni = true
                lower == "system.ini" -> hasSystemIni = true
                childRel == "system/first.iet" || childRel.endsWith("/system/first.iet") -> hasFirstIet = true
                lower == "root.pfs" -> hasRootPfs = true
                lower == "root.pfs" || PFS_PATCH_NAME_RE.matches(lower) -> hasPatchPfs = hasPatchPfs || lower != "root.pfs"
                lower.endsWith(".pfs") || PFS_PATCH_NAME_RE.matches(lower) -> hasAnyPfs = true
                lower.endsWith(".obb") || OBB_NAME_RE.matches(lower) -> hasObbLikeFile = true
                lower == "0.txt" || lower == "00.txt" || lower == "nscript.dat" ||
                    lower == "onscript.nt2" || lower == "onscript.nt3" -> hasOnsScript = true
                lower.endsWith(".nsa") || lower.endsWith(".sar") -> hasOnsArchive = true
                lower.endsWith(".xp3") -> xp3Files.add(childRel)
                lower.endsWith(".rgssad") -> if (firstRgssad == null) firstRgssad = childRel
                lower.endsWith(".rgss2a") -> if (firstRgss2a == null) firstRgss2a = childRel
                lower.endsWith(".rgss3a") -> if (firstRgss3a == null) firstRgss3a = childRel
                rel.isEmpty() && lower.startsWith("x64-msvcrt-ruby") && lower.endsWith(".dll") ->
                    hasMkxpZRubyRuntime = true
                childRel.startsWith("data/") && lower.endsWith(".rxdata") -> hasRxdata = true
                childRel.startsWith("data/") && lower.endsWith(".rvdata") -> hasRvdata = true
                childRel.startsWith("data/") && lower.endsWith(".rvdata2") -> hasRvdata2 = true
                lower.endsWith(".rpa") -> hasRpa = true
                lower.endsWith(".rpy") -> {
                    hasRpy = true
                    if (childRel == "game/script.rpy" || childRel.endsWith("/game/script.rpy")) {
                        hasGameScriptRpy = true
                    }
                    if (childRel == "game/options.rpy" || childRel.endsWith("/game/options.rpy")) {
                        hasGameOptionsRpy = true
                    }
                }
                lower.endsWith(".rpyc") -> hasRpyc = true
            }
        }
        children.forEach { collect(it, "") }

        if ((hasSystemIni && hasFirstIet) || hasRootPfs || hasPatchPfs || hasAnyPfs || (hasBootIni && hasObbLikeFile)) {
            return Detection(
                EngineType.ARTEMIS,
                if ((hasSystemIni && hasFirstIet) || hasRootPfs || (hasBootIni && hasObbLikeFile)) 95 else 90,
                LAUNCH_TARGET_GAME_DIR,
            )
        }
        if (hasIndex && hasTyranoDir) {
            return Detection(EngineType.TYRANO, 95, LAUNCH_TARGET_GAME_DIR)
        }
        if (hasIndex && hasRpgMvCore) {
            return Detection(EngineType.RPG_MV, 95, LAUNCH_TARGET_GAME_DIR)
        }
        if (hasIndex && hasRpgMzCore) {
            return Detection(EngineType.RPG_MZ, 95, LAUNCH_TARGET_GAME_DIR)
        }
        if (hasIndex && hasVnData) {
            return Detection(EngineType.VN, 90, LAUNCH_TARGET_GAME_DIR)
        }
        if (hasAppAsar) {
            return Detection(EngineType.TYRANO, 80, LAUNCH_TARGET_GAME_DIR)
        }
        firstRgss3a?.let {
            return Detection(EngineType.RPGMAKER, 96, it, "internal.rpgmvxace")
        }
        firstRgss2a?.let {
            return Detection(EngineType.RPGMAKER, 96, it, "internal.rpgmvx")
        }
        if (hasGameIni && hasRvdata2) {
            return Detection(EngineType.RPGMAKER, 92, LAUNCH_TARGET_GAME_DIR, "internal.rpgmvxace")
        }
        if (hasGameIni && hasRvdata) {
            return Detection(EngineType.RPGMAKER, 92, LAUNCH_TARGET_GAME_DIR, "internal.rpgmvx")
        }
        if (hasMkxpZRubyRuntime) {
            return Detection(EngineType.RPGMAKER, 92, LAUNCH_TARGET_GAME_DIR, "internal.mkxp-z")
        }
        firstRgssad?.let {
            return Detection(EngineType.RPGMAKER, 96, it, "internal.rpgmxp")
        }
        if (hasGameIni && hasRxdata) {
            return Detection(EngineType.RPGMAKER, 92, LAUNCH_TARGET_GAME_DIR, "internal.rpgmxp")
        }
        if (hasRpa || hasGameScriptRpy || hasGameOptionsRpy || (hasRenpyDir && (hasRpy || hasRpyc)) || (hasGameDir && hasRpy)) {
            val confidence = when {
                hasRpa -> 96
                hasGameScriptRpy || hasGameOptionsRpy -> 94
                hasRenpyDir && (hasRpy || hasRpyc) -> 90
                else -> 85
            }
            // Ren'Py 版本由单游戏设置选择，扫描不写死版本别名（避免误导为固定 8.5 模块）
            return Detection(EngineType.RENPY, confidence, LAUNCH_TARGET_GAME_DIR)
        }
        if (hasIndex) {
            return Detection(EngineType.WEB_OTHER, 70, LAUNCH_TARGET_GAME_DIR)
        }
        if (xp3Files.isNotEmpty() || hasStartupTjs || hasConfigTjs) {
            return Detection(EngineType.KIRIKIRI, if (xp3Files.isNotEmpty()) 95 else 80, xp3Files.firstOrNull() ?: LAUNCH_TARGET_GAME_DIR)
        }
        if (hasOnsScript || hasOnsArchive) {
            return Detection(EngineType.ONS, if (hasOnsScript) 90 else 70, LAUNCH_TARGET_GAME_DIR)
        }
        return UNKNOWN_DETECTION
    }

    const val LAUNCH_TARGET_GAME_DIR = "DIR"

    private val UNKNOWN_DETECTION = Detection(EngineType.UNKNOWN, 0, "")

    private fun localizedText(context: Context, stringRes: Int): String =
        AppLocaleController.wrap(context.applicationContext).getString(stringRes)

    private val ENGINE_SEARCH_DIRECTORIES = setOf(
        "data",
        "tyrano",
        "scenario",
        "system",
        "app",
        "game",
        "renpy",
        "resources",
        "app.asar",
        "www",
        "js",
    )
}
