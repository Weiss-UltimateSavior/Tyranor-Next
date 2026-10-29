package bridge

import android.content.Context
import android.database.Cursor
import android.media.MediaScannerConnection
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.AtomicFile
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.OutputStreamWriter
import java.io.RandomAccessFile
import java.nio.channels.FileLockInterruptionException
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.coroutines.cancellation.CancellationException

/**
 * Builds the local shadow tree used by Kirikiroid2 for removable-storage game folders.
 *
 * Directories and empty placeholder files satisfy libc stat/access/opendir calls. Actual file
 * contents are supplied by [NativeBridge.open] from the indexed SAF Uri for read-only opens.
 * Updating an indexed resource first copies it into a durable local override; source documents
 * are never opened for writing. The source `savedata`
 * subtree is skipped: saves live in the app-private mirror directory by design. Non-empty
 * local files are retained and take precedence, matching Tyranor's original overlay behavior.
 */
object KrSafMirror {
    private const val TAG = "KrSafMirror"
    private const val INDEX_VERSION_V1 = "krkr-saf-index-v1"
    private const val INDEX_VERSION_V2 = "krkr-saf-index-v2"

    /** Depth cap for the recursive mirror walk (game trees are shallow; guards odd structures). */
    // base has no depth limit; 12 keeps pathological trees bounded while covering
    // real Kirikiri layouts deeper than the previous cap of 6 (review round 10, #16).
    private const val MIRROR_MAX_DEPTH = 12
    private const val SIG_SCAN_WAIT_MILLIS = 250L
    private const val PREPARATION_LOCK_SHARDS = 64
    private val preparationLocks = ConcurrentHashMap<String, ReentrantLock>()

    val BOOTSTRAP_FILE_NAMES: List<String> = listOf("patch.tjs", "ds.ini")

    /**
     * 索引条目：镜像占位文件对应的 SAF 文档与其真实尺寸。尺寸未知（v1 索引）时为 -1。
     */
    data class MirrorEntry(val uri: Uri, val size: Long)

    /**
     * 镜像准备失败（**类型化原因，不带文案**：文案由 app 层按 [stage] 映射，见 AGENTS.md 错误协议）。
     *
     * [name] 为相关目录/文件名，供调用方给出可定位的提示（如「无法创建镜像目录：data」）。
     */
    class PrepareException(
        val stage: Stage,
        val name: String? = null,
    ) : IllegalStateException(stage.name + (name?.let { ": $it" } ?: ""))

    enum class Stage {
        /** 无法解析 SD 卡/SAF 源目录（授权失效或目录已被移动）。 */
        SOURCE_UNRESOLVED,

        /** 无法创建镜像根目录。 */
        MIRROR_DIR,

        /** 无法创建 SAF 索引目录。 */
        INDEX_DIR,

        /** No source resources could be mirrored; partial-provider failures are logged separately. */
        EMPTY_MIRROR,
    }

    enum class BootstrapFileState { PRESENT, ABSENT, UNAVAILABLE }

    /** Typed bootstrap descriptor so the app never rebuilds engine index keys. */
    data class BootstrapFile(
        val state: BootstrapFileState,
        val uri: String?,
        val size: Long,
        val localOverride: Boolean,
    )

    data class Prepared(
        val mirrorRoot: File,
        val indexFile: File,
        val fileCount: Int,
        val bootstrapFiles: Map<String, BootstrapFile> = emptyMap(),
        val localOverrides: Set<String> = emptySet(),
        val diagnostics: Diagnostics = Diagnostics(),
    )

    data class Diagnostics(
        val skippedEntries: Int = 0,
        val queryFailures: Int = 0,
        val depthLimitedDirectories: Int = 0,
        val mergeFailures: Int = 0,
        val scannerTimeouts: Int = 0,
        val observedSourceFiles: Int = 0,
        val rootListingInvalid: Boolean = false,
    )

    private class BuildDiagnostics {
        var skippedEntries = 0
        var queryFailures = 0
        var depthLimitedDirectories = 0
        var mergeFailures = 0
        var scannerTimeouts = 0
        var observedSourceFiles = 0
        val rootNames = HashSet<String>()
        var rootListingComplete = false
        var rootListingInvalid = false

        fun snapshot() = Diagnostics(
            skippedEntries = skippedEntries,
            queryFailures = queryFailures,
            depthLimitedDirectories = depthLimitedDirectories,
            mergeFailures = mergeFailures,
            scannerTimeouts = scannerTimeouts,
            observedSourceFiles = observedSourceFiles,
            rootListingInvalid = rootListingInvalid,
        )
    }

    /**
     * 构建镜像目录树并写索引。流程：解析 SAF 源目录 → 递归建 0 字节占位（跳过源
     * `savedata` 子树：存档按设计只走应用私有目录，源存档经存档管理导入）
     * → 三重补全缺失条目（MediaStore / File 直列 / .sig 反推探测）→ 原子写索引 v2。
     * 失败以 [PrepareException] 按阶段抛出，由 app 层映射文案。
     */
    @JvmStatic
    fun prepare(
        context: Context,
        sourceReference: String,
        logicalPath: String,
    ): Prepared {
        val indexDirectory = File(context.noBackupFilesDir, "krkr_saf_index")
        val key = mirrorKey(sourceReference, logicalPath)
        if (!indexDirectory.isDirectory && !indexDirectory.mkdirs() && !indexDirectory.isDirectory) {
            throw PrepareException(Stage.INDEX_DIR, indexDirectory.name)
        }
        return try {
            withPreparationLock(indexDirectory, key) {
                prepareLocked(context, sourceReference, logicalPath, indexDirectory, key)
            }
        } catch (error: IOException) {
            throw PrepareException(Stage.INDEX_DIR, key).also { it.initCause(error) }
        }
    }

    /** A process mutex avoids overlapping JVM locks; the file lock also covers other processes. */
    internal fun <T> withPreparationLock(indexDirectory: File, key: String, action: () -> T): T {
        val lockFile = File(indexDirectory, "prepare-${(key.hashCode() and Int.MAX_VALUE) % PREPARATION_LOCK_SHARDS}.lock")
        val mutex = preparationLocks.getOrPut(lockFile.absolutePath) { ReentrantLock() }
        try {
            mutex.lockInterruptibly()
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw CancellationException("Mirror preparation interrupted").also { it.initCause(error) }
        }
        try {
            return RandomAccessFile(lockFile, "rw").use { file ->
                file.channel.lock().use { action() }
            }
        } catch (error: FileLockInterruptionException) {
            Thread.currentThread().interrupt()
            throw CancellationException("Mirror preparation interrupted").also { it.initCause(error) }
        } finally {
            mutex.unlock()
        }
    }

