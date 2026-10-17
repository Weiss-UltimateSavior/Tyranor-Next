package com.tyranor.next.core.settings

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ImmutableFontImportTest {
    @get:Rule val temporary = TemporaryFolder()
    private val font = byteArrayOf(0, 1, 0, 0, 1, 2, 3, 4)

    @Test fun sameDisplayNameNeverOverwritesAnyExistingFont() {
        val dir = temporary.newFolder()
        val old = File(dir, "shared.ttf").apply { writeBytes(font) }
        val first = ImmutableFontImport.import(dir, "shared.ttf", { ByteArrayInputStream(font) })!!
        val second = ImmutableFontImport.import(dir, "shared.ttf", { ByteArrayInputStream(font) })!!
        assertNotEquals(first, second)
        assertNotEquals(old.absolutePath, first)
        assertArrayEquals(font, old.readBytes())
    }

    @Test fun actualCopyBudgetRejectsOversizeWithoutRetainingPartialFiles() {
        val dir = temporary.newFolder()
        val input = object : InputStream() {
            var read = 0L
            override fun read(): Int = if (read++ < ImmutableFontImport.MAX_BYTES + 1) 0 else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (read > ImmutableFontImport.MAX_BYTES) return -1
                val count = minOf(len.toLong(), ImmutableFontImport.MAX_BYTES + 1 - read).toInt()
                b.fill(0, off, off + count)
                read += count
                return count
            }
        }
        try {
            ImmutableFontImport.import(dir, "large.ttf", { input })
            fail("Expected actual size rejection")
        } catch (_: IOException) { }
        assertEquals(0, dir.listFiles()!!.size)
    }

    @Test fun invalidEmptyOrUnparseableFontsDoNotPublishPaths() {
        val dir = temporary.newFolder()
        assertNull(ImmutableFontImport.import(dir, "empty.ttf", { ByteArrayInputStream(byteArrayOf()) }))
        assertNull(ImmutableFontImport.import(dir, "fake.ttf", { ByteArrayInputStream("no font".toByteArray()) }))
        assertNull(ImmutableFontImport.import(dir, "fake.exe", { ByteArrayInputStream(font) }))
        assertNull(ImmutableFontImport.import(dir, "bad.ttf", { ByteArrayInputStream(font) }, validate = { false }))
        assertEquals(0, dir.listFiles()!!.size)
    }

    @Test fun cancellationPropagatesAndRemovesOnlyItsOwnStagingFile() {
        val dir = temporary.newFolder()
        val old = File(dir, "old.ttf").apply { writeBytes(font) }
        var checks = 0
        try {
            ImmutableFontImport.import(dir, "font.ttf", { ByteArrayInputStream(font) }, checkCancelled = {
                if (++checks >= 3) throw CancellationException()
            })
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
        assertEquals(listOf(old), dir.listFiles()!!.toList())
        assertArrayEquals(font, old.readBytes())
    }
}
