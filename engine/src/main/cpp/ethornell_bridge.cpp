// ABI source: ethornell-rs/platform/android/include/ethornell_android.h (v1).
// The adjacent vendored header is byte-identical; never redeclare ABI structures here.
#include "ethornell_android.h"
#include "ethornell_diagnostic.h"
#include "ethornell_input_trace_gate.h"
#include <jni.h>
#include <android/native_window_jni.h>
#include <dlfcn.h>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <memory>
#include <limits>
#include <string>
#include <thread>
#include <type_traits>
#include <unordered_map>
#include <vector>

namespace {
JavaVM* vm = nullptr;
jobject appContext = nullptr;
std::mutex mutex;
void* library = nullptr;
// Absolute path supplied by Java before the first load (extracted runtime pack).
std::mutex libraryPathMutex;
std::string libraryPath;
// The 14 base exports are mandatory; request_payload is an additive v1 export.
#define REQUIRED_API_LIST(X) X(abi_version) X(build_info) X(last_error) X(init_context) X(create) X(set_surface) \
    X(detach_surface) X(step) X(input) X(set_paused) X(request_close) X(poll_event) X(complete_request) X(destroy)
#define DECLARE(name) decltype(&ethornell_android_##name) api_##name = nullptr;
REQUIRED_API_LIST(DECLARE)
#undef DECLARE
using RequestPayloadApi = decltype(&ethornell_android_request_payload);
RequestPayloadApi api_request_payload = nullptr;
bool payloadSymbolAvailable = false;
// Upstream bounds input StringV1 to 16 KiB; the read-only dialog payload gets a 64 KiB host cap.
constexpr uint32_t MAX_PAYLOAD_BYTES = 64u * 1024u;
constexpr size_t MAX_RESPONSE_BYTES = 16u * 1024u;
constexpr jsize MAX_RESPONSE_UNITS = 16 * 1024;
struct Session {
    uint64_t nativeHandle;
    std::thread::id owner;
    ANativeWindow* window = nullptr;
    uint64_t generation = 0;
};
std::unordered_map<uint64_t, Session> sessions;
uint64_t nextToken = 1;
bool utf8(JNIEnv* env, jstring value, std::string& out, bool required);

bool approvedInputTrace(JNIEnv* env) {
#ifdef NDEBUG
    (void) env;
    return false;
#else
    jclass contextClass = env->GetObjectClass(appContext);
    jmethodID getFiles = contextClass ? env->GetMethodID(contextClass, "getFilesDir", "()Ljava/io/File;") : nullptr;
    jobject files = getFiles ? env->CallObjectMethod(appContext, getFiles) : nullptr;
    jclass fileClass = files && !env->ExceptionCheck() ? env->GetObjectClass(files) : nullptr;
    jmethodID getPath = fileClass ? env->GetMethodID(fileClass, "getAbsolutePath", "()Ljava/lang/String;") : nullptr;
    auto path = getPath ? static_cast<jstring>(env->CallObjectMethod(files, getPath)) : nullptr;
    std::string value;
    bool allowed = path && !env->ExceptionCheck() && utf8(env, path, value, true)
        && ethornell_bridge::inputTraceApproved(value.c_str());
    if (path) env->DeleteLocalRef(path);
    if (fileClass) env->DeleteLocalRef(fileClass);
    if (files) env->DeleteLocalRef(files);
    if (contextClass) env->DeleteLocalRef(contextClass);
    return allowed;
#endif
}

void fail(JNIEnv* env, const char* text) {
    if (!env->ExceptionCheck()) {
        jclass cls = env->FindClass("java/lang/IllegalStateException");
        if (cls) { env->ThrowNew(cls, text); env->DeleteLocalRef(cls); }
    }
}
bool status(JNIEnv* env, int32_t code) {
    if (code >= 0) return true;
    // Fetch bounded, redacted diagnostics on the same engine thread. Do not leak game paths.
    std::string detail = ethornell_bridge::boundedDiagnostic(api_last_error);
    std::string message = "Ethornell status " + std::to_string(code);
    if (!detail.empty()) message += ": " + detail;
    fail(env, message.c_str());
    return false;
}
bool acknowledgement(JNIEnv* env, int32_t code) {
    if (code == 0) return true;
    if (code < 0) return status(env, code);
    fail(env, "Missing Ethornell lifecycle acknowledgement"); return false;
}
bool load(JNIEnv* env) {
    if (library) return true;
    std::string configured;
    {
        std::lock_guard<std::mutex> lock(libraryPathMutex);
        configured = libraryPath;
    }
    // The runtime pack supplies an absolute path; the plain SONAME is the legacy in-APK layout.
    const char* target = configured.empty() ? "libethornell.so" : configured.c_str();
    void* candidate = dlopen(target, RTLD_NOW | RTLD_LOCAL);
    if (!candidate) { fail(env, "Ethornell library unavailable"); return false; }
    bool valid = true;
#define RESOLVE(name) api_##name = reinterpret_cast<decltype(api_##name)>(dlsym(candidate, "ethornell_android_" #name)); valid = valid && api_##name != nullptr;
    REQUIRED_API_LIST(RESOLVE)
#undef RESOLVE
    // A legacy 14-symbol library stays loadable, but kind-5 dialogs are unavailable.
    api_request_payload = reinterpret_cast<RequestPayloadApi>(dlsym(candidate, "ethornell_android_request_payload"));
    if (!valid || api_abi_version() != ETHORNELL_ANDROID_ABI_VERSION) {
        api_request_payload = nullptr; payloadSymbolAvailable = false;
        dlclose(candidate); fail(env, "Ethornell ABI mismatch"); return false;
    }
    payloadSymbolAvailable = api_request_payload != nullptr;
    library = candidate;
    return true;
}
Session* session(JNIEnv* env, jlong token) {
    auto it = sessions.find(static_cast<uint64_t>(token));
    if (token == 0 || it == sessions.end() || it->second.owner != std::this_thread::get_id()) {
        fail(env, "Invalid Ethornell handle/thread"); return nullptr;
    }
    return &it->second;
}
// Strict standard UTF-8: rejects NUL, overlong forms, surrogates and truncated sequences.
bool validUtf8(const uint8_t* data, size_t length) {
    for (size_t i = 0; i < length;) {
        uint32_t cp = data[i];
        if (cp == 0) return false;
        if (cp < 0x80) { ++i; continue; }
        size_t extra;
        if ((cp & 0xE0) == 0xC0) { cp &= 0x1F; extra = 1; }
        else if ((cp & 0xF0) == 0xE0) { cp &= 0x0F; extra = 2; }
        else if ((cp & 0xF8) == 0xF0) { cp &= 0x07; extra = 3; }
        else return false;
        if (i + extra >= length) return false;
        for (size_t j = 1; j <= extra; ++j) {
            uint8_t next = data[i + j];
            if ((next & 0xC0) != 0x80) return false;
            cp = (cp << 6) | (next & 0x3F);
        }
        if ((extra == 1 && cp < 0x80) || (extra == 2 && cp < 0x800) || (extra == 3 && cp < 0x10000)) return false;
        if (cp > 0x10FFFF || (cp >= 0xD800 && cp <= 0xDFFF)) return false;
        i += extra + 1;
    }
    return true;
}
enum class Utf16 { OK, INVALID };
// Explicit UTF-16 -> standard UTF-8. NUL and malformed surrogate sequences are rejected.
Utf16 convertUtf8(JNIEnv* env, jstring value, std::string& out, jsize maxUnits, size_t maxBytes) {
    out.clear();
    jsize length = env->GetStringLength(value);
    if (length > maxUnits) return Utf16::INVALID;
    const jchar* chars = env->GetStringChars(value, nullptr);
    if (!chars) return Utf16::INVALID;
    bool valid = true;
    for (jsize i = 0; i < length; ++i) {
        uint32_t cp = chars[i];
        if (cp == 0) { valid = false; break; }
        if (cp >= 0xD800 && cp <= 0xDBFF) {
            if (++i >= length || chars[i] < 0xDC00 || chars[i] > 0xDFFF) { valid = false; break; }
            cp = 0x10000 + ((cp - 0xD800) << 10) + (chars[i] - 0xDC00);
        } else if (cp >= 0xDC00 && cp <= 0xDFFF) { valid = false; break; }
        if (cp < 0x80) out.push_back(static_cast<char>(cp));
        else if (cp < 0x800) { out.push_back(0xC0 | (cp >> 6)); out.push_back(0x80 | (cp & 63)); }
        else if (cp < 0x10000) {
            out.push_back(0xE0 | (cp >> 12)); out.push_back(0x80 | ((cp >> 6) & 63)); out.push_back(0x80 | (cp & 63));
        } else {
            out.push_back(0xF0 | (cp >> 18)); out.push_back(0x80 | ((cp >> 12) & 63));
            out.push_back(0x80 | ((cp >> 6) & 63)); out.push_back(0x80 | (cp & 63));
        }
    }
    env->ReleaseStringChars(value, chars);
    if (!valid || out.size() > maxBytes) return Utf16::INVALID;
    return Utf16::OK;
}
bool utf8(JNIEnv* env, jstring value, std::string& out, bool required) {
    if (!value) { if (required) fail(env, "Missing Ethornell path"); return !required; }
    if (convertUtf8(env, value, out, 8192, 16384) != Utf16::OK || (required && out.empty())) {
        fail(env, "Invalid Ethornell UTF-16 string"); return false;
    }
    return true;
}
// Response text is optional. Empty means the integer answer; non-empty is the kind-5 JSON answer.
bool utf8Text(JNIEnv* env, jstring value, std::string& out, const char* failure) {
    if (!value) { out.clear(); return true; }
    if (convertUtf8(env, value, out, MAX_RESPONSE_UNITS, MAX_RESPONSE_BYTES) != Utf16::OK) {
        fail(env, failure); return false;
    }
    return true;
}
EthornellStringV1 slice(const std::string& text) {
    return {reinterpret_cast<const uint8_t*>(text.data()), static_cast<uint32_t>(text.size()), 0};
}
jstring javaText(JNIEnv* env, const uint8_t* data, uint32_t length) {
    // Java Charset decoding produces standard UTF-8 and handles supplementary-plane text.
    jbyteArray bytes = env->NewByteArray(static_cast<jsize>(length));
    if (!bytes) return nullptr;
    env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(length), reinterpret_cast<const jbyte*>(data));
    jclass cls = env->FindClass("java/lang/String");
    jmethodID constructor = cls ? env->GetMethodID(cls, "<init>", "([BLjava/lang/String;)V") : nullptr;
    jstring charset = env->NewStringUTF("UTF-8");
    auto result = constructor && charset && !env->ExceptionCheck()
        ? static_cast<jstring>(env->NewObject(cls, constructor, bytes, charset)) : nullptr;
    if (charset) env->DeleteLocalRef(charset);
    if (cls) env->DeleteLocalRef(cls);
    env->DeleteLocalRef(bytes);
    return result;
}
jlong create(JNIEnv* env, jclass, jobject context, jstring root, jstring user, jstring font, jint encoding) {
    std::lock_guard<std::mutex> guard(mutex);
    if (!context || (encoding != 0 && encoding != 1) || !load(env)) return 0;
    if (nextToken == std::numeric_limits<uint64_t>::max()) { fail(env, "Ethornell handle budget exhausted"); return 0; }
    std::string r, u, f;
    if (!utf8(env, root, r, true) || !utf8(env, user, u, true) || !utf8(env, font, f, false)) return 0;
    if (!appContext) {
        // Java sends Application, never Activity. Verify independently at this trust boundary.
        jclass contextClass = env->GetObjectClass(context);
        jmethodID getApp = contextClass ? env->GetMethodID(contextClass, "getApplicationContext", "()Landroid/content/Context;") : nullptr;
        jobject application = getApp ? env->CallObjectMethod(context, getApp) : nullptr;
        if (contextClass) env->DeleteLocalRef(contextClass);
        if (env->ExceptionCheck() || !application) return 0;
        appContext = env->NewGlobalRef(application);
        env->DeleteLocalRef(application);
        if (!appContext) return 0;
        if (!status(env, api_init_context(vm, appContext))) {
            env->DeleteGlobalRef(appContext); appContext = nullptr; return 0;
        }
    }
    EthornellConfigV1 config{};
    config.struct_size = sizeof(config); config.abi_version = ETHORNELL_ANDROID_ABI_VERSION;
    config.resource_root = slice(r); config.user_root = slice(u); config.font_path = slice(f);
    config.text_encoding = static_cast<uint32_t>(encoding);
    // Only owner-created private metadata in native Debug builds; never an Intent/game knob.
    if (approvedInputTrace(env)) config.flags |= 2u;
    if (env->ExceptionCheck()) return 0;
    uint64_t handle = 0;
    int32_t code = api_create(&config, &handle);
    if (!acknowledgement(env, code) || handle == 0) {
        return 0;
    }
    // Java receives a shim registry token, never an address (nor unchecked Rust token).
    uint64_t token = nextToken++;
    try { sessions.emplace(token, Session{handle, std::this_thread::get_id()}); }
    catch (...) {
        api_destroy(handle);
        throw;
    }
    return static_cast<jlong>(token);
}
void attach(JNIEnv* env, jclass, jlong token, jobject surface, jint width, jint height, jlong generation) {
    std::lock_guard<std::mutex> guard(mutex);
    Session* s = session(env, token); if (!s) return;
    if (!surface || width <= 0 || height <= 0 || width > 16384 || height > 16384 || generation <= 0
        || static_cast<uint64_t>(generation) <= s->generation) { fail(env, "Invalid Ethornell surface"); return; }
    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (!window || env->ExceptionCheck()) return;
    std::unique_ptr<ANativeWindow, decltype(&ANativeWindow_release)> incoming(window, ANativeWindow_release);
    int32_t code = api_set_surface(s->nativeHandle, window, width, height, generation);
    if (acknowledgement(env, code)) {
        if (s->window) ANativeWindow_release(s->window);
        s->window = incoming.release(); s->generation = generation;
    }
}
void detach(JNIEnv* env, jclass, jlong token, jlong generation) {
    std::lock_guard<std::mutex> guard(mutex);
    Session* s = session(env, token); if (!s) return;
    if (generation <= 0) { fail(env, "Invalid Ethornell generation"); return; }
    if (acknowledgement(env, api_detach_surface(s->nativeHandle, generation)) && static_cast<uint64_t>(generation) == s->generation) {
        if (s->window) ANativeWindow_release(s->window);
        s->window = nullptr;
    }
}
void pause(JNIEnv* env, jclass, jlong token, jint reasons) {
    std::lock_guard<std::mutex> guard(mutex); Session* s = session(env, token); if (!s) return;
    if (reasons < 0 || (reasons & ~15) != 0) { fail(env, "Invalid pause mask"); return; }
    status(env, api_set_paused(s->nativeHandle, reasons));
}
void input(JNIEnv* env, jclass, jlong token, jint kind, jint code, jfloat x, jfloat y, jfloat value) {
    std::lock_guard<std::mutex> guard(mutex); Session* s = session(env, token); if (!s) return;
    if (kind < 1 || kind > 7 || code < 0 || code > 255 || !std::isfinite(x) || !std::isfinite(y) || !std::isfinite(value)) {
        fail(env, "Invalid Ethornell input"); return;
    }
    EthornellInputV1 event{}; event.struct_size = sizeof(event); event.kind = kind;
    event.code = code; event.x = x; event.y = y; event.value = value;
    status(env, api_input(s->nativeHandle, &event));
}
void step(JNIEnv* env, jclass, jlong token, jlong time) {
    std::lock_guard<std::mutex> guard(mutex); Session* s = session(env, token); if (!s) return;
    if (time <= 0) { fail(env, "Invalid Ethornell clock"); return; }
    status(env, api_step(s->nativeHandle, time));
}
jobject poll(JNIEnv* env, jclass, jlong token) {
    std::lock_guard<std::mutex> guard(mutex); Session* s = session(env, token); if (!s) return nullptr;
    EthornellEventV1 event{}; event.struct_size = sizeof(event);
    int32_t code = api_poll_event(s->nativeHandle, &event);
    if (!status(env, code) || code == 1) return nullptr;
    if (event.struct_size != sizeof(event) || event.text_len > sizeof(event.text) || event.kind < 1 || event.kind > 5) {
        fail(env, "Invalid Ethornell event"); return nullptr;
    }
    jstring text = javaText(env, event.text, event.text_len);
    if (!text || env->ExceptionCheck()) return nullptr;
    jclass cls = env->FindClass("com/core/ethornell/EthornellSession$Event");
    jmethodID constructor = cls ? env->GetMethodID(cls, "<init>", "(IJILjava/lang/String;)V") : nullptr;
    jobject result = constructor ? env->NewObject(cls, constructor, static_cast<jint>(event.kind),
        static_cast<jlong>(event.request_id), static_cast<jint>(event.code), text) : nullptr;
    env->DeleteLocalRef(text); if (cls) env->DeleteLocalRef(cls);
    return result;
}
jstring requestPayload(JNIEnv* env, jclass, jlong token, jlong id) {
    std::lock_guard<std::mutex> guard(mutex); Session* s = session(env, token); if (!s) return nullptr;
    if (id == 0) { fail(env, "Invalid Ethornell request"); return nullptr; }
    if (!payloadSymbolAvailable || api_request_payload == nullptr) return nullptr;
    // Size query returns required bytes INCLUDING NUL; a positive value is a length, not a status.
    int32_t required = api_request_payload(s->nativeHandle, static_cast<uint64_t>(id), nullptr, 0);
    if (required == ETHORNELL_E_RESOURCE) return nullptr; // unknown/stale id: caller may cancel, session stays healthy
    if (required < 0) { status(env, required); return nullptr; }
    if (required == 0) return nullptr;
    if (static_cast<uint32_t>(required) > MAX_PAYLOAD_BYTES + 1u) { fail(env, "Ethornell payload limit"); return nullptr; }
    std::vector<char> data(static_cast<size_t>(required));
    int32_t copied = api_request_payload(s->nativeHandle, static_cast<uint64_t>(id), data.data(),
                                         static_cast<uint32_t>(data.size()));
    if (copied == ETHORNELL_E_RESOURCE) return nullptr;
    if (copied < 0) { status(env, copied); return nullptr; }
    if (copied != required) { fail(env, "Ethornell payload changed"); return nullptr; }
    uint32_t bytes = static_cast<uint32_t>(required) - 1u;
    if (data[bytes] != '\0' || std::memchr(data.data(), '\0', bytes) != nullptr
        || !validUtf8(reinterpret_cast<const uint8_t*>(data.data()), bytes)) {
        fail(env, "Invalid Ethornell payload"); return nullptr;
    }
    return javaText(env, reinterpret_cast<const uint8_t*>(data.data()), bytes);
}
void respond(JNIEnv* env, jclass, jlong token, jlong id, jint result, jstring text) {
    std::lock_guard<std::mutex> guard(mutex); Session* s = session(env, token); if (!s) return;
    if (id == 0) { fail(env, "Invalid Ethornell request"); return; }
    std::string answer;
    if (!utf8Text(env, text, answer, "Invalid Ethornell response text")) return;
    EthornellResponseV1 response{}; response.struct_size = sizeof(response); response.result = result;
    if (!answer.empty()) response.text = slice(answer); // non-empty text => JSON dialog answer
    int32_t code = api_complete_request(s->nativeHandle, static_cast<uint64_t>(id), &response);
    // Unknown/stale ids (already settled or canceled) do not poison a healthy session.
    if (code == ETHORNELL_E_RESOURCE) return;
    status(env, code);
}
void close(JNIEnv* env, jclass, jlong token) {
    std::lock_guard<std::mutex> guard(mutex); Session* s = session(env, token); if (!s) return;
    status(env, api_request_close(s->nativeHandle));
}
void destroy(JNIEnv* env, jclass, jlong token) {
    std::lock_guard<std::mutex> guard(mutex);
    if (sessions.find(static_cast<uint64_t>(token)) == sessions.end()) return; // idempotent
    Session* s = session(env, token); if (!s) return;
    if (!acknowledgement(env, api_destroy(s->nativeHandle))) return; // no ACK => retain borrow until process teardown
    if (s->window) ANativeWindow_release(s->window);
    sessions.erase(static_cast<uint64_t>(token));
    // ABI v1 retains ndk-context for this isolated process; the GlobalRef must match that lifetime.
}
void setLibraryPath(JNIEnv* env, jclass, jstring path) {
    std::string value;
    if (path != nullptr) {
        const char* chars = env->GetStringUTFChars(path, nullptr);
        if (chars != nullptr) {
            value = chars;
            env->ReleaseStringUTFChars(path, chars);
        }
    }
    std::lock_guard<std::mutex> lock(libraryPathMutex);
    libraryPath = std::move(value);
}
// C++ allocation/system exceptions must never unwind through the VM's JNI frame.
template<auto Function> struct Guard;
template<typename Result, typename... Args, Result (*Function)(JNIEnv*, jclass, Args...)>
struct Guard<Function> {
    static Result call(JNIEnv* env, jclass cls, Args... args) noexcept {
        try { return Function(env, cls, args...); }
        catch (...) {
            fail(env, "Ethornell JNI bridge failure");
            if constexpr (!std::is_void_v<Result>) return Result{};
        }
    }
};
#define METHOD(name, signature, function) {const_cast<char*>(name), const_cast<char*>(signature), reinterpret_cast<void*>(Guard<function>::call)}
JNINativeMethod methods[] = {
    METHOD("create", "(Landroid/content/Context;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;I)J", create),
    METHOD("setSurface", "(JLandroid/view/Surface;IIJ)V", attach),
    METHOD("detachSurface", "(JJ)V", detach), METHOD("setPaused", "(JI)V", pause),
    METHOD("input", "(JIIFFF)V", input), METHOD("step", "(JJ)V", step),
    METHOD("pollEvent", "(J)Lcom/core/ethornell/EthornellSession$Event;", poll),
    METHOD("requestPayload", "(JJ)Ljava/lang/String;", requestPayload),
    METHOD("completeRequest", "(JJILjava/lang/String;)V", respond), METHOD("requestClose", "(J)V", close),
    METHOD("destroy", "(J)V", destroy),
    METHOD("nativeSetEngineLibraryPath", "(Ljava/lang/String;)V", setLibraryPath)
};
}
extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM* javaVm, void*) {
    vm = javaVm; JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    jclass cls = env->FindClass("com/core/ethornell/NativeEthornell");
    if (!cls) return JNI_ERR;
    jint result = env->RegisterNatives(cls, methods, sizeof(methods) / sizeof(methods[0]));
    env->DeleteLocalRef(cls);
    return result == JNI_OK ? JNI_VERSION_1_6 : JNI_ERR;
}
extern "C" JNIEXPORT void JNI_OnUnload(JavaVM* javaVm, void*) {
    std::lock_guard<std::mutex> guard(mutex);
    // Never free a Context while native sessions may still query CPAL/Oboe.
    if (!sessions.empty()) return;
    if (library) { dlclose(library); library = nullptr; }
    JNIEnv* env = nullptr;
    if (javaVm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_OK && appContext) {
        env->DeleteGlobalRef(appContext); appContext = nullptr;
    }
}
