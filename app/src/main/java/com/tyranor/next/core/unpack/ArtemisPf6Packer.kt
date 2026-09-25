package com.tyranor.next.core.unpack

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Artemis PF6 packer, the write counterpart of [ArtemisPfsUnpacker]'s table format.
 *
 * PF6 only (magic `pf6`, all entries plaintext — measured: real PF6 packs are fully
 * plaintext). PF8 and XP3 go through the Rust cores ([PfsArchive]/[Xp3Archive]);
 * the Rust PF8 writer cannot emit PF6, hence this small dedicated writer for legacy
 * PF6 titles whose engines predate PF8.
 *
 * Binary layout (byte-verified against a real PF6 game archive):
 * ```
 * magic 'pf6' (3 bytes)
 * index_size u32 LE  (header bytes starting at 0x07)
 * index_count u32 LE
 * per entry: nameLen u32, name bytes (UTF-8, backslash-separated),
 *            4 zero bytes, offset u32 LE (absolute), size u32 LE
 * filesize_count u32 (= index_count + 1)
 * index_count x u64 LE filesize offsets (each entry's size-field position minus 0x0F)
 * 8 zero bytes (end marker)
 * filesize_count_offset u32 LE (filesize_count field position minus 0x07)
 * file data concatenated (data starts at 0x07 + index_size)
 * ```
 */
object ArtemisPf6Packer {
    private const val U32_MAX = 0xFFFFFFFFL
    private const val IO_CHUNK = 64 * 1024
    private val MAGIC = byteArrayOf(0x70, 0x66, 0x36) // 'p','f','6'

    data class PackStats(
        /** Number of archived entries. */
        val entryCount: Int,
        /** Sum of source data bytes (excludes the archive header). */
        val bytesWritten: Long,
    )

    private data class SourceFile(val file: File, val rel: String)

    private data class PlannedEntry(
        val file: File,
        val archiveName: String,
        val nameBytes: ByteArray,
        val size: Long,
        val dataOffset: Long,
        /** Absolute position of this entry's size field; trailer stores it minus 0x0F. */
        val sizeFieldPos: Long,
    )

