package com.tyranor.next.core.engine.external

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipInputStream
import kotlin.coroutines.cancellation.CancellationException

/** 日志标签（导入/恢复这类文件操作的失败归因，见各 `Log.w` 调用点）。 */
private const val TAG = "RpgmRtp"

/**
 * RTP 导入被拒的原因（类型化错误码，不带文案）。
 *
 * core 层禁止把本地化文案当返回值（AGENTS.md 错误处理协议）：此处只给类型与数值，
 * 可展示文案由 UI 层组装（见 `ui/settings/RpgMakerRtpMessages.kt`）。
 *
 * 导入入口直接返回本类型（null = 成功），不再维护「最近一次失败原因」这类全局状态：
 * 全局槽在并发/连续导入下会把上一次的原因挂到本次失败上，也让调用方必须记住「先读返回值
 * 再读原因」的隐式顺序。
 */
sealed interface RtpImportRejection {

    /** 条目数超过上限。 */
    data class EntryCountExceeded(val count: Int, val limit: Int) : RtpImportRejection

    /** 单条目解压后超过上限。 */
    data class EntrySizeExceeded(val size: Long, val limit: Long) : RtpImportRejection

    /** 累计解压量超过上限。 */
    data class TotalSizeExceeded(val size: Long, val limit: Long) : RtpImportRejection

    /** 无法创建目录（参数为目录名或条目名）。 */
    data class DirectoryCreateFailed(val name: String) : RtpImportRejection

    /**
     * 读写失败（源不可读、临时目录创建失败、替换目标失败等）。
     *
     * [name] 为相关路径/目录名，便于用户定位是「选错了目录」还是「存储空间不足」。
     */
    data class IoFailed(val name: String) : RtpImportRejection

    /** 源里没有任何可导入内容（空 zip / 空目录，或全被元数据过滤掉）。 */
    data object EmptyArchive : RtpImportRejection
}

/**
 * RPGM 外置模块的共享目录管理（RTP / 自定义字体）。
 *
 * 目录契约由 RPGM 插件固定（插件 `MainActivity` 将
 * `/sdcard/JoiPlay/RTP/<RPGXP|RPGVX|RPGVXACE|mkxp-z>/app` 挂载为 RTP 根）：
 * 因此 RTP 与自定义字体必须落在共享存储，App 私有目录外置插件不可读。
 *
 * 所有方法允许失败，且失败一律以**类型化结果**表达（`RtpImportRejection?` / null 或
 * `Boolean` / `String?`），由调用方按非致命处理；界面文案在 ui 层组装。
 */
object RpgMakerRuntimeEnvironment {
    private const val RTP_PARENT = "JoiPlay/RTP"
    private const val FONT_PARENT = "JoiPlay/fonts"

    /**
     * 导入临时目录名前缀（**每次导入唯一**，见 [newImportTempDir]）。
     *
     * 历史实现用固定名 `.import_tmp`：两次导入同时进行时，后者会 `deleteRecursively()`
     * 删掉前者正在写入的目录，先提交者把两次内容**混合** rename 成 `app`（`isRtpImported`
     * 仍报「已导入」），后提交者以「读写失败：.import_tmp」失败。因此临时目录必须逐次唯一。
     */
    internal const val IMPORT_TEMP_DIR = ".import_tmp"

    /**
     * 陈旧临时目录的老化阈值：超过该年龄才在下次导入/清除时清理。
     *
     * 不清「新鲜」的临时目录是有意为之——它可能是并发的另一次导入正在写入的目录
     * （UI 有在途守卫，但守卫之外仍可能并发），删掉它等于让那次导入失败。
     */
    private const val STALE_IMPORT_TEMP_AGE_MS = 24L * 60L * 60L * 1000L

    /** 同进程内并发导入的序号；配合每进程随机盐保证唯一（纯 JVM 单测也可用，不依赖 android.os）。 */
    private val importTempSeq = java.util.concurrent.atomic.AtomicInteger(0)

    /** 每进程随机盐：跨进程（多开/残留进程）并发导入同样不会撞名。 */
    private val importTempSalt = java.util.UUID.randomUUID().toString().take(8)

    /** 备份式替换用的旧目录暂存名（与 [IMPORT_TEMP_DIR] 同层，替换成功即删）。 */
    private const val IMPORT_BACKUP_DIR = ".import_backup"

    /**
     * 提交标记名（与 [IMPORT_BACKUP_DIR] 同层）。
     *
     * 备份式替换在「旧目录已移开、新内容未顶上」之间被杀时，`target` 缺失而旧数据在
     * 备份里，尚可归位；但 `copyRecursively` 只拷了一半就被杀时，`target` 存在且非空，
     * 仅凭目录是否存在无法与「完整导入」区分。因此替换期间先落 `.importing` 标记，
     * 成功收尾才删除——**标记存在即表示 target 不可信**（见 [recoverPendingImport]）。
     */
    private const val IMPORT_MARKER_FILE = ".importing"

    /**
     * 恢复/提交/清除共用 `.import_backup`、`.importing` 这两个固定名，是同一台状态机：
     * 导入在 IO 线程、`isRtpImported`/清除在 UI 线程，交错会互相踩踏（如把「替换中」的
     * 半成品 target 判成有效、或把刚写下的标记当残留删掉），因此这三段必须互斥。
     */
    private val importLock = Any()

