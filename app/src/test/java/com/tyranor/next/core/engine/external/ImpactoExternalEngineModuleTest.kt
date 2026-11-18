package com.tyranor.next.core.engine.external

import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.settings.EngineSettingsStore
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ImpactoExternalEngineModuleTest {
    @Test
    fun registeredAsTheExternalModuleForImpacto() {
        assertSame(ImpactoExternalEngineModule, ExternalEngineModuleRegistry.moduleForEngine(EngineType.IMPACTO))
        assertSame(ImpactoExternalEngineModule, ExternalEngineModuleRegistry.moduleForAlias("internal.impacto"))
        assertSame(ImpactoExternalEngineModule, ExternalEngineModuleRegistry.moduleForAlias(ImpactoHostContract.PACKAGE))
        assertTrue(ExternalEngineModuleRegistry.isExternalEngine(EngineType.IMPACTO))
    }

    @Test
    fun protocolConstantsAreNamespacedToTheHostPackage() {
        assertEquals("com.tyranor.next.impacto", ImpactoExternalEngineModule.packageName)
        listOf(
            ImpactoHostContract.ACTION_RUN,
            ImpactoHostContract.EXTRA_GAME_DIR,
            ImpactoHostContract.EXTRA_PROFILE,
            ImpactoHostContract.EXTRA_GAME_KEY,
            ImpactoHostContract.EXTRA_TITLE,
            ImpactoHostContract.EXTRA_SETTINGS,
        ).forEach { assertTrue(it, it.startsWith(ImpactoHostContract.PACKAGE + ".")) }
        // Always the latest published host APK (fixed asset name), so new host releases need no app change.
        assertEquals(
            "https://github.com/yuukana1/Tyranor-Next-Impacto/releases/latest/download/impacto-host.apk",
            ImpactoExternalEngineModule.installUrl,
        )
        assertEquals(ImpactoHostContract.VERSION_CODE.toLong(), ImpactoExternalEngineModule.minimumVersionCode)
        assertTrue(ImpactoExternalEngineModule.minimumVersionCode > 0L)
        assertTrue(ImpactoExternalEngineModule.requiresGameDirectoryPath)
        assertFalse(ImpactoExternalEngineModule.launchEntryIsFile)
    }

    @Test
    fun minimumHostVersionIsTheOneThatUnderstandsSettings() {
        // 宿主 0.1.2（versionCode 3）起认识 settings extra；更老的宿主会忽略它，设置不会生效，所以按“需要更新”处理。
        assertEquals(3, ImpactoHostContract.VERSION_CODE)
    }

    @Test
    fun settingsJsonCarriesTheEngineDefaultsWhenNothingIsResolved() {
        val json = JSONObject(ImpactoExternalEngineModule.buildSettingsJson(null))
        assertEquals(
            setOf(
                "swapConfirmCancel", "vsync", "videoPlayback", "subtitles",
                "closeBacklogAtEnd", "delusionMouse", "logLevel",
            ),
            json.keys().asSequence().toSet(),
        )
        assertFalse(json.getBoolean("swapConfirmCancel"))
        assertTrue(json.getBoolean("vsync"))
        assertTrue(json.getBoolean("videoPlayback"))
        assertEquals("all", json.getString("subtitles"))
        assertTrue(json.getBoolean("closeBacklogAtEnd"))
        assertTrue(json.getBoolean("delusionMouse"))
        assertEquals("warning", json.getString("logLevel"))
    }

    @Test
    fun settingsJsonReflectsTheResolvedSettingsAndNormalisesTheLogLevel() {
        val json = JSONObject(
            ImpactoExternalEngineModule.buildSettingsJson(
                EngineSettingsStore.Impacto(
                    swapConfirmCancel = true,
                    vsync = false,
                    videoPlayback = false,
                    subtitles = "translation",
                    closeBacklogAtEnd = false,
                    delusionMouse = false,
                    logLevel = " DEBUG ",
                ),
            ),
        )
        assertTrue(json.getBoolean("swapConfirmCancel"))
        assertFalse(json.getBoolean("vsync"))
        assertFalse(json.getBoolean("videoPlayback"))
        assertEquals("translation", json.getString("subtitles"))
        assertFalse(json.getBoolean("closeBacklogAtEnd"))
        assertFalse(json.getBoolean("delusionMouse"))
        assertEquals("debug", json.getString("logLevel"))
        // 非法日志等级不会原样下发给宿主
        val bad = JSONObject(
            ImpactoExternalEngineModule.buildSettingsJson(EngineSettingsStore.Impacto(logLevel = "loud")),
        )
        assertEquals("warning", bad.getString("logLevel"))
    }

    @Test
    fun gameKeyMatchesTheHostApksGoldenVector() {
        // The host APK recomputes this key and refuses a request whose key does not belong to its folder.
        // The same vector is asserted by the host's ImpactoRequestTest; both derivations must stay identical.
        assertEquals(
            "853382f8b09761cd5c6e475c",
            ImpactoExternalEngineModule.gameKeyFor("/storage/emulated/0/Games/mo6tw", "mo6tw"),
        )
    }

    @Test
    fun gameKeyIsStableAndSeparatesGamesAndProfiles() {
        val folder = "/storage/emulated/0/Games/mo6tw"
        val key = ImpactoExternalEngineModule.gameKeyFor(folder, "mo6tw")
        assertEquals(key, ImpactoExternalEngineModule.gameKeyFor(folder, "mo6tw"))
        assertTrue(Regex("[0-9a-f]{24}").matches(key))
        assertNotEquals(key, ImpactoExternalEngineModule.gameKeyFor("$folder-2", "mo6tw"))
        assertNotEquals(key, ImpactoExternalEngineModule.gameKeyFor(folder, "chlcc"))
    }
}
