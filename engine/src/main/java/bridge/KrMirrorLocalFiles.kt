package bridge

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Shared SHA-256 hex helper for mirror-local keying. */
internal object KrMirrorDigest {
    fun sha256Hex(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        val hex = "0123456789abcdef"
        return buildString(bytes.size * 2) {
            for (byte in bytes) {
                val value = byte.toInt() and 0xff
                append(hex[value ushr 4])
                append(hex[value and 15])
            }
        }
    }
}

/** App-private copy-up state. Markers survive an intentionally empty local override. */
internal class KrMirrorLocalFiles(mirrorRoot: File) {
    private val absoluteRoot = mirrorRoot.absoluteFile
    private val root = mirrorRoot.canonicalFile
    private val rootPrefixes = listOf(absoluteRoot, root).map {
        it.path.replace(File.separatorChar, '/').lowercase(Locale.ROOT).trimEnd('/') + "/"
    }.distinct()
    private val stateDirectory = File(root, ".tyranor-mirror-state")

    /**
     * Resolve an index key (or engine path) to the local mirror file. Index keys are
     * written from [absoluteRoot]-based spellings, but callers may present the same real
     * root through a different symlinked ancestor whose string form matches neither
     * prefix; such keys canonicalize onto [root], and the relative remainder rebuilds
     * the same local file under [absoluteRoot]. Returns null for paths outside the root.
     */
    fun fileForIndexKey(indexKey: String): File? {
        val normalized = indexKey.replace(File.separatorChar, '/').lowercase(Locale.ROOT)
        fileForPrefix(normalized)?.let { return it }
        val canonical = runCatching { File(indexKey).canonicalFile.path }.getOrNull()
            ?.replace(File.separatorChar, '/')?.lowercase(Locale.ROOT) ?: return null
        return fileForPrefix(canonical)
    }

    /** Scoped/local savedata must not acquire resource copy-up locks or create state. */
    fun resourceFileForPath(path: String): File? {
        val file = fileForIndexKey(path) ?: return null
        val relative = relativeForIndexKey(file.path) ?: return null
        return file.takeUnless { relative == "savedata" || relative.startsWith("savedata/") }
    }

    private fun fileForPrefix(normalized: String): File? {
        for (prefix in rootPrefixes) {
            if (!normalized.startsWith(prefix)) continue
            val relative = normalized.substring(prefix.length)
            if (!safeRelativePath(relative)) return null
            return File(absoluteRoot, relative)
        }
        return null
    }

    /**
     * Cheap prefix-only relative resolution for index keys written by this mirror
     * (absoluteRoot-based spelling). Returns null for unknown spellings; no canonicalize.
     */
    fun relativeForIndexKey(indexKey: String): String? {
        val normalized = indexKey.replace(File.separatorChar, '/').lowercase(Locale.ROOT)
        for (prefix in rootPrefixes) {
            if (!normalized.startsWith(prefix)) continue
            val relative = normalized.substring(prefix.length)
            return relative.takeIf { safeRelativePath(it) }
        }
        return null
    }

    /**
     * Relative keys (lowercase, root-relative) of intentional local overrides, including
     * legacy one-byte markers resolved against the provided index keys. Consumers compare
     * the relative part of their own keys instead of enumerating root spellings.
     */
    fun snapshotOverrideKeys(indexKeys: Collection<String> = emptyList()): Set<String> {
        val directory = File(stateDirectory, "local-overrides")
        val result = HashSet<String>()
        val legacyHashes = HashSet<String>()
        directory.listFiles().orEmpty().filter { it.isFile }.forEach { marker ->
            val relative = try { marker.readText(Charsets.UTF_8) } catch (_: IOException) { return@forEach }
            if (relative == "\u0001") {
                legacyHashes.add(marker.name)
            } else if (safeRelativePath(relative) && marker.name == digest(relative)) {
                result.add(relative)
            }
        }
        // Only pre-upgrade markers lack a relative payload. Keep intentional empty
        // overrides during upgrade without hashing every asset in the normal path.
        if (legacyHashes.isNotEmpty()) {
            indexKeys.forEach { key ->
                val file = fileForIndexKey(key) ?: return@forEach
                val relative = try { localKey(file) } catch (_: IOException) { return@forEach }
                if (digest(relative) in legacyHashes) result.add(relative)
            }
        }
        return result
    }

