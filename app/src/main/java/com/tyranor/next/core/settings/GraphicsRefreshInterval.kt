package com.tyranor.next.core.settings

import com.core.gl.GraphicsMonitorOptions

/** UI-facing interval presets and bounds, shared with the engine's defensive launch handling. */
object GraphicsRefreshInterval {
    const val DEFAULT_MS = GraphicsMonitorOptions.DEFAULT_REFRESH_MS
    const val MIN_MS = GraphicsMonitorOptions.MIN_REFRESH_MS
    const val MAX_MS = GraphicsMonitorOptions.MAX_REFRESH_MS
    val PRESETS_MS: List<Int> = GraphicsMonitorOptions.refreshPresets().toList()
    fun normalize(value: Int): Int = GraphicsMonitorOptions.normalizeRefresh(value)
}
