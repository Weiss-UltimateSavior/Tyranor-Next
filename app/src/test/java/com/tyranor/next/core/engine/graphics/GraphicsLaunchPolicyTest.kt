package com.tyranor.next.core.engine.graphics

import com.core.engine.LaunchContract
import com.core.gl.GraphicsFilterChain
import com.tyranor.next.core.settings.*
import org.junit.Assert.*
import org.junit.Test

class GraphicsLaunchPolicyTest {
    private val saved = GraphicsSettings(true, listOf(GraphicsNode("m"), GraphicsNode("deblur", false), GraphicsNode("lanczos")), true)

    @Test fun supportedActualExtrasPreserveOrderAndPauseAndEnableAnalyzer() {
        val extras = GraphicsLaunchPolicy.extras(saved, GraphicsCapabilities.forRuntime(GraphicsFamily.ARTEMIS, EngineSettingsStore.ART_KERNEL_OFFICIAL, EngineSettingsStore.ART_ENGINE_V6))
        assertEquals("v1:m,!deblur,lanczos", extras[LaunchContract.GRAPHICS_FILTER_CHAIN])
        assertEquals(true, extras[LaunchContract.GRAPHICS_FILTER_ENABLED])
        assertEquals(true, extras[LaunchContract.GRAPHICS_FILTER_ANALYZER])
        assertEquals(3, GraphicsFilterChain.parse(extras[LaunchContract.GRAPHICS_FILTER_CHAIN] as String)!!.steps().size)
    }

    @Test fun unsupportedKernelsCarryDisabledContractWithoutSwitchingOrBorrowingSettings() {
        for (capability in listOf(
            GraphicsCapabilities.forRuntime(GraphicsFamily.KR, EngineSettingsStore.KERNEL_KRKRSDL3, renderer = EngineSettingsStore.RENDERER_SOFTWARE),
            GraphicsCapabilities.forRuntime(GraphicsFamily.ARTEMIS, EngineSettingsStore.ART_KERNEL_OFFICIAL, EngineSettingsStore.ART_ENGINE_V2),
        )) {
            val extras = GraphicsLaunchPolicy.extras(saved, capability)
            assertEquals(false, extras[LaunchContract.GRAPHICS_FILTER_ENABLED])
            assertEquals(false, extras[LaunchContract.GRAPHICS_FILTER_ANALYZER])
            assertEquals("v1:m,!deblur,lanczos", extras[LaunchContract.GRAPHICS_FILTER_CHAIN])
            assertEquals(setOf(LaunchContract.GRAPHICS_FILTER_CHAIN, LaunchContract.GRAPHICS_FILTER_ENABLED,
                LaunchContract.GRAPHICS_FILTER_ANALYZER, LaunchContract.GRAPHICS_FILTER_ANALYZER_POSITION,
                LaunchContract.GRAPHICS_FILTER_REFRESH_INTERVAL_MS), extras.keys)
        }
        val neutral = GraphicsLaunchPolicy.disabledExtras()
        assertEquals("v1:", neutral[LaunchContract.GRAPHICS_FILTER_CHAIN])
        assertEquals(false, neutral[LaunchContract.GRAPHICS_FILTER_ENABLED])
        assertEquals(false, neutral[LaunchContract.GRAPHICS_FILTER_ANALYZER])
    }

    @Test fun siglusUsesTheSharedOrderedChainWithoutChangingItsRuntime() {
        val capability = GraphicsCapabilities.forRuntime(GraphicsFamily.SIGLUS)
        val extras = GraphicsLaunchPolicy.extras(saved, capability)
        assertTrue(capability.supported)
        assertEquals("v1:m,!deblur,lanczos", extras[LaunchContract.GRAPHICS_FILTER_CHAIN])
        assertEquals(true, extras[LaunchContract.GRAPHICS_FILTER_ENABLED])
        assertEquals(true, extras[LaunchContract.GRAPHICS_FILTER_ANALYZER])
        val disabled = GraphicsLaunchPolicy.extras(saved.copy(enabled = false), capability)
        assertEquals(false, disabled[LaunchContract.GRAPHICS_FILTER_ENABLED])
        assertEquals(false, disabled[LaunchContract.GRAPHICS_FILTER_ANALYZER])
    }

