package com.tyranor.next.core.settings

import com.core.engine.ArtemisVersion
import com.core.gl.GraphicsFilterChain
import com.core.gl.GraphicsFilterDefinitions
import com.core.gl.ArtemisLibraryIdentity
import com.core.gl.KirikiroidLibraryIdentity
import com.core.nativeplugin.NativePluginConstants
import org.json.JSONArray
import org.json.JSONObject

/** Shared settings format; defaults remain independent for each engine family. */
enum class GraphicsFamily(val preferenceKey: String, val overrideKey: String) {
    KR("kr_graphics_v1", "kr_graphics_v1"),
    ARTEMIS("artemis_graphics_v1", "artemis_graphics_v1"),
    SIGLUS("siglus_graphics_v1", "siglus_graphics_v1"),
}

enum class GraphicsAnalyzerPosition(val wire: String) {
    TOP("top"), BOTTOM("bottom");
    companion object {
        fun fromWire(value: String?): GraphicsAnalyzerPosition? = when (value) {
            "top", "top_left", "top_right" -> TOP
            "bottom", "bottom_left", "bottom_right" -> BOTTOM
            else -> null
        }
    }
}

enum class GraphicsScaleMode(val wire: String) { NONE("none"), FIT("fit"), FACTOR("factor") }

data class GraphicsNode(
    val id: String,
    val enabled: Boolean = true,
    val sharpness: Double = GraphicsFilterChain.legacySharpness(id),
    val scaleMode: GraphicsScaleMode = GraphicsScaleMode.entries.first { it.wire == GraphicsFilterChain.defaultScaleMode(id) },
    val widthScale: Double = 1.0,
    val heightScale: Double = 1.0,
) {
    // Retain the old wire fields; migrate unequal legacy factors without increasing either dimension.
    val scaleFactor: Double get() = minOf(widthScale, heightScale)

    internal fun normalized() = when (scaleMode) {
        GraphicsScaleMode.FIT -> copy(widthScale = 1.0, heightScale = 1.0)
        GraphicsScaleMode.FACTOR -> copy(widthScale = scaleFactor, heightScale = scaleFactor)
        GraphicsScaleMode.NONE -> this
    }

    internal fun step() = GraphicsFilterChain.Step(id, enabled, sharpness, scaleMode.wire, widthScale, heightScale)
}

data class GraphicsSettings(
    val enabled: Boolean = false,
    val chain: List<GraphicsNode> = listOf(GraphicsNode("s")),
    val analyzer: Boolean = false,
    val refreshIntervalMs: Int = GraphicsRefreshInterval.DEFAULT_MS,
    val analyzerPosition: GraphicsAnalyzerPosition = GraphicsAnalyzerPosition.TOP,
) {
    fun hasActive(): Boolean = chain.any { it.enabled }

    companion object {
        fun defaults(family: GraphicsFamily): GraphicsSettings = GraphicsSettings(
            chain = if (family == GraphicsFamily.KR) listOf(GraphicsNode("s"))
                else listOf(GraphicsNode("s"), GraphicsNode("lanczos")),
        )
    }
}

/** Null means inheritance; an empty chain explicitly overrides the whole global list. */
data class GraphicsOverride(
    val enabled: Boolean? = null,
    val chain: List<GraphicsNode>? = null,
    val analyzer: Boolean? = null,
    val refreshIntervalMs: Int? = null,
    val analyzerPosition: GraphicsAnalyzerPosition? = null,
    /** Retained custom chain while the active chain follows global settings. */
    val savedChain: List<GraphicsNode>? = null,
)

