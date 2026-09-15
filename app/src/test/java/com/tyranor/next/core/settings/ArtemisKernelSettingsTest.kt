package com.tyranor.next.core.settings

import com.core.nativeplugin.NativePluginConstants
import com.tyranor.next.core.game.storage.GameOverridePartitions
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtemisKernelSettingsTest {

    @Test
    fun kernelConstantsAndWhitelist() {
        assertEquals("official", EngineSettingsStore.ART_KERNEL_OFFICIAL)
        assertEquals("clean", EngineSettingsStore.ART_KERNEL_CLEAN)
        assertEquals(setOf("official", "clean"), EngineSettingsStore.ART_KERNELS)
    }

    @Test
    fun normalizeArtKernelFallsBackToOfficial() {
        assertEquals(EngineSettingsStore.ART_KERNEL_CLEAN, EngineSettingsStore.normalizeArtKernel("clean"))
        assertEquals(EngineSettingsStore.ART_KERNEL_CLEAN, EngineSettingsStore.normalizeArtKernel(" clean "))
        assertEquals(EngineSettingsStore.ART_KERNEL_OFFICIAL, EngineSettingsStore.normalizeArtKernel("bogus"))
        assertEquals(EngineSettingsStore.ART_KERNEL_OFFICIAL, EngineSettingsStore.normalizeArtKernel(null))
    }

    @Test
    fun perGameKernelKeyMatchesPartitionContract() {
        assertEquals("artemis_kernel", PerGameSettingsStore.F_ART_KERNEL)
        assertEquals(PerGameSettingsStore.F_ART_KERNEL, GameOverridePartitions.KEY_ART_KERNEL)
        assertTrue(PerGameSettingsStore.F_ART_KERNEL in GameOverridePartitions.ARTEMIS_KEYS)
    }

    @Test
    fun artemisKernelOverrideRoundTripsThroughPartition() {
        val blob = JSONObject().put(PerGameSettingsStore.F_ART_KERNEL, "clean")

        val row = GameOverridePartitions.split("/games/artemis", blob, 1L)

        val artemisPartition = JSONObject(row.artemisJson!!)
        assertTrue(artemisPartition.has(PerGameSettingsStore.F_ART_KERNEL))
        assertEquals(blob.length(), GameOverridePartitions.assemble(row).length())
    }

    @Test
    fun cleanLibNameContractMatchesLoaderConvention() {
        // artemis_loader 按 "lib<engineLibName>.so" 拼路径，两常量必须一致
        assertEquals("libartemis-clean.so", NativePluginConstants.LIB_ARTEMIS_CLEAN)
        assertEquals("artemis-clean", NativePluginConstants.ARTEMIS_CLEAN_ENGINE_LIB_NAME)
        assertEquals(
            NativePluginConstants.LIB_ARTEMIS_CLEAN,
            "lib" + NativePluginConstants.ARTEMIS_CLEAN_ENGINE_LIB_NAME + ".so",
        )
        assertTrue(NativePluginConstants.LIB_ARTEMIS_CLEAN in NativePluginConstants.ARTEMIS_REQUIRED_LIBS)
    }
}
