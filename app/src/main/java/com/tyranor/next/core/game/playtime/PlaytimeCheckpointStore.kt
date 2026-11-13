package com.tyranor.next.core.game.playtime

import android.util.AtomicFile
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock

/** Small process-safe journal. Checkpoints are immutable after `closed=true`, so main can archive them. */
internal class PlaytimeCheckpointStore(directory: File) {
    private val root = File(directory, "game_playtime")

    fun write(snapshot: PlaytimeRecordSnapshot) = withJournalLock {
        check(snapshot.recordId.matches(RECORD_ID_PATTERN)) { "Invalid playtime record id" }
        if (!root.isDirectory && !root.mkdirs() && !root.isDirectory) error("Cannot create playtime journal")
        val atomicFile = AtomicFile(File(root, "${snapshot.recordId}.gpt"))
        val stream = atomicFile.startWrite()
        try {
            val output = DataOutputStream(stream)
            output.writeInt(MAGIC)
            output.writeUTF(snapshot.recordId)
            output.writeUTF(snapshot.sessionId)
            output.writeUTF(snapshot.gameUri)
            output.writeLong(snapshot.durationMillis.coerceAtLeast(0))
            output.writeLong(snapshot.startedAtMillis)
            output.writeBoolean(snapshot.closed)
            output.writeInt(snapshot.processId)
            output.flush()
            stream.fd.sync()
            atomicFile.finishWrite(stream)
        } catch (failure: Throwable) {
            atomicFile.failWrite(stream)
            throw failure
        }
    }

    fun readAll(): List<PlaytimeRecordSnapshot> = withJournalLock {
        root.listFiles { file ->
            file.isFile && (file.name.endsWith(FILE_SUFFIX) || file.name.endsWith(BACKUP_SUFFIX))
        }.orEmpty().mapNotNull { file ->
            val baseName = file.name.removeSuffix(BACKUP_SUFFIX).removeSuffix(FILE_SUFFIX)
            if (!baseName.matches(RECORD_ID_PATTERN)) return@mapNotNull null
            runCatching { read(File(root, "$baseName$FILE_SUFFIX")) }.getOrNull()
        }.distinctBy(PlaytimeRecordSnapshot::recordId)
    }

    /** Only closed snapshots are removed, and only after their DB transaction has committed. */
    fun deleteClosed(recordId: String) = withJournalLock {
        if (recordId.matches(RECORD_ID_PATTERN)) {
            AtomicFile(File(root, "$recordId$FILE_SUFFIX")).delete()
        }
    }

    private inline fun <T> withJournalLock(block: () -> T): T = synchronized(JOURNAL_LOCK_MONITOR) {
        if (!root.isDirectory && !root.mkdirs() && !root.isDirectory) error("Cannot create playtime journal")
        RandomAccessFile(File(root, LOCK_FILE), "rw").channel.use { channel ->
            channel.lock().use { _: FileLock -> block() }
        }
    }

    private fun read(file: File): PlaytimeRecordSnapshot {
        val atomicFile = AtomicFile(file)
        DataInputStream(atomicFile.openRead()).use { input ->
            require(input.readInt() == MAGIC)
            val record = PlaytimeRecordSnapshot(
                recordId = input.readUTF(),
                sessionId = input.readUTF(),
                gameUri = input.readUTF(),
                durationMillis = input.readLong().coerceAtLeast(0),
                startedAtMillis = input.readLong(),
                closed = input.readBoolean(),
                processId = input.readInt(),
            )
            require(record.recordId == file.name.removeSuffix(FILE_SUFFIX))
            require(record.recordId.matches(RECORD_ID_PATTERN))
            require(record.sessionId.isNotBlank() && record.gameUri.isNotBlank())
            return record
        }
    }

    private companion object {
        const val MAGIC = 0x54595054 // TYPT
        const val FILE_SUFFIX = ".gpt"
        const val BACKUP_SUFFIX = ".gpt.bak"
        const val LOCK_FILE = ".journal.lock"
        val JOURNAL_LOCK_MONITOR = Any()
        val RECORD_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,160}")
    }
}
