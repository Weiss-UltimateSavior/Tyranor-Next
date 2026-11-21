package com.core.gl;

/** Signals a retryable or deterministic render target / capacity rejection without failing the pipeline permanently. */
final class TargetUnavailableException extends RuntimeException {
    /**
     * Typed failure classification shared by throw sites and the runtime MRT fusion fallback
     * discriminator. The exception message stays human-facing (logs only) and never carries
     * dispatch semantics: changing wording cannot silently disable the fallback because the
     * discriminator reads {@link #kind}, not text.
     */
    enum FailureKind {
        /** Not MRT-specific: generic allocation, budget, geometry or probe rejection. */
        GENERIC(false),
        /** GLES device cannot provide the draw buffers / color attachments the fused pass needs. */
        MRT_CAPACITY(true),
        /** Multi-target framebuffer configuration is incomplete on this device. */
        MRT_FBO_INCOMPLETE(true),
        /** Fused multi-target program failed to link. */
        MRT_PROGRAM_LINK(true),
        /** Fused multi-target fragment shader failed to compile. */
        MRT_FRAGMENT_SHADER_COMPILE(true);

        final boolean mrt;

        FailureKind(boolean mrt) { this.mrt = mrt; }
    }

    final boolean retryAfterDelay;
    final FailureKind kind;

    TargetUnavailableException(String detail) {
        this(detail, true);
    }

    TargetUnavailableException(String detail, boolean retryAfterDelay) {
        this(detail, retryAfterDelay, FailureKind.GENERIC);
    }

    TargetUnavailableException(String detail, boolean retryAfterDelay, Throwable cause) {
        this(detail, retryAfterDelay, FailureKind.GENERIC, cause);
    }

    TargetUnavailableException(String detail, boolean retryAfterDelay, FailureKind kind) {
        super(detail);
        this.retryAfterDelay = retryAfterDelay;
        this.kind = kind;
    }

    TargetUnavailableException(String detail, boolean retryAfterDelay, FailureKind kind, Throwable cause) {
        super(detail, cause);
        this.retryAfterDelay = retryAfterDelay;
        this.kind = kind;
    }

    /** Runtime MRT fusion fallback discriminator: true exactly for the MRT FailureKind family. */
    static boolean isMrtFailure(TargetUnavailableException failure) {
        return failure != null && failure.kind.mrt;
    }
}
