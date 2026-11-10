package com.tyranor.next.core.game.storage

import android.content.Context
import android.content.UriPermission
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import com.tyranor.runtime.systemnnn.SystemNnnHost
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

/**
 * App-owned imported game copies for a SystemNNN game whose shared-storage path is blocked but
 * whose SAF tree is readable (Android 10). The engine only receives the final directory path, so it
 * owns no copy inventory and writes no source identity of its own.
 *
 * Layout under `externalFilesDir/systemnnn-imports`:
 * - `<sha256(source.id)>/source.id`: normalized SAF document id that produced the copy
 * - `<sha256(source.id)>/game/`: committed game resources plus the shared `sav` directory
 * - `<sha256(source.id)>/resources-evicted`: resource-reclamation marker; the copy becomes runnable
 *   again only after the app restores the resources
 */
internal object SystemNnnCopyStore {
    /** Throw [CancellationException] to cancel; no partially copied directory becomes runnable. */
    fun interface Cancellation {
        fun check()
    }

    enum class Reason {
        NO_GRANT,
        SOURCE_MISSING,
        INVALID_SOURCE,
        INCOMPLETE_SOURCE,
        NO_SPACE,
        COPY_FAILED,
        COMMIT_FAILED,
        CORRUPT_COPY,
        BUSY,
    }

    class Failure(val reason: Reason, cause: Throwable? = null) : IOException(reason.name, cause)

    fun interface ImportSource {
        fun stageResources(game: File): SaveCopy
    }

