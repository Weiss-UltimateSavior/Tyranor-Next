package com.tyranor.next.core.engine.external

import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.game.save.GameSaveManager
import com.tyranor.next.core.game.scan.EngineScanner
import com.tyranor.next.core.game.scan.ShiinaRioGameCatalog
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ShiinaRioExternalEngineModuleTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun registersModuleWithPublishedHostAndRequiresTheStartupFix() {
        val module = ShiinaRioExternalEngineModule
        assertSame(module, ExternalEngineModuleRegistry.moduleForEngine(EngineType.SHIINARIO))
        assertSame(module, ExternalEngineModuleRegistry.moduleForAlias("internal.shiinario"))
        assertEquals("com.tyranor.next.shiinario", module.packageName)
        assertEquals("com.tyranor.next.action.LAUNCH_SHIINARIO", module.action)
        assertEquals("com.tyranor.next.shiinario.ShiinaRioLaunchActivity", ShiinaRioHostContract.LAUNCH_ACTIVITY)
        assertEquals(1, ShiinaRioHostContract.PROTOCOL_VERSION)
        assertEquals(2L, module.minimumVersionCode)
        assertEquals(ExternalEngineErrorCode.PACKAGE_OUTDATED, ExternalEngineLauncher.versionCodeError(1L, module))
        assertNull(ExternalEngineLauncher.versionCodeError(2L, module))
        assertEquals(
            "https://github.com/SIRENA-Core/Tyranor-Next-ShiinaRio/releases/latest/download/Tyranor-Next-ShiinaRio.apk",
            module.installUrl,
        )
        val release = JSONObject("""{"tag_name":"v0.1.1","assets":[{"name":"Tyranor-Next-ShiinaRio.apk"}]}""")
        assertEquals("0.1.1", ExternalEngineUpdateChecker.versionFromRelease(module.updateSource, release))
        assertFalse(GameSaveManager.supportsFileSaves(EngineType.SHIINARIO, 36))
    }

    @Test
    fun validatesSharedAbsolutePathAndRejectsTraversal() {
        val module = ShiinaRioExternalEngineModule
        assertTrue(module.isValidGameDirectory("/storage/emulated/0/Visual Novels/作品"))
        assertFalse(module.isValidGameDirectory(""))
        assertFalse(module.isValidGameDirectory("content://example/tree/path"))
        assertFalse(module.isValidGameDirectory("/storage/emulated/0/../game"))
        assertFalse(module.isValidGameDirectory("/storage/emulated/0/game\u0000"))
    }

    @Test
    fun fillsDirectoryOnlyRecordAfterHostInstallation() {
        val folder = temporaryFolder.newFolder()
        folder.resolve("Game.EXE").createNewFile()
        val catalog = ShiinaRioGameCatalog.parse(
            """
            {"schemaVersion":1,"engine":"openshiina","schemes":[{"name":"Story"}],"gameMap":{"game.exe":"Story"}}
            """.trimIndent(),
        )
        val module = ShiinaRioExternalEngineModule

        assertEquals("Game.EXE", module.resolveLaunchTarget(folder.path, EngineScanner.LAUNCH_TARGET_GAME_DIR) { catalog })
        assertEquals("Game.EXE", module.resolveLaunchTarget(folder.path, "Game.EXE") { error("unneeded lookup") })
        assertNull(module.resolveLaunchTarget(folder.path, "../Game.EXE") { ShiinaRioGameCatalog.EMPTY })
    }
}
