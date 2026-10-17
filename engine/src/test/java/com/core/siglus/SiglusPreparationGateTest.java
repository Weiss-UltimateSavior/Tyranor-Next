package com.core.siglus;

import org.junit.Test;
import static org.junit.Assert.*;

public final class SiglusPreparationGateTest {
    @Test public void timeoutBeforeDeliveryBlocksAQueuedLateProducer() {
        SiglusPreparationGate gate = new SiglusPreparationGate();
        assertTrue(gate.cancel());
        assertFalse(gate.deliver());
        assertFalse(gate.cancel());
    }

    @Test public void deliveredProducerCannotBeCancelledByThePreparationTimeout() {
        SiglusPreparationGate gate = new SiglusPreparationGate();
        assertTrue(gate.deliver());
        assertFalse(gate.cancel());
        assertFalse(gate.deliver());
    }
}
