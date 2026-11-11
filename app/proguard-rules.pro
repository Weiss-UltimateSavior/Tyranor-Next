# ---- kotlinx.serialization ----
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.tyranor.next.**$$serializer { *; }
-keepclassmembers class com.tyranor.next.** {
    *** Companion;
}
-keepclasseswithmembers class com.tyranor.next.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ---- 引擎宿主 Activity（manifest 已引用，兜底保留以防混淆导致启动失败） ----
-keep class com.akira.** { *; }
-keep class com.core.** { *; }
-keep class com.yuri.** { *; }

# ---- Native/JNI bridge and engine runtimes ----
-keep class bridge.** { *; }
-keep class org.tvp.kirikiri2.** { *; }
-keep class org.tvp.krkrsdl3.** { *; }
-keep class org.cocos2dx.** { *; }
-keep class org.libsdl.** { *; }
-keep class org.libsdl3.** { *; }
-keep class com.ies_net.artemis.** { *; }
-keep class moe.artemis.** { *; }
-keep class tv.danmaku.ijk.** { *; }
-keep class com.bytedance.** { *; }
-keep class com.tencent.bugly.** { *; }

# JNI 名称和 native callback 不能被 R8 改写。
-keepclasseswithmembernames class * {
    native <methods>;
}
-keepclassmembers class * {
    native <methods>;
}

# 界面动效「增强」档：运行期替换 Miuix 弹窗展开弹簧（ui/common/motion/MotionMode.kt 的 MiuixPopupMotion），
# 保留类名与静态字段名，反射失败时会静默回退原版行为。
-keepclassmembers class top.yukonga.miuix.kmp.basic.ListPopupDefaults {
    static ** FractionAnimationSpec;
}
-keepnames class top.yukonga.miuix.kmp.basic.ListPopupDefaults
