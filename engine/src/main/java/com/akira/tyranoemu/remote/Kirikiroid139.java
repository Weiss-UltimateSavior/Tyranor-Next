package com.akira.tyranoemu.remote;


import com.core.nativeplugin.NativeLibraryLoader;

public final class Kirikiroid139 extends KirikiroidLauncherBaseActivity {
    @Override
    public void onLoadNativeLibraries() {
        String gameLibrary = NativeLibraryLoader.loadKirikiroid139(this);
        if (gameLibrary == null) {
            // 与 KR2Activity 契约一致：静默 return 会让 Cocos 宿主带着缺失的 libgame.so
            // 继续启动，最终在 GL 线程以难以诊断的 UnsatisfiedLinkError 崩溃。
            // 提前抛出让失败原因直接出现在 logcat 顶部（插件缺失应在引擎页预检拦截）。
            throw new UnsatisfiedLinkError("Kirikiroid2 plugin missing or invalid for libgame.so");
        }
        setResolvedGameLibrary(gameLibrary);
        System.loadLibrary("krkr_bridge_v2");
        super.onLoadNativeLibraries();
    }

    @Override
    public String soName() {
        return "libgame.so";
    }
}
