package com.core.ethornell;

/** Terminal policy: never kill on WM_CLOSE request, only after native cleanup ACK (or no runtime). */
final class EthornellProcessExit {
    private boolean dispatched;
    synchronized void afterCleanup(boolean ownEngineProcess, boolean cleanupAcknowledged,
                                   Runnable finishUi, Runnable terminateSelf) {
        if (dispatched || !ownEngineProcess || !cleanupAcknowledged) return;
        dispatched = true;
        // finish updates the task before termination. No delayed cached-process BUSY window.
        try { finishUi.run(); } finally { terminateSelf.run(); }
    }
}
