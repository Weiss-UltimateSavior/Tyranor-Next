package com.tyranor.next.core.game.save.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun emptyDirectoriesDoNotChangeSaveContentHash() {
        val plain = archiveWithDirectory(null)
        val withDirectory = archiveWithDirectory(byteArrayOf())

        assertEquals(SaveContentHash.archive(plain), SaveContentHash.archive(withDirectory))
    }

    @Test
    fun directoryPayloadIsRejectedEvenWithValidSaveFile() {
        val file = archiveWithDirectory(ByteArray(128 * 1024))

        assertCorrupt(file)
    }

    @Test
    fun centralDirectoryCannotDeclareNonemptyDirectoryAsEmpty() {
        val file = archiveWithDirectory(ByteArray(128 * 1024))
        val bytes = file.readBytes()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val end = bytes.size - 22
        val firstCentral = buffer.getInt(end + 16)
        val directoryCentral = firstCentral + 46 + (buffer.getShort(firstCentral + 28).toInt() and 0xffff)
        assertEquals(0x02014b50, buffer.getInt(directoryCentral))
        buffer.putInt(directoryCentral + 24, 0) // Lie only about the uncompressed directory size.
        file.writeBytes(bytes)
        ZipFile(file).use { assertEquals(0L, it.getEntry("directory/").size) }

        assertCorrupt(file)
    }

    @Test
    fun hiddenLocalDirectoryPayloadIsRejectedBeforeDrainingItsBody() {
        val file = archiveWithDirectory(ByteArray(128 * 1024))
        val bytes = file.readBytes()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val centralStart = buffer.getInt(bytes.size - 22 + 16)
        assertEquals(0x08074b50, buffer.getInt(centralStart - 16))
        // Draining the body would hit this invalid trailing CRC. Rejecting its first
        // byte must produce the policy error instead, without reaching the descriptor.
        buffer.putInt(centralStart - 12, buffer.getInt(centralStart - 12) xor 1)
        file.writeBytes(bytes)
        omitLastCentralRecord(file)
        ZipFile(file).use { assertEquals(1, it.size()) }

        val failure = assertThrows(SaveSyncException::class.java) { SaveContentHash.archive(file) }
        assertEquals(SaveSyncError.CORRUPT_ARCHIVE, failure.code)
        assertNull("Directory payload must be rejected before ZIP CRC validation drains it", failure.cause)
    }

    private fun archiveWithDirectory(payload: ByteArray?): File = temp.newFile().also { file ->
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("save.bin"))
            zip.write("save bytes".toByteArray())
            zip.closeEntry()
            if (payload != null) {
                zip.putNextEntry(ZipEntry("directory/"))
                zip.write(payload)
                zip.closeEntry()
            }
        }
    }

    private fun assertCorrupt(file: File) {
        val failure = assertThrows(SaveSyncException::class.java) { SaveContentHash.archive(file) }
        assertEquals(SaveSyncError.CORRUPT_ARCHIVE, failure.code)
    }

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
