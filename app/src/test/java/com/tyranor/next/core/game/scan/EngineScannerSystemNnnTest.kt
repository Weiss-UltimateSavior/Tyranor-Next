package com.tyranor.next.core.game.scan

import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.engine.runtime.RuntimeModuleBindings
import com.tyranor.next.core.game.model.ScanGame
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
        val thirdParty = old.copy(runtimeModulePackage = "com.tyranor.runtime.other")
        assertSame(thirdParty, RuntimeModuleBindings.builtInSystemNnn(thirdParty))
    }
}
