package com.tyranor.next.core.settings

import com.core.gl.GraphicsFilterChain
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GraphicsSettingsCodecTest {
    private val codec = GraphicsSettingsCodec

    @Test fun legacyGlobalsRetainEachFamilyBehaviorAndDormantPreferences() {
        assertEquals(GraphicsSettings(false, listOf(GraphicsNode("s")), false), codec.legacyGlobal(GraphicsFamily.KR))
        assertEquals(listOf(GraphicsNode("soft_l")), codec.legacyGlobal(GraphicsFamily.KR, krMode = "soft_l").chain)
        assertTrue(codec.legacyGlobal(GraphicsFamily.KR, krMode = "soft_l").enabled)
        val dormant = codec.legacyGlobal(GraphicsFamily.ARTEMIS, artEnabled = false, artMode = "deblur")
        assertFalse(dormant.enabled)
        assertEquals(listOf(GraphicsNode("deblur"), GraphicsNode("lanczos")), dormant.chain)
        assertFalse(codec.legacyGlobal(GraphicsFamily.SIGLUS).enabled)
    }

    @Test fun authoritativeGlobalDoesNotReadLegacyEvenWhenMalformed() {
        var reads = 0
        val legacy = { reads++; GraphicsSettings(true, listOf(GraphicsNode("l")), true) }
        val fresh = GraphicsSettings(true, listOf(GraphicsNode("m", false), GraphicsNode("deblur")), false)
        assertEquals(fresh, codec.readGlobal(true, codec.encode(fresh), GraphicsFamily.KR, legacy))
        assertEquals(GraphicsSettings.defaults(GraphicsFamily.KR), codec.readGlobal(true, "bad", GraphicsFamily.KR, legacy))
        assertEquals(0, reads)
        assertTrue(codec.readGlobal(false, null, GraphicsFamily.KR, legacy).enabled)
        assertEquals(1, reads)
    }

    @Test fun oldGameOverridesStayPartialInsteadOfFreezingGlobalValues() {
        val art = JSONObject().put("art_anime4k_mode", "soft_m").put("art_graphics_monitor", true)
        val migrated = codec.readOverride(art, GraphicsFamily.ARTEMIS)
        assertNull(migrated.enabled)
        assertEquals(listOf(GraphicsNode("soft_m"), GraphicsNode("lanczos")), migrated.chain)
        assertFalse(codec.resolve(GraphicsSettings(enabled = false), migrated).enabled)
        assertTrue(codec.resolve(GraphicsSettings(enabled = true), migrated).enabled)
        val krOff = codec.readOverride(JSONObject().put("anime4k_mode", " off "), GraphicsFamily.KR)
        assertEquals(false, krOff.enabled)
        assertNull(krOff.chain)
    }

    @Test fun restoringInheritanceWithMarkerNeverResurrectsOldFields() {
        val saved = JSONObject().put("anime4k_mode", "l").put("art_graphics_monitor", true)
        saved.put(GraphicsFamily.KR.overrideKey, codec.encode(GraphicsOverride()))
        assertEquals(GraphicsOverride(), codec.readOverride(saved, GraphicsFamily.KR))
        assertEquals("l", saved.getString("anime4k_mode"))
        val global = GraphicsSettings(false, listOf(GraphicsNode("deblur")), false)
        assertEquals(global, codec.resolve(global, codec.readOverride(saved, GraphicsFamily.KR)))
    }

    @Test fun orderedRepeatedAndPausedNodesSurviveJsonAndActualWireParser() {
        val stages = listOf(GraphicsNode("s"), GraphicsNode("deblur", false), GraphicsNode("s"), GraphicsNode("lanczos"))
        val settings = GraphicsSettings(true, stages, true)
        assertEquals(settings, codec.decodeSettings(codec.encode(settings)))
        assertEquals("v1:s,!deblur,s,lanczos", codec.encodeChain(stages))
        val parsed = GraphicsFilterChain.parse(codec.encodeChain(stages))!!
        assertEquals(stages.map { it.id }, parsed.steps().map { it.id })
        assertEquals(stages.map { it.enabled }, parsed.steps().map { it.enabled })
        assertEquals(codec.encodeChain(stages), parsed.encode())
        for (id in codec.IDS) assertTrue(GraphicsFilterChain.known(id))
        assertEquals(GraphicsFilterChain.MAX_STEPS, codec.MAX_NODES)
    }

    @Test fun categoriesKeepStableLeavesAndNeverBecomeSerializedNodes() {
        val ids = GraphicsFilterCategory.entries.flatMap { it.presetIds }
        assertTrue(ids.isNotEmpty())
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(codec.IDS, ids)
        GraphicsFilterCategory.entries.forEach { category ->
            assertFalse(GraphicsFilterChain.known(category.name))
            category.presetIds.forEach { assertTrue(GraphicsFilterChain.known(it)) }
        }
        val nodes = listOf(GraphicsNode("s"), GraphicsNode("lanczos"), GraphicsNode("s"))
        val settings = GraphicsSettings(true, nodes, true)
        assertEquals(settings, codec.decodeSettings(codec.encode(settings)))
        assertEquals(GraphicsOverride(chain = nodes), codec.decodeOverride(codec.encode(GraphicsOverride(chain = nodes))))
        assertEquals(codec.encodeChain(nodes), GraphicsFilterChain.parse(codec.encodeChain(nodes))!!.encode())
    }

    @Test fun explicitEmptyAndNullListsHaveDifferentInheritanceSemantics() {
        val global = GraphicsSettings(true, listOf(GraphicsNode("m")), true)
        val empty = codec.decodeOverride(codec.encode(GraphicsOverride(chain = emptyList())))!!
        assertTrue(codec.resolve(global, empty).chain.isEmpty())
        val following = codec.decodeOverride(JSONObject().put("version", 1).put("enabled", JSONObject.NULL)
            .put("chain", JSONObject.NULL).put("monitor", false))!!
        assertNull(following.chain)
        assertEquals(global.chain, codec.resolve(global, following).chain)
        // Retired debug monitoring does not override the analyzer inherited from global settings.
        assertTrue(codec.resolve(global, following).analyzer)
        assertEquals("v1:", codec.encodeChain(emptyList()))
    }

    @Test fun malformedChainFallsBackAsAWholeAndDoesNotPartiallyApplyFlags() {
        val bad = JSONObject().put("version", 1).put("enabled", false).put("monitor", false)
            .put("chain", JSONArray().put(JSONObject().put("id", "s").put("enabled", true))
                .put(JSONObject().put("id", "unknown").put("enabled", true)))
        val saved = JSONObject().put("anime4k_mode", "l").put(GraphicsFamily.KR.overrideKey, bad)
        val global = GraphicsSettings(true, listOf(GraphicsNode("m")), true)
        assertEquals(global, codec.resolve(global, codec.readOverride(saved, GraphicsFamily.KR)))
        assertNull(codec.decodeSettings(bad.toString()))
        val oversized = GraphicsSettings(chain = List(9) { GraphicsNode("s") })
        assertFalse(codec.validChain(oversized.chain))
        assertNull(GraphicsFilterChain.parse("v1:" + List(9) { "s" }.joinToString(",")))
    }

    @Test fun inactiveChainsGateAnalyzerWithoutErasingPreferences() {
        val capability = GraphicsCapabilities.forRuntime(GraphicsFamily.ARTEMIS, EngineSettingsStore.ART_KERNEL_OFFICIAL, EngineSettingsStore.ART_ENGINE_V6)
        val saved = GraphicsSettings(false, listOf(GraphicsNode("deblur")), true)
        assertFalse(codec.effectiveAnalyzer(saved, capability))
        assertTrue(codec.effectiveAnalyzer(saved.copy(enabled = true), capability))
        assertTrue(saved.analyzer)
        assertFalse(codec.effectiveAnalyzer(saved.copy(enabled = true, chain = listOf(GraphicsNode("s", false))), capability))
        assertFalse(codec.effectiveAnalyzer(saved.copy(enabled = true, chain = emptyList()), capability))
    }

    @Test fun gameMasterAndAnalyzerOverridesGateAfterInheritance() {
        val capability = GraphicsCapabilities.forRuntime(GraphicsFamily.KR, EngineSettingsStore.KERNEL_KIRIKIRI2)
        val global = GraphicsSettings(true, listOf(GraphicsNode("s")), true)
        assertFalse(codec.effectiveAnalyzer(codec.resolve(global, GraphicsOverride(enabled = false)), capability))
        assertFalse(codec.effectiveAnalyzer(codec.resolve(global.copy(enabled = false), GraphicsOverride(analyzer = true)), capability))
        assertTrue(codec.effectiveAnalyzer(codec.resolve(global.copy(enabled = false), GraphicsOverride(enabled = true)), capability))
        assertFalse(codec.effectiveAnalyzer(codec.resolve(global, GraphicsOverride(chain = emptyList())), capability))
    }

    @Test fun capabilitiesDistinguishOriginalFramesAndUnsupportedFamilies() {
        val kr = GraphicsCapabilities.forRuntime(GraphicsFamily.KR, EngineSettingsStore.KERNEL_KIRIKIRI2)
        assertTrue(kr.supported)
        assertFalse(GraphicsCapabilities.forRuntime(GraphicsFamily.ARTEMIS, EngineSettingsStore.ART_KERNEL_CLEAN).supported)
        assertTrue(GraphicsCapabilities.forRuntime(GraphicsFamily.KR, EngineSettingsStore.KERNEL_KRKRSDL3).supported)
        assertTrue(GraphicsCapabilities.forRuntime(GraphicsFamily.ARTEMIS, EngineSettingsStore.ART_KERNEL_OFFICIAL, EngineSettingsStore.ART_ENGINE_V6).supported)
        assertFalse(GraphicsCapabilities.forRuntime(GraphicsFamily.ARTEMIS, EngineSettingsStore.ART_KERNEL_OFFICIAL, EngineSettingsStore.ART_ENGINE_V2).supported)
        assertTrue(GraphicsCapabilities.forRuntime(GraphicsFamily.SIGLUS).supported)
    }
    @Test fun analyzerAndOverridesRoundTripWithoutBorrowingDebugFlags() {
        val global = GraphicsSettings(enabled = true, analyzer = false)
        assertEquals(global, codec.decodeSettings(codec.encode(global)))
        val override = GraphicsOverride(analyzer = true)
        assertEquals(override, codec.decodeOverride(codec.encode(override)))
        assertTrue(codec.resolve(global, override).analyzer)
        val old = JSONObject().put("version", 1).put("enabled", true).put("monitor", true)
            .put("chain", JSONArray().put(JSONObject().put("id", "s").put("enabled", true)))
        assertFalse(codec.decodeSettings(old.toString())!!.analyzer)
        assertEquals(global, codec.decodeSettings(old.put("analyzerPosition", "unknown").toString()))
        assertEquals(override.copy(analyzerPosition = GraphicsAnalyzerPosition.BOTTOM), codec.decodeOverride(codec.encode(override).put("analyzerPosition", "bottom_right")))
        assertNull(codec.decodeOverride(JSONObject().put("version", 2).put("analyzer", "true")))
    }
    @Test fun fitDiscardsHiddenMultipliersAndLegacyFactorBecomesUniform() {
        val fit = GraphicsNode("lanczos", scaleMode = GraphicsScaleMode.FIT, widthScale = 2.0, heightScale = 0.5)
        val legacyFactor = fit.copy(scaleMode = GraphicsScaleMode.FACTOR)
        val factor = legacyFactor.copy(widthScale = 0.5, heightScale = 0.5)
        val settings = GraphicsSettings(chain = listOf(fit, legacyFactor))
        val expected = listOf(fit.copy(widthScale = 1.0, heightScale = 1.0), factor)
        assertEquals(expected, codec.decodeSettings(codec.encode(settings))!!.chain)
        val old = JSONObject(codec.encode(settings))
        old.getJSONArray("chain").getJSONObject(0).put("widthScale", 2.0).put("heightScale", 0.5)
        old.getJSONArray("chain").getJSONObject(1).put("widthScale", 2.0).put("heightScale", 0.5)
        assertEquals(expected, codec.decodeSettings(old.toString())!!.chain)
        val override = codec.decodeOverride(JSONObject().put("version", 2).put("chain", old.getJSONArray("chain")))!!
        assertEquals(expected, codec.resolve(GraphicsSettings(), override).chain)
        val wire = GraphicsFilterChain.parse(codec.encodeChain(settings.chain))!!
        assertEquals(1.0, wire.steps()[0].widthScale, 0.0)
        assertEquals(0.5, wire.steps()[1].widthScale, 0.0)
        assertEquals(0.5, wire.steps()[1].heightScale, 0.0)
    }
    @Test fun savedCustomChainSurvivesInheritancePersistenceAndGlobalChanges() {
        val custom = listOf(GraphicsNode("s"), GraphicsNode("deblur", false), GraphicsNode("s"),
            GraphicsNode("lanczos", scaleMode = GraphicsScaleMode.FACTOR, widthScale = 1.5, heightScale = 1.5))
        val global = GraphicsSettings(true, listOf(GraphicsNode("m")), false)
        val original = GraphicsOverride(enabled = false, chain = custom, analyzer = true)
        val following = codec.setChainInheritance(global, original, inherit = true)
        val reloaded = codec.decodeOverride(JSONObject(codec.encode(following).toString()))!!
        assertNull(reloaded.chain)
        assertEquals(custom, reloaded.savedChain)
        val changedGlobal = global.copy(chain = listOf(GraphicsNode("l"), GraphicsNode("lanczos")))
        assertEquals(changedGlobal.chain, codec.resolve(changedGlobal, reloaded).chain)
        val restored = codec.setChainInheritance(changedGlobal, reloaded, inherit = false)
        assertEquals(custom, restored.chain)
        assertEquals(custom, restored.savedChain)
        assertEquals(false, restored.enabled)
        assertEquals(true, restored.analyzer)
        assertEquals(restored, codec.decodeOverride(JSONObject(codec.encode(restored).toString())))
    }

    @Test fun explicitlyEmptyCustomChainSurvivesFollowGlobalAndRestore() {
        val global = GraphicsSettings(true, listOf(GraphicsNode("m")), true)
        val following = codec.setChainInheritance(global, GraphicsOverride(chain = emptyList()), inherit = true)
        val reloaded = codec.decodeOverride(JSONObject(codec.encode(following).toString()))!!
        assertEquals(emptyList<GraphicsNode>(), reloaded.savedChain)
        assertEquals(global.chain, codec.resolve(global, reloaded).chain)
        val restored = codec.setChainInheritance(global.copy(chain = listOf(GraphicsNode("l"))), reloaded, inherit = false)
        assertEquals(emptyList<GraphicsNode>(), restored.chain)
        assertFalse(codec.resolve(global, restored).hasActive())
    }

    @Test fun editingInheritedChainStartsCustomChainAndReplacesSavedCopy() {
        val global = GraphicsSettings(chain = listOf(GraphicsNode("m")))
        val following = codec.setChainInheritance(global,
            GraphicsOverride(chain = listOf(GraphicsNode("s")), analyzer = false), inherit = true)
        val edited = listOf(GraphicsNode("deblur", false), GraphicsNode("lanczos"))
        val reloaded = codec.decodeOverride(JSONObject(codec.encode(codec.setCustomChain(following, edited)).toString()))!!
        assertEquals(edited, reloaded.chain)
        assertEquals(edited, reloaded.savedChain)
        assertEquals(false, reloaded.analyzer)
        assertEquals(edited, codec.resolve(global.copy(chain = listOf(GraphicsNode("l"))), reloaded).chain)
    }

    @Test fun globalSnapshotMarkerNeverResurrectsLegacyForMalformedOrNonStringValues() {
        val legacy = mapOf<String, Any?>(
            EngineSettingsStore.KEY_KR_ANIME4K_MODE to "l",
            EngineSettingsStore.KEY_ARTEMIS_ANIME4K_ENABLED to true,
            EngineSettingsStore.KEY_ARTEMIS_ANIME4K_MODE to "soft_m",
        )
        assertTrue(codec.readGlobal(legacy, GraphicsFamily.KR).enabled)
        assertEquals(listOf(GraphicsNode("l")), codec.readGlobal(legacy, GraphicsFamily.KR).chain)
        assertTrue(codec.readGlobal(legacy, GraphicsFamily.ARTEMIS).enabled)
        assertEquals(listOf(GraphicsNode("soft_m"), GraphicsNode("lanczos")),
            codec.readGlobal(legacy, GraphicsFamily.ARTEMIS).chain)
        for (family in GraphicsFamily.entries) {
            for (marker in listOf("invalid JSON", true, 7, null)) {
                assertEquals(GraphicsSettings.defaults(family),
                    codec.readGlobal(legacy + (family.preferenceKey to marker), family))
            }
            val current = GraphicsSettings(false, listOf(GraphicsNode("deblur", false)), true)
            assertEquals(current, codec.readGlobal(legacy + (family.preferenceKey to codec.encode(current)), family))
        }
    }

    @Test fun globalReadUsesTheSuppliedSnapshotAcrossLaterPreferenceChanges() {
        val preferences = mutableMapOf<String, Any?>(EngineSettingsStore.KEY_KR_ANIME4K_MODE to "m")
        val oldSnapshot = preferences.toMap()
        val saved = GraphicsSettings(false, listOf(GraphicsNode("deblur")), true)
        preferences[GraphicsFamily.KR.preferenceKey] = codec.encode(saved)
        preferences[EngineSettingsStore.KEY_KR_ANIME4K_MODE] = "off"
        assertEquals(GraphicsSettings(true, listOf(GraphicsNode("m")), false),
            codec.readGlobal(oldSnapshot, GraphicsFamily.KR))
        assertEquals(saved, codec.readGlobal(preferences.toMap(), GraphicsFamily.KR))
    }

}
