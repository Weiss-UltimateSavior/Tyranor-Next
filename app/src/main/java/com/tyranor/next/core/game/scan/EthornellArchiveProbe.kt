package com.tyranor.next.core.game.scan

import java.io.InputStream

/** Conservative, bounded index probe. No extraction or archive payload decoding occurs during scans. */
internal object EthornellArchiveProbe {
    const val MAX_BYTES = 64 * 1024

    /** InputStream.read may return fewer bytes than requested, including on document providers. */
    fun readHead(input: InputStream, maxBytes: Int = MAX_BYTES): ByteArray {
        require(maxBytes in 0..MAX_BYTES)
        val bytes = ByteArray(maxBytes)
        var used = 0
        while (used < bytes.size) {
            if (Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException()
            val count = input.read(bytes, used, bytes.size - used)
            if (count < 0) break
            if (count == 0) {
                val byte = input.read()
                if (byte < 0) break
                bytes[used++] = byte.toByte()
            } else used += count
        }
        return bytes.copyOf(used)
    }

    /** Requires a complete bounded index and an exact ASCII bootstrap name, not a substring. */
    fun hasBootstrap(head: ByteArray?, archiveBytes: Long? = null): Boolean {
        if (head == null || head.size < 16) return false
        // A bounded reader returning fewer than MAX_BYTES reached EOF, even if the provider
        // omitted COLUMN_SIZE. Use that known physical bound instead of accepting a cut payload.
        val knownSize = if (head.size < MAX_BYTES) {
            minOf(archiveBytes ?: head.size.toLong(), head.size.toLong())
        } else archiveBytes
        val (entrySize, nameSize) = when {
            head.copyOfRange(0, 12).contentEquals("PackFile    ".toByteArray(Charsets.US_ASCII)) -> 32 to 16
            head.copyOfRange(0, 12).contentEquals("BURIKO ARC20".toByteArray(Charsets.US_ASCII)) -> 128 to 96
            else -> return false
        }
        val count = u32(head, 12)
        if (count == 0L || count > (MAX_BYTES - 16) / entrySize) return false
        val end = 16L + count * entrySize
        if (end > head.size || (knownSize != null && end > knownSize)) return false
        var found = false
        repeat(count.toInt()) { index ->
            val at = 16 + index * entrySize
            val nameEnd = (at until at + nameSize).firstOrNull { head[it] == 0.toByte() } ?: at + nameSize
            val name = String(head, at, nameEnd - at, Charsets.US_ASCII)
            val offset = end + u32(head, at + nameSize)
            val size = u32(head, at + nameSize + 4)
            if (knownSize != null && offset + size > knownSize) return false
            if (name.equals("ipl._bp", ignoreCase = true) && size > 0) found = true
        }
        return found
    }

    private fun u32(bytes: ByteArray, at: Int): Long =
        (0..3).fold(0L) { value, i -> value or ((bytes[at + i].toLong() and 255) shl (8 * i)) }
}
