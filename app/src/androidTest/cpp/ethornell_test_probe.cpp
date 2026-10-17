// Instrumentation APK only. Uses the exact same versioned C header as production.
#include "ethornell_android.h"
#include <jni.h>
#include <dlfcn.h>
#include <stddef.h>
#include <stdio.h>
#include <string.h>

static_assert(sizeof(EthornellStringV1) == 16);
static_assert(sizeof(EthornellConfigV1) == 88);
static_assert(offsetof(EthornellConfigV1, text_encoding) == 56);
static_assert(sizeof(EthornellInputV1) == 32);
static_assert(sizeof(EthornellEventV1) == 2072);
static_assert(offsetof(EthornellEventV1, text) == 24);
static_assert(sizeof(EthornellResponseV1) == 24);

static jstring probe_library(JNIEnv* env, jstring path) {
    // Path comes only from targetContext.applicationInfo.nativeLibraryDir, not game data.
    const char* name = env->GetStringUTFChars(path, nullptr);
    if (!name) return nullptr;
    void* library = dlopen(name, RTLD_NOW | RTLD_LOCAL);
    env->ReleaseStringUTFChars(path, name);
    char result[8192]{};
    if (!library) {
        snprintf(result, sizeof(result), "FAIL RTLD_NOW: %s", dlerror());
        return env->NewStringUTF(result);
    }
    bool valid = true;
#define CHECK(name) valid = valid && dlsym(library, "ethornell_android_" #name) != nullptr;
    CHECK(abi_version) CHECK(build_info) CHECK(last_error) CHECK(init_context)
    CHECK(create) CHECK(set_surface) CHECK(detach_surface) CHECK(step) CHECK(input)
    CHECK(set_paused) CHECK(request_close) CHECK(poll_event) CHECK(complete_request) CHECK(destroy)
#undef CHECK
    auto abi = reinterpret_cast<decltype(&ethornell_android_abi_version)>(dlsym(library, "ethornell_android_abi_version"));
    auto info = reinterpret_cast<decltype(&ethornell_android_build_info)>(dlsym(library, "ethornell_android_build_info"));
    char build[4096]{};
    int needed = info ? info(nullptr, 0) : -1;
    bool copied = info && needed > 1 && needed <= static_cast<int>(sizeof(build)) && info(build, sizeof(build)) == needed;
    bool terminated = copied && build[needed - 1] == 0 && strlen(build) == static_cast<size_t>(needed - 1);
    snprintf(result, sizeof(result), "%s RTLD_NOW symbols=14 ABI=%u Config=88 Input=32 Event=2072 Response=24 build_info=%s",
             valid && abi && abi() == ETHORNELL_ANDROID_ABI_VERSION && terminated ? "OK" : "FAIL",
             abi ? abi() : 0, build);
    dlclose(library);
    return env->NewStringUTF(result);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_core_ethornell_EthornellDeviceTest_probe(JNIEnv* env, jclass, jstring path) {
    return probe_library(env, path);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_tyranor_next_releaseprobe_EthornellReleaseInstrumentation_probe(JNIEnv* env, jclass, jstring path) {
    return probe_library(env, path);
}
