#include <android/log.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <jni.h>
#include <link.h>
#include <dirent.h>
#include <sys/stat.h>

#include <cstdarg>
#include <cerrno>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <string>
#include <cstdint>
#include <atomic>
#include <sys/mman.h>
#include <unistd.h>
#include <time.h>

namespace {

constexpr const char* kTag = "KrkrBridge";
constexpr const char* kGetSceneSymbol = "_ZN12TVPMainScene11GetInstanceEv";
constexpr const char* kStartupSymbol = "_ZN12TVPMainScene11startupFromERKSs";
constexpr const char* kUpdateSymbol = "_ZN12TVPMainScene6updateEf";
constexpr size_t kUiFormContainerOffset = 0x348;
constexpr size_t kUiFormCountVtableOffset = 0x238;

using GetScene = void* (*)();

// Forward declaration so LegacyCowString's constructor can increment the
// leak counter; the definition lives with the other globals below.
extern std::atomic<int> gLeakedCowStringCount;

// libgame was built against the old GNU libstdc++ copy-on-write string ABI.
// Its startupFrom symbol receives a one-word string object that points to the
// character data; the three words immediately before that data are length,
// capacity and reference count. Passing std::__ndk1::string here corrupts the
// engine, despite the same mangled "Ss" spelling in the game symbol.
//
// OWNERSHIP: startupFrom retains this legacy string object after returning.
// The KRKR process is short-lived (one launch per process invocation),
// so the one allocation per launch is intentionally leaked. A debug counter
// is tracked below for diagnostic visibility.
struct LegacyCowString {
    char* data = nullptr;

    explicit LegacyCowString(const std::string& value) {
        constexpr size_t kHeaderSize = sizeof(size_t) * 3;
        const size_t size = value.size();
        auto* header = static_cast<unsigned char*>(std::malloc(kHeaderSize + size + 1));
        if (header == nullptr) return;
        auto* words = reinterpret_cast<size_t*>(header);
        words[0] = size;
        words[1] = size;
        words[2] = 1;
        data = reinterpret_cast<char*>(header + kHeaderSize);
        std::memcpy(data, value.data(), size);
        data[size] = '\0';
        gLeakedCowStringCount.fetch_add(1, std::memory_order_relaxed);
    }

    // startupFrom retains this legacy string object after returning. The old
    // bridge intentionally leaked the one allocation per process launch; the
    // KRKR process is short-lived, so preserve that ownership contract.
    ~LegacyCowString() = default;

