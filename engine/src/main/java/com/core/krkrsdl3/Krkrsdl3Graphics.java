package com.core.krkrsdl3;

import android.content.Context;
import android.content.Intent;
import android.opengl.EGL14;
import android.opengl.EGLContext;
import android.util.Log;
import androidx.annotation.Keep;
import com.core.gl.Anime4kPostProcessor;
import com.core.gl.Anime4kRuntime;
import com.core.gl.ArtemisGraphicsStats;
import com.core.gl.ArtemisPerformanceOverlay;
import com.core.gl.GraphicsFilterChain;
import com.core.gl.GraphicsFilterPipeline;
import com.core.gl.GraphicsOutputSize;
import com.core.gl.GraphicsStatus;

/** SDLThread-owned complete-stage adapter for the pinned SDL3 distribution. */
@Keep
public final class Krkrsdl3Graphics implements ArtemisPerformanceOverlay.Source {
    private static final String TAG = "Krkrsdl3Graphics";
    private static final boolean LIBRARY_AVAILABLE;
    static {
        boolean loaded;
        try { System.loadLibrary("krkrsdl3_graphics_bridge"); loaded = true; }
        catch (UnsatisfiedLinkError | SecurityException unavailable) {
            Log.w(TAG, "SDL3 graphics adapter unavailable", unavailable); loaded = false;
        }
        LIBRARY_AVAILABLE = loaded;
    }

    private final Context context;
    private final GraphicsFilterChain chain;
    private final boolean enabled, analyzerRequested;
    private final ArtemisGraphicsStats stats = new ArtemisGraphicsStats();
    private volatile int status;
    private volatile boolean closed;
    // Only accessed on SDLThread, including install and close.
    private boolean attempted;
    private GraphicsFilterPipeline pipeline;
    private int canvasWidth, canvasHeight;
    private EGLContext pipelineContext = EGL14.EGL_NO_CONTEXT;

    public Krkrsdl3Graphics(Context context, Intent intent) {
        this.context = context.getApplicationContext();
        // Reuse the shared protocol and GLES eligibility rules. This host does
        // not ask SDL to change its selected renderer or context attributes.
        Anime4kRuntime.configureFromIntent(this.context, intent);
        enabled = Anime4kRuntime.isEnabled();
        analyzerRequested = enabled && Anime4kRuntime.isAnalyzing();
        chain = Anime4kRuntime.chain();
        status = Anime4kRuntime.status();
    }

    /** Called immediately before SDLActivity.nativeRunMain, on SDLThread. */
    public void install() {
        if (closed || !enabled || attempted) return;
        attempted = true;
        if (!LIBRARY_AVAILABLE) { status = GraphicsStatus.MISSING_ABI; return; }
        try {
            if (!nativeInstall() && status == GraphicsStatus.WAITING) status = GraphicsStatus.MISSING_ABI;
        } catch (UnsatisfiedLinkError unavailable) {
            status = GraphicsStatus.MISSING_ABI;
            Log.w(TAG, "SDL3 graphics registration unavailable", unavailable);
        }
    }

