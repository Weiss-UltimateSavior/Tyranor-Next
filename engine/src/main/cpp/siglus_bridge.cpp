#include <jni.h>
#include <android/native_window_jni.h>
#include <android/log.h>
#include <dlfcn.h>

#include <cstdint>
#include <cstdlib>
#include <mutex>
#include <string>

#define LOG_TAG "siglus_bridge"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

// ---------------------------------------------------------------------------
// siglus_android_* C ABI (see siglus_rs: crates/siglus_scene_vm/include/siglus.h)
// ---------------------------------------------------------------------------

using messagebox_callback_t = void (*)(void* user_data,
                                       uint64_t request_id,
                                       int32_t kind,
                                       const char* title_utf8,
                                       const char* message_utf8);

using init_context_fn_t = void (*)(void* java_vm_ptr, void* app_context_global_ref);
using create_fn_t = void* (*)(void* native_window_ptr,
                              uint32_t w_px,
                              uint32_t h_px,
                              double scale,
                              const char* game_dir_utf8);
using set_messagebox_callback_fn_t = void (*)(void* handle, messagebox_callback_t callback, void* user_data);
using submit_messagebox_result_fn_t = void (*)(void* handle, uint64_t request_id, int64_t value);
using step_fn_t = int32_t (*)(void* handle, uint32_t dt_ms);
using resize_fn_t = void (*)(void* handle, uint32_t w_px, uint32_t h_px);
using set_surface_fn_t = int32_t (*)(void* handle, void* native_window_ptr, uint32_t w_px, uint32_t h_px);
using touch_fn_t = void (*)(void* handle, int32_t phase, double x_px, double y_px);
using key_event_fn_t = void (*)(void* handle, int32_t key_code, const char* text_utf8, int32_t is_repeat);
using ime_area_fn_t = int32_t (*)(void* handle, int32_t* out_xywh);
using text_fn_t = void (*)(void* handle, const char* text_utf8);
using ime_preedit_fn_t = void (*)(void* handle, const char* text_utf8, int32_t cursor_start, int32_t cursor_end);
using destroy_fn_t = void (*)(void* handle);
using string_free_fn_t = void (*)(char* ptr);
using string_from_dir_fn_t = char* (*)(const char* game_dir_utf8);

struct SiglusApi {
    init_context_fn_t init_context = nullptr;
    create_fn_t create = nullptr;
    set_messagebox_callback_fn_t set_messagebox_callback = nullptr;
    submit_messagebox_result_fn_t submit_messagebox_result = nullptr;
    step_fn_t step = nullptr;
    resize_fn_t resize = nullptr;
    set_surface_fn_t set_surface = nullptr;
    touch_fn_t touch = nullptr;
    key_event_fn_t key_event = nullptr;
    ime_area_fn_t ime_area = nullptr;
    text_fn_t text_input = nullptr;
    ime_preedit_fn_t ime_preedit = nullptr;
    destroy_fn_t destroy = nullptr;
    string_free_fn_t string_free = nullptr;
    string_from_dir_fn_t game_name_from_dir = nullptr;
};

static SiglusApi g_api;
static std::once_flag g_api_once;
static void* g_lib_handle = nullptr;

static JavaVM* g_java_vm = nullptr;
static jclass g_native_class = nullptr;         // com/core/siglus/NativeSiglus
static jmethodID g_on_messagebox_method = nullptr;

static void* load_symbol(const char* sym) {
    void* p = dlsym(g_lib_handle, sym);
    if (!p) {
        LOGE("dlsym failed: %s (%s)", sym, dlerror());
    }
    return p;
}

