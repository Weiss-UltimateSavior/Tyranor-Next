package com.core.rpgmaker

import java.util.Locale

/**
 * 请求名与磁盘名的宽松匹配（仅 v2 会话启用）。
 *
 * 真实案例（ホムンクルスの研究記録）：游戏 data 引用
 * `img/pictures/イースウェン　街並み  .png`（分隔符是 U+3000 全角空格），
 * 而磁盘上的文件是 `イースウェン 街並み  .png`（U+0020 半角空格）——素材在
 * 分发/搬运环节被做过「全角空格→半角空格」规范化，其余字符（含那些看似
 * 异常的尾随空格数量）逐字节一致。精确匹配与大小写不敏感匹配都无法命中，
 * 表现为图片 404 → MV 报加载失败。
 *
 * 匹配策略分四级，逐级放宽；后两级要求**唯一命中**，歧义时宁可不匹配
 * （宁可 404 也不要匹配到错误的图）：
 *   1. 精确
 *   2. 大小写不敏感
 *   3. 空白字符类别归一（U+3000↔U+0020 等，保留空白出现次数）
 *   4. 忽略扩展名前的尾随空白
 *
 * **条目上限**（见 [DEFAULT_MAX_FUZZY_ENTRIES]）：后两级是 O(n) 字符串比对，
 * 超过上限的目录只保留前两级。上限必须由所有调用方共用同一份实现——HTTP 服务器
 * 与 fs 桥对同一路径必须给出同一结论，否则会出现「引擎加载得到、插件读不到」。
 */
internal object RpgMakerNameMatcher {

    /**
     * 宽松匹配（第 3/4 级）的目录条目上限。
     *
     * 超过上限的目录仍支持精确与大小写不敏感（一次无分配的线性扫描，与 v0/v1 语义
     * 一致），只是不再做空白类别归一：宁可少匹配，也不要在上万素材的目录上为每次
     * miss 做全目录比对。
     */
    const val DEFAULT_MAX_FUZZY_ENTRIES = 4096

    /** 空白字符类别归一：任何空白字符替换为单个 U+0020，保留出现次数。 */
    fun whitespaceClassKey(name: String): String {
        if (name.none { it.isWhitespace() }) return name
        val sb = StringBuilder(name.length)
        for (ch in name) sb.append(if (ch.isWhitespace()) ' ' else ch)
        return sb.toString()
    }

    /** 忽略扩展名之前的尾随空白：`a .png` / `a.png` 归一为同键。 */
    fun trailingWhitespaceTrimmedKey(name: String): String {
        val dot = name.lastIndexOf('.')
        if (dot <= 0) return name.trimEnd()
        return name.substring(0, dot).trimEnd() + name.substring(dot)
    }

    /**
     * 在 [children] 中为 [requested] 找出唯一匹配的磁盘名；无匹配返回 null。
     * 调用方保证 [children] 单调（同一目录下的直接子项名）。
     *
     * [maxFuzzyEntries] ≤ 0 表示不设上限（仅供测试/特殊场景）；默认值即生产策略。
     */
    fun match(
        children: List<String>,
        requested: String,
        maxFuzzyEntries: Int = DEFAULT_MAX_FUZZY_ENTRIES,
    ): String? {
        children.firstOrNull { it == requested }?.let { return it }
        uniqueIgnoreCase(children, requested)?.let { return it }
        if (maxFuzzyEntries > 0 && children.size > maxFuzzyEntries) return null
        uniqueByKey(children, requested, ::whitespaceClassKey)?.let { return it }
        uniqueByKey(children, requested, ::trailingWhitespaceTrimmedKey)?.let { return it }
        return null
    }

    private fun uniqueIgnoreCase(children: List<String>, requested: String): String? {
        val hits = children.filter { it.equals(requested, ignoreCase = true) }
        return hits.singleOrNull()
    }

    private fun uniqueByKey(
        children: List<String>,
        requested: String,
        key: (String) -> String,
    ): String? {
        val target = key(requested).lowercase(Locale.ROOT)
        val hits = children.filter { key(it).lowercase(Locale.ROOT) == target }
        return hits.singleOrNull()
    }
}
