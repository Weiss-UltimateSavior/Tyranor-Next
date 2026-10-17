package com.core.gl;

import androidx.annotation.Keep;

import java.util.Arrays;

/** Optional non-blocking timer queries, owned by a single pipeline and GL context. */
@Keep
final class GraphicsGpuTimer {
    private static final double NO_NEW_SAMPLE = -2;
    private static final boolean AVAILABLE;
    static {
        boolean loaded;
        try { System.loadLibrary("graphics_timer"); loaded = true; }
        catch (UnsatisfiedLinkError | SecurityException missing) { loaded = false; }
        AVAILABLE = loaded;
    }
    private long handle;
    private final double[] results;
    GraphicsGpuTimer(int count) {
        results = new double[count];
        handle = AVAILABLE ? nativeCreate(count) : 0;
    }
    void poll(ArtemisGraphicsStats stats) {
        if (handle != 0) {
            Arrays.fill(results, NO_NEW_SAMPLE);
            nativePoll(handle, results);
            recordSamples(stats, results);
        }
    }
    static void recordSamples(ArtemisGraphicsStats stats, double[] values) {
        for (int i = 0; i < values.length; i++) {
            if (values[i] == NO_NEW_SAMPLE) continue; // Ring still pending; no new sample.
            stats.nodeGpu(i, values[i]);
        }
    }
    void begin(int index) { if (handle != 0) nativeBegin(handle, index); }
    void end() { if (handle != 0) nativeEnd(handle); }
    void invalidate() { if (handle != 0) nativeInvalidate(handle); }
    void release(boolean current) {
        if (handle != 0) nativeDestroy(handle, current);
        handle = 0;
    }
    private static native long nativeCreate(int count);
    private static native void nativePoll(long handle, double[] values);
    private static native void nativeBegin(long handle, int index);
    private static native void nativeEnd(long handle);
    private static native void nativeInvalidate(long handle);
    private static native void nativeDestroy(long handle, boolean current);
}