    /** Called by native only after the complete Underlay/main/Overlay stage. */
    @Keep
    private int renderStage(int texture, int mainWidth, int mainHeight, int width, int height,
                            int screenWidth, int screenHeight, int padLeft, int padBottom, boolean cropMain) {
        // Zero asks native to present the complete, unfiltered canvas. It must
        // not interpret that canvas texture as a successfully cropped main.
        if (closed || !enabled || texture == 0) return 0;
        EGLContext current = EGL14.eglGetCurrentContext();
        if (current == null || EGL14.EGL_NO_CONTEXT.equals(current)) {
            nativeStatus(GraphicsStatus.GLES3_UNAVAILABLE); return 0;
        }
        try {
            if (!current.equals(pipelineContext)) contextChanged(true);
            if (pipeline == null || width != canvasWidth || height != canvasHeight) {
                disposePipeline(false);
                // The complete display canvas includes letterbox content at
                // the main source's pixel scale. Reserve RGBA8 plus D24S8;
                // renderCroppedTexture adds its exact main-crop storage itself.
                long bytes = Math.multiplyExact(Math.multiplyExact((long) width, height), 8L);
                pipeline = new GraphicsFilterPipeline(context, chain, bytes);
                pipelineContext = current;
                canvasWidth = width; canvasHeight = height;
                if (analyzerRequested) pipeline.setProfiler(stats, true);
            }
            int inputWidth = cropMain ? mainWidth : width, inputHeight = cropMain ? mainHeight : height;
            float u0 = (float) padLeft / width, v0 = (float) padBottom / height;
            float u1 = (float) (padLeft + mainWidth) / width, v1 = (float) (padBottom + mainHeight) / height;
            if (cropMain && !exactCrop(mainWidth, mainHeight, width, height, padLeft, padBottom, u0, v0, u1, v1)) {
                nativeStatus(GraphicsStatus.SIZE_UNAVAILABLE); return 0;
            }
            int result = cropMain
                    ? pipeline.renderCroppedTexture(texture, mainWidth, mainHeight,
                            u0, v0, u1, v1,
                            screenWidth, screenHeight)
                    : pipeline.renderTexture(texture, width, height, screenWidth, screenHeight);
            status = pipeline.isFailed() ? GraphicsStatus.FILTER_FAILED
                    : pipeline.bypassReason() != Anime4kPostProcessor.BYPASS_NONE
                    ? GraphicsStatus.SIZE_UNAVAILABLE : GraphicsStatus.READY;
            if (analyzerRequested) {
                GraphicsOutputSize.Size fit = cropMain
                        ? GraphicsOutputSize.fit(mainWidth, mainHeight, screenWidth, screenHeight) : null;
                stats.surface(screenWidth, screenHeight);
                // Dynamic overlays outside the main fit are processed as the
                // full canvas; never label that input with the main's dimensions.
                stats.frame(inputWidth, inputHeight, pipeline.outputWidth(), pipeline.outputHeight(),
                        cropMain ? (fit == null ? 0 : fit.width) : screenWidth,
                        cropMain ? (fit == null ? 0 : fit.height) : screenHeight);
                stats.gpu(-1); stats.presented();
            }
            return result;
        } catch (RuntimeException failure) {
            nativeStatus(GraphicsStatus.FILTER_FAILED);
            Log.w(TAG, "Complete-stage filter failed", failure);
            return 0;
        }
    }

    private static boolean exactCrop(int mainWidth, int mainHeight, int width, int height,
                                     int left, int bottom, float u0, float v0, float u1, float v1) {
        // The shared nearest-copy adapter reconstructs these integers from UVs.
        // Prove its result rather than allowing float cancellation on an
        // extremely narrow source surrounded by a very large display canvas.
        int inferredWidth = Math.round(mainWidth / (u1 - u0));
        int inferredHeight = Math.round(mainHeight / (v1 - v0));
        return inferredWidth == width && inferredHeight == height
                && Math.round(u0 * inferredWidth) == left && Math.round(v0 * inferredHeight) == bottom;
    }

    /** Native proves context generations, including recycled EGL handles. */
    @Keep
    private void contextChanged(boolean abandoned) {
        disposePipeline(abandoned);
        stats.reset();
        if (!closed) status = GraphicsStatus.WAITING;
    }

    @Keep
    private void nativeStatus(int next) {
        if (closed) return;
        status = next;
        if (analyzerRequested && next != GraphicsStatus.READY && next != GraphicsStatus.WAITING) {
            // An unverified stage must never retain a previous frame's raw-size
            // claim or GPU samples in the analyzer.
            stats.frame(0, 0, 0, 0, 0, 0);
            stats.invalidateNodes(); stats.gpu(-1);
        }
    }

    private void disposePipeline(boolean abandoned) {
        if (pipeline != null) {
            EGLContext current = EGL14.eglGetCurrentContext();
            if (abandoned || !pipelineContext.equals(current)) pipeline.abandonContext();
            else pipeline.release();
        }
        pipeline = null; pipelineContext = EGL14.EGL_NO_CONTEXT;
        canvasWidth = canvasHeight = 0;
    }

    /** SDLThread finally block, after native main and all engine rendering stop. */
    public void close() {
        if (closed) return;
        // nativeClose rechecks context generations before deleting any GL names.
        // Pipeline destruction is routed through the native context callback
        // below so Java names obey the same generation proof.
        if (attempted && LIBRARY_AVAILABLE) {
            try { nativeClose(); }
            catch (UnsatisfiedLinkError unavailable) { Log.w(TAG, "SDL3 graphics cleanup unavailable", unavailable); }
        }
        disposePipeline(true);
        closed = true;
        stats.reset();
        Anime4kRuntime.reset();
    }

    @Override public ArtemisGraphicsStats stats() { return stats; }
    @Override public boolean isAnalyzing() { return analyzerRequested && !closed; }
    @Override public int status() { return status; }

    @Keep private native boolean nativeInstall();
    @Keep private native void nativeClose();
}
