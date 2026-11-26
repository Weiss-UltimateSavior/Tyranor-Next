package com.tyranor.next.core.settings

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * EasyRPG 外置宿主设置的键 / 默认值 / 覆盖解析 / 三级合并回归。
 * 键名同时锚定全局 prefs（EngineSettingsStore）与单游戏覆盖键（PerGameSettingsStore）。
 */
class EasyRpgSettingsKeysTest {

    @Test
    fun perGameKeysShareGlobalLiterals() {
        val pairs = listOf(
            EngineSettingsStore.KEY_EASYRPG_ENCODING to PerGameSettingsStore.F_EASYRPG_ENCODING,
            EngineSettingsStore.KEY_EASYRPG_SCALING to PerGameSettingsStore.F_EASYRPG_SCALING,
            EngineSettingsStore.KEY_EASYRPG_GAME_RESOLUTION to PerGameSettingsStore.F_EASYRPG_GAME_RESOLUTION,
            EngineSettingsStore.KEY_EASYRPG_STRETCH to PerGameSettingsStore.F_EASYRPG_STRETCH,
            EngineSettingsStore.KEY_EASYRPG_VSYNC to PerGameSettingsStore.F_EASYRPG_VSYNC,
            EngineSettingsStore.KEY_EASYRPG_FPS_LIMIT to PerGameSettingsStore.F_EASYRPG_FPS_LIMIT,
            EngineSettingsStore.KEY_EASYRPG_SHOW_FPS to PerGameSettingsStore.F_EASYRPG_SHOW_FPS,
            EngineSettingsStore.KEY_EASYRPG_DISABLE_AUDIO to PerGameSettingsStore.F_EASYRPG_DISABLE_AUDIO,
            EngineSettingsStore.KEY_EASYRPG_MUSIC_VOLUME to PerGameSettingsStore.F_EASYRPG_MUSIC_VOLUME,
            EngineSettingsStore.KEY_EASYRPG_SOUND_VOLUME to PerGameSettingsStore.F_EASYRPG_SOUND_VOLUME,
            EngineSettingsStore.KEY_EASYRPG_NO_RTP to PerGameSettingsStore.F_EASYRPG_NO_RTP,
            EngineSettingsStore.KEY_EASYRPG_PATCH_MODE to PerGameSettingsStore.F_EASYRPG_PATCH_MODE,
            EngineSettingsStore.KEY_EASYRPG_TEST_PLAY to PerGameSettingsStore.F_EASYRPG_TEST_PLAY,
        )
        pairs.forEach { (global, perGame) -> assertEquals(global, perGame) }
        pairs.forEach { (global, _) -> assertTrue(global, global.startsWith("easyrpg_")) }
        assertEquals(13, pairs.map { it.first }.toSet().size)
    }

    @Test
    fun defaultsMatchFrozenContract() {
        val d = EngineSettingsStore.EasyRpg()
        assertEquals("auto", d.encoding)
        assertEquals("bilinear", d.scaling)
        assertEquals("original", d.gameResolution)
        assertFalse(d.stretch)
        assertTrue(d.vsync)
        assertEquals(60, d.fpsLimit)
        assertFalse(d.showFps)
        assertFalse(d.disableAudio)
        assertEquals(100, d.musicVolume)
        assertEquals(100, d.soundVolume)
        assertFalse(d.noRtp)
        assertEquals("auto", d.patchMode)
        assertFalse(d.testPlay)
    }

    @Test
    fun allowListsAndNormalizersEnforceFrozenContract() {
        assertEquals(
            listOf("auto", "932", "936", "950", "949", "1252", "1251", "65001"),
            EngineSettingsStore.EASYRPG_ENCODINGS.toList(),
        )
        assertEquals(listOf("nearest", "integer", "bilinear"), EngineSettingsStore.EASYRPG_SCALINGS.toList())
        assertEquals(listOf("original", "widescreen", "ultrawide"), EngineSettingsStore.EASYRPG_RESOLUTIONS.toList())
        assertEquals(
            listOf("auto", "none", "easyrpg", "maniac", "dynrpg"),
            EngineSettingsStore.EASYRPG_PATCH_MODES.toList(),
        )
        assertEquals(0..240, EngineSettingsStore.EASYRPG_FPS_LIMIT_RANGE)
        assertEquals(0..100, EngineSettingsStore.EASYRPG_VOLUME_RANGE)

        assertEquals("936", EngineSettingsStore.normalizeEasyRpgEncoding(" 936 "))
        assertEquals("auto", EngineSettingsStore.normalizeEasyRpgEncoding("utf-8"))
        assertEquals("nearest", EngineSettingsStore.normalizeEasyRpgScaling(" NEAREST "))
        assertEquals("bilinear", EngineSettingsStore.normalizeEasyRpgScaling("cubic"))
        assertEquals("ultrawide", EngineSettingsStore.normalizeEasyRpgGameResolution(" UltraWide "))
        assertEquals("original", EngineSettingsStore.normalizeEasyRpgGameResolution("16:9"))
        assertEquals("dynrpg", EngineSettingsStore.normalizeEasyRpgPatchMode(" DynRPG "))
        assertEquals("auto", EngineSettingsStore.normalizeEasyRpgPatchMode("custom"))

        assertEquals(0, EngineSettingsStore.normalizeEasyRpgFpsLimit(0))
        assertEquals(240, EngineSettingsStore.normalizeEasyRpgFpsLimit(240))
        assertEquals(60, EngineSettingsStore.normalizeEasyRpgFpsLimit(-1))
        assertEquals(60, EngineSettingsStore.normalizeEasyRpgFpsLimit(241))
        assertEquals(60, EngineSettingsStore.normalizeEasyRpgFpsLimit(null))

        assertEquals(0, EngineSettingsStore.normalizeEasyRpgVolume(0))
        assertEquals(100, EngineSettingsStore.normalizeEasyRpgVolume(100))
        assertEquals(100, EngineSettingsStore.normalizeEasyRpgVolume(-1))
        assertEquals(100, EngineSettingsStore.normalizeEasyRpgVolume(101))
        assertEquals(100, EngineSettingsStore.normalizeEasyRpgVolume(null))
    }

