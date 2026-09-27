#include <jni.h>
#include <android/native_window_jni.h>
#include <android/log.h>
#include <dlfcn.h>

#include <cstdint>
#include <mutex>
#include <string>
#include <unordered_map>

#define LOG_TAG "rfvp_bridge"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

// ---------------------------------------------------------------------------
// rfvp_android_* C ABI (see rfvp: crates/rfvp/src/android_host.rs)
// ---------------------------------------------------------------------------

using init_context_fn_t = void (*)(void* java_vm_ptr, void* app_context_global_ref);
using create_fn_t = void* (*)(void* native_window_ptr,
                              uint32_t w_px,
                              uint32_t h_px,
                              double scale,
                              const char* game_dir_utf8,
                              const char* nls_utf8);
using step_fn_t = int32_t (*)(void* handle, uint32_t dt_ms);
using resize_fn_t = void (*)(void* handle, uint32_t w_px, uint32_t h_px);
using set_surface_fn_t = void (*)(void* handle, void* native_window_ptr, uint32_t w_px, uint32_t h_px);
using touch_fn_t = void (*)(void* handle, int32_t phase, double x_px, double y_px);
using set_text_hidpi_fn_t = void (*)(void* handle, int32_t enabled);
using key_fn_t = void (*)(void* handle, int32_t vk_code, int32_t phase);
using set_system_font_fn_t = void (*)(void* handle, int32_t enabled);
using add_font_fn_t = int32_t (*)(void* handle, const char* font_path_utf8);
using set_forced_font_fn_t = void (*)(void* handle, int32_t font_id);
using destroy_fn_t = void (*)(void* handle);

struct RfvpApi {
    init_context_fn_t init_context = nullptr;
    create_fn_t create = nullptr;
    step_fn_t step = nullptr;
    resize_fn_t resize = nullptr;
    set_surface_fn_t set_surface = nullptr;
    touch_fn_t touch = nullptr;
    set_text_hidpi_fn_t set_text_hidpi = nullptr;
    key_fn_t key = nullptr;
    set_system_font_fn_t set_system_font = nullptr;
    add_font_fn_t add_font = nullptr;
    set_forced_font_fn_t set_forced_font = nullptr;
    destroy_fn_t destroy = nullptr;
};

static RfvpApi g_api;
static std::once_flag g_api_once;
static void* g_lib_handle = nullptr;

static JavaVM* g_java_vm = nullptr;

static void* load_symbol(const char* sym) {
    void* p = dlsym(g_lib_handle, sym);
    if (!p) {
        LOGE("dlsym failed: %s (%s)", sym, dlerror());
    }
    return p;
}

static void load_api_once() {
    std::call_once(g_api_once, []() {
        g_lib_handle = dlopen("librfvp.so", RTLD_NOW);
        if (!g_lib_handle) {
            LOGE("dlopen librfvp.so failed: %s", dlerror());
            return;
        }
        g_api.init_context = reinterpret_cast<init_context_fn_t>(load_symbol("rfvp_android_init_context"));
        g_api.create = reinterpret_cast<create_fn_t>(load_symbol("rfvp_android_create"));
        g_api.step = reinterpret_cast<step_fn_t>(load_symbol("rfvp_android_step"));
        g_api.resize = reinterpret_cast<resize_fn_t>(load_symbol("rfvp_android_resize"));
        g_api.set_surface = reinterpret_cast<set_surface_fn_t>(load_symbol("rfvp_android_set_surface"));
        g_api.touch = reinterpret_cast<touch_fn_t>(load_symbol("rfvp_android_touch"));
        g_api.set_text_hidpi = reinterpret_cast<set_text_hidpi_fn_t>(load_symbol("rfvp_android_set_text_hidpi"));
        g_api.key = reinterpret_cast<key_fn_t>(load_symbol("rfvp_android_key"));
        g_api.set_system_font = reinterpret_cast<set_system_font_fn_t>(load_symbol("rfvp_android_set_system_font"));
        g_api.add_font = reinterpret_cast<add_font_fn_t>(load_symbol("rfvp_android_add_font"));
        g_api.set_forced_font = reinterpret_cast<set_forced_font_fn_t>(load_symbol("rfvp_android_set_forced_font"));
        g_api.destroy = reinterpret_cast<destroy_fn_t>(load_symbol("rfvp_android_destroy"));

        bool core_ok = g_api.create && g_api.step && g_api.resize && g_api.set_surface &&
                       g_api.touch && g_api.destroy && g_api.init_context;
        if (!core_ok) {
            LOGE("missing one or more required rfvp_android_* symbols");
        }
        if (!g_api.key || !g_api.set_system_font) {
            LOGW("rfvp_android_key/set_system_font missing; back key and system font fallback stay disabled");
        }
        if (!g_api.add_font || !g_api.set_forced_font) {
            LOGW("rfvp_android_add_font/set_forced_font missing; custom font stays disabled");
        }
        LOGI("rfvp_android_* symbols resolved (core=%d)", core_ok ? 1 : 0);
    });
}