/** UI and launch requests share the bridge's library-name gate; native installation still verifies Build IDs. */
data class GraphicsCapabilities(val supported: Boolean, val runtimeDependent: Boolean = false) {
    companion object {
        /** Auto has no resolved game candidate on a settings page; keep the request configurable.
         * Launch still gates each actual candidate, including an unsupported first candidate. */
        fun forSettings(family: GraphicsFamily, kernel: String = "", version: String = "", renderer: String = ""): GraphicsCapabilities {
            if (family == GraphicsFamily.ARTEMIS && kernel == EngineSettingsStore.ART_KERNEL_OFFICIAL &&
                (version.isBlank() || version.trim() == EngineSettingsStore.ART_ENGINE_AUTO)) {
                val candidates = ArtemisVersion.values().map { ArtemisLibraryIdentity.supports(it.libraryFilename) }
                return GraphicsCapabilities(candidates.any { it }, runtimeDependent = candidates.any { !it })
            }
            return forRuntime(family, kernel, version, renderer)
        }

        fun forRuntime(family: GraphicsFamily, kernel: String = "", version: String = "", renderer: String = ""): GraphicsCapabilities {
            val supported = when (family) {
                GraphicsFamily.KR -> {
                    val library = when (version) {
                        "", EngineSettingsStore.KR_AUTO, EngineSettingsStore.KR_139 -> NativePluginConstants.LIB_GAME_139
                        EngineSettingsStore.KR_134 -> NativePluginConstants.LIB_GAME_134
                        EngineSettingsStore.KR_126 -> NativePluginConstants.LIB_GAME_126
                        else -> null
                    }
                    if (kernel == EngineSettingsStore.KERNEL_KRKRSDL3) {
                        // SDL3 defaults to OpenGL; its software renderer has no GLES stage interface.
                        renderer.trim().lowercase() !in setOf(EngineSettingsStore.RENDERER_SOFTWARE, "sw")
                    } else kernel in setOf(EngineSettingsStore.KR_AUTO, EngineSettingsStore.KERNEL_KIRIKIRI2) &&
                        KirikiroidLibraryIdentity.supports(library)
                }
                GraphicsFamily.ARTEMIS -> {
                    kernel == EngineSettingsStore.ART_KERNEL_OFFICIAL &&
                        ArtemisLibraryIdentity.supports(ArtemisVersion.requested(version)?.libraryFilename)
                }
                GraphicsFamily.SIGLUS -> true
            }
            return GraphicsCapabilities(supported)
        }
    }
}

/** Versioned, authoritative JSON and the shared launch wire format, without Android or UI dependencies. */
object GraphicsSettingsCodec {
    const val MAX_NODES = GraphicsFilterChain.MAX_STEPS
    val IDS: List<String> = GraphicsFilterCategory.entries.flatMap { it.presetIds }

    fun validChain(chain: List<GraphicsNode>): Boolean =
        chain.size <= MAX_NODES && chain.all { it.id in IDS && runCatching { it.step() }.isSuccess }

    fun encodeChain(chain: List<GraphicsNode>): String {
        require(validChain(chain)) { "invalid graphics chain" }
        return GraphicsFilterChain.of(chain.map { it.normalized().step() }).encode()
    }

    fun resolve(global: GraphicsSettings, override: GraphicsOverride): GraphicsSettings = global.copy(
        enabled = override.enabled ?: global.enabled,
        chain = override.chain?.takeIf(::validChain)?.toList() ?: global.chain.toList(),
        analyzer = override.analyzer ?: global.analyzer,
        analyzerPosition = override.analyzerPosition ?: global.analyzerPosition,
        refreshIntervalMs = GraphicsRefreshInterval.normalize(override.refreshIntervalMs ?: global.refreshIntervalMs),
    )

    fun setChainInheritance(global: GraphicsSettings, override: GraphicsOverride, inherit: Boolean): GraphicsOverride {
        val custom = (override.chain ?: override.savedChain)?.takeIf(::validChain)?.toList()
        return if (inherit) override.copy(chain = null, savedChain = custom)
        else setCustomChain(override, custom ?: global.chain)
    }

    fun setCustomChain(override: GraphicsOverride, chain: List<GraphicsNode>): GraphicsOverride {
        require(validChain(chain)) { "invalid graphics chain" }
        return override.copy(chain = chain.toList(), savedChain = chain.toList())
    }

    fun effectiveEnabled(settings: GraphicsSettings, capability: GraphicsCapabilities): Boolean =
        capability.supported && settings.enabled && validChain(settings.chain) && settings.hasActive()

    fun effectiveAnalyzer(settings: GraphicsSettings, capability: GraphicsCapabilities): Boolean =
        effectiveEnabled(settings, capability) && settings.analyzer

    fun encode(settings: GraphicsSettings): String = JSONObject()
        .put("version", 2).put("enabled", settings.enabled)
        .put("chain", chainJson(settings.chain))
        .put("analyzer", settings.analyzer).put("analyzerPosition", settings.analyzerPosition.wire).put("refreshIntervalMs", GraphicsRefreshInterval.normalize(settings.refreshIntervalMs)).toString()