    private fun prepareLocked(
        context: Context,
        sourceReference: String,
        logicalPath: String,
        indexDir: File,
        key: String,
    ): Prepared {
        val startedAt = System.currentTimeMillis()
        val diagnostics = BuildDiagnostics()
        val resolved = resolveDocumentDirectory(context.applicationContext, sourceReference, logicalPath)
            ?: throw PrepareException(Stage.SOURCE_UNRESOLVED, logicalPath)
        val gamesRoot = File(context.filesDir.parentFile ?: context.filesDir, "games")
        val mirrorRoot = withStickyRootSelection(context, gamesRoot, key) {
            findMirrorRoot(gamesRoot, key)
        }
        if (!mirrorRoot.isDirectory && !mirrorRoot.mkdirs()) {
            throw PrepareException(Stage.MIRROR_DIR, mirrorRoot.name)
        }
        // The prepared root is authoritative: rewrite the selection unconditionally so a
        // diverged or stale marker converges to this run's choice (withStickyRootSelection
        // only publishes on first selection; this corrects any leftover disagreement).
        writeRootSelection(File(rootSelectionDirectory(context), "root-$key.txt"), mirrorRoot.name)

        val indexFile = File(indexDir, "$key.idx")
        val localFiles = KrMirrorLocalFiles(mirrorRoot)
        // Identity buildUri: carryPreviousIndexRows only needs the raw uri string/size.
        val previousIndex: Map<String, KrSafMirror.MirrorEntryHolder<String>> = readIndexFile(indexFile) { it }
        val entries = ArrayList<Triple<String, String, Long>>()
        // Case-collapsed key set shared by mirroring and the merge passes: mirroring
        // dedupes root-tree entries, the merges skip anything already known
        val known = HashSet<String>()
        mirrorDirectory(context.applicationContext, resolved, mirrorRoot, entries, known, localFiles, diagnostics, atRoot = true)
        val sourceRoot = logicalPath.trimEnd('/')
        var merged = mergeMissingFromMediaStore(context.applicationContext, resolved.treeUri, sourceRoot, mirrorRoot, entries, known, localFiles, diagnostics)
        merged += mergeMissingFromDirectListing(resolved.treeUri, sourceRoot, mirrorRoot, entries, known, localFiles, diagnostics)
        merged += probeSigSiblingDlls(context.applicationContext, resolved.treeUri, entries, known, localFiles, diagnostics)

        val bootstrapLocalOverrides = HashSet<String>()
        val bootstrapFiles = probeBootstrapFiles(context.applicationContext, resolved, mirrorRoot, entries,
            known, localFiles, bootstrapLocalOverrides, diagnostics, previousIndex)
        // Local fallback must not depend on this run's SAF rows: a failed provider can
        // return no rows even though durable local resource copies still exist.
        val preparationSources = assemblePreparationSources(
            mirrorRoot, previousIndex.keys, entries, diagnostics.observedSourceFiles,
            diagnostics.queryFailures, bootstrapLocalOverrides,
            diagnostics.skippedEntries, diagnostics.depthLimitedDirectories,
            diagnostics.rootListingInvalid, diagnostics.rootListingComplete,
        )
        val localOverrides = preparationSources.localOverrides
        entries.removeAll { it.first in localOverrides }
        known.removeAll(localOverrides)
        // An incomplete listing must not silently erase rows of failed subtrees.
        val decision = preparationSources.decision
        if (decision == MirrorSourceDecision.FAIL_EMPTY) {
            throw PrepareException(Stage.EMPTY_MIRROR, mirrorRoot.name)
        }
        val confirmedAbsentKeys = bootstrapFiles.filterValues { it.state == BootstrapFileState.ABSENT }
            .keys.mapTo(HashSet()) { File(mirrorRoot, it).absolutePath.lowercase(Locale.ROOT) }
        val carried = reconcilePreparedIndex(mirrorRoot, previousIndex, entries, known,
            localOverrides, decision, confirmedAbsentKeys) { key ->
            isSourceDocumentGone(context.applicationContext, resolved.treeUri, previousIndex[key])
        }
        if (decision == MirrorSourceDecision.REBUILD_MERGED) {
            Log.w(TAG, "mirror listing incomplete; carried $carried previous index rows (queryFailures=${diagnostics.queryFailures})")
        }
        val fileCount = when (decision) {
            MirrorSourceDecision.KEEP_EXISTING -> previousIndex.size + localOverrides.size
            else -> entries.size + localOverrides.size
        }
        val snapshot = diagnostics.snapshot()
        Log.i(TAG, "mirror diagnostics files=$fileCount decision=$decision $snapshot")
        // KEEP_EXISTING preserves the previous index verbatim: this run observed nothing,
        // so rewriting would replace known-good rows with an empty listing.
        if (decision != MirrorSourceDecision.KEEP_EXISTING) {
            // 索引写入失败会抛 IOException（AtomicFile.failWrite 后原样抛出）：归到 INDEX_DIR，
            // 否则它会落到调用方的 catch-all 分支、报成「未知路径」，用户看不出是写索引失败
            try {
                writeIndex(indexFile, entries)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                throw PrepareException(Stage.INDEX_DIR, indexFile.name).also { it.initCause(error) }
            }
        }
        val sized = entries.count { it.third >= 0 }
        val totalBytes = entries.sumOf { if (it.third >= 0) it.third else 0L }
        Log.i(
            TAG,
            "mirror prepared root=${mirrorRoot.name} files=$fileCount decision=$decision sized=$sized " +
                "mergedFiles=$merged localOverrides=${localOverrides.size} bytes=$totalBytes cost=${System.currentTimeMillis() - startedAt}ms",
        )
        return Prepared(mirrorRoot, indexFile, fileCount, bootstrapFiles, localOverrides, snapshot)
    }

    internal data class PreparationSources(
        val localOverrides: Set<String>,
        val decision: MirrorSourceDecision,
    )

    /** Assemble fallback from durable local state, independently of current provider rows. */
    internal fun assemblePreparationSources(
        mirrorRoot: File,
        previousIndexKeys: Set<String>,
        entries: List<Triple<String, String, Long>>,
        observed: Int,
        queryFailures: Int,
        bootstrapLocalOverrides: Set<String> = emptySet(),
        skippedEntries: Int = 0,
        depthLimitedDirectories: Int = 0,
        rootListingInvalid: Boolean = false,
        rootListingComplete: Boolean = false,
    ): PreparationSources {
        val localFiles = KrMirrorLocalFiles(mirrorRoot)
        val candidateKeys = previousIndexKeys + entries.map { it.first }
        val overrides = bootstrapLocalOverrides.toMutableSet()
        fun collectExisting(relativeKeys: Set<String>) {
            for (relative in relativeKeys) {
                if (isSourceEntryExcluded(relative)) continue
                val file = File(mirrorRoot, relative)
                try {
                    if (file.isFile && localFiles.isOverridden(file)) {
                        overrides.add(file.absolutePath.lowercase(Locale.ROOT))
                    }
                } catch (error: IOException) {
                    Log.w(TAG, "local mirror override unavailable path=${file.path}", error)
                }
            }
        }
        collectExisting(localFiles.snapshotOverrideKeys(candidateKeys))
        if (overrides.isEmpty() && observed == 0 && previousIndexKeys.isEmpty() &&
            File(mirrorRoot, ".tyranor-mirror-state/local-overrides").listFiles().orEmpty().any { it.isFile }
        ) {
            // A header-only old index cannot resolve one-byte markers back to filenames.
            // Only in this failed-source fallback, inspect existing local resources to
            // supply candidate names; normal preparation never adds a second tree walk.
            val canonicalRoot = mirrorRoot.canonicalFile
            val candidates = mirrorRoot.walkTopDown().onEnter { directory ->
                directory == mirrorRoot || (!isSourceEntryExcluded(directory.relativeTo(mirrorRoot).invariantSeparatorsPath) &&
                    !Files.isSymbolicLink(directory.toPath()))
            }.onFail { file, error ->
                Log.w(TAG, "local mirror fallback listing failed path=${file.path}", error)
            }.filter { file ->
                file.isFile && !isSourceEntryExcluded(file.relativeTo(mirrorRoot).invariantSeparatorsPath) &&
                    file.canonicalPath.startsWith(canonicalRoot.path + File.separator)
            }.map { it.absolutePath.lowercase(Locale.ROOT) }.toList()
            collectExisting(localFiles.snapshotOverrideKeys(candidates))
        }
        return PreparationSources(overrides, decideMirrorSources(
            observed, previousIndexKeys.size, queryFailures, overrides.size,
            skippedEntries, depthLimitedDirectories, rootListingInvalid, rootListingComplete,
        ))
    }

    /** Completeness decision for one prepare run; pure for JVM regression. */
    internal enum class MirrorSourceDecision { REBUILD_FRESH, REBUILD_MERGED, KEEP_EXISTING, FAIL_EMPTY }

    /**
     * Second-source absence proof for a fresh rebuild (audit F3): cursor EOF alone is
     * not destructive evidence for a known old row, so removal additionally requires
     * the row's document to answer a by-ID query with a definitive miss (AOSP returns
     * a null cursor for missing documents). Provider errors keep the old row.
     */
    private fun isSourceDocumentGone(
        context: Context,
        treeUri: Uri,
        previous: MirrorEntryHolder<String>?,
    ): Boolean {
        if (previous == null) return false
        return try {
            val documentId = DocumentsContract.getDocumentId(Uri.parse(previous.uri))
            val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
            val cursor = context.contentResolver.query(
                uri,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                null, null, null,
            )
            cursor?.use { !it.moveToFirst() } ?: true
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            Log.w(TAG, "fresh rebuild absence check unavailable", error)
            false
        }
    }

