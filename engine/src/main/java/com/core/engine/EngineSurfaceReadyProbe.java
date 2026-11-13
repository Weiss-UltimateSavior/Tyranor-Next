package com.core.engine;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.PixelCopy;
import android.view.Surface;

/**
 * Detects whether the current host Surface has queued a copyable buffer. This is a buffer
 * availability signal only; it does not inspect pixels or claim that a game is interactive.
 */
public final class EngineSurfaceReadyProbe {
    public interface SurfaceProvider {
        Surface currentSurface();
        long currentGeneration();
    }

    public interface Listener {
        void onReady(long generation);
        void onFailure(Failure failure);
    }

    public enum Failure { TIMEOUT, SOURCE_INVALID, PIXEL_COPY_ERROR, SURFACE_UNAVAILABLE }

    private static final String TAG = "EngineStartup";
    private static final long PROBE_TIMEOUT_MS = 120_000L;
    private static final long RETRY_DELAY_MS = 100L;
    private static final int DESTINATION_SIZE = 1;

    private final Activity activity;
    private final SurfaceProvider surfaceProvider;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final EngineSurfaceReadyState state = new EngineSurfaceReadyState();
    private final Runnable tick = this::probeOnMain;

    private long remainingTimeoutMs = PROBE_TIMEOUT_MS;
    private long activeSinceMs;
    private boolean terminalFailure;

    public EngineSurfaceReadyProbe(Activity activity, SurfaceProvider surfaceProvider, Listener listener) {
        if (activity == null || surfaceProvider == null || listener == null) {
            throw new IllegalArgumentException("activity, surfaceProvider and listener are required");
        }
        this.activity = activity;
        this.surfaceProvider = surfaceProvider;
        this.listener = listener;
    }

    /** Starts or resumes probing. Time spent between stop() and start() is excluded from timeout. */
    public void start() {
        runOnMain(() -> {
            if (state.isDisposed() || state.isReady() || state.isFailed() || state.isActive()) return;
            long generation = surfaceProvider.currentGeneration();
            state.start(generation);
            activeSinceMs = SystemClock.elapsedRealtime();
            Log.i(TAG, "probe start host=" + hostName() + " sourceGeneration=" + generation);
            main.removeCallbacks(tick);
            main.post(tick);
        });
    }

    /** Pauses timeout accounting and invalidates callbacks from the previous active epoch. */
    public void stop() {
        runOnMain(() -> {
            if (!state.isActive()) return;
            remainingTimeoutMs = remainingTimeoutMs();
            state.stop();
            main.removeCallbacks(tick);
            Log.i(TAG, "probe stop host=" + hostName() + " sourceGeneration=" + state.generation());
        });
    }

    public void dispose() {
        runOnMain(() -> {
            if (state.isDisposed()) return;
            if (state.isActive()) remainingTimeoutMs = remainingTimeoutMs();
            state.dispose();
            main.removeCallbacks(tick);
            Log.i(TAG, "probe dispose host=" + hostName());
        });
    }

    private void probeOnMain() {
        if (!state.isActive() || state.isDisposed() || state.isReady() || state.isFailed()) return;
        long currentGeneration = surfaceProvider.currentGeneration();
        if (state.updateGeneration(currentGeneration)) {
            Log.i(TAG, "probe source generation changed host=" + hostName()
                    + " sourceGeneration=" + currentGeneration);
        }
        if (remainingTimeoutMs() <= 0L) {
            fail(state.hasInFlightRequest() || hasUsableSurface()
                    ? Failure.TIMEOUT : Failure.SURFACE_UNAVAILABLE);
            return;
        }
        if (!state.hasInFlightRequest()) requestCopy(currentGeneration);
        scheduleTick(RETRY_DELAY_MS);
    }

    private void requestCopy(long generation) {
        Surface surface = surfaceProvider.currentSurface();
        if (surface == null || !surface.isValid()) return;
        EngineSurfaceReadyState.Request request = state.beginRequest();
        if (request == null) return;
        Bitmap destination;
        try {
            destination = Bitmap.createBitmap(DESTINATION_SIZE, DESTINATION_SIZE, Bitmap.Config.ARGB_8888);
        } catch (RuntimeException failure) {
            complete(request, surface, generation, PixelCopy.ERROR_DESTINATION_INVALID);
            return;
        }
        try {
            PixelCopy.request(surface, (Rect) null, destination,
                    result -> {
                        try {
                            complete(request, surface, generation, result);
                        } finally {
                            // The bitmap belongs to PixelCopy until its callback, even if stop() invalidated it.
                            if (!destination.isRecycled()) destination.recycle();
                        }
                    }, main);
        } catch (IllegalArgumentException | IllegalStateException failure) {
            if (!destination.isRecycled()) destination.recycle();
            Log.w(TAG, "probe request rejected host=" + hostName()
                    + " sourceGeneration=" + generation, failure);
            complete(request, surface, generation, PixelCopy.ERROR_UNKNOWN);
        }
    }

