package com.tyranor.next.ui.archive

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tyranor.next.R
import com.tyranor.next.core.game.model.GamePathUtils
import com.tyranor.next.core.unpack.ArchiveCancelledException
import com.tyranor.next.core.unpack.ArchiveConflictException
import com.tyranor.next.core.unpack.ArchiveConflictKind
import com.tyranor.next.core.unpack.ArchiveEmptyInputException
import com.tyranor.next.core.unpack.ArchiveNativeMissingException
import com.tyranor.next.core.unpack.ArchiveOpGate
import com.tyranor.next.core.unpack.ArchiveScanner
import com.tyranor.next.core.unpack.ArchiveStaging
import com.tyranor.next.core.unpack.ScannedArchive
import com.tyranor.next.core.unpack.Xp3Archive
import com.tyranor.next.core.unpack.baseNameWithoutExtension
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

enum class ArchiveMode { UNPACK, PACK }

private const val TAG = "ArchiveViewModel"

data class EntryRow(val name: String, val size: Long, val isDirectory: Boolean)

/**
 * 解包/封包页状态机（ViewModel 常驻，旋转不丢目录/扫描结果/选中项）。
 *
 * 解包：选目录 → 扫描目录内 XP3 → 主从预览（左归档、右条目）→ 整体解包到归档同名文件夹（同名拒绝）。
 * 封包：选目录 → 选压缩等级 → 封包为同级同名 .xp3 文件（同名拒绝）。
 *
 * 状态载体用 Compose mutableStateOf（与 MainLibraryViewModel 的 StateFlow 先例不同）：
 * 本页状态全部由单一 Compose 树消费、无跨页面订阅，取舍记录于此。
 */
class ArchiveViewModel : ViewModel() {

    var mode by mutableStateOf(ArchiveMode.UNPACK)
        private set

    // ---- 解包状态 ----
    var sourceTreeUri by mutableStateOf<Uri?>(null)
        private set
    var sourceDirName by mutableStateOf("")
        private set
    var archives by mutableStateOf<List<ScannedArchive>>(emptyList())
        private set
    var selectedId by mutableStateOf<String?>(null)
        private set
    var entries by mutableStateOf<List<EntryRow>>(emptyList())
        private set
    var entriesListed by mutableStateOf(false)
        private set

    val selectedArchive: ScannedArchive?
        get() = archives.firstOrNull { it.id == selectedId }

    // ---- 封包状态 ----
    var packTreeUri by mutableStateOf<Uri?>(null)
        private set
    var packDirName by mutableStateOf("")
        private set
    var packLevel by mutableStateOf(6)
        private set

    // ---- 通用运行态 ----
    var working by mutableStateOf(false)
        private set
    var workingLabel by mutableStateOf("")
        private set
    var progress by mutableFloatStateOf(0f)
        private set

    /** 当前文件字节进度（双层进度条第二层）；fileBytes.second<=0 时 UI 隐藏该层。 */
    var fileProgress by mutableFloatStateOf(0f)
        private set
    var progressBytes by mutableStateOf(0L to 0L)
        private set
    var fileBytes by mutableStateOf(0L to 0L)
        private set
    var progressDeterminate by mutableStateOf(false)
        private set
    var progressName by mutableStateOf("")
        private set
    var message by mutableStateOf<String?>(null)
        private set

    /** 模态进度弹窗：运行中强制锁定页面，结束后展示结果等待「完成」。 */
    var dialogVisible by mutableStateOf(false)
        private set

    /** 无法映射真实路径时，封包产物暂存 cache 等待系统保存框。 */
    var pendingSaveFile by mutableStateOf<File?>(null)
        private set
    var pendingSaveName by mutableStateOf("")
        private set

    /** 系统保存框已 launch 的标记：旋转重组合时防 LaunchedEffect 重复弹框。 */
    var saveDialogActive by mutableStateOf(false)
        private set

    @Volatile
    private var currentJob: Job? = null
    private val cancelFlag = AtomicBoolean(false)

    @Volatile
    private var stagedArchive: Pair<String, File>? = null

    private var sessionCleaned = false

    /** 会话首清任务：GB 级残留可能删数秒，暂存前必须 join，防旧清理误删新拷贝。 */
    private var staleCleanupJob: Job? = null

