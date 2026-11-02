package com.tyranor.next.core.game.save.sync

import com.tyranor.next.core.game.save.SavePathSafety
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** A lightweight, content-stable identity for already enumerated save files. */
internal object SaveSyncMetadataFingerprint {
    data class Entry(
        val relativePath: String,
        val size: Long,
        val modifiedAtNanos: Long,
    )

    fun fingerprint(root: File, files: List<File>, check: () -> Unit = {}): String {
        if (files.size > SaveContentHash.MAX_FILES) {
            throw SaveSyncException(SaveSyncError.CORRUPT_ARCHIVE)
        }
        val safeRoot = SavePathSafety.requireSafeRoot(root)
        val rootPath = safeRoot.toPath()
        val names = HashSet<String>(files.size)
        val entries = ArrayList<Entry>(files.size)
        for (file in files) {
            check()
            val safeFile = SavePathSafety.requireSafeFile(safeRoot, file)
            val path = safeFile.toPath()
            // Deliberately do not catch IOException: a vanished or unreadable file must
            // invalidate the cache instead of silently producing a reusable signature.
            val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, java.nio.file.LinkOption.NOFOLLOW_LINKS)
            if (!attributes.isRegularFile) {
                throw SaveSyncException(SaveSyncError.CORRUPT_ARCHIVE)
            }
            val relativePath = rootPath.relativize(path)
                .toString()
                .replace(File.separatorChar, '/')
            if (!names.add(relativePath)) {
                throw SaveSyncException(SaveSyncError.CORRUPT_ARCHIVE)
            }
            entries += Entry(
                relativePath = relativePath,
                size = attributes.size(),
                modifiedAtNanos = attributes.lastModifiedTime().to(TimeUnit.NANOSECONDS),
            )
            check()
        }
        return fingerprint(entries)
    }

    fun fingerprint(entries: List<Entry>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("TYN-save-metadata-v1\u0000".toByteArray(Charsets.UTF_8))
        entries.sortedBy { it.relativePath }.forEach { entry ->
            val path = entry.relativePath.toByteArray(Charsets.UTF_8)
            digest.update(ByteBuffer.allocate(4).putInt(path.size).array())
            digest.update(path)
            digest.update(ByteBuffer.allocate(16)
                .putLong(entry.size)
                .putLong(entry.modifiedAtNanos)
                .array())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
