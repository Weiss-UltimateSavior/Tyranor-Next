package bridge

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.system.OsConstants
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.core.engine.LaunchContract
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.Locale
import org.tvp.kirikiri2.KR2Activity

object NativeBridge {
    @Volatile
    private var safMirror = KrMirrorReadSession<KrSafMirror.MirrorEntry>(emptyMap())
    @Volatile
    private var krkrGameReadyListener: Runnable? = null
    @Volatile
    private var patchOverlayTarget: String? = null
    @Volatile
    private var patchOverlayPath: String? = null
    @Volatile
    private var steamConfigOverlayTarget: String? = null
    @Volatile
    private var steamConfigOverlayPath: String? = null

    @JvmStatic external fun initialize(so: String?): Boolean
    @JvmStatic external fun isLaunchSceneReady(so: String?): Boolean
    @JvmStatic external fun launch(so: String?, path: String?, useMaps: Boolean): Boolean
    @JvmStatic external fun interceptor(prefix: String?): Unit
    @JvmStatic external fun setMirrorPrefix(prefix: String?): Unit
    @JvmStatic external fun relocate(): Int
    @JvmStatic external fun write(path: String?, data: ByteArray?): Boolean

    @JvmStatic
    private fun onKrkrGameReady() {
        val listener = krkrGameReadyListener
        Log.i("NativeBridge", "KRKR ready callback listener=${listener != null}")
        listener?.run()
    }

    @JvmStatic
    fun setKrkrGameReadyListener(listener: Runnable?) {
        krkrGameReadyListener = listener
    }

    /** 装载 SAF 镜像索引（path→文档 Uri+真实尺寸），供 [open] 路由与 [metadataSize] 查询。 */
    @JvmStatic
    @JvmOverloads
    fun configureSafMirror(indexPath: String?, mirrorRoot: String? = null) {
        val root = mirrorRoot?.takeIf { it.isNotBlank() }?.let { File(it) }
        if (root == null) {
            // A session without a root cannot route opens (the router fails closed for
            // every key) nor resolve ownership markers: load nothing instead of turning
            // all opens into EIO when a caller omits the root extra (review round 9, #16).
            safMirror = KrMirrorReadSession<KrSafMirror.MirrorEntry>(emptyMap(), null)
            Log.i("NativeBridge", "SAF mirror index skipped: mirror root missing path=$indexPath")
            return
        }
        val loadResult = try {
            val loaded = KrSafMirror.loadIndexed(indexPath, root) { uri -> Uri.parse(uri) }
            // A zero-RAW-row index means the file is missing, truncated or from an
            // unknown version — it degrades (fail closed) instead of serving every
            // placeholder as empty content (R10 #2). The raw count comes from the SAME
            // parse as the filtered documents; a header-only file still flags degraded,
            // and its live local overrides keep working through mirrorDegradedBlock's
            // ownership exemption (audit of 2fe2f38, N1/N2).
            loaded.documents.mapValues { (_, holder) ->
                KrSafMirror.MirrorEntry(holder.uri, holder.size)
            } to (loaded.rawCount == 0)
        } catch (t: Throwable) {
            Log.e("NativeBridge", "load SAF mirror index failed path=$indexPath", t)
            emptyMap<String, KrSafMirror.MirrorEntry>() to true
        }
        val documents = loadResult.first
        val degraded = loadResult.second
        val localFiles = root?.let { KrMirrorLocalFiles(it) }
        // snapshotOverrideKeys returns root-relative keys; translate them to this index's
        // full keys so the session's override lookups stay key-based. Named markers carry
        // their relative payload and resolve even for a header-only index (a rebuild
        // whose rows are all local overrides), so rebuild the full keys from the mirror
        // root directly instead of relying on document keys being present.
        val relativeOverrides = localFiles?.snapshotOverrideKeys(documents.keys).orEmpty()
        val initialOverrides = if (localFiles == null || relativeOverrides.isEmpty()) {
            emptySet()
        } else {
            documents.keys
                .filter { key -> localFiles.relativeForIndexKey(key)?.let { it in relativeOverrides } == true }
                .toSet() + relativeOverrides.map { File(root, it).absolutePath.lowercase(Locale.ROOT) }
        }
        safMirror = KrMirrorReadSession(documents, localFiles, initialOverrides, degraded)
        val sized = documents.values.count { it.size >= 0 }
        Log.i("NativeBridge", "SAF mirror index entries=${documents.size} sized=$sized overrides=${initialOverrides.size} degraded=$degraded")
    }

