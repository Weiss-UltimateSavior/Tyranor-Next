package com.tyranor.next.core.game.scan

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.game.model.ScannedRoot
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
 * 支持引擎：Kirikiri、ONS、Tyrano、RPG Maker MV/MZ、VN、WebOther、Artemis。
 */
object EngineScanner {

    private const val TAG = "EngineScanner"

    private const val PREFS = "game_scanner"
    private const val KEY_ROOTS = "scan_roots"      // uri 按换行分隔
    private const val KEY_GAMES = "scan_games"      // 已有游戏 entry，按行；每行字段用 \u0001 分隔
    private const val KEY_RECENT_GAMES = "recent_games"
    private const val KEY_QUICK_LAUNCH = "quick_launch" // 首页快捷启动（最多 3 个）

    // 主页面会在 Tab 动画中反复进入组合。将已解析的数据保留在进程内，避免每次切页都在
    // 主线程重新读取 SharedPreferences、split 字符串并构造完整游戏列表。
    private val cacheLock = Any()
    @Volatile
    private var gamesCache: List<ScanGame>? = null
    @Volatile
    private var recentGamesCache: List<ScanGame>? = null
    @Volatile
    private var quickLaunchCache: List<ScanGame>? = null

    // 快捷启动版本号：任何增删/刷新后自增，供首页实时感知改动后重新加载
    private val _quickLaunchRevision = MutableStateFlow(0)
    val quickLaunchRevision: StateFlow<Int> = _quickLaunchRevision.asStateFlow()

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

    fun saveGames(context: Context, games: List<ScanGame>) {
        synchronized(cacheLock) {
            saveGamesLocked(context, games.toList())
        }
    }

    fun loadGames(context: Context): List<ScanGame> =
        gamesCache ?: synchronized(cacheLock) {
            gamesCache ?: loadList(context, KEY_GAMES).also { gamesCache = it }
        }

    fun updateGames(context: Context, transform: (List<ScanGame>) -> List<ScanGame>): List<ScanGame> =
        synchronized(cacheLock) {
            val current = gamesCache ?: loadList(context, KEY_GAMES).also { gamesCache = it }
            val updated = transform(current).toList()
            saveGamesLocked(context, updated)
            updated
        }

    private fun saveGamesLocked(context: Context, games: List<ScanGame>) {
        gamesCache = games
        saveList(context, KEY_GAMES, games)
    }

    fun recordRecentGame(context: Context, game: ScanGame) {
        val touched = game.copy(openTime = System.currentTimeMillis())
        updateRecentGames(context) { current ->
            (listOf(touched) + current.filterNot { it.uri == game.uri }).take(20)
        }
    }

    fun loadRecentGames(context: Context): List<ScanGame> =
        recentGamesCache ?: synchronized(cacheLock) {
            recentGamesCache ?: loadList(context, KEY_RECENT_GAMES).also { recentGamesCache = it }
        }