    fun encode(override: GraphicsOverride): JSONObject = JSONObject().put("version", 2).apply {
        override.enabled?.let { put("enabled", it) }
        override.chain?.let { put("chain", chainJson(it)) }
        override.savedChain?.let { put("savedChain", chainJson(it)) }
        override.analyzer?.let { put("analyzer", it) }
        override.analyzerPosition?.let { put("analyzerPosition", it.wire) }
        override.refreshIntervalMs?.let { put("refreshIntervalMs", GraphicsRefreshInterval.normalize(it)) }
    }

    fun readGlobal(hasMarker: Boolean, raw: String?, family: GraphicsFamily, legacy: () -> GraphicsSettings): GraphicsSettings =
        if (hasMarker) decodeSettings(raw) ?: GraphicsSettings.defaults(family) else legacy()

    /** One prefs snapshot owns both the marker and legacy inputs, so concurrent saves cannot mix them. */
    fun readGlobal(snapshot: Map<String, *>, family: GraphicsFamily): GraphicsSettings = readGlobal(
        snapshot.containsKey(family.preferenceKey), snapshot[family.preferenceKey] as? String, family,
    ) {
        when (family) {
            GraphicsFamily.KR -> legacyGlobal(family, krMode =
                (snapshot[EngineSettingsStore.KEY_KR_ANIME4K_MODE] as? String)
                    ?.takeIf { it in EngineSettingsStore.ANIME4K_MODES } ?: EngineSettingsStore.ANIME4K_OFF)
            GraphicsFamily.ARTEMIS -> legacyGlobal(family,
                artEnabled = snapshot[EngineSettingsStore.KEY_ARTEMIS_ANIME4K_ENABLED] as? Boolean ?: false,
                artMode = EngineSettingsStore.normalizeArtAnime4kMode(snapshot[EngineSettingsStore.KEY_ARTEMIS_ANIME4K_MODE] as? String))
            GraphicsFamily.SIGLUS -> GraphicsSettings.defaults(family)
        }
    }

    fun decodeSettings(raw: String?): GraphicsSettings? = runCatching {
        val json = raw?.let(::JSONObject) ?: return null
        if (json.opt("version") !in listOf(1, 2)) return null
        val enabled = json.opt("enabled") as? Boolean ?: return null
        val chain = decodeChain(json.optJSONArray("chain"), json.optInt("version")) ?: return null
        if (json.has("analyzer") && json.opt("analyzer") !is Boolean) return null
        val position = GraphicsAnalyzerPosition.fromWire(json.opt("analyzerPosition") as? String) ?: GraphicsAnalyzerPosition.TOP
        val refresh = if (!json.isNull("refreshIntervalMs")) decodeRefresh(json.opt("refreshIntervalMs")) ?: return null
            else GraphicsRefreshInterval.DEFAULT_MS
        GraphicsSettings(enabled, chain, json.optBoolean("analyzer", false), refresh, position)
    }.getOrNull()

    fun decodeOverride(json: JSONObject?): GraphicsOverride? {
        if (json == null || json.opt("version") !in listOf(1, 2)) return null
        if (!json.isNull("enabled") && json.opt("enabled") !is Boolean) return null
        val chain = if (!json.isNull("chain")) decodeChain(json.optJSONArray("chain"), json.optInt("version")) ?: return null else null
        val savedChain = if (!json.isNull("savedChain")) decodeChain(json.optJSONArray("savedChain"), json.optInt("version")) ?: return null else null
        if (!json.isNull("analyzer") && json.opt("analyzer") !is Boolean) return null
        val refresh = if (!json.isNull("refreshIntervalMs")) decodeRefresh(json.opt("refreshIntervalMs")) ?: return null else null
        return GraphicsOverride(json.opt("enabled") as? Boolean, chain,
            json.opt("analyzer") as? Boolean, refresh, GraphicsAnalyzerPosition.fromWire(json.opt("analyzerPosition") as? String), savedChain)
    }

    /** A saved family marker freezes its migrated legacy values, even on an unrelated settings save.
     * Missing fields subsequently mean inheritance; old keys remain readable only before this marker. */
    fun readOverride(json: JSONObject, family: GraphicsFamily): GraphicsOverride =
        if (json.has(family.overrideKey)) decodeOverride(json.optJSONObject(family.overrideKey)) ?: GraphicsOverride()
        else legacyOverride(json, family)

