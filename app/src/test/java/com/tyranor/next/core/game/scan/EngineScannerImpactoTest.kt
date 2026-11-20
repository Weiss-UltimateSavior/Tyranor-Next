package com.tyranor.next.core.game.scan

import com.tyranor.next.core.engine.EngineType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Impacto detection matches the archive layout of a profile (exact case) and nothing else. It says the
 * folder looks like that profile, not that the game runs.
 */
class EngineScannerImpactoTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @After
    fun backToTheBuiltInRules() {
        ImpactoGameCatalog.useHostRules(null)
    }

    /** The folder is not taken at all (no engine). */
    private fun assertUnknown(detection: EngineScanner.Detection, message: String = "") {
        assertEquals(message, EngineType.UNKNOWN, detection.engine)
    }

    /** No profile matches, but the layout looks like MAGES.: listed as unsupported and tried with [tryProfile]. */
    private fun assertUnsupported(detection: EngineScanner.Detection, tryProfile: String, message: String = "") {
        assertEquals(message, EngineType.IMPACTO, detection.engine)
        assertTrue(message, detection.unsupported)
        assertEquals(message, tryProfile, detection.launchTarget)
        assertEquals(message, "internal.impacto", detection.externalModuleAlias)
        assertEquals(message, null, detection.titleHint)
        assertEquals(message, emptyList<String>(), detection.launchTargetChoices)
        assertEquals(message, 60, detection.confidence)
    }

    /** Names found in `data/` of the Windows/Steam Memories Off 6 sample (App 2184580); `MgsShader.cpk` is mixed case. */
    private val steamDataFiles = listOf(
        "MgsShader.cpk", "bg.cpk", "bgm.cpk", "chara.cpk", "manual.cpk", "mask.cpk", "mes00.cpk", "mes01.cpk",
        "mes02.cpk", "movie.cpk", "script.cpk", "se.cpk", "shader.cpk", "sysse.cpk", "system.cpk", "voice.cpk",
    )

    private fun folder(name: String, vararg entries: String): File {
        val root = temporaryFolder.newFolder(name)
        entries.forEach { entry ->
            if (entry == "script") root.resolve(entry).mkdirs() else root.resolve(entry).writeBytes(byteArrayOf(0))
        }
        return root
    }

    /** `<root>/<rootFiles>` plus `<root>/<dataDir>/<dataFiles>`; placeholders are one byte, no game content. */
    private fun pcFolder(
        name: String,
        dataFiles: List<String> = steamDataFiles,
        rootFiles: List<String> = listOf("game.exe", "steam_appid.txt"),
        dataDir: String = "data",
    ): File {
        val root = temporaryFolder.newFolder(name)
        rootFiles.forEach { root.resolve(it).writeBytes(byteArrayOf(0)) }
        val data = root.resolve(dataDir).apply { mkdirs() }
        dataFiles.forEach { data.resolve(it).writeBytes(byteArrayOf(0)) }
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
        assertEquals(emptyList<String>(), detection.launchTargetChoices)
    }

    @Test
    fun detectsAProfileWhoseScriptIsADirectory() {
        val root = folder("cclcc", *ImpactoGameCatalog.byProfileId("cclcc")!!.requiredEntries.toTypedArray())

        val detection = EngineScanner.detectEngine(root)

        assertEquals(EngineType.IMPACTO, detection.engine)
        assertEquals("cclcc", detection.launchTarget)
    }

    @Test
    fun theFlatWindowsSteamNamesAreNoProfileButAnUnsupportedMagesGame() {
        // The same files directly in the folder (not in data/) are not where the engine looks for them.
        val root = folder(
            "steam-flat",
            "bg.cpk", "bgm.cpk", "chara.cpk", "manual.cpk", "mask.cpk", "mes00.cpk", "movie.cpk", "script.cpk",
            "se.cpk", "sysse.cpk", "system.cpk", "voice.cpk", "game.exe", "Launcher.exe", "steam_api.dll",
        )

        assertUnsupported(EngineScanner.detectEngine(root), "chlcc")
    }

    @Test
    fun partialLayoutIsNotDetected() {
        val entries = ImpactoGameCatalog.byProfileId("mo6tw")!!.requiredEntries - "VOICE.CPK"
        val root = folder("partial", *entries.toTypedArray())

        // SCRIPT.cls, not script.cpk: no unsupported layout matches either.
        assertUnknown(EngineScanner.detectEngine(root))
    }

    @Test
    fun nestedFolderWithSameNamesDoesNotCount() {
        val root = temporaryFolder.newFolder("wrapper")
        val inner = root.resolve("data").apply { mkdirs() }
        ImpactoGameCatalog.byProfileId("mo6tw")!!.requiredEntries.forEach { inner.resolve(it).writeBytes(byteArrayOf(0)) }

        assertUnknown(EngineScanner.detectEngine(root))
    }

    // ---- Windows／Steam 版：game.exe + data/*.cpk ----

    @Test
    fun detectsTheWindowsSteamLayoutAndOffersEveryLanguageVariant() {
        val detection = EngineScanner.detectEngine(pcFolder("steam"))

        assertEquals(EngineType.IMPACTO, detection.engine)
        // The pure detection is independent of the app language: it reports the default (Japanese) variant plus all
        // candidates, and the caller that knows the UI language picks (see scanLaunchTarget).
        assertEquals("mo6tw-pc", detection.launchTarget)
        assertEquals(listOf("mo6tw-pc", "mo6tw-pc-zhs", "mo6tw-pc-zht"), detection.launchTargetChoices)
        assertEquals("internal.impacto", detection.externalModuleAlias)
        assertEquals("Memories Off 6 ~T-Wave~ (PC)", detection.titleHint)
        assertEquals(94, detection.confidence)
    }

    @Test
    fun theWindowsSteamLayoutWithoutTheMovieArchiveIsStillDetected() {
        val root = pcFolder("steam-no-movie", steamDataFiles - "movie.cpk")

        assertEquals(EngineType.IMPACTO, EngineScanner.detectEngine(root).engine)
    }

    @Test
    fun aWindowsCopyWithOneLanguageOffersOnlyThatVariantAndPlainTargetStaysSingle() {
        val onlyJapanese = EngineScanner.detectEngine(
            pcFolder("only-ja", steamDataFiles - "mes01.cpk" - "mes02.cpk"),
        )
        assertEquals("mo6tw-pc", onlyJapanese.launchTarget)
        assertEquals(emptyList<String>(), onlyJapanese.launchTargetChoices)

        val noSimplified = EngineScanner.detectEngine(pcFolder("no-zhs", steamDataFiles - "mes02.cpk"))
        assertEquals(listOf("mo6tw-pc", "mo6tw-pc-zht"), noSimplified.launchTargetChoices)
    }

    @Test
    fun aWindowsFolderThatLacksAMarkerIsNoProfile() {
        assertUnsupported(
            EngineScanner.detectEngine(pcFolder("no-exe", rootFiles = listOf("steam_appid.txt"))),
            "mo6tw-pc",
            "no game.exe",
        )
        assertUnknown(
            EngineScanner.detectEngine(pcFolder("no-script", steamDataFiles - "script.cpk")),
            "no script.cpk",
        )
        assertUnsupported(
            EngineScanner.detectEngine(pcFolder("no-mes00", steamDataFiles - "mes00.cpk")),
            "mo6tw-pc",
            "no mes00.cpk",
        )
    }

    @Test
    fun theWindowsLayoutIsCaseSensitiveLikeTheEngineThatOpensTheFiles() {
        // A directory named Data/ is traversed by the scanner but is not the path the engine opens: only an unsupported try.
        assertUnsupported(EngineScanner.detectEngine(pcFolder("steam-upper-data", dataDir = "Data")), "mo6tw-pc")
        assertUnsupported(
            EngineScanner.detectEngine(pcFolder("steam-upper-files", steamDataFiles.map { it.uppercase() })),
            "mo6tw-pc",
        )
    }

    @Test
    fun aDataEntryThatIsNotAFolderOrWhoseFilesAreFoldersDoesNotCount() {
        val dataIsAFile = temporaryFolder.newFolder("data-is-a-file")
        dataIsAFile.resolve("game.exe").writeBytes(byteArrayOf(0))
        dataIsAFile.resolve("data").writeBytes(byteArrayOf(0))
        assertUnknown(EngineScanner.detectEngine(dataIsAFile))

        val archivesAreFolders = temporaryFolder.newFolder("archives-are-folders")
        archivesAreFolders.resolve("game.exe").writeBytes(byteArrayOf(0))
        val data = archivesAreFolders.resolve("data").apply { mkdirs() }
        steamDataFiles.forEach { data.resolve(it).mkdirs() }
        assertUnknown(EngineScanner.detectEngine(archivesAreFolders))
    }

    @Test
    fun aFolderAroundTheWindowsGameIsNotTheGame() {
        // The scan root / a parent folder is not the game: the scanner has to descend into memo06-pc/.
        val parent = temporaryFolder.newFolder("games")
        val game = parent.resolve("memo06-pc").apply { mkdirs() }
        game.resolve("game.exe").writeBytes(byteArrayOf(0))
        val data = game.resolve("data").apply { mkdirs() }
        steamDataFiles.forEach { data.resolve(it).writeBytes(byteArrayOf(0)) }

        assertUnknown(EngineScanner.detectEngine(parent))
        assertEquals(EngineType.IMPACTO, EngineScanner.detectEngine(game).engine)
    }

    @Test
    fun otherEnginesStillWinOverTheWindowsLayout() {
        // Impacto is tried last: a folder that also carries another engine's marker keeps that engine.
        val root = pcFolder("steam-and-siglus", rootFiles = listOf("game.exe", "Gameexe.dat", "Scene.pck"))

        assertEquals(EngineType.SIGLUS, EngineScanner.detectEngine(root).engine)
    }

    @Test
    fun theUpstreamLayoutAndTheWindowsLayoutTogetherAreNotGuessed() {
        val root = pcFolder("both")
        ImpactoGameCatalog.byProfileId("mo6tw")!!.requiredEntries.forEach { root.resolve(it).writeBytes(byteArrayOf(0)) }

        // Ambiguous: neither a profile nor an unsupported try.
        assertUnknown(EngineScanner.detectEngine(root))
    }

    // ---- SAF 与文件扫描共用的泛型识别 ----

    private class Node(val name: String, val children: List<Node>? = null) {
        val isDirectory: Boolean get() = children != null
    }

    private fun detect(tree: List<Node>) = EngineScanner.detectEngine(
        children = tree,
        nameOf = Node::name,
        isDirectory = Node::isDirectory,
        childrenOf = { it.children.orEmpty() },
    )

    private fun steamData() = steamDataFiles.map { Node(it) }

    @Test
    fun theGenericDetectorFindsTheWindowsLayoutThroughSafStyleNodes() {
        val detection = detect(listOf(Node("game.exe"), Node("steam_appid.txt"), Node("data", steamData())))

        assertEquals(EngineType.IMPACTO, detection.engine)
        assertEquals(listOf("mo6tw-pc", "mo6tw-pc-zhs", "mo6tw-pc-zht"), detection.launchTargetChoices)
    }

    @Test
    fun aDataFolderNestedInsideAnotherSearchedFolderDoesNotProvideTheRootDataFolder() {
        // `game/` is one of the folders the scanner looks into, so its data/ is visited with rel = "game"; its files
        // must not be mistaken for the root-level data/ the engine opens. The real root data/ has unrelated files only.
        val tree = listOf(
            Node("game.exe"),
            Node("data", listOf(Node("readme.txt"))),
            Node("game", listOf(Node("data", steamData()))),
        )

        assertUnknown(detect(tree))
    }

    @Test
    fun aWrapperFolderAroundTheGameIsNotTheGame() {
        val tree = listOf(Node("game.exe"), Node("wrapper", listOf(Node("data", steamData()))))

        assertUnknown(detect(tree))
    }

    @Test
    fun filesOfADifferentlyCasedDataFolderAreNotCountedForTheExactOne() {
        // Only possible on a case-sensitive store, where Data/ and data/ are two folders: the engine opens data/, so the
        // complete Data/ must not complete the incomplete data/.
        val tree = listOf(
            Node("game.exe"),
            Node("Data", steamData()),
            Node("data", listOf(Node("script.cpk"))),
        )

        // No profile; the (case-insensitive) unsupported layout still sees a MAGES. data folder.
        assertUnsupported(detect(tree), "mo6tw-pc")
    }

    // ---- 多个语言变体时选哪一个 ----

    private val allVariants = listOf("mo6tw-pc", "mo6tw-pc-zhs", "mo6tw-pc-zht")

    private fun detection(choices: List<String>) = EngineScanner.Detection(
        engine = EngineType.IMPACTO,
        confidence = 94,
        launchTarget = choices.first(),
        launchTargetChoices = if (choices.size > 1) choices else emptyList(),
    )

    @Test
    fun theUiLanguageChoosesTheVariantOfAFirstScan() {
        val offered = detection(allVariants)

        assertEquals("mo6tw-pc-zhs", EngineScanner.selectLaunchTarget(offered, null, "zh-CN"))
        assertEquals("mo6tw-pc-zht", EngineScanner.selectLaunchTarget(offered, null, "zh-TW"))
        assertEquals("mo6tw-pc", EngineScanner.selectLaunchTarget(offered, null, "ja"))
        // No text exists for English: the default (Japanese original) variant.
        assertEquals("mo6tw-pc", EngineScanner.selectLaunchTarget(offered, null, "en-US"))
        assertEquals("mo6tw-pc", EngineScanner.selectLaunchTarget(offered, null, null))
    }

    @Test
    fun aVariantAlreadyInTheLibraryIsKeptWhateverTheUiLanguageIsNow() {
        // The host isolates saves per "profile + folder": a rescan after the app language changed must not move an
        // existing game to another profile.
        val offered = detection(allVariants)

        assertEquals("mo6tw-pc-zhs", EngineScanner.selectLaunchTarget(offered, "mo6tw-pc-zhs", "ja"))
        assertEquals("mo6tw-pc", EngineScanner.selectLaunchTarget(offered, "mo6tw-pc", "zh-CN"))
        assertEquals("mo6tw-pc-zht", EngineScanner.selectLaunchTarget(offered, "mo6tw-pc-zht", "en"))
    }

    @Test
    fun aSavedVariantWhoseTextArchiveIsGoneIsNotKept() {
        // mes02.cpk was removed since the last scan: mo6tw-pc-zhs is no longer offered and would fail to start.
        val offered = detection(listOf("mo6tw-pc", "mo6tw-pc-zht"))

        assertEquals("mo6tw-pc-zht", EngineScanner.selectLaunchTarget(offered, "mo6tw-pc-zhs", "zh-TW"))
        // Nothing preferred is on disk either: back to the default.
        assertEquals("mo6tw-pc", EngineScanner.selectLaunchTarget(offered, "mo6tw-pc-zhs", "zh-CN"))
    }

    @Test
    fun aSavedProfileOfAnotherLayoutOrAnUnknownValueIsIgnored() {
        val offered = detection(allVariants)

        assertEquals("mo6tw-pc-zhs", EngineScanner.selectLaunchTarget(offered, "mo6tw", "zh-CN"))
        assertEquals("mo6tw-pc-zhs", EngineScanner.selectLaunchTarget(offered, "DIR", "zh-CN"))
        assertEquals("mo6tw-pc-zhs", EngineScanner.selectLaunchTarget(offered, "", "zh-CN"))
    }

    @Test
    fun aSingleCandidateIsNeverOverriddenBySavedStateOrLanguage() {
        val onlyJapanese = detection(listOf("mo6tw-pc"))
        assertEquals("mo6tw-pc", EngineScanner.selectLaunchTarget(onlyJapanese, "mo6tw-pc-zhs", "zh-CN"))

        val ps3 = detection(listOf("mo6tw"))
        assertEquals("mo6tw", EngineScanner.selectLaunchTarget(ps3, "mo6tw-pc-zhs", "zh-CN"))
    }

    // ---- 宿主提供的识别规则与「未支持的 MAGES. 布局」 ----

    /**
     * Host rules with a profile the built-in catalog does not know, in a subdirectory the built-in rules never expand
     * (`GAMEDATA/`), plus an unsupported layout for incomplete copies of it.
     */
    private val hostRules = ImpactoGameCatalog.Rules(
        games = ImpactoGameCatalog.builtInGames + ImpactoGameCatalog.Game(
            profileId = "host-only-pc",
            title = "Host only (PC)",
            requiredEntries = setOf("Game.exe", "GAMEDATA/script.mpk", "GAMEDATA/system.mpk"),
        ),
        unsupportedLayouts = listOf(
            ImpactoGameCatalog.UnsupportedLayout(
                "pc-gamedata-mpk", setOf("GAMEDATA/script.mpk", "GAMEDATA/system.mpk"), "host-only-pc",
            ),
        ),
    )

    private fun pcMpkFolder(
        name: String,
        dir: String,
        rootFiles: List<String> = listOf("Game.exe"),
        files: List<String> = listOf("script.mpk", "system.mpk"),
    ): File {
        val root = temporaryFolder.newFolder(name)
        rootFiles.forEach { root.resolve(it).writeBytes(byteArrayOf(0)) }
        val sub = root.resolve(dir).apply { mkdirs() }
        files.forEach { sub.resolve(it).writeBytes(byteArrayOf(0)) }
        return root
    }

    @Test
    fun aProfileFromTheHostRulesIsRecognisedInASubdirectory() {
        ImpactoGameCatalog.useHostRules(hostRules)

        val detection = EngineScanner.detectEngine(pcMpkFolder("host-only", "GAMEDATA"))

        assertEquals(EngineType.IMPACTO, detection.engine)
        assertEquals("host-only-pc", detection.launchTarget)
        assertEquals("Host only (PC)", detection.titleHint)
        assertEquals(94, detection.confidence)
        assertFalse(detection.unsupported)
    }

    @Test
    fun withoutTheHostRulesAFolderOnlyTheHostKnowsIsNotTaken() {
        assertUnknown(EngineScanner.detectEngine(pcMpkFolder("host-only-builtin", "GAMEDATA")))
    }

    @Test
    fun theBuiltInCatalogRecognisesTheSteinsGateSteamLayout() {
        val detection = EngineScanner.detectEngine(pcMpkFolder("sg", "USRDIR"))

        assertEquals(EngineType.IMPACTO, detection.engine)
        assertEquals("sg-pc", detection.launchTarget)
        assertEquals("STEINS;GATE (PC, Steam)", detection.titleHint)
        assertFalse(detection.unsupported)
    }

    @Test
    fun anIncompleteLayoutOfAHostProfileIsAnUnsupportedGameTriedWithTheLayoutsProfile() {
        ImpactoGameCatalog.useHostRules(hostRules)

        assertUnsupported(
            EngineScanner.detectEngine(pcMpkFolder("host-no-exe", "GAMEDATA", rootFiles = emptyList())),
            "host-only-pc",
        )
    }

    @Test
    fun unsupportedLayoutsIgnoreCaseButProfilesDoNot() {
        ImpactoGameCatalog.useHostRules(hostRules)

        assertUnsupported(EngineScanner.detectEngine(pcMpkFolder("host-lower", "gamedata")), "host-only-pc")
        // The built-in S;G layout in a lower-case usrdir/ is likewise only a try with sg-pc.
        ImpactoGameCatalog.useHostRules(null)
        assertUnsupported(EngineScanner.detectEngine(pcMpkFolder("sg-lower", "usrdir")), "sg-pc")
    }

    @Test
    fun aMissingWindowsTextArchiveMakesTheFolderAnUnsupportedGameNotAWrongVariant() {
        assertUnsupported(EngineScanner.detectEngine(pcFolder("no-mes00-try", steamDataFiles - "mes00.cpk")), "mo6tw-pc")
    }

    @Test
    fun anAmbiguousLayoutIsStillNotListedAsUnsupported() {
        // Two unrelated profiles match completely: the folder stays unrecognised instead of becoming an unsupported try.
        val root = pcFolder("both-again")
        ImpactoGameCatalog.byProfileId("mo6tw")!!.requiredEntries.forEach { root.resolve(it).writeBytes(byteArrayOf(0)) }

        assertUnknown(EngineScanner.detectEngine(root))
    }

    @Test
    fun aFolderWithoutAnyMagesArchiveIsNotListed() {
        assertUnknown(EngineScanner.detectEngine(folder("plain", "game.exe", "readme.txt")))
    }

    @Test
    fun oneOrTwoCommonCriArchiveNamesAreNotAMagesGame() {
        // Other CRI-based games also ship script.cpk / system.cpk or a USRDIR/ with one archive.
        assertUnknown(EngineScanner.detectEngine(folder("cri-cpk", "script.cpk", "system.cpk", "game.exe")))
        assertUnknown(EngineScanner.detectEngine(folder("cri-mpk", "system.mpk", "game.exe")))
        assertUnknown(
            EngineScanner.detectEngine(pcMpkFolder("cri-usrdir", "USRDIR", files = listOf("script.mpk"))),
        )
    }

    @Test
    fun anotherEnginesMarkerWinsOverAnUnsupportedMagesLayout() {
        // Impacto (and its unsupported layouts) is tried last.
        val root = folder("siglus-with-cpk", "Gameexe.dat", "Scene.pck", "script.cpk", "system.cpk", "bgm.cpk")

        assertEquals(EngineType.SIGLUS, EngineScanner.detectEngine(root).engine)
    }
}
