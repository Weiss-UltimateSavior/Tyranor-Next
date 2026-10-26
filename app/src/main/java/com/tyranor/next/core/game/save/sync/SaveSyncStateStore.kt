package com.tyranor.next.core.game.save.sync

import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Small checkpoints survive process death; they never authorize changing an active save. */
internal class SaveSyncStateStore(
    private val directory: File,
    /** Only reconstructible runtime caches may recover as missing; durable records stay strict. */
    private val recoverCorrupt: Boolean = false,
) {
    fun load(key: String): JSONObject {
        val file = file(key)
        if (!file.exists()) return JSONObject()
        val text = try {
            file.readText(Charsets.UTF_8)
        } catch (failure: IOException) {
            throw SaveSyncException(SaveSyncError.LOCAL_IO, cause = failure)
        } catch (failure: SecurityException) {
            throw SaveSyncException(SaveSyncError.LOCAL_IO, cause = failure)
        }
        return try {
            JSONObject(text)
        } catch (failure: JSONException) {
            quarantine(file, consumeOriginal = recoverCorrupt)
            if (recoverCorrupt) JSONObject()
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

    fun remove(key: String) {
        val state = file(key)
        if (!directory.exists()) return
        val candidates = (directory.listFiles() ?: throw SaveSyncException(SaveSyncError.LOCAL_IO))
            .filter { it.name == state.name || isQuarantineName(key, it.name) }
        var deletionFailed = false
        for (candidate in candidates) {
            if (candidate.exists() && !candidate.delete()) deletionFailed = true
        }
        if (deletionFailed) throw SaveSyncException(SaveSyncError.LOCAL_IO)
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

    private fun isQuarantineName(key: String, name: String): Boolean {
        val prefix = "$key.json.corrupt"
        if (name == prefix) return true
        if (!name.startsWith("$prefix-")) return false
        return name.removePrefix("$prefix-").let { suffix ->
            suffix.isNotEmpty() && suffix.all { it in '0'..'9' }
        }
    }

    private fun file(key: String): File {
        require(key.matches(KEY))
        return File(directory, "$key.json")
    }

    companion object { private val KEY = Regex("[a-f0-9]{64}") }
}