    LegacyCowString(const LegacyCowString&) = delete;
    LegacyCowString& operator=(const LegacyCowString&) = delete;
};

using StartupFrom = void (*)(void*, const LegacyCowString&);
using Update = void (*)(void*, float);
using UiFormCount = int (*)(void*);
using OpenFn = int (*)(const char*, int, ...);
using FopenFn = FILE* (*)(const char*, const char*);
using StatFn = int (*)(const char*, struct stat*);
using Stat64Fn = int (*)(const char*, struct stat64*);
using AccessFn = int (*)(const char*, int);
using RenameFn = int (*)(const char*, const char*);
using PathFn = int (*)(const char*);
using MkdirFn = int (*)(const char*, mode_t);
using OpendirFn = DIR* (*)(const char*);

struct GameApi {
    std::string library;
    void* handle = nullptr;
    GetScene getScene = nullptr;
    StartupFrom startupFrom = nullptr;
    Update update = nullptr;
    bool languageCheckPatched = false;
};

JavaVM* gVm = nullptr;
std::mutex gMutex;
GameApi gGame;
std::string gPathPrefix;
OpenFn gOriginalOpen = nullptr;
OpenFn gOriginalOpen64 = nullptr;
FopenFn gOriginalFopen = nullptr;
FopenFn gOriginalFopen64 = nullptr;
StatFn gOriginalStat = nullptr;
StatFn gOriginalLstat = nullptr;
Stat64Fn gOriginalStat64 = nullptr;
Stat64Fn gOriginalLstat64 = nullptr;
AccessFn gOriginalAccess = nullptr;
RenameFn gOriginalRename = nullptr;
PathFn gOriginalUnlink = nullptr;
PathFn gOriginalRemove = nullptr;
MkdirFn gOriginalMkdir = nullptr;
PathFn gOriginalRmdir = nullptr;
OpendirFn gOriginalOpendir = nullptr;
std::atomic<Update> gOriginalSceneUpdate{nullptr};
void** gSceneUpdateSlot = nullptr;
std::atomic<int> gLeakedCowStringCount{0};
std::atomic<bool> gGameReadyReported{false};
std::atomic<int> gLastLaunchReadiness{-1};
int64_t gFirstSceneUpdateNs = 0;
constexpr int64_t kMenuShrinkWaitNs = 1200LL * 1000LL * 1000LL;
constexpr uint32_t kArm64RetInstruction = 0xD65F03C0;
constexpr uint32_t kArm64SubSpPrologue = 0xD10203FF;
constexpr uintptr_t kLibGameLanguageCheckOffset = 0x9235C8;
constexpr uintptr_t kLibGame134LanguageCheckOffset = 0x90F6CC;

bool supportedGameLibrary(const char* library) {
    if (library == nullptr || library[0] == '\0') return false;
    const char* baseName = std::strrchr(library, '/');
    baseName = baseName == nullptr ? library : baseName + 1;
    return std::strcmp(baseName, "libgame.so") == 0
            || std::strcmp(baseName, "libgame134.so") == 0
            || std::strcmp(baseName, "libgame126.so") == 0;
}

std::string takeString(JNIEnv* env, jstring value) {
    if (value == nullptr) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

const char* baseName(const char* path) {
    if (path == nullptr) return "";
    const char* name = std::strrchr(path, '/');
    return name == nullptr ? path : name + 1;
}

struct LibraryBaseRequest {
    const char* library;
    uintptr_t base = 0;
};

int findLoadedLibraryBase(struct dl_phdr_info* info, size_t, void* data) {
    auto* request = static_cast<LibraryBaseRequest*>(data);
    if (info == nullptr || request == nullptr || request->library == nullptr) return 0;
    const char* loaded = info->dlpi_name == nullptr ? "" : info->dlpi_name;
    if (*loaded == '\0') return 0;
    if (std::strcmp(loaded, request->library) != 0 &&
            std::strcmp(baseName(loaded), baseName(request->library)) != 0) {
        return 0;
    }
    request->base = static_cast<uintptr_t>(info->dlpi_addr);
    return 1;
}

uintptr_t loadedLibraryBase(const std::string& library) {
    LibraryBaseRequest request{library.c_str(), 0};
    dl_iterate_phdr(findLoadedLibraryBase, &request);
    return request.base;
}

bool patchCode32(uintptr_t address, uint32_t value) {
    if (address == 0) return false;
    const long pageSize = sysconf(_SC_PAGESIZE);
    if (pageSize <= 0) return false;
    const uintptr_t page = address & ~static_cast<uintptr_t>(pageSize - 1);
    if (mprotect(reinterpret_cast<void*>(page), static_cast<size_t>(pageSize),
                 PROT_READ | PROT_WRITE | PROT_EXEC) != 0) {
        __android_log_print(ANDROID_LOG_ERROR, kTag,
                            "mprotect code patch failed address=%p errno=%d",
                            reinterpret_cast<void*>(address), errno);
        return false;
    }
    auto* slot = reinterpret_cast<uint32_t*>(address);
    __atomic_store_n(slot, value, __ATOMIC_RELEASE);
    __builtin___clear_cache(reinterpret_cast<char*>(address),
                            reinterpret_cast<char*>(address + sizeof(uint32_t)));
    const int restoreResult = mprotect(reinterpret_cast<void*>(page), static_cast<size_t>(pageSize),
                                       PROT_READ | PROT_EXEC);
    if (restoreResult != 0) {
        __android_log_print(ANDROID_LOG_WARN, kTag,
                            "restore code page protection failed address=%p errno=%d",
                            reinterpret_cast<void*>(address), errno);
    }
    return true;
}

bool installLanguageCheckBypass(GameApi& api) {
    if (api.languageCheckPatched || api.library.empty()) return api.languageCheckPatched;
    const char* name = baseName(api.library.c_str());
    uintptr_t offset = 0;
    if (std::strcmp(name, "libgame.so") == 0) {
        offset = kLibGameLanguageCheckOffset;
    } else if (std::strcmp(name, "libgame134.so") == 0) {
        offset = kLibGame134LanguageCheckOffset;
    } else {
        return false;
    }

    const uintptr_t base = loadedLibraryBase(api.library);
    if (base == 0) {
        __android_log_print(ANDROID_LOG_WARN, kTag,
                            "language check bypass skipped: library base not found for %s",
                            api.library.c_str());
        return false;
    }
    const uintptr_t address = base + offset;
    const uint32_t before = __atomic_load_n(reinterpret_cast<uint32_t*>(address), __ATOMIC_ACQUIRE);
    if (before == kArm64RetInstruction) {
        api.languageCheckPatched = true;
        __android_log_print(ANDROID_LOG_INFO, kTag,
                            "language check bypass already active %s offset=0x%lx",
                            name, static_cast<unsigned long>(offset));
        return true;
    }
    if (before != kArm64SubSpPrologue) {
        __android_log_print(ANDROID_LOG_WARN, kTag,
                            "language check bypass signature mismatch %s offset=0x%lx before=0x%08x",
                            name, static_cast<unsigned long>(offset), before);
        return false;
    }
    api.languageCheckPatched = patchCode32(address, kArm64RetInstruction);
    __android_log_print(api.languageCheckPatched ? ANDROID_LOG_INFO : ANDROID_LOG_ERROR, kTag,
                        "language check bypass %s %s offset=0x%lx address=%p",
                        api.languageCheckPatched ? "installed" : "failed",
                        name, static_cast<unsigned long>(offset), reinterpret_cast<void*>(address));
    return api.languageCheckPatched;
}

bool resolveGameLocked(const char* library) {
    if (!supportedGameLibrary(library)) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "unsupported game library: %s",
                            library == nullptr ? "(null)" : library);
        return false;
    }
    if (gGame.handle != nullptr) {
        if (gGame.library == library) return true;
        __android_log_print(ANDROID_LOG_ERROR, kTag, "refusing to switch game library %s -> %s",
                            gGame.library.c_str(), library);
        return false;
    }

