package com.core.rpgmaker

import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.json.JSONObject

/** Read-only, session-scoped directory metadata for browser plugins such as DKTools. */
internal class RpgMakerFileIndex(
    root: File,
    private val asar: AsarArchive? = null,
    private val asarPrefix: String = "",
    private val maxEntries: Int = 50_000,
    private val maxDepth: Int = 64,
    private val maxBytes: Int = 8 * 1024 * 1024,
    private val legacyArchive: com.core.tyrano.AsarArchive? = null,
) {
    private val root = root.canonicalFile
    private fun archiveHas(path: String) = asar?.has(path) ?: (legacyArchive?.has(path) == true)
    private fun archiveIsDirectory(path: String) = asar?.isDirectory(path) ?: (legacyArchive?.isDirectory(path) == true)
    private fun archiveSize(path: String) = asar?.entrySize(path) ?: legacyArchive?.fileSize(path)
    private fun archiveChildren(path: String) = asar?.children(path) ?: legacyArchive?.children(path).orEmpty()

    fun build(): ByteArray {
        var entries = 0
        var directories = 0
        var estimatedBytes = 2L
        fun visit(relative: String, depth: Int): JSONObject? {
            if (depth > maxDepth) throw IOException("Plugin file index exceeds depth limit")
            val logical = File(root, relative)
            val canonical = logical.canonicalFile
            // Do not follow aliases, including directory loops or links outside the game.
            if (canonical != logical.absoluteFile ||
                (canonical != root && !canonical.path.startsWith(root.path + File.separator))) return null
            val disk = canonical.takeIf { it.exists() }
            val archiveKey = asarPrefix + relative
            val archiveExists = archiveHas(archiveKey)
            if (relative.isNotEmpty() && disk == null && !archiveExists) return null
            val directory = disk?.isDirectory ?: (relative.isEmpty() || archiveIsDirectory(archiveKey))
            if (disk != null && !disk.isFile && !disk.isDirectory) return null
            if (++entries > maxEntries) throw IOException("Plugin file index exceeds entry limit")
            estimatedBytes += relative.substringAfterLast('/').length.toLong() * 6 + 160
            if (estimatedBytes > maxBytes) throw IOException("Plugin file index exceeds size limit")
            val node = JSONObject()
            if (relative.isNotEmpty()) node.put("__stats__", JSONObject()
                .put("type", if (directory) "directory" else "file")
                .put("size", disk?.length() ?: archiveSize(archiveKey) ?: 0L)
                .put("mtimeMs", disk?.lastModified() ?: 0L))
            if (directory) {
                if (++directories > 2_048) throw IOException("Plugin file index exceeds directory limit")
                val names = linkedSetOf<String>()
                fun addName(name: String) {
                    names.add(name)
                    if (names.size > maxEntries - entries) throw IOException("Plugin file index exceeds directory entry limit")
                }
                if (disk?.isDirectory == true) {
                    Files.newDirectoryStream(disk.toPath()).use { children ->
                        children.forEach { addName(it.fileName.toString()) }
                    }
                }
                if (relative.isEmpty() || archiveIsDirectory(archiveKey)) {
                    archiveChildren(archiveKey).forEach { addName(it.first) }
                }
                for (name in names.sorted()) {
                    if (name.isEmpty() || name in RESERVED_NAMES || name.any { it == '/' || it == '\\' || it.isISOControl() }) continue
                    if (name == "node_modules" || name == ".git") continue
                    val childPath = if (relative.isEmpty()) name else "$relative/$name"
                    visit(childPath, depth + 1)?.let { node.put(name, it) }
                }
            }
            return node
        }
        val result = requireNotNull(visit("", 0)).toString().toByteArray(Charsets.UTF_8)
        if (result.size > maxBytes) throw IOException("Plugin file index exceeds encoded size limit")
        return result
    }

    companion object {
        const val RESOURCE_PATH = "__tyranor_internal/dktools-index.json"
        private val RESERVED_NAMES = setOf(".", "..", "__stats__", "__proto__", "constructor", "prototype")
    }
}
