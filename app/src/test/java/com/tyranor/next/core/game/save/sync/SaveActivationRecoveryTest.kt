package com.tyranor.next.core.game.save.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class SaveActivationRecoveryTest {
    @Test fun committedFailureDoesNotClaimThatDifferentSelectionWasApplied() = runBlocking {
        var discarded = false
        val result = SaveActivationRecovery.beforeSelection(false, true, false,
            finish = { SaveActivationResult.METADATA_PENDING }, discard = { discarded = true })
        assertEquals(SaveActivationResult.PREVIOUS_PENDING, result)
        assertFalse(discarded)
    }

    @Test fun confirmedDiscardAllowsDifferentSelectionAfterPreservingCurrentSave() = runBlocking {
        val events = ArrayList<String>()
        val result = SaveActivationRecovery.beforeSelection(false, true, true,
            finish = { events += "finish"; SaveActivationResult.METADATA_PENDING },
            discard = { events += "preserve-current-and-discard" })
        assertNull(result)
        assertEquals(listOf("finish", "preserve-current-and-discard"), events)
    }

    @Test fun sameSelectionStillReportsAppliedSaveWithMetadataPending() = runBlocking {
        val result = SaveActivationRecovery.beforeSelection(true, true, false,
            finish = { SaveActivationResult.METADATA_PENDING }, discard = { error("not confirmed") })
        assertEquals(SaveActivationResult.METADATA_PENDING, result)
    }

    @Test fun failedPreservationCannotAuthorizeNewImportAndCancellationIsUnchanged() = runBlocking {
        for (failure in listOf(IOException("preserve failed"), CancellationException("cancelled"))) {
            var reachedNewImport = false
            try {
                SaveActivationRecovery.beforeSelection(false, false, false,
                    finish = { error("uncommitted") }, discard = { throw failure })
                reachedNewImport = true
            } catch (caught: Exception) {
                assertSame(failure, caught)
            }
            assertFalse(reachedNewImport)
        }
    }

    @Test fun completedPreviousSelectionDoesNotDiscardItsHistory() = runBlocking {
        var finished = false
        val result = SaveActivationRecovery.beforeSelection(false, true, false,
            finish = { finished = true; SaveActivationResult.APPLIED }, discard = { error("already completed") })
        assertTrue(finished)
        assertNull(result)
    }
}
