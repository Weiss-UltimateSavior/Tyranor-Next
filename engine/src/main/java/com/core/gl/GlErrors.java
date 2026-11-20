package com.core.gl;

import android.opengl.GLES30;

import java.util.function.IntSupplier;

/**
 * Shared Level-0 OpenGL ES error draining, context-loss classification, and retryable-pressure
 * utilities. Keeps bounded {@code glGetError()} loops and error-code rules in a single source of
 * truth across all higher-level GL executors and backends.
 *
 * <p>Failure policy: errors observed while drawing (draw-site checks run with
 * {@code targetFailure=false}) are always permanent — a draw that produced GL errors has
 * already corrupted the frame, so {@link IllegalStateException} latches the failing executor
 * and retrying cannot repair it. Only allocation/attachment-site escapes with
 * GL_OUT_OF_MEMORY or GL_INVALID_FRAMEBUFFER_OPERATION (and no context loss) are retryable
 * through {@link TargetUnavailableException} with bounded backoff.</p>
 */
final class GlErrors {
    static final int MAX_GL_ERRORS = 16;
    static final int GL_CONTEXT_LOST_KHR = 0x0507;

    private GlErrors() {}

    /**
     * Drains up to {@link #MAX_GL_ERRORS} flags before leaving an operation boundary.
     * When {@code targetFailure} is true and every observed error is {@code GL_OUT_OF_MEMORY}
     * or {@code GL_INVALID_FRAMEBUFFER_OPERATION}, throws a retryable
     * {@link TargetUnavailableException}; otherwise throws {@link IllegalStateException}.
     */
    static void requireGlSuccess(String operation, boolean targetFailure, IntSupplier errors) {
        StringBuilder detail = null;
        boolean retryable = targetFailure;
        for (int i = 0; i < MAX_GL_ERRORS; i++) {
            int error = errors.getAsInt();
            if (error == GLES30.GL_NO_ERROR) {
                if (detail == null) return;
                if (retryable) throw new TargetUnavailableException(detail.toString());
                throw new IllegalStateException(detail.toString());
            }
            if (detail == null) detail = new StringBuilder(operation).append(": GL errors");
            detail.append(" 0x").append(Integer.toHexString(error));
            // Resource pressure and unusable attachments can recover after backoff;
            // invalid API use or a lost context cannot be repaired by retrying.
            retryable &= error != GL_CONTEXT_LOST_KHR
                    && (error == GLES30.GL_OUT_OF_MEMORY
                    || error == GLES30.GL_INVALID_FRAMEBUFFER_OPERATION);
        }
        // A lost/broken context must not loop forever or be cached as a geometry rejection.
        String suffix = detail != null ? " (" + detail + ")" : "";
        throw new IllegalStateException(operation + ": GL error state did not clear" + suffix);
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
     * Best-effort bounded drain used on error-unwind paths so secondary flags never mask
     * the primary exception or leak into the next node boundary. Never throws.
     */
    static void drainBestEffortNoThrow(int initialError, IntSupplier errors) {
        if (initialError == GL_CONTEXT_LOST_KHR) {
            return;
        }
        for (int i = 0; i < MAX_GL_ERRORS; i++) {
            int err = errors.getAsInt();
            if (err == GLES30.GL_NO_ERROR || err == GL_CONTEXT_LOST_KHR) {
                return;
            }
        }
    }
}