    void* handle = dlopen(library, RTLD_NOW | RTLD_LOCAL);
    if (handle == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "dlopen %s failed: %s", library, dlerror());
        return false;
    }
    auto getScene = reinterpret_cast<GetScene>(dlsym(handle, kGetSceneSymbol));
    auto startupFrom = reinterpret_cast<StartupFrom>(dlsym(handle, kStartupSymbol));
    auto update = reinterpret_cast<Update>(dlsym(handle, kUpdateSymbol));
    if (getScene == nullptr || startupFrom == nullptr || update == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, kTag,
                            "required KRKR symbols missing in %s scene=%p startup=%p update=%p error=%s", library,
                            reinterpret_cast<void*>(getScene), reinterpret_cast<void*>(startupFrom),
                            reinterpret_cast<void*>(update), dlerror());
        dlclose(handle);
        return false;
    }
    gGame.library = library;
    gGame.handle = handle;
    gGame.getScene = getScene;
    gGame.startupFrom = startupFrom;
    gGame.update = update;
    installLanguageCheckBypass(gGame);
    __android_log_print(ANDROID_LOG_INFO, kTag, "initialized %s scene=%p startup=%p update=%p", library,
                        reinterpret_cast<void*>(getScene), reinterpret_cast<void*>(startupFrom),
                        reinterpret_cast<void*>(update));
    return true;
}

bool setWritablePointer(void** slot, void* value) {
    if (slot == nullptr) return false;
    const long pageSize = sysconf(_SC_PAGESIZE);
    if (pageSize <= 0) return false;
    const uintptr_t address = reinterpret_cast<uintptr_t>(slot);
    const uintptr_t page = address & ~static_cast<uintptr_t>(pageSize - 1);
    if (mprotect(reinterpret_cast<void*>(page), static_cast<size_t>(pageSize),
                 PROT_READ | PROT_WRITE) != 0) return false;
    *slot = value;
    __builtin___clear_cache(reinterpret_cast<char*>(slot), reinterpret_cast<char*>(slot + 1));
    return mprotect(reinterpret_cast<void*>(page), static_cast<size_t>(pageSize), PROT_READ) == 0;
}

void notifyGameReady() {
    if (gVm == nullptr) return;
    JNIEnv* env = nullptr;
    bool attached = false;
    if (gVm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        if (gVm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
        attached = true;
    }
    jclass bridge = env->FindClass("bridge/NativeBridge");
    if (bridge != nullptr) {
        jmethodID callback = env->GetStaticMethodID(bridge, "onKrkrGameReady", "()V");
        if (callback != nullptr) env->CallStaticVoidMethod(bridge, callback);
        env->DeleteLocalRef(bridge);
    }
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (attached) gVm->DetachCurrentThread();
}

void hookedSceneUpdate(void* scene, float dt) {
    Update original = gOriginalSceneUpdate.load();
    if (original != nullptr) original(scene, dt);
    if (gGameReadyReported.load()) return;

    timespec now{};
    clock_gettime(CLOCK_MONOTONIC, &now);
    const int64_t nowNs = static_cast<int64_t>(now.tv_sec) * 1000LL * 1000LL * 1000LL + now.tv_nsec;
    if (gFirstSceneUpdateNs == 0) {
        // doStartup schedules this update immediately after calling
        // TVPGameMainMenu::shrinkWithTime(1.0f). Do not expose that menu while
        // its move/fade action is still running.
        gFirstSceneUpdateNs = nowNs;
        __android_log_print(ANDROID_LOG_INFO, kTag, "TVPMainScene first update; waiting for menu shrink");
        return;
    }
    if (nowNs - gFirstSceneUpdateNs < kMenuShrinkWaitNs) return;

    if (!gGameReadyReported.exchange(true)) {
        if (gSceneUpdateSlot != nullptr) {
            // Restore after the one-off transition window so the hook has no
            // steady-state per-frame cost.
            setWritablePointer(gSceneUpdateSlot, reinterpret_cast<void*>(original));
        }
        const int leakedCount = gLeakedCowStringCount.load(std::memory_order_relaxed);
        __android_log_print(ANDROID_LOG_INFO, kTag,
                            "TVPMainScene startup completed; menu shrink finished; leakedCowStrings=%d",
                            leakedCount);
        notifyGameReady();
    }
}

bool installGameReadySignal(void* scene) {
    if (scene == nullptr || gGame.update == nullptr) return false;
    if (gSceneUpdateSlot != nullptr) return true;
    auto** vtable = *reinterpret_cast<void***>(scene);
    if (vtable == nullptr) return false;
    for (size_t index = 0; index < 256; ++index) {
        if (vtable[index] != reinterpret_cast<void*>(gGame.update)) continue;
        gOriginalSceneUpdate.store(gGame.update);
        if (!setWritablePointer(&vtable[index], reinterpret_cast<void*>(hookedSceneUpdate))) {
            gOriginalSceneUpdate.store(nullptr);
            __android_log_print(ANDROID_LOG_ERROR, kTag, "TVPMainScene update vtable patch failed index=%zu", index);
            return false;
        }
        gSceneUpdateSlot = &vtable[index];
        gGameReadyReported.store(false);
        gFirstSceneUpdateNs = 0;
        __android_log_print(ANDROID_LOG_INFO, kTag, "TVPMainScene ready signal installed vtableIndex=%zu", index);
        return true;
    }
    __android_log_print(ANDROID_LOG_ERROR, kTag, "TVPMainScene update not found in vtable");
    return false;
}

bool isLaunchSceneReady(void* scene) {
    if (scene == nullptr) return false;
    auto* uiForms = *reinterpret_cast<void**>(reinterpret_cast<uintptr_t>(scene) + kUiFormContainerOffset);
    if (uiForms == nullptr) return false;
    auto** vtable = *reinterpret_cast<void***>(uiForms);
    if (vtable == nullptr) return false;
    auto count = reinterpret_cast<UiFormCount>(
            vtable[kUiFormCountVtableOffset / sizeof(*vtable)]);
    const int formCount = count(uiForms);
    const int previous = gLastLaunchReadiness.exchange(formCount);
    if (previous != formCount) {
        __android_log_print(ANDROID_LOG_INFO, kTag, "launch UI forms ready count=%d", formCount);
    }
    return formCount > 0;
}

bool pathMatchesPrefix(const char* path) {
    std::lock_guard<std::mutex> lock(gMutex);
    if (path == nullptr || gPathPrefix.empty()) return false;
    const char* normalized = path;
    if (std::strncmp(normalized, "file://", 7) == 0) normalized += 7;
    while (std::strncmp(normalized, "./", 2) == 0) normalized += 2;
    // Some Windows Steam wrappers read root config files through relative paths
    // (for example "ds.ini") after chdir/gamedir setup. Keep this whitelist tight:
    // Java will still redirect only when the matching overlay has been configured.
    if (std::strcmp(normalized, "ds.ini") == 0 || std::strcmp(normalized, "patch.tjs") == 0) return true;
    if (std::strncmp(normalized, gPathPrefix.c_str(), gPathPrefix.size()) == 0) return true;
    return gPathPrefix[0] == '/'
            && std::strncmp(normalized, gPathPrefix.c_str() + 1, gPathPrefix.size() - 1) == 0;
}

int callOriginal(OpenFn original, const char* path, int flags, mode_t mode) {
    if (original == nullptr) return -1;
    if ((flags & O_CREAT) != 0) return original(path, flags, mode);
    return original(path, flags);
}

int callJavaOpen(const char* path, int flags) {
    if (gVm == nullptr || path == nullptr) return -1;
    JNIEnv* env = nullptr;
    bool attached = false;
    if (gVm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        if (gVm->AttachCurrentThread(&env, nullptr) != JNI_OK) return -1;
        attached = true;
    }

    int fd = -1;
    jclass clazz = env->FindClass("bridge/NativeBridge");
    if (clazz != nullptr) {
        jmethodID method = env->GetStaticMethodID(clazz, "open", "(Ljava/lang/String;I)I");
        if (method != nullptr) {
            jstring javaPath = env->NewStringUTF(path);
            if (javaPath != nullptr) {
                fd = env->CallStaticIntMethod(clazz, method, javaPath, flags);
                env->DeleteLocalRef(javaPath);
            }
        }
        env->DeleteLocalRef(clazz);
    }
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        fd = -1;
    }
    if (attached) gVm->DetachCurrentThread();
    return fd;
}