    /**
     * 在途导入登记：`rtpDirName(gameType) → 该子类型下所有在途导入的临时目录`（进程级，
     * 跨设置页重建存活）。
     *
     * 用途见 [clearRtp]：清除必须能识别「同子类型有导入正在写」，否则会删掉别人的临时目录，
     * 让那次导入以残缺内容提交。导入结束（成功/失败/异常）在 `finally` 里注销。
     *
     * 三条约定（第八轮复审修正）：
     *  - **键按 [rtpDirName] 归一**：与目录名同源。否则同一子类型的两种拼写（大小写/前后空白）
     *    各登记一份，而 `clearRtp` 只认其中一种，等于没有互斥。
     *  - **值是集合而非单个 File**：同子类型允许两次导入并发（UI 置灰只是便利，可被页面重建绕过）。
     *    单个 File 时键上只留**最后一次**登记：后登记者若先结束，它的注销会摘掉整条键，让先登记
     *    （仍在途）的那次「无登记」，清除便能删掉它正在写的目录。反过来（先登记者先结束）时，
     *    条件注销 `remove(key, value)` 与当前值不匹配、是空操作，缺陷才不显现。
     *  - **登记/注销与 [clearRtp] 的检查互斥**（[importLock]）：登记在锁内、且早于临时目录的
     *    `mkdirs`，因此「清除通过检查 → 删除所有临时目录」与「导入登记 → 建目录」不可能交错；
     *    长耗时的解压仍在锁外，不会把清除阻塞住（提交段在锁内，但 `renameTo` 成功时是 O(1)，
     *    只有当 rename 失败退回整目录 `copyRecursively` 时才会长时间占锁）。
     *
     * `internal` 仅为单测可注入（生产只由 zip/目录两条导入路径写入）。
     */
    internal val inFlightImports =
        java.util.concurrent.ConcurrentHashMap<String, MutableSet<File>>()

    // 用户挑选的 RTP 包解压上限。数值与 NativePluginInstaller.unzipSafely **不同**
    // （那边是 32 条目 / 512MiB 总量，面向内置插件包）：RTP 是整包素材，条目数与总量
    // 都大得多，因此这里给到 10 万条目 / 4GiB、单条目同为 256MiB
    // （早期版本用 2 万 / 1GiB，对含音频的整包素材偏紧，属功能收窄）。
    // 三者都只是为了拦住 zip 炸弹（高压缩比）这一类构造，正常素材包远不会触及。
    private const val MAX_ZIP_ENTRIES = 100_000
    private const val MAX_ENTRY_UNCOMPRESSED_BYTES = 256L * 1024L * 1024L
    private const val MAX_TOTAL_UNCOMPRESSED_BYTES = 4L * 1024L * 1024L * 1024L
    private const val ZIP_BUFFER_SIZE = 64 * 1024

    private val FONT_EXTENSIONS = listOf(".ttf", ".ttc", ".otf", ".otc")

    fun rtpDirName(gameType: String): String = when (gameType.trim().lowercase(Locale.ROOT)) {
        "rpgmxp" -> "RPGXP"
        "rpgmvx" -> "RPGVX"
        "mkxp-z" -> "mkxp-z"
        else -> "RPGVXACE"
    }

    fun rtpDir(gameType: String): File =
        File(Environment.getExternalStorageDirectory(), "$RTP_PARENT/${rtpDirName(gameType)}")

    /**
     * RTP 根目录（`.../<子类型>/app`）。
     *
     * 顺带做一次**待替换现场的归位**：备份式替换若在「旧目录已改名、新内容未顶上」之间
     * 被杀，这里把旧目录放回去。少了这一步，[isRtpImported] 会报「未导入」、游戏也读不到
     * RTP，而盘上其实还留着完整的旧数据。归位是幂等的（目标已有效时不做任何事）。
     */
    fun rtpAppDir(gameType: String): File {
        val dir = File(rtpDir(gameType), "app")
        recoverPendingImport(dir)
        return dir
    }

    /**
     * RTP 根目录（`.../<子类型>/app`），**仅在无未收尾现场时**返回。
     *
     * 与 [rtpAppDir] 的区别：归位失败（如「标记已删、备份 rename 回 target 失败」留下
     * 「无标记 + 仅备份 + target 缺失」）时返回 null。**会向 app 目录写入**的调用方必须用本
     * 方法——若此时先把 target 建成非空（如 mkdirs + 写 sf.sf2），下一次恢复会把备份当成
     * 「已成功替换的陈旧孤儿」删掉，旧 RTP 永久丢失。
     */
    internal fun rtpAppDirIfReady(gameType: String): File? = rtpAppDirIfReady(rtpDir(gameType))

    /** [rtpAppDirIfReady] 的可测重载：以 [root] 为 `<子类型>` 目录，[delete] 可注入失败。 */
    internal fun rtpAppDirIfReady(
        root: File,
        delete: (File) -> Boolean = { it.deleteRecursively() },
    ): File? {
        val dir = File(root, "app")
        return if (recoverPendingImport(dir, delete)) dir else null
    }

