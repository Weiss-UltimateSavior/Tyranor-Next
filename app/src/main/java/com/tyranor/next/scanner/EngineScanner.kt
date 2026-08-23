package com.tyranor.next.scanner

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.tyranor.next.settings.AppSettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.abs

/**
 * 精简版游戏扫描器，识别逻辑移植自 RinneMobile 的 EngineDetector/GameScanner。
 * 支持引擎：Kirikiri(kr/krkr2)、ONS、Tyrano(ty)、Artemis(ar)。
 */
object EngineScanner {

    private const val PREFS = "game_scanner"
    private const val KEY_ROOTS = "scan_roots"      // uri 按换行分隔
    private const val KEY_GAMES = "scan_games"      // 已有游戏 entry，按行；每行字段用 \u0001 分隔
    private const val KEY_RECENT_GAMES = "recent_games"
    private const val KEY_QUICK_LAUNCH = "quick_launch" // 首页快捷启动（最多 3 个）

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

    fun saveGames(context: Context, games: List<ScanGame>) = saveList(context, KEY_GAMES, games)

    fun loadGames(context: Context): List<ScanGame> = loadList(context, KEY_GAMES)

    fun recordRecentGame(context: Context, game: ScanGame) {
        val touched = game.copy(openTime = System.currentTimeMillis())
        val next = (listOf(touched) + loadRecentGames(context).filterNot { it.uri == game.uri }).take(20)
        saveRecentGames(context, next)
    }

    fun loadRecentGames(context: Context): List<ScanGame> = loadList(context, KEY_RECENT_GAMES)

    /** 删除游戏时从最近游戏列表中移除对应条目。 */
    fun removeRecentGame(context: Context, uri: String) {
        saveRecentGames(context, loadRecentGames(context).filterNot { it.uri == uri })
    }

    /** 从持久游戏库中移除指定游戏（在游戏页或首页删除游戏时调用，保证库与最近列表一致）。 */
    fun removeGame(context: Context, uri: String) {
        saveGames(context, loadGames(context).filterNot { it.uri == uri })
    }

/** 目录名 → 安全文件名（用于应用内镜像/独立存档目录），非法字符替换为下划线。 */
    fun safeSaveName(rootPath: String): String {
        val name = runCatching { File(rootPath).name.takeIf { it.isNotBlank() } }.getOrNull()
            ?: abs(rootPath.hashCode()).toString()
        return name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifEmpty { "default" }
    }

    /**
     * PSP 存档根推导：从游戏真实路径逐级向上查找名为 "PSP" 的祖先目录。
     * 候选包含游戏目录自身（镜像可直接放在 PSP 根部）；
     * 特征确认要求其下存在 SAVEDATA/GAME/GAMES/ISO 子目录，
     * 或任意 .iso/.cso/.pbp/.chd 镜像文件（全新部署尚无子目录时也能命中）。
     * 命中即返回该目录作为 memstick 根（存档位于 …/PSP/SAVEDATA）；找不到返回 null。
     */
    fun derivePspMemstick(gameRealPath: String): String? {
        var cur: File? = File(gameRealPath)
        while (cur != null) {
            // 从游戏目录自身开始逐级向上检查（镜像可能直接放在 PSP 根部）
            if (cur.name.equals("PSP", ignoreCase = true)) {
                val hasMarker = cur.listFiles()
                    ?.any { it.isDirectory && (it.name.equals("SAVEDATA", true) ||
                        it.name.equals("GAME", true) || it.name.equals("GAMES", true) ||
                        it.name.equals("ISO", true)) } == true
                if (hasMarker) return cur.absolutePath
                // 名为 PSP 但无特征子目录：继续向上找（可能是同名但非根的目录）
            }
            cur = cur.parentFile
        }
        return null
    }


internal fun saveRecentGames(context: Context, games: List<ScanGame>) =
        saveList(context, KEY_RECENT_GAMES, games)

