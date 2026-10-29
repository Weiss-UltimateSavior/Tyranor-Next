package bridge

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NativeBridgeMirrorPolicyTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `mirror source reads exclude explicit writes while update mode requires copy up`() {
        assertTrue(NativeBridge.mirrorReadAllowed(NativeBridge.FileAccess.READ_ONLY, false, false))
        assertTrue(NativeBridge.mirrorReadAllowed(NativeBridge.FileAccess.READ_WRITE, false, false))
        assertFalse(NativeBridge.mirrorReadAllowed(NativeBridge.FileAccess.WRITE_ONLY, false, false))
        for (access in NativeBridge.FileAccess.entries) {
            assertFalse(NativeBridge.mirrorReadAllowed(access, true, false))
            assertFalse(NativeBridge.mirrorReadAllowed(access, false, true))
            assertFalse(NativeBridge.mirrorReadAllowed(access, true, true))
        }
    }

    @Test
    fun `provider mapping preserves contents unless truncation is explicit`() {
        assertEquals("r", NativeBridge.pfdModeFor(NativeBridge.FileAccess.READ_ONLY, false, false))
        assertEquals("rw", NativeBridge.pfdModeFor(NativeBridge.FileAccess.WRITE_ONLY, false, false))
        assertEquals("rw", NativeBridge.pfdModeFor(NativeBridge.FileAccess.READ_WRITE, false, false))
        assertEquals("wt", NativeBridge.pfdModeFor(NativeBridge.FileAccess.WRITE_ONLY, false, true))
        assertEquals("rwt", NativeBridge.pfdModeFor(NativeBridge.FileAccess.READ_WRITE, false, true))
        assertEquals("wa", NativeBridge.pfdModeFor(NativeBridge.FileAccess.WRITE_ONLY, true, false))
        assertEquals("rw", NativeBridge.pfdModeFor(NativeBridge.FileAccess.READ_WRITE, true, false))
    }

    @Test
    fun `metadata agrees with available bytes and preserves unknown v1 sizes`() {
        assertEquals(123L, NativeBridge.mirrorMetadataSize(123L, 0L, false))
        assertEquals(0L, NativeBridge.mirrorMetadataSize(0L, 0L, false))
        assertEquals(-1L, NativeBridge.mirrorMetadataSize(-1L, 0L, false))
        assertEquals(-1L, NativeBridge.mirrorMetadataSize(-2L, 0L, false))
        assertEquals(-1L, NativeBridge.mirrorMetadataSize(123L, 7L, false))
        assertEquals(-1L, NativeBridge.mirrorMetadataSize(0L, 7L, false))
        assertEquals(-1L, NativeBridge.mirrorMetadataSize(123L, 0L, true))
        assertEquals(-1L, NativeBridge.mirrorMetadataSize(0L, 0L, true))
    }

    @Test
    fun `mirror asset case folding matches ROOT locale including Unicode`() {
        assertEquals("plugin/gamepad.dll", NativeBridge.normalizeMirrorCase("Plugin/GamePad.dll"))
        assertEquals("äset/σcript.dll", NativeBridge.normalizeMirrorCase("Äset/Σcript.DLL"))
    }

    @Test
    fun `missing mirror root loads an empty session instead of a fail-closed router`() {
        // R9-#16: a caller omitting the mirror root extra must not arm a session whose
        // router fails closed for every key — that would turn all opens (savedata too)
        // into EIO. The guard loads nothing, so opens fall through to normal routing.
        // (A positive routing case needs android.net.Uri and stays device-verified.)
        NativeBridge.configureSafMirror(null, null)
        assertEquals(-1L, NativeBridge.metadataSize("/data/user/0/x/games/game-k/data.xp3"))
        assertFalse(NativeBridge.mirrorSessionDegradedForTest)
    }

    @Test
    fun `corrupt or zero-row index marks the session degraded for fail-closed opens`() {
        // R10 #2: a present but unreadable/zero-row index must not silently serve the
        // 0-byte placeholders as empty content; the session is flagged and the open
        // gate fails existing unowned mirror-root files while savedata stays local.
        val mirrorRoot = temporary.newFolder("game-corrupt")
        val indexFile = File(temporary.newFolder("idx"), "corrupt.idx")
        indexFile.writeText("not-an-index-header\n")
        NativeBridge.configureSafMirror(indexFile.path, mirrorRoot.path)
        assertTrue(NativeBridge.mirrorSessionDegradedForTest)
        val session = NativeBridge.mirrorSessionForTest
        File(mirrorRoot, "data.xp3").createNewFile()
        assertTrue(NativeBridge.mirrorDegradedBlock(session, File(mirrorRoot, "data.xp3").path.lowercase(), File(mirrorRoot, "data.xp3")))
        assertFalse(NativeBridge.mirrorDegradedBlock(session, File(mirrorRoot, "savedata/slot.ksd").path.lowercase(), File(mirrorRoot, "savedata/slot.ksd")))
        assertFalse(NativeBridge.mirrorDegradedBlock(session, "/storage/emulated/0/elsewhere/data.xp3", File("/storage/emulated/0/elsewhere/data.xp3")))
    }

    @Test
    fun `header-only index keeps live local overrides working across the degraded gate`() {
        // Audit of df6984c, P3-1: a fresh rebuild whose rows are all local overrides
        // publishes a header-only index; the degraded gate must not block those live
        // owned files on the next launch (marker + file present → not blocked).
        val mirrorRoot = temporary.newFolder("game-overrides")
        val indexFile = File(temporary.newFolder("idx"), "header-only.idx")
        indexFile.writeText("krkr-saf-index-v2\n")
        val overrideTarget = File(mirrorRoot, "data.xp3").apply { createNewFile() }
        KrMirrorLocalFiles(mirrorRoot).markOverridden(overrideTarget)
        NativeBridge.configureSafMirror(indexFile.path, mirrorRoot.path)
        assertTrue(NativeBridge.mirrorSessionDegradedForTest)
        val session = NativeBridge.mirrorSessionForTest
        assertFalse(NativeBridge.mirrorDegradedBlock(session, overrideTarget.path.lowercase(), overrideTarget))
        // A placeholder without ownership stays blocked.
        val plain = File(mirrorRoot, "plugin.dll").apply { createNewFile() }
        assertTrue(NativeBridge.mirrorDegradedBlock(session, plain.path.lowercase(), plain))
    }

    @Test
    fun `successful all-override preparation allows missing probes and new local files`() {
        val root = temporary.newFolder("prepared-all-local")
        val resource = File(root, "data.xp3").apply { writeText("local resource") }
        KrMirrorLocalFiles(root).markOverridden(resource)
        val key = resource.absolutePath.lowercase(java.util.Locale.ROOT)
        val entries = mutableListOf(Triple(key, "content://source/data", 14L))
        val sources = KrSafMirror.assemblePreparationSources(root, emptySet(), entries,
            observed = 1, queryFailures = 0, rootListingComplete = true)
        assertEquals(KrSafMirror.MirrorSourceDecision.REBUILD_FRESH, sources.decision)
        entries.removeAll { it.first in sources.localOverrides }
        assertTrue(entries.isEmpty())
        val index = File(temporary.newFolder("prepared-idx"), "all-local.idx").apply {
            writeText(buildString { KrSafMirror.writeIndexText(this, entries) })
        }
        NativeBridge.configureSafMirror(index.path, root.path)
        val session = NativeBridge.mirrorSessionForTest
        assertTrue(session.degraded)
        val missing = File(root, "new-run.log")
        val missingKey = missing.absolutePath.lowercase(java.util.Locale.ROOT)
        assertFalse(session.shouldRoute(missingKey))
        assertFalse(NativeBridge.mirrorDegradedBlock(session, missingKey, missing))
        // No gate rejection: a non-creating local probe remains a real miss.
        try {
            java.io.FileInputStream(missing).close()
            org.junit.Assert.fail("Missing probe must not create a file")
        } catch (_: java.io.FileNotFoundException) {
            assertFalse(missing.exists())
        }
        // Exercise the production router's unindexed creating-open path. It must
        // publish ownership even when the caller never writes a byte to the new file.
        val createdSession = KrMirrorReadSession<KrSafMirror.MirrorEntry>(
            emptyMap(), KrMirrorLocalFiles(root), degraded = true)
        var localOpened = false
        val router = KrMirrorOpenRouter(
            session = createdSession,
            sourceSize = { it.size },
            sourceStream = { error("No source exists for a new file") },
            sourceDescriptor = { error("No source exists for a new file") },
            localDescriptor = { file, _ ->
                java.io.RandomAccessFile(file, "rw").close()
                localOpened = true
                17
            },
            closeDescriptor = { error("Successful owned open must not close its descriptor") },
            diagnostic = { _, error -> if (error != null) throw error },
        )
        assertEquals(17, router.open(missingKey, missing, KrMirrorOpenRouter.Intent.WRITE, allowCreate = true))
        assertTrue(localOpened)
        assertEquals(0L, missing.length())
        assertTrue(createdSession.shouldRoute(missingKey))
        assertFalse(NativeBridge.mirrorDegradedBlock(createdSession, missingKey, missing))
        NativeBridge.configureSafMirror(index.path, root.path)
        assertFalse(NativeBridge.mirrorDegradedBlock(NativeBridge.mirrorSessionForTest, missingKey, missing))
        assertTrue(NativeBridge.mirrorSessionForTest.shouldRoute(missingKey))
        assertEquals("local resource", resource.readText())
        assertFalse(NativeBridge.mirrorDegradedBlock(session, key, resource))
    }

    @Test
    fun `missing unindexed noncreating update defers before Java can create a file`() {
        val root = temporary.newFolder("missing-update")
        val index = File(temporary.newFolder("missing-update-index"), "index").apply {
            writeText(buildString { KrSafMirror.writeIndexText(this, emptyList()) })
        }
        NativeBridge.configureSafMirror(index.path, root.path)
        val session = NativeBridge.mirrorSessionForTest
        val missing = File(root, "optional.cfg")
        val key = missing.absolutePath.lowercase(java.util.Locale.ROOT)
        assertFalse(NativeBridge.mirrorDegradedBlock(session, key, missing))
        assertFalse(session.shouldRoute(key))
        var directOpens = 0
        // This is the same final direct-open dispatch used by NativeBridge.open(r+).
        // If reached, the actual Java update opener would create the absent file.
        val updateOpen = {
            directOpens++
            java.io.RandomAccessFile(missing, "rw").use { 17 }
        }
        assertEquals(KrMirrorOpenRouter.NOT_ROUTED,
            NativeBridge.openUnindexedLocal(session, key, allowCreate = false, directOpen = updateOpen))
        assertEquals(0, directOpens)
        assertFalse(missing.exists())
        // Even a repeat probe cannot turn the failed update into an empty unowned file.
        assertEquals(KrMirrorOpenRouter.NOT_ROUTED,
            NativeBridge.openUnindexedLocal(session, key, allowCreate = false, directOpen = updateOpen))
        assertEquals(0, directOpens)
        assertFalse(missing.exists())
        // Ordinary sessions also defer non-creating updates for missing unindexed resources.
        val ordinaryState = KrMirrorLocalFiles(root)
        val ordinary = KrMirrorReadSession<String>(emptyMap(), ordinaryState)
        assertEquals(KrMirrorOpenRouter.NOT_ROUTED,
            NativeBridge.openUnindexedLocal(ordinary, key, allowCreate = false, directOpen = updateOpen))
        assertEquals(0, directOpens)
        assertFalse(missing.exists())
        assertFalse(ordinaryState.isOverridden(missing))

        // Subsequent creating open on optional.cfg succeeds and establishes ownership.
        assertEquals(17,
            NativeBridge.openUnindexedLocal(ordinary, key, allowCreate = true, directOpen = updateOpen))
        assertEquals(1, directOpens)
        assertTrue(missing.exists())
        assertTrue(ordinaryState.isOverridden(missing))
        assertTrue(ordinary.shouldRoute(key))

        // A path outside the mirror root or savedata with allowCreate = false still invokes directOpen.
        val saveFile = File(root, "savedata/slot.ksd")
        val saveKey = saveFile.absolutePath.lowercase(java.util.Locale.ROOT)
        var saveOpens = 0
        val saveOpen = {
            saveOpens++
            23
        }
        assertEquals(23,
            NativeBridge.openUnindexedLocal(ordinary, saveKey, allowCreate = false, directOpen = saveOpen))
        assertEquals(1, saveOpens)

        val outsideFile = File(temporary.newFolder("outside"), "other.txt")
        val outsideKey = outsideFile.absolutePath.lowercase(java.util.Locale.ROOT)
        var outsideOpens = 0
        val outsideOpen = {
            outsideOpens++
            29
        }
        assertEquals(29,
            NativeBridge.openUnindexedLocal(ordinary, outsideKey, allowCreate = false, directOpen = outsideOpen))
        assertEquals(1, outsideOpens)
    }

    @Test
    fun `damaged index permits absent paths but never exposes an existing placeholder`() {
        val root = temporary.newFolder("damaged-gate")
        for ((number, contents) in listOf("", "broken-header\n").withIndex()) {
            val index = File(temporary.newFolder("damaged-index-$number"), "index").apply { writeText(contents) }
            val placeholder = File(root, "asset-$number.xp3").apply { createNewFile() }
            NativeBridge.configureSafMirror(index.path, root.path)
            val session = NativeBridge.mirrorSessionForTest
            assertTrue(session.degraded)
            assertTrue(NativeBridge.mirrorDegradedBlock(session, placeholder.absolutePath.lowercase(), placeholder))
            val guardedSession = KrMirrorReadSession<String>(emptyMap(), KrMirrorLocalFiles(root), degraded = true)
            val router = KrMirrorOpenRouter(guardedSession,
                sourceSize = { 0L },
                sourceStream = { error("No source may be opened") },
                sourceDescriptor = { error("No source may be opened") },
                localDescriptor = { _, _ -> error("Existing placeholder must not be adopted") },
                closeDescriptor = { error("No descriptor was opened") },
                diagnostic = { _, error -> if (error != null) throw error })
            assertEquals(KrMirrorOpenRouter.FAILED, router.open(placeholder.absolutePath.lowercase(),
                placeholder, KrMirrorOpenRouter.Intent.WRITE, allowCreate = true))
            assertFalse(KrMirrorLocalFiles(root).isOverridden(placeholder))
            val missing = File(root, "optional-$number.log")
            assertFalse(NativeBridge.mirrorDegradedBlock(session, missing.absolutePath.lowercase(), missing))
            assertFalse(missing.exists())
        }
    }

    @Test
    fun `all-override index rows filter to an empty but not degraded session`() {
        // Audit of 84e1d22, M4: a full index whose every row is a live local override
        // loads as documents=∅ with degraded=false (rawCount>0) — routing for the
        // override keys survives through the armed localOverrides, not the documents.
        val mirrorRoot = temporary.newFolder("game-all-override")
        val overrideTarget = File(mirrorRoot, "data.xp3").apply { createNewFile() }
        KrMirrorLocalFiles(mirrorRoot).markOverridden(overrideTarget)
        val indexFile = File(temporary.newFolder("idx"), "all-override.idx")
        indexFile.writeText(buildString {
            KrSafMirror.writeIndexText(
                this,
                listOf(Triple(overrideTarget.absolutePath.lowercase(), "content://stub/document/x", 5L)),
            )
        })
        NativeBridge.configureSafMirror(indexFile.path, mirrorRoot.path)
        assertFalse(NativeBridge.mirrorSessionDegradedForTest)
        val session = NativeBridge.mirrorSessionForTest
        assertTrue(session.shouldRoute(overrideTarget.absolutePath.lowercase()))
        assertFalse(NativeBridge.mirrorDegradedBlock(session, overrideTarget.absolutePath.lowercase(), overrideTarget))
    }

    @Test
    fun `normal session unindexed creation persists ownership across all-override header-only reload`() {
        assertNormalCreationSurvivesHeaderOnlyReload("engine-created local log")
    }

    @Test
    fun `normal session empty unindexed creation persists ownership across all-override header-only reload`() {
        assertNormalCreationSurvivesHeaderOnlyReload("")
    }

    private fun assertNormalCreationSurvivesHeaderOnlyReload(initialContents: String) {
        val root = temporary.newFolder()
        val sourceFile = File(root, "startup.tjs").apply { createNewFile() }
        val sourceKey = sourceFile.absolutePath.lowercase(java.util.Locale.ROOT)
        val state = KrMirrorLocalFiles(root)
        val normal = KrMirrorReadSession(
            documents = mapOf(sourceKey to "source script"),
            localFiles = state,
            initialOverrides = emptySet(),
            degraded = false,
        )
        val created = File(root, "new-run.log")
        val createdKey = created.absolutePath.lowercase(java.util.Locale.ROOT)
        assertFalse(normal.shouldRoute(createdKey))
        val fd = NativeBridge.openUnindexedLocal(normal, createdKey, allowCreate = true) {
            java.io.RandomAccessFile(created, "rw").use { raf ->
                raf.write(initialContents.toByteArray(Charsets.UTF_8))
            }
            17
        }
        assertEquals(17, fd)
        assertEquals(initialContents.length.toLong(), created.length())
        assertTrue(state.isOverridden(created))
        assertTrue(normal.shouldRoute(createdKey))
        assertTrue(normal.isLocal(createdKey, created))

        val sourceBytes = "source script".toByteArray(Charsets.UTF_8)
        state.copyUp(sourceFile, sourceBytes.size.toLong()) { sourceBytes.inputStream() }
        val previous = mapOf(
            sourceKey to KrSafMirror.MirrorEntryHolder("content://source/document/startup", sourceBytes.size.toLong()),
        )
        val entries = mutableListOf(
            Triple(sourceKey, "content://source/document/startup", sourceBytes.size.toLong()),
        )
        val known = hashSetOf(sourceKey)
        val sources = KrSafMirror.assemblePreparationSources(
            root, previous.keys, entries,
            observed = 1, queryFailures = 0, rootListingComplete = true,
        )
        assertEquals(KrSafMirror.MirrorSourceDecision.REBUILD_FRESH, sources.decision)
        entries.removeAll { it.first in sources.localOverrides }
        known.removeAll(sources.localOverrides)
        KrSafMirror.reconcilePreparedIndex(
            root, previous, entries, known, sources.localOverrides, sources.decision,
        )
        val index = File(temporary.newFolder(), "next.idx").apply {
            writeText(buildString { KrSafMirror.writeIndexText(this, entries) })
        }
        NativeBridge.configureSafMirror(index.path, root.path)
        val next = NativeBridge.mirrorSessionForTest
        assertTrue(next.degraded)
        assertTrue(state.isOverridden(sourceFile))
        assertFalse(NativeBridge.mirrorDegradedBlock(next, createdKey, created))
        assertTrue(next.shouldRoute(createdKey))

        val reopenedSession = KrMirrorReadSession<String>(
            documents = emptyMap(),
            localFiles = state,
            initialOverrides = state.snapshotOverrideKeys().mapTo(HashSet()) {
                File(root, it).absolutePath.lowercase(java.util.Locale.ROOT)
            },
            degraded = true,
        )
        val router = KrMirrorOpenRouter(
            session = reopenedSession,
            sourceSize = { 0L },
            sourceStream = { error("No source stream for local file") },
            sourceDescriptor = { error("No source fd for local file") },
            localDescriptor = { local, intent ->
                if (intent == KrMirrorOpenRouter.Intent.WRITE) {
                    java.io.RandomAccessFile(local, "rw").use { raf ->
                        raf.seek(raf.length())
                        raf.write("+append".toByteArray(Charsets.UTF_8))
                    }
                }
                23
            },
            closeDescriptor = { error("Successful local open must not close its descriptor") },
            diagnostic = { _, error -> if (error != null) throw error },
        )
        assertEquals(23, router.open(createdKey, created, KrMirrorOpenRouter.Intent.READ_ONLY))
        assertEquals(initialContents, created.readText())
        assertEquals(23, router.open(createdKey, created, KrMirrorOpenRouter.Intent.WRITE, allowCreate = true))
        assertEquals("$initialContents+append", created.readText())
    }
}
