package com.tyranor.next.core.game.save.sync

import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.IOException
import java.nio.file.FileSystemException

/** Classifies local storage failures without changing network or cancellation semantics. */
internal fun classifySaveSyncStorageFailure(
    failure: Throwable,
    directory: File?,
    remainingBytes: Long,
    usableSpace: (File) -> Long = { it.usableSpace },
): SaveSyncException {
    if (failure is CancellationException) throw failure
    if (failure is SaveSyncException) return failure
    val storageFull = failure.indicatesStorageFull() ||
        (directory != null && storageProvablyInsufficient(directory, remainingBytes, usableSpace))
    return SaveSyncException(
        if (storageFull) SaveSyncError.STORAGE_FULL else SaveSyncError.LOCAL_IO,
        cause = failure,
    )
}

/** Cache temp files are transient; only the persistent history write keeps the full 16 MiB headroom. */
internal fun requireSaveSyncTransientSpace(
    archiveBytes: Long,
    directory: File,
    usableSpace: (File) -> Long = { it.usableSpace },
) {
    val usableBytes = try {
        usableSpace(directory)
    } catch (failure: SecurityException) {
        throw classifySaveSyncStorageFailure(failure, directory, archiveBytes, usableSpace)
    }
    if (!hasSaveSyncTransientSpace(archiveBytes, usableBytes)) {
        throw SaveSyncException(SaveSyncError.STORAGE_FULL)
    }
}

internal const val SAVE_SYNC_TRANSIENT_RESERVE_BYTES = 1L * 1024 * 1024

internal fun <T> runSaveSyncStorageOperation(
    directory: File?,
    remainingBytes: Long,
    usableSpace: (File) -> Long = { it.usableSpace },
    operation: () -> T,
): T {
    try {
        return operation()
    } catch (failure: IOException) {
        throw classifySaveSyncStorageFailure(failure, directory, remainingBytes, usableSpace)
    } catch (failure: SecurityException) {
        throw classifySaveSyncStorageFailure(failure, directory, remainingBytes, usableSpace)
    }
}

private fun storageProvablyInsufficient(
    directory: File,
    remainingBytes: Long,
    usableSpace: (File) -> Long,
): Boolean {
    return try {
        !hasSaveSyncTransientSpace(remainingBytes, usableSpace(directory))
    } catch (_: SecurityException) {
        false
    }
}

private fun hasSaveSyncTransientSpace(requiredBytes: Long, usableBytes: Long): Boolean {
    val required = requiredBytes.coerceAtLeast(0L)
    return usableBytes >= SAVE_SYNC_TRANSIENT_RESERVE_BYTES &&
        required <= usableBytes - SAVE_SYNC_TRANSIENT_RESERVE_BYTES
}

internal fun Throwable.indicatesStorageFull(): Boolean {
    var current: Throwable? = this
    while (current != null) {
        val details = listOfNotNull(current.message, (current as? FileSystemException)?.reason)
            .joinToString(" ").lowercase()
        if (STORAGE_FULL_MESSAGES.any(details::contains)) return true
        current = current.cause
    }
    return false
}

private val STORAGE_FULL_MESSAGES = listOf(
    "enospc",
    "no space left",
    "not enough space on the disk",
)
