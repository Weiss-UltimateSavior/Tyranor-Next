package com.tyranor.next.core.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GraphicsCapabilitiesTest {
    @Test fun automaticArtemisPreviewDoesNotClaimTheOriginalHostWillBeSelected() {
        val preview = GraphicsCapabilities.forSettings(GraphicsFamily.ARTEMIS,
            EngineSettingsStore.ART_KERNEL_OFFICIAL, EngineSettingsStore.ART_ENGINE_AUTO)
        assertTrue(preview.supported) // Keep the user's request configurable for supported candidates.
        assertTrue(preview.runtimeDependent)
        assertFalse(GraphicsCapabilities.forRuntime(GraphicsFamily.ARTEMIS,
            EngineSettingsStore.ART_KERNEL_OFFICIAL, EngineSettingsStore.ART_ENGINE_V2).supported)
    }

    @Test fun explicitArtemisCandidatesShareTheRuntimeGate() {
        for (version in EngineSettingsStore.ART_VERSIONS - EngineSettingsStore.ART_ENGINE_AUTO) {
            val preview = GraphicsCapabilities.forSettings(GraphicsFamily.ARTEMIS,
                EngineSettingsStore.ART_KERNEL_OFFICIAL, version)
            assertEquals(GraphicsCapabilities.forRuntime(GraphicsFamily.ARTEMIS,
                EngineSettingsStore.ART_KERNEL_OFFICIAL, version), preview)
            assertFalse(preview.runtimeDependent)
        }
    }

    @Test fun unavailableKernelsAndUnknownVersionsNeverBecomeAutomaticSupport() {
        val clean = GraphicsCapabilities.forSettings(GraphicsFamily.ARTEMIS,
            EngineSettingsStore.ART_KERNEL_CLEAN, EngineSettingsStore.ART_ENGINE_AUTO)
        assertFalse(clean.supported)
        assertFalse(clean.runtimeDependent)
        val unknown = GraphicsCapabilities.forSettings(GraphicsFamily.ARTEMIS,
            EngineSettingsStore.ART_KERNEL_OFFICIAL, "unknown")
        assertFalse(unknown.supported)
        assertFalse(unknown.runtimeDependent)
    }

    @Test fun sdl3PreviewUsesTheSelectedRendererWithoutBorrowingKirikiroidVersion() {
        for (version in listOf(EngineSettingsStore.KR_126, "unknown")) {
            for (renderer in listOf("", "opengl", "software", " SW ")) {
                val preview = GraphicsCapabilities.forSettings(GraphicsFamily.KR,
                    EngineSettingsStore.KERNEL_KRKRSDL3, version, renderer)
                assertEquals(GraphicsCapabilities.forRuntime(GraphicsFamily.KR,
                    EngineSettingsStore.KERNEL_KRKRSDL3, version, renderer), preview)
                assertFalse(preview.runtimeDependent)
            }
        }
    }

    @Test fun otherFamiliesKeepTheirExistingCapabilityContract() {
        for (version in EngineSettingsStore.KR_VERSIONS) {
            assertEquals(GraphicsCapabilities.forRuntime(GraphicsFamily.KR,
                EngineSettingsStore.KERNEL_KIRIKIRI2, version),
                GraphicsCapabilities.forSettings(GraphicsFamily.KR,
                    EngineSettingsStore.KERNEL_KIRIKIRI2, version))
        }
        assertEquals(GraphicsCapabilities(true), GraphicsCapabilities.forSettings(GraphicsFamily.SIGLUS))
    }
}
