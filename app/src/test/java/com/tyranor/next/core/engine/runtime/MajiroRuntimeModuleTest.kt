package com.tyranor.next.core.engine.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MajiroRuntimeModuleTest {
    @Test
    fun manifestUsesThirdPartyModuleScanningRules() {
        val manifest = requireNotNull(
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
}
