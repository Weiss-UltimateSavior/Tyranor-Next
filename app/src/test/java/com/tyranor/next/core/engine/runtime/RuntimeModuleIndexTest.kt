package com.tyranor.next.core.engine.runtime

import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 索引级多模块选优：ALL 优先于 ANY → marker 数量多优先 → 包名排序兜底；
 * 只考虑已启用且声明了扫描描述的模块。
 */
class RuntimeModuleIndexTest {

    private fun module(
        packageName: String,
        match: RuntimeModuleScanSpec.Match = RuntimeModuleScanSpec.Match.ANY,
        extensions: List<String> = emptyList(),
        markers: List<String> = emptyList(),
        enabled: Boolean = true,
        scan: RuntimeModuleScanSpec? = RuntimeModuleScanSpec(match, extensions, markers),
    ) = RuntimeModuleDescriptor(
        packageName = packageName,
        label = packageName,
        versionName = "1.0",
        versionCode = 1L,
        protocol = RuntimeModuleContract.PROTOCOL_VERSION,
        activityName = "$packageName.RuntimeActivity",
        scan = scan,
        enabled = enabled,
    )

    private fun file(name: String) = RuntimeModuleEntry(name = name, isDirectory = false)

    private val noChildren: (RuntimeModuleEntry) -> List<RuntimeModuleEntry> = { emptyList() }

    @Test
    fun allMatchWinsOverAnyEvenWhenPackageSortsLater() {
        val anyModule = module(
            packageName = "com.tyranor.runtime.aaa",
            match = RuntimeModuleScanSpec.Match.ANY,
            markers = listOf("a", "b", "c"),
        )
        val allModule = module(
            packageName = "com.tyranor.runtime.zzz",
            match = RuntimeModuleScanSpec.Match.ALL,
            markers = listOf("a"),
        )
        val index = RuntimeModuleIndex.of(listOf(anyModule, allModule))

        val hit = index.match(listOf(file("a"), file("b"), file("c")), noChildren)

        assertSame(allModule, hit)
    }

    @Test
    fun markerCountBreaksTieBetweenAnyModules() {
        val single = module(
            packageName = "com.tyranor.runtime.aaa",
            markers = listOf("a"),
        )
        val multiple = module(
            packageName = "com.tyranor.runtime.zzz",
            markers = listOf("a", "b"),
        )
        val index = RuntimeModuleIndex.of(listOf(single, multiple))

        assertSame(multiple, index.match(listOf(file("a"), file("b")), noChildren))
    }

    @Test
    fun packageNameSortsModulesWithEqualPriority() {
        val beta = module(packageName = "com.tyranor.runtime.beta", markers = listOf("a"))
        val alpha = module(packageName = "com.tyranor.runtime.alpha", markers = listOf("a"))
        val index = RuntimeModuleIndex.of(listOf(beta, alpha))

        assertSame(alpha, index.match(listOf(file("a")), noChildren))
    }

    @Test
    fun emptyIndexNeverMatches() {
        assertNull(RuntimeModuleIndex.EMPTY.match(listOf(file("game.dat")), noChildren))
        assertNull(RuntimeModuleIndex.of(emptyList()).match(listOf(file("game.dat")), noChildren))
    }

    @Test
    fun disabledModuleIsIgnored() {
        val disabled = module(
            packageName = "com.tyranor.runtime.disabled",
            markers = listOf("game.dat"),
            enabled = false,
        )

        assertNull(RuntimeModuleIndex.of(listOf(disabled)).match(listOf(file("game.dat")), noChildren))
    }

    @Test
    fun moduleWithoutScanSpecIsIgnored() {
        val noScan = module(
            packageName = "com.tyranor.runtime.noscan",
            enabled = true,
            scan = null,
        )
        val fallback = module(
            packageName = "com.tyranor.runtime.fallback",
            markers = listOf("game.dat"),
        )
        val index = RuntimeModuleIndex.of(listOf(noScan, fallback))

        assertSame(fallback, index.match(listOf(file("game.dat")), noChildren))
    }
}
