// artemis_audio_bridge.cpp
// Artemis 音频暂停/恢复桥接层
// 通过 dlsym 动态查找 libartemis*.so 导出的 CSoundDevice::PauseAllInstance/ResumeAllInstance，
// 避免直接修改二进制。旧版本（libartemis-compatible.so）无此符号时静默跳过。

#include <jni.h>
#include <dlfcn.h>
#include <android/log.h>

#define TAG "ArtemisAudio"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

// artemis::CSoundDevice::PauseAllInstance() / ResumeAllInstance() 的 mangled name
// _ZN7artemis12CSoundDevice16PauseAllInstanceEv
// _ZN7artemis12CSoundDevice17ResumeAllInstanceEv
typedef void (*SoundInstanceFunc)();

static SoundInstanceFunc g_pauseAllInstance = nullptr;
static SoundInstanceFunc g_resumeAllInstance = nullptr;
static bool g_resolved = false;

static void resolveSymbols() {
    if (g_resolved) return;
    g_resolved = true;

    // RTLD_DEFAULT 搜索所有已加载的库（包括 System.loadLibrary 加载的 libartemis*.so）
    g_pauseAllInstance = reinterpret_cast<SoundInstanceFunc>(
            dlsym(RTLD_DEFAULT, "_ZN7artemis12CSoundDevice16PauseAllInstanceEv"));
    g_resumeAllInstance = reinterpret_cast<SoundInstanceFunc>(
            dlsym(RTLD_DEFAULT, "_ZN7artemis12CSoundDevice17ResumeAllInstanceEv"));

    if (g_pauseAllInstance && g_resumeAllInstance) {
        LOGI("CSoundDevice::PauseAllInstance/ResumeAllInstance resolved");
    } else {
        LOGW("CSoundDevice::PauseAllInstance/ResumeAllInstance not found (old engine version?)");
    }
}

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_ies_1net_artemis_ArtemisActivity_nativePauseAllSound(JNIEnv* /*env*/, jobject /*thiz*/) {
    resolveSymbols();
    if (g_pauseAllInstance) {
        g_pauseAllInstance();
        return JNI_TRUE;
    }
    return JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_ies_1net_artemis_ArtemisActivity_nativeResumeAllSound(JNIEnv* /*env*/, jobject /*thiz*/) {
    resolveSymbols();
    if (g_resumeAllInstance) {
        g_resumeAllInstance();
        return JNI_TRUE;
    }
    return JNI_FALSE;
}

}
