package com.tyranor.next.ui.save

import com.tyranor.next.core.game.save.sync.SaveVersion
import com.tyranor.next.core.game.save.sync.SaveVersionSource
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

class SaveVersionsPanelTextTest {

    private val dateFormat = SimpleDateFormat("yy.MM.dd HH:mm", Locale.ROOT).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    @Test
    fun restoredVersionOnSameDayOmitsRepeatedDate() {
        assertEquals(
            "26.10.25 21:30" to "22:15",
            saveVersionTimes(
                version(createdAt = millis("26.10.25 22:15"), previousCreatedAt = millis("26.10.25 21:30")),
                dateFormat,
                "unknown",
            ),
        )
    }

    @Test
    fun restoredVersionAcrossDaysKeepsBothDates() {
        assertEquals(
            "26.10.25 23:50" to "26.10.26 00:10",
            saveVersionTimes(
                version(createdAt = millis("26.10.26 00:10"), previousCreatedAt = millis("26.10.25 23:50")),
                dateFormat,
                "unknown",
            ),
        )
    }

    @Test
    fun missingPreviousTimeUsesUnknownLabel() {
        assertEquals(
            "unknown" to "26.10.26 00:10",
            saveVersionTimes(
                version(createdAt = millis("26.10.26 00:10"), previousCreatedAt = null),
                dateFormat,
                "unknown",
            ),
        )
    }

    private fun millis(value: String): Long = dateFormat.parse(value)?.time
        ?: error("Invalid test date: $value")

    private fun version(createdAt: Long, previousCreatedAt: Long?) = SaveVersion(
        id = "version",
        contentHash = "hash",
        createdAt = createdAt,
        size = 1,
        source = SaveVersionSource.LOCAL,
        restored = previousCreatedAt != null,
        previousCreatedAt = previousCreatedAt,
    )
}
