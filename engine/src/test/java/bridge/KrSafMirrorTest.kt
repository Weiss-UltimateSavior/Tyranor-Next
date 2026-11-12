package bridge

import java.io.File
import java.nio.file.Files
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeNoException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Pure-JVM tests for KrSafMirror preparation policies and index/path conversion (no SAF/Context/Android
 * stubs). Covers the #129 index v2 size column, v1 compatibility, document-id conversion
 * and the write→parse round trip via [KrSafMirror.writeIndexText]/[KrSafMirror.parseIndexLines]
 * (uri construction is injected as identity to bypass the android.net.Uri JVM stub).
 */
class KrSafMirrorTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private fun v2(vararg lines: String): List<String> =
        listOf("krkr-saf-index-v2") + lines.toList()

    private fun v1(vararg lines: String): List<String> =
        listOf("krkr-saf-index-v1") + lines.toList()

    private fun v2Line(path: String, uri: String, size: Long): String =
        buildString { KrSafMirror.writeIndexText(this, listOf(Triple(path, uri, size))) }.lineSequence().drop(1).first()

    private fun v1Line(path: String, uri: String): String =
        v2Line(path, uri, 0L).substringBeforeLast('\t')

    @Test
    fun v2IndexRoundTripsSizesAndUris() {
        val path = "/data/user/0/x/games/game-abc/plugin/gamepad.dll"
        val uri = "content://com.android.externalstorage.documents/tree/VOL%3AROOT/document/VOL%3AROOT%2Fplugin%2Fgamepad.dll"
        val index = KrSafMirror.parseIndexLines(v2(v2Line(path, uri, 241664L))) { it }
        val entry = index[path]
        assertEquals(uri, entry?.uri)
        assertEquals(241664L, entry?.size)
    }

    @Test
    fun v2IndexToleratesMalformedSizeColumn() {
        val path = "/x/data.xp3"
        val line = v2Line(path, "content://a/b", 0L).substringBeforeLast('\t') + "\tnot-a-number"
        val index = KrSafMirror.parseIndexLines(v2(line)) { it }
        // Malformed size falls back to -1 (stat hook does not override); entry survives
        assertEquals(-1L, index[path]?.size)
    }

    @Test
    fun v1IndexLoadsWithoutSizes() {
        val index = KrSafMirror.parseIndexLines(v1(v1Line("/x/data.xp3", "content://a/b"))) { it }
        assertEquals("content://a/b", index["/x/data.xp3"]?.uri)
        // v1 has no size column: -1, the stat hook does not override
        assertEquals(-1L, index["/x/data.xp3"]?.size)
    }

    @Test
    fun unknownIndexVersionIsRejected() {
        val index = KrSafMirror.parseIndexLines(listOf("krkr-saf-index-v9", "junk\tjunk")) { it }
        assertTrue(index.isEmpty())
    }

    @Test
    fun emptyInputYieldsEmptyMap() {
        assertTrue(KrSafMirror.parseIndexLines(emptyList<String>()) { it }.isEmpty())
    }

    @Test
    fun pathsAreLowercasedOnLoad() {
        val index = KrSafMirror.parseIndexLines(v2(v2Line("/X/Data.XP3", "content://a/b", 1L))) { it }
        // Index keys are lowercased to match engine-lowercased game paths
        assertEquals(1, index.size)
        assertEquals("content://a/b", index["/x/data.xp3"]?.uri)
    }

    @Test
    fun blankPathsAndUrisAreSkipped() {
        val blankPath = v2Line("", "content://a/b", 5L)
        val blankUri = v2Line("/x/f", "", 5L)
        val index = KrSafMirror.parseIndexLines(v2(blankPath, blankUri)) { it }
        assertTrue(index.isEmpty())
    }

    @Test
    fun documentIdConversionCoversVolumesAndAliases() {
        assertEquals("VOL:sub/file.xp3", KrSafMirror.storagePathToDocumentId("/storage/VOL/sub/file.xp3"))
        assertEquals("VOL:", KrSafMirror.storagePathToDocumentId("/storage/VOL"))
        assertEquals("primary:Games/G", KrSafMirror.storagePathToDocumentId("/storage/emulated/0/Games/G"))
        assertEquals("primary:Games/G", KrSafMirror.storagePathToDocumentId("/sdcard/Games/G"))
        assertEquals("primary:", KrSafMirror.storagePathToDocumentId("/storage/emulated/0"))
        assertEquals("primary:", KrSafMirror.storagePathToDocumentId("/sdcard"))
        assertNull(KrSafMirror.storagePathToDocumentId("/data/user/0/app/files"))
        assertNull(KrSafMirror.storagePathToDocumentId(null))
        assertNull(KrSafMirror.storagePathToDocumentId(""))
    }

    @Test
    fun documentIdConversionNormalizesSeparatorsAndTrailingSlash() {
        assertEquals("VOL:sub/file.xp3", KrSafMirror.storagePathToDocumentId("/storage/VOL/sub\\file.xp3"))
        assertEquals("VOL:sub", KrSafMirror.storagePathToDocumentId("/storage/VOL/sub/"))
    }

    @Test
    fun stripSigSuffixIsCaseInsensitiveAndConservative() {
        // Document ids keep the provider case: an uppercase .SIG must be stripped too,
        // otherwise the signature document is indexed as the DLL itself
        assertEquals("/x/Foo.DLL", KrSafMirror.stripSigSuffix("/x/Foo.DLL.SIG"))
        assertEquals("/x/Foo.dll", KrSafMirror.stripSigSuffix("/x/Foo.dll.sig"))
        assertEquals("/x/Foo.Dll", KrSafMirror.stripSigSuffix("/x/Foo.Dll.Sig"))
        assertEquals("/x/Foo.dll", KrSafMirror.stripSigSuffix("/x/Foo.dll"))
        assertEquals("", KrSafMirror.stripSigSuffix(".sig"))
        assertEquals("sig", KrSafMirror.stripSigSuffix("sig"))
        assertEquals("", KrSafMirror.stripSigSuffix(""))
    }

    @Test
    fun encodedDocumentIdsSurviveRoundTrip() {
        // Index uris carry %XX-encoded document ids: parsing must not decode them
        val encoded = "content://com.android.externalstorage.documents/tree/AAE6-D7FF%3A%E6%98%9F/document/AAE6-D7FF%3A%E6%98%9F%2Fdata.xp3"
        val index = KrSafMirror.parseIndexLines(v2(v2Line("/x/data.xp3", encoded, 100L))) { it }
        assertEquals(encoded, index["/x/data.xp3"]?.uri)
    }

    @Test
    fun writeIndexTextRoundTripsThroughParser() {
        // Real write→parse round trip: writeIndexText (the writeIndex serializer) feeds
        // parseIndexLines (the loadIndex parser), locking the on-disk format from both sides
        val entries = listOf(
            Triple("/a/b.xp3", "content://t/d", 42L),
            Triple("/plugin/gamepad.dll", "content://t/e", 241664L),
            Triple("/全cg存档/datasu.ksd", "content://t/f", -1L),
        )
        val text = buildString { KrSafMirror.writeIndexText(this, entries) }
        val index = KrSafMirror.parseIndexLines(text.lineSequence().filter { it.isNotBlank() }.toList()) { it }
        assertEquals(3, index.size)
        assertEquals(42L, index["/a/b.xp3"]?.size)
        assertEquals("content://t/e", index["/plugin/gamepad.dll"]?.uri)
        assertEquals(-1L, index["/全cg存档/datasu.ksd"]?.size)
    }

    @Test
    fun writeIndexTextStartsWithV2Header() {
        val text = buildString { KrSafMirror.writeIndexText(this, emptyList()) }
        assertEquals("krkr-saf-index-v2", text.lineSequence().first())
    }

    @Test
    fun staleOverrideMarkerKeepsTheIndexRowRoutableWhileALiveOverrideFiltersIt() {
        // Audit of 84e1d22, M1: a marker can outlive its local file (failed marker
        // delete after unlink, or external removal). Filtering such a row out of the
        // loaded documents would make the key unroutable (NOT_ROUTED → ENOENT on the
        // missing local file) — the row must stay so a read falls back to the source.
        val root = temporary.newFolder("stale-marker")
        val staleTarget = File(root, "data.xp3").apply { createNewFile() }
        val liveTarget = File(root, "plugin/gamepad.dll").apply {
            requireNotNull(parentFile).mkdirs(); createNewFile()
        }
        val localFiles = KrMirrorLocalFiles(root)
        localFiles.markOverridden(staleTarget)
        localFiles.markOverridden(liveTarget)
        check(staleTarget.delete())
        val indexFile = File(temporary.newFolder("idx"), "k.idx")
        indexFile.writeText(buildString { KrSafMirror.writeIndexText(this, listOf(
            Triple(staleTarget.absolutePath.lowercase(Locale.ROOT), "content://t/stale", 10L),
            Triple(liveTarget.absolutePath.lowercase(Locale.ROOT), "content://t/live", 20L),
        )) })
        val loaded = KrSafMirror.loadIndexed(indexFile.path, root) { it }
        // The stale marker keeps its row (source fallback stays reachable)...
        assertEquals("content://t/stale", loaded.documents[staleTarget.absolutePath.lowercase(Locale.ROOT)]?.uri)
        // ...while the live override's row is filtered out (local semantics own it).
        assertFalse(loaded.documents.containsKey(liveTarget.absolutePath.lowercase(Locale.ROOT)))
    }

    @Test
    fun corruptRowsDoNotDiscardValidSiblingResources() {
        val index = KrSafMirror.parseIndexLines(v2(
            v2Line("/x/first", "content://a/first", 10L),
            "%%%\tY29udGVudDovL2EvYg\t1",
            "L3g\t%%%\t1",
            v2Line("/x/last", "content://a/last", 20L),
        )) { it }
        assertEquals(setOf("/x/first", "/x/last"), index.keys)
    }

    @Test
    fun productionSerializerRetainsTheLiteralV2WireFormat() {
        assertEquals("krkr-saf-index-v2\nL3g\tY29udGVudDovL2EvYg\t41\n",
            buildString { KrSafMirror.writeIndexText(this, listOf(Triple("/x", "content://a/b", 41L))) })
    }

    @Test
    fun productionFileReaderAndStreamingWriterRoundTrip() {
        val file = temporary.newFile("index.idx")
        file.bufferedWriter().use { writer ->
            KrSafMirror.writeIndexText(writer, listOf(Triple("/X/data.xp3", "content://a/b", 123L)))
        }
        val index = KrSafMirror.readIndexFile(file) { it }
        assertEquals(123L, index["/x/data.xp3"]?.size)
        assertEquals("content://a/b", index["/x/data.xp3"]?.uri)
        assertTrue(KrSafMirror.readIndexFile(File(temporary.root, "missing")) { it }.isEmpty())
    }

    @Test
    fun savedataExclusionMatchesOnlyTheSourceRootSubtree() {
        assertTrue(KrSafMirror.isSourceSavedata("savedata"))
        assertTrue(KrSafMirror.isSourceSavedata("SaveData/slot/data.ksd"))
        assertFalse(KrSafMirror.isSourceSavedata("savedatabase/data.xp3"))
        assertFalse(KrSafMirror.isSourceSavedata("data/savedata/archive.xp3"))
    }

    @Test
    fun newMirrorIdentityDoesNotDependOnTheGameTitle() {
        val key = KrSafMirror.mirrorKey("content://a/game", "/storage/VOL/game")
        val games = temporary.newFolder("games")
        assertEquals(File(games, "game-$key"), KrSafMirror.findMirrorRoot(games, key))
    }

    @Test
    fun legacyMirrorWithSavesIsReusedWithoutMovingOrDeletingData() {
        val games = temporary.newFolder("games")
        val key = "0123456789abcdef"
        val empty = File(games, "aaa-$key").apply { mkdirs() }
        val legacy = File(games, "old-title-$key").apply { mkdirs() }
        val save = File(legacy, "savedata/slot.ksd").apply { requireNotNull(parentFile).mkdirs(); writeText("save") }
        assertEquals(legacy, KrSafMirror.findMirrorRoot(games, key))
        assertTrue(empty.isDirectory)
        assertEquals("save", save.readText())
        assertFalse(File(games, "game-$key").exists())
    }

    @Test
    fun staleSaveCleanupOnlyDeletesIndexedEmptyPlaceholders() {
        val root = temporary.newFolder("mirror")
        val saveRoot = File(root, "savedata").apply { mkdirs() }
        val stale = File(saveRoot, "stale.ksd").apply { createNewFile() }
        val userEmpty = File(saveRoot, "user-empty.ksd").apply { createNewFile() }
        val userSave = File(saveRoot, "slot.ksd").apply { writeText("save") }
        val current = File(saveRoot, "current.ksd").apply { createNewFile() }
        val outside = temporary.newFile("outside.ksd")
        val previous = setOf(stale.path, userSave.path, current.path, outside.path)
        assertEquals(1, KrSafMirror.removeStaleSavePlaceholders(root, previous, setOf(current.path)))
        assertFalse(stale.exists())
        assertTrue(userEmpty.exists())
        assertTrue(current.exists())
        assertTrue(outside.exists())
        assertEquals("save", userSave.readText())
    }


    @Test
    fun populatedLegacySavesBeatAnExistingEmptyStableRoot() {
        val games = temporary.newFolder("games")
        val key = "0123456789abcdef"
        val stable = File(games, "game-$key").apply { mkdirs() }
        val legacy = File(games, "legacy-$key").apply { mkdirs() }
        File(legacy, "savedata/slot.ksd").apply {
            requireNotNull(parentFile).mkdirs()
            writeText("save")
        }
        assertEquals(legacy, KrSafMirror.findMirrorRoot(games, key))
        assertTrue(stable.isDirectory)
        File(stable, "savedata/current.ksd").apply {
            requireNotNull(parentFile).mkdirs()
            writeText("current")
        }
        assertEquals(stable, KrSafMirror.findMirrorRoot(games, key))
        assertEquals("save", File(legacy, "savedata/slot.ksd").readText())
    }


    @Test
    fun childDocumentIdsDoNotInsertASlashAfterTheVolumeColon() {
        assertEquals("VOL:patch.tjs", KrSafMirror.childDocumentId("VOL:", "patch.tjs"))
        assertEquals("primary:ds.ini", KrSafMirror.childDocumentId("primary:", "ds.ini"))
        assertEquals("VOL:game/patch.tjs", KrSafMirror.childDocumentId("VOL:game", "patch.tjs"))
        assertEquals("VOL:game/patch.tjs", KrSafMirror.childDocumentId("VOL:game/", "patch.tjs"))
    }

    @Test
    fun scanCallbacksHaveABoundedWaitAndPropagateInterruption() {
        assertTrue(KrSafMirror.awaitScanCallbacks(CountDownLatch(0), 0L))
        assertFalse(KrSafMirror.awaitScanCallbacks(CountDownLatch(1), 0L))
        try {
            Thread.currentThread().interrupt()
            try {
                KrSafMirror.awaitScanCallbacks(CountDownLatch(1), 0L)
                fail("Interrupted scan must cancel preparation")
            } catch (_: CancellationException) {
                assertTrue(Thread.currentThread().isInterrupted)
            }
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun sameGamePreparationHasASingleWriter() {
        val directory = temporary.newFolder("index-locks")
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val secondEntered = CountDownLatch(1)
        val error = AtomicReference<Throwable>()
        val first = Thread {
            try {
                KrSafMirror.withPreparationLock(directory, "same-game") {
                    firstEntered.countDown()
                    check(releaseFirst.await(5, TimeUnit.SECONDS))
                }
            } catch (failure: Throwable) {
                error.set(failure)
            }
        }
        val second = Thread {
            try {
                secondStarted.countDown()
                KrSafMirror.withPreparationLock(directory, "same-game") { secondEntered.countDown() }
            } catch (failure: Throwable) {
                error.set(failure)
            }
        }
        try {
            first.start()
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS))
            second.start()
            assertTrue(secondStarted.await(5, TimeUnit.SECONDS))
            assertFalse(secondEntered.await(100, TimeUnit.MILLISECONDS))
        } finally {
            releaseFirst.countDown()
            first.join(5000)
            second.join(5000)
        }
        assertFalse(first.isAlive)
        assertFalse(second.isAlive)
        assertNull(error.get())
        assertEquals(0L, secondEntered.count)
    }

    @Test
    fun staleSaveCleanupPreservesMarkedEmptyLocalEdits() {
        val root = temporary.newFolder("marked-mirror")
        val file = File(root, "savedata/empty.ksd").apply {
            requireNotNull(parentFile).mkdirs()
            createNewFile()
        }
        assertEquals(0, KrSafMirror.removeStaleSavePlaceholders(root, setOf(file.path), emptySet()) {
            it == file
        })
        assertTrue(file.exists())
    }


    @Test
    fun internalMirrorStateIsNeverImportedFromTheGameSource() {
        assertTrue(KrSafMirror.isSourceEntryExcluded(".tyranor-mirror-state"))
        assertTrue(KrSafMirror.isSourceEntryExcluded(".TYRANOR-MIRROR-STATE/local-overrides/hash"))
        assertTrue(KrSafMirror.isSourceEntryExcluded("savedata/slot.ksd"))
        assertFalse(KrSafMirror.isSourceEntryExcluded("data/.tyranor-mirror-state/source"))
        assertFalse(KrSafMirror.isSourceEntryExcluded(".tyranor-mirror-state.txt"))
    }

    @Test
    fun volumeRootGrantsKeepTheFirstChildNameIntact() {
        assertEquals("game/plugins", KrSafMirror.relativeDocumentPath("VOL:game/plugins", "VOL:"))
        assertEquals("", KrSafMirror.relativeDocumentPath("VOL:", "vol:"))
        assertEquals("plugins", KrSafMirror.relativeDocumentPath("VOL:game/plugins", "VOL:game"))
        assertEquals("plugins", KrSafMirror.relativeDocumentPath("VOL:game/plugins", "VOL:game/"))
        assertNull(KrSafMirror.relativeDocumentPath("VOL:game-other", "VOL:game"))
        assertNull(KrSafMirror.relativeDocumentPath("OTHER:game", "VOL:"))
    }

    @Test
    fun stalePlaceholderCleanupResolvesTheActualLegacyRootCase() {
        val root = temporary.newFolder("OldTitle-0123456789abcdef")
        val stale = File(root, "savedata/stale.ksd").apply {
            requireNotNull(parentFile).mkdirs()
            createNewFile()
        }
        val local = File(root, "savedata/local.ksd").apply { createNewFile() }
        val state = KrMirrorLocalFiles(root)
        state.markOverridden(local)
        val keys = setOf(stale.path.lowercase(Locale.ROOT), local.path.lowercase(Locale.ROOT),
            root.path.lowercase(Locale.ROOT) + "/../outside")
        assertEquals(1, KrSafMirror.removeStaleSavePlaceholders(root, keys, emptySet(), state::isOverridden))
        assertFalse(stale.exists())
        assertTrue(local.exists())
    }

    @Test
    fun missingBootstrapRequiresBothAnAbsentDocumentAndACompleteRootListing() {
        assertEquals(KrSafMirror.BootstrapFileState.ABSENT,
            KrSafMirror.bootstrapProbeFailureState("patch.tjs", true, true, setOf("data.xp3")))
        assertEquals(KrSafMirror.BootstrapFileState.UNAVAILABLE,
            KrSafMirror.bootstrapProbeFailureState("patch.tjs", true, false, emptySet()))
        assertEquals(KrSafMirror.BootstrapFileState.UNAVAILABLE,
            KrSafMirror.bootstrapProbeFailureState("patch.tjs", false, true, emptySet()))
        assertEquals(KrSafMirror.BootstrapFileState.UNAVAILABLE,
            KrSafMirror.bootstrapProbeFailureState("patch.tjs", true, true, setOf("patch.tjs")))
        assertEquals(KrSafMirror.BootstrapFileState.ABSENT,
            KrSafMirror.bootstrapProbeFailureState("PATCH.TJS", true, true, setOf("data.xp3")))
    }

    @Test
    fun mirrorSourceDecisionCoversAllCompletenessStates() {
        // Fresh listing, no failures: full rebuild.
        assertEquals(KrSafMirror.MirrorSourceDecision.REBUILD_FRESH,
            KrSafMirror.decideMirrorSources(observed = 10, previousCount = 5, queryFailures = 0,
                localOverrideCount = 0, rootListingComplete = true))
        // A root listing that was not observed complete must not rebuild-fresh: a
        // silently truncated provider response would otherwise delete old rows.
        assertEquals(KrSafMirror.MirrorSourceDecision.REBUILD_MERGED,
            KrSafMirror.decideMirrorSources(observed = 10, previousCount = 5, queryFailures = 0,
                localOverrideCount = 0, rootListingComplete = false))
        // Partial listing failure: rebuild but carry previous rows.
        assertEquals(KrSafMirror.MirrorSourceDecision.REBUILD_MERGED,
            KrSafMirror.decideMirrorSources(observed = 3, previousCount = 10, queryFailures = 2,
                localOverrideCount = 1, rootListingComplete = true))
        // Total listing failure with a usable previous index: keep it and launch anyway.
        assertEquals(KrSafMirror.MirrorSourceDecision.KEEP_EXISTING,
            KrSafMirror.decideMirrorSources(observed = 0, previousCount = 10, queryFailures = 3,
                localOverrideCount = 0, rootListingComplete = true))
        assertEquals(KrSafMirror.MirrorSourceDecision.KEEP_EXISTING,
            KrSafMirror.decideMirrorSources(observed = 0, previousCount = 0, queryFailures = 1,
                localOverrideCount = 2, rootListingComplete = true))
        // Nothing observed and nothing to fall back on: typed failure (EMPTY_MIRROR).
        assertEquals(KrSafMirror.MirrorSourceDecision.FAIL_EMPTY,
            KrSafMirror.decideMirrorSources(observed = 0, previousCount = 0, queryFailures = 1,
                localOverrideCount = 0, rootListingComplete = true))
    }

    @Test
    fun mirrorSourceDecisionTreatsLocalOverridesAsNonObservations() {
        // Local copies alone (0 observed source entries) do not satisfy the freshness
        // requirement; the decision still consults previous index availability.
        assertEquals(KrSafMirror.MirrorSourceDecision.KEEP_EXISTING,
            KrSafMirror.decideMirrorSources(observed = 0, previousCount = 0, queryFailures = 0, localOverrideCount = 3))
    }

    @Test
    fun symlinkMirrorRootRetainsIndexedSourcesAndCleansOnlyOldPlaceholders() {
        val realRoot = temporary.newFolder("real-OldTitle")
        val alias = File(temporary.root, "alias-OldTitle")
        try {
            Files.createSymbolicLink(alias.toPath(), realRoot.toPath())
        } catch (error: Exception) {
            assumeNoException("Symbolic links require platform support", error)
            return
        }
        val asset = File(alias, "plugin.dll").apply { createNewFile() }
        val stale = File(alias, "savedata/stale.ksd").apply {
            requireNotNull(parentFile).mkdirs()
            createNewFile()
        }
        val local = File(alias, "savedata/local.ksd").apply { createNewFile() }
        val helper = KrMirrorLocalFiles(alias)
        helper.markOverridden(local)
        val key = asset.absolutePath.lowercase(Locale.ROOT)
        val index = File(temporary.root, "alias.idx")
        index.writeText(buildString { KrSafMirror.writeIndexText(this, listOf(Triple(key, "content://source/plugin", 4L))) })
        val loaded = KrSafMirror.retainSourceEntries(KrSafMirror.readIndexFile(index) { it },
            helper, helper.snapshotOverrideKeys())
        assertEquals("content://source/plugin", loaded[key]?.uri)
        assertEquals(asset.canonicalFile, helper.fileForIndexKey(key)?.canonicalFile)
        assertEquals(1, KrSafMirror.removeStaleSavePlaceholders(alias,
            setOf(stale.path.lowercase(Locale.ROOT), local.path.lowercase(Locale.ROOT)), emptySet(), helper::isOverridden))
        assertFalse(stale.exists())
        assertTrue(local.exists())
        assertTrue(asset.exists())
    }

    @Test
    fun legacyEmptyOverrideStillWinsWhenFilteringAnExistingIndex() {
        val root = temporary.newFolder("legacy-marker")
        val local = File(root, "patch.tjs").apply { createNewFile() }
        val helper = KrMirrorLocalFiles(root)
        helper.markOverridden(local)
        helper.ownershipMarker(local).writeBytes(byteArrayOf(1))
        val key = local.absolutePath.lowercase(Locale.ROOT)
        val entries = mapOf(key to KrSafMirror.MirrorEntryHolder("content://source/patch", 123L))
        assertTrue(KrSafMirror.retainSourceEntries(entries, helper, helper.snapshotOverrideKeys(entries.keys)).isEmpty())
        assertEquals(0L, local.length())
    }

    @Test
    fun listedBootstrapPlaceholderKeepsRealSourceUriAndSize() {
        val root = temporary.newFolder("listed-bootstrap")
        for (name in KrSafMirror.BOOTSTRAP_FILE_NAMES) {
            val local = File(root, name).apply { createNewFile() }
            val source = "content://source/$name"
            val payload = requireNotNull(KrSafMirror.bootstrapFromListedEntry(local, source, 123L, false))
            assertEquals(KrSafMirror.BootstrapFileState.PRESENT, payload.state)
            assertEquals(source, payload.uri)
            assertEquals(123L, payload.size)
            assertFalse(payload.localOverride)
            assertEquals(0L, local.length())
        }
    }

    @Test
    fun onlyActualLocalBootstrapContentsOrAnExistingMarkedFileOverrideTheSource() {
        val root = temporary.newFolder("local-bootstrap")
        val local = File(root, "patch.tjs").apply { writeText("local patch") }
        assertTrue(requireNotNull(KrSafMirror.bootstrapFromListedEntry(local, "content://source/patch", 50L, false)).localOverride)
        local.writeBytes(byteArrayOf())
        assertTrue(requireNotNull(KrSafMirror.bootstrapFromListedEntry(local, "content://source/patch", 50L, true)).localOverride)
        assertFalse(requireNotNull(KrSafMirror.bootstrapFromListedEntry(local, "content://source/patch", 0L, false)).localOverride)
        assertNull(KrSafMirror.bootstrapFromListedEntry(local, null, -1L, false))
        assertTrue(local.delete())
        assertNull(KrSafMirror.bootstrapFromListedEntry(local, null, -1L, true))
    }

    @Test
    fun failedSourceWithAnEmptyPreviousIndexKeepsARealMarkedStartupCopy() {
        val root = temporary.newFolder("local-startup-fallback")
        val startup = File(root, "startup.tjs").apply { writeText("local startup contents") }
        KrMirrorLocalFiles(root).markOverridden(startup)
        val index = File(temporary.root, "empty-previous.idx").apply { writeText("krkr-saf-index-v2\n") }
        val previous = KrSafMirror.readIndexFile(index) { it }
        assertTrue(previous.isEmpty())

        val sources = KrSafMirror.assemblePreparationSources(root, previous.keys, emptyList(),
            observed = 0, queryFailures = 3)

        assertEquals(KrSafMirror.MirrorSourceDecision.KEEP_EXISTING, sources.decision)
        assertTrue(startup.absolutePath.lowercase(Locale.ROOT) in sources.localOverrides)
        assertEquals("local startup contents", startup.readText())
    }

    @Test
    fun absentMarkedResourcesAndSaveOnlyStateDoNotPretendTheMirrorIsUsable() {
        val root = temporary.newFolder("stale-local-fallback")
        val startup = File(root, "startup.tjs").apply { writeText("local startup") }
        val saves = File(root, "savedata/slot.ksd").apply {
            requireNotNull(parentFile).mkdirs()
            writeText("save")
        }
        val state = KrMirrorLocalFiles(root)
        state.markOverridden(startup)
        state.markOverridden(saves)
        assertTrue(startup.delete())

        val sources = KrSafMirror.assemblePreparationSources(root, emptySet(), emptyList(),
            observed = 0, queryFailures = 2)

        assertEquals(KrSafMirror.MirrorSourceDecision.FAIL_EMPTY, sources.decision)
        assertTrue(sources.localOverrides.isEmpty())
        assertEquals("save", saves.readText())
    }

    @Test
    fun failedSourceWithHeaderOnlyIndexAlsoRecognizesLegacyStartupMarkers() {
        val root = temporary.newFolder("legacy-startup-fallback")
        val startup = File(root, "startup.tjs").apply { writeText("legacy startup contents") }
        val helper = KrMirrorLocalFiles(root)
        helper.markOverridden(startup)
        helper.ownershipMarker(startup).writeBytes(byteArrayOf(1))
        val emptyIndex = File(temporary.root, "legacy-empty.idx").apply { writeText("krkr-saf-index-v2\n") }
        val previous = KrSafMirror.readIndexFile(emptyIndex) { it }

        val sources = KrSafMirror.assemblePreparationSources(root, previous.keys, emptyList(),
            observed = 0, queryFailures = 2)

        assertEquals(KrSafMirror.MirrorSourceDecision.KEEP_EXISTING, sources.decision)
        assertTrue(startup.absolutePath.lowercase(Locale.ROOT) in sources.localOverrides)
        assertEquals("legacy startup contents", startup.readText())
    }

    @Test
    fun nullMissingBootstrapResponsesAllowFreshPreparationAndRemoveOnlyProvenPlaceholders() {
        val root = temporary.newFolder("missing-bootstrap-fresh")
        val startup = File(root, "startup.tjs").apply { createNewFile() }
        val removed = File(root, "old-resource.xp3").apply { createNewFile() }
        val unknown = File(root, "user-empty").apply { createNewFile() }
        val edited = File(root, "edited.cfg").apply { writeText("local contents") }
        val markedEmpty = File(root, "local-empty.cfg").apply { createNewFile() }
        KrMirrorLocalFiles(root).markOverridden(markedEmpty)
        val key: (File) -> String = { it.absolutePath.lowercase(Locale.ROOT) }
        val previousFile = File(temporary.root, "previous-deleted.idx")
        previousFile.writeText(buildString { KrSafMirror.writeIndexText(this, listOf(
            Triple(key(removed), "content://source/old-resource", 100L),
            Triple(key(edited), "content://source/edited", 100L),
            Triple(key(markedEmpty), "content://source/local-empty", 100L),
        )) })
        val previous = KrSafMirror.readIndexFile(previousFile) { it }
        val bootstrap = KrSafMirror.BOOTSTRAP_FILE_NAMES.map { name ->
            KrSafMirror.bootstrapFromMissingResponse(name, true, true, setOf("startup.tjs"))
        }
        assertTrue(bootstrap.all { it.state == KrSafMirror.BootstrapFileState.ABSENT && !it.localOverride })
        val entries = mutableListOf(Triple(key(startup), "content://source/startup", 50L))
        val known = entries.mapTo(HashSet()) { it.first }
        val sources = KrSafMirror.assemblePreparationSources(root, previous.keys, entries,
            observed = 1, queryFailures = bootstrap.count { it.state == KrSafMirror.BootstrapFileState.UNAVAILABLE },
            rootListingComplete = true)

        assertEquals(KrSafMirror.MirrorSourceDecision.REBUILD_FRESH, sources.decision)
        assertEquals(0, KrSafMirror.reconcilePreparedIndex(root, previous, entries, known,
            sources.localOverrides, sources.decision, confirmMissing = { true }))
        assertFalse(removed.exists())
        assertTrue(unknown.exists())
        assertTrue(markedEmpty.exists())
        assertEquals("local contents", edited.readText())
        assertEquals(setOf(key(startup)), entries.map { it.first }.toSet())
    }

    @Test
    fun freshRebuildKeepsSilentlyOmittedResourcesUntilAbsenceIsConfirmed() {
        // Audit F3: a root cursor that ends normally can still be a valid subset; the
        // omitted old row keeps its placeholder and index row until the second source
        // check confirms the document is gone.
        val root = temporary.newFolder("silent-subset")
        val listed = File(root, "data.xp3").apply { createNewFile() }
        val omitted = File(root, "plugin/plugin.dll").apply {
            requireNotNull(parentFile).mkdirs(); createNewFile()
        }
        val key: (File) -> String = { it.absolutePath.lowercase(Locale.ROOT) }
        val previousFile = File(temporary.root, "previous-subset.idx")
        previousFile.writeText(buildString { KrSafMirror.writeIndexText(this, listOf(
            Triple(key(listed), "content://source/data", 10L),
            Triple(key(omitted), "content://source/plugin", 20L),
        )) })
        val previous = KrSafMirror.readIndexFile(previousFile) { it }
        val sources = KrSafMirror.assemblePreparationSources(root, previous.keys,
            mutableListOf(Triple(key(listed), "content://source/data", 10L)),
            observed = 1, queryFailures = 0, rootListingComplete = true)
        assertEquals(KrSafMirror.MirrorSourceDecision.REBUILD_FRESH, sources.decision)

        // Without confirmation: the omitted row is carried back and stays reachable.
        val entriesCarried = mutableListOf(Triple(key(listed), "content://source/data", 10L))
        val knownCarried = entriesCarried.mapTo(HashSet()) { it.first }
        assertEquals(1, KrSafMirror.reconcilePreparedIndex(root, previous, entriesCarried, knownCarried,
            sources.localOverrides, sources.decision, confirmMissing = { false }))
        assertTrue(omitted.exists())
        assertTrue(entriesCarried.any {
            it.first == key(omitted) && it.second == "content://source/plugin" && it.third == 20L
        })

        // With confirmation: placeholder removed and the row dropped from the index.
        val entriesConfirmed = mutableListOf(Triple(key(listed), "content://source/data", 10L))
        val knownConfirmed = entriesConfirmed.mapTo(HashSet()) { it.first }
        assertEquals(0, KrSafMirror.reconcilePreparedIndex(root, previous, entriesConfirmed, knownConfirmed,
            sources.localOverrides, sources.decision, confirmMissing = { true }))
        assertFalse(omitted.exists())
        assertFalse(entriesConfirmed.any { it.first == key(omitted) })
    }

    @Test
    fun freshRebuildConfirmsMissingDocumentsUsingOriginalAbsoluteIndexKeys() {
        val root = temporary.newFolder("absolute-absence-check")
        val listed = File(root, "data.xp3").apply { createNewFile() }
        val removed = File(root, "plugin/removed.dll").apply {
            requireNotNull(parentFile).mkdirs()
            createNewFile()
        }
        val unconfirmed = File(root, "plugin/unavailable.dll").apply { createNewFile() }
        val key: (File) -> String = { it.absolutePath.lowercase(Locale.ROOT) }
        val removedUri = "content://source/removed"
        val unavailableUri = "content://source/unavailable"
        val previousFile = File(temporary.root, "previous-absolute-absence.idx").apply {
            writeText(buildString { KrSafMirror.writeIndexText(this, listOf(
                Triple(key(listed), "content://source/data", 10L),
                Triple(key(removed), removedUri, 20L),
                Triple(key(unconfirmed), unavailableUri, 30L),
            )) })
        }
        val previous = KrSafMirror.readIndexFile(previousFile) { it }
        val entries = mutableListOf(Triple(key(listed), "content://source/data", 10L))
        val known = entries.mapTo(HashSet()) { it.first }
        val checked = mutableListOf<String>()

        val carried = KrSafMirror.reconcilePreparedIndex(root, previous, entries, known,
            emptySet(), KrSafMirror.MirrorSourceDecision.REBUILD_FRESH,
            confirmMissing = { indexKey ->
                checked += indexKey
                // Match prepare's real lookup; a relative identity cannot retrieve the row.
                val entry = requireNotNull(previous[indexKey])
                entry.uri == removedUri
            })

        assertEquals(setOf(key(removed), key(unconfirmed)), checked.toSet())
        assertEquals(2, checked.size)
        assertFalse(removed.exists())
        assertTrue(unconfirmed.exists())
        assertTrue(listed.exists())
        assertEquals(1, carried)
        assertEquals(setOf(key(listed), key(unconfirmed)), known)
        assertEquals(setOf(key(listed), key(unconfirmed)), entries.map { it.first }.toSet())
        assertTrue(entries.any { it.first == key(unconfirmed) && it.second == unavailableUri && it.third == 30L })
    }

    @Test
    fun freshCleanupPreservesConcurrentNonEmptyCopyUp() {
        assertFreshCleanupPreservesConcurrentCopyUp("local resource")
    }

    @Test
    fun freshCleanupPreservesConcurrentIntentionallyEmptyCopyUp() {
        assertFreshCleanupPreservesConcurrentCopyUp("")
    }

    private fun assertFreshCleanupPreservesConcurrentCopyUp(contents: String) {
        val root = temporary.newFolder("cleanup-copy-up-race")
        val listed = File(root, "data.xp3").apply { createNewFile() }
        val resource = File(root, "plugin/removed.dll").apply {
            requireNotNull(parentFile).mkdirs()
            createNewFile()
        }
        val key: (File) -> String = { it.absolutePath.lowercase(Locale.ROOT) }
        val previous = mapOf(key(resource) to KrSafMirror.MirrorEntryHolder("content://source/removed", 50L))
        val entries = mutableListOf(Triple(key(listed), "content://source/data", 10L))
        val known = entries.mapTo(HashSet()) { it.first }
        val localFiles = KrMirrorLocalFiles(root)
        val confirmed = CountDownLatch(1)
        val failure = AtomicReference<Throwable>()
        val carried = AtomicReference<Int>()
        val cleanup = Thread {
            try {
                carried.set(KrSafMirror.reconcilePreparedIndex(root, previous, entries, known,
                    emptySet(), KrSafMirror.MirrorSourceDecision.REBUILD_FRESH,
                    confirmMissing = { indexKey ->
                        assertEquals(key(resource), indexKey)
                        confirmed.countDown()
                        true
                    }))
            } catch (error: Throwable) {
                failure.set(error)
            }
        }
        try {
            localFiles.withFileLock(resource) {
                cleanup.start()
                assertTrue(confirmed.await(5, TimeUnit.SECONDS))
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (cleanup.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.yield()
                assertEquals("Cleanup must wait for the resource lock", Thread.State.BLOCKED, cleanup.state)
                // A running session publishes after source confirmation but before
                // cleanup acquires the resource lock; even a deliberate empty override survives.
                val bytes = contents.toByteArray(Charsets.UTF_8)
                localFiles.copyUp(resource, bytes.size.toLong()) { bytes.inputStream() }
                assertTrue(localFiles.isOverridden(resource))
            }
            cleanup.join(5000)
            assertFalse(cleanup.isAlive)
            assertNull(failure.get())
            assertEquals(0, carried.get().toInt())
            assertTrue(resource.isFile)
            assertEquals(contents, resource.readText())
            assertTrue(localFiles.isOverridden(resource))
            assertFalse(entries.any { it.first == key(resource) })
        } finally {
            cleanup.join(5000)
        }
    }

    @Test
    fun skippedDepthLimitedOrInvalidRootListingsCarryPreviousRowsInsteadOfPublishingAFreshSubset() {
        for ((index, flags) in listOf(Triple(1, 0, false), Triple(0, 1, false), Triple(0, 0, true)).withIndex()) {
            val (skipped, depth, invalidRoot) = flags
            val root = temporary.newFolder("partial-$index")
            val startup = File(root, "startup.tjs").apply { createNewFile() }
            val old = File(root, "deep/resource.xp3").apply { requireNotNull(parentFile).mkdirs(); createNewFile() }
            val key: (File) -> String = { it.absolutePath.lowercase(Locale.ROOT) }
            val previous = mapOf(key(old) to KrSafMirror.MirrorEntryHolder("content://source/deep", 999L))
            val entries = mutableListOf(Triple(key(startup), "content://source/startup", 1L))
            val known = entries.mapTo(HashSet()) { it.first }
            val sources = KrSafMirror.assemblePreparationSources(root, previous.keys, entries,
                observed = 1, queryFailures = 0, skippedEntries = skipped,
                depthLimitedDirectories = depth, rootListingInvalid = invalidRoot)
            assertEquals(KrSafMirror.MirrorSourceDecision.REBUILD_MERGED, sources.decision)
            assertEquals(1, KrSafMirror.reconcilePreparedIndex(root, previous, entries, known,
                sources.localOverrides, sources.decision))
            assertTrue(old.exists())
            assertTrue(entries.any { it.first == key(old) && it.second == "content://source/deep" && it.third == 999L })
        }
    }

    @Test
    fun partialPreparationDoesNotCarryAConfirmedAbsentBootstrapButKeepsUnknownResources() {
        val root = temporary.newFolder("partial-absent-bootstrap")
        val patch = File(root, "patch.tjs").apply { createNewFile() }
        val unknown = File(root, "unknown.xp3").apply { createNewFile() }
        val key: (File) -> String = { it.absolutePath.lowercase(Locale.ROOT) }
        val previous = mapOf(key(patch) to KrSafMirror.MirrorEntryHolder("content://source/patch", 30L),
            key(unknown) to KrSafMirror.MirrorEntryHolder("content://source/unknown", 70L))
        val missing = KrSafMirror.bootstrapFromMissingResponse("patch.tjs", true, true, setOf("data"))
        val absent = if (missing.state == KrSafMirror.BootstrapFileState.ABSENT) setOf(key(patch)) else emptySet()
        val entries = mutableListOf<Triple<String, String, Long>>()
        val known = hashSetOf<String>()
        assertEquals(1, KrSafMirror.reconcilePreparedIndex(root, previous, entries, known,
            emptySet(), KrSafMirror.MirrorSourceDecision.REBUILD_MERGED, absent))
        assertFalse(patch.exists())
        assertTrue(unknown.exists())
        assertEquals(setOf(key(unknown)), entries.map { it.first }.toSet())
    }

    @Test
    fun latestSaveTimestampWinsBeforeStableRootAndDirectoryNames() {
        val games = temporary.newFolder("timestamp-roots")
        val key = "0123456789abcdef"
        val stable = File(games, "game-$key").apply { mkdirs() }
        val recent = File(games, "z-renamed-$key").apply { mkdirs() }
        val oldSave = File(stable, "savedata/slot.ksd").apply {
            requireNotNull(parentFile).mkdirs(); writeText("older"); assertTrue(setLastModified(1_600_000_000_000L))
        }
        val newSave = File(recent, "savedata/slot.ksd").apply {
            requireNotNull(parentFile).mkdirs(); writeText("newer"); assertTrue(setLastModified(1_700_000_000_000L))
        }
        assertEquals(recent, KrSafMirror.findMirrorRoot(games, key))
        val staged = File(stable, "savedata.import_backup/slot.ksd").apply {
            requireNotNull(parentFile).mkdirs(); writeText("newest staged"); assertTrue(setLastModified(1_800_000_000_000L))
        }
        assertEquals(stable, KrSafMirror.findMirrorRoot(games, key))
        assertEquals("older", oldSave.readText())
        assertEquals("newer", newSave.readText())
        assertEquals("newest staged", staged.readText())
    }

    @Test
    fun bootstrapMissingResolutionConfirmsAbsenceOnlyFromCompleteListings() {
        // R9-#1: null/FNF responses (AOSP returns null for missing documents) only prove
        // absence with a complete root listing — with or without a previous row.
        assertEquals(KrSafMirror.BootstrapMissingResolution.CONFIRMED_ABSENT,
            KrSafMirror.resolveBootstrapMissing("patch.tjs", true, true, setOf("data.xp3"), true))
        assertEquals(KrSafMirror.BootstrapMissingResolution.CONFIRMED_ABSENT,
            KrSafMirror.resolveBootstrapMissing("patch.tjs", true, true, setOf("data.xp3"), false))
        // Incomplete listing: a previous index row is durable evidence the source existed.
        assertEquals(KrSafMirror.BootstrapMissingResolution.CARRY_PREVIOUS,
            KrSafMirror.resolveBootstrapMissing("patch.tjs", true, false, emptySet(), true))
        assertEquals(KrSafMirror.BootstrapMissingResolution.CARRY_PREVIOUS,
            KrSafMirror.resolveBootstrapMissing("patch.tjs", true, true, setOf("patch.tjs"), true))
        // Same evidence without a previous row: untrusted provider state.
        assertEquals(KrSafMirror.BootstrapMissingResolution.UNAVAILABLE,
            KrSafMirror.resolveBootstrapMissing("patch.tjs", true, false, emptySet(), false))
        assertEquals(KrSafMirror.BootstrapMissingResolution.UNAVAILABLE,
            KrSafMirror.resolveBootstrapMissing("patch.tjs", false, true, emptySet(), true))
    }

    @Test
    fun stickyRootSelectionPersistsReusesAndInvalidatesTheMarker() {
        val games = temporary.newFolder("games")
        val key = "0123456789abcdef"
        val stable = File(games, "game-$key").apply { mkdirs() }
        val legacy = File(games, "Legacy-Title-$key").apply { mkdirs() }
        val selection = File(temporary.newFolder("sel"), "root-$key.txt")
        // First choice is persisted.
        assertEquals(legacy, KrSafMirror.withStickyRootSelection(games, selection, key) { legacy })
        assertEquals(legacy.name, selection.readText().trim())
        // A later newer save in another root must not rebind the key.
        val stableSave = File(stable, "savedata/slot.ksd").apply {
            requireNotNull(parentFile).mkdirs(); writeText("fresh")
        }
        assertEquals(legacy, KrSafMirror.withStickyRootSelection(games, selection, key) {
            throw AssertionError("sticky marker must not re-choose while valid")
        })
        // A path-injected or foreign marker name is rejected and re-chosen.
        selection.writeText("../escape-$key")
        assertEquals(stable, KrSafMirror.withStickyRootSelection(games, selection, key) { stable })
        assertEquals(stable.name, selection.readText().trim())
        // A marker pointing at a vanished directory is invalidated.
        legacy.deleteRecursively()
        assertEquals(stable, KrSafMirror.withStickyRootSelection(games, selection, key) { stable })
    }

    @Test
    fun stickyRootMarkerWriteIsAtomicAndNeverObservedEmpty() {
        val games = temporary.newFolder("games")
        val root = File(games, "game-k").apply { mkdirs() }
        val selection = File(temporary.newFolder("sel"), "root-k.txt")
        KrSafMirror.writeRootSelection(selection, root.name)
        assertTrue(selection.isFile)
        assertEquals(root.name, selection.readText().trim())
        assertFalse(File(selection.parentFile, selection.name + ".tmp").exists())
    }

    @Test
    fun firstStickySelectionLockRunCreatesMissingIndexDirectoryAndReleasesTheMutex() {
        // Audit F1: the index directory is otherwise only created by prepare(); a
        // first-ever save-management open used to throw FileNotFoundException here and
        // leak the held process mutex, so the directory must be established and every
        // failure path must still unlock.
        val indexDirectory = File(temporary.newFolder("first-entry"), "no_backup/krkr_saf_index")
        assertEquals("ok", KrSafMirror.tryStickySelectionLock(indexDirectory, "0123456789abcdef") { "ok" })
        assertTrue(indexDirectory.isDirectory)
        assertEquals("ok2", KrSafMirror.tryStickySelectionLock(indexDirectory, "0123456789abcdef") { "ok2" })
        try {
            KrSafMirror.tryStickySelectionLock(indexDirectory, "0123456789abcdef") {
                throw java.io.IOException("boom")
            }
            fail("action failure must propagate")
        } catch (error: java.io.IOException) {
            assertEquals("boom", error.message)
        }
        // The mutex was released despite the failure: an immediate acquisition succeeds.
        assertEquals("ok3", KrSafMirror.tryStickySelectionLock(indexDirectory, "0123456789abcdef") { "ok3" })
    }

    @Test
    fun stickySelectionLockDegradesToNullWhenTheIndexDirectoryCannotBeCreated() {
        // Audit of df6984c, P3-2: an I/O failure (here: the index path is occupied by
        // a regular file) must return null so the caller takes the tolerant fallback.
        val blocker = temporary.newFile("index-blocker")
        assertNull(KrSafMirror.tryStickySelectionLock(blocker, "0123456789abcdef") { "ok" })
    }

    @Test
    fun stickySelectionLockDegradesWhenTheLockFileCannotBeOpenedAndReleasesTheMutex() {
        // Audit of 2fe2f38 (N3): the RandomAccessFile IOException branch (all shard
        // lock paths are directories) must return null, and the mutex must be released
        // — verified from another thread, where a leaked ReentrantLock would time out.
        val indexDirectory = temporary.newFolder("idx-unopenable")
        for (shard in 0 until 64) {
            File(indexDirectory, "prepare-$shard.lock").mkdirs()
        }
        assertNull(KrSafMirror.tryStickySelectionLock(indexDirectory, "0123456789abcdef") { "ok" })
        // Unblock and re-run on a fresh thread: success proves the mutex was released.
        for (shard in 0 until 64) {
            File(indexDirectory, "prepare-$shard.lock").deleteRecursively()
        }
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val second = java.util.concurrent.atomic.AtomicReference<String?>()
            executor.execute {
                second.set(KrSafMirror.tryStickySelectionLock(indexDirectory, "0123456789abcdef") { "ok" })
            }
            executor.shutdown()
            assertTrue(executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals("ok", second.get())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun stickySelectionLockRestoresTheInterruptFlagAndThrowsCancellation() {
        // Audit of df6984c, P3-2: an already-interrupted thread must receive a
        // CancellationException and keep its interrupt flag set (AGENTS protocol).
        val indexDirectory = temporary.newFolder("interrupted-idx")
        Thread.currentThread().interrupt()
        try {
            try {
                KrSafMirror.tryStickySelectionLock(indexDirectory, "0123456789abcdef") { "ok" }
                fail("An interrupted wait must throw the cancellation type")
            } catch (cancelled: kotlin.coroutines.cancellation.CancellationException) {
                assertTrue(Thread.currentThread().isInterrupted)
            }
        } finally {
            Thread.interrupted()
        }
    }

}