    @Test fun masterOffEmptyPausedAndInvalidChainsNeverRequestAnalyzer() {
        val capability = GraphicsCapabilities.forRuntime(GraphicsFamily.KR, EngineSettingsStore.KERNEL_KIRIKIRI2)
        for (settings in listOf(saved.copy(enabled = false), saved.copy(chain = emptyList()),
            saved.copy(chain = listOf(GraphicsNode("s", false))), saved.copy(chain = listOf(GraphicsNode("invalid"))))) {
            val extras = GraphicsLaunchPolicy.extras(settings, capability)
            assertEquals(false, extras[LaunchContract.GRAPHICS_FILTER_ENABLED])
            assertEquals(false, extras[LaunchContract.GRAPHICS_FILTER_ANALYZER])
            assertNotNull(GraphicsFilterChain.parse(extras[LaunchContract.GRAPHICS_FILTER_CHAIN] as String))
        }
        assertEquals(true, GraphicsLaunchPolicy.extras(saved, capability)[LaunchContract.GRAPHICS_FILTER_ANALYZER])
    }

    @Test fun inheritedGameRequestsUseTheResolvedMasterAndChain() {
        val capability = GraphicsCapabilities.forRuntime(GraphicsFamily.ARTEMIS, EngineSettingsStore.ART_KERNEL_OFFICIAL, EngineSettingsStore.ART_ENGINE_V6)
        val gameOff = GraphicsSettingsCodec.resolve(saved, GraphicsOverride(enabled = false, analyzer = true))
        assertEquals(false, GraphicsLaunchPolicy.extras(gameOff, capability)[LaunchContract.GRAPHICS_FILTER_ANALYZER])
        val gameOn = GraphicsSettingsCodec.resolve(saved.copy(enabled = false), GraphicsOverride(enabled = true))
        assertEquals(true, GraphicsLaunchPolicy.extras(gameOn, capability)[LaunchContract.GRAPHICS_FILTER_ANALYZER])
    }
    @Test fun analyzerIsGatedForUnsupportedOrInactiveHosts() {
        val settings = saved.copy(analyzer = true)
        val supported = GraphicsCapabilities.forRuntime(GraphicsFamily.SIGLUS)
        val extras = GraphicsLaunchPolicy.extras(settings, supported)
        assertEquals(true, extras[LaunchContract.GRAPHICS_FILTER_ANALYZER])
        for (capability in listOf(supported, GraphicsCapabilities.forRuntime(GraphicsFamily.KR, EngineSettingsStore.KERNEL_KRKRSDL3))) {
            assertEquals(false, GraphicsLaunchPolicy.extras(settings.copy(enabled = false), capability)[LaunchContract.GRAPHICS_FILTER_ANALYZER])
        }
        assertEquals(false, GraphicsLaunchPolicy.extras(settings, GraphicsCapabilities.forRuntime(GraphicsFamily.ARTEMIS, EngineSettingsStore.ART_KERNEL_OFFICIAL, EngineSettingsStore.ART_ENGINE_V2))[LaunchContract.GRAPHICS_FILTER_ANALYZER])
    }


