#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <cstring>

// krkrsdl3 文本编码桥（POC）。
//
// libkrkr_bridge_v2 被 JNI_OnLoad 约束为只能在 :kirikiri2 进程加载（它要
// FindClass("bridge/NativeBridge")），因此 krkrsdl3 进程不能复用它。这里单独
// 编一个小库，只做 dlopen/dlsym，不依赖任何 engine 模块的 Java 类。

namespace {

constexpr const char* kTag = "KrkrTextEncoding";
constexpr const char* kEngineLib = "libkrkrsdl3.so";
constexpr const char* kSetEncodingSym = "_Z25TVPSetDefaultReadEncodingRKN3TJS10tTJSStringE";

constexpr const char* kStringCtorSyms[] = {
        "_ZN3TJS10tTJSStringC1EPKc",
        "_ZN3TJS10tTJSStringC2EPKc",
};
constexpr const char* kStringDtorSyms[] = {
        "_ZN3TJS10tTJSStringD1Ev",
        "_ZN3TJS10tTJSStringD2Ev",
};

using TjsStringCtor = void (*)(void*, const char*);
using TjsStringDtor = void (*)(void*);
using SetDefaultReadEncoding = void (*)(const void*);

template <typename T>
T resolveSymbol(void* handle, const char* name) {
    return reinterpret_cast<T>(dlsym(handle, name));
}

template <typename T, size_t N>
T resolveFirst(void* handle, const char* const (&names)[N]) {
    for (size_t i = 0; i < N; ++i) {
        if (auto fn = resolveSymbol<T>(handle, names[i])) return fn;
    }
    return nullptr;
}

}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_bridge_KrkrTextEncodingBridge_setDefaultReadEncoding(
        JNIEnv* env, jclass, jstring soName, jstring encoding) {
    if (encoding == nullptr) return JNI_FALSE;
    const char* enc = env->GetStringUTFChars(encoding, nullptr);
    if (enc == nullptr) return JNI_FALSE;
    if (enc[0] == '\0') {
        // 空编码 = 不修改，视为成功。
        env->ReleaseStringUTFChars(encoding, enc);
        return JNI_TRUE;
    }

    char libName[128] = {};
    if (soName != nullptr) {
        const char* raw = env->GetStringUTFChars(soName, nullptr);
        if (raw != nullptr) {
            std::strncpy(libName, raw, sizeof(libName) - 1);
            env->ReleaseStringUTFChars(soName, raw);
        }
    }
    if (libName[0] == '\0') {
        std::strncpy(libName, kEngineLib, sizeof(libName) - 1);
    }

    // 引擎已加载该库时用 RTLD_NOLOAD 只取句柄（不增加引用计数，故不 dlclose）；
    // 否则退化为普通 dlopen。句柄泄漏一次可以接受，绝不能 dlclose 掉引擎在用的库。
    void* handle = dlopen(libName, RTLD_NOW | RTLD_NOLOAD);
    if (handle == nullptr) {
        handle = dlopen(libName, RTLD_NOW);
    }
    if (handle == nullptr) {
        __android_log_print(ANDROID_LOG_WARN, kTag, "dlopen %s failed: %s", libName, dlerror());
        env->ReleaseStringUTFChars(encoding, enc);
        return JNI_FALSE;
    }

    auto setter = resolveSymbol<SetDefaultReadEncoding>(handle, kSetEncodingSym);
    if (setter == nullptr) {
        __android_log_print(ANDROID_LOG_WARN, kTag, "dlsym %s failed", kSetEncodingSym);
        env->ReleaseStringUTFChars(encoding, enc);
        return JNI_FALSE;
    }

    auto ctor = resolveFirst<TjsStringCtor>(handle, kStringCtorSyms);
    auto dtor = resolveFirst<TjsStringDtor>(handle, kStringDtorSyms);
    if (ctor == nullptr || dtor == nullptr) {
        __android_log_print(ANDROID_LOG_WARN, kTag,
                            "tTJSString ctor/dtor not exported (ctor=%p dtor=%p)",
                            reinterpret_cast<void*>(ctor), reinterpret_cast<void*>(dtor));
        env->ReleaseStringUTFChars(encoding, enc);
        return JNI_FALSE;
    }

    // tTJSString 是 TVP 内部类型，仓库内没有对应头文件。用足够大的对齐缓冲区承载，
    // 生命周期完全交给导出的构造/析构函数，避免跨库猜测内存布局。
    alignas(16) unsigned char storage[128] = {};
    bool ok = true;
    try {
        ctor(storage, enc);
        setter(storage);
        dtor(storage);
    } catch (...) {
        ok = false;
    }

    __android_log_print(ok ? ANDROID_LOG_INFO : ANDROID_LOG_WARN, kTag,
                        "setDefaultReadEncoding encoding=%s ok=%d", enc, ok ? 1 : 0);
    env->ReleaseStringUTFChars(encoding, enc);
    return ok ? JNI_TRUE : JNI_FALSE;
}
