package com.tyranor.next.core.game.save

import org.json.JSONObject
import kotlinx.coroutines.CancellationException
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** The marker moves with staging; a recreated destination cannot prove the swap committed. */
internal class SaveImportTransaction(private val destination: File, private val gameUri: String) {
    private val record = File(destination.parentFile, destination.name + ".import_transaction")
    private val marker get() = File(destination, MARKER)

    fun prepare(staging: File, preservePrevious: Boolean = false) {
        val token = UUID.randomUUID().toString()
        val state = JSONObject().put("uri", gameUri).put("token", token)
            .put("preservePrevious", preservePrevious).toString()
        writeSynced(File(staging, MARKER), state)
        val temp = File.createTempFile("import_", ".tmp", destination.parentFile)
        try {
            writeSynced(temp, state)
            Files.move(temp.toPath(), record.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally { temp.delete() }
    }

    fun committed(): Boolean = committedRecord() != null

    private fun committedRecord(): JSONObject? = try {
        if (!record.isFile || Files.isSymbolicLink(record.toPath()) || record.length() > 4096 ||
            !marker.isFile || Files.isSymbolicLink(marker.toPath()) || marker.length() > 4096) null
        else {
            val expected = JSONObject(record.readText())
            val actual = JSONObject(marker.readText())
            val preserveMatches = if (expected.has("preservePrevious") || actual.has("preservePrevious")) {
                expected.opt("preservePrevious") is Boolean && actual.opt("preservePrevious") is Boolean &&
                    expected.getBoolean("preservePrevious") == actual.getBoolean("preservePrevious")
            } else true
            expected.takeIf {
                preserveMatches && expected.getString("uri") == gameUri && actual.getString("uri") == gameUri &&
                    expected.getString("token").isNotBlank() && expected.getString("token") == actual.getString("token")
            }
        }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { null }

    fun recoverBackup(backup: File, restoreCommitted: () -> Unit, copyMissingResources: () -> Unit) {
        SavePathSafety.requirePlainDirectoryEntry(backup)
        SavePathSafety.requirePlainDirectoryEntry(destination)
        if (backup.isDirectory) {
            val committedState = committedRecord()
            when {
                !destination.exists() -> if (!backup.renameTo(destination)) {
                    throw GameSaveException(SaveErrorCode.SAVE_DIR_UNAVAILABLE)
                }
                committedState != null -> {
                    restoreCommitted()
                    if (committedState.optBoolean("preservePrevious")) {
                        preserveUnprovenBackup(backup)
                    } else SavePathSafety.deleteTreeNoFollow(backup)
                }
                else -> {
                    // Keep the indexed backup until every atomic resource copy succeeds.
                    copyMissingResources()
                    preserveUnprovenBackup(backup)
                }
            }
        }
        clear()
    }

    fun preserveUnprovenBackup(backup: File): File {
        val orphan = File(backup.parentFile, destination.name + ".import_orphan_" + UUID.randomUUID())
        if (!backup.renameTo(orphan)) throw GameSaveException(SaveErrorCode.SAVE_DIR_UNAVAILABLE)
        return orphan
    }

    fun clear() {
        if (marker.exists() && !marker.delete()) throw GameSaveException(SaveErrorCode.SAVE_DIR_UNAVAILABLE)
        if (record.exists() && !record.delete()) throw GameSaveException(SaveErrorCode.SAVE_DIR_UNAVAILABLE)
    }

    private fun writeSynced(file: File, value: String) {
        java.io.FileOutputStream(file).use { stream ->
            stream.write(value.toByteArray(Charsets.UTF_8))
            stream.fd.sync()
        }
    }

    companion object { const val MARKER = ".tyranor_import_commit" }
}

internal fun copyMissingImportResource(source: File, target: File, root: File, skip: (String) -> Boolean) {
    SavePathSafety.requireSafeRoot(target.parentFile ?: throw GameSaveException(SaveErrorCode.SAVE_DIR_UNAVAILABLE), root)
    if (java.nio.file.Files.isSymbolicLink(source.toPath()) || java.nio.file.Files.isSymbolicLink(target.toPath())) {
        throw GameSaveException(SaveErrorCode.SAVE_DIR_UNAVAILABLE)
    }
    if (source.isDirectory) {
        if (!target.isDirectory && !target.mkdirs()) throw GameSaveException(SaveErrorCode.SAVE_DIR_UNAVAILABLE)
        val children = source.listFiles() ?: throw GameSaveException(SaveErrorCode.SAVE_DIR_UNAVAILABLE)
        children.filter { !skip(it.name) }
            .forEach { copyMissingImportResource(it, File(target, it.name), root, skip) }
    } else if (!source.isFile) {
        throw GameSaveException(SaveErrorCode.SAVE_DIR_UNAVAILABLE)
    } else if (target.exists() && !target.isFile) {
        throw GameSaveException(SaveErrorCode.SAVE_DIR_UNAVAILABLE)
    } else if (!target.exists()) {
        val parent = target.parentFile ?: throw GameSaveException(SaveErrorCode.SAVE_DIR_UNAVAILABLE)
        if (!parent.isDirectory && !parent.mkdirs()) throw GameSaveException(SaveErrorCode.CREATE_SAVE_DIR)
        parent.listFiles()?.filter { isImportRecoveryTemporary(it.name) }?.forEach(SavePathSafety::deleteTreeNoFollow)
        val staged = File.createTempFile(".tyranor_resource_", ".tmp", parent)
        try {
            source.inputStream().use { input -> java.io.FileOutputStream(staged).use { output ->
                input.copyTo(output)
                output.fd.sync()
            } }
            java.nio.file.Files.move(staged.toPath(), target.toPath())
        } finally { staged.delete() }
    }
}

internal fun isImportRecoveryTemporary(name: String): Boolean =
    name.matches(Regex("^\\.tyranor_resource_[0-9]+\\.tmp$"))
