package com.core.siglus;

import org.junit.Test;
import static org.junit.Assert.*;

public final class SiglusFrameGateTest {
    @Test public void noDrawStepsNeverWaitForAFrameThatWillNotArrive() {
        SiglusFrameGate gate = new SiglusFrameGate();
        assertTrue(gate.begin(1)); gate.completeStep(5, 5);
        assertTrue(gate.begin(2)); gate.completeStep(5, 5);
        assertFalse(gate.stalled(3_000_000_000L));
    }

    @Test public void permitsOnlyOneUnconsumedFrameAndTimesOutWithoutBlocking() {
        SiglusFrameGate gate = new SiglusFrameGate();
        assertTrue(gate.begin(10)); gate.completeStep(7, 8);
        assertFalse(gate.begin(20));
        assertFalse(gate.stalled(2_000_000_010L));
        assertTrue(gate.stalled(2_000_000_011L));
        gate.consumed(100);
        assertTrue(gate.begin(30));
    }

    @Test public void earlyAndDuplicateCallbacksCannotGrantTwoOutstandingFrames() {
        SiglusFrameGate gate = new SiglusFrameGate();
        assertTrue(gate.begin(1));
        gate.consumed(100); gate.completeStep(0, 1); // Worker finishes before JNI returns.
        assertTrue(gate.begin(2));
        gate.consumed(100); // Old notification without a new buffer.
        gate.completeStep(1, 2);
        assertFalse(gate.begin(3));
        gate.consumed(101);
        assertTrue(gate.begin(4));
    }
}
