package com.core.ethornell;

import android.content.Context;
import android.view.Surface;

/** JNI transport only. Every call, including loading, belongs to Ethornell-engine. */
public final class NativeEthornell {
    private NativeEthornell() {}
    private static boolean loaded;
    public static synchronized void load() {
        if (!loaded) { System.loadLibrary("ethornell_bridge"); loaded = true; }
    }
    public static native long create(Context application, String resourceRoot, String userRoot,
                                     String fontPath, int encoding);
    public static native void setSurface(long handle, Surface surface, int width, int height, long generation);
    public static native void detachSurface(long handle, long generation);
    public static native void setPaused(long handle, int reasons);
    public static native void input(long handle, int kind, int code, float x, float y, float value);
    public static native void step(long handle, long monotonicTimeNs);
    public static native EthornellSession.Event pollEvent(long handle);
    public static native void completeRequest(long handle, long requestId, int result);
    public static native void requestClose(long handle);
    public static native void destroy(long handle);
}
