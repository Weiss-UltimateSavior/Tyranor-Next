package com.tyranor.next.core.game.storage

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.tyranor.runtime.systemnnn.SystemNnnHost
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

/**
 * Read-only support for SystemNNN game copies that older builds created under
 * `externalFilesDir/systemnnn-imports`. Current builds open the game directory in place and never
 * copy it: a directory the engine cannot read or write is a launch error. Existing copies stay
 * readable so their saves are not stranded, and their resources can still be reclaimed.
 *
 * Layout kept for compatibility:
 * - `<sha256(source.id)>/source.id`: normalized SAF document id that produced the copy
 * - `<sha256(source.id)>/game/`: game resources plus the shared `sav` directory
 * - `<sha256(source.id)>/resources-evicted`: resource-reclamation marker
 */
internal object SystemNnnCopyStore {
    enum class Reason {
        NO_GRANT,
        SOURCE_MISSING,
        INVALID_SOURCE,
        COPY_FAILED,
        CORRUPT_COPY,
        BUSY,
    }

    class Failure(val reason: Reason, cause: Throwable? = null) : IOException(reason.name, cause)

    fun interface CopyOperation<T> {
        fun run(): T
    }

    internal const val STORAGE_PROVIDER = "com.android.externalstorage.documents"
    private const val DIRECTORY = "systemnnn-imports"
    private const val IDENTITY = "source.id"
    private const val GAME = "game"
    private const val EVICTED = "resources-evicted"
    private val COPY_KEY: Pattern = Pattern.compile("[0-9a-f]{64}")
    internal val VOLUME_NAME: Regex = Regex("[A-Za-z0-9_-]+")
    private const val MAX_ID_BYTES = 8192
    private const val TAG = "SystemNnnCopyStore"
    private val OPEN_COPIES: MutableSet<Path> = ConcurrentHashMap.newKeySet()

    fun sourceIdentity(directory: Uri?): String {
        if (directory == null || "content" != directory.scheme || STORAGE_PROVIDER != directory.authority) {
            throw Failure(Reason.INVALID_SOURCE)
        }
        val id = try {
            DocumentsContract.getDocumentId(directory)
        } catch (invalid: IllegalArgumentException) {
            try {
                DocumentsContract.getTreeDocumentId(directory)
            } catch (bad: IllegalArgumentException) {
                throw Failure(Reason.INVALID_SOURCE, bad)
            }
        }
        return normalizeIdentity(id)
    }

    fun normalizeIdentity(id: String?): String {
        if (id == null || id.toByteArray(StandardCharsets.UTF_8).size > MAX_ID_BYTES) {
            throw Failure(Reason.INVALID_SOURCE)
        }
        val colon = id.indexOf(':')
        if (colon <= 0) throw Failure(Reason.INVALID_SOURCE)
        var volume = id.substring(0, colon)
        val relative = id.substring(colon + 1)
        if (!VOLUME_NAME.matches(volume) || relative.indexOf('\\') >= 0 || relative.indexOf('\u0000') >= 0) {
            throw Failure(Reason.INVALID_SOURCE)
        }
        if (volume.equals("primary", ignoreCase = true)) volume = "primary"
        if (relative.isNotEmpty()) for (part in relative.split("/")) checkName(part)
        return "$volume:$relative"
    }

    fun committedRoot(context: Context, sourceId: String): File? {
        val external = context.getExternalFilesDir(null) ?: throw Failure(Reason.COPY_FAILED)
        return committedRoot(File(external, DIRECTORY), normalizeIdentity(sourceId))
    }

    fun committedRoot(base: File, sourceId: String): File? {
        if (Files.notExists(base.toPath(), LinkOption.NOFOLLOW_LINKS)) return null
        plainDirectory(base)
        val target = File(base, key(sourceId))
        if (Files.notExists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) return null
        plainDirectory(target)
        if (sourceId != readIdentity(target)) throw Failure(Reason.CORRUPT_COPY)
        val game = File(target, GAME)
        plainDirectory(game)
        return game
    }

    private fun evictionMarker(game: File): Path? =
        game.parentFile?.let { File(it, EVICTED).toPath() }

    fun resourcesEvicted(game: File): Boolean {
        val entry = game.parentFile ?: return false
        val base = entry.parentFile ?: return false
        val marker = evictionMarker(game) ?: return false
        return GAME == game.name && DIRECTORY == base.name &&
            COPY_KEY.matcher(entry.name).matches() &&
            !Files.notExists(marker, LinkOption.NOFOLLOW_LINKS)
    }

    fun hasImportedResources(game: File): Boolean = resourceChildren(game).isNotEmpty()

    fun readableBootstrap(root: File): Boolean = try {
        if (resourcesEvicted(root)) {
            false
        } else {
            val spt = child(root, "spt", true)
            val script = spt?.let { child(it, "sys.spt", false) }
            when {
                spt == null || script == null -> false
                Files.isSymbolicLink(spt.toPath()) || Files.isSymbolicLink(script.toPath()) -> false
                else -> FileInputStream(script).use { input -> input.read() != -1 }
            }
        }
    } catch (_: IOException) {
        false
    } catch (_: SecurityException) {
        false
    }