std::string callJavaPathMethod(const char* path, const char* methodName) {
    if (gVm == nullptr || path == nullptr || methodName == nullptr) return {};
    JNIEnv* env = nullptr;
    bool attached = false;
    if (gVm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        if (gVm->AttachCurrentThread(&env, nullptr) != JNI_OK) return {};
        attached = true;
    }

    std::string result;
    jclass clazz = env->FindClass("bridge/NativeBridge");
    if (clazz != nullptr) {
        jmethodID method = env->GetStaticMethodID(
                clazz, methodName, "(Ljava/lang/String;)Ljava/lang/String;");
        if (method != nullptr) {
            jstring javaPath = env->NewStringUTF(path);
            if (javaPath != nullptr) {
                auto redirected = static_cast<jstring>(
                        env->CallStaticObjectMethod(clazz, method, javaPath));
                if (redirected != nullptr) {
                    result = takeString(env, redirected);
                    env->DeleteLocalRef(redirected);
                }
                env->DeleteLocalRef(javaPath);
            }
        }
        env->DeleteLocalRef(clazz);
    }
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        result.clear();
    }
    if (attached) gVm->DetachCurrentThread();
    return result;
}

std::string callJavaPathModeMethod(const char* path, int mode, const char* methodName) {
    if (gVm == nullptr || path == nullptr || methodName == nullptr) return {};
    JNIEnv* env = nullptr;
    bool attached = false;
    if (gVm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        if (gVm->AttachCurrentThread(&env, nullptr) != JNI_OK) return {};
        attached = true;
    }

    std::string result;
    jclass clazz = env->FindClass("bridge/NativeBridge");
    if (clazz != nullptr) {
        jmethodID method = env->GetStaticMethodID(
                clazz, methodName, "(Ljava/lang/String;I)Ljava/lang/String;");
        if (method != nullptr) {
            jstring javaPath = env->NewStringUTF(path);
            if (javaPath != nullptr) {
                auto redirected = static_cast<jstring>(
                        env->CallStaticObjectMethod(clazz, method, javaPath, mode));
                if (redirected != nullptr) {
                    result = takeString(env, redirected);
                    env->DeleteLocalRef(redirected);
                }
                env->DeleteLocalRef(javaPath);
            }
        }
        env->DeleteLocalRef(clazz);
    }
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        result.clear();
    }
    if (attached) gVm->DetachCurrentThread();
    return result;
}

std::string callJavaRedirect(const char* path) {
    return callJavaPathMethod(path, "redirect");
}

std::string callJavaOpenRedirect(const char* path, int flags) {
    return callJavaPathModeMethod(path, flags, "redirectOpen");
}

std::string callJavaReadMetadataRedirect(const char* path) {
    return callJavaPathMethod(path, "redirectReadMetadata");
}

