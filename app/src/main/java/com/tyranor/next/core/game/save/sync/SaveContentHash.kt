package com.tyranor.next.core.game.save.sync

import com.tyranor.next.core.game.save.SavePathSafety
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.zip.ZipFile
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/** A save's identity includes relative paths and bytes, never ZIP order or modification times. */
object SaveContentHash {
    const val MAX_BYTES = 1024L * 1024 * 1024
    const val MAX_FILES = 20_000
    private data class Entry(val name: String, val size: Long, val hash: ByteArray)

    fun text(value: String): String = hex(digest().digest(value.toByteArray(Charsets.UTF_8)))

    fun files(root: File, files: List<File>, check: () -> Unit = {}): String? {
        if (files.isEmpty()) return null
        if (files.size > MAX_FILES) throw SaveSyncException(SaveSyncError.CORRUPT_ARCHIVE)
        val safeRoot = SavePathSafety.requireSafeRoot(root)
        val rootPath = safeRoot.toPath()
        var total = 0L
        val entries = files.map { file ->
            check()
            val safeFile = SavePathSafety.requireSafeFile(safeRoot, file)
            val path = safeFile.toPath()
            val name = validName(rootPath.relativize(path).toString().replace(File.separatorChar, '/'))
            val entry = safeFile.inputStream().buffered().use { read(name, it, check) }
            total += entry.size
            if (total > MAX_BYTES) throw SaveSyncException(SaveSyncError.CORRUPT_ARCHIVE)
            entry
        }
        return combine(entries)
    }

    fun archive(file: File, check: () -> Unit = {}): String {
        if (file.length() > MAX_BYTES) throw SaveSyncException(SaveSyncError.CORRUPT_ARCHIVE)
        try {
            ZipFile(file).use { zip ->
                val names = HashSet<String>()
                val entries = ArrayList<Entry>()
                var total = 0L
                val all = zip.entries()
                while (all.hasMoreElements()) {
                    check()
                    val entry = all.nextElement()
                    val name = validName(if (entry.isDirectory) entry.name.removeSuffix("/") else entry.name)
                    if (!names.add(name) || names.size > MAX_FILES) {
                        throw SaveSyncException(SaveSyncError.CORRUPT_ARCHIVE)
                    }
                    if (entry.isDirectory) continue
                    val content = zip.getInputStream(entry).buffered().use { read(name, it, check) }
                    if (entry.size != content.size) throw SaveSyncException(SaveSyncError.CORRUPT_ARCHIVE)
                    total += content.size
                    if (total > MAX_BYTES) throw SaveSyncException(SaveSyncError.CORRUPT_ARCHIVE)
                    entries += content
                }
                if (entries.isEmpty()) throw SaveSyncException(SaveSyncError.CORRUPT_ARCHIVE)
                val expected = combine(entries)
                // Import reads local ZIP records. Reject extra/omitted/different records rather
                // than trusting a central directory that describes a different set of save bytes.
                val localNames = HashSet<String>()
                val localEntries = ArrayList<Entry>()
                var localTotal = 0L
                ZipInputStream(file.inputStream().buffered()).use { local ->
                    while (true) {
                        check()
                        val entry = local.nextEntry ?: break
                        val name = validName(if (entry.isDirectory) entry.name.removeSuffix("/") else entry.name)
                        if (!localNames.add(name) || localNames.size > MAX_FILES) {
                            throw SaveSyncException(SaveSyncError.CORRUPT_ARCHIVE)
                        }
                        if (!entry.isDirectory) {
                            val content = read(name, local, check)
                            localTotal += content.size
                            if (localTotal > MAX_BYTES) throw SaveSyncException(SaveSyncError.CORRUPT_ARCHIVE)
                            localEntries += content
                        }
                        local.closeEntry()
                    }
                }
                if (localNames != names || combine(localEntries) != expected) {
                    throw SaveSyncException(SaveSyncError.CORRUPT_ARCHIVE)
                }
                return expected
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: SaveSyncException) {
            throw failure
        } catch (failure: ZipException) {
            throw SaveSyncException(SaveSyncError.CORRUPT_ARCHIVE, cause = failure)
        } catch (failure: EOFException) {
            throw SaveSyncException(SaveSyncError.CORRUPT_ARCHIVE, cause = failure)
        } catch (failure: IOException) {
            throw SaveSyncException(SaveSyncError.LOCAL_IO, cause = failure)
        }
    }

    private fun read(name: String, input: InputStream, check: () -> Unit): Entry {
        val hash = digest()
        val buffer = ByteArray(64 * 1024)
        var size = 0L
        while (true) {
            check()
            val count = input.read(buffer)
            if (count < 0) break
            size += count
            if (size > MAX_BYTES) throw SaveSyncException(SaveSyncError.CORRUPT_ARCHIVE)
            hash.update(buffer, 0, count)
        }
        return Entry(name, size, hash.digest())
    }

    private fun combine(entries: List<Entry>): String {
        val hash = digest()
        hash.update("TYN-save-v1\u0000".toByteArray(Charsets.UTF_8))
        entries.sortedBy { it.name }.forEach { entry ->
            val name = entry.name.toByteArray(Charsets.UTF_8)
            hash.update(ByteBuffer.allocate(4).putInt(name.size).array())
            hash.update(name)
            hash.update(ByteBuffer.allocate(8).putLong(entry.size).array())
            hash.update(entry.hash)
        }
        return hex(hash.digest())
    }

    private fun validName(name: String): String {
        if (name.isBlank() || name.startsWith('/') || '\\' in name || ':' in name ||
            name.any { it.code < 32 } || name.split('/').any { it.isEmpty() || it == "." || it == ".." }
        ) throw SaveSyncException(SaveSyncError.CORRUPT_ARCHIVE)
        return name
    }

    private fun digest() = MessageDigest.getInstance("SHA-256")
    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