    internal fun decideMirrorSources(
        observed: Int,
        previousCount: Int,
        queryFailures: Int,
        localOverrideCount: Int,
        skippedEntries: Int = 0,
        depthLimitedDirectories: Int = 0,
        rootListingInvalid: Boolean = false,
        rootListingComplete: Boolean = false,
    ): MirrorSourceDecision = when {
        observed > 0 && queryFailures == 0 && skippedEntries == 0 &&
            depthLimitedDirectories == 0 && !rootListingInvalid &&
            rootListingComplete -> MirrorSourceDecision.REBUILD_FRESH
        observed > 0 -> MirrorSourceDecision.REBUILD_MERGED
        previousCount > 0 || localOverrideCount > 0 -> MirrorSourceDecision.KEEP_EXISTING
        else -> MirrorSourceDecision.FAIL_EMPTY
    }

    /**
     * Reconcile old rows after completeness/absence evidence has been assembled.
     * [confirmMissing] receives the original key from [previousIndex], not a relative identity.
     */
    internal fun reconcilePreparedIndex(
        mirrorRoot: File,
        previousIndex: Map<String, MirrorEntryHolder<String>>,
        entries: MutableList<Triple<String, String, Long>>,
        known: MutableSet<String>,
        localOverrides: Set<String>,
        decision: MirrorSourceDecision,
        confirmedAbsentKeys: Set<String> = emptySet(),
        confirmMissing: ((String) -> Boolean)? = null,
    ): Int {
        if (decision == MirrorSourceDecision.KEEP_EXISTING || decision == MirrorSourceDecision.FAIL_EMPTY) return 0
        val localFiles = KrMirrorLocalFiles(mirrorRoot)
        fun identity(key: String): String? = localFiles.relativeForIndexKey(key)
            ?: localFiles.fileForIndexKey(key)?.let { localFiles.relativeForIndexKey(it.path) }
        val current = (known + localOverrides).mapNotNullTo(HashSet(), ::identity)
        val confirmedAbsent = confirmedAbsentKeys.mapNotNullTo(HashSet(), ::identity)
        val missing = if (decision == MirrorSourceDecision.REBUILD_FRESH) {
            // Cursor EOF alone is not destructive absence proof (audit F3): a provider
            // may return a valid subset. A known old row loses its placeholder only
            // when the second source check independently confirms the document is
            // gone; everything unconfirmed stays conservative and is carried below.
            previousIndex.keys.mapNotNullTo(HashSet()) { key ->
                val relative = identity(key) ?: return@mapNotNullTo null
                if (relative !in current && confirmMissing?.invoke(key) == true) relative else null
            }
        } else {
            confirmedAbsent
        }
        removeStaleSavePlaceholders(mirrorRoot, previousIndex.keys, known, localFiles::isOverridden)
        val canonicalRoot = mirrorRoot.canonicalFile
        for (key in previousIndex.keys) {
            val relative = identity(key) ?: continue
            if (relative !in missing || relative in current || isSourceEntryExcluded(relative)) continue
            val local = localFiles.fileForIndexKey(key) ?: continue
            try {
                // Source confirmation may race a running session's copy-up. Recheck
                // local ownership and emptiness under the same resource lock before deleting.
                localFiles.withFileLock(local) {
                    if (!localFiles.isOverridden(local) && local.isFile && local.length() == 0L &&
                        local.canonicalPath.startsWith(canonicalRoot.path + File.separator)
                    ) {
                        if (!local.delete()) throw PrepareException(Stage.MIRROR_DIR, local.name)
                    }
                }
            } catch (error: IOException) {
                throw PrepareException(Stage.MIRROR_DIR, local.name).also { it.initCause(error) }
            }
        }
        var carried = 0
        for ((key, entry) in previousIndex) {
            val relative = identity(key) ?: continue
            if (relative in current || relative in confirmedAbsent || relative in missing ||
                isSourceEntryExcluded(relative)
            ) continue
            entries += Triple(key, entry.uri, entry.size)
            known.add(key)
            current.add(relative)
            carried++
        }
        return carried
    }

    /** [prepare] 使用的镜像 key；存档管理侧据此定位引擎真实读写的镜像目录，算法必须与 prepare 一致。 */
    @JvmStatic
    fun mirrorKey(sourceReference: String, logicalPath: String): String =
        KrMirrorDigest.sha256Hex("$sourceReference\n$logicalPath").take(16)

    /** Locate by stable source key; reuse an existing legacy root without moving its saves. */
    @JvmStatic
    fun mirrorRootFor(
        context: Context,
        sourceReference: String,
        logicalPath: String,
    ): File {
        val dataRoot = context.filesDir.parentFile ?: context.filesDir
        val gamesRoot = File(dataRoot, "games")
        val key = mirrorKey(sourceReference, logicalPath)
        // Serialize marker read-modify-write with concurrent prepares when the lock is
        // free within a bounded wait; a slow prepare in this or another process must
        // not block the save-management UI indefinitely, so fall back to the tolerant
        // (atomic-marker) path instead.
        val indexDirectory = File(context.noBackupFilesDir, "krkr_saf_index")
        return tryStickySelectionLock(indexDirectory, key) {
            withStickyRootSelection(context, gamesRoot, key) {
                findMirrorRoot(gamesRoot, key)
            }
        } ?: withStickyRootSelection(context, gamesRoot, key) {
            findMirrorRoot(gamesRoot, key)
        }
    }

    /**
     * Bounded per-key lock for the sticky marker; null = lock busy or unavailable,
     * caller falls back to the tolerant path — I/O failures (missing directory,
     * EMFILE/ENOSPC/EACCES) must degrade here instead of escaping into
     * resolveSaveLocation/cleanupAppData (review round 10, #4). The index directory
     * is otherwise only created by [prepare]: a first-ever save-management open
     * before any launch used to throw FileNotFoundException here, and because the
     * file was constructed outside the finally scope it also leaked the held process
     * mutex — every later prepare on the shard then blocked until process death
     * (local audit F1).
     */
    internal fun <T> tryStickySelectionLock(indexDirectory: File, key: String, action: () -> T): T? {
        val lockFile = File(indexDirectory, "prepare-${(key.hashCode() and Int.MAX_VALUE) % PREPARATION_LOCK_SHARDS}.lock")
        val mutex = preparationLocks.getOrPut(lockFile.absolutePath) { ReentrantLock() }
        val locked = try {
            mutex.tryLock(500, TimeUnit.MILLISECONDS)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw CancellationException("Sticky selection lock interrupted").also { it.initCause(error) }
        }
        if (!locked) return null
        try {
            if (!indexDirectory.isDirectory && !indexDirectory.mkdirs() && !indexDirectory.isDirectory) return null
            val file = try {
                RandomAccessFile(lockFile, "rw")
            } catch (error: IOException) {
                Log.w(TAG, "sticky selection lock unavailable path=${lockFile.path}", error)
                return null
            }
            try {
                val held = try {
                    file.channel.tryLock()
                } catch (_: OverlappingFileLockException) {
                    null
                } catch (error: IOException) {
                    Log.w(TAG, "sticky selection channel lock unavailable path=${lockFile.path}", error)
                    null
                } ?: return null
                try {
                    return action()
                } finally {
                    runCatching { held.release() }
                }
            } finally {
                runCatching { file.close() }
            }
        } finally {
            mutex.unlock()
        }
    }

    /**
     * The first root selection is sticky: once a launch has prepared mirrors or an import
     * has targeted a root, later save mtimes in other same-key roots must not rebind the
     * key — otherwise a just-imported stable root would be switched away and its
     * `savedata.import_backup` would be orphaned. The choice is persisted beside the
     * index and invalidated only when the persisted root directory disappears.
     */
    internal fun withStickyRootSelection(
        context: Context,
        gamesRoot: File,
        key: String,
        choose: () -> File,
    ): File = withStickyRootSelection(gamesRoot, File(rootSelectionDirectory(context), "root-$key.txt"), key, choose)

