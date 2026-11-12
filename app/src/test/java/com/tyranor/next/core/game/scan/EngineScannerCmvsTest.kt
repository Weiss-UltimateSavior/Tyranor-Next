package com.tyranor.next.core.game.scan

import com.tyranor.next.core.engine.EngineType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** CMVS detection must descend into data/pack without widening the global directory whitelist. */
class EngineScannerCmvsTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun game(name: String): java.io.File {
        val root = temporaryFolder.newFolder(name)
        val pack = root.resolve("data/pack")
        pack.mkdirs()
        pack.resolve("script.cpz").writeBytes(byteArrayOf(1, 2, 3))
        return root
    }

    @Test
    fun detectsStrongCmvsFromPs3Header() {
        val root = game("Realive")
        root.resolve("data/pack/start.ps3").writeBytes(byteArrayOf(0x50, 0x53, 0x32, 0x41, 0, 0, 0))

        val detection = EngineScanner.detectEngine(root)

        assertEquals(EngineType.CMVS, detection.engine)
        assertEquals(96, detection.confidence)
        assertEquals(EngineScanner.LAUNCH_TARGET_GAME_DIR, detection.launchTarget)
    }

    @Test
    fun detectsMediumCmvsWithoutReadingHeader() {
        val root = game("Realive PS2")
        root.resolve("data/pack/start.ps2").writeText("not-a-header")

        val detection = EngineScanner.detectEngine(root)

        assertEquals(EngineType.CMVS, detection.engine)
        assertEquals(92, detection.confidence)
    }

    @Test
    fun detectsWeakCmvsFromRootExecutable() {
        val root = temporaryFolder.newFolder("CMVS exe only")
        root.resolve("cmvs64.exe").writeText("fake")

        val detection = EngineScanner.detectEngine(root)

        assertEquals(EngineType.CMVS, detection.engine)
        assertEquals(82, detection.confidence)
    }

    @Test
    fun doesNotDetectCmvsWithoutUniqueStart() {
        val root = game("CMVS broken")
        root.resolve("data/pack/start.ps3").writeBytes(byteArrayOf(0x50, 0x53, 0x32, 0x41))
        root.resolve("data/pack/start.ps2").writeBytes(byteArrayOf(0x50, 0x53, 0x32, 0x41))

        assertNotEquals(EngineType.CMVS, EngineScanner.detectEngine(root).engine)
    }
}
