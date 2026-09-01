package com.akira.tyranoemu.remote;


import com.core.nativeplugin.NativeLibraryLoader;

/**
 * KRKR 引擎 1.2.6（beta4）启动器。
 *
 * 加载历史版本 libgame126.so（来自吉里吉里模拟器2 v1.2.6 beta4 APK）。
 * 该版本与 1.3.4 类似，不依赖 SDL2；与 1.3.4 相比少两个 JNI 导出：
 *   - Java_org_tvp_kirikiri2_KR2Activity_initDump
 *   - Java_org_tvp_kirikiri2_KR2Activity_nativeOnLowMemory
 * 父类 KR2Activity 已通过 try-catch 兜底，缺失时会降级为空操作，
 * 因此 1.2.6 的 libgame126.so 可以安全加载。
 *
 * 加载顺序与 Kirikiroid134 一致：ffmpeg → game126 → krkr_bridge。
 */
public final class Kirikiroid126 extends KirikiroidLauncherBaseActivity {
    @Override
    public void onLoadNativeLibraries() {
        String gameLibrary = NativeLibraryLoader.loadKirikiroid126(this);
        if (gameLibrary == null) {
            // 与 KR2Activity 契约一致：静默 return 会让 Cocos 宿主带着缺失的 libgame126.so
            // 继续启动，最终在 GL 线程以难以诊断的 UnsatisfiedLinkError 崩溃（见 Kirikiroid139）。
            throw new UnsatisfiedLinkError("Kirikiroid2 plugin missing or invalid for libgame126.so");
        }
        setResolvedGameLibrary(gameLibrary);
        System.loadLibrary("krkr_bridge_v2");
        super.onLoadNativeLibraries();
    }

    @Override
    public String soName() {
        return "libgame126.so";
    }
}
