package com.tyranor.next.core.game.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImpactoGameCatalogTest {
    private fun entriesOf(profileId: String): Set<String> =
        ImpactoGameCatalog.byProfileId(profileId)!!.requiredEntries

    @Test
    fun profileIdsAreUniqueAndMatchTheHostApkList() {
        val ids = ImpactoGameCatalog.games.map { it.profileId }
        assertEquals(ids.size, ids.toSet().size)
        // Kept in sync with ImpactoRequest.PROFILES in engine/impacto/host-apk (the host refuses anything else).
        assertEquals(
            setOf("chlcc", "cclcc", "cclcc-switch", "mo6tw", "darling", "dash", "rne"),
            ids.toSet(),
        )
    }

    @Test
    fun matchesEachProfileFromItsOwnEntries() {
        for (game in ImpactoGameCatalog.games.filter { it.profileId != "darling" }) {
            assertEquals(game.profileId, ImpactoGameCatalog.match(game.requiredEntries + "readme.txt")?.profileId)
        }
    }

    @Test
    fun matchingIsCaseSensitiveBecauseTheEngineOpensExactNames() {
        val upper = entriesOf("mo6tw")
        assertEquals("mo6tw", ImpactoGameCatalog.match(upper)?.profileId)
        // Lowercased, mo6tw's names are exactly darling's required set: the two layouts cannot be told apart by
        // root entry names, so neither is reported (a wrong profile would launch the wrong game and retitle it).
        assertNull(ImpactoGameCatalog.match(upper.map { it.lowercase() }.toSet()))
        assertNull(ImpactoGameCatalog.match(entriesOf("darling")))
    }

    @Test
    fun theWindowsSteamLayoutIsNotMistakenForAnUpstreamProfile() {
        // Names as found in the Windows/Steam Memories Off 6 sample: lowercase .cpk and script.cpk, no SCRIPT.cls.
        val steam = setOf(
            "bg.cpk", "bgm.cpk", "chara.cpk", "manual.cpk", "mask.cpk", "mes00.cpk", "mes01.cpk", "mes02.cpk",
            "MgsShader.cpk", "movie.cpk", "script.cpk", "se.cpk", "shader.cpk", "sysse.cpk", "system.cpk",
            "voice.cpk", "game.exe", "Launcher.exe", "steam_api.dll",
        )
        assertNull(ImpactoGameCatalog.match(steam))
    }

    @Test
    fun missingRequiredEntryOrAmbiguousLayoutIsNotGuessed() {
        val mo6tw = entriesOf("mo6tw")
        assertNull(ImpactoGameCatalog.match(mo6tw - "SYSTEM.CPK"))
        // Both darling and rne entries present: two profiles match, so none is chosen.
        assertNull(ImpactoGameCatalog.match(entriesOf("darling") + entriesOf("rne")))
        assertNull(ImpactoGameCatalog.match(emptySet()))
    }

    @Test
    fun movieArchivesAreNotRequiredSoStrippedCopiesStillMatch() {
        for (game in ImpactoGameCatalog.games) {
            assertTrue(game.profileId, game.requiredEntries.none { it.startsWith("movie", ignoreCase = true) })
        }
    }

    @Test
    fun everyGameHasATitleAndLookupWorks() {
        for (game in ImpactoGameCatalog.games) {
            assertTrue(game.title.isNotBlank())
            assertNotNull(ImpactoGameCatalog.byProfileId(game.profileId))
        }
        assertNull(ImpactoGameCatalog.byProfileId("characterviewer"))
        assertNull(ImpactoGameCatalog.byProfileId(null))
    }
}
