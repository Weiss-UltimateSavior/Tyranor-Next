package com.tyranor.next.core.game.scan

import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.engine.runtime.RuntimeModuleBindings
import com.tyranor.next.core.engine.runtime.RuntimeModuleDescriptor
import com.tyranor.next.core.engine.runtime.RuntimeModuleSnapshot
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.game.storage.GameEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EngineScannerSystemNnnTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun matchesOnlyARootSptDirectoryWithAScriptFile() {
        val root = temp.newFolder()
        root.resolve("nested/spt").apply { check(mkdirs()) }.resolve("sys.spt").writeText("nested")
        // data is searched recursively; the nested spt must still fail the root-only check.
        root.resolve("data/spt").apply { check(mkdirs()) }.resolve("sys.spt").writeText("nested")
        val spt = root.resolve("SpT").apply { check(mkdir()) }
        val marker = spt.resolve("SYS.SPT").apply { check(mkdir()) }
        assertEquals(EngineType.UNKNOWN, EngineScanner.detectEngine(root).engine)
        check(marker.delete())
        marker.writeText("script")
        root.resolve("index.html").writeText("<html></html>")

        val detection = EngineScanner.detectEngine(root)
        assertEquals(EngineType.SYSTEMNNN, detection.engine)
        assertEquals(EngineScanner.LAUNCH_TARGET_GAME_DIR, detection.launchTarget)
        root.resolve("data.xp3").writeText("xp3")
        assertEquals(EngineType.KIRIKIRI, EngineScanner.detectEngine(root).engine)
    }

    @Test fun migratesOnlyTheOldEmberfallBindingAndKeepsMetadata() {
        val old = ScanGame(
            title = "Renamed game",
            uri = "/storage/emulated/0/game",
            engine = EngineType.UNKNOWN,
            launchTarget = EngineScanner.LAUNCH_TARGET_GAME_DIR,
            coverUri = "file:///cover.png",
            openTime = 123,
            runtimeModulePackage = "com.tyranor.runtime.systemnnn",
        )
        val converted = RuntimeModuleBindings.builtInSystemNnn(old)
        assertEquals(old.copy(engine = EngineType.SYSTEMNNN, runtimeModulePackage = null), converted)
        assertNull(RuntimeModuleBindings.builtInSystemNnn(old.copy(engine = EngineType.RUNTIME_MODULE)).runtimeModulePackage)
        assertEquals(converted, RuntimeModuleBindings.builtInSystemNnn(old.copy(engine = EngineType.SYSTEMNNN)))
        val thirdParty = old.copy(runtimeModulePackage = "com.tyranor.runtime.other")
        assertSame(thirdParty, RuntimeModuleBindings.builtInSystemNnn(thirdParty))
    }

    @Test fun rescanDiscoveryFailureKeepsTheBoundModuleInTheStoredRow() {
        val root = temp.newFolder()
        root.resolve("spt").apply { check(mkdir()) }.resolve("sys.spt").writeText("script")
        val detection = EngineScanner.detectEngine(root)
        assertEquals(EngineType.SYSTEMNNN, detection.engine)
        val detected = ScanGame("Game", root.path, detection.engine, detection.launchTarget)
        val bound = detected.copy(
            engine = EngineType.RUNTIME_MODULE,
            runtimeModulePackage = MODULE,
            coverUri = "file:///cover.png",
            openTime = 123,
        )
        val failedQuery = RuntimeModuleSnapshot(emptyList(), lastError = "PackageManager query failed")

        assertEquals(bound, storedRescanResult(bound, detected, failedQuery, setOf(MODULE)))
        assertEquals(bound.copy(engine = EngineType.SYSTEMNNN), storedRescanResult(bound, detected, failedQuery))
    }

    @Test fun failedReconciliationPreservesThirdPartyBindingsAndStillMigratesEmberfall() {
        val bound = ScanGame("Game", "/storage/emulated/0/game", EngineType.RUNTIME_MODULE,
            EngineScanner.LAUNCH_TARGET_GAME_DIR, runtimeModulePackage = MODULE)
        val oldEmberfall = bound.copy(runtimeModulePackage = "com.tyranor.runtime.systemnnn")

        assertSame(bound, RuntimeModuleBindings.reconcileBinding(bound, null, emptySet(), restoreReenabled = true))
        assertEquals(bound.copy(engine = EngineType.UNKNOWN),
            RuntimeModuleBindings.reconcileBinding(bound, setOf(MODULE), emptySet(), restoreReenabled = false))
        assertEquals(oldEmberfall.copy(engine = EngineType.SYSTEMNNN, runtimeModulePackage = null),
            RuntimeModuleBindings.reconcileBinding(oldEmberfall, null, emptySet(), restoreReenabled = false))
    }

    @Test fun rescanKeepsTheOriginalPackageThroughBuiltInFallbackAndRestoresItOnReenable() {
        val detected = ScanGame("Game", "/storage/emulated/0/game", EngineType.SYSTEMNNN,
            EngineScanner.LAUNCH_TARGET_GAME_DIR)
        val bound = detected.copy(engine = EngineType.RUNTIME_MODULE, runtimeModulePackage = MODULE,
            coverUri = "file:///cover.png", openTime = 123)
        val expectedFallback = bound.copy(engine = EngineType.SYSTEMNNN)
        val unavailable = RuntimeModuleSnapshot(emptyList())
        assertEquals(expectedFallback, storedRescanResult(bound, detected, unavailable))

        val disabled = RuntimeModuleSnapshot(listOf(module(MODULE, enabled = false)))
        // Disabling the module first downgrades existing bindings to UNKNOWN.
        val fallback = storedRescanResult(bound.copy(engine = EngineType.UNKNOWN), detected, disabled)
        assertEquals(expectedFallback, fallback)
        val unrelated = RuntimeModuleSnapshot(listOf(module("com.tyranor.runtime.unrelated", enabled = true)))
        assertEquals(fallback, storedRescanResult(fallback, detected, unrelated))
        assertEquals(listOf(fallback), EngineScanner.mergeRescanResults(listOf(fallback), emptyList(), disabled, emptySet()))

        val enabled = RuntimeModuleSnapshot(listOf(module(MODULE, enabled = true)))
        assertEquals(bound, storedRescanResult(fallback, detected, enabled))
        assertEquals(detected, storedRescanResult(detected, detected, enabled))
        val otherEngine = detected.copy(engine = EngineType.KIRIKIRI, launchTarget = "data.xp3")
        assertEquals(EngineType.KIRIKIRI, storedRescanResult(fallback, otherEngine, enabled).engine)
    }

    @Test fun rescanUsesTheCurrentToggleWhenTheRegistryAndScanStillShowTheModule() {
        val detected = ScanGame("Game", "/storage/emulated/0/game", EngineType.SYSTEMNNN,
            EngineScanner.LAUNCH_TARGET_GAME_DIR)
        val fallback = detected.copy(runtimeModulePackage = MODULE)
        val oldRegistry = RuntimeModuleSnapshot(listOf(module(MODULE, enabled = true)))
        assertEquals(fallback, storedRescanResult(fallback, detected, oldRegistry, emptySet()))

        val staleDetection = fallback.copy(engine = EngineType.RUNTIME_MODULE)
        val freshEntries = EngineScanner.mergeRescanResults(emptyList(), listOf(staleDetection), oldRegistry, emptySet())
        assertEquals(listOf(staleDetection.copy(engine = EngineType.UNKNOWN)), freshEntries)
    }

    private fun storedRescanResult(
        saved: ScanGame,
        detected: ScanGame,
        modules: RuntimeModuleSnapshot,
        enabled: Set<String> = modules.modules.filter { it.enabled }.mapTo(HashSet()) { it.packageName },
    ): ScanGame {
        val merged = EngineScanner.mergeRescanResults(listOf(saved), listOf(detected), modules, enabled).single()
        return GameEntity.from(merged, discoveredAt = 1, updatedAt = 2).toModel()
    }

    private fun module(packageName: String, enabled: Boolean) = RuntimeModuleDescriptor(
        packageName = packageName,
        label = packageName,
        versionName = null,
        versionCode = 0L,
        protocol = 1,
        activityName = "$packageName.MainActivity",
        scan = null,
        enabled = enabled,
    )

    private companion object {
        const val MODULE = "com.tyranor.runtime.other"
    }
}
