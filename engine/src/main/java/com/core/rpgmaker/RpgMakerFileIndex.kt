package com.core.rpgmaker

import android.util.Log
import com.core.web.AsarChildrenIndex
import com.core.web.DkToolsCompat
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.file.DirectoryIteratorException
import java.nio.file.DirectoryStream
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
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
    private val directoryReader: (File) -> DirectoryStream<Path> = { Files.newDirectoryStream(it.toPath()) },
    private val isCancelled: () -> Boolean = { Thread.currentThread().isInterrupted },
    private val nanoTime: () -> Long = System::nanoTime,
    private val maxDurationNanos: Long = 5_000_000_000L,
) {
    private val root = root.canonicalFile
    private fun archiveHas(path: String) = asar?.has(path) ?: (legacyArchive?.has(path) == true)
    private fun archiveIsDirectory(path: String) = asar?.isDirectory(path) ?: (legacyArchive?.isDirectory(path) == true)
    private fun archiveSize(path: String) = asar?.entrySize(path) ?: legacyArchive?.fileSize(path)
    private fun archiveChildren(path: String) = asar?.children(path) ?: legacyArchive?.children(path).orEmpty()

    class LimitExceeded(val limit: String) : IOException("Plugin file index exceeds $limit limit")

    fun build(onlyRoots: Set<String>? = null): ByteArray {
        val started = nanoTime()
        fun checkActive() {
            if (isCancelled()) throw InterruptedIOException("Plugin file index cancelled")
            if (nanoTime() - started > maxDurationNanos) throw LimitExceeded("time")
        }
        var entries = 0
        var directories = 0
        var estimatedBytes = 2L
        fun visit(relative: String, depth: Int): JSONObject? {
            checkActive()
            if (depth > maxDepth) throw LimitExceeded("depth")
            val logical = File(root, relative)
            val canonical = try { logical.canonicalFile } catch (error: Exception) {
                if (relative.isEmpty() || (error !is IOException && error !is SecurityException)) throw error
                return null
            }
            // Do not follow aliases, including directory loops or links outside the game.
            if (canonical != logical.absoluteFile ||
                (canonical != root && !canonical.path.startsWith(root.path + File.separator))) return null
            val disk = canonical.takeIf { it.exists() }
            val archiveKey = asarPrefix + relative
            val archiveChildren = try { archiveChildren(archiveKey) }
                catch (_: AsarChildrenIndex.LimitExceeded) { throw LimitExceeded("archive node") }
            val archiveDirectory = archiveIsDirectory(archiveKey) || archiveChildren.isNotEmpty()
            val archiveExists = archiveHas(archiveKey) || archiveDirectory
            if (relative.isNotEmpty() && disk == null && !archiveExists) return null
            val directory = disk?.isDirectory ?: (relative.isEmpty() || archiveDirectory)
            val size = if (directory) 0L else disk?.length() ?: archiveSize(archiveKey) ?: return null
            if (disk != null && !disk.isFile && !disk.isDirectory) return null
            if (++entries > maxEntries) throw LimitExceeded("entry")
            estimatedBytes += relative.substringAfterLast('/').length.toLong() * 6 + 160
            if (estimatedBytes > maxBytes) throw LimitExceeded("size")
            val node = JSONObject()
            if (relative.isNotEmpty()) node.put("__stats__", JSONObject()
                .put("type", if (directory) "directory" else "file")
                .put("size", size)
                .put("mtimeMs", disk?.lastModified() ?: 0L))
            if (directory) {
                if (++directories > 2_048) throw LimitExceeded("directory")
                val names = linkedSetOf<String>()
                fun addName(name: String) {
                    checkActive()
                    if (name.isEmpty() || name in RESERVED_NAMES || name.any { it == '/' || it == '\\' || it.isISOControl() }) return
                    val lowerName = name.lowercase(Locale.ROOT)
                    if (lowerName in EXCLUDED_NAMES || (relative.isEmpty() && lowerName in PRIVATE_ROOTS)) return
                    if (relative.isEmpty() && onlyRoots != null && lowerName !in onlyRoots) return
                    val container = asar?.getArchiveFile() ?: legacyArchive?.getArchiveFile()
                    val child = File(logical, name).absoluteFile
                    if (container != null && (child == container || child == File(container.path + ".unpacked"))) return
                    names.add(name)
                    if (names.size > maxEntries - entries) throw LimitExceeded("entry")
                }
                if (disk?.isDirectory == true) {
                    try {
                        directoryReader(disk).use { children ->
                            children.forEach { addName(it.fileName.toString()) }
                        }
                    } catch (error: Exception) {
                        val cause = if (error is DirectoryIteratorException) error.cause else error
                        // Do not swallow quota/size/depth errors raised by addName().
                        if ((relative.isEmpty() && asar == null && legacyArchive == null) ||
                            (cause !is FileSystemException && cause !is SecurityException)) throw error
                        Log.w(TAG, "index skipped unreadable directory: $relative (${cause.javaClass.simpleName})")
                    }
                }
                if (relative.isEmpty() || archiveDirectory) {
                    archiveChildren.forEach { addName(it.first) }
                }
                for (name in names.sortedWith(compareBy<String> { if (it.equals("locales", true)) 0 else if (it.equals("data", true)) 1 else 2 }.thenBy { it })) {
                    val childPath = if (relative.isEmpty()) name else "$relative/$name"
                    visit(childPath, depth + 1)?.let { node.put(name, it) }
                }
            }
            return node
        }
        val result = requireNotNull(visit("", 0)).toString().toByteArray(Charsets.UTF_8)
        checkActive()
        if (result.size > maxBytes) throw LimitExceeded("encoded size")
        return result
    }

    companion object {
        private const val TAG = "YukiRpgMaker"
        const val RESOURCE_PATH = DkToolsCompat.RESOURCE_PATH
        private val RESERVED_NAMES = setOf(".", "..", "__stats__", "__proto__", "constructor", "prototype")
        private val EXCLUDED_NAMES = setOf("node_modules", ".git")
        private val PRIVATE_ROOTS = setOf("save", "saves", "savedata", "backup", "backups", "config", "original", "deleted")
    }
}
