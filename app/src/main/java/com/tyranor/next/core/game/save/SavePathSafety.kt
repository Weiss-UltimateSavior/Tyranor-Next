package com.tyranor.next.core.game.save

import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/** Validates save paths without letting canonicalization hide symbolic-link ancestors. */
internal object SavePathSafety {
    /**
     * Returns a canonical root; missing tails are allowed, existing non-directories are not.
     * [trustedRoot] may resolve aliases in its own prefix, while every segment below it is
     * checked in the caller-supplied order so `link/..` cannot become harmless through normalize.
     */
    fun requireSafeRoot(root: File, trustedRoot: File? = null): File {
        val absolute = absolutePath(root)
        if (trustedRoot != null) return requireTrustedRoot(absolute, absolutePath(trustedRoot))

        // Keep link/.. visible until every original component has been inspected.
        rejectSymbolicLinks(absolute)
        val normalized = absolute.normalize()
        if (normalized != absolute) rejectSymbolicLinks(normalized)
        val canonical = canonicalFile(normalized)
        validateDirectoryOrMissing(canonical.toPath())
        return canonical
    }

    /** Validates [file] against a root returned by [requireSafeRoot] and returns its canonical path. */
    fun requireSafeFile(root: File, file: File): File {
        val rootAbsolute = absolutePath(root)
        val fileAbsolute = absolutePath(file)
        // The trusted root lets the file scan skip ancestors without losing link/.. detection.
        rejectSymbolicLinks(fileAbsolute, trustedRoot = rootAbsolute)

        val rootPath = rootAbsolute.normalize()
        val filePath = fileAbsolute.normalize()
        if (!filePath.startsWith(rootPath) || filePath == rootPath) unavailable()
        if (filePath != fileAbsolute) rejectSymbolicLinks(filePath, trustedRoot = rootPath)

        val canonical = canonicalFile(filePath)
        val canonicalPath = canonical.toPath().normalize()
        if (!canonicalPath.startsWith(rootPath) || canonicalPath == rootPath || !canonical.isFile) {
            unavailable()
        }
        return canonical
    }

    /**
     * Deletes [root] and its descendants without following symbolic links. A symbolic link is
     * deleted as an entry, leaving its target untouched.
     */
    fun deleteTreeNoFollow(root: File) {
        val path = absolutePath(root)
        if (attributesOrNull(path) == null) return
        try {
            Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.deleteIfExists(file)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, failure: IOException): FileVisitResult {
                    if (failure is NoSuchFileException) return FileVisitResult.CONTINUE
                    throw failure
                }

                override fun postVisitDirectory(directory: Path, failure: IOException?): FileVisitResult {
                    if (failure != null) throw failure
                    Files.deleteIfExists(directory)
                    return FileVisitResult.CONTINUE
                }
            })
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: IOException) {
            unavailable(failure)
        } catch (failure: SecurityException) {
            unavailable(failure)
        }
    }

    /** Import sidecars must be actual directories; their existing parent retains its own policy. */
    fun requirePlainDirectoryEntry(directory: File) {
        val attributes = attributesOrNull(absolutePath(directory))
        if (attributes != null && !attributes.isDirectory) unavailable()
    }

    private fun requireTrustedRoot(absolute: Path, trustedRoot: Path): File {
        // The caller's original path must establish containment. Canonicalization starts only
        // at the trusted boundary so it cannot erase a link or parent traversal below that root.
        if (!absolute.startsWith(trustedRoot)) unavailable()

        val canonicalTrusted = canonicalFile(trustedRoot)
        val canonicalTrustedPath = canonicalTrusted.toPath()
        validateDirectoryOrMissing(canonicalTrustedPath)

        // Path.relativize may normalize link/.. before the link can be inspected.
        val candidate = if (absolute == trustedRoot) {
            canonicalTrustedPath
        } else {
            canonicalTrustedPath.resolve(absolute.subpath(trustedRoot.nameCount, absolute.nameCount))
        }
        rejectSymbolicLinks(candidate, trustedRoot = canonicalTrustedPath)
        val normalized = candidate.normalize()
        if (!normalized.startsWith(canonicalTrustedPath)) unavailable()
        if (normalized != candidate) {
            rejectSymbolicLinks(normalized, trustedRoot = canonicalTrustedPath)
        }

        val canonical = canonicalFile(normalized)
        val canonicalPath = canonical.toPath()
        if (!canonicalPath.startsWith(canonicalTrustedPath)) unavailable()
        validateDirectoryOrMissing(canonicalPath)
        return canonical
    }

    private fun absolutePath(file: File): Path {
        return try {
            file.absoluteFile.toPath()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: InvalidPathException) {
            unavailable(failure)
        } catch (failure: SecurityException) {
            unavailable(failure)
        }
    }

    private fun rejectSymbolicLinks(path: Path, trustedRoot: Path? = null) {
        val root = path.root ?: unavailable()
        val trusted = trustedRoot?.takeIf { path.startsWith(it) }
        var current = trusted ?: root
        val startIndex = trusted?.nameCount ?: 0
        try {
            if (trusted == null && Files.isSymbolicLink(root)) unavailable()
            for (index in startIndex until path.nameCount) {
                current = current.resolve(path.getName(index))
                if (Files.isSymbolicLink(current)) unavailable()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: SecurityException) {
            unavailable(failure)
        }
    }

    private fun validateDirectoryOrMissing(path: Path) {
        val root = path.root ?: unavailable()
        var current = root
        if (attributesOrNull(current)?.isDirectory != true) unavailable()
        for (index in 0 until path.nameCount) {
            current = current.resolve(path.getName(index))
            val attributes = attributesOrNull(current) ?: return
            if (!attributes.isDirectory) unavailable()
        }
    }

    private fun attributesOrNull(path: Path): BasicFileAttributes? {
        return try {
            Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: NoSuchFileException) {
            null
        } catch (failure: IOException) {
            unavailable(failure)
        } catch (failure: SecurityException) {
            unavailable(failure)
        }
    }

    private fun canonicalFile(path: Path): File {
        return try {
            path.toFile().canonicalFile
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: IOException) {
            unavailable(failure)
        } catch (failure: SecurityException) {
            unavailable(failure)
        }
    }

    private fun unavailable(cause: Throwable? = null): Nothing =
        throw GameSaveException(SaveErrorCode.SAVE_DIR_UNAVAILABLE, cause = cause)
}