    fun cleanStaleStagingOnce(appContext: Context) {
        if (sessionCleaned) return
        sessionCleaned = true
        staleCleanupJob = viewModelScope.launch(Dispatchers.IO) {
            // 首清也过闸：另一实例正拿着暂存区干活时跳过本次清理，防止误删在用的暂存。
            ArchiveOpGate.runIfIdle { ArchiveStaging.clearStaging(appContext) }
        }
    }

    private suspend fun awaitStaleCleanup() {
        staleCleanupJob?.join()
        staleCleanupJob = null
    }

    fun switchMode(next: ArchiveMode) {
        if (mode != next) {
            mode = next
            message = null
        }
    }

    private fun launchOp(appContext: Context, label: String, determinate: Boolean, block: suspend () -> Unit): Boolean {
        if (working) return false
        val busyMessage = appContext.getString(R.string.archive_busy)
        // 门闸在派发前取：拒绝时调用方能就地善后自己的产物（如 finishSave 的
        // 暂存包与 CreateDocument 预建的目标文档）。
        if (!ArchiveOpGate.tryLock()) {
            message = busyMessage
            dialogVisible = false
            Toast.makeText(appContext, busyMessage, Toast.LENGTH_LONG).show()
            return false
        }
        working = true
        workingLabel = label
        progress = 0f
        fileProgress = 0f
        progressBytes = 0L to 0L
        fileBytes = 0L to 0L
        progressDeterminate = determinate
        progressName = ""
        message = null
        dialogVisible = true
        cancelFlag.set(false)
        val failedMessage = appContext.getString(R.string.archive_failed_generic)
        val cancelledMessage = appContext.getString(R.string.archive_cancelled)
        val nativeMissingMessage = appContext.getString(R.string.archive_native_missing)
        val job = viewModelScope.launch {
            try {
                try {
                    block()
                } catch (cancelled: CancellationException) {
                    message = cancelledMessage
                    throw cancelled
                } catch (cancelled: ArchiveCancelledException) {
                    message = cancelledMessage
                } catch (missing: ArchiveNativeMissingException) {
                    message = nativeMissingMessage
                } catch (conflict: ArchiveConflictException) {
                    // 同名产物拒绝：core 只给类型与主体，本地化文案在此统一格式化。
                    // 不进结果弹窗，就地 toast + 状态栏提示。
                    val text = conflictMessage(appContext, conflict)
                    message = text
                    dialogVisible = false
                    Toast.makeText(appContext, text, Toast.LENGTH_LONG).show()
                } catch (empty: ArchiveEmptyInputException) {
                    message = appContext.getString(R.string.archive_input_empty)
                } catch (error: Exception) {
                    // 错误协议收口：泛化失败不再把 core 技术消息拼上屏（AGENT.md core→ui
                    // 协议），本地化兜底 + 日志留痕。
                    Log.w(TAG, "archive op failed: $label", error)
                    message = failedMessage
                } finally {
                    working = false
                    currentJob = null
                    // 暂存拷贝必须随操作结束立即收回（大包可达 GB 级），成功/取消/失败一律释放。
                    releaseStagedArchive()
                    // 结果需要展示（成功提示/取消/失败）则保留弹窗等待「完成」；静默成功（如扫描）自动关闭。
                    if (message == null) dialogVisible = false
                }
            } finally {
                ArchiveOpGate.unlock()
            }
        }
        currentJob = job
        return true
    }

    fun dismissDialog() {
        dialogVisible = false
    }

    /** core 层冲突异常 → UI 本地化文案（AGENT.md：core 错误不带本地化文本）。 */
    private fun conflictMessage(appContext: Context, conflict: ArchiveConflictException): String =
        appContext.getString(
            when (conflict.kind) {
                ArchiveConflictKind.OUTPUT_DIR_EXISTS -> R.string.archive_conflict_dir
                ArchiveConflictKind.OUTPUT_FILE_EXISTS -> R.string.archive_conflict_file
                ArchiveConflictKind.DUPLICATE_ENTRY -> R.string.archive_conflict_inside
                ArchiveConflictKind.ROLLBACK_INCOMPLETE -> R.string.archive_conflict_rollback
            },
        ).format(conflict.subject)

    /** 释放当前操作的暂存输入拷贝（cacheDir/archive_staging/input 目录）。 */
    private fun releaseStagedArchive() {
        stagedArchive?.let { (_, file) -> runCatching { file.delete() } }
        stagedArchive = null
    }

    private val isCancelled: () -> Boolean = { cancelFlag.get() || currentJob?.isCancelled == true }

