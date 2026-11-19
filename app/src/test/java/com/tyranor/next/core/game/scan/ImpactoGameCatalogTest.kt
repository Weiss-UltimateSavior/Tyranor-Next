package com.tyranor.next.core.game.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImpactoGameCatalogTest {
    private fun entriesOf(profileId: String): Set<String> =
        ImpactoGameCatalog.byProfileId(profileId)!!.requiredEntries

    /** Windows／Steam 版《秋之回忆6》真机样本的 `data/` 文件名（含大小写）。 */
    private val steamDataNames = setOf(
        "MgsShader.cpk", "bg.cpk", "bgm.cpk", "chara.cpk", "manual.cpk", "mask.cpk", "mes00.cpk", "mes01.cpk",
        "mes02.cpk", "movie.cpk", "script.cpk", "se.cpk", "shader.cpk", "sysse.cpk", "system.cpk", "voice.cpk",
    )
    private val steamRootNames = setOf("data", "game.exe", "steam_appid.txt")

    private fun pcProfileIds(candidates: List<ImpactoGameCatalog.Game>) = candidates.map { it.profileId }

    /** 识别并按偏好选一个：目录无法识别（含布局有歧义）时为 null。 */
    private fun match(
        rootEntryNames: Set<String>,
        dataEntryNames: Set<String> = emptySet(),
        preferredTextLanguage: String? = null,
    ): ImpactoGameCatalog.Game? = ImpactoGameCatalog.pick(
        ImpactoGameCatalog.candidates(rootEntryNames, dataEntryNames), preferredTextLanguage,
    )

    @Test
    fun profileIdsAreUniqueAndMatchTheHostApkList() {
        val ids = ImpactoGameCatalog.games.map { it.profileId }
        assertEquals(ids.size, ids.toSet().size)
        // Kept in sync with ImpactoRequest.PROFILES in the host repository (yuukana1/Tyranor-Next-MAGES; the host refuses anything else).
        assertEquals(
            setOf(
                "chlcc", "cclcc", "cclcc-switch", "mo6tw", "mo6tw-pc", "mo6tw-pc-zhs", "mo6tw-pc-zht",
                "darling", "dash", "rne",
            ),
            ids.toSet(),
        )
    }

    @Test
    fun matchesEachProfileFromItsOwnEntries() {
        // darling is the case-twin of mo6tw (see below). The mo6tw-pc* variants read the same files, so a bare layout
        // only identifies the family; the preferred language picks the variant.
        for (game in ImpactoGameCatalog.games.filter { it.profileId != "darling" }) {
            val match = match(
                rootEntryNames = game.requiredEntries + "readme.txt",
                dataEntryNames = game.requiredDataEntries,
                preferredTextLanguage = game.textLanguage,
            )
            assertEquals(game.profileId, match?.profileId)
        }
    }

    @Test
    fun matchingIsCaseSensitiveBecauseTheEngineOpensExactNames() {
        val upper = entriesOf("mo6tw")
        assertEquals("mo6tw", match(upper)?.profileId)
        // Lowercased, mo6tw's names are exactly darling's required set: the two layouts cannot be told apart by
        // root entry names, so neither is reported (a wrong profile would launch the wrong game and retitle it).
        assertNull(match(upper.map { it.lowercase() }.toSet()))
        assertNull(match(entriesOf("darling")))
    }

    @Test
    fun theFlatWindowsSteamNamesAreNotMistakenForAnUpstreamProfile() {
        // Names as found in the Windows/Steam Memories Off 6 sample when the archives are NOT inside data/:
        // lowercase .cpk and script.cpk, no SCRIPT.cls. The engine would not find them there, so nothing matches.
        val flat = setOf(
            "bg.cpk", "bgm.cpk", "chara.cpk", "manual.cpk", "mask.cpk", "mes00.cpk", "mes01.cpk", "mes02.cpk",
            "MgsShader.cpk", "movie.cpk", "script.cpk", "se.cpk", "shader.cpk", "sysse.cpk", "system.cpk",
            "voice.cpk", "game.exe", "Launcher.exe", "steam_api.dll",
        )
        assertNull(match(flat))
        assertTrue(ImpactoGameCatalog.candidates(flat).isEmpty())
    }

    @Test
    fun missingRequiredEntryOrAmbiguousLayoutIsNotGuessed() {
        val mo6tw = entriesOf("mo6tw")
        assertNull(match(mo6tw - "SYSTEM.CPK"))
        // Both darling and rne entries present: two profiles match, so none is chosen.
        assertNull(match(entriesOf("darling") + entriesOf("rne")))
        assertNull(match(emptySet()))
    }

    @Test
    fun movieArchivesAreNotRequiredSoStrippedCopiesStillMatch() {
        for (game in ImpactoGameCatalog.games) {
            assertTrue(game.profileId, game.requiredEntries.none { it.startsWith("movie", ignoreCase = true) })
            assertTrue(game.profileId, game.requiredDataEntries.none { it.startsWith("movie", ignoreCase = true) })
        }
        assertEquals(
            "mo6tw-pc",
            match(steamRootNames, steamDataNames - "movie.cpk")?.profileId,
        )
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

    // ---- Windows／Steam 版（archives in data/）----

    @Test
    fun theWindowsSteamLayoutIsOfferedAsEveryLanguageVariantJapaneseFirst() {
        val candidates = ImpactoGameCatalog.candidates(steamRootNames, steamDataNames)

        assertEquals(listOf("mo6tw-pc", "mo6tw-pc-zhs", "mo6tw-pc-zht"), pcProfileIds(candidates))
        assertEquals(
            listOf(
                ImpactoGameCatalog.TEXT_JA, ImpactoGameCatalog.TEXT_ZH_HANS, ImpactoGameCatalog.TEXT_ZH_HANT,
            ),
            candidates.map { it.textLanguage },
        )
        assertTrue(candidates.all { it.title == "Memories Off 6 ~T-Wave~ (PC)" })
        // Without a preference the original Japanese variant is the default.
        assertEquals("mo6tw-pc", match(steamRootNames, steamDataNames)?.profileId)
    }

    @Test
    fun theLanguagePreferenceChoosesTheVariantAndFallsBackToJapanese() {
        fun pick(language: String?) =
            match(steamRootNames, steamDataNames, language)?.profileId

        assertEquals("mo6tw-pc-zhs", pick(ImpactoGameCatalog.TEXT_ZH_HANS))
        assertEquals("mo6tw-pc-zht", pick(ImpactoGameCatalog.TEXT_ZH_HANT))
        assertEquals("mo6tw-pc", pick(ImpactoGameCatalog.TEXT_JA))
        assertEquals("mo6tw-pc", pick(null))
        assertEquals("mo6tw-pc", pick("fr"))
    }

    @Test
    fun onlyVariantsWhoseTextArchiveExistsAreOffered() {
        val withoutSimplified = ImpactoGameCatalog.candidates(steamRootNames, steamDataNames - "mes02.cpk")
        assertEquals(listOf("mo6tw-pc", "mo6tw-pc-zht"), pcProfileIds(withoutSimplified))
        // The wanted language is not on disk: fall back to the first offered (Japanese), never to a missing archive.
        assertEquals(
            "mo6tw-pc",
            ImpactoGameCatalog.pick(withoutSimplified, ImpactoGameCatalog.TEXT_ZH_HANS)?.profileId,
        )

        val onlyJapanese = ImpactoGameCatalog.candidates(steamRootNames, steamDataNames - "mes01.cpk" - "mes02.cpk")
        assertEquals(listOf("mo6tw-pc"), pcProfileIds(onlyJapanese))
    }

    @Test
    fun theBaseJapaneseTextArchiveIsPartOfEveryVariantsContract() {
        // profiles.json lists data/mes00.cpk in allOf for all three variants: a copy without mes00.cpk is not offered,
        // even though mes01.cpk and mes02.cpk are still there.
        assertTrue(ImpactoGameCatalog.candidates(steamRootNames, steamDataNames - "mes00.cpk").isEmpty())
        assertTrue(ImpactoGameCatalog.candidates(steamRootNames, steamDataNames - "script.cpk").isEmpty())
    }

    @Test
    fun theWindowsLayoutNeedsTheExeTheDataFolderAndExactNames() {
        assertTrue(ImpactoGameCatalog.candidates(steamRootNames - "game.exe", steamDataNames).isEmpty())
        assertTrue(ImpactoGameCatalog.candidates(steamRootNames - "data", steamDataNames).isEmpty())
        // The engine opens <game>/data/script.cpk case-sensitively: Data/, Game.exe or SCRIPT.CPK are not the layout.
        assertTrue(ImpactoGameCatalog.candidates(setOf("Data", "game.exe"), steamDataNames).isEmpty())
        assertTrue(ImpactoGameCatalog.candidates(setOf("data", "Game.exe"), steamDataNames).isEmpty())
        assertTrue(
            ImpactoGameCatalog.candidates(steamRootNames, steamDataNames.map { it.uppercase() }.toSet()).isEmpty(),
        )
    }

    @Test
    fun dataEntryNamesDoNotCountAsRootEntries() {
        // data/script.cpk must not satisfy a root-level requirement and vice versa.
        assertTrue(ImpactoGameCatalog.candidates(emptySet(), steamDataNames).isEmpty())
        assertTrue(ImpactoGameCatalog.candidates(steamRootNames + steamDataNames, emptySet()).isEmpty())
    }

    @Test
    fun theUpstreamPs3LayoutAndTheWindowsLayoutTogetherAreAmbiguous() {
        val both = entriesOf("mo6tw") + steamRootNames
        assertTrue(ImpactoGameCatalog.candidates(both, steamDataNames).isEmpty())
        assertNull(match(both, steamDataNames))
        // The PS3 profile alone is still found when no data/ folder exists.
        assertEquals("mo6tw", match(entriesOf("mo6tw"))?.profileId)
    }

    @Test
    fun theWindowsVariantsAreNotCaseTwinsOfEachOther() {
        // Same root names, different text archive: they must not suppress each other like mo6tw / darling do.
        val zht = ImpactoGameCatalog.byProfileId("mo6tw-pc-zht")!!
        assertEquals(
            listOf("mo6tw-pc", "mo6tw-pc-zht"),
            pcProfileIds(ImpactoGameCatalog.candidates(zht.requiredEntries, setOf("script.cpk", "mes00.cpk", "mes01.cpk"))),
        )
    }

    @Test
    fun pcEditionsMirrorTheHostProfilesJsonDetectBlocks() {
        // engine/impacto/profiles.json in yuukana1/Tyranor-Next-MAGES: detect.allOf + detect.textArchive per profile.
        val allOf = setOf("game.exe", "data/script.cpk", "data/mes00.cpk")
        val textArchive = mapOf(
            "mo6tw-pc" to "data/mes00.cpk",
            "mo6tw-pc-zhs" to "data/mes02.cpk",
            "mo6tw-pc-zht" to "data/mes01.cpk",
        )
        val language = mapOf(
            "mo6tw-pc" to "ja", "mo6tw-pc-zhs" to "zh-Hans", "mo6tw-pc-zht" to "zh-Hant",
        )
        for ((profile, archive) in textArchive) {
            val game = ImpactoGameCatalog.byProfileId(profile)!!
            val needed = game.requiredEntries.filter { it != "data" }.toSet() +
                game.requiredDataEntries.map { "data/$it" }
            assertEquals(profile, allOf + archive, needed)
            assertTrue(profile, "data" in game.requiredEntries)
            assertEquals(profile, language.getValue(profile), game.textLanguage)
            assertEquals(profile, "mo6tw-pc", game.family)
        }
    }

    @Test
    fun windowsEditionsNeedHostZeroPointTwoAndOtherProfilesDoNot() {
        for (game in ImpactoGameCatalog.games) {
            val expected = if (game.family == "mo6tw-pc") 4 else 0
            assertEquals(game.profileId, expected, game.minHostVersionCode)
        }
    }

    @Test
    fun theScannerOnlyKeepsTheDataFileNamesSomeProfileAsksFor() {
        // A large data/ folder of another engine must not be copied name by name into the scan state.
        assertEquals(
            setOf("script.cpk", "mes00.cpk", "mes01.cpk", "mes02.cpk"),
            ImpactoGameCatalog.dataEntryNames,
        )
    }

    // ---- 界面语言 → 文本语言 ----

    @Test
    fun uiLanguageMapsToTheClosestTextLanguage() {
        val hans = ImpactoGameCatalog.TEXT_ZH_HANS
        val hant = ImpactoGameCatalog.TEXT_ZH_HANT
        mapOf(
            "zh" to hans, "zh-CN" to hans, "zh-SG" to hans, "zh-Hans" to hans, "zh-Hans-CN" to hans,
            "zh-TW" to hant, "zh-HK" to hant, "zh-MO" to hant, "zh-Hant" to hant, "zh-Hant-TW" to hant,
            "zh-Hant-CN" to hant,
            // An explicit script wins over the region: Simplified Chinese as used in Hong Kong / Macau / Taiwan.
            "zh-Hans-HK" to hans, "zh-Hans-MO" to hans, "zh-Hans-TW" to hans,
            "ja" to ImpactoGameCatalog.TEXT_JA, "ja-JP" to ImpactoGameCatalog.TEXT_JA,
        ).forEach { (tag, expected) ->
            assertEquals(tag, expected, ImpactoGameCatalog.textLanguageForUiTag(tag))
        }
        // No Memories Off 6 text exists for these: no preference, the default variant is used.
        listOf("en", "en-US", "ko", "fr-FR", "", "  ", "!!not a tag!!").forEach { tag ->
            assertNull(tag, ImpactoGameCatalog.textLanguageForUiTag(tag))
        }
        assertNull(ImpactoGameCatalog.textLanguageForUiTag(null))
    }

    @Test
    fun preferredProfileIdFollowsTheUiLanguageWithinTheOfferedProfiles() {
        val all = listOf("mo6tw-pc", "mo6tw-pc-zhs", "mo6tw-pc-zht")
        assertEquals("mo6tw-pc-zhs", ImpactoGameCatalog.preferredProfileId(all, "zh-CN"))
        assertEquals("mo6tw-pc-zht", ImpactoGameCatalog.preferredProfileId(all, "zh-TW"))
        assertEquals("mo6tw-pc", ImpactoGameCatalog.preferredProfileId(all, "ja"))
        assertEquals("mo6tw-pc", ImpactoGameCatalog.preferredProfileId(all, "en-US"))
        assertEquals("mo6tw-pc", ImpactoGameCatalog.preferredProfileId(all, null))
        // The wanted language is not among the offered ones.
        assertEquals("mo6tw-pc", ImpactoGameCatalog.preferredProfileId(listOf("mo6tw-pc", "mo6tw-pc-zht"), "zh-CN"))
        assertEquals("mo6tw-pc-zht", ImpactoGameCatalog.preferredProfileId(listOf("mo6tw-pc-zht"), "ja"))
        assertNull(ImpactoGameCatalog.preferredProfileId(emptyList(), "zh-CN"))
        assertNull(ImpactoGameCatalog.preferredProfileId(listOf("not-a-profile"), "zh-CN"))
    }
}
