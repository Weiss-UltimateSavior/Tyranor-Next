package bridge

import android.content.Intent
import android.net.Uri
import android.util.Log
import com.core.engine.LaunchContract
import org.tvp.kirikiri2.KR2Activity
import java.io.File
import java.util.Locale

object KrPathUtils {
    private const val TAG = "KrPathUtils"

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
            val activity = currentActivity()
            if (activity == null || activity.intent == null) return null
            if (!activity.intent.getBooleanExtra(LaunchContract.SCOPED_SAVE_DIR, false)) return null
            if (path == null || path.trim().isEmpty()) return null
            val p = normalizeFilePath(path)!!
            val lower = p.lowercase(Locale.ROOT)
            var idx = lower.indexOf("/savedata/")
            val len = if (idx < 0) {
                if (lower.endsWith("/savedata")) {
                    idx = lower.length - "/savedata".length
                    "/savedata".length
                } else {
                    return null
                }
            } else "/savedata/".length
            val rel = if (p.length > idx + len) p.substring(idx + len) else ""
            var root = normalizeFilePath(activity.intent.getStringExtra(LaunchContract.SCOPED_SAVE_ROOT))
            if (root != null && root.trim().isNotEmpty() && root.startsWith("/")) {
                val dir = File(root)
                val out = if (rel.isEmpty()) dir else File(dir, rel)
                val parent = if (out.isDirectory) out else out.parentFile
                if (parent != null && !parent.exists()) parent.mkdirs()
                Log.i(TAG, "redirect KR save $p -> ${out.absolutePath}")
                return out.absolutePath
            }
            root = normalizeFilePath(activity.intent.getStringExtra(LaunchContract.PROJECT_ROOT))
            if (root == null || root.trim().isEmpty()) root = normalizeFilePath(activity.intent.getStringExtra(LaunchContract.GAME_DIR))
            if (root == null || root.trim().isEmpty() || !root.startsWith("/")) return null
            val dir = File(root, "savedata")
            val out = if (rel.isEmpty()) dir else File(dir, rel)
            val parent = if (out.isDirectory) out else out.parentFile
            if (parent != null && !parent.exists()) parent.mkdirs()
            Log.i(TAG, "redirect KR save $p -> ${out.absolutePath}")
            out.absolutePath
        } catch (t: Throwable) {
            Log.w(TAG, "redirect KR save failed path=$path", t)
            null
        }
    }
}
