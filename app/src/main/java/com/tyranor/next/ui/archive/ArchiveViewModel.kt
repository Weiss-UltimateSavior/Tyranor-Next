package com.tyranor.next.ui.archive

import android.content.Context
import android.net.Uri
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
import com.tyranor.next.core.unpack.ArchiveNativeMissingException
import com.tyranor.next.core.unpack.ArchiveScanner
import com.tyranor.next.core.unpack.ArchiveStaging
import com.tyranor.next.core.unpack.ScannedArchive
import com.tyranor.next.core.unpack.Xp3Archive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

enum class ArchiveMode { UNPACK, PACK }

data class EntryRow(val name: String, val size: Long, val isDirectory: Boolean)

/**
 * 解包/封包页状态机（ViewModel 常驻，旋转不丢目录/扫描结果/选中项）。
 *
 * 解包：选目录 → 扫描目录内 XP3 → 主从预览（左归档、右条目）→ 全量解压到归档同名文件夹（去重）。
 * 封包：选目录 → 选压缩等级 → 封包为同级同名 .xp3 文件（去重）。
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
    var scanned by mutableStateOf(false)
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

    @Volatile
    private var currentJob: Job? = null
    private val cancelFlag = AtomicBoolean(false)

    @Volatile
    private var stagedArchive: Pair<String, File>? = null

    private var sessionCleaned = false

    fun cleanStaleStagingOnce(appContext: Context) {
        if (sessionCleaned) return
        sessionCleaned = true
        viewModelScope.launch(Dispatchers.IO) { ArchiveStaging.clearStaging(appContext) }
    }

    fun switchMode(next: ArchiveMode) {
        if (mode != next) {
            mode = next
            message = null
        }
    }

    private fun launchOp(appContext: Context, label: String, determinate: Boolean, block: suspend () -> Unit) {
        if (working) return
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
        val failedFormat = appContext.getString(R.string.archive_failed)
        val cancelledMessage = appContext.getString(R.string.archive_cancelled)
        val nativeMissingMessage = appContext.getString(R.string.archive_native_missing)
        val job = viewModelScope.launch {
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
                // 同名产物拒绝：不进结果弹窗，就地 toast + 状态栏提示。
                message = conflict.message
                dialogVisible = false
                Toast.makeText(appContext, conflict.message, Toast.LENGTH_LONG).show()
            } catch (error: Exception) {
                message = failedFormat.format(error.message ?: error.javaClass.simpleName)
            } finally {
                working = false
                currentJob = null
                // 暂存拷贝必须随操作结束立即收回（大包可达 GB 级），成功/取消/失败一律释放。
                releaseStagedArchive()
                // 结果需要展示（成功提示/取消/失败）则保留弹窗等待「完成」；静默成功（如扫描）自动关闭。
                if (message == null) dialogVisible = false
            }
        }
        currentJob = job
    }

    fun dismissDialog() {
        dialogVisible = false
    }

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
        scanned = false
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
            scanned = true
            if (found.isEmpty()) message = noArchives
            // 扫描后自动选中第一个并预览。
            if (found.isNotEmpty()) {
                selectedId = found.first().id
                listSelectedInternal(appContext)
            }
        }
    }

    fun selectArchive(appContext: Context, id: String) {
        if (selectedId == id && entriesListed) return
        selectedId = id
        entries = emptyList()
        entriesListed = false
        val archive = selectedArchive ?: return
        launchOp(appContext, archive.fileName, determinate = false) {
            listSelectedInternal(appContext)
        }
    }

    private suspend fun listSelectedInternal(appContext: Context) {
        val archive = selectedArchive ?: return
        val file = requireArchiveFile(appContext, archive)
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
        val conflictDirFormat = appContext.getString(R.string.archive_conflict_dir)
        val baseName = baseNameWithoutExt(archive.fileName)
        // 包内重名预检：大小写折叠后同名（含仅大小写不同）即拒绝解压——
        // /sdcard 等大小写不敏感文件系统上后写会覆盖先写，静默丢数据。
        val duplicate = findDuplicateEntryName()
        if (duplicate != null) {
            val text = appContext.getString(R.string.archive_conflict_inside).format(duplicate)
            message = text
            Toast.makeText(appContext, text, Toast.LENGTH_LONG).show()
            return
        }
        launchOp(appContext, archive.fileName, determinate = true) {
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
                        throw ArchiveConflictException(conflictDirFormat.format(outDir.absolutePath))
                    }
                    if (!outDir.mkdirs()) {
                        throw java.io.IOException("cannot create directory: ${outDir.path}")
                    }
                    extractTo(file, outDir, isCancelled) to outDir.absolutePath
                } else {
                    val treeRoot = sourceTreeUri
                        ?: throw java.io.IOException("missing source directory")
                    val outDoc = ArchiveStaging.createChildDirectoryExclusive(appContext, treeRoot, baseName)
                        ?: throw ArchiveConflictException(conflictDirFormat.format(baseName))
                    val tmp = File(ArchiveStaging.stagingDir(appContext), "extract_${System.currentTimeMillis()}")
                    try {
                        val counts = extractTo(file, tmp, isCancelled)
                        ArchiveStaging.publishDir(appContext, tmp, outDoc, isCancelled)
                        counts to (GamePathUtils.safUriToPath(outDoc.uri.toString()) ?: outDoc.name)
                    } catch (error: Throwable) {
                        // 失败/取消回滚：刚在用户目录树里建出的输出目录（可能已有半成品）整体移除。
                        runCatching { outDoc.delete() }
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
        val staged = withContext(Dispatchers.IO) {
            ArchiveStaging.stageInputFile(appContext, uri, isCancelled, onStagingProgress)
        }
        stagedArchive = archive.id to staged
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
        packLevel = level
    }

    fun pack(appContext: Context) {
        val uri = packTreeUri ?: run {
            message = appContext.getString(R.string.archive_no_dir)
            return
        }
        val ext = ".xp3"
        val level = packLevel
        val doneFormat = appContext.getString(R.string.archive_pack_done)
        val conflictFileFormat = appContext.getString(R.string.archive_conflict_file)
        launchOp(appContext, packDirName, determinate = true) {
            // SAF 专有 provider 下 stageInputDir 的整棵输入拷贝必须随操作回收
            var stagedDir: File? = null
            try {
                val mappedPath = GamePathUtils.safUriToPath(uri.toString())
                val mappedDir = mappedPath?.let { File(it) }?.takeIf { it.isDirectory }
                if (mappedDir != null) {
                    // 真实路径：输出为同级同名文件，同名拒绝（提示提前备份/改名）。
                    val parent = mappedDir.parentFile ?: mappedDir
                    val outFile = File(parent, "${mappedDir.name}$ext")
                    if (outFile.exists()) {
                        throw ArchiveConflictException(conflictFileFormat.format(outFile.absolutePath))
                    }
                    withContext(Dispatchers.IO) { runPack(mappedDir, outFile, level) }
                    message = doneFormat.format(outFile.absolutePath)
                } else {
                    // SAF 专有 provider：封到 cache，交给系统保存框。
                    val srcDir = withContext(Dispatchers.IO) { ArchiveStaging.stageInputDir(appContext, uri, isCancelled) }
                    stagedDir = srcDir
                    val outFile = File(ArchiveStaging.stagingDir(appContext), "packed/resolved$ext")
                    outFile.parentFile?.mkdirs()
                    if (outFile.exists()) outFile.delete()
                    withContext(Dispatchers.IO) { runPack(srcDir, outFile, level) }
                    pendingSaveFile = outFile
                    pendingSaveName = "${packDirName.ifBlank { "archive" }}$ext"
                }
            } finally {
                stagedDir?.let { runCatching { it.deleteRecursively() } }
            }
        }
    }

    private suspend fun runPack(srcDir: File, outFile: File, level: Int) {
        Xp3Archive.pack(srcDir, outFile, level, ::reportProgress, isCancelled)
    }

    /** 系统保存框回调：null 表示放弃，清理中转包。 */
    fun finishSave(appContext: Context, targetUri: Uri?) {
        val packed = pendingSaveFile
        pendingSaveFile = null
        pendingSaveName = ""
        if (packed == null) return
        if (targetUri == null) {
            runCatching { packed.delete() }
            return
        }
        val doneFormat = appContext.getString(R.string.archive_pack_done)
        launchOp(appContext, packed.name, determinate = false) {
            try {
                withContext(Dispatchers.IO) {
                    appContext.contentResolver.openOutputStream(targetUri)?.use { output ->
                        packed.inputStream().use { input -> input.copyTo(output) }
                    } ?: throw java.io.IOException("Cannot open output: $targetUri")
                }
                message = doneFormat.format(packed.name)
            } finally {
                runCatching { packed.delete() }
            }
        }
    }

    // ==================== 工具 ====================

    private fun baseNameWithoutExt(fileName: String): String {
        val dot = fileName.lastIndexOf('.')
        return if (dot > 0) fileName.substring(0, dot) else fileName
    }

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