    @Test fun autoKernelPreservesMigratedModeAndUnsupportedVersionsStayDisabled() {
        val migrated = GraphicsSettingsCodec.legacyGlobal(GraphicsFamily.KR, krMode = "m")
        for (kernel in listOf(EngineSettingsStore.KR_AUTO, EngineSettingsStore.KERNEL_KIRIKIRI2)) {
            for (version in listOf(EngineSettingsStore.KR_AUTO, EngineSettingsStore.KR_139, EngineSettingsStore.KR_134, EngineSettingsStore.KR_126)) {
                val capability = GraphicsCapabilities.forRuntime(GraphicsFamily.KR, kernel, version)
                assertTrue(capability.supported)
                val extras = GraphicsLaunchPolicy.extras(migrated, capability)
                assertEquals(true, extras[LaunchContract.GRAPHICS_FILTER_ENABLED])
                assertEquals(GraphicsSettingsCodec.encodeChain(migrated.chain), extras[LaunchContract.GRAPHICS_FILTER_CHAIN])
            }
            for (version in listOf("unknown")) {
                val capability = GraphicsCapabilities.forRuntime(GraphicsFamily.KR, kernel, version)
                assertFalse(capability.supported)
                assertEquals(false, GraphicsLaunchPolicy.extras(migrated, capability)[LaunchContract.GRAPHICS_FILTER_ENABLED])
            }
        }
    }
    @Test fun sdl3OpenGlIsIndependentOfTheKirikiroidVersionAndSoftwareKeepsItsSettings() {
        for (version in listOf(EngineSettingsStore.KR_AUTO, EngineSettingsStore.KR_126, "unknown")) {
            for (renderer in listOf("", "opengl", "gl", "gpu")) {
                val capability = GraphicsCapabilities.forRuntime(GraphicsFamily.KR,
                    EngineSettingsStore.KERNEL_KRKRSDL3, version, renderer)
                assertTrue(capability.supported)
                assertEquals(true, GraphicsLaunchPolicy.extras(saved, capability)[LaunchContract.GRAPHICS_FILTER_ENABLED])
            }
            for (renderer in listOf("software", "sw", " SW ")) {
                val capability = GraphicsCapabilities.forRuntime(GraphicsFamily.KR,
                    EngineSettingsStore.KERNEL_KRKRSDL3, version, renderer)
                val extras = GraphicsLaunchPolicy.extras(saved, capability)
                assertFalse(capability.supported)
                assertEquals(false, extras[LaunchContract.GRAPHICS_FILTER_ENABLED])
                assertEquals(false, extras[LaunchContract.GRAPHICS_FILTER_ANALYZER])
                assertEquals("v1:m,!deblur,lanczos", extras[LaunchContract.GRAPHICS_FILTER_CHAIN])
            }
        }
    }

    @Test fun artemisActualCandidatesGateWithoutErasingTheOriginalRequest() {
        val supported = listOf(EngineSettingsStore.ART_ENGINE_AUTO, EngineSettingsStore.ART_ENGINE_V1,
            EngineSettingsStore.ART_ENGINE_V3, EngineSettingsStore.ART_ENGINE_V4,
            EngineSettingsStore.ART_ENGINE_V5, EngineSettingsStore.ART_ENGINE_V6)
        for (version in supported + EngineSettingsStore.ART_ENGINE_V2) {
            val capability = GraphicsCapabilities.forRuntime(GraphicsFamily.ARTEMIS,
                EngineSettingsStore.ART_KERNEL_OFFICIAL, version)
            val extras = GraphicsLaunchPolicy.artemisExtras(saved, capability)
            assertEquals(version in supported, extras[LaunchContract.GRAPHICS_FILTER_ENABLED])
            assertEquals(version in supported, extras[LaunchContract.GRAPHICS_FILTER_ANALYZER])
            assertEquals(true, extras[LaunchContract.ARTEMIS_GRAPHICS_REQUESTED_ENABLED])
            assertEquals(true, extras[LaunchContract.ARTEMIS_GRAPHICS_REQUESTED_ANALYZER])
            assertEquals("v1:m,!deblur,lanczos", extras[LaunchContract.GRAPHICS_FILTER_CHAIN])
        }
        val off = GraphicsLaunchPolicy.artemisExtras(saved.copy(enabled = false), GraphicsCapabilities(true))
        assertEquals(false, off[LaunchContract.ARTEMIS_GRAPHICS_REQUESTED_ENABLED])
        assertEquals(false, off[LaunchContract.ARTEMIS_GRAPHICS_REQUESTED_ANALYZER])
    }

