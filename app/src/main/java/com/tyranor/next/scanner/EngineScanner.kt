package com.tyranor.next.scanner

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
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

    // ============ 游戏结果持久化 ============

    fun saveGames(context: Context, games: List<ScanGame>) {
        val str = games.joinToString("\n") { serializeGame(it) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_GAMES, str).apply()
    }

    fun loadGames(context: Context): List<ScanGame> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_GAMES, null) ?: return emptyList()
        return raw.split("\n").mapNotNull { parseGame(it) }
    }

    fun recordRecentGame(context: Context, game: ScanGame) {
        val touched = game.copy(openTime = System.currentTimeMillis())
        val next = (listOf(touched) + loadRecentGames(context).filterNot { it.uri == game.uri }).take(20)
        saveRecentGames(context, next)
    }

    fun loadRecentGames(context: Context): List<ScanGame> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_RECENT_GAMES, null) ?: return emptyList()
        return raw.split("\n").mapNotNull { parseGame(it) }
    }

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
     * 将新扫描结果与既有游戏库按 uri 合并：保留封面/VNDB 绑定/启动文件/打开时间等
     * 非扫描字段，避免每次重新扫描清空用户手动数据。
     */
    fun mergeScannedGames(existing: List<ScanGame>, fresh: List<ScanGame>): List<ScanGame> {
        val oldByUri = existing.associateBy { it.uri }
        return fresh.map { scanned ->
            val old = oldByUri[scanned.uri]
            if (old == null) scanned
            else scanned.copy(
                coverUri = old.coverUri,
                vndbId = old.vndbId,
                metadataTitle = old.metadataTitle,
                launchFile = old.launchFile,
                openTime = old.openTime,
            )
        }
    }

    internal fun saveRecentGames(context: Context, games: List<ScanGame>) {
        val str = games.joinToString("\n") { serializeGame(it) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_RECENT_GAMES, str).apply()
    }

    // ============ 首页快捷启动（最多 3 个） ============

    fun loadQuickLaunch(context: Context): List<ScanGame> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_QUICK_LAUNCH, null) ?: return emptyList()
        return raw.split("\n").mapNotNull { parseGame(it) }
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
        val str = games.joinToString("\n") { serializeGame(it) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_QUICK_LAUNCH, str).apply()
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

    fun removeRoot(context: Context, uri: Uri) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existing = loadRoots(context).filterNot { it == uri.toString() }
        prefs.edit().putString(KEY_ROOTS, existing.joinToString("\n")).apply()
    }

    fun loadRoots(context: Context): List<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ROOTS, null)
            ?.split("\n")
            ?.filter { it.isNotBlank() }
            ?: emptyList()

    // ============ 扫描游戏 ============

    suspend fun scanRoot(context: Context, rootUriStr: String): List<ScanGame> = withContext(Dispatchers.IO) {
        val rootUri = Uri.parse(rootUriStr)
        val root = DocumentFile.fromTreeUri(context.applicationContext, rootUri)
        if (root == null || !root.isDirectory) return@withContext emptyList()

        val results = mutableListOf<ScanGame>()
        // 深度优先遍历子目录，识别每个候选游戏目录
        traverseDirectories(context.applicationContext, root, 0, results)
        results
    }

    private fun traverseDirectories(context: Context, dir: DocumentFile, level: Int, out: MutableList<ScanGame>) {
        if (level > 3) return
        val children = dir.listFiles()

        // 1) 本级目录本身可能是游戏（含引擎特征文件）
        val detected = detectEngine(dir)
        if (detected.engine != EngineType.UNKNOWN) {
            out.add(
                ScanGame(
                    title = dir.name?.takeIf { it.isNotBlank() } ?: "未命名游戏",
                    uri = dir.uri.toString(),
                    engine = detected.engine,
                    launchTarget = detected.launchTarget,
                    coverUri = null,
                )
            )
            // 已识别为游戏，其子目录多为引擎内部资源，仅扫描直接文件层，不再深挖
            return
        }

        // 2) 否则递归子目录
        for (child in children) {
            if (child.isDirectory) {
                traverseDirectories(context, child, level + 1, out)
            }
        }
    }

    // ============ 引擎识别（移植自 EngineDetector） ============

    data class Detection(val engine: EngineType, val confidence: Int, val launchTarget: String)

    fun detectEngine(dir: DocumentFile): Detection {
        val r = Detection(EngineType.UNKNOWN, 0, "")
        if (!dir.isDirectory) return r
        val children = dir.listFiles()

        val names = HashSet<String>()            // 小写名
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

        fun collect(f: DocumentFile, rel: String) {
            val lower = (f.name ?: "").lowercase(Locale.ROOT)
            if (lower.isEmpty()) return
            val childRel = if (rel.isEmpty()) lower else "$rel/$lower"
            names.add(lower)
            if (f.isDirectory) {
                if (lower == "tyrano") hasTyranoDir = true
                // resources 是 Tyrano asar 打包的存放目录（resources/app.asar），需下钻识别
                if (lower == "data" || lower == "tyrano" || lower == "scenario" ||
                    lower == "system" || lower == "app" || lower == "game" || lower == "resources"
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
        return r
    }
}
