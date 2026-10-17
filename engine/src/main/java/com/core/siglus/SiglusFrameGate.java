package com.core.siglus;

/** One unconsumed producer frame at a time; never wait on the Activity thread. */
final class SiglusFrameGate {
    private boolean pending;
    private long submittedNs;
    private long lastTimestamp = Long.MIN_VALUE;

    synchronized boolean begin(long nowNs) {
        if (pending) return false;
        pending = true;
        submittedNs = nowNs;
        return true;
    }

    synchronized void completeStep(long beforeSerial, long afterSerial) {
        // Native dialogs, suppressed draws and recoverable Surface errors do
        // not enqueue a frame. They must not wait for a frame callback.
        if (beforeSerial == afterSerial) pending = false;
    }

    synchronized void consumed(long timestamp) {
        // A duplicate frame-available notification must not grant a second
        // credit while a newer producer frame has not reached the queue yet.
        if (timestamp == lastTimestamp) return;
        lastTimestamp = timestamp;
        pending = false;
    }

    synchronized boolean stalled(long nowNs) {
        return pending && nowNs - submittedNs > 2_000_000_000L;
    }
}
