package com.tyranor.next.ui.home

/** Round to tenths of an hour without floating-point error or overflowing a millisecond value. */
internal fun formatPlaytimeHours(millis: Long): String {
    val positive = millis.coerceAtLeast(0L)
    val tenths = positive / 360_000L + if (positive % 360_000L >= 180_000L) 1L else 0L
    val hours = tenths / 10L
    val fraction = tenths % 10L
    return if (fraction == 0L) "${hours}h" else "$hours.${fraction}h"
}
