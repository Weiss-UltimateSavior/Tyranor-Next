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
}