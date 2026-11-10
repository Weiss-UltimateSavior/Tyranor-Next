package com.tyranor.next.core.game.launch

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class SystemNnnCopyConsentTest {
    @Test fun firstCopyWithoutInteractionReturnsAConfirmationFailure() = runBlocking {
        val result = EngineLauncher.checkSystemNnnCopyConsent(restoreResources = false, interaction = null)
        assertEquals(LaunchResult.Failure.SystemNnnResourcesFailed(SystemNnnResourceError.COPY_CONFIRMATION_REQUIRED), result)
    }

    @Test fun decliningFirstCopyCancelsTheLaunch() {
        val interaction = EngineLauncher.SystemNnnCopyInteraction(confirmFirstCopy = { false }, onCopying = {})
        assertThrows(CancellationException::class.java) {
            runBlocking { EngineLauncher.checkSystemNnnCopyConsent(restoreResources = false, interaction) }
        }
    }

    @Test fun existingCopyRestoresResourcesWithoutConfirmation() = runBlocking {
        val interaction = EngineLauncher.SystemNnnCopyInteraction(
            confirmFirstCopy = { error("Restoring resources asked for first-copy confirmation") },
            onCopying = {},
        )
        assertNull(EngineLauncher.checkSystemNnnCopyConsent(restoreResources = true, interaction = null))
        assertNull(EngineLauncher.checkSystemNnnCopyConsent(restoreResources = true, interaction))
    }
}