    /** JVM test seams for the degraded fail-closed session state. */
    internal val mirrorSessionDegradedForTest: Boolean
        get() = safMirror.degraded

    internal val mirrorSessionForTest: KrMirrorReadSession<*>
        get() = safMirror

    /**
     * The degraded fail-closed gate: existing unowned mirror paths fail, redirected savedata and
     * anything outside the mirror root keep their normal semantics. A header-only
     * index is the legitimate product of a rebuild whose rows are all local
     * overrides, so live owned files keep working across the gate (audit of
     * df6984c, P3-1). Extracted so the gate is JVM-testable without an activity
     * (audit of df6984c, P3-2).
     */
    internal fun mirrorDegradedBlock(session: KrMirrorReadSession<*>, key: String, file: File): Boolean {
        if (!session.degraded) return false
        val relative = session.localFiles?.relativeForIndexKey(key) ?: return false
        if (relative.equals("savedata", ignoreCase = true) ||
            relative.startsWith("savedata/", ignoreCase = true)
        ) return false
        // An absent path cannot expose a zero-byte placeholder. Preserve normal
        // ENOENT probes and explicit O_CREAT local creation even when a successful
        // all-override prepare published a header-only index (independent audit B3).
        if (!file.exists()) return false
        return !(session.isLocal(key, file) && file.isFile)
    }

    @JvmStatic
    fun configurePatchOverlay(targetPath: String?, overlayPath: String?) {
        val target = KrPathUtils.canonicalizeKrStoragePath(KrPathUtils.normalizeFilePath(targetPath))
        val overlay = KrPathUtils.normalizeFilePath(overlayPath)
        val overlayFile = overlay?.let { File(it) }
        if (target.isNullOrBlank() || overlay.isNullOrBlank() || overlayFile?.isFile != true) {
            patchOverlayTarget = null
            patchOverlayPath = null
            Log.i("NativeBridge", "KRKR patch overlay disabled target=$targetPath overlay=$overlayPath")
            return
        }
        patchOverlayTarget = target
        patchOverlayPath = overlay
        Log.i("NativeBridge", "KRKR patch overlay configured target=$target overlay=$overlay")
    }

    @JvmStatic
    fun configureSteamConfigOverlay(targetPath: String?, overlayPath: String?) {
        val target = KrPathUtils.canonicalizeKrStoragePath(KrPathUtils.normalizeFilePath(targetPath))
        val overlay = KrPathUtils.normalizeFilePath(overlayPath)
        val overlayFile = overlay?.let { File(it) }
        if (target.isNullOrBlank() || overlay.isNullOrBlank() || overlayFile?.isFile != true) {
            steamConfigOverlayTarget = null
            steamConfigOverlayPath = null
            Log.i("NativeBridge", "KRKR Steam config overlay disabled target=$targetPath overlay=$overlayPath")
            return
        }
        steamConfigOverlayTarget = target
        steamConfigOverlayPath = overlay
        Log.i("NativeBridge", "KRKR Steam config overlay configured target=$target overlay=$overlay")
    }

