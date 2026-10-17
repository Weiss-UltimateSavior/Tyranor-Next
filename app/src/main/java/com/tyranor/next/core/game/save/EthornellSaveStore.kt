package com.tyranor.next.core.game.save

import com.tyranor.next.core.game.model.EthornellPaths
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.EOFException
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.READ
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Android-native user-data backup, not a PC-format converter. Call only while
 * holding EthornellOperationGate and after proving the whole host is stopped.
 * No resource path is accepted. Private app storage plus the stopped host are
 * the no-concurrent-mutator assumption; this is not an adversarial same-UID sandbox.
 */
internal class EthornellSaveStore(
    private val filesDir: File,
    private val identity: String,
    private val maxFiles: Int = 20_000,
    private val maxBytes: Long = 1024L * 1024L * 1024L,
    private val checkCancelled: () -> Unit = {},
    private val rename: (File, File) -> Boolean = { from, to -> from.renameTo(to) },
    private val maxNodes: Int = MAX_VISITED,
) {
    val root: File = EthornellPaths.userDataDir(filesDir, identity)
    private val staging = File(root.parentFile, ".ethornell-${root.name}-import-staging")
    private val backup = File(root.parentFile, ".ethornell-${root.name}-import-backup")

    /** Exact identity check is repeated before any read, write, recovery or cleanup. */
    internal fun validateRoot(supplied: File = root) {
        try {
            EthornellPaths.validateUserDataDir(filesDir, identity, supplied.absolutePath)
            for (path in listOf(root, staging, backup)) {
                if (path.canonicalFile != path || Files.isSymbolicLink(path.toPath())) unavailable()
                if (exists(path) && !path.isDirectory) unavailable()
                validateTree(path)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (typed: GameSaveException) {
            throw typed
        } catch (error: IOException) {
            throw GameSaveException(SaveErrorCode.SAVE_DIR_UNAVAILABLE, cause = error)
        }
    }

    fun listFiles(): List<File> {
        prepare()
        return userFiles(root)
    }

    /** Do not even open/truncate the provider destination for an empty export. */
    fun export(openOutput: () -> OutputStream): Int {
        val files = listFiles()
        if (files.isEmpty()) throw GameSaveException(SaveErrorCode.NO_EXPORTABLE_FILES)
        ZipOutputStream(openOutput()).use { zip ->
            files.forEach { file ->
                checkCancelled()
                val relative = root.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/')
                val name = safeName(relative)
                zip.putNextEntry(ZipEntry(name).apply { time = file.lastModified() })
                FileInputStream(file).use { input ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        checkCancelled()
                        val read = input.read(buffer)
                        if (read == -1) break
                        zip.write(buffer, 0, read)
                    }
                }
                zip.closeEntry()
            }
        }
        return files.size
    }

    fun import(openInput: () -> InputStream): Int {
        prepare()
        if (!root.parentFile!!.isDirectory && !root.parentFile!!.mkdirs()) unavailable()
        removeTree(staging)
        if (!staging.mkdir()) unavailable()
        var committed = false
        try {
            val count = try {
                extract(openInput())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: ZipException) {
                // Android may reject a hostile entry in nextEntry() before our
                // safeName validator runs. Do not bypass platform validation or
                // inspect its version-dependent/localized message to classify it.
                throw GameSaveException(SaveErrorCode.READ_IMPORT_ZIP, cause = error)
            } catch (error: EOFException) {
                throw GameSaveException(SaveErrorCode.READ_IMPORT_ZIP, cause = error)
            } catch (error: IllegalArgumentException) {
                // Malformed ZIP-name encoding may be reported this way by the
                // platform parser, rather than as ZipException.
                throw GameSaveException(SaveErrorCode.READ_IMPORT_ZIP, cause = error)
            }
            if (count == 0) throw GameSaveException(SaveErrorCode.NO_FILES_IN_ZIP)
            validateRoot()
            validateReservedDestinations(root, staging)
            syncTreeDirectories(staging)
            checkCancelled()
            // No old data has changed before this point. The backup survives a
            // crash between the two renames and is recovered by prepare().
            if (exists(root) && !rename(root, backup)) unavailable()
            if (!rename(staging, root)) {
                if (exists(backup) && !rename(backup, root)) unavailable()
                unavailable()
            }
            committed = true
            // A sync failure here is post-commit durability uncertainty, not a
            // promise that old saves survived. Backup is retained for recovery.
            syncDirectory(root.parentFile!!)
            restoreReserved(backup, root)
            syncTreeDirectories(root)
            removeTree(backup)
            syncDirectory(root.parentFile!!)
            return count
        } finally {
            // On a failed commit, never discard the only copy of the old tree.
            // On a post-commit reserved-file failure, retain backup for recovery.
            if (!committed && exists(backup) && !exists(root)) rename(backup, root)
            try { removeTree(staging) } catch (_: IOException) { /* Safe residual for next recovery. */ }
        }
    }

    /** Also used for app-data cleanup: root and every reserved inode remain. */
    fun delete(): Int {
        prepare()
        val files = userFiles(root)
        files.forEach { checkCancelled(); if (!it.delete()) unavailable() }
        pruneEmptyUserDirectories(root)
        return files.size
    }

    private fun prepare() {
        checkCancelled()
        validateRoot()
        if (exists(backup)) {
            if (!exists(root)) {
                if (!rename(backup, root)) unavailable()
            } else {
                restoreReserved(backup, root)
                removeTree(backup)
            }
        }
        // Only our exact sibling staging path is eligible for disposal.
        removeTree(staging)
    }

    /** Launch must recover an interrupted import BEFORE mkdir/create can mask a missing root. */
    fun recoverForLaunch() = prepare()

    private fun extract(input: InputStream): Int {
        ZipInputStream(input).use { zip ->
            val entries = mutableSetOf<String>()
            val nodes = EthornellExtractionNodes(maxNodes)
            // Own the provider stream before traversal can fail or cancel.
            // Restored internal nodes also occupy the final tree's traversal
            // budget; reserve them before creating any extracted paths.
            reserveInternalNodes(root, "", false, nodes)
            var count = 0
            var bytes = 0L
            val buffer = ByteArray(16 * 1024)
            while (true) {
                checkCancelled()
                val entry = zip.nextEntry ?: break
                val name = safeName(entry.name.removeSuffix(if (entry.isDirectory) "/" else ""))
                if (!entries.add(name)) throw GameSaveException(SaveErrorCode.DUPLICATE_ZIP_ENTRY, name)
                if (entries.size > maxFiles) throw GameSaveException(SaveErrorCode.TOO_MANY_ZIP_FILES)
                val output = File(staging, name)
                if (!output.canonicalFile.toPath().startsWith(staging.toPath())) illegal(name)
                if (entry.isDirectory) {
                    // Directory payloads are invalid. Read at most one byte;
                    // never let closeEntry()/nextEntry() implicitly drain an
                    // arbitrarily large compressed payload outside the budget.
                    checkCancelled()
                    if (zip.read() != -1) illegal(name)
                    checkCancelled()
                    nodes.reserve(name)
                    if (!output.isDirectory && !output.mkdirs()) unavailable()
                } else {
                    nodes.reserve(name) // Count every implicit parent BEFORE creating any of them.
                    if (!output.parentFile!!.isDirectory && !output.parentFile!!.mkdirs()) unavailable()
                    if (exists(output)) illegal(name) // Directory/file alias, never overwrite.
                    FileOutputStream(output).use { out ->
                        while (true) {
                            checkCancelled()
                            val read = zip.read(buffer)
                            if (read == -1) break
                            bytes += read
                            if (bytes > maxBytes) throw GameSaveException(SaveErrorCode.ZIP_TOO_LARGE)
                            out.write(buffer, 0, read)
                        }
                        out.fd.sync()
                    }
                    if (entry.time > 0) output.setLastModified(entry.time)
                    count++
                }
                zip.closeEntry()
            }
            return count
        }
    }

    private fun safeName(raw: String): String {
        // Unlike the legacy importer, do not trim absolute names into relative
        // ones, or normalize backslash/dot aliases before duplicate detection.
        if (raw.isBlank() || raw.length > 8192 || raw.any { it == '\u0000' || it == '\\' || it == ':' }) illegal(raw)
        val parts = raw.split('/')
        if (parts.size > MAX_DEPTH || parts.any { it.isEmpty() || it == "." || it == ".." || reserved(it) }) illegal(raw)
        return raw
    }

    private fun reserveInternalNodes(directory: File, prefix: String, internal: Boolean, nodes: EthornellExtractionNodes) {
        if (!exists(directory)) return
        children(directory).forEach { child ->
            checkCancelled()
            val relative = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
            val preserve = internal || reserved(child.name)
            if (preserve) nodes.reserve(relative)
            if (child.isDirectory) reserveInternalNodes(child, relative, preserve, nodes)
        }
    }

    private fun userFiles(directory: File): List<File> = buildList {
        if (!exists(directory)) return@buildList
        children(directory).forEach { child ->
            checkCancelled()
            if (!reserved(child.name)) {
                if (child.isDirectory) addAll(userFiles(child)) else add(child)
            }
        }
    }

    private fun validateTree(directory: File, depth: Int = 0, visited: IntArray = intArrayOf(0), cancellable: Boolean = true) {
        if (cancellable) checkCancelled()
        if (!exists(directory)) return
        if (++visited[0] > maxNodes || depth > MAX_DEPTH || Files.isSymbolicLink(directory.toPath())) unavailable()
        if (directory.isDirectory) children(directory).forEach { child ->
            if (!reserved(child.name)) safeName(child.name)
            validateTree(child, depth + 1, visited, cancellable)
        }
        else if (!directory.isFile) unavailable() // No FIFO/socket/device reads.
    }

    private fun restoreReserved(from: File, to: File) {
        if (!exists(from)) return
        children(from).forEach { child ->
            val target = File(to, child.name)
            if (reserved(child.name)) {
                if (exists(target)) unavailable() // Never overwrite/drop an internal inode.
                if (!to.isDirectory && !to.mkdirs()) unavailable()
                if (!rename(child, target)) unavailable()
            } else if (child.isDirectory) {
                // Imported file at an ancestor of old reserved metadata must
                // fail safely, keeping backup, rather than delete metadata.
                if (exists(target) && !target.isDirectory) {
                    if (containsReserved(child)) unavailable()
                } else restoreReserved(child, target)
            }
        }
    }

    private fun validateReservedDestinations(from: File, to: File) {
        if (!exists(from)) return
        children(from).forEach { child ->
            val target = File(to, child.name)
            if (reserved(child.name)) {
                if (exists(target)) unavailable()
            } else if (child.isDirectory && containsReserved(child)) {
                if (exists(target) && !target.isDirectory) unavailable()
                validateReservedDestinations(child, target)
            }
        }
    }

    private fun containsReserved(directory: File): Boolean = children(directory).any {
        reserved(it.name) || (it.isDirectory && containsReserved(it))
    }

    private fun pruneEmptyUserDirectories(directory: File) {
        if (!exists(directory)) return
        children(directory).forEach { child ->
            if (child.isDirectory && !reserved(child.name)) {
                pruneEmptyUserDirectories(child)
                if (children(child).isEmpty() && !child.delete()) unavailable()
            }
        }
    }

    private fun removeTree(file: File) {
        if (!exists(file)) return
        // Validate the complete bounded tree once; then remove without consulting
        // coroutine cancellation so a cancelled extraction cannot strand a tree
        // that blocks the next prepare/launch. Links still fail closed.
        validateTree(file, cancellable = false)
        fun removeValidated(current: File) {
            if (current.isDirectory) children(current).forEach { child ->
                if (reserved(child.name)) unavailable() // Reserved data is never garbage.
                removeValidated(child)
            }
            if (!current.delete()) unavailable()
        }
        removeValidated(file)
    }

    private fun syncTreeDirectories(directory: File) {
        if (!exists(directory)) return
        children(directory).filter { it.isDirectory && !reserved(it.name) }.forEach(::syncTreeDirectories)
        syncDirectory(directory)
    }

    private fun syncDirectory(directory: File) {
        FileChannel.open(directory.toPath(), READ).use { it.force(true) }
    }

    private fun children(directory: File): Array<File> = directory.listFiles() ?: unavailable()
    private fun exists(file: File) = Files.exists(file.toPath(), NOFOLLOW_LINKS)
    private fun reserved(name: String) = name.startsWith(".ethornell-", ignoreCase = true)
    private fun illegal(name: String): Nothing = throw GameSaveException(SaveErrorCode.ILLEGAL_ZIP_PATH, name)
    private fun unavailable(): Nothing = throw GameSaveException(SaveErrorCode.SAVE_DIR_UNAVAILABLE)

    companion object {
        private const val MAX_DEPTH = 64
        private const val MAX_VISITED = 100_000
    }
}

/** Counts actual staging nodes, including its root and ZIP-implicit parents.
 * Admission is atomic: an over-budget entry creates NO part of its path. */
internal class EthornellExtractionNodes(private val maximum: Int) {
    private val paths = mutableSetOf("")

    fun reserve(name: String) {
        val missing = mutableListOf<String>()
        var prefix = ""
        name.split('/').forEach { part ->
            prefix = if (prefix.isEmpty()) part else "$prefix/$part"
            if (prefix !in paths) missing += prefix
        }
        if (missing.size > maximum - paths.size) throw GameSaveException(SaveErrorCode.TOO_MANY_ZIP_FILES)
        paths.addAll(missing)
    }

    internal val size: Int get() = paths.size
}
