package com.core.games;

import android.content.Context;

/**
 * JNI 桥：加载自研 shim {@code libgames_bridge.so}，由 shim 再 dlopen 引擎库
 * {@code libsiglus.so}（上游 {@code game_launcher}，同时导出 Siglus 与 framebuffer 两套 ABI）。
 *
 * <p>对应上游 C ABI：{@code game_scan_json / game_probe_json / game_add_font_file /
 * game_fb_open / game_fb_step / game_fb_frame / game_fb_pointer_* / game_fb_wheel /
 * game_fb_key / game_fb_text / game_fb_close}。仅供 RealLive / AVG32 / UK2 三个
 * 软件渲染引擎使用；Siglus 仍走 {@link com.core.siglus.NativeSiglus}。
 */
public final class NativeGames {

    static {
        System.loadLibrary("games_bridge");
    }

    private NativeGames() {}

    /** 鼠标键：与 {@code game_fb_pointer_button} 的 button 参数一致。 */
    public static final int BUTTON_LEFT = 0;
    public static final int BUTTON_RIGHT = 1;

    /** 键码：与 {@code game_fb_key} 的 code 参数一致。 */
    public static final int KEY_ENTER = 1;
    public static final int KEY_ESCAPE = 2;
    public static final int KEY_SPACE = 3;
    public static final int KEY_UP = 4;
    public static final int KEY_DOWN = 5;
    public static final int KEY_LEFT = 6;
    public static final int KEY_RIGHT = 7;
    public static final int KEY_PAGE_UP = 8;
    public static final int KEY_PAGE_DOWN = 9;
    public static final int KEY_HOME = 10;
    public static final int KEY_END = 11;
    public static final int KEY_BACKSPACE = 12;
    public static final int KEY_TAB = 13;
    public static final int KEY_CTRL = 14;
    public static final int KEY_SHIFT = 15;
    public static final int KEY_F_BASE = 0x100;
    public static final int KEY_CHAR_BASE = 0x10000;

    private static volatile boolean sAndroidContextInited = false;

    /**
     * 必须在 {@link #fbOpen} 之前调用：初始化 ndk-context（cpal/AAudio 音频后端依赖），
     * 同时让引擎库初始化自身日志。
     */
    public static synchronized void initAndroidContext(Context ctx) {
        if (sAndroidContextInited || ctx == null) {
            return;
        }
        nativeInitAndroidContext(ctx.getApplicationContext());
        sAndroidContextInited = true;
    }

    private static native void nativeInitAndroidContext(Context appContext);

    /** 扫描目录（深度 {@code depth}）并返回探测结果 JSON 数组；失败返回 null。 */
    public static native String scanJson(String path, int depth, String coverCacheDir);

    /** 探测单个游戏目录并返回 JSON；{@code nls} 可为 null（引擎默认）。 */
    public static native String probeJson(String root, String nls, String coverCacheDir);

    /** 注册额外字体（启动前调用）；成功返回字体 id，失败返回负数。 */
    public static native int addFontFile(String path);

    /**
     * 打开 RealLive / AVG32 / UK2 游戏。
     *
     * @param root      游戏根目录
     * @param engine    引擎 id（{@code reallive/avg32/uk2}）
     * @param nls       文本编码 id（{@code sjis/gbk/big5/utf8/korean/auto}），可为 null
     * @param errorOut  失败时 {@code errorOut[0]} 回填错误信息
     * @return 引擎句柄；失败返回 0
     */
    public static native long fbOpen(String root, String engine, String nls, String[] errorOut);

    /** 推进一帧：0 = 运行中，1 = 游戏结束。 */
    public static native int fbStep(long handle, int dtMs);

    /** 当前帧尺寸 {@code [width, height]}；失败返回 null。 */
    public static native int[] fbFrameSize(long handle);

    /** 把当前帧 RGBA8 拷进直接缓冲区；成功返回 true。 */
    public static native boolean fbCopyFrame(long handle, java.nio.ByteBuffer directBuffer);

    public static native void fbPointerMove(long handle, int x, int y);

    public static native void fbPointerButton(long handle, int button, boolean pressed);

    public static native void fbWheel(long handle, boolean up);

    public static native void fbKey(long handle, int code, boolean pressed);

    /** IME 提交文本。 */
    public static native void fbText(long handle, String text);

    /** 1 = 宿主应显示指针。 */
    public static native int fbCursorVisible(long handle);

    /** 窗口标题；失败返回 null。 */
    public static native String fbTitle(long handle);

    public static native void fbClose(long handle);
}