    /**
     * 引擎侧文件打开入口（native 钩子上调）。优先级：只读 overlay → scoped 存档重定向 →
     * SAF 镜像文档（只读资源或本地 copy-up）→ 本地直开/SAF 兜底。
     * 返回可用 fd；-1 允许 native 常规回退，-2 为必须停止回退的镜像 I/O 失败。
     */
    @JvmStatic
    fun open(path: String?, mode: Int): Int {
        val normalized = KrPathUtils.canonicalizeKrStoragePath(KrPathUtils.normalizeFilePath(path))
        readOnlyOverlayRedirect(normalized)?.takeIf { isReadOnlyOpen(mode) }?.let { overlay ->
            return openDirectFile(path, overlay, mode, diagnosticPrefix = "read-only overlay")
        }
        val redirected = KrPathUtils.redirectScopedSavePath(normalized)
        // The native hook uses the stable storage-volume prefix because KRKR may lowercase
        // the game path. Keep regular asset I/O native; only scoped saves need Java redirection.
        if (redirected == null && !isSafFallbackEnabled()) return -1
        val target = if (redirected != null) redirected else normalized ?: return -1
        val javaMode: String = try {
            toJavaMode(mode)
        } catch (t: Throwable) {
            Log.e("NativeBridge", "bad open mode=$mode path=$path", t)
            return -1
        }
        val session = safMirror
        val key = target.lowercase(Locale.ROOT)
        // A degraded session (unreadable/zero-row index) must not let the engine open
        // the 0-byte placeholders as empty content: fail closed for existing unowned paths under
        // the mirror root, while redirected saves keep their local semantics (R10 #2).
        if (mirrorDegradedBlock(session, key, File(target))) {
            Log.e("NativeBridge", "mirror index degraded; failing open path=$path")
            return KrMirrorOpenRouter.FAILED
        }
        if (session.shouldRoute(key)) {
            val router = KrMirrorOpenRouter(
                session = session,
                sourceSize = { it.size },
                sourceStream = { entry ->
                    val activity = KrPathUtils.currentActivity()
                        ?: throw IOException("Missing mirror activity")
                    activity.contentResolver.openInputStream(entry.uri)
                        ?: throw IOException("Cannot open mirror source")
                },
                sourceDescriptor = { entry -> openDocumentUri(entry.uri, OsConstants.O_RDONLY) },
                localDescriptor = { file, _ -> openDirectFile(path, file.path, mode, null) },
                closeDescriptor = { fd -> ParcelFileDescriptor.adoptFd(fd).close() },
                diagnostic = { message, error -> Log.w("NativeBridge", "$message path=$path flags=$mode", error) },
            )
            return router.open(key, File(target), mirrorOpenIntent(mode), allowCreate = (mode and OsConstants.O_CREAT) != 0)
        }
        return try {
            openUnindexedLocal(
                session = session,
                key = key,
                allowCreate = (mode and OsConstants.O_CREAT) != 0,
                intent = mirrorOpenIntent(mode),
                closeDescriptor = { fd -> ParcelFileDescriptor.adoptFd(fd).close() },
            ) {
                openDirectFile(path, target, mode, diagnosticPrefix = if (redirected != null) "redirect" else null)
            }
        } catch (directError: Throwable) {
            if (isSafFallbackEnabled()) {
                val safFd = openViaSaf(target, mode, directError)
                if (safFd >= 0) return safFd
            }
            if (redirected != null) recordOpenDiagnostic(
                "failed path=$path target=$target flags=$mode mode=$javaMode " +
                    "error=${directError.javaClass.simpleName}:${directError.message}",
            )
            Log.e("NativeBridge", "open failed mode=$mode path=$path", directError)
            -1
        }
    }

    internal fun mirrorOpenIntent(mode: Int): KrMirrorOpenRouter.Intent {
        val readIntent = mirrorReadAllowed(
            openAccess(mode),
            append = (mode and OsConstants.O_APPEND) != 0,
            truncate = (mode and OsConstants.O_TRUNC) != 0,
        )
        return when {
            !readIntent && (mode and OsConstants.O_TRUNC) != 0 -> KrMirrorOpenRouter.Intent.TRUNCATE
            !readIntent -> KrMirrorOpenRouter.Intent.WRITE
            openAccess(mode) == FileAccess.READ_WRITE -> KrMirrorOpenRouter.Intent.UPDATE
            else -> KrMirrorOpenRouter.Intent.READ_ONLY
        }
    }

    /**
     * Preserve libc existence semantics for unindexed degraded mirror probes and publish
     * durable ownership when an unindexed mirror resource is created locally. Java's
     * RandomAccessFile("rw") creates missing files even without O_CREAT, so defer
     * non-creating degraded requests to native rather than checking existence before Java open.
     * Indexed/owned paths have already been handled by the mirror router.
     */
    internal fun openUnindexedLocal(
        session: KrMirrorReadSession<*>,
        key: String,
        allowCreate: Boolean,
        directOpen: () -> Int,
    ): Int = openUnindexedLocal(session, key, allowCreate, KrMirrorOpenRouter.Intent.WRITE, {}, directOpen)

