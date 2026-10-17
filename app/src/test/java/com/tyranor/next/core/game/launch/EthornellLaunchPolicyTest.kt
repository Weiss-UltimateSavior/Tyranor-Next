package com.tyranor.next.core.game.launch

import org.junit.Assert.assertEquals
import org.junit.Test

class EthornellLaunchPolicyTest {
    private val game = "content://com.android.externalstorage.documents/tree/primary%3Agames/document/primary%3Agames%2Fa"

    @Test fun liveSameGameRestoresInsteadOfPreparingAnotherSession() {
        assertEquals(EthornellLaunchPolicy.Action.RESTORE_EXISTING, EthornellLaunchPolicy.decide(game, game, true))
    }

    @Test fun differentLiveGameIsRejected() {
        assertEquals(EthornellLaunchPolicy.Action.BUSY, EthornellLaunchPolicy.decide(game, "file:///other", true))
    }

    @Test fun deadHostWithStaleRegistryFileCanStartNewSession() {
        // currentLiveGame filters stale PID/start-time ownership even when currentGame still has a file.
        assertEquals(EthornellLaunchPolicy.Action.START_NEW, EthornellLaunchPolicy.decide(game, null, false))
    }

    @Test fun hostAliveWithoutPublishedIdentityMustNotPrepareAnotherWriter() {
        assertEquals(EthornellLaunchPolicy.Action.BUSY, EthornellLaunchPolicy.decide(game, null, true))
    }

    @Test fun mainProcessAloneDoesNotMeanEngineHostIsAlive() {
        // The registry scopes isHostAlive to exact package:ethornell and UID, not the main process.
        assertEquals(EthornellLaunchPolicy.Action.START_NEW, EthornellLaunchPolicy.decide(game, null, false))
    }

    @Test fun liveIdentityWinsOverALaterRacingProcessSnapshot() {
        // Conservative if the host disappears between currentLiveGame and isHostAlive reads.
        assertEquals(EthornellLaunchPolicy.Action.RESTORE_EXISTING, EthornellLaunchPolicy.decide(game, game, false))
        assertEquals(EthornellLaunchPolicy.Action.BUSY, EthornellLaunchPolicy.decide(game, "file:///other", false))
    }
}
