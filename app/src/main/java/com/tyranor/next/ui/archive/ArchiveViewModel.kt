package com.tyranor.next.ui.archive

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tyranor.next.R
import com.tyranor.next.core.game.model.GamePathUtils
import com.tyranor.next.core.unpack.ArchiveCancelledException
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
    var progressDeterminate by mutableStateOf(false)
        private set
    var progressName by mutableStateOf("")
        private set
    var message by mutableStateOf<String?>(null)
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
        progressDeterminate = determinate
        progressName = ""
        message = null
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
            } catch (error: Exception) {
                message = failedFormat.format(error.message ?: error.javaClass.simpleName)
            } finally {
                working = false
                currentJob = null
            }
        }
        currentJob = job
    }

    private val isCancelled: () -> Boolean = { cancelFlag.get() || currentJob?.isCancelled == true }

    private fun reportProgress(written: Long, total: Long, name: String) {
        if (total > 0) progress = (written.toFloat() / total).coerceIn(0f, 1f)
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
            val found = withContext(Dispatchers.IO) { ArchiveScanner.scan(appContext, uri) }
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

    fun extractSelected(appContext: Context) {
        val archive = selectedArchive ?: run {
            message = appContext.getString(R.string.archive_select_hint)
            return
        }
        val doneFormat = appContext.getString(R.string.archive_extract_created)
        val doneSkippedFormat = appContext.getString(R.string.archive_done_extract_skipped)
        val baseName = baseNameWithoutExt(archive.fileName)
        launchOp(appContext, archive.fileName, determinate = true) {
            val file = requireArchiveFile(appContext, archive)
            val result = withContext(Dispatchers.IO) {
                if (archive.realFile != null) {
                    val parent = archive.realFile.parentFile
                        ?: throw java.io.IOException("archive has no parent: ${archive.realFile.path}")
                    val outDir = uniqueDir(parent, baseName)
                    extractTo(file, outDir, isCancelled) to outDir.name
                } else {
                    val treeRoot = sourceTreeUri
                        ?: throw java.io.IOException("missing source directory")
                    val outDoc = ArchiveStaging.createDedupedChildDirectory(appContext, treeRoot, baseName)
                        ?: throw java.io.IOException("cannot create output folder: $baseName")
                    val tmp = File(ArchiveStaging.stagingDir(appContext), "extract_${System.currentTimeMillis()}")
                    try {
                        val counts = extractTo(file, tmp, isCancelled)
                        ArchiveStaging.publishDir(appContext, tmp, outDoc, isCancelled)
                        counts to outDoc.name
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

    /** 取归档文件：真实路径直用，否则中转（带缓存）。 */
    private suspend fun requireArchiveFile(appContext: Context, archive: ScannedArchive): File {
        archive.realFile?.let { return it }
        val uri = archive.docUri ?: throw java.io.IOException("archive source missing: ${archive.fileName}")
        stagedArchive?.takeIf { it.first == archive.id }?.let { return it.second }
        val staged = withContext(Dispatchers.IO) {
            ArchiveStaging.stageInputFile(appContext, uri, isCancelled)
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
        launchOp(appContext, packDirName, determinate = true) {
            val mappedPath = GamePathUtils.safUriToPath(uri.toString())
            val mappedDir = mappedPath?.let { File(it) }?.takeIf { it.isDirectory }
            if (mappedDir != null) {
                // 真实路径：输出为同级同名文件（去重）。
                val parent = mappedDir.parentFile ?: mappedDir
                val outFile = uniqueFile(parent, "${mappedDir.name}$ext")
                withContext(Dispatchers.IO) { runPack(mappedDir, outFile, level) }
                message = doneFormat.format(outFile.name)
            } else {
                // SAF 专有 provider：封到 cache，交给系统保存框。
                val srcDir = withContext(Dispatchers.IO) { ArchiveStaging.stageInputDir(appContext, uri, isCancelled) }
                val outFile = File(ArchiveStaging.stagingDir(appContext), "packed/resolved$ext")
                outFile.parentFile?.mkdirs()
                if (outFile.exists()) outFile.delete()
                withContext(Dispatchers.IO) { runPack(srcDir, outFile, level) }
                pendingSaveFile = outFile
                pendingSaveName = "${packDirName.ifBlank { "archive" }}$ext"
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

    private fun uniqueDir(parent: File, base: String): File {
        var candidate = File(parent, base)
        var index = 1
        while (candidate.exists()) {
            candidate = File(parent, "$base($index)")
            index++
        }
        if (!candidate.mkdirs()) throw java.io.IOException("cannot create directory: ${candidate.path}")
        return candidate
    }

    private fun uniqueFile(parent: File, name: String): File {
        var candidate = File(parent, name)
        var index = 1
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        while (candidate.exists()) {
            candidate = File(parent, "$stem($index)$ext")
            index++
        }
        return candidate
    }

    override fun onCleared() {
        cancelFlag.set(true)
        super.onCleared()
    }
}