    internal fun openUnindexedLocal(
        session: KrMirrorReadSession<*>,
        key: String,
        allowCreate: Boolean,
        closeDescriptor: (Int) -> Unit,
        directOpen: () -> Int,
    ): Int = openUnindexedLocal(session, key, allowCreate, KrMirrorOpenRouter.Intent.WRITE, closeDescriptor, directOpen)

    internal fun openUnindexedLocal(
        session: KrMirrorReadSession<*>,
        key: String,
        allowCreate: Boolean,
        intent: KrMirrorOpenRouter.Intent,
        closeDescriptor: (Int) -> Unit,
        directOpen: () -> Int,
    ): Int {
        val resource = session.localFiles?.resourceFileForPath(key)
        if (resource != null) {
            if (intent != KrMirrorOpenRouter.Intent.READ_ONLY && (allowCreate || (!session.degraded && resource.isFile && resource.length() > 0L))) {
                return KrMirrorOpenRouter(
                    session = session,
                    sourceSize = { -1L },
                    sourceStream = { throw IOException("Cannot open unindexed mirror source") },
                    sourceDescriptor = { KrMirrorOpenRouter.FAILED },
                    localDescriptor = { _, _ -> directOpen() },
                    closeDescriptor = closeDescriptor,
                    diagnostic = { message, error -> Log.w("NativeBridge", "$message key=$key", error) },
                ).open(key, resource, intent, allowCreate = allowCreate)
            }
            if (session.degraded || !resource.isFile || resource.length() == 0L) {
                return KrMirrorOpenRouter.NOT_ROUTED
            }
        }
        return directOpen()
    }

    /** JNI bridge back to unhooked libc pointers; called only within the Java lock transaction. */
    @JvmStatic
    private external fun executeOriginalMirrorMutation(operation: Int, from: String, to: String?): Long

    /** Result low word is syscall result; high word preserves errno across Java bookkeeping. */
    @JvmStatic
    fun runMirrorMutation(operation: Int, fromPath: String?, toPath: String?): Long {
        val session = safMirror
        val state = session.localFiles ?: return Long.MIN_VALUE
        val fromKey = fromPath?.lowercase(Locale.ROOT)
        val from = fromKey?.let { state.resourceFileForPath(it) }
        val toKey = toPath?.lowercase(Locale.ROOT)
        val to = toKey?.let { state.resourceFileForPath(it) }
        if (fromPath == null || (from == null && to == null)) return Long.MIN_VALUE
        return try {
            session.runMutation(fromKey.takeIf { from != null }, from, toKey, to) {
                executeOriginalMirrorMutation(operation, fromPath, toPath)
            }
        } catch (cancelled: kotlin.coroutines.cancellation.CancellationException) {
            // Cancellation is not a syscall failure; it must propagate as-is (AGENTS).
            throw cancelled
        } catch (error: Exception) {
            Log.w("NativeBridge", "Mirror mutation failed from=$fromPath to=$toPath", error)
            val errorNumber = if (Thread.currentThread().isInterrupted) OsConstants.EINTR else OsConstants.EIO
            (errorNumber.toLong() shl 32) or 0xffffffffL
        }
    }

    /** Called only for mirror-relative asset suffixes; root/scoped save case stays intact. */
    @JvmStatic
    fun normalizeMirrorCase(path: String?): String? = path?.lowercase(Locale.ROOT)

    /** 仅做路径规范化/重定向（不开文件）：scoped 存档重定向优先，其次规范化差异。 */
    @JvmStatic
    fun redirect(path: String?): String? {
        val raw = KrPathUtils.normalizeFilePath(path)
        val normalized = KrPathUtils.canonicalizeKrStoragePath(raw)
        KrPathUtils.redirectScopedSavePath(normalized)?.let { return it }
        if (normalized != null && normalized != path) return normalized
        return null
    }

    /** [redirect] 的带模式版本：只读时叠加只读 overlay 重定向，供 fopen/open 回退路径选择。 */
    @JvmStatic
    fun redirectOpen(path: String?, mode: Int): String? {
        val raw = KrPathUtils.normalizeFilePath(path)
        val normalized = KrPathUtils.canonicalizeKrStoragePath(raw)
        if (isReadOnlyOpen(mode)) {
            readOnlyOverlayRedirect(normalized)?.let { return it }
        }
        KrPathUtils.redirectScopedSavePath(normalized)?.let { return it }
        if (normalized != null && normalized != path) return normalized
        return null
    }