    @Test fun moduleGraphicsContractIsNotOverwrittenByDisabledDefaults() {
        val module = mapOf<String, Any>(
            LaunchContract.GRAPHICS_FILTER_CHAIN to "v1:lanczos",
            LaunchContract.GRAPHICS_FILTER_ENABLED to true,
            LaunchContract.GRAPHICS_FILTER_ANALYZER to true,
            LaunchContract.GRAPHICS_FILTER_ANALYZER_POSITION to "bottom",
            LaunchContract.GRAPHICS_FILTER_REFRESH_INTERVAL_MS to 500,
            "module_game_path" to "/games/module",
        )
        val additions = GraphicsLaunchPolicy.missingDisabledExtras(module.keys)
        assertTrue(additions.isEmpty())
        assertEquals(module, module + additions)
    }

    @Test fun partialModuleContractPreservesExplicitValuesAndFillsOnlyMissingFields() {
        val module = mapOf<String, Any>(
            LaunchContract.GRAPHICS_FILTER_ENABLED to true,
            LaunchContract.GRAPHICS_FILTER_CHAIN to "v1:s,lanczos",
            "module_game_path" to "/games/module",
        )
        val combined = module + GraphicsLaunchPolicy.missingDisabledExtras(module.keys)
        assertEquals(true, combined[LaunchContract.GRAPHICS_FILTER_ENABLED])
        assertEquals("v1:s,lanczos", combined[LaunchContract.GRAPHICS_FILTER_CHAIN])
        assertEquals(false, combined[LaunchContract.GRAPHICS_FILTER_ANALYZER])
        assertEquals("top", combined[LaunchContract.GRAPHICS_FILTER_ANALYZER_POSITION])
        assertEquals(GraphicsRefreshInterval.DEFAULT_MS, combined[LaunchContract.GRAPHICS_FILTER_REFRESH_INTERVAL_MS])
        assertEquals("/games/module", combined["module_game_path"])
    }

    @Test fun moduleWithoutGraphicsContractGetsNeutralChainWithoutLosingItsExtras() {
        val module = mapOf<String, Any>("module_game_path" to "/games/module")
        val combined = module + GraphicsLaunchPolicy.missingDisabledExtras(module.keys)
        assertEquals("v1:", combined[LaunchContract.GRAPHICS_FILTER_CHAIN])
        assertEquals(false, combined[LaunchContract.GRAPHICS_FILTER_ENABLED])
        assertEquals(false, combined[LaunchContract.GRAPHICS_FILTER_ANALYZER])
        assertEquals("/games/module", combined["module_game_path"])
        assertFalse(GraphicsFilterChain.parse(combined[LaunchContract.GRAPHICS_FILTER_CHAIN] as String)!!.hasActive())
    }

    @Test fun conditionalAutoPreviewKeepsRequestsForEachResolvedCandidate() {
        val preview = GraphicsCapabilities.forSettings(GraphicsFamily.ARTEMIS,
            EngineSettingsStore.ART_KERNEL_OFFICIAL, EngineSettingsStore.ART_ENGINE_AUTO)
        assertTrue(preview.runtimeDependent)
        assertTrue(GraphicsSettingsCodec.effectiveEnabled(saved, preview))
        for (version in listOf(EngineSettingsStore.ART_ENGINE_V2, EngineSettingsStore.ART_ENGINE_V3)) {
            val actual = GraphicsCapabilities.forRuntime(GraphicsFamily.ARTEMIS,
                EngineSettingsStore.ART_KERNEL_OFFICIAL, version)
            val request = GraphicsLaunchPolicy.artemisExtras(saved, actual)
            assertEquals(actual.supported, request[LaunchContract.GRAPHICS_FILTER_ENABLED])
            assertEquals(actual.supported, request[LaunchContract.GRAPHICS_FILTER_ANALYZER])
            assertEquals(true, request[LaunchContract.ARTEMIS_GRAPHICS_REQUESTED_ENABLED])
            assertEquals(true, request[LaunchContract.ARTEMIS_GRAPHICS_REQUESTED_ANALYZER])
            assertEquals(GraphicsSettingsCodec.encodeChain(saved.chain), request[LaunchContract.GRAPHICS_FILTER_CHAIN])
        }
    }

}
