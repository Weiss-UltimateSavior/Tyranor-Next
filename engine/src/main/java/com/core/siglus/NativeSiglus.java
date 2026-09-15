package com.core.siglus;

import android.content.Context;
import android.view.Surface;

/**
 * SiglusEngine（siglus_rs）宿主 JNI 桥：加载自研 shim {@code libsiglus_bridge.so}，
 * 由 shim 再 dlopen 官方引擎库 {@code libsiglus.so}（随 APK jniLibs 分发）。
 *
 * <p>函数签名与 {@code siglus_android_*} C ABI 对齐，禁止在此之外新增 JNI 入口。
 */
public final class NativeSiglus {

    static {
        System.loadLibrary("siglus_bridge");
    }

    private NativeSiglus() {}

    /** 引擎线程回调的宿主实现（由 {@link SiglusActivity} 注册）。 */
    public interface MessageboxListener {
        void onMessagebox(long handle, long requestId, int kind, String title, String message);
    }

    private static volatile MessageboxListener sMessageboxListener;

    public static void setMessageboxListener(MessageboxListener listener) {
        sMessageboxListener = listener;
    }

    /** 由 libsiglus_bridge.so 调用（引擎线程）。 */
    @SuppressWarnings("unused")
    public static void dispatchMessagebox(long handle, long requestId, int kind, String title, String message) {
        MessageboxListener listener = sMessageboxListener;
        if (listener != null) {
            listener.onMessagebox(handle, requestId, kind, title, message);
        }
    }

    /** 必须在 create 之前调用（音频后端依赖 ndk-context）。 */
    public static native void nativeInitContext(Context appContext);

    /**
     * 设置 {@code SIGLUS_LANGUAGE} 环境变量（必须在 create 之前）。传 null/空串保持引擎默认（JP）。
     */
    public static native void nativeSetLanguage(String language);

    public static native long create(Surface surface, int widthPx, int heightPx, double nativeScale, String gameDir);

    public static native void setNativeMessageboxCallback(long handle);

    public static native void submitMessageboxResult(long handle, long requestId, long value);

    /** 推进一帧：&gt;0 游戏正常退出；&lt;0 VM 错误；0 继续。 */
    public static native int step(long handle, int dtMs);

    public static native void resize(long handle, int widthPx, int heightPx);

    /** 后台恢复时重挂 ANativeWindow；失败返回 false。 */
    public static native boolean setSurface(long handle, Surface surface, int widthPx, int heightPx);

    public static native void touch(long handle, int phase, double xPx, double yPx);

    /** 键事件（keyCode 为 Windows VK；0 且 text 非空表示未映射键的文本）。 */
    public static native void keyEvent(long handle, int keyCode, String text, boolean isRepeat);

    public static native void textInput(long handle, String text);

    public static native void imePreedit(long handle, String text, int cursorStart, int cursorEnd);

    /** 查询是否需要软键盘；返回 true 时 outXywh 填入光标区域（surface 像素）。 */
    public static native boolean imeArea(long handle, int[] outXywh);

    /** Gameexe GAMENAME（失败返回 null）——标题回写用。 */
    public static native String gameNameFromDir(String gameDir);

    public static native void destroy(long handle);
}
