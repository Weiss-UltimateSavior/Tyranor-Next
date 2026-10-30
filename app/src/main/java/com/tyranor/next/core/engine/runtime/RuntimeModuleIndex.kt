package com.tyranor.next.core.engine.runtime

/**
 * 一次扫描开始时的可用模块快照（已启用 + 有扫描描述）。
 *
 * 多模块命中按「ALL 优先于 ANY → marker 数量多优先 → packageName 排序」稳定选择，
 * 保证同一目录在重复扫描中得到确定结果，不阻塞扫描询问用户。
 */
class RuntimeModuleIndex private constructor(val modules: List<RuntimeModuleDescriptor>) {

    fun match(
        children: List<RuntimeModuleEntry>,
        childrenOf: (RuntimeModuleEntry) -> List<RuntimeModuleEntry>,
    ): RuntimeModuleDescriptor? {
        // 多个模块可能声明同一个 `data/*.pak`：索引级共享一次子目录读取，避免 SAF 重复往返。
        val directoryCache = HashMap<RuntimeModuleEntry, List<RuntimeModuleEntry>>()
        val cachedChildrenOf: (RuntimeModuleEntry) -> List<RuntimeModuleEntry> = { entry ->
            directoryCache.getOrPut(entry) { childrenOf(entry) }
        }
        return modules.asSequence()
            .filter { it.enabled }
            .mapNotNull { module ->
                val spec = module.scan ?: return@mapNotNull null
                module.takeIf { RuntimeModuleMatcher.matches(spec, children, cachedChildrenOf) }
            }
            .minWithOrNull(PRIORITY)
    }

    companion object {
        val EMPTY: RuntimeModuleIndex = RuntimeModuleIndex(emptyList())

        fun of(modules: List<RuntimeModuleDescriptor>): RuntimeModuleIndex {
            if (modules.isEmpty()) return EMPTY
            return RuntimeModuleIndex(modules.sortedBy { it.packageName })
        }

        private val PRIORITY = compareBy<RuntimeModuleDescriptor>(
            { if (it.scan?.match == RuntimeModuleScanSpec.Match.ALL) 0 else 1 },
            { -(it.scan?.markers?.size ?: 0) },
            { it.packageName },
        )
    }
}