    private void complete(EngineSurfaceReadyState.Request request, Surface requestedSurface,
                          long requestedGeneration, int pixelCopyResult) {
        if (state.isDisposed()) return;
        long currentGeneration = surfaceProvider.currentGeneration();
        Surface currentSurface = surfaceProvider.currentSurface();
        EngineSurfaceReadyState.CopyResult copyResult = mapResult(pixelCopyResult);

        boolean sourceChanged = currentGeneration != requestedGeneration
                || currentSurface != requestedSurface || !requestedSurface.isValid();
        if (sourceChanged) {
            if (!state.updateGeneration(currentGeneration)) state.invalidateCurrentSource();
            Log.i(TAG, "probe source changed during copy host=" + hostName()
                    + " requestedGeneration=" + requestedGeneration
                    + " currentGeneration=" + currentGeneration);
        }

        // A destroyed/replaced Surface can report SOURCE_INVALID before its owner publishes the
        // next generation. Treat that race as retryable, but never accept its old success.
        if (pixelCopyResult == PixelCopy.ERROR_SOURCE_INVALID && sourceChanged) {
            copyResult = EngineSurfaceReadyState.CopyResult.TRANSIENT_ERROR;
        }
        EngineSurfaceReadyState.Outcome outcome = state.complete(request, currentGeneration, copyResult);
        if (outcome == EngineSurfaceReadyState.Outcome.STALE) {
            Log.i(TAG, "probe stale result ignored host=" + hostName()
                    + " sourceGeneration=" + requestedGeneration + " currentGeneration=" + currentGeneration);
            scheduleTick(0L);
            return;
        }
        if (outcome == EngineSurfaceReadyState.Outcome.READY) {
            main.removeCallbacks(tick);
            Log.i(TAG, "probe buffer available host=" + hostName()
                    + " sourceGeneration=" + requestedGeneration + " evidence=PixelCopy.SUCCESS");
            try { listener.onReady(requestedGeneration); }
            catch (RuntimeException callbackFailure) {
                Log.w(TAG, "probe ready listener failed host=" + hostName(), callbackFailure);
            }
            return;
        }
        if (outcome == EngineSurfaceReadyState.Outcome.FAILURE) {
            Log.w(TAG, "probe copy failure host=" + hostName() + " sourceGeneration="
                    + requestedGeneration + " pixelCopyResult=" + pixelCopyResult);
            fail(pixelCopyResult == PixelCopy.ERROR_SOURCE_INVALID
                    ? Failure.SOURCE_INVALID : Failure.PIXEL_COPY_ERROR);
            return;
        }
        if (pixelCopyResult != PixelCopy.ERROR_SOURCE_NO_DATA && pixelCopyResult != PixelCopy.ERROR_TIMEOUT
                && pixelCopyResult != PixelCopy.ERROR_SOURCE_INVALID) {
            Log.w(TAG, "probe transient copy result host=" + hostName()
                    + " sourceGeneration=" + requestedGeneration + " pixelCopyResult=" + pixelCopyResult);
        }
        scheduleTick(RETRY_DELAY_MS);
    }

    private boolean hasUsableSurface() {
        Surface surface = surfaceProvider.currentSurface();
        return surface != null && surface.isValid();
    }

    private long remainingTimeoutMs() {
        if (!state.isActive()) return remainingTimeoutMs;
        long elapsed = Math.max(0L, SystemClock.elapsedRealtime() - activeSinceMs);
        return Math.max(0L, remainingTimeoutMs - elapsed);
    }

    private void fail(Failure failure) {
        if (terminalFailure || !state.fail()) return;
        terminalFailure = true;
        main.removeCallbacks(tick);
        Log.w(TAG, "probe failure host=" + hostName() + " sourceGeneration="
                + state.generation() + " failure=" + failure + " timeoutMs=" + PROBE_TIMEOUT_MS);
        try { listener.onFailure(failure); }
        catch (RuntimeException callbackFailure) {
            Log.w(TAG, "probe failure listener failed host=" + hostName(), callbackFailure);
        }
    }

    private static EngineSurfaceReadyState.CopyResult mapResult(int result) {
        if (result == PixelCopy.SUCCESS) return EngineSurfaceReadyState.CopyResult.SUCCESS;
        if (result == PixelCopy.ERROR_SOURCE_NO_DATA) return EngineSurfaceReadyState.CopyResult.NO_DATA;
        if (result == PixelCopy.ERROR_TIMEOUT) return EngineSurfaceReadyState.CopyResult.TRANSIENT_ERROR;
        if (result == PixelCopy.ERROR_SOURCE_INVALID) return EngineSurfaceReadyState.CopyResult.SOURCE_INVALID;
        return EngineSurfaceReadyState.CopyResult.PERMANENT_ERROR;
    }

    private String hostName() { return activity.getClass().getSimpleName(); }

    private void scheduleTick(long delayMs) {
        if (!state.isActive() || state.isDisposed() || state.isReady() || state.isFailed()) return;
        main.removeCallbacks(tick);
        main.postDelayed(tick, delayMs);
    }

    private void runOnMain(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) action.run();
        else main.post(action);
    }
}