    /** 删除游戏时从最近游戏列表中移除对应条目。 */
    fun removeRecentGame(context: Context, uri: String) {
        updateRecentGames(context) { games -> games.filterNot { it.uri == uri } }
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

    internal fun saveRecentGames(context: Context, games: List<ScanGame>) {
        synchronized(cacheLock) {
            saveRecentGamesLocked(context, games.toList())
        }
    }

    internal fun updateRecentGames(
        context: Context,
        transform: (List<ScanGame>) -> List<ScanGame>,
    ): List<ScanGame> = synchronized(cacheLock) {
        val current = recentGamesCache ?: loadList(context, KEY_RECENT_GAMES).also { recentGamesCache = it }
        val updated = transform(current).toList()
        if (updated != current) saveRecentGamesLocked(context, updated)
        updated
    }

    private fun saveRecentGamesLocked(context: Context, games: List<ScanGame>) {
        recentGamesCache = games
        saveList(context, KEY_RECENT_GAMES, games)
    }

    // ============ 首页快捷启动（最多 3 个） ============

    fun loadQuickLaunch(context: Context): List<ScanGame> =
        quickLaunchCache ?: synchronized(cacheLock) {
            quickLaunchCache ?: loadList(context, KEY_QUICK_LAUNCH).also { quickLaunchCache = it }
        }

    fun isQuickLaunched(context: Context, uri: String): Boolean =
        loadQuickLaunch(context).any { it.uri == uri }

    /** 加入快捷启动。已存在视为成功；槽位满 3 个返回 false。 */
    fun addQuickLaunch(context: Context, game: ScanGame): Boolean {
        val current = loadQuickLaunch(context)
        if (current.any { it.uri == game.uri }) return true
        if (current.size >= 3) return false
        saveQuickLaunch(context, current + game)
        return true
    }

    fun removeQuickLaunch(context: Context, uri: String) {
        saveQuickLaunch(context, loadQuickLaunch(context).filterNot { it.uri == uri })
    }

    /**
     * 用主游戏库最新数据刷新快捷启动快照（游戏页修改封面等后首页实时同步），并回写存储。
     * 已从库中删除的游戏保留原快照（不主动移除）。
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
        saveList(context, KEY_QUICK_LAUNCH, snapshot)
        _quickLaunchRevision.value++
    }

    // ---------- 通用存取助手 ----------

    private fun saveList(context: Context, key: String, games: List<ScanGame>) {
        val str = games.joinToString("\n") { serializeGame(it) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(key, str).apply()
    }

    private fun loadList(context: Context, key: String): List<ScanGame> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(key, null) ?: return emptyList()
        return raw.split("\n").mapNotNull { parseGame(it) }
    }

    private fun serializeGame(g: ScanGame): String {
        // 标题/元数据可能来自 VNDB，含 \n 或 \u0001 会把整个持久化文件解析错乱，需清洗。
        fun clean(s: String): String = s.replace("\n", " ").replace("\u0001", " ")
        return listOf(
            clean(g.title),
            g.uri,
            g.engine.name,
            g.launchTarget,
            g.coverUri.orEmpty(),
            g.vndbId.orEmpty(),
            clean(g.metadataTitle.orEmpty()),
            g.launchFile.orEmpty(),
            g.openTime.toString(),
            g.coverSource.orEmpty(),
        ).joinToString("\u0001")
    }

    private fun parseGame(line: String): ScanGame? {
        val p = line.split("\u0001")
        if (p.size < 3) return null
        return ScanGame(
            title = p[0],
            uri = p[1],
            engine = runCatching { EngineType.valueOf(p[2]) }.getOrDefault(EngineType.UNKNOWN),
            launchTarget = p.getOrElse(3) { "" },
            coverUri = p.getOrElse(4) { "" }.takeIf { it.isNotBlank() },
            vndbId = p.getOrElse(5) { "" }.takeIf { it.isNotBlank() },
            metadataTitle = p.getOrElse(6) { "" }.takeIf { it.isNotBlank() },
            launchFile = p.getOrElse(7) { "" }.takeIf { it.isNotBlank() },
            openTime = p.getOrElse(8) { "" }.toLongOrNull() ?: 0,
            coverSource = p.getOrElse(9) { "" }.takeIf { it.isNotBlank() },
        )
    }

    // ============ 扫描根目录持久化 ============

    fun saveRoot(context: Context, uri: Uri): List<String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existing = loadRoots(context).toMutableList()
        val key = uri.toString()
        if (!existing.contains(key)) existing.add(key)
        prefs.edit().putString(KEY_ROOTS, existing.joinToString("\n")).apply()
        return existing
    }

    fun removeRoot(context: Context, uri: Uri) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existing = loadRoots(context).filterNot { it == uri.toString() }
        prefs.edit().putString(KEY_ROOTS, existing.joinToString("\n")).apply()
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
        saveRecentGames(context, loadRecentGames(context).filterNot { it.uri in removedUris })
        saveQuickLaunch(context, loadQuickLaunch(context).filterNot { it.uri in removedUris })
    }

    fun loadRoots(context: Context): List<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ROOTS, null)
            ?.split("\n")
            ?.filter { it.isNotBlank() }
            ?: emptyList()

    private fun isGameUnderRoot(rootUriText: String, gameUriText: String): Boolean {
        val rootPath = normalizePath(safUriToPath(rootUriText))
        val gamePath = normalizePath(safUriToPath(gameUriText) ?: uriFilePath(gameUriText))
        if (rootPath != null && gamePath != null && isSameOrChildPath(rootPath, gamePath)) return true

        val rootDocId = documentId(rootUriText) ?: return false
        val gameDocId = documentId(gameUriText) ?: return false
        return gameDocId == rootDocId || gameDocId.startsWith("${rootDocId.trimEnd('/')}/")
    }

    private fun documentId(uriText: String): String? = runCatching {
        val uri = Uri.parse(uriText)
        DocumentsContract.getDocumentId(uri)
    }.getOrNull() ?: runCatching {
        DocumentsContract.getTreeDocumentId(Uri.parse(uriText))
    }.getOrNull()

    private fun uriFilePath(uriText: String): String? = runCatching {
        val uri = Uri.parse(uriText)
        if (uri.scheme.equals("file", ignoreCase = true)) uri.path else null
    }.getOrNull() ?: uriText.takeIf { it.startsWith("/") }

    private fun normalizePath(path: String?): String? =
        path?.replace('\\', '/')?.trimEnd('/')?.takeIf { it.isNotBlank() }

    private fun isSameOrChildPath(rootPath: String, gamePath: String): Boolean =
        gamePath == rootPath || gamePath.startsWith("$rootPath/")

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
        val refreshed = updateGames(context) { currentGames ->
            val existingByUri = currentGames.associateBy { it.uri }
            scanned.map { current ->
                existingByUri[current.uri]?.let { previous ->
                    current.copy(
                        coverUri = previous.coverUri ?: current.coverUri,
                        coverSource = previous.coverSource
                            ?: current.coverSource?.takeIf {
                                previous.coverUri == null || previous.coverUri == current.coverUri
                            },
                        vndbId = previous.vndbId,
                        metadataTitle = previous.metadataTitle,
                        launchFile = previous.launchFile,
                        openTime = previous.openTime,
                    )
                } ?: current
            }
        }
        val validUris = refreshed.mapTo(HashSet()) { it.uri }
        saveRecentGames(context, loadRecentGames(context).filter { it.uri in validUris })
        saveQuickLaunch(context, loadQuickLaunch(context).filter { it.uri in validUris })
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
                scanRootIncremental(safSession, rootNode, 0, maxDepth, known, found)
            }
            // 只有 SAF 不可用时才走真实路径兜底；正常的“没有新游戏”不再重复扫描整棵目录树。
            if (found.size == beforeCount && (safRoot == null || safSession.queryFailed)) safUriToPath(root)?.let { path ->
                scanRootIncrementalFile(FileScanSession(), File(path), 0, maxDepth, known, found)
            }
        }
        existing + found.filter { seen.add(it.uri) }
    }

    /** 增量遍历：目录已在库中（已知游戏）→ 剪枝；识别为新游戏 → 记录并停止下钻。 */
    private fun scanRootIncremental(
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
                    title = dir.name.takeIf { it.isNotBlank() } ?: "未命名游戏",
                    uri = dir.uri.toString(),
                    engine = detected.engine,
                    launchTarget = detected.launchTarget,
                    coverUri = coverUri,
                    coverSource = if (coverUri.isNullOrBlank()) null else AppSettingsStore.COVER_SOURCE_LOCAL,
                )
            )
            return
        }
        for (child in children) {
            if (child.isDirectory) {
                scanRootIncremental(session, child, level + 1, maxDepth, known, out)
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
            traverseDirectories(safSession, root, 0, maxDepth, results)
        }
        // SAF 成功但未发现游戏是正常结果，不重复用 File API 扫一遍。
        // 查询异常/权限失效时仍保留 SD 卡真实路径兼容兜底。
        if (results.isEmpty() && (safRoot == null || safSession.queryFailed)) safUriToPath(rootUriStr)?.let { path ->
            traverseFileDirectories(FileScanSession(), File(path), 0, maxDepth, results)
        }
        val seen = HashSet<String>()
        return results.filter { seen.add(it.uri) }
    }

    private fun traverseDirectories(
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
                    title = dir.name.takeIf { it.isNotBlank() } ?: "未命名游戏",
                    uri = dir.uri.toString(),
                    engine = detected.engine,
                    launchTarget = detected.launchTarget,
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
                traverseDirectories(session, child, level + 1, maxDepth, out)
            }
        }
    }

    /**
     * 一次 ContentResolver.query 取得一个目录的全部子项名称和类型。
     * 相比 DocumentFile.listFiles 后逐个读取 name/isDirectory，可显著减少 SAF Binder 往返。
     */
    private class SafScanSession(context: Context, private val treeUri: Uri) {
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
                ?: documentId.substringAfterLast('/').substringAfterLast(':').ifBlank { "未命名目录" }
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

    private class FileScanSession {
        private val childrenCache = HashMap<String, Array<File>>()

        fun children(dir: File): Array<File> = childrenCache.getOrPut(dir.absolutePath) {
            dir.listFiles() ?: emptyArray()
        }
    }

    private fun scanRootIncrementalFile(
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
                    title = dir.name.takeIf { it.isNotBlank() } ?: "未命名游戏",
                    uri = dir.absolutePath,
                    engine = detected.engine,
                    launchTarget = detected.launchTarget,
                    coverUri = coverUri,
                    coverSource = if (coverUri.isNullOrBlank()) null else AppSettingsStore.COVER_SOURCE_LOCAL,
                )
            )
            return
        }
        children.filter { it.isDirectory }.forEach { child ->
            scanRootIncrementalFile(session, child, level + 1, maxDepth, known, out)
        }
    }

    private fun traverseFileDirectories(
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
                    title = dir.name.takeIf { it.isNotBlank() } ?: "未命名游戏",
                    uri = dir.absolutePath,
                    engine = detected.engine,
                    launchTarget = detected.launchTarget,
                    coverUri = coverUri,
                    coverSource = if (coverUri.isNullOrBlank()) null else AppSettingsStore.COVER_SOURCE_LOCAL,
                )
            )
            return
        }
        children.filter { it.isDirectory }.forEach { child ->
            traverseFileDirectories(session, child, level + 1, maxDepth, out)
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

    data class Detection(val engine: EngineType, val confidence: Int, val launchTarget: String)

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
        var hasRootPfs = false
        var hasAnyPfs = false
        var hasOnsScript = false
        var hasOnsArchive = false
        var hasRenpyDir = false
        var hasGameDir = false
        var hasRpa = false
        var hasRpy = false
        var hasRpyc = false
        var hasGameScriptRpy = false
        var hasGameOptionsRpy = false

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
                lower == "app.asar" || childRel.endsWith("/app.asar") -> hasAppAsar = true
                lower == "startup.tjs" -> hasStartupTjs = true
                lower == "config.tjs" -> hasConfigTjs = true
                lower == "system.ini" -> hasSystemIni = true
                childRel == "system/first.iet" || childRel.endsWith("/system/first.iet") -> hasFirstIet = true
                lower == "root.pfs" -> hasRootPfs = true
                lower.endsWith(".pfs") -> hasAnyPfs = true
                lower == "0.txt" || lower == "00.txt" || lower == "nscript.dat" ||
                    lower == "onscript.nt2" || lower == "onscript.nt3" -> hasOnsScript = true
                lower.endsWith(".nsa") || lower.endsWith(".sar") -> hasOnsArchive = true
                lower.endsWith(".xp3") -> xp3Files.add(childRel)
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

        if ((hasSystemIni && hasFirstIet) || hasRootPfs || hasAnyPfs) {
            return Detection(EngineType.ARTEMIS, if ((hasSystemIni && hasFirstIet) || hasRootPfs) 95 else 90, "[游戏目录]")
        }
        if (hasIndex && hasTyranoDir) {
            return Detection(EngineType.TYRANO, 95, "[游戏目录]")
        }
        if (hasIndex && hasRpgMvCore) {
            return Detection(EngineType.RPG_MV, 95, "[游戏目录]")
        }
        if (hasIndex && hasRpgMzCore) {
            return Detection(EngineType.RPG_MZ, 95, "[游戏目录]")
        }
        if (hasIndex && hasVnData) {
            return Detection(EngineType.VN, 90, "[游戏目录]")
        }
        if (hasAppAsar) {
            return Detection(EngineType.TYRANO, 80, "[游戏目录]")
        }
        if (hasRpa || hasGameScriptRpy || hasGameOptionsRpy || (hasRenpyDir && (hasRpy || hasRpyc)) || (hasGameDir && hasRpy)) {
            val confidence = when {
                hasRpa -> 96
                hasGameScriptRpy || hasGameOptionsRpy -> 94
                hasRenpyDir && (hasRpy || hasRpyc) -> 90
                else -> 85
            }
            return Detection(EngineType.RENPY, confidence, "[游戏目录]")
        }
        if (hasIndex) {
            return Detection(EngineType.WEB_OTHER, 70, "[游戏目录]")
        }
        if (xp3Files.isNotEmpty() || hasStartupTjs || hasConfigTjs) {
            return Detection(EngineType.KIRIKIRI, if (xp3Files.isNotEmpty()) 95 else 80, xp3Files.firstOrNull() ?: "[游戏目录]")
        }
        if (hasOnsScript || hasOnsArchive) {
            return Detection(EngineType.ONS, if (hasOnsScript) 90 else 70, "[游戏目录]")
        }
        return UNKNOWN_DETECTION
    }

    private val UNKNOWN_DETECTION = Detection(EngineType.UNKNOWN, 0, "")

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