    private fun reportProgress(written: Long, total: Long, fileWritten: Long, fileTotal: Long, name: String) {
        if (total > 0) progress = (written.toFloat() / total).coerceIn(0f, 1f)
        fileProgress = if (fileTotal > 0) (fileWritten.toFloat() / fileTotal).coerceIn(0f, 1f) else 0f
        progressBytes = written to total
        fileBytes = fileWritten to fileTotal
        progressName = name
    }

    fun cancel() {
        cancelFlag.set(true)
        currentJob?.cancel()
    }

    // ==================== 解包 ====================

    fun chooseSourceDir(appContext: Context, uri: Uri, displayName: String) {
        sourceTreeUri = uri
        sourceDirName = displayName
        archives = emptyList()
        selectedId = null
        entries = emptyList()
        entriesListed = false
        rescan(appContext)
    }

    fun rescan(appContext: Context) {
        val uri = sourceTreeUri ?: return
        val scanning = appContext.getString(R.string.archive_scanning)
        val noArchives = appContext.getString(R.string.archive_no_archives)
        launchOp(appContext, scanning, determinate = false) {
            val found = withContext(Dispatchers.IO) { ArchiveScanner.scan(appContext, uri, isCancelled) }
            archives = found
            if (found.isEmpty()) message = noArchives
            // 扫描后自动选中第一个并预览。
            if (found.isNotEmpty()) {
                selectedId = found.first().id
                // “正在扫描”弹窗里会隐式暂存首个 SAF 归档（GB 级拷贝）：把档名透出成
                // 进度名，用户能看见实际在搬哪个包。
                listSelectedInternal(appContext) { copied, total ->
                    reportProgress(copied, total, 0, 0, found.first().fileName)
                }
            }
        }
    }

    fun selectArchive(appContext: Context, id: String) {
        if (working) return
        if (selectedId == id && entriesListed) return
        selectedId = id
        entries = emptyList()
        entriesListed = false
        val archive = selectedArchive ?: return
        launchOp(appContext, archive.fileName, determinate = false) {
            listSelectedInternal(appContext)
        }
    }

    private suspend fun listSelectedInternal(
        appContext: Context,
        onStagingProgress: ((copied: Long, total: Long) -> Unit)? = null,
    ) {
        val archive = selectedArchive ?: return
        val file = requireArchiveFile(appContext, archive, onStagingProgress)
        val listed = withContext(Dispatchers.IO) {
            Xp3Archive.listEntries(file).map {
                EntryRow(it.name, it.size, it.isDirectory)
            }
        }
        entries = listed
        entriesListed = true
    }

    /** 解包到指定输出目录，返回 (成功数, 跳过数)。 */
    private fun extractTo(
        file: File,
        outDir: File,
        isCancelled: () -> Boolean,
    ): Pair<Int, Int> = Xp3Archive.extractAll(file, outDir, ::reportProgress, isCancelled)
        .let { it.extracted to it.skipped }

    /**
     * 包内重名条目预检：大小写折叠后存在同名条目（含仅大小写不同、目录与文件同名）
     * 返回该名字，无重名返回 null。
     */
    private fun findDuplicateEntryName(): String? {
        val seen = HashSet<String>()
        for (entry in entries) {
            if (!seen.add(entry.name.lowercase(Locale.ROOT))) return entry.name
        }
        return null
    }

