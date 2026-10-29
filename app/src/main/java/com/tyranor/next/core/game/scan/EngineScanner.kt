package com.tyranor.next.core.game.scan

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.tyranor.next.R
import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.game.manual.AndroidAppGames
import com.tyranor.next.core.game.model.GamePathUtils
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.game.model.Xs4Ini
import com.tyranor.next.core.game.storage.EngineDetectionRepository
import com.tyranor.next.core.game.storage.GameLibraryFacade
import com.tyranor.next.core.game.storage.GameLibraryRepository
import com.tyranor.next.core.i18n.AppLocaleController
import com.tyranor.next.core.settings.AppSettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runInterruptible
import java.io.File
import java.util.Locale

/**
 * 精简版游戏扫描器，识别逻辑移植自 RinneMobile 的 EngineDetector/GameScanner。
 * 支持引擎：Kirikiri、ONS、Tyrano、RPG Maker XP/VX/VX Ace、RPG Maker MV/MZ、VN、WebOther、Artemis、Ren'Py、Ruffle（SWF）。
 *
 * 职责边界（架构优化 P0-1）：本类只负责「扫描 + 引擎识别」；游戏库 CRUD / 最近游玩 /
 * 快捷启动 / 扫描根等持久化门面职责见 [GameLibraryFacade]，路径工具见 [GamePathUtils]。
 */
object EngineScanner {

    private const val TAG = "EngineScanner"

    private val PFS_PATCH_NAME_RE = Regex("""^[^.]+\.pfs\.\d{3}$""")
    private val OBB_NAME_RE = Regex("""^(main|patch)\.\d+\..+\.obb$""")

    /** YU-RIS 引擎 DLL（YSPNG/YSWBP/YSZLB/YSSNP/YSTCH 等，至少两个才作为弱特征）。 */
    private val YS_DLL_NAME_RE = Regex("""^ys[a-z0-9]*\.dll$""")

    /** Siglus Gameexe（含本地化变体，与引擎 GAMEEXE_CANDIDATES 对齐）。 */
    private val GAMEEXE_DAT_RE = Regex("""^gameexe(en|zh|zhtw|de|es|fr|id)?\.dat$""")
    private val GAMEEXE_INI_RE = Regex("""^gameexe(en|zh|zhtw|de|es|fr|id)?\.ini$""")

    /** AVG32 散装场景 `SEEN###.TXT`（三位数）与 RealLive `SEEN####.TXT`（四位数）。 */
    private val SEEN_SCENE_AVG32_RE = Regex("""^seen\d{3}\.txt$""")
    private val SEEN_SCENE_REALLIVE_RE = Regex("""^seen\d{4}\.txt$""")

    /** UK2 MES 文件头（`<< UK2 TEXT Ver1.00 >>`）。 */
    private val UK2_MES_MAGIC = "<< UK2 TEXT Ver1.00 >>".toByteArray(Charsets.US_ASCII)

    /** System3.x（xsystem35）场景/图形档 `*.ALD` 的最小文件名长度（`SA.ALD` 起）。 */
    private const val XS35_ALD_MIN_LENGTH = 6

    /** System3.x 辅助特征（根目录或 `GAMEDATA/` 内的 System39 配置文件）。 */
    private val XS35_SUPPORT_EXACT_NAMES = setOf("system39.ain", "system39.ini")

    /** System3.x 辅助特征扩展名（`.wai` / `.bgi` / `.alk`）。 */
    private val XS35_SUPPORT_EXTENSIONS = listOf(".wai", ".bgi", ".alk")

    /** System4 入口 ini（根目录，大小写不敏感；与 `Xs4Paths.resolveIniFile` 同口径）。 */
    private const val XS4_SYSTEM40_INI = "system40.ini"
    private const val XS4_ALICESTART_INI = "alicestart.ini"
    private const val XS4_AIN_EXTENSION = ".ain"

    /** RealLive `SEEN.TXT` 场景头尺寸（`0x1d0`，AVG2000 为 `0x1cc`）。 */
    private val REALLIVE_HEADER_SIZES = intArrayOf(0x1d0, 0x1cc)

    // ============ 扫描游戏 ============