    /** File-injected core so JVM tests can cover persist/reuse/invalidate without Android. */
    internal fun withStickyRootSelection(
        gamesRoot: File,
        selection: File,
        key: String,
        choose: () -> File,
    ): File {
        if (selection.isFile) {
            val name = runCatching { selection.readText(Charsets.UTF_8).trim() }.getOrNull().orEmpty()
            if (isValidRootSelection(gamesRoot, name, key)) {
                return File(gamesRoot, name)
            }
            runCatching { selection.delete() }
        }
        val chosen = choose()
        if (chosen.isDirectory) writeRootSelection(selection, chosen.name)
        return chosen
    }

    /** The marker must name one plain directory directly under [gamesRoot] (canonical check). */
    internal fun isValidRootSelection(gamesRoot: File, name: String, key: String): Boolean {
        if (name.isEmpty() || name == "." || name == "..") return false
        if (name.contains('/') || name.contains('\\') || name.contains(File.separatorChar)) return false
        if (!name.endsWith("-$key")) return false
        val persisted = File(gamesRoot, name)
        if (!persisted.isDirectory) return false
        return runCatching {
            persisted.canonicalFile.parentFile == gamesRoot.canonicalFile
        }.getOrDefault(false)
    }

    /** Publish atomically: readers never observe a truncated marker mid-write. */
    internal fun writeRootSelection(selection: File, name: String) {
        runCatching {
            selection.parentFile?.mkdirs()
            val temp = File(selection.parentFile, selection.name + ".tmp")
            temp.writeText(name, Charsets.UTF_8)
            if (!temp.renameTo(selection)) temp.delete()
        }
    }

    internal fun rootSelectionDirectory(context: Context): File =
        File(context.noBackupFilesDir, "krkr_saf_index")

    internal fun findMirrorRoot(gamesRoot: File, key: String): File {
        val stable = File(gamesRoot, "game-$key")
        // Prefer actual saves even when an empty stable root already exists. The import
        // transaction's savedata.import_backup counts as staged saves: a crash inside the
        // exchange window must not rebind this key to a different root. Never rename/merge
        // legacy directories; newest real save mtime wins before stable/name tie-breaks.
        return gamesRoot.listFiles()
            ?.filter { it.isDirectory && it.name.endsWith("-$key") }
            ?.map { root ->
                val latestSave = sequenceOf(File(root, "savedata"), File(root, "savedata.import_backup"))
                    .flatMap { it.walkTopDown().asSequence() }
                    .filter { it.isFile && it.length() > 0L }
                    .maxOfOrNull { it.lastModified() }
                root to latestSave
            }
            ?.sortedWith(compareByDescending<Pair<File, Long?>> { it.second != null }
                .thenByDescending { it.second ?: Long.MIN_VALUE }
                .thenByDescending { it.first == stable }.thenBy { it.first.name })
            ?.firstOrNull()?.first ?: stable
    }

    internal data class IndexedLoad<T>(val documents: Map<String, MirrorEntryHolder<T>>, val rawCount: Int)

    /**
     * Single-parse load that also reports the RAW row count before local-override
     * filtering, so callers can distinguish "header-only because every row is a live
     * override" from "truncated/unknown file" without a second parse whose result can
     * diverge from the first (audit of 2fe2f38, N1).
     */
    internal fun <T> loadIndexed(
        indexPath: String?,
        mirrorRoot: File?,
        buildUri: (String) -> T,
    ): IndexedLoad<T> {
        if (indexPath.isNullOrBlank()) return IndexedLoad(emptyMap(), 0)
        val file = File(indexPath)
        // Preparation already excludes known local overrides. Read self-describing marker
        // keys once; only legacy markers require a one-time comparison with index keys.
        // Do not canonicalize/hash every source row or discard an unrecognized root alias.
        val entries = readIndexFile(file, buildUri)
        val localFiles = mirrorRoot?.let { KrMirrorLocalFiles(it) }
        val relativeOverrides = localFiles?.snapshotOverrideKeys(entries.keys).orEmpty()
        val documents = retainSourceEntries(entries, localFiles, relativeOverrides)
        return IndexedLoad(documents, entries.size)
    }

    internal fun <T> retainSourceEntries(
        entries: Map<String, MirrorEntryHolder<T>>,
        localFiles: KrMirrorLocalFiles?,
        relativeOverrides: Set<String>,
    ): Map<String, MirrorEntryHolder<T>> {
        if (localFiles == null || relativeOverrides.isEmpty()) return entries
        return entries.filterKeys { key ->
            val relative = localFiles.relativeForIndexKey(key)
            if (relative == null || relative !in relativeOverrides) return@filterKeys true
            // A marker can outlive its local file (an unlink whose marker delete
            // failed, or an external removal): such a STALE marker must not drop the
            // indexed row, or the key becomes unroutable and a read hits the missing
            // local file with ENOENT instead of the still-readable source document
            // (audit of 84e1d22, M1; KrMirrorOpenRouter #15 owns the read side).
            // Drop the row only when the owned file is really present.
            val owned = localFiles.fileForIndexKey(key)
            owned == null || !owned.isFile
        }
    }

    /** Shared production file reader; URI construction is injectable for plain JVM tests. */
    internal fun <T> readIndexFile(file: File, buildUri: (String) -> T): Map<String, MirrorEntryHolder<T>> {
        if (!file.isFile) return emptyMap()
        return try {
            file.bufferedReader().useLines { lines -> parseIndexLines(lines.asIterable(), buildUri) }
        } catch (error: IOException) {
            Log.w(TAG, "mirror index read failed path=${file.path}", error)
            emptyMap()
        }
    }

    /**
     * 索引文本行解析（与 [loadIndex] 共用；独立出来便于 JVM 单测——
     * Uri 构造经由 [buildUri] 注入，测试中用恒等函数绕开 android.net.Uri stub）。
     */
    internal fun <T> parseIndexLines(
        lines: Iterable<String>,
        buildUri: (String) -> T,
    ): Map<String, MirrorEntryHolder<T>> {
        val result = LinkedHashMap<String, MirrorEntryHolder<T>>()
        val iterator = lines.iterator()
        if (!iterator.hasNext()) return result
        when (iterator.next()) {
            // v2 行带第三列十进制尺寸；v1 无尺寸列，size 记 -1（stat 钩子不覆盖）
            INDEX_VERSION_V2 -> {}
            INDEX_VERSION_V1 -> {}
            else -> return result
        }
        var invalidRows = 0
        while (iterator.hasNext()) {
            val line = iterator.next()
            try {
                val split = line.indexOf('\t')
                if (split <= 0 || split >= line.lastIndex) continue
                val path = decode(line.substring(0, split)).lowercase(Locale.ROOT)
                val rest = line.substring(split + 1)
                val sizeSplit = rest.indexOf('\t')
                val uri = if (sizeSplit < 0) rest else rest.substring(0, sizeSplit)
                val size = if (sizeSplit < 0) -1L else {
                    rest.substring(sizeSplit + 1).toLongOrNull()?.takeIf { it >= 0L } ?: -1L
                }
                val decodedUri = decode(uri)
                if (path.isNotBlank() && decodedUri.isNotBlank()) {
                    result[path] = MirrorEntryHolder(buildUri(decodedUri), size)
                }
            } catch (_: IllegalArgumentException) {
                invalidRows++
            }
        }
        if (invalidRows > 0) Log.w(TAG, "skipped $invalidRows invalid mirror index rows")
        return result
    }

    /** Parse intermediate: the built uri plus its size; generic over android/net or test identity. */
    internal data class MirrorEntryHolder<T>(val uri: T, val size: Long)

    internal fun isSourceSavedata(relativePath: String): Boolean =
        relativePath.substringBefore('/').equals("savedata", ignoreCase = true)

    internal fun isSourceEntryExcluded(relativePath: String): Boolean =
        isSourceSavedata(relativePath) ||
            relativePath.substringBefore('/').equals(".tyranor-mirror-state", ignoreCase = true)

