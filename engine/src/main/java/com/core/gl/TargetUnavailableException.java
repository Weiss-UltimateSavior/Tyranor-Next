package com.core.gl;

/** Signals a retryable or deterministic render target / capacity rejection without failing the pipeline permanently. */
final class TargetUnavailableException extends RuntimeException {
    final boolean retryAfterDelay;

    TargetUnavailableException(String detail) {
        this(detail, true);
    }

    TargetUnavailableException(String detail, boolean retryAfterDelay) {
        super(detail);
        this.retryAfterDelay = retryAfterDelay;
    }

    TargetUnavailableException(String detail, boolean retryAfterDelay, Throwable cause) {
        super(detail, cause);
        this.retryAfterDelay = retryAfterDelay;
    }
}
