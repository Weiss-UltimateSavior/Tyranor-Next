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

    private companion object {
        /** 迁移产物的按游戏作用域（真实调用方传 gameId 哈希）。 */
        const val TEST_SCOPE = "gabc1234"
    }

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
    fun actionKeySurvivesJsonRoundTrip() {
        // 截屏按钮绑的是 canonical 动作段（2000），必须能正常落盘/读回
        val shot = PadProfile.newButtonDefaults(PadButtonGeometry.ROUND, "shot", "Shot")
            .copy(keys = listOf(CanonicalKeys.ACTION_SCREENSHOT))
        val profile = PadProfile.defaultProfile().copy(buttons = listOf(shot))

        val parsed = PadProfile.parse(profile.toJson())!!
        val restored = parsed.buttons.single()
        assertEquals(listOf(CanonicalKeys.ACTION_SCREENSHOT), restored.keys)
        assertTrue(CanonicalKeys.isAction(restored.keys.single()))
        assertEquals(1f, restored.aspect, 0.0001f)
    }

    @Test
    fun migratedIdsAreUniquePerGameAndWithinIdLimit() {
        // B1a：两个游戏的迁移产物必须落在不同 id（否则共享目录里互相覆盖）
        val legacy = JSONObject().apply {
            put(
                "buttons",
                JSONObject().apply { put("esc", JSONObject().apply { put("x", 0.4); put("y", 0.4) }) },
            )
        }.toString()
        val gameA = PadProfile.migrateLegacy(legacy, null, "g1111")
        val gameB = PadProfile.migrateLegacy(legacy, null, "g2222")
        val idA = gameA.single().id
        val idB = gameB.single().id
        assertTrue("不同游戏的迁移 id 必须不同：$idA vs $idB", idA != idB)
        assertTrue(idA != PadProfile.BUILTIN_DEFAULT_ID && idB != PadProfile.BUILTIN_DEFAULT_ID)
    }

    @Test
    fun migratedIdsFitInputConfigStoreWhitelist() {
        // B1b：id 必须落在 InputConfigStore 的白名单 [A-Za-z0-9_-]{1,32} 内，
        // 否则 writeProfile 静默拒绝、迁移永不成功且每次启动重试
        val pattern = Regex("[A-Za-z0-9_-]{1,32}")
        val longName = "很长的中文预设名称用于触发slug截断与长度上限校验"
        val legacy = JSONObject().apply {
            put(
                "buttons",
                JSONObject().apply { put("esc", JSONObject().apply { put("x", 0.4); put("y", 0.4) }) },
            )
        }.toString()
        val presets = JSONObject().apply {
            put(longName, JSONObject().apply {
                put("buttons", JSONObject().apply { put("enter", JSONObject().apply { put("x", 0.5); put("y", 0.5) }) })
            })
        }.toString()

        val profiles = PadProfile.migrateLegacy(legacy, presets, "g" + "f".repeat(40))
        assertTrue(profiles.isNotEmpty())
        profiles.forEach { profile ->
            assertTrue(
                "迁移 id 必须是合法方案 id 且不超过 32 字符，实际：'${profile.id}'（${profile.id.length}）",
                pattern.matches(profile.id),
            )
        }
        assertEquals("迁移 id 必须互不重复", profiles.size, profiles.map { it.id }.toSet().size)
    }

    @Test
    fun migratedIdNeverCollidesAcrossManyGames() {
        // 方案目录全 App 共享：N 个不同游戏的迁移产物 id 必须两两不同，
        // 否则先迁移的游戏会被后启动的游戏顶掉
        val legacy = JSONObject().apply {
            put("buttons", JSONObject().apply { put("esc", JSONObject().apply { put("x", 0.4); put("y", 0.4) }) })
        }.toString()
        val ids = (1..50).map { PadProfile.migrateLegacy(legacy, null, "g$it").single().id }
        assertEquals("不同游戏的迁移 id 必须两两不同", ids.size, ids.toSet().size)
        assertTrue("迁移 id 不得占用内置 default", ids.none { it == PadProfile.BUILTIN_DEFAULT_ID })
        assertTrue("迁移 id 必须合法且不超长", ids.all { Regex("[A-Za-z0-9_-]{1,32}").matches(it) })
    }

    @Test
    fun migratedPresetsKeepDistinctIdsWithinOneGame() {
        // 同一游戏的多个预设（含纯 CJK 名）不得互相覆盖
        val presets = JSONObject()
        listOf("竖屏布局", "横屏布局", "单手布局").forEachIndexed { i, name ->
            presets.put(name, JSONObject().apply {
                put("buttons", JSONObject().apply { put("enter", JSONObject().apply { put("x", 0.3 + i * 0.1); put("y", 0.5) }) })
            })
        }
        val profiles = PadProfile.migrateLegacy(null, presets.toString(), "ggame")
        assertEquals(3, profiles.size)
        assertEquals("同游戏内预设 id 必须唯一", 3, profiles.map { it.id }.toSet().size)
        assertTrue(profiles.all { Regex("[A-Za-z0-9_-]{1,32}").matches(it.id) })
    }

    @Test
    fun longAsciiSlugPresetsKeepDistinctIds() {
        // 长 ASCII slug 的预设：id 拼接结果被截断时不得吃掉尾部序号，
        // 否则两个不同预设算出同一 id，后者被当作「已存在」跳过 → 静默丢预设
        val names = listOf(
            "My custom layout for phone",
            "My custom layout for tablet",
            "Another very long preset name here",
        )
        val presets = JSONObject()
        names.forEachIndexed { i, name ->
            presets.put(name, JSONObject().apply {
                put("buttons", JSONObject().apply { put("enter", JSONObject().apply { put("x", 0.3 + i * 0.1); put("y", 0.5) }) })
            })
        }

        val profiles = PadProfile.migrateLegacy(null, presets.toString(), "gabc123")
        assertEquals("预设数量不得因 id 撞车而减少", names.size, profiles.size)
        assertEquals("长 slug 预设的 id 必须两两不同", names.size, profiles.map { it.id }.toSet().size)
        profiles.forEach { profile ->
            assertTrue(
                "id 必须是合法方案 id 且不超 32 字符，实际：'${profile.id}'（${profile.id.length}）",
                Regex("[A-Za-z0-9_-]{1,32}").matches(profile.id),
            )
        }
    }

    @Test
    fun directionMissingFieldFallsBackButEmptyArrayUnbinds() {
        // 方向键位两种语义必须可区分：
        //  - 字段缺失 = 用出厂方向键；
        //  - 字段存在但为空数组 = 该方向不输出（显式解绑，编辑器里清空绑定）
        val missingField = PadProfile.parse(
            """{"id":"a","name":"a","buttons":[],"direction":{"x":0.2,"y":0.8}}""",
        )!!
        assertEquals(listOf(KeyEvent.KEYCODE_DPAD_UP), missingField.direction.up)

        val emptyArray = PadProfile.parse(
            """{"id":"b","name":"b","buttons":[],"direction":{"up":[],"down":[],"left":[],"right":[]}}""",
        )!!
        assertTrue("空数组必须表达「不输出」，实际：${emptyArray.direction.up}", emptyArray.direction.up.isEmpty())
        assertTrue(emptyArray.direction.down.isEmpty())
        assertTrue(emptyArray.direction.left.isEmpty())
        assertTrue(emptyArray.direction.right.isEmpty())

        // 往返不丢「解绑」语义
        val roundTrip = PadProfile.parse(emptyArray.toJson())!!
        assertTrue("解绑语义必须在落盘后保留", roundTrip.direction.up.isEmpty())
    }

    @Test
    fun parseRejectsReservedDirectionId() {
        // 保留 id 与方向控件撞键（同处 elementById）：导入的用户方案可达此路径，
        // 重名会让渲染、命中与编辑全部错乱，故解析时直接丢弃同名按钮
        val raw = """
            {"id":"c","name":"c","buttons":[
              {"id":"direction","text":"fake","x":0.5,"y":0.5,"size":0.1},
              {"id":"ok","text":"OK","x":0.5,"y":0.5,"size":0.1}
            ]}
        """.trimIndent()
        val profile = PadProfile.parse(raw)!!
        assertEquals("保留 id 的按钮必须被丢弃", listOf("ok"), profile.buttons.map { it.id })
    }

    @Test
    fun parseCapsKeysPerBinding() {
        // 键位数组无上限会让坏文件一次分配大量对象；上限同时约束方向键位
        val keys = (1..200).joinToString(",")
        val raw = """
            {"id":"c","name":"c","buttons":[
              {"id":"ok","text":"OK","x":0.5,"y":0.5,"size":0.1,"keys":[$keys]}
            ],"direction":{"up":[$keys]}}
        """.trimIndent()
        val profile = PadProfile.parse(raw)!!
        assertEquals(InputJsonLimits.MAX_KEYS_PER_BINDING, profile.buttons.single().keys.size)
        assertEquals(InputJsonLimits.MAX_KEYS_PER_BINDING, profile.direction.up.size)
    }

    @Test
    fun parseRejectsHigherSchemaButAcceptsEqualAndMissing() {
        // 高版本文件可能带本实现不认识的字段：静默按低版本读会在下次落盘时丢字段，
        // 因此拒绝解析（调用方回退出厂默认），而不是做有损读取
        assertNull(
            "schema 高于当前版本必须拒绝",
            PadProfile.parse("""{"schema":${PadProfile.SCHEMA + 1},"id":"a","name":"a","buttons":[]}"""),
        )
        assertNotNull(
            "同版本必须接受",
            PadProfile.parse("""{"schema":${PadProfile.SCHEMA},"id":"a","name":"a","buttons":[]}"""),
        )
        assertNotNull(
            "缺失 schema（历史文件）按当前版本处理",
            PadProfile.parse("""{"id":"a","name":"a","buttons":[]}"""),
        )
    }

    @Test
    fun migratedIdGivesScopePriorityOverLongSlug() {
        // 作用域是跨游戏唯一性的唯一来源：长 slug 不得把它挤到只剩 1 字符——
        // 那样不同游戏会算出同一个 id（导入的用户方案带长名即可触发）
        val longName = "a".repeat(80)
        val presets = JSONObject().apply {
            put(longName, JSONObject().apply {
                put("buttons", JSONObject().apply { put("enter", JSONObject().apply { put("x", 0.5); put("y", 0.5) }) })
            })
        }.toString()
        val scopeA = "g1234567890a".take(13)
        val scopeB = "g1234567890b".take(13)
        val idA = PadProfile.migrateLegacy(null, presets, scopeA).single().id
        val idB = PadProfile.migrateLegacy(null, presets, scopeB).single().id

        assertTrue("不同游戏的迁移 id 必须不同：$idA vs $idB", idA != idB)
        // 作用域取满自己的配额（MAX_SCOPE_SLUG_LENGTH），不再被长 slug 压缩
        assertTrue("id 必须包含完整作用域前缀，实际：$idA", idA.contains(scopeA.take(12)))
        assertTrue(
            "id 必须合法且不超 32 字符，实际：'$idA'（${idA.length}）",
            Regex("[A-Za-z0-9_-]{1,32}").matches(idA),
        )
    }

    @Test
    fun parseEnforcesButtonCountAndTextLimits() {
        // 上限防御：坏文件/构造文件不得让解析无界分配
        val buttons = (0 until PadProfile.MAX_PAD_BUTTONS + 40).joinToString(",") { i ->
            """{"id":"b$i","text":"${"x".repeat(60)}","x":0.5,"y":0.5,"size":0.1}"""
        }
        val profile = PadProfile.parse("""{"id":"c","name":"c","buttons":[$buttons]}""")!!
        assertEquals(PadProfile.MAX_PAD_BUTTONS, profile.buttons.size)
        assertTrue(
            "按钮文字必须截断到上限",
            profile.buttons.all { it.text.length <= PadProfile.MAX_BUTTON_TEXT },
        )
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

        val profiles = PadProfile.migrateLegacy(legacy, null, TEST_SCOPE)
        assertEquals(1, profiles.size)
        val profile = profiles.single()
        // 主布局必须带按游戏作用域的独立 id：沿用内置 default 会让第一个迁移的游戏
        // 污染全局默认布局（方案目录全 App 共享）
        assertTrue("迁移主布局不得占用内置 default id", profile.id != PadProfile.BUILTIN_DEFAULT_ID)
        assertTrue("id 应含游戏作用域，实际：${profile.id}", profile.id.contains(TEST_SCOPE))

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

        val profile = PadProfile.migrateLegacy(legacy, null, TEST_SCOPE).single()

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

        val profile = PadProfile.migrateLegacy(legacy, null, TEST_SCOPE).single()
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

        val profiles = PadProfile.migrateLegacy(null, presets, TEST_SCOPE)
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
        assertTrue(PadProfile.migrateLegacy(legacy, null, TEST_SCOPE).isEmpty())
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
        val profiles = PadProfile.migrateLegacy(null, presets, TEST_SCOPE)
        assertEquals(1, profiles.size)
        assertEquals("竖屏布局", profiles.single().name)
        assertTrue(profiles.single().id.startsWith("migrated-"))
    }

    @Test
    fun legacyMigrationReturnsEmptyWhenNoData() {
        assertTrue(PadProfile.migrateLegacy(null, null, TEST_SCOPE).isEmpty())
        assertTrue(PadProfile.migrateLegacy("{}", "{}", TEST_SCOPE).isEmpty())
        assertTrue(PadProfile.migrateLegacy("not-json", "not-json", TEST_SCOPE).isEmpty())
    }

    @Test
    fun migratedMainLayoutCarriesLegacyMainFlag() {
        // 迁移主布局必须带 legacyMain=true（app 侧据此本地化展示名）；用户重命名后
        // renameProfile 把它清成 false——即使名字恰好撞占位名 "Default" 也不翻回。
        val legacy = JSONObject().apply {
            put("buttons", JSONObject().apply { put("esc", JSONObject().apply { put("x", 0.4); put("y", 0.4) }) })
        }.toString()

        val main = PadProfile.migrateLegacy(legacy, null, TEST_SCOPE).single()
        assertTrue("迁移主布局必须带 legacyMain 标记", main.legacyMain == true)
        assertTrue("迁移主布局的 id 必须是 -main 结尾", main.id.endsWith("-${PadProfile.MAIN_SLUG}"))

        // 往返不丢标记
        val roundTripped = PadProfile.parse(main.toJson())!!
        assertTrue("legacyMain 必须落盘/读回", roundTripped.legacyMain == true)

        // 用户重命名后（legacyMain=false）再落盘读回仍为 false
        val renamed = main.copy(name = "Default", legacyMain = false)
        val renamedBack = PadProfile.parse(renamed.toJson())!!
        assertEquals("重命名后标记必须保持 false", false, renamedBack.legacyMain)

        // 旧文件（无 legacyMain 字段）解析为 null（app 侧按占位名兜底）
        val legacyFile = PadProfile.parse("""{"id":"x","name":"Default","buttons":[]}""")!!
        assertEquals("旧文件无该字段应为 null", null, legacyFile.legacyMain)
    }
}
