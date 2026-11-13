package com.tyranor.next.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaytimeHoursTest {
    @Test
    fun hoursUseAtMostOneDecimalWithoutTrailingZero() {
        assertEquals("0h", formatPlaytimeHours(0L))
        assertEquals("0h", formatPlaytimeHours(-1L))
        assertEquals("0.1h", formatPlaytimeHours(360_000L))
        assertEquals("1h", formatPlaytimeHours(3_600_000L))
        assertEquals("100h", formatPlaytimeHours(360_000_000L))
    }

    @Test
    fun roundingHandlesBoundariesAndLargeDurations() {
        assertEquals("0h", formatPlaytimeHours(179_999L))
        assertEquals("0.1h", formatPlaytimeHours(180_000L))
        assertEquals("1h", formatPlaytimeHours(3_420_000L))
        assertEquals("2562047788015.2h", formatPlaytimeHours(Long.MAX_VALUE))
    }
}
