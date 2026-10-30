package com.tyranor.next.core.game.save.sync

import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.game.model.ScanGame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class SaveDirectoryIdentityTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun disabledLaunchDoesNotResolveTheSaveDirectory() {
        var resolved = false
        val admission = optionalSaveSyncLaunchAdmission(enabled = { false }) {
            resolved = true
            fail("A disabled opt-in feature must not resolve saves before launching")
            null
        }
        assertSame(SaveSyncLaunchAdmission.Disabled, admission)
        assertFalse(resolved)
    }

    @Test
    fun unreadableOptInSettingsSkipResolutionAndReturnDisabled() {
        var resolved = false
        val admission = optionalSaveSyncLaunchAdmission(enabled = {
            throw SecurityException("prefs unavailable")
        }) {
            resolved = true
            fail("An unreadable optional switch must not resolve saves")
            null
        }
        assertSame(SaveSyncLaunchAdmission.Disabled, admission)
        assertFalse(resolved)
    }

    @Test
    fun nullAndNonCancellationResolutionFailuresReturnUnresolved() {
        assertSame(
            SaveSyncLaunchAdmission.Unresolved,
            optionalSaveSyncLaunchAdmission(enabled = { true }) { null },
        )
        assertSame(
            SaveSyncLaunchAdmission.Unresolved,
            optionalSaveSyncLaunchAdmission(enabled = { true }) {
                throw SecurityException("access revoked")
            },
        )

        val game = game("content://games/launch")
        val brokenDirectory = object : File(temp.root, "unreadable") {
            override fun getCanonicalPath(): String = throw IOException("canonical path failed")
        }
        assertSame(
            SaveSyncLaunchAdmission.Unresolved,
            optionalSaveSyncLaunchAdmission(enabled = { true }) {
                saveDirectoryIdentity(game) { brokenDirectory }
            },
        )
    }

    @Test
    fun nonNullResolutionBecomesDirectoryWithoutPrefixInference() {
        assertEquals(
            SaveSyncLaunchAdmission.Directory("opaque-launch-key"),
            optionalSaveSyncLaunchAdmission(enabled = { true }) { "opaque-launch-key" },
        )
        assertEquals(
            SaveSyncLaunchAdmission.Directory("$SAVE_DIRECTORY_KEY_PREFIX/saves/demo"),
            optionalSaveSyncLaunchAdmission(enabled = { true }) { "$SAVE_DIRECTORY_KEY_PREFIX/saves/demo" },
        )
    }

    @Test
    fun launchAdmissionBranchesRemainDistinctTypedStates() {
        val admissions = listOf<SaveSyncLaunchAdmission>(
            SaveSyncLaunchAdmission.Disabled,
            SaveSyncLaunchAdmission.Unmanaged,
            SaveSyncLaunchAdmission.Unresolved,
            SaveSyncLaunchAdmission.Directory("opaque-launch-key"),
        )

        val branches = admissions.map { admission ->
            when (admission) {
                SaveSyncLaunchAdmission.Disabled -> "disabled"
                SaveSyncLaunchAdmission.Unmanaged -> "unmanaged"
                SaveSyncLaunchAdmission.Unresolved -> "unresolved"
                is SaveSyncLaunchAdmission.Directory -> "directory:${admission.key}"
            }
        }

        assertEquals(
            listOf("disabled", "unmanaged", "unresolved", "directory:opaque-launch-key"),
            branches,
        )
    }

    @Test
    fun optionalLaunchAdmissionPreservesCancellationFromSwitchAndResolver() {
        val switchCancellation = CancellationException("cancel switch")
        try {
            optionalSaveSyncLaunchAdmission(enabled = { throw switchCancellation }) {
                fail("Cancelled settings must not resolve saves")
                null
            }
            fail("Switch cancellation was swallowed")
        } catch (caught: CancellationException) {
            assertSame(switchCancellation, caught)
        }

        val resolverCancellation = CancellationException("cancel resolver")
        try {
            optionalSaveSyncLaunchAdmission(enabled = { true }) { throw resolverCancellation }
            fail("Resolver cancellation was swallowed")
        } catch (caught: CancellationException) {
            assertSame(resolverCancellation, caught)
        }
    }

    @Test
    fun overlappingSafRootsShareWriterAdmissionButKeepSeparateHistoryKeys() = runBlocking {
        val root = temp.newFolder("Demo")
        val parentRoot = game("content://storage/tree/primary%3AGames/document/primary%3AGames%2FDemo")
        val childRoot = game("content://storage/tree/primary%3AGames%2FDemo/document/primary%3AGames%2FDemo")
        val unrelated = game("content://storage/tree/other")
        fun directory(game: ScanGame) = when (game.uri) {
            parentRoot.uri -> root
            childRoot.uri -> File(root, "../Demo")
            else -> File(temp.root, "other")
        }
        val related = relatedSaveUris(childRoot, listOf(parentRoot, childRoot, unrelated), ::directory)
        assertEquals(setOf(parentRoot.uri, childRoot.uri), related)
        val firstKey = saveDirectoryIdentity(parentRoot, ::directory)
        val secondKey = saveDirectoryIdentity(childRoot, ::directory)
        assertEquals(firstKey, secondKey)
        assertFalse(SaveContentHash.text(parentRoot.uri) == SaveContentHash.text(childRoot.uri))

        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = async {
            SaveSyncLocks.active(firstKey) { entered.complete(Unit); release.await() }
        }
        entered.await()
        val secondEntered = CompletableDeferred<Unit>()
        val second = async {
            SaveSyncLocks.active(secondKey) { secondEntered.complete(Unit) }
        }
        kotlinx.coroutines.yield()
        assertFalse(secondEntered.isCompleted)
        release.complete(Unit)
        withTimeout(5_000) { first.await(); second.await() }
        Unit
    }

    @Test
    fun nestedGamesShareRunningEvidenceAndCannotLaunchDuringParentReplacement() = runBlocking {
        val root = temp.newFolder("nested-games")
        val parent = game("parent-game")
        val child = game("nested-game")
        val disjoint = game("other-game")
        fun directory(game: ScanGame) = when (game.uri) {
            parent.uri -> root
            child.uri -> File(root, "B/savedata")
            else -> File(root.parentFile, root.name + "-other")
        }
        assertEquals(setOf(parent.uri, child.uri), relatedSaveUris(parent, listOf(parent, child, disjoint), ::directory))
        assertEquals(setOf(parent.uri, child.uri), relatedSaveUris(child, listOf(parent, child, disjoint), ::directory))
        val parentKey = saveDirectoryIdentity(parent, ::directory)
        val childKey = saveDirectoryIdentity(child, ::directory)
        val disjointKey = saveDirectoryIdentity(disjoint, ::directory)
        val childEntered = CompletableDeferred<Unit>()
        val otherEntered = CompletableDeferred<Unit>()
        lateinit var childLaunch: kotlinx.coroutines.Deferred<Unit>
        SaveSyncLocks.active(parentKey) {
            childLaunch = async(start = CoroutineStart.UNDISPATCHED) {
                withSaveSyncLaunchAdmission(
                    resolve = { SaveSyncLaunchAdmission.Directory(childKey) },
                    managed = { childEntered.complete(Unit); Unit },
                    unmanaged = { fail("Nested game bypassed directory admission") },
                )
            }
            withTimeout(5_000) {
                withSaveSyncLaunchAdmission(
                    resolve = { SaveSyncLaunchAdmission.Directory(disjointKey) },
                    managed = { otherEntered.complete(Unit); Unit },
                    unmanaged = { fail("Resolved game bypassed directory admission") },
                )
            }
            assertFalse(childEntered.isCompleted)
            assertTrue(otherEntered.isCompleted)
        }
        withTimeout(5_000) { childLaunch.await() }
        assertTrue(childEntered.isCompleted)
    }

    private fun game(uri: String) = ScanGame("Demo", uri, EngineType.ONS, "__game_dir__")
}
