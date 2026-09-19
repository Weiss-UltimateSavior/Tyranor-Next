package com.core.fvp;

import android.content.Context;
import android.view.Surface;

/**
 * JNI 桥接：驱动 rfvp（FVP 引擎 Rust 运行时）的 Android host-driven C ABI。
 *
 * <p>{@code librfvp_bridge.so} 通过 dlopen 解析 {@code librfvp.so} 导出的
 * {@code rfvp_android_*} 符号；本类的 native 方法即宿主与引擎的唯一契约。
 */
public final class NativeRfvp {

    static {
        System.loadLibrary("rfvp_bridge");
    }

    private NativeRfvp() {}

    /**
     * 初始化 ndk-context（音频后端在 create 时依赖）。必须在 create 之前调用，可重复调用。
     */
    public static native void nativeInitAndroidContext(Context appContext);

    /** 创建引擎实例并绑定 Surface；失败返回 0。 */
    public static native long create(
            Surface surface,
            int widthPx,
            int heightPx,
            double nativeScaleFactor,
            String gameDirUtf8,
            String nlsUtf8
    );

    /** 步进一帧；返回非 0 表示引擎请求退出。 */
    public static native int step(long handle, int dtMs);

    /** Surface 尺寸变化（物理像素）。 */
    public static native void resize(long handle, int widthPx, int heightPx);

    /** 重新绑定 ANativeWindow（SurfaceView 重建时使用）。 */
    public static native void setSurface(long handle, Surface surface, int widthPx, int heightPx);

    /** 注入单指触摸事件（坐标为物理像素）。phase 0/1/2/3 = down/move/up/cancel。 */
    public static native void touch(long handle, int phase, double xPx, double yPx);

    /** 文本高分辨率渲染开关。 */
    public static native void setTextHidpi(long handle, boolean enabled);

    /**
     * 注入按键事件（Windows VK 语义）。keyCode 0x1B = Escape、0x0D = Enter、0x20 = Space、
     * 0x25..0x28 = 方向键、0x11 = Control；phase 0 = down、1 = up。
     */
    public static native void keyEvent(long handle, int keyCode, int phase);

    /** 系统 CJK 字体回退开关（开启后触发一次性系统字体扫描）。 */
    public static native void setSystemFont(long handle, boolean enabled);

    /** 销毁实例。 */
    public static native void destroy(long handle);
}
