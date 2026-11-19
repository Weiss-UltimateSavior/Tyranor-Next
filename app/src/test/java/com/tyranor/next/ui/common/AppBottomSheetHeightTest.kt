package com.tyranor.next.ui.common

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppBottomSheetHeightTest {
    @Test
    fun shortWindowsKeepMostOfTheirAvailableHeight() {
        for (landscape in listOf(false, true)) {
            assertEquals(126.dp, appBottomSheetMaxHeight(150, landscape))
            assertEquals(156.dp, appBottomSheetMaxHeight(180, landscape))
            assertEquals(320.dp, appBottomSheetMaxHeight(360, landscape))
        }
    }

    @Test
    fun normalWindowsKeepExistingHeightPolicy() {
        assertEquals(360.dp, appBottomSheetMaxHeight(480, false))
        assertEquals(384.dp, appBottomSheetMaxHeight(480, true))
        assertEquals(560.dp, appBottomSheetMaxHeight(800, false))
        assertEquals(560.dp, appBottomSheetMaxHeight(800, true))
    }

    @Test
    fun maximumAlwaysFitsEvenAnAlmostCollapsedWindow() {
        for (height in listOf(0, 12, 24, 48, 100, 150, 180, 270, 360, 480, 800)) {
            for (landscape in listOf(false, true)) {
                val maximum = appBottomSheetMaxHeight(height, landscape)
                assertTrue(maximum >= 0.dp && maximum <= height.dp)
            }
        }
    }
}