    /**
     * 把中断的替换留下的现场归位（幂等）。
     *
     * 现场有两种形态：
     *  - **提交标记 `.importing`**：上一次替换未正常收尾，`target` 可能是半成品（备份式
     *    替换在「旧目录已改名、新内容未顶上」之间被杀，或 `copyRecursively` 只拷了一半）。
     *    有备份 → 删掉半成品 target、把备份归位；无备份 → 首次导入的半成品，无旧数据可
     *    保护，整体清除（连同标记）。
     *  - **仅备份（旧版遗留）**：目标缺失**或为空目录**、备份在 → 归位备份。目标为空目录
     *    只可能是上次替换在 `copyRecursively` 刚建目录就被杀留下的半成品，备份才是完整
     *    旧数据；[isRtpImported] 只看目录非空，不归位会让它误报「未导入」。
     *
     * **删除失败必须中止恢复**：半成品 target 删不掉时若继续删标记、声称成功，下次恢复
     * 会把「非空 target + 备份」判成「已成功替换的残留」而删掉备份——那是盘上唯一的旧
     * 数据。此时保留「标记 + 备份」现场，等下次重试（[delete] 可注入以便测试）。
     *
     * @return false = 现场无法收拾（备份路径被占等），调用方应放弃本次替换（不丢数据）
     */
    internal fun recoverPendingImport(
        target: File,
        delete: (File) -> Boolean = { it.deleteRecursively() },
    ): Boolean = synchronized(importLock) {
        val parent = target.parentFile ?: return@synchronized false
        val backup = File(parent, IMPORT_BACKUP_DIR)
        val marker = File(parent, IMPORT_MARKER_FILE)
        if (marker.exists()) {
            if (backup.exists()) {
                if (target.exists() && !delete(target)) return@synchronized false
                if (!marker.delete()) return@synchronized false
                if (!backup.renameTo(target)) return@synchronized false
            } else {
                if (target.exists() && !delete(target)) return@synchronized false
                if (!marker.delete()) return@synchronized false
            }
            return@synchronized true
        }
        if (backup.exists()) {
            if (!target.exists() || target.listFiles()?.isEmpty() == true) {
                // 目标缺失或为空目录：备份是唯一旧数据（或完整旧数据），归位
                if (target.exists() && !delete(target)) return@synchronized false
                if (!backup.renameTo(target)) return@synchronized false
            } else {
                // 目标有效：备份是上一次已成功替换的残留（成功路径清理失败留下的孤儿）。
                // 删不掉会占住 .import_backup 路径，使后续 target.renameTo(backup) 失败并
                // 被迫走「删旧数据」分支——直接返回失败更安全（不丢数据）
                if (!delete(backup)) return@synchronized false
            }
        }
        return@synchronized true
    }

    /** RTP 是否已导入（app 目录存在且非空）。 */
    fun isRtpImported(gameType: String): Boolean {
        val dir = rtpAppDir(gameType)
        return dir.isDirectory && (dir.listFiles()?.isNotEmpty() == true)
    }

    /**
     * RTP 清除结果（core→ui 类型化协议；文案由 UI 组装）。
     *
     * [IMPORT_IN_FLIGHT] 是独立分支而不是并入 [FAILED]：清除与在途导入并发时，把导入的临时
     * 目录删掉会让它拿着残缺内容继续提交（把半成品装成 `app` 并删掉备份），必须直接拒绝。
     */
    enum class RtpClearResult {
        /** 已清除（或本就未导入）。 */
        CLEARED,

        /** IO 失败（标记/备份/app 任一步删不掉），可让用户重试。 */
        FAILED,

        /** 该子类型有导入在途：此时不得触碰导入现场，等导入结束后再清除。 */
        IMPORT_IN_FLIGHT,
    }

    /**
     * 清除该子类型的 RTP（仅删 `app` 层级，保留同目录其余用户内容）。
     *
     * 顺带清理备份与提交标记：只删 `app` 的话，中断替换留下的备份会在下次
     * [recoverPendingImport] 时被**归位**——用户看到「清除成功却又回来了」。
     * 顺序：先删标记（标记在则恢复逻辑会把备份归位），再删备份，最后删 app；
     * 任一步失败都返回 [RtpClearResult.FAILED] 让用户重试，不留半个现场。
     *
     * **与在途导入的互斥由本类承担**（[inFlightImports] 进程级登记），不依赖 UI 的按钮置灰：
     * 后者可被「退出/重建设置页」「导入 A 期间又导入 B」绕过，而这里删掉别人正在写的临时
     * 目录会让那次导入以残缺内容提交。同子类型（含大小写/空白变体，键按 [rtpDirName] 归一）
     * 有登记时直接返回 [RtpClearResult.IMPORT_IN_FLIGHT]。
     *
     * 互斥是**完整**的：登记在 [importLock] 内、且早于导入侧的 `mkdirs`（见
     * [registerInFlightImport]），所以本方法的「查登记 → 按 `Long.MAX_VALUE` 清空临时目录」
     * 与「另一次导入登记 → 建临时目录」不可能交错。正因如此，走到清理临时目录这一步时同目录内
     * 不可能有在途导入，残留只可能是崩溃留下的半成品，可以全清。
     */
    fun clearRtp(gameType: String): RtpClearResult = synchronized(importLock) {
        if (hasInFlightImport(gameType)) return@synchronized RtpClearResult.IMPORT_IN_FLIGHT
        val root = rtpDir(gameType)
        val appDir = File(root, "app")
        val backup = File(root, IMPORT_BACKUP_DIR)
        val marker = File(root, IMPORT_MARKER_FILE)
        deleteStaleImportTemps(root, keep = null, staleBefore = Long.MAX_VALUE)
        if (marker.exists() && !marker.delete()) return@synchronized RtpClearResult.FAILED
        if (backup.exists() && !backup.deleteRecursively()) return@synchronized RtpClearResult.FAILED
        if (!appDir.exists()) return@synchronized RtpClearResult.CLEARED
        return@synchronized if (appDir.deleteRecursively()) RtpClearResult.CLEARED else RtpClearResult.FAILED
    }

    /**
     * 该子类型是否有导入在途。
     *
     * 供 UI 恢复「导入中」态：Composable 里的在途标记会随页面重建丢失，而导入在 IO 线程
     * 继续跑（阻塞式文件 IO 不响应协程取消）。
     */
    fun isRtpImportInFlight(gameType: String): Boolean = hasInFlightImport(gameType)

    /** 在途判定（键按 [rtpDirName] 归一；[clearRtp] 在 [importLock] 内复用本判定）。 */
    private fun hasInFlightImport(gameType: String): Boolean =
        inFlightImports[inFlightKey(gameType)]?.isNotEmpty() == true

