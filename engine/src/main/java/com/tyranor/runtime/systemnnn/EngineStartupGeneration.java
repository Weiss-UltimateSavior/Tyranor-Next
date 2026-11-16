package com.tyranor.runtime.systemnnn;

/** Rejects startup callbacks queued by an earlier retry attempt or a destroyed host. */
final class EngineStartupGeneration {
    private long current;
    private boolean failed;
    private boolean disposed;

    synchronized long current() {
        return current;
    }

    synchronized long advance() {
        if (!disposed) {
            current++;
            failed = false;
        }
        return current;
    }

    synchronized boolean accepts(long generation) {
        return !disposed && !failed && generation == current;
    }

    synchronized boolean fail(long generation) {
        if (disposed || failed || generation != current) return false;
        failed = true;
        return true;
    }

    synchronized void dispose() {
        disposed = true;
        failed = true;
        current++;
    }
}
