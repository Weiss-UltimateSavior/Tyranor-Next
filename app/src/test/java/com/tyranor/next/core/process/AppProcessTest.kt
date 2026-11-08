package com.tyranor.next.core.process

import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppProcessTest {
    @Test
    fun knownPlatformIdentityDoesNotConsultFallback() {
        assertTrue(AppProcess.matchesMainProcess(PACKAGE, PACKAGE, false) { error("Known main process") })
        assertFalse(AppProcess.matchesMainProcess(PACKAGE, "$PACKAGE:tyrano", true) { error("Known engine process") })
    }

    @Test
    fun missingProcessListUsesOnlyTheNulTerminatedCurrentProcessName() {
        val cmdline = Files.createTempFile("app-process", ".cmdline").toFile()
        try {
            cmdline.writeBytes(("$PACKAGE:artemis\u0000$PACKAGE\u0000").toByteArray())
            assertFalse(AppProcess.matchesMainProcess(PACKAGE, null, true) { AppProcess.readProcessName(cmdline) })
            cmdline.writeBytes(("$PACKAGE\u0000--ignored-argument").toByteArray())
            assertTrue(AppProcess.matchesMainProcess(PACKAGE, null, false) { AppProcess.readProcessName(cmdline) })
        } finally {
            cmdline.delete()
        }
    }

    @Test
    fun unavailableOrOverlongIdentityPreservesTheCallersUnknownPolicy() {
        val missing = Files.createTempFile("app-process-missing", ".cmdline").toFile().apply { delete() }
        assertNull(AppProcess.readProcessName(missing))
        assertFalse(AppProcess.matchesMainProcess(PACKAGE, null, false) { null })
        assertTrue(AppProcess.matchesMainProcess(PACKAGE, null, true) { null })
        val cmdline = Files.createTempFile("app-process-long", ".cmdline").toFile()
        try {
            cmdline.writeText("a".repeat(300))
            assertNull(AppProcess.readProcessName(cmdline))
        } finally {
            cmdline.delete()
        }
    }

    private companion object {
        const val PACKAGE = "com.tyranor.next.motionv2"
    }
}