    /** 在途登记键：与 [rtpDir] 的目录名同源，保证同一子类型的任意拼写命中同一条登记。 */
    private fun inFlightKey(gameType: String): String = rtpDirName(gameType)

    /**
     * 登记一次在途导入（**锁内**，与 [clearRtp] 的「查登记 → 清临时目录」互斥）。
     *
     * 调用方必须在登记**之后**再创建临时目录：这样「清除已清完全部临时目录」与「导入建目录」
     * 不可能交错——要么清除先跑完（此时目录尚未创建，不会被删），要么清除看到本登记而拒绝。
     * `internal` 供单测注入。
     */
    internal fun registerInFlightImport(gameType: String, temp: File) {
        synchronized(importLock) {
            inFlightImports.getOrPut(inFlightKey(gameType)) { newInFlightSet() }.add(temp)
        }
    }

    /**
     * 注销一次在途导入（**锁内**）。
     *
     * 只在集合为空时摘除键：同子类型两次导入并发时，先结束者的注销不得把仍在途的那次
     * 一并清掉（否则清除会删掉它正在写的目录）。`internal` 供单测注入。
     */
    internal fun unregisterInFlightImport(gameType: String, temp: File) {
        synchronized(importLock) {
            val key = inFlightKey(gameType)
            val set = inFlightImports[key] ?: return@synchronized
            set.remove(temp)
            if (set.isEmpty()) inFlightImports.remove(key, set)
        }
    }

    /** 并发集合：登记/注销在锁内，但 [isRtpImportInFlight] 会被 UI 线程无锁读取。 */
    private fun newInFlightSet(): MutableSet<File> =
        java.util.concurrent.ConcurrentHashMap.newKeySet()

    /**
     * 分配本次导入专用的临时目录（`<parent>/.import_tmp.<盐><序号>`）。
     *
     * 唯一性是并发导入不互相破坏的前提：两次导入各写各的目录，提交段再由
     * [commitImportTemp] 的 `importLock` 串行化替换 `app`。
     * 不用 `android.os.Process.myPid()`：纯 JVM 单测（本文件大量语义都靠它锚定）无法调用。
     */
    internal fun newImportTempDir(parent: File): File =
        File(parent, "$IMPORT_TEMP_DIR.$importTempSalt${importTempSeq.incrementAndGet()}")

    /**
     * 清理**陈旧**的导入临时目录（[newImportTempDir] 的产物之外的兄弟目录不动）。
     *
     * 兼容历史固定名 `.import_tmp`（旧版本崩溃残留）一并纳入清理范围。
     *
     * @param keep 当前在途的临时目录（若有），永不删除。
     * @param staleBefore 修改时间早于该时刻才算陈旧；默认「一天前」，避免删掉并发的
     *   另一次导入正在写入的目录（它一定是刚创建的）。清除路径传 [Long.MAX_VALUE] 表示全清。
     */
    internal fun deleteStaleImportTemps(
        parent: File,
        keep: File?,
        staleBefore: Long = System.currentTimeMillis() - STALE_IMPORT_TEMP_AGE_MS,
    ) {
        parent.listFiles { file ->
            file.isDirectory &&
                (file.name == IMPORT_TEMP_DIR || file.name.startsWith("$IMPORT_TEMP_DIR."))
        }?.forEach { dir ->
            if (keep != null && dir.absolutePath == keep.absolutePath) return@forEach
            if (dir.lastModified() >= staleBefore) return@forEach
            dir.deleteRecursively()
        }
    }

    /**
     * 从 SAF zip 导入 RTP：解压到临时目录 → 拍平单层根目录 → 替换 `app`。
     *
     * @return null 表示成功；否则为类型化拒绝原因（文案由 UI 组装）
     */
    fun importRtpZip(context: Context, gameType: String, uri: Uri): RtpImportRejection? {
        // 整段包在 try 里：rtpAppDir 会读外部存储状态、mkdirs 会做 IO，
        // 任一环节抛出都必须转成类型化失败，异常不得穿到 UI 层
        var created: File? = null
        return try {
            val target = rtpAppDir(gameType)
            val parent = target.parentFile ?: return RtpImportRejection.IoFailed(target.path)
            if (!parent.exists() && !parent.mkdirs()) return RtpImportRejection.IoFailed(parent.name)
            // 本次导入专属临时目录（并发导入各写各的，不共用固定名）
            val temp = newImportTempDir(parent)
            created = temp
            // 登记在途：清除（clearRtp）据此拒绝，避免删掉正在写入的临时目录。
            // 登记在锁内且早于下面的 mkdirs（见 registerInFlightImport）
            registerInFlightImport(gameType, temp)
            deleteStaleImportTemps(parent, keep = temp)
            temp.deleteRecursively()
            if (!temp.mkdirs()) return RtpImportRejection.IoFailed(temp.name)
            extractZip(context, uri, temp)?.let { return it }
            commitImportTemp(temp, target)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: java.util.zip.ZipException) {
            // 损坏/非法 zip：归因到源文件而非临时目录（否则用户看到的是「读写失败：.import_tmp」）
            RtpImportRejection.IoFailed(uri.lastPathSegment ?: "zip")
        } catch (_: Throwable) {
            // 磁盘满、权限不足、源不可读：
            // 都按类型化失败返回（本方法是 core 门面，调用方只按返回值分支）
            RtpImportRejection.IoFailed(created?.name ?: IMPORT_TEMP_DIR)
        } finally {
            created?.let { unregisterInFlightImport(gameType, it) }
            created?.deleteRecursively()
        }
    }

