package com.core.siglus;

/** Optional downstream C ABI; old engine libraries continue through NativeSiglus. */
final class SiglusStageBridge {
    private static boolean available;
    static {
        try {
            System.loadLibrary("siglus_bridge");
            available = true;
        } catch (UnsatisfiedLinkError failure) {
            android.util.Log.w("SiglusStageBridge", "Optional stage bridge unavailable", failure);
        }
    }
    private SiglusStageBridge() {}

    /** logical W/H, configured Surface W/H, viewport X/Y/W/H, render serial low/high. Engine thread only. */
    static boolean geometry(long handle, int[] outGeometry) {
        if (!available) return false;
        try {
            return geometryNative(handle, outGeometry);
        } catch (UnsatisfiedLinkError failure) {
            available = false;
            android.util.Log.w("SiglusStageBridge", "Optional stage geometry ABI unavailable", failure);
            return false;
        }
    }

    private static native boolean geometryNative(long handle, int[] outGeometry);
}
