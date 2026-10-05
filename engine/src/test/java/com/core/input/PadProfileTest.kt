package com.core.input

import android.view.KeyEvent
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 方案 JSON 往返与旧 `__touch_pad.js` 配置迁移。 */
class PadProfileTest {

    @Test
    fun jsonRoundTripKeepsEverything() {
        val profile = PadProfile.defaultProfile("custom", "我的布局")
            .withButton(
                PadButton(
                    id = "combo", text = "连击", x = 0.42f, y = 0.66f, size = 0.14f,
                    aspect = 0.5f, shape = PadButton.SHAPE_SQUARE, visible = false,
                    keys = listOf(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_A), autoKeep = true,
                ),
            )
        val parsed = PadProfile.parse(profile.toJson())
        assertNotNull(parsed)
        assertEquals("custom", parsed!!.id)
        assertEquals("我的布局", parsed.name)
        assertEquals(profile.buttons.size, parsed.buttons.size)
        val combo = parsed.buttons.first { it.id == "combo" }
        assertEquals("连击", combo.text)
        assertEquals(0.42f, combo.x, 0.0001f)
        assertEquals(0.66f, combo.y, 0.0001f)
        assertEquals(0.14f, combo.size, 0.0001f)
        assertEquals(0.5f, combo.aspect, 0.0001f)
        assertEquals(PadButton.SHAPE_SQUARE, combo.shape)
        assertEquals(false, combo.visible)
        assertEquals(listOf(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_A), combo.keys)
        assertEquals(true, combo.autoKeep)
        assertEquals(profile.direction, parsed.direction)
    }

    @Test
    fun parseRejectsBadJsonAndMissingId() {
        assertNull(PadProfile.parse("not-json"))
        assertNull(PadProfile.parse(null))
        assertNull(PadProfile.parse("{}"))
    }

    @Test
    fun parseClampsOutOfRangeValues() {
        val raw = JSONObject().apply {
            put("id", "x")
            put("name", "x")
            put(
                "buttons",
                org.json.JSONArray().put(
                    JSONObject().apply {
                        put("id", "b")
                        put("x", 5.0)
                        put("y", -5.0)
                        put("size", 99.0)
                        put("aspect", 0.001)
                    },
                ),
            )
        }.toString()
        val profile = PadProfile.parse(raw)!!
        val button = profile.buttons.single()
        assertEquals(1.1f, button.x, 0.0001f)
        assertEquals(-0.1f, button.y, 0.0001f)
        assertEquals(PadButton.MAX_SIZE, button.size, 0.0001f)
        assertEquals(0.1f, button.aspect, 0.0001f)
    }

    @Test
    fun legacyConfigMigratesPositionsVisibilityAndKeys() {
        val legacy = JSONObject().apply {
            put(
                "buttons",
                JSONObject().apply {
                    put("pageup", JSONObject().apply { put("x", 0.91); put("y", 0.30); put("scale", 1.2) })
                    put("enter", JSONObject().apply { put("x", 0.60); put("y", 0.72); put("visible", false) })
                    put("btn.hide", JSONObject().apply { put("x", 0.1); put("y", 0.1) })
                    put("qwzx", JSONObject().apply { put("x", 0.80); put("y", 0.88) })
                    put("joystick", JSONObject().apply { put("x", 0.20); put("y", 0.82) })
                },
            )
        }.toString()

        val profiles = PadProfile.migrateLegacy(legacy, null)
        assertEquals(1, profiles.size)
        val profile = profiles.single()
        assertEquals(PadProfile.BUILTIN_DEFAULT_ID, profile.id)

        val pageUp = profile.buttons.first { it.id == "pageup" }
        assertEquals(0.91f, pageUp.x, 0.0001f)
        assertEquals(0.30f, pageUp.y, 0.0001f)
        assertEquals(listOf(KeyEvent.KEYCODE_PAGE_UP), pageUp.keys)

        val enter = profile.buttons.first { it.id == "enter" }
        assertEquals(false, enter.visible)
        assertEquals(listOf(KeyEvent.KEYCODE_ENTER), enter.keys)

        // 旧开关类按钮不迁移（由原生 FAB 取代）
        assertTrue(profile.buttons.none { it.id == "btn.hide" })

        // QWZX 整组拆成四键
        assertEquals(4, profile.buttons.count { it.id in setOf("q", "w", "z", "x") })

        // 方向控件位置迁移
        assertEquals(0.20f, profile.direction.x, 0.0001f)
        assertEquals(0.82f, profile.direction.y, 0.0001f)
    }

    @Test
    fun legacyPresetsBecomeProfiles() {
        val presets = JSONObject().apply {
            put(
                "竖屏布局",
                JSONObject().apply {
                    put(
                        "buttons",
                        JSONObject().apply {
                            put("enter", JSONObject().apply { put("x", 0.5); put("y", 0.9) })
                        },
                    )
                },
            )
        }.toString()
        val profiles = PadProfile.migrateLegacy(null, presets)
        assertEquals(1, profiles.size)
        assertEquals("竖屏布局", profiles.single().name)
        assertTrue(profiles.single().id.startsWith("migrated-"))
    }

    @Test
    fun legacyMigrationReturnsEmptyWhenNoData() {
        assertTrue(PadProfile.migrateLegacy(null, null).isEmpty())
        assertTrue(PadProfile.migrateLegacy("{}", "{}").isEmpty())
        assertTrue(PadProfile.migrateLegacy("not-json", "not-json").isEmpty())
    }
}
