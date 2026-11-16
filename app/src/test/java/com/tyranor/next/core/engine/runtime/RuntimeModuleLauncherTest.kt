package com.tyranor.next.core.engine.runtime

import com.tyranor.next.core.engine.runtime.RuntimeModuleLaunchDispatch.SafSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.coroutines.cancellation.CancellationException

class RuntimeModuleLauncherTest {
    private val path = "/storage/emulated/0/Games/Majiro"
    private val saf = "content://provider/tree/games%2Fmajiro"
    private val exactTree = source(saf)
    private val parentTree = SafSource(
        treeUri = "content://provider/tree/games",
        authority = "provider",
        treeDocumentId = "games",
        rootDocumentId = "games/majiro",
    )

    @Test
    fun readableSafIsPreferredEvenWhenPathIsAvailable() {
        val calls = mutableListOf<Pair<String?, String?>>()
        var checkedSource: String? = null
        val result = launch(
            canRead = {
                checkedSource = it
                true
            },
            dispatch = { folder, source ->
                calls += folder to source
                RuntimeModuleLaunchResult.Dispatched
            },
        )

        assertSame(RuntimeModuleLaunchResult.Dispatched, result)
        assertEquals(saf, checkedSource)
        assertEquals(listOf(path to saf), calls)
    }

    @Test
    fun readableSafCanLaunchWithoutAnyLocalPath() {
        val calls = mutableListOf<Pair<String?, String?>>()
        val result = launch(folderPath = null, dispatch = record(calls))

        assertSame(RuntimeModuleLaunchResult.Dispatched, result)
        assertEquals(listOf(null to saf), calls)
    }

    @Test
    fun parsedButRevokedSafDoesNotBlockLocalPath() {
        val calls = mutableListOf<Pair<String?, String?>>()
        val result = launch(canRead = { false }, dispatch = record(calls))

        assertSame(RuntimeModuleLaunchResult.Dispatched, result)
        assertEquals(listOf(path to null), calls)
    }

    @Test
    fun pureSafWithRevokedPermissionIsDeniedWithoutDispatch() {
        val calls = mutableListOf<Pair<String?, String?>>()
        val result = launch(folderPath = null, canRead = { false }, dispatch = record(calls))

        assertSame(RuntimeModuleLaunchResult.SecurityDenied, result)
        assertEquals(emptyList<Pair<String?, String?>>(), calls)
    }

    @Test
    fun permissionSecurityExceptionFallsBackToPath() {
        val calls = mutableListOf<Pair<String?, String?>>()
        val result = launch(canRead = { throw SecurityException("revoked") }, dispatch = record(calls))

        assertSame(RuntimeModuleLaunchResult.Dispatched, result)
        assertEquals(listOf(path to null), calls)
    }

    @Test
    fun pureSafPermissionSecurityExceptionIsDenied() {
        val calls = mutableListOf<Pair<String?, String?>>()
        val result = launch(
            folderPath = null,
            canRead = { throw SecurityException("revoked") },
            dispatch = record(calls),
        )

        assertSame(RuntimeModuleLaunchResult.SecurityDenied, result)
        assertEquals(emptyList<Pair<String?, String?>>(), calls)
    }

    @Test
    fun localPathDoesNotRequireSafPermission() {
        val calls = mutableListOf<Pair<String?, String?>>()
        val result = launch(
            safSource = null,
            canRead = { error("Path-only launches must not query SAF") },
            dispatch = record(calls),
        )

        assertSame(RuntimeModuleLaunchResult.Dispatched, result)
        assertEquals(listOf(path to null), calls)
    }

    @Test
    fun noSourceIsUnresolvedWithoutDispatch() {
        val calls = mutableListOf<Pair<String?, String?>>()
        val result = launch(folderPath = null, safSource = null, dispatch = record(calls))

        assertSame(RuntimeModuleLaunchResult.PathUnresolved, result)
        assertEquals(emptyList<Pair<String?, String?>>(), calls)
    }

    @Test
    fun blankPathCannotBypassDeniedSaf() {
        val calls = mutableListOf<Pair<String?, String?>>()
        val result = launch(folderPath = " \t", canRead = { false }, dispatch = record(calls))

        assertSame(RuntimeModuleLaunchResult.SecurityDenied, result)
        assertEquals(emptyList<Pair<String?, String?>>(), calls)
    }

    @Test
    fun blankPathIsOmittedFromReadableSafLaunch() {
        val calls = mutableListOf<Pair<String?, String?>>()
        val result = launch(folderPath = " \t", dispatch = record(calls))

        assertSame(RuntimeModuleLaunchResult.Dispatched, result)
        assertEquals(listOf(null to saf), calls)
    }

