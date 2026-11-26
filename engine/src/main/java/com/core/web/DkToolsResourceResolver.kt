package com.core.web

import com.core.rpgmaker.RpgMakerNameMatcher
import java.io.File
import java.io.IOException

/** Keep generated DKTools metadata lookups and HTTP resource names on the same logical tree. */
internal object DkToolsResourceResolver {
    data class Resource(val file: File? = null, val archivePath: String? = null)

    fun resolveExactArchivePath(
        parts: List<String>,
        archivePrefix: String,
        archiveIsFile: (String) -> Boolean,
        archiveIsDirectory: (String) -> Boolean = { false },
    ): String? {
        val normalizedPath = parts.joinToString("/")
        if (archivePrefix.isEmpty()) return normalizedPath.takeIf(archiveIsFile)
        val prefixed = archivePrefix + normalizedPath
        return when {
            archiveIsFile(prefixed) -> prefixed
            archiveIsDirectory(prefixed) -> null
            (1 until parts.size).any { len ->
                archiveIsFile(archivePrefix + parts.take(len).joinToString("/"))
            } -> null
            else -> normalizedPath.takeIf(archiveIsFile)
        }
    }

    fun resolve(
        root: File,
        uri: String,
        archivePrefix: String,
        archiveChildren: (String) -> List<Pair<String, Boolean>>,
        archiveIsFile: (String) -> Boolean,
        archiveDirectChildren: ((String) -> List<Pair<String, Boolean>>)? = null,
        diskChildren: (File) -> List<String> = { it.list()?.toList().orEmpty() },
        archiveIsDirectory: (String) -> Boolean = { false },
    ): Resource? {
        val canonicalRoot = try { root.canonicalFile }
            catch (_: IOException) { return null }
            catch (_: SecurityException) { return null }
        val parts = uri.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty() || parts.any { it == ".." || it.any(Char::isISOControl) }) return null
        // Preserve the hosts' exact lookup order: web-root prefix, then archive root.
        // Root fallback deliberately does not apply loose/case-insensitive matching,
        // and a prefixed directory or ancestor file shadows any root-level fallback.
        val exactArchivePath = resolveExactArchivePath(parts, archivePrefix, archiveIsFile, archiveIsDirectory)
        if (exactArchivePath != null) {
            // Most packaged resource requests are already exact. They must not construct
            // the optional directory tree (which can exceed its separate metadata budget).
            var candidate = canonicalRoot
            for ((index, part) in parts.withIndex()) {
                candidate = try { File(candidate, part).canonicalFile } catch (_: IOException) { return null }
                    catch (_: SecurityException) { return null }
                if (candidate != canonicalRoot && !candidate.path.startsWith(canonicalRoot.path + File.separator)) return null
                if (candidate.exists() && !candidate.isDirectory && index != parts.lastIndex) return null
            }
            if (candidate.exists()) return candidate.takeIf { it.isFile }?.let { Resource(file = it) }
            return Resource(archivePath = exactArchivePath)
        }
        var disk: File? = canonicalRoot
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
            disk = if (disk != null) {
                val canonical = try { File(disk, matched).canonicalFile } catch (_: IOException) { return null }
                    catch (_: SecurityException) { return null }
                if (canonical != canonicalRoot && !canonical.path.startsWith(canonicalRoot.path + File.separator)) return null
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
