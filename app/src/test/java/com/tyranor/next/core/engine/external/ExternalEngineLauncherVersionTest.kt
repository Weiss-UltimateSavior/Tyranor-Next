package com.tyranor.next.core.engine.external

import android.content.pm.ActivityInfo
import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.game.model.ScanGame
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ExternalEngineLauncherVersionTest {
    @Test
    fun samePackageVersionsAreNotInterchangeable() {
        assertNull(ExternalEngineLauncher.versionCodeError(10100L, RenPyExternalEngineModule))
        assertEquals(
            ExternalEngineErrorCode.PACKAGE_VERSION_MISMATCH,
            ExternalEngineLauncher.versionCodeError(10200L, RenPyExternalEngineModule),
        )
        assertEquals(
            ExternalEngineErrorCode.PACKAGE_OUTDATED,
            ExternalEngineLauncher.versionCodeError(10100L, RenPy853ExternalEngineModule),
        )
        assertNull(ExternalEngineLauncher.versionCodeError(10200L, RenPy853ExternalEngineModule))
    }

    @Test
    fun oldModuleVersionRangeIncludesBothBoundaries() {
        assertEquals(
            ExternalEngineErrorCode.PACKAGE_OUTDATED,
            ExternalEngineLauncher.versionCodeError(10099L, RenPyExternalEngineModule),
        )
        assertNull(ExternalEngineLauncher.versionCodeError(10199L, RenPyExternalEngineModule))
        assertNull(ExternalEngineLauncher.versionCodeError(0L, RenPy77ExternalEngineModule))
        assertNull(ExternalEngineLauncher.versionCodeError(Long.MAX_VALUE, RenPy77ExternalEngineModule))
    }

    @Test
    fun acceptsOnlyInstalledVersionsAtOrAboveModuleMinimum() {
        assertFalse(ExternalEngineLauncher.isVersionCodeSupported(10_199L, 10_200L))
        assertTrue(ExternalEngineLauncher.isVersionCodeSupported(10_200L, 10_200L))
        assertTrue(ExternalEngineLauncher.isVersionCodeSupported(10_201L, 10_200L))
    }

    @Test
    fun zeroMinimumPreservesAnyInstalledVersionCompatibility() {
        assertTrue(ExternalEngineLauncher.isVersionCodeSupported(1L, 0L))
        assertTrue(ExternalEngineLauncher.isVersionCodeSupported(0L, 0L))
    }

    @Test
    fun anOlderHostIsOutdatedOnlyForTheTargetsThatNeedANewerOne() {
        val windows = "mo6tw-pc-zhs"
        val ps3 = "mo6tw"

        assertEquals(
            ExternalEngineErrorCode.PACKAGE_OUTDATED,
            ExternalEngineLauncher.versionCodeError(3L, ImpactoExternalEngineModule, windows),
        )
        assertNull(ExternalEngineLauncher.versionCodeError(4L, ImpactoExternalEngineModule, windows))
        assertNull(ExternalEngineLauncher.versionCodeError(5L, ImpactoExternalEngineModule, windows))
        // The same installed host still launches the profiles that existed before the patch series.
        assertNull(ExternalEngineLauncher.versionCodeError(3L, ImpactoExternalEngineModule, ps3))
        // Without a target only the module-wide minimum applies (engine page install state).
        assertNull(ExternalEngineLauncher.versionCodeError(3L, ImpactoExternalEngineModule))
        assertEquals(
            ExternalEngineErrorCode.PACKAGE_OUTDATED,
            ExternalEngineLauncher.versionCodeError(2L, ImpactoExternalEngineModule),
        )
    }

    @Test
    fun modulesWithoutPerTargetRulesIgnoreTheTargetWhenCheckingTheirMinimumVersion() {
        assertEquals(10100L, RenPyExternalEngineModule.minimumVersionCodeFor("DIR"))
        assertEquals(10200L, RenPy853ExternalEngineModule.minimumVersionCodeFor("DIR"))
        // The previous results do not change when a target is passed.
        assertNull(ExternalEngineLauncher.versionCodeError(10100L, RenPyExternalEngineModule, "DIR"))
        assertEquals(
            ExternalEngineErrorCode.PACKAGE_OUTDATED,
            ExternalEngineLauncher.versionCodeError(10100L, RenPy853ExternalEngineModule, "DIR"),
        )
    }

    @Test
    fun prepareRequestResolvesNormalizedOrientationAfterPrepareAndChecksCancellation() {
        val events = mutableListOf<String>()
        val initialRequest = ExternalEngineLaunchRequest(
            game = ScanGame(
                title = "RPG Maker Test",
                uri = "file:///storage/emulated/0/Games/Rpg",
                engine = EngineType.RPGMAKER,
                launchTarget = "[游戏目录]",
            ),
            gameDirectoryPath = "/storage/emulated/0/Games/Rpg",
            orientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
        )

        val prepared = ExternalEngineLauncher.prepareRequest(
            prepareStep = {
                events += "prepare"
                null
            },
            request = initialRequest,
            orientationResolver = {
                events += "resolve"
                ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
            },
            cancellationCheck = {
                events += "check"
            },
        )

        val ready = prepared as ExternalEngineLauncher.PreparedLaunchRequest.Ready
        assertEquals(listOf("prepare", "check", "resolve", "check"), events)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE, ready.request.orientation)
    }

    @Test
    fun prepareRequestNormalizesUnexpectedOrientationAndSkipsWhenPrepareFails() {
        val baseRequest = ExternalEngineLaunchRequest(
            game = ScanGame(
                title = "Ruffle Test",
                uri = "file:///storage/emulated/0/Games/Ruffle",
                engine = EngineType.RUFFLE,
                launchTarget = "game.swf",
            ),
            gameDirectoryPath = "/storage/emulated/0/Games/Ruffle",
        )

        val normalized = ExternalEngineLauncher.prepareRequest(
            prepareStep = { null },
            request = baseRequest,
            orientationResolver = { ActivityInfo.SCREEN_ORIENTATION_PORTRAIT },
        )
        val ready = normalized as ExternalEngineLauncher.PreparedLaunchRequest.Ready
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, ready.request.orientation)

        var resolvedAfterFailure = false
        val expectedFailure = ExternalEngineLaunchResult.failure(ExternalEngineErrorCode.INVALID_GAME_PATH)
        val failed = ExternalEngineLauncher.prepareRequest(
            prepareStep = { expectedFailure },
            request = baseRequest,
            orientationResolver = {
                resolvedAfterFailure = true
                ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
            },
        )
        val failedResult = failed as ExternalEngineLauncher.PreparedLaunchRequest.Failed
        assertEquals(expectedFailure, failedResult.failure)
        assertFalse(resolvedAfterFailure)
    }

    @Test
    fun prepareRequestPropagatesCancellationBeforeAndAfterOrientationResolve() {
        val baseRequest = ExternalEngineLaunchRequest(
            game = ScanGame(
                title = "RenPy Test",
                uri = "file:///storage/emulated/0/Games/RenPy",
                engine = EngineType.RENPY,
                launchTarget = "[游戏目录]",
            ),
            gameDirectoryPath = "/storage/emulated/0/Games/RenPy",
        )

        val expectedCancel = CancellationException("cancelled after resolve")
        var checkCount = 0
        try {
            ExternalEngineLauncher.prepareRequest(
                prepareStep = { null },
                request = baseRequest,
                orientationResolver = { ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE },
                cancellationCheck = {
                    checkCount += 1
                    if (checkCount == 2) throw expectedCancel
                },
            )
            fail("Expected cancellation exception after orientation resolve")
        } catch (caught: CancellationException) {
            assertSame(expectedCancel, caught)
            assertEquals(2, checkCount)
        }
    }

    @Test
    fun prepareRequestPropagatesFirstCheckCancellationWithoutResolvingOrientation() {
        val baseRequest = ExternalEngineLaunchRequest(
            game = ScanGame(
                title = "RenPy Test",
                uri = "file:///storage/emulated/0/Games/RenPy",
                engine = EngineType.RENPY,
                launchTarget = "[游戏目录]",
            ),
            gameDirectoryPath = "/storage/emulated/0/Games/RenPy",
        )

        val expectedCancel = CancellationException("cancelled before resolve")
        var orientationResolved = false
        try {
            ExternalEngineLauncher.prepareRequest(
                prepareStep = { null },
                request = baseRequest,
                orientationResolver = {
                    orientationResolved = true
                    ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
                },
                cancellationCheck = { throw expectedCancel },
            )
            fail("Expected cancellation exception before orientation resolve")
        } catch (caught: CancellationException) {
            assertSame(expectedCancel, caught)
            assertFalse(orientationResolved)
        }
    }

    @Test
    fun prepareRequestFallsBackToRequestOrientationWhenResolverThrows() {
        // P3-12（ROB-08）：解析器抛非取消异常时回落请求原方向并上报，启动仍以原方向进行，
        // 不落入未类型化的异常路径。
        val baseRequest = ExternalEngineLaunchRequest(
            game = ScanGame(
                title = "RenPy Test",
                uri = "file:///storage/emulated/0/Games/RenPy",
                engine = EngineType.RENPY,
                launchTarget = "[游戏目录]",
            ),
            gameDirectoryPath = "/storage/emulated/0/Games/RenPy",
            orientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
        )
        val resolverFailure = IllegalStateException("orientation resolver exploded")
        var reported: Throwable? = null
        val events = mutableListOf<String>()

        val prepared = ExternalEngineLauncher.prepareRequest(
            prepareStep = {
                events += "prepare"
                null
            },
            request = baseRequest,
            orientationResolver = { throw resolverFailure },
            cancellationCheck = {
                events += "check"
            },
            onOrientationResolveFailed = { reported = it },
        )

        val ready = prepared as ExternalEngineLauncher.PreparedLaunchRequest.Ready
        assertEquals(listOf("prepare", "check", "check"), events)
        assertSame(resolverFailure, reported)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, ready.request.orientation)
    }

    @Test
    fun prepareRequestPropagatesCancellationThrownInsideOrientationResolver() {
        // 取消不是失败：解析器内抛出的取消异常必须原样传播，且不执行第二次取消检查。
        val baseRequest = ExternalEngineLaunchRequest(
            game = ScanGame(
                title = "RenPy Test",
                uri = "file:///storage/emulated/0/Games/RenPy",
                engine = EngineType.RENPY,
                launchTarget = "[游戏目录]",
            ),
            gameDirectoryPath = "/storage/emulated/0/Games/RenPy",
        )

        val expectedCancel = CancellationException("cancelled inside resolver")
        var reported: Throwable? = null
        var checkCount = 0
        try {
            ExternalEngineLauncher.prepareRequest(
                prepareStep = { null },
                request = baseRequest,
                orientationResolver = { throw expectedCancel },
                cancellationCheck = { checkCount += 1 },
                onOrientationResolveFailed = { reported = it },
            )
            fail("Expected cancellation exception thrown inside orientation resolver")
        } catch (caught: CancellationException) {
            assertSame(expectedCancel, caught)
            assertNull(reported)
            assertEquals(1, checkCount)
        }
    }
}
