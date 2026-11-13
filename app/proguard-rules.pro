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
