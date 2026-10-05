package com.core.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 手柄映射 JSON 往返、默认预设与坏数据回退。 */
class GamepadMapTest {

    @Test
    fun jsonRoundTrip() {
        val map = GamepadMap.default()
            .withBinding(GamepadButtons.X, GamepadBinding(keys = listOf(113, 62), autoKeep = true))
            .copy(
                leftStick = StickBinding(
                    up = listOf(38), down = listOf(40), left = listOf(37), right = listOf(39),
                    deadzone = 0.42f, hysteresis = 0.1f,
                ),
            )
        val parsed = GamepadMap.parse(map.toJson())
        assertNotNull(parsed)
        assertEquals(listOf(113, 62), parsed!!.binding(GamepadButtons.X).keys)
        assertEquals(true, parsed.binding(GamepadButtons.X).autoKeep)
        assertEquals(listOf(38), parsed.leftStick.up)
        assertEquals(0.42f, parsed.leftStick.deadzone, 0.0001f)
        assertEquals(0.1f, parsed.leftStick.hysteresis, 0.0001f)
    }

    @Test
    fun defaultMapCoversAllLogicalButtons() {
        val map = GamepadMap.default()
        GamepadButtons.ALL.forEach { id ->
            assertTrue("missing default binding: $id", map.binding(id).keys.isNotEmpty())
        }
        assertEquals(4, listOf(map.leftStick.up, map.leftStick.down, map.leftStick.left, map.leftStick.right).count { it.isNotEmpty() })
    }

    @Test
    fun defaultMapUsesEnterPlusSpaceForConfirm() {
        val map = GamepadMap.default()
        // A 键输出 Enter + Space：MV/MZ 的确定 + Tyrano 的推进都覆盖
        assertEquals(listOf(66, 62), map.binding(GamepadButtons.A).keys)
    }

    @Test
    fun badDataFallsBackToNull() {
        assertNull(GamepadMap.parse("not-json"))
        assertNull(GamepadMap.parse(null))
        assertNull(GamepadMap.parse("[1,2,3]"))
    }

    @Test
    fun unknownLogicalButtonsAreDropped() {
        val raw = """
            {"schema":1,"buttons":{"A":{"keys":[66]},"NOT_A_BUTTON":{"keys":[1]},"B":{"keys":[]}}}
        """.trimIndent()
        val parsed = GamepadMap.parse(raw)
        assertNotNull(parsed)
        assertEquals(2, parsed!!.buttons.size)
        assertTrue(GamepadButtons.A in parsed.buttons)
        assertTrue(GamepadButtons.B in parsed.buttons)
        assertTrue(parsed.binding(GamepadButtons.B).keys.isEmpty())
    }

    @Test
    fun deadzoneIsClamped() {
        val raw = """{"schema":1,"buttons":{},"sticks":{"left":{"up":[38],"deadzone":5.0}}}"""
        val parsed = GamepadMap.parse(raw)!!
        assertEquals(StickBinding.MAX_DEADZONE, parsed.leftStick.deadzone, 0.0001f)
    }
}