    fun extractSelected(appContext: Context) {
        val archive = selectedArchive ?: run {
            message = appContext.getString(R.string.archive_select_hint)
            return
        }
        val doneFormat = appContext.getString(R.string.archive_extract_created)
        val doneSkippedFormat = appContext.getString(R.string.archive_done_extract_skipped)
        val baseName = baseNameWithoutExtension(archive.fileName)
        launchOp(appContext, archive.fileName, determinate = true) {
            // 包内重名预检放 IO：敌意大索引的全量小写化不卡主线程。大小写折叠后同名
            // （含仅大小写不同）即拒绝——/sdcard 等大小写不敏感文件系统上后写会覆盖
            // 先写，静默丢数据。
            val duplicate = withContext(Dispatchers.IO) { findDuplicateEntryName() }
            if (duplicate != null) {
                throw ArchiveConflictException(ArchiveConflictKind.DUPLICATE_ENTRY, duplicate)
            }
            val file = requireArchiveFile(appContext, archive) { copied, total ->
                reportProgress(copied, total, 0, 0, archive.fileName)
            }
            val result = withContext(Dispatchers.IO) {
                if (archive.realFile != null) {
                    val parent = archive.realFile.parentFile
                        ?: throw java.io.IOException("archive has no parent: ${archive.realFile.path}")
                    // 同名拒绝：不静默去重，提示用户先备份或改名。
                    val outDir = File(parent, baseName)
                    if (outDir.exists()) {
                        throw ArchiveConflictException(ArchiveConflictKind.OUTPUT_DIR_EXISTS, outDir.absolutePath)
                    }
                    if (!outDir.mkdirs()) {
                        throw java.io.IOException("cannot create directory: ${outDir.path}")
                    }
                    try {
                        extractTo(file, outDir, isCancelled) to outDir.absolutePath
                    } catch (error: Throwable) {
                        // 取消/失败回滚：刚建出的输出目录（可能已有半成品）整体移除，
                        // 与 SAF 路径的回滚语义保持一致；删除必须复核，残留按类型化错误上报。
                        if (!runCatching { outDir.deleteRecursively() }.getOrDefault(false)) {
                            Log.w(TAG, "rollback incomplete for ${outDir.path}", error)
                            throw ArchiveConflictException(ArchiveConflictKind.ROLLBACK_INCOMPLETE, baseName)
                        }
                        throw error
                    }
                } else {
                    // 与真实路径链路对齐：输出目录建在归档所在目录（扫描期记录的父目录
                    // URI），而非扫描根——否则嵌套归档的落点随路径映射成败漂移。
                    // document ID 是 provider 不透明标识，运行期绝不按其推导层级。
                    val parentUri = archive.parentDocUri
                        ?: throw java.io.IOException("missing parent directory: ${archive.fileName}")
                    val outDocUri = ArchiveStaging.createChildDirectoryExclusive(appContext, parentUri, baseName)
                        ?: throw ArchiveConflictException(ArchiveConflictKind.OUTPUT_DIR_EXISTS, baseName)
                    if (ArchiveStaging.hasChildren(appContext, outDocUri)) {
                        // provider 对已存在目录可能返回成功：非空即视作同名冲突，
                        // 绝不进入写回流程——否则取消回滚会误删目录树里的既有内容。
                        throw ArchiveConflictException(ArchiveConflictKind.OUTPUT_DIR_EXISTS, baseName)
                    }
                    val tmp = File(ArchiveStaging.stagingDir(appContext), "extract_${System.currentTimeMillis()}")
                    try {
                        val counts = extractTo(file, tmp, isCancelled)
                        // 回写阶段进度：解包字节此时已计满，把 SAF 发布字节接到同一根条上
                        // 续跑（总数 = 解包 + 回写，单调递增）。否则 GB 级回写期间条会
                        // 长时间停格在 ~100%，看起来像卡死——用户反馈的“解包没多久就卡住”。
                        // 基准直读 Rust 终态 TOTAL（含末条目自校正），不用滞后的轮询快照。
                        val base = Xp3Archive.extractProgressTotalSnapshot()
                        val publishTotal = tmp.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
                        ArchiveStaging.publishDir(appContext, tmp, outDocUri, isCancelled) { copied, total, name ->
                            reportProgress(base + copied, base + publishTotal, copied, total, name)
                        }
                        counts to (GamePathUtils.safUriToPath(outDocUri.toString()) ?: baseName)
                    } catch (error: Throwable) {
                        // 失败/取消回滚：删除自建输出目录并复核——第三方 provider 可能
                        // 拒绝递归删除，残留半成品必须按类型化错误如实上报，绝不静默。
                        if (!ArchiveStaging.rollbackCreatedDirectory(appContext, outDocUri)) {
                            Log.w(TAG, "rollback incomplete for $outDocUri", error)
                            throw ArchiveConflictException(ArchiveConflictKind.ROLLBACK_INCOMPLETE, baseName)
                        }
                        throw error
                    } finally {
                        runCatching { tmp.deleteRecursively() }
                    }
                }
            }
            val extracted = result.first.first
            val skipped = result.first.second
            val folderName = result.second
            message = if (skipped > 0) {
                doneSkippedFormat.format(extracted, skipped)
            } else {
                doneFormat.format(folderName)
            }
        }
    }

