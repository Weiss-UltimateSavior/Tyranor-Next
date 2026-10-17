package com.core.gl;

/** Bounds live, releasing and retained proxy resources without evicting EGL owners. */
public final class GraphicsProxyResourceSlots {
    private static final int PROCESS_CAPACITY = 2;
    private static final GraphicsProxyResourceSlots PROCESS = new GraphicsProxyResourceSlots(PROCESS_CAPACITY);
    private final int capacity;
    private int occupied;

    GraphicsProxyResourceSlots(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("Positive proxy capacity required");
        this.capacity = capacity;
    }

    /** Acquire before starting a worker or allocating EGL resources. Null keeps native presentation. */
    public static Slot acquireForProcess() {
        return PROCESS.acquire();
    }

    synchronized Slot acquire() {
        if (occupied >= capacity) return null;
        Slot slot = new Slot(this);
        occupied++;
        return slot;
    }

    private synchronized void release(Slot slot) {
        if (slot.released) return;
        slot.released = true;
        occupied--;
    }

    public static final class Slot {
        private final GraphicsProxyResourceSlots owner;
        // Accessed under the owner's monitor, including cross-thread releases.
        private boolean released;

        private Slot(GraphicsProxyResourceSlots owner) {
            this.owner = owner;
        }

        /** Only after complete disposal, or construction failed before any EGL work was queued. */
        public void release() {
            owner.release(this);
        }
    }
}
