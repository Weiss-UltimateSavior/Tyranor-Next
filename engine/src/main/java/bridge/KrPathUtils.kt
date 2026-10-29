package bridge

import android.util.Log
import com.core.engine.LaunchContract
import org.tvp.kirikiri2.KR2Activity
import java.io.File

object KrPathUtils {
    private const val TAG = "KrPathUtils"

    /** Mount spellings of the emulated primary volume the engine may use interchangeably. */
    private val STORAGE_ROOT_ALIASES = listOf(
        "/storage/emulated/0",
        "/sdcard",
        "/storage/self/primary",
    )

    @JvmStatic
    fun currentActivity(): KR2Activity? {
        return KR2Activity.getInstance() ?: KR2Activity.GetInstance()
    }

    @JvmStatic
    fun normalizeFilePath(path: String?): String? {
        if (path == null) return path
        var p = path.trim()
        if (p.startsWith("file://")) p = p.substring("file://".length)
        while (p.startsWith("./")) p = p.substring(2)
        if (p.startsWith("storage/")) p = "/$p"
        while (p.contains("//")) p = p.replace("//", "/")
        return p
    }

    @JvmStatic
    fun canonicalizeKrStoragePath(path: String?): String? {
        val p = normalizeFilePath(path) ?: return null
        return try {
            val activity = currentActivity()
            if (activity == null || !p.startsWith("/")) return p
            var result: String? = p
            val appExternal = activity.getExternalFilesDir(null)
            if (appExternal != null) result = replacePrefixIgnoreCase(result, appExternal.absolutePath)
            val intent = activity.intent
            if (intent != null) {
                result = replacePrefixIgnoreCase(result, normalizeFilePath(intent.getStringExtra(LaunchContract.PROJECT_ROOT)))
                result = replacePrefixIgnoreCase(result, normalizeFilePath(intent.getStringExtra(LaunchContract.GAME_DIR)))
                result = replacePrefixIgnoreCase(result, normalizeFilePath(intent.getStringExtra(LaunchContract.ROOT_URI)))
                val gamePath = normalizeFilePath(intent.getStringExtra(LaunchContract.GAME_PATH))
                if (gamePath != null && gamePath.isNotEmpty()) {
                    val game = File(gamePath)
                    val root = if (game.isFile) game.parentFile else game
                    if (root != null) result = replacePrefixIgnoreCase(result, root.absolutePath)
                }
            }
            result
        } catch (_: Throwable) {
            p
        }
    }

    @JvmStatic
    fun replacePrefixIgnoreCase(path: String?, prefix: String?): String? {
        if (path == null || prefix == null) return path
        var clean = normalizeFilePath(prefix) ?: return path
        if (clean.length <= 1 || !clean.startsWith("/")) return path
        while (clean.endsWith("/") && clean.length > 1) clean = clean.substring(0, clean.length - 1)
        if (path.length == clean.length && path.regionMatches(0, clean, 0, clean.length, ignoreCase = true)) return clean
        if (path.length > clean.length && path.regionMatches(0, clean, 0, clean.length, ignoreCase = true) && path[clean.length] == '/') {
            return clean + path.substring(clean.length)
        }
        return path
    }

    @JvmStatic
    fun redirectScopedSavePath(path: String?): String? {
        return try {
            val intent = currentActivity()?.intent ?: return null
            if (!intent.getBooleanExtra(LaunchContract.SCOPED_SAVE_DIR, false)) return null
            val out = resolveScopedSavePath(
                path,
                intent.getStringExtra(LaunchContract.PROJECT_ROOT),
                intent.getStringExtra(LaunchContract.GAME_DIR),
                intent.getStringExtra(LaunchContract.SCOPED_SAVE_ROOT),
            ) ?: return null
            val parent = if (out.isDirectory) out else out.parentFile
            if (parent != null && !parent.exists()) parent.mkdirs()
            Log.i(TAG, "redirect KR save $path -> ${out.absolutePath}")
            out.absolutePath
        } catch (t: Throwable) {
            Log.w(TAG, "redirect KR save failed path=$path", t)
            null
        }
    }

    /** Source savedata and the independent destination are distinct anchors. */
    internal fun resolveScopedSavePath(
        path: String?,
        projectRoot: String?,
        gameDirectory: String?,
        scopedSaveRoot: String?,
    ): File? {
        val requested = normalizeFilePath(path)?.replace(File.separatorChar, '/')
            ?.takeIf { it.isNotBlank() && File(it).isAbsolute } ?: return null
        fun absoluteDirectory(value: String?): File? = normalizeFilePath(value)
            ?.takeIf { it.isNotBlank() && File(it).isAbsolute }?.let { File(it) }
        val sourceProject = absoluteDirectory(projectRoot) ?: absoluteDirectory(gameDirectory)
        val source = sourceProject?.let { File(it, "savedata") }
        val destination = absoluteDirectory(scopedSaveRoot) ?: source ?: return null
        return try {
            val destinationCanonical = destination.canonicalFile
            // Already redirected paths are idempotent. Only the game's top-level
            // savedata is a source; resources such as data/savedata stay untouched.
            for (anchor in listOfNotNull(destination, source).distinct()) {
                for (alias in anchorSpellings(anchor).distinct()) {
                    val prefix = alias.path.replace(File.separatorChar, '/').trimEnd('/')
                    val relative = when {
                        requested.equals(prefix, ignoreCase = true) -> ""
                        requested.startsWith(prefix + "/", ignoreCase = true) -> requested.substring(prefix.length + 1)
                        else -> continue
                    }
                    val out = if (relative.isEmpty()) destinationCanonical else File(destinationCanonical, relative).canonicalFile
                    if (out == destinationCanonical || out.path.startsWith(destinationCanonical.path + File.separator)) {
                        return out
                    }
                    return null
                }
            }
            null
        } catch (_: java.io.IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    /**
     * The engine may spell the same storage root through its mount aliases
     * (`/sdcard`, `/storage/self/primary`, `/storage/emulated/0`). Anchor matching
     * must cover them or internal saves silently stop redirecting (review round 9,
     * #14). Only the leading mount segment is swapped; the game-relative remainder
     * and the containment check stay unchanged. Pure string expansion for JVM tests.
     */
    internal fun expandStorageRootAliases(path: String): List<String> {
        val normalized = path.replace(File.separatorChar, '/').trimEnd('/')
        val result = LinkedHashSet<String>()
        result += normalized
        for (alias in STORAGE_ROOT_ALIASES) {
            if (normalized.startsWith("$alias/")) {
                val remainder = normalized.substring(alias.length)
                for (other in STORAGE_ROOT_ALIASES) {
                    if (other != alias) result += other + remainder
                }
            }
        }
        return result.toList()
    }

    private fun anchorSpellings(anchor: File): List<File> {
        val spellings = LinkedHashSet<String>()
        spellings += expandStorageRootAliases(anchor.path)
        spellings += runCatching {
            expandStorageRootAliases(anchor.canonicalFile.path)
        }.getOrDefault(emptyList())
        return spellings.map(::File)
    }
}