    // ============ 首页快捷启动（最多 3 个） ============

    fun loadQuickLaunch(context: Context): List<ScanGame> = loadList(context, KEY_QUICK_LAUNCH)

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

    internal fun saveQuickLaunch(context: Context, games: List<ScanGame>) =
        saveList(context, KEY_QUICK_LAUNCH, games)

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

    /** 新扫描根与已有根是否指向同一物理目录或互为父子（用于提示避免重复扫描）。 */
    fun isDuplicateOrNestedRoot(context: Context, newUri: Uri): Boolean {
        val newPath = safUriToPath(newUri.toString())?.trimEnd('/') ?: return false
        if (newPath.isEmpty()) return false
        return loadRoots(context).any { existing ->
            val p = safUriToPath(existing)?.trimEnd('/') ?: return@any false
            val a = newPath; val b = p
            a == b || a.startsWith("$b/") || b.startsWith("$a/")
        }
    }

    fun removeRoot(context: Context, uri: Uri) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existing = loadRoots(context).filterNot { it == uri.toString() }
        prefs.edit().putString(KEY_ROOTS, existing.joinToString("\n")).apply()
    }

    fun removeRootAndGames(context: Context, uri: Uri) {
        removeRoot(context, uri)
        val root = uri.toString()
        val removedUris = loadGames(context)
            .filter { isGameUnderRoot(root, it.uri) }
            .mapTo(HashSet()) { it.uri }
        if (removedUris.isEmpty()) return
        saveGames(context, loadGames(context).filterNot { it.uri in removedUris })
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

    /** 去重键：优先用解析后的真实物理路径，同一目录经不同扫描根会得到不同 SAF URI，但真实路径相同。 */
    private fun dedupKey(context: Context, game: ScanGame): String {
        return safUriToPath(game.uri) ?: game.uri
    }

    /** 全量扫描所有根目录（结果以本次扫描为准，用于首次/无数据场景）。 */
    suspend fun scanAll(context: Context): List<ScanGame> = withContext(Dispatchers.IO) {
        val all = mutableListOf<ScanGame>()
        val maxDepth = AppSettingsStore.getScanDepth(context)
        loadRoots(context).forEach { root ->
            all += scanRoot(context, root, maxDepth)
        }
        val seen = mutableSetOf<String>()
        all.filter { seen.add(dedupKey(context, it)) }
    }

    /** 全量刷新游戏库：以当前扫描结果为准，移除已删除/改名路径的旧缓存条目。 */
    suspend fun rescanLibrary(context: Context): List<ScanGame> = withContext(Dispatchers.IO) {
        val existingByUri = loadGames(context).associateBy { it.uri }
        val scanned = scanAll(context)
        val refreshed = scanned.map { current ->
            existingByUri[current.uri]?.let { previous ->
                current.copy(
                    coverUri = previous.coverUri ?: current.coverUri,
                    vndbId = previous.vndbId,
                    metadataTitle = previous.metadataTitle,
                    launchFile = previous.launchFile,
                    openTime = previous.openTime,
                )
            } ?: current
        }
        saveGames(context, refreshed)
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
        // 已知游戏同时按 uri 与真实路径记录，避免同一目录经不同扫描根重复入库
        val known = HashSet<String>()
        val seen = HashSet<String>()
        existing.forEach {
            known.add(it.uri)
            safUriToPath(it.uri)?.let(known::add)
            // 已有条目也占住去重键：新根下扫到的同一物理目录不再重复入库
            seen.add(dedupKey(context, it))
        }
        val found = mutableListOf<ScanGame>()
        val maxDepth = AppSettingsStore.getScanDepth(context)
        loadRoots(context).forEach { root ->
            val beforeCount = found.size
            val rootUri = Uri.parse(root)
            val rootDir = DocumentFile.fromTreeUri(context.applicationContext, rootUri)
            if (rootDir != null) {
                scanRootIncremental(context.applicationContext, rootDir, 0, maxDepth, known, found)
            }
            if (found.size == beforeCount) safUriToPath(root)?.let { path ->
                scanRootIncrementalFile(File(path), 0, maxDepth, known, found)
            }
        }
        existing + found.filter { seen.add(dedupKey(context, it)) }
    }

    /** 增量遍历：目录已在库中（已知游戏）→ 剪枝；识别为新游戏 → 记录并停止下钻。 */
    private fun scanRootIncremental(
        context: Context,
        dir: DocumentFile,
        level: Int,
        maxDepth: Int,
        known: HashSet<String>,
        out: MutableList<ScanGame>,
    ) {
        if (level > maxDepth) return
        if (dir.uri.toString() in known) return
        val children = dir.listFiles()

        val detected = detectEngine(dir)
        if (detected.engine != EngineType.UNKNOWN) {
            val coverUri = findLocalCoverUri(children)
            out.add(
                ScanGame(
                    title = dir.name?.takeIf { it.isNotBlank() } ?: "未命名游戏",
                    uri = dir.uri.toString(),
                    engine = detected.engine,
                    launchTarget = detected.launchTarget,
                    coverUri = coverUri,
                )
            )
            return
        }
        for (child in children) {
            if (child.isDirectory) {
                scanRootIncremental(context, child, level + 1, maxDepth, known, out)
            }
        }
    }

    suspend fun scanRoot(context: Context, rootUriStr: String, maxDepth: Int = 3): List<ScanGame> = withContext(Dispatchers.IO) {
        val rootUri = Uri.parse(rootUriStr)
        val root = DocumentFile.fromTreeUri(context.applicationContext, rootUri)
        val results = mutableListOf<ScanGame>()
        if (root != null && root.isDirectory) {
            // 深度优先遍历子目录，识别每个候选游戏目录（深度由应用设置「扫描深度」控制）
            traverseDirectories(context.applicationContext, root, 0, maxDepth, results)
        }
        if (results.isEmpty()) safUriToPath(rootUriStr)?.let { path ->
            traverseFileDirectories(File(path), 0, maxDepth, results)
        }
        val seen = HashSet<String>()
        results.filter { seen.add(it.uri) }
    }

    private fun traverseDirectories(
        context: Context,
        dir: DocumentFile,
        level: Int,
        maxDepth: Int,
        out: MutableList<ScanGame>,
    ) {
        if (level > maxDepth) return
        val children = dir.listFiles()

        // 1) 本级目录本身可能是游戏（含引擎特征文件）
        val detected = detectEngine(dir)
        if (detected.engine != EngineType.UNKNOWN) {
            val coverUri = findLocalCoverUri(children)
            out.add(
                ScanGame(
                    title = dir.name?.takeIf { it.isNotBlank() } ?: "未命名游戏",
                    uri = dir.uri.toString(),
                    engine = detected.engine,
                    launchTarget = detected.launchTarget,
                    coverUri = coverUri,
                )
            )
            // 已识别为游戏，其子目录多为引擎内部资源，仅扫描直接文件层，不再深挖
            return
        }

        // 2) 否则递归子目录
        for (child in children) {
            if (child.isDirectory) {
                traverseDirectories(context, child, level + 1, maxDepth, out)
            }
        }
    }

    fun applyLocalCover(context: Context, game: ScanGame): ScanGame {
        if (!game.coverUri.isNullOrBlank()) return game
        val dir = DocumentFile.fromTreeUri(context.applicationContext, Uri.parse(game.uri)) ?: return game
        val coverUri = findLocalCoverUri(dir.listFiles())
        return if (coverUri.isNullOrBlank()) game else game.copy(coverUri = coverUri)
    }

    private fun findLocalCoverUri(children: Array<DocumentFile>): String? {
        return LOCAL_COVER_NAMES.firstNotNullOfOrNull { expected ->
            children.firstOrNull { child ->
                !child.isDirectory && child.name.equals(expected, ignoreCase = true)
            }?.uri?.toString()
        }
    }

    private fun scanRootIncrementalFile(
        dir: File,
        level: Int,
        maxDepth: Int,
        known: HashSet<String>,
        out: MutableList<ScanGame>,
    ) {
        if (level > maxDepth || !dir.isDirectory) return
        if (dir.absolutePath in known) return
        val children = dir.listFiles() ?: return

        val detected = detectEngine(dir)
        if (detected.engine != EngineType.UNKNOWN) {
            out.add(
                ScanGame(
                    title = dir.name.takeIf { it.isNotBlank() } ?: "未命名游戏",
                    uri = dir.absolutePath,
                    engine = detected.engine,
                    launchTarget = detected.launchTarget,
                    coverUri = findLocalCoverUri(children),
                )
            )
            return
        }
        children.filter { it.isDirectory }.forEach { child ->
            scanRootIncrementalFile(child, level + 1, maxDepth, known, out)
        }
    }

    private fun traverseFileDirectories(
        dir: File,
        level: Int,
        maxDepth: Int,
        out: MutableList<ScanGame>,
    ) {
        if (level > maxDepth || !dir.isDirectory) return
        val children = dir.listFiles() ?: return

        val detected = detectEngine(dir)
        if (detected.engine != EngineType.UNKNOWN) {
            out.add(
                ScanGame(
                    title = dir.name.takeIf { it.isNotBlank() } ?: "未命名游戏",
                    uri = dir.absolutePath,
                    engine = detected.engine,
                    launchTarget = detected.launchTarget,
                    coverUri = findLocalCoverUri(children),
                )
            )
            return
        }
        children.filter { it.isDirectory }.forEach { child ->
            traverseFileDirectories(child, level + 1, maxDepth, out)
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
        val r = Detection(EngineType.UNKNOWN, 0, "")
        if (!dir.isDirectory) return r
        val children = dir.listFiles()

        val names = HashSet<String>()            // 小写名
        val xp3Files = mutableListOf<String>()
        val pspFiles = mutableListOf<String>()
        var hasStartupTjs = false
        var hasConfigTjs = false
        var hasIndex = false
        var hasAppAsar = false
        var hasTyranoDir = false
        var hasSystemIni = false
        var hasFirstIet = false
        var hasRootPfs = false
        var hasAnyPfs = false
        var hasOnsScript = false
        var hasOnsArchive = false

        fun collect(f: DocumentFile, rel: String) {
            val lower = (f.name ?: "").lowercase(Locale.ROOT)
            if (lower.isEmpty()) return
            val childRel = if (rel.isEmpty()) lower else "$rel/$lower"
            names.add(lower)
            if (f.isDirectory) {
                if (lower == "tyrano") hasTyranoDir = true
                if (lower == "app.asar" || childRel.endsWith("/app.asar")) hasAppAsar = true
                // resources/app.asar 可能是文件，也可能是已解包目录，需继续下钻识别父级游戏目录。
                if (lower == "data" || lower == "tyrano" || lower == "scenario" ||
                    lower == "system" || lower == "app" || lower == "game" ||
                    lower == "resources" || lower == "app.asar"
                ) {
                    val sub = f.listFiles()
                    sub.forEach { collect(it, childRel) }
                }
                return
            }
            when {
                lower == "index.html" || lower == "index.htm" -> hasIndex = true
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
                lower.endsWith(".iso") || lower.endsWith(".cso") || lower.endsWith(".pbp") ||
                    lower.endsWith(".chd") -> {
                    // 粗校验：PSP 游戏镜像通常很大（≥10MB），避免把散落的小镜像误判为 PSP 游戏
                    if (f.length() >= 10L * 1024L * 1024L) pspFiles.add(childRel)
                }
            }
        }
        children.forEach { collect(it, "") }

        // 优先 Artemis（Ar）
        if ((hasSystemIni && hasFirstIet) || hasRootPfs || hasAnyPfs) {
            return Detection(EngineType.ARTEMIS, if ((hasSystemIni && hasFirstIet) || hasRootPfs) 95 else 90, "[游戏目录]")
        }
        // Tyrano（Ty）：浏览器结构（index.html + data/tyrano）或 asar 打包（app.asar / resources/app.asar）
        if ((hasIndex && hasTyranoDir) || hasAppAsar) {
            return Detection(EngineType.TYRANO, if (hasAppAsar) 96 else 95, "[游戏目录]")
        }
        if (hasIndex) {
            return Detection(EngineType.TYRANO, 70, "[游戏目录]")
        }
        // Kirikiri（kr）
        if (xp3Files.isNotEmpty() || hasStartupTjs || hasConfigTjs) {
            return Detection(EngineType.KIRIKIRI, if (xp3Files.isNotEmpty()) 95 else 80, xp3Files.firstOrNull() ?: "[游戏目录]")
        }
        // ONS
        if (hasOnsScript || hasOnsArchive) {
            return Detection(EngineType.ONS, if (hasOnsScript) 90 else 70, "[游戏目录]")
        }
        // PSP（iso/cso/pbp 镜像文件）
        if (pspFiles.isNotEmpty()) {
            return Detection(EngineType.PSP, 90, pspFiles.first())
        }
        return r
    }

    fun detectEngine(dir: File): Detection {
        val r = Detection(EngineType.UNKNOWN, 0, "")
        if (!dir.isDirectory) return r
        val children = dir.listFiles() ?: return r

        val xp3Files = mutableListOf<String>()
        var hasStartupTjs = false
        var hasConfigTjs = false
        var hasIndex = false
        var hasAppAsar = false
        var hasTyranoDir = false
        var hasSystemIni = false
        var hasFirstIet = false
        var hasRootPfs = false
        var hasAnyPfs = false
        var hasOnsScript = false
        var hasOnsArchive = false

        fun collect(f: File, rel: String) {
            val lower = f.name.lowercase(Locale.ROOT)
            if (lower.isEmpty()) return
            val childRel = if (rel.isEmpty()) lower else "$rel/$lower"
            if (f.isDirectory) {
                if (lower == "tyrano") hasTyranoDir = true
                if (lower == "app.asar" || childRel.endsWith("/app.asar")) hasAppAsar = true
                if (lower == "data" || lower == "tyrano" || lower == "scenario" ||
                    lower == "system" || lower == "app" || lower == "game" ||
                    lower == "resources" || lower == "app.asar"
                ) {
                    f.listFiles()?.forEach { collect(it, childRel) }
                }
                return
            }
            when {
                lower == "index.html" || lower == "index.htm" -> hasIndex = true
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
            }
        }
        children.forEach { collect(it, "") }

        if ((hasSystemIni && hasFirstIet) || hasRootPfs || hasAnyPfs) {
            return Detection(EngineType.ARTEMIS, if ((hasSystemIni && hasFirstIet) || hasRootPfs) 95 else 90, "[游戏目录]")
        }
        if ((hasIndex && hasTyranoDir) || hasAppAsar) {
            return Detection(EngineType.TYRANO, if (hasAppAsar) 96 else 95, "[游戏目录]")
        }
        if (hasIndex) {
            return Detection(EngineType.TYRANO, 70, "[游戏目录]")
        }
        if (xp3Files.isNotEmpty() || hasStartupTjs || hasConfigTjs) {
            return Detection(EngineType.KIRIKIRI, if (xp3Files.isNotEmpty()) 95 else 80, xp3Files.firstOrNull() ?: "[游戏目录]")
        }
        if (hasOnsScript || hasOnsArchive) {
            return Detection(EngineType.ONS, if (hasOnsScript) 90 else 70, "[游戏目录]")
        }
        return r
    }
}
