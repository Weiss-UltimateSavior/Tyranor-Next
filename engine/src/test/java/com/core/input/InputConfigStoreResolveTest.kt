package com.core.input

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 生效设置的覆盖语义回归（单游戏覆盖 ?: 全局显式设置 ?: 引擎默认）。
 *
 * 关键锚定：
 *  - `org.json` 的 `optBoolean(name)` 在键缺失时返回 **false** 而非 null，因此覆盖判定
 *    必须走 `has()`；早期实现用 `optBoolean(name) ?: global` 会让任何存过单游戏设置的游戏
 *    静默失去虚拟按键与手柄映射（回归用例见 [missingOverrideFallsBackToGlobal]）；
 *  - 全局侧同理但对象是 prefs：`getBoolean(key, true)` 分不清「显式开启」与「从未设置」，
 *    因此需要 `contains` + 可空的全局值（见 [engineDefaultAppliesWhenGlobalUnset] 等）。
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

    // ---------- 引擎默认（仅 MV/MZ 默认开） ----------

    @Test
    fun engineDefaultAppliesWhenGlobalUnset() {
        // Tyrano / VN / WebOther：用户从未设置过开关 → 引擎默认关（老用户不该凭空多出按键层）
        val disabled = InputConfigStore.resolveSettings(
            blob = null,
            globalPadEnabled = null,
            globalGamepadEnabled = true,
            globalProfileId = null,
            enginePadDefault = false,
        )
        assertFalse("未设置 + 引擎默认关 → 关闭", disabled.padEnabled)

        // MV / MZ：引擎默认开（改造前就有按键层）
        val enabled = InputConfigStore.resolveSettings(
            blob = null,
            globalPadEnabled = null,
            globalGamepadEnabled = true,
            globalProfileId = null,
            enginePadDefault = true,
        )
        assertTrue("未设置 + 引擎默认开 → 开启", enabled.padEnabled)
    }

    @Test
    fun explicitGlobalSettingWinsOverEngineDefault() {
        // 用户在设置页显式关掉：即使引擎默认开也必须关闭
        val off = InputConfigStore.resolveSettings(
            blob = null,
            globalPadEnabled = false,
            globalGamepadEnabled = true,
            globalProfileId = null,
            enginePadDefault = true,
        )
        assertFalse("显式设置必须优先于引擎默认", off.padEnabled)

        // 用户在设置页显式打开：即使引擎默认关也必须开启（Tyrano 用户主动开按键层）
        val on = InputConfigStore.resolveSettings(
            blob = null,
            globalPadEnabled = true,
            globalGamepadEnabled = true,
            globalProfileId = null,
            enginePadDefault = false,
        )
        assertTrue("显式设置必须优先于引擎默认", on.padEnabled)
    }

    @Test
    fun gameOverrideWinsOverExplicitGlobalAndEngineDefault() {
        // 单游戏覆盖优先于「全局显式设置」与「引擎默认」两者
        val blob = JSONObject().put(InputConfigStore.KEY_PAD_ENABLED, true)
        val resolved = InputConfigStore.resolveSettings(
            blob = blob,
            globalPadEnabled = false,
            globalGamepadEnabled = true,
            globalProfileId = null,
            enginePadDefault = false,
        )
        assertTrue("单游戏覆盖优先级最高", resolved.padEnabled)

        val blobOff = JSONObject().put(InputConfigStore.KEY_PAD_ENABLED, false)
        val resolvedOff = InputConfigStore.resolveSettings(
            blob = blobOff,
            globalPadEnabled = true,
            globalGamepadEnabled = true,
            globalProfileId = null,
            enginePadDefault = true,
        )
        assertFalse("单游戏覆盖优先级最高", resolvedOff.padEnabled)
    }

    @Test
    fun engineDefaultDoesNotAffectGamepadSwitch() {
        // 手柄映射本来就是全引擎默认开，且不参与引擎分档：只有虚拟按键分「引擎默认」
        val resolved = InputConfigStore.resolveSettings(
            blob = null,
            globalPadEnabled = null,
            globalGamepadEnabled = true,
            globalProfileId = null,
            enginePadDefault = false,
        )
        assertTrue("手柄映射不受虚拟按键的引擎默认影响", resolved.gamepadEnabled)
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
