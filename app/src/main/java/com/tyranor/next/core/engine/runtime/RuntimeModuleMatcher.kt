package com.tyranor.next.core.engine.runtime

import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException

/** 候选目录的一个直接子项；[name] 只含文件名，不含路径。 */
data class RuntimeModuleEntry(val name: String, val isDirectory: Boolean)

/**
 * 第三方运行时扫描特征匹配器（纯 Kotlin，不依赖 Android，便于 JVM 回归）。
 *
 * 只对候选目录的直接子项求值：
 * - extensions：任一直接子文件后缀命中（大小写不敏感）；
 * - markers：相对路径存在即命中（文件/目录都算）；`data/` 下的 `*.pak` 只查下一层；
 * - ANY：扩展名谓词 OR 任一 marker；ALL：扩展名谓词（若有）AND 所有 marker。
 *
 * 调用方传入已读取的子项与 `childrenOf`，匹配器不自行触碰文件系统。
 */
object RuntimeModuleMatcher {

    fun matches(
        spec: RuntimeModuleScanSpec,
        children: List<RuntimeModuleEntry>,
        childrenOf: (RuntimeModuleEntry) -> List<RuntimeModuleEntry>,
    ): Boolean {
        // 防御手写 spec：非法模式/超限条目在匹配阶段同样被忽略，且不抛异常。
        val extensions = spec.extensions.asSequence()
            .mapNotNull { RuntimeModulePatterns.normalizeExtension(it) }
            .take(RuntimeModulePatterns.MAX_EXTENSIONS)
            .toList()
        val markers = spec.markers.asSequence()
            .mapNotNull { RuntimeModulePatterns.normalizeMarker(it) }
            .take(RuntimeModulePatterns.MAX_MARKERS)
            .toList()
        if (extensions.isEmpty() && markers.isEmpty()) return false

        val extensionHit = extensions.isNotEmpty() && children.any { entry ->
            !entry.isDirectory && extensions.any { entry.name.endsWith(it, ignoreCase = true) }
        }

        val directoryCache = HashMap<RuntimeModuleEntry, List<RuntimeModuleEntry>>()
        return when (spec.match) {
            RuntimeModuleScanSpec.Match.ALL ->
                (extensions.isEmpty() || extensionHit) &&
                    markers.all { markerMatches(it, children, childrenOf, directoryCache) }

            RuntimeModuleScanSpec.Match.ANY ->
                extensionHit ||
                    markers.any { markerMatches(it, children, childrenOf, directoryCache) }
        }
    }

    private fun markerMatches(
        marker: String,
        children: List<RuntimeModuleEntry>,
        childrenOf: (RuntimeModuleEntry) -> List<RuntimeModuleEntry>,
        directoryCache: MutableMap<RuntimeModuleEntry, List<RuntimeModuleEntry>>,
    ): Boolean {
        val segments = marker.split('/')
        if (segments.size !in 1..2) return false
        if (segments.size == 1) return childMatches(segments[0], children)

        val directoryName = segments[0]
        if (directoryName.contains('*') || directoryName.contains('?')) return false
        val directory = children.firstOrNull {
            it.isDirectory && it.name.equals(directoryName, ignoreCase = true)
        } ?: return false
        val listing = directoryCache.getOrPut(directory) {
            safely(emptyList()) { childrenOf(directory) }
        }
        return childMatches(segments[1], listing)
    }

    private fun childMatches(pattern: String, children: List<RuntimeModuleEntry>): Boolean {
        if (pattern.isEmpty()) return false
        val hasWildcard = pattern.indexOf('*') >= 0 || pattern.indexOf('?') >= 0
        val lowerPattern = pattern.lowercase(Locale.ROOT)
        return children.any { entry ->
            val name = entry.name.lowercase(Locale.ROOT)
            if (hasWildcard) globMatches(lowerPattern, name) else name == lowerPattern
        }
    }

    /** 单层 glob：`*` 匹配任意长度（可为空），`?` 匹配单个字符。 */
    private fun globMatches(pattern: String, value: String): Boolean {
        var patternIndex = 0
        var valueIndex = 0
        var starIndex = -1
        var starValueIndex = 0
        while (valueIndex < value.length) {
            when {
                patternIndex < pattern.length &&
                    (pattern[patternIndex] == '?' || pattern[patternIndex] == value[valueIndex]) -> {
                    patternIndex++
                    valueIndex++
                }

                patternIndex < pattern.length && pattern[patternIndex] == '*' -> {
                    starIndex = patternIndex
                    starValueIndex = valueIndex
                    patternIndex++
                }

                starIndex >= 0 -> {
                    patternIndex = starIndex + 1
                    starValueIndex++
                    valueIndex = starValueIndex
                }

                else -> return false
            }
        }
        while (patternIndex < pattern.length && pattern[patternIndex] == '*') patternIndex++
        return patternIndex == pattern.length
    }

    private inline fun <T> safely(fallback: T, block: () -> T): T =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            fallback
        }
}