    fun isOverridden(file: File): Boolean = ownershipMarker(file).isFile

    /** A session caches this path, avoiding canonicalization and SHA on every stat. */
    fun ownershipMarker(file: File): File =
        File(File(stateDirectory, "local-overrides"), digest(localKey(file)))

    /** Called before recreating an absent placeholder; never discard a live empty override. */
    fun clearMissingOverride(file: File): Boolean {
        if (file.exists()) return false
        val marker = ownershipMarker(file)
        if (!marker.exists()) return false
        return withFileLock(file) {
            if (file.exists() || !marker.exists()) return@withFileLock false
            if (!marker.delete()) throw IOException("Cannot clear missing mirror override")
            true
        }
    }

    fun removeOverride(file: File) = withFileLock(file) {
        val marker = ownershipMarker(file)
        if (marker.exists() && !marker.delete()) throw IOException("Cannot remove mirror override")
    }

    fun markOverridden(file: File) {
        withFileLock(file) {
            val destination = ownershipMarker(file)
            val relative = localKey(file)
            if (destination.isFile && destination.readText(Charsets.UTF_8) == relative) return@withFileLock
            val directory = destination.parentFile ?: throw IOException("Missing override directory")
            ensureStateDirectory(directory)
            val temporary = File.createTempFile(".override-", ".tmp", directory)
            try {
                FileOutputStream(temporary).use { output ->
                    output.write(relative.toByteArray(Charsets.UTF_8))
                    output.fd.sync()
                }
                replaceAtomically(temporary, destination)
            } finally {
                temporary.delete()
            }
        }
    }

    /** Only modifying opens take an OS lock; at most 64 files exist per mirror. */
    fun <T> withFileLock(file: File, action: () -> T): T = withFileLocks(listOf(file), action)

    /** Real rename/unlink and copy-up use the same lock domain, in fixed stripe order. */
    fun <T> withFileLocks(files: List<File>, action: () -> T): T {
        files.forEach { localKey(it) }
        val stripes = files.map { stripe(it) }.distinct().sorted()
        fun acquire(index: Int): T {
            if (index == stripes.size) return action()
            val stripe = stripes[index]
            return synchronized(PROCESS_LOCKS[stripe]) {
                val lockKey = root.path + "/" + stripe
                val held = requireNotNull(HELD_LOCKS.get())
                if (lockKey in held) return@synchronized acquire(index + 1)
                val lockDirectory = File(stateDirectory, "locks")
                ensureStateDirectory(lockDirectory)
                RandomAccessFile(File(lockDirectory, "$stripe.lock"), "rw").use { lockFile ->
                    lockFile.channel.lock().use {
                        held.add(lockKey)
                        try { acquire(index + 1) } finally { held.remove(lockKey) }
                    }
                }
            }
        }
        return acquire(0)
    }

