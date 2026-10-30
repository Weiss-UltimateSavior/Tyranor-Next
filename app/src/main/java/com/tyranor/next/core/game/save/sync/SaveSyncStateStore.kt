package com.tyranor.next.core.game.save.sync

import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption

/** Small checkpoints survive process death; they never authorize changing an active save. */
internal class SaveSyncStateStore(
    private val directory: File,
    /** Only reconstructible runtime caches may recover as missing; durable records stay strict. */
    private val recoverCorrupt: Boolean = false,
) {
    fun load(key: String): JSONObject = read(key, {}, recoverCorrupt)

    fun load(key: String, validate: (JSONObject) -> Unit): JSONObject =
        read(key, validate, recoverCorrupt)

    /** Ownership scans must leave unrelated malformed records and recovery copies untouched. */
    fun inspect(key: String): JSONObject = read(key, {}, recover = false, quarantineCorrupt = false)

    private fun read(
        key: String,
        validate: (JSONObject) -> Unit,
        recover: Boolean,
        quarantineCorrupt: Boolean = true,
    ): JSONObject {
        val file = file(key)
        val text = try {
            String(Files.readAllBytes(file.toPath()), Charsets.UTF_8)
        } catch (failure: NoSuchFileException) {
            // Windows may report PATH_NOT_FOUND where Unix reports ENOTDIR. An existing
            // non-directory store root is an I/O failure on either platform, not missing state.
            if (Files.exists(directory.toPath()) && !Files.isDirectory(directory.toPath())) {
                throw SaveSyncException(SaveSyncError.LOCAL_IO, cause = failure)
            }
            return JSONObject()
        } catch (failure: IOException) {
            throw SaveSyncException(SaveSyncError.LOCAL_IO, cause = failure)
        } catch (failure: SecurityException) {
            throw SaveSyncException(SaveSyncError.LOCAL_IO, cause = failure)
        }
        return try {
            JSONObject(text).also(validate)
        } catch (failure: JSONException) {
            if (quarantineCorrupt) quarantine(file, consumeOriginal = recover)
            if (recover) JSONObject()
            else throw SaveSyncException(SaveSyncError.LOCAL_IO, cause = failure)
        }
    }

    fun save(key: String, state: JSONObject) {
        if (!directory.isDirectory && !directory.mkdirs()) throw SaveSyncException(SaveSyncError.LOCAL_IO)
        val target = file(key)
        val temp = File.createTempFile("state_", ".tmp", directory)
        try {
            temp.outputStream().use { output ->
                output.write(state.toString().toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            try {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temp.delete()
        }
    }

    fun keys(): List<String> {
        if (!directory.exists()) return emptyList()
        return (directory.listFiles() ?: throw SaveSyncException(SaveSyncError.LOCAL_IO))
            .mapNotNull { it.name.removeSuffix(".json").takeIf { key -> it.name.endsWith(".json") && key.matches(KEY) } }
    }

    /** Remove only the live record; recovery copies remain available for inspection. */
    fun remove(key: String) = deleteRecord(key, allowEmptyDirectory = false)

    /**
     * The caller must establish ownership independently. A known empty-directory obstruction
     * can be removed on retry; ordinary file read failures still fail closed.
     */
    fun removeOwned(key: String) {
        if (!Files.isDirectory(file(key).toPath())) {
            try {
                read(key, {}, recover = false)
            } catch (failure: SaveSyncException) {
                if (failure.cause !is JSONException) throw failure
            }
        }
        deleteRecord(key, allowEmptyDirectory = true)
    }

    private fun deleteRecord(key: String, allowEmptyDirectory: Boolean) {
        val state = file(key)
        try {
            if (!allowEmptyDirectory && Files.isDirectory(state.toPath())) throw IOException("State record is a directory")
            Files.deleteIfExists(state.toPath())
        } catch (failure: IOException) {
            throw SaveSyncException(SaveSyncError.LOCAL_IO, cause = failure)
        } catch (failure: SecurityException) {
            throw SaveSyncException(SaveSyncError.LOCAL_IO, cause = failure)
        }
    }

    /**
     * Repair a known owned key only. JSON/schema corruption is quarantined before removal;
     * read failures propagate and valid records are untouched, including reconstructible caches.
     */
    fun discardCorrupt(key: String, validate: (JSONObject) -> Unit = {}): Boolean {
        try {
            read(key, validate, recover = false)
            return false
        } catch (failure: SaveSyncException) {
            if (failure.cause !is JSONException) throw failure
            remove(key)
            return true
        }
    }

    private fun quarantine(file: File, consumeOriginal: Boolean) {
        val base = File(directory, file.name + ".corrupt")
        try {
            if (!consumeOriginal) {
                if (base.exists()) return
                val temp = File.createTempFile("${file.name}.quarantine-", ".tmp", directory)
                try {
                    Files.copy(file.toPath(), temp.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    move(temp, base)
                } finally {
                    temp.delete()
                }
                return
            }
            var target = base
            var suffix = 1
            while (target.exists()) target = File(directory, "${base.name}-${suffix++}")
            move(file, target)
        } catch (failure: IOException) {
            throw SaveSyncException(SaveSyncError.LOCAL_IO, cause = failure)
        } catch (failure: SecurityException) {
            throw SaveSyncException(SaveSyncError.LOCAL_IO, cause = failure)
        }
    }

    private fun move(source: File, target: File) {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath())
        }
    }

    private fun file(key: String): File {
        require(key.matches(KEY))
        return File(directory, "$key.json")
    }

    companion object { private val KEY = Regex("[a-f0-9]{64}") }
}
