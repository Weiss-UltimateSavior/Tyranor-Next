package com.tyranor.next.core.game.scan

import com.tyranor.next.core.engine.EngineType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class EngineScannerShiinaRioTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun detectsRootWarcAndRioIniWithoutAnInstalledHost() {
        val root = temporaryFolder.newFolder()
        root.resolve("rIo.InI").writeText("v2.49")
        root.resolve("Story.WAR").writeBytes(warcHeader("WARC 1.7"))
        root.resolve("index.html").writeText("Manual")

        val detection = EngineScanner.detectEngine(root)

        assertEquals(EngineType.SHIINARIO, detection.engine)
        assertEquals(EngineScanner.LAUNCH_TARGET_GAME_DIR, detection.launchTarget)
        assertEquals("internal.shiinario", detection.externalModuleAlias)
    }

    @Test
    fun ignoresWebArchivesAndIncompleteOrNestedGameLayouts() {
        val root = temporaryFolder.newFolder()
        root.resolve("RIO.INI").writeText("v2.49")
        val archive = root.resolve("ordinary.war").apply { writeBytes(warcHeader("PKZIP00!")) }
        assertEquals(EngineType.UNKNOWN, EngineScanner.detectEngine(root).engine)

        archive.writeBytes(warcHeader("WARC 1.9"))
        assertEquals(EngineType.UNKNOWN, EngineScanner.detectEngine(root).engine)

        archive.writeText("WARC 1.7")
        assertEquals(EngineType.UNKNOWN, EngineScanner.detectEngine(root).engine)

        archive.delete()
        root.resolve("data").mkdir()
        root.resolve("data/story.war").writeBytes(warcHeader("WARC 1.7"))
        assertEquals(EngineType.UNKNOWN, EngineScanner.detectEngine(root).engine)

        root.resolve("RIO.INI").delete()
        root.resolve("story.war").writeBytes(warcHeader("WARC 1.7"))
        assertEquals(EngineType.UNKNOWN, EngineScanner.detectEngine(root).engine)
    }

    @Test
    fun catalogPrefersMappedExecutableAndFallsBackToDirectoryForConflictingSchemes() {
        val root = temporaryFolder.newFolder()
        root.resolve("RIO.INI").writeText("v2.49")
        root.resolve("story.war").writeBytes(warcHeader("WARC 1.7"))
        root.resolve("Story.EXE").createNewFile()
        root.resolve("UNINS000.exe").createNewFile()
        val catalog = ShiinaRioGameCatalog.parse(
            """
            {
              "schemaVersion": 1, "engine": "openshiina",
              "schemes": [{"name":"Story"}, {"name":"Other"}],
              "gameMap": {"story.exe":"Story", "story.war":"Story", "other.exe":"Other"}
            }
            """.trimIndent(),
        )

        assertEquals("Story.EXE", detectWithCatalog(root, catalog).launchTarget)
        root.resolve("Story.EXE").delete()
        assertEquals("story.war", detectWithCatalog(root, catalog).launchTarget)
        root.resolve("other.exe").createNewFile()
        assertEquals(EngineScanner.LAUNCH_TARGET_GAME_DIR, detectWithCatalog(root, catalog).launchTarget)
    }

    @Test
    fun ignoresUnrecognizedCatalogSchemaAndGenericMappings() {
        assertNull(ShiinaRioGameCatalog.parse("{broken").launchTargetFor(listOf("game.exe")))
        val generic = ShiinaRioGameCatalog.parse(
            """
            {
              "schemaVersion": 1, "engine": "openshiina",
              "schemes": [{"name":"ShiinaRio v2.36-2.38"}],
              "gameMap": {"game.exe":"ShiinaRio v2.36-2.38"}
            }
            """.trimIndent(),
        )
        assertNull(generic.launchTargetFor(listOf("game.exe")))
    }

    private fun warcHeader(magic: String): ByteArray =
        magic.toByteArray(Charsets.US_ASCII).copyOf(16)

    private fun detectWithCatalog(root: File, catalog: ShiinaRioGameCatalog): EngineScanner.Detection =
        EngineScanner.detectEngine(
            children = root.listFiles().orEmpty().asIterable(),
            nameOf = File::getName,
            isDirectory = File::isDirectory,
            childrenOf = { it.listFiles().orEmpty().asIterable() },
            headOf = File::readBytes,
            shiinaRioCatalog = catalog,
        )
}
