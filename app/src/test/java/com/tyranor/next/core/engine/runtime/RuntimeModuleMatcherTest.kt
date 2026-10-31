package com.tyranor.next.core.engine.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第三方运行时匹配器：扩展名只看直接子文件、marker 支持直接项与单层通配，
 * ANY / ALL 语义与非法/超限模式防御。
 */
class RuntimeModuleMatcherTest {

    private fun spec(
        match: RuntimeModuleScanSpec.Match = RuntimeModuleScanSpec.Match.ANY,
        extensions: List<String> = emptyList(),
        markers: List<String> = emptyList(),
    ) = RuntimeModuleScanSpec(match = match, extensions = extensions, markers = markers)

    private fun file(name: String) = RuntimeModuleEntry(name = name, isDirectory = false)

    private fun dir(name: String) = RuntimeModuleEntry(name = name, isDirectory = true)

    private fun childrenOf(
        vararg pairs: Pair<RuntimeModuleEntry, List<RuntimeModuleEntry>>,
    ): (RuntimeModuleEntry) -> List<RuntimeModuleEntry> {
        val map = pairs.toMap()
        return { entry -> map[entry].orEmpty() }
    }

    @Test
    fun extensionMatchesOnlyDirectChildFilesIgnoringCase() {
        val spec = spec(extensions = listOf(".mge"))

        assertTrue(RuntimeModuleMatcher.matches(spec, listOf(file("GAME.MGE")), childrenOf()))
        // 同名目录不算扩展名命中
        assertFalse(RuntimeModuleMatcher.matches(spec, listOf(dir("GAME.MGE")), childrenOf()))
        // 子目录里的文件不在直接子项列表，扩展名谓词不递归
        assertFalse(
            RuntimeModuleMatcher.matches(
                spec,
                listOf(dir("sub")),
                childrenOf(dir("sub") to listOf(file("nested.mge"))),
            ),
        )
        assertFalse(RuntimeModuleMatcher.matches(spec, listOf(file("game.dat")), childrenOf()))
    }

    @Test
    fun extensionPatternIsNormalizedBeforeMatching() {
        // 手写 spec 的大写扩展名同样按大小写不敏感处理
        assertTrue(
            RuntimeModuleMatcher.matches(
                spec(extensions = listOf(".MGE")),
                listOf(file("game.mge")),
                childrenOf(),
            ),
        )
    }

    @Test
    fun markerMatchesDirectFileOrDirectory() {
        assertTrue(
            RuntimeModuleMatcher.matches(
                spec(markers = listOf("game.dat")),
                listOf(file("GAME.DAT")),
                childrenOf(),
            ),
        )
        assertTrue(
            RuntimeModuleMatcher.matches(
                spec(markers = listOf("data")),
                listOf(dir("Data")),
                childrenOf(),
            ),
        )
    }

    @Test
    fun markerWildcardChecksOnlyOneLevelBelowNamedDirectory() {
        val spec = spec(markers = listOf("data/*.pak"))

        assertTrue(
            RuntimeModuleMatcher.matches(
                spec,
                listOf(dir("DATA")),
                childrenOf(dir("DATA") to listOf(file("ONE.PAK"))),
            ),
        )
        // 只看 data/ 下一层：data/sub/one.pak 不命中
        assertFalse(
            RuntimeModuleMatcher.matches(
                spec,
                listOf(dir("data")),
                childrenOf(dir("data") to listOf(dir("sub"))),
            ),
        )
        // 目录必须真实存在且是目录
        assertFalse(
            RuntimeModuleMatcher.matches(
                spec,
                listOf(file("data")),
                childrenOf(file("data") to listOf(file("one.pak"))),
            ),
        )
    }

    @Test
    fun markerStarMatchesAnyDirectChildOfDirectory() {
        val spec = spec(markers = listOf("data/*"))

        assertTrue(
            RuntimeModuleMatcher.matches(
                spec,
                listOf(dir("data")),
                childrenOf(dir("data") to listOf(file("anything.bin"))),
            ),
        )
        assertTrue(
            RuntimeModuleMatcher.matches(
                spec,
                listOf(dir("data")),
                childrenOf(dir("data") to listOf(dir("nested"))),
            ),
        )
        assertFalse(
            RuntimeModuleMatcher.matches(
                spec,
                listOf(dir("data")),
                childrenOf(dir("data") to emptyList()),
            ),
        )
    }

