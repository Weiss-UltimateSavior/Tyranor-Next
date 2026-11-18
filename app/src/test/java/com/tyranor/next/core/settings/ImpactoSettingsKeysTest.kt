package com.tyranor.next.core.settings

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MAGES.（impacto）宿主设置的键 / 默认值 / 覆盖解析 / 三级合并回归。
 * 键名同时锚定全局 prefs（EngineSettingsStore）与单游戏覆盖键（PerGameSettingsStore）。
 */
class ImpactoSettingsKeysTest {

    @Test
    fun perGameKeysShareGlobalLiterals() {
        val pairs = listOf(
            EngineSettingsStore.KEY_IMPACTO_SWAP_CONFIRM_CANCEL to PerGameSettingsStore.F_IMPACTO_SWAP_CONFIRM_CANCEL,
            EngineSettingsStore.KEY_IMPACTO_VSYNC to PerGameSettingsStore.F_IMPACTO_VSYNC,
            EngineSettingsStore.KEY_IMPACTO_VIDEO_PLAYBACK to PerGameSettingsStore.F_IMPACTO_VIDEO_PLAYBACK,
            EngineSettingsStore.KEY_IMPACTO_SUBTITLES to PerGameSettingsStore.F_IMPACTO_SUBTITLES,
            EngineSettingsStore.KEY_IMPACTO_CLOSE_BACKLOG_AT_END to PerGameSettingsStore.F_IMPACTO_CLOSE_BACKLOG_AT_END,
            EngineSettingsStore.KEY_IMPACTO_DELUSION_MOUSE to PerGameSettingsStore.F_IMPACTO_DELUSION_MOUSE,
            EngineSettingsStore.KEY_IMPACTO_LOG_LEVEL to PerGameSettingsStore.F_IMPACTO_LOG_LEVEL,
        )
        pairs.forEach { (global, perGame) -> assertEquals(global, perGame) }
        // 键名都带 impacto_ 前缀，不会与其它引擎的键相撞
        pairs.forEach { (global, _) -> assertTrue(global, global.startsWith("impacto_")) }
        assertEquals(pairs.size, pairs.map { it.first }.toSet().size)
    }

    @Test
    fun defaultsMatchUpstreamEngineDefaults() {
        // 上游 userconfig.h：SwapConfirmCancelButtons=false、UseVSync=true、VideoPlayer=FFmpeg、
        // SubtitleConfig=All、CloseBacklogWhenReachedEnd=true、DelusionMousePatch=true、LogLvl=Warning
        val d = EngineSettingsStore.Impacto()
        assertFalse(d.swapConfirmCancel)
        assertTrue(d.vsync)
        assertTrue(d.videoPlayback)
        assertEquals("all", d.subtitles)
        assertTrue(d.closeBacklogAtEnd)
        assertTrue(d.delusionMouse)
        assertEquals("warning", d.logLevel)
    }

    @Test
    fun logLevelAllowListMatchesUpstreamLogLevels() {
        assertEquals(
            listOf("off", "fatal", "error", "warning", "info", "debug", "trace"),
            EngineSettingsStore.IMPACTO_LOG_LEVELS.toList(),
        )
        assertEquals("debug", EngineSettingsStore.normalizeImpactoLogLevel(" DEBUG "))
        assertEquals("off", EngineSettingsStore.normalizeImpactoLogLevel("Off"))
        assertEquals("warning", EngineSettingsStore.normalizeImpactoLogLevel("loud"))
        assertEquals("warning", EngineSettingsStore.normalizeImpactoLogLevel(null))
    }

    @Test
    fun toImpactoOverrideReturnsNullWhenNoRelevantKeyExists() {
        assertNull(PerGameSettingsStore.toImpactoOverride(null))
        assertNull(PerGameSettingsStore.toImpactoOverride(JSONObject().put("ruffle_backend", "vulkan")))
    }