    private fun child(parent: File?, name: String, directory: Boolean): File? {
        if (parent == null) return null
        val exact = File(parent, name)
        if (if (directory) exact.isDirectory else exact.isFile) return exact
        val entries = parent.listFiles() ?: return null
        var found: File? = null
        for (entry in entries) {
            if (!entry.name.equals(name, ignoreCase = true)) continue
            if (!(if (directory) entry.isDirectory else entry.isFile)) continue
            if (found == null || entry.name < found.name) found = entry
        }
        return found
    }

    /** Serializes save mutations against resource reclamation for a legacy copy. */
    fun <T> withImportLock(context: Context, sourceId: String, operation: CopyOperation<T>): T {
        val external = context.getExternalFilesDir(null) ?: throw Failure(Reason.COPY_FAILED)
        return withImportLock(File(external, DIRECTORY), sourceId, operation)
    }

    fun <T> withImportLock(base: File, sourceId: String, operation: CopyOperation<T>): T =
        lockImportPath(base, sourceId, ".lock").use { operation.run() }

    private fun lockImportPath(base: File, sourceId: String, suffix: String): ImportLock {
        val key = key(normalizeIdentity(sourceId))
        Files.createDirectories(base.toPath())
        plainDirectory(base)
        val path = base.canonicalFile.toPath().resolve(key + suffix)
        if (!OPEN_COPIES.add(path)) throw Failure(Reason.BUSY)
        var channel: FileChannel? = null
        try {
            val opened = FileChannel.open(
                path,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS,
            )
            channel = opened
            val lock = try {
                opened.tryLock()
            } catch (_: OverlappingFileLockException) {
                null
            }
            if (lock == null) throw Failure(Reason.BUSY)
            return ImportLock(path, opened)
        } catch (failure: Exception) {
            try {
                channel?.close()
            } catch (cleanup: IOException) {
                failure.addSuppressed(cleanup)
            } finally {
                OPEN_COPIES.remove(path)
            }
            throw failure
        }
    }

    class ImportLock internal constructor(private val path: Path, private val channel: FileChannel) : AutoCloseable {
        override fun close() {
            try {
                channel.close()
            } finally {
                OPEN_COPIES.remove(path)
            }
        }
    }

    private fun checkName(name: String?) {
        if (name == null || name.isEmpty() || name == "." || name == ".." ||
            name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.indexOf('\u0000') >= 0
        ) {
            throw Failure(Reason.INVALID_SOURCE)
        }
    }

    private fun plainDirectory(directory: File) {
        if (Files.isSymbolicLink(directory.toPath()) ||
            !Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS)
        ) {
            throw Failure(Reason.CORRUPT_COPY)
        }
    }

    fun key(identity: String): String = try {
        val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(StandardCharsets.UTF_8))
        val text = StringBuilder()
        for (byte in digest) text.append(String.format(Locale.ROOT, "%02x", byte.toInt() and 255))
        text.toString()
    } catch (impossible: NoSuchAlgorithmException) {
        throw AssertionError(impossible)
    }

    private fun readIdentity(entry: File): String {
        val id = File(entry, IDENTITY).toPath()
        if (!Files.isRegularFile(id, LinkOption.NOFOLLOW_LINKS) || Files.size(id) > MAX_ID_BYTES) {
            throw Failure(Reason.CORRUPT_COPY)
        }
        val identity = ByteArrayOutputStream()
        Files.newInputStream(id, LinkOption.NOFOLLOW_LINKS).use { input ->
            while (true) {
                val value = input.read()
                if (value == -1) break
                if (identity.size() >= MAX_ID_BYTES) throw Failure(Reason.CORRUPT_COPY)
                identity.write(value)
            }
        }
        return String(identity.toByteArray(), StandardCharsets.UTF_8)
    }

    private fun resourceChildren(game: File): List<File> {
        plainDirectory(game)
        val children = game.listFiles() ?: throw Failure(Reason.COPY_FAILED)
        val resources = ArrayList<File>()
        var sawSaves = false
        for (child in children) {
            if (!child.name.equals("sav", ignoreCase = true)) {
                resources.add(child)
                continue
            }
            if (sawSaves) throw Failure(Reason.CORRUPT_COPY)
            plainDirectory(child)
            sawSaves = true
        }
        return resources
    }

    /** Removes imported game resources and keeps the whole `sav` directory in place. */
    fun clearResources(context: Context, sourceId: String): Boolean {
        val external = context.getExternalFilesDir(null) ?: throw Failure(Reason.COPY_FAILED)
        return clearResources(File(external, DIRECTORY), sourceId)
    }

    fun clearResources(base: File, sourceId: String): Boolean {
        val identity = normalizeIdentity(sourceId)
        if (committedRoot(base, identity) == null) return false
        return withImportLock(base, identity) {
            val game = committedRoot(base, identity) ?: return@withImportLock false
            val resources = resourceChildren(game)
            if (resources.isEmpty()) return@withImportLock false
            SystemNnnHost.lockSaveDirectory(game).use {
                val marker = evictionMarker(game) ?: throw Failure(Reason.CORRUPT_COPY)
                if (Files.notExists(marker, LinkOption.NOFOLLOW_LINKS)) Files.createFile(marker)
                else if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) throw Failure(Reason.CORRUPT_COPY)
                for (resource in resources) deleteOwnedTree(resource)
            }
            true
        }
    }

    private fun deleteOwnedTree(root: File) {
        if (Files.isDirectory(root.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            val children = root.listFiles() ?: throw IOException("Cannot list imported resources")
            for (child in children) deleteOwnedTree(child)
        }
        Files.delete(root.toPath())
    }
}