    @Test
    fun markerNestedFilePathMatchesExactly() {
        val spec = spec(markers = listOf("game/script.rpy"))

        assertTrue(
            RuntimeModuleMatcher.matches(
                spec,
                listOf(dir("game")),
                childrenOf(dir("game") to listOf(file("script.rpy"))),
            ),
        )
        assertFalse(
            RuntimeModuleMatcher.matches(
                spec,
                listOf(dir("game")),
                childrenOf(dir("game") to listOf(file("options.rpy"))),
            ),
        )
    }

    @Test
    fun anyMatchesExtensionOrSingleMarker() {
        val spec = spec(extensions = listOf(".mge"), markers = listOf("game.dat"))

        // 扩展名命中
        assertTrue(RuntimeModuleMatcher.matches(spec, listOf(file("game.mge")), childrenOf()))
        // marker 命中
        assertTrue(RuntimeModuleMatcher.matches(spec, listOf(file("game.dat")), childrenOf()))
        // 两者都不命中
        assertFalse(RuntimeModuleMatcher.matches(spec, listOf(file("readme.txt")), childrenOf()))
    }

    @Test
    fun allRequiresExtensionAndEveryMarker() {
        val spec = spec(
            match = RuntimeModuleScanSpec.Match.ALL,
            extensions = listOf(".mge"),
            markers = listOf("game.dat", "data/*.pak"),
        )
        val dataDir = dir("data")
        val dataChildren = childrenOf(dataDir to listOf(file("one.pak")))

        // 全中
        assertTrue(
            RuntimeModuleMatcher.matches(
                spec,
                listOf(file("game.mge"), file("game.dat"), dataDir),
                dataChildren,
            ),
        )
        // 只有扩展名，缺 marker
        assertFalse(
            RuntimeModuleMatcher.matches(
                spec,
                listOf(file("game.mge")),
                childrenOf(),
            ),
        )
        // marker 齐全但缺扩展名
        assertFalse(
            RuntimeModuleMatcher.matches(
                spec,
                listOf(file("game.dat"), dataDir),
                dataChildren,
            ),
        )
    }

    @Test
    fun allWithoutExtensionsRequiresEveryMarker() {
        val spec = spec(
            match = RuntimeModuleScanSpec.Match.ALL,
            markers = listOf("game.dat", "data/*.pak"),
        )
        val dataDir = dir("data")
        val dataChildren = childrenOf(dataDir to listOf(file("one.pak")))

        assertTrue(
            RuntimeModuleMatcher.matches(
                spec,
                listOf(file("game.dat"), dataDir),
                dataChildren,
            ),
        )
        assertFalse(
            RuntimeModuleMatcher.matches(
                spec,
                listOf(file("game.dat")),
                childrenOf(),
            ),
        )
    }

    @Test
    fun emptySpecNeverMatches() {
        assertFalse(RuntimeModuleMatcher.matches(spec(), listOf(file("game.dat")), childrenOf()))
        assertFalse(
            RuntimeModuleMatcher.matches(
                spec(match = RuntimeModuleScanSpec.Match.ALL),
                listOf(file("game.dat")),
                childrenOf(),
            ),
        )
    }

    @Test
    fun invalidMarkersAreIgnoredWithoutThrowing() {
        val dangerous = spec(
            markers = listOf(
                "../escape",
                "/abs/path",
                "C:/windows/path",
                "dir\\file",
                "**",
                "",
                "*/*",
                "data/*/nested.pak",
            ),
        )

        assertFalse(RuntimeModuleMatcher.matches(dangerous, listOf(file("game.dat")), childrenOf()))
    }

    @Test
    fun overLimitPatternsAreTruncatedWithoutThrowing() {
        // 超过上限的有效模式在匹配阶段被截断（防御手写 spec），全程不抛异常。
        val markers = (0 until RuntimeModulePatterns.MAX_MARKERS + 20).map { "miss$it.dat" }
        val extensions = (0 until RuntimeModulePatterns.MAX_EXTENSIONS + 20).map { ".x$it" }

        assertFalse(
            RuntimeModuleMatcher.matches(
                spec(extensions = extensions, markers = markers),
                listOf(file("game.mge"), file("game.dat")),
                childrenOf(),
            ),
        )
    }

    @Test
    fun throwingChildrenLookupIsTreatedAsMiss() {
        val spec = spec(markers = listOf("data/*.pak"))

        assertFalse(
            RuntimeModuleMatcher.matches(
                spec,
                listOf(dir("data")),
                childrenOf = { throw IllegalStateException("provider died") },
            ),
        )
    }
}
