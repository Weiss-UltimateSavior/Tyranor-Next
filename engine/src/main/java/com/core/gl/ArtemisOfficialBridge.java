package com.core.gl;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.opengl.EGL14;
import android.opengl.EGLContext;
import android.view.Surface;
import androidx.annotation.Keep;
import com.core.engine.LaunchContract;

/** Optional host-owned producer for fixed official libraries. No clean-engine callbacks. */
public final class ArtemisOfficialBridge implements ArtemisPerformanceOverlay.Source {
    private static final String TAG = "ArtemisOfficial";
    private static final ArtemisOfficialBridge INSTANCE = new ArtemisOfficialBridge();
    private static final Object SINGLE_LOCK = new Object();
    // Boundary note: SINGLE_LOCK serializes whole renderStage frames against
    // contextDestroyed. A context teardown arriving mid-frame (native hookEglDestroyContext
    // on the same render thread or a concurrent caller) blocks until the in-flight frame
    // leaves the lock, then disposes; disposeSinglePipelineLocked re-reads the current EGL
    // context and abandons (instead of releasing) when the context already changed. The
    // lock never spans frames, so a teardown waits at most one frame of GL work.
    // Deterministic single-context pipeline construction failures (asset/config/size errors)
    // are counted and latched: past this many consecutive failures the filter stays disabled
    // for the process instead of rebuilding and logging on every frame (bypass = safe).
    private static final int MAX_SINGLE_PIPELINE_CONSTRUCTION_FAILURES = 4;
    private static final ArtemisGraphicsStats singleStats = new ArtemisGraphicsStats();
    private static final ArtemisOfficialOutputSize singleOutputSize = new ArtemisOfficialOutputSize();
    private static int singlePipelineConstructionFailures;
    private static Context context;
    private static GraphicsFilterChain chain;
    private static volatile boolean requested, analyzer, singleMode;
    private static volatile int state = GraphicsStatus.WAITING;
    private static volatile ArtemisOfficialSurfaceProxy proxy;
    private static GraphicsFilterPipeline singlePipeline;
    private static EGLContext singlePipelineContext = EGL14.EGL_NO_CONTEXT;
    private static long singlePipelineContextGeneration = -1L;
    // Chain identity of the constructed single-context pipeline (reference comparison):
    // renderStage rebuilds when it differs from the chain prepared for the current launch,
    // so a stale pipeline can never keep rendering with a previous launch's chain.
    private static GraphicsFilterChain singlePipelineChain;
    private static int singleStageWidth, singleStageHeight;
    private final ArtemisGraphicsStats empty = new ArtemisGraphicsStats();
    private ArtemisOfficialBridge() {}

