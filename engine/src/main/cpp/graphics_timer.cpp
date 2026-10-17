// Host-only profiling; no changes to any engine ABI or upstream renderer.
#include <EGL/egl.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#include <jni.h>
#include <array>
#include <cstring>
#include <new>

namespace {
constexpr int kNodes = 8, kDepth = 4;
struct Slot { GLuint id = 0; int node = 0; bool pending = false, invalid = false; uint64_t order = 0; };
struct Timer {
    EGLContext context = EGL_NO_CONTEXT;
    int count = 0, active = -1;
    bool invalidated = false;
    uint64_t order = 0;
    std::array<Slot, kNodes * kDepth> slots{};
    PFNGLGENQUERIESEXTPROC gen = nullptr;
    PFNGLDELETEQUERIESEXTPROC del = nullptr;
    PFNGLBEGINQUERYEXTPROC begin = nullptr;
    PFNGLENDQUERYEXTPROC end = nullptr;
    PFNGLGETQUERYIVEXTPROC get = nullptr;
    PFNGLGETQUERYOBJECTUIVEXTPROC available = nullptr;
    PFNGLGETQUERYOBJECTUI64VEXTPROC result = nullptr;
    bool current() const { return context != EGL_NO_CONTEXT && eglGetCurrentContext() == context; }
    void invalidate() { for (auto& s : slots) if (s.pending) s.invalid = true; }
};
Timer* timer(jlong value) { return reinterpret_cast<Timer*>(value); }
bool extension(const char* list, const char* name) {
    if (!list) return false;
    size_t n = std::strlen(name);
    for (const char* p = std::strstr(list, name); p; p = std::strstr(p + n, name))
        if ((p == list || p[-1] == ' ') && (p[n] == 0 || p[n] == ' ')) return true;
    return false;
}
}
extern "C" JNIEXPORT jlong JNICALL
Java_com_core_gl_GraphicsGpuTimer_nativeCreate(JNIEnv*, jclass, jint count) {
    if (count < 1 || count > kNodes || eglGetCurrentContext() == EGL_NO_CONTEXT ||
        !extension(reinterpret_cast<const char*>(glGetString(GL_EXTENSIONS)), "GL_EXT_disjoint_timer_query")) return 0;
    Timer* t = new (std::nothrow) Timer;
    if (!t) return 0;
    t->context = eglGetCurrentContext(); t->count = count;
#define LOAD(member, type, name) t->member = reinterpret_cast<type>(eglGetProcAddress(name))
    LOAD(gen, PFNGLGENQUERIESEXTPROC, "glGenQueriesEXT");
    LOAD(del, PFNGLDELETEQUERIESEXTPROC, "glDeleteQueriesEXT");
    LOAD(begin, PFNGLBEGINQUERYEXTPROC, "glBeginQueryEXT");
    LOAD(end, PFNGLENDQUERYEXTPROC, "glEndQueryEXT");
    LOAD(get, PFNGLGETQUERYIVEXTPROC, "glGetQueryivEXT");
    LOAD(available, PFNGLGETQUERYOBJECTUIVEXTPROC, "glGetQueryObjectuivEXT");
    LOAD(result, PFNGLGETQUERYOBJECTUI64VEXTPROC, "glGetQueryObjectui64vEXT");
#undef LOAD
    if (!t->gen || !t->del || !t->begin || !t->end || !t->get || !t->available || !t->result) { delete t; return 0; }
    GLint bits = 0; t->get(GL_TIME_ELAPSED_EXT, GL_QUERY_COUNTER_BITS_EXT, &bits);
    if (bits <= 0) { delete t; return 0; }
    for (int i = 0; i < count * kDepth; ++i) {
        auto& s = t->slots[i]; s.node = i / kDepth; t->gen(1, &s.id);
    }
    return reinterpret_cast<jlong>(t);
}
extern "C" JNIEXPORT void JNICALL
Java_com_core_gl_GraphicsGpuTimer_nativePoll(JNIEnv* env, jclass, jlong handle, jdoubleArray values) {
    if (!values) return;
    jsize count = env->GetArrayLength(values);
    if (count < 1 || count > kNodes) return;
    std::array<jdouble, kNodes> ms; ms.fill(-2);
    // Even a lost context/invalid handle reports no new samples, never old values.
    env->SetDoubleArrayRegion(values, 0, count, ms.data());
    Timer* t = timer(handle);
    if (!t || !t->current() || count != t->count) return;
    std::array<uint64_t, kNodes> latest{};
    GLint disjoint = 0; glGetIntegerv(GL_GPU_DISJOINT_EXT, &disjoint);
    if (disjoint || t->invalidated) { t->invalidate(); ms.fill(-1); t->invalidated = false; }
    for (int i = 0; i < t->count * kDepth; ++i) {
        auto& s = t->slots[i]; if (!s.pending) continue;
        GLuint done = 0; t->available(s.id, GL_QUERY_RESULT_AVAILABLE_EXT, &done);
        if (!done) continue; // Never block on GPU completion.
        if (!s.invalid && s.order > latest[s.node]) {
            GLuint64 ns = 0; t->result(s.id, GL_QUERY_RESULT_EXT, &ns);
            latest[s.node] = s.order; ms[s.node] = ns / 1000000.0;
        }
        s.pending = s.invalid = false;
    }
    // Availability/result reads can straddle a disjoint event too.
    glGetIntegerv(GL_GPU_DISJOINT_EXT, &disjoint);
    if (disjoint) { t->invalidate(); ms.fill(-1); }
    env->SetDoubleArrayRegion(values, 0, t->count, ms.data());
}
extern "C" JNIEXPORT void JNICALL
Java_com_core_gl_GraphicsGpuTimer_nativeBegin(JNIEnv*, jclass, jlong handle, jint node) {
    Timer* t = timer(handle);
    if (!t || !t->current() || t->active >= 0 || node < 0 || node >= t->count) return;
    GLint active = 0, disjoint = 0;
    t->get(GL_TIME_ELAPSED_EXT, GL_CURRENT_QUERY_EXT, &active);
    glGetIntegerv(GL_GPU_DISJOINT_EXT, &disjoint);
    if (active || disjoint) { if (disjoint) { t->invalidate(); t->invalidated = true; } return; }
    for (int i = node * kDepth; i < (node + 1) * kDepth; ++i) {
        auto& s = t->slots[i]; if (!s.id || s.pending) continue;
        t->begin(GL_TIME_ELAPSED_EXT, s.id); t->active = i; return;
    }
}
extern "C" JNIEXPORT void JNICALL
Java_com_core_gl_GraphicsGpuTimer_nativeEnd(JNIEnv*, jclass, jlong handle) {
    Timer* t = timer(handle); if (!t || !t->current() || t->active < 0) return;
    t->end(GL_TIME_ELAPSED_EXT);
    auto& s = t->slots[t->active]; s.pending = true; s.order = ++t->order; s.invalid = false;
    t->active = -1;
}
extern "C" JNIEXPORT void JNICALL
Java_com_core_gl_GraphicsGpuTimer_nativeInvalidate(JNIEnv*, jclass, jlong handle) {
    Timer* t = timer(handle); if (t) t->invalidate();
}
extern "C" JNIEXPORT void JNICALL
Java_com_core_gl_GraphicsGpuTimer_nativeDestroy(JNIEnv*, jclass, jlong handle, jboolean current) {
    Timer* t = timer(handle); if (!t) return;
    if (current && t->current()) {
        if (t->active >= 0) t->end(GL_TIME_ELAPSED_EXT);
        for (auto& s : t->slots) if (s.id) t->del(1, &s.id);
    }
    delete t;
}
