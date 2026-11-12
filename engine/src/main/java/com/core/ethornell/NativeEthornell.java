package com.core.ethornell;

import android.content.Context;
import android.view.Surface;

import com.core.nativeplugin.NativeRuntimePack;

/** JNI transport only. Every call, including loading, belongs to Ethornell-engine. */
public final class NativeEthornell {
    private NativeEthornell() {}
    private static boolean loaded;
    public static synchronized void load() {
        if (!loaded) { System.loadLibrary("ethornell_bridge"); loaded = true; }
    }

    /** 引擎运行库绝对路径（首启从 assets 解压）；必须在 create 之前设置。 */
    public static native void nativeSetEngineLibraryPath(String path);

    /**
     * 绑定已解压的 {@code libethornell.so}。返回 false 表示运行库未就绪，
     * 调用方按引擎初始化失败处理。
     */
    public static boolean prepareEngineLibrary(Context context) {
        String path = NativeRuntimePack.libraryPath(context, NativeRuntimePack.PACK_ETHORNELL, "libethornell.so");
        if (path == null) {
            return false;
        }
        load();
        nativeSetEngineLibraryPath(path);
        return true;
    }

    public static native long create(Context application, String resourceRoot, String userRoot,
                                     String fontPath, int encoding);
    public static native void setSurface(long handle, Surface surface, int width, int height, long generation);
    public static native void detachSurface(long handle, long generation);
    public static native void setPaused(long handle, int reasons);
    public static native void input(long handle, int kind, int code, float x, float y, float value);
    public static native void step(long handle, long monotonicTimeNs);
    public static native EthornellSession.Event pollEvent(long handle);
    /** Owner worker only: full JSON payload for a kind-5 dialog request; null if stale/unsupported. */
    public static native String requestPayload(long handle, long requestId);
    /** Empty text answers as an integer; non-empty text carries the kind-5 JSON answer. */
    public static native void completeRequest(long handle, long requestId, int result, String text);
    public static void completeRequest(long handle, long requestId, int result) {
        completeRequest(handle, requestId, result, "");
    }
    public static native void requestClose(long handle);
    public static native void destroy(long handle);
}