    fun legacyGlobal(
        family: GraphicsFamily,
        krMode: String = "off",
        artEnabled: Boolean = false,
        artMode: String = "s",
    ): GraphicsSettings = when (family) {
        GraphicsFamily.KR -> GraphicsSettings(
            enabled = krMode in IDS && krMode != "lanczos",
            chain = listOf(GraphicsNode(krMode.takeIf { it in IDS && it != "lanczos" } ?: "s")),
        )
        GraphicsFamily.ARTEMIS -> GraphicsSettings(
            enabled = artEnabled,
            chain = listOf(GraphicsNode(artMode.takeIf { it in IDS && it != "lanczos" } ?: "s"), GraphicsNode("lanczos")),
        )
        GraphicsFamily.SIGLUS -> GraphicsSettings.defaults(family)
    }

    fun legacyOverride(json: JSONObject, family: GraphicsFamily): GraphicsOverride = when (family) {
        GraphicsFamily.KR -> {
            val mode = (json.opt(PerGameSettingsStore.F_ANIME4K_MODE) as? String)?.trim()
            when {
                mode == "off" -> GraphicsOverride(enabled = false)
                mode in IDS && mode != "lanczos" -> GraphicsOverride(true, listOf(GraphicsNode(mode!!)))
                else -> GraphicsOverride()
            }
        }
        GraphicsFamily.ARTEMIS -> {
            val mode = (json.opt("art_anime4k_mode") as? String)?.trim()?.takeIf { it in IDS && it != "lanczos" }
            GraphicsOverride(
                enabled = json.opt("art_anime4k_enabled") as? Boolean,
                chain = mode?.let { listOf(GraphicsNode(it), GraphicsNode("lanczos")) },
            )
        }
        GraphicsFamily.SIGLUS -> GraphicsOverride()
    }

    private fun decodeRefresh(value: Any?): Int? {
        val number = (value as? Number)?.toDouble() ?: return null
        return number.takeIf { it.isFinite() && it % 1.0 == 0.0 &&
            it >= GraphicsRefreshInterval.MIN_MS && it <= GraphicsRefreshInterval.MAX_MS }?.toInt()?.let(GraphicsRefreshInterval::normalize)
    }

    private fun chainJson(chain: List<GraphicsNode>): JSONArray {
        require(validChain(chain)) { "invalid graphics chain" }
        return JSONArray().apply {
            chain.map(GraphicsNode::normalized).forEach { node ->
                put(JSONObject().put("id", node.id).put("enabled", node.enabled).apply {
                    if (GraphicsFilterCatalog.adjustable(node.id)) {
                        put("sharpness", node.sharpness).put("scaleMode", node.scaleMode.wire)
                        put("widthScale", node.widthScale).put("heightScale", node.heightScale)
                    }
                })
            }
        }
    }

    private fun decodeChain(json: JSONArray?, version: Int): List<GraphicsNode>? {
        if (json == null || json.length() > MAX_NODES) return null
        val chain = ArrayList<GraphicsNode>()
        for (index in 0 until json.length()) {
            val node = json.optJSONObject(index) ?: return null
            val id = node.opt("id") as? String ?: return null
            val enabled = node.opt("enabled") as? Boolean ?: return null
            val definition = GraphicsFilterDefinitions.find(id) ?: return null
            // Alias migration belongs to the contributing filter.
            val migrated = definition.migrationTarget != null
            val canonicalId = definition.migrationTarget ?: id
            if (canonicalId !in IDS) return null
            if (version == 2 && !migrated && !definition.adjustable() &&
                listOf("sharpness", "scaleMode", "widthScale", "heightScale").any(node::has)) return null
            val defaults = GraphicsNode(canonicalId, enabled, definition.defaultSharpness,
                GraphicsScaleMode.entries.first { it.wire == definition.defaultScaleMode })
            val decoded = if (version == 2 && !migrated && definition.adjustable()) {
                val sharpness = (node.opt("sharpness") as? Number)?.toDouble() ?: return null
                val scaleMode = GraphicsScaleMode.entries.firstOrNull { it.wire == node.opt("scaleMode") } ?: return null
                val widthScale = (node.opt("widthScale") as? Number)?.toDouble() ?: return null
                val heightScale = (node.opt("heightScale") as? Number)?.toDouble() ?: return null
                defaults.copy(sharpness = sharpness, scaleMode = scaleMode, widthScale = widthScale, heightScale = heightScale)
            } else defaults
            if (!runCatching { decoded.step() }.isSuccess) return null
            chain.add(decoded.normalized())
        }
        return chain
    }
}
