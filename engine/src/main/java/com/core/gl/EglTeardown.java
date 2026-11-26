package com.core.gl;

import android.opengl.EGL14;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;

/**
 * Shared EGL teardown invariants for the GLES filter proxies
 * ({@code FramebufferGraphicsProxy}, {@code OnsGraphicsProxy}, {@code SiglusGraphicsProxy}).
 *
 * <p>Keeping the unbind gate, handle destruction, conditional {@code eglTerminate} and bounded
 * retry backoff in one place prevents the three proxies from drifting on driver-error paths.</p>
 */
public final class EglTeardown {
    private EglTeardown() {}

    /**
     * Bounded exponential retry delay for deferred EGL cleanup:
     * attempt 1 -> {@code baseMs}, 2 -> {@code 2*baseMs}, ..., clamped to avoid shift overflow.
     */
    public static long retryDelayMs(long baseMs, int attempt) {
        if (baseMs <= 0L) return 0L;
        int shift = attempt <= 1 ? 0 : Math.min(attempt - 1, 30);
        long maxBeforeShift = Long.MAX_VALUE >> shift;
        return baseMs > maxBeforeShift ? Long.MAX_VALUE : (baseMs << shift);
    }

    /**
     * Detaches the calling worker thread from any current EGL surfaces/context before
     * destroying EGL handles. Succeeds only if either {@code eglMakeCurrent(NO_SURFACE, NO_CONTEXT)}
     * or {@code eglReleaseThread()} succeeds.
     */
    public static boolean unbind(EGLDisplay display) {
        boolean unbound = EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
        boolean released = EGL14.eglReleaseThread();
        return unbound || released;
    }

    /**
     * Destroys {@code surface} if non-empty, returning {@link EGL14#EGL_NO_SURFACE} only when
     * destruction is confirmed so failed attempts retain the handle for retry.
     */
    public static EGLSurface destroySurface(EGLDisplay display, EGLSurface surface) {
        if (surface == EGL14.EGL_NO_SURFACE) return EGL14.EGL_NO_SURFACE;
        return EGL14.eglDestroySurface(display, surface) ? EGL14.EGL_NO_SURFACE : surface;
    }

    /**
     * Destroys {@code context} if non-empty, invoking {@code onDestroyed} only when context
     * destruction is confirmed so GL names/pipeline stay eligible for retry until then.
     */
    public static EGLContext destroyContext(EGLDisplay display, EGLContext context, Runnable onDestroyed) {
        if (context == EGL14.EGL_NO_CONTEXT) return EGL14.EGL_NO_CONTEXT;
        if (!EGL14.eglDestroyContext(display, context)) return context;
        if (onDestroyed != null) onDestroyed.run();
        return EGL14.EGL_NO_CONTEXT;
    }

    /**
     * Terminates {@code display} only when this proxy previously succeeded at
     * {@code eglInitialize}; avoids unbalancing the shared default display's reference count.
     */
    public static boolean terminate(EGLDisplay display, boolean initialized) {
        return !initialized || EGL14.eglTerminate(display);
    }
}
