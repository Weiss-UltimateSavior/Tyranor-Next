package com.tyranor.next.core.game.scan

import com.tyranor.next.core.engine.EngineType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Impacto detection matches the archive layout of an upstream profile (exact case) and nothing else. It says the
 * folder looks like that profile, not that the game runs.
 */
class EngineScannerImpactoTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun folder(name: String, vararg entries: String): File {
        val root = temporaryFolder.newFolder(name)
        entries.forEach { entry ->
            if (entry == "script") root.resolve(entry).mkdirs() else root.resolve(entry).writeBytes(byteArrayOf(0))
        }
        return root
    }

    @Test
    fun detectsMo6twAndKeepsTheProfileIdAsLaunchTarget() {
        val root = folder("mo6tw", *ImpactoGameCatalog.byProfileId("mo6tw")!!.requiredEntries.toTypedArray())

        val detection = EngineScanner.detectEngine(root)

        assertEquals(EngineType.IMPACTO, detection.engine)
        assertEquals("mo6tw", detection.launchTarget)
        assertEquals("internal.impacto", detection.externalModuleAlias)
        assertEquals("Memories Off 6 ~T-Wave~", detection.titleHint)
        assertEquals(94, detection.confidence)
    }

    @Test
    fun detectsAProfileWhoseScriptIsADirectory() {
        val root = folder("cclcc", *ImpactoGameCatalog.byProfileId("cclcc")!!.requiredEntries.toTypedArray())

        val detection = EngineScanner.detectEngine(root)

        assertEquals(EngineType.IMPACTO, detection.engine)
        assertEquals("cclcc", detection.launchTarget)
    }

    @Test
    fun doesNotClaimTheWindowsSteamLayout() {
        val root = folder(
            "steam",
            "bg.cpk", "bgm.cpk", "chara.cpk", "manual.cpk", "mask.cpk", "mes00.cpk", "movie.cpk", "script.cpk",
            "se.cpk", "sysse.cpk", "system.cpk", "voice.cpk", "game.exe", "Launcher.exe", "steam_api.dll",
        )

        assertNotEquals(EngineType.IMPACTO, EngineScanner.detectEngine(root).engine)
    }

    @Test
    fun partialLayoutIsNotDetected() {
        val entries = ImpactoGameCatalog.byProfileId("mo6tw")!!.requiredEntries - "VOICE.CPK"
        val root = folder("partial", *entries.toTypedArray())

        assertNotEquals(EngineType.IMPACTO, EngineScanner.detectEngine(root).engine)
    }

    @Test
    fun nestedFolderWithSameNamesDoesNotCount() {
        val root = temporaryFolder.newFolder("wrapper")
        val inner = root.resolve("data").apply { mkdirs() }
        ImpactoGameCatalog.byProfileId("mo6tw")!!.requiredEntries.forEach { inner.resolve(it).writeBytes(byteArrayOf(0)) }

        assertNotEquals(EngineType.IMPACTO, EngineScanner.detectEngine(root).engine)
    }
}
