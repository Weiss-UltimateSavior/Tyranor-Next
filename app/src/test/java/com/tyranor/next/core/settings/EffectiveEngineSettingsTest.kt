package com.tyranor.next.core.settings

import com.tyranor.next.core.engine.EngineType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EffectiveEngineSettingsTest {

    @Test
    fun resolvePrefersOverrideAndFallsBackToGlobal() {
        assertEquals("game", EffectiveEngineSettings.resolve("game", "global"))
        assertEquals("global", EffectiveEngineSettings.resolve(null, "global"))
    }

    @Test
    fun resolveBoolPrefersOverrideAndFallsBackToGlobal() {
        assertTrue(EffectiveEngineSettings.resolveBool(true, false))
        assertFalse(EffectiveEngineSettings.resolveBool(false, true))
        assertTrue(EffectiveEngineSettings.resolveBool(null, true))
    }

    @Test
    fun resolveAllowedRejectsIllegalOverrideAndGlobal() {
        val allowed = setOf("auto", "force", "off")
        assertEquals("force", EffectiveEngineSettings.resolveAllowed("force", "auto", allowed, "auto"))
        // 非法覆盖回退全局
        assertEquals("off", EffectiveEngineSettings.resolveAllowed("bogus", "off", allowed, "auto"))
        // 全局也非法时回退 fallback
        assertEquals("auto", EffectiveEngineSettings.resolveAllowed(null, "bogus", allowed, "auto"))
        // 覆盖值去空白
        assertEquals("force", EffectiveEngineSettings.resolveAllowed(" force ", "auto", allowed, "auto"))
    }

    @Test
    fun krKernelFallsBackOnRemovableStorage() {
        assertEquals(
            EngineSettingsStore.KERNEL_KIRIKIRI2,
            EffectiveEngineSettings.resolveKrKernel(
                EngineSettingsStore.KERNEL_KRKRSDL3,
                EngineSettingsStore.KERNEL_KRKRSDL3,
                removableStorage = true,
            ),
        )
        assertEquals(
            EngineSettingsStore.KERNEL_KRKRSDL3,
            EffectiveEngineSettings.resolveKrKernel(
                EngineSettingsStore.KERNEL_KRKRSDL3,
                EngineSettingsStore.KERNEL_KRKRSDL3,
                removableStorage = false,
            ),
        )
        assertEquals(
            EngineSettingsStore.KERNEL_KIRIKIRI2,
            EffectiveEngineSettings.resolveKrKernel(
                EngineSettingsStore.KERNEL_KIRIKIRI2,
                EngineSettingsStore.KERNEL_KRKRSDL3,
                removableStorage = false,
            ),
        )
    }

    @Test
    fun rpgMakerModEnabledOnlyForMvMz() {
        assertTrue(EffectiveEngineSettings.resolveRpgMakerModEnabled(EngineType.RPG_MV, null, true))
        assertFalse(EffectiveEngineSettings.resolveRpgMakerModEnabled(EngineType.RPG_MV, false, true))
        assertFalse(EffectiveEngineSettings.resolveRpgMakerModEnabled(EngineType.TYRANO, true, true))
        assertFalse(EffectiveEngineSettings.resolveRpgMakerModEnabled(EngineType.WEB_OTHER, true, true))
    }

    @Test
    fun rpgVersionValidatesOverrideAgainstWhitelist() {
        val allowed = setOf(
            EngineSettingsStore.RPG_MV_V0,
            EngineSettingsStore.RPG_MV_V1,
            EngineSettingsStore.RPG_MV_V2,
        )
        assertEquals(
            EngineSettingsStore.RPG_MV_V2,
            EffectiveEngineSettings.resolveRpgVersion("V2", "v0", allowed, "v0"),
        )
        assertEquals(
            EngineSettingsStore.RPG_MV_V0,
            EffectiveEngineSettings.resolveRpgVersion("v9", "v0", allowed, "v0"),
        )
        assertEquals(
            EngineSettingsStore.RPG_MV_V1,
            EffectiveEngineSettings.resolveRpgVersion(null, "v1", allowed, "v0"),
        )
    }

    @Test
    fun mergeOnsFollowsGlobalWhenNoOverride() {
        val global = EngineSettingsStore.Ons(scopedSaveDir = false, sharpness = true)
        assertEquals(global, EffectiveEngineSettings.mergeOns(global, null))
    }

    @Test
    fun mergeOnsAppliesFieldOverridesOnly() {
        val global = EngineSettingsStore.Ons(
            scopedSaveDir = true,
            stretchFull = false,
            ignoreCutout = true,
            disableVideo = false,
            sharpness = false,
            sharpnessValue = "2",
            encoding = "gbk",
        )
        val merged = EffectiveEngineSettings.mergeOns(
            global,
            OnsOverride(
                scopedSaveDir = false,
                sharpness = true,
                sharpnessValue = "4.5",
                encoding = "utf-8",
            ),
        )
        assertFalse(merged.scopedSaveDir)
        assertTrue(merged.sharpness)
        assertEquals("4.5", merged.sharpnessValue)
        // 编码统一归一
        assertEquals("utf8", merged.encoding)
        // 未覆盖字段保持全局
        assertFalse(merged.stretchFull)
        assertTrue(merged.ignoreCutout)
    }
}