    /** Called before NativeActivity starts its worker. JNI then owns connection changes. */
    public static void prepare(Activity activity) {
        Intent intent = activity.getIntent();
        String library = intent == null ? null : intent.getStringExtra(LaunchContract.ENGINE_LIB_NAME);
        context = activity.getApplicationContext();
        chain = intent == null ? null : GraphicsFilterChain.parse(intent.getStringExtra(LaunchContract.GRAPHICS_FILTER_CHAIN));
        requested = library != null && ArtemisLibraryIdentity.supports("lib" + library + ".so")
                && chain != null && chain.hasActive()
                && intent.getBooleanExtra(LaunchContract.GRAPHICS_FILTER_ENABLED, false);
        analyzer = requested && intent.getBooleanExtra(LaunchContract.GRAPHICS_FILTER_ANALYZER, false);
        state = GraphicsStatus.WAITING;
        singleMode = false;
        synchronized (SINGLE_LOCK) {
            // A new engine launch re-parses the chain: dispose any stale single-context
            // pipeline from the previous launch. Normal teardown clears it via close() /
            // contextDestroyed, but if the previous EGLContext was never observed destroyed
            // (teardown can bypass the engine's PLT hooks), renderStage would otherwise keep
            // rendering with the previous game's chain. abandonContext clears GL names
            // without deletes and is safe off the render thread.
            disposeSinglePipelineLocked(true);
            singlePipelineConstructionFailures = 0;
        }
        singleOutputSize.clear();
        singleStats.reset();
        INSTANCE.empty.reset();
    }
    public static ArtemisPerformanceOverlay.Source source() { return INSTANCE; }
    /** The bootstrap supplies the Build ID from the actually loaded, readable ELF notes. */
    public static boolean verifyLibrary(String path, String buildId) {
        if (!requested) return false;
        boolean verified = ArtemisLibraryIdentity.matches(path, buildId);
        if (!verified) state = GraphicsStatus.NATIVE_FAILED;
        return verified;
    }
    /** Render-thread call; GLES preparation never needs an Activity-thread callback. */
    public static synchronized Surface connect(Surface screen, int width, int height, int producerWidth, int producerHeight) {
        if (!requested || screen == null) return null;
        boolean handedToProxy = false;
        try {
            if (proxy == null) proxy = new ArtemisOfficialSurfaceProxy(context, chain, analyzer);
            handedToProxy = true;
            return proxy.connect(screen, width, height, producerWidth, producerHeight);
        } catch (RuntimeException failure) {
            // Construction can reject a new consumer before connect owns this reference.
            // Once handed over, claim/cancel/release remains entirely the proxy's job.
            if (!handedToProxy) screen.release();
            state = GraphicsStatus.NATIVE_FAILED;
            android.util.Log.w(TAG, "Surface proxy unavailable; keeping direct output", failure);
            // Keep the instance until native confirms its output has disconnected.
            // A timed-out connect job may still be running on the GLES thread.
            return null;
        }
    }
    /** Set the consumer's actual default buffer size before the official resize/reset. */
    public static boolean resizeProducer(int width, int height) {
        ArtemisOfficialSurfaceProxy current = proxy;
        return current != null && current.resizeProducer(width, height);
    }
    public static void submitted(long timestamp, int width, int height, boolean original) {
        ArtemisOfficialSurfaceProxy current = proxy;
        if (current != null) current.submitted(timestamp, width, height, original);
    }
    public static boolean healthy() {
        ArtemisOfficialSurfaceProxy current = proxy;
        return singleMode || (current != null && !current.failed());
    }
    /** No monitor or worker wait: input/render hooks may already own a native surface lock. */
    public static long outputSize() {
        ArtemisOfficialSurfaceProxy current = proxy;
        if (current != null) return current.outputSize();
        return singleMode ? singleOutputSize.get() : 0L;
    }