    @Test
    fun revokedBetweenCheckAndDispatchRetriesOnceWithOnlyPath() {
        val calls = mutableListOf<Pair<String?, String?>>()
        val result = launch(dispatch = { folder, source ->
            calls += folder to source
            if (source != null) RuntimeModuleLaunchResult.SecurityDenied else RuntimeModuleLaunchResult.Dispatched
        })

        assertSame(RuntimeModuleLaunchResult.Dispatched, result)
        assertEquals(listOf(path to saf, path to null), calls)
    }

    @Test
    fun pureSafSecurityDenialCannotRetry() {
        val calls = mutableListOf<Pair<String?, String?>>()
        val result = launch(
            folderPath = null,
            dispatch = record(calls, RuntimeModuleLaunchResult.SecurityDenied),
        )

        assertSame(RuntimeModuleLaunchResult.SecurityDenied, result)
        assertEquals(listOf(null to saf), calls)
    }

    @Test
    fun pathSecurityDenialCannotRetry() {
        val calls = mutableListOf<Pair<String?, String?>>()
        val result = launch(
            safSource = null,
            dispatch = record(calls, RuntimeModuleLaunchResult.SecurityDenied),
        )

        assertSame(RuntimeModuleLaunchResult.SecurityDenied, result)
        assertEquals(listOf(path to null), calls)
    }

    @Test
    fun failedPathFallbackStopsAfterSecondDispatch() {
        val calls = mutableListOf<Pair<String?, String?>>()
        val result = launch(dispatch = record(calls, RuntimeModuleLaunchResult.SecurityDenied))

        assertSame(RuntimeModuleLaunchResult.SecurityDenied, result)
        assertEquals(listOf(path to saf, path to null), calls)
    }

    @Test
    fun fallbackPreservesFinalFailure() {
        val calls = mutableListOf<Pair<String?, String?>>()
        val failure = RuntimeModuleLaunchResult.StartFailed("path launch failed")
        val result = launch(dispatch = { folder, source ->
            calls += folder to source
            if (source != null) RuntimeModuleLaunchResult.SecurityDenied else failure
        })

        assertSame(failure, result)
        assertEquals(listOf(path to saf, path to null), calls)
    }

    @Test
    fun missingEntryDoesNotRetryWithPath() {
        val calls = mutableListOf<Pair<String?, String?>>()
        val result = launch(dispatch = record(calls, RuntimeModuleLaunchResult.EntryMissing))

        assertSame(RuntimeModuleLaunchResult.EntryMissing, result)
        assertEquals(listOf(path to saf), calls)
    }

    @Test
    fun otherStartFailureDoesNotRetryWithPath() {
        val calls = mutableListOf<Pair<String?, String?>>()
        val failure = RuntimeModuleLaunchResult.StartFailed("system error")
        val result = launch(dispatch = record(calls, failure))

        assertSame(failure, result)
        assertEquals(listOf(path to saf), calls)
    }

    @Test
    fun unexpectedPermissionFailureDoesNotLaunchOrRetry() {
        val calls = mutableListOf<Pair<String?, String?>>()
        val result = launch(
            canRead = { throw IllegalStateException("permission service failed") },
            dispatch = record(calls),
        )

        assertEquals(RuntimeModuleLaunchResult.StartFailed("permission service failed"), result)
        assertEquals(emptyList<Pair<String?, String?>>(), calls)
    }

    @Test
    fun permissionCancellationPropagatesUnchangedWithoutDispatch() {
        val calls = mutableListOf<Pair<String?, String?>>()
        val cancellation = CancellationException("cancel permission check")
        val thrown = assertThrows(CancellationException::class.java) {
            launch(canRead = { throw cancellation }, dispatch = record(calls))
        }

        assertSame(cancellation, thrown)
        assertEquals(emptyList<Pair<String?, String?>>(), calls)
    }

    @Test
    fun dispatchCancellationPropagatesUnchangedWithoutRetry() {
        val calls = mutableListOf<Pair<String?, String?>>()
        val cancellation = CancellationException("cancel launch")
        val thrown = assertThrows(CancellationException::class.java) {
            launch(dispatch = { folder, source ->
                calls += folder to source
                throw cancellation
            })
        }

        assertSame(cancellation, thrown)
        assertEquals(listOf(path to saf), calls)
    }

