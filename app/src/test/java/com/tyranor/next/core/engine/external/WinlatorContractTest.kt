package com.tyranor.next.core.engine.external

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Winlator 外置启动下发参数映射：空值不下发、box64Preset 走 overrides JSON、save 仅在开启时下发。
 */
class WinlatorContractTest {

    @Test
    fun emptyOptionsProduceNoExtras() {
        assertTrue(WinlatorContract.extras(WinlatorContract.LaunchOptions()).isEmpty())
    }

    @Test
    fun blankStringsAndZeroIdAreSkipped() {
        val extras = WinlatorContract.extras(
            WinlatorContract.LaunchOptions(
                containerId = 0,
                containerName = "   ",
                graphicsDriver = "",
                dxwrapper = " ",
                screenSize = "",
                lcAll = "",
                tz = "",
                box64Preset = "",
                save = false,
            ),
        )

        assertTrue(extras.isEmpty())
    }

    @Test
    fun valuesAreMappedToProtocolKeys() {
        val extras = WinlatorContract.extras(
            WinlatorContract.LaunchOptions(
                containerId = 3,
                containerName = "容器-1",
                graphicsDriver = "turnip,zink",
                dxwrapper = "dxvk",
                screenSize = "1600x900",
                lcAll = "ja_JP.UTF-8",
                tz = "Asia/Tokyo",
            ),
        )

        assertEquals(3, extras[WinlatorContract.EXTRA_CONTAINER_ID])
        assertEquals("容器-1", extras[WinlatorContract.EXTRA_CONTAINER_NAME])
        assertEquals("turnip,zink", extras[WinlatorContract.EXTRA_GRAPHICS_DRIVER])
        assertEquals("dxvk", extras[WinlatorContract.EXTRA_DXWRAPPER])
        assertEquals("1600x900", extras[WinlatorContract.EXTRA_SCREEN_SIZE])
        assertEquals("ja_JP.UTF-8", extras[WinlatorContract.EXTRA_LC_ALL])
        assertEquals("Asia/Tokyo", extras[WinlatorContract.EXTRA_TZ])
        assertFalse(extras.containsKey(WinlatorContract.EXTRA_SAVE))
    }

    @Test
    fun box64PresetIsSentThroughOverridesJson() {
        val extras = WinlatorContract.extras(
            WinlatorContract.LaunchOptions(box64Preset = "PERFORMANCE"),
        )

        assertEquals(
            """{"${WinlatorContract.OVERRIDE_BOX64_PRESET}":"PERFORMANCE"}""",
            extras[WinlatorContract.EXTRA_OVERRIDES],
        )
    }

    @Test
    fun saveIsSentOnlyWhenEnabled() {
        assertEquals(true, WinlatorContract.extras(WinlatorContract.LaunchOptions(save = true))[WinlatorContract.EXTRA_SAVE])
        assertFalse(WinlatorContract.extras(WinlatorContract.LaunchOptions(save = false)).containsKey(WinlatorContract.EXTRA_SAVE))
    }
}
