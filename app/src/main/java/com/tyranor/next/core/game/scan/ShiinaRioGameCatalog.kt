package com.tyranor.next.core.game.scan

import android.content.Context
import android.content.pm.PackageManager
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException

/** The installed OpenShiina host's named game mappings. WARC detection also works without them. */
internal class ShiinaRioGameCatalog private constructor(private val gameMap: Map<String, String>) {
    fun launchTargetFor(fileNames: Iterable<String>): String? {
        val matches = fileNames.mapNotNull { name ->
            gameMap[name.lowercase(Locale.ROOT)]?.let { scheme -> name to scheme }
        }
        if (matches.map { it.second }.distinct().singleOrNull() == null) return null
        // Prefer a mapped executable for the host's translation overlay lookup.
        return matches.map { it.first }.minWithOrNull(
            compareBy<String> { !it.endsWith(".exe", ignoreCase = true) }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it }
                .thenBy { it },
        )
    }

    companion object {
        const val HOST_PACKAGE = "com.tyranor.next.shiinario"
        private const val ASSET_PATH = "tyn/games.json"
        private const val MAX_CATALOG_CHARS = 256 * 1024
        private val WARC_MAGIC = "WARC 1.".toByteArray(Charsets.US_ASCII)
        private val GENERIC_SCHEMES = setOf("ShiinaRio v2.34 and older", "ShiinaRio v2.36-2.38")
        val EMPTY = ShiinaRioGameCatalog(emptyMap())

        fun hasWarcHeader(head: ByteArray?): Boolean =
            head != null && head.size >= 16 &&
                WARC_MAGIC.indices.all { head[it] == WARC_MAGIC[it] } &&
                head[7] in '0'.code.toByte()..'7'.code.toByte()

        fun isLaunchTarget(name: String): Boolean =
            name.length in 1..256 && name == name.trim() && !name.contains("..") &&
                name.none { it == '/' || it == '\\' || it.isISOControl() } &&
                (name.endsWith(".exe", ignoreCase = true) || name.endsWith(".war", ignoreCase = true))

        fun load(context: Context): ShiinaRioGameCatalog = try {
            val resources = context.packageManager.getResourcesForApplication(HOST_PACKAGE)
            resources.assets.open(ASSET_PATH).bufferedReader(Charsets.UTF_8).use { reader ->
                val chars = CharArray(MAX_CATALOG_CHARS + 1)
                var count = 0
                while (count < chars.size) {
                    if (Thread.currentThread().isInterrupted) throw CancellationException()
                    val read = reader.read(chars, count, chars.size - count)
                    if (read < 0) break
                    count += read
                }
                if (count > MAX_CATALOG_CHARS) EMPTY else parse(String(chars, 0, count))
            }
        } catch (_: PackageManager.NameNotFoundException) {
            EMPTY
        } catch (_: IOException) {
            EMPTY
        } catch (_: SecurityException) {
            EMPTY
        }

        internal fun parse(text: String): ShiinaRioGameCatalog = try {
            val root = JSONObject(text)
            if (root.optInt("schemaVersion") != 1 || root.optString("engine") != "openshiina") {
                EMPTY
            } else {
                val generic = root.optJSONArray("genericSchemes")
                val excluded = GENERIC_SCHEMES + (0 until (generic?.length() ?: 0)).mapNotNull {
                    generic?.opt(it) as? String
                }
                val schemes = root.getJSONArray("schemes")
                val names = (0 until schemes.length()).mapNotNull { index ->
                    (schemes.optJSONObject(index)?.opt("name") as? String)?.takeIf { name ->
                        name.isNotBlank() && excluded.none { it.equals(name, ignoreCase = true) }
                    }
                }.toSet()
                val mappings = root.getJSONObject("gameMap")
                val gameMap = mappings.keys().asSequence().mapNotNull { name ->
                    val scheme = mappings.opt(name) as? String
                    if (scheme != null && scheme in names && isLaunchTarget(name)) {
                        name.lowercase(Locale.ROOT) to scheme
                    } else {
                        null
                    }
                }.toMap()
                ShiinaRioGameCatalog(gameMap)
            }
        } catch (_: JSONException) {
            EMPTY
        } catch (_: StackOverflowError) {
            EMPTY
        }
    }
}
