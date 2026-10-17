package com.core.gl;

import java.util.concurrent.atomic.AtomicReference;

/** Transfers a screen reference exactly once between the caller and GLES worker. */
final class ArtemisOfficialConnectRequest<T> {
    private final AtomicReference<T> pending;
    private final boolean firstConnection;
    private volatile boolean cancelledBeforeClaim;

    ArtemisOfficialConnectRequest(T screen, boolean firstConnection) {
        if (screen == null) throw new IllegalArgumentException("Missing connection screen");
        pending = new AtomicReference<>(screen);
        this.firstConnection = firstConnection;
    }

    T claim() { return pending.getAndSet(null); }

    T cancel() {
        T unclaimed = pending.getAndSet(null);
        if (unclaimed != null) cancelledBeforeClaim = true;
        return unclaimed;
    }

    // An unclaimed reconnect says nothing about a previous consumer's queue.
    boolean canConfirmDetached() { return firstConnection && cancelledBeforeClaim; }
}
