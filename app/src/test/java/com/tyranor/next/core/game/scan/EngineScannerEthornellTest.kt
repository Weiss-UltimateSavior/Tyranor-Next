package com.tyranor.next.core.game.scan

import com.tyranor.next.core.engine.EngineType
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream

class EngineScannerEthornellTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun archive(arc20: Boolean = false, bootstrap: String = "ipl._bp"): ByteArray {
        val entrySize = if (arc20) 128 else 32
        val nameSize = if (arc20) 96 else 16
        return ByteArray(16 + entrySize + 4).apply {
            (if (arc20) "BURIKO ARC20" else "PackFile    ").toByteArray().copyInto(this)
            this[12] = 1
            bootstrap.toByteArray().copyInto(this, 16)
            this[16 + nameSize + 4] = 4
        }
    }

    @Test fun bothArchiveFormatsAndCaseInsensitiveRootAreRecognized() {
        for (arc20 in listOf(false, true)) {
            val root = temporary.newFolder()
            root.resolve("System.ARC").writeBytes(archive(arc20, "IPL._BP"))
            val result = EngineScanner.detectEngine(root)
            assertEquals(EngineType.ETHORNELL, result.engine)
            assertEquals(96, result.confidence)
            assertEquals(EngineScanner.LAUNCH_TARGET_GAME_DIR, result.launchTarget)
        }
    }

    @Test fun strongBootstrapBeatsGenericMesButDoesNotStealRealUk2OrSystem35() {
        val root = temporary.newFolder()
        root.resolve("system.arc").writeBytes(archive())
        root.resolve("text.mes").writeText("not UK2")
        assertEquals(EngineType.ETHORNELL, EngineScanner.detectEngine(root).engine)
        val uk2 = temporary.newFolder()
        uk2.resolve("uk2.cfg").writeText("")
        uk2.resolve("text.mes").writeText("")
        uk2.resolve("system.arc").writeText("unknown ARC")
        assertEquals(EngineType.UK2, EngineScanner.detectEngine(uk2).engine)
        uk2.resolve("system.arc").writeBytes(archive())
        assertEquals(EngineType.UK2, EngineScanner.detectEngine(uk2).engine)
        val xs35 = temporary.newFolder()
        xs35.resolve("SA.ALD").writeText("")
        xs35.resolve("System35.bgi").writeText("")
        xs35.resolve("system.arc").writeBytes(archive(bootstrap = "image"))
        assertEquals(EngineType.XSYS35, EngineScanner.detectEngine(xs35).engine)
    }

    @Test fun extensionLooseScriptNestedAndWrongEntryAreNotSufficient() {
        val root = temporary.newFolder()
        root.resolve("ipl._bp").writeText("loose")
        root.resolve("system.arc").writeBytes(archive(bootstrap = "notipl._bp"))
        assertNotEquals(EngineType.ETHORNELL, EngineScanner.detectEngine(root).engine)
        root.resolve("system.arc").delete()
        root.resolve("data").mkdir()
        root.resolve("data/system.arc").writeBytes(archive())
        assertNotEquals(EngineType.ETHORNELL, EngineScanner.detectEngine(root).engine)
        root.resolve("data/system.arc").delete()
        root.resolve("system.arc").writeText("ipl._bp PackFile")
        assertNotEquals(EngineType.ETHORNELL, EngineScanner.detectEngine(root).engine)
    }

    @Test fun malformedTruncatedAndOversizedIndicesFailClosed() {
        assertFalse(EthornellArchiveProbe.hasBootstrap(archive().copyOf(30)))
        assertFalse(EthornellArchiveProbe.hasBootstrap(archive().apply { this[15] = 127 }))
        assertFalse(EthornellArchiveProbe.hasBootstrap(archive().apply { this[12] = 0 }))
        assertFalse(EthornellArchiveProbe.hasBootstrap(archive().apply { this[36] = 0 }))
        assertFalse(EthornellArchiveProbe.hasBootstrap(null))
        assertFalse(EthornellArchiveProbe.hasBootstrap(archive(), 48))
        assertFalse(EthornellArchiveProbe.hasBootstrap(archive().apply { this[32] = 127 }, 52))
    }

    @Test fun streamProbeHandlesShortAndZeroReadsAndNeverReadsPastBudget() {
        val bytes = archive(true)
        val short = object : ByteArrayInputStream(bytes) {
            var calls = 0
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (++calls % 3 == 0) return 0
                return super.read(b, off, minOf(len, 3))
            }
        }
        assertTrue(EthornellArchiveProbe.hasBootstrap(EthornellArchiveProbe.readHead(short)))
        val large = ByteArrayInputStream(ByteArray(100_000))
        assertEquals(65_536, EthornellArchiveProbe.readHead(large).size)
        assertEquals(100_000 - 65_536, large.available())
    }

    @Test fun documentAdapterAndFileAdapterUseIdenticalContentEvidence() {
        val root = temporary.newFolder()
        val file = root.resolve("SYSTEM.ARC").apply { writeBytes(archive(true)) }
        val documents = listOf("SYSTEM.ARC")
        val result = EngineScanner.detectEngine(
            children = documents,
            nameOf = { it },
            isDirectory = { false },
            childrenOf = { emptyList() },
            headOf = { name -> root.resolve(name).inputStream().use { EthornellArchiveProbe.readHead(it) } },
        )
        assertEquals(EngineScanner.detectEngine(root), result)
        assertEquals(EngineType.ETHORNELL, result.engine)
        assertEquals(file.length().toInt(), file.inputStream().use { EthornellArchiveProbe.readHead(it).size })
    }

    @Test fun missingProviderSizeStillRejectsTruncatedPayloadJustLikeFileAdapter() {
        for (arc20 in listOf(false, true)) {
            val root = temporary.newFolder()
            root.resolve("system.arc").writeBytes(archive(arc20).dropLast(1).toByteArray())
            val documentResult = EngineScanner.detectEngine(
                children = listOf("system.arc"), nameOf = { it }, isDirectory = { false },
                childrenOf = { emptyList() },
                headOf = { root.resolve(it).inputStream().use(EthornellArchiveProbe::readHead) },
                sizeOf = { null },
            )
            assertEquals(EngineScanner.detectEngine(root), documentResult)
            assertNotEquals(EngineType.ETHORNELL, documentResult.engine)
        }
    }

    @Test fun ambiguousRootArchivesFailClosedRegardlessOfProviderOrder() {
        for (names in listOf(listOf("system.arc", "SYSTEM.ARC"), listOf("SYSTEM.ARC", "system.arc"))) {
            val result = EngineScanner.detectEngine(
                children = names, nameOf = { it }, isDirectory = { false },
                childrenOf = { emptyList() }, headOf = { archive() },
            )
            assertNotEquals(EngineType.ETHORNELL, result.engine)
        }
    }

    @Test fun interruptedProbePropagatesCancellationInsteadOfReturningAnUnknownEngine() {
        Thread.currentThread().interrupt()
        try {
            EthornellArchiveProbe.readHead(ByteArrayInputStream(archive()))
            fail("Expected cancellation")
        } catch (_: java.util.concurrent.CancellationException) {
            // Expected: cancellation is not an archive parse failure.
        } finally {
            Thread.interrupted()
        }
    }
}
