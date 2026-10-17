package com.core.siglus;

/** Main-thread decision: only an undelivered producer may be cancelled without EGL detachment. */
final class SiglusPreparationGate {
    private boolean pending = true;

    boolean deliver() {
        if (!pending) return false;
        pending = false;
        return true;
    }

    boolean cancel() {
        if (!pending) return false;
        pending = false;
        return true;
    }
}