    @Test
    fun fallbackCancellationPropagatesUnchangedWithoutFurtherRetry() {
        val calls = mutableListOf<Pair<String?, String?>>()
        val cancellation = CancellationException("cancel fallback")
        val thrown = assertThrows(CancellationException::class.java) {
            launch(dispatch = { folder, source ->
                calls += folder to source
                if (source != null) RuntimeModuleLaunchResult.SecurityDenied else throw cancellation
            })
        }

        assertSame(cancellation, thrown)
        assertEquals(listOf(path to saf, path to null), calls)
    }

    @Test
    fun gameTreeItselfIsAllowedWithoutLookingUpOtherGrants() {
        val calls = mutableListOf<Pair<String?, String?>>()
        val result = launchScoped(
            gameSource = exactTree,
            folderPath = null,
            persistedSources = { error("An exact game tree must not require another persisted grant") },
            dispatch = record(calls),
        )

        assertSame(RuntimeModuleLaunchResult.Dispatched, result)
        assertEquals(listOf(null to saf), calls)
    }

    @Test
    fun parentTreeWithoutExactGrantUsesOnlyLocalPath() {
        val calls = mutableListOf<Pair<String?, String?>>()
        val result = launchScoped(dispatch = record(calls))

        assertSame(RuntimeModuleLaunchResult.Dispatched, result)
        assertEquals(listOf(path to null), calls)
    }

    @Test
    fun pureSafParentTreeWithoutExactGrantIsDenied() {
        val calls = mutableListOf<Pair<String?, String?>>()
        val result = launchScoped(folderPath = null, dispatch = record(calls))

        assertSame(RuntimeModuleLaunchResult.SecurityDenied, result)
        assertEquals(emptyList<Pair<String?, String?>>(), calls)
    }

    @Test
    fun sameAuthoritySiblingGrantCannotAuthorizeCurrentGame() {
        val calls = mutableListOf<Pair<String?, String?>>()
        val sibling = exactTree.copy(
            treeUri = "content://provider/tree/games%2Fsibling",
            treeDocumentId = "games/sibling",
            rootDocumentId = "games/sibling",
        )
        val similarName = exactTree.copy(
            treeUri = "content://provider/tree/games%2Fmajiro2",
            treeDocumentId = "games/majiro2",
            rootDocumentId = "games/majiro2",
        )
        val result = launchScoped(
            folderPath = null,
            persistedSources = { sequenceOf(parentTree, sibling, similarName) },
            dispatch = record(calls),
        )

        assertSame(RuntimeModuleLaunchResult.SecurityDenied, result)
        assertEquals(emptyList<Pair<String?, String?>>(), calls)
    }

    @Test
    fun sameDocumentIdAtAnotherAuthorityCannotAuthorizeCurrentGame() {
        val calls = mutableListOf<Pair<String?, String?>>()
        val otherProvider = exactTree.copy(
            treeUri = "content://other-provider/tree/games%2Fmajiro",
            authority = "other-provider",
        )
        val result = launchScoped(
            folderPath = null,
            persistedSources = { sequenceOf(otherProvider) },
            dispatch = record(calls),
        )

        assertSame(RuntimeModuleLaunchResult.SecurityDenied, result)
        assertEquals(emptyList<Pair<String?, String?>>(), calls)
    }

    @Test
    fun realExactPersistedGrantIsPreferredToLocalPath() {
        val calls = mutableListOf<Pair<String?, String?>>()
        val checked = mutableListOf<String>()
        val realTree = exactTree.copy(treeUri = "$saf?existing-grant")
        val result = launchScoped(
            persistedSources = { sequenceOf(parentTree, realTree) },
            canRead = {
                checked += it
                true
            },
            dispatch = record(calls),
        )

        assertSame(RuntimeModuleLaunchResult.Dispatched, result)
        assertEquals(listOf(realTree.treeUri), checked)
        assertEquals(listOf(path to realTree.treeUri), calls)
    }

    @Test
    fun pureSafParentRecordUsesExistingExactGameGrant() {
        val calls = mutableListOf<Pair<String?, String?>>()
        val result = launchScoped(
            folderPath = null,
            persistedSources = { sequenceOf(exactTree) },
            dispatch = record(calls),
        )

        assertSame(RuntimeModuleLaunchResult.Dispatched, result)
        assertEquals(listOf(null to saf), calls)
    }

    @Test
    fun exactGameGrantOnlyCanBeSelectedAmongSameAuthoritySiblings() {
        val game = exactTree.copy(
            treeUri = "content://provider/tree/game",
            treeDocumentId = "game",
            rootDocumentId = "game",
        )
        val sibling = game.copy(
            treeUri = "content://provider/tree/sibling",
            treeDocumentId = "sibling",
            rootDocumentId = "sibling",
        )
        val grants = sequenceOf(game, sibling)
        val calls = mutableListOf<Pair<String?, String?>>()
        val result = launchScoped(
            gameSource = parentTree.copy(rootDocumentId = "sibling"),
            folderPath = null,
            persistedSources = { grants },
            dispatch = record(calls),
        )

        assertSame(RuntimeModuleLaunchResult.Dispatched, result)
        assertEquals(listOf(null to sibling.treeUri), calls)
    }