    /** Only remove prior indexed placeholders, never arbitrary zero-byte user save files. */
    internal fun removeStaleSavePlaceholders(
        root: File,
        previousKeys: Set<String>,
        currentKeys: Set<String>,
        isLocalOverride: (File) -> Boolean = { false },
    ): Int {
        val saveRoot = File(root, "savedata").canonicalFile
        val localFiles = KrMirrorLocalFiles(root)
        return previousKeys.count { key ->
            if (key in currentKeys) return@count false
            val file = localFiles.fileForIndexKey(key) ?: return@count false
            try {
                if (!file.canonicalPath.startsWith(saveRoot.path + File.separator)) return@count false
                // Deletion races a running session's copy-up: take the same resource
                // lock and re-verify ownership/emptiness under it, mirroring
                // reconcilePreparedIndex (review round 10, #14).
                localFiles.withFileLock(file) {
                    !isLocalOverride(file) && file.isFile && file.length() == 0L && file.delete()
                }
            } catch (_: IOException) {
                false
            }
        }
    }

    private data class ResolvedDocument(val treeUri: Uri, val documentId: String)

    /** Retry providers that reject the optional size projection; absent sizes stay unknown. */
    private fun queryWithOptionalSize(context: Context, uri: Uri, projection: Array<String>): Cursor? {
        return try {
            context.contentResolver.query(uri, projection, null, null, null)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            context.contentResolver.query(
                uri,
                projection.filterNot { it == DocumentsContract.Document.COLUMN_SIZE }.toTypedArray(),
                null, null, null,
            )
        }
    }

    /** Probe bootstrap documents by ID, independently of an incomplete children listing. */
    private fun probeBootstrapFiles(
        context: Context,
        source: ResolvedDocument,
        mirrorRoot: File,
        entries: MutableList<Triple<String, String, Long>>,
        known: MutableSet<String>,
        localFiles: KrMirrorLocalFiles,
        localOverrides: MutableSet<String>,
        diagnostics: BuildDiagnostics,
        previousIndex: Map<String, KrSafMirror.MirrorEntryHolder<String>>,
    ): Map<String, BootstrapFile> {
        return BOOTSTRAP_FILE_NAMES.associateWith { name ->
            val local = File(mirrorRoot, name)
            val key = local.absolutePath.lowercase(Locale.ROOT)
            localFiles.clearMissingOverride(local)
            val listed = entries.firstOrNull { it.first == key }
            bootstrapFromListedEntry(local, listed?.second, listed?.third ?: -1L,
                localFiles.isOverridden(local))?.let { bootstrap ->
                if (bootstrap.localOverride) localOverrides.add(key)
                return@associateWith bootstrap
            }
            try {
                val uri = DocumentsContract.buildDocumentUriUsingTree(source.treeUri, childDocumentId(source.documentId, name))
                val cursor = queryWithOptionalSize(
                    context, uri,
                    arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE),
                ) ?: return@associateWith bootstrapFromMissingOrPrevious(
                    name, key, local, true, diagnostics, previousIndex[key], entries, known,
                )
                cursor.use {
                    if (!it.moveToFirst()) {
                        return@associateWith bootstrapFromMissingOrPrevious(
                            name, key, local, true, diagnostics, previousIndex[key], entries, known,
                        )
                    }
                    val mimeColumn = it.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    if (mimeColumn >= 0 && it.getString(mimeColumn) == DocumentsContract.Document.MIME_TYPE_DIR) {
                        diagnostics.queryFailures++
                        return@associateWith BootstrapFile(BootstrapFileState.UNAVAILABLE, null, -1L, localOverride = false)
                    }
                    val sizeColumn = it.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                    val size = if (sizeColumn < 0 || it.isNull(sizeColumn)) -1L else it.getLong(sizeColumn)
                    localFiles.clearMissingOverride(local)
                    if (local.isDirectory || (!local.exists() && !local.createNewFile())) {
                        diagnostics.queryFailures++
                        return@associateWith BootstrapFile(BootstrapFileState.UNAVAILABLE, null, -1L, localOverride = false)
                    }
                    entries += Triple(key, uri.toString(), size)
                    known.add(key)
                    diagnostics.observedSourceFiles++
                    BootstrapFile(BootstrapFileState.PRESENT, uri.toString(), size, localOverride = false)
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                val bootstrap = bootstrapFromMissingOrPrevious(name, key, local,
                    error is FileNotFoundException, diagnostics, previousIndex[key], entries, known)
                if (bootstrap.state == BootstrapFileState.UNAVAILABLE) {
                    Log.w(TAG, "bootstrap document probe unavailable: $name", error)
                }
                bootstrap
            }
        }
    }

    /**
     * Shared null-cursor / empty-cursor / FNF handler. Absence is only proven when the
     * root listing is complete and does not contain the name; every other missing
     * response is untrustworthy, so a previous index row is durable evidence the source
     * existed and is carried as PRESENT (a flaky provider must not turn into a basic
     * overlay that shadows the real patch.tjs). Carried rows join entries/known so a
     * REBUILD_FRESH index keeps them, but never count as observed sources — otherwise
     * an empty listing would flip KEEP_EXISTING into a rebuilding run that erases the
     * whole placeholder tree.
     */
    private fun bootstrapFromMissingOrPrevious(
        name: String,
        key: String,
        local: File,
        missingResponse: Boolean,
        diagnostics: BuildDiagnostics,
        previous: KrSafMirror.MirrorEntryHolder<String>?,
        entries: MutableList<Triple<String, String, Long>>,
        known: MutableSet<String>,
    ): BootstrapFile {
        return when (resolveBootstrapMissing(
            name, missingResponse, diagnostics.rootListingComplete, diagnostics.rootNames, previous != null,
        )) {
            BootstrapMissingResolution.CONFIRMED_ABSENT ->
                BootstrapFile(BootstrapFileState.ABSENT, null, -1L, localOverride = false)
            BootstrapMissingResolution.CARRY_PREVIOUS -> {
                val row = requireNotNull(previous)
                val placeholderReady = try {
                    !local.isDirectory && (local.exists() || local.createNewFile())
                } catch (error: IOException) {
                    false
                }
                if (!placeholderReady) {
                    diagnostics.queryFailures++
                    BootstrapFile(BootstrapFileState.UNAVAILABLE, null, -1L, localOverride = false)
                } else {
                    entries += Triple(key, row.uri, row.size)
                    known.add(key)
                    BootstrapFile(BootstrapFileState.PRESENT, row.uri, row.size, localOverride = false)
                }
            }
            BootstrapMissingResolution.UNAVAILABLE -> {
                diagnostics.queryFailures++
                BootstrapFile(BootstrapFileState.UNAVAILABLE, null, -1L, localOverride = false)
            }
        }
    }

    /** Preserve listed source metadata; an empty placeholder is not a local override. */
    fun bootstrapFromListedEntry(
        local: File,
        indexedUri: String?,
        indexedSize: Long,
        markedLocal: Boolean,
    ): BootstrapFile? = when {
        local.isFile && (markedLocal || local.length() > 0L) ->
            BootstrapFile(BootstrapFileState.PRESENT, null, local.length(), localOverride = true)
        indexedUri != null ->
            BootstrapFile(BootstrapFileState.PRESENT, indexedUri, indexedSize, localOverride = false)
        else -> null
    }

    /** Shared null/FNF producer used before completeness accounting and app reader assembly. */
    internal fun bootstrapFromMissingResponse(
        name: String,
        missingResponse: Boolean,
        listingComplete: Boolean,
        listedNames: Set<String>,
    ): BootstrapFile = BootstrapFile(
        bootstrapProbeFailureState(name, missingResponse, listingComplete, listedNames),
        null, -1L, localOverride = false,
    )

    /**
     * Pure missing-response resolution (review round 9, #1/#2): absence is confirmed
     * only by a complete root listing without the name; a previous index row is
     * durable evidence of an existing source and is carried otherwise; without a row
     * the provider state is untrusted and fails unavailable.
     */
    internal enum class BootstrapMissingResolution { CONFIRMED_ABSENT, CARRY_PREVIOUS, UNAVAILABLE }

