#include "kirikiroid_library.h"

#include <android/log.h>
#include <dlfcn.h>
#include <jni.h>

#include <algorithm>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <mutex>

namespace {
using namespace kirikiroid;

constexpr char kTag[] = "Kr2Audio";
constexpr uintptr_t kOboeCallbackSlot = 0x1a2c2b8;
constexpr uintptr_t kOboeCallback = 0x978c60;
// The callback's secondary base is 0x98 bytes into the mixer. Its signed PCM
// frame size is at mixer + 0x88. The original callback uses the same field.
constexpr ptrdiff_t kOboeFrameSizeOffset = -0x10;

struct AlcContext;
struct AlcDevice;
using OboeCallback = int32_t (*)(void*, void*, void*, int32_t);
using MakeCurrent = char (*)(AlcContext*);
using CloseDevice = char (*)(AlcDevice*);
using CurrentContext = AlcContext* (*)();
using ContextDevice = AlcDevice* (*)(AlcContext*);
using DeviceAction = void (*)(AlcDevice*);

std::atomic<bool> audioPaused{true};
std::atomic<bool> oboeFramesSuppressed{false};
std::mutex openAlMutex;
OboeCallback originalOboeCallback = nullptr;
MakeCurrent makeCurrent = nullptr;
CloseDevice closeDevice = nullptr;
CurrentContext currentContext = nullptr;
ContextDevice contextDevice = nullptr;
DeviceAction pauseDevice = nullptr;
DeviceAction resumeDevice = nullptr;
std::vector<AlcDevice*> pausedDevices;

int32_t onOboeAudioReady(void* callback, void* stream, void* output, int32_t frames) {
    if (!audioPaused.load(std::memory_order_acquire)) {
        return originalOboeCallback(callback, stream, output, frames);
    }
    // Supply silence without running the engine mixer, which consumes queued game PCM.
    int32_t frameSize;
    std::memcpy(&frameSize, static_cast<const char*>(callback) + kOboeFrameSizeOffset, sizeof(frameSize));
    if (frames > 0 && frameSize > 0) {
        std::memset(output, 0, static_cast<size_t>(frames) * frameSize);
        oboeFramesSuppressed.store(true, std::memory_order_relaxed);
    }
    return 0; // oboe::DataCallbackResult::Continue
}

void pauseOpenAl(AlcContext* context) {
    if (!context) return;
    AlcDevice* device = contextDevice(context);
    if (!device || std::find(pausedDevices.begin(), pausedDevices.end(), device) != pausedDevices.end()) return;
    pausedDevices.push_back(device);
    pauseDevice(device);
}

char onMakeCurrent(AlcContext* context) {
    std::lock_guard<std::mutex> lock(openAlMutex);
    char result = makeCurrent(context);
    if (result && audioPaused.load(std::memory_order_relaxed)) pauseOpenAl(context);
    return result;
}

char onCloseDevice(AlcDevice* device) {
    std::lock_guard<std::mutex> lock(openAlMutex);
    char result = closeDevice(device);
    if (result) {
        auto found = std::find(pausedDevices.begin(), pausedDevices.end(), device);
        if (found != pausedDevices.end()) pausedDevices.erase(found);
    }
    return result;
}

bool installOpenAl(const Image& image, Kernel kernel, void* handle) {
    makeCurrent = reinterpret_cast<MakeCurrent>(dlsym(handle, "alcMakeContextCurrent"));
    closeDevice = reinterpret_cast<CloseDevice>(dlsym(handle, "alcCloseDevice"));
    currentContext = reinterpret_cast<CurrentContext>(dlsym(handle, "alcGetCurrentContext"));
    contextDevice = reinterpret_cast<ContextDevice>(dlsym(handle, "alcGetContextsDevice"));
    pauseDevice = reinterpret_cast<DeviceAction>(dlsym(handle, "alcDevicePauseSOFT"));
    resumeDevice = reinterpret_cast<DeviceAction>(dlsym(handle, "alcDeviceResumeSOFT"));
    if (!makeCurrent || !closeDevice || !currentContext || !contextDevice || !pauseDevice || !resumeDevice) return false;

    // These are the checked JUMP_SLOT relocations of each identified library.
    const uintptr_t closeSlot = kernel == Kernel::V139 ? 0x1a924b8 : 0x1a0ec38;
    const uintptr_t currentSlot = kernel == Kernel::V139 ? 0x1a92bf8 : 0x1a0f310;
    std::vector<Change> changes;
    if (!collect(image, changes, reinterpret_cast<void**>(image.base + closeSlot),
                 reinterpret_cast<void*>(closeDevice), reinterpret_cast<void*>(onCloseDevice)) ||
        !collect(image, changes, reinterpret_cast<void**>(image.base + currentSlot),
                 reinterpret_cast<void*>(makeCurrent), reinterpret_cast<void*>(onMakeCurrent))) return false;
    if (change(changes[0], false) && change(changes[1], false)) return true;
    change(changes[1], true);
    change(changes[0], true);
    return false;
}

bool installOboe(const Image& image) {
    originalOboeCallback = reinterpret_cast<OboeCallback>(image.base + kOboeCallback);
    std::vector<Change> changes;
    if (!collect(image, changes, reinterpret_cast<void**>(image.base + kOboeCallbackSlot),
                 reinterpret_cast<void*>(originalOboeCallback), reinterpret_cast<void*>(onOboeAudioReady))) return false;
    if (change(changes.front(), false)) return true;
    change(changes.front(), true);
    return false;
}

bool openAlInstalled = false;
bool installed = false;

void install(const std::string& path) {
    if (installed) return;
    void* handle = dlopen(path.c_str(), RTLD_NOW | RTLD_NOLOAD);
    if (!handle) {
        __android_log_print(ANDROID_LOG_WARN, kTag, "Loaded Kirikiroid2 library is unavailable");
        return;
    }
    Dl_info info{};
    Image image;
    image.path = path;
    if (dladdr(dlsym(handle, "alcOpenDevice"), &info) && info.dli_fbase) {
        image.base = reinterpret_cast<uintptr_t>(info.dli_fbase);
        dl_iterate_phdr(findImage, &image);
    }
    Kernel kernel = identify(image);
    if (kernel != Kernel::Unknown) {
        openAlInstalled = installOpenAl(image, kernel, handle);
        bool oboeInstalled = kernel == Kernel::V139 && installOboe(image);
        installed = true;
        __android_log_print(openAlInstalled && (kernel == Kernel::V134 || oboeInstalled)
                           ? ANDROID_LOG_INFO : ANDROID_LOG_WARN,
                           kTag, "Native audio control OpenAL=%d Oboe=%d", openAlInstalled, oboeInstalled);
    } else {
        __android_log_print(ANDROID_LOG_WARN, kTag, "Unrecognized Kirikiroid2 audio library");
    }
    dlclose(handle);
}
}  // namespace

