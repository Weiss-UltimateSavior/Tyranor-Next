package com.tyranor.next.core.game.save.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class SaveSnapshotSpaceTest {
    @Test fun admissionReservesSpaceWithoutEvictingExistingVersions() {
        val reserve = 16L * 1024 * 1024
        requireSnapshotSpace(1024, reserve + 1024)
        for ((archive, available) in listOf(1024L to reserve, Long.MAX_VALUE to Long.MAX_VALUE, 1L to 0L)) {
            try {
                requireSnapshotSpace(archive, available)
                fail("Insufficient space was accepted")
            } catch (failure: SaveSyncException) {
                assertEquals(SaveSyncError.STORAGE_FULL, failure.code)
            }
        }
    }
}
