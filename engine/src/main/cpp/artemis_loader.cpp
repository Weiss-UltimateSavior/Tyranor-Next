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

#include <cerrno>
#include <cstdint>
#include <cstring>
#include <string>
#include <sys/mman.h>
#include <unistd.h>

#define TAG "ArtemisLoader"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

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
        || name == "artemis-v4"
        || name == "artemis-v5"
        || name == "artemis-v6"
        || name == "artemis-clean";
}

// ----- CSoundTrack::Read 无进展自递归（音频线程爆栈闪退）运行时修复 -----
//
// 官方线 Artemis 内核（artemis / v4 / v5 / v6 / compatible-v2）的
// artemis::CSoundTrack::Read 在「循环播放回绕」分支里经自身 vtable 槽 16
// 递归调用自己；当循环 BGM 回绕（Rewind）后底层 Ogg 解码仍读不出数据时，
// 该递归不收敛：AAudio 回调线程栈耗尽，SIGSEGV/SEGV_ACCERR 命中栈保护页。
//
// 修复方式：dlopen 之后把 CSoundTrack vtable 的 Read 槽换成带递归深度守卫的
// 转发函数——正常回绕语义不变（回绕后读到数据即正常返回），仅在无进展时截断
// 递归（上限 8 层，正常 BGM 回绕只需 1~2 层）。槽号在官方全部变体中一致
// （第 16 槽），且整个插件里只有这一处引用 Read，单点补丁即可覆盖全部调用
// （含引擎内部递归）。vtable 位于 .data.rel.ro（RELRO），mprotect 读写后恢复
// 只读即可，不触碰代码段，规避 Android 10+ 对 app 私有 so 代码段的 W^X /
// execmod 限制。
//
// 每个 revision 的宿 Activity 各占独立进程，且 5 个官方插件 so 的 SONAME 同名，
// 同一进程只会存在一份 CSoundTrack vtable，故只需登记一个原函数指针。

typedef int (*CsReadFn)(void*, unsigned char*, int);

constexpr size_t kCsReadVtableSlotIndex = 16;
// Itanium ABI：_ZTV 符号首槽是 offset-to-top，随后 typeinfo，之后才是虚函数表。
constexpr size_t kCsReadVtablePrologueSlots = 2;
constexpr int kCsReadMaxRecursionDepth = 8;
constexpr int kCsReadLimitLogTimes = 8;

CsReadFn g_csReadOriginal = nullptr;
int g_csReadLimitHits = 0;
thread_local int g_csReadDepth = 0;

// 异常安全：即使被转发调用抛异常，深度计数也会在栈展开时归还。
struct CsReadDepthScope {
    CsReadDepthScope() { ++g_csReadDepth; }
    ~CsReadDepthScope() { --g_csReadDepth; }
};

int guardedCsRead(void* self, unsigned char* buf, int len) {
    const CsReadFn original = __atomic_load_n(&g_csReadOriginal, __ATOMIC_ACQUIRE);
    if (original == nullptr) return 0;
    if (g_csReadDepth >= kCsReadMaxRecursionDepth) {
        if (__atomic_fetch_add(&g_csReadLimitHits, 1, __ATOMIC_RELAXED) <
            kCsReadLimitLogTimes) {
            LOGW("Artemis plugin: CSoundTrack::Read recursion limit hit; truncated");
        }
        return 0;
    }
    CsReadDepthScope depthScope;
    return original(self, buf, len);
}

void installCsReadStackGuard(void* handle) {
    auto original = reinterpret_cast<CsReadFn>(
            dlsym(handle, "_ZN7artemis11CSoundTrack4ReadEPhi"));
    auto vtable = reinterpret_cast<uintptr_t*>(
            dlsym(handle, "_ZTVN7artemis11CSoundTrackE"));
    if (original == nullptr || vtable == nullptr) {
        // compatible / clean 等非官方线内核不导出该符号，无需修复。
        LOGI("Artemis plugin: CSoundTrack::Read stack guard skipped (symbol not exported)");
        return;
    }

    uintptr_t* slot = vtable + kCsReadVtablePrologueSlots + kCsReadVtableSlotIndex;
    if (*slot == reinterpret_cast<uintptr_t>(&guardedCsRead)) {
        LOGI("Artemis plugin: CSoundTrack::Read stack guard already installed");
        return;
    }
    if (*slot != reinterpret_cast<uintptr_t>(original)) {
        LOGE("Artemis plugin: CSoundTrack::Read vtable slot mismatch; stack guard skipped");
        return;
    }

    const long pageSize = sysconf(_SC_PAGESIZE);
    if (pageSize <= 0) {
        LOGE("Artemis plugin: invalid page size; stack guard skipped");
        return;
    }
    void* page = reinterpret_cast<void*>(
            reinterpret_cast<uintptr_t>(slot) & ~(static_cast<uintptr_t>(pageSize) - 1));
    if (mprotect(page, static_cast<size_t>(pageSize), PROT_READ | PROT_WRITE) != 0) {
        LOGE("Artemis plugin: mprotect vtable page failed: %s", strerror(errno));
        return;
    }
    // 先登记原函数再改槽：任何能看到新槽的调用都必然能查到原函数。
    // 恢复 PROT_READ 即原始保护位（该 vtable 页位于官方全部变体的 RELRO 段内）。
    __atomic_store_n(&g_csReadOriginal, original, __ATOMIC_RELEASE);
    *slot = reinterpret_cast<uintptr_t>(&guardedCsRead);
    if (mprotect(page, static_cast<size_t>(pageSize), PROT_READ) != 0) {
        LOGE("Artemis plugin: restore vtable page protection failed: %s", strerror(errno));
        return;
    }
    LOGI("Artemis plugin: CSoundTrack::Read stack guard installed (slot=%p)", slot);
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

    // 必须早于引擎创建任何 CSoundTrack：此刻还没转发 ANativeActivity_onCreate。
    installCsReadStackGuard(handle);

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
