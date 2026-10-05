package com.tyranor.next.core.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Artemis 补丁策略默认值：关闭（不询问、不自动补丁），可选 ask/auto/off。
 */
class ArtemisPatchDefaultTest {

    @Test
    fun defaultStrategyIsOff() {
        assertEquals(EngineSettingsStore.AUTO_PATCH_OFF, EngineSettingsStore.ART_PATCH_DEFAULT)
        assertTrue(EngineSettingsStore.AUTO_PATCH_OFF in EngineSettingsStore.ART_PATCHES)
    }

    @Test
    fun allThreeStrategiesRemainSelectable() {
        assertEquals(
            setOf(
                EngineSettingsStore.AUTO_PATCH_ASK,
                EngineSettingsStore.AUTO_PATCH_AUTO,
                EngineSettingsStore.AUTO_PATCH_OFF,
            ),
            EngineSettingsStore.ART_PATCHES,
        )
    }

    @Test
    fun invalidOverrideFallsBackToOff() {
        // 单游戏覆盖非法值 → 回退默认（关闭）
        assertEquals(
            EngineSettingsStore.ART_PATCH_DEFAULT,
            EffectiveEngineSettings.resolveAllowed(
                "garbage",
                EngineSettingsStore.ART_PATCH_DEFAULT,
                EngineSettingsStore.ART_PATCHES,
                EngineSettingsStore.ART_PATCH_DEFAULT,
            ),
        )
        // 合法覆盖值优先
        assertEquals(
            EngineSettingsStore.AUTO_PATCH_ASK,
            EffectiveEngineSettings.resolveAllowed(
                EngineSettingsStore.AUTO_PATCH_ASK,
                EngineSettingsStore.ART_PATCH_DEFAULT,
                EngineSettingsStore.ART_PATCHES,
                EngineSettingsStore.ART_PATCH_DEFAULT,
            ),
        )
    }
}