std::string callJavaScopedSaveRedirect(const char* path) {
    return callJavaPathMethod(path, "redirectScopedSave");
}

int hookOpenCommon(OpenFn original, const char* path, int flags, va_list arguments) {
    mode_t mode = 0;
    if ((flags & O_CREAT) != 0) mode = static_cast<mode_t>(va_arg(arguments, int));
    if (!pathMatchesPrefix(path)) return callOriginal(original, path, flags, mode);

    const std::string scopedSave = callJavaScopedSaveRedirect(path);
    if (!scopedSave.empty()) return callOriginal(original, scopedSave.c_str(), flags, mode);

    // Preserve the existing Java-first order for regular paths: SAF mirrors use
    // zero-byte placeholders that libc can open, while Java supplies real content.
    const int fd = callJavaOpen(path, flags);
    if (fd >= 0) return fd;

    const std::string redirected = callJavaOpenRedirect(path, flags);
    return callOriginal(original, redirected.empty() ? path : redirected.c_str(), flags, mode);
}

int hookedOpen(const char* path, int flags, ...) {
    va_list arguments;
    va_start(arguments, flags);
    const int result = hookOpenCommon(gOriginalOpen, path, flags, arguments);
    va_end(arguments);
    return result;
}

int hookedOpen64(const char* path, int flags, ...) {
    va_list arguments;
    va_start(arguments, flags);
    const int result = hookOpenCommon(gOriginalOpen64, path, flags, arguments);
    va_end(arguments);
    return result;
}

int fopenModeToFlags(const char* mode) {
    if (mode == nullptr || mode[0] == '\0') return -1;
    const bool update = std::strchr(mode, '+') != nullptr;
    switch (mode[0]) {
        case 'r':
            return update ? O_RDWR : O_RDONLY;
        case 'w':
            return (update ? O_RDWR : O_WRONLY) | O_CREAT | O_TRUNC;
        case 'a':
            return (update ? O_RDWR : O_WRONLY) | O_CREAT | O_APPEND;
        default:
            return -1;
    }
}

FILE* hookFopenCommon(FopenFn original, const char* path, const char* mode) {
    if (!pathMatchesPrefix(path)) return original == nullptr ? nullptr : original(path, mode);
    const std::string scopedSave = callJavaScopedSaveRedirect(path);
    if (!scopedSave.empty()) {
        return original == nullptr ? nullptr : original(scopedSave.c_str(), mode);
    }

    const int flags = fopenModeToFlags(mode);
    if (flags >= 0) {
        const int fd = callJavaOpen(path, flags);
        if (fd >= 0) {
            FILE* stream = fdopen(fd, mode);
            if (stream != nullptr) return stream;
            close(fd);
        }
    }

    const std::string redirected = flags >= 0 ? callJavaOpenRedirect(path, flags) : callJavaRedirect(path);
    return original == nullptr ? nullptr
            : original(redirected.empty() ? path : redirected.c_str(), mode);
}

FILE* hookedFopen(const char* path, const char* mode) {
    return hookFopenCommon(gOriginalFopen, path, mode);
}

FILE* hookedFopen64(const char* path, const char* mode) {
    return hookFopenCommon(gOriginalFopen64, path, mode);
}

template<typename Fn, typename... Args>
int hookSinglePath(Fn original, const char* path, Args... args) {
    if (original == nullptr) return -1;
    if (!pathMatchesPrefix(path)) return original(path, args...);
    const std::string redirected = callJavaRedirect(path);
    return original(redirected.empty() ? path : redirected.c_str(), args...);
}

int hookedStat(const char* path, struct stat* info) {
    if (gOriginalStat == nullptr) return -1;
    if (!pathMatchesPrefix(path)) return gOriginalStat(path, info);
    const std::string redirected = callJavaReadMetadataRedirect(path);
    return gOriginalStat(redirected.empty() ? path : redirected.c_str(), info);
}

int hookedLstat(const char* path, struct stat* info) {
    if (gOriginalLstat == nullptr) return -1;
    if (!pathMatchesPrefix(path)) return gOriginalLstat(path, info);
    const std::string redirected = callJavaReadMetadataRedirect(path);
    return gOriginalLstat(redirected.empty() ? path : redirected.c_str(), info);
}

int hookedStat64(const char* path, struct stat64* info) {
    if (gOriginalStat64 == nullptr) return -1;
    if (!pathMatchesPrefix(path)) return gOriginalStat64(path, info);
    const std::string redirected = callJavaReadMetadataRedirect(path);
    return gOriginalStat64(redirected.empty() ? path : redirected.c_str(), info);
}

int hookedLstat64(const char* path, struct stat64* info) {
    if (gOriginalLstat64 == nullptr) return -1;
    if (!pathMatchesPrefix(path)) return gOriginalLstat64(path, info);
    const std::string redirected = callJavaReadMetadataRedirect(path);
    return gOriginalLstat64(redirected.empty() ? path : redirected.c_str(), info);
}

int hookedAccess(const char* path, int mode) {
    if (gOriginalAccess == nullptr) return -1;
    if (!pathMatchesPrefix(path)) return gOriginalAccess(path, mode);
    const std::string redirected = callJavaReadMetadataRedirect(path);
    return gOriginalAccess(redirected.empty() ? path : redirected.c_str(), mode);
}

