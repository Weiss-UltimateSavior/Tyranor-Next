package com.core.web

import com.core.rpgmaker.RpgMakerNameMatcher
import java.io.File
import java.io.IOException

/** Keep generated DKTools metadata lookups and HTTP resource names on the same logical tree. */
internal object DkToolsResourceResolver {
    data class Resource(val file: File? = null, val archivePath: String? = null)

    fun resolve(
        root: File,
        uri: String,
        archivePrefix: String,
        archiveChildren: (String) -> List<Pair<String, Boolean>>,
        archiveIsFile: (String) -> Boolean,
        archiveDirectChildren: ((String) -> List<Pair<String, Boolean>>)? = null,
        diskChildren: (File) -> List<String> = { it.list()?.toList().orEmpty() },
    ): Resource? {
        val parts = uri.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty() || parts.any { it == ".." || it.any(Char::isISOControl) }) return null
        val normalizedPath = parts.joinToString("/")
        // Preserve the hosts' exact lookup order: web-root prefix, then archive root.
        // Root fallback deliberately does not apply loose/case-insensitive matching.
        val exactArchivePath = (if (archivePrefix.isEmpty()) listOf(normalizedPath)
            else listOf(archivePrefix + normalizedPath, normalizedPath)).firstOrNull(archiveIsFile)
        if (exactArchivePath != null) {
            // Most packaged resource requests are already exact. They must not construct
            // the optional directory tree (which can exceed its separate metadata budget).
            var candidate = root
            for ((index, part) in parts.withIndex()) {
                candidate = try { File(candidate, part).canonicalFile } catch (_: IOException) { return null }
                    catch (_: SecurityException) { return null }
                if (candidate != root && !candidate.path.startsWith(root.path + File.separator)) return null
                if (candidate.exists() && !candidate.isDirectory && index != parts.lastIndex) return null
            }
            if (candidate.exists()) return candidate.takeIf { it.isFile }?.let { Resource(file = it) }
            return Resource(archivePath = exactArchivePath)
        }
        var disk: File? = root
        var archivePath = archivePrefix.trimEnd('/')
        for ((index, part) in parts.withIndex()) {
            val diskNames = disk?.takeIf { it.isDirectory }?.let(diskChildren).orEmpty()
            val names = linkedSetOf<String>()
            names.addAll(diskNames)
            val archiveNames = try { archiveChildren(archivePath) }
                // Directory metadata is optional. Fall back to a direct-child scan when
                // an unrelated archive subtree exhausts the archive-wide metadata node budget.
                catch (_: AsarChildrenIndex.LimitExceeded) {
                    try { archiveDirectChildren?.invoke(archivePath).orEmpty() }
                    catch (_: AsarChildrenIndex.LimitExceeded) { emptyList() }
                }
            archiveNames.forEach { names.add(it.first) }
            val matched = RpgMakerNameMatcher.match(names.toList(), part) ?: return null
            disk = if (disk != null && diskNames.contains(matched)) {
                val canonical = try { File(disk, matched).canonicalFile } catch (_: IOException) { return null }
                    catch (_: SecurityException) { return null }
                if (canonical != root && !canonical.path.startsWith(root.path + File.separator)) return null
                canonical.takeIf { it.exists() }
            } else null
            archivePath = if (archivePath.isEmpty()) matched else "$archivePath/$matched"
            // A disk file shadows the archive directory, just as it does in the file index.
            if (disk != null && !disk.isDirectory && index != parts.lastIndex) return null
        }
        if (disk != null) return disk.takeIf { it.isFile }?.let { Resource(file = it) }
        return if (archiveIsFile(archivePath)) Resource(archivePath = archivePath) else null
    }
}
