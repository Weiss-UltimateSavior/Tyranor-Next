package com.core.web

import java.io.File
import java.io.IOException

/** Shared script identity and buffering policy for the two RPG Maker web hosts. */
internal object DkToolsCompat {
    const val RESOURCE_PATH = "__tyranor_internal/dktools-index.json"
    const val SCRIPT_PATH = "js/plugins/dktools.js"
    const val ASSET_PATH = "rpgmaker/__dktools_compat.js"
    const val MAX_SCRIPT_BYTES = 8L * 1024 * 1024

    // Registered before any game script can assign window.onload or register listeners.
    // A page without DKTools never publishes the promise, so its load event is unchanged.
    const val BOOTSTRAP_SCRIPT = """;(function () {
    function waitForIndex(event) {
        window.removeEventListener("load", waitForIndex, true);
        var ready = window.__tyranorDkToolsReady;
        delete window.__tyranorDkToolsReady;
        if (!ready) return;
        event.stopImmediatePropagation();
        ready.then(function () { window.dispatchEvent(new Event("load")); });
    }
    window.addEventListener("load", waitForIndex, true);
})();"""

    fun injectBootstrap(html: String): String {
        val uncommented = Regex("<!--([\\s\\S]*?)-->|<!--[\\s\\S]*$").replace(html) { " ".repeat(it.value.length) }
        val headEnd = Regex("<head(?:\\s[^>]*)?>", RegexOption.IGNORE_CASE).find(uncommented)?.range?.last?.plus(1)
        val doctypeEnd = Regex("<!doctype\\s[^>]*>", RegexOption.IGNORE_CASE).find(uncommented)?.range?.last?.plus(1)
        val firstScript = Regex("<script(?:\\s|>)", RegexOption.IGNORE_CASE).find(uncommented)?.range?.first
        // For fragments, place the bootstrap before body onload attributes as well.
        val position = minOf(headEnd ?: doctypeEnd ?: 0, firstScript ?: html.length)
        val script = "\n<script type='text/javascript'>\n$BOOTSTRAP_SCRIPT\n</script>\n"
        return html.substring(0, position) + script + html.substring(position)
    }

    /** Fast request-path probe: never constructs optional archive directory metadata. */
    fun hasExactOriginalStamp(root: File, archivePrefix: String, archiveIsFile: (String) -> Boolean): Boolean {
        try {
            val canonicalRoot = root.canonicalFile
            var disk = canonicalRoot
            for ((index, part) in listOf("data", "Stamp.json").withIndex()) {
                disk = File(disk, part).canonicalFile
                if (!disk.path.startsWith(canonicalRoot.path + File.separator)) return false
                if (disk.exists()) {
                    if (index == 0 && !disk.isDirectory) return false
                    if (index == 1) return disk.isFile
                }
            }
        } catch (_: IOException) { return false }
          catch (_: SecurityException) { return false }
        return archiveIsFile(archivePrefix + "data/Stamp.json") ||
            (archivePrefix.isNotEmpty() && archiveIsFile("data/Stamp.json"))
    }
}
