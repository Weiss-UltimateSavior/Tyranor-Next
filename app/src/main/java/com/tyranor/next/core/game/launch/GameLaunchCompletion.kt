package com.tyranor.next.core.game.launch

import kotlinx.coroutines.CancellationException

/**
 * Completes a launch after its Activity has been dispatched.
 *
 * Bookkeeping runs after [onDispatched], so a late bookkeeping failure cannot turn a successfully
 * started game into a failed launch. Cancellation still propagates after dispatch is recorded.
 */
internal fun completeDispatchedGameLaunch(
    onDispatched: () -> Unit,
    recordRecent: () -> Unit,
    onRecordFailure: (Exception) -> Unit = {},
): LaunchResult {
    onDispatched()
    return try {
        recordRecent()
        LaunchResult.Success
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        onRecordFailure(failure)
        LaunchResult.Success
    }
}
