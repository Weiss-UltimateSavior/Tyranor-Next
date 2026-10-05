package com.core.input

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 生效设置的覆盖语义回归（单游戏覆盖 ?: 全局）。
 *
 * 关键锚定：`org.json` 的 `optBoolean(name)` 在键缺失时返回 **false** 而非 null，
 * 因此覆盖判定必须走 `has()`；早期实现用 `optBoolean(name) ?: global` 会让任何存过
 * 单游戏设置的游戏静默失去虚拟按键与手柄映射（回归用例见 [missingOverrideFallsBackToGlobal]）。
 */
class InputConfigStoreResolveTest {

    @Test
    fun missingOverrideFallsBackToGlobal() {
        // 该游戏存过其它单游戏设置（如 ONS 覆盖），但不含输入三键
        val blob = JSONObject().put("ons", JSONObject().put("encoding", "gbk"))

        val enabled = InputConfigStore.resolveSettings(
            blob = blob,
            globalPadEnabled = true,
            globalGamepadEnabled = true,
            globalProfileId = "custom-1",
        )

        assertTrue("缺键必须跟随全局开启", enabled.padEnabled)
        assertTrue("缺键必须跟随全局开启", enabled.gamepadEnabled)
        assertEquals("custom-1", enabled.profileId)
    }

    @Test
    fun explicitOverrideWins() {
        val blob = JSONObject()
            .put(InputConfigStore.KEY_PAD_ENABLED, false)
            .put(InputConfigStore.KEY_GAMEPAD_ENABLED, false)
            .put(InputConfigStore.KEY_PROFILE_ID, "p-xyz")

        val resolved = InputConfigStore.resolveSettings(
            blob = blob,
            globalPadEnabled = true,
            globalGamepadEnabled = true,
            globalProfileId = "custom-1",
        )

        assertFalse(resolved.padEnabled)
        assertFalse(resolved.gamepadEnabled)
        assertEquals("p-xyz", resolved.profileId)
    }

    @Test
    fun globalDisabledAndNoOverrideStaysDisabled() {
        val resolved = InputConfigStore.resolveSettings(
            blob = null,
            globalPadEnabled = false,
            globalGamepadEnabled = false,
            globalProfileId = null,
        )

        assertFalse(resolved.padEnabled)
        assertFalse(resolved.gamepadEnabled)
        assertEquals(InputConfigStore.DEFAULT_PROFILE_ID, resolved.profileId)
    }

    @Test
    fun blankOrIllegalProfileIdFallsBackToDefault() {
        assertEquals(
            InputConfigStore.DEFAULT_PROFILE_ID,
            InputConfigStore.resolveSettings(null, true, true, "  ").profileId,
        )
        assertEquals(
            InputConfigStore.DEFAULT_PROFILE_ID,
            InputConfigStore.resolveSettings(null, true, true, "../escape").profileId,
        )
        // 覆盖为空串时回落全局合法值
        val blob = JSONObject().put(InputConfigStore.KEY_PROFILE_ID, "")
        assertEquals(
            "custom-1",
            InputConfigStore.resolveSettings(blob, true, true, "custom-1").profileId,
        )
    }

    @Test
    fun partialOverrideLeavesOtherFieldGlobal() {
        // 只覆盖虚拟按键开关，手柄映射仍跟随全局
        val blob = JSONObject().put(InputConfigStore.KEY_PAD_ENABLED, false)
        val resolved = InputConfigStore.resolveSettings(
            blob = blob,
            globalPadEnabled = true,
            globalGamepadEnabled = true,
            globalProfileId = null,
        )
        assertFalse(resolved.padEnabled)
        assertTrue("未覆盖的字段必须跟随全局", resolved.gamepadEnabled)
    }

    @Test
    fun profileIdSanitizationRejectsPathTraversal() {
        assertEquals(null, InputConfigStore.sanitizeProfileId("../etc/passwd"))
        assertEquals(null, InputConfigStore.sanitizeProfileId("a/b"))
        assertEquals(null, InputConfigStore.sanitizeProfileId(""))
        assertEquals("ok-id_1", InputConfigStore.sanitizeProfileId("ok-id_1"))
    }
}
