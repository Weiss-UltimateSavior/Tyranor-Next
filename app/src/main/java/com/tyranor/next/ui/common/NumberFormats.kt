package com.tyranor.next.ui.common

import java.util.Locale

/** Stable decimal text for numeric input and settings values; preserves requested precision. */
internal fun formatUiDecimal(value: Double, fractionDigits: Int, trimTrailingZeros: Boolean = false): String {
    val text = String.format(Locale.ROOT, "%.${fractionDigits}f", value)
    return if (trimTrailingZeros && fractionDigits > 0) text.trimEnd('0').trimEnd('.') else text
}
