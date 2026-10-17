package com.core.ethornell;

import java.util.function.Consumer;

/** Redacted evidence at actual lifecycle boundaries; no synthetic history at host completion. */
final class EthornellLifecycle {
    static final String TAG = "EthornellLifecycle";
    private final String suffix;
    private final Consumer<String> log;

    EthornellLifecycle(String identitySha256, long token, Consumer<String> log) {
        if (identitySha256 == null || !identitySha256.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Invalid identity digest");
        suffix = " identity_sha256=" + identitySha256 + " token=" + token;
        this.log = log;
    }

    void observe(EthornellSession.Event event) {
        if (event.kind == 3) log.accept("closed_event" + suffix);
    }

    void destroy(Runnable nativeDestroy) {
        nativeDestroy.run(); // A thrown JNI exception or a blocked call must never produce an ACK log.
        log.accept("destroy_ack" + suffix);
    }

    void failed() { log.accept("failed" + suffix); }
    void failedDetail(String safeDetail) { log.accept("failed_detail" + suffix + " diagnostic_schema=2 " + safeDetail); }
    void forcedExit(String safeReason) { log.accept("forced_exit" + suffix + " " + safeReason); }
    void cleanupExit() { log.accept("process_exit" + suffix + " reason=cleanup_ack"); }
}