    /** 元数据读取（stat/access）前的重定向：只读 overlay 与 scoped 存档重定向。 */
    @JvmStatic
    fun redirectReadMetadata(path: String?): String? {
        val raw = KrPathUtils.normalizeFilePath(path)
        val normalized = KrPathUtils.canonicalizeKrStoragePath(raw)
        readOnlyOverlayRedirect(normalized)?.let { return it }
        KrPathUtils.redirectScopedSavePath(normalized)?.let { return it }
        if (normalized != null && normalized != path) return normalized
        return null
    }

    /** scoped 存档目录重定向：命中 SCOPED_SAVE_DIR 配置的路径改写到独立存档根。 */
    @JvmStatic
    fun redirectScopedSave(path: String?): String? {
        val normalized = KrPathUtils.canonicalizeKrStoragePath(KrPathUtils.normalizeFilePath(path))
        return KrPathUtils.redirectScopedSavePath(normalized)
    }

    /**
     * 镜像占位文件的 stat 尺寸覆盖：本地占位为 0 字节、SAF 侧记录了真实尺寸时返回该尺寸，
     * 否则返回 -1（不覆盖——引擎已本地覆写的文件 stat 本来就正确）。
     * 可写打开先 copy-up 为本地覆盖并退出索引，SD 源文件始终不被引擎改写。
     * 供 native stat/lstat 钩子把 st_size 纠正为真实值：引擎按 stat 尺寸分配缓冲/匹配插件时，
     * 0 字节会让插件 DLL 与既有存档被当成空文件（issue #129 的 SD 卡 Krkr 启动失败/存档不可读）。
     */
    @JvmStatic
    fun metadataSize(path: String?): Long {
        val normalized = KrPathUtils.canonicalizeKrStoragePath(KrPathUtils.normalizeFilePath(path))
            ?: return -1L
        val key = normalized.lowercase(Locale.ROOT)
        val session = safMirror
        // Top-level savedata rows (pre-upgrade indexes) stay local: never override their
        // stat with indexed sizes, or a 0-byte local save reads as N bytes.
        if (!session.shouldRoute(key)) return -1L
        val entry = session.entry(key) ?: return -1L
        if (session.isLocal(key, File(normalized))) return -1L
        // Native calls this only for a successful stat of a zero-byte regular file.
        // Ownership path derivation is cached by the session; do not canonicalize/hash here.
        return mirrorMetadataSize(entry.size, 0L, session.readFailed(key))
    }

    /** A local overwrite or failed source read always wins over indexed placeholder metadata. */
    internal fun mirrorMetadataSize(indexedSize: Long, localSize: Long, readFailed: Boolean): Long =
        if (!readFailed && indexedSize >= 0L && localSize == 0L) indexedSize else -1L

    private fun recordOpenDiagnostic(value: String) {
        try {
            KrPathUtils.currentActivity()?.getSharedPreferences("krkr_bridge_diagnostics", 0)
                ?.edit()?.putString("last_open", value)?.commit()
        } catch (_: Throwable) {
        }
    }

    private fun readOnlyOverlayRedirect(path: String?): String? {
        if (path == null || path.isBlank()) return null
        val steamConfigTarget = steamConfigOverlayTarget
        val steamConfigOverlay = steamConfigOverlayPath
        if (
            !steamConfigTarget.isNullOrBlank() &&
            !steamConfigOverlay.isNullOrBlank() &&
            matchesOverlayTarget(path, steamConfigTarget)
        ) {
            return steamConfigOverlay
        }
        val patchTarget = patchOverlayTarget
        val patchOverlay = patchOverlayPath
        if (
            !patchTarget.isNullOrBlank() &&
            !patchOverlay.isNullOrBlank() &&
            matchesOverlayTarget(path, patchTarget)
        ) {
            return patchOverlay
        }
        return null
    }

    private fun matchesOverlayTarget(path: String, target: String): Boolean {
        if (path.equals(target, ignoreCase = true)) return true
        if (path.contains('/')) return false
        return path.equals(File(target).name, ignoreCase = true)
    }

