package com.core.impacto;

import java.util.concurrent.TimeUnit;

/** Android-independent teardown decisions shared by the private SDL host and its lifecycle. */
public final class ImpactoShutdownPolicy {
    /** After SDL's short UI-thread grace, allow a bounded background wait for pending saves/quit. */
    public static final long BACKGROUND_GRACE_MS = 10_000;

    public interface Actions {
        /** Only for validation cancellation or a failed load before a native writer exists. */
        void releaseUnstartedSession();
        /** Must terminate the isolated process WITHOUT first releasing its admission lease. */
        void terminateProcess(boolean forced);
    }

    public enum Outcome { UNSTARTED, FINISHED, FORCED }

    private ImpactoShutdownPolicy() { }

    /** nativeQuit clears SDL's pending lifecycle events and must not race the native thread. */
    public static boolean mayQuitNative(Thread nativeThread) {
        return nativeThread == null || !nativeThread.isAlive();
    }

    /**
     * Called after Activity.onDestroy, on a background thread when native code might still run.
     * Interrupts do not surrender the lease or remove the deadline. A timeout is a forced stop,
     * not a successful/graceful save. Process death, rather than a Java close, releases the lease.
     */
    public static Outcome finish(boolean libraryLoaded, Thread nativeThread, long graceMillis, Actions actions) {
        if (graceMillis < 0) throw new IllegalArgumentException("Negative shutdown grace");
        if (!libraryLoaded && nativeThread == null) {
            actions.releaseUnstartedSession();
            return Outcome.UNSTARTED;
        }
        long start = System.nanoTime();
        long budget = TimeUnit.MILLISECONDS.toNanos(graceMillis);
        boolean interrupted = false;
        try {
            while (!mayQuitNative(nativeThread)) {
                long remaining = budget - (System.nanoTime() - start);
                if (remaining <= 0) break;
                try {
                    TimeUnit.NANOSECONDS.timedJoin(nativeThread, remaining);
                } catch (InterruptedException cancelledWait) {
                    // Destruction must still stop the old writer before another launch is admitted.
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
        boolean forced = !mayQuitNative(nativeThread);
        actions.terminateProcess(forced);
        return forced ? Outcome.FORCED : Outcome.FINISHED;
    }
}
