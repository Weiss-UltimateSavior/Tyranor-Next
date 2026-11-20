package com.tyranor.next.core.game.scan

import com.tyranor.next.core.engine.EngineType
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException

internal object SeenArchiveProbe {
    private const val TABLE_BYTES = 10_000 * 8
    private const val PACL_MAGIC = 0x4c434150
    private data class Scene(val offset: Long, val size: Long)

    fun detect(size: Long?, open: () -> InputStream?): EngineType? = try {
        open()?.use { classify(DataInputStream(it), size) }
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }

    private fun classify(input: DataInputStream, size: Long?): EngineType? {
        val table = ByteArray(TABLE_BYTES)
        input.readFully(table, 0, 4)
        val index = ByteBuffer.wrap(table).order(ByteOrder.LITTLE_ENDIAN)
        if (index.getInt(0) == PACL_MAGIC) return EngineType.AVG32
        if (size != null && size < TABLE_BYTES) return null
        input.readFully(table, 4, TABLE_BYTES - 4)

        val scenes = buildList {
            while (index.hasRemaining()) {
                val offset = Integer.toUnsignedLong(index.getInt())
                val length = Integer.toUnsignedLong(index.getInt())
                if (offset >= TABLE_BYTES && length >= 0x1cc && (size == null || offset + length <= size)) {
                    add(Scene(offset, length))
                }
            }
        }.sortedBy { it.offset }

        // Reading in offset order lets SAF providers serve the probe through a forward-only stream.
        var position = TABLE_BYTES.toLong()
        for (scene in scenes) {
            if (scene.offset < position) continue
            if (!skipTo(input, scene.offset - position)) return null
            val headerSize = Integer.reverseBytes(input.readInt())
            position = scene.offset + 4
            if (headerSize != 0x1d0 && headerSize != 0x1cc || scene.size < headerSize) continue
            if (size == null && (!skipTo(input, scene.size - 5) || input.read() == -1)) return null
            return EngineType.REALLIVE
        }
        return null
    }

    private fun skipTo(input: InputStream, count: Long): Boolean {
        var remaining = count
        while (remaining > 0) {
            if (Thread.currentThread().isInterrupted) throw CancellationException()
            val skipped = input.skip(remaining)
            if (skipped > 0) remaining -= skipped
            else if (input.read() == -1) return false
            else remaining--
        }
        return true
    }
}