    private fun isReadOnlyOpen(mode: Int): Boolean =
        (mode and OsConstants.O_ACCMODE) == OsConstants.O_RDONLY

    private fun openDirectFile(path: String?, target: String, mode: Int, diagnosticPrefix: String?): Int {
        val javaMode = toJavaMode(mode)
        val fd = RandomAccessFile(File(target), javaMode).use { raf ->
            if ((mode and OsConstants.O_TRUNC) == OsConstants.O_TRUNC) raf.setLength(0)
            if ((mode and OsConstants.O_APPEND) == OsConstants.O_APPEND) raf.seek(raf.length())
            getFd(raf)
        }
        if (diagnosticPrefix != null) recordOpenDiagnostic(
            "$diagnosticPrefix ok path=$path target=$target flags=$mode mode=$javaMode fd=$fd",
        )
        Log.i("NativeBridge", "open $fd $javaMode $path -> $target")
        return fd
    }

    private fun isSafFallbackEnabled(): Boolean {
        return try {
            val activity = KrPathUtils.currentActivity()
            val intent = activity?.intent
            intent != null && intent.getBooleanExtra(LaunchContract.SAF_FILE_FALLBACK, false)
        } catch (_: Throwable) {
            false
        }
    }

    private fun openViaSaf(path: String, mode: Int, directError: Throwable): Int {
        return try {
            val uri = storagePathToPersistedDocumentUri(path, mode) ?: return -1
            openDocumentUri(uri, mode)
        } catch (safError: Throwable) {
            Log.w("NativeBridge", "open SAF fallback failed path=$path direct=$directError", safError)
            -1
        }
    }

    private fun openDocumentUri(uri: Uri, mode: Int): Int {
        val activity = KrPathUtils.currentActivity() ?: return -1
        val pfdMode = toPfdMode(mode)
        val pfd = activity.contentResolver.openFileDescriptor(uri, pfdMode) ?: return -1
        // PFD 无读写追加模式：RDWR|APPEND 以 "rw" 打开后在此定位到末尾实现追加语义
        // （Os.lseek 需要 FileDescriptor，须在 detachFd 前执行）
        if (pfdMode == "rw" && (mode and OsConstants.O_APPEND) != 0) {
            runCatching { android.system.Os.lseek(pfd.fileDescriptor, 0, OsConstants.SEEK_END) }
        }
        val fd = pfd.detachFd()
        Log.i("NativeBridge", "open SAF $fd $pfdMode -> $uri")
        return fd
    }

    @JvmStatic
    fun writeViaSafIfPossible(path: String?, data: ByteArray?): Boolean {
        return try {
            val p = KrPathUtils.canonicalizeKrStoragePath(path) ?: return false
            val uri = storagePathToPersistedDocumentUri(p, OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_TRUNC) ?: return false
            val activity = KrPathUtils.currentActivity() ?: return false
            val out = activity.contentResolver.openOutputStream(uri, "wt") ?: return false
            out.use {
                if (data != null) it.write(data)
                it.flush()
            }
            Log.i("NativeBridge", "write SAF $p -> $uri bytes=${data?.size ?: 0}")
            true
        } catch (t: Throwable) {
            Log.w("NativeBridge", "write SAF failed path=$path", t)
            false
        }
    }

    @JvmStatic
    fun createDirectoryViaSafIfPossible(path: String?): Boolean {
        return try {
            val p = KrPathUtils.canonicalizeKrStoragePath(path) ?: return false
            val uri = storagePathToPersistedDocumentUri(p + "/.tyranor_dir_probe", OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_TRUNC) ?: return false
            val activity = KrPathUtils.currentActivity()
            if (activity != null) {
                try { DocumentsContract.deleteDocument(activity.contentResolver, uri) } catch (_: Throwable) {}
            }
            Log.i("NativeBridge", "mkdir SAF $p")
            true
        } catch (t: Throwable) {
            Log.w("NativeBridge", "mkdir SAF failed path=$path", t)
            false
        }
    }

