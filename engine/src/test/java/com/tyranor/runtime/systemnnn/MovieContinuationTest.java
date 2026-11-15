package com.tyranor.runtime.systemnnn;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class MovieContinuationTest {
    @Test public void backgroundTimeDoesNotConsumePostMovieWait(){
        MovieContinuation flow=new MovieContinuation();
        int[] advances={0};
        flow.start(()->advances[0]++,100);
        assertEquals(1100,flow.resume(1000));
        flow.pause(1040);
        assertTrue(flow.pending());
        assertNull(flow.takeReady(3000));

        assertEquals(3060,flow.resume(3000));
        assertEquals(-1,flow.resume(3020));
        assertNull(flow.takeReady(3059));
        flow.takeReady(3060).run();
        assertEquals(1,advances[0]);
        assertNull(flow.takeReady(4000));
    }

    @Test public void completionArrivingWhilePausedStartsItsWaitOnResume(){
        MovieContinuation flow=new MovieContinuation();
        int[] advances={0};
        flow.start(()->advances[0]++,70);
        flow.pause(200);
        assertNull(flow.takeReady(400));
        assertEquals(570,flow.resume(500));
        assertNull(flow.takeReady(569));
        flow.takeReady(570).run();
        assertEquals(1,advances[0]);
    }

    @Test public void cancellationDiscardsTheOldActionAcrossAReplacement(){
        MovieContinuation flow=new MovieContinuation();
        int[] advances={0};
        flow.start(()->{throw new AssertionError("Retired movie ran");},100);
        assertEquals(200,flow.resume(100));
        flow.pause(110);
        flow.cancel();
        assertFalse(flow.pending());
        assertEquals(-1,flow.resume(1000));

        flow.start(()->advances[0]++,0);
        assertEquals(1000,flow.resume(1000));
        flow.takeReady(1000).run();
        assertEquals(1,advances[0]);
        assertNull(flow.takeReady(1000));
    }
}
