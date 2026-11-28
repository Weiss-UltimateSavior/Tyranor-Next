package com.tyranor.next.core.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MZ 专有设置的单游戏覆盖读取（与全局三级合并）。
 *
 * 覆盖语义统一为「缺键 / JSON null = 跟随全局」：把 `JSONObject.NULL` 读成
 * `"null"` 或 `false` 都会把「未设置」误判成「有覆盖」，用户改了全局设置却发现
 * 这个游戏不跟着变。
 */
class MzPerGameOverrideTest {

    @Test
    fun missingKeysFollowTheGlobalValue() {
        val json = org.json.JSONObject()
        assertNull(overrideStr(json, PerGameSettingsStore.F_RPG_EFFEKSEER_MODE))
        assertNull(overrideBool(json, PerGameSettingsStore.F_RPG_AUDIO_STREAMING))
    }

    @Test
    fun explicitJsonNullFollowsTheGlobalValue() {
        val json = org.json.JSONObject()
        json.put(PerGameSettingsStore.F_RPG_EFFEKSEER_MODE, org.json.JSONObject.NULL)
        json.put(PerGameSettingsStore.F_RPG_AUDIO_STREAMING, org.json.JSONObject.NULL)
        assertNull(overrideStr(json, PerGameSettingsStore.F_RPG_EFFEKSEER_MODE))
        assertNull(overrideBool(json, PerGameSettingsStore.F_RPG_AUDIO_STREAMING))
    }

    @Test
    fun explicitValuesWinOverTheGlobalValue() {
        val json = org.json.JSONObject()
        json.put(PerGameSettingsStore.F_RPG_EFFEKSEER_MODE, "off")
        json.put(PerGameSettingsStore.F_RPG_AUDIO_STREAMING, false)
        assertEquals("off", overrideStr(json, PerGameSettingsStore.F_RPG_EFFEKSEER_MODE))
        assertEquals(false, overrideBool(json, PerGameSettingsStore.F_RPG_AUDIO_STREAMING))
    }

    /** 显式 false 与「未设置」必须分得开：两者在三级合并里结果相反。 */
    @Test
    fun explicitFalseIsNotTreatedAsUnset() {
        val json = org.json.JSONObject()
        json.put(PerGameSettingsStore.F_RPG_AUDIO_STREAMING, false)
        assertFalse(EffectiveEngineSettings.resolveBool(overrideBool(json, PerGameSettingsStore.F_RPG_AUDIO_STREAMING), true))
        assertTrue(EffectiveEngineSettings.resolveBool(overrideBool(json, "absent"), true))
    }

    /**
     * 「跟随全局」必须落成**移除覆盖**，不能写空串。
     *
     * 空串在 `overrideStr` 里是有效覆盖（返回 `""`），`resolveAllowed` 会判它非法并
     * 回退**全局值**——所以行为上看起来「碰巧对」，但覆盖并没有被移除：设置页会一直
     * 显示成「已覆盖」，用户之后再改全局时，这个游戏的显示值与实际生效值会分裂
     * （显示跟随全局，`overrideStr` 却始终返回空串）。这条守住 `OverrideChoice`
     * 的回调契约：选中「跟随全局」时传 `null`。
     */
    @Test
    fun emptyStringOverrideIsStillAnOverrideEvenThoughItResolvesToGlobal() {
        val json = org.json.JSONObject()
        json.put(PerGameSettingsStore.F_RPG_EFFEKSEER_MODE, "")
        // 空串确实被当成「已覆盖」读出来——这就是不能写空串的原因
        assertEquals("", overrideStr(json, PerGameSettingsStore.F_RPG_EFFEKSEER_MODE))
        // 空串被判非法后回退**全局值**（不是 fallback）：结果对，覆盖状态错
        assertEquals(
            EngineSettingsStore.RPG_EFFEKSEER_OFF,
            EffectiveEngineSettings.resolveAllowed(
                overrideStr(json, PerGameSettingsStore.F_RPG_EFFEKSEER_MODE),
                EngineSettingsStore.RPG_EFFEKSEER_OFF,
                EngineSettingsStore.RPG_EFFEKSEER_MODES,
                EngineSettingsStore.RPG_EFFEKSEER_AUTO,
            ),
        )
        // 正确语义：覆盖移除（null），同样跟随全局
        assertEquals(
            "off",
            EffectiveEngineSettings.resolveAllowed(
                overrideStr(org.json.JSONObject(), PerGameSettingsStore.F_RPG_EFFEKSEER_MODE),
                EngineSettingsStore.RPG_EFFEKSEER_OFF,
                EngineSettingsStore.RPG_EFFEKSEER_MODES,
                EngineSettingsStore.RPG_EFFEKSEER_AUTO,
            ),
        )
    }
}