    /** Single-context GLES3 stage entry: runs filters on the engine render thread. Returns 0 to present texture unchanged. */
    @Keep
    public static int renderStage(int texture, int sw, int sh, int screenW, int screenH,
                                  boolean original, boolean frameDirty, long contextGeneration) {
        if (!requested) {
            // Non-requested invocations must not latch singleMode (analytics/health would
            // then claim a live single-context path that never engaged).
            if (analyzer) singleStats.presented();
            return 0;
        }
        if (!original || texture == 0 || sw <= 0 || sh <= 0 || screenW <= 0 || screenH <= 0) {
            if (singleMode) {
                if (screenW > 0 && screenH > 0) singleOutputSize.presented(screenW, screenH);
                else singleOutputSize.clear();
            }
            // Leaving stage rendering (original == false) must drop any stale held filter
            // timings so direct output does not keep presenting old pass GPU timings.
            if (analyzer) {
                if (!original && singleMode) singleStats.invalidateNodes();
                singleStats.presented();
            }
            return 0;
        }
        // Latch the singleMode claim and record the logical output size only after the
        // stage parameters and the EGL context validate: invalid-parameter or no-current-
        // context calls must neither make analytics/health report a live single-context
        // path nor publish a bogus size.
        EGLContext current = EGL14.eglGetCurrentContext();
        if (current == null || EGL14.EGL_NO_CONTEXT.equals(current)) {
            state = GraphicsStatus.preserveFailure(state, GraphicsStatus.GLES3_UNAVAILABLE);
            if (analyzer) singleStats.presented();
            return 0;
        }
        singleMode = true;
        singleOutputSize.presented(screenW, screenH);
        if (analyzer) singleStats.surface(screenW, screenH);
        synchronized (SINGLE_LOCK) {
            try {
                if (!current.equals(singlePipelineContext)
                        || contextGeneration != singlePipelineContextGeneration) {
                    disposeSinglePipelineLocked(true);
                }
                if (singlePipeline == null && singlePipelineConstructionFailures
                        >= MAX_SINGLE_PIPELINE_CONSTRUCTION_FAILURES) {
                    // Deterministic construction failures exhausted the bounded retry budget:
                    // keep presenting the unfiltered engine texture without per-frame rebuilds
                    // and log storms (in-process disable; bypass direction is safe).
                    if (analyzer) singleStats.presented();
                    // Keep health/analyzer state consistent with the latched bypass: the 4th
                    // construction failure already reported FILTER_FAILED through the catch
                    // path, but a later native status report could otherwise leave the status
                    // showing READY/WAITING while every frame is presented unfiltered, and
                    // the analyzer's held-timing display keys off a non-failure status.
                    state = GraphicsStatus.preserveFailure(state, GraphicsStatus.FILTER_FAILED);
                    return 0;
                }
                if (singlePipeline == null || sw != singleStageWidth || sh != singleStageHeight
                        || chain != singlePipelineChain) {
                    disposeSinglePipelineLocked(false);
                    // Rebuilding for a new stage size or filter chain starts a fresh pipeline lifecycle;
                    // clear stale FILTER_FAILED so a healthy rebuild returns to READY (keep GLES3_UNAVAILABLE/NATIVE_FAILED).
                    if (state == GraphicsStatus.FILTER_FAILED) {
                        state = GraphicsStatus.WAITING;
                    }
                    // Host owns the stage color texture (RGBA8) and depth/stencil renderbuffer (D24S8).
                    long bytes = Math.multiplyExact(Math.multiplyExact((long) sw, sh), 8L);
                    singlePipeline = new GraphicsFilterPipeline(context, chain, bytes);
                    singlePipelineChain = chain;
                    singlePipelineContext = current;
                    singlePipelineContextGeneration = contextGeneration;
                    singleStageWidth = sw;
                    singleStageHeight = sh;
                    singlePipelineConstructionFailures = 0;
                    if (analyzer) singlePipeline.setProfiler(singleStats, true);
                }
                int result = singlePipeline.renderTexture(texture, sw, sh, screenW, screenH, frameDirty);
                int nextState = singlePipeline.isFailed() ? GraphicsStatus.FILTER_FAILED
                        : singlePipeline.bypassReason() != CnnPipeline.BYPASS_NONE
                        ? GraphicsStatus.SIZE_UNAVAILABLE : GraphicsStatus.READY;
                state = GraphicsStatus.preserveFailure(state, nextState);
                if (analyzer) {
                    GraphicsOutputSize.Size fit = GraphicsOutputSize.fit(sw, sh, screenW, screenH);
                    singleStats.frame(sw, sh, singlePipeline.outputWidth(), singlePipeline.outputHeight(),
                            fit == null ? screenW : fit.width, fit == null ? screenH : fit.height);
                    singleStats.gpu(-1);
                    singleStats.presented();
                }
                return result == texture ? 0 : result;
            } catch (RuntimeException failure) {
                // Count failures reached with no pipeline object left behind: they re-enter the
                // construction path on the next frame and would otherwise retry (and log) forever.
                // Asymmetry (SPEC §12): only construction failures feed the 4-strike circuit
                // breaker. A failure with a constructed pipeline keeps the pipeline object;
                // the failed object permanently presents unfiltered output for this context
                // (GraphicsFilterPipeline.failed latches) without consuming construction budget,
                // and a geometry change or context recreation rebuilds from a fresh budget.
                if (singlePipeline == null) {
                    singlePipelineConstructionFailures++;
                    if (singlePipelineConstructionFailures >= MAX_SINGLE_PIPELINE_CONSTRUCTION_FAILURES) {
                        android.util.Log.w(TAG, "Single-context stage filter disabled after "
                                + singlePipelineConstructionFailures + " construction failures", failure);
                    } else {
                        android.util.Log.w(TAG, "Single-context stage filter construction failed", failure);
                    }
                } else {
                    android.util.Log.w(TAG, "Single-context stage filter failed", failure);
                }
                state = GraphicsStatus.preserveFailure(state, GraphicsStatus.FILTER_FAILED);
                if (analyzer) singleStats.presented();
                return 0;
            }
        }
    }