static void load_api_once() {
    std::call_once(g_api_once, []() {
        g_lib_handle = dlopen("libsiglus.so", RTLD_NOW);
        if (!g_lib_handle) {
            LOGE("dlopen libsiglus.so failed: %s", dlerror());
            return;
        }
        g_api.init_context = reinterpret_cast<init_context_fn_t>(load_symbol("siglus_android_init_context"));
        g_api.create = reinterpret_cast<create_fn_t>(load_symbol("siglus_android_create"));
        g_api.set_messagebox_callback =
                reinterpret_cast<set_messagebox_callback_fn_t>(load_symbol("siglus_android_set_native_messagebox_callback"));
        g_api.submit_messagebox_result =
                reinterpret_cast<submit_messagebox_result_fn_t>(load_symbol("siglus_android_submit_messagebox_result"));
        g_api.step = reinterpret_cast<step_fn_t>(load_symbol("siglus_android_step"));
        g_api.resize = reinterpret_cast<resize_fn_t>(load_symbol("siglus_android_resize"));
        g_api.set_surface = reinterpret_cast<set_surface_fn_t>(load_symbol("siglus_android_set_surface"));
        g_api.touch = reinterpret_cast<touch_fn_t>(load_symbol("siglus_android_touch"));
        g_api.key_event = reinterpret_cast<key_event_fn_t>(load_symbol("siglus_android_key_event"));
        g_api.ime_area = reinterpret_cast<ime_area_fn_t>(load_symbol("siglus_android_ime_area"));
        g_api.text_input = reinterpret_cast<text_fn_t>(load_symbol("siglus_android_text_input"));
        g_api.ime_preedit = reinterpret_cast<ime_preedit_fn_t>(load_symbol("siglus_android_ime_preedit"));
        g_api.destroy = reinterpret_cast<destroy_fn_t>(load_symbol("siglus_android_destroy"));
        g_api.string_free = reinterpret_cast<string_free_fn_t>(load_symbol("siglus_string_free"));
        g_api.game_name_from_dir = reinterpret_cast<string_from_dir_fn_t>(load_symbol("siglus_game_name_from_dir"));

        bool core_ok = g_api.create && g_api.step && g_api.resize && g_api.set_surface &&
                       g_api.touch && g_api.destroy && g_api.init_context;
        if (!core_ok) {
            LOGE("missing one or more required siglus_android_* symbols");
        }
        if (!g_api.ime_area || !g_api.key_event) {
            LOGW("siglus_android_ime_area/key_event missing; soft keyboard and key repeat stay disabled");
        }
        LOGI("siglus_android_* symbols resolved (core=%d)", core_ok ? 1 : 0);
    });
}

static JNIEnv* get_env(bool* attached) {
    *attached = false;
    if (g_java_vm == nullptr) {
        return nullptr;
    }
    JNIEnv* env = nullptr;
    jint status = g_java_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (status == JNI_OK && env != nullptr) {
        return env;
    }
    if (status == JNI_EDETACHED) {
        if (g_java_vm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
            *attached = true;
            return env;
        }
    }
    return nullptr;
}

// Runs on the engine thread that stepped the VM (host main thread in practice).
static void messagebox_trampoline(void* user_data,
                                  uint64_t request_id,
                                  int32_t kind,
                                  const char* title_utf8,
                                  const char* message_utf8) {
    bool attached = false;
    JNIEnv* env = get_env(&attached);
    if (env != nullptr && g_native_class != nullptr && g_on_messagebox_method != nullptr) {
        jstring title = env->NewStringUTF(title_utf8 != nullptr ? title_utf8 : "");
        jstring message = env->NewStringUTF(message_utf8 != nullptr ? message_utf8 : "");
        env->CallStaticVoidMethod(g_native_class,
                                  g_on_messagebox_method,
                                  reinterpret_cast<jlong>(user_data),
                                  static_cast<jlong>(request_id),
                                  static_cast<jint>(kind),
                                  title,
                                  message);
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
        }
        if (title != nullptr) {
            env->DeleteLocalRef(title);
        }
        if (message != nullptr) {
            env->DeleteLocalRef(message);
        }
        if (attached) {
            g_java_vm->DetachCurrentThread();
        }
        return;
    }
    if (attached && g_java_vm != nullptr) {
        g_java_vm->DetachCurrentThread();
    }
    // Java 桥不可用时直接回默认按钮，绝不让引擎等待一个永远不会出现的对话框。
    if (g_api.submit_messagebox_result != nullptr && user_data != nullptr) {
        int64_t fallback = kind == 1 || kind == 2 ? 1 : (kind == 3 ? 2 : 0);
        g_api.submit_messagebox_result(user_data, request_id, fallback);
    }
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
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    jclass local = env->FindClass("com/core/siglus/NativeSiglus");
    if (local != nullptr) {
        g_native_class = reinterpret_cast<jclass>(env->NewGlobalRef(local));
        g_on_messagebox_method = env->GetStaticMethodID(
                g_native_class,
                "dispatchMessagebox",
                "(JJILjava/lang/String;Ljava/lang/String;)V");
        if (g_on_messagebox_method == nullptr) {
            env->ExceptionClear();
            LOGW("dispatchMessagebox not found on NativeSiglus");
        }
        env->DeleteLocalRef(local);
    } else {
        env->ExceptionClear();
        LOGE("NativeSiglus class not found during JNI_OnLoad");
    }
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT void JNICALL
Java_com_core_siglus_NativeSiglus_nativeInitContext(JNIEnv* env, jclass, jobject app_context) {
    load_api_once();
    if (g_api.init_context == nullptr) {
        return;
    }
    if (app_context == nullptr) {
        LOGW("nativeInitContext: null context");
        return;
    }
    jobject global = env->NewGlobalRef(app_context);
    g_api.init_context(reinterpret_cast<void*>(g_java_vm), reinterpret_cast<void*>(global));
}

