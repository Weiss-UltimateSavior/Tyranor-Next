// CMVS NativeActivity bootstrap.
//
// The Java Activity exposes the resolved runtime-pack path.  This loader only
// validates the Activity handshake, dlopen()s the real engine and forwards the
// one native entry point.  Lifecycle callbacks are installed by the engine's
// android-activity runtime during ANativeActivity_onCreate; the loader must not
// touch activity->callbacks or dlclose the engine.
#include <android/log.h>
#include <android/native_activity.h>
#include <dlfcn.h>
#include <jni.h>

#include <cstdio>
#include <cstring>
#include <string>

namespace {

constexpr const char* TAG = "CmvsLoader";
constexpr const char* ERROR_FILE = "launch-error.txt";

void log_error(const char* message) {
    __android_log_print(ANDROID_LOG_ERROR, TAG, "%s", message);
}

std::string internal_data_path(ANativeActivity* activity) {
    return activity != nullptr && activity->internalDataPath != nullptr
            ? std::string(activity->internalDataPath)
            : std::string();
}

void write_error(ANativeActivity* activity, const char* code, const char* detail) {
    const std::string directory = internal_data_path(activity);
    if (directory.empty()) return;
    const std::string path = directory + "/" + ERROR_FILE;
    FILE* file = std::fopen(path.c_str(), "wb");
    if (file == nullptr) return;
    std::fprintf(file, "code=%s\n%s\n", code, detail != nullptr ? detail : "");
    std::fclose(file);
}

void finish(ANativeActivity* activity) {
    if (activity != nullptr) {
        ANativeActivity_finish(activity);
    }
}

bool should_start(ANativeActivity* activity) {
    if (activity == nullptr || activity->env == nullptr || activity->clazz == nullptr) {
        return false;
    }
    JNIEnv* env = activity->env;
    jclass clazz = env->GetObjectClass(activity->clazz);
    if (clazz == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return false;
    }
    jmethodID method = env->GetMethodID(clazz, "shouldStartCmvsNative", "()Z");
    if (method == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        env->DeleteLocalRef(clazz);
        return false;
    }
    const jboolean allowed = env->CallBooleanMethod(activity->clazz, method);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        env->DeleteLocalRef(clazz);
        return false;
    }
    env->DeleteLocalRef(clazz);
    return allowed == JNI_TRUE;
}

std::string resolve_library_path(ANativeActivity* activity) {
    if (activity == nullptr || activity->env == nullptr || activity->clazz == nullptr) {
        return {};
    }
    JNIEnv* env = activity->env;
    jclass clazz = env->GetObjectClass(activity->clazz);
    if (clazz == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return {};
    }
    jmethodID method = env->GetMethodID(
            clazz, "resolveCmvsEngineLibraryPath", "()Ljava/lang/String;");
    if (method == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        env->DeleteLocalRef(clazz);
        return {};
    }
    auto path = static_cast<jstring>(env->CallObjectMethod(activity->clazz, method));
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        env->DeleteLocalRef(clazz);
        return {};
    }
    if (path == nullptr) {
        env->DeleteLocalRef(clazz);
        return {};
    }
    const char* chars = env->GetStringUTFChars(path, nullptr);
    if (chars == nullptr && env->ExceptionCheck()) env->ExceptionClear();
    std::string result = chars != nullptr ? std::string(chars) : std::string();
    if (chars != nullptr) env->ReleaseStringUTFChars(path, chars);
    env->DeleteLocalRef(path);
    env->DeleteLocalRef(clazz);
    return result;
}

bool is_expected_library(const std::string& path) {
    constexpr const char* suffix = "/libcmvs_android.so";
    if (path.size() < std::strlen(suffix)) return false;
    if (path.compare(path.size() - std::strlen(suffix), std::strlen(suffix), suffix) != 0) return false;
    // Only the NativeRuntimePack extraction root is trusted, never arbitrary paths
    // reachable from a malicious Intent or a stale static field.
    return path.find("/engine_runtime/cmvs/") != std::string::npos &&
           path.find("/../") == std::string::npos;
}

using OnCreateFn = void (*)(ANativeActivity*, void*, size_t);

}  // namespace

extern "C" JNIEXPORT void JNICALL
ANativeActivity_onCreate(ANativeActivity* activity, void* savedState, size_t savedStateSize) {
    if (!should_start(activity)) {
        log_error("Activity preflight rejected native startup");
        write_error(activity, "preflight", "Activity preflight rejected engine startup");
        finish(activity);
        return;
    }

    const std::string libraryPath = resolve_library_path(activity);
    if (!is_expected_library(libraryPath)) {
        log_error("invalid or unavailable CMVS engine library path");
        write_error(activity, "library", "CMVS engine runtime is unavailable");
        finish(activity);
        return;
    }

    void* handle = dlopen(libraryPath.c_str(), RTLD_NOW | RTLD_GLOBAL);
    if (handle == nullptr) {
        const char* error = dlerror();
        log_error(error != nullptr ? error : "dlopen failed");
        write_error(activity, "library", error != nullptr ? error : "dlopen failed");
        finish(activity);
        return;
    }

    auto onCreate = reinterpret_cast<OnCreateFn>(dlsym(handle, "ANativeActivity_onCreate"));
    if (onCreate == nullptr) {
        const char* error = dlerror();
        log_error(error != nullptr ? error : "ANativeActivity_onCreate missing");
        write_error(activity, "library", error != nullptr ? error : "engine entry point missing");
        finish(activity);
        return;
    }

    // The engine installs activity->callbacks inside this call.  Do not forward
    // individual lifecycle callbacks; NativeActivity will invoke the engine's
    // callbacks directly after this returns.
    onCreate(activity, savedState, savedStateSize);
}
