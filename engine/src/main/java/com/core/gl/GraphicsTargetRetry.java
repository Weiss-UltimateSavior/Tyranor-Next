package com.core.gl;

import java.util.function.LongSupplier;

/** Render-thread retry gate: resource pressure backs off; deterministic rejections wait for reset. */
final class GraphicsTargetRetry {
    private static final long INITIAL_DELAY_NANOS = 2_000_000_000L;
    private static final long MAX_DELAY_NANOS = 30_000_000_000L;
    private final LongSupplier clock;
    private boolean waiting, retryAfterDelay;
    private long delayNanos, nextAttemptNanos;

    GraphicsTargetRetry() { this(System::nanoTime); }

    GraphicsTargetRetry(LongSupplier clock) {
        if (clock == null) throw new IllegalArgumentException("missing retry clock");
        this.clock = clock;
    }

    void failed(boolean retryAfterDelay) {
        waiting = true;
        this.retryAfterDelay = retryAfterDelay;
        if (!retryAfterDelay) return;
        delayNanos = delayNanos == 0 ? INITIAL_DELAY_NANOS
                : Math.min(MAX_DELAY_NANOS, delayNanos * 2);
        nextAttemptNanos = clock.getAsLong() + delayNanos;
    }

    /** Called only when a frame is available; never sleeps or queues asynchronous GL work. */
    boolean beginAttempt() {
        if (!waiting) return true;
        // Subtraction also works when nanoTime's arbitrary signed origin wraps.
        if (!retryAfterDelay || clock.getAsLong() - nextAttemptNanos < 0) return false;
        waiting = false;
        return true;
    }

    boolean isRetryableFailure() { return waiting && retryAfterDelay; }

    /** A successful complete operation, new geometry or a new context starts fresh. */
    void reset() {
        waiting = retryAfterDelay = false;
        delayNanos = nextAttemptNanos = 0;
    }
}
