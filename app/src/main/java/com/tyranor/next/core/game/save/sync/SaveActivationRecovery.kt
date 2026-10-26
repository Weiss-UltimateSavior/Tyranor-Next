package com.tyranor.next.core.game.save.sync

/** A different explicit choice must not be reported as an earlier incomplete selection. */
internal object SaveActivationRecovery {
    suspend fun beforeSelection(
        sameContent: Boolean,
        canFinalize: Boolean,
        discardPrevious: Boolean,
        finish: suspend () -> SaveActivationResult,
        discard: suspend () -> Unit,
    ): SaveActivationResult? {
        if (canFinalize) {
            val result = finish()
            if (result == SaveActivationResult.APPLIED) {
                return if (sameContent) result else null
            }
            if (!discardPrevious) {
                return if (sameContent) SaveActivationResult.METADATA_PENDING else SaveActivationResult.PREVIOUS_PENDING
            }
        }
        discard()
        return null
    }
}
