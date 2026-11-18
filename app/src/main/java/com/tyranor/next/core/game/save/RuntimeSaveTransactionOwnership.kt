package com.tyranor.next.core.game.save

import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

/** Proves ownership of adjacent import/recovery slots for third-party game directories. */
internal class RuntimeSaveTransactionOwnership(
    private val privateRoot: File,
    private val gameRoot: File,
    private val saveDirectory: File,
    private val gameUri: String,
    private val modulePackage: String,
    private val declarationFingerprint: String,
) {
    private val fixedArtifacts = listOf(
        File(saveDirectory.parentFile, saveDirectory.name + ".import_staging"),
        File(saveDirectory.parentFile, saveDirectory.name + ".import_backup"),
        File(saveDirectory.parentFile, saveDirectory.name + ".import_transaction"),
        File(saveDirectory.parentFile, saveDirectory.name + ".import_backup_retained"),
        File(saveDirectory.parentFile, saveDirectory.name + ".import_backup_retained1"),
        File(saveDirectory.parentFile, saveDirectory.name + ".import_backup_retained2"),
    )
    private val canonicalRoot = SavePathSafety.requireSafeRoot(gameRoot)
    private val canonicalSave = SavePathSafety.requireSafeRoot(saveDirectory, canonicalRoot)
    private val record = File(privateRoot, "runtime_save_tx_${digest(canonicalSave.path)}.json")
    private fun artifacts(): List<File> = fixedArtifacts + SaveImportTransaction.recoveryCopies(saveDirectory)

    /** Persist provenance before the first sibling can be created. Keep it after interrupted work. */
    fun begin() {
        val paths = artifacts()
        val transactionRecord = File(saveDirectory.parentFile, saveDirectory.name + ".import_transaction")
        paths.forEach { path ->
            SavePathSafety.requireSafeRoot(path.parentFile, canonicalRoot)
            if (Files.isSymbolicLink(path.toPath())) throw GameSaveException(SaveErrorCode.RESOURCE_RESTORE_CONFLICT)
            // The transaction journal is a regular file, unlike directory sidecars.
            if (path != transactionRecord) SavePathSafety.requireSafeRoot(path, canonicalRoot)
        }
        val existing = paths.filter { it.exists() || Files.isSymbolicLink(it.toPath()) }
        if (existing.isNotEmpty()) {
            if (!matchesRecord()) throw GameSaveException(SaveErrorCode.RESOURCE_RESTORE_CONFLICT)
            existing.forEach { path ->
                if (path == transactionRecord) {
                    if (!path.isFile || Files.isSymbolicLink(path.toPath()) || path.length() > 4096) {
                        throw GameSaveException(SaveErrorCode.RESOURCE_RESTORE_CONFLICT)
                    }
                } else {
                    SavePathSafety.requirePlainDirectoryEntry(path)
                    if (!path.isDirectory) throw GameSaveException(SaveErrorCode.RESOURCE_RESTORE_CONFLICT)
                }
            }
            return
        }
        if (record.exists() || Files.isSymbolicLink(record.toPath())) {
            if (!matchesRecord()) throw GameSaveException(SaveErrorCode.RESOURCE_RESTORE_CONFLICT)
            return
        }
        writeRecord()
    }

    /** Retire provenance only when every reserved sidecar has been removed. */
    fun completeIfClean() {
        if (!record.exists() && artifacts().none { it.exists() || Files.isSymbolicLink(it.toPath()) }) return
        if (!matchesRecord()) throw GameSaveException(SaveErrorCode.RESOURCE_RESTORE_CONFLICT)
        if (artifacts().any { it.exists() || Files.isSymbolicLink(it.toPath()) }) return
        Files.deleteIfExists(record.toPath())
    }

    /** Delete owns the transaction journal too, but only after all other reserved slots are gone. */
    fun completeAfterDelete() {
        if (!matchesRecord()) throw GameSaveException(SaveErrorCode.RESOURCE_RESTORE_CONFLICT)
        val transactionRecord = File(saveDirectory.parentFile, saveDirectory.name + ".import_transaction")
        if (artifacts().any { it != transactionRecord && (it.exists() || Files.isSymbolicLink(it.toPath())) }) {
            throw GameSaveException(SaveErrorCode.RESOURCE_RESTORE_CONFLICT)
        }
        Files.deleteIfExists(transactionRecord.toPath())
        Files.deleteIfExists(record.toPath())
    }

    private fun matchesRecord(): Boolean = try {
        if (!record.isFile || Files.isSymbolicLink(record.toPath()) || record.length() > 4096) false
        else {
            val json = JSONObject(record.readText())
            json.optString("uri") == gameUri && json.optString("module") == modulePackage &&
                json.optString("fingerprint") == declarationFingerprint &&
                json.optString("root") == canonicalRoot.path && json.optString("directory") == canonicalSave.path &&
                json.optString("token").isNotBlank()
        }
    } catch (_: Exception) {
        false
    }

    private fun writeRecord() {
        if (!privateRoot.exists() && !privateRoot.mkdirs()) throw GameSaveException(SaveErrorCode.SAVE_DIR_UNAVAILABLE)
        val temp = File.createTempFile("runtime_save_tx_", ".tmp", privateRoot)
        try {
            val value = JSONObject().put("uri", gameUri).put("module", modulePackage)
                .put("fingerprint", declarationFingerprint).put("root", canonicalRoot.path)
                .put("directory", canonicalSave.path).put("token", UUID.randomUUID().toString())
            temp.writeText(value.toString())
            try {
                Files.move(temp.toPath(), record.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), record.toPath())
            }
        } finally {
            temp.delete()
        }
    }

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
