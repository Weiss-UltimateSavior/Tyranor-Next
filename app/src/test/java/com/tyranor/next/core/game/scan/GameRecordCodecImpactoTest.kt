package com.tyranor.next.core.game.scan

import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.game.model.ScanGame
import org.junit.Assert.assertEquals
import org.junit.Test

class GameRecordCodecImpactoTest {
    @Test fun stableEngineNameRoundTripsWithoutAddingProfilePersistenceFields() {
        val game = ScanGame("Memories Off", "content://games/mo6tw", EngineType.IMPACTO, EngineScanner.LAUNCH_TARGET_GAME_DIR)
        assertEquals(game, GameRecordCodec.parseGame(GameRecordCodec.serializeGame(game)))
    }
}
