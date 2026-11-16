package com.tyranor.next.core.game.playtime

/** Aggregated playtime for a game. Durations use monotonic elapsed time, never wall-clock differences. */
data class GamePlaytimeStats(
    val totalMillis: Long = 0,
    val lastSessionMillis: Long = 0,
)