    /** 从 SAF 目录树导入 RTP：递归复制 → 拍平单层根目录 → 替换 `app`。 */
    fun importRtpTree(context: Context, gameType: String, treeUri: Uri): RtpImportRejection? {
        // 目录导入此前只有「失败」布尔，用户看不到原因（选错目录、空目录、存储空间不足
        // 的提示全都一样），这里与 zip 路径对齐给出类型化原因
        var created: File? = null
        return try {
            // fromTreeUri 对非 tree URI 会抛 IllegalArgumentException：必须在 try 内
            val source = DocumentFile.fromTreeUri(context, treeUri)
                ?: return RtpImportRejection.IoFailed(treeUri.lastPathSegment ?: "tree")
            val target = rtpAppDir(gameType)
            val parent = target.parentFile ?: return RtpImportRejection.IoFailed(target.path)
            if (!parent.exists() && !parent.mkdirs()) return RtpImportRejection.IoFailed(parent.name)
            // 本次导入专属临时目录（并发导入各写各的，不共用固定名）
            val temp = newImportTempDir(parent)
            created = temp
            // 登记在途：清除（clearRtp）据此拒绝，避免删掉正在写入的临时目录。
            // 登记在锁内且早于下面的 mkdirs（见 registerInFlightImport）
            registerInFlightImport(gameType, temp)
            deleteStaleImportTemps(parent, keep = temp)
            temp.deleteRecursively()
            if (!temp.mkdirs()) return RtpImportRejection.IoFailed(temp.name)
            copyDocumentTree(context, source, temp)?.let { return it }
            commitImportTemp(temp, target)
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            RtpImportRejection.IoFailed(created?.name ?: IMPORT_TEMP_DIR)
        } finally {
            created?.let { unregisterInFlightImport(gameType, it) }
            created?.deleteRecursively()
        }
    }

    /**
     * 临时目录 → `app` 的提交段（zip 与目录导入共用）。
     *
     * 两个导入路径的失败分支必须一致：拍平、替换与「导入后仍为空」的判定都在这里，
     * 否则两条路径会给出不同的失败原因。
     *
     * 替换采用**备份式** + **提交标记**：
     *  - 旧目录先改名成 `.import_backup`，新内容顶上后再删备份，失败即回滚；
     *  - `.importing` 标记在「旧目录已移开、新内容落位」前写盘、成功收尾才删除——
     *    存在即表示 target 不可信（可能只拷了一半），[recoverPendingImport] 据此
     *    恢复，而不是让 [isRtpImported] 误报「已导入」却缺素材；
     *  - **旧目录不能改名时直接放弃本次替换，绝不删旧数据**：删除后若安装又失败，
     *    旧 RTP 没有备份可回滚，将永久丢失；
     *  - 回滚时半成品**删不掉也必须中止**（保留「标记 + 备份」现场）：删掉标记继续走
     *    的话，下次恢复会把「非空 target + 备份」判成「已成功替换的残留」而删掉旧数据。
     *
     * 标记的落盘顺序（先改名、后写标记）是刻意的：标记 + 无备份 ⇔ 首次导入，恢复逻辑
     * 据此可安全清除半成品 target；若反过来先写标记再改名，在两步之间被杀会留下
     * 「标记 + 无备份 + 完整旧数据」——恢复逻辑无法把它与首次导入的半成品区分，
     * 会把完整旧数据当半成品删掉。先改名后写标记的窗口（备份 + 无标记 + 无 target）
     * 由任何先走 [recoverPendingImport] 的写入者归位（含启动路径，见
     * `RpgMakerExternalEngineModule.ensureRtpEnvironment`）。
     *
     * 先前的「先删旧目录再铺新内容」有两个后果——`copyRecursively` 中途失败会留下半成品
     * 目录，而 [isRtpImported] 只看目录非空，下次会显示「已导入」却缺素材；删除与替换之间
     * 被杀则旧 RTP 直接丢失。
     */
    internal fun commitImportTemp(
        temp: File,
        target: File,
        delete: (File) -> Boolean = { it.deleteRecursively() },
    ): RtpImportRejection? = synchronized(importLock) {
        flattenSingleRoot(temp)
        // 「无内容」在建目录前判定：空目录/空 zip 复制过去也只会得到空 app 目录，
        // 用户看到「导入成功」却没有任何素材。listFiles() 为 null 是 IO 错误而非空目录，
        // 按 IoFailed 上报（报成「没有内容」会把排查引到错误方向）
        val children = temp.listFiles() ?: return@synchronized RtpImportRejection.IoFailed(temp.name)
        if (children.isEmpty()) return@synchronized RtpImportRejection.EmptyArchive

        val parent = target.parentFile ?: return@synchronized RtpImportRejection.IoFailed(target.name)
        val backup = File(parent, IMPORT_BACKUP_DIR)
        val marker = File(parent, IMPORT_MARKER_FILE)
        // 上一次替换未正常收尾的现场（含半成品 target）先归位；收拾不了直接放弃本次替换
        if (!recoverPendingImport(target, delete)) {
            return@synchronized RtpImportRejection.IoFailed(target.name)
        }

        var hadTarget = false
        if (target.exists()) {
            if (!target.renameTo(backup)) {
                // 旧目录不能改名：**不删旧数据**。删除后若安装又失败，旧 RTP 将没有备份
                // 可回滚而永久丢失，直接放弃本次替换（旧数据保持原样）
                return@synchronized RtpImportRejection.IoFailed(target.name)
            }
            hadTarget = true
        }
        // 旧数据已移开（或本就无旧数据）后才置提交标记（次序说明见函数 KDoc）。
        // 置位失败回滚 rename 并放弃。
        if (!marker.createNewFile()) {
            if (hadTarget) runCatching { backup.renameTo(target) }
            return@synchronized RtpImportRejection.IoFailed(marker.name)
        }

        // `copyRecursively` 默认 onError 是**抛出**而非返回 false：中途 IO 失败会跳过下面
        // 整个回滚块（半成品 target + 标记 + 备份留在盘上，只能等下次 recover 归位），
        // 并把错误归因成「读写失败：.import_tmp」。这里把异常收敛成 installed=false，
        // 让本次调用就走完整回滚（清半成品 → 删标记 → 回滚备份）。
        // 不用 onError = TERMINATE：`copyRecursively` 在 TERMINATE 下的返回值不保证为
        // false（走查中断时仍可能返回 true），会把不完整安装当成成功提交。
        val installed = temp.renameTo(target) ||
            runCatching { temp.copyRecursively(target, overwrite = true) }
                // 用户侧只看到类型化「读写失败」，磁盘满 / 权限不足 / 目录被删无法区分：
                // 留日志（含异常）才能归因，且不为返回协议引入新分支
                .onFailure { Log.w(TAG, "rtp commit copy failed: ${target.absolutePath}", it) }
                .getOrDefault(false)
        if (!installed || target.listFiles()?.isNotEmpty() != true) {
            // 回滚：清掉半成品、删标记，把旧目录放回去。
            // 顺序很关键——标记必须先删：若先放回旧数据却留下标记，下次恢复会把
            // 「放回的旧数据」误当成首次导入半成品而删掉。
            // 半成品删不掉则保持「标记 + 备份」现场（旧数据仍在备份），由下次恢复重试。
            if (target.exists() && !delete(target)) {
                return@synchronized RtpImportRejection.IoFailed(target.name)
            }
            if (!marker.delete()) {
                return@synchronized RtpImportRejection.IoFailed(target.name)
            }
            if (hadTarget && backup.exists() && !backup.renameTo(target)) {
                return@synchronized RtpImportRejection.IoFailed(target.name)
            }
            return@synchronized if (installed) {
                RtpImportRejection.EmptyArchive
            } else {
                RtpImportRejection.IoFailed(target.name)
            }
        }
        // 成功：先删提交标记（提交点）再删备份。标记删不掉 = 无法确认提交（下次恢复会把
        // 新 target 回滚到旧数据），按失败处理；旧数据仍在备份，不丢。
        if (!marker.delete()) {
            return@synchronized RtpImportRejection.IoFailed(target.name)
        }
        // 备份清理失败不阻塞本次成功，但会占住 .import_backup 固定路径（下次导入的
        // target.renameTo(backup) 会因目标已存在而失败 → 导入持续失败）：改名让位，
        // 陈旧的孤儿备份留作磁盘残留即可，不再挡路。
        if (backup.exists() && !backup.deleteRecursively()) {
            runCatching {
                backup.renameTo(File(parent, "$IMPORT_BACKUP_DIR.${System.currentTimeMillis()}"))
            }
        }
        null
    }