    fun interface SaveCopy {
        /** Invoked only for a fresh copy; the source save lock stays held through publication. */
        fun copyAndPublish(publish: CopyOperation<File>): File
    }

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
    private const val TAG = "SystemNnnCopyStore"
    private const val MAX_ENTRIES = 100000
    private const val MAX_DEPTH = 64
    private const val MAX_ID_BYTES = 8192
    private const val CHUNK = 64 * 1024
    private const val FREE_HEADROOM = 256L * 1024 * 1024
    private const val SPACE_RECHECK_BYTES = 4L * 1024 * 1024
    private val NONE = Cancellation {}
    private val OPEN_COPIES: MutableSet<Path> = ConcurrentHashMap.newKeySet()
    private val COLUMNS = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
    )

    /** A `primary:` document id is independent of the granting ancestor and the Android user number. */
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

    private fun contains(ancestor: String, child: String): Boolean =
        child == ancestor || child.startsWith(ancestor + if (ancestor.endsWith(":")) "" else "/")

    /** Returns a document URI rooted in the nearest persisted read grant, or null when reauthorization is needed. */
    fun findAuthorizedSource(context: Context, directory: Uri): Uri? {
        val identity = sourceIdentity(directory)
        var best: Uri? = null
        var length = -1
        for (permission: UriPermission in context.contentResolver.persistedUriPermissions) {
            if (!permission.isReadPermission) continue
            val tree = permission.uri
            if ("content" != tree.scheme || STORAGE_PROVIDER != tree.authority) continue
            try {
                val parent = normalizeIdentity(DocumentsContract.getTreeDocumentId(tree))
                if (contains(parent, identity) && parent.length > length) {
                    best = tree
                    length = parent.length
                }
            } catch (_: IllegalArgumentException) {
            } catch (_: Failure) {
            }
        }
        return best?.let { DocumentsContract.buildDocumentUriUsingTree(it, identity) }
    }

    /** Null means no committed copy. A damaged committed copy never silently falls back to another sav directory. */
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

    /** Imports resources without changing the source or replacing an existing game/sav. */
    fun importDirectory(
        context: Context,
        source: Uri,
        sourceId: String,
        allowCreate: Boolean,
        cancellation: Cancellation?,
    ): File = stageDirectory(context, source, sourceId, allowCreate, cancellation).use { it.publish() }

    /** Only private staging files are written here. The caller admits the save change before publish. */
    fun stageDirectory(
        context: Context,
        source: Uri,
        sourceId: String,
        allowCreate: Boolean,
        cancellation: Cancellation?,
    ): StagedCopy {
        val check = cancellation ?: NONE
        check.check()
        val identity = normalizeIdentity(sourceId)
        if (identity != sourceIdentity(source)) throw Failure(Reason.INVALID_SOURCE)
        val external = context.getExternalFilesDir(null) ?: throw Failure(Reason.COPY_FAILED)
        val base = File(external, DIRECTORY)
        return stageDirectory(base, identity, allowCreate, check) {
            val granted = findAuthorizedSource(context, source) ?: throw Failure(Reason.NO_GRANT)
            ImportSource { game -> Source(context, granted, identity, check).stageResources(base, game) }
        }
    }

    class StagedCopy internal constructor(
        private val staging: File?,
        private val lock: ImportLock?,
        private val commit: CopyOperation<File>,
    ) : AutoCloseable {
        private var closed = false

        /** Call under the host's save mutation fence when the active sav may change. */
        fun publish(): File {
            check(!closed) { "Import staging is closed" }
            return copyOperation(commit)
        }

        override fun close() {
            if (closed) return
            closed = true
            if (lock != null) cleanupStage(staging, lock, null)
        }
    }

    /**
     * Stages game resources next to [base] and publishes them atomically. [authorize] produces the
     * [ImportSource] whose `stageResources` receives the staging directory, so the caller can include
     * source saves or restore an evicted copy.
     */
    fun stageDirectory(
        base: File,
        identity: String,
        allowCreate: Boolean,
        check: Cancellation,
        authorize: CopyOperation<ImportSource>,
    ): StagedCopy = copyOperation {
        val existing = committedRoot(base, identity)
        if (existing != null && !resourcesEvicted(existing)) {
            return@copyOperation StagedCopy(null, null) { existing }
        }
        if (existing == null && !allowCreate) throw Failure(Reason.CORRUPT_COPY)
        val key = key(identity)
        // This lock is distinct from the source import lock used by ZIP writes and publication.
        val lock = lockImportPath(base, identity, ".staging.lock")
        val staging = File(base, "$key.pending-${UUID.randomUUID()}")
        var created = false
        try {
            val source = authorize.run()
            val leftovers = base.listFiles() ?: throw Failure(Reason.COPY_FAILED)
            for (leftover in leftovers) {
                if (leftover.name.startsWith("$key.pending-")) {
                    check.check()
                    deleteOwnedTree(leftover)
                }
            }
            check.check()
            Files.createDirectory(staging.toPath())
            created = true
            val game = File(staging, GAME)
            Files.createDirectory(game.toPath())
            val saves = source.stageResources(game)
            writeIdentity(staging, identity)
            StagedCopy(staging, lock) {
                withImportLock(base, identity) {
                    check.check()
                    val current = committedRoot(base, identity)
                    if (current != null) {
                        // An evicted copy keeps its sav; only the resources are restored.
                        if (resourcesEvicted(current)) restoreResources(current, game)
                        current
                    } else {
                        if (!allowCreate) throw Failure(Reason.CORRUPT_COPY)
                        saves.copyAndPublish {
                            check.check()
                            val target = File(base, key)
                            try {
                                Files.move(staging.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
                            } catch (failed: IOException) {
                                throw Failure(Reason.COMMIT_FAILED, failed)
                            }
                            File(target, GAME)
                        }
                    }
                }
            }
        } catch (failure: Exception) {
            cleanupStage(if (created) staging else null, lock, failure)
            throw failure
        }
    }

    private fun writeIdentity(staging: File, identity: String) {
        val bytes = identity.toByteArray(StandardCharsets.UTF_8)
        createFile(File(staging, IDENTITY)).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
    }

    private fun cleanupStage(staging: File?, lock: ImportLock, failure: Throwable?) {
        try {
            lock.use {
                if (staging != null && Files.exists(staging.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                    deleteOwnedTree(staging)
                }
            }
        } catch (cleanup: IOException) {
            if (failure != null) failure.addSuppressed(cleanup)
            else android.util.Log.w(TAG, "Could not remove SystemNNN import staging", cleanup)
        } catch (cleanup: SecurityException) {
            if (failure != null) failure.addSuppressed(cleanup)
            else android.util.Log.w(TAG, "Could not remove SystemNNN import staging", cleanup)
        }
    }

    private fun <T> copyOperation(operation: CopyOperation<T>): T = try {
        operation.run()
    } catch (failure: Failure) {
        throw failure
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (grant: SecurityException) {
        throw Failure(Reason.NO_GRANT, grant)
    } catch (io: IOException) {
        throw Failure(Reason.COPY_FAILED, io)
    } catch (runtime: RuntimeException) {
        throw Failure(Reason.COPY_FAILED, runtime)
    }

    // The marker makes an interrupted publish unrunnable while keeping the same save authority.
    private fun restoreResources(game: File, staging: File) {
        val marker = evictionMarker(game) ?: throw Failure(Reason.CORRUPT_COPY)
        if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) throw Failure(Reason.CORRUPT_COPY)
        val old = resourceChildren(game)
        try {
            SystemNnnHost.lockSaveDirectory(game).use {
                for (resource in old) deleteOwnedTree(resource)
                for (resource in resourceChildren(staging)) {
                    Files.move(
                        resource.toPath(),
                        File(game, resource.name).toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                    )
                }
                Files.delete(marker)
            }
        } catch (busy: SystemNnnHost.SaveBusyException) {
            throw Failure(Reason.BUSY, busy)
        }
    }

    /** Holds the same source lock used for publishing a copy. Resolve and write the active sav inside it. */
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

    private class Entry(row: Cursor) {
        val id: String = normalizeIdentity(row.getString(0))
        val name: String = row.getString(1).also { checkName(it) }
        val directory: Boolean = DocumentsContract.Document.MIME_TYPE_DIR == row.getString(2)
        val size: Long = if (row.isNull(3)) -1 else row.getLong(3)
        val modified: Long = if (row.isNull(4)) 0 else row.getLong(4)

        fun stamp(): String = "$id\n$size\n$modified"
    }

    private class Source(
        private val context: Context,
        private val tree: Uri,
        private val identity: String,
        private val check: Cancellation,
    ) {
        fun stageResources(base: File, game: File): SaveCopy {
            requireRoot()
            val roots = children(identity).toMutableList()
            requireBootstrap(roots)
            roots.removeAll { it.name.equals("sav", ignoreCase = true) }
            val budget = Budget(base)
            copy(roots, game, "", 0, budget)
            if (!readableBootstrap(game)) throw Failure(Reason.INVALID_SOURCE)
            return SaveCopy { publish -> copySaves(game, budget, publish) }
        }

        private fun copySaves(game: File, budget: Budget, publish: CopyOperation<File>): File {
            val sav = findSaves()
            lockSaves(sav).use {
                val before = saveSnapshot(sav)
                if (sav != null) copy(listOf(sav), game, "", 0, budget)
                val current = findSaves()
                if ((sav == null) != (current == null) ||
                    (sav != null && sav.id != current?.id) ||
                    before != saveSnapshot(current)
                ) {
                    throw Failure(Reason.INCOMPLETE_SOURCE)
                }
                return publish.run()
            }
        }

        private fun findSaves(): Entry? {
            var saves: Entry? = null
            for (entry in children(identity)) {
                if (!entry.name.equals("sav", ignoreCase = true)) continue
                if (!entry.directory || saves != null) throw Failure(Reason.INVALID_SOURCE)
                saves = entry
            }
            return saves
        }

        private fun uri(id: String): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)

        private fun requireBootstrap(roots: List<Entry>) {
            var spt: Entry? = null
            for (entry in roots) {
                if (entry.directory && entry.name.equals("spt", ignoreCase = true)) {
                    if (spt != null) throw Failure(Reason.INVALID_SOURCE)
                    spt = entry
                }
            }
            val directory = spt ?: throw Failure(Reason.INVALID_SOURCE)
            var script: Entry? = null
            for (entry in children(directory.id)) {
                if (!entry.directory && entry.name.equals("sys.spt", ignoreCase = true)) {
                    if (script != null) throw Failure(Reason.INVALID_SOURCE)
                    script = entry
                }
            }
            val found = script ?: throw Failure(Reason.INVALID_SOURCE)
            val input: InputStream = context.contentResolver.openInputStream(uri(found.id))
                ?: throw Failure(Reason.NO_GRANT)
            input.use { if (it.read() == -1) throw Failure(Reason.INVALID_SOURCE) }
        }

        private fun requireRoot() {
            try {
                context.contentResolver.query(uri(identity), COLUMNS, null, null, null).use { row ->
                    if (row == null) throw Failure(Reason.NO_GRANT)
                    if (!row.moveToFirst()) throw Failure(Reason.SOURCE_MISSING)
                    val root = Entry(row)
                    if (identity != root.id || !root.directory) throw Failure(Reason.INVALID_SOURCE)
                }
            } catch (grant: SecurityException) {
                throw Failure(Reason.NO_GRANT, grant)
            }
        }

        private fun children(id: String): List<Entry> {
            check.check()
            val result = ArrayList<Entry>()
            val names = HashSet<String>()
            try {
                context.contentResolver
                    .query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, id), COLUMNS, null, null, null)
                    .use { row ->
                        if (row == null) throw Failure(Reason.NO_GRANT)
                        while (row.moveToNext()) {
                            check.check()
                            val entry = Entry(row)
                            if (!contains(id, entry.id) || id == entry.id || !names.add(entry.name) ||
                                result.size >= MAX_ENTRIES
                            ) {
                                throw Failure(Reason.INVALID_SOURCE)
                            }
                            result.add(entry)
                        }
                    }
            } catch (grant: SecurityException) {
                throw Failure(Reason.NO_GRANT, grant)
            }
            return result
        }

        private fun saveSnapshot(sav: Entry?): Map<String, String> {
            val result = LinkedHashMap<String, String>()
            if (sav != null) snapshot(sav.id, "", 0, result)
            return result
        }

        private fun snapshot(id: String, relative: String, depth: Int, result: MutableMap<String, String>) {
            if (depth > MAX_DEPTH) throw Failure(Reason.INVALID_SOURCE)
            for (entry in children(id)) {
                val path = relative + entry.name
                if (relative.isEmpty() && entry.name.equals("emberfall.lock", ignoreCase = true)) continue
                if (entry.name.startsWith("emberfall") &&
                    (entry.name.endsWith(".tmp") || entry.name.endsWith(".new"))
                ) {
                    throw Failure(Reason.INCOMPLETE_SOURCE)
                }
                if (relative.isEmpty() && entry.name.equals("emberfall.migrate", ignoreCase = true)) {
                    if (!entry.directory || children(entry.id).isNotEmpty()) {
                        throw Failure(Reason.INCOMPLETE_SOURCE)
                    }
                    continue
                }
                if (result.size >= MAX_ENTRIES) throw Failure(Reason.INVALID_SOURCE)
                result[path] = entry.stamp()
                if (entry.directory) snapshot(entry.id, "$path/", depth + 1, result)
            }
        }

        private fun lockSaves(sav: Entry?): SourceLock {
            if (sav == null) return SourceLock(null, null)
            for (entry in children(sav.id)) {
                if (!entry.name.equals("emberfall.lock", ignoreCase = true)) continue
                if (entry.directory) throw Failure(Reason.INVALID_SOURCE)
                val descriptor = (try {
                    context.contentResolver.openFileDescriptor(uri(entry.id), "r")
                } catch (denied: SecurityException) {
                    throw Failure(Reason.NO_GRANT, denied)
                } catch (missing: FileNotFoundException) {
                    throw Failure(Reason.NO_GRANT, missing)
                }) ?: throw Failure(Reason.NO_GRANT)
                val stream = android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor)
                val lock: FileLock? = try {
                    stream.channel.tryLock(0, Long.MAX_VALUE, true)
                } catch (failure: IOException) {
                    stream.close()
                    throw Failure(Reason.COPY_FAILED, failure)
                } catch (failure: OverlappingFileLockException) {
                    stream.close()
                    throw Failure(Reason.BUSY, failure)
                }
                if (lock == null) {
                    stream.close()
                    throw Failure(Reason.BUSY)
                }
                return SourceLock(stream, lock)
            }
            return SourceLock(null, null)
        }

        private fun copy(entries: List<Entry>, destination: File, relative: String, depth: Int, budget: Budget) {
            if (depth > MAX_DEPTH) throw Failure(Reason.INVALID_SOURCE)
            for (entry in entries) {
                check.check()
                if (++budget.entries > MAX_ENTRIES) throw Failure(Reason.INVALID_SOURCE)
                if (relative.equals("sav/", ignoreCase = true) &&
                    entry.name.equals("emberfall.lock", ignoreCase = true)
                ) {
                    continue
                }
                if (relative.equals("sav/", ignoreCase = true) &&
                    entry.name.equals("emberfall.migrate", ignoreCase = true)
                ) {
                    if (!entry.directory || children(entry.id).isNotEmpty()) {
                        throw Failure(Reason.INCOMPLETE_SOURCE)
                    }
                    continue
                }
                val target = File(destination, entry.name)
                if (Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                    throw Failure(Reason.INVALID_SOURCE)
                }
                if (entry.directory) {
                    Files.createDirectory(target.toPath())
                    copy(children(entry.id), target, relative + entry.name + "/", depth + 1, budget)
                } else {
                    val input = context.contentResolver.openInputStream(uri(entry.id))
                        ?: throw Failure(Reason.COPY_FAILED)
                    input.use { copyFile(it, target, entry.size, budget, check) }
                    if (entry.modified > 0) target.setLastModified(entry.modified)
                }
            }
        }
    }

    private class SourceLock(private val stream: FileInputStream?, private val lock: FileLock?) : AutoCloseable {
        override fun close() {
            try {
                if (lock != null && lock.isValid) lock.release()
            } catch (_: IOException) {
                // Closing the stream below is what actually releases the shared lock.
            } finally {
                stream?.close()
            }
        }
    }

    private class Budget(volume: File) {
        val volume: File = volume
        val limit: Long = volume.usableSpace - FREE_HEADROOM
        var bytes = 0L
        var nextSpaceCheck = 0L
        var entries = 0

        init {
            if (limit <= 0) throw Failure(Reason.NO_SPACE)
        }
    }

    private fun copyFile(input: InputStream, target: File, declared: Long, budget: Budget, check: Cancellation) {
        val block = ByteArray(CHUNK)
        var copied = 0L
        createFile(target).use { channel ->
            val output = Channels.newOutputStream(channel)
            while (true) {
                check.check()
                val size = input.read(block)
                if (size < 0) break
                if (size == 0) continue
                if (size > budget.limit - budget.bytes) throw Failure(Reason.NO_SPACE)
                if (budget.bytes >= budget.nextSpaceCheck) {
                    if (budget.volume.usableSpace < FREE_HEADROOM + size) throw Failure(Reason.NO_SPACE)
                    budget.nextSpaceCheck = budget.bytes + SPACE_RECHECK_BYTES
                }
                output.write(block, 0, size)
                copied += size
                budget.bytes += size
            }
            if (declared >= 0 && copied != declared) throw Failure(Reason.INCOMPLETE_SOURCE)
            channel.force(true)
        }
    }

    private fun createFile(target: File): FileChannel = FileChannel.open(
        target.toPath(),
        StandardOpenOption.CREATE_NEW,
        StandardOpenOption.WRITE,
        LinkOption.NOFOLLOW_LINKS,
    )

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
