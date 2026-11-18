package com.tyranor.next.core.engine.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MajiroRuntimeModuleTest {
    private val manifest = requireNotNull(
        RuntimeModuleManifestParser.parse(
            """
            {
              "protocol": 1,
              "label": "Majiro Emulator",
              "scan": {
                "match": "any",
                "extensions": [".mjo", ".arc"],
                "markers": ["majiro.env"]
              }
            }
            """.trimIndent(),
        ),
    )

    @Test
    fun manifestUsesThirdPartyModuleScanningRules() {
        assertEquals("Majiro Emulator", manifest.label)
        val scan = requireNotNull(manifest.scan)
        assertEquals(RuntimeModuleScanSpec.Match.ANY, scan.match)
        assertTrue(
            RuntimeModuleMatcher.matches(
                scan,
                listOf(RuntimeModuleEntry("MAJIRO.ENV", isDirectory = false)),
            ) { emptyList() },
        )
        assertTrue(
            RuntimeModuleMatcher.matches(
                scan,
                listOf(RuntimeModuleEntry("story.MJO", isDirectory = false)),
            ) { emptyList() },
        )
        assertTrue(
            RuntimeModuleMatcher.matches(
                scan,
                listOf(RuntimeModuleEntry("fastdata.ARC", isDirectory = false)),
            ) { emptyList() },
        )
    }

    @Test
    fun gameArchiveIsNotAttributedToItsParentDirectory() {
        val scan = requireNotNull(manifest.scan)
        val game = RuntimeModuleEntry("game", isDirectory = true)
        val sibling = RuntimeModuleEntry("sibling", isDirectory = true)
        val gameFiles = listOf(RuntimeModuleEntry("fastdata.arc", isDirectory = false))
        val siblingFiles = listOf(RuntimeModuleEntry("story.mjo", isDirectory = false))

        assertFalse(RuntimeModuleMatcher.matches(scan, listOf(game, sibling)) { entry ->
            if (entry == game) gameFiles else siblingFiles
        })
        assertTrue(RuntimeModuleMatcher.matches(scan, gameFiles) { emptyList() })
        assertTrue(RuntimeModuleMatcher.matches(scan, siblingFiles) { emptyList() })
    }
}
