package com.tyranor.next.core.settings

import com.core.gl.GraphicsFilterChain
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CasSettingsCodecTest {
    private val codec = GraphicsSettingsCodec

    @Test fun legacyCasPresetsMigrateWithoutLosingOrderPauseStrengthOrScaling() {
        val presets = listOf("cas_low", "cas_standard", "cas_high", "cas_scale_low", "cas_scale_standard", "cas_scale_high")
        val raw = JSONObject().put("version", 1).put("enabled", true).put("monitor", false)
            .put("chain", JSONArray().apply { presets.forEachIndexed { index, id ->
                put(JSONObject().put("id", id).put("enabled", index % 2 == 0))
            } })
        val migrated = codec.decodeSettings(raw.toString())!!
        assertEquals(List(6) { "cas" }, migrated.chain.map { it.id })
        assertEquals(listOf(0.2, 0.4, 0.8, 0.2, 0.4, 0.8), migrated.chain.map { it.sharpness })
        assertEquals(List(3) { GraphicsScaleMode.NONE } + List(3) { GraphicsScaleMode.FIT }, migrated.chain.map { it.scaleMode })
        assertEquals(migrated, codec.decodeSettings(codec.encode(migrated)))
        assertEquals(List(6) { it % 2 == 0 }, migrated.chain.map { it.enabled })
    }
    @Test fun customParametersSurviveJsonInheritanceAndTheActualEngineProtocol() {
        val nodes = listOf(GraphicsNode("cas", sharpness = 0.6), GraphicsNode("lanczos", false,
            scaleMode = GraphicsScaleMode.FACTOR, widthScale = 1.25, heightScale = 1.25))
        val settings = GraphicsSettings(true, nodes, true)
        assertEquals(settings, codec.decodeSettings(codec.encode(settings)))
        val override = codec.decodeOverride(codec.encode(GraphicsOverride(chain = nodes)))!!
        assertEquals(nodes, codec.resolve(GraphicsSettings(), override).chain)
        val engine = GraphicsFilterChain.parse(codec.encodeChain(nodes))!!
        assertEquals(0.6, engine.steps()[0].sharpness, 0.0)
        assertEquals(1.25, engine.steps()[1].widthScale, 0.0)
        assertEquals(1.25, engine.steps()[1].heightScale, 0.0)
        assertFalse(engine.steps()[1].enabled)
        for (bad in listOf(nodes[0].copy(sharpness = Double.NaN), nodes[0].copy(sharpness = 1.1),
            nodes[1].copy(widthScale = 0.0), nodes[1].copy(heightScale = Double.POSITIVE_INFINITY),
            GraphicsNode("s", sharpness = 0.6), GraphicsNode("lanczos", scaleMode = GraphicsScaleMode.NONE)))
            assertFalse(codec.validChain(listOf(bad)))
        val malformed = JSONObject(codec.encode(settings)).apply { getJSONArray("chain").getJSONObject(1).put("widthScale", "1.25") }
        assertNull(codec.decodeSettings(malformed.toString()))
        val inappropriate = JSONObject(codec.encode(GraphicsSettings(chain = listOf(GraphicsNode("s"))))).apply {
            getJSONArray("chain").getJSONObject(0).put("sharpness", 0.6)
        }
        assertNull(codec.decodeSettings(inappropriate.toString()))
    }
}
