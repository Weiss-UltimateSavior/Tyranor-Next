#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>

#include <cstdint>
#include <cstring>
#include <mutex>
#include <string>

#define LOG_TAG "games_bridge"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

// ---------------------------------------------------------------------------
// game_launcher C ABI (see siglus_rs: crates/game_launcher/src/ffi.rs)
//
// The shared object also exports the Siglus `siglus_android_*` host API, so the
// existing libsiglus_bridge.so keeps working against the same library.
// ---------------------------------------------------------------------------

using init_context_fn_t = void (*)(void* java_vm_ptr, void* app_context_global_ref);
using string_free_fn_t = void (*)(char* ptr);
using scan_json_fn_t = char* (*)(const char* path, int32_t depth, const char* cover_cache_dir);
using probe_json_fn_t = char* (*)(const char* root, const char* nls, const char* cover_cache_dir);
using add_font_file_fn_t = int32_t (*)(const char* path);
using fb_open_fn_t = void* (*)(const char* root, const char* engine, const char* nls, char** error_out);
using fb_step_fn_t = int32_t (*)(void* game, uint32_t dt_ms);
using fb_frame_fn_t = const uint8_t* (*)(void* game, uint32_t* width, uint32_t* height);
using fb_xy_fn_t = void (*)(void* game, int32_t x, int32_t y);
using fb_button_fn_t = void (*)(void* game, int32_t button, int32_t pressed);
using fb_int_fn_t = void (*)(void* game, int32_t value);
using fb_key_fn_t = void (*)(void* game, uint32_t code, int32_t pressed);
using fb_text_fn_t = void (*)(void* game, const char* text_utf8);
using fb_cursor_fn_t = int32_t (*)(void* game);
using fb_title_fn_t = const char* (*)(void* game);
using fb_close_fn_t = void (*)(void* game);

struct GamesApi {
    init_context_fn_t init_context = nullptr;
    string_free_fn_t string_free = nullptr;
    scan_json_fn_t scan_json = nullptr;
    probe_json_fn_t probe_json = nullptr;
    add_font_file_fn_t add_font_file = nullptr;
    fb_open_fn_t fb_open = nullptr;
    fb_step_fn_t fb_step = nullptr;
    fb_frame_fn_t fb_frame = nullptr;
    fb_xy_fn_t fb_pointer_move = nullptr;
    fb_button_fn_t fb_pointer_button = nullptr;
    fb_int_fn_t fb_wheel = nullptr;
    fb_key_fn_t fb_key = nullptr;
    fb_text_fn_t fb_text = nullptr;
    fb_cursor_fn_t fb_cursor_visible = nullptr;
    fb_title_fn_t fb_title = nullptr;
    fb_close_fn_t fb_close = nullptr;
};

static GamesApi g_api;
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
        g_lib_handle = dlopen("libsiglus.so", RTLD_NOW);
        if (!g_lib_handle) {
            LOGE("dlopen libsiglus.so failed: %s", dlerror());
            return;
        }
        g_api.init_context = reinterpret_cast<init_context_fn_t>(load_symbol("siglus_android_init_context"));
        g_api.string_free = reinterpret_cast<string_free_fn_t>(load_symbol("game_string_free"));
        g_api.scan_json = reinterpret_cast<scan_json_fn_t>(load_symbol("game_scan_json"));
        g_api.probe_json = reinterpret_cast<probe_json_fn_t>(load_symbol("game_probe_json"));
        g_api.add_font_file = reinterpret_cast<add_font_file_fn_t>(load_symbol("game_add_font_file"));
        g_api.fb_open = reinterpret_cast<fb_open_fn_t>(load_symbol("game_fb_open"));
        g_api.fb_step = reinterpret_cast<fb_step_fn_t>(load_symbol("game_fb_step"));
        g_api.fb_frame = reinterpret_cast<fb_frame_fn_t>(load_symbol("game_fb_frame"));
        g_api.fb_pointer_move = reinterpret_cast<fb_xy_fn_t>(load_symbol("game_fb_pointer_move"));
        g_api.fb_pointer_button = reinterpret_cast<fb_button_fn_t>(load_symbol("game_fb_pointer_button"));
        g_api.fb_wheel = reinterpret_cast<fb_int_fn_t>(load_symbol("game_fb_wheel"));
        g_api.fb_key = reinterpret_cast<fb_key_fn_t>(load_symbol("game_fb_key"));
        g_api.fb_text = reinterpret_cast<fb_text_fn_t>(load_symbol("game_fb_text"));
        g_api.fb_cursor_visible = reinterpret_cast<fb_cursor_fn_t>(load_symbol("game_fb_cursor_visible"));
        g_api.fb_title = reinterpret_cast<fb_title_fn_t>(load_symbol("game_fb_title"));
        g_api.fb_close = reinterpret_cast<fb_close_fn_t>(load_symbol("game_fb_close"));

        bool core_ok = g_api.fb_open && g_api.fb_step && g_api.fb_frame && g_api.fb_close;
        if (!core_ok) {
            LOGE("missing one or more required game_fb_* symbols");
        }
        if (!g_api.scan_json || !g_api.probe_json) {
            LOGW("game_scan_json/game_probe_json missing; metadata probing stays disabled");
        }
        if (!g_api.fb_pointer_move || !g_api.fb_pointer_button || !g_api.fb_key || !g_api.fb_text) {
            LOGW("some game_fb_* input symbols missing; input support is partial");
        }
        LOGI("game_launcher symbols resolved (core=%d)", core_ok ? 1 : 0);
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

