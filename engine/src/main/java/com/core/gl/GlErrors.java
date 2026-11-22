package com.core.gl;

import java.util.function.IntSupplier;

/**
 * Shared Level-0 OpenGL ES error draining, context-loss classification, and retryable-pressure
 * utilities. Keeps bounded {@code glGetError()} loops and error-code rules in a single source of
 * truth across all higher-level GL executors and backends.
 *
 * <p>Pure-Java Level-0 utility: defines the three GL error constants locally so unit tests run
 * without {@code android.opengl.GLES30}.</p>
 *
 * <p>Failure policy: errors observed while drawing (draw-site checks run with
 * {@code targetFailure=false}) are always permanent — a draw that produced GL errors has
 * already corrupted the frame, so {@link IllegalStateException} latches the failing executor
 * and retrying cannot repair it. Only allocation/attachment-site escapes with
 * GL_OUT_OF_MEMORY or GL_INVALID_FRAMEBUFFER_OPERATION (and no context loss) are retryable
 * through {@link TargetUnavailableException} with bounded backoff.</p>
 */
public final class GlErrors {
    public static final int MAX_GL_ERRORS = 16;
    static final int GL_NO_ERROR = 0;
    static final int GL_OUT_OF_MEMORY = 0x0505;
    static final int GL_INVALID_FRAMEBUFFER_OPERATION = 0x0506;
    public static final int GL_CONTEXT_LOST_KHR = 0x0507;

    /** Permanent failure signaling GL_CONTEXT_LOST_KHR (never retryable or capability bypass). */
    public static final class ContextLostException extends IllegalStateException {
        public ContextLostException(String message) {
            super(message);
        }
    }

    private GlErrors() {}

    /**
     * Drains inherited GL errors before an owned execution block. Returns {@link #GL_NO_ERROR}
     * when cleared, {@link #GL_CONTEXT_LOST_KHR} immediately on context loss, or {@code -1}
     * when {@link #MAX_GL_ERRORS} reads fail to drain the queue.
     */
    public static int beginGlOperations(IntSupplier errors) {
        for (int i = 0; i < MAX_GL_ERRORS; i++) {
            int error = errors.getAsInt();
            if (error == GL_NO_ERROR) return GL_NO_ERROR;
            if (error == GL_CONTEXT_LOST_KHR) return GL_CONTEXT_LOST_KHR;
        }
        return -1;
    }

    /**
     * Drains up to {@link #MAX_GL_ERRORS} flags before leaving an operation boundary.
     * Throws {@link ContextLostException} immediately on {@code GL_CONTEXT_LOST_KHR}.
     * When {@code targetFailure} is true and every observed error is {@code GL_OUT_OF_MEMORY}
     * or {@code GL_INVALID_FRAMEBUFFER_OPERATION}, throws a retryable
     * {@link TargetUnavailableException}; otherwise throws {@link IllegalStateException}.
     */
    static void requireGlSuccess(String operation, boolean targetFailure, IntSupplier errors) {
        requireGlSuccess(operation, targetFailure, TargetUnavailableException.FailureKind.GENERIC, errors);
    }

    /**
     * Variant of {@link #requireGlSuccess(String, boolean, IntSupplier)} that carries an
     * explicit {@link TargetUnavailableException.FailureKind}. On MRT-exclusive setup/compile/link
     * sites ({@code targetFailure == true && failureKind.mrt}), any non-context-lost GL error
     * (including driver-specific {@code GL_INVALID_OPERATION} / {@code GL_INVALID_VALUE} on
     * multi-target draw buffers or shader compilation) throws a typed
     * {@link TargetUnavailableException} so runtime MRT fusion can fall back once to the unfused
     * single-output plan. {@code GL_CONTEXT_LOST_KHR} always throws {@link ContextLostException}.
     */
    static void requireGlSuccess(String operation, boolean targetFailure,
                                 TargetUnavailableException.FailureKind failureKind,
                                 IntSupplier errors) {
        TargetUnavailableException.FailureKind effectiveKind =
                failureKind != null ? failureKind : TargetUnavailableException.FailureKind.GENERIC;
        boolean mrtSite = targetFailure && effectiveKind.mrt;
        StringBuilder detail = null;
        boolean retryable = targetFailure;
        for (int i = 0; i < MAX_GL_ERRORS; i++) {
            int error = errors.getAsInt();
            if (error == GL_NO_ERROR) {
                if (detail == null) return;
                if (retryable) throw new TargetUnavailableException(detail.toString(), true, effectiveKind);
                throw new IllegalStateException(detail.toString());
            }
            if (error == GL_CONTEXT_LOST_KHR) {
                throw new ContextLostException(operation + ": GL_CONTEXT_LOST_KHR (0x507)");
            }
            if (detail == null) detail = new StringBuilder(operation).append(": GL errors");
            detail.append(" 0x").append(Integer.toHexString(error));
            // Resource pressure and unusable attachments can recover after backoff;
            // on MRT-exclusive sites, driver-specific multi-target GL errors also route to the
            // bounded one-shot unfused fallback. Invalid API use elsewhere or a lost context
            // cannot be repaired by retrying.
            retryable &= (mrtSite
                    || error == GL_OUT_OF_MEMORY
                    || error == GL_INVALID_FRAMEBUFFER_OPERATION);
        }
        // A lost/broken context must not loop forever or be cached as a geometry rejection.
        String suffix = detail != null ? " (" + detail + ")" : "";
        throw new IllegalStateException(operation + ": GL error state did not clear (exhausted "
                + MAX_GL_ERRORS + " reads)" + suffix);
    }

    /**
     * Drains allocation errors before rejecting a zero GL object name; OOM/attachment flags
     * and zero names become retryable {@link TargetUnavailableException}, while API misuse or
     * context loss throws {@link IllegalStateException}.
     */
    static void requireTargetName(int name, String operation, IntSupplier errors) {
        requireGlSuccess(operation, true, errors);
        if (name == 0) throw new TargetUnavailableException("cannot allocate " + operation);
    }

    /**
     * Drains up to {@link #MAX_GL_ERRORS} error flags without throwing, stopping early on
     * {@code GL_NO_ERROR} or {@code GL_CONTEXT_LOST_KHR}. Returns {@code true} iff
     * {@code GL_CONTEXT_LOST_KHR} was observed.
     */
    static boolean drainAndDetectContextLost(int initialError, IntSupplier errors) {
        if (initialError == GL_CONTEXT_LOST_KHR) {
            return true;
        }
        if (initialError == GL_NO_ERROR) {
            return false;
        }
        for (int i = 0; i < MAX_GL_ERRORS; i++) {
            int err = errors.getAsInt();
            if (err == GL_CONTEXT_LOST_KHR) {
                return true;
            }
            if (err == GL_NO_ERROR) {
                return false;
            }
        }
        return false;
    }

    /**
     * Best-effort bounded drain used on error-unwind paths so secondary flags never mask
     * the primary exception or leak into the next node boundary. Never throws.
     */
    static void drainBestEffortNoThrow(int initialError, IntSupplier errors) {
        drainAndDetectContextLost(initialError, errors);
    }
}