    @JvmStatic
    fun deleteViaSafIfPossible(path: String?): Boolean {
        return try {
            val p = KrPathUtils.canonicalizeKrStoragePath(path) ?: return false
            val uri = storagePathToPersistedDocumentUri(p, OsConstants.O_RDONLY) ?: return false
            val activity = KrPathUtils.currentActivity() ?: return false
            val ok = DocumentsContract.deleteDocument(activity.contentResolver, uri)
            Log.i("NativeBridge", "delete SAF $p -> $uri ok=$ok")
            ok
        } catch (t: Throwable) {
            Log.w("NativeBridge", "delete SAF failed path=$path", t)
            false
        }
    }

    @JvmStatic
    fun existsViaSafIfPossible(path: String?): Boolean {
        return try {
            val p = KrPathUtils.canonicalizeKrStoragePath(path) ?: return false
            val uri = storagePathToPersistedDocumentUri(p, OsConstants.O_RDONLY) ?: return false
            val activity = KrPathUtils.currentActivity() ?: return false
            val input = activity.contentResolver.openInputStream(uri)
            input.use { it != null }
        } catch (_: Throwable) {
            false
        }
    }

    @JvmStatic
    fun renameViaSafIfPossible(from: String?, to: String?): Boolean {
        return try {
            val f = KrPathUtils.canonicalizeKrStoragePath(from) ?: return false
            val t = KrPathUtils.canonicalizeKrStoragePath(to) ?: return false
            val src = storagePathToPersistedDocumentUri(f, OsConstants.O_RDONLY) ?: return false
            val activity = KrPathUtils.currentActivity() ?: return false
            val resolver = activity.contentResolver
            val input = resolver.openInputStream(src) ?: return false
            input.use {
                val dst = storagePathToPersistedDocumentUri(t, OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_TRUNC) ?: return false
                val out = resolver.openOutputStream(dst, "wt") ?: return false
                out.use { o ->
                    val buf = ByteArray(64 * 1024)
                    var n: Int
                    while (input.read(buf).also { n = it } > 0) o.write(buf, 0, n)
                    o.flush()
                }
                try { DocumentsContract.deleteDocument(resolver, src) } catch (_: Throwable) {}
                Log.i("NativeBridge", "rename SAF $f -> $t src=$src")
                true
            }
        } catch (t: Throwable) {
            Log.w("NativeBridge", "rename SAF failed $from -> $to", t)
            false
        }
    }

    @SuppressLint("SdCardPath")
    private fun storagePathToPersistedDocumentUri(path: String?, mode: Int): Uri? {
        if (path == null) return null
        val p = KrPathUtils.normalizeFilePath(path) ?: return null
        if (!p.startsWith("/storage/") && !p.startsWith("/sdcard")) return null
        val volume: String
        val rel: String
        if (p.startsWith("/storage/emulated/0/")) {
            volume = "primary"
            rel = p.substring("/storage/emulated/0/".length)
        } else if ("/storage/emulated/0" == p) {
            volume = "primary"
            rel = ""
        } else if (p.startsWith("/sdcard/")) {
            volume = "primary"
            rel = p.substring("/sdcard/".length)
        } else if ("/sdcard" == p) {
            volume = "primary"
            rel = ""
        } else {
            val rest = p.substring("/storage/".length)
            val slash = rest.indexOf('/')
            if (slash <= 0) return null
            volume = rest.substring(0, slash)
            rel = rest.substring(slash + 1)
        }
        if (volume.isEmpty()) return null
        val activity = KrPathUtils.currentActivity() ?: return null
        val resolver = activity.contentResolver
        val docId = "$volume:$rel"
        Log.i("NativeBridge", "SAF resolve path=$path volume=$volume rel=$rel")
        for (perm in resolver.persistedUriPermissions) {
            val tree = perm.uri ?: continue
            val treeId = try { DocumentsContract.getTreeDocumentId(tree) } catch (_: Throwable) { null } ?: continue
            val decodedTreeId = Uri.decode(treeId)
            if (!decodedTreeId.startsWith("$volume:")) continue
            val treeRel = decodedTreeId.substring("$volume:".length)
            if (treeRel.isNotEmpty()) {
                if (rel != treeRel && !rel.startsWith("$treeRel/")) continue
            }
            val existing = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
            if (!needsCreate(mode)) return existing
            val created = ensureDocumentExists(resolver, tree, decodedTreeId, volume, rel)
            return created ?: existing
        }
        return null
    }