    /** 全量扫描所有根目录（结果以本次扫描为准，用于首次/无数据场景）。 */
    suspend fun scanAll(context: Context): List<ScanGame> = withContext(Dispatchers.IO) {
        val startedAt = SystemClock.elapsedRealtime()
        val maxDepth = AppSettingsStore.getScanDepth(context)
        val roots = GameLibraryFacade.loadRoots(context)
        // 多个存储卷可以并行扫描，但限制为 2，避免同时向 DocumentsProvider 发起过多查询。
        val gate = Semaphore(2)
        val all = coroutineScope {
            roots.map { root ->
                async {
                    gate.withPermit { runInterruptible { scanRootInternal(context.applicationContext, root, maxDepth) } }
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
        val activeRoots = GameLibraryFacade.loadRoots(context)
        val activeScanned = scanned.filter { game ->
            activeRoots.any { root -> isGameUnderRoot(root, game.uri) }
        }
        val refreshed = GameLibraryFacade.updateGames(context) { currentGames ->
            val existingByUri = currentGames.associateBy { it.uri }
            val scanned = activeScanned.map { current ->
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
            mergeScannedWithManual(currentGames, scanned)
        }
        val validUris = refreshed.mapTo(HashSet()) { it.uri }
        // 最近打开/快捷启动为 games 派生视图，消失的游戏行已随差量删除，这里同步内存缓存即可。
        GameLibraryFacade.updateRecentGames(context) { current ->
            current.filter { it.uri in validUris }
        }
        GameLibraryFacade.updateQuickLaunch(context) { list ->
            list.filter { it.uri in validUris }
        }
        // 扫描识别结果入缓存（迁移方案阶段 5）：Ren'Py 版本建议与 RPGM 子运行时。
        GameLibraryRepository.post(context) { EngineDetectionRepository.recordScanDetections(it, refreshed) }
        refreshed
    }

    /**
     * System4 一次性迁移：只对库中 `engine == XSYS35` 的条目定点重跑识别，不做整库扫描，其它条目不动。
     * 结果为 XSYS4 的条目经 [Xs4RescanMigration.applyDetection] 改判；未能确定结果的条目
     * （[Xs4RedetectOutcome.Missing] 暂不可达 / [Xs4RedetectOutcome.Unreadable] 读取失败）原样保留，
     * 且使 [Xs4RedetectReport.complete] 为假，调用方据此不置位迁移标志，下次启动重试。
     */
    suspend fun redetectXs35Games(context: Context): Xs4RedetectReport = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val targets = Xs4RescanMigration.targetsOf(GameLibraryFacade.loadGames(appContext))
        if (targets.isEmpty()) return@withContext Xs4RedetectReport(changed = 0, complete = true)
        val roots = GameLibraryFacade.loadRoots(appContext)
        val rootReadable = HashMap<String, Boolean>()
        val outcomes = ArrayList<Xs4RedetectOutcome>(targets.size)
        val done = HashMap<String, Xs4RedetectOutcome.Done>()
        for (game in targets) {
            ensureActive()
            val outcome = redetectXs35Game(appContext, game, roots, rootReadable)
            outcomes += outcome
            if (outcome is Xs4RedetectOutcome.Done) done[game.uri] = outcome
        }
        var changed = 0
        if (done.isNotEmpty()) {
            GameLibraryFacade.updateGames(appContext) { games ->
                games.map { game ->
                    val result = done[game.uri] ?: return@map game
                    Xs4RescanMigration.applyDetection(game, result.detection, result.dirName).also { updated ->
                        if (updated !== game) changed++
                    }
                }
            }
        }
        Xs4RedetectReport(changed = changed, complete = Xs4RescanMigration.canMarkDone(outcomes))
    }

    private fun redetectXs35Game(
        context: Context,
        game: ScanGame,
        roots: List<String>,
        rootReadable: MutableMap<String, Boolean>,
    ): Xs4RedetectOutcome {
        if (!game.uri.startsWith("/")) {
            val root = roots.firstOrNull { isGameUnderRoot(it, game.uri) } ?: return Xs4RedetectOutcome.Missing
            val treeUri = Uri.parse(root)
            val readable = rootReadable.getOrPut(root) {
                val rootSession = SafScanSession(context, treeUri)
                rootSession.root()?.let { rootSession.nodeOf(it.uri) } != null
            }
            if (!readable) return Xs4RedetectOutcome.Unreadable
            val session = SafScanSession(context, treeUri)
            val node = session.nodeOf(Uri.parse(game.uri)) ?: return Xs4RedetectOutcome.Missing
            val children = session.children(node)
            if (session.queryFailed) return Xs4RedetectOutcome.Unreadable
            return Xs4RedetectOutcome.Done(detectEngine(children, session::children, session::readHead), node.name)
        }
        val dir = File(game.uri)
        if (!dir.isDirectory) return Xs4RedetectOutcome.Missing
        if (dir.listFiles() == null) return Xs4RedetectOutcome.Unreadable
        return Xs4RedetectOutcome.Done(detectEngine(dir, FileScanSession()), dir.name)
    }

    /**
     * 重扫合并：手动添加的条目（PC 游戏 / 安卓游戏，[EngineType.isManual]）不参与扫描（不依赖扫描根），
     * 重扫时必须原样保留；安卓条目额外按 uri 前缀识别，避免 engine 字段损坏（回退 UNKNOWN）时被丢弃。
     * 同 uri 若被扫描命中则以扫描结果为准（避免重复条目）。
     */
    internal fun mergeScannedWithManual(current: List<ScanGame>, scanned: List<ScanGame>): List<ScanGame> {
        val scannedUris = scanned.mapTo(HashSet()) { it.uri }
        val manual = current.filter { game ->
            (game.engine.isManual || AndroidAppGames.isAndroidApp(game)) &&
                game.uri !in scannedUris
        }
        return manual + scanned
    }

    /**
     * 增量扫描（游戏库已有数据时调用）：遍历根目录时对已识别游戏目录剪枝跳过，
     * 只发现新游戏；返回 现有游戏 + 新发现游戏（已删除游戏保留，不主动移除）。
     */
    suspend fun incrementalScan(context: Context): List<ScanGame> = runInterruptible(Dispatchers.IO) {
        val existing = GameLibraryFacade.loadGames(context)
        val known = existing.mapTo(HashSet()) { it.uri }
        val seen = HashSet<String>()
        val found = mutableListOf<ScanGame>()
        val maxDepth = AppSettingsStore.getScanDepth(context)
        GameLibraryFacade.loadRoots(context).forEach { root ->
            val beforeCount = found.size
            val rootUri = Uri.parse(root)
            val safSession = SafScanSession(context.applicationContext, rootUri)
            val safRoot = safSession.root()
            safRoot?.let { rootNode ->
                scanRootIncremental(context, safSession, rootNode, 0, maxDepth, known, found)
            }
            // 只有 SAF 不可用时才走真实路径兜底；正常的“没有新游戏”不再重复扫描整棵目录树。
            if (found.size == beforeCount && (safRoot == null || safSession.queryFailed)) {
                GamePathUtils.safUriToPath(root)?.let { path ->
                    scanRootIncrementalFile(context, FileScanSession(), File(path), 0, maxDepth, known, found)
                }
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

        val detected = detectEngine(children, session::children, session::readHead)
        if (detected.engine != EngineType.UNKNOWN) {
            val coverUri = findLocalCoverUri(children)
            out.add(
                ScanGame(
                    title = scanTitle(context, detected, dir.name),
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
        // 未识别引擎：按 ROM 文件逐条入库（已存在的 ROM uri 由 known 剪枝），再递归子目录
        out.addAll(romGamesForSaf(children, findLocalCoverUri(children), known, dir.name))
        for (child in children) {
            if (child.isDirectory) {
                scanRootIncremental(context, session, child, level + 1, maxDepth, known, out)
            }
        }
    }

    suspend fun scanRoot(context: Context, rootUriStr: String, maxDepth: Int = 3): List<ScanGame> = runInterruptible(Dispatchers.IO) {
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
        if (results.isEmpty() && (safRoot == null || safSession.queryFailed)) {
            GamePathUtils.safUriToPath(rootUriStr)?.let { path ->
                traverseFileDirectories(context, FileScanSession(), File(path), 0, maxDepth, results)
            }
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
        val detected = detectEngine(children, session::children, session::readHead)
        if (detected.engine != EngineType.UNKNOWN) {
            val coverUri = findLocalCoverUri(children)
            out.add(
                ScanGame(
                    title = scanTitle(context, detected, dir.name),
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

        // 2) 未识别引擎：按 ROM 文件逐条入库（PSP / Switch），再递归子目录
        out.addAll(romGamesForSaf(children, findLocalCoverUri(children), directoryName = dir.name))

        // 3) 否则递归子目录
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

        /** 已知文档 URI → 节点；名称查询失败（文档已不存在或无权限）返回 null。 */
        fun nodeOf(documentUri: Uri): SafNode? {
            val documentId = runCatching { DocumentsContract.getDocumentId(documentUri) }.getOrNull() ?: return null
            val name = queryDisplayName(documentUri) ?: return null
            return SafNode(documentUri, documentId, name, isDirectory = true)
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
                                    size = if (it.isNull(3)) null else it.getLong(3).takeIf { size -> size >= 0 },
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

        /** Bounded content evidence shared by the framebuffer and Ethornell scanners. */
        fun readHead(node: SafNode, maxBytes: Int = 64 * 1024): ByteArray? = try {
            resolver.openInputStream(node.uri)?.use { input ->
                EthornellArchiveProbe.readHead(input, maxBytes)
            }
        } catch (cancelled: java.util.concurrent.CancellationException) {
            throw cancelled
        } catch (_: java.io.IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    private data class SafNode(
        val uri: Uri,
        val documentId: String,
        val name: String,
        val isDirectory: Boolean,
        val size: Long? = null,
    )

    private val SAF_PROJECTION = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE,
    )

    fun applyLocalCover(context: Context, game: ScanGame): ScanGame {
        if (!game.coverUri.isNullOrBlank()) return game
        // ROM 文件型游戏（PPSSPP / Eden）的 uri 是文件或真实路径，不是 SAF tree，跳过目录封面探测
        val uri = runCatching { Uri.parse(game.uri) }.getOrNull() ?: return game
        if (!DocumentsContract.isTreeUri(uri)) return game
        val dir = DocumentFile.fromTreeUri(context.applicationContext, uri) ?: return game
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

    // ============ ROM 文件型游戏（PSP / Nintendo Switch / Ruffle SWF） ============
    // 一 ROM 一条游戏：uri = ROM 文件自身（SAF document URI / 真实路径），launchTarget = 文件名。
    // uri 作为游戏库主键天然唯一；目录归属由 GameRootMatcher 的 documentId 前缀匹配兜住。
    // Ruffle 包装目录（根 index.html + 叶子目录 + 唯一 SWF）由 detectEngine 识别为目录型游戏；
    // 散装 SWF（含无 index.html 的单文件目录）与其它 ROM 一样按文件逐条入库。

    private val PSP_ROM_EXTENSIONS = setOf("pbp", "cso", "iso", "chd")
    private val SWITCH_ROM_EXTENSIONS = setOf("nsp", "xci", "nca", "nro")

    /** 目录内出现即视为「主机 ROM 目录」的引擎：Ruffle 包装判定必须让位，按 ROM 逐条入库。 */
    private val CONSOLE_ROM_ENGINES = setOf(EngineType.PSP, EngineType.NINTENDO_SWITCH)

    internal fun romEngineOf(name: String): EngineType? {
        // 隐藏文件（macOS 的 ._foo.swf、.nomedia 等）不是可启动 ROM。
        if (name.startsWith(".")) return null
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return when (ext) {
            in PSP_ROM_EXTENSIONS -> EngineType.PSP
            in SWITCH_ROM_EXTENSIONS -> EngineType.NINTENDO_SWITCH
            "swf" -> EngineType.RUFFLE
            else -> null
        }
    }

    /**
     * CatSystem2 目录评分（对齐 `docs/cs2参考.md` §15 的权重，PE 项因扫描链仅有文件名而省略）。
     * `cs2.exe` 只作为辅助加分：Runtime 常被改名，不能作为唯一判定依据。
     */
    private fun cs2Score(
        hasStartupXml: Boolean,
        intCount: Int,
        typicalIntCount: Int,
        hasCst: Boolean,
        hasHg3: Boolean,
        hasCstl: Boolean,
        hasFes: Boolean,
        hasAnm: Boolean,
        hasKcs: Boolean,
        hasCs2Exe: Boolean,
    ): Int {
        var score = 0
        if (hasStartupXml) score += 15
        if (intCount >= 2) score += 25 else if (intCount == 1) score += 10
        // §11：典型 INT 文件名（scene/image/config/bgm/se/kcs）出现多个时明显提高可信度
        if (typicalIntCount >= 3) score += 20 else if (typicalIntCount >= 1) score += 10
        if (hasCst) score += 15
        if (hasHg3) score += 10
        if (hasCstl) score += 5
        if (hasFes) score += 5
        if (hasAnm) score += 5
        if (hasKcs) score += 10
        if (hasCs2Exe) score += 10
        return score
    }

    private fun romTitle(name: String): String =
        name.substringBeforeLast('.').takeIf { it.isNotBlank() } ?: name

    /** 扫描标题：识别期标题 > 目录名；Ruffle 包装目录在目录名缺失时回退 SWF 文件主名。 */
    private fun scanTitle(context: Context, detected: Detection, dirName: String): String {
        val ruffleFallback = if (detected.engine == EngineType.RUFFLE) {
            romTitle(detected.launchTarget).takeIf { it.isNotBlank() }
        } else {
            null
        }
        return detected.titleHint
            ?: dirName.takeIf { it.isNotBlank() }
            ?: ruffleFallback
            ?: localizedText(context, R.string.scan_unnamed_game)
    }

    private fun romGamesForSaf(
        children: List<SafNode>,
        coverUri: String?,
        known: Set<String>? = null,
        directoryName: String? = null,
    ): List<ScanGame> {
        val roms = children.filter { child ->
            !child.isDirectory &&
                romEngineOf(child.name) != null &&
                (romEngineOf(child.name) != EngineType.RUFFLE || child.size == null || child.size > 0L) &&
                (known == null || child.uri.toString() !in known)
        }
        if (roms.isEmpty()) return emptyList()
        // 单封面按目录内 ROM 总数判定（含已入库条目），与标题策略保持同一口径。
        val totalRoms = children.count { !it.isDirectory && romEngineOf(it.name) != null }
        val singleCover = if (totalRoms == 1) coverUri else null
        // 标题策略按目录内 SWF 总数判定（含已入库条目），增量扫描不会把多 SWF 误判为单入口
        val singleSwf = children.count { !it.isDirectory && romEngineOf(it.name) == EngineType.RUFFLE } == 1
        return roms.map { node ->
            val engine = romEngineOf(node.name)!!
            ScanGame(
                title = romTitleFor(engine, node.name, singleSwf, directoryName),
                uri = node.uri.toString(),
                engine = engine,
                launchTarget = node.name,
                externalModuleAlias = romModuleAlias(engine),
                coverUri = singleCover,
                coverSource = if (singleCover.isNullOrBlank()) null else AppSettingsStore.COVER_SOURCE_LOCAL,
            )
        }
    }

    internal fun romGamesForFile(
        children: Array<File>,
        coverUri: String?,
        known: Set<String>? = null,
        directoryName: String? = null,
    ): List<ScanGame> {
        val roms = children.filter { child ->
            child.isFile &&
                romEngineOf(child.name) != null &&
                (romEngineOf(child.name) != EngineType.RUFFLE || child.length() > 0L) &&
                (known == null || child.absolutePath !in known)
        }
        if (roms.isEmpty()) return emptyList()
        // 单封面按目录内 ROM 总数判定（含已入库条目），与标题策略保持同一口径。
        val totalRoms = children.count { it.isFile && romEngineOf(it.name) != null }
        val singleCover = if (totalRoms == 1) coverUri else null
        // 标题策略按目录内 SWF 总数判定（含已入库条目），增量扫描不会把多 SWF 误判为单入口
        val singleSwf = children.count { it.isFile && romEngineOf(it.name) == EngineType.RUFFLE } == 1
        return roms.map { rom ->
            val engine = romEngineOf(rom.name)!!
            ScanGame(
                title = romTitleFor(engine, rom.name, singleSwf, directoryName),
                uri = rom.absolutePath,
                engine = engine,
                launchTarget = rom.name,
                externalModuleAlias = romModuleAlias(engine),
                coverUri = singleCover,
                coverSource = if (singleCover.isNullOrBlank()) null else AppSettingsStore.COVER_SOURCE_LOCAL,
            )
        }
    }

    /**
     * 单个 Ruffle SWF 以父目录名作为标题（目录名缺失时回退文件主名）；
     * 同目录多个 SWF 或其它 ROM 保持文件主名。
     */
    private fun romTitleFor(
        engine: EngineType,
        fileName: String,
        singleSwf: Boolean,
        directoryName: String?,
    ): String =
        if (engine == EngineType.RUFFLE && singleSwf) {
            directoryName?.takeIf { it.isNotBlank() } ?: romTitle(fileName)
        } else {
            romTitle(fileName)
        }

    private fun romModuleAlias(engine: EngineType): String? =
        if (engine == EngineType.RUFFLE) RUFFLE_MODULE_ALIAS else null

    /**
     * 扫描写入的 Ruffle 模块别名，与 `RuffleExternalEngineModule.defaultAlias` 保持同值。
     * 扫描层不直接依赖 external 模块（避免 game-scan → engine-external 的耦合/循环），
     * 两处如需改名必须同步。
     */
    private const val RUFFLE_MODULE_ALIAS = "internal.flash"

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
        private val headCache = HashMap<String, ByteArray?>()

        fun children(dir: File): Array<File> = childrenCache.getOrPut(dir.absolutePath) {
            dir.listFiles() ?: emptyArray()
        }

        /** Bounded content evidence shared by the framebuffer and Ethornell scanners. */
        fun readHead(file: File, maxBytes: Int = 64 * 1024): ByteArray? =
            headCache.getOrPut(file.absolutePath) {
                try {
                    file.inputStream().use { input ->
                        EthornellArchiveProbe.readHead(input, maxBytes)
                    }
                } catch (cancelled: java.util.concurrent.CancellationException) {
                    throw cancelled
                } catch (_: java.io.IOException) {
                    null
                } catch (_: SecurityException) {
                    null
                }
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
                    title = scanTitle(context, detected, dir.name),
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
        out.addAll(romGamesForFile(children, findLocalCoverUri(children), known, dir.name))
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
                    title = scanTitle(context, detected, dir.name),
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
        out.addAll(romGamesForFile(children, findLocalCoverUri(children), directoryName = dir.name))
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

    /** [titleHint] 为识别期能从内容读到的标题（当前仅 System4 的 `GameName`），缺省回落目录名。 */
    data class Detection(
        val engine: EngineType,
        val confidence: Int,
        val launchTarget: String,
        val externalModuleAlias: String? = null,
        val titleHint: String? = null,
    )

    fun detectEngine(context: Context, dir: DocumentFile): Detection {
        if (!dir.isDirectory) return UNKNOWN_DETECTION
        return detectEngine(
            children = dir.listFiles().asIterable(),
            nameOf = { it.name.orEmpty() },
            isDirectory = { it.isDirectory },
            childrenOf = { it.listFiles().asIterable() },
            headOf = { file ->
                try {
                    context.contentResolver.openInputStream(file.uri)?.use { EthornellArchiveProbe.readHead(it) }
                } catch (cancelled: java.util.concurrent.CancellationException) {
                    throw cancelled
                } catch (_: java.io.IOException) {
                    null
                } catch (_: SecurityException) {
                    null
                }
            },
            sizeOf = { it.length().takeIf { size -> size > 0 } },
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
        headOf = { session.readHead(it) },
        sizeOf = { it.length() },
    )

    private fun detectEngine(
        children: List<SafNode>,
        childrenOf: (SafNode) -> List<SafNode>,
        headOf: (SafNode) -> ByteArray?,
    ): Detection = detectEngine(
        children = children,
        nameOf = { it.name },
        isDirectory = { it.isDirectory },
        childrenOf = childrenOf,
        headOf = headOf,
        sizeOf = { it.size },
    )

    internal fun <T> detectEngine(
        children: Iterable<T>,
        nameOf: (T) -> String,
        isDirectory: (T) -> Boolean,
        childrenOf: (T) -> Iterable<T>,
        headOf: (T) -> ByteArray? = { null },
        sizeOf: (T) -> Long? = { null },
    ): Detection {

        val xp3Files = mutableListOf<String>()
        var hasStartupTjs = false
        var hasConfigTjs = false
        var hasIndex = false
        var hasRootIndex = false
        var hasChildDirectory = false
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
        var hasGameexeDat = false
        var hasGameexeIni = false
        var hasYscfgDat = false
        var hasYpf = false
        var hasYmv = false
        var ysDllCount = 0
        var hasStartupXml = false
        var hasCs2Exe = false
        var intCount = 0
        var typicalIntCount = 0
        var hasCst = false
        var hasCstl = false
        var hasHg3 = false
        var hasFes = false
        var hasAnm = false
        var hasKcs = false
        var hasScenePck = false
        var hasSelectIni = false
        var hasG00 = false
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
        var firstSwf: String? = null
        var swfCount = 0
        var hasGameIni = false
        var hasRxdata = false
        var hasRvdata = false
        var hasRvdata2 = false
        var hasMkxpZRubyRuntime = false
        var hasFvpScript = false
        var hasFvpPack = false
        var hasAldScript = false
        var aldCount = 0
        var hasXs35Support = false
        var hasSystem39Marker = false
        var system40IniNode: T? = null
        var aliceStartIniNode: T? = null
        var hasAin = false
        var hasUk2Cfg = false
        var hasSeenSceneAvg32 = false
        var hasSeenSceneReallive = false
        var seenArchiveNode: T? = null
        var seenArchiveIsRoot = false
        var uk2MesNode: T? = null
        var ethornellSystemNode: T? = null
        var ambiguousEthornellSystem = false

        fun collect(entry: T, rel: String) {
            if (Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException()
            val lower = nameOf(entry).lowercase(Locale.ROOT)
            if (lower.isEmpty()) return
            val childRel = if (rel.isEmpty()) lower else "$rel/$lower"
            if (isDirectory(entry)) {
                hasChildDirectory = true
                if (lower == "tyrano") hasTyranoDir = true
                if (lower == "renpy") hasRenpyDir = true
                if (lower == "game") hasGameDir = true
                if (lower == "app.asar" || childRel.endsWith("/app.asar")) hasAppAsar = true
                if (lower == "config") {
                    // CatSystem2：config/startup.xml 是高价值目录特征（不递归，只看该文件名）
                    childrenOf(entry).forEach { child ->
                        if (nameOf(child).equals("startup.xml", ignoreCase = true)) hasStartupXml = true
                    }
                }
                if (lower == "pac") {
                    // YU-RIS 封包目录：只为 YURIS 特征扫描（.ypf/.ymv），不进入通用目录白名单，
                    // 避免把包内文件暴露给其它引擎的检测规则
                    childrenOf(entry).forEach { child ->
                        val childName = nameOf(child).lowercase(Locale.ROOT)
                        if (childName.endsWith(".ypf")) hasYpf = true
                        if (childName.endsWith(".ymv")) hasYmv = true
                    }
                }
                if (rel.isEmpty() && lower == "gamedata") {
                    // System3.x（xsystem35）CD 镜像解出形态（`<根>/GAMEDATA/SA.ALD`）：只为 XS35
                    // 特征扫描（.ALD / System39.* / .wai / .bgi / .alk），不进入通用目录白名单，
                    // 避免把包内文件暴露给其它引擎的检测规则（与 pac 分支同策略）。
                    // 要求 rel.isEmpty():仅认根目录的直接子目录，`data/gamedata/` 等嵌套同名目录不贡献特征
                    childrenOf(entry).forEach { child ->
                        if (isDirectory(child)) return@forEach
                        val childName = nameOf(child).lowercase(Locale.ROOT)
                        if (childName.endsWith(".ald") && childName.length >= XS35_ALD_MIN_LENGTH) {
                            hasAldScript = true
                            aldCount++
                        }
                        if (isXs35SupportName(childName)) hasXs35Support = true
                        // 与根目录分支同口径：精确名 System39.* 是 System 3.x 标记（System 4 判定的护栏）
                        if (childName in XS35_SUPPORT_EXACT_NAMES) hasSystem39Marker = true
                    }
                }
                if (lower in ENGINE_SEARCH_DIRECTORIES) {
                    childrenOf(entry).forEach { collect(it, childRel) }
                }
                return
            }
            when {
                rel.isEmpty() && lower == "system.arc" -> {
                    if (ethornellSystemNode != null) ambiguousEthornellSystem = true
                    ethornellSystemNode = entry
                }
                lower == "index.html" || lower == "index.htm" -> {
                    hasIndex = true
                    if (rel.isEmpty()) hasRootIndex = true
                }
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
                GAMEEXE_DAT_RE.matches(lower) -> hasGameexeDat = true
                GAMEEXE_INI_RE.matches(lower) -> hasGameexeIni = true
                lower == "scene.pck" -> hasScenePck = true
                lower == "select.ini" -> hasSelectIni = true
                lower.endsWith(".g00") -> hasG00 = true
                // RealLive / AVG32 / UK2（framebuffer 引擎）
                lower == "uk2.cfg" -> hasUk2Cfg = true
                lower == "seen.txt" -> {
                    if (seenArchiveNode == null || (!seenArchiveIsRoot && rel.isEmpty())) {
                        seenArchiveNode = entry
                        seenArchiveIsRoot = rel.isEmpty()
                    }
                }
                SEEN_SCENE_AVG32_RE.matches(lower) -> hasSeenSceneAvg32 = true
                SEEN_SCENE_REALLIVE_RE.matches(lower) -> hasSeenSceneReallive = true
                lower.endsWith(".mes") -> if (uk2MesNode == null) uk2MesNode = entry
                lower == "yscfg.dat" -> hasYscfgDat = true
                lower == "cs2.exe" -> hasCs2Exe = true
                lower.endsWith(".int") -> {
                    intCount++
                    if (lower in CS2_TYPICAL_INT_NAMES) typicalIntCount++
                }
                lower.endsWith(".cst") -> hasCst = true
                lower.endsWith(".cstl") -> hasCstl = true
                lower.endsWith(".hg3") -> hasHg3 = true
                lower.endsWith(".fes") -> hasFes = true
                lower.endsWith(".anm") -> hasAnm = true
                lower.endsWith(".kcs") -> hasKcs = true
                lower.endsWith(".ypf") -> hasYpf = true
                lower.endsWith(".ymv") -> hasYmv = true
                YS_DLL_NAME_RE.matches(lower) -> ysDllCount++
                lower == "0.txt" || lower == "00.txt" || lower == "nscript.dat" ||
                    lower == "onscript.nt2" || lower == "onscript.nt3" -> hasOnsScript = true
                lower.endsWith(".nsa") || lower.endsWith(".sar") -> hasOnsArchive = true
                lower.endsWith(".xp3") -> xp3Files.add(childRel)
                lower.endsWith(".rgssad") -> if (firstRgssad == null) firstRgssad = childRel
                lower.endsWith(".rgss2a") -> if (firstRgss2a == null) firstRgss2a = childRel
                lower.endsWith(".rgss3a") -> if (firstRgss3a == null) firstRgss3a = childRel
                rel.isEmpty() && lower.endsWith(".swf") && !lower.startsWith(".") -> {
                    swfCount++
                    if (firstSwf == null) firstSwf = nameOf(entry)
                }
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
                // FVP（rfvp）：根目录脚本（原版 *.hcb / 汉化 *.bch）+ 资源包特征
                rel.isEmpty() && (lower.endsWith(".hcb") || lower.endsWith(".bch")) -> hasFvpScript = true
                rel.isEmpty() && lower in FVP_PACK_NAMES -> hasFvpPack = true
                // System3.x（xsystem35）：根目录级 ALD 与辅助特征（GAMEDATA/ 子目录见 collect 专用分支）
                rel.isEmpty() && lower.endsWith(".ald") && lower.length >= XS35_ALD_MIN_LENGTH -> {
                    hasAldScript = true
                    aldCount++
                }
                rel.isEmpty() && isXs35SupportName(lower) -> {
                    hasXs35Support = true
                    // 精确名 System39.ini / System39.ain 是 System 3.x 标记（System 4 判定的护栏）
                    if (lower in XS35_SUPPORT_EXACT_NAMES) hasSystem39Marker = true
                }
                // System4（xsystem4）：根目录入口 ini 与 *.ain（ain 只作 ini 的附属条件，不单独成判据；
                // system39.ain 已由上一分支计入 System 3.x 标记，此处显式排除）
                rel.isEmpty() && lower == XS4_SYSTEM40_INI -> system40IniNode = entry
                rel.isEmpty() && lower == XS4_ALICESTART_INI -> aliceStartIniNode = entry
                rel.isEmpty() && lower.endsWith(XS4_AIN_EXTENSION) && lower !in XS35_SUPPORT_EXACT_NAMES -> hasAin = true
            }
        }
        children.forEach { collect(it, "") }

        if (hasGameexeDat && hasScenePck) {
            return Detection(EngineType.SIGLUS, 96, LAUNCH_TARGET_GAME_DIR)
        }
        if (hasFvpScript && hasFvpPack) {
            return Detection(EngineType.FVP, 96, LAUNCH_TARGET_GAME_DIR)
        }
        if (hasFvpScript) {
            return Detection(EngineType.FVP, 88, LAUNCH_TARGET_GAME_DIR)
        }
        // ---- framebuffer 引擎（RealLive / AVG32 / UK2）----
        if (hasUk2Cfg) {
            return Detection(EngineType.UK2, 96, LAUNCH_TARGET_GAME_DIR)
        }
        val uk2Mes = uk2MesNode
        if (uk2Mes != null && hasUk2MesHeader(headOf(uk2Mes))) {
            return Detection(EngineType.UK2, 90, LAUNCH_TARGET_GAME_DIR)
        }
        // Preserve explicit UK2 evidence; only outrank its weak generic .mes fallback.
        if (!ambiguousEthornellSystem && ethornellSystemNode?.let { EthornellArchiveProbe.hasBootstrap(headOf(it), sizeOf(it)) } == true) {
            return Detection(EngineType.ETHORNELL, 96, LAUNCH_TARGET_GAME_DIR)
        }
        val seenKind = classifySeenArchive(seenArchiveNode?.let { headOf(it) })
        if (hasGameexeIni && seenKind != null) {
            return Detection(seenKind, 96, LAUNCH_TARGET_GAME_DIR)
        }
        if (hasGameexeIni && hasSeenSceneReallive) {
            return Detection(EngineType.REALLIVE, 92, LAUNCH_TARGET_GAME_DIR)
        }
        if (hasGameexeIni && hasSeenSceneAvg32) {
            return Detection(EngineType.AVG32, 92, LAUNCH_TARGET_GAME_DIR)
        }
        if (seenKind != null) {
            return Detection(seenKind, 80, LAUNCH_TARGET_GAME_DIR)
        }
        if (uk2MesNode != null) {
            return Detection(EngineType.UK2, 82, LAUNCH_TARGET_GAME_DIR)
        }
        if (hasGameexeIni && hasScenePck) {
            return Detection(EngineType.SIGLUS, 95, LAUNCH_TARGET_GAME_DIR)
        }
        if (hasGameexeDat || hasGameexeIni) {
            return Detection(EngineType.SIGLUS, 85, LAUNCH_TARGET_GAME_DIR)
        }
        if (hasScenePck && hasSelectIni && hasG00) {
            return Detection(EngineType.SIGLUS, 80, LAUNCH_TARGET_GAME_DIR)
        }
        if (hasYscfgDat && hasYpf) {
            return Detection(EngineType.YURIS, 96, LAUNCH_TARGET_GAME_DIR)
        }
        if (hasYpf) {
            return Detection(EngineType.YURIS, 90, LAUNCH_TARGET_GAME_DIR)
        }
        if (hasYscfgDat) {
            return Detection(EngineType.YURIS, 85, LAUNCH_TARGET_GAME_DIR)
        }
        if (ysDllCount >= 2) {
            return Detection(EngineType.YURIS, 80, LAUNCH_TARGET_GAME_DIR)
        }
        if (hasYmv) {
            return Detection(EngineType.YURIS, 75, LAUNCH_TARGET_GAME_DIR)
        }

        // CatSystem2（docs/cs2参考.md）：文件名层面的评分识别。
        // Runtime exe 常被改名（cs2.exe 仅作辅助），PE 版本信息检测不适用于仅名称可得的扫描链。
        val cs2Score = cs2Score(
            hasStartupXml = hasStartupXml,
            intCount = intCount,
            typicalIntCount = typicalIntCount,
            hasCst = hasCst,
            hasHg3 = hasHg3,
            hasCstl = hasCstl,
            hasFes = hasFes,
            hasAnm = hasAnm,
            hasKcs = hasKcs,
            hasCs2Exe = hasCs2Exe,
        )
        if (cs2Score >= CS2_MIN_SCORE) {
            return Detection(EngineType.CATSYSTEM2, cs2Score, LAUNCH_TARGET_GAME_DIR)
        }
        // System4（xsystem4）：根目录 System40.ini / AliceStart.ini 为硬特征，*.ain 为辅助。
        // 判定规则放在本函数末尾（所有既有引擎与 ONS 之后），此处只计算 XSYS35 / Ruffle 护栏用的 isSystem4。
        // 护栏用 !hasSystem39Marker 而非 hasXs35Support：后者含 .alk，System 4 同样用 ALK 资源包。
        // 嵌套目录（data/System40.ini）不构成特征，见 collect 的 rel.isEmpty() 约束。
        val system4IniNode = system40IniNode ?: aliceStartIniNode
        val isSystem4 = system4IniNode != null && !hasSystem39Marker
        // System3.x（xsystem35）：场景/图形档 *.ALD 为硬特征，System39.*/.wai/.bgi/.alk 为辅助。
        // 判定置于 CS2 之后、ARTEMIS 之前（即所有既有 native 引擎之后）：既有引擎优先，
        // 防止「既有引擎特征 + 杂散 ALD」被抢判为 XSYS35；XSYS35 真实目录不含既有引擎特征，
        // 召回不受影响。仅含非 S 前缀 ALD 的目录（如只有 GA.ALD，无法启动）会被低分识别为
        // XSYS35，由宿主启动预检（场景档 S*.ALD / adisk.ald 检查，见 Xs35Activity）兜底拦下。
        // 护栏 !isSystem4：System 4 根目录常含 *.ald（如 Rance6BA.ald），不护栏会被 hasAldScript 规则吞掉。
        if (!isSystem4 && hasAldScript && hasXs35Support) {
            return Detection(EngineType.XSYS35, 96, LAUNCH_TARGET_GAME_DIR)
        }
        if (!isSystem4 && hasAldScript && aldCount >= 2) {
            return Detection(EngineType.XSYS35, 90, LAUNCH_TARGET_GAME_DIR)
        }
        if (!isSystem4 && hasAldScript) {
            return Detection(EngineType.XSYS35, 80, LAUNCH_TARGET_GAME_DIR)
        }
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
        // Flash 包装目录：根目录 index.html/htm + 叶子目录（无子目录）+ 唯一顶层 SWF。
        // 必须排在通用 hasIndex -> WEB_OTHER 之前，但不得覆盖 Tyrano/RPGM/VN/Node 等既有判定，
        // 也不得抢走其后才判定的 KRKR/ONS/XSYS4 或 PSP/Switch ROM：
        // - 无根 index 的散装 SWF 由 romGamesForSaf/romGamesForFile 按文件入库；
        // - 有子目录的扫描根不能再被当成一个包装游戏吞掉整棵目录。
        val ruffleSwf = firstSwf
        val hasConsoleRomChild = children.any { child ->
            !isDirectory(child) && romEngineOf(nameOf(child)) in CONSOLE_ROM_ENGINES
        }
        if (
            hasRootIndex && !hasChildDirectory && swfCount == 1 && ruffleSwf != null &&
            !hasTyranoDir && !hasRpgMvCore && !hasRpgMzCore && !hasVnData && !hasAppAsar &&
            xp3Files.isEmpty() && !hasStartupTjs && !hasConfigTjs &&
            !hasOnsScript && !hasOnsArchive && !isSystem4 && !hasConsoleRomChild
        ) {
            return Detection(EngineType.RUFFLE, 88, ruffleSwf, RUFFLE_MODULE_ALIAS)
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
        // System4（xsystem4）：排在所有既有引擎（含 ONS）之后、UNKNOWN 之前。
        // 既有引擎目录若混入杂散 System40.ini / AliceStart.ini，仍判原引擎；System 4 真实目录不含既有引擎特征，召回不受影响。
        // 与 XSYS35 的关系见上方 isSystem4 护栏。标题只在判为 XSYS4 时才读取 ini。
        fun system4Title(): String? = system4IniNode?.let { node ->
            headOf(node)?.let { Xs4Ini.parse(it).gameName }
        }
        if (isSystem4 && hasAin) {
            return Detection(EngineType.XSYS4, 97, LAUNCH_TARGET_GAME_DIR, titleHint = system4Title())
        }
        if (isSystem4) {
            return Detection(EngineType.XSYS4, 90, LAUNCH_TARGET_GAME_DIR, titleHint = system4Title())
        }
        return UNKNOWN_DETECTION
    }

    /** UK2 `.MES` 头判定（读到内容时使用）。 */
    private fun hasUk2MesHeader(head: ByteArray?): Boolean {
        if (head == null || head.size < UK2_MES_MAGIC.size) return false
        return UK2_MES_MAGIC.indices.all { head[it] == UK2_MES_MAGIC[it] }
    }

    /** System3.x（xsystem35）辅助特征文件名判定（调用方传 lowercase 名称）。 */
    private fun isXs35SupportName(lowerName: String): Boolean =
        lowerName in XS35_SUPPORT_EXACT_NAMES || XS35_SUPPORT_EXTENSIONS.any(lowerName::endsWith)

    /**
     * 区分共享 `SEEN.TXT` 文件名的 AVG32 与 RealLive：
     * `PACL` 头 → AVG32；10000 项 TOC（每项 8 字节 offset/length，场景头 `0x1d0`/`0x1cc`）→ RealLive。
     * 参考上游 `engine-detect`，只读文件头即可判定。
     */
    private fun classifySeenArchive(head: ByteArray?): EngineType? {
        if (head == null || head.size < 8) return null
        if (head.size >= 4 &&
            head[0] == 'P'.code.toByte() &&
            head[1] == 'A'.code.toByte() &&
            head[2] == 'C'.code.toByte() &&
            head[3] == 'L'.code.toByte()
        ) {
            return EngineType.AVG32
        }
        var valid = 0
        for (index in 0 until 4) {
            val at = index * 8
            if (at + 8 > head.size) break
            val offset = readU32(head, at)
            val length = readU32(head, at + 4)
            if (offset == 0L || length < 4) continue
            if (offset + 4 <= head.size) {
                val header = readU32(head, offset.toInt())
                if (REALLIVE_HEADER_SIZES.none { it.toLong() == header }) return null
            }
            valid++
            if (valid >= 4) break
        }
        return if (valid > 0) EngineType.REALLIVE else null
    }

    private fun readU32(bytes: ByteArray, at: Int): Long {
        if (at < 0 || at + 4 > bytes.size) return 0
        return (bytes[at].toLong() and 0xFF) or
            ((bytes[at + 1].toLong() and 0xFF) shl 8) or
            ((bytes[at + 2].toLong() and 0xFF) shl 16) or
            ((bytes[at + 3].toLong() and 0xFF) shl 24)
    }

    const val LAUNCH_TARGET_GAME_DIR = "DIR"

    /** CatSystem2 判定阈值（§15 评分：`startup.xml + 多个 .int`（含典型名）即可达标）。 */
    private const val CS2_MIN_SCORE = 50

    /** §11 典型 INT 文件名。 */
    private val CS2_TYPICAL_INT_NAMES = setOf(
        "scene.int", "image.int", "config.int", "bgm.int", "se.int", "kcs.int",
    )

    /** FVP 资源包文件名（根目录特征，与 `*.hcb`/`*.bch` 脚本配合判定）。 */
    private val FVP_PACK_NAMES = setOf(
        "graph.bin", "graph_vis.bin", "bgm.bin", "se.bin", "se_env.bin", "se_sys.bin",
        "voice.bin", "voice2.bin", "etc.bin",
    )

    private val UNKNOWN_DETECTION = Detection(EngineType.UNKNOWN, 0, "")

    private fun localizedText(context: Context, stringRes: Int): String =
        AppLocaleController.wrap(context.applicationContext).getString(stringRes)

    private val ENGINE_SEARCH_DIRECTORIES = setOf(
        "data",
        "dat",
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

    private fun isGameUnderRoot(rootUriText: String, gameUriText: String): Boolean =
        GameRootMatcher.isGameUnderRoot(rootUriText, gameUriText)
}