int hookedRename(const char* from, const char* to) {
    if (gOriginalRename == nullptr) return -1;
    const std::string redirectedFrom = pathMatchesPrefix(from) ? callJavaRedirect(from) : std::string();
    const std::string redirectedTo = pathMatchesPrefix(to) ? callJavaRedirect(to) : std::string();
    return gOriginalRename(
            redirectedFrom.empty() ? from : redirectedFrom.c_str(),
            redirectedTo.empty() ? to : redirectedTo.c_str());
}

int hookedUnlink(const char* path) {
    return hookSinglePath(gOriginalUnlink, path);
}

int hookedRemove(const char* path) {
    return hookSinglePath(gOriginalRemove, path);
}

int hookedMkdir(const char* path, mode_t mode) {
    return hookSinglePath(gOriginalMkdir, path, mode);
}

int hookedRmdir(const char* path) {
    return hookSinglePath(gOriginalRmdir, path);
}

DIR* hookedOpendir(const char* path) {
    if (gOriginalOpendir == nullptr) return nullptr;
    if (!pathMatchesPrefix(path)) return gOriginalOpendir(path);
    const std::string redirected = callJavaRedirect(path);
    return gOriginalOpendir(redirected.empty() ? path : redirected.c_str());
}

struct GotHookRequest {
    const std::string* library;
    const char* symbol;
    void* replacement;
    void* original = nullptr;
    int patched = 0;
};

uintptr_t loadedAddress(uintptr_t base, ElfW(Addr) value) {
    const uintptr_t address = static_cast<uintptr_t>(value);
    return base != 0 && address < base ? base + address : address;
}

bool patchGotSlot(void** slot, void* replacement, void** original) {
    if (slot == nullptr || replacement == nullptr) return false;
    const long pageSize = sysconf(_SC_PAGESIZE);
    if (pageSize <= 0) return false;
    const uintptr_t page = reinterpret_cast<uintptr_t>(slot)
            & ~static_cast<uintptr_t>(pageSize - 1);
    if (mprotect(reinterpret_cast<void*>(page), static_cast<size_t>(pageSize),
                 PROT_READ | PROT_WRITE) != 0) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "mprotect GOT failed errno=%d", errno);
        return false;
    }
    void* previous = __atomic_load_n(slot, __ATOMIC_ACQUIRE);
    __atomic_store_n(slot, replacement, __ATOMIC_RELEASE);
    mprotect(reinterpret_cast<void*>(page), static_cast<size_t>(pageSize), PROT_READ);
    if (original != nullptr && *original == nullptr) *original = previous;
    return previous != nullptr;
}

int patchLoadedLibrary(struct dl_phdr_info* info, size_t, void* data) {
    auto* request = static_cast<GotHookRequest*>(data);
    if (info == nullptr || request == nullptr || request->library == nullptr) return 0;
    const char* loaded = info->dlpi_name == nullptr ? "" : info->dlpi_name;
    const char* loadedBase = std::strrchr(loaded, '/');
    loadedBase = loadedBase == nullptr ? loaded : loadedBase + 1;
    const char* requestedBase = std::strrchr(request->library->c_str(), '/');
    requestedBase = requestedBase == nullptr ? request->library->c_str() : requestedBase + 1;
    if (*loaded == '\0' || (std::strcmp(loaded, request->library->c_str()) != 0
            && std::strcmp(loadedBase, requestedBase) != 0)) return 0;

    const uintptr_t base = static_cast<uintptr_t>(info->dlpi_addr);
    ElfW(Dyn)* dynamic = nullptr;
    for (ElfW(Half) i = 0; i < info->dlpi_phnum; ++i) {
        if (info->dlpi_phdr[i].p_type == PT_DYNAMIC) {
            dynamic = reinterpret_cast<ElfW(Dyn)*>(base + info->dlpi_phdr[i].p_vaddr);
            break;
        }
    }
    if (dynamic == nullptr) return 1;

    ElfW(Sym)* symbols = nullptr;
    const char* strings = nullptr;
    ElfW(Rela)* relocations = nullptr;
    size_t relocationSize = 0;
    for (ElfW(Dyn)* item = dynamic; item->d_tag != DT_NULL; ++item) {
        switch (item->d_tag) {
            case DT_SYMTAB:
                symbols = reinterpret_cast<ElfW(Sym)*>(loadedAddress(base, item->d_un.d_ptr));
                break;
            case DT_STRTAB:
                strings = reinterpret_cast<const char*>(loadedAddress(base, item->d_un.d_ptr));
                break;
            case DT_JMPREL:
                relocations = reinterpret_cast<ElfW(Rela)*>(loadedAddress(base, item->d_un.d_ptr));
                break;
            case DT_PLTRELSZ:
                relocationSize = static_cast<size_t>(item->d_un.d_val);
                break;
            default:
                break;
        }
    }
    if (symbols == nullptr || strings == nullptr || relocations == nullptr) return 1;

    const size_t count = relocationSize / sizeof(ElfW(Rela));
    for (size_t i = 0; i < count; ++i) {
        const size_t symbolIndex = static_cast<size_t>(ELF64_R_SYM(relocations[i].r_info));
        const char* name = strings + symbols[symbolIndex].st_name;
        if (name != nullptr && std::strcmp(name, request->symbol) == 0) {
            auto** slot = reinterpret_cast<void**>(base + relocations[i].r_offset);
            if (patchGotSlot(slot, request->replacement, &request->original)) request->patched++;
        }
    }
    return 1;
}