    /**
     * 导入自定义字体到共享目录，返回插件可读的绝对路径；扩展名非法或 IO 失败返回 null。
     *
     * 每次导入均使用唯一文件；64MiB 实际字节上限、签名/平台字体解析验证后才发布路径。
     * 同名导入不会覆盖其它游戏仍引用的字体。
     */
    fun importCustomFont(context: Context, uri: Uri): String? {
        return try {
            val displayName = runCatching {
                context.contentResolver
                    .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            }.getOrNull() ?: uri.lastPathSegment
            val name = (displayName ?: "font.ttf").substringAfterLast('/').substringAfterLast('\\')
            if (FONT_EXTENSIONS.none { name.lowercase(Locale.ROOT).endsWith(it) }) return null
            val dir = File(Environment.getExternalStorageDirectory().canonicalFile, FONT_PARENT)
            if (!dir.isDirectory && !dir.mkdirs()) return null
            if (dir.canonicalFile != dir.absoluteFile) return null
            com.tyranor.next.core.settings.ImmutableFontImport.import(
                directory = dir, displayName = name,
                input = { context.contentResolver.openInputStream(uri) },
                validate = { android.graphics.Typeface.createFromFile(it) != null },
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 把字体源写入同目录 staging 文件；**实际写入 0 字节视为失败**（删除半成品并返回 false）。
     *
     * 不能用 `copyTo(...) != null` 判定：`copyTo` 返回 `Long`（字节数），非空判定恒真。
     * 于是 0 字节源（或第三方 SAF provider 的短读）会被 rename 成目标名，**原子地覆盖掉
     * 用户已导入的可用字体**，而函数注释承诺的「失败时旧字体保持原样」并不成立。
     */
    internal fun copyFontToStaging(input: InputStream, staging: File): Boolean {
        val written = staging.outputStream().use { output -> input.copyTo(output) }
        if (written <= 0L) {
            runCatching { staging.delete() }
            return false
        }
        return true
    }

    fun isCustomFontPresent(path: String): Boolean =
        path.isNotBlank() && File(path).exists()

    fun customFontFileName(path: String): String =
        path.substringAfterLast('/').substringAfterLast('\\')

    private fun extractZip(context: Context, uri: Uri, dest: File): RtpImportRejection? {
        val input = context.contentResolver.openInputStream(uri)
            ?: return RtpImportRejection.IoFailed(uri.lastPathSegment ?: "zip")
        return input.use { extractZipStream(it.buffered(), dest) }
    }

    /**
     * ZIP 解压主体。与 [extractZip] 拆开是为了可单测：本函数只依赖输入流，
     * 不需要 Context/Uri；上限参数默认取生产常量，测试注入小值即可低成本覆盖边界。
     *
     * @return null = 成功；否则为拒绝原因（含 IO/目录创建失败）
     */
    internal fun extractZipStream(
        input: InputStream,
        dest: File,
        maxEntries: Int = MAX_ZIP_ENTRIES,
        maxEntryBytes: Long = MAX_ENTRY_UNCOMPRESSED_BYTES,
        maxTotalBytes: Long = MAX_TOTAL_UNCOMPRESSED_BYTES,
    ): RtpImportRejection? {
        val tempRoot = dest.canonicalPath
        var entryCount = 0
        var totalBytes = 0L
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                // 先计数再判断跳过：__MACOSX/ 与 .DS_Store 也是条目，
                // 若跳过后才计数，构造大量此类条目即可绕过上限、持续消耗解析与 I/O
                entryCount += 1
                if (entryCount > maxEntries) {
                    return RtpImportRejection.EntryCountExceeded(entryCount, maxEntries)
                }
                // 声明大小检查对所有条目生效（含被过滤的）。流式 zip 的 entry.size 恒为 -1，
                // 故这里只是廉价的前置拒绝，真正的兜底是下面的有界读取。
                if (entry.size > maxEntryBytes) {
                    return RtpImportRejection.EntrySizeExceeded(entry.size, maxEntryBytes)
                }

                val name = entry.name.replace('\\', '/')
                val outFile = File(dest, name)
                val insideRoot = outFile.canonicalPath.startsWith(tempRoot + File.separator)
                val filtered = name.startsWith("__MACOSX/") || name.contains(".DS_Store")
                if (filtered || !insideRoot) {
                    // 跳过也必须**有界**读取：ZipInputStream 是顺序流，跳过条目必须读掉其数据，
                    // 而 closeEntry() 会把整条无界读完（DEFLATED 条目被完整解压）。
                    // 实测：255KB 的 zip（条目名 __MACOSX/big.bin、解压后 256MB）光
                    // closeEntry() 就耗时 586ms —— 被过滤条目因此是绕过字节上限的通道。
                    when (val drained = drainEntryBounded(zip, maxEntryBytes, maxTotalBytes, totalBytes)) {
                        is DrainResult.Drained -> totalBytes = drained.total
                        is DrainResult.EntryOver ->
                            return RtpImportRejection.EntrySizeExceeded(drained.bytes, maxEntryBytes)
                        is DrainResult.TotalOver ->
                            return RtpImportRejection.TotalSizeExceeded(drained.bytes, maxTotalBytes)
                    }
                    zip.closeEntry()
                    continue
                }

                if (entry.isDirectory) {
                    if (!outFile.exists() && !outFile.mkdirs()) {
                        return RtpImportRejection.DirectoryCreateFailed(outFile.name)
                    }
                } else {
                    outFile.parentFile?.let { parent ->
                        if (!parent.exists() && !parent.mkdirs()) {
                            return RtpImportRejection.DirectoryCreateFailed(parent.name)
                        }
                    }
                    // 上限与 NativePluginInstaller.unzipSafely 一致：用户挑选的 zip 也可能
                    // 是损坏或精心构造的（高压缩比），无界解压会写满共享存储。
                    // copyTo 不提供计数，故手工逐块读写并同时校验单条目与总量。
                    // 超限/IO 失败时删除半成品：被中止的条目会留下**截断文件**，
                    // 而导入失败后整个临时目录会被丢弃，残留文件只会误导排查；
                    // 若调用方复用了目标目录，截断文件还会被当成有效内容。
                    var written = false
                    try {
                        outFile.outputStream().use { output ->
                            val buffer = ByteArray(ZIP_BUFFER_SIZE)
                            var entryBytes = 0L
                            while (true) {
                                val read = zip.read(buffer)
                                if (read < 0) break
                                entryBytes += read
                                totalBytes += read
                                // 与排空路径同类判定：这里此前只返回 false 不带原因，
                                // 用户拿到的是一句无信息量的「导入失败」
                                if (entryBytes > maxEntryBytes) {
                                    return RtpImportRejection.EntrySizeExceeded(entryBytes, maxEntryBytes)
                                }
                                if (totalBytes > maxTotalBytes) {
                                    return RtpImportRejection.TotalSizeExceeded(totalBytes, maxTotalBytes)
                                }
                                output.write(buffer, 0, read)
                            }
                        }
                        written = true
                    } catch (_: Throwable) {
                        return RtpImportRejection.IoFailed(outFile.name)
                    } finally {
                        if (!written) {
                            runCatching { outFile.delete() }
                        }
                    }
                }
                zip.closeEntry()
            }
        }
        return if (dest.listFiles()?.isNotEmpty() == true) null else RtpImportRejection.EmptyArchive
    }

    /** 有界排空的结果：成功给出新的累计值，超限给出**区分类型**的拒绝原因。 */
    private sealed interface DrainResult {
        data class Drained(val total: Long) : DrainResult

        /** 单条目超限（[bytes] 为该条目实际解压量）。 */
        data class EntryOver(val bytes: Long) : DrainResult

        /** 累计超限（[bytes] 为触发时的实际累计值）。 */
        data class TotalOver(val bytes: Long) : DrainResult
    }

    /**
     * 有界排空当前条目的剩余数据并计入累计字节。
     *
     * 与解压写入路径共用同一对上限，确保被过滤/越界的条目不能成为绕过字节上限的通道。
     * 两类超限分别上报：合并成一个错误码会产出「0 B 超过 1 MiB」这类自相矛盾的提示
     * （单条目超限时，进入该条目之前的累计值可能远小于上限）。
     */
    private fun drainEntryBounded(
        zip: ZipInputStream,
        maxEntryBytes: Long,
        maxTotalBytes: Long,
        runningTotal: Long,
    ): DrainResult {
        val buffer = ByteArray(ZIP_BUFFER_SIZE)
        var entryBytes = 0L
        var total = runningTotal
        while (true) {
            val read = zip.read(buffer)
            if (read < 0) break
            entryBytes += read
            total += read
            if (entryBytes > maxEntryBytes) return DrainResult.EntryOver(entryBytes)
            if (total > maxTotalBytes) return DrainResult.TotalOver(total)
        }
        return DrainResult.Drained(total)
    }

    /** 递归复制的累计计数（跨递归层共享）。 */
    private class CopyCounters(var entries: Int = 0, var totalBytes: Long = 0L)

    /**
     * 递归复制 SAF 目录树；失败给出类型化原因（null = 成功）。
     *
     * 与 zip 路径共用同一组上限（条目数 / 单文件 / 累计字节，见 [extractZipStream]）：
     * 目录树同样可能被构造得巨大（SAF 树没有压缩比问题，但条目数与总量仍可无界），
     * 无上限复制会写满共享存储。
     */
    private fun copyDocumentTree(
        context: Context,
        source: DocumentFile,
        dest: File,
        maxEntries: Int = MAX_ZIP_ENTRIES,
        maxEntryBytes: Long = MAX_ENTRY_UNCOMPRESSED_BYTES,
        maxTotalBytes: Long = MAX_TOTAL_UNCOMPRESSED_BYTES,
        counters: CopyCounters = CopyCounters(),
    ): RtpImportRejection? {
        val destRoot = runCatching { dest.canonicalPath }.getOrNull() ?: dest.absolutePath
        for (child in source.listFiles()) {
            val name = child.name ?: continue
            // 与 zip 路径同一套包含校验：`DocumentFile.name` 完全由 DocumentsProvider 提供，
            // 恶意 provider 可以返回 `../x`、`a/b` 或绝对路径，直接拼 `File(dest, name)`
            // 会借本应用的 MANAGE_EXTERNAL_STORAGE 在共享存储任意位置创建/截断文件
            // （Zip Slip 的 SAF 版本）。不安全的名字直接跳过，不中止整次导入。
            val childFile = safeChild(dest, destRoot, name) ?: continue
            if (child.isDirectory) {
                if (!childFile.exists() && !childFile.mkdirs()) {
                    return RtpImportRejection.DirectoryCreateFailed(name)
                }
                copyDocumentTree(context, child, childFile, maxEntries, maxEntryBytes, maxTotalBytes, counters)
                    ?.let { return it }
            } else {
                // 条目数先计数再复制：构造海量小文件同样能拖垮导入
                counters.entries += 1
                if (counters.entries > maxEntries) {
                    return RtpImportRejection.EntryCountExceeded(counters.entries, maxEntries)
                }
                val target = childFile
                // 上限超限走类型化原因；openInputStream 为 null / 拷贝抛异常归为 IoFailed。
                // 半成品文件必须删掉：调用方会把整个临时目录丢弃，但若将来复用，
                // 截断文件会被当成有效素材。
                var limitRejection: RtpImportRejection? = null
                val copied = runCatching {
                    val input = context.contentResolver.openInputStream(child.uri)
                    if (input == null) {
                        false
                    } else {
                        input.use { src ->
                            target.outputStream().use { dst ->
                                limitRejection = copyFileBounded(src, dst, maxEntryBytes, maxTotalBytes, counters)
                            }
                        }
                        limitRejection == null
                    }
                }.onFailure { Log.w(TAG, "rtp tree copy failed: ${child.uri}", it) }
                    .getOrDefault(false)
                if (!copied) {
                    runCatching { target.delete() }
                    return limitRejection ?: RtpImportRejection.IoFailed(name)
                }
            }
        }
        return null
    }

    /**
     * 把 provider 给的条目名安全地落到 [dest] 之下；不安全返回 null（调用方跳过该条目）。
     *
     * 拒绝三类名字：空串、含路径分隔符（`/` `\`）、以及规范化后不在 [destRoot] 之内
     * （`..`、绝对路径、符号链接绕行）。与 zip 条目共用同一判定口径。
     */
    private fun safeChild(dest: File, destRoot: String, name: String): File? {
        if (name.isBlank()) return null
        if (name.contains('/') || name.contains('\\')) return null
        if (name == "." || name == "..") return null
        val file = File(dest, name)
        val canonical = runCatching { file.canonicalPath }.getOrNull() ?: return null
        return if (canonical.startsWith(destRoot + File.separator)) file else null
    }

    /**
     * 有界拷贝单个文件：逐块读写并同时校验单文件与累计总量（与 zip 路径共用上限）。
     *
     * @return null = 成功；否则为超限拒绝原因
     */
    private fun copyFileBounded(
        input: InputStream,
        output: java.io.OutputStream,
        maxEntryBytes: Long,
        maxTotalBytes: Long,
        counters: CopyCounters,
    ): RtpImportRejection? {
        val buffer = ByteArray(ZIP_BUFFER_SIZE)
        var fileBytes = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            fileBytes += read
            counters.totalBytes += read
            if (fileBytes > maxEntryBytes) {
                return RtpImportRejection.EntrySizeExceeded(fileBytes, maxEntryBytes)
            }
            if (counters.totalBytes > maxTotalBytes) {
                return RtpImportRejection.TotalSizeExceeded(counters.totalBytes, maxTotalBytes)
            }
            output.write(buffer, 0, read)
        }
        return null
    }

    /**
     * 若目录下仅有一个子目录且无文件，把该子目录内容上移一层：
     * 兼容官方 RTP 包 `RPGVXAce_RTP/Graphics/...` 这类多包一层根目录的结构。
     */
    private fun flattenSingleRoot(dir: File) {
        val children = dir.listFiles() ?: return
        if (children.size != 1 || !children[0].isDirectory) return
        val inner = children[0]
        val innerChildren = inner.listFiles() ?: return
        for (child in innerChildren) {
            if (!child.renameTo(File(dir, child.name))) {
                return
            }
        }
        inner.delete()
    }
}
