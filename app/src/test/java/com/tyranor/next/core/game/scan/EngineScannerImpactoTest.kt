package com.tyranor.next.core.game.scan

import com.tyranor.next.core.engine.EngineType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EngineScannerImpactoTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val valid = "{\"version\":1,\"profile\":\"mo6tw\"}".toByteArray()

    @Test fun explicitSupportedDeclarationIsRequired() {
        val root = temporaryFolder.newFolder()
        root.resolve("script.cpk").writeText("CPK")
        root.resolve("bg.mpk").writeText("MPK")
        assertNotEquals(EngineType.IMPACTO, EngineScanner.detectEngine(root).engine)
        root.resolve(ImpactoDescriptor.FILE_NAME).writeBytes(valid)
        val detection = EngineScanner.detectEngine(root)
        assertEquals(EngineType.IMPACTO, detection.engine)
        assertEquals(EngineScanner.LAUNCH_TARGET_GAME_DIR, detection.launchTarget)
    }

    @Test fun malformedNestedAndWrongCaseDeclarationsDoNotIdentifyAnEngine() {
        val root = temporaryFolder.newFolder()
        root.resolve("IMPACTO.JSON").writeBytes(valid)
        root.resolve("data").mkdir()
        root.resolve("data/impacto.json").writeBytes(valid)
        assertNotEquals(EngineType.IMPACTO, EngineScanner.detectEngine(root).engine)
        root.resolve(ImpactoDescriptor.FILE_NAME).writeText("{\"version\":1,\"profile\":\"modelviewer\"}")
        assertNotEquals(EngineType.IMPACTO, EngineScanner.detectEngine(root).engine)
        root.resolve(ImpactoDescriptor.FILE_NAME).writeText(String(valid).padEnd(ImpactoDescriptor.MAX_BYTES + 1))
        assertNotEquals(EngineType.IMPACTO, EngineScanner.detectEngine(root).engine)
    }

    @Test fun existingEnginesRetainPriority() {
        listOf("data.xp3" to EngineType.KIRIKIRI, "0.txt" to EngineType.ONS, "index.html" to EngineType.WEB_OTHER).forEach { (name, expected) ->
            val root = temporaryFolder.newFolder()
            root.resolve(name).writeText("existing engine")
            root.resolve(ImpactoDescriptor.FILE_NAME).writeBytes(valid)
            assertEquals(expected, EngineScanner.detectEngine(root).engine)
        }
    }

    private data class Node(val name: String, val head: ByteArray?, val size: Long? = head?.size?.toLong(), val directory: Boolean = false)
    private fun detect(nodes: List<Node>) = EngineScanner.detectEngine(
        children = nodes,
        nameOf = { it.name },
        isDirectory = { it.directory },
        childrenOf = { emptyList() },
        headOf = { it.head },
        sizeOf = { it.size },
    ).engine

    @Test fun genericSafAdapterHasTheSameBoundedDeclarationPolicy() {
        val descriptor = Node(ImpactoDescriptor.FILE_NAME, valid)
        assertEquals(EngineType.IMPACTO, detect(listOf(descriptor)))
        assertEquals(EngineType.IMPACTO, detect(listOf(descriptor.copy(size = null))))
        assertNotEquals(EngineType.IMPACTO, detect(listOf(descriptor, descriptor)))
        assertNotEquals(EngineType.IMPACTO, detect(listOf(descriptor.copy(head = null))))
        assertNotEquals(EngineType.IMPACTO, detect(listOf(descriptor.copy(size = 1_000_000))))
        assertNotEquals(EngineType.IMPACTO, detect(listOf(descriptor.copy(directory = true))))
    }
}
