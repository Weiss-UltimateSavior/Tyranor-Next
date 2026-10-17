package com.tyranor.next.core.engine.graphics

import com.core.engine.LaunchContract
import com.core.gl.GraphicsFilterChain
import com.tyranor.next.core.settings.GraphicsCapabilities
import com.tyranor.next.core.settings.GraphicsSettings
import com.tyranor.next.core.settings.GraphicsSettingsCodec

/** Shared by every graphics-family launch branch; unsupported hosts receive an explicit disabled request. */
internal object GraphicsLaunchPolicy {
    /** Neutral expansion contract for hosts without a graphics settings family. */
    fun disabledExtras(): Map<String, Any> = extras(
        GraphicsSettings(enabled = false, chain = emptyList()),
        GraphicsCapabilities(false),
    )

    fun missingDisabledExtras(existingKeys: Set<String>): Map<String, Any> =
        disabledExtras().filterKeys { it !in existingKeys }

    fun artemisExtras(settings: GraphicsSettings, capability: GraphicsCapabilities): Map<String, Any> {
        val requested = extras(settings, GraphicsCapabilities(true))
        return extras(settings, capability) + mapOf(
            LaunchContract.ARTEMIS_GRAPHICS_REQUESTED_ENABLED to requested.getValue(LaunchContract.GRAPHICS_FILTER_ENABLED),
            LaunchContract.ARTEMIS_GRAPHICS_REQUESTED_ANALYZER to requested.getValue(LaunchContract.GRAPHICS_FILTER_ANALYZER),
        )
    }

    fun extras(settings: GraphicsSettings, capability: GraphicsCapabilities): Map<String, Any> {
        val encoded = if (GraphicsSettingsCodec.validChain(settings.chain)) GraphicsSettingsCodec.encodeChain(settings.chain) else "v1:"
        val parsed = GraphicsFilterChain.parse(encoded)
        val enabled = parsed != null && parsed.hasActive() && GraphicsSettingsCodec.effectiveEnabled(settings, capability)
        return mapOf(
            LaunchContract.GRAPHICS_FILTER_CHAIN to (parsed?.encode() ?: "v1:"),
            LaunchContract.GRAPHICS_FILTER_ENABLED to enabled,
            LaunchContract.GRAPHICS_FILTER_ANALYZER to (enabled && settings.analyzer),
            LaunchContract.GRAPHICS_FILTER_ANALYZER_POSITION to settings.analyzerPosition.wire,
            LaunchContract.GRAPHICS_FILTER_REFRESH_INTERVAL_MS to com.tyranor.next.core.settings.GraphicsRefreshInterval.normalize(settings.refreshIntervalMs),
        )
    }
}
