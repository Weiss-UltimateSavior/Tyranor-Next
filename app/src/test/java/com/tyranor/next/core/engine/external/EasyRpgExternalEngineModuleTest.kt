package com.tyranor.next.core.engine.external

import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.settings.EngineSettingsStore
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class EasyRpgExternalEngineModuleTest {
    @Test
    fun registeredAsTheExternalModuleForEasyRpg() {
        assertSame(EasyRpgExternalEngineModule, ExternalEngineModuleRegistry.moduleForEngine(EngineType.EASYRPG))
        assertSame(EasyRpgExternalEngineModule, ExternalEngineModuleRegistry.resolveModule(EngineType.EASYRPG))
        assertSame(EasyRpgExternalEngineModule, ExternalEngineModuleRegistry.moduleForAlias("internal.easyrpg"))
        assertSame(EasyRpgExternalEngineModule, ExternalEngineModuleRegistry.moduleForAlias("external.easyrpg"))
        assertSame(EasyRpgExternalEngineModule, ExternalEngineModuleRegistry.moduleForAlias(EasyRpgHostContract.PACKAGE))
        assertTrue(ExternalEngineModuleRegistry.isExternalEngine(EngineType.EASYRPG))
    }

    @Test
    fun protocolConstantsMatchFrozenContract() {
        assertEquals("easyrpg", EasyRpgExternalEngineModule.id)
        assertEquals("EasyRPG", EasyRpgExternalEngineModule.displayName)
        assertEquals("com.tyranor.next.easyrpg", EasyRpgExternalEngineModule.packageName)
        assertEquals("com.tyranor.next.easyrpg.action.RUN", EasyRpgExternalEngineModule.action)
        assertEquals("internal.easyrpg", EasyRpgExternalEngineModule.defaultAlias)
        assertEquals(setOf("external.easyrpg"), EasyRpgExternalEngineModule.supportedAliases)
        assertEquals(1, EasyRpgHostContract.VERSION_CODE)
        assertEquals(1L, EasyRpgExternalEngineModule.minimumVersionCode)
        assertEquals(
            ExternalEngineReleaseSource("Weiss-UltimateSavior/Tyranor-Next-EasyRPG", "easyrpg-host-"),
            EasyRpgExternalEngineModule.updateSource,
        )
        assertEquals(
            "https://github.com/Weiss-UltimateSavior/Tyranor-Next-EasyRPG/releases/latest/download/Tyranor-Next-EasyRPG.apk",
            EasyRpgExternalEngineModule.installUrl,
        )
        listOf(
            EasyRpgHostContract.ACTION_RUN,
            EasyRpgHostContract.EXTRA_GAME_DIR,
            EasyRpgHostContract.EXTRA_TITLE,
            EasyRpgHostContract.EXTRA_SETTINGS,
        ).forEach { assertTrue(it, it.startsWith(EasyRpgHostContract.PACKAGE + ".")) }
        assertTrue(EasyRpgExternalEngineModule.requiresGameDirectoryPath)
        assertFalse(EasyRpgExternalEngineModule.launchEntryIsFile)
    }

    @Test
    fun settingsJsonCarriesEngineDefaultsWhenNothingIsResolved() {
        val json = JSONObject(EasyRpgExternalEngineModule.buildSettingsJson(null))
        assertEquals(
            setOf(
                "encoding", "scaling", "gameResolution", "stretch", "vsync",
                "fpsLimit", "showFps", "disableAudio", "musicVolume", "soundVolume",
                "noRtp", "patchMode", "testPlay",
            ),
            json.keys().asSequence().toSet(),
        )
        assertEquals("auto", json.getString("encoding"))
        assertEquals("bilinear", json.getString("scaling"))
        assertEquals("original", json.getString("gameResolution"))
        assertFalse(json.getBoolean("stretch"))
        assertTrue(json.getBoolean("vsync"))
        assertEquals(60, json.getInt("fpsLimit"))
        assertFalse(json.getBoolean("showFps"))
        assertFalse(json.getBoolean("disableAudio"))
        assertEquals(100, json.getInt("musicVolume"))
        assertEquals(100, json.getInt("soundVolume"))
        assertFalse(json.getBoolean("noRtp"))
        assertEquals("auto", json.getString("patchMode"))
        assertFalse(json.getBoolean("testPlay"))
    }

    @Test
    fun settingsJsonReflectsResolvedSettingsAndNormalizesInvalidValues() {
        val json = JSONObject(
            EasyRpgExternalEngineModule.buildSettingsJson(
                EngineSettingsStore.EasyRpg(
                    encoding = " 936 ",
                    scaling = " INTEGER ",
                    gameResolution = " WIDESCREEN ",
                    stretch = true,
                    vsync = false,
                    fpsLimit = 120,
                    showFps = true,
                    disableAudio = true,
                    musicVolume = 45,
                    soundVolume = 80,
                    noRtp = true,
                    patchMode = " MANIAC ",
                    testPlay = true,
                ),
            ),
        )
        assertEquals("936", json.getString("encoding"))
        assertEquals("integer", json.getString("scaling"))
        assertEquals("widescreen", json.getString("gameResolution"))
        assertTrue(json.getBoolean("stretch"))
        assertFalse(json.getBoolean("vsync"))
        assertEquals(120, json.getInt("fpsLimit"))
        assertTrue(json.getBoolean("showFps"))
        assertTrue(json.getBoolean("disableAudio"))
        assertEquals(45, json.getInt("musicVolume"))
        assertEquals(80, json.getInt("soundVolume"))
        assertTrue(json.getBoolean("noRtp"))
        assertEquals("maniac", json.getString("patchMode"))
        assertTrue(json.getBoolean("testPlay"))

        val clamped = JSONObject(
            EasyRpgExternalEngineModule.buildSettingsJson(
                EngineSettingsStore.EasyRpg(
                    encoding = "invalid",
                    scaling = "blur",
                    gameResolution = "4k",
                    fpsLimit = 999,
                    musicVolume = -10,
                    soundVolume = 101,
                    patchMode = "unknown",
                ),
            ),
        )
        assertEquals("auto", clamped.getString("encoding"))
        assertEquals("bilinear", clamped.getString("scaling"))
        assertEquals("original", clamped.getString("gameResolution"))
        assertEquals(60, clamped.getInt("fpsLimit"))
        assertEquals(100, clamped.getInt("musicVolume"))
        assertEquals(100, clamped.getInt("soundVolume"))
        assertEquals("auto", clamped.getString("patchMode"))
    }

    @Test
    fun validatesAbsoluteDirectoryAndResolvesFolderAndTitle() {
        assertNull(EasyRpgExternalEngineModule.validateGameDirectoryPath("/storage/emulated/0/Games/Yume2kki"))
        assertEquals(
            ExternalEngineErrorCode.INVALID_GAME_PATH,
            EasyRpgExternalEngineModule.validateGameDirectoryPath("relative/path")?.error,
        )
        assertEquals(
            ExternalEngineErrorCode.INVALID_GAME_PATH,
            EasyRpgExternalEngineModule.validateGameDirectoryPath("   ")?.error,
        )

        val request = ExternalEngineLaunchRequest(
            game = ScanGame(
                title = "",
                uri = "file:///storage/emulated/0/Games/Yume2kki",
                engine = EngineType.EASYRPG,
                launchTarget = "DIR",
            ),
            gameDirectoryPath = " /storage/emulated/0/Games/Yume2kki/ ",
        )
        val folder = EasyRpgExternalEngineModule.resolveLaunchFolder(request)
        assertEquals("/storage/emulated/0/Games/Yume2kki", folder)
        assertEquals("Yume2kki", EasyRpgExternalEngineModule.resolveLaunchTitle(request, folder))

        val explicitTitleRequest = request.copy(game = request.game.copy(title = "ゆめ2っき"))
        assertEquals("ゆめ2っき", EasyRpgExternalEngineModule.resolveLaunchTitle(explicitTitleRequest, folder))
    }
}
