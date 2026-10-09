package com.core.input

/**
 * 输入配置 JSON 的解析约束（[PadProfile] 与 [GamepadMap] 共用，两处规则必须一致）。
 *
 * 这些值只用于拦住「坏文件 / 构造文件」：两类文件都由外部路径可达（导入的用户方案、
 * 被改坏的文件），无约束解析会一次分配大量对象；正常文件远不会触及上限。
 */
internal object InputJsonLimits {

    /**
     * 单个控件/逻辑按键可绑定的键位数量上限。
     *
     * 这是**防御性**上限（拦住坏文件/构造文件的一次性大分配），不是编辑约束：
     * 取值必须显著大于 `InputKeyCatalog` 的可选键总数，否则用户在键位对话框里如实勾选
     * 很多键时，落盘后会被解析侧静默截断（重启即丢绑定）。该关系由
     * `InputJsonLimitsTest` 锚定。
     */
    const val MAX_KEYS_PER_BINDING = 128

    /**
     * `schema` 兼容性判定：缺失（历史文件）按当前版本处理，高于 [current] 拒绝解析。
     *
     * 高版本文件可能带本实现不认识的字段，静默按低版本读会在下次落盘时**丢掉**这些字段，
     * 因此宁可拒绝、让调用方走「解析失败」路径（回退出厂默认），也不做有损读取。
     * 低版本暂无迁移差异，按当前字段解析即可（字段缺失已有各自的回落）。
     *
     * 非数值类型（字符串、对象等）视为缺失放行：它们不表达「更新的版本」，
     * 按当前字段尽力解析比整体拒绝更接近用户的预期。
     */
    fun isSupportedSchema(raw: Any?, current: Int): Boolean {
        if (raw == null) return true
        val number = raw as? Number ?: return true
        // 用 Long 比较而不是 toInt()：JSON 的数值可能是 Long，截断后
        // 0x1_0000_0001 会变成 1 而被当成旧版本放行
        return number.toLong() <= current.toLong()
    }
}
