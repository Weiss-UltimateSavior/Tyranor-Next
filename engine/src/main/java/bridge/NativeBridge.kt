package bridge

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.system.OsConstants
import android.util.Log
import org.tvp.kirikiri2.KR2Activity
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale

object NativeBridge {
    @Volatile
    private var SAF_DOCUMENTS: Map<String, Uri> = emptyMap()
    @Volatile
    private var krkrGameReadyListener: Runnable? = null
    @Volatile
    private var patchOverlayTarget: String? = null
    @Volatile
    private var patchOverlayPath: String? = null

    @JvmStatic external fun initialize(so: String?): Boolean
    @JvmStatic external fun isLaunchSceneReady(so: String?): Boolean
    @JvmStatic external fun launch(so: String?, path: String?, useMaps: Boolean): Boolean
    @JvmStatic external fun interceptor(prefix: String?): Unit
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

    @JvmStatic
    fun configureSafMirror(indexPath: String?) {
        SAF_DOCUMENTS = try {
            KrSafMirror.loadIndex(indexPath)
        } catch (t: Throwable) {
            Log.e("NativeBridge", "load SAF mirror index failed path=$indexPath", t)
            emptyMap()
        }
        Log.i("NativeBridge", "SAF mirror index entries=${SAF_DOCUMENTS.size}")
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

    @Synchronized
    @JvmStatic
    fun open(path: String?, mode: Int): Int {
        val normalized = KrPathUtils.canonicalizeKrStoragePath(KrPathUtils.normalizeFilePath(path))
        patchOverlayRedirect(normalized)?.takeIf { isReadOnlyOpen(mode) }?.let { overlay ->
            return openDirectFile(path, overlay, mode, diagnosticPrefix = "patch overlay")
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
        val mirrorUri = SAF_DOCUMENTS[target.lowercase(Locale.ROOT)]
        val readOnly = (mode and OsConstants.O_ACCMODE) == OsConstants.O_RDONLY
        if (readOnly && mirrorUri != null && File(target).length() == 0L) {
            val mirrorFd = openDocumentUri(mirrorUri, mode)
            if (mirrorFd >= 0) return mirrorFd
        }
        return try {
            openDirectFile(path, target, mode, diagnosticPrefix = if (redirected != null) "redirect" else null)
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

    @JvmStatic
    fun redirect(path: String?): String? {
        val raw = KrPathUtils.normalizeFilePath(path)
        val normalized = KrPathUtils.canonicalizeKrStoragePath(raw)
        KrPathUtils.redirectScopedSavePath(normalized)?.let { return it }
        if (normalized != null && normalized != path) return normalized
        return null
    }

    @JvmStatic
    fun redirectOpen(path: String?, mode: Int): String? {
        val raw = KrPathUtils.normalizeFilePath(path)
        val normalized = KrPathUtils.canonicalizeKrStoragePath(raw)
        if (isReadOnlyOpen(mode)) {
            patchOverlayRedirect(normalized)?.let { return it }
        }
        KrPathUtils.redirectScopedSavePath(normalized)?.let { return it }
        if (normalized != null && normalized != path) return normalized
        return null
    }

    @JvmStatic
    fun redirectReadMetadata(path: String?): String? {
        val raw = KrPathUtils.normalizeFilePath(path)
        val normalized = KrPathUtils.canonicalizeKrStoragePath(raw)
        patchOverlayRedirect(normalized)?.let { return it }
        KrPathUtils.redirectScopedSavePath(normalized)?.let { return it }
        if (normalized != null && normalized != path) return normalized
        return null
    }

    @JvmStatic
    fun redirectScopedSave(path: String?): String? {
        val normalized = KrPathUtils.canonicalizeKrStoragePath(KrPathUtils.normalizeFilePath(path))
        return KrPathUtils.redirectScopedSavePath(normalized)
    }

    private fun recordOpenDiagnostic(value: String) {
        try {
            KrPathUtils.currentActivity()?.getSharedPreferences("krkr_bridge_diagnostics", 0)
                ?.edit()?.putString("last_open", value)?.commit()
        } catch (_: Throwable) {
        }
    }

    private fun patchOverlayRedirect(path: String?): String? {
        val target = patchOverlayTarget ?: return null
        val overlay = patchOverlayPath ?: return null
        if (path == null || path.isBlank()) return null
        return if (path.equals(target, ignoreCase = true)) overlay else null
    }

    private fun isReadOnlyOpen(mode: Int): Boolean =
        (mode and OsConstants.O_ACCMODE) == OsConstants.O_RDONLY

    private fun openDirectFile(path: String?, target: String, mode: Int, diagnosticPrefix: String?): Int {
        val javaMode = toJavaMode(mode)
        val raf = RandomAccessFile(File(target), javaMode)
        if ((mode and OsConstants.O_TRUNC) == OsConstants.O_TRUNC) raf.setLength(0)
        if ((mode and OsConstants.O_APPEND) == OsConstants.O_APPEND) raf.seek(raf.length())
        val fd = getFd(raf)
        raf.close()
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
            intent != null && intent.getBooleanExtra("safFileFallback", false)
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
            val uri = storagePathToPersistedDocumentUri(p + "/.yukihub_dir_probe", OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_TRUNC) ?: return false
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

    private fun toJavaMode(mode: Int): String {
        val accessMode = mode and OsConstants.O_ACCMODE
        if (accessMode == OsConstants.O_RDONLY) return "r"
        if (accessMode == OsConstants.O_WRONLY || accessMode == OsConstants.O_RDWR) return "rw"
        throw IllegalArgumentException("Bad mode: $mode")
    }

    private fun toPfdMode(mode: Int): String {
        val accessMode = mode and OsConstants.O_ACCMODE
        if (accessMode == OsConstants.O_RDONLY) return "r"
        if ((mode and OsConstants.O_APPEND) == OsConstants.O_APPEND) return "wa"
        if ((mode and OsConstants.O_TRUNC) == OsConstants.O_TRUNC) return "wt"
        if (accessMode == OsConstants.O_WRONLY) return "w"
        if (accessMode == OsConstants.O_RDWR) return "rw"
        throw IllegalArgumentException("Bad mode: $mode")
    }

    private fun getFd(raf: RandomAccessFile): Int {
        val duplicate = ParcelFileDescriptor.dup(raf.fd)
        return duplicate.detachFd()
    }
}
