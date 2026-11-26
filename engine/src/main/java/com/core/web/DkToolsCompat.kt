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

    private val HTML_COMMENT_REGEX = Regex("<!--([\\s\\S]*?)-->|<!--[\\s\\S]*$")
    private val HEAD_OPEN_REGEX = Regex("<head(?:\\s[^>]*)?>", RegexOption.IGNORE_CASE)
    private val DOCTYPE_REGEX = Regex("<!doctype\\s[^>]*>", RegexOption.IGNORE_CASE)
    private val SCRIPT_OPEN_REGEX = Regex("<script(?:\\s|>)", RegexOption.IGNORE_CASE)
    private val STAMP_PARTS = listOf("data", "Stamp.json")

    fun injectBootstrap(html: String): String {
        val uncommented = HTML_COMMENT_REGEX.replace(html) { " ".repeat(it.value.length) }
        val headEnd = HEAD_OPEN_REGEX.find(uncommented)?.range?.last?.plus(1)
        val doctypeEnd = DOCTYPE_REGEX.find(uncommented)?.range?.last?.plus(1)
        val firstScript = SCRIPT_OPEN_REGEX.find(uncommented)?.range?.first
        // For fragments, place the bootstrap before body onload attributes as well.
        val position = minOf(headEnd ?: doctypeEnd ?: 0, firstScript ?: html.length)
        val script = "\n<script type='text/javascript'>\n$BOOTSTRAP_SCRIPT\n</script>\n"
        return html.substring(0, position) + script + html.substring(position)
    }

    /** Fast request-path probe: never constructs optional archive directory metadata. */
    fun hasExactOriginalStamp(
        root: File,
        archivePrefix: String,
        archiveIsDirectory: (String) -> Boolean = { false },
        archiveIsFile: (String) -> Boolean,
    ): Boolean {
        try {
            val canonicalRoot = root.canonicalFile
            var disk = canonicalRoot
            for ((index, part) in STAMP_PARTS.withIndex()) {
                disk = File(disk, part).canonicalFile
                if (!disk.path.startsWith(canonicalRoot.path + File.separator)) return false
                if (disk.exists()) {
                    if (index == 0 && !disk.isDirectory) return false
                    if (index == 1) return disk.isFile
                }
            }
        } catch (_: IOException) { return false }
          catch (_: SecurityException) { return false }
        return DkToolsResourceResolver.resolveExactArchivePath(
            STAMP_PARTS, archivePrefix, archiveIsFile, archiveIsDirectory,
        ) != null
    }
}