    @Test
    fun toEasyRpgOverrideReturnsNullWhenNoRelevantKeyOrOnlyJsonNullExists() {
        assertNull(PerGameSettingsStore.toEasyRpgOverride(null))
        assertNull(PerGameSettingsStore.toEasyRpgOverride(JSONObject().put("impacto_vsync", false)))
        assertNull(
            PerGameSettingsStore.toEasyRpgOverride(
                JSONObject()
                    .put(PerGameSettingsStore.F_EASYRPG_ENCODING, JSONObject.NULL)
                    .put(PerGameSettingsStore.F_EASYRPG_VSYNC, JSONObject.NULL),
            ),
        )
    }

    @Test
    fun toEasyRpgOverrideReadsOnlyPresentKeys() {
        assertEquals(
            EasyRpgOverride(vsync = false),
            PerGameSettingsStore.toEasyRpgOverride(JSONObject().put(PerGameSettingsStore.F_EASYRPG_VSYNC, false)),
        )
        val full = PerGameSettingsStore.toEasyRpgOverride(
            JSONObject()
                .put(PerGameSettingsStore.F_EASYRPG_ENCODING, " 932 ")
                .put(PerGameSettingsStore.F_EASYRPG_SCALING, " Nearest ")
                .put(PerGameSettingsStore.F_EASYRPG_GAME_RESOLUTION, " Widescreen ")
                .put(PerGameSettingsStore.F_EASYRPG_STRETCH, true)
                .put(PerGameSettingsStore.F_EASYRPG_VSYNC, false)
                .put(PerGameSettingsStore.F_EASYRPG_FPS_LIMIT, " 0 ")
                .put(PerGameSettingsStore.F_EASYRPG_SHOW_FPS, true)
                .put(PerGameSettingsStore.F_EASYRPG_DISABLE_AUDIO, true)
                .put(PerGameSettingsStore.F_EASYRPG_MUSIC_VOLUME, " 55 ")
                .put(PerGameSettingsStore.F_EASYRPG_SOUND_VOLUME, " 75 ")
                .put(PerGameSettingsStore.F_EASYRPG_NO_RTP, true)
                .put(PerGameSettingsStore.F_EASYRPG_PATCH_MODE, " EasyRpg ")
                .put(PerGameSettingsStore.F_EASYRPG_TEST_PLAY, true),
        )
        assertEquals(
            EasyRpgOverride(
                encoding = "932",
                scaling = "nearest",
                gameResolution = "widescreen",
                stretch = true,
                vsync = false,
                fpsLimit = "0",
                showFps = true,
                disableAudio = true,
                musicVolume = "55",
                soundVolume = "75",
                noRtp = true,
                patchMode = "easyrpg",
                testPlay = true,
            ),
            full,
        )
    }

    @Test
    fun mergeFollowsGlobalAppliesFieldOverridesAndRejectsInvalidOverrides() {
        val global = EngineSettingsStore.EasyRpg(
            encoding = "932",
            scaling = "nearest",
            gameResolution = "widescreen",
            vsync = false,
            fpsLimit = 30,
            musicVolume = 80,
            soundVolume = 70,
            patchMode = "easyrpg",
        )
        assertSame(global, EffectiveEngineSettings.mergeEasyRpg(global, null))
        assertEquals(global, EffectiveEngineSettings.mergeEasyRpg(global, EasyRpgOverride()))

        val merged = EffectiveEngineSettings.mergeEasyRpg(
            global,
            EasyRpgOverride(
                encoding = "65001",
                fpsLimit = "0",
                musicVolume = "40",
                showFps = true,
                testPlay = true,
            ),
        )
        assertEquals(
            global.copy(encoding = "65001", fpsLimit = 0, musicVolume = 40, showFps = true, testPlay = true),
            merged,
        )

        val fallback = EffectiveEngineSettings.mergeEasyRpg(
            global,
            EasyRpgOverride(
                encoding = "bogus",
                scaling = "bogus",
                gameResolution = "bogus",
                fpsLimit = "999",
                musicVolume = "-5",
                soundVolume = "abc",
                patchMode = "bogus",
            ),
        )
        assertEquals(global, fallback)
    }
}
