package com.tyranor.next.core.game.save

import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/** Validates save paths without letting canonicalization hide symbolic-link ancestors. */
internal object SavePathSafety {
    /** Returns a canonical root; missing tails are allowed, existing non-directories are not. */
    fun requireSafeRoot(root: File): File {
        val absolute = absolutePath(root)
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
