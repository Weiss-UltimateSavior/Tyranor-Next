package com.tyranor.next.core.settings

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RPG Maker MZ（TynMZ-0.2）两个专有设置的键、默认值、白名单与三级合并回归。
 *
 * 这两项只对 MZ 会话有意义：
 * - `rpg_effekseer_mode`：auto / on / off。Chromium WebView < 124 上 MZ 实例化
 *   Effekseer WASM 会确定性杀进程（见实施方案阶段 D），auto 按 WebView 主版本判定；
 * - `rpg_audio_streaming`：钉 `WebAudio._shouldUseDecoder`，长 BGM 流式解码。
 *
 * 键名同时锚定全局 prefs（EngineSettingsStore）、单游戏覆盖键（PerGameSettingsStore）
 * 与分区键（GameOverridePartitions），三者必须是同一字面量。
 *
 * 阈值判定与 sessionConfig 拼装是宿主（engine 模块）的职责，由
 * `com.core.rpgmaker.RpgEffekseerGateTest` 覆盖——app 侧只负责取值与合并。
 */
class MzSettingsKeysTest {

    @Test
    fun globalAndPerGameKeysShareLiterals() {
        assertEquals(EngineSettingsStore.KEY_RPG_EFFEKSEER_MODE, PerGameSettingsStore.F_RPG_EFFEKSEER_MODE)
        assertEquals(EngineSettingsStore.KEY_RPG_AUDIO_STREAMING, PerGameSettingsStore.F_RPG_AUDIO_STREAMING)
        assertEquals(
            EngineSettingsStore.KEY_RPG_EFFEKSEER_MODE,
            com.tyranor.next.core.game.storage.GameOverridePartitions.KEY_RPG_EFFEKSEER_MODE,
        )
        assertEquals(
            EngineSettingsStore.KEY_RPG_AUDIO_STREAMING,
            com.tyranor.next.core.game.storage.GameOverridePartitions.KEY_RPG_AUDIO_STREAMING,
        )
        assertEquals("rpg_effekseer_mode", EngineSettingsStore.KEY_RPG_EFFEKSEER_MODE)
        assertEquals("rpg_audio_streaming", EngineSettingsStore.KEY_RPG_AUDIO_STREAMING)
    }

    @Test
    fun defaultsAreAutoAndStreaming() {
        assertEquals("auto", EngineSettingsStore.RPG_EFFEKSEER_AUTO)
        assertEquals("auto", EngineSettingsStore.normalizeRpgEffekseerMode(null))
        assertTrue(EngineSettingsStore.RPG_AUDIO_STREAMING_DEFAULT)
    }

    /** 非法值必须归一，不能原样落盘：下拉之外的脏值会一路带到宿主。 */
    @Test
    fun effekseerModeNormalizesUnknownValuesToAuto() {
        assertEquals("auto", EngineSettingsStore.normalizeRpgEffekseerMode(""))
        assertEquals("auto", EngineSettingsStore.normalizeRpgEffekseerMode("AUTO"))
        assertEquals("on", EngineSettingsStore.normalizeRpgEffekseerMode("ON"))
        assertEquals("off", EngineSettingsStore.normalizeRpgEffekseerMode("off"))
        assertEquals("auto", EngineSettingsStore.normalizeRpgEffekseerMode("yes"))
        assertEquals("auto", EngineSettingsStore.normalizeRpgEffekseerMode("1"))
        assertEquals(setOf("auto", "on", "off"), EngineSettingsStore.RPG_EFFEKSEER_MODES)
    }

    @Test
    fun effekseerModeMergesPerGameFirst() {
        assertEquals(
            "on",
            EffectiveEngineSettings.resolveAllowed(
                "on",
                "off",
                EngineSettingsStore.RPG_EFFEKSEER_MODES,
                EngineSettingsStore.RPG_EFFEKSEER_AUTO,
            ),
        )
        assertEquals(
            "off",
            EffectiveEngineSettings.resolveAllowed(
                null,
                "off",
                EngineSettingsStore.RPG_EFFEKSEER_MODES,
                EngineSettingsStore.RPG_EFFEKSEER_AUTO,
            ),
        )
        assertEquals(
            "auto",
            EffectiveEngineSettings.resolveAllowed(
                "bogus",
                "also-bogus",
                EngineSettingsStore.RPG_EFFEKSEER_MODES,
                EngineSettingsStore.RPG_EFFEKSEER_AUTO,
            ),
        )
    }

    @Test
    fun audioStreamingMergesPerGameFirst() {
        assertEquals(true, EffectiveEngineSettings.resolveBool(null, true))
        assertEquals(false, EffectiveEngineSettings.resolveBool(false, true))
        assertEquals(true, EffectiveEngineSettings.resolveBool(true, false))
    }

    @Test
    fun perGameOverrideJsonRoundTrip() {
        val json = JSONObject()
        json.put(PerGameSettingsStore.F_RPG_EFFEKSEER_MODE, "off")
        json.put(PerGameSettingsStore.F_RPG_AUDIO_STREAMING, false)
        assertEquals("off", json.optString(PerGameSettingsStore.F_RPG_EFFEKSEER_MODE))
        assertFalse(json.optBoolean(PerGameSettingsStore.F_RPG_AUDIO_STREAMING, true))
    }
}