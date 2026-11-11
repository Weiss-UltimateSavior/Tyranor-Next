package com.tyranor.next.ui.game

/** Owns one accepted launch across asynchronous preparation and its confirmation dialogs. Main thread only. */
internal class GameLaunchPreparation {
    private var active = false

    /** Acquires synchronously before scheduling work, so queued activations cannot start a second request. */
    fun tryBegin(): Boolean {
        if (active) return false
        active = true
        return true
    }

    /** Ends a failed, cancelled or delivered request; successful navigation keeps its separate sheet guard. */
    fun finish() {
        active = false
    }

    /** Keeps ownership after a normal handoff, but releases it if a preparation stage throws or is cancelled. */
    suspend fun step(action: suspend () -> Unit) {
        var completed = false
        try {
            action()
            completed = true
        } finally {
            if (!completed) finish()
        }
    }
}