    /**
     * Packs [source] (directory tree, or a single file) into a PF6 archive at [output].
     *
     * Archive entry names use backslash separators. [onProgress] reports cumulative data
     * bytes; its [entryName] is empty on the final completion call. [isCancelled] is
     * polled per entry and per IO chunk. A cancelled or failed run deletes the partial
     * output and throws ([ArchiveCancelledException] on cancel).
     */
    fun pack(
        source: File,
        output: File,
        onProgress: ((writtenBytes: Long, totalBytes: Long, entryName: String) -> Unit)? = null,
        isCancelled: () -> Boolean = { false },
    ): PackStats {
        val files = collectFiles(source)
        if (files.isEmpty()) throw IOException("PFS pack: no files to archive in ${source.path}")
        // Fail fast on duplicate archive names (e.g. `a\b` file vs `a/b` path collision).
        val seen = HashSet<String>(files.size * 2)
        for ((_, rel) in files) {
            if (!seen.add(toArchiveName(rel))) throw IOException("PFS pack: duplicate entry name: $rel")
        }
        val totalBytes = files.sumOf { it.file.length() }
        val parent = output.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw IOException("PFS pack: cannot create output directory: ${parent.path}")
        }
        try {
            BufferedOutputStream(FileOutputStream(output), IO_CHUNK).use { out ->
                if (!writeArchive(files, totalBytes, out, onProgress, isCancelled)) {
                    throw ArchiveCancelledException(output.path)
                }
            }
        } catch (error: Throwable) {
            runCatching { output.delete() }
            if (output.exists()) runCatching { output.deleteOnExit() }
            throw error
        }
        return PackStats(files.size, totalBytes)
    }

    /** Returns false when cancelled (partial output already deleted by [pack]). */
    private fun writeArchive(
        files: List<SourceFile>,
        totalBytes: Long,
        out: BufferedOutputStream,
        onProgress: ((Long, Long, String) -> Unit)?,
        isCancelled: () -> Boolean,
    ): Boolean {
        val count = files.size
        // Header planning pass: names, absolute data offsets, size-field positions.
        var headerPos = 11L // magic(3) + index_size(4) + count(4)
        val tableBodySize = 4L + files.sumOf { (_, rel) ->
            // Per entry: nameLen(4) + name + reserved(4) + offset(4) + size(4).
            4L + toArchiveName(rel).toByteArray(Charsets.UTF_8).size + 4L + 4L + 4L
        } + 4L + (count + 1L) * 8L + 4L
        if (tableBodySize > U32_MAX) throw IOException("PFS pack: index too large")
        // index_size covers the index region [0x07, ...), i.e. count(4) onward, not itself.
        val indexSize = tableBodySize
        var dataOffset = ArtemisPfsFormat.HEADER_BASE + indexSize // 7 = magic(3) + index_size(4)
        val entries = files.map { (file, rel) ->
            val archiveName = toArchiveName(rel)
            val nameBytes = archiveName.toByteArray(Charsets.UTF_8)
            if (nameBytes.isEmpty() || nameBytes.size > ArtemisPfsFormat.MAX_NAME_BYTES) {
                throw IOException("PFS pack: bad entry name length ${nameBytes.size}: $archiveName")
            }
            val size = file.length()
            if (size > U32_MAX) throw IOException("PFS pack: file too large: $archiveName")
            if (dataOffset + size > U32_MAX) {
                throw IOException("PFS pack: archive exceeds 4GB at $archiveName")
            }
            val entry = PlannedEntry(
                file = file,
                archiveName = archiveName,
                nameBytes = nameBytes,
                size = size,
                dataOffset = dataOffset,
                sizeFieldPos = headerPos + 4L + nameBytes.size + 4L + 4L,
            )
            headerPos += 4L + nameBytes.size + 4L + 4L + 4L
            dataOffset += size
            entry
        }

        fun emitU32(value: Long) {
            out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(ArtemisPfsFormat.checkU32(value, "header field")).array())
        }
        fun emitU64(value: Long) {
            out.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array())
        }

        out.write(MAGIC)
        out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(ArtemisPfsFormat.checkU32(indexSize, "index_size")).array())
        emitU32(count.toLong())
        for (entry in entries) {
            emitU32(entry.nameBytes.size.toLong())
            out.write(entry.nameBytes)
            out.write(ByteArray(4)) // reserved
            emitU32(entry.dataOffset)
            emitU32(entry.size)
        }
        val filesizeCountPos = headerPos
        emitU32((count + 1).toLong())
        for (entry in entries) {
            emitU64(entry.sizeFieldPos - ArtemisPfsFormat.TRAILER_BASE)
        }
        out.write(ByteArray(8)) // end marker
        emitU32(filesizeCountPos - ArtemisPfsFormat.HEADER_BASE)

        // Data streaming pass (PF6: always plaintext).
        val chunk = ByteArray(IO_CHUNK)
        var written = 0L
        for (entry in entries) {
            if (isCancelled()) return false
            onProgress?.invoke(written, totalBytes, entry.archiveName)
            var entryPos = 0L
            BufferedInputStream(FileInputStream(entry.file), IO_CHUNK).use { input ->
                while (entryPos < entry.size) {
                    if (isCancelled()) return false
                    val want = minOf(chunk.size.toLong(), entry.size - entryPos).toInt()
                    var got = 0
                    while (got < want) {
                        val n = input.read(chunk, got, want - got)
                        if (n < 0) throw IOException("PFS pack: truncated read: ${entry.archiveName}")
                        got += n
                    }
                    out.write(chunk, 0, got)
                    entryPos += got
                    written += got
                }
            }
            if (entry.file.length() != entry.size) {
                throw IOException("PFS pack: source changed during pack: ${entry.archiveName}")
            }
        }
        onProgress?.invoke(written, totalBytes, "")
        out.flush()
        return !isCancelled()
    }

    private fun collectFiles(source: File): List<SourceFile> {
        if (!source.exists()) throw IOException("PFS pack: source missing: ${source.path}")
        if (source.isFile) {
            if (source.name.isBlank()) throw IOException("PFS pack: bad file name: ${source.path}")
            return listOf(SourceFile(source, source.name))
        }
        val root = try {
            source.canonicalFile
        } catch (error: IOException) {
            throw IOException("PFS pack: cannot resolve source: ${source.path}")
        }
        val out = mutableListOf<SourceFile>()
        // Ancestor canonical paths along the current DFS path: a revisit means a
        // symlink cycle (a global visited set would false-positive on DAG links
        // that merely point at an already-packed sibling subtree).
        val stack = ArrayDeque<Triple<File, String, Set<String>>>()
        stack.add(Triple(root, "", setOf(root.canonicalPath)))
        while (stack.isNotEmpty()) {
            val (dir, rel, ancestors) = stack.removeLast()
            // Fail closed on unreadable subtrees instead of shipping an incomplete pack.
            val children = dir.listFiles()
                ?: throw IOException("PFS pack: cannot list directory: ${dir.path}")
            children.sortedBy { it.name }.forEach { child ->
                val childRel = if (rel.isEmpty()) child.name else "$rel/${child.name}"
                if (child.isDirectory) {
                    val canonical = try {
                        child.canonicalPath
                    } catch (error: IOException) {
                        throw IOException("PFS pack: cannot resolve directory: ${child.path}")
                    }
                    if (canonical in ancestors) {
                        throw IOException("PFS pack: cyclic directory link: ${child.path}")
                    }
                    stack.add(Triple(child, childRel, ancestors + canonical))
                } else if (child.isFile) {
                    out.add(SourceFile(child, childRel))
                }
            }
        }
        // Byte order (case-sensitive) matches the on-disk backslash form used by readers.
        return out.sortedBy { it.rel }
    }

    private fun toArchiveName(rel: String): String {
        if (rel.isBlank()) throw IOException("PFS pack: empty entry name")
        val parts = rel.replace('\\', '/').split('/')
        if (parts.any { it.isEmpty() || it == "." || it == ".." }) {
            throw IOException("PFS pack: unsafe entry name: $rel")
        }
        if (rel.contains('\u0000')) throw IOException("PFS pack: bad entry name: $rel")
        return parts.joinToString("\\")
    }
}
