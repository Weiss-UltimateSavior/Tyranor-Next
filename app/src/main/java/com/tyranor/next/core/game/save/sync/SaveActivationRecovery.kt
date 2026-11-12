package com.tyranor.next.core.game.save.sync

/** A different explicit choice must not be reported as an earlier incomplete selection. */
internal object SaveActivationRecovery {
    suspend fun beforeSelection(
        sameContent: Boolean,
        canFinalize: Boolean,
        discardPrevious: Boolean,
        currentMatchesSelection: Boolean = false,
        finish: suspend () -> SaveActivationResult,
        discard: suspend () -> Unit,
    ): SaveActivationResult? {
        if (canFinalize) {
            val result = finish()
            if (result == SaveActivationResult.APPLIED) {
                // Finalizing an older transaction can publish a later N without importing S.
                // A fresh explicit choice must still import S when the active bytes advanced.
                return if (sameContent && currentMatchesSelection) result else null
            }
            if (!discardPrevious) {
                return if (sameContent && currentMatchesSelection) SaveActivationResult.METADATA_PENDING else SaveActivationResult.PREVIOUS_PENDING
            }
        }
        discard()
        return null
    }
}