    @Test
    fun revokedExactGrantCannotFallBackToParentGrant() {
        val calls = mutableListOf<Pair<String?, String?>>()
        val result = launchScoped(
            persistedSources = { sequenceOf(exactTree, parentTree) },
            canRead = { false },
            dispatch = record(calls),
        )

        assertSame(RuntimeModuleLaunchResult.Dispatched, result)
        assertEquals(listOf(path to null), calls)
    }

    @Test
    fun pureSafRevokedExactGrantIsDeniedEvenWhenParentIsReadable() {
        val calls = mutableListOf<Pair<String?, String?>>()
        val result = launchScoped(
            folderPath = null,
            persistedSources = { sequenceOf(exactTree, parentTree) },
            canRead = { it == parentTree.treeUri },
            dispatch = record(calls),
        )

        assertSame(RuntimeModuleLaunchResult.SecurityDenied, result)
        assertEquals(emptyList<Pair<String?, String?>>(), calls)
    }

    @Test
    fun childDocumentCandidateCannotReplaceAnExactTreeGrant() {
        val calls = mutableListOf<Pair<String?, String?>>()
        val result = launchScoped(
            folderPath = null,
            persistedSources = { sequenceOf(exactTree.copy(rootDocumentId = "games/majiro/child")) },
            dispatch = record(calls),
        )

        assertSame(RuntimeModuleLaunchResult.SecurityDenied, result)
        assertEquals(emptyList<Pair<String?, String?>>(), calls)
    }

    @Test
    fun unreadableExactCandidateDoesNotHideAnotherReadableExactGrant() {
        val calls = mutableListOf<Pair<String?, String?>>()
        val revoked = exactTree.copy(treeUri = "$saf?revoked")
        val result = launchScoped(
            persistedSources = { sequenceOf(revoked, exactTree) },
            canRead = { it == saf },
            dispatch = record(calls),
        )

        assertSame(RuntimeModuleLaunchResult.Dispatched, result)
        assertEquals(listOf(path to saf), calls)
    }

    @Test
    fun persistedGrantLookupCancellationPropagatesWithoutDispatch() {
        val calls = mutableListOf<Pair<String?, String?>>()
        val cancellation = CancellationException("cancel persisted grant lookup")
        val thrown = assertThrows(CancellationException::class.java) {
            launchScoped(persistedSources = { throw cancellation }, dispatch = record(calls))
        }

        assertSame(cancellation, thrown)
        assertEquals(emptyList<Pair<String?, String?>>(), calls)
    }

    private fun launch(
        folderPath: String? = path,
        safSource: String? = saf,
        canRead: (String) -> Boolean = { true },
        dispatch: (String?, String?) -> RuntimeModuleLaunchResult,
    ): RuntimeModuleLaunchResult = RuntimeModuleLaunchDispatch.launch(
        folderPath = folderPath,
        safSource = safSource?.let(::source),
        canReadSafSource = { canRead(it.treeUri) },
        dispatch = { folder, tree -> dispatch(folder, tree?.treeUri) },
    )

    private fun launchScoped(
        gameSource: SafSource<String> = parentTree,
        folderPath: String? = path,
        persistedSources: () -> Sequence<SafSource<String>> = { emptySequence() },
        canRead: (String) -> Boolean = { true },
        dispatch: (String?, String?) -> RuntimeModuleLaunchResult,
    ): RuntimeModuleLaunchResult = RuntimeModuleLaunchDispatch.launch(
        folderPath = folderPath,
        safSource = gameSource,
        persistedSafSources = persistedSources,
        canReadSafSource = { canRead(it.treeUri) },
        dispatch = { folder, tree -> dispatch(folder, tree?.treeUri) },
    )

    private fun source(uri: String): SafSource<String> = SafSource(
        treeUri = uri,
        authority = "provider",
        treeDocumentId = "games/majiro",
        rootDocumentId = "games/majiro",
    )

    private fun record(
        calls: MutableList<Pair<String?, String?>>,
        result: RuntimeModuleLaunchResult = RuntimeModuleLaunchResult.Dispatched,
    ): (String?, String?) -> RuntimeModuleLaunchResult = { folder, source ->
        calls += folder to source
        result
    }
}
