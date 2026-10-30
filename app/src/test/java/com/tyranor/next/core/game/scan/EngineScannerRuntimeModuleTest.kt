package com.tyranor.next.core.game.scan

import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.engine.runtime.RuntimeModuleDescriptor
import com.tyranor.next.core.engine.runtime.RuntimeModuleIndex
import com.tyranor.next.core.engine.runtime.RuntimeModuleScanSpec
import com.tyranor.next.core.game.model.ScanGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 扫描器接入第三方运行时模块：只覆盖 UNKNOWN / WEB_OTHER 弱结果，
 * KRKR 等强特征不被抢走；空索引/未命中保持内置识别结果。
 */
class EngineScannerRuntimeModuleTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private fun module(
        packageName: String = PACKAGE_NAME,
        extensions: List<String> = emptyList(),
        markers: List<String> = emptyList(),
        match: RuntimeModuleScanSpec.Match = RuntimeModuleScanSpec.Match.ANY,
        enabled: Boolean = true,
    ) = RuntimeModuleDescriptor(
        packageName = packageName,
        label = "Runtime Test",
        versionName = "1.0",
        versionCode = 1L,
        protocol = 1,
        activityName = "$packageName.RuntimeActivity",
        scan = RuntimeModuleScanSpec(match = match, extensions = extensions, markers = markers),
        enabled = enabled,
    )

    private fun index(vararg modules: RuntimeModuleDescriptor) = RuntimeModuleIndex.of(modules.toList())

    @Test
    fun unknownDirectoryWithDeclaredMarkerBindsRuntimeModule() {
        val dir = temporary.newFolder("runtime-marker")
        dir.resolve("game.dat").writeText("payload")
        assertEquals(EngineType.UNKNOWN, EngineScanner.detectEngine(dir).engine)

        val detection = EngineScanner.detectEngine(dir, index(module(markers = listOf("game.dat"))))

        assertEquals(EngineType.RUNTIME_MODULE, detection.engine)
        assertEquals(PACKAGE_NAME, detection.runtimeModulePackage)
        assertEquals(EngineScanner.LAUNCH_TARGET_GAME_DIR, detection.launchTarget)
    }

    @Test
    fun webOtherResultCanBeOverriddenByModuleMarker() {
        val dir = temporary.newFolder("runtime-web")
        dir.resolve("index.html").writeText("<html></html>")
        dir.resolve("game.dat").writeText("payload")
        assertEquals(EngineType.WEB_OTHER, EngineScanner.detectEngine(dir).engine)

        val detection = EngineScanner.detectEngine(dir, index(module(markers = listOf("game.dat"))))

        assertEquals(EngineType.RUNTIME_MODULE, detection.engine)
        assertEquals(PACKAGE_NAME, detection.runtimeModulePackage)
    }

    @Test
    fun strongBuiltInKirikiriIsNotOverriddenByDeclaredExtension() {
        val dir = temporary.newFolder("built-in-krkr")
        dir.resolve("data.xp3").writeText("xp3")
        assertEquals(EngineType.KIRIKIRI, EngineScanner.detectEngine(dir).engine)

        val detection = EngineScanner.detectEngine(dir, index(module(extensions = listOf(".xp3"))))

        assertEquals(EngineType.KIRIKIRI, detection.engine)
        assertNull(detection.runtimeModulePackage)
    }

    @Test
    fun emptyIndexKeepsBuiltInResult() {
        val unknownDir = temporary.newFolder("plain-dir")
        unknownDir.resolve("readme.txt").writeText("x")
        val webDir = temporary.newFolder("web-dir")
        webDir.resolve("index.html").writeText("<html></html>")

        assertEquals(
            EngineType.UNKNOWN,
            EngineScanner.detectEngine(unknownDir, RuntimeModuleIndex.EMPTY).engine,
        )
        assertEquals(
            EngineType.WEB_OTHER,
            EngineScanner.detectEngine(webDir, RuntimeModuleIndex.EMPTY).engine,
        )
    }

    @Test
    fun unmatchedModuleKeepsBuiltInResult() {
        val unknownDir = temporary.newFolder("plain-unmatched")
        unknownDir.resolve("readme.txt").writeText("x")
        val webDir = temporary.newFolder("web-unmatched")
        webDir.resolve("index.html").writeText("<html></html>")
        val module = module(markers = listOf("game.dat"))

        val unknownDetection = EngineScanner.detectEngine(unknownDir, index(module))
        assertEquals(EngineType.UNKNOWN, unknownDetection.engine)
        assertNull(unknownDetection.runtimeModulePackage)

        val webDetection = EngineScanner.detectEngine(webDir, index(module))
        assertEquals(EngineType.WEB_OTHER, webDetection.engine)
        assertNull(webDetection.runtimeModulePackage)
    }

    @Test
    fun disabledModuleDoesNotOverrideUnknownResult() {
        val dir = temporary.newFolder("runtime-disabled")
        dir.resolve("game.dat").writeText("payload")

        val detection = EngineScanner.detectEngine(
            dir,
            index(module(markers = listOf("game.dat"), enabled = false)),
        )

        assertEquals(EngineType.UNKNOWN, detection.engine)
        assertNull(detection.runtimeModulePackage)
    }

    @Test
    fun hostRomDirectoryIsNotTakenByModuleExtension() {
        val dir = temporary.newFolder("runtime-rom")
        dir.resolve("game.iso").writeText("iso")

        val detection = EngineScanner.detectEngine(dir, index(module(extensions = listOf(".iso"))))

        assertEquals(EngineType.UNKNOWN, detection.engine)
        assertNull(detection.runtimeModulePackage)
    }

    @Test
    fun rescanMergePreservesRuntimeBoundEntriesWhenScanMissesThem() {
        val runtime = ScanGame(
            title = "runtime",
            uri = "content://tree/runtime",
            engine = EngineType.RUNTIME_MODULE,
            launchTarget = "DIR",
            runtimeModulePackage = PACKAGE_NAME,
        )
        val degraded = runtime.copy(engine = EngineType.UNKNOWN)
        val plainUnknown = ScanGame(
            title = "unknown",
            uri = "content://tree/unknown",
            engine = EngineType.UNKNOWN,
            launchTarget = "DIR",
        )

        val merged = EngineScanner.mergeScannedWithManual(
            listOf(runtime, degraded, plainUnknown),
            emptyList(),
        )

        assertEquals(2, merged.size)
        assertTrue(merged.any { it.uri == runtime.uri })
        assertTrue(merged.any { it.uri == degraded.uri })
        assertTrue(merged.none { it.uri == plainUnknown.uri })
    }

    private companion object {
        const val PACKAGE_NAME = "com.tyranor.runtime.example"
    }
}
