# Preserve JNI/SDL engine entry points used by embedded native engines.
-keep class org.tvp.kirikiri2.** { *; }
# krkrsdl3 links against SDL3.  SDL's JNI_OnLoad resolves these classes and
# callbacks by their literal names, which R8 cannot infer from native code.
# Keep the full surface: native setup is only one of many callbacks looked up
# dynamically (including onNativeSoftReturnKey).
-keep class org.tvp.krkrsdl3.** { *; }
-keep class org.libsdl3.app.** { *; }
-keep class com.yuri.onscripter.** { *; }
-keep class org.libsdl.app.** { *; }
-keep class org.cocos2dx.lib.** { *; }
-keep class bridge.NativeBridge { *; }
-keep class com.akira.tyranoemu.remote.** { *; }
-keep class com.core.fvp.** { *; }
# Artemis 引擎 native（libartemis*.so）通过字符串 FindClass("moe/artemis/gui/Dialog")
# 引用该 Java 桥接类，R8 无法从 native 代码推断，会当作无用代码剥离，
# 导致 Dialog.Show() 不被调用、游戏卡在等待 OnClose 回调。须整体 keep 以防回归。
-keep class moe.artemis.gui.Dialog { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