    private fun needsCreate(mode: Int): Boolean {
        val accessMode = mode and OsConstants.O_ACCMODE
        return accessMode == OsConstants.O_WRONLY || accessMode == OsConstants.O_RDWR || (mode and OsConstants.O_CREAT) == OsConstants.O_CREAT
    }

    private fun ensureDocumentExists(resolver: ContentResolver, tree: Uri, decodedTreeId: String, volume: String, rel: String): Uri? {
        return try {
            val activity = KrPathUtils.currentActivity() ?: return null
            val dir = DocumentFile.fromTreeUri(activity, tree) ?: return null
            val treePrefix = "$volume:"
            val treeRel = if (decodedTreeId.startsWith(treePrefix)) decodedTreeId.substring(treePrefix.length) else ""
            var localRel = rel
            if (treeRel.isNotEmpty()) {
                if (localRel == treeRel) return DocumentsContract.buildDocumentUriUsingTree(tree, "$volume:$rel")
                if (localRel.startsWith("$treeRel/")) localRel = localRel.substring(treeRel.length + 1)
            }
            val parts = localRel.split("/".toRegex()).toTypedArray()
            var current: DocumentFile? = dir
            for (i in parts.indices) {
                val part = parts[i]
                if (part.isEmpty() || part == ".") continue
                val last = i == parts.size - 1
                var child = findChildDocument(current, part)
                if (last) {
                    if (child == null) child = current!!.createFile(guessMime(part), part)
                    return child?.uri
                }
                if (child == null) child = current!!.createDirectory(part)
                if (child == null || !child.isDirectory) return null
                current = child
            }
            null
        } catch (t: Throwable) {
            Log.w("NativeBridge", "ensure SAF document failed rel=$rel", t)
            null
        }
    }

    private fun guessMime(name: String?): String {
        val lower = name?.lowercase(Locale.ROOT) ?: ""
        if (lower.endsWith(".txt") || lower.endsWith(".tjs") || lower.endsWith(".ks") || lower.endsWith(".xml") || lower.endsWith(".json")) return "text/plain"
        return "application/octet-stream"
    }

    private fun findChildDocument(dir: DocumentFile?, name: String?): DocumentFile? {
        if (dir == null || name == null) return null
        return try {
            dir.findFile(name) ?: dir.listFiles().firstOrNull { it.getName()?.equals(name, ignoreCase = true) == true }
        } catch (_: Throwable) {
            null
        }
    }

    internal enum class FileAccess { READ_ONLY, WRITE_ONLY, READ_WRITE }

    private fun openAccess(mode: Int): FileAccess = when (mode and OsConstants.O_ACCMODE) {
        OsConstants.O_RDONLY -> FileAccess.READ_ONLY
        OsConstants.O_WRONLY -> FileAccess.WRITE_ONLY
        OsConstants.O_RDWR -> FileAccess.READ_WRITE
        else -> throw IllegalArgumentException("Bad mode: $mode")
    }

    internal fun mirrorReadAllowed(access: FileAccess, append: Boolean, truncate: Boolean): Boolean =
        access != FileAccess.WRITE_ONLY && !append && !truncate

    private fun toJavaMode(mode: Int): String =
        if (openAccess(mode) == FileAccess.READ_ONLY) "r" else "rw"

    private fun toPfdMode(mode: Int): String = pfdModeFor(
        openAccess(mode),
        append = (mode and OsConstants.O_APPEND) != 0,
        truncate = (mode and OsConstants.O_TRUNC) != 0,
    )

    /** PFD has no non-truncating write-only mode and no read-write append mode. */
    internal fun pfdModeFor(access: FileAccess, append: Boolean, truncate: Boolean): String {
        if (access == FileAccess.READ_ONLY) return "r"
        if (append) return if (access == FileAccess.READ_WRITE) "rw" else "wa"
        if (truncate) return if (access == FileAccess.READ_WRITE) "rwt" else "wt"
        return "rw"
    }

    private fun getFd(raf: RandomAccessFile): Int {
        val duplicate = ParcelFileDescriptor.dup(raf.fd)
        return duplicate.detachFd()
    }
}
