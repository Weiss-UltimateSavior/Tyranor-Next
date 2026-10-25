package com.tyranor.next.core.game.save.sync

import org.json.JSONObject
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Small checkpoints survive process death; they never authorize changing an active save. */
internal class SaveSyncStateStore(private val directory: File) {
    fun load(key: String): JSONObject {
        val file = file(key)
        if (!file.exists()) return JSONObject()
        try {
            return JSONObject(file.readText(Charsets.UTF_8))
        } catch (failure: Exception) {
            throw SaveSyncException(SaveSyncError.LOCAL_IO, cause = failure)
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
        val file = file(key)
        if (file.exists() && !file.delete()) throw SaveSyncException(SaveSyncError.LOCAL_IO)
    }

    private fun file(key: String): File {
        require(key.matches(KEY))
        return File(directory, "$key.json")
    }

    companion object { private val KEY = Regex("[a-f0-9]{64}") }
}
