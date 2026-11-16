package com.tyranor.runtime.systemnnn;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class EngineStartupGenerationTest {
    @Test public void retryRejectsQueuedSuccessAndFailureFromPreviousAttempt() {
        EngineStartupGeneration generation = new EngineStartupGeneration();
        long firstAttempt = generation.current();

        long retry = generation.advance();

        assertTrue(generation.accepts(retry));
        assertFalse("Queued first-attempt frame must not reveal retry overlay",
                generation.accepts(firstAttempt));
        assertFalse("Queued first-attempt failure must not poison retry state",
                generation.fail(firstAttempt));
        assertTrue(generation.accepts(retry));
    }

    @Test public void failureRejectsLateFrameUntilTheNextAttempt() {
        EngineStartupGeneration generation = new EngineStartupGeneration();
        long attempt = generation.current();

        assertTrue(generation.fail(attempt));
        assertFalse("Late frame after an error must not reveal failed attempt",
                generation.accepts(attempt));
        long retry = generation.advance();
        assertTrue(generation.accepts(retry));
    }

    @Test public void disposeRejectsAlreadyQueuedCallbacks() {
        EngineStartupGeneration generation = new EngineStartupGeneration();
        long queuedFrame = generation.current();

        generation.dispose();

        assertFalse(generation.accepts(queuedFrame));
        assertFalse(generation.accepts(generation.current()));
    }
}