    /** Abandons single-context filter GL resources when native tears down or loses its EGLContext. */
    @Keep
    public static void contextDestroyed() {
        synchronized (SINGLE_LOCK) {
            // Context is already being destroyed or has reported GL_CONTEXT_LOST_KHR:
            // abandon GL names without issuing glDelete* calls on a dead/lost context.
            disposeSinglePipelineLocked(true);
            // Context recreation rebuilds from a fresh budget: deterministic construction
            // failures counted against the torn-down context must not survive its
            // disposal, or a healthy new context would inherit a spent circuit breaker.
            singlePipelineConstructionFailures = 0;
        }
        if (state == GraphicsStatus.FILTER_FAILED) {
            state = GraphicsStatus.WAITING;
        }
        singleMode = false;
        singleOutputSize.clear();
        singleStats.reset();
    }

    private static void disposeSinglePipelineLocked(boolean abandoned) {
        if (singlePipeline != null) {
            EGLContext current = EGL14.EGL_NO_CONTEXT;
            try { current = EGL14.eglGetCurrentContext(); } catch (RuntimeException ignored) {}
            if (abandoned || !singlePipelineContext.equals(current)) singlePipeline.abandonContext();
            else singlePipeline.release();
        }
        singlePipeline = null;
        singlePipelineContext = EGL14.EGL_NO_CONTEXT;
        singlePipelineContextGeneration = -1L;
        singlePipelineChain = null;
        singleStageWidth = 0;
        singleStageHeight = 0;
    }
    /** Native detaches its EGL producer before dropping either Surface reference. */
    public static synchronized int disconnect() {
        ArtemisOfficialSurfaceProxy current = proxy;
        // A detached producer ends any latched single-context claim (no-op for the proxy path,
        // where singleMode is never set).
        singleMode = false;
        return current == null ? ArtemisOfficialDisconnect.DETACHED : current.disconnect();
    }
    public static synchronized void nativeStatus(int status) { state = GraphicsStatus.preserveFailure(state, status); }
    /** Called only after native successfully binds official direct output. */
    public static synchronized void directRestored() {
        ArtemisOfficialSurfaceProxy current = proxy;
        if (current != null) current.directRestored();
        state = GraphicsStatus.preserveFailure(state, GraphicsStatus.WAITING);
    }
    /** Called after NativeActivity.onDestroy joined its worker. */
    public static synchronized void close() {
        if (proxy != null) proxy.release();
        proxy = null; requested = analyzer = false;
        synchronized (SINGLE_LOCK) {
            disposeSinglePipelineLocked(true);
            singlePipelineConstructionFailures = 0;
        }
        singleMode = false;
        singleOutputSize.clear();
        singleStats.reset();
    }
    @Override public ArtemisGraphicsStats stats() {
        ArtemisOfficialSurfaceProxy current = proxy;
        if (current != null) return current.stats();
        return singleMode ? singleStats : empty;
    }
    @Override public boolean isAnalyzing() { return requested && analyzer; }
    @Override public int status() {
        ArtemisOfficialSurfaceProxy current = proxy;
        // Native direct-output/registration failures take precedence over a
        // consumer's last successfully filtered frame.
        return state != GraphicsStatus.WAITING || current == null ? state : current.status();
    }
}
