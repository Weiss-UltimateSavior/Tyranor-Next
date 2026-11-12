package com.core.siglus;

/** One native owner per process across both direct and filtered presentation. */
public final class SiglusEngineAdmission {
    static final SiglusEngineAdmission PROCESS = new SiglusEngineAdmission();
    private Permit active;
    /** Cross-layer read-only evidence; only owner release confirms native destruction. */
    public static boolean hasActiveOwner() {
        synchronized (PROCESS) { return PROCESS.active != null; }
    }
    synchronized Permit acquire() {
        if (active != null) return null;
        active = new Permit();
        return active;
    }
    final class Permit {
        void release() {
            synchronized (SiglusEngineAdmission.this) {
                // An old completion cannot free a subsequent owner's permit.
                if (active == this) active = null;
            }
        }
    }
}
