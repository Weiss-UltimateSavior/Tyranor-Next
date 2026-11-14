package com.tyranor.next.core.engine.external

import com.tyranor.next.core.engine.EngineType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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
        ).forEach { assertTrue(it, it.startsWith(ImpactoHostContract.PACKAGE + ".")) }
        // No download page is published yet; the UI must not offer a fake one.
        assertNull(ImpactoExternalEngineModule.installUrl)
        assertTrue(ImpactoExternalEngineModule.requiresGameDirectoryPath)
        assertFalse(ImpactoExternalEngineModule.launchEntryIsFile)
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
