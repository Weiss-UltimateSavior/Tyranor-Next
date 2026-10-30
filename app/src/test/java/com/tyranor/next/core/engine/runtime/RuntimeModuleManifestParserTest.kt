package com.tyranor.next.core.engine.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 模块描述文件容错解析：字段缺省、非法 JSON、路径/通配校验与数量上限。
 * 解析失败只关闭自动扫描能力，不抛出未受控异常。
 */
class RuntimeModuleManifestParserTest {

    @Test
    fun parsesValidManifestFields() {
        val manifest = RuntimeModuleManifestParser.parse(
            """
            {
              "protocol": 2,
              "label": "My Engine",
              "scan": {
                "match": "all",
                "extensions": [".MGE"],
                "markers": ["Game.dat", "DATA/*.pak"]
              }
            }
            """.trimIndent(),
        )

        requireNotNull(manifest)
        assertEquals(2, manifest.protocol)
        assertEquals("My Engine", manifest.label)
        val scan = requireNotNull(manifest.scan)
        assertEquals(RuntimeModuleScanSpec.Match.ALL, scan.match)
        assertEquals(listOf(".mge"), scan.extensions)
        assertEquals(listOf("game.dat", "data/*.pak"), scan.markers)
    }

    @Test
    fun missingProtocolDefaultsToOneAndMatchDefaultsToAny() {
        val manifest = requireNotNull(
            RuntimeModuleManifestParser.parse("""{"scan":{"markers":["game.dat"]}}"""),
        )

        assertEquals(RuntimeModuleContract.PROTOCOL_VERSION, manifest.protocol)
        assertNull(manifest.label)
        assertEquals(RuntimeModuleScanSpec.Match.ANY, manifest.scan?.match)
        assertEquals(listOf("game.dat"), manifest.scan?.markers)
    }

    @Test
    fun invalidJsonReturnsNull() {
        assertNull(RuntimeModuleManifestParser.parse(null))
        assertNull(RuntimeModuleManifestParser.parse(""))
        assertNull(RuntimeModuleManifestParser.parse("   "))
        assertNull(RuntimeModuleManifestParser.parse("{not json"))
        assertNull(RuntimeModuleManifestParser.parse("""["array"]"""))
    }

    @Test
    fun oversizedManifestReturnsNull() {
        val padding = "a".repeat(RuntimeModuleManifestParser.MAX_BYTES + 1)
        assertNull(RuntimeModuleManifestParser.parse("""{"label":"$padding"}"""))
    }

    @Test
    fun scanIsNullWhenNoValidPatternSurvives() {
        val manifest = requireNotNull(
            RuntimeModuleManifestParser.parse(
                """{"scan":{"extensions":["mge",""],"markers":["..","/abs/x","a\\b","**","data/*/x"]}}""",
            ),
        )

        assertNull(manifest.scan)
    }

    @Test
    fun dropsUnsafePatternsAndKeepsValidOnes() {
        val manifest = requireNotNull(
            RuntimeModuleManifestParser.parse(
                """
                {"scan":{
                  "extensions":["mge",".MGE",".",".toolongextension1",".*"],
                  "markers":[
                    "/etc/passwd","C:/games/x","..","../escape","dir\\file","a**b","",
                    ".","*/*","*","?","*?*","data/*/x.pak","data/*.pak","Game.Dat"
                  ]
                }}
                """.trimIndent(),
            ),
        )

        val scan = requireNotNull(manifest.scan)
        // 必须带前导点；不能含通配/空白；大小写重复项按归一结果去重。
        assertEquals(listOf(".mge"), scan.extensions)
        // 绝对路径、`..`、反斜杠、`**`、空串、中间段/多层通配全部丢弃。
        assertEquals(listOf("data/*.pak", "game.dat"), scan.markers)
    }

    @Test
    fun dropsPatternsOutsideLengthLimits() {
        val maxExtension = "." + "e".repeat(RuntimeModulePatterns.MAX_EXTENSION_LENGTH - 1)
        val longExtension = "." + "e".repeat(RuntimeModulePatterns.MAX_EXTENSION_LENGTH)
        val maxMarker = "m".repeat(RuntimeModulePatterns.MAX_MARKER_LENGTH)
        val longMarker = "m".repeat(RuntimeModulePatterns.MAX_MARKER_LENGTH + 1)

        val manifest = requireNotNull(
            RuntimeModuleManifestParser.parse(
                """{"scan":{"extensions":["$maxExtension","$longExtension"],"markers":["$maxMarker","$longMarker"]}}""",
            ),
        )

        val scan = requireNotNull(manifest.scan)
        assertEquals(listOf(maxExtension), scan.extensions)
        assertEquals(listOf(maxMarker), scan.markers)
    }

    @Test
    fun truncatesPatternsAtProtocolLimits() {
        val extensions = (0 until RuntimeModulePatterns.MAX_EXTENSIONS + 8)
            .joinToString(",") { "\".e$it\"" }
        val markers = (0 until RuntimeModulePatterns.MAX_MARKERS + 6)
            .joinToString(",") { "\"m$it.dat\"" }

        val manifest = requireNotNull(
            RuntimeModuleManifestParser.parse("""{"scan":{"extensions":[$extensions],"markers":[$markers]}}"""),
        )

        val scan = requireNotNull(manifest.scan)
        assertEquals(RuntimeModulePatterns.MAX_EXTENSIONS, scan.extensions.size)
        assertEquals(RuntimeModulePatterns.MAX_MARKERS, scan.markers.size)
    }

    @Test
    fun scanAbsentMeansNoAutomaticScanning() {
        val manifest = requireNotNull(RuntimeModuleManifestParser.parse("""{"label":"Manual Only"}"""))

        assertNull(manifest.scan)
        assertEquals("Manual Only", manifest.label)
    }
}
