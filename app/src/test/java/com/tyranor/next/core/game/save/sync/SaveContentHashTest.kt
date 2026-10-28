package com.tyranor.next.core.game.save.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class SaveContentHashTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun centralDirectoryCannotHideAdditionalLocalSaveEntry() {
        val file = temp.newFile("inconsistent.zip")
        ZipOutputStream(file.outputStream()).use { zip ->
            for (name in listOf("visible.bin", "hidden.bin")) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(name.toByteArray())
                zip.closeEntry()
            }
        }
        omitLastCentralRecord(file)
        ZipFile(file).use { assertEquals(listOf("visible.bin"), it.entries().asSequence().map { entry -> entry.name }.toList()) }
        ZipInputStream(file.inputStream()).use { input ->
            val names = generateSequence { input.nextEntry?.name }.toList()
            assertEquals(listOf("visible.bin", "hidden.bin"), names)
        }

        val failure = assertThrows(SaveSyncException::class.java) { SaveContentHash.archive(file) }
        assertEquals(SaveSyncError.CORRUPT_ARCHIVE, failure.code)
    }

    private fun omitLastCentralRecord(file: File) {
        val bytes = file.readBytes()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val end = bytes.size - 22 // Fixture has no ZIP comment.
        assertEquals(0x06054b50, buffer.getInt(end))
        val centralStart = buffer.getInt(end + 16)
        val centralSize = buffer.getInt(end + 12)
        val firstLength = 46 + (buffer.getShort(centralStart + 28).toInt() and 0xffff) +
            (buffer.getShort(centralStart + 30).toInt() and 0xffff) +
            (buffer.getShort(centralStart + 32).toInt() and 0xffff)
        val removed = centralSize - firstLength
        val altered = bytes.copyOfRange(0, centralStart + firstLength) + bytes.copyOfRange(end, bytes.size)
        val updated = ByteBuffer.wrap(altered).order(ByteOrder.LITTLE_ENDIAN)
        val newEnd = end - removed
        updated.putShort(newEnd + 8, 1.toShort())
        updated.putShort(newEnd + 10, 1.toShort())
        updated.putInt(newEnd + 12, firstLength)
        file.writeBytes(altered)
    }
}