    /** 取归档文件：真实路径直用，否则中转（带缓存与字节级进度）。 */
    private suspend fun requireArchiveFile(
        appContext: Context,
        archive: ScannedArchive,
        onStagingProgress: ((copied: Long, total: Long) -> Unit)? = null,
    ): File {
        archive.realFile?.let { return it }
        val uri = archive.docUri ?: throw java.io.IOException("archive source missing: ${archive.fileName}")
        stagedArchive?.takeIf { it.first == archive.id }?.let { return it.second }
        awaitStaleCleanup()
        val staged = withContext(Dispatchers.IO) {
            ArchiveStaging.stageInputFile(appContext, uri, isCancelled, onStagingProgress)
        }
        // stageInputFile 能映射真实路径时直接返回用户原文（零拷贝）：原文不是暂存拷贝，
        // 绝不能登记进 stagedArchive，否则操作结束 releaseStagedArchive 会把用户的源封包
        // 一并 delete（成功/取消/失败都触发——线上反馈“取消后连游戏文件也删了”的根因）。
        // 只有真正位于暂存区的拷贝才允许随操作回收；换档前顺带收回上一档残留拷贝。
        releaseStagedArchive()
        if (ArchiveStaging.isStagingFile(appContext, staged)) {
            stagedArchive = archive.id to staged
        }
        return staged
    }

    // ==================== 封包 ====================

    fun choosePackDir(appContext: Context, uri: Uri, displayName: String) {
        packTreeUri = uri
        packDirName = displayName
        pendingSaveFile = null
        pendingSaveName = ""
        message = null
    }

    fun choosePackLevel(level: Int) {
        if (working) return
        packLevel = level
    }

    fun pack(appContext: Context) {
        val uri = packTreeUri ?: run {
            message = appContext.getString(R.string.archive_no_dir)
            return
        }
        val ext = ".xp3"
        val level = packLevel
        val dirName = packDirName
        val doneFormat = appContext.getString(R.string.archive_pack_done)
        launchOp(appContext, dirName, determinate = true) {
            // SAF 专有 provider 下 stageInputDir 的整棵输入拷贝必须随操作回收
            var stagedDir: File? = null
            try {
                val outcome = withContext(Dispatchers.IO) {
                    // canRead + 所有文件访问双重门禁：未授权时 File 直读会得到残缺结果
                    //（read_dir 失败 → 封包失败），必须回退 SAF 暂存路径。
                    // 门禁 stat 全在 IO：主线程不做磁盘访问。
                    val mappedPath = GamePathUtils.safUriToPath(uri.toString())
                    val mappedDir = mappedPath
                        ?.let { File(it) }
                        ?.takeIf {
                            it.isDirectory && it.canRead() &&
                                (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager())
                        }
                    if (mappedDir != null) {
                        // 真实路径：输出为同级同名文件，同名拒绝（提示提前备份/改名）。
                        val parent = mappedDir.parentFile ?: mappedDir
                        val outFile = File(parent, "${mappedDir.name}$ext")
                        if (outFile.exists()) {
                            throw ArchiveConflictException(ArchiveConflictKind.OUTPUT_FILE_EXISTS, outFile.absolutePath)
                        }
                        ensurePackInputNonEmpty(mappedDir)
                        runPack(mappedDir, outFile, level)
                        PackOutcome.Saved(outFile.absolutePath)
                    } else {
                        // SAF 专有 provider：封到 cache，交给系统保存框。
                        awaitStaleCleanup()
                        val srcDir = ArchiveStaging.stageInputDir(appContext, uri, isCancelled)
                        stagedDir = srcDir
                        val outFile = File(ArchiveStaging.stagingDir(appContext), "packed/resolved$ext")
                        outFile.parentFile?.mkdirs()
                        if (outFile.exists()) outFile.delete()
                        ensurePackInputNonEmpty(srcDir)
                        runPack(srcDir, outFile, level)
                        // 保存框建议名取末级目录名（dirName 是完整展示路径，含分隔符）。
                        PackOutcome.PendingSave(outFile, "${dirName.substringAfterLast('/').ifBlank { "archive" }}$ext")
                    }
                }
                when (outcome) {
                    is PackOutcome.Saved -> message = doneFormat.format(outcome.path)
                    is PackOutcome.PendingSave -> {
                        pendingSaveFile = outcome.file
                        pendingSaveName = outcome.suggestedName
                    }
                }
            } finally {
                // stageInputDir 理论上也可能零拷贝返回用户原目录（与 stageInputFile 同款
                // 契约）：回收前必须确认位于暂存区，绝不 deleteRecursively 用户的源目录。
                stagedDir?.takeIf { ArchiveStaging.isStagingFile(appContext, it) }
                    ?.let { runCatching { it.deleteRecursively() } }
            }
        }
    }

