#include "artemis_host_class.h"
#include <android/log.h>

namespace {
bool clearException(JNIEnv* env) {
    if (!env || !env->ExceptionCheck()) return false;
    env->ExceptionClear();
    __android_log_print(ANDROID_LOG_ERROR, "ArtemisHost", "Application class loading failed");
    return true;
}
}

// NativeActivity.loadNativeCode runs in the framework loader. Resolve both
// host bridges through the Activity application loader, not JNI FindClass.
jclass loadArtemisHostClass(ANativeActivity* activity, const char* className) {
    if (!activity || !activity->env || !activity->clazz) return nullptr;
    JNIEnv* env = activity->env;
    jclass activity_class = env->GetObjectClass(activity->clazz);
    if (clearException(env) || !activity_class) return nullptr;
    jmethodID get_loader = env->GetMethodID(activity_class, "getClassLoader", "()Ljava/lang/ClassLoader;");
    env->DeleteLocalRef(activity_class);
    if (clearException(env) || !get_loader) return nullptr;
    jobject loader = env->CallObjectMethod(activity->clazz, get_loader);
    if (clearException(env) || !loader) return nullptr;
    jclass loader_class = env->GetObjectClass(loader);
    if (clearException(env) || !loader_class) { env->DeleteLocalRef(loader); return nullptr; }
    jmethodID load = env->GetMethodID(loader_class, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
    env->DeleteLocalRef(loader_class);
    if (clearException(env) || !load) { env->DeleteLocalRef(loader); return nullptr; }
    jstring name = env->NewStringUTF(className);
    if (clearException(env) || !name) { env->DeleteLocalRef(loader); return nullptr; }
    jclass result = static_cast<jclass>(env->CallObjectMethod(loader, load, name));
    env->DeleteLocalRef(name);
    env->DeleteLocalRef(loader);
    if (clearException(env)) return nullptr;
    return result;
}