static std::string jstring_to_string(JNIEnv* env, jstring value) {
    if (value == nullptr) {
        return std::string();
    }
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) {
        return std::string();
    }
    std::string out(chars);
    env->ReleaseStringUTFChars(value, chars);
    return out;
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    g_java_vm = vm;
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT void JNICALL
Java_com_core_fvp_NativeRfvp_nativeInitAndroidContext(JNIEnv* env, jclass, jobject app_context) {
    load_api_once();
    if (g_api.init_context == nullptr) {
        return;
    }
    if (app_context == nullptr) {
        LOGW("nativeInitAndroidContext: null context");
        return;
    }
    // ndk-context expects a GlobalRef that stays valid for the process lifetime.
    jobject global = env->NewGlobalRef(app_context);
    if (global == nullptr) {
        LOGE("nativeInitAndroidContext: NewGlobalRef failed");
        return;
    }
    g_api.init_context(reinterpret_cast<void*>(g_java_vm), reinterpret_cast<void*>(global));
    LOGI("ndk-context initialized");
}

// Keep one ANativeWindow ref per engine handle so the pointer stays valid while Rust uses it.
static std::mutex g_window_mu;
static std::unordered_map<jlong, ANativeWindow*> g_windows;

static void release_window_locked(jlong key) {
    auto it = g_windows.find(key);
    if (it != g_windows.end()) {
        if (it->second != nullptr) {
            ANativeWindow_release(it->second);
        }
        g_windows.erase(it);
    }
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_core_fvp_NativeRfvp_create(JNIEnv* env, jclass, jobject surface, jint width_px,
                                    jint height_px, jdouble native_scale, jstring game_dir,
                                    jstring nls) {
    load_api_once();
    if (g_api.create == nullptr) {
        LOGE("create: engine symbols unavailable");
        return 0;
    }
    if (surface == nullptr) {
        LOGE("create: surface is null");
        return 0;
    }
    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (window == nullptr) {
        LOGE("create: ANativeWindow_fromSurface failed");
        return 0;
    }
    std::string dir = jstring_to_string(env, game_dir);
    if (dir.empty()) {
        LOGE("create: empty game dir");
        ANativeWindow_release(window);
        return 0;
    }
    std::string nls_value = jstring_to_string(env, nls);
    void* handle = g_api.create(window, static_cast<uint32_t>(width_px > 0 ? width_px : 1),
                                static_cast<uint32_t>(height_px > 0 ? height_px : 1),
                                native_scale, dir.c_str(),
                                nls_value.empty() ? nullptr : nls_value.c_str());
    if (handle == nullptr) {
        LOGE("create: engine returned null handle");
        ANativeWindow_release(window);
        return 0;
    }
    jlong key = reinterpret_cast<jlong>(handle);
    {
        std::lock_guard<std::mutex> lock(g_window_mu);
        release_window_locked(key);
        g_windows.emplace(key, window);
    }
    return key;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_core_fvp_NativeRfvp_step(JNIEnv*, jclass, jlong handle, jint dt_ms) {
    if (g_api.step == nullptr || handle == 0) {
        return 1;
    }
    return g_api.step(reinterpret_cast<void*>(handle),
                      static_cast<uint32_t>(dt_ms > 0 ? dt_ms : 0));
}

extern "C" JNIEXPORT void JNICALL
Java_com_core_fvp_NativeRfvp_resize(JNIEnv*, jclass, jlong handle, jint width_px, jint height_px) {
    if (g_api.resize == nullptr || handle == 0) {
        return;
    }
    g_api.resize(reinterpret_cast<void*>(handle),
                 static_cast<uint32_t>(width_px > 0 ? width_px : 1),
                 static_cast<uint32_t>(height_px > 0 ? height_px : 1));
}

extern "C" JNIEXPORT void JNICALL
Java_com_core_fvp_NativeRfvp_setSurface(JNIEnv* env, jclass, jlong handle, jobject surface,
                                        jint width_px, jint height_px) {
    if (g_api.set_surface == nullptr || handle == 0) {
        return;
    }
    if (surface == nullptr) {
        LOGW("setSurface: surface is null (ignored)");
        return;
    }
    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (window == nullptr) {
        LOGE("setSurface: ANativeWindow_fromSurface failed");
        return;
    }
    g_api.set_surface(reinterpret_cast<void*>(handle), window,
                      static_cast<uint32_t>(width_px > 0 ? width_px : 1),
                      static_cast<uint32_t>(height_px > 0 ? height_px : 1));
    {
        std::lock_guard<std::mutex> lock(g_window_mu);
        release_window_locked(handle);
        g_windows.emplace(handle, window);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_core_fvp_NativeRfvp_touch(JNIEnv*, jclass, jlong handle, jint phase, jdouble x_px,
                                   jdouble y_px) {
    if (g_api.touch == nullptr || handle == 0) {
        return;
    }
    g_api.touch(reinterpret_cast<void*>(handle), phase, x_px, y_px);
}

extern "C" JNIEXPORT void JNICALL
Java_com_core_fvp_NativeRfvp_setTextHidpi(JNIEnv*, jclass, jlong handle, jboolean enabled) {
    if (g_api.set_text_hidpi == nullptr || handle == 0) {
        return;
    }
    g_api.set_text_hidpi(reinterpret_cast<void*>(handle), enabled == JNI_TRUE ? 1 : 0);
}

extern "C" JNIEXPORT void JNICALL
Java_com_core_fvp_NativeRfvp_keyEvent(JNIEnv*, jclass, jlong handle, jint vk_code, jint phase) {
    if (g_api.key == nullptr || handle == 0) {
        return;
    }
    g_api.key(reinterpret_cast<void*>(handle), vk_code, phase);
}

extern "C" JNIEXPORT void JNICALL
Java_com_core_fvp_NativeRfvp_setSystemFont(JNIEnv*, jclass, jlong handle, jboolean enabled) {
    if (g_api.set_system_font == nullptr || handle == 0) {
        return;
    }
    g_api.set_system_font(reinterpret_cast<void*>(handle), enabled == JNI_TRUE ? 1 : 0);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_core_fvp_NativeRfvp_addFont(JNIEnv* env, jclass, jlong handle, jstring path) {
    if (g_api.add_font == nullptr || handle == 0) {
        return -1;
    }
    std::string value = jstring_to_string(env, path);
    if (value.empty()) {
        return -1;
    }
    return static_cast<jint>(g_api.add_font(reinterpret_cast<void*>(handle), value.c_str()));
}

extern "C" JNIEXPORT void JNICALL
Java_com_core_fvp_NativeRfvp_setForcedFont(JNIEnv*, jclass, jlong handle, jint font_id) {
    if (g_api.set_forced_font == nullptr || handle == 0) {
        return;
    }
    g_api.set_forced_font(reinterpret_cast<void*>(handle), font_id);
}

extern "C" JNIEXPORT void JNICALL
Java_com_core_fvp_NativeRfvp_destroy(JNIEnv*, jclass, jlong handle) {
    if (g_api.destroy == nullptr || handle == 0) {
        return;
    }
    g_api.destroy(reinterpret_cast<void*>(handle));
    {
        std::lock_guard<std::mutex> lock(g_window_mu);
        release_window_locked(handle);
    }
}
