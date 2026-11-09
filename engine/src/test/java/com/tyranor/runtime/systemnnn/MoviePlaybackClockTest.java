package com.tyranor.runtime.systemnnn;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class MoviePlaybackClockTest {
    @Test public void silentPlaybackExcludesPauseAndMissingTextureTime() {
        MoviePlayback.Clock clock = new MoviePlayback.Clock();
        clock.setRunning(true, 0);
        clock.continueSilently(20_000_000);
        assertEquals(0, clock.position(20_000_000));
        clock.setRunning(false, 120_000_000);
        assertEquals(100_000, clock.position(2_120_000_000L));
        clock.setRunning(false, 2_120_000_000L);
        clock.setRunning(true, 3_120_000_000L);
        assertEquals(150_000, clock.position(3_170_000_000L));
    }

    @Test public void videoTailResumesFromAudioHeadWhenAudioEndsWhilePaused() {
        MoviePlayback.Clock clock = new MoviePlayback.Clock();
        clock.setRunning(true, 0);
        clock.audioPosition(250_000);
        clock.audioPosition(249_000);
        clock.setRunning(false, 300_000_000);
        clock.continueSilently(900_000_000);
        assertEquals(250_000, clock.position(1_000_000_000));
        clock.setRunning(true, 1_500_000_000);
        assertEquals(375_000, clock.position(1_625_000_000));
    }
}
