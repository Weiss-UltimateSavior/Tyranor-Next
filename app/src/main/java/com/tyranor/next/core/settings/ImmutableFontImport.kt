package com.tyranor.next.core.settings

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.Locale

/** Bounded, write-once font import shared by private and external font stores. */
internal object ImmutableFontImport {
    const val MAX_BYTES = 64L * 1024 * 1024
    private val EXTENSIONS = setOf("ttf", "ttc", "otf", "otc")

    fun import(
        directory: File,
        displayName: String,
        input: () -> InputStream?,
        checkCancelled: () -> Unit = {
            if (Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException()
        },
        validate: (File) -> Boolean = { true },
    ): String? {
        val extension = displayName.substringAfterLast('.').lowercase(Locale.ROOT)
        if (extension !in EXTENSIONS) return null
        checkCancelled()
        if (!directory.isDirectory && !directory.mkdirs()) return null
        // Never open a provider-controlled name: each unreferenced staging file is exclusively ours.
        val target = File.createTempFile("font-", ".$extension", directory)
        var complete = false
        try {
            input()?.use { source ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(16 * 1024)
                    var total = 0L
                    while (true) {
                        checkCancelled()
                        val read = source.read(buffer, 0, minOf(buffer.size.toLong(), MAX_BYTES - total + 1).toInt())
                        checkCancelled()
                        if (read < 0) break
                        if (read == 0) {
                            val byte = source.read()
                            checkCancelled()
                            if (byte < 0) break
                            if (++total > MAX_BYTES) throw IOException("font size limit")
                            output.write(byte)
                        } else {
                            total += read
                            if (total > MAX_BYTES) throw IOException("font size limit")
                            output.write(buffer, 0, read)
                        }
                    }
                }
            } ?: return null
            checkCancelled()
            val signature = target.inputStream().use { source ->
                val bytes = ByteArray(4)
                if (source.read(bytes) != bytes.size) return null
                bytes
            }
            if (!signature.contentEquals(byteArrayOf(0, 1, 0, 0)) &&
                String(signature, Charsets.US_ASCII) !in setOf("OTTO", "ttcf", "true", "typ1")) return null
            if (!validate(target)) return null
            checkCancelled()
            complete = true
            return target.absolutePath
        } finally {
            if (!complete) target.delete()
        }
    }
}
