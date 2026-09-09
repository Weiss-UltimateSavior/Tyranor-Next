package com.tyranor.next.core.theme

/**
 * 纯数据载荷：注入引擎 Intent 的 App 主题色快照。
 *
 * 由 ui 层（`AppThemeColors.refresh`）在主题加载/切换时填充 [ThemeColorPayloadStore]，
 * core（EngineLauncher）与 engine（EngineThemeColors.fromIntent）只读，不依赖任何
 * Compose 类型，保证 core → theme 依赖方向与「core 不得依赖 Compose 组件」的约束。
 */
data class ThemeColorPayload(
    val darkMode: Boolean,
    val primaryArgb: Int,
    val onPrimaryArgb: Int,
    val cardArgb: Int,
    val textArgb: Int,
    val mutedArgb: Int,
) {
    companion object {
        /** ui 层尚未写入快照时的回落值；与引擎侧 EngineThemeColors 缺失回落一致（浅色默认绿）。 */
        val DEFAULT = ThemeColorPayload(
            darkMode = false,
            primaryArgb = 0xFF18B978.toInt(),
            onPrimaryArgb = 0xFFFFFFFF.toInt(),
            cardArgb = 0xFFFFFFFF.toInt(),
            textArgb = 0xFF14221B.toInt(),
            mutedArgb = 0xFF82908A.toInt(),
        )
    }
}

/** 主题色快照提供者：ui 层写入最新值，core/engine 层读取；未写入时回落 [ThemeColorPayload.DEFAULT]。 */
object ThemeColorPayloadStore {
    @Volatile
    var current: ThemeColorPayload? = null
}