    @Test
    fun toImpactoOverrideReadsOnlyPresentKeys() {
        assertEquals(
            ImpactoOverride(vsync = false),
            PerGameSettingsStore.toImpactoOverride(JSONObject().put(PerGameSettingsStore.F_IMPACTO_VSYNC, false)),
        )
        val full = PerGameSettingsStore.toImpactoOverride(
            JSONObject()
                .put(PerGameSettingsStore.F_IMPACTO_SWAP_CONFIRM_CANCEL, true)
                .put(PerGameSettingsStore.F_IMPACTO_VSYNC, false)
                .put(PerGameSettingsStore.F_IMPACTO_VIDEO_PLAYBACK, false)
                .put(PerGameSettingsStore.F_IMPACTO_SUBTITLES, " Karaoke ")
                .put(PerGameSettingsStore.F_IMPACTO_CLOSE_BACKLOG_AT_END, false)
                .put(PerGameSettingsStore.F_IMPACTO_DELUSION_MOUSE, false)
                .put(PerGameSettingsStore.F_IMPACTO_LOG_LEVEL, " Trace "),
        )
        assertEquals(
            ImpactoOverride(
                swapConfirmCancel = true,
                vsync = false,
                videoPlayback = false,
                subtitles = "karaoke",
                closeBacklogAtEnd = false,
                delusionMouse = false,
                logLevel = "trace",
            ),
            full,
        )
    }

    @Test
    fun mergeFollowsGlobalWhenNoOverride() {
        val global = EngineSettingsStore.Impacto(swapConfirmCancel = true, vsync = false, logLevel = "debug")
        assertSame(global, EffectiveEngineSettings.mergeImpacto(global, null))
        assertEquals(global, EffectiveEngineSettings.mergeImpacto(global, ImpactoOverride()))
    }

    @Test
    fun mergeAppliesFieldOverridesOnly() {
        val global = EngineSettingsStore.Impacto(
            swapConfirmCancel = false,
            vsync = true,
            videoPlayback = true,
            subtitles = "all",
            logLevel = "warning",
        )
        val merged = EffectiveEngineSettings.mergeImpacto(
            global,
            ImpactoOverride(swapConfirmCancel = true, videoPlayback = false, logLevel = "error"),
        )
        assertEquals(
            global.copy(swapConfirmCancel = true, videoPlayback = false, logLevel = "error"),
            merged,
        )
        // 未覆盖的字段保持全局值
        assertTrue(merged.vsync)
        assertEquals("all", merged.subtitles)
    }

    @Test
    fun subtitlesHaveNoUnrepresentableAllOffState() {
        // 引擎的 SubtitleConfig 是标志枚举，“两个都关”没有可读回的写法，所以只有三档
        assertEquals(listOf("all", "translation", "karaoke"), EngineSettingsStore.IMPACTO_SUBTITLES.toList())
        assertEquals("translation", EngineSettingsStore.normalizeImpactoSubtitles(" Translation "))
        assertEquals("all", EngineSettingsStore.normalizeImpactoSubtitles("none"))
        assertEquals("all", EngineSettingsStore.normalizeImpactoSubtitles(null))
        val global = EngineSettingsStore.Impacto(subtitles = "translation")
        assertEquals("translation", EffectiveEngineSettings.mergeImpacto(global, ImpactoOverride(subtitles = "none")).subtitles)
        assertEquals("karaoke", EffectiveEngineSettings.mergeImpacto(global, ImpactoOverride(subtitles = " KARAOKE ")).subtitles)
    }

    @Test
    fun mergeFallsBackToGlobalForAnIllegalLogLevel() {
        val global = EngineSettingsStore.Impacto(logLevel = "info")
        assertEquals("info", EffectiveEngineSettings.mergeImpacto(global, ImpactoOverride(logLevel = "loud")).logLevel)
        // 全局值也非法时回退默认 warning
        val badGlobal = EngineSettingsStore.Impacto(logLevel = "loud")
        assertEquals("warning", EffectiveEngineSettings.mergeImpacto(badGlobal, ImpactoOverride(logLevel = "")).logLevel)
        // 覆盖值大小写与空白按白名单归一
        assertEquals("trace", EffectiveEngineSettings.mergeImpacto(global, ImpactoOverride(logLevel = " TRACE ")).logLevel)
        assertFalse(EffectiveEngineSettings.mergeImpacto(global, ImpactoOverride(logLevel = "loud")).logLevel == "loud")
    }
}
