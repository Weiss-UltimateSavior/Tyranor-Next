package com.tyranor.next.core.engine.external

import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.game.model.ScanGame
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
    fun usesNestedSettingsForRpgMakerXpOnly() {
        assertEquals(
            "{\"rpg\":{\"useRuby18\":{\"boolean\":true}}}",
            RpgMakerExternalEngineModule.buildSettingsJson("rpgmxp"),
        )
        assertEquals("{}", RpgMakerExternalEngineModule.buildSettingsJson("rpgmvx"))
        assertEquals("{}", RpgMakerExternalEngineModule.buildSettingsJson("rpgmvxace"))
        assertEquals("{}", RpgMakerExternalEngineModule.buildSettingsJson("mkxp-z"))
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
