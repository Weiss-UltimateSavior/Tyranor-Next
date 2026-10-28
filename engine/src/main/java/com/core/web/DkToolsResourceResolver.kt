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
    ): Resource? {
        val parts = uri.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty() || parts.any { it == ".." || it.any(Char::isISOControl) }) return null
        val exactArchivePath = archivePrefix + parts.joinToString("/")
        if (archiveIsFile(exactArchivePath)) {
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
            val diskChildren = disk?.takeIf { it.isDirectory }?.listFiles().orEmpty()
            val names = linkedSetOf<String>()
            diskChildren.forEach { names.add(it.name) }
            val archiveNames = try { archiveChildren(archivePath) }
                catch (_: AsarChildrenIndex.LimitExceeded) { return null }
            archiveNames.forEach { names.add(it.first) }
            val matched = RpgMakerNameMatcher.match(names.toList(), part) ?: return null
            disk = diskChildren.firstOrNull { it.name == matched }?.let { candidate ->
                val canonical = try { candidate.canonicalFile } catch (_: IOException) { return null }
                    catch (_: SecurityException) { return null }
                if (canonical != root && !canonical.path.startsWith(root.path + File.separator)) return null
                canonical
            }
            archivePath = if (archivePath.isEmpty()) matched else "$archivePath/$matched"
            // A disk file shadows the archive directory, just as it does in the file index.
            if (disk != null && !disk.isDirectory && index != parts.lastIndex) return null
        }
        if (disk != null) return disk.takeIf { it.isFile }?.let { Resource(file = it) }
        return if (archiveIsFile(archivePath)) Resource(archivePath = archivePath) else null
    }
}