    /** Stream complete source bytes, bounded in disk use, and atomically publish ownership. */
    fun copyUp(
        file: File,
        expectedSize: Long,
        onSizeChanged: (Long, Long) -> Unit = { _, _ -> },
        openSource: () -> InputStream,
    ): File = withFileLock(file) {
        // A marker can outlive its local file (failed marker delete after unlink, or
        // external removal): treating such a key as owned would return the MISSING
        // file and the caller's RandomAccessFile("rw") would silently create empty
        // content — re-copy from the source instead (audit of fe3d057, FM1).
        if (isOverridden(file) && file.isFile) return@withFileLock file
        if (file.isDirectory) throw IOException("Mirror target is a directory")
        if (file.length() > 0L) {
            markOverridden(file)
            return@withFileLock file
        }
        if (expectedSize > MAX_COPY_BYTES) throw IOException("Mirror copy exceeds 64 MiB")
        val parent = file.parentFile ?: throw IOException("Missing mirror parent")
        if (!parent.isDirectory) throw IOException("Missing mirror directory")
        val temporary = createCopyTemporary(file, "copy-")
        val existed = file.exists()
        var published = false
        try {
            var copied = 0L
            openSource().use { input ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(COPY_BUFFER_SIZE)
                    while (true) {
                        checkInterrupted()
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        copied += count
                        if (copied > MAX_COPY_BYTES) throw IOException("Mirror copy exceeds 64 MiB")
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            // A short read means the provider stream ended early: publishing would
            // install a permanently truncated copy (the ownership marker stops later
            // copy-ups from re-reading the source). Fail like KrMirrorOverlayReader's
            // short-read policy instead; a larger actual size stays publishable (R10 #3).
            if (expectedSize >= 0L && copied < expectedSize) {
                throw IOException("Mirror source short read: indexed=$expectedSize copied=$copied")
            }
            if (expectedSize >= 0L && copied != expectedSize) onSizeChanged(expectedSize, copied)
            checkInterrupted()
            replaceAtomically(temporary, file)
            published = true
            markOverridden(file)
            file
        } catch (error: Exception) {
            // No writable fd is exposed until ownership is durable.
            if (published && !isOverridden(file)) {
                try {
                    if (existed) {
                        // Readers can hold the published inode without the write lock.
                        // Replace the path atomically instead of truncating that inode.
                        val rollback = createCopyTemporary(file, "rollback-")
                        try {
                            FileOutputStream(rollback).use { it.fd.sync() }
                            replaceAtomically(rollback, file)
                        } finally {
                            discardCopyTemporary(rollback)
                        }
                    } else {
                        file.delete()
                    }
                } catch (rollbackError: Exception) {
                    error.addSuppressed(rollbackError)
                }
            }
            throw error
        } finally {
            discardCopyTemporary(temporary)
        }
    }

    /** The caller holds this stripe's thread/process locks for the entire copy. */
    private fun createCopyTemporary(file: File, prefix: String): File {
        val directory = File(stateDirectory, "tmp/" + stripe(file))
        ensureStateDirectory(directory)
        directory.listFiles().orEmpty().forEach { candidate ->
            val ownedName = (candidate.name.startsWith("copy-") || candidate.name.startsWith("rollback-")) &&
                candidate.name.endsWith(".tmp")
            if (ownedName && candidate.isFile && candidate.path !in ACTIVE_TEMPORARIES) {
                if (!candidate.delete()) throw IOException("Cannot remove stale mirror temporary")
            }
        }
        return File.createTempFile(prefix, ".tmp", directory).also { ACTIVE_TEMPORARIES.add(it.path) }
    }

    private fun discardCopyTemporary(file: File) {
        try {
            file.delete()
        } finally {
            ACTIVE_TEMPORARIES.remove(file.path)
        }
    }

    private fun stripe(file: File): Int =
        (root.path.hashCode() xor file.name.lowercase(Locale.ROOT).hashCode()) and (LOCK_STRIPES - 1)

    private fun ensureStateDirectory(directory: File) {
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
            throw IOException("Cannot create mirror state directory")
        }
        if (!directory.canonicalPath.startsWith(root.path + File.separator)) {
            throw IOException("Mirror state escaped root")
        }
    }

    private fun digest(value: String): String = KrMirrorDigest.sha256Hex(value)

    private fun localKey(file: File): String {
        val path = file.canonicalPath
        val prefix = root.path + File.separator
        if (!path.startsWith(prefix)) throw IOException("Mirror local file escaped root")
        return path.substring(prefix.length).replace(File.separatorChar, '/').lowercase(Locale.ROOT)
    }

    private fun safeRelativePath(path: String): Boolean =
        path.isNotEmpty() && !path.startsWith('/') && !path.contains('\\') && !path.contains('\u0000') &&
            path.split('/').all { it.isNotEmpty() && it != "." && it != ".." }

    private fun replaceAtomically(from: File, to: File) {
        Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun checkInterrupted() {
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Mirror copy interrupted")
    }

    internal companion object {
        const val MAX_COPY_BYTES = 64L * 1024 * 1024
        private const val COPY_BUFFER_SIZE = 64 * 1024
        private const val LOCK_STRIPES = 64
        private val PROCESS_LOCKS = Array(LOCK_STRIPES) { Any() }
        private val ACTIVE_TEMPORARIES = ConcurrentHashMap.newKeySet<String>()
        private val HELD_LOCKS = ThreadLocal.withInitial { HashSet<String>() }
    }
}