static jstring string_to_jstring(JNIEnv* env, const char* value) {
    if (value == nullptr) {
        return nullptr;
    }
    jstring out = env->NewStringUTF(value);
    if (g_api.string_free != nullptr) {
        g_api.string_free(const_cast<char*>(value));
    }
    return out;
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    g_java_vm = vm;
    return JNI_VERSION_1_6;
}

// ---------------------------------------------------------------------------
// Metadata / fonts
// ---------------------------------------------------------------------------

extern "C" JNIEXPORT void JNICALL
Java_com_core_games_NativeGames_nativeInitAndroidContext(JNIEnv* env, jclass, jobject app_context) {
    load_api_once();
    if (g_api.init_context == nullptr) {
        return;
    }
    if (app_context == nullptr) {
        LOGW("initAndroidContext: null context");
        return;
    }
    // ndk-context (used by cpal/AAudio) expects a GlobalRef that outlives the call.
    jobject global = env->NewGlobalRef(app_context);
    if (global == nullptr) {
        LOGE("initAndroidContext: NewGlobalRef failed");
        return;
    }
    g_api.init_context(reinterpret_cast<void*>(g_java_vm), reinterpret_cast<void*>(global));
    LOGI("ndk-context initialized");
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_core_games_NativeGames_scanJson(JNIEnv* env, jclass, jstring path, jint depth,
                                         jstring cover_cache_dir) {
    load_api_once();
    if (g_api.scan_json == nullptr) {
        return nullptr;
    }
    std::string path_value = jstring_to_string(env, path);
    if (path_value.empty()) {
        return nullptr;
    }
    std::string cache_value = jstring_to_string(env, cover_cache_dir);
    char* json = g_api.scan_json(path_value.c_str(), static_cast<int32_t>(depth),
                                 cache_value.empty() ? nullptr : cache_value.c_str());
    return string_to_jstring(env, json);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_core_games_NativeGames_probeJson(JNIEnv* env, jclass, jstring root, jstring nls,
                                          jstring cover_cache_dir) {
    load_api_once();
    if (g_api.probe_json == nullptr) {
        return nullptr;
    }
    std::string root_value = jstring_to_string(env, root);
    if (root_value.empty()) {
        return nullptr;
    }
    std::string nls_value = jstring_to_string(env, nls);
    std::string cache_value = jstring_to_string(env, cover_cache_dir);
    char* json = g_api.probe_json(root_value.c_str(), nls_value.empty() ? nullptr : nls_value.c_str(),
                                  cache_value.empty() ? nullptr : cache_value.c_str());
    return string_to_jstring(env, json);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_core_games_NativeGames_addFontFile(JNIEnv* env, jclass, jstring path) {
    load_api_once();
    if (g_api.add_font_file == nullptr) {
        return -1;
    }
    std::string value = jstring_to_string(env, path);
    if (value.empty()) {
        return -1;
    }
    return static_cast<jint>(g_api.add_font_file(value.c_str()));
}

// ---------------------------------------------------------------------------
// Framebuffer runtime
// ---------------------------------------------------------------------------

extern "C" JNIEXPORT jlong JNICALL
Java_com_core_games_NativeGames_fbOpen(JNIEnv* env, jclass, jstring root, jstring engine,
                                       jstring nls, jobjectArray error_out) {
    load_api_once();
    if (g_api.fb_open == nullptr) {
        return 0;
    }
    std::string root_value = jstring_to_string(env, root);
    if (root_value.empty()) {
        return 0;
    }
    std::string engine_value = jstring_to_string(env, engine);
    std::string nls_value = jstring_to_string(env, nls);

    char* error = nullptr;
    void* handle = g_api.fb_open(root_value.c_str(),
                                 engine_value.empty() ? nullptr : engine_value.c_str(),
                                 nls_value.empty() ? nullptr : nls_value.c_str(),
                                 &error);
    if (handle == nullptr && error != nullptr && error_out != nullptr) {
        jstring message = string_to_jstring(env, error);
        if (message != nullptr) {
            env->SetObjectArrayElement(error_out, 0, message);
            env->DeleteLocalRef(message);
        }
    }
    return reinterpret_cast<jlong>(handle);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_core_games_NativeGames_fbStep(JNIEnv*, jclass, jlong handle, jint dt_ms) {
    if (g_api.fb_step == nullptr || handle == 0) {
        return 1;
    }
    return static_cast<jint>(
            g_api.fb_step(reinterpret_cast<void*>(handle), static_cast<uint32_t>(dt_ms > 0 ? dt_ms : 0)));
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_core_games_NativeGames_fbFrameSize(JNIEnv* env, jclass, jlong handle) {
    if (g_api.fb_frame == nullptr || handle == 0) {
        return nullptr;
    }
    uint32_t width = 0;
    uint32_t height = 0;
    const uint8_t* frame = g_api.fb_frame(reinterpret_cast<void*>(handle), &width, &height);
    if (frame == nullptr || width == 0 || height == 0) {
        return nullptr;
    }
    jint values[2] = {static_cast<jint>(width), static_cast<jint>(height)};
    jintArray out = env->NewIntArray(2);
    if (out == nullptr) {
        return nullptr;
    }
    env->SetIntArrayRegion(out, 0, 2, values);
    return out;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_core_games_NativeGames_fbCopyFrame(JNIEnv* env, jclass, jlong handle, jobject buffer) {
    if (g_api.fb_frame == nullptr || handle == 0 || buffer == nullptr) {
        return JNI_FALSE;
    }
    uint32_t width = 0;
    uint32_t height = 0;
    const uint8_t* frame = g_api.fb_frame(reinterpret_cast<void*>(handle), &width, &height);
    if (frame == nullptr || width == 0 || height == 0) {
        return JNI_FALSE;
    }
    void* dst = env->GetDirectBufferAddress(buffer);
    jlong capacity = env->GetDirectBufferCapacity(buffer);
    if (dst == nullptr || capacity <= 0) {
        return JNI_FALSE;
    }
    size_t needed = static_cast<size_t>(width) * static_cast<size_t>(height) * 4u;
    if (static_cast<size_t>(capacity) < needed) {
        needed = static_cast<size_t>(capacity);
    }
    std::memcpy(dst, frame, needed);
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_core_games_NativeGames_fbPointerMove(JNIEnv*, jclass, jlong handle, jint x, jint y) {
    if (g_api.fb_pointer_move == nullptr || handle == 0) {
        return;
    }
    g_api.fb_pointer_move(reinterpret_cast<void*>(handle), x, y);
}

extern "C" JNIEXPORT void JNICALL
Java_com_core_games_NativeGames_fbPointerButton(JNIEnv*, jclass, jlong handle, jint button,
                                                jboolean pressed) {
    if (g_api.fb_pointer_button == nullptr || handle == 0) {
        return;
    }
    g_api.fb_pointer_button(reinterpret_cast<void*>(handle), button, pressed == JNI_TRUE ? 1 : 0);
}

extern "C" JNIEXPORT void JNICALL
Java_com_core_games_NativeGames_fbWheel(JNIEnv*, jclass, jlong handle, jboolean up) {
    if (g_api.fb_wheel == nullptr || handle == 0) {
        return;
    }
    g_api.fb_wheel(reinterpret_cast<void*>(handle), up == JNI_TRUE ? 1 : 0);
}

extern "C" JNIEXPORT void JNICALL
Java_com_core_games_NativeGames_fbKey(JNIEnv*, jclass, jlong handle, jint code, jboolean pressed) {
    if (g_api.fb_key == nullptr || handle == 0) {
        return;
    }
    g_api.fb_key(reinterpret_cast<void*>(handle), static_cast<uint32_t>(code), pressed == JNI_TRUE ? 1 : 0);
}

extern "C" JNIEXPORT void JNICALL
Java_com_core_games_NativeGames_fbText(JNIEnv* env, jclass, jlong handle, jstring text) {
    if (g_api.fb_text == nullptr || handle == 0) {
        return;
    }
    std::string value = jstring_to_string(env, text);
    if (!value.empty()) {
        g_api.fb_text(reinterpret_cast<void*>(handle), value.c_str());
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_core_games_NativeGames_fbCursorVisible(JNIEnv*, jclass, jlong handle) {
    if (g_api.fb_cursor_visible == nullptr || handle == 0) {
        return 0;
    }
    return static_cast<jint>(g_api.fb_cursor_visible(reinterpret_cast<void*>(handle)));
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_core_games_NativeGames_fbTitle(JNIEnv* env, jclass, jlong handle) {
    if (g_api.fb_title == nullptr || handle == 0) {
        return nullptr;
    }
    const char* title = g_api.fb_title(reinterpret_cast<void*>(handle));
    if (title == nullptr) {
        return nullptr;
    }
    return env->NewStringUTF(title);
}

extern "C" JNIEXPORT void JNICALL
Java_com_core_games_NativeGames_fbClose(JNIEnv*, jclass, jlong handle) {
    if (g_api.fb_close == nullptr || handle == 0) {
        return;
    }
    g_api.fb_close(reinterpret_cast<void*>(handle));
}
