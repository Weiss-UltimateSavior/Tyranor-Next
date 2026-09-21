package com.tyranor.next.core.settings

/**
 * 应用设置「外观风格」三档（见 docs/高级玻璃外观风格计划方案.md）：
 * - [DEFAULT]：默认（现有主题）；
 * - [RETRO_GLASS]：复古玻璃（原「玻璃」——黑灰渐变背景 + 主题色环境光 + 半透明毛玻璃容器）；
 * - [ADVANCED_GLASS]：高级玻璃（游戏封面拼贴的模糊底图 + 浅色磨砂卡片 + 光学描边/高光，
 *   Android 12+ 的悬浮元素获得实时模糊增强）。
 *
 * [storageValue] 为持久化取值，**已发布值不得更改**（改了等于一次静默数据迁移，
 * 存量用户的外观风格会丢失）；单测 `AppearanceStyleTest` 已钉住。
 */
enum class AppearanceStyle(val storageValue: String) {
    DEFAULT("default"),
    RETRO_GLASS("glass"),
    ADVANCED_GLASS("glass_advanced"),
    ;

    /** 是否为玻璃系外观（复古 / 高级）：透明页面背景、玻璃面、禁用外观模式等规则两档共用。 */
    val isGlass: Boolean get() = this != DEFAULT

    /** 是否为高级玻璃档。 */
    val isAdvancedGlass: Boolean get() = this == ADVANCED_GLASS

    companion object {
        /** 未知 / 缺失值归一化为 [DEFAULT]（纯函数，便于 JVM 单测）。 */
        fun fromStorage(value: String?): AppearanceStyle =
            entries.firstOrNull { it.storageValue == value } ?: DEFAULT
    }
}