    internal fun resolveBootstrapMissing(
        name: String,
        missingResponse: Boolean,
        listingComplete: Boolean,
        listedNames: Set<String>,
        hasPreviousRow: Boolean,
    ): BootstrapMissingResolution = when {
        missingResponse && listingComplete && name.lowercase(Locale.ROOT) !in listedNames ->
            BootstrapMissingResolution.CONFIRMED_ABSENT
        // Only a missing response (null cursor / empty cursor / FNF) may carry the
        // previous row as evidence; a non-FNF exception is a provider failure and
        // must count as a query failure (UNAVAILABLE) instead of asserting presence.
        missingResponse && hasPreviousRow -> BootstrapMissingResolution.CARRY_PREVIOUS
        else -> BootstrapMissingResolution.UNAVAILABLE
    }

    /** Null/FNF missing-document responses need a complete root listing before proving absence. */
    internal fun bootstrapProbeFailureState(
        name: String,
        notFound: Boolean,
        listingComplete: Boolean,
        listedNames: Set<String>,
    ): BootstrapFileState = when (resolveBootstrapMissing(name, notFound, listingComplete, listedNames, false)) {
        BootstrapMissingResolution.CONFIRMED_ABSENT -> BootstrapFileState.ABSENT
        else -> BootstrapFileState.UNAVAILABLE
    }

    internal fun childDocumentId(parentDocumentId: String, name: String): String =
        parentDocumentId + (if (parentDocumentId.endsWith(':') || parentDocumentId.endsWith('/')) "" else "/") + name

    // 目录级 scanFile 已移除：实测 MediaScannerConnection 对目录路径不递归建行
    //（本机 ROM 25-67ms 即回调但 children 无变化），真正生效的是
    // probeSigSiblingDlls 的逐文件扫描（按文件路径建行即时可见）。

    /**
     * 用 MediaStore 文件表补全 SAF 子文档列表缺失的条目，返回补全数量。
     * 仅做增量合并：SAF 已列出的以 SAF 为准；缺失的按 `_data` 路径推导文档 ID，
     * 直接经 [treeUri] 构造文档 URI——按 ID 的 openFileDescriptor/stat
     * 不依赖 children 查询，树授权即可访问。查询失败（权限/ROM 差异）静默跳过。
     */
    private fun mergeMissingFromMediaStore(
        context: Context,
        treeUri: Uri,
        sourceRoot: String,
        mirrorRoot: File,
        entries: MutableList<Triple<String, String, Long>>,
        known: MutableSet<String>,
        localFiles: KrMirrorLocalFiles,
        diagnostics: BuildDiagnostics,
    ): Int {
        var merged = 0
        try {
            val collection = MediaStore.Files.getContentUri("external")
            context.contentResolver.query(
                collection,
                arrayOf(
                    MediaStore.MediaColumns.DATA,
                    MediaStore.MediaColumns.SIZE,
                    MediaStore.MediaColumns.MIME_TYPE,
                    // MediaStore 无公开 FORMAT 常量，目录行按 MTP format 列识别（association=0x3001）
                    "format",
                ),
                "${MediaStore.MediaColumns.DATA} LIKE ?",
                arrayOf("$sourceRoot/%"),
                null,
            )?.use { cursor ->
                val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
                val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                val formatColumn = cursor.getColumnIndex("format")
                while (cursor.moveToNext()) {
                    val data = cursor.getString(dataColumn) ?: continue
                    if (!data.startsWith("$sourceRoot/")) continue
                    // 目录行必须排除：否则会生成同名普通文件占位，后续子文件占位的
                    // parent.mkdirs() 全部失败，整棵子树被跳过。目录的 mime_type 通常为
                    // NULL，需按 MTP format 列（FORMAT_ASSOCIATION=0x3001）识别
                    val mime = cursor.getString(mimeColumn)
                    if (mime != null && mime == DocumentsContract.Document.MIME_TYPE_DIR) continue
                    if (formatColumn >= 0 && !cursor.isNull(formatColumn) &&
                        cursor.getLong(formatColumn) == 0x3001L
                    ) {
                        continue
                    }
                    val size = if (cursor.isNull(sizeColumn)) -1L else cursor.getLong(sizeColumn)
                    if (addMergedEntry(treeUri, sourceRoot, data, size, mirrorRoot, entries, known, localFiles, diagnostics)) merged++
                }
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            diagnostics.queryFailures++
            diagnostics.mergeFailures++
            Log.w(TAG, "merge media store entries failed root=$sourceRoot", t)
        }
        if (merged > 0) Log.i(TAG, "media store merged $merged missing files for $sourceRoot")
        return merged
    }

    /**
     * 树授权目录在 FUSE 层按路径对持有者放行：直接 File 遍历游戏目录补全 SAF 列表
     * 缺失的文件（尺寸取自 File）。应用无权访问（Scoped Storage 拒绝）时静默跳过。
     */
    private fun mergeMissingFromDirectListing(
        treeUri: Uri,
        sourceRoot: String,
        mirrorRoot: File,
        entries: MutableList<Triple<String, String, Long>>,
        known: MutableSet<String>,
        localFiles: KrMirrorLocalFiles,
        diagnostics: BuildDiagnostics,
    ): Int {
        val rootDir = File(sourceRoot)
        val canList = runCatching { rootDir.isDirectory && rootDir.canRead() }.getOrDefault(false)
        if (!canList) {
            diagnostics.mergeFailures++
            return 0
        }
        var merged = 0
        // 深度封顶防异常结构递归；krkr 游戏目录树通常 ≤4 层
        fun walk(dir: File, depth: Int) {
            if (depth > MIRROR_MAX_DEPTH) {
                diagnostics.depthLimitedDirectories++
                Log.w(TAG, "direct mirror depth limit reached dir=${dir.name}")
                return
            }
            val children = runCatching { dir.listFiles() }.getOrNull() ?: run {
                diagnostics.mergeFailures++
                return
            }
            for (child in children) {
                if (depth == 0 && isSourceEntryExcluded(child.name)) continue
                if (child.isDirectory) {
                    walk(child, depth + 1)
                } else if (child.isFile) {
                    if (addMergedEntry(treeUri, sourceRoot, child.absolutePath, child.length(), mirrorRoot, entries, known, localFiles, diagnostics)) {
                        merged++
                    }
                }
            }
        }
        walk(rootDir, 0)
        if (merged > 0) Log.i(TAG, "direct listing merged $merged missing files for $sourceRoot")
        return merged
    }

    /** 大小写不敏感地去掉 `.sig` 后缀（文档 ID 保留原始大小写，removeSuffix 会漏掉 `X.DLL.SIG`）。 */
    internal fun stripSigSuffix(name: String): String {
        return if (name.length >= 4 && name.regionMatches(name.length - 4, ".sig", 0, 4, ignoreCase = true)) {
            name.dropLast(4)
        } else {
            name
        }
    }

    /**
     * 末道兜底：已索引的 `X.dll.sig` 证明源目录存在插件 `X.dll`。对缺失的同名 DLL：
     * 先以应用身份逐文件触发媒体扫描（scanFile 对目录不递归，必须按文件路径扫描；
     * 扫描建行后按 ID 的访问与列表随之可见），再 queryDocument 校验并补全。
     * 真实路径取自 sig 条目 URI 中的文档 ID（保留原始大小写）。
     */
    private fun probeSigSiblingDlls(
        context: Context,
        treeUri: Uri,
        entries: MutableList<Triple<String, String, Long>>,
        known: MutableSet<String>,
        localFiles: KrMirrorLocalFiles,
        diagnostics: BuildDiagnostics,
    ): Int {
        val candidates = ArrayList<Pair<String, String>>() // (mirrorKey, sourcePath)
        val sigEntries = entries.filter { it.first.endsWith(".dll.sig") }.toList()
        for ((mirrorKeyPath, sigUriString, _) in sigEntries) {
            val dllMirrorKey = stripSigSuffix(mirrorKeyPath)
            if (dllMirrorKey == mirrorKeyPath || known.contains(dllMirrorKey)) continue
            val sigDocId = runCatching {
                DocumentsContract.getDocumentId(Uri.parse(sigUriString))
            }.getOrNull() ?: continue
            val dllSourcePath = documentIdToStoragePath(stripSigSuffix(sigDocId)) ?: continue
            candidates += dllMirrorKey to dllSourcePath
        }
        if (candidates.isEmpty()) return 0
        // Scan callbacks are best-effort; never add seconds to every launch for a missing DLL.
        val latch = CountDownLatch(candidates.size)
        try {
            MediaScannerConnection.scanFile(context, candidates.map { it.second }.toTypedArray(), null) { _, _ ->
                latch.countDown()
            }
            if (!awaitScanCallbacks(latch)) {
                diagnostics.scannerTimeouts++
                Log.w(TAG, "sig sibling scan timed out remaining=${latch.count} candidates=${candidates.size}")
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            diagnostics.mergeFailures++
            Log.w(TAG, "sig sibling scan failed remaining=${latch.count}", error)
        }
        var merged = 0
        for ((dllMirrorKey, dllSourcePath) in candidates) {
            val dllDocumentId = storagePathToDocumentId(dllSourcePath) ?: continue
            val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, dllDocumentId)
            // A present row with unknown size (-1) joins the index like the main
            // traversal does; only an exhausted cursor proves the source absent, and
            // absence is a fact — not an incompleteness signal for the decision. A
            // null cursor is a provider failure, not absence: count it as a query
            // failure so a transient hiccup degrades to REBUILD_MERGED instead of
            // silently dropping the DLL row (review round 9, #15).
            var absent = false
            var rowSize: Long? = null
            var queryFailed = false
            try {
                val cursor = context.contentResolver.query(
                    uri,
                    arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_SIZE),
                    null, null, null,
                )
                if (cursor == null) {
                    queryFailed = true
                } else cursor.use {
                    if (!it.moveToFirst()) {
                        absent = true
                    } else {
                        val sizeColumn = it.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                        rowSize = if (sizeColumn >= 0 && !it.isNull(sizeColumn)) it.getLong(sizeColumn) else -1L
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                queryFailed = true
                Log.w(TAG, "sig sibling metadata unavailable path=$dllSourcePath", error)
            }
            if (queryFailed) {
                diagnostics.queryFailures++
                continue
            }
            if (absent) continue
            val size = rowSize ?: -1L
            val local = localFiles.fileForIndexKey(dllMirrorKey)
            if (local == null) {
                diagnostics.skippedEntries++
                continue
            }
            val parent = local.parentFile
            if (parent == null || (!parent.isDirectory && !runCatching { parent.mkdirs() }.getOrDefault(false))) continue
            localFiles.clearMissingOverride(local)
            if (!local.exists() && !runCatching { local.createNewFile() }.getOrDefault(false)) continue
            diagnostics.observedSourceFiles++
            entries += Triple(dllMirrorKey, uri.toString(), size)
            known.add(dllMirrorKey)
            merged++
        }
        if (merged > 0) Log.i(TAG, "sig probing merged $merged plugin dlls")
        return merged
    }

    internal fun awaitScanCallbacks(latch: CountDownLatch, timeoutMillis: Long = SIG_SCAN_WAIT_MILLIS): Boolean =
        try {
            latch.await(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw CancellationException("Mirror media scan interrupted").also { it.initCause(error) }
        }

    /** 文档 ID（`卷:相对路径`）还原为 `/storage/卷/相对路径`；主存储 `primary:` 对应 emulated/0。 */
    private fun documentIdToStoragePath(documentId: String): String? {
        val colon = documentId.indexOf(':')
        if (colon <= 0 || colon == documentId.lastIndex) return null
        val volume = documentId.substring(0, colon)
        val rest = documentId.substring(colon + 1)
        if (volume.isBlank() || rest.isBlank()) return null
        val volumeDir = if (volume.equals("primary", ignoreCase = true)) "emulated/0" else volume
        return "/storage/$volumeDir/$rest"
    }

    /**
     * 把一条源文件路径合并进镜像（已在索引中则跳过）。[sourcePath] 必须位于
     * `[sourceRoot]/` 之下。返回是否新增。
     */
    private fun addMergedEntry(
        treeUri: Uri,
        sourceRoot: String,
        sourcePath: String,
        size: Long,
        mirrorRoot: File,
        entries: MutableList<Triple<String, String, Long>>,
        known: MutableSet<String>,
        localFiles: KrMirrorLocalFiles,
        diagnostics: BuildDiagnostics,
    ): Boolean {
        try {
            if (!sourcePath.startsWith("$sourceRoot/")) return false
            val relative = sourcePath.substring(sourceRoot.length + 1)
            if (relative.isBlank()) return false
            // 补全路径的中央拦截：源 savedata 子树与 mirrorDirectory 的跳过规则一致，
            // 否则 MediaStore/File 直列会把源存档合并成占位、绕过镜像跳过逻辑
            if (isSourceEntryExcluded(relative)) {
                return false
            }
            val local = File(mirrorRoot, relative.lowercase(Locale.ROOT))
            // canonical 前缀校验（与 mirrorDirectory 的 safeMirrorChild 同类防护）：
            // sourcePath 携带 ../ 时 File(mirrorRoot, relative) 可能逃出镜像目录
            val mirrorRootCanonical = runCatching { mirrorRoot.canonicalPath }
                .getOrDefault(mirrorRoot.absolutePath)
            val localCanonical = runCatching { local.canonicalPath }.getOrNull() ?: return false
            if (!localCanonical.startsWith("$mirrorRootCanonical/")) return false
            val key = local.absolutePath.lowercase(Locale.ROOT)
            if (known.contains(key)) return false
            // 目标已是目录（SAF 已列出该目录）时跳过：createNewFile 会失败，
            // exists()==true 也会把目录当文件写进索引
            if (local.isDirectory) return false
            val parent = local.parentFile ?: return false
            if (!parent.isDirectory && !parent.mkdirs()) return false
            localFiles.clearMissingOverride(local)
            if (!local.exists() && !local.createNewFile()) return false
            val documentId = storagePathToDocumentId(sourcePath) ?: return false
            entries += Triple(key, DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId).toString(), size)
            known.add(key)
            diagnostics.observedSourceFiles++
            return true
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            diagnostics.skippedEntries++
            Log.w(TAG, "merge entry failed path=$sourcePath", t)
            return false
        }
    }

    /**
     * Recursively mirror the SAF directory tree. One DocumentsContract children query per
     * directory (displayName/mime/size in the same projection, avoiding per-file length()
     * Binder round-trips). Placeholders stay 0 bytes: real content is supplied by
     * NativeBridge from the indexed SAF Uri, and the real size is recorded for the stat
     * hook override. [atRoot] skips the source `savedata` subtree: saves live in the
     * app-private mirror directory by design. A failed children query logs a warning and
     * returns the partial result (the three merge passes fill the gaps) instead of failing
     * the whole launch; [seenKeys] dedupes case-collapsed paths so two documents that only
     * differ in case cannot produce conflicting index entries.
     */
    private fun mirrorDirectory(
        context: Context,
        source: ResolvedDocument,
        destination: File,
        entries: MutableList<Triple<String, String, Long>>,
        seenKeys: MutableSet<String>,
        localFiles: KrMirrorLocalFiles,
        diagnostics: BuildDiagnostics,
        depth: Int = 0,
        atRoot: Boolean = false,
    ) {
        if (depth > MIRROR_MAX_DEPTH) {
            diagnostics.depthLimitedDirectories++
            Log.w(TAG, "SAF mirror depth limit reached dir=${destination.name}")
            return
        }
        val destRoot = runCatching { destination.canonicalPath }.getOrNull() ?: destination.absolutePath
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            source.treeUri,
            source.documentId,
        )
        val cursor = try {
            queryWithOptionalSize(
                context, childrenUri,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE),
            )
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            Log.w(TAG, "mirror children query failed dir=${destination.name}", error)
            null
        } ?: run {
            diagnostics.queryFailures++
            return
        }
        try {
            cursor.use {
                val idColumn = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameColumn = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeColumn = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeColumn = it.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                while (it.moveToNext()) {
                    try {
                        val documentId = it.getString(idColumn)
                        val name = it.getString(nameColumn)
                        if (documentId.isNullOrBlank() || name.isNullOrBlank()) {
                            if (atRoot) diagnostics.rootListingInvalid = true
                            diagnostics.skippedEntries++
                            continue
                        }
                        if (atRoot) diagnostics.rootNames.add(name.lowercase(Locale.ROOT))
                        val isDirectory = it.getString(mimeColumn) == DocumentsContract.Document.MIME_TYPE_DIR
                        val size = if (sizeColumn < 0 || it.isNull(sizeColumn)) -1L else it.getLong(sizeColumn)
                        val local = safeMirrorChild(destination, destRoot, name.lowercase(Locale.ROOT))
                        if (local == null) {
                            if (atRoot) diagnostics.rootListingInvalid = true
                            diagnostics.skippedEntries++
                            continue
                        }
                        if (atRoot && isSourceEntryExcluded(local.name)) continue
                        if (isDirectory) {
                            if (!local.isDirectory && !local.mkdirs()) throw IOException("Cannot create mirror directory")
                            mirrorDirectory(context, ResolvedDocument(source.treeUri, documentId), local,
                                entries, seenKeys, localFiles, diagnostics, depth + 1)
                        } else {
                            val key = local.absolutePath.lowercase(Locale.ROOT)
                            if (key in seenKeys) continue
                            localFiles.clearMissingOverride(local)
                            if (!local.exists() && !local.createNewFile()) throw IOException("Cannot create mirror placeholder")
                            entries += Triple(key,
                                DocumentsContract.buildDocumentUriUsingTree(source.treeUri, documentId).toString(), size)
                            seenKeys.add(key)
                            diagnostics.observedSourceFiles++
                        }
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        if (atRoot) diagnostics.rootListingInvalid = true
                        diagnostics.skippedEntries++
                        // A provider row or one invalid local filename must not discard sibling entries.
                        Log.w(TAG, "mirror entry skipped dir=${destination.name}", error)
                    }
                }
            }
            if (atRoot) diagnostics.rootListingComplete = !diagnostics.rootListingInvalid
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            diagnostics.queryFailures++
            // Cursor iteration/close can fail after some rows; keep the already collected files.
            Log.w(TAG, "mirror children listing incomplete dir=${destination.name}", error)
        }
    }

