// artemis_loader.cpp
// Artemis 外置 native 插件 bootstrap 加载器。
//
// 背景：Artemis 引擎走 NativeActivity，其真实运行库由 manifest 的
// `android.app.lib_name` 指定，系统在 super.onCreate() 内从 APK 解压目录
// dlopen 并调用 ANativeActivity_onCreate。真实 so 外置成 zip 插件后不再随 APK
// 打包，故用一个内置的极小白名单 so（本文件，仅依赖 libdl/liblog）承担 lib_name，
// 在 ANativeActivity_onCreate 里从 intent 读取 engineLibName，dlopen 插件目录下
// 对应的真实 so（RTLD_GLOBAL 使符号进全局表，供 artemis_audio_bridge 的 dlsym 命中），
// 再把系统传入的 ANativeActivity* 原样转发给真实 so 的 ANativeActivity_onCreate。

#include <jni.h>
#include <android/native_activity.h>
#include <dlfcn.h>
#include <android/log.h>

#include <string>

#define TAG "ArtemisLoader"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

typedef void (*OnCreateFn)(ANativeActivity*, void*, size_t);

std::string getEngineLibName(JNIEnv* env, ANativeActivity* activity) {
    jclass activityCls = env->GetObjectClass(activity->clazz);
    if (activityCls == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return {};
    }
    jmethodID getIntent = env->GetMethodID(
            activityCls, "getIntent", "()Landroid/content/Intent;");
    if (getIntent == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return {};
    }
    jobject intent = env->CallObjectMethod(activity->clazz, getIntent);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return {};
    }
    if (intent == nullptr) return {};
    jclass intentCls = env->GetObjectClass(intent);
    jmethodID getStringExtra = env->GetMethodID(
            intentCls, "getStringExtra", "(Ljava/lang/String;)Ljava/lang/String;");
    if (getStringExtra == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return {};
    }
    jstring key = env->NewStringUTF("engineLibName");
    jstring value = static_cast<jstring>(env->CallObjectMethod(intent, getStringExtra, key));
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return {};
    }
    if (value == nullptr) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    std::string result = chars != nullptr ? chars : "";
    if (chars != nullptr) env->ReleaseStringUTFChars(value, chars);
    return result;
}

std::string getFilesDir(JNIEnv* env, ANativeActivity* activity) {
    jclass activityCls = env->GetObjectClass(activity->clazz);
    if (activityCls == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return {};
    }
    jmethodID getFilesDir = env->GetMethodID(
            activityCls, "getFilesDir", "()Ljava/io/File;");
    if (getFilesDir == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return {};
    }
    jobject file = env->CallObjectMethod(activity->clazz, getFilesDir);
    if (env->ExceptionCheck() || file == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return {};
    }
    jclass fileCls = env->GetObjectClass(file);
    jmethodID getPath = env->GetMethodID(fileCls, "getPath", "()Ljava/lang/String;");
    if (getPath == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return {};
    }
    jstring path = static_cast<jstring>(env->CallObjectMethod(file, getPath));
    if (env->ExceptionCheck() || path == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return {};
    }
    const char* chars = env->GetStringUTFChars(path, nullptr);
    std::string result = chars != nullptr ? chars : "";
    if (chars != nullptr) env->ReleaseStringUTFChars(path, chars);
    return result;
}

void finishActivity(ANativeActivity* activity) {
    ANativeActivity_finish(activity);
}

// engineLibName 硬白名单：只允许已知 revision 库名，杜绝路径穿越。
// 这是 C++ 层的最后防线，绝不信任 intent extra 上游字符串。
bool isAllowedEngineLibName(const std::string& name) {
    return name == "artemis"
        || name == "artemis-compatible"
        || name == "artemis-compatible-v2"
        || name == "artemis-v4";
}

}  // namespace

extern "C" JNIEXPORT void JNICALL
ANativeActivity_onCreate(ANativeActivity* activity, void* savedState, size_t savedStateSize) {
    const std::string engineLibName = getEngineLibName(activity->env, activity);
    if (engineLibName.empty()) {
        LOGE("Artemis plugin: engineLibName intent extra missing; aborting startup");
        finishActivity(activity);
        return;
    }
    if (!isAllowedEngineLibName(engineLibName)) {
        LOGE("Artemis plugin: invalid engineLibName '%s'; aborting startup",
             engineLibName.c_str());
        finishActivity(activity);
        return;
    }

    const std::string filesDir = getFilesDir(activity->env, activity);
    if (filesDir.empty()) {
        LOGE("Artemis plugin: cannot resolve filesDir; aborting startup");
        finishActivity(activity);
        return;
    }

    // 插件目录约定：<filesDir>/engine_plugins/artemis/current/arm64-v8a/lib<engineLibName>.so
    // engineLibName 已通过白名单校验，此处拼接是安全的。
    const std::string libPath =
        filesDir + "/engine_plugins/artemis/current/arm64-v8a/lib" + engineLibName + ".so";
    LOGI("Artemis plugin: dlopen %s", libPath.c_str());

    void* handle = dlopen(libPath.c_str(), RTLD_NOW | RTLD_GLOBAL);
    if (handle == nullptr) {
        LOGE("Artemis plugin: dlopen failed for %s: %s", libPath.c_str(), dlerror());
        finishActivity(activity);
        return;
    }

    OnCreateFn onCreate = reinterpret_cast<OnCreateFn>(
            dlsym(handle, "ANativeActivity_onCreate"));
    if (onCreate == nullptr) {
        LOGE("Artemis plugin: ANativeActivity_onCreate not found in %s: %s",
             libPath.c_str(), dlerror());
        finishActivity(activity);
        return;
    }

    LOGI("Artemis plugin: forwarding ANativeActivity_onCreate to %s", libPath.c_str());
    onCreate(activity, savedState, savedStateSize);
}