extern "C" JNIEXPORT void JNICALL
Java_com_core_siglus_NativeSiglus_nativeSetLanguage(JNIEnv* env, jclass, jstring language) {
    std::string value = jstring_to_string(env, language);
    if (value.empty()) {
        // Keep upstream default (JP).
        return;
    }
    // The Rust runtime reads SIGLUS_LANGUAGE when the VM boots (siglus_android_create),
    // so this must be called before create(). Value is a pass-through of GET_LANGUAGE.
    setenv("SIGLUS_LANGUAGE", value.c_str(), 1);
    LOGI("SIGLUS_LANGUAGE=%s", value.c_str());
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_core_siglus_NativeSiglus_create(JNIEnv* env, jclass, jobject surface, jint width_px,
                                         jint height_px, jdouble native_scale, jstring game_dir) {
    load_api_once();
    if (g_api.create == nullptr) {
        LOGE("create: engine symbols unavailable");
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
        return 0;
    }
    void* handle = g_api.create(window, static_cast<uint32_t>(width_px > 0 ? width_px : 1),
                                static_cast<uint32_t>(height_px > 0 ? height_px : 1),
                                native_scale, dir.c_str());
    if (handle == nullptr) {
        LOGE("create: engine returned null handle");
    }
    return reinterpret_cast<jlong>(handle);
}

extern "C" JNIEXPORT void JNICALL
Java_com_core_siglus_NativeSiglus_setNativeMessageboxCallback(JNIEnv*, jclass, jlong handle) {
    if (g_api.set_messagebox_callback == nullptr || handle == 0) {
        return;
    }
    g_api.set_messagebox_callback(reinterpret_cast<void*>(handle), messagebox_trampoline,
                                  reinterpret_cast<void*>(handle));
}

extern "C" JNIEXPORT void JNICALL
Java_com_core_siglus_NativeSiglus_submitMessageboxResult(JNIEnv*, jclass, jlong handle,
                                                         jlong request_id, jlong value) {
    if (g_api.submit_messagebox_result == nullptr || handle == 0) {
        return;
    }
    g_api.submit_messagebox_result(reinterpret_cast<void*>(handle),
                                   static_cast<uint64_t>(request_id), static_cast<int64_t>(value));
}

extern "C" JNIEXPORT jint JNICALL
Java_com_core_siglus_NativeSiglus_step(JNIEnv*, jclass, jlong handle, jint dt_ms) {
    if (g_api.step == nullptr || handle == 0) {
        return 0;
    }
    return g_api.step(reinterpret_cast<void*>(handle),
                      static_cast<uint32_t>(dt_ms > 0 ? dt_ms : 0));
}

extern "C" JNIEXPORT void JNICALL
Java_com_core_siglus_NativeSiglus_resize(JNIEnv*, jclass, jlong handle, jint width_px, jint height_px) {
    if (g_api.resize == nullptr || handle == 0) {
        return;
    }
    g_api.resize(reinterpret_cast<void*>(handle),
                 static_cast<uint32_t>(width_px > 0 ? width_px : 1),
                 static_cast<uint32_t>(height_px > 0 ? height_px : 1));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_core_siglus_NativeSiglus_setSurface(JNIEnv* env, jclass, jlong handle, jobject surface,
                                             jint width_px, jint height_px) {
    if (g_api.set_surface == nullptr || handle == 0) {
        return JNI_FALSE;
    }
    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (window == nullptr) {
        return JNI_FALSE;
    }
    int32_t ok = g_api.set_surface(reinterpret_cast<void*>(handle), window,
                                   static_cast<uint32_t>(width_px > 0 ? width_px : 1),
                                   static_cast<uint32_t>(height_px > 0 ? height_px : 1));
    return ok != 0 ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_core_siglus_NativeSiglus_touch(JNIEnv*, jclass, jlong handle, jint phase, jdouble x_px,
                                        jdouble y_px) {
    if (g_api.touch == nullptr || handle == 0) {
        return;
    }
    g_api.touch(reinterpret_cast<void*>(handle), phase, x_px, y_px);
}

extern "C" JNIEXPORT void JNICALL
Java_com_core_siglus_NativeSiglus_keyEvent(JNIEnv* env, jclass, jlong handle, jint key_code,
                                           jstring text, jboolean is_repeat) {
    if (g_api.key_event == nullptr || handle == 0) {
        return;
    }
    std::string text_value = jstring_to_string(env, text);
    g_api.key_event(reinterpret_cast<void*>(handle), key_code,
                    text_value.empty() ? nullptr : text_value.c_str(), is_repeat ? 1 : 0);
}

extern "C" JNIEXPORT void JNICALL
Java_com_core_siglus_NativeSiglus_textInput(JNIEnv* env, jclass, jlong handle, jstring text) {
    if (g_api.text_input == nullptr || handle == 0) {
        return;
    }
    std::string value = jstring_to_string(env, text);
    if (!value.empty()) {
        g_api.text_input(reinterpret_cast<void*>(handle), value.c_str());
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_core_siglus_NativeSiglus_imePreedit(JNIEnv* env, jclass, jlong handle, jstring text,
                                             jint cursor_start, jint cursor_end) {
    if (g_api.ime_preedit == nullptr || handle == 0) {
        return;
    }
    if (text == nullptr) {
        g_api.ime_preedit(reinterpret_cast<void*>(handle), nullptr, -1, -1);
        return;
    }
    std::string value = jstring_to_string(env, text);
    g_api.ime_preedit(reinterpret_cast<void*>(handle), value.c_str(), cursor_start, cursor_end);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_core_siglus_NativeSiglus_imeArea(JNIEnv* env, jclass, jlong handle, jintArray out_xywh) {
    if (g_api.ime_area == nullptr || handle == 0 || out_xywh == nullptr) {
        return JNI_FALSE;
    }
    jint buffer[4] = {0, 0, 0, 0};
    int32_t needed = g_api.ime_area(reinterpret_cast<void*>(handle), buffer);
    if (needed == 0) {
        return JNI_FALSE;
    }
    if (env->GetArrayLength(out_xywh) < 4) {
        return JNI_FALSE;
    }
    env->SetIntArrayRegion(out_xywh, 0, 4, buffer);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_core_siglus_NativeSiglus_gameNameFromDir(JNIEnv* env, jclass, jstring game_dir) {
    load_api_once();
    if (g_api.game_name_from_dir == nullptr || g_api.string_free == nullptr) {
        return nullptr;
    }
    std::string dir = jstring_to_string(env, game_dir);
    if (dir.empty()) {
        return nullptr;
    }
    char* raw = g_api.game_name_from_dir(dir.c_str());
    if (raw == nullptr) {
        return nullptr;
    }
    jstring result = env->NewStringUTF(raw);
    g_api.string_free(raw);
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_core_siglus_NativeSiglus_destroy(JNIEnv*, jclass, jlong handle) {
    if (g_api.destroy == nullptr || handle == 0) {
        return;
    }
    g_api.destroy(reinterpret_cast<void*>(handle));
}
