package com.core.engine;

import android.app.Activity;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.ViewGroup;

/** Binds one engine host Surface to the shared startup overlay and buffer probe. */
public final class EngineSurfaceStartupController implements SurfaceHolder.Callback {
    private final Activity activity;
    private final Runnable onReady;
    private final boolean probeSurface;
    private volatile SurfaceHolder surfaceHolder;
    private volatile long surfaceGeneration;
    private SurfaceHolder callbackHolder;
    private EngineStartupOverlay overlay;
    private EngineSurfaceReadyProbe probe;
    private boolean resumed;
    private boolean focused;
    private boolean disposed;

    public EngineSurfaceStartupController(Activity activity) {
        this(activity, null);
    }

    public EngineSurfaceStartupController(Activity activity, Runnable onReady) {
        this(activity, onReady, true);
    }

    /**
     * @param probeSurface {@code false} for hosts whose filter path presents through a proxy
     *                     producer Surface; the classic Surface probe would never observe data.
     */
    public EngineSurfaceStartupController(Activity activity, Runnable onReady, boolean probeSurface) {
        if (activity == null) throw new IllegalArgumentException("activity must not be null");
        this.activity = activity;
        this.onReady = onReady;
        this.probeSurface = probeSurface;
    }

    /** Attaches above regular content and tracks the supplied SurfaceHolder lifecycle. */
    public void attachTo(ViewGroup parent, SurfaceHolder holder) {
        if (disposed || parent == null || holder == null) return;
        detachHolderCallback();
        surfaceHolder = holder;
        callbackHolder = holder;
        focused = activity.hasWindowFocus();
        if (holder.getSurface() != null && holder.getSurface().isValid()) surfaceGeneration++;
        holder.addCallback(this);
        start(parent, false);
    }

    /**
     * Attaches the loading overlay without any Surface probe.
     *
     * <p>For hosts whose filter path no longer presents through the engine's own SurfaceView
     * (the texture is redirected to a proxy producer), {@link EngineSurfaceReadyProbe} can only
     * ever observe {@code ERROR_SOURCE_NO_DATA} and would fail the overlay after the 120s
     * timeout. Such hosts signal readiness and failure explicitly via {@link #reveal()} /
     * {@link #fail()} instead.</p>
     */
    public void attachOverlayOnly(ViewGroup parent) {
        if (disposed || parent == null) return;
        detachHolderCallback();
        surfaceHolder = null;
        // 先回收可能存在的探针，否则先 attachTo 再 attachOverlayOnly 的宿主会留下
        // 一个恒 NO_DATA 的探针，120s 后把已揭开的界面重新判成失败。
        releaseProbe();
        // 显式传 false：不能交给 start() 按 probeSurface 重建探针（否则回收动作立刻被撤销）。
        start(parent, false, false);
    }

    /** Attaches as a non-focusable app panel for NativeActivity's taken Surface. */
    public void attachToWindow() {
        if (disposed) return;
        focused = activity.hasWindowFocus();
        start(null, true, probeSurface);
    }

    private void start(ViewGroup parent, boolean asWindow) {
        start(parent, asWindow, probeSurface);
    }

    private void start(ViewGroup parent, boolean asWindow, boolean attachProbe) {
        if (overlay == null) overlay = new EngineStartupOverlay(activity);
        if (asWindow) overlay.attachToWindow();
        else overlay.attachTo(parent);
        if (attachProbe && probe == null) {
            probe = new EngineSurfaceReadyProbe(activity, new EngineSurfaceReadyProbe.SurfaceProvider() {
                @Override public Surface currentSurface() {
                    SurfaceHolder holder = surfaceHolder;
                    if (holder == null) return null;
                    Surface surface = holder.getSurface();
                    return surface != null && surface.isValid() ? surface : null;
                }
                @Override public long currentGeneration() { return surfaceGeneration; }
            }, new EngineSurfaceReadyProbe.Listener() {
                @Override public void onReady(long generation) {
                    if (overlay != null && overlay.isLoading()) overlay.reveal();
                    if (onReady != null) {
                        try { onReady.run(); }
                        catch (RuntimeException failure) {
                            Log.w("EngineStartup", "host ready callback failed", failure);
                        }
                    }
                }
                @Override public void onFailure(EngineSurfaceReadyProbe.Failure failure) {
                    Log.w("EngineStartup", "surface startup failed host="
                            + activity.getClass().getSimpleName() + " reason=" + failure);
                    if (overlay != null && overlay.isLoading()) overlay.fail("");
                }
            });
        }
        updateProbeState();
    }

    /** NativeActivity callback forwarders: call these after the matching superclass callback. */
    public void onNativeSurfaceCreated(SurfaceHolder holder) {
        if (disposed) return;
        surfaceHolder = holder;
        surfaceGeneration++;
    }

    public void onNativeSurfaceChanged(SurfaceHolder holder) {
        if (disposed) return;
        surfaceHolder = holder;
        surfaceGeneration++;
    }

    public void onNativeSurfaceRedrawNeeded(SurfaceHolder holder) {
        if (disposed) return;
        surfaceHolder = holder;
        surfaceGeneration++;
    }

    public void onNativeSurfaceDestroyed(SurfaceHolder holder) {
        if (disposed) return;
        surfaceGeneration++;
        if (surfaceHolder == holder) surfaceHolder = null;
    }

    public void onResume() {
        if (disposed) return;
        resumed = true;
        focused = activity.hasWindowFocus();
        updateProbeState();
    }

    public void onPause() {
        resumed = false;
        updateProbeState();
    }

    public void onWindowFocusChanged(boolean hasFocus) {
        focused = hasFocus;
        updateProbeState();
    }

    public boolean isLoading() { return overlay != null && overlay.isLoading(); }

    /** Explicit readiness for hosts that attach the overlay without a Surface probe.
     *  {@code onReady} 只在「由 loading 态转出」触发一次，重复调用不会重复回调宿主。 */
    public void reveal() {
        if (disposed) return;
        boolean wasLoading = overlay != null && overlay.isLoading();
        if (wasLoading) overlay.reveal();
        if (!wasLoading || onReady == null) return;
        try { onReady.run(); }
        catch (RuntimeException failure) { Log.w("EngineStartup", "host ready callback failed", failure); }
    }

    /** Explicit startup failure for hosts that attach the overlay without a Surface probe. */
    public void fail() {
        if (disposed) return;
        if (overlay != null && overlay.isLoading()) overlay.fail("");
    }

    public void dispose() {
        if (disposed) return;
        disposed = true;
        releaseProbe();
        detachHolderCallback();
        surfaceGeneration++;
        surfaceHolder = null;
        if (overlay != null) {
            overlay.dispose();
            overlay = null;
        }
    }

    private void releaseProbe() {
        if (probe != null) {
            probe.dispose();
            probe = null;
        }
    }

    private void detachHolderCallback() {
        if (callbackHolder != null) callbackHolder.removeCallback(this);
        callbackHolder = null;
    }

    private void updateProbeState() {
        if (probe == null || disposed) return;
        if (resumed && focused && overlay != null && overlay.isLoading()) probe.start();
        else probe.stop();
    }

    @Override public void surfaceCreated(SurfaceHolder holder) {
        if (disposed) return;
        onNativeSurfaceCreated(holder);
    }
    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        if (disposed) return;
        onNativeSurfaceChanged(holder);
    }
    @Override public void surfaceDestroyed(SurfaceHolder holder) {
        if (disposed) return;
        onNativeSurfaceDestroyed(holder);
    }
}
