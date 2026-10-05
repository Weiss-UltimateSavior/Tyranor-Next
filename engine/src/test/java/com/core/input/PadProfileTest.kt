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
    fun newButtonDefaultsCoverBothGeometries() {
        // 新增按钮必须能出椭圆（默认）与圆形（正方形 + 全圆角）两种外观
        val oval = PadProfile.newButtonDefaults(PadButtonGeometry.OVAL, "b1", "New")
        assertEquals(PadButton.DEFAULT_ASPECT, oval.aspect, 0.0001f)
        assertEquals(PadButton.SHAPE_ROUND, oval.shape)
        assertEquals("b1", oval.id)
        assertEquals("New", oval.text)
        assertEquals(0.5f, oval.x, 0.0001f)
        assertEquals(0.5f, oval.y, 0.0001f)
        assertTrue("新增按钮默认不绑定键位，避免误触", oval.keys.isEmpty())
        assertTrue(oval.visible)

        val round = PadProfile.newButtonDefaults(PadButtonGeometry.ROUND, "b2", "New")
        assertEquals("圆形必须是正方形盒（aspect=1），否则渲染成椭圆", 1f, round.aspect, 0.0001f)
        assertEquals(PadButton.SHAPE_ROUND, round.shape)
        assertTrue("圆形应比椭圆更小以保持视觉体量", round.size < oval.size)
        assertTrue(round.keys.isEmpty())

        // 两种几何落盘后往返不丢
        val profile = PadProfile.defaultProfile().copy(buttons = listOf(oval, round))
        val parsed = PadProfile.parse(profile.toJson())!!
        assertEquals(PadButton.DEFAULT_ASPECT, parsed.buttons.first { it.id == "b1" }.aspect, 0.0001f)
        assertEquals(1f, parsed.buttons.first { it.id == "b2" }.aspect, 0.0001f)
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
    fun legacyIncrementalConfigKeepsFullButtonSet() {
        // 旧配置是「相对出厂布局的增量」：用户只拖过一个按钮，迁移后其余按键必须仍在
        val legacy = JSONObject().apply {
            put(
                "buttons",
                JSONObject().apply {
                    put("esc", JSONObject().apply { put("x", 0.42); put("y", 0.37) })
                },
            )
        }.toString()

        val profile = PadProfile.migrateLegacy(legacy, null).single()

        // 旧布局的完整按键集（9 个动作键 + QWZX 四键），不因只拖过一个而丢失
        val expectedIds = setOf(
            "pageup", "pagedown", "tab", "alt", "ctrl", "shift", "space", "enter", "esc",
            "q", "w", "z", "x",
        )
        assertEquals(expectedIds, profile.buttons.map { it.id }.toSet())

        // 拖过的那一个用旧位置
        val esc = profile.buttons.first { it.id == "esc" }
        assertEquals(0.42f, esc.x, 0.0001f)
        assertEquals(0.37f, esc.y, 0.0001f)

        // 未被动过的按钮保留其出厂锚点（不被挤到屏幕中心）
        val untouched = profile.buttons.first { it.id == "pageup" }
        val defaultPageUp = PadProfile.defaultProfile().buttons.first { it.id == "pageup" }
        assertEquals(defaultPageUp.x, untouched.x, 0.0001f)
        assertEquals(defaultPageUp.y, untouched.y, 0.0001f)
    }

    @Test
    fun legacyNullCoordinatesKeepDefaultAnchor() {
        // 旧 JS 只改显隐时写 x/y = null：不得把按钮搬到屏幕中心
        val legacy = JSONObject().apply {
            put(
                "buttons",
                JSONObject().apply {
                    put("tab", JSONObject().apply { put("x", JSONObject.NULL); put("y", JSONObject.NULL); put("visible", false) })
                },
            )
        }.toString()

        val profile = PadProfile.migrateLegacy(legacy, null).single()
        val tab = profile.buttons.first { it.id == "tab" }
        val defaultTab = PadProfile.defaultProfile().buttons.first { it.id == "tab" }
        assertEquals(defaultTab.x, tab.x, 0.0001f)
        assertEquals(defaultTab.y, tab.y, 0.0001f)
        assertEquals(false, tab.visible)
    }

    @Test
    fun legacyPresetsWithCjkNamesAllSurvive() {
        // 纯 CJK 方案名 slug 化后都为空串：必须靠序号去重，否则只留最后一个
        val presets = JSONObject().apply {
            listOf("竖屏布局", "横屏布局", "单手布局").forEachIndexed { index, name ->
                put(
                    name,
                    JSONObject().apply {
                        put(
                            "buttons",
                            JSONObject().apply {
                                put("enter", JSONObject().apply { put("x", 0.3 + index * 0.1); put("y", 0.5) })
                            },
                        )
                    },
                )
            }
        }.toString()

        val profiles = PadProfile.migrateLegacy(null, presets)
        assertEquals(3, profiles.size)
        assertEquals(3, profiles.map { it.id }.toSet().size)
        assertEquals(setOf("竖屏布局", "横屏布局", "单手布局"), profiles.map { it.name }.toSet())
    }

    @Test
    fun legacyOnlyRemovedSwitchesIsNotMigrated() {
        // 旧条目全是已下线开关（btn.hide 等）：无可迁移内容，返回空
        val legacy = JSONObject().apply {
            put(
                "buttons",
                JSONObject().apply {
                    put("btn.hide", JSONObject().apply { put("x", 0.1); put("y", 0.1) })
                    put("btn.stick", JSONObject().apply { put("x", 0.1); put("y", 0.2) })
                },
            )
        }.toString()
        assertTrue(PadProfile.migrateLegacy(legacy, null).isEmpty())
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
