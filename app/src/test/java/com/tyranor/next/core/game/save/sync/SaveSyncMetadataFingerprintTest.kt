package com.tyranor.next.core.game.save.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SaveSyncMetadataFingerprintTest {
    @Test
    fun fingerprintIsIndependentOfEnumerationOrder() {
        val first = SaveSyncMetadataFingerprint.Entry("save/one.bin", 12, 100)
        val second = SaveSyncMetadataFingerprint.Entry("save/two.bin", 24, 200)

        assertEquals(
            SaveSyncMetadataFingerprint.fingerprint(listOf(first, second)),
            SaveSyncMetadataFingerprint.fingerprint(listOf(second, first)),
        )
    }

    @Test
    fun fingerprintIncludesPathSizeAndModificationTime() {
        val baseline = SaveSyncMetadataFingerprint.Entry("save/one.bin", 12, 100)
        val fingerprint = SaveSyncMetadataFingerprint.fingerprint(listOf(baseline))

        assertFalse(fingerprint == SaveSyncMetadataFingerprint.fingerprint(
            listOf(baseline.copy(relativePath = "save/renamed.bin")),
        ))
        assertFalse(fingerprint == SaveSyncMetadataFingerprint.fingerprint(
            listOf(baseline.copy(size = 13)),
        ))
        assertFalse(fingerprint == SaveSyncMetadataFingerprint.fingerprint(
            listOf(baseline.copy(modifiedAtNanos = 101)),
        ))
    }
}