    /** 封包输入预检：目录里没有任何普通文件时按类型化错误拒绝（不进 native 报技术串）。 */
    private fun ensurePackInputNonEmpty(srcDir: File) {
        if (srcDir.walkBottomUp().none { it.isFile }) throw ArchiveEmptyInputException(srcDir.path)
    }

    /** 封包结果：落盘到同级同名 .xp3，或暂存等待系统保存框。 */
    private sealed interface PackOutcome {
        data class Saved(val path: String) : PackOutcome
        data class PendingSave(val file: File, val suggestedName: String) : PackOutcome
    }

    private suspend fun runPack(srcDir: File, outFile: File, level: Int) {
        Xp3Archive.pack(srcDir, outFile, level, ::reportProgress, isCancelled)
    }

    fun markSaveDialogLaunched() {
        saveDialogActive = true
    }

    /** 系统保存框回调：null 表示放弃，清理中转包。 */
    fun finishSave(appContext: Context, targetUri: Uri?) {
        saveDialogActive = false
        val packed = pendingSaveFile
        val suggestedName = pendingSaveName
        if (packed == null) return
        if (targetUri == null) {
            pendingSaveFile = null
            pendingSaveName = ""
            runCatching { packed.delete() }
            return
        }
        val doneFormat = appContext.getString(R.string.archive_pack_done)
        val accepted = launchOp(appContext, suggestedName.ifBlank { packed.name }, determinate = false) {
            // 门闸已受理才清待保存状态：拒绝路径要靠它们善后。
            pendingSaveFile = null
            pendingSaveName = ""
            var written = false
            try {
                val savedName = withContext(Dispatchers.IO) {
                    appContext.contentResolver.openOutputStream(targetUri)?.use { output ->
                        packed.inputStream().use { input ->
                            // 分块拷贝并响应取消：整包 copyTo 会让取消按钮在拷完前像失效一样。
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                if (isCancelled()) throw ArchiveCancelledException(packed.name)
                                val n = input.read(buf)
                                if (n < 0) break
                                output.write(buf, 0, n)
                            }
                        }
                    } ?: throw java.io.IOException("Cannot open output: $targetUri")
                    // 成功文案显示用户在保存框里实际选择的文件名，而非内部暂存名 resolved.xp3。
                    ArchiveStaging.queryDisplayName(appContext, targetUri)
                        ?: suggestedName.ifBlank { packed.name }
                }
                written = true
                message = doneFormat.format(savedName)
            } catch (error: Throwable) {
                // 目标文档由 CreateDocument 预先创建：失败/取消时删除半成品并复核——
                // provider 返回 false 时用户目录残留截断的 .xp3，必须按类型化错误如实上报。
                val removed = withContext(NonCancellable + Dispatchers.IO) {
                    runCatching { DocumentsContract.deleteDocument(appContext.contentResolver, targetUri) }
                        .getOrDefault(false)
                }
                if (!removed) {
                    Log.w(TAG, "save rollback incomplete: $targetUri", error)
                    throw ArchiveConflictException(
                        ArchiveConflictKind.ROLLBACK_INCOMPLETE,
                        suggestedName.ifBlank { packed.name },
                    )
                }
                throw error
            } finally {
                // 清理是跨进程/磁盘操作，挪 IO 线程；NonCancellable 保证取消路径上仍执行。
                withContext(NonCancellable + Dispatchers.IO) {
                    runCatching { packed.delete() }
                }
            }
        }
        if (!accepted) {
            // 门闸拒绝（另一实例在运行）：暂存包与预建的空目标文档都明确清理。
            viewModelScope.launch(Dispatchers.IO) {
                runCatching { packed.delete() }
                runCatching { DocumentsContract.deleteDocument(appContext.contentResolver, targetUri) }
            }
            pendingSaveFile = null
            pendingSaveName = ""
        }
    }

    // ==================== 工具 ====================

    override fun onCleared() {
        cancelFlag.set(true)
        currentJob?.cancel()
        // 页面销毁兜底：暂存输入拷贝与未落盘的封包产物不可残留。
        releaseStagedArchive()
        pendingSaveFile?.let { runCatching { it.delete() } }
        pendingSaveFile = null
        super.onCleared()
    }
}
