package com.tyranor.next.core.settings

/**
 * ONScripter 引擎内置锐化（`--sharpness`）与共用画质滤镜链的用户可感互斥策略：
 * 两者同时作用于输出画面会导致双重锐化；在全局设置与单游戏设置中双向互斥联动，
 * 开启一方时自动关闭另一方（保留既有自定义滤镜链），同时载入历史双开配置时按滤镜优先归一。
 */
internal object OnsGraphicsSharpnessPolicy {
    enum class AutoDisabledTarget {
        NONE,
        GRAPHICS_FILTER,
        ENGINE_SHARPNESS,
    }

    data class GlobalResult(
        val sharpness: Boolean,
        val graphics: GraphicsSettings,
        val autoDisabled: AutoDisabledTarget,
    )

    data class PerGameResult(
        val sharpnessOverride: Boolean?,
        val graphicsOverride: GraphicsOverride,
        val autoDisabled: AutoDisabledTarget,
    )

    private val onsCapability: GraphicsCapabilities = GraphicsCapabilities(supported = true)

    fun isFilterEffective(settings: GraphicsSettings): Boolean =
        GraphicsSettingsCodec.effectiveEnabled(settings, onsCapability)

    fun isFilterEffective(global: GraphicsSettings, override: GraphicsOverride): Boolean =
        isFilterEffective(GraphicsSettingsCodec.resolve(global, override))

    fun normalizeGlobal(sharpness: Boolean, graphics: GraphicsSettings): Boolean =
        if (sharpness && isFilterEffective(graphics)) false else sharpness

    fun onGlobalSharpnessChange(
        newSharpness: Boolean,
        graphics: GraphicsSettings,
    ): GlobalResult =
        if (newSharpness && isFilterEffective(graphics)) {
            GlobalResult(
                sharpness = true,
                graphics = graphics.copy(enabled = false),
                autoDisabled = AutoDisabledTarget.GRAPHICS_FILTER,
            )
        } else {
            GlobalResult(
                sharpness = newSharpness,
                graphics = graphics,
                autoDisabled = AutoDisabledTarget.NONE,
            )
        }

    fun onGlobalGraphicsChange(
        newGraphics: GraphicsSettings,
        currentSharpness: Boolean,
    ): GlobalResult =
        if (isFilterEffective(newGraphics) && currentSharpness) {
            GlobalResult(
                sharpness = false,
                graphics = newGraphics,
                autoDisabled = AutoDisabledTarget.ENGINE_SHARPNESS,
            )
        } else {
            GlobalResult(
                sharpness = currentSharpness,
                graphics = newGraphics,
                autoDisabled = AutoDisabledTarget.NONE,
            )
        }

    fun normalizePerGame(
        globalSharpness: Boolean,
        globalGraphics: GraphicsSettings,
        sharpnessOverride: Boolean?,
        graphicsOverride: GraphicsOverride,
    ): Boolean? {
        val effectiveSharpness = sharpnessOverride ?: globalSharpness
        return if (effectiveSharpness && isFilterEffective(globalGraphics, graphicsOverride)) {
            false
        } else {
            sharpnessOverride
        }
    }

    fun onPerGameSharpnessChange(
        globalSharpness: Boolean,
        globalGraphics: GraphicsSettings,
        newSharpnessOverride: Boolean?,
        currentGraphicsOverride: GraphicsOverride,
    ): PerGameResult {
        val effectiveSharpness = newSharpnessOverride ?: globalSharpness
        return if (effectiveSharpness && isFilterEffective(globalGraphics, currentGraphicsOverride)) {
            PerGameResult(
                sharpnessOverride = newSharpnessOverride,
                graphicsOverride = currentGraphicsOverride.copy(enabled = false),
                autoDisabled = AutoDisabledTarget.GRAPHICS_FILTER,
            )
        } else {
            PerGameResult(
                sharpnessOverride = newSharpnessOverride,
                graphicsOverride = currentGraphicsOverride,
                autoDisabled = AutoDisabledTarget.NONE,
            )
        }
    }

    fun onPerGameGraphicsChange(
        globalSharpness: Boolean,
        globalGraphics: GraphicsSettings,
        currentSharpnessOverride: Boolean?,
        newGraphicsOverride: GraphicsOverride,
    ): PerGameResult {
        val effectiveSharpness = currentSharpnessOverride ?: globalSharpness
        return if (isFilterEffective(globalGraphics, newGraphicsOverride) && effectiveSharpness) {
            PerGameResult(
                sharpnessOverride = false,
                graphicsOverride = newGraphicsOverride,
                autoDisabled = AutoDisabledTarget.ENGINE_SHARPNESS,
            )
        } else {
            PerGameResult(
                sharpnessOverride = currentSharpnessOverride,
                graphicsOverride = newGraphicsOverride,
                autoDisabled = AutoDisabledTarget.NONE,
            )
        }
    }
}
