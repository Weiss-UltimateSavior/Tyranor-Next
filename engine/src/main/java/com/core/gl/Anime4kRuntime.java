package com.core.gl;

/**
 * Anime4K 后处理运行时配置（进程内静态，主线程写 / GL 线程读）。
 *
 * KR2（Kirikiroid2）路径专用：由启动器经 intent extra {@link #EXTRA_MODE} 配置，
 * 必须在 Activity onCreate（GL SurfaceView 构造）之前写入，
 * 因为 EGL 上下文版本（2/3）在 SurfaceView 构造时就要确定。
 */
public final class Anime4kRuntime {

    /** intent extra：画面超分模式，取值见 {@link #configure(String)} */
    public static final String EXTRA_MODE = "tyranor_anime4k";

    // ---- 模式常量（extra 取值） ----
    public static final String MODE_OFF = "off";
    public static final String MODE_S = "s";
    public static final String MODE_M = "m";
    public static final String MODE_L = "l";
    public static final String MODE_SOFT_S = "soft_s";
    public static final String MODE_SOFT_M = "soft_m";
    public static final String MODE_SOFT_L = "soft_l";
    public static final String MODE_DEBLUR = "deblur";

    private static volatile boolean sEnabled = false;
    private static volatile String sShaderAsset = "Anime4K_Restore_CNN_S";

    private Anime4kRuntime() {}

    /**
     * 按 intent extra 值配置。无法识别的值视为关闭。
     * 必须在 GL SurfaceView 构造前调用（决定 EGL 上下文版本）。
     *
     * @param context Activity 上下文（用于设备 GLES3 能力探测，不支持时强制关闭避免黑屏）
     */
    public static void configure(android.content.Context context, String mode) {
        if (mode == null || mode.isEmpty() || MODE_OFF.equals(mode)) {
            sEnabled = false;
            sShaderAsset = "Anime4K_Restore_CNN_S";
            return;
        }
        // 请求 ES3 上下文前确认设备支持（不支持时 EGL 创建失败会黑屏）
        if (context != null) {
            android.app.ActivityManager am = (android.app.ActivityManager)
                    context.getSystemService(android.content.Context.ACTIVITY_SERVICE);
            if (am == null || am.getDeviceConfigurationInfo().reqGlEsVersion < 0x30000) {
                android.util.Log.w("Anime4kRuntime", "device lacks GLES3, Anime4K disabled");
                sEnabled = false;
                return;
            }
        }
        switch (mode) {
            case MODE_S:       sShaderAsset = "Anime4K_Restore_CNN_S"; break;
            case MODE_M:       sShaderAsset = "Anime4K_Restore_CNN_M"; break;
            case MODE_L:       sShaderAsset = "Anime4K_Restore_CNN_L"; break;
            case MODE_SOFT_S:  sShaderAsset = "Anime4K_Restore_CNN_Soft_S"; break;
            case MODE_SOFT_M:  sShaderAsset = "Anime4K_Restore_CNN_Soft_M"; break;
            case MODE_SOFT_L:  sShaderAsset = "Anime4K_Restore_CNN_Soft_L"; break;
            case MODE_DEBLUR:  sShaderAsset = "Anime4K_Deblur_DoG"; break;
            default:
                sEnabled = false;
                return;
        }
        sEnabled = true;
    }

    /** 超分是否启用（决定是否请求 GLES3 上下文与逐帧后处理） */
    public static boolean isEnabled() { return sEnabled; }

    /** 是否需要 GLES3 上下文（CNN 中间值可为负，需 RGBA16F 中间纹理，ES2 无可靠渲染到 fp16 能力） */
    public static boolean useGles3Context() { return sEnabled; }

    /** assets/anime4k/ 下的着色器文件名（不含扩展名） */
    public static String shaderAsset() { return sShaderAsset; }

    /** Activity 销毁时重置，避免同进程下次启动残留 */
    public static void reset() {
        sEnabled = false;
    }
}
