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
    fun cardsAndNewSettingTypesEncodeAsFlatV1Values() {
        val spec = requireNotNull(RuntimeModuleManifestParser.parse(
            """{"settings":{"version":1,"items":[
                {"key":"display","type":"card","title":"Display","description":"Rendering","items":[
                  {"key":"scale","type":"slider","title":"Scale","default":1.0,"min":0.5,"max":2.0,"step":0.1},
                  {"key":"config","type":"file","title":"Config","default":""},
                  {"key":"assets","type":"folder","title":"Assets","default":""}
                ]},
                {"key":"fps","type":"integer","title":"FPS","default":60,"min":30,"max":120}
            ]}}""",
        )?.settings)

        assertEquals(4, spec.items.size)
        assertEquals(2, spec.entries.size)
        assertTrue(spec.entries.first() is RuntimeModuleSettingsEntry.Card)
        assertEquals(
            listOf(
                RuntimeModuleSettingItemSpec.Type.SLIDER,
                RuntimeModuleSettingItemSpec.Type.FILE,
                RuntimeModuleSettingItemSpec.Type.FOLDER,
                RuntimeModuleSettingItemSpec.Type.INTEGER,
            ),
            spec.items.map { it.type },
        )

        val effective = spec.resolve(
            JSONObject()
                .put("scale", 1.5)
                .put("config", "/storage/emulated/0/game/config.ini")
                .put("assets", "/storage/emulated/0/game/assets")
                .put("fps", 90),
        )
        val payload = JSONObject(requireNotNull(spec.encode(effective)))
        val values = payload.getJSONObject("values")

        assertEquals(1, payload.getInt("version"))
        assertEquals(4, values.length())
        assertEquals(1.5, values.getDouble("scale"), 0.000001)
        assertEquals("/storage/emulated/0/game/config.ini", values.getString("config"))
        assertEquals("/storage/emulated/0/game/assets", values.getString("assets"))
        assertEquals(90, values.getInt("fps"))
        assertFalse(values.has("display"))
    }

    @Test
    fun sliderAndPathValuesEnforceDeclaredConstraints() {
        val spec = requireNotNull(RuntimeModuleManifestParser.parse(
            """{"settings":{"version":1,"items":[
                {"key":"scale","type":"slider","title":"Scale","default":1.0,"min":0.5,"max":2.0,"step":0.1},
                {"key":"config","type":"file","title":"Config","default":""},
                {"key":"assets","type":"folder","title":"Assets","default":""}
            ]}}""",
        )?.settings)
        val scale = spec.items.first { it.key == "scale" }
        val file = spec.items.first { it.key == "config" }
        val folder = spec.items.first { it.key == "assets" }

        assertTrue(RuntimeModuleSettingsParser.isValidValue(scale, 1.5))
        assertFalse(RuntimeModuleSettingsParser.isValidValue(scale, 1.55))
        assertFalse(RuntimeModuleSettingsParser.isValidValue(scale, 2.1))
        assertTrue(RuntimeModuleSettingsParser.isValidValue(file, "/storage/emulated/0/game.ini"))
        assertFalse(RuntimeModuleSettingsParser.isValidValue(file, "content://provider/document/1"))
        assertFalse(RuntimeModuleSettingsParser.isValidValue(file, "/storage/emulated/0/../private.ini"))
        assertTrue(RuntimeModuleSettingsParser.isValidValue(folder, "/storage/emulated/0/assets"))
        assertFalse(RuntimeModuleSettingsParser.isValidValue(folder, "relative/assets"))
    }

    @Test
    fun invalidSliderAndPathDeclarationsAreRejected() {
        assertNull(RuntimeModuleSettingsParser.parseItem(JSONObject(
            """{"key":"scale","type":"slider","title":"Scale","default":1.05,"min":0.5,"max":2.0,"step":0.1}""",
        )))
        assertNull(RuntimeModuleSettingsParser.parseItem(JSONObject(
            """{"key":"config","type":"file","title":"Config","default":"content://provider/document/1"}""",
        )))
        assertNull(RuntimeModuleSettingsParser.parseItem(JSONObject(
            """{"key":"assets","type":"folder","title":"Assets","default":"/storage/emulated/0/../private"}""",
        )))
    }

    @Test
    fun steppedSliderSupportDoesNotDependOnAbsoluteFloatTicks() {
        val collapsedFloatRange = RuntimeModuleSettingsParser.parseItem(JSONObject(
            """{"key":"scale","type":"slider","title":"Scale","default":16777219,"min":16777219,"max":16777220,"step":0.5}""",
        ))
        val collapsedIntermediateTicks = RuntimeModuleSettingsParser.parseItem(JSONObject(
            """{"key":"scale","type":"slider","title":"Scale","default":16777218,"min":16777218,"max":16777224,"step":1}""",
        ))
        val unsupportedContinuousRange = RuntimeModuleSettingsParser.parseItem(JSONObject(
            """{"key":"scale","type":"slider","title":"Scale","default":16777219,"min":16777219,"max":16777220}""",
        ))

        assertTrue(collapsedFloatRange != null)
        assertTrue(collapsedIntermediateTicks != null)
        assertNull(unsupportedContinuousRange)
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
        assertEquals("aa606c11cacc0ea7522e29fdd4b43a4a19ea37306e2359d3db4e2e2195d58c10", spec(false).fingerprint)
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