template<typename Fn>
bool hookGotSymbol(const std::string& library, const char* symbol,
                   void* replacement, Fn* original) {
    GotHookRequest request{&library, symbol, replacement};
    dl_iterate_phdr(patchLoadedLibrary, &request);
    if (original != nullptr) *original = reinterpret_cast<Fn>(request.original);
    __android_log_print(request.patched > 0 ? ANDROID_LOG_INFO : ANDROID_LOG_ERROR, kTag,
                        "GOT hook library=%s symbol=%s patched=%d original=%p",
                        library.c_str(), symbol, request.patched, request.original);
    return request.patched > 0 && request.original != nullptr;
}

}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_bridge_NativeBridge_initialize(JNIEnv* env, jclass, jstring gameLibrary) {
    const std::string library = takeString(env, gameLibrary);
    std::lock_guard<std::mutex> lock(gMutex);
    return resolveGameLocked(library.c_str()) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_bridge_NativeBridge_launch(JNIEnv* env, jclass, jstring gameLibrary, jstring path, jboolean useMaps) {
    const std::string library = takeString(env, gameLibrary);
    const std::string gamePath = takeString(env, path);
    if (gamePath.empty()) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "launch rejected: empty path");
        return JNI_FALSE;
    }

    GetScene getScene = nullptr;
    StartupFrom startupFrom = nullptr;
    {
        std::lock_guard<std::mutex> lock(gMutex);
        if (!resolveGameLocked(library.c_str())) return JNI_FALSE;
        getScene = gGame.getScene;
        startupFrom = gGame.startupFrom;
    }
    void* scene = getScene();
    if (scene == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "launch rejected: TVPMainScene unavailable");
        return JNI_FALSE;
    }
    // The original bridge accepted this argument but did not read it before
    // dispatching startupFrom. Preserve the ABI while making that explicit.
    (void) useMaps;
    LegacyCowString legacyPath(gamePath);
    if (legacyPath.data == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "launch rejected: allocate path failed");
        return JNI_FALSE;
    }
    startupFrom(scene, legacyPath);
    const bool readySignal = installGameReadySignal(scene);
    __android_log_print(ANDROID_LOG_INFO, kTag, "started %s from %s readySignal=%d", library.c_str(), gamePath.c_str(), readySignal);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_bridge_NativeBridge_isLaunchSceneReady(JNIEnv* env, jclass, jstring gameLibrary) {
    const std::string library = takeString(env, gameLibrary);
    GetScene getScene = nullptr;
    {
        std::lock_guard<std::mutex> lock(gMutex);
        if (!resolveGameLocked(library.c_str())) return JNI_FALSE;
        getScene = gGame.getScene;
    }
    return isLaunchSceneReady(getScene()) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_bridge_NativeBridge_interceptor(JNIEnv* env, jclass, jstring prefix) {
    const std::string value = takeString(env, prefix);
    std::lock_guard<std::mutex> lock(gMutex);
    gPathPrefix = value;
    __android_log_print(ANDROID_LOG_INFO, kTag, "SAF open prefix=%s", gPathPrefix.c_str());
}

extern "C" JNIEXPORT jint JNICALL
Java_bridge_NativeBridge_relocate(JNIEnv*, jclass) {
    std::string library;
    {
        std::lock_guard<std::mutex> lock(gMutex);
        library = gGame.library;
        if (gPathPrefix.empty()) {
            __android_log_print(ANDROID_LOG_WARN, kTag, "SAF open hook skipped: empty prefix");
            return 0;
        }
    }
    if (library.empty()) return 0;

    const bool openHooked = gOriginalOpen != nullptr
            || hookGotSymbol(library, "open", reinterpret_cast<void*>(hookedOpen), &gOriginalOpen);
    const bool open64Hooked = gOriginalOpen64 != nullptr
            || hookGotSymbol(library, "open64", reinterpret_cast<void*>(hookedOpen64), &gOriginalOpen64);
    const bool fopenHooked = gOriginalFopen != nullptr
            || hookGotSymbol(library, "fopen", reinterpret_cast<void*>(hookedFopen), &gOriginalFopen);
    const bool fopen64Hooked = gOriginalFopen64 != nullptr
            || hookGotSymbol(library, "fopen64", reinterpret_cast<void*>(hookedFopen64), &gOriginalFopen64);
    const bool statHooked = gOriginalStat != nullptr
            || hookGotSymbol(library, "stat", reinterpret_cast<void*>(hookedStat), &gOriginalStat);
    const bool lstatHooked = gOriginalLstat != nullptr
            || hookGotSymbol(library, "lstat", reinterpret_cast<void*>(hookedLstat), &gOriginalLstat);
    const bool stat64Hooked = gOriginalStat64 != nullptr
            || hookGotSymbol(library, "stat64", reinterpret_cast<void*>(hookedStat64), &gOriginalStat64);
    const bool lstat64Hooked = gOriginalLstat64 != nullptr
            || hookGotSymbol(library, "lstat64", reinterpret_cast<void*>(hookedLstat64), &gOriginalLstat64);
    const bool accessHooked = gOriginalAccess != nullptr
            || hookGotSymbol(library, "access", reinterpret_cast<void*>(hookedAccess), &gOriginalAccess);
    const bool renameHooked = gOriginalRename != nullptr
            || hookGotSymbol(library, "rename", reinterpret_cast<void*>(hookedRename), &gOriginalRename);
    const bool unlinkHooked = gOriginalUnlink != nullptr
            || hookGotSymbol(library, "unlink", reinterpret_cast<void*>(hookedUnlink), &gOriginalUnlink);
    const bool removeHooked = gOriginalRemove != nullptr
            || hookGotSymbol(library, "remove", reinterpret_cast<void*>(hookedRemove), &gOriginalRemove);
    const bool mkdirHooked = gOriginalMkdir != nullptr
            || hookGotSymbol(library, "mkdir", reinterpret_cast<void*>(hookedMkdir), &gOriginalMkdir);
    const bool rmdirHooked = gOriginalRmdir != nullptr
            || hookGotSymbol(library, "rmdir", reinterpret_cast<void*>(hookedRmdir), &gOriginalRmdir);
    const bool opendirHooked = gOriginalOpendir != nullptr
            || hookGotSymbol(library, "opendir", reinterpret_cast<void*>(hookedOpendir), &gOriginalOpendir);
    return (openHooked ? 1 : 0) | (open64Hooked ? 2 : 0)
            | (fopenHooked ? 4 : 0) | (fopen64Hooked ? 8 : 0)
            | (statHooked ? 16 : 0) | (lstatHooked ? 32 : 0)
            | (lstat64Hooked ? 64 : 0) | (accessHooked ? 128 : 0)
            | (renameHooked ? 256 : 0) | (unlinkHooked ? 512 : 0)
            | (removeHooked ? 1024 : 0) | (mkdirHooked ? 2048 : 0)
            | (rmdirHooked ? 4096 : 0) | (opendirHooked ? 8192 : 0)
            | (stat64Hooked ? 16384 : 0);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_bridge_NativeBridge_write(JNIEnv* env, jclass, jstring path, jbyteArray bytes) {
    const std::string outputPath = takeString(env, path);
    if (outputPath.empty() || bytes == nullptr) return JNI_FALSE;
    FILE* file = std::fopen(outputPath.c_str(), "wb");
    if (file == nullptr) return JNI_FALSE;
    const jsize length = env->GetArrayLength(bytes);
    jbyte* data = env->GetByteArrayElements(bytes, nullptr);
    if (data == nullptr) {
        std::fclose(file);
        return JNI_FALSE;
    }
    const size_t written = std::fwrite(data, 1, static_cast<size_t>(length), file);
    env->ReleaseByteArrayElements(bytes, data, JNI_ABORT);
    const int closeResult = std::fclose(file);
    return (written == static_cast<size_t>(length) && closeResult == 0) ? JNI_TRUE : JNI_FALSE;
}

// JNI_OnLoad is invoked when libkrkr_bridge.so is loaded via System.loadLibrary("krkr_bridge")
// in Kirikiroid134/139 (KirikiroidLauncherBaseActivity.onLoadNativeLibraries). This must only
// happen in the :kirikiri2 process, where the engine module's classes are visible to the
// application class loader. Loading this library from a non-application context (e.g. an
// isolated native process) will cause FindClass("bridge/NativeBridge") to fail and JNI_OnLoad
// to return JNI_ERR.
extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    gVm = vm;
    // NativeBridge is also exported by the legacy inline-hook library. Bind
    // our bridge methods eagerly, before that library is dlopen'ed by the exit
    // guard, so JNI's lazy symbol lookup can never select the legacy bridge.
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK || env == nullptr) {
        return JNI_ERR;
    }
    jclass bridge = env->FindClass("bridge/NativeBridge");
    if (bridge == nullptr) {
        env->ExceptionClear();
        __android_log_print(ANDROID_LOG_ERROR, kTag,
                            "NativeBridge class unavailable for RegisterNatives; "
                            "ensure libkrkr_bridge.so is only loaded by the :kirikiri2 process "
                            "where the engine module's ClassLoader can resolve bridge/NativeBridge");
        return JNI_ERR;
    }
    JNINativeMethod methods[] = {
            {const_cast<char*>("initialize"), const_cast<char*>("(Ljava/lang/String;)Z"),
             reinterpret_cast<void*>(Java_bridge_NativeBridge_initialize)},
            {const_cast<char*>("launch"), const_cast<char*>("(Ljava/lang/String;Ljava/lang/String;Z)Z"),
             reinterpret_cast<void*>(Java_bridge_NativeBridge_launch)},
            {const_cast<char*>("isLaunchSceneReady"), const_cast<char*>("(Ljava/lang/String;)Z"),
             reinterpret_cast<void*>(Java_bridge_NativeBridge_isLaunchSceneReady)},
            {const_cast<char*>("interceptor"), const_cast<char*>("(Ljava/lang/String;)V"),
             reinterpret_cast<void*>(Java_bridge_NativeBridge_interceptor)},
            {const_cast<char*>("relocate"), const_cast<char*>("()I"),
             reinterpret_cast<void*>(Java_bridge_NativeBridge_relocate)},
            {const_cast<char*>("write"), const_cast<char*>("(Ljava/lang/String;[B)Z"),
             reinterpret_cast<void*>(Java_bridge_NativeBridge_write)},
    };
    if (env->RegisterNatives(bridge, methods, sizeof(methods) / sizeof(methods[0])) != JNI_OK) {
        env->ExceptionClear();
        env->DeleteLocalRef(bridge);
        __android_log_print(ANDROID_LOG_ERROR, kTag, "RegisterNatives failed");
        return JNI_ERR;
    }
    env->DeleteLocalRef(bridge);
    __android_log_print(ANDROID_LOG_INFO, kTag, "NativeBridge methods bound explicitly");
    return JNI_VERSION_1_6;
}