extern "C" JNIEXPORT void JNICALL
Java_org_tvp_kirikiri2_KR2Activity_nativeInstallAudioControl(JNIEnv* env, jclass, jstring libraryPath) {
    const char* path = env->GetStringUTFChars(libraryPath, nullptr);
    if (!path) return;
    install(path);
    env->ReleaseStringUTFChars(libraryPath, path);
}

extern "C" JNIEXPORT void JNICALL
Java_org_tvp_kirikiri2_KR2Activity_nativeSetAudioPaused(JNIEnv*, jclass, jboolean paused) {
    std::lock_guard<std::mutex> lock(openAlMutex);
    bool wasPaused = audioPaused.exchange(paused, std::memory_order_acq_rel);
    if (wasPaused == static_cast<bool>(paused)) return;
    if (paused) {
        if (openAlInstalled) pauseOpenAl(currentContext());
        __android_log_print(ANDROID_LOG_DEBUG, kTag, "Background audio paused, OpenAL devices=%zu", pausedDevices.size());
    } else {
        for (AlcDevice* device : pausedDevices) resumeDevice(device);
        __android_log_print(ANDROID_LOG_DEBUG, kTag, "Foreground audio restored, OpenAL devices=%zu Oboe PCM held=%d",
                            pausedDevices.size(), oboeFramesSuppressed.exchange(false, std::memory_order_relaxed));
        pausedDevices.clear();
    }
}
