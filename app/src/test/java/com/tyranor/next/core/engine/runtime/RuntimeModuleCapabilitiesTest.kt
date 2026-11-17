package com.tyranor.next.core.engine.runtime

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeModuleCapabilitiesTest {

    @Test
    fun settingsAndSavesParseAlongsideScan() {
        val manifest = requireNotNull(RuntimeModuleManifestParser.parse(
            """{"scan":{"markers":["game.dat"]},"settings":{"version":1,"items":[
                {"key":"vsync","type":"boolean","title":"VSync","default":true},
                {"key":"quality","type":"enum","title":"Quality","default":"auto","options":[
                  {"value":"auto","title":"Auto"},{"value":"high","title":"High"}]},
                {"key":"name","type":"text","title":"Name","default":"","maxLength":32},
                {"key":"fps","type":"integer","title":"FPS","default":60,"min":30,"max":120}
            ]},"saves":{"version":1,"directory":"data/save"}}""",
        ))
        assertEquals(1, manifest.settings?.version)
        assertEquals(4, manifest.settings?.items?.size)
        assertEquals("data/save", manifest.saves?.directory)
        assertEquals(listOf("game.dat"), manifest.scan?.markers)
    }

    @Test
    fun invalidCapabilitiesFailIndependently() {
        val manifest = requireNotNull(RuntimeModuleManifestParser.parse(
            """{"scan":{"markers":["game.dat"]},"settings":{"version":7,"items":[]},
                "saves":{"version":1,"directory":"../outside"}}""",
        ))
        assertNull(manifest.settings)
        assertNull(manifest.saves)
        assertEquals(listOf("game.dat"), manifest.scan?.markers)
        assertTrue(manifest.settingsDiagnostic!!.contains("version"))
        assertTrue(manifest.savesDiagnostic!!.contains("directory"))
    }

    @Test
    fun badItemsAreDroppedAndFirstDuplicateKeyWins() {
        val manifest = requireNotNull(RuntimeModuleManifestParser.parse(
            """{"settings":{"version":1,"items":[
                {"key":"bad-key","type":"boolean","title":"Bad","default":true},
                {"key":"flag","type":"boolean","title":"First","default":false},
                {"key":"flag","type":"boolean","title":"Second","default":true},
                {"key":"fraction","type":"integer","title":"Fraction","default":1.2,"min":0,"max":2}
            ]}}""",
        ))
        assertEquals(1, manifest.settings?.items?.size)
        assertEquals("First", manifest.settings?.items?.single()?.title)
    }

    @Test
    fun resolutionPreservesFalseZeroAndEmptyStringAndFallsBackOnInvalid() {
        val spec = requireNotNull(RuntimeModuleManifestParser.parse(
            """{"settings":{"version":1,"items":[
                {"key":"flag","type":"boolean","title":"Flag","default":true},
                {"key":"count","type":"integer","title":"Count","default":4,"min":0,"max":10},
                {"key":"text","type":"text","title":"Text","default":"default","maxLength":10}
            ]}}""",
        )?.settings)
        val effective = spec.resolve(JSONObject().put("flag", false).put("count", 0).put("text", ""))
        assertEquals(false, effective.values["flag"])
        assertEquals(0, effective.values["count"])
        assertEquals("", effective.values["text"])
        assertTrue(effective.invalidOverrideKeys.isEmpty())
        assertTrue(spec.encode(effective)!!.toByteArray(Charsets.UTF_8).size <= RuntimeModuleSettingsParser.MAX_PAYLOAD_BYTES)

        val invalid = spec.resolve(JSONObject().put("count", 99).put("unknown", true))
        assertEquals(4, invalid.values["count"])
        assertEquals(setOf("count"), invalid.invalidOverrideKeys)
        assertFalse(invalid.values.containsKey("unknown"))
    }

    @Test
    fun saveDirectoryRejectsTraversalAndAcceptsNestedRelativeDirectory() {
        listOf("", ".", "..", "a/../b", "/save", "C:/save", "content://provider", "a\\b", "a//b", "a/")
            .forEach { path -> assertNull(RuntimeModuleManifestParser.parse("""{"saves":{"version":1,"directory":"$path"}}""")?.saves) }
        assertEquals("save/slot1", RuntimeModuleManifestParser.parse("""{"saves":{"version":1,"directory":"save/slot1"}}""")?.saves?.directory)
    }

    @Test
    fun settingsFingerprintTracksNormalizedDeclaration() {
        fun spec(default: Boolean) = RuntimeModuleManifestParser.parse(
            """{"settings":{"version":1,"items":[{"key":"flag","type":"boolean","title":"Flag","default":$default}]}}""",
        )!!.settings!!
        assertEquals(spec(false).fingerprint, spec(false).fingerprint)
        assertTrue(spec(false).fingerprint != spec(true).fingerprint)
    }

    @Test
    fun aggregateBudgetClosesOnlySettingsCapability() {
        val items = (0 until 32).joinToString(",") { index ->
            """{"key":"text$index","type":"text","title":"Text","default":"","maxLength":1024}"""
        }
        val manifest = requireNotNull(RuntimeModuleManifestParser.parse(
            """{"scan":{"markers":["game.dat"]},"settings":{"version":1,"items":[$items]},
                "saves":{"version":1,"directory":"save"}}""",
        ))
        assertNull(manifest.settings)
        assertTrue(manifest.settingsDiagnostic!!.contains("48 KiB"))
        assertEquals(listOf("game.dat"), manifest.scan?.markers)
    }
}
