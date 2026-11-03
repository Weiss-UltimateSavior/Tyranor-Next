package com.tyranor.next.core.settings

/**
 * 应用设置「外观风格」两档：
 * - [DEFAULT]：默认（现有主题）；
 * - [RETRO_GLASS]：复古玻璃（原「玻璃」——黑灰渐变背景 + 主题色环境光 + 半透明毛玻璃容器）。
 *
 * [storageValue] 为持久化取值，**已发布值不得更改**（改了等于一次静默数据迁移，
 * 存量用户的外观风格会丢失）；单测 `AppearanceStyleTest` 已钉住。
 * 历史上曾存在的第三档（现已删除）的遗留持久化值会经 [fromStorage] 自然回退 [DEFAULT]。
 */
enum class AppearanceStyle(val storageValue: String) {
    DEFAULT("default"),
    RETRO_GLASS("glass"),
    ;

    /** 是否为复古玻璃外观：透明页面背景、玻璃面、禁用外观模式等规则均按此判定。 */
    val isGlass: Boolean get() = this == RETRO_GLASS

    companion object {
        /** 未知 / 缺失值归一化为 [DEFAULT]（纯函数，便于 JVM 单测）。 */
        fun fromStorage(value: String?): AppearanceStyle =
            entries.firstOrNull { it.storageValue == value } ?: DEFAULT
    }
}
