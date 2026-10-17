package com.core.gl;

import org.junit.Test;

import java.util.function.LongSupplier;

import static org.junit.Assert.*;

public class GraphicsTargetRetryTest {
    @Test public void frameRateDoesNotShortenTheFirstPressureDelay() {
        Clock clock = new Clock();
        GraphicsTargetRetry retry = new GraphicsTargetRetry(clock);
        assertTrue(retry.beginAttempt());
        retry.failed(true);
        assertTrue(retry.isRetryableFailure());
        for (int frame = 0; frame < 1000; frame++) assertFalse(retry.beginAttempt());
        clock.advance(1_999_999_999L);
        assertFalse(retry.beginAttempt());
        clock.advance(1);
        assertTrue(retry.beginAttempt());
    }

    @Test public void repeatedAllocationRejectionBacksOffAndContinuesAtACappedRate() {
        Clock clock = new Clock();
        GraphicsTargetRetry retry = new GraphicsTargetRetry(clock);
        for (long seconds : new long[]{2, 4, 8, 16, 30, 30, 30}) {
            retry.failed(true);
            clock.advance(seconds * 1_000_000_000L - 1);
            assertFalse(retry.beginAttempt());
            clock.advance(1);
            assertTrue(retry.beginAttempt());
        }
    }

    @Test public void resetAllowsImmediateGeometryOrContextRecoveryAndRestartsTheDelay() {
        Clock clock = new Clock();
        GraphicsTargetRetry retry = new GraphicsTargetRetry(clock);
        retry.failed(true);
        clock.advance(2_000_000_000L);
        assertTrue(retry.beginAttempt());
        retry.failed(true); // The second pressure rejection would wait four seconds.
        retry.reset();
        assertTrue(retry.beginAttempt());
        assertFalse(retry.isRetryableFailure());
        retry.failed(true);
        clock.advance(2_000_000_000L);
        assertTrue(retry.beginAttempt());
    }

    @Test public void deterministicBudgetOrGeometryRejectionNeverRetriesWithTimeAlone() {
        Clock clock = new Clock();
        GraphicsTargetRetry retry = new GraphicsTargetRetry(clock);
        retry.failed(false);
        assertFalse(retry.isRetryableFailure());
        for (int interval = 0; interval < 10; interval++) {
            clock.advance(60_000_000_000L);
            assertFalse(retry.beginAttempt());
        }
        retry.reset();
        assertTrue(retry.beginAttempt());
    }

    @Test public void aLaterNodeFailureRetainsWholeChainBackoffDespiteDiscardingNodeTargets() {
        Clock clock = new Clock();
        GraphicsTargetRetry chain = new GraphicsTargetRetry(clock);
        GraphicsTargetRetry node = new GraphicsTargetRetry(clock);
        for (long seconds : new long[]{2, 4, 8}) {
            node.failed(true);
            chain.failed(node.isRetryableFailure());
            node.reset(); // Releasing partial output must not reset the whole-chain gate.
            assertFalse(chain.beginAttempt());
            clock.advance(seconds * 1_000_000_000L);
            assertTrue(chain.beginAttempt());
            assertTrue(node.beginAttempt());
        }
        chain.reset(); // Publish success only after every node has completed.
        node.failed(true);
        chain.failed(node.isRetryableFailure());
        clock.advance(2_000_000_000L);
        assertTrue(chain.beginAttempt());
    }

    @Test public void arbitraryNanoTimeOriginCanWrapWithoutUnblockingEarly() {
        Clock clock = new Clock();
        clock.now = Long.MAX_VALUE - 1_000_000_000L;
        GraphicsTargetRetry retry = new GraphicsTargetRetry(clock);
        retry.failed(true);
        clock.advance(1_999_999_999L);
        assertFalse(retry.beginAttempt());
        clock.advance(1);
        assertTrue(retry.beginAttempt());
    }

    private static final class Clock implements LongSupplier {
        long now;
        void advance(long nanos) { now += nanos; }
        @Override public long getAsLong() { return now; }
    }
}
