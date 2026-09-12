package com.tyranor.next.core.engine.external

import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.settings.EngineSettingsStore
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RpgMakerExternalEngineModuleTest {
    @Test
    fun resolvesSubtypeFromExternalModuleAlias() {
        assertEquals("rpgmxp", RpgMakerExternalEngineModule.resolveGameType(request("internal.rpgmxp")))
        assertEquals("rpgmvx", RpgMakerExternalEngineModule.resolveGameType(request("internal.rpgmvx")))
        assertEquals("rpgmvxace", RpgMakerExternalEngineModule.resolveGameType(request("internal.rpgmvxace")))
        assertEquals("mkxp-z", RpgMakerExternalEngineModule.resolveGameType(request("internal.mkxp-z")))
        assertEquals("mkxp-z", RpgMakerExternalEngineModule.resolveGameType(request("internal.mkxpz")))
    }

    @Test
    fun fallsBackToLaunchTargetSuffixWhenAliasIsMissing() {
        assertEquals("rpgmxp", RpgMakerExternalEngineModule.resolveGameType(request(null, "Game.rgssad")))
        assertEquals("rpgmvx", RpgMakerExternalEngineModule.resolveGameType(request(null, "Game.rgss2a")))
        assertEquals("rpgmvxace", RpgMakerExternalEngineModule.resolveGameType(request(null, "Game.rgss3a")))
    }

    @Test
    fun mapsSubtypeToPluginActions() {
        assertEquals("cyou.joiplay.runtime.rpgmxp.run", RpgMakerExternalEngineModule.actionForGameType("rpgmxp"))
        assertEquals("cyou.joiplay.runtime.rpgmvx.run", RpgMakerExternalEngineModule.actionForGameType("rpgmvx"))
        assertEquals("cyou.joiplay.runtime.rpgmvxace.run", RpgMakerExternalEngineModule.actionForGameType("rpgmvxace"))
        assertEquals("cyou.joiplay.runtime.mkxp-z.run", RpgMakerExternalEngineModule.actionForGameType("mkxp-z"))
    }

    @Test
    fun buildsRpgMakerGameJsonPayload() {
        val request = request("internal.rpgmxp", "[游戏目录]", "/storage/emulated/0/Games/RPGXP/")

        val payload = RpgMakerExternalEngineModule.buildGameJson(request)

        assertTrue(payload.contains("\"title\":\"测试 RPGM\""))
        assertTrue(payload.contains("\"folder\":\"/storage/emulated/0/Games/RPGXP\""))
        assertTrue(payload.contains("\"execFile\":\"\""))
        assertTrue(payload.contains("\"type\":\"rpgmxp\""))
    }

    @Test
    fun buildsNestedRpgSettingsWithTypeWrappers() {
        val json = JSONObject(RpgMakerExternalEngineModule.buildSettingsJson("rpgmvxace"))
        val app = json.getJSONObject("app")
        val rpg = json.getJSONObject("rpg")
        assertTrue(app.getJSONObject("cheats").getBoolean("boolean"))
        assertTrue(rpg.getJSONObject("useRuby18").getBoolean("boolean"))
        assertTrue(rpg.getJSONObject("smoothScaling").getBoolean("boolean"))
        assertTrue(rpg.getJSONObject("prebuiltPathCache").getBoolean("boolean"))
        assertTrue(rpg.getJSONObject("fastPathEnum").getBoolean("boolean"))
        assertEquals(false, rpg.getJSONObject("vsync").getBoolean("boolean"))
        assertEquals(false, rpg.getJSONObject("useCJKFont").getBoolean("boolean"))
        assertEquals("top-center", rpg.getJSONObject("verticalScreenAlign").getString("string"))
        assertEquals("640x480", rpg.getJSONObject("windowSize").getString("string"))
        assertEquals("1", rpg.getJSONObject("speedUp").getString("string"))
        assertEquals("0.75", rpg.getJSONObject("fontScale").getString("string"))
    }

    @Test
    fun xpDefaultsToRuby18WhenSettingsMissing() {
        val rpg = JSONObject(RpgMakerExternalEngineModule.buildSettingsJson("rpgmxp"))
            .getJSONObject("rpg")
        assertTrue(rpg.getJSONObject("useRuby18").getBoolean("boolean"))
    }

    @Test
    fun explicitUseRuby18OverrideIsNotForcedBack() {
        val disabled = EngineSettingsStore.RpgMaker(useRuby18 = false)

        val xp = JSONObject(RpgMakerExternalEngineModule.buildSettingsJson("rpgmxp", disabled))
            .getJSONObject("rpg")
        val vx = JSONObject(RpgMakerExternalEngineModule.buildSettingsJson("rpgmvx", disabled))
            .getJSONObject("rpg")

        assertEquals(false, xp.getJSONObject("useRuby18").getBoolean("boolean"))
        assertEquals(false, vx.getJSONObject("useRuby18").getBoolean("boolean"))
    }

    @Test
    fun passesResolvedSettingsIntoPayload() {
        val settings = EngineSettingsStore.RpgMaker(
            vsync = true,
            customFont = "/data/user/0/com.tyranor.next/files/fonts/demo.ttf",
            windowSize = "1280x720",
            speedUp = "3",
            fontScale = "1.25",
        )

        val rpg = JSONObject(RpgMakerExternalEngineModule.buildSettingsJson("mkxp-z", settings))
            .getJSONObject("rpg")

        assertTrue(rpg.getJSONObject("vsync").getBoolean("boolean"))
        assertEquals("1280x720", rpg.getJSONObject("windowSize").getString("string"))
        assertEquals("3", rpg.getJSONObject("speedUp").getString("string"))
        assertEquals("1.25", rpg.getJSONObject("fontScale").getString("string"))
        assertEquals(
            "/data/user/0/com.tyranor.next/files/fonts/demo.ttf",
            rpg.getJSONObject("customFont").getString("string"),
        )
    }

    @Test
    fun usesArchiveParentAsGameFolder() {
        val request = request(null, "Data/Game.rgss3a", "/storage/emulated/0/Games/VXAce")

        assertEquals(
            "/storage/emulated/0/Games/VXAce",
            RpgMakerExternalEngineModule.resolveGameFolder(request),
        )
    }

    private fun request(
        alias: String?,
        launchTarget: String = "[游戏目录]",
        path: String = "/storage/emulated/0/Games/RPGM",
    ): ExternalEngineLaunchRequest =
        ExternalEngineLaunchRequest(
            game = ScanGame(
                title = "测试 RPGM",
                uri = "content://com.android.externalstorage.documents/tree/primary%3AGames%2FRPGM",
                engine = EngineType.RPGMAKER,
                launchTarget = launchTarget,
                externalModuleAlias = alias,
            ),
            gameDirectoryPath = path,
            launchTarget = launchTarget,
        )
}
