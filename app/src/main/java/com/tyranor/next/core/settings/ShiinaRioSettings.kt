package com.tyranor.next.core.settings

import org.json.JSONException
import org.json.JSONObject

/** OpenShiina host settings. A missing field inherits the corresponding fallback value. */
data class ShiinaRioSettings(
    val renderer: String? = null,
    val scaling: String? = null,
    val fsrSharpness: Int? = null,
    val framePacing: String? = null,
    val fileCache: String? = null,
    val translateX86: Boolean? = null,
    val pauseInBackground: Boolean? = null,
    val gameController: Boolean? = null,
    val showFrameRate: Boolean? = null,
    val theme: String? = null,
    val language: String? = null,
) {
    fun withFallback(fallback: ShiinaRioSettings): ShiinaRioSettings = ShiinaRioSettings(
        renderer = renderer ?: fallback.renderer,
        scaling = scaling ?: fallback.scaling,
        fsrSharpness = fsrSharpness ?: fallback.fsrSharpness,
        framePacing = framePacing ?: fallback.framePacing,
        fileCache = fileCache ?: fallback.fileCache,
        translateX86 = translateX86 ?: fallback.translateX86,
        pauseInBackground = pauseInBackground ?: fallback.pauseInBackground,
        gameController = gameController ?: fallback.gameController,
        showFrameRate = showFrameRate ?: fallback.showFrameRate,
        theme = theme ?: fallback.theme,
        language = language ?: fallback.language,
    )

    fun toJson(): JSONObject = JSONObject()
        .put("renderer", renderer)
        .put("scaling", scaling)
        .put("fsrSharpness", fsrSharpness)
        .put("framePacing", framePacing)
        .put("fileCache", fileCache)
        .put("translateX86", translateX86)
        .put("pauseInBackground", pauseInBackground)
        .put("gameController", gameController)
        .put("showFrameRate", showFrameRate)
        .put("theme", theme)
        .put("language", language)

    companion object {
        internal val RENDERERS = listOf("Gpu", "Cpu")
        internal val SCALINGS = listOf("Sharp", "WholeNumbers", "Smooth", "Nearest", "Fsr", "Anime4K")
        internal val FRAME_PACING = listOf("Game", "Display")
        internal val FILE_CACHES = listOf("Auto", "Potato", "Low", "Medium", "High", "Max")
        internal val THEMES = listOf("Shiina", "Parchment", "Graphite", "White", "System")
        internal val LANGUAGES = listOf("", "zh-CN", "ja", "en")
        internal val FSR_SHARPNESS = 0..20

        val Defaults = ShiinaRioSettings(
            renderer = "Gpu",
            scaling = "Sharp",
            fsrSharpness = 8,
            framePacing = "Game",
            fileCache = "Auto",
            translateX86 = true,
            pauseInBackground = true,
            gameController = true,
            showFrameRate = false,
            theme = "Shiina",
            language = "",
        )

        internal fun fromStoredJson(raw: String?): ShiinaRioSettings = try {
            fromJson(raw?.let(::JSONObject))
        } catch (_: JSONException) {
            ShiinaRioSettings()
        }

        internal fun fromJson(json: JSONObject?): ShiinaRioSettings {
            fun option(key: String, allowed: List<String>): String? {
                val value = (json?.opt(key) as? String)?.trim() ?: return null
                return allowed.firstOrNull { it.equals(value, ignoreCase = true) }
            }
            return ShiinaRioSettings(
                renderer = option("renderer", RENDERERS),
                scaling = option("scaling", SCALINGS),
                fsrSharpness = (json?.opt("fsrSharpness") as? Int)?.coerceIn(FSR_SHARPNESS),
                framePacing = option("framePacing", FRAME_PACING),
                fileCache = option("fileCache", FILE_CACHES),
                translateX86 = json?.opt("translateX86") as? Boolean,
                pauseInBackground = json?.opt("pauseInBackground") as? Boolean,
                gameController = json?.opt("gameController") as? Boolean,
                showFrameRate = json?.opt("showFrameRate") as? Boolean,
                theme = option("theme", THEMES),
                language = option("language", LANGUAGES),
            )
        }
    }
}
