package com.akira.tyranoemu.remote;


import com.core.nativeplugin.NativeLibraryLoader;

public final class Kirikiroid134 extends KirikiroidLauncherBaseActivity {
    @Override
    public void onLoadNativeLibraries() {
        String gameLibrary = NativeLibraryLoader.loadKirikiroid134(this);
        if (gameLibrary == null) {
            // 与 KR2Activity 契约一致：静默 return 会让 Cocos 宿主带着缺失的 libgame134.so
            // 继续启动，最终在 GL 线程以难以诊断的 UnsatisfiedLinkError 崩溃（见 Kirikiroid139）。
            throw new UnsatisfiedLinkError("Kirikiroid2 plugin missing or invalid for libgame134.so");
        }
        setResolvedGameLibrary(gameLibrary);
        System.loadLibrary("krkr_bridge_v2");
        super.onLoadNativeLibraries();
    }

    @Override
    public String soName() {
        return "libgame134.so";
    }
}