    /** 把 provider 给的条目名安全落到 [destination] 之下；不安全返回 null（调用方跳过）。 */
    private fun safeMirrorChild(destination: File, destRoot: String, name: String): File? {
        if (name.isBlank() || name == "." || name == "..") return null
        if (name.contains('/') || name.contains('\\')) return null
        val file = File(destination, name)
        val canonical = runCatching { file.canonicalPath }.getOrNull() ?: return null
        return if (canonical.startsWith(destRoot + File.separator)) file else null
    }

    /**
     * Resolve a scanned child document through the nearest persisted tree grant. A saved game Uri
     * is not guaranteed to be a tree Uri itself; providers commonly return a child document Uri
     * whose access is inherited from an ancestor selected through ACTION_OPEN_DOCUMENT_TREE.
     */
    private fun resolveDocumentDirectory(
        context: Context,
        sourceReference: String,
        logicalPath: String,
    ): ResolvedDocument? {
        val uri = Uri.parse(sourceReference)
        val contentUri = uri.takeIf { it.scheme.equals("content", ignoreCase = true) }
        val documentId = contentUri?.let {
            runCatching { DocumentsContract.getDocumentId(it) }.getOrNull()
                ?: runCatching { DocumentsContract.getTreeDocumentId(it) }.getOrNull()
        } ?: storagePathToDocumentId(logicalPath)
            ?: return null
        val candidates = LinkedHashMap<String, Uri>()
        contentUri?.let { sourceUri ->
            runCatching { DocumentsContract.getTreeDocumentId(sourceUri) }.getOrNull()?.let { treeId ->
                candidates[treeId] = sourceUri
            }
        }
        for (permission in context.contentResolver.persistedUriPermissions) {
            if (!permission.isReadPermission) continue
            if (contentUri != null && permission.uri.authority != contentUri.authority) continue
            if (contentUri == null && permission.uri.authority != "com.android.externalstorage.documents") continue
            val treeId = runCatching {
                DocumentsContract.getTreeDocumentId(permission.uri)
            }.getOrNull() ?: continue
            candidates[treeId] = permission.uri
        }
        val ordered = candidates.entries
            .filter { (treeId, _) -> relativeDocumentPath(documentId, treeId) != null }
            .sortedByDescending { it.key.length }
        for ((treeId, treeUri) in ordered) {
            val root = DocumentFile.fromTreeUri(context, treeUri)?.takeIf { it.isDirectory } ?: continue
            var current = root
            var failed = false
            val relative = relativeDocumentPath(documentId, treeId) ?: continue
            for (segment in relative.split('/').filter { it.isNotBlank() }) {
                val next = current.findFile(segment)
                    ?: current.listFiles().firstOrNull { it.name.equals(segment, ignoreCase = true) }
                if (next == null || !next.isDirectory) {
                    failed = true
                    break
                }
                current = next
            }
            if (!failed) {
                val resolvedId = runCatching { DocumentsContract.getDocumentId(current.uri) }.getOrNull()
                if (resolvedId != null) {
                    Log.i(TAG, "resolved document=$documentId from tree=$treeId name=${current.name}")
                    return ResolvedDocument(treeUri, resolvedId)
                }
            }
        }
        Log.e(TAG, "cannot resolve document=$documentId grants=${candidates.keys}")
        return null
    }

    /** Convert the real path used by native KRKR into ExternalStorageProvider's document id. */
    @JvmStatic
    fun storagePathToDocumentId(path: String?): String? {
        if (path.isNullOrBlank()) return null
        val normalized = path.replace('\\', '/').trimEnd('/')
        return when {
            normalized == "/storage/emulated/0" || normalized == "/sdcard" -> "primary:"
            normalized.startsWith("/storage/emulated/0/") ->
                "primary:${normalized.substring("/storage/emulated/0/".length)}"
            normalized.startsWith("/sdcard/") ->
                "primary:${normalized.substring("/sdcard/".length)}"
            normalized.startsWith("/storage/") -> {
                val rest = normalized.substring("/storage/".length)
                val slash = rest.indexOf('/')
                if (slash < 0) "$rest:" else {
                    val volume = rest.substring(0, slash)
                    val relative = rest.substring(slash + 1)
                    if (volume.isBlank()) null else "$volume:$relative"
                }
            }
            else -> null
        }
    }

    /** ExternalStorageProvider volume roots end in ':' rather than a path separator. */
    internal fun relativeDocumentPath(documentId: String, treeId: String): String? {
        if (documentId.equals(treeId, ignoreCase = true)) return ""
        val prefix = treeId + if (treeId.endsWith(':') || treeId.endsWith('/')) "" else "/"
        if (!documentId.startsWith(prefix, ignoreCase = true)) return null
        return documentId.substring(prefix.length)
    }

    /** Shared streaming serializer; production never materializes the whole index String. */
    internal fun writeIndexText(output: Appendable, entries: List<Triple<String, String, Long>>) {
        output.append(INDEX_VERSION_V2).append('\n')
        for ((path, uri, size) in entries) {
            output.append(encode(path)).append('\t')
                .append(encode(uri)).append('\t')
                .append(size.toString()).append('\n')
        }
    }


    private fun writeIndex(file: File, entries: List<Triple<String, String, Long>>) {
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            val writer = OutputStreamWriter(stream, Charsets.UTF_8).buffered()
            writeIndexText(writer, entries)
            writer.flush()
            atomic.finishWrite(stream)
        } catch (t: Throwable) {
            atomic.failWrite(stream)
            throw t
        }
    }

    // API 26+ URL-safe encoding without padding; the decoder also accepts padded legacy rows.
    // java.util.Base64 keeps the production format available to plain JVM regression tests.
    private fun encode(value: String): String =
        java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun decode(value: String): String =
        String(java.util.Base64.getUrlDecoder().decode(value), Charsets.UTF_8)
}
