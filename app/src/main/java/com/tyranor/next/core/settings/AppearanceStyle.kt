package com.tyranor.next.core.settings

/**
 * 应用设置「外观风格」三档：
 * - [DEFAULT]：默认（现有主题）；
 * - [RETRO_GLASS]：复古玻璃（原「玻璃」——黑灰渐变背景 + 主题色环境光 + 半透明毛玻璃容器）；
 * - [MILK_GLASS]：白雾玻璃（深色图片底图 + 70% 白纱亮面条目 + 深色正文）。
 *   显示名由「乳白玻璃」更名；枚举名与持久化值 `milk_glass` 为兼容存量数据保留，不随显示名变化。
 *
 * [storageValue] 为持久化取值，**已发布值不得更改**（改了等于一次静默数据迁移，
 * 存量用户的外观风格会丢失）；单测 `AppearanceStyleTest` 已钉住。
 * 历史上曾存在的第三档（现已删除）的遗留持久化值会经 [fromStorage] 自然回退 [DEFAULT]。
 */
enum class AppearanceStyle(val storageValue: String) {
    DEFAULT("default"),
    RETRO_GLASS("glass"),
    MILK_GLASS("milk_glass"),
    ;

    /** 是否为复古玻璃外观：深色玻璃面、白色文字、禁用外观模式等规则均按此判定。 */
    val isGlass: Boolean get() = this == RETRO_GLASS

    /** 是否为白雾玻璃外观：图片底图 + 高明度白雾条目 + 深色正文。 */
    val isMilkGlass: Boolean get() = this == MILK_GLASS

    /**
     * 页面背景是否为固定深色画面（复古玻璃 / 白雾玻璃）：
     * 系统栏恒用浅色图标，顶栏文字使用页面前景色而非条目正文色。
     */
    val hasDarkBackdrop: Boolean get() = isGlass || isMilkGlass

    /** 是否使用与外观模式无关的固定配色（复古玻璃 / 白雾玻璃）：设置页禁用深浅色切换。 */
    val hasFixedContentPalette: Boolean get() = hasDarkBackdrop

    companion object {
        /** 未知 / 缺失值归一化为 [DEFAULT]（纯函数，便于 JVM 单测）。 */
        fun fromStorage(value: String?): AppearanceStyle =
            entries.firstOrNull { it.storageValue == value } ?: DEFAULT
    }
}
