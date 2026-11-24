// Fixed official Artemis binaries keep their original GLES context and game loop.
// Only this library's PLT calls are redirected; libEGL and other engines are untouched.
#include "artemis_official_graphics.h"
#include "artemis_host_class.h"
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <android/input.h>
#include <android/native_window_jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <elf.h>
#include <link.h>
#include <sys/mman.h>
#include <unistd.h>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <cstring>
#include <map>
#include <memory>
#include <new>
#include <mutex>
#include <limits>
#include <set>
#include <iterator>
#include <unordered_map>
#include <unordered_set>
#include <vector>
#include <algorithm>

#ifndef EGL_OPENGL_ES3_BIT_KHR
#define EGL_OPENGL_ES3_BIT_KHR 0x0040
#endif

// GL_KHR_robustness / GLES 3.2; not declared by the GLES3 (3.0) headers this file includes.
#ifndef GL_CONTEXT_LOST_KHR
#define GL_CONTEXT_LOST_KHR 0x0507
#endif

namespace {
constexpr const char* kDisplay = "_ZN7artemis6CStage7DisplayEv";
// Stage canvas ceiling for the single-context path (RGBA8 + D24S8, 8 bytes per pixel),
// counted per surface; independent of the Java-side 256 MiB whole-chain filter budget
// (CnnPipeline.TARGET_MEMORY_BUDGET_BYTES, which covers every filter target of the chain).
// The stage canvas plus the full filter chain therefore peaks around 768 MiB on top of the
// engine's own allocations, and an oversized canvas falls back to direct output instead of
// chaining an unbounded allocation onto the filter budget.
constexpr uint64_t kBudgetBytes = 512ULL * 1024 * 1024;
using StageDisplay = void (*)(void*);
StageDisplay stageDisplay = nullptr;
JavaVM* vm = nullptr;
jclass bridge = nullptr;
jmethodID connectMethod = nullptr, submittedMethod = nullptr, healthyMethod = nullptr,
        disconnectMethod = nullptr, resizeMethod = nullptr, statusMethod = nullptr,
        directRestoredMethod = nullptr, outputSizeMethod = nullptr,
        renderStageMethod = nullptr, contextDestroyedMethod = nullptr;
struct Env {
    JNIEnv* value = nullptr; bool attached = false;
    Env() {
        if (vm && vm->GetEnv(reinterpret_cast<void**>(&value), JNI_VERSION_1_6) != JNI_OK) {
            attached = vm->AttachCurrentThread(&value, nullptr) == JNI_OK;
            if (!attached) value = nullptr;
        }
    }
    ~Env() { if (attached) vm->DetachCurrentThread(); }
    bool clear() { if (!value || !value->ExceptionCheck()) return false;value->ExceptionClear();return true; }
};
struct SurfaceState;
using SurfaceRegistry = std::map<EGLSurface, std::shared_ptr<SurfaceState>>;
struct SurfaceState {
    // Allocate before connecting any window. While parked here, mapped() stays null
    // so the spare node cannot form a shared_ptr cycle back to this state.
    SurfaceRegistry::node_type directAlias;
    // The original handle remains a live alias until the engine destroys it.
    // EGL/producer fields are guarded separately from the registry: no registry
    // lock may span JNI, EGL, or the original engine's Display callback.
    // These hooks belong only to the official ELF's PLT. The Java consumer
    // worker calls its own EGL entries, so JNI cannot reenter operationMutex.
    // Permit valid EGL thread migration; serialize operations, not thread IDs.
    std::mutex operationMutex;
    EGLSurface actual = EGL_NO_SURFACE, retainedProducer = EGL_NO_SURFACE;
    EGLConfig config = nullptr;
    ANativeWindow* screen = nullptr;
    ANativeWindow* producer = nullptr;
    PFNEGLPRESENTATIONTIMEANDROIDPROC presentationTime = nullptr;
    bool direct = false, destroyed = false, singleContext = false;
    int producerWidth = 0, producerHeight = 0;
};
std::mutex surfacesMutex;
// Serialize create/retire while a destroyed handle is being removed from the
// alias registry. EGL must not recycle it into a concurrent official create.
// Lock order: state operationMutex -> surfaceLifecycleMutex -> surfacesMutex;
// create has no published state yet and never acquires operationMutex.
std::mutex surfaceLifecycleMutex;
SurfaceRegistry surfaces;
std::atomic<int> logicalWidth{0}, logicalHeight{0};
// Initial display bounds until the consumer publishes its first successful frame.
std::atomic<uint64_t> screenSize{0}, contextGeneration{1}, externalMutationCounter{1};
std::atomic<bool> directOutput{false}, allowed{false}, nativeFailed{false}, gesture{false}, singleContextMode{false}, es3UpgradeFailed{false};
// Set at install when the engine imports a GLES3 content-change entry point outside the
// hook/deny-verified surface: single-context mode is refused and the producer
// path is used instead. Registration of the hooks themselves still proceeds.
std::atomic<bool> singleContextDenied{false};
std::atomic_flag installing = ATOMIC_FLAG_INIT;
thread_local bool stageFrame = false, inHostGl = false;
thread_local int stageTargetWidth = 0, stageTargetHeight = 0;
void resetStageRedirectOnScopeExit();
// BeginRender may destroy and recreate all official EGL resources inside Display.
// Keep this target separate from the current surface/input dimensions.
struct StageScope {
    const bool previousFrame = stageFrame;
    const int previousWidth = stageTargetWidth, previousHeight = stageTargetHeight;
    StageScope(int width, int height) {
        stageFrame = false;
        stageTargetWidth = width;
        stageTargetHeight = height;
    }
    ~StageScope() {
        if (stageFrame && !previousFrame) resetStageRedirectOnScopeExit();
        stageFrame = previousFrame;
        stageTargetWidth = previousWidth;
        stageTargetHeight = previousHeight;
    }
};
void status(int code) {
    if (code == 3) nativeFailed = true;
    // A healthy direct surface is not evidence that raw-frame registration or
    // the consumer recovered. Preserve the cause across direct-output fallback.
    if (code == 5 && nativeFailed.load()) return;
    Env env;if(env.value && bridge){env.value->CallStaticVoidMethod(bridge,statusMethod,code);env.clear();}
}
std::shared_ptr<SurfaceState> findSurface(EGLSurface surface) {
    std::lock_guard<std::mutex> lock(surfacesMutex);
    auto found = surfaces.find(surface);
    return found == surfaces.end() ? nullptr : found->second;
}
SurfaceRegistry::node_type allocateSurfaceEntry() {
    SurfaceRegistry pending;
    pending.emplace(EGL_NO_SURFACE, nullptr);
    return pending.extract(EGL_NO_SURFACE);
}
// Same default allocator and pointer comparator: node insertion does not allocate.
// Keep rejected entries reusable without retaining their proposed owner.
bool registerSurfaceEntry(SurfaceRegistry::node_type& entry, EGLSurface surface,
                          const std::shared_ptr<SurfaceState>& state) {
    if (entry.empty()) return false;
    entry.key() = surface;
    entry.mapped() = state;
    std::lock_guard<std::mutex> lock(surfacesMutex);
    auto inserted = surfaces.insert(std::move(entry));
    if (inserted.inserted) return true;
    entry = std::move(inserted.node);
    entry.mapped().reset();
    return false;
}
bool noSurfaces() {
    std::lock_guard<std::mutex> lock(surfacesMutex);
    return surfaces.empty();
}
// Keep these values aligned with ArtemisOfficialDisconnect. A pending worker
// still owns the screen queue and is not an EGL failure in the official engine.
enum class DisconnectResult { Detached = 0, Pending = 1, Failed = 2 };
enum class RestoreResult { Restored, Pending, Failed };
DisconnectResult disconnect() {
    Env env;if(!env.value)return DisconnectResult::Failed;
    const jint result = env.value->CallStaticIntMethod(bridge,disconnectMethod);
    if(env.clear())return DisconnectResult::Failed;
    if(result == static_cast<jint>(DisconnectResult::Detached))return DisconnectResult::Detached;
    if(result == static_cast<jint>(DisconnectResult::Pending))return DisconnectResult::Pending;
    return DisconnectResult::Failed;
}
bool healthy() {
    if (nativeFailed.load()) return false;
    Env env;if(!env.value)return false;
    bool result = env.value->CallStaticBooleanMethod(bridge,healthyMethod);return !env.clear() && result;
}
uint64_t packScreenSize(int width,int height) {
    return (static_cast<uint64_t>(width)<<32)|static_cast<uint32_t>(height);
}
uint64_t currentScreenSize() {
    // This getter only reads Java volatile state. Never wait for the EGL worker:
    // Display can hold operationMutex while requesting a logical producer resize.
    // Use the returned pair directly, so concurrent readers cannot regress a cache.
    const uint64_t initial=screenSize.load();
    Env env;
    if(!env.value || !bridge || !outputSizeMethod)return initial;
    jlong packed=env.value->CallStaticLongMethod(bridge,outputSizeMethod);
    if(env.clear() || packed<=0)return initial;
    uint64_t size=static_cast<uint64_t>(packed);
    uint32_t width=static_cast<uint32_t>(size>>32),height=static_cast<uint32_t>(size);
    if(width==0 || height==0 || width>std::numeric_limits<int>::max() || height>std::numeric_limits<int>::max())return initial;
    return size;
}
void directRestored() {
    Env env;
    if(env.value){env.value->CallStaticVoidMethod(bridge,directRestoredMethod);env.clear();}
    status(5);
}
struct HostGlScope {
    const bool previous = inHostGl;
    HostGlScope() { inHostGl = true; }
    ~HostGlScope() { inHostGl = previous; }
};

// Bound matches the Java-side GlErrors.MAX_GL_ERRORS discipline (and drainStageGlErrors below).
constexpr int kMaxGlErrorDrain = 16;

// Bounded drain, never latches: consume at most kMaxGlErrorDrain error records and stop
// early on GL_CONTEXT_LOST_KHR. Under GL_KHR_robustness, glGetError clears error flags
// one at a time in arbitrary order — so GL_CONTEXT_LOST_KHR is not guaranteed on the first call
// nor guaranteed to be followed immediately by GL_NO_ERROR if other flags are pending, though once
// no other errors remain that single context-lost flag clears on read (unless a broken driver or
// persistent fault stream keeps emitting errors). Bounding to kMaxGlErrorDrain and returning early on
// GL_CONTEXT_LOST_KHR guarantees the render thread cannot spin permanently in either case. Returns
// true iff GL_CONTEXT_LOST_KHR was observed so callers can abandon stage resources and transition to
// NATIVE_FAILED/directOutput instead of silently swallowing context loss.
bool drainGlErrorsBounded() {
    for (int i = 0; i < kMaxGlErrorDrain; ++i) {
        const GLenum error = glGetError();
        if (error == GL_NO_ERROR) return false;
        if (error == GL_CONTEXT_LOST_KHR) return true;
    }
    return false;
}

struct StageGlState {
    static constexpr size_t kUnits = 8;
    static constexpr GLenum kCaps[] = {
        GL_BLEND, GL_DEPTH_TEST, GL_STENCIL_TEST, GL_SCISSOR_TEST,
        GL_CULL_FACE, GL_RASTERIZER_DISCARD, GL_SAMPLE_ALPHA_TO_COVERAGE,
        GL_SAMPLE_COVERAGE, GL_DITHER
    };
    GLint readFbo = 0, drawFbo = 0, renderbuffer = 0, program = 0, vao = 0, active = GL_TEXTURE0;
    GLint arrayBuffer = 0, elementArrayBuffer = 0, unpackBuffer = 0;
    GLint unpackAlign = 4, packAlign = 4;
    GLint viewport[4]{}, scissorBox[4]{};
    GLint textures[kUnits]{}, samplers[kUnits]{};
    GLint stencilFrontFunc = GL_ALWAYS, stencilFrontRef = 0, stencilFrontValueMask = ~0, stencilFrontMask = ~0;
    GLint stencilBackFunc = GL_ALWAYS, stencilBackRef = 0, stencilBackValueMask = ~0, stencilBackMask = ~0;
    GLint stencilFail = GL_KEEP, stencilZFail = GL_KEEP, stencilZPass = GL_KEEP;
    GLint stencilBackFail = GL_KEEP, stencilBackZFail = GL_KEEP, stencilBackZPass = GL_KEEP;
    GLint depthFunc = GL_LESS;
    GLboolean depthMask = GL_TRUE, colorMask[4]{GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE};
    GLfloat clearColor[4]{};
    GLboolean enabled[sizeof(kCaps) / sizeof(kCaps[0])]{};
    bool useIndexedBuffer0 = false;
    bool contextLost = false;
    void (*colorMaskiFn)(GLuint, GLboolean, GLboolean, GLboolean, GLboolean) = nullptr;
    void (*enableiFn)(GLenum, GLuint) = nullptr;
    void (*disableiFn)(GLenum, GLuint) = nullptr;
    GLboolean (*isEnablediFn)(GLenum, GLuint) = nullptr;
    // Restore-surface coverage note (two-sided capture complete):
    // Host-side GL work in this file (stage FBO creation in ensureStageResources, the swap
    // present draw, redirect binds) never writes glCullFace/glFrontFace parameters,
    // glBlendColor, glDepthRange/glDepthRangef, glLineWidth, glPolygonOffset, or
    // glSampleCoverage — those engine-set values pass through untouched. Both front and
    // back stencil ops are captured (GL_STENCIL[_BACK]_FAIL / PASS_DEPTH_FAIL /
    // PASS_DEPTH_PASS) and written back symmetrically via glStencilOpSeparate(GL_FRONT/BACK,
    // ...), preserving asymmetric ops if an engine ever uses glStencilOpSeparate. Caps that
    // gate those states (GL_CULL_FACE, GL_BLEND, ...) are disabled for our draws and
    // restored through kCaps below.

    static bool hasGlExtension(const char* name) {
        if (!name) return false;
        GLint extCount = 0;
        glGetIntegerv(GL_NUM_EXTENSIONS, &extCount);
        for (GLint i = 0; i < extCount; ++i) {
            const auto* ext = reinterpret_cast<const char*>(glGetStringi(GL_EXTENSIONS, static_cast<GLuint>(i)));
            if (ext && std::strcmp(ext, name) == 0) return true;
        }
        return false;
    }

    StageGlState() {
        glGetIntegerv(GL_READ_FRAMEBUFFER_BINDING, &readFbo);
        glGetIntegerv(GL_DRAW_FRAMEBUFFER_BINDING, &drawFbo);
        glGetIntegerv(GL_RENDERBUFFER_BINDING, &renderbuffer);
        glGetIntegerv(GL_CURRENT_PROGRAM, &program);
        glGetIntegerv(GL_VERTEX_ARRAY_BINDING, &vao);
        glGetIntegerv(GL_ACTIVE_TEXTURE, &active);
        glGetIntegerv(GL_ARRAY_BUFFER_BINDING, &arrayBuffer);
        glGetIntegerv(GL_ELEMENT_ARRAY_BUFFER_BINDING, &elementArrayBuffer);
        glGetIntegerv(GL_PIXEL_UNPACK_BUFFER_BINDING, &unpackBuffer);
        glGetIntegerv(GL_UNPACK_ALIGNMENT, &unpackAlign);
        glGetIntegerv(GL_PACK_ALIGNMENT, &packAlign);
        glGetIntegerv(GL_VIEWPORT, viewport);
        glGetIntegerv(GL_SCISSOR_BOX, scissorBox);
        glGetIntegerv(GL_STENCIL_FUNC, &stencilFrontFunc);
        glGetIntegerv(GL_STENCIL_REF, &stencilFrontRef);
        glGetIntegerv(GL_STENCIL_VALUE_MASK, &stencilFrontValueMask);
        glGetIntegerv(GL_STENCIL_WRITEMASK, &stencilFrontMask);
        glGetIntegerv(GL_STENCIL_BACK_FUNC, &stencilBackFunc);
        glGetIntegerv(GL_STENCIL_BACK_REF, &stencilBackRef);
        glGetIntegerv(GL_STENCIL_BACK_VALUE_MASK, &stencilBackValueMask);
        glGetIntegerv(GL_STENCIL_BACK_WRITEMASK, &stencilBackMask);
        glGetIntegerv(GL_STENCIL_FAIL, &stencilFail);
        glGetIntegerv(GL_STENCIL_PASS_DEPTH_FAIL, &stencilZFail);
        glGetIntegerv(GL_STENCIL_PASS_DEPTH_PASS, &stencilZPass);
        glGetIntegerv(GL_STENCIL_BACK_FAIL, &stencilBackFail);
        glGetIntegerv(GL_STENCIL_BACK_PASS_DEPTH_FAIL, &stencilBackZFail);
        glGetIntegerv(GL_STENCIL_BACK_PASS_DEPTH_PASS, &stencilBackZPass);
        glGetIntegerv(GL_DEPTH_FUNC, &depthFunc);
        glGetBooleanv(GL_DEPTH_WRITEMASK, &depthMask);
        glGetBooleanv(GL_COLOR_WRITEMASK, colorMask);
        glGetFloatv(GL_COLOR_CLEAR_VALUE, clearColor);
        for (size_t i = 0; i < sizeof(kCaps) / sizeof(kCaps[0]); ++i) {
            enabled[i] = glIsEnabled(kCaps[i]);
        }
        // Probe indexed draw-buffer-0 state (GLES 3.2 core or GL_EXT_draw_buffers_indexed)
        // so displayStage only touches/restores buffer 0 instead of overwriting buffers 1..N.
        GLint glMajor = 0, glMinor = 0;
        glGetIntegerv(GL_MAJOR_VERSION, &glMajor);
        glGetIntegerv(GL_MINOR_VERSION, &glMinor);
        const bool isEs32 = glMajor > 3 || (glMajor == 3 && glMinor >= 2);
        const bool hasIndexedExt = !isEs32 && hasGlExtension("GL_EXT_draw_buffers_indexed");
        if (isEs32 || hasIndexedExt) {
            colorMaskiFn = reinterpret_cast<decltype(colorMaskiFn)>(
                    eglGetProcAddress(isEs32 ? "glColorMaski" : "glColorMaskiEXT"));
            enableiFn = reinterpret_cast<decltype(enableiFn)>(
                    eglGetProcAddress(isEs32 ? "glEnablei" : "glEnableiEXT"));
            disableiFn = reinterpret_cast<decltype(disableiFn)>(
                    eglGetProcAddress(isEs32 ? "glDisablei" : "glDisableiEXT"));
            isEnablediFn = reinterpret_cast<decltype(isEnablediFn)>(
                    eglGetProcAddress(isEs32 ? "glIsEnabledi" : "glIsEnablediEXT"));
            if (colorMaskiFn && enableiFn && disableiFn && isEnablediFn) {
                // Bounded drain (never unbounded: caps residual engine flags and exits early on
                // GL_CONTEXT_LOST_KHR regardless of whether the driver reports it once per spec or
                // repeatedly); if flags remain, the probe below fails its GL_NO_ERROR check and
                // capture falls back to the non-indexed global colorMask path.
                if (drainGlErrorsBounded()) {
                    contextLost = true;
                } else {
                    GLint mask0[4]{};
                    glGetIntegeri_v(GL_COLOR_WRITEMASK, 0, mask0);
                    const GLboolean blend0 = isEnablediFn(GL_BLEND, 0);
                    const GLenum probeErr = glGetError();
                    if (probeErr == GL_CONTEXT_LOST_KHR) {
                        contextLost = true;
                    } else if (probeErr == GL_NO_ERROR) {
                        for (int c = 0; c < 4; ++c) colorMask[c] = mask0[c] ? GL_TRUE : GL_FALSE;
                        enabled[0] = blend0;
                        useIndexedBuffer0 = true;
                    }
                }
            }
        }
        for (size_t i = 0; i < kUnits; ++i) {
            glActiveTexture(GL_TEXTURE0 + static_cast<GLenum>(i));
            glGetIntegerv(GL_TEXTURE_BINDING_2D, &textures[i]);
            glGetIntegerv(GL_SAMPLER_BINDING, &samplers[i]);
        }
        glActiveTexture(active);
    }

    void restore(GLuint stageFbo = 0) const {
        const bool redirectedDraw = stageFbo != 0 && static_cast<GLuint>(drawFbo) == stageFbo;
        glBindFramebuffer(GL_READ_FRAMEBUFFER,
                (stageFbo != 0 && static_cast<GLuint>(readFbo) == stageFbo) ? 0 : readFbo);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, redirectedDraw ? 0 : drawFbo);
        // Never overwrite the engine's logical stage viewport with physical screen bounds:
        // CGpuRenderer caches m_width/m_height and skips glViewport when surface size stays fixed.
        glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
        glScissor(scissorBox[0], scissorBox[1], scissorBox[2], scissorBox[3]);
        glUseProgram(program);
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, arrayBuffer);
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, elementArrayBuffer);
        glBindBuffer(GL_PIXEL_UNPACK_BUFFER, unpackBuffer);
        glBindRenderbuffer(GL_RENDERBUFFER, renderbuffer);
        glPixelStorei(GL_UNPACK_ALIGNMENT, unpackAlign);
        glPixelStorei(GL_PACK_ALIGNMENT, packAlign);
        glStencilFuncSeparate(GL_FRONT, stencilFrontFunc, stencilFrontRef, static_cast<GLuint>(stencilFrontValueMask));
        glStencilFuncSeparate(GL_BACK, stencilBackFunc, stencilBackRef, static_cast<GLuint>(stencilBackValueMask));
        glStencilMaskSeparate(GL_FRONT, static_cast<GLuint>(stencilFrontMask));
        glStencilMaskSeparate(GL_BACK, static_cast<GLuint>(stencilBackMask));
        glStencilOpSeparate(GL_FRONT, stencilFail, stencilZFail, stencilZPass);
        glStencilOpSeparate(GL_BACK, stencilBackFail, stencilBackZFail, stencilBackZPass);
        glDepthFunc(depthFunc);
        glDepthMask(depthMask);
        if (useIndexedBuffer0) {
            colorMaskiFn(0, colorMask[0], colorMask[1], colorMask[2], colorMask[3]);
            if (enabled[0]) enableiFn(GL_BLEND, 0); else disableiFn(GL_BLEND, 0);
        } else {
            glColorMask(colorMask[0], colorMask[1], colorMask[2], colorMask[3]);
        }
        glClearColor(clearColor[0], clearColor[1], clearColor[2], clearColor[3]);
        for (size_t i = useIndexedBuffer0 ? 1 : 0; i < sizeof(kCaps) / sizeof(kCaps[0]); ++i) {
            if (enabled[i]) glEnable(kCaps[i]); else glDisable(kCaps[i]);
        }
        for (size_t i = 0; i < kUnits; ++i) {
            glActiveTexture(GL_TEXTURE0 + static_cast<GLenum>(i));
            glBindTexture(GL_TEXTURE_2D, textures[i]);
            glBindSampler(static_cast<GLuint>(i), samplers[i]);
        }
        glActiveTexture(active);
    }
};

struct StageResources {
    EGLContext context = EGL_NO_CONTEXT;
    EGLContext lastTrackedContext = EGL_NO_CONTEXT;
    uint64_t generation = 0;
    GLuint fbo = 0, texture = 0, depthStencil = 0, program = 0, vao = 0, sampler = 0;
    GLint boundsLoc = -1, sourceLoc = -1;
    int width = 0, height = 0;
    // Deterministic size/budget rejections are memoized per context generation so
    // an unchanged failing geometry does not repeat GL limit queries or status(7)
    // reports on every draw of the same frame. Cleared on context change.
    int failedWidth = 0, failedHeight = 0;
    bool unavailable = false;

    void abandonTargets() {
        fbo = texture = depthStencil = 0;
        width = height = 0;
    }

    void abandon() {
        abandonTargets();
        program = vao = sampler = 0;
        boundsLoc = sourceLoc = -1;
        context = EGL_NO_CONTEXT;
        generation = 0;
        failedWidth = failedHeight = 0;
        unavailable = false;
    }

    void releaseTargets() {
        if (context != EGL_NO_CONTEXT && context == eglGetCurrentContext()) {
            if (fbo) glDeleteFramebuffers(1, &fbo);
            if (texture) glDeleteTextures(1, &texture);
            if (depthStencil) glDeleteRenderbuffers(1, &depthStencil);
        }
        abandonTargets();
    }

    void release() {
        if (context != EGL_NO_CONTEXT && context == eglGetCurrentContext()) {
            releaseTargets();
            if (vao) glDeleteVertexArrays(1, &vao);
            if (sampler) glDeleteSamplers(1, &sampler);
            if (program) glDeleteProgram(program);
        }
        abandon();
    }
} stageResources;
std::mutex stageResourcesMutex;

GLuint compileStageShader(GLenum type, const char* src, bool* outAllocZero) {
    GLuint s = glCreateShader(type);
    if (!s) {
        if (outAllocZero) *outAllocZero = true;
        return 0;
    }
    glShaderSource(s, 1, &src, nullptr);
    glCompileShader(s);
    GLint ok = 0;
    glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
    if (!ok) { glDeleteShader(s); return 0; }
    return s;
}

struct FrameDirtyTracker {
    static constexpr size_t kUnits = StageGlState::kUnits;
    static constexpr size_t kAttribs = 16;
    static constexpr GLsizei kMaxCpuIndices = 8192;
    static constexpr uint32_t kMaxCpuVertexIndex = 4096;

    struct AttribState {
        bool enabled = false;
        GLint size = 4;
        GLenum type = GL_FLOAT;
        GLboolean normalized = GL_FALSE;
        GLsizei stride = 0;
        const void* pointer = nullptr;
        GLuint buffer = 0;
    };

    static constexpr size_t kMaxOutsideUniforms = 512;
    static constexpr size_t kMaxProgramUniforms = 1024;
    static constexpr size_t kMaxTexParamsPerTexture = 32;
    static constexpr size_t kMaxTrackedCaps = 64;
    uint64_t frameHash = 0, previousHash = 0, lastExternalMutation = 0, nextMutationId = 1;
    int previousW = 0, previousH = 0;
    bool hasPrevious = false, frameValid = true;
    uint32_t drawCount = 0, clearCount = 0;
    EGLContext boundContext = EGL_NO_CONTEXT;
    // Last observed out-of-window state values for delta accounting.
    GLfloat lastClearColor[4]{};
    GLfloat lastClearDepthf = 0.0f;
    GLint lastClearStencil = 0;
    GLint lastViewport[4]{};
    GLenum lastCullFace = GL_BACK, lastFrontFace = GL_CCW;
    GLenum lastBlendFunc[4] = {GL_ONE, GL_ZERO, GL_ONE, GL_ZERO};
    GLenum lastBlendEq[2] = {GL_FUNC_ADD, GL_FUNC_ADD};
    GLenum lastStencilFunc = GL_ALWAYS;
    GLint lastStencilRef = 0;
    GLuint lastStencilFuncMask = ~0u;
    GLenum lastStencilOp[3] = {GL_KEEP, GL_KEEP, GL_KEEP};
    GLuint lastStencilWriteMask = ~0u;
    GLenum lastDepthFunc = GL_LESS;
    bool hasClearColor = false, hasClearDepthf = false, hasClearStencil = false;
    bool hasViewport = false, hasCullFace = false, hasFrontFace = false;
    bool hasBlendFunc = false, hasBlendEq = false;
    bool hasStencilFunc = false, hasStencilOp = false, hasStencilMask = false, hasDepthFunc = false;
    std::unordered_map<GLenum, bool> lastCapEnabled;
    bool primitiveRestartFixed = false, primitiveRestartSynced = false;
    GLuint logicalFramebuffer = 0, logicalReadFramebuffer = 0, currentProgram = 0;
    GLuint arrayBuffer = 0, elementArrayBuffer = 0;
    bool arrayBufferSynced = false, elementBufferSynced = false;
    bool attribStateSynced[kAttribs]{};
    size_t activeUnit = 0;
    GLuint boundTextures[kUnits]{};
    AttribState attribs[kAttribs]{};
    std::unordered_map<GLuint, uint64_t> textureVersions;
    std::unordered_map<GLuint, std::unordered_map<GLenum, GLint>> textureParams;
    std::unordered_map<GLuint, uint64_t> bufferVersions;
    std::unordered_map<GLuint, GLuint> fboColorTextures;
    std::unordered_map<GLuint, std::unordered_map<GLint, uint64_t>> lastOutsideUniforms;
    std::unordered_map<GLuint, std::unordered_map<GLint, uint64_t>> programUniforms;
    std::unordered_set<GLuint> programUniformUnknown;
    bool stageRedirected = false, stageReadRedirected = false, stageDrawnThisFrame = false;
    bool stageFailedThisFrame = false, windowWrittenOutsideStage = false;
    bool stageColorClearedAtStart = false, stageColorWriteStarted = false;
    bool stageDepthClearedAtStart = false, stageDepthUsed = false;
    bool stageStencilClearedAtStart = false, stageStencilUsed = false;

    static void mix64(uint64_t& h, uint64_t v) {
        v ^= v >> 33;
        v *= 0xff51afd7ed558ccdULL;
        v ^= v >> 33;
        h ^= v;
        h *= 0x100000001b3ULL;
    }

    static void hashBytes(uint64_t& h, const void* ptr, size_t bytes) {
        const auto* p = static_cast<const uint8_t*>(ptr);
        while (bytes >= 8) {
            uint64_t chunk = 0;
            std::memcpy(&chunk, p, 8);
            mix64(h, chunk);
            p += 8;
            bytes -= 8;
        }
        if (bytes > 0) {
            uint64_t tail = 0;
            std::memcpy(&tail, p, bytes);
            mix64(h, tail ^ (static_cast<uint64_t>(bytes) << 56));
        }
    }

    static size_t attrElementBytes(GLint size, GLenum type) {
        if (size < 1 || size > 4) return 0;
        size_t comp = 0;
        switch (type) {
            case GL_BYTE:
            case GL_UNSIGNED_BYTE: comp = 1; break;
            case GL_SHORT:
            case GL_UNSIGNED_SHORT:
            case GL_HALF_FLOAT: comp = 2; break;
            case GL_INT:
            case GL_UNSIGNED_INT:
            case GL_FLOAT: comp = 4; break;
            default: return 0;
        }
        return static_cast<size_t>(size) * comp;
    }

    void invalidateHistory() {
        hasPrevious = false;
        previousHash = 0;
        previousW = 0;
        previousH = 0;
    }

    void resetAllOnContextChange(EGLContext currentCtx = EGL_NO_CONTEXT, GLuint stageFbo = 0) {
        invalidateHistory();
        textureVersions.clear();
        textureParams.clear();
        bufferVersions.clear();
        fboColorTextures.clear();
        lastOutsideUniforms.clear();
        programUniforms.clear();
        programUniformUnknown.clear();
        boundContext = currentCtx;
        logicalFramebuffer = 0;
        logicalReadFramebuffer = 0;
        currentProgram = 0;
        activeUnit = 0;
        if (currentCtx != EGL_NO_CONTEXT) {
            GLint actualDraw = 0, actualRead = 0, actualProg = 0, actualUnit = GL_TEXTURE0;
            glGetIntegerv(GL_DRAW_FRAMEBUFFER_BINDING, &actualDraw);
            glGetIntegerv(GL_READ_FRAMEBUFFER_BINDING, &actualRead);
            glGetIntegerv(GL_CURRENT_PROGRAM, &actualProg);
            glGetIntegerv(GL_ACTIVE_TEXTURE, &actualUnit);
            const GLuint drawId = actualDraw > 0 ? static_cast<GLuint>(actualDraw) : 0;
            const GLuint readId = actualRead > 0 ? static_cast<GLuint>(actualRead) : 0;
            logicalFramebuffer = (stageFbo != 0 && drawId == stageFbo) ? 0 : drawId;
            logicalReadFramebuffer = (stageFbo != 0 && readId == stageFbo) ? 0 : readId;
            currentProgram = actualProg > 0 ? static_cast<GLuint>(actualProg) : 0;
            activeUnit = (actualUnit >= GL_TEXTURE0) ? static_cast<size_t>(actualUnit - GL_TEXTURE0) : 0;
        }
        arrayBuffer = 0;
        elementArrayBuffer = 0;
        arrayBufferSynced = false;
        elementBufferSynced = false;
        std::memset(attribStateSynced, 0, sizeof(attribStateSynced));
        std::memset(boundTextures, 0, sizeof(boundTextures));
        for (auto& a : attribs) a = {};
        stageRedirected = false;
        stageReadRedirected = false;
        stageDrawnThisFrame = false;
        stageFailedThisFrame = false;
        windowWrittenOutsideStage = false;
        stageColorClearedAtStart = false;
        stageColorWriteStarted = false;
        stageDepthClearedAtStart = false;
        stageDepthUsed = false;
        stageStencilClearedAtStart = false;
        stageStencilUsed = false;
        hasClearColor = hasClearDepthf = hasClearStencil = false;
        hasViewport = hasCullFace = hasFrontFace = false;
        hasBlendFunc = hasBlendEq = false;
        hasStencilFunc = hasStencilOp = hasStencilMask = hasDepthFunc = false;
        lastCapEnabled.clear();
        primitiveRestartFixed = false;
        primitiveRestartSynced = false;
    }

    void onContextSwitched(EGLContext newContext, GLuint ownStageFbo) {
        if (newContext == boundContext) return;
        boundContext = newContext;
        invalidateHistory();
        arrayBuffer = 0;
        elementArrayBuffer = 0;
        arrayBufferSynced = false;
        elementBufferSynced = false;
        std::memset(attribStateSynced, 0, sizeof(attribStateSynced));
        hasClearColor = hasClearDepthf = hasClearStencil = false;
        hasViewport = hasCullFace = hasFrontFace = false;
        hasBlendFunc = hasBlendEq = false;
        hasStencilFunc = hasStencilOp = hasStencilMask = hasDepthFunc = false;
        lastCapEnabled.clear();
        primitiveRestartFixed = false;
        primitiveRestartSynced = false;
        stageRedirected = false;
        stageReadRedirected = false;
        windowWrittenOutsideStage = false;
        if (newContext != EGL_NO_CONTEXT) {
            GLint actualDraw = 0, actualRead = 0, actualProg = 0, actualUnit = GL_TEXTURE0;
            glGetIntegerv(GL_DRAW_FRAMEBUFFER_BINDING, &actualDraw);
            glGetIntegerv(GL_READ_FRAMEBUFFER_BINDING, &actualRead);
            glGetIntegerv(GL_CURRENT_PROGRAM, &actualProg);
            glGetIntegerv(GL_ACTIVE_TEXTURE, &actualUnit);
            const GLuint drawId = actualDraw > 0 ? static_cast<GLuint>(actualDraw) : 0;
            const GLuint readId = actualRead > 0 ? static_cast<GLuint>(actualRead) : 0;
            logicalFramebuffer = (ownStageFbo != 0 && drawId == ownStageFbo) ? 0 : drawId;
            logicalReadFramebuffer = (ownStageFbo != 0 && readId == ownStageFbo) ? 0 : readId;
            currentProgram = actualProg > 0 ? static_cast<GLuint>(actualProg) : 0;
            activeUnit = (actualUnit >= GL_TEXTURE0) ? static_cast<size_t>(actualUnit - GL_TEXTURE0) : 0;
        } else {
            logicalFramebuffer = 0;
            logicalReadFramebuffer = 0;
            currentProgram = 0;
            activeUnit = 0;
        }
    }

    static uint64_t computeUniformValHash(uint32_t opTag, const void* bytes, size_t len,
                                          uint64_t salt = 0, bool hasSalt = false) {
        uint64_t valHash = 0x517cc1b727220a95ULL;
        mix64(valHash, opTag);
        if (hasSalt) mix64(valHash, salt);
        mix64(valHash, len);
        if (bytes && len > 0) hashBytes(valHash, bytes, len);
        return valHash;
    }

    void recordProgramUniformSlot(GLuint program, GLint loc, uint64_t valHash) {
        if (!program || programUniformUnknown.count(program)) return;
        auto& progMap = programUniforms[program];
        if (progMap.find(loc) == progMap.end() && progMap.size() >= kMaxProgramUniforms) {
            programUniforms.erase(program);
            programUniformUnknown.insert(program);
            return;
        }
        progMap[loc] = valHash;
    }

    // In-window scalar/vector uniform writes invalidate only the matching out-of-window memo
    // and update the persistent per-location program uniform snapshot used at draw time.
    void noteUniformInsideWindow(uint32_t opTag, GLint loc, const void* bytes, size_t len) {
        const uint64_t valHash = computeUniformValHash(opTag, bytes, len);
        recordProgramUniformSlot(currentProgram, loc, valHash);
        auto pit = lastOutsideUniforms.find(currentProgram);
        if (pit != lastOutsideUniforms.end()) pit->second.erase(loc);
    }

    // Out-of-window scalar/vector uniform accounting: updates persistent program uniform state
    // and bumps externalMutationCounter unless identical to the previous outside write.
    void noteUniformOutsideWindow(uint32_t opTag, GLint loc, const void* bytes, size_t len) {
        const uint64_t valHash = computeUniformValHash(opTag, bytes, len);
        recordProgramUniformSlot(currentProgram, loc, valHash);
        auto& outProg = lastOutsideUniforms[currentProgram];
        auto it = outProg.find(loc);
        if (it != outProg.end() && it->second == valHash) return;
        if (it == outProg.end() && outProg.size() >= kMaxOutsideUniforms) {
            outProg.clear();
        }
        outProg[loc] = valHash;
        externalMutationCounter.fetch_add(1, std::memory_order_relaxed);
    }

    // Array uniform writes update per-element uniform slots [loc, loc + count) individually so a
    // shorter upload at loc preserves tail element state (u[k] for k >= count). Unbounded or
    // malformed payloads fail closed into programUniformUnknown so stale reuse never occurs.
    void noteArrayUniform(uint32_t opTag, GLint loc, GLsizei count, const void* bytes, size_t elemBytes,
                          uint64_t salt, bool hasSalt, bool validPayload, bool inWindow) {
        lastOutsideUniforms.erase(currentProgram);
        if (!inWindow) {
            externalMutationCounter.fetch_add(1, std::memory_order_relaxed);
        }
        if (!currentProgram) return;
        if (!validPayload) {
            programUniforms.erase(currentProgram);
            programUniformUnknown.insert(currentProgram);
            return;
        }
        if (loc < 0 || count == 0) return;
        if (programUniformUnknown.count(currentProgram)) return;
        if (static_cast<int64_t>(loc) + static_cast<int64_t>(count) - 1 > std::numeric_limits<GLint>::max()) {
            programUniforms.erase(currentProgram);
            programUniformUnknown.insert(currentProgram);
            return;
        }
        const auto* base = static_cast<const uint8_t*>(bytes);
        for (GLsizei i = 0; i < count; ++i) {
            const uint64_t elemHash = computeUniformValHash(
                    opTag, base + static_cast<size_t>(i) * elemBytes, elemBytes, salt, hasSalt);
            recordProgramUniformSlot(currentProgram, static_cast<GLint>(static_cast<int64_t>(loc) + i), elemHash);
            if (programUniformUnknown.count(currentProgram)) return;
        }
    }

    void beforeStageColorWrite() {
        if (!stageFrame || logicalFramebuffer != 0) return;
        if (!stageColorWriteStarted) {
            stageColorWriteStarted = true;
            if (!stageColorClearedAtStart) frameValid = false;
        }
        // If depth or stencil testing is active on the stage FBO without a prior full-surface
        // clear in this frame, rendering depends on previous-frame depth/stencil pixels:
        // fail closed so static reuse cannot treat the frame as a pure function of commands.
        if (glIsEnabled(GL_DEPTH_TEST) != GL_FALSE) {
            stageDepthUsed = true;
            if (!stageDepthClearedAtStart) frameValid = false;
        }
        if (glIsEnabled(GL_STENCIL_TEST) != GL_FALSE) {
            stageStencilUsed = true;
            if (!stageStencilClearedAtStart) frameValid = false;
        }
    }

    bool isUnscissoredClearBox(bool checkStageScissorBounds) const {
        if (glIsEnabled(GL_SCISSOR_TEST)) {
            if (!checkStageScissorBounds) return false;
            GLint box[4]{};
            glGetIntegerv(GL_SCISSOR_BOX, box);
            if (box[0] > 0 || box[1] > 0
                    || box[0] + box[2] < stageTargetWidth
                    || box[1] + box[3] < stageTargetHeight) {
                return false;
            }
        }
        return true;
    }

    bool isFullUnscissoredColorClear(GLbitfield mask, bool checkStageScissorBounds) const {
        if ((mask & GL_COLOR_BUFFER_BIT) == 0) return false;
        GLboolean cm[4]{};
        glGetBooleanv(GL_COLOR_WRITEMASK, cm);
        if (!(cm[0] && cm[1] && cm[2] && cm[3])) return false;
        return isUnscissoredClearBox(checkStageScissorBounds);
    }

    void noteStageColorClear(GLbitfield mask) {
        if (!stageFrame || logicalFramebuffer != 0) return;
        const bool unscissored = isUnscissoredClearBox(true);
        if (!unscissored) return;
        if (!stageColorWriteStarted && (mask & GL_COLOR_BUFFER_BIT) != 0) {
            GLboolean cm[4]{};
            glGetBooleanv(GL_COLOR_WRITEMASK, cm);
            if (cm[0] && cm[1] && cm[2] && cm[3]) {
                stageColorClearedAtStart = true;
            }
        }
        if (!stageDepthUsed && (mask & GL_DEPTH_BUFFER_BIT) != 0) {
            GLboolean dm = GL_FALSE;
            glGetBooleanv(GL_DEPTH_WRITEMASK, &dm);
            if (dm != GL_FALSE) {
                stageDepthClearedAtStart = true;
            }
        }
        if (!stageStencilUsed && (mask & GL_STENCIL_BUFFER_BIT) != 0) {
            GLint sMask = 0;
            GLint sBits = 8;
            glGetIntegerv(GL_STENCIL_WRITEMASK, &sMask);
            glGetIntegerv(GL_STENCIL_BITS, &sBits);
            const GLuint fullMask = (sBits >= 32) ? ~0u : (sBits > 0 ? ((1u << sBits) - 1u) : 0u);
            if (fullMask != 0u && (static_cast<GLuint>(sMask) & fullMask) == fullMask) {
                stageStencilClearedAtStart = true;
            }
        }
    }

    void noteCapEnabled(GLenum cap, bool enabled, bool inWindow) {
        auto it = lastCapEnabled.find(cap);
        if (it != lastCapEnabled.end() && it->second == enabled) return;
        if (it == lastCapEnabled.end() && lastCapEnabled.size() >= kMaxTrackedCaps) {
            if (!inWindow) externalMutationCounter.fetch_add(1, std::memory_order_relaxed);
            return;
        }
        lastCapEnabled[cap] = enabled;
        if (!inWindow) externalMutationCounter.fetch_add(1, std::memory_order_relaxed);
    }

    void noteStencilFunc(GLenum func, GLint ref, GLuint mask, bool inWindow) {
        if (hasStencilFunc && lastStencilFunc == func && lastStencilRef == ref && lastStencilFuncMask == mask) return;
        lastStencilFunc = func;
        lastStencilRef = ref;
        lastStencilFuncMask = mask;
        hasStencilFunc = true;
        if (!inWindow) externalMutationCounter.fetch_add(1, std::memory_order_relaxed);
    }

    void noteStencilOp(GLenum fail, GLenum zfail, GLenum zpass, bool inWindow) {
        const GLenum vals[3] = {fail, zfail, zpass};
        if (hasStencilOp && !std::memcmp(vals, lastStencilOp, sizeof(vals))) return;
        std::memcpy(lastStencilOp, vals, sizeof(vals));
        hasStencilOp = true;
        if (!inWindow) externalMutationCounter.fetch_add(1, std::memory_order_relaxed);
    }

    void noteStencilMask(GLuint mask, bool inWindow) {
        if (hasStencilMask && lastStencilWriteMask == mask) return;
        lastStencilWriteMask = mask;
        hasStencilMask = true;
        if (!inWindow) externalMutationCounter.fetch_add(1, std::memory_order_relaxed);
    }

    void noteDepthFunc(GLenum func, bool inWindow) {
        if (hasDepthFunc && lastDepthFunc == func) return;
        lastDepthFunc = func;
        hasDepthFunc = true;
        if (!inWindow) externalMutationCounter.fetch_add(1, std::memory_order_relaxed);
    }

    void onProgramDeleted(GLuint program) {
        if (!program) return;
        const bool tracked = programUniforms.erase(program) > 0
                | lastOutsideUniforms.erase(program) > 0
                | programUniformUnknown.erase(program) > 0;
        if (program == currentProgram) {
            // A deleted program remains active until unbound; mark its uniform snapshot unknown
            // while it is still bound so subsequent draws fail closed.
            programUniformUnknown.insert(program);
        }
        if (tracked) {
            if (stageFrame) frameValid = false;
            else externalMutationCounter.fetch_add(1, std::memory_order_relaxed);
        }
    }

    void onProgramLinked(GLuint program, bool linkOk) {
        if (!program) return;
        const bool hadUniforms = programUniforms.erase(program) > 0
                | lastOutsideUniforms.erase(program) > 0
                | programUniformUnknown.erase(program) > 0;
        if (!linkOk) {
            programUniformUnknown.insert(program);
        }
        if (hadUniforms || !linkOk) {
            if (stageFrame) frameValid = false;
            else externalMutationCounter.fetch_add(1, std::memory_order_relaxed);
        }
    }

    void noteViewport(GLint x, GLint y, GLsizei w, GLsizei h, bool inWindow) {
        const GLint vp[4] = {x, y, static_cast<GLint>(w), static_cast<GLint>(h)};
        if (hasViewport && !std::memcmp(vp, lastViewport, sizeof(vp))) return;
        std::memcpy(lastViewport, vp, sizeof(vp));
        hasViewport = true;
        if (!inWindow) externalMutationCounter.fetch_add(1, std::memory_order_relaxed);
    }

    void noteCullFace(GLenum mode, bool inWindow) {
        if (hasCullFace && lastCullFace == mode) return;
        lastCullFace = mode;
        hasCullFace = true;
        if (!inWindow) externalMutationCounter.fetch_add(1, std::memory_order_relaxed);
    }

    void noteFrontFace(GLenum mode, bool inWindow) {
        if (hasFrontFace && lastFrontFace == mode) return;
        lastFrontFace = mode;
        hasFrontFace = true;
        if (!inWindow) externalMutationCounter.fetch_add(1, std::memory_order_relaxed);
    }

    void noteBlendFunc(GLenum srgb, GLenum drgb, GLenum sa, GLenum da, bool inWindow) {
        const GLenum vals[4] = {srgb, drgb, sa, da};
        if (hasBlendFunc && !std::memcmp(vals, lastBlendFunc, sizeof(vals))) return;
        std::memcpy(lastBlendFunc, vals, sizeof(vals));
        hasBlendFunc = true;
        if (!inWindow) externalMutationCounter.fetch_add(1, std::memory_order_relaxed);
    }

    void noteBlendEq(GLenum mrgb, GLenum ma, bool inWindow) {
        const GLenum vals[2] = {mrgb, ma};
        if (hasBlendEq && !std::memcmp(vals, lastBlendEq, sizeof(vals))) return;
        std::memcpy(lastBlendEq, vals, sizeof(vals));
        hasBlendEq = true;
        if (!inWindow) externalMutationCounter.fetch_add(1, std::memory_order_relaxed);
    }

    void hashScissorAndColorMask() {
        GLboolean writeMask[4]{};
        glGetBooleanv(GL_COLOR_WRITEMASK, writeMask);
        mix64(frameHash, 0x115);
        hashBytes(frameHash, writeMask, sizeof(writeMask));
        const bool scissorEnabled = (glIsEnabled(GL_SCISSOR_TEST) != GL_FALSE);
        mix64(frameHash, 0x112);
        mix64(frameHash, scissorEnabled ? 1ULL : 0ULL);
        if (scissorEnabled) {
            GLint scissorBox[4]{};
            glGetIntegerv(GL_SCISSOR_BOX, scissorBox);
            hashBytes(frameHash, scissorBox, sizeof(scissorBox));
        }
    }

    // Out-of-window clear-state accounting: a value change bumps the monotonic external
    // counter so a later same-mask clear cannot look identical after the clear value
    // changed. Redundant same-value writes do not bump, so engines that restate an
    // unchanged clear color/depth/stencil every frame keep static frames cacheable.
    void noteClearColorOutsideWindow(GLfloat r, GLfloat g, GLfloat b, GLfloat a) {
        const GLfloat v[4] = {r, g, b, a};
        if (hasClearColor && !std::memcmp(v, lastClearColor, sizeof(v))) return;
        std::memcpy(lastClearColor, v, sizeof(v));
        hasClearColor = true;
        externalMutationCounter.fetch_add(1, std::memory_order_relaxed);
    }

    void noteClearDepthfOutsideWindow(GLclampf depth) {
        const GLfloat v = static_cast<GLfloat>(depth);
        if (hasClearDepthf && v == lastClearDepthf) return;
        lastClearDepthf = v;
        hasClearDepthf = true;
        externalMutationCounter.fetch_add(1, std::memory_order_relaxed);
    }

    void noteClearStencilOutsideWindow(GLint s) {
        if (hasClearStencil && s == lastClearStencil) return;
        lastClearStencil = s;
        hasClearStencil = true;
        externalMutationCounter.fetch_add(1, std::memory_order_relaxed);
    }

    void beginFrame(int w, int h) {
        frameHash = 0x9e3779b97f4a7c15ULL;
        mix64(frameHash, static_cast<uint64_t>(w));
        mix64(frameHash, static_cast<uint64_t>(h));
        frameValid = true;
        drawCount = 0;
        clearCount = 0;
        stageRedirected = false;
        stageReadRedirected = false;
        stageDrawnThisFrame = false;
        stageFailedThisFrame = false;
        stageColorClearedAtStart = false;
        stageColorWriteStarted = false;
        stageDepthClearedAtStart = false;
        stageDepthUsed = false;
        stageStencilClearedAtStart = false;
        stageStencilUsed = false;
    }

    bool finishFrame(int w, int h, bool original) {
        if (!original || !frameValid || (drawCount == 0 && clearCount == 0)) {
            invalidateHistory();
            return true;
        }
        const uint64_t ext = externalMutationCounter.load(std::memory_order_relaxed);
        const bool clean = hasPrevious && previousW == w && previousH == h
                && frameHash == previousHash && ext == lastExternalMutation;
        hasPrevious = true;
        previousW = w;
        previousH = h;
        previousHash = frameHash;
        lastExternalMutation = ext;
        return !clean;
    }

    uint64_t mutateTexture(GLuint tex) {
        if (!tex) return 0;
        const uint64_t id = ++nextMutationId;
        textureVersions[tex] = id;
        if (!stageFrame) externalMutationCounter.fetch_add(1, std::memory_order_relaxed);
        return id;
    }

    void mutateBoundActiveTexture() {
        if (activeUnit < kUnits && boundTextures[activeUnit] != 0) {
            const GLuint tex = boundTextures[activeUnit];
            const uint64_t ver = mutateTexture(tex);
            // 0x136 (texture-content mutation domain) — do not reuse the buffer-bind tag 0x109.
            if (stageFrame) hashOp(0x136, tex, ver);
        } else if (stageFrame) {
            frameValid = false;
        } else {
            externalMutationCounter.fetch_add(1, std::memory_order_relaxed);
        }
    }

    void noteBoundTexParameteri(GLenum target, GLenum pname, GLint param) {
        if (target == GL_TEXTURE_2D && activeUnit < kUnits && boundTextures[activeUnit] != 0) {
            const GLuint tex = boundTextures[activeUnit];
            auto& params = textureParams[tex];
            auto it = params.find(pname);
            if (it != params.end() && it->second == param) {
                return;
            }
            if (it == params.end() && params.size() >= kMaxTexParamsPerTexture) {
                params.clear();
            }
            params[pname] = param;
            const uint64_t ver = mutateTexture(tex);
            if (stageFrame) hashOp(0x108, tex, ver, pname, static_cast<uint64_t>(param));
        } else if (stageFrame) {
            frameValid = false;
        } else {
            externalMutationCounter.fetch_add(1, std::memory_order_relaxed);
        }
    }

    void hashCurrentReadSourceForBlit(GLbitfield mask) {
        if (!stageFrame) return;
        // 0x137 (blit read-source domain) — do not reuse the attachment-mount tag 0x12C.
        mix64(frameHash, 0x137);
        mix64(frameHash, logicalReadFramebuffer);
        if ((mask & (GL_DEPTH_BUFFER_BIT | GL_STENCIL_BUFFER_BIT)) != 0) {
            frameValid = false;
            return;
        }
        if (logicalReadFramebuffer == 0) {
            mix64(frameHash, windowWrittenOutsideStage ? 1ULL : 0ULL);
            if (!stageColorClearedAtStart && !stageDrawnThisFrame) {
                frameValid = false;
            }
            return;
        }
        auto fboIt = fboColorTextures.find(logicalReadFramebuffer);
        if (fboIt != fboColorTextures.end() && fboIt->second != 0) {
            const GLuint srcTex = fboIt->second;
            auto verIt = textureVersions.find(srcTex);
            mix64(frameHash, srcTex);
            mix64(frameHash, verIt == textureVersions.end() ? 0ULL : verIt->second);
        } else {
            frameValid = false;
        }
    }

    void touchCurrentFramebufferTarget(bool fullColorReset = false) {
        if (logicalFramebuffer == 0) {
            if (stageFrame) stageDrawnThisFrame = true;
            else {
                windowWrittenOutsideStage = true;
                externalMutationCounter.fetch_add(1, std::memory_order_relaxed);
            }
            return;
        }
        auto it = fboColorTextures.find(logicalFramebuffer);
        if (it != fboColorTextures.end() && it->second != 0) {
            const GLuint tex = it->second;
            if (!stageFrame) {
                textureVersions[tex] = ++nextMutationId;
            } else if (fullColorReset) {
                // Full un-scissored color clear replaces all RTT pixels deterministically.
                textureVersions[tex] = frameHash;
            } else {
                // Partial/blended writes depend on prior RTT contents: chain the previous
                // version into the new version so identical cross-frame draws advance version.
                auto vit = textureVersions.find(tex);
                uint64_t nextVer = frameHash;
                mix64(nextVer, vit == textureVersions.end() ? 0 : vit->second);
                textureVersions[tex] = nextVer;
            }
        } else if (!stageFrame) {
            externalMutationCounter.fetch_add(1, std::memory_order_relaxed);
        } else {
            // In-window draws to an FBO whose color attachment the tracker never learned
            // (attachment 1+, a pre-hook attachment, or a detached slot) cannot be
            // versioned. Conservatively judge the frame dirty; deterministic on the
            // command stream and only degrades reuse, never correctness.
            frameValid = false;
        }
    }

    // Frame-hash op-tag allocation (one tag per tracked command domain; assigned tags are
    // unique across the whole table except the intentional same-state-domain pairs below,
    // where a draw-time state snapshot reuses its setter's tag — value payloads differ).
    //   0x101 bindFramebuffer        0x102 activeTexture        0x103 bindTexture
    //   0x104 texImage2D             0x105 compressedTexImage2D 0x106 copyTexImage2D
    //   0x107 texSubImage2D          0x108 boundTexParameteri   0x109 bindBuffer
    //   0x10A bufferData             0x10B bufferSubData
    //   0x110 useProgram             0x111 viewport (event + draw-state snapshot)
    //   0x112 scissor (event + hashScissorAndColorMask snapshot)
    //   0x113 enable                 0x114 disable
    //   0x115 colorMask (event + hashScissorAndColorMask snapshot)
    //   0x116 clearColor             0x117 clear                0x118 blendFuncSeparate
    //   0x119 blendEquationSeparate  0x11A stencilFunc          0x11B stencilOp
    //   0x11C stencilMask            0x11D depthFunc            0x11E clearStencil
    //   0x11F clearDepthf            0x120..0x127 uniform uploads (event tag = val-hash tag)
    //   0x128 copyTexSubImage2D      0x129 blitFramebuffer      0x12A generateMipmap
    //   0x12B texStorage2D           0x12C framebufferTexture2D
    //   0x130 drawElements op        0x131 drawElements common  0x132 drawArrays op
    //   0x133 cullFace event         0x134 frontFace (event + snapshot)
    //   0x135 bindVertexArray[OES]   0x136 bound-texture content mutation (version mix)
    //   0x137 blit read-source block 0x138 drawArrays common block
    //   0x139 draw-state cull snapshot; 0x13A framebufferRenderbuffer event;
    //   0x140 program-uniform snapshot; 0x200+i attributes.
    void hashOp(uint64_t tag, uint64_t a = 0, uint64_t b = 0, uint64_t c = 0, uint64_t d = 0) {
        if (!stageFrame) return;
        mix64(frameHash, tag);
        mix64(frameHash, a);
        mix64(frameHash, b);
        mix64(frameHash, c);
        mix64(frameHash, d);
    }
};

thread_local FrameDirtyTracker frameTracker;

void resetStageRedirectOnScopeExit() {
    if (frameTracker.stageRedirected || frameTracker.stageReadRedirected) {
        HostGlScope host;
        if (frameTracker.stageRedirected) {
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, 0);
            frameTracker.stageRedirected = false;
        }
        if (frameTracker.stageReadRedirected) {
            glBindFramebuffer(GL_READ_FRAMEBUFFER, 0);
            frameTracker.stageReadRedirected = false;
        }
    }
}

// Align with the Java-side GlErrors discipline: stage GL work must not leak error
// flags into the engine's subsequent glGetError calls. Bounded drain, never latches
// (see drainGlErrorsBounded: stops on GL_NO_ERROR, GL_CONTEXT_LOST_KHR, or 16 records);
// returns true iff GL_CONTEXT_LOST_KHR was observed.
bool drainStageGlErrors() {
    return drainGlErrorsBounded();
}

void handleStageContextLost(EGLContext currentCtx) {
    bool lostTrackedStage = false;
    {
        std::lock_guard<std::mutex> lock(stageResourcesMutex);
        if (stageResources.context == currentCtx && currentCtx != EGL_NO_CONTEXT) {
            stageResources.abandon();
            stageResources.lastTrackedContext = EGL_NO_CONTEXT;
            lostTrackedStage = true;
        }
    }
    frameTracker.invalidateHistory();
    frameTracker.stageRedirected = false;
    frameTracker.stageReadRedirected = false;
    frameTracker.windowWrittenOutsideStage = false;
    singleContextMode = false;
    logicalWidth = 0;
    logicalHeight = 0;
    directOutput = true;
    if (lostTrackedStage) {
        // Notify Java immediately so singlePipeline targets (up to 256 MiB budget),
        // singleMode, and singleOutputSize are released instead of staying stranded
        // until process/Activity teardown. contextDestroyed() abandons without GL calls.
        Env env;
        if (env.value && bridge && contextDestroyedMethod) {
            env.value->CallStaticVoidMethod(bridge, contextDestroyedMethod);
            env.clear();
        }
        contextGeneration.fetch_add(1);
    }
    status(3);
}

bool ensureStageResources(int w, int h, GLuint* outFbo = nullptr) {
    if (w <= 0 || h <= 0) return false;
    EGLContext ctx = eglGetCurrentContext();
    if (ctx == EGL_NO_CONTEXT) {
        status(2);
        return false;
    }
    const uint64_t gen = contextGeneration.load();
    std::unique_lock<std::mutex> lock(stageResourcesMutex);
    if (stageResources.context != ctx) {
        stageResources.abandon();
        stageResources.context = ctx;
        stageResources.lastTrackedContext = ctx;
        stageResources.generation = gen;
        frameTracker.resetAllOnContextChange(ctx, 0);
    } else if (stageResources.generation != gen) {
        stageResources.generation = gen;
    }
    if (stageResources.unavailable) return false;
    // Memoized deterministic rejection: same failing geometry within one context
    // generation cannot pass (limits and budget are context constants).
    if (stageResources.failedWidth == w && stageResources.failedHeight == h) return false;
    if (stageResources.width == w && stageResources.height == h && stageResources.fbo
            && stageResources.texture && stageResources.depthStencil && stageResources.program
            && stageResources.vao && stageResources.sampler) {
        if (outFbo) *outFbo = stageResources.fbo;
        return true;
    }
    GLint major = 0;
    glGetIntegerv(GL_MAJOR_VERSION, &major);
    if (major < 3) {
        stageResources.unavailable = true;
        es3UpgradeFailed = true;
        drainStageGlErrors();
        lock.unlock();
        status(2);
        return false;
    }
    GLint maxTex = 0;
    glGetIntegerv(GL_MAX_TEXTURE_SIZE, &maxTex);
    const uint64_t bytes = static_cast<uint64_t>(w) * static_cast<uint64_t>(h) * 8ULL;
    if (maxTex <= 0 || w > maxTex || h > maxTex || bytes >= kBudgetBytes) {
        stageResources.failedWidth = w;
        stageResources.failedHeight = h;
        lock.unlock();
        status(7);
        return false;
    }
    stageResources.failedWidth = 0;
    stageResources.failedHeight = 0;

    StageGlState saved;
    HostGlScope host;
    const GLuint prevFbo = stageResources.fbo;
    stageResources.releaseTargets();
    stageResources.context = ctx;
    stageResources.generation = gen;

    if (!stageResources.program) {
        static constexpr const char* kVs = "#version 300 es\n"
            "uniform vec4 bounds;out vec2 uv;void main(){"
            "vec2 p=vec2(float(gl_VertexID&1),float((gl_VertexID>>1)&1));uv=p;"
            "gl_Position=vec4((bounds.xy+bounds.zw*p)*2.0-1.0,0.0,1.0);}";
        static constexpr const char* kFs = "#version 300 es\n"
            "precision highp float;in vec2 uv;uniform sampler2D source;out vec4 color;"
            "void main(){color=texture(source,uv);}";
        bool allocOrOom = false;
        GLuint vs = compileStageShader(GL_VERTEX_SHADER, kVs, &allocOrOom);
        GLuint fs = vs ? compileStageShader(GL_FRAGMENT_SHADER, kFs, &allocOrOom) : 0;
        if (vs && fs) {
            stageResources.program = glCreateProgram();
            if (!stageResources.program) {
                allocOrOom = true;
            } else {
                glAttachShader(stageResources.program, vs);
                glAttachShader(stageResources.program, fs);
                glLinkProgram(stageResources.program);
            }
        }
        if (vs) glDeleteShader(vs);
        if (fs) glDeleteShader(fs);
        GLint linked = 0;
        if (stageResources.program) glGetProgramiv(stageResources.program, GL_LINK_STATUS, &linked);
        if (!linked) {
            for (int i = 0; i < 16; ++i) {
                GLenum err = glGetError();
                if (err == GL_NO_ERROR) break;
                if (err == GL_OUT_OF_MEMORY) allocOrOom = true;
            }
            if (allocOrOom) {
                if (stageResources.program) {
                    glDeleteProgram(stageResources.program);
                    stageResources.program = 0;
                }
                stageResources.releaseTargets();
                saved.restore(prevFbo);
                drainStageGlErrors();
                lock.unlock();
                status(7);
                return false;
            }
            // Genuine compile/link failure for the fixed passthrough program: preserve the
            // owning context and generation across release() so subsequent frames in this
            // context skip rebuilding until the context itself changes.
            stageResources.release();
            stageResources.context = ctx;
            stageResources.lastTrackedContext = ctx;
            stageResources.generation = gen;
            stageResources.unavailable = true;
            es3UpgradeFailed = true;
            saved.restore(prevFbo);
            drainStageGlErrors();
            lock.unlock();
            status(3);
            return false;
        }
        stageResources.boundsLoc = glGetUniformLocation(stageResources.program, "bounds");
        stageResources.sourceLoc = glGetUniformLocation(stageResources.program, "source");
    }
    if (!stageResources.vao) glGenVertexArrays(1, &stageResources.vao);
    if (!stageResources.sampler) {
        glGenSamplers(1, &stageResources.sampler);
        glSamplerParameteri(stageResources.sampler, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glSamplerParameteri(stageResources.sampler, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glSamplerParameteri(stageResources.sampler, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glSamplerParameteri(stageResources.sampler, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    }

    glGenTextures(1, &stageResources.texture);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, stageResources.texture);
    glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);

    glGenRenderbuffers(1, &stageResources.depthStencil);
    glBindRenderbuffer(GL_RENDERBUFFER, stageResources.depthStencil);
    glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH24_STENCIL8, w, h);

    glGenFramebuffers(1, &stageResources.fbo);
    glBindFramebuffer(GL_FRAMEBUFFER, stageResources.fbo);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, stageResources.texture, 0);
    glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_STENCIL_ATTACHMENT, GL_RENDERBUFFER, stageResources.depthStencil);

    const bool complete = stageResources.vao && stageResources.sampler && stageResources.texture
            && stageResources.depthStencil && stageResources.fbo
            && stageResources.boundsLoc >= 0 && stageResources.sourceLoc >= 0
            && glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;
    if (complete) {
        glDisable(GL_SCISSOR_TEST);
        if (saved.useIndexedBuffer0) {
            saved.colorMaskiFn(0, GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);
        } else {
            glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);
        }
        glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
        glClear(GL_COLOR_BUFFER_BIT);
        glDepthMask(GL_TRUE);
        glStencilMaskSeparate(GL_FRONT_AND_BACK, ~GLuint{0});
        glClearBufferfi(GL_DEPTH_STENCIL, 0, 1.0f, 0);
        stageResources.width = w;
        stageResources.height = h;
        frameTracker.invalidateHistory();
        if (outFbo) *outFbo = stageResources.fbo;
    } else {
        stageResources.releaseTargets();
    }
    saved.restore(prevFbo);
    drainStageGlErrors();
    lock.unlock();
    if (!complete) status(7);
    return complete;
}

// Bounded backoff before latching direct output when stage FBO creation fails:
// a single transient failure (allocation pressure, OOM) must not disable the whole
// filter chain until context recreation. Four consecutive failures — the same
// threshold as the Java-side pipeline construction circuit breaker — latch
// permanent direct output; any success clears the streak. Deterministic rejections
// are memoized inside ensureStageResources, so a permanently failing geometry
// reaches the latch on the fourth retry while isolated transients retry next frame.
constexpr int kStageFailuresBeforeDirectOutput = 4;
std::atomic<int> stageFailureStreak{0};
bool ensureStageResourcesWithBackoff(int w, int h, GLuint* outFbo) {
    if (frameTracker.stageFailedThisFrame) return false;
    if (ensureStageResources(w, h, outFbo) && outFbo && *outFbo) {
        stageFailureStreak.store(0, std::memory_order_relaxed);
        return true;
    }
    frameTracker.stageFailedThisFrame = true;
    if (stageFailureStreak.fetch_add(1, std::memory_order_relaxed) + 1
            >= kStageFailuresBeforeDirectOutput) {
        directOutput = true;
        gesture = false;
        __android_log_print(ANDROID_LOG_WARN, "ArtemisOfficial",
                "Stage FBO creation failed %d consecutive times; latching direct output",
                kStageFailuresBeforeDirectOutput);
    }
    return false;
}

bool ensureStageRedirected() {
    if (!singleContextMode.load() || directOutput.load() || !stageFrame || inHostGl) return false;
    if (frameTracker.logicalFramebuffer != 0) return false;
    const int lw = logicalWidth.load(), lh = logicalHeight.load();
    if (lw <= 0 || lh <= 0) return false;
    GLuint stageFbo = 0;
    if (!ensureStageResourcesWithBackoff(lw, lh, &stageFbo)) return false;
    if (!frameTracker.stageRedirected) {
        HostGlScope host;
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, stageFbo);
        frameTracker.stageRedirected = true;
    }
    return true;
}

GLuint currentContextStageFbo(EGLContext currentCtx = eglGetCurrentContext()) {
    if (currentCtx == EGL_NO_CONTEXT) return 0;
    std::lock_guard<std::mutex> lock(stageResourcesMutex);
    return (stageResources.context == currentCtx) ? stageResources.fbo : 0;
}

void hookGlBindFramebuffer(GLenum target, GLuint framebuffer) {
    if (inHostGl || !singleContextMode.load() || directOutput.load()) {
        glBindFramebuffer(target, framebuffer);
        return;
    }
    const GLuint stageFbo = currentContextStageFbo();
    const GLuint normalized = (stageFbo != 0 && framebuffer == stageFbo) ? 0 : framebuffer;
    if (target == GL_FRAMEBUFFER || target == GL_DRAW_FRAMEBUFFER) {
        frameTracker.logicalFramebuffer = normalized;
        if (target == GL_FRAMEBUFFER) frameTracker.logicalReadFramebuffer = normalized;
        if (normalized == 0 && stageFrame) {
            const int lw = logicalWidth.load(), lh = logicalHeight.load();
            GLuint redirectedFbo = 0;
            if (lw > 0 && lh > 0 && ensureStageResourcesWithBackoff(lw, lh, &redirectedFbo)) {
                glBindFramebuffer(target, redirectedFbo);
                frameTracker.logicalFramebuffer = 0;
                if (target == GL_FRAMEBUFFER) frameTracker.logicalReadFramebuffer = 0;
                frameTracker.stageRedirected = true;
                if (target == GL_FRAMEBUFFER) frameTracker.stageReadRedirected = true;
                frameTracker.hashOp(0x101, target, 0);
                return;
            }
        }
        frameTracker.stageRedirected = false;
        if (target == GL_FRAMEBUFFER) frameTracker.stageReadRedirected = false;
    } else if (target == GL_READ_FRAMEBUFFER) {
        frameTracker.logicalReadFramebuffer = normalized;
        if (normalized == 0 && stageFrame && stageFbo != 0) {
            glBindFramebuffer(GL_READ_FRAMEBUFFER, stageFbo);
            frameTracker.stageReadRedirected = true;
            frameTracker.hashOp(0x101, target, 0);
            return;
        }
        frameTracker.stageReadRedirected = false;
    }
    glBindFramebuffer(target, normalized);
    if (target == GL_FRAMEBUFFER || target == GL_DRAW_FRAMEBUFFER) {
        frameTracker.logicalFramebuffer = normalized;
        if (target == GL_FRAMEBUFFER) frameTracker.logicalReadFramebuffer = normalized;
    }
    frameTracker.hashOp(0x101, target, normalized);
}

void hookGlGetIntegerv(GLenum pname, GLint* data) {
    glGetIntegerv(pname, data);
    if (!inHostGl && singleContextMode.load() && !directOutput.load() && data
            && (pname == GL_FRAMEBUFFER_BINDING || pname == GL_DRAW_FRAMEBUFFER_BINDING
                || pname == GL_READ_FRAMEBUFFER_BINDING)) {
        const GLuint stageFbo = currentContextStageFbo();
        if (stageFbo != 0 && static_cast<GLuint>(*data) == stageFbo) {
            *data = 0;
        }
    }
}

void hookGlFramebufferTexture2D(GLenum target, GLenum attachment, GLenum textarget, GLuint texture, GLint level) {
    glFramebufferTexture2D(target, attachment, textarget, texture, level);
    if (!inHostGl && singleContextMode.load()) {
        // (Re)mounting an attachment changes what the target FBO renders to and is itself a
        // command-stream event: hash it deterministically (identical command streams keep
        // identical hashes) so a remount-based content switch cannot alias a previous
        // frame's hash. Covers attachments 1+ and re-mounts, not only COLOR_ATTACHMENT0.
        frameTracker.hashOp(0x12C, target, attachment, textarget,
                (static_cast<uint64_t>(static_cast<uint32_t>(level)) << 32)
                        | static_cast<uint32_t>(texture));
        if (attachment == GL_COLOR_ATTACHMENT0
                && (target == GL_FRAMEBUFFER || target == GL_DRAW_FRAMEBUFFER)
                && frameTracker.logicalFramebuffer != 0) {
            frameTracker.fboColorTextures[frameTracker.logicalFramebuffer] = texture;
        }
    }
}

void hookGlFramebufferRenderbuffer(GLenum target, GLenum attachment, GLenum renderbuffertarget, GLuint renderbuffer) {
    glFramebufferRenderbuffer(target, attachment, renderbuffertarget, renderbuffer);
    if (!inHostGl && singleContextMode.load() && !directOutput.load()) {
        const GLuint fbo = (target == GL_READ_FRAMEBUFFER)
                ? frameTracker.logicalReadFramebuffer
                : frameTracker.logicalFramebuffer;
        const bool isColorAttachment = (attachment >= GL_COLOR_ATTACHMENT0
                && attachment < GL_COLOR_ATTACHMENT0 + 32);
        if (fbo != 0 && isColorAttachment) {
            frameTracker.fboColorTextures.erase(fbo);
            if (stageFrame) {
                // Renderbuffer color attachments are not version-tracked in the 2D texture
                // model; fail closed so the frame is judged dirty.
                frameTracker.frameValid = false;
            } else {
                externalMutationCounter.fetch_add(1, std::memory_order_relaxed);
            }
        } else {
            if (stageFrame) {
                frameTracker.hashOp(0x13A, target, attachment, renderbuffertarget, renderbuffer);
            } else {
                externalMutationCounter.fetch_add(1, std::memory_order_relaxed);
            }
        }
    }
}

void hookGlActiveTexture(GLenum texture) {
    glActiveTexture(texture);
    if (!inHostGl && singleContextMode.load()) {
        frameTracker.activeUnit = texture >= GL_TEXTURE0 ? static_cast<size_t>(texture - GL_TEXTURE0) : FrameDirtyTracker::kUnits;
        if (frameTracker.activeUnit >= FrameDirtyTracker::kUnits) {
            // An out-of-range active unit cannot be tracked per-unit; judge the frame
            // dirty immediately instead of waiting for the draw-time hash check.
            frameTracker.frameValid = false;
        }
        frameTracker.hashOp(0x102, texture);
    }
}

void hookGlBindTexture(GLenum target, GLuint texture) {
    glBindTexture(target, texture);
    if (!inHostGl && singleContextMode.load()) {
        if (target == GL_TEXTURE_2D && frameTracker.activeUnit < FrameDirtyTracker::kUnits) {
            frameTracker.boundTextures[frameTracker.activeUnit] = texture;
        } else if (target != GL_TEXTURE_2D) {
            // Content writes to non-2D texture objects (cube maps, 3D, external) are never
            // version-tracked by the 2D binding model. Conservatively judge such frames
            // dirty; the decision is deterministic on the command stream and only degrades
            // reuse, never correctness.
            frameTracker.frameValid = false;
        }
        frameTracker.hashOp(0x103, target, texture);
    }
}

void hookGlDeleteTextures(GLsizei n, const GLuint* textures) {
    if (!inHostGl && singleContextMode.load() && textures && n > 0) {
        for (GLsizei i = 0; i < n; ++i) {
            const GLuint t = textures[i];
            if (!t) continue;
            frameTracker.textureVersions.erase(t);
            frameTracker.textureParams.erase(t);
            for (GLuint& bound : frameTracker.boundTextures) {
                if (bound == t) bound = 0;
            }
            for (auto& kv : frameTracker.fboColorTextures) {
                if (kv.second == t) kv.second = 0;
            }
        }
        if (!stageFrame) externalMutationCounter.fetch_add(1, std::memory_order_relaxed);
    }
    glDeleteTextures(n, textures);
}

// Optional deletion hooks: without them, deleted names could later be recycled by
// the driver while their stale tracker mappings (FBO color attachment, buffer
// versions) kept feeding the frame hash.
void hookGlDeleteFramebuffers(GLsizei n, const GLuint* framebuffers) {
    if (!inHostGl && singleContextMode.load() && framebuffers && n > 0) {
        for (GLsizei i = 0; i < n; ++i) {
            const GLuint f = framebuffers[i];
            if (!f) continue;
            frameTracker.fboColorTextures.erase(f);
            if (frameTracker.logicalFramebuffer == f) {
                frameTracker.logicalFramebuffer = 0;
                frameTracker.stageRedirected = false;
            }
            if (frameTracker.logicalReadFramebuffer == f) {
                frameTracker.logicalReadFramebuffer = 0;
                frameTracker.stageReadRedirected = false;
            }
        }
        if (!stageFrame) externalMutationCounter.fetch_add(1, std::memory_order_relaxed);
    }
    glDeleteFramebuffers(n, framebuffers);
}

void hookGlDeleteBuffers(GLsizei n, const GLuint* buffers) {
    if (!inHostGl && singleContextMode.load() && buffers && n > 0) {
        for (GLsizei i = 0; i < n; ++i) {
            const GLuint b = buffers[i];
            if (!b) continue;
            frameTracker.bufferVersions.erase(b);
            if (frameTracker.arrayBuffer == b) frameTracker.arrayBuffer = 0;
            if (frameTracker.elementArrayBuffer == b) frameTracker.elementArrayBuffer = 0;
        }
        if (!stageFrame) externalMutationCounter.fetch_add(1, std::memory_order_relaxed);
    }
    glDeleteBuffers(n, buffers);
}

void hookGlTexImage2D(GLenum target, GLint level, GLint internalformat, GLsizei width, GLsizei height,
                      GLint border, GLenum format, GLenum type, const void* pixels) {
    glTexImage2D(target, level, internalformat, width, height, border, format, type, pixels);
    if (!inHostGl && singleContextMode.load()) {
        frameTracker.mutateBoundActiveTexture();
        frameTracker.hashOp(0x104, target, static_cast<uint64_t>(level),
                static_cast<uint64_t>(width), static_cast<uint64_t>(height));
    }
}

void hookGlCompressedTexImage2D(GLenum target, GLint level, GLenum internalformat, GLsizei width, GLsizei height,
                                GLint border, GLsizei imageSize, const void* data) {
    glCompressedTexImage2D(target, level, internalformat, width, height, border, imageSize, data);
    if (!inHostGl && singleContextMode.load()) {
        frameTracker.mutateBoundActiveTexture();
        frameTracker.hashOp(0x105, target, static_cast<uint64_t>(width),
                static_cast<uint64_t>(height), static_cast<uint64_t>(imageSize));
    }
}

void maybeBindStageReadFboForCopy(bool* outRestoreReadFbo, GLint* outPrevReadFbo) {
    *outRestoreReadFbo = false;
    *outPrevReadFbo = 0;
    if (!inHostGl && singleContextMode.load() && !directOutput.load()
            && stageFrame && frameTracker.logicalReadFramebuffer == 0
            && !frameTracker.stageReadRedirected) {
        const int lw = logicalWidth.load(), lh = logicalHeight.load();
        GLuint stageFbo = 0;
        if (lw > 0 && lh > 0 && ensureStageResourcesWithBackoff(lw, lh, &stageFbo) && stageFbo != 0) {
            HostGlScope host;
            glGetIntegerv(GL_READ_FRAMEBUFFER_BINDING, outPrevReadFbo);
            if (static_cast<GLuint>(*outPrevReadFbo) == 0) {
                glBindFramebuffer(GL_READ_FRAMEBUFFER, stageFbo);
                *outRestoreReadFbo = true;
            }
        }
    }
}

void restoreStageReadFboAfterCopy(bool restoreReadFbo, GLint prevReadFbo) {
    if (restoreReadFbo) {
        HostGlScope host;
        glBindFramebuffer(GL_READ_FRAMEBUFFER, static_cast<GLuint>(prevReadFbo));
    }
}

void hookGlCopyTexImage2D(GLenum target, GLint level, GLenum internalformat, GLint x, GLint y,
                          GLsizei width, GLsizei height, GLint border) {
    bool restoreReadFbo = false;
    GLint prevReadFbo = 0;
    maybeBindStageReadFboForCopy(&restoreReadFbo, &prevReadFbo);
    glCopyTexImage2D(target, level, internalformat, x, y, width, height, border);
    restoreStageReadFboAfterCopy(restoreReadFbo, prevReadFbo);
    if (!inHostGl && singleContextMode.load()) {
        frameTracker.mutateBoundActiveTexture();
        frameTracker.hashOp(0x106, target, static_cast<uint64_t>(width), static_cast<uint64_t>(height));
    }
}

void hookGlTexSubImage2D(GLenum target, GLint level, GLint xoffset, GLint yoffset,
                         GLsizei width, GLsizei height, GLenum format, GLenum type, const void* pixels) {
    glTexSubImage2D(target, level, xoffset, yoffset, width, height, format, type, pixels);
    if (!inHostGl && singleContextMode.load()) {
        frameTracker.mutateBoundActiveTexture();
        frameTracker.hashOp(0x107, target, static_cast<uint64_t>(width), static_cast<uint64_t>(height));
    }
}

void hookGlTexParameteri(GLenum target, GLenum pname, GLint param) {
    glTexParameteri(target, pname, param);
    if (!inHostGl && singleContextMode.load() && !directOutput.load()) {
        frameTracker.noteBoundTexParameteri(target, pname, param);
    }
}

// Defensive content-change entry points (mutate+hashOp semantics aligned with the
// TexImage2D family). They run as optional hooks: engines whose import table lacks
// them install nothing and lose nothing.
void hookGlCopyTexSubImage2D(GLenum target, GLint level, GLint xoffset, GLint yoffset,
                             GLint x, GLint y, GLsizei width, GLsizei height) {
    // CopyTexSubImage2D reads from GL_READ_FRAMEBUFFER; when logical framebuffer 0 was lazily
    // redirected on GL_DRAW_FRAMEBUFFER only, bind stageFbo on GL_READ_FRAMEBUFFER for the copy
    // and restore the caller's read binding afterwards.
    bool restoreReadFbo = false;
    GLint prevReadFbo = 0;
    maybeBindStageReadFboForCopy(&restoreReadFbo, &prevReadFbo);
    glCopyTexSubImage2D(target, level, xoffset, yoffset, x, y, width, height);
    restoreStageReadFboAfterCopy(restoreReadFbo, prevReadFbo);
    if (!inHostGl && singleContextMode.load()) {
        frameTracker.mutateBoundActiveTexture();
        frameTracker.hashOp(0x128, target, static_cast<uint64_t>(width), static_cast<uint64_t>(height));
    }
}

void hookGlBlitFramebuffer(GLint srcX0, GLint srcY0, GLint srcX1, GLint srcY1,
                           GLint dstX0, GLint dstY0, GLint dstX1, GLint dstY1,
                           GLbitfield mask, GLenum filter) {
    // Tracking runs before the blit: a frame-start blit to the default framebuffer is
    // redirected into the stage FBO exactly like clear/draw, and blits outside stageFrame
    // still account content changes (RTT version bump or ext counter; hashOp no-ops
    // outside stageFrame) so the next frame cannot be judged clean from stale content.
    if (!inHostGl && singleContextMode.load() && !directOutput.load()) {
        if (frameTracker.logicalFramebuffer == 0) ensureStageRedirected();
        if (stageFrame) {
            frameTracker.hashCurrentReadSourceForBlit(mask);
            frameTracker.beforeStageColorWrite();
            ++frameTracker.drawCount;
            frameTracker.hashOp(0x129, frameTracker.logicalFramebuffer,
                    static_cast<uint64_t>(srcX0), static_cast<uint64_t>(srcY0),
                    static_cast<uint64_t>(srcX1));
            FrameDirtyTracker::mix64(frameTracker.frameHash, static_cast<uint64_t>(srcY1));
            FrameDirtyTracker::mix64(frameTracker.frameHash, static_cast<uint64_t>(dstX0));
            FrameDirtyTracker::mix64(frameTracker.frameHash, static_cast<uint64_t>(dstY0));
            FrameDirtyTracker::mix64(frameTracker.frameHash, static_cast<uint64_t>(dstX1));
            FrameDirtyTracker::mix64(frameTracker.frameHash, static_cast<uint64_t>(dstY1));
            FrameDirtyTracker::mix64(frameTracker.frameHash, static_cast<uint64_t>(mask));
            FrameDirtyTracker::mix64(frameTracker.frameHash, static_cast<uint64_t>(filter));
        }
        frameTracker.touchCurrentFramebufferTarget();
    }
    bool restoreReadFbo = false;
    GLint prevReadFbo = 0;
    maybeBindStageReadFboForCopy(&restoreReadFbo, &prevReadFbo);
    glBlitFramebuffer(srcX0, srcY0, srcX1, srcY1, dstX0, dstY0, dstX1, dstY1, mask, filter);
    restoreStageReadFboAfterCopy(restoreReadFbo, prevReadFbo);
}

void hookGlGenerateMipmap(GLenum target) {
    glGenerateMipmap(target);
    if (!inHostGl && singleContextMode.load()) {
        // Regenerates the mip chain of the texture bound to the active unit.
        frameTracker.mutateBoundActiveTexture();
        frameTracker.hashOp(0x12A, target);
    }
}

void hookGlTexStorage2D(GLenum target, GLsizei levels, GLenum internalformat,
                        GLsizei width, GLsizei height) {
    glTexStorage2D(target, levels, internalformat, width, height);
    if (!inHostGl && singleContextMode.load()) {
        // Immutable storage redefines the whole mip chain without pixel data; the
        // allocation still invalidates prior content, so mutate like TexImage2D.
        frameTracker.mutateBoundActiveTexture();
        frameTracker.hashOp(0x12B, target, static_cast<uint64_t>(levels), internalformat,
                (static_cast<uint64_t>(static_cast<uint32_t>(width)) << 32)
                        | static_cast<uint32_t>(height));
    }
}

void hookGlBindBuffer(GLenum target, GLuint buffer) {
    glBindBuffer(target, buffer);
    if (!inHostGl && singleContextMode.load()) {
        if (target == GL_ARRAY_BUFFER) {
            frameTracker.arrayBuffer = buffer;
            frameTracker.arrayBufferSynced = true;
        } else if (target == GL_ELEMENT_ARRAY_BUFFER) {
            frameTracker.elementArrayBuffer = buffer;
            frameTracker.elementBufferSynced = true;
        }
        frameTracker.hashOp(0x109, target, buffer);
    }
}

using BindVertexArrayProc = void (*)(GLuint);
std::atomic<BindVertexArrayProc> realBindVertexArrayOES{nullptr};

void onVertexArrayBound(GLuint array, uint64_t oesFlag) {
    if (!inHostGl && singleContextMode.load() && !directOutput.load()) {
        frameTracker.elementBufferSynced = false;
        std::memset(frameTracker.attribStateSynced, 0, sizeof(frameTracker.attribStateSynced));
        frameTracker.hashOp(0x135, array, oesFlag);
    }
}

void hookGlBindVertexArray(GLuint array) {
    glBindVertexArray(array);
    onVertexArrayBound(array, 0);
}

void hookGlBindVertexArrayOES(GLuint array) {
    if (BindVertexArrayProc orig = realBindVertexArrayOES.load(std::memory_order_acquire)) {
        orig(array);
    } else {
        glBindVertexArray(array);
    }
    onVertexArrayBound(array, 1);
}

void syncBufferBindingForUpload(GLenum target) {
    if (target == GL_ARRAY_BUFFER && !frameTracker.arrayBufferSynced) {
        GLint actualVbo = 0;
        glGetIntegerv(GL_ARRAY_BUFFER_BINDING, &actualVbo);
        frameTracker.arrayBuffer = actualVbo > 0 ? static_cast<GLuint>(actualVbo) : 0;
        frameTracker.arrayBufferSynced = true;
    } else if (target == GL_ELEMENT_ARRAY_BUFFER && !frameTracker.elementBufferSynced) {
        GLint actualEbo = 0;
        glGetIntegerv(GL_ELEMENT_ARRAY_BUFFER_BINDING, &actualEbo);
        frameTracker.elementArrayBuffer = actualEbo > 0 ? static_cast<GLuint>(actualEbo) : 0;
        frameTracker.elementBufferSynced = true;
    }
}

void hookGlBufferData(GLenum target, GLsizeiptr size, const void* data, GLenum usage) {
    glBufferData(target, size, data, usage);
    if (!inHostGl && singleContextMode.load()) {
        syncBufferBindingForUpload(target);
        const GLuint buf = target == GL_ARRAY_BUFFER ? frameTracker.arrayBuffer
                : (target == GL_ELEMENT_ARRAY_BUFFER ? frameTracker.elementArrayBuffer : 0);
        if (buf) frameTracker.bufferVersions[buf] = ++frameTracker.nextMutationId;
        frameTracker.hashOp(0x10A, target, static_cast<uint64_t>(size), usage);
    }
}

void hookGlBufferSubData(GLenum target, GLintptr offset, GLsizeiptr size, const void* data) {
    glBufferSubData(target, offset, size, data);
    if (!inHostGl && singleContextMode.load()) {
        syncBufferBindingForUpload(target);
        const GLuint buf = target == GL_ARRAY_BUFFER ? frameTracker.arrayBuffer
                : (target == GL_ELEMENT_ARRAY_BUFFER ? frameTracker.elementArrayBuffer : 0);
        if (buf) frameTracker.bufferVersions[buf] = ++frameTracker.nextMutationId;
        frameTracker.hashOp(0x10B, target, static_cast<uint64_t>(offset), static_cast<uint64_t>(size));
    }
}

void hookGlEnableVertexAttribArray(GLuint index) {
    glEnableVertexAttribArray(index);
    if (!inHostGl && singleContextMode.load()) {
        if (index < FrameDirtyTracker::kAttribs) frameTracker.attribs[index].enabled = true;
        else if (index != ~GLuint{0}) frameTracker.frameValid = false;
    }
}

void hookGlDisableVertexAttribArray(GLuint index) {
    glDisableVertexAttribArray(index);
    if (!inHostGl && singleContextMode.load() && index < FrameDirtyTracker::kAttribs) {
        frameTracker.attribs[index].enabled = false;
    }
}

void hookGlVertexAttribPointer(GLuint index, GLint size, GLenum type, GLboolean normalized,
                               GLsizei stride, const void* pointer) {
    glVertexAttribPointer(index, size, type, normalized, stride, pointer);
    if (!inHostGl && singleContextMode.load()) {
        if (index < FrameDirtyTracker::kAttribs) {
            if (!frameTracker.arrayBufferSynced) {
                GLint actualVbo = 0;
                glGetIntegerv(GL_ARRAY_BUFFER_BINDING, &actualVbo);
                frameTracker.arrayBuffer = static_cast<GLuint>(actualVbo);
                frameTracker.arrayBufferSynced = true;
            }
            auto& a = frameTracker.attribs[index];
            a.size = size;
            a.type = type;
            a.normalized = normalized;
            a.stride = stride;
            a.pointer = pointer;
            a.buffer = frameTracker.arrayBuffer;
            frameTracker.attribStateSynced[index] = true;
        } else if (index != ~GLuint{0}) {
            frameTracker.frameValid = false;
        }
    }
}

void hookGlUseProgram(GLuint program) {
    glUseProgram(program);
    if (!inHostGl && singleContextMode.load()) {
        frameTracker.currentProgram = program;
        frameTracker.hashOp(0x110, program);
    }
}

void hookGlDeleteProgram(GLuint program) {
    glDeleteProgram(program);
    if (!inHostGl && singleContextMode.load() && !directOutput.load()) {
        frameTracker.onProgramDeleted(program);
    }
}

void hookGlLinkProgram(GLuint program) {
    glLinkProgram(program);
    if (!inHostGl && singleContextMode.load() && !directOutput.load()) {
        GLint linked = GL_FALSE;
        glGetProgramiv(program, GL_LINK_STATUS, &linked);
        frameTracker.onProgramLinked(program, linked != GL_FALSE);
    }
}

void hookGlViewport(GLint x, GLint y, GLsizei width, GLsizei height) {
    glViewport(x, y, width, height);
    if (!inHostGl && singleContextMode.load()) {
        frameTracker.noteViewport(x, y, width, height, stageFrame);
        frameTracker.hashOp(0x111, static_cast<uint64_t>(x), static_cast<uint64_t>(y),
                static_cast<uint64_t>(width), static_cast<uint64_t>(height));
    }
}

void hookGlScissor(GLint x, GLint y, GLsizei width, GLsizei height) {
    glScissor(x, y, width, height);
    if (!inHostGl && singleContextMode.load()) {
        frameTracker.hashOp(0x112, static_cast<uint64_t>(x), static_cast<uint64_t>(y),
                static_cast<uint64_t>(width), static_cast<uint64_t>(height));
    }
}

void hookGlEnable(GLenum cap) {
    glEnable(cap);
    if (!inHostGl && singleContextMode.load()) {
        if (cap == GL_PRIMITIVE_RESTART_FIXED_INDEX) {
            frameTracker.primitiveRestartFixed = true;
            frameTracker.primitiveRestartSynced = true;
        }
        if (!directOutput.load()) frameTracker.noteCapEnabled(cap, true, stageFrame);
        frameTracker.hashOp(0x113, cap);
    }
}

void hookGlDisable(GLenum cap) {
    glDisable(cap);
    if (!inHostGl && singleContextMode.load()) {
        if (cap == GL_PRIMITIVE_RESTART_FIXED_INDEX) {
            frameTracker.primitiveRestartFixed = false;
            frameTracker.primitiveRestartSynced = true;
        }
        if (!directOutput.load()) frameTracker.noteCapEnabled(cap, false, stageFrame);
        frameTracker.hashOp(0x114, cap);
    }
}

void hookGlColorMask(GLboolean r, GLboolean g, GLboolean b, GLboolean a) {
    glColorMask(r, g, b, a);
    if (!inHostGl && singleContextMode.load()) frameTracker.hashOp(0x115, r, g, b, a);
}

// Clear-value state belongs in the frame hash alongside glClear (0x117): a changed
// clear color alters clear results without any draw. Outside the stage window the
// state change is accounted through the monotonic external counter (blit's
// out-of-window role), value-delta tracked so redundant same-value writes keep
// static frames cacheable.
void hookGlClearColor(GLfloat r, GLfloat g, GLfloat b, GLfloat a) {
    glClearColor(r, g, b, a);
    if (!inHostGl && singleContextMode.load() && !directOutput.load()) {
        if (stageFrame) {
            const GLfloat vals[4] = {r, g, b, a};
            FrameDirtyTracker::mix64(frameTracker.frameHash, 0x116);
            FrameDirtyTracker::hashBytes(frameTracker.frameHash, vals, sizeof(vals));
        } else {
            frameTracker.noteClearColorOutsideWindow(r, g, b, a);
        }
    }
}

// Same accounting shape as blit (0x129): a clear writes the current framebuffer's
// content wherever it happens in the engine loop. In-window behavior is unchanged
// (stage redirect on FB0, content touch, clearCount + hashOp); out-of-window clears
// still account the target content (known RTT version bump, unknown target external
// counter) so the next frame cannot be judged clean from stale content.
void hookGlClear(GLbitfield mask) {
    if (!inHostGl && singleContextMode.load() && !directOutput.load()) {
        if (frameTracker.logicalFramebuffer == 0) ensureStageRedirected();
        bool fullRttReset = false;
        if (stageFrame) {
            frameTracker.noteStageColorClear(mask);
            if (frameTracker.logicalFramebuffer != 0) {
                fullRttReset = frameTracker.isFullUnscissoredColorClear(mask, false);
            }
            ++frameTracker.clearCount;
            frameTracker.hashOp(0x117, frameTracker.logicalFramebuffer, mask);
            frameTracker.hashScissorAndColorMask();
            if (mask & GL_COLOR_BUFFER_BIT) {
                GLfloat effectiveColor[4]{};
                glGetFloatv(GL_COLOR_CLEAR_VALUE, effectiveColor);
                FrameDirtyTracker::hashBytes(frameTracker.frameHash, effectiveColor, sizeof(effectiveColor));
            }
            if (mask & GL_DEPTH_BUFFER_BIT) {
                GLfloat effectiveDepth = 0.0f;
                glGetFloatv(GL_DEPTH_CLEAR_VALUE, &effectiveDepth);
                FrameDirtyTracker::hashBytes(frameTracker.frameHash, &effectiveDepth, sizeof(effectiveDepth));
            }
            if (mask & GL_STENCIL_BUFFER_BIT) {
                GLint effectiveStencil = 0;
                glGetIntegerv(GL_STENCIL_CLEAR_VALUE, &effectiveStencil);
                FrameDirtyTracker::mix64(frameTracker.frameHash, static_cast<uint64_t>(effectiveStencil));
            }
        }
        frameTracker.touchCurrentFramebufferTarget(fullRttReset);
    }
    glClear(mask);
}

void hookGlCullFace(GLenum mode) {
    glCullFace(mode);
    if (!inHostGl && singleContextMode.load() && !directOutput.load()) {
        frameTracker.noteCullFace(mode, stageFrame);
        frameTracker.hashOp(0x133, mode);
    }
}

void hookGlFrontFace(GLenum mode) {
    glFrontFace(mode);
    if (!inHostGl && singleContextMode.load() && !directOutput.load()) {
        frameTracker.noteFrontFace(mode, stageFrame);
        frameTracker.hashOp(0x134, mode);
    }
}

void hookGlBlendFuncSeparate(GLenum srgb, GLenum drgb, GLenum sa, GLenum da) {
    glBlendFuncSeparate(srgb, drgb, sa, da);
    if (!inHostGl && singleContextMode.load()) {
        if (!directOutput.load()) frameTracker.noteBlendFunc(srgb, drgb, sa, da, stageFrame);
        frameTracker.hashOp(0x118, srgb, drgb, sa, da);
    }
}

void hookGlBlendEquationSeparate(GLenum mrgb, GLenum ma) {
    glBlendEquationSeparate(mrgb, ma);
    if (!inHostGl && singleContextMode.load()) {
        if (!directOutput.load()) frameTracker.noteBlendEq(mrgb, ma, stageFrame);
        frameTracker.hashOp(0x119, mrgb, ma);
    }
}

void hookGlStencilFunc(GLenum func, GLint ref, GLuint mask) {
    glStencilFunc(func, ref, mask);
    if (!inHostGl && singleContextMode.load()) {
        if (!directOutput.load()) frameTracker.noteStencilFunc(func, ref, mask, stageFrame);
        frameTracker.hashOp(0x11A, func, static_cast<uint64_t>(ref), mask);
    }
}

void hookGlStencilOp(GLenum fail, GLenum zfail, GLenum zpass) {
    glStencilOp(fail, zfail, zpass);
    if (!inHostGl && singleContextMode.load()) {
        if (!directOutput.load()) frameTracker.noteStencilOp(fail, zfail, zpass, stageFrame);
        frameTracker.hashOp(0x11B, fail, zfail, zpass);
    }
}

void hookGlStencilMask(GLuint mask) {
    glStencilMask(mask);
    if (!inHostGl && singleContextMode.load()) {
        if (!directOutput.load()) frameTracker.noteStencilMask(mask, stageFrame);
        frameTracker.hashOp(0x11C, mask);
    }
}

void hookGlDepthFunc(GLenum func) {
    glDepthFunc(func);
    if (!inHostGl && singleContextMode.load()) {
        if (!directOutput.load()) frameTracker.noteDepthFunc(func, stageFrame);
        frameTracker.hashOp(0x11D, func);
    }
}

// Clear-value state belongs in the frame hash alongside glClearColor (0x116):
// a changed stencil/depth clear value alters clear results without any draw.
// Outside the stage window the state change goes through the monotonic external
// counter (hashOp no-ops there), value-delta tracked like glClearColor accounting.
void hookGlClearStencil(GLint s) {
    glClearStencil(s);
    if (!inHostGl && singleContextMode.load() && !directOutput.load()) {
        if (stageFrame) frameTracker.hashOp(0x11E, static_cast<uint64_t>(s));
        else frameTracker.noteClearStencilOutsideWindow(s);
    }
}

void hookGlClearDepthf(GLclampf depth) {
    glClearDepthf(depth);
    if (!inHostGl && singleContextMode.load() && !directOutput.load()) {
        if (stageFrame) {
            const GLfloat value = static_cast<GLfloat>(depth);
            FrameDirtyTracker::mix64(frameTracker.frameHash, 0x11F);
            FrameDirtyTracker::hashBytes(frameTracker.frameHash, &value, sizeof(value));
        } else {
            frameTracker.noteClearDepthfOutsideWindow(depth);
        }
    }
}

void hookGlUniform1f(GLint loc, GLfloat v0) {
    glUniform1f(loc, v0);
    if (!inHostGl && singleContextMode.load() && !directOutput.load()) {
        if (stageFrame) {
            frameTracker.noteUniformInsideWindow(0x120, loc, &v0, sizeof(v0));
            FrameDirtyTracker::mix64(frameTracker.frameHash, 0x120);
            FrameDirtyTracker::mix64(frameTracker.frameHash, static_cast<uint64_t>(loc));
            FrameDirtyTracker::hashBytes(frameTracker.frameHash, &v0, sizeof(v0));
        } else {
            frameTracker.noteUniformOutsideWindow(0x120, loc, &v0, sizeof(v0));
        }
    }
}

void hookGlUniform1fv(GLint loc, GLsizei count, const GLfloat* value) {
    glUniform1fv(loc, count, value);
    if (!inHostGl && singleContextMode.load() && !directOutput.load()) {
        const bool validPayload = (count == 0) || (value && count > 0 && count <= 1024);
        const size_t len = (value && count > 0 && count <= 1024) ? static_cast<size_t>(count) * sizeof(GLfloat) : 0;
        frameTracker.noteArrayUniform(0x121, loc, count, value, sizeof(GLfloat), 0, false, validPayload, stageFrame);
        if (stageFrame) {
            FrameDirtyTracker::mix64(frameTracker.frameHash, 0x121);
            FrameDirtyTracker::mix64(frameTracker.frameHash, static_cast<uint64_t>(loc));
            FrameDirtyTracker::mix64(frameTracker.frameHash, static_cast<uint64_t>(count));
            if (len > 0) {
                FrameDirtyTracker::hashBytes(frameTracker.frameHash, value, len);
            } else if (count > 1024) {
                frameTracker.frameValid = false;
            }
        }
    }
}

void hookGlUniform1i(GLint loc, GLint v0) {
    glUniform1i(loc, v0);
    if (!inHostGl && singleContextMode.load() && !directOutput.load()) {
        if (stageFrame) {
            frameTracker.noteUniformInsideWindow(0x122, loc, &v0, sizeof(v0));
            frameTracker.hashOp(0x122, static_cast<uint64_t>(loc), static_cast<uint64_t>(v0));
        } else {
            frameTracker.noteUniformOutsideWindow(0x122, loc, &v0, sizeof(v0));
        }
    }
}

void hookGlUniform2f(GLint loc, GLfloat v0, GLfloat v1) {
    glUniform2f(loc, v0, v1);
    if (!inHostGl && singleContextMode.load() && !directOutput.load()) {
        const GLfloat vals[2] = {v0, v1};
        if (stageFrame) {
            frameTracker.noteUniformInsideWindow(0x123, loc, vals, sizeof(vals));
            FrameDirtyTracker::mix64(frameTracker.frameHash, 0x123);
            FrameDirtyTracker::mix64(frameTracker.frameHash, static_cast<uint64_t>(loc));
            FrameDirtyTracker::hashBytes(frameTracker.frameHash, vals, sizeof(vals));
        } else {
            frameTracker.noteUniformOutsideWindow(0x123, loc, vals, sizeof(vals));
        }
    }
}

void hookGlUniform3f(GLint loc, GLfloat v0, GLfloat v1, GLfloat v2) {
    glUniform3f(loc, v0, v1, v2);
    if (!inHostGl && singleContextMode.load() && !directOutput.load()) {
        const GLfloat vals[3] = {v0, v1, v2};
        if (stageFrame) {
            frameTracker.noteUniformInsideWindow(0x124, loc, vals, sizeof(vals));
            FrameDirtyTracker::mix64(frameTracker.frameHash, 0x124);
            FrameDirtyTracker::mix64(frameTracker.frameHash, static_cast<uint64_t>(loc));
            FrameDirtyTracker::hashBytes(frameTracker.frameHash, vals, sizeof(vals));
        } else {
            frameTracker.noteUniformOutsideWindow(0x124, loc, vals, sizeof(vals));
        }
    }
}

void hookGlUniform4f(GLint loc, GLfloat v0, GLfloat v1, GLfloat v2, GLfloat v3) {
    glUniform4f(loc, v0, v1, v2, v3);
    if (!inHostGl && singleContextMode.load() && !directOutput.load()) {
        const GLfloat vals[4] = {v0, v1, v2, v3};
        if (stageFrame) {
            frameTracker.noteUniformInsideWindow(0x125, loc, vals, sizeof(vals));
            FrameDirtyTracker::mix64(frameTracker.frameHash, 0x125);
            FrameDirtyTracker::mix64(frameTracker.frameHash, static_cast<uint64_t>(loc));
            FrameDirtyTracker::hashBytes(frameTracker.frameHash, vals, sizeof(vals));
        } else {
            frameTracker.noteUniformOutsideWindow(0x125, loc, vals, sizeof(vals));
        }
    }
}

void hookGlUniform4fv(GLint loc, GLsizei count, const GLfloat* value) {
    glUniform4fv(loc, count, value);
    if (!inHostGl && singleContextMode.load() && !directOutput.load()) {
        const bool validPayload = (count == 0) || (value && count > 0 && count <= 1024);
        const size_t len = (value && count > 0 && count <= 1024) ? static_cast<size_t>(count) * 4 * sizeof(GLfloat) : 0;
        frameTracker.noteArrayUniform(0x126, loc, count, value, 4 * sizeof(GLfloat), 0, false, validPayload, stageFrame);
        if (stageFrame) {
            FrameDirtyTracker::mix64(frameTracker.frameHash, 0x126);
            FrameDirtyTracker::mix64(frameTracker.frameHash, static_cast<uint64_t>(loc));
            FrameDirtyTracker::mix64(frameTracker.frameHash, static_cast<uint64_t>(count));
            if (len > 0) {
                FrameDirtyTracker::hashBytes(frameTracker.frameHash, value, len);
            } else if (count > 1024) {
                frameTracker.frameValid = false;
            }
        }
    }
}

void hookGlUniformMatrix4fv(GLint loc, GLsizei count, GLboolean transpose, const GLfloat* value) {
    glUniformMatrix4fv(loc, count, transpose, value);
    if (!inHostGl && singleContextMode.load() && !directOutput.load()) {
        const bool validPayload = (count == 0) || (value && count > 0 && count <= 256);
        const size_t len = (value && count > 0 && count <= 256) ? static_cast<size_t>(count) * 16 * sizeof(GLfloat) : 0;
        frameTracker.noteArrayUniform(0x127, loc, count, value, 16 * sizeof(GLfloat),
                transpose ? 1ULL : 0ULL, true, validPayload, stageFrame);
        if (stageFrame) {
            FrameDirtyTracker::mix64(frameTracker.frameHash, 0x127);
            FrameDirtyTracker::mix64(frameTracker.frameHash, static_cast<uint64_t>(loc));
            FrameDirtyTracker::mix64(frameTracker.frameHash, static_cast<uint64_t>(count));
            FrameDirtyTracker::mix64(frameTracker.frameHash, transpose);
            if (len > 0) {
                FrameDirtyTracker::hashBytes(frameTracker.frameHash, value, len);
            } else if (count > 256) {
                frameTracker.frameValid = false;
            }
        }
    }
}

void hashDrawCommon(uint64_t opTag, GLuint maxCpuVertex, bool hasMaxCpuVertex) {
    FrameDirtyTracker::mix64(frameTracker.frameHash, opTag);
    FrameDirtyTracker::mix64(frameTracker.frameHash, frameTracker.logicalFramebuffer);
    FrameDirtyTracker::mix64(frameTracker.frameHash, frameTracker.currentProgram);
    if (frameTracker.currentProgram != 0) {
        if (frameTracker.programUniformUnknown.count(frameTracker.currentProgram)) {
            frameTracker.frameValid = false;
        } else {
            auto pit = frameTracker.programUniforms.find(frameTracker.currentProgram);
            if (pit != frameTracker.programUniforms.end() && !pit->second.empty()) {
                // Perf evaluation (kept per-draw sort, no snapshot cache): the map is bounded
                // by kMaxProgramUniforms (1024) per program and holds tens of entries in
                // practice; the copy+sort cost is negligible next to the GL draw call itself
                // and the CPU vertex/index hashing below. A cached sorted snapshot would add
                // invalidation coupling to every uniform-write path (noteUniform* / context
                // reset) for no measurable gain on the fixed supported kernels, whose program
                // sets are static; revisit only if device profiling shows this segment.
                std::vector<std::pair<GLint, uint64_t>> items(pit->second.begin(), pit->second.end());
                std::sort(items.begin(), items.end(),
                        [](const auto& a, const auto& b) { return a.first < b.first; });
                FrameDirtyTracker::mix64(frameTracker.frameHash, 0x140);
                FrameDirtyTracker::mix64(frameTracker.frameHash, items.size());
                for (const auto& entry : items) {
                    FrameDirtyTracker::mix64(frameTracker.frameHash, static_cast<uint64_t>(entry.first));
                    FrameDirtyTracker::mix64(frameTracker.frameHash, entry.second);
                }
            }
        }
    }
    frameTracker.hashScissorAndColorMask();
    if (frameTracker.hasViewport) {
        FrameDirtyTracker::mix64(frameTracker.frameHash, 0x111);
        FrameDirtyTracker::hashBytes(frameTracker.frameHash, frameTracker.lastViewport, sizeof(frameTracker.lastViewport));
    }
    if (frameTracker.hasCullFace) {
        // 0x139 (draw-state cull snapshot) — distinct from the glCullFace event tag 0x133
        // and the DrawArrays common block tag 0x138.
        FrameDirtyTracker::mix64(frameTracker.frameHash, 0x139);
        FrameDirtyTracker::mix64(frameTracker.frameHash, frameTracker.lastCullFace);
    }
    if (frameTracker.hasFrontFace) {
        FrameDirtyTracker::mix64(frameTracker.frameHash, 0x134);
        FrameDirtyTracker::mix64(frameTracker.frameHash, frameTracker.lastFrontFace);
    }
    // Hash every tracked texture unit (0..kUnits-1), not just 0..3: binding and
    // content-version tracking both cover kUnits, so units >= 4 must contribute
    // to the frame hash too. Identical command streams still hash identically
    // (identical boundTextures[4..7] and version values), preserving the RTT
    // version derivation invariant.
    for (size_t u = 0; u < FrameDirtyTracker::kUnits; ++u) {
        const GLuint tex = frameTracker.boundTextures[u];
        FrameDirtyTracker::mix64(frameTracker.frameHash, tex);
        if (tex) {
            auto it = frameTracker.textureVersions.find(tex);
            FrameDirtyTracker::mix64(frameTracker.frameHash,
                    it == frameTracker.textureVersions.end() ? 0 : it->second);
        }
    }
    if (frameTracker.activeUnit >= FrameDirtyTracker::kUnits) {
        frameTracker.frameValid = false;
    }
    for (size_t i = 0; i < FrameDirtyTracker::kAttribs; ++i) {
        const auto& a = frameTracker.attribs[i];
        if (!a.enabled) continue;
        // If context reset cleared attribute state before this frame, never dereference an
        // unconfirmed pointer as a CPU array (an EBO/VBO may already be bound in GL).
        if (!frameTracker.attribStateSynced[i]) {
            frameTracker.frameValid = false;
            continue;
        }
        FrameDirtyTracker::mix64(frameTracker.frameHash, 0x200 + i);
        FrameDirtyTracker::mix64(frameTracker.frameHash, static_cast<uint64_t>(a.size));
        FrameDirtyTracker::mix64(frameTracker.frameHash, a.type);
        FrameDirtyTracker::mix64(frameTracker.frameHash, a.normalized);
        FrameDirtyTracker::mix64(frameTracker.frameHash, static_cast<uint64_t>(a.stride));
        FrameDirtyTracker::mix64(frameTracker.frameHash, a.buffer);
        if (a.buffer != 0) {
            auto it = frameTracker.bufferVersions.find(a.buffer);
            FrameDirtyTracker::mix64(frameTracker.frameHash,
                    it == frameTracker.bufferVersions.end() ? 0 : it->second);
            FrameDirtyTracker::mix64(frameTracker.frameHash, reinterpret_cast<uintptr_t>(a.pointer));
            continue;
        }
        if (!hasMaxCpuVertex || !a.pointer || maxCpuVertex > FrameDirtyTracker::kMaxCpuVertexIndex) {
            frameTracker.frameValid = false;
            continue;
        }
        const size_t elemBytes = FrameDirtyTracker::attrElementBytes(a.size, a.type);
        const size_t stride = a.stride == 0 ? elemBytes : static_cast<size_t>(a.stride);
        if (elemBytes == 0 || a.stride < 0 || stride < elemBytes || stride > 256) {
            frameTracker.frameValid = false;
            continue;
        }
        const auto* base = static_cast<const uint8_t*>(a.pointer);
        const uint32_t vertCount = maxCpuVertex + 1;
        if (stride == elemBytes) {
            FrameDirtyTracker::hashBytes(frameTracker.frameHash, base, static_cast<size_t>(vertCount) * elemBytes);
        } else {
            for (uint32_t v = 0; v < vertCount; ++v) {
                FrameDirtyTracker::hashBytes(frameTracker.frameHash, base + static_cast<size_t>(v) * stride, elemBytes);
            }
        }
    }
}

void hookGlDrawElements(GLenum mode, GLsizei count, GLenum type, const void* indices) {
    // Same accounting shape as the blit family: the current framebuffer target is touched
    // in every window (outside stageFrame it bumps the RTT version or the external
    // counter, so out-of-window draws cannot leave stale content looking clean), while the
    // command-stream hash stays a pure function of in-window commands.
    if (!inHostGl && singleContextMode.load() && !directOutput.load()) {
        if (frameTracker.logicalFramebuffer == 0) ensureStageRedirected();
        frameTracker.touchCurrentFramebufferTarget();
        if (stageFrame) {
            frameTracker.beforeStageColorWrite();
            ++frameTracker.drawCount;
            frameTracker.hashOp(0x130, mode, static_cast<uint64_t>(count), type);
            uint32_t maxVertex = 0;
            bool hasMaxVertex = false;
            if (!frameTracker.elementBufferSynced) {
                GLint actualEbo = 0;
                glGetIntegerv(GL_ELEMENT_ARRAY_BUFFER_BINDING, &actualEbo);
                frameTracker.elementArrayBuffer = static_cast<GLuint>(actualEbo);
                frameTracker.elementBufferSynced = true;
            }
            if (frameTracker.elementArrayBuffer != 0) {
                auto it = frameTracker.bufferVersions.find(frameTracker.elementArrayBuffer);
                FrameDirtyTracker::mix64(frameTracker.frameHash, frameTracker.elementArrayBuffer);
                FrameDirtyTracker::mix64(frameTracker.frameHash,
                        it == frameTracker.bufferVersions.end() ? 0 : it->second);
                FrameDirtyTracker::mix64(frameTracker.frameHash, reinterpret_cast<uintptr_t>(indices));
                hasMaxVertex = (count <= 0);
            } else if (count > 0 && indices != nullptr && count <= FrameDirtyTracker::kMaxCpuIndices) {
                if (!frameTracker.primitiveRestartSynced) {
                    frameTracker.primitiveRestartFixed = (glIsEnabled(GL_PRIMITIVE_RESTART_FIXED_INDEX) != GL_FALSE);
                    frameTracker.primitiveRestartSynced = true;
                }
                const bool fixedRestart = frameTracker.primitiveRestartFixed;
                const size_t cnt = static_cast<size_t>(count);
                bool sawNonRestart = false;
                if (type == GL_UNSIGNED_BYTE) {
                    const auto* idx = static_cast<const uint8_t*>(indices);
                    FrameDirtyTracker::hashBytes(frameTracker.frameHash, idx, cnt);
                    for (size_t i = 0; i < cnt; ++i) {
                        if (fixedRestart && idx[i] == 0xFFu) continue;
                        if (!sawNonRestart || idx[i] > maxVertex) maxVertex = idx[i];
                        sawNonRestart = true;
                    }
                    hasMaxVertex = sawNonRestart;
                } else if (type == GL_UNSIGNED_SHORT) {
                    const auto* idx = static_cast<const uint16_t*>(indices);
                    FrameDirtyTracker::hashBytes(frameTracker.frameHash, idx, cnt * sizeof(uint16_t));
                    for (size_t i = 0; i < cnt; ++i) {
                        if (fixedRestart && idx[i] == 0xFFFFu) continue;
                        if (!sawNonRestart || idx[i] > maxVertex) maxVertex = idx[i];
                        sawNonRestart = true;
                    }
                    hasMaxVertex = sawNonRestart;
                } else if (type == GL_UNSIGNED_INT) {
                    const auto* idx = static_cast<const uint32_t*>(indices);
                    FrameDirtyTracker::hashBytes(frameTracker.frameHash, idx, cnt * sizeof(uint32_t));
                    for (size_t i = 0; i < cnt; ++i) {
                        if (fixedRestart && idx[i] == 0xFFFFFFFFu) continue;
                        if (!sawNonRestart || idx[i] > maxVertex) maxVertex = idx[i];
                        sawNonRestart = true;
                    }
                    hasMaxVertex = sawNonRestart;
                }
            } else if (count > FrameDirtyTracker::kMaxCpuIndices) {
                frameTracker.frameValid = false;
            }
            if (count > 0) hashDrawCommon(0x131, maxVertex, hasMaxVertex);
        }
    }
    glDrawElements(mode, count, type, indices);
}

void hookGlDrawArrays(GLenum mode, GLint first, GLsizei count) {
    // Out-of-window accounting mirrors hookGlDrawElements / the blit family (see above).
    if (!inHostGl && singleContextMode.load() && !directOutput.load()) {
        if (frameTracker.logicalFramebuffer == 0) ensureStageRedirected();
        frameTracker.touchCurrentFramebufferTarget();
        if (stageFrame) {
            frameTracker.beforeStageColorWrite();
            ++frameTracker.drawCount;
            frameTracker.hashOp(0x132, mode, static_cast<uint64_t>(first), static_cast<uint64_t>(count));
            const bool okRange = first >= 0 && count > 0
                    && static_cast<uint64_t>(first) + static_cast<uint64_t>(count) - 1 <= FrameDirtyTracker::kMaxCpuVertexIndex;
            const uint32_t maxV = okRange ? static_cast<uint32_t>(first + count - 1) : 0;
            // 0x138 (DrawArrays draw-state block) — distinct from the glCullFace event tag
            // 0x133 and the draw-state cull snapshot tag 0x139.
            hashDrawCommon(0x138, maxV, okRange);
        }
    }
    glDrawArrays(mode, first, count);
}

EGLBoolean hookEglChooseConfig(EGLDisplay display, const EGLint* attribList, EGLConfig* configs,
                               EGLint configSize, EGLint* numConfig) {
    if (allowed.load() && attribList && numConfig) {
        std::vector<EGLint> upgraded;
        bool rewritten = false;
        bool terminated = false;
        for (size_t i = 0; i < 128; i += 2) {
            const EGLint key = attribList[i];
            if (key == EGL_NONE) {
                terminated = true;
                break;
            }
            if (i + 1 >= 128) break;
            const EGLint val = attribList[i + 1];
            upgraded.push_back(key);
            if (key == EGL_RENDERABLE_TYPE && (val & EGL_OPENGL_ES2_BIT) && !(val & EGL_OPENGL_ES3_BIT_KHR)) {
                upgraded.push_back(val | EGL_OPENGL_ES3_BIT_KHR);
                rewritten = true;
            } else {
                upgraded.push_back(val);
            }
        }
        if (terminated && rewritten) {
            upgraded.push_back(EGL_NONE);
            if (eglChooseConfig(display, upgraded.data(), configs, configSize, numConfig)
                    && *numConfig > 0) {
                return EGL_TRUE;
            }
        }
        if (!terminated) {
            // No EGL_NONE inside the 128-entry scan bound (list longer than the bound or
            // malformed): the original list is passed through unchanged below, which skips
            // the ES3 renderable upgrade and — if the context stays ES2 — later latches
            // es3UpgradeFailed and closes single-context mode. Log so the silent degradation
            // is diagnosable instead of surfacing only as a status code.
            __android_log_print(ANDROID_LOG_WARN, "ArtemisOfficial",
                    "eglChooseConfig attribute list has no EGL_NONE within 128 entries; skipping ES3 renderable upgrade");
        }
    }
    return eglChooseConfig(display, attribList, configs, configSize, numConfig);
}

EGLContext hookEglCreateContext(EGLDisplay display, EGLConfig config, EGLContext shareContext, const EGLint* attribList) {
    if (allowed.load() && attribList) {
        EGLint renderable = 0;
        if (eglGetConfigAttrib(display, config, EGL_RENDERABLE_TYPE, &renderable)
                && (renderable & EGL_OPENGL_ES3_BIT_KHR)) {
            std::vector<EGLint> upgraded;
            bool rewritten = false;
            bool terminated = false;
            for (size_t i = 0; i < 64; i += 2) {
                const EGLint key = attribList[i];
                if (key == EGL_NONE) {
                    terminated = true;
                    break;
                }
                if (i + 1 >= 64) break;
                const EGLint val = attribList[i + 1];
                upgraded.push_back(key);
                if (key == EGL_CONTEXT_CLIENT_VERSION && val == 2) {
                    upgraded.push_back(3);
                    rewritten = true;
                } else {
                    upgraded.push_back(val);
                }
            }
            if (terminated && rewritten) {
                upgraded.push_back(EGL_NONE);
                EGLContext ctx = eglCreateContext(display, config, shareContext, upgraded.data());
                if (ctx != EGL_NO_CONTEXT) {
                    __android_log_print(ANDROID_LOG_INFO, "ArtemisOfficial", "Upgraded Artemis EGLContext to GLES3");
                    return ctx;
                }
                const EGLint eglErr = eglGetError();
                // Permanently latch only on definitive unsupported-config/attribute errors;
                // transient failures such as EGL_BAD_ALLOC fall back for this call without
                // permanently disabling future ES3 upgrades.
                if (eglErr == EGL_BAD_MATCH || eglErr == EGL_BAD_CONFIG || eglErr == EGL_BAD_ATTRIBUTE) {
                    es3UpgradeFailed = true;
                }
                __android_log_print(ANDROID_LOG_WARN, "ArtemisOfficial",
                        "GLES3 context upgrade failed (eglError=0x%x); falling back to requested context", eglErr);
            }
        }
    }
    return eglCreateContext(display, config, shareContext, attribList);
}

EGLBoolean hookEglDestroyContext(EGLDisplay display, EGLContext context) {
    const EGLBoolean destroyed = eglDestroyContext(display, context);
    if (destroyed && allowed.load() && context != EGL_NO_CONTEXT) {
        bool trackedContextDestroyed = false;
        bool notifyContextDestroyed = false;
        {
            std::lock_guard<std::mutex> lock(stageResourcesMutex);
            if (stageResources.context == context) {
                // Tracked stage context: notify for both current and non-current destroys
                // (release only when still bound to the calling thread, abandon otherwise)
                // so the Java pipeline never keeps GL names of a destroyed context.
                notifyContextDestroyed = true;
                trackedContextDestroyed = true;
                if (context == eglGetCurrentContext()) {
                    HostGlScope host;
                    stageResources.release();
                } else {
                    stageResources.abandon();
                }
                stageResources.lastTrackedContext = EGL_NO_CONTEXT;
            } else if (stageResources.lastTrackedContext == context) {
                trackedContextDestroyed = true;
                stageResources.lastTrackedContext = EGL_NO_CONTEXT;
            }
            // Auxiliary context destroys do not tear down live stage resources or bump
            // contextGeneration for another active render context.
        }
        if (notifyContextDestroyed) {
            Env env;
            if (env.value && bridge && contextDestroyedMethod) {
                env.value->CallStaticVoidMethod(bridge, contextDestroyedMethod);
                env.clear();
            }
            frameTracker.invalidateHistory();
        }
        if (trackedContextDestroyed) {
            contextGeneration.fetch_add(1);
        }
    }
    return destroyed;
}

EGLSurface createDirectSurface(EGLDisplay display,EGLConfig config,EGLNativeWindowType window,const EGLint* attributes) {
    EGLSurface result=eglCreateWindowSurface(display,config,window,attributes);
    if(result!=EGL_NO_SURFACE){directOutput=true;gesture=false;directRestored();}
    else status(3);
    return result;
}
EGLSurface createSurface(EGLDisplay display,EGLConfig config,EGLNativeWindowType window,const EGLint* attributes) {
    if(!allowed.load())return eglCreateWindowSurface(display,config,window,attributes);
    std::lock_guard<std::mutex> lifecycle(surfaceLifecycleMutex);
    if(nativeFailed.load()) {
        // An engine-driven recreate can race a late consumer disconnect too.
        // Never reconnect the screen while that consumer still owns its queue.
        return disconnect() == DisconnectResult::Detached
                ? createDirectSurface(display,config,window,attributes) : EGL_NO_SURFACE;
    }
    // lifecycle excludes concurrent consumer creation and handle retirement.
    if(!noSurfaces()) {
        // Fail closed: an existing registered surface (e.g. create-before-destroy or a prior
        // destroySurface failure) must not keep single-context stage redirection active while
        // the new unregistered surface swaps its default backbuffer.
        singleContextMode = false;
        directOutput = true;
        logicalWidth = 0;
        logicalHeight = 0;
        resetStageRedirectOnScopeExit();
        stageFrame = false;
        frameTracker.invalidateHistory();
        status(3);
        __android_log_print(ANDROID_LOG_WARN, "ArtemisOfficial",
                "Surface registry non-empty on createSurface; falling back to direct output");
        return eglCreateWindowSurface(display,config,window,attributes);
    }
    auto direct = [&]() { status(3); return createDirectSurface(display,config,window,attributes); };
    EGLint renderable = 0;
    // A denied import surface (unhooked GLES3 content-change entry points) keeps
    // single-context mode permanently off; the engine takes the producer path instead.
    if(!es3UpgradeFailed.load() && !singleContextDenied.load() && eglGetConfigAttrib(display,config,EGL_RENDERABLE_TYPE,&renderable)
            && (renderable & EGL_OPENGL_ES3_BIT_KHR)) {
        EGLSurface result = eglCreateWindowSurface(display,config,window,attributes);
        if(result == EGL_NO_SURFACE)return direct();
        std::shared_ptr<SurfaceState> state;
        SurfaceRegistry::node_type entry;
        try {
            state = std::make_shared<SurfaceState>();
            entry = allocateSurfaceEntry();
        } catch (const std::bad_alloc&) {
            eglDestroySurface(display,result);
            return direct();
        }
        state->singleContext = true;
        state->actual = result;
        state->config = config;
        if(!registerSurfaceEntry(entry,result,state)) {
            eglDestroySurface(display,result);
            return direct();
        }
        EGLint sw = 0, sh = 0;
        if(eglQuerySurface(display,result,EGL_WIDTH,&sw) && eglQuerySurface(display,result,EGL_HEIGHT,&sh) && sw > 0 && sh > 0) {
            screenSize = packScreenSize(sw,sh);
        }
        singleContextMode = true;
        directOutput = false;
        // Fresh surface/context: restore the full stage-failure retry budget.
        stageFailureStreak.store(0, std::memory_order_relaxed);
        gesture = false;
        const bool stageTarget = stageTargetWidth > 0 && stageTargetHeight > 0;
        logicalWidth = stageTarget ? stageTargetWidth : 0;
        logicalHeight = stageTarget ? stageTargetHeight : 0;
        if(stageTarget) {
            stageFrame = true;
            frameTracker.beginFrame(stageTargetWidth,stageTargetHeight);
        }
        frameTracker.invalidateHistory();
        __android_log_print(ANDROID_LOG_INFO,"ArtemisOfficial","Single-context GLES3 window created: screen=%dx%d stage=%dx%d",
                sw,sh,stageTargetWidth,stageTargetHeight);
        return result;
    }
    const char* extensions=eglQueryString(display,EGL_EXTENSIONS);
    if(!extensions || !std::strstr(extensions,"EGL_ANDROID_presentation_time"))return direct();
    auto presentationTime=reinterpret_cast<PFNEGLPRESENTATIONTIMEANDROIDPROC>(eglGetProcAddress("eglPresentationTimeANDROID"));
    if(!presentationTime)return direct();
    Env env;if(!env.value)return direct();
    auto* screen=reinterpret_cast<ANativeWindow*>(window);
    int width=ANativeWindow_getWidth(screen),height=ANativeWindow_getHeight(screen);
    if(width<=0 || height<=0)return direct();
    std::shared_ptr<SurfaceState> state;
    SurfaceRegistry::node_type producerEntry;
    try {
        state = std::make_shared<SurfaceState>();
        producerEntry = allocateSurfaceEntry();
        state->directAlias = allocateSurfaceEntry();
    } catch (const std::bad_alloc&) {
        // No consumer, native window reference or producer EGLSurface exists yet.
        __android_log_print(ANDROID_LOG_WARN,"ArtemisOfficial","Cannot allocate surface ownership; using direct output");
        return direct();
    }
    jobject screenSurface=ANativeWindow_toSurface(env.value,screen);
    const bool screenFailed = env.clear();
    if(!screenSurface || screenFailed) {
        if(screenSurface)env.value->DeleteLocalRef(screenSurface);
        return direct();
    }
    const bool stageTarget = stageTargetWidth > 0 && stageTargetHeight > 0;
    const int producerWidth = stageTarget ? stageTargetWidth : width;
    const int producerHeight = stageTarget ? stageTargetHeight : height;
    jobject source=env.value->CallStaticObjectMethod(bridge,connectMethod,screenSurface,
            width,height,producerWidth,producerHeight);
    env.value->DeleteLocalRef(screenSurface);
    const bool sourceFailed = env.clear();
    if(sourceFailed || !source) {
        if(source)env.value->DeleteLocalRef(source);
        status(3);
        return disconnect() == DisconnectResult::Detached ? direct() : EGL_NO_SURFACE;
    }
    ANativeWindow* producer=ANativeWindow_fromSurface(env.value,source);
    env.value->DeleteLocalRef(source);
    const bool producerFailed = env.clear();
    if(!producer || producerFailed) {
        if(producer)ANativeWindow_release(producer);
        status(3);
        return disconnect() == DisconnectResult::Detached ? direct() : EGL_NO_SURFACE;
    }
    EGLint format=0;
    if(!eglGetConfigAttrib(display,config,EGL_NATIVE_VISUAL_ID,&format) ||
       ANativeWindow_setBuffersGeometry(producer,producerWidth,producerHeight,format)!=0) {
        ANativeWindow_release(producer);
        status(3);
        return disconnect() == DisconnectResult::Detached ? direct() : EGL_NO_SURFACE;
    }
    EGLSurface result=eglCreateWindowSurface(display,config,producer,attributes);
    if(result==EGL_NO_SURFACE){ANativeWindow_release(producer);status(3);
        return disconnect() == DisconnectResult::Detached ? direct() : EGL_NO_SURFACE;}
    ANativeWindow_acquire(screen);
    state->actual=result;state->config=config;state->screen=screen;state->producer=producer;
    state->presentationTime=presentationTime;state->producerWidth=producerWidth;state->producerHeight=producerHeight;
    if (!registerSurfaceEntry(producerEntry,result,state)) {
        eglDestroySurface(display,result);
        ANativeWindow_release(producer);
        ANativeWindow_release(screen);
        status(3);
        return disconnect() == DisconnectResult::Detached ? direct() : EGL_NO_SURFACE;
    }
    screenSize=packScreenSize(width,height);directOutput=false;
    logicalWidth=stageTarget ? producerWidth : 0;
    logicalHeight=stageTarget ? producerHeight : 0;
    if(stageTarget)stageFrame=true;
    __android_log_print(ANDROID_LOG_INFO,"ArtemisOfficial","Producer connected: buffer=%dx%d screen=%dx%d stage=%d",
            producerWidth,producerHeight,width,height,stageTarget);
    return result;
}
EGLBoolean querySurface(EGLDisplay display,EGLSurface surface,EGLint attribute,EGLint* value) {
    auto state=findSurface(surface);
    if(!state)return eglQuerySurface(display,surface,attribute,value);
    std::lock_guard<std::mutex> operation(state->operationMutex);
    if(state->destroyed || state->actual==EGL_NO_SURFACE)return EGL_FALSE;
    if(state->singleContext && !state->direct && !directOutput.load() && value!=nullptr) {
        const int lw=logicalWidth.load(), lh=logicalHeight.load();
        if(lw>0 && lh>0) {
            if(attribute==EGL_WIDTH){*value=lw;return EGL_TRUE;}
            if(attribute==EGL_HEIGHT){*value=lh;return EGL_TRUE;}
        }
    }
    return eglQuerySurface(display,state->actual,attribute,value);
}
EGLBoolean makeCurrent(EGLDisplay display,EGLSurface draw,EGLSurface read,EGLContext context) {
    auto drawState=findSurface(draw),readState=findSurface(read);
    std::unique_lock<std::mutex> drawLock,readLock;
    if(drawState)drawLock=std::unique_lock<std::mutex>(drawState->operationMutex,std::defer_lock);
    if(readState && readState!=drawState)readLock=std::unique_lock<std::mutex>(readState->operationMutex,std::defer_lock);
    if(drawState && readState && readState!=drawState)std::lock(drawLock,readLock);
    else if(drawState)drawLock.lock();
    else if(readState)readLock.lock();
    if((drawState && (drawState->destroyed || drawState->actual==EGL_NO_SURFACE)) ||
       (readState && (readState->destroyed || readState->actual==EGL_NO_SURFACE)))return EGL_FALSE;
    const EGLBoolean ok = eglMakeCurrent(
            display,
            drawState ? drawState->actual : draw,
            readState ? readState->actual : read,
            context);
    if (ok && singleContextMode.load() && !directOutput.load()) {
        frameTracker.onContextSwitched(context, currentContextStageFbo(context));
    }
    return ok;
}
EGLBoolean destroySurface(EGLDisplay display,EGLSurface surface) {
    auto state=findSurface(surface);
    if(!state) {
        std::lock_guard<std::mutex> lifecycle(surfaceLifecycleMutex);
        EGLBoolean destroyed=eglDestroySurface(display,surface);
        if(destroyed && allowed.load() && noSurfaces())status(5);
        return destroyed;
    }
    std::lock_guard<std::mutex> operation(state->operationMutex);
    std::lock_guard<std::mutex> lifecycle(surfaceLifecycleMutex);
    if(state->destroyed)return EGL_FALSE;
    auto failClosedOnDestroyError = [&]() {
        state->direct = true;
        if (state->singleContext) {
            singleContextMode = false;
            directOutput = true;
            logicalWidth = 0;
            logicalHeight = 0;
            resetStageRedirectOnScopeExit();
            stageFrame = false;
            frameTracker.invalidateHistory();
        }
        status(3);
        return EGL_FALSE;
    };
    if(state->actual!=EGL_NO_SURFACE) {
        if(!eglDestroySurface(display,state->actual))return failClosedOnDestroyError();
        EGLSurface destroyed=state->actual;
        state->actual=EGL_NO_SURFACE;
        // If retiring the retained producer fails, do not keep a freed actual
        // handle as an alias. The original, still-live handle permits retry.
        if(state->retainedProducer!=EGL_NO_SURFACE) {
            std::lock_guard<std::mutex> lock(surfacesMutex);
            surfaces.erase(destroyed);
        }
    }
    if(state->retainedProducer!=EGL_NO_SURFACE) {
        if(!eglDestroySurface(display,state->retainedProducer))return failClosedOnDestroyError();
        state->retainedProducer=EGL_NO_SURFACE;
    }
    const bool disconnected=state->singleContext || state->direct || disconnect() == DisconnectResult::Detached;
    if(state->singleContext) {
        singleContextMode=false;
        frameTracker.invalidateHistory();
    }
    if(state->producer)ANativeWindow_release(state->producer);
    if(state->screen)ANativeWindow_release(state->screen);
    state->producer=state->screen=nullptr;state->destroyed=true;
    logicalWidth=logicalHeight=0;directOutput=true;gesture=false;
    {
        std::lock_guard<std::mutex> lock(surfacesMutex);
        for(auto it=surfaces.begin();it!=surfaces.end();) {
            if(it->second==state)it=surfaces.erase(it);else ++it;
        }
    }
    status(disconnected ? 5 : 3);
    return EGL_TRUE;
}
// Caller owns operationMutex. Do not destroy the engine-visible producer handle:
// EGL may otherwise recycle its value while the engine still uses it as an alias.
RestoreResult restoreDirect(EGLDisplay display,const std::shared_ptr<SurfaceState>& state) {
    std::lock_guard<std::mutex> lifecycle(surfaceLifecycleMutex);
    status(3);
    const DisconnectResult disconnected = disconnect();
    if(disconnected == DisconnectResult::Pending)return RestoreResult::Pending;
    if(disconnected != DisconnectResult::Detached)return RestoreResult::Failed;
    // The consumer has confirmed EGL detachment. Only now may direct output
    // connect to the screen's queue; keep all producer aliases live until then.
    EGLContext context=eglGetCurrentContext();
    EGLSurface restored=eglCreateWindowSurface(display,state->config,state->screen,nullptr);
    if(restored==EGL_NO_SURFACE)return RestoreResult::Failed;
    // Register the real alias before committing the EGL switch; this consumes only
    // the preallocated node and never allocates after the screen has been acquired.
    if (!registerSurfaceEntry(state->directAlias,restored,state)) {
        eglDestroySurface(display,restored);
        return RestoreResult::Failed;
    }
    if (!eglMakeCurrent(display,restored,restored,context)) {
        {
            std::lock_guard<std::mutex> lock(surfacesMutex);
            state->directAlias = surfaces.extract(restored);
            if (!state->directAlias.empty()) state->directAlias.mapped().reset();
        }
        eglDestroySurface(display,restored);
        return RestoreResult::Failed;
    }
    state->retainedProducer=state->actual;
    state->actual=restored;state->direct=true;
    if(state->producer){ANativeWindow_release(state->producer);state->producer=nullptr;}
    directOutput=true;gesture=false;
    directRestored();
    __android_log_print(ANDROID_LOG_WARN,"ArtemisOfficial","Consumer unavailable; restored official direct output without restarting VM");
    return RestoreResult::Restored;
}
EGLBoolean recoverSwap(EGLDisplay display,const std::shared_ptr<SurfaceState>& state) {
    // EndRender treats EGL_FALSE as DeviceReset(false), discarding its handles.
    // While detachment is pending, drop this frame without swapping the stalled
    // producer queue. The next frame polls the same job and keeps the VM alive.
    return restoreDirect(display,state) == RestoreResult::Failed ? EGL_FALSE : EGL_TRUE;
}
EGLBoolean swap(EGLDisplay display,EGLSurface surface) {
    auto state=findSurface(surface);
    if(!state)return eglSwapBuffers(display,surface);
    std::lock_guard<std::mutex> operation(state->operationMutex);
    if(state->destroyed || state->actual==EGL_NO_SURFACE)return EGL_FALSE;
    if(state->direct)return eglSwapBuffers(display,state->actual);
    if(state->singleContext) {
        const EGLSurface actualSurface=state->actual;
        EGLint screenW=0, screenH=0;
        if(!eglQuerySurface(display,actualSurface,EGL_WIDTH,&screenW)
                || !eglQuerySurface(display,actualSurface,EGL_HEIGHT,&screenH))return EGL_FALSE;
        if(screenW<=0 || screenH<=0)return eglSwapBuffers(display,actualSurface);
        screenSize=packScreenSize(screenW,screenH);
        const int lw=logicalWidth.load(), lh=logicalHeight.load();
        const EGLContext currentCtx=eglGetCurrentContext();
        const uint64_t currentGen=contextGeneration.load();
        GLuint stageFbo=0, stageTex=0, stageProgram=0, stageVao=0, stageSampler=0;
        GLint stageBoundsLoc=-1, stageSourceLoc=-1;
        bool stageValid=false;
        {
            std::lock_guard<std::mutex> lock(stageResourcesMutex);
            stageValid=stageFrame && !directOutput.load() && lw>0 && lh>0
                    && stageResources.fbo!=0 && stageResources.texture!=0
                    && stageResources.program!=0 && stageResources.vao!=0 && stageResources.sampler!=0
                    && stageResources.boundsLoc>=0 && stageResources.sourceLoc>=0
                    && stageResources.width==lw && stageResources.height==lh
                    && stageResources.context==currentCtx
                    && stageResources.generation==currentGen;
            if(stageValid) {
                stageFbo=stageResources.fbo;
                stageTex=stageResources.texture;
                stageProgram=stageResources.program;
                stageVao=stageResources.vao;
                stageSampler=stageResources.sampler;
                stageBoundsLoc=stageResources.boundsLoc;
                stageSourceLoc=stageResources.sourceLoc;
            }
        }
        // Only skip presenting the stage quad when the engine directly wrote the window
        // backbuffer outside the stage window (and did not redraw the stage). Arbitrary
        // off-stage mutations (externalMutationCounter) still mark frameDirty below without
        // suppressing stage presentation and exposing an undrawn backbuffer.
        const bool skipUnwrittenStage = !frameTracker.stageDrawnThisFrame
                && frameTracker.windowWrittenOutsideStage;
        if(!stageValid || skipUnwrittenStage) {
            frameTracker.invalidateHistory();
            Env env;
            if(env.value && bridge && renderStageMethod) {
                env.value->CallStaticIntMethod(bridge,renderStageMethod,0,0,0,screenW,screenH,
                        JNI_FALSE,JNI_FALSE,static_cast<jlong>(currentGen));
                env.clear();
            }
            frameTracker.stageRedirected=false;
            frameTracker.stageReadRedirected=false;
            frameTracker.windowWrittenOutsideStage=false;
            return eglSwapBuffers(display,actualSurface);
        }
        const bool frameDirty=frameTracker.stageDrawnThisFrame
                ? frameTracker.finishFrame(lw,lh,true)
                : (externalMutationCounter.load(std::memory_order_relaxed) != frameTracker.lastExternalMutation);
        StageGlState saved;
        if (saved.contextLost) {
            handleStageContextLost(currentCtx);
            return eglSwapBuffers(display,actualSurface);
        }
        HostGlScope host;
        GLuint outputTex=stageTex;
        {
            Env env;
            if(env.value && bridge && renderStageMethod) {
                const jint filtered=env.value->CallStaticIntMethod(bridge,renderStageMethod,
                        static_cast<jint>(stageTex),lw,lh,screenW,screenH,
                        JNI_TRUE,frameDirty?JNI_TRUE:JNI_FALSE,static_cast<jlong>(currentGen));
                if(!env.clear() && filtered>0 && glIsTexture(static_cast<GLuint>(filtered))) {
                    outputTex=static_cast<GLuint>(filtered);
                }
            }
        }
        bool stillValid=false;
        {
            std::lock_guard<std::mutex> lock(stageResourcesMutex);
            stillValid=stageResources.context==currentCtx && stageResources.generation==currentGen
                    && stageResources.fbo==stageFbo && stageResources.program==stageProgram
                    && stageResources.vao==stageVao && stageResources.sampler==stageSampler;
        }
        if(!stillValid) {
            frameTracker.stageRedirected=false;
            frameTracker.stageReadRedirected=false;
            saved.restore(stageFbo);
            if (drainStageGlErrors()) {
                handleStageContextLost(currentCtx);
            }
            return eglSwapBuffers(display,actualSurface);
        }
        int fitW=0, fitH=0;
        if(static_cast<int64_t>(screenW)*lh<=static_cast<int64_t>(screenH)*lw) {
            fitW=screenW;
            fitH=static_cast<int>(std::max<int64_t>(1,static_cast<int64_t>(lh)*screenW/lw));
        } else {
            fitW=static_cast<int>(std::max<int64_t>(1,static_cast<int64_t>(lw)*screenH/lh));
            fitH=screenH;
        }
        // Defensive non-negative clamp: the fit branch math guarantees fitW<=screenW and
        // fitH<=screenH, but a driver-reported oversized screen dimension must not feed a
        // negative letterbox offset into the bounds uniform.
        const int fitX=std::max(0,(screenW-fitW)/2), fitY=std::max(0,(screenH-fitH)/2);
        glBindFramebuffer(GL_FRAMEBUFFER,0);
        glViewport(0,0,screenW,screenH);
        if(saved.useIndexedBuffer0) {
            for(size_t i=1;i<sizeof(StageGlState::kCaps)/sizeof(StageGlState::kCaps[0]);++i)glDisable(StageGlState::kCaps[i]);
            saved.disableiFn(GL_BLEND,0);
            saved.colorMaskiFn(0,GL_TRUE,GL_TRUE,GL_TRUE,GL_TRUE);
        } else {
            for(GLenum cap : StageGlState::kCaps)glDisable(cap);
            glColorMask(GL_TRUE,GL_TRUE,GL_TRUE,GL_TRUE);
        }
        glClearColor(0.0f,0.0f,0.0f,1.0f);
        glClear(GL_COLOR_BUFFER_BIT);
        glUseProgram(stageProgram);
        glBindVertexArray(stageVao);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D,outputTex);
        glBindSampler(0,stageSampler);
        glUniform1i(stageSourceLoc,0);
        glUniform4f(stageBoundsLoc,
                static_cast<float>(fitX)/screenW,static_cast<float>(fitY)/screenH,
                static_cast<float>(fitW)/screenW,static_cast<float>(fitH)/screenH);
        glDrawArrays(GL_TRIANGLE_STRIP,0,4);
        frameTracker.stageRedirected=false;
        frameTracker.stageReadRedirected=false;
        frameTracker.windowWrittenOutsideStage=false;
        saved.restore(stageFbo);
        // Present drawing is our GL work; drain its error flags (bounded) so they do
        // not leak into the engine's subsequent glGetError calls, and transition cleanly if
        // GL_CONTEXT_LOST_KHR is observed.
        if (drainStageGlErrors()) {
            handleStageContextLost(currentCtx);
        }
        return eglSwapBuffers(display,actualSurface);
    }
    if(!healthy()) {
        // The current frame was drawn to the old producer. Let the next normal
        // BeginRender see the real screen size and rebuild the engine's scale.
        return recoverSwap(display,state);
    }
    EGLint width=0,height=0;
    if(!eglQuerySurface(display,state->actual,EGL_WIDTH,&width)||!eglQuerySurface(display,state->actual,EGL_HEIGHT,&height))return EGL_FALSE;
    GLint viewport[4]={},framebuffer=0;glGetIntegerv(GL_VIEWPORT,viewport);
    glGetIntegerv(GL_FRAMEBUFFER_BINDING,&framebuffer);
    const bool original=stageFrame && framebuffer==0 && width==logicalWidth.load() && height==logicalHeight.load()
            && viewport[0]==0 && viewport[1]==0 && viewport[2]==width && viewport[3]==height;
    const auto stamp=std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now().time_since_epoch()).count();
    if(!state->presentationTime(display,state->actual,stamp)) {
        return recoverSwap(display,state);
    }
    Env env;
    if(!env.value)return recoverSwap(display,state);
    env.value->CallStaticVoidMethod(bridge,submittedMethod,static_cast<jlong>(stamp),width,height,original?JNI_TRUE:JNI_FALSE);
    if(env.clear())return recoverSwap(display,state);
    return eglSwapBuffers(display,state->actual);
}
void displayStage(void* stage) {
    // The bundled-library Build ID gate selects this layout. Constructors request int
    // width/height; the SIMD SCVTF in SetupScale converts those integer bits.
    int width=0,height=0;
    if(stage) {
        std::memcpy(&width,static_cast<char*>(stage)+0x100,sizeof(width));
        std::memcpy(&height,static_cast<char*>(stage)+0x104,sizeof(height));
    }
    const bool valid=width>0 && height>0 && width<=8192 && height<=8192 &&
            static_cast<int64_t>(width)*height*16 < 256LL*1024*1024;
    StageScope scope(valid ? width : 0, valid ? height : 0);
    if(valid && !directOutput.load()) {
        auto state=findSurface(eglGetCurrentSurface(EGL_DRAW));
        if(state) {
            std::lock_guard<std::mutex> operation(state->operationMutex);
            if(!state->destroyed && !state->direct && state->actual!=EGL_NO_SURFACE) {
                if(state->singleContext) {
                    logicalWidth=width;logicalHeight=height;stageFrame=true;
                    frameTracker.beginFrame(width,height);
                } else {
                    bool resized=true;
                    if(state->producerWidth!=width || state->producerHeight!=height) {
                        // Resize the real queue before BeginRender rebuilds its scale.
                        Env env;
                        resized=env.value && env.value->CallStaticBooleanMethod(bridge,resizeMethod,width,height);
                        resized=!env.clear() && resized;
                        if(resized)resized=ANativeWindow_setBuffersGeometry(state->producer,width,height,0)==0;
                        if(resized) {
                            state->producerWidth=width;state->producerHeight=height;
                            __android_log_print(ANDROID_LOG_INFO,"ArtemisOfficial","Logical producer %dx%d",width,height);
                        }
                    }
                    if(resized){logicalWidth=width;logicalHeight=height;stageFrame=true;}
                }
            }
        }
    } else if(!valid) {
        frameTracker.invalidateHistory();
    }
    // BeginRender can reenter the EGL hooks, so release operationMutex first.
    stageDisplay(stage);
}
struct Viewport {float left=0,top=0,width=0,height=0;int sw=0,sh=0;};
Viewport inputViewport() {
    Viewport v;v.sw=logicalWidth.load();v.sh=logicalHeight.load();
    if(directOutput.load()||v.sw<=0||v.sh<=0)return {};
    const uint64_t size=currentScreenSize();
    int dw=static_cast<int>(size>>32),dh=static_cast<int>(static_cast<uint32_t>(size));
    if(dw<=0||dh<=0)return {};
    if(static_cast<int64_t>(dw)*v.sh<=static_cast<int64_t>(dh)*v.sw) {
        v.width=dw;v.height=std::max<int64_t>(1,static_cast<int64_t>(v.sh)*dw/v.sw);
    } else {
        v.width=std::max<int64_t>(1,static_cast<int64_t>(v.sw)*dh/v.sh);v.height=dh;
    }
    v.left=(dw-static_cast<int>(v.width))/2;v.top=(dh-static_cast<int>(v.height))/2;return v;
}
float inputX(const AInputEvent* event,size_t index) {
    float x=AMotionEvent_getX(event,index);auto v=inputViewport();
    return v.sw>0 ? std::clamp((x-v.left)*v.sw/v.width,0.0f,static_cast<float>(v.sw)):x;
}
float inputY(const AInputEvent* event,size_t index) {
    float y=AMotionEvent_getY(event,index);auto v=inputViewport();
    return v.sh>0 ? std::clamp((y-v.top)*v.sh/v.height,0.0f,static_cast<float>(v.sh)):y;
}
int inputEvent(AInputQueue* queue,AInputEvent** event) {
    while(true) {
        int result=AInputQueue_getEvent(queue,event);if(result<0)return result;
        if(AInputEvent_getType(*event)!=AINPUT_EVENT_TYPE_MOTION)return result;
        auto v=inputViewport();if(v.sw<=0)return result;
        int action=AMotionEvent_getAction(*event)&AMOTION_EVENT_ACTION_MASK;
        if(action==AMOTION_EVENT_ACTION_DOWN) {
            float x=AMotionEvent_getX(*event,0),y=AMotionEvent_getY(*event,0);
            gesture=x>=v.left && y>=v.top && x<v.left+v.width && y<v.top+v.height;
        }
        bool deliver=gesture.load();
        if(action==AMOTION_EVENT_ACTION_UP||action==AMOTION_EVENT_ACTION_CANCEL)gesture=false;
        if(deliver)return result;
        AInputQueue_finishEvent(queue,*event,1); // Black bars cannot start a game gesture.
    }
}
struct Image {uintptr_t base=0;const Elf64_Phdr* phdr=nullptr;size_t count=0;};
int findImage(dl_phdr_info* info,size_t,void* data) {
    auto& image=*static_cast<Image*>(data);
    if(static_cast<uintptr_t>(info->dlpi_addr)!=image.base)return 0;
    image.phdr=info->dlpi_phdr;image.count=info->dlpi_phnum;return 1;
}
// Identify the loaded image without opening or hashing its entire file on the UI thread.
std::string loadedBuildId(const Image& image) {
    if (!image.phdr) return {};
    for (size_t i = 0; i < image.count; ++i) {
        const auto& noteSegment = image.phdr[i];
        if (noteSegment.p_type != PT_NOTE) continue;
        bool readable = false;
        for (size_t j = 0; j < image.count; ++j) {
            const auto& load = image.phdr[j];
            if (load.p_type != PT_LOAD || !(load.p_flags & PF_R) || noteSegment.p_vaddr < load.p_vaddr) continue;
            Elf64_Addr offset = noteSegment.p_vaddr - load.p_vaddr;
            if (offset <= load.p_memsz && noteSegment.p_memsz <= load.p_memsz - offset) readable = true;
        }
        if (!readable || noteSegment.p_vaddr > std::numeric_limits<uintptr_t>::max() - image.base) continue;
        uintptr_t start = image.base + noteSegment.p_vaddr;
        if (noteSegment.p_memsz > std::numeric_limits<uintptr_t>::max() - start) continue;
        const auto* at = reinterpret_cast<const unsigned char*>(start);
        size_t length = noteSegment.p_memsz;
        while (length >= sizeof(Elf64_Nhdr)) {
            Elf64_Nhdr note{};
            std::memcpy(&note, at, sizeof(note));
            size_t name = (static_cast<size_t>(note.n_namesz) + 3u) & ~size_t{3};
            size_t desc = (static_cast<size_t>(note.n_descsz) + 3u) & ~size_t{3};
            if (name > length - sizeof(note) || desc > length - sizeof(note) - name) break;
            if (note.n_type == NT_GNU_BUILD_ID && note.n_namesz == 4 && note.n_descsz == 20
                    && std::memcmp(at + sizeof(note), "GNU", 4) == 0) {
                char hex[41]{};
                const unsigned char* id = at + sizeof(note) + name;
                for (size_t n = 0; n < 20; ++n) {
                    hex[2 * n] = "0123456789abcdef"[id[n] >> 4];
                    hex[2 * n + 1] = "0123456789abcdef"[id[n] & 15];
                }
                return hex;
            }
            size_t total = sizeof(note) + name + desc;
            at += total;
            length -= total;
        }
    }
    return {};
}
struct Change {void** slot;void* before;void* after;int protection;};
bool collect(const Image& image,std::vector<Change>& changes,void** slot,void* before,void* after) {
    if(!before || !after || *slot!=before)return false;
    uintptr_t at=reinterpret_cast<uintptr_t>(slot);
    for(size_t i=0;i<image.count;++i) {
        const auto& p=image.phdr[i];
        if(p.p_type!=PT_LOAD || at<image.base+p.p_vaddr || at+sizeof(void*)>image.base+p.p_vaddr+p.p_memsz)continue;
        int protection=PROT_READ|(p.p_flags&PF_W?PROT_WRITE:0)|(p.p_flags&PF_X?PROT_EXEC:0);
        for(size_t j=0;j<image.count;++j){const auto& q=image.phdr[j];if(q.p_type==PT_GNU_RELRO && at>=image.base+q.p_vaddr && at<image.base+q.p_vaddr+q.p_memsz)protection&=~PROT_WRITE;}
        changes.push_back({slot,before,after,protection});return true;
    }return false;
}
bool change(const Change& item,bool restore) {
    long size=sysconf(_SC_PAGESIZE);if(size<=0)return false;
    uintptr_t page=reinterpret_cast<uintptr_t>(item.slot)&~(static_cast<uintptr_t>(size)-1);
    if(mprotect(reinterpret_cast<void*>(page),size,item.protection|PROT_WRITE)!=0)return false;
    __atomic_store_n(item.slot,restore?item.before:item.after,__ATOMIC_RELEASE);
    return mprotect(reinterpret_cast<void*>(page),size,item.protection)==0;
}
uintptr_t address(uintptr_t base,Elf64_Addr pointer){return pointer<base?base+pointer:pointer;}
}

// Install-time fail-closed deny set. GLES3 content-write entry points that the
// host dirty-frame tracker (FrameDirtyTracker) cannot observe, derived as:
//   GLES 3.0/3.1/3.2 entry-point surface
//   − the mandatory hook table and the optional hook table above
//   − known-harmless read-only/sync/lifecycle/debug classes.
// If the engine's PLT imports any of these, the command-stream hash can miss content
// changes, so single-context mode is refused and the producer path is used instead;
// the refusal reason is logged with the offending import names. The two identity-supported
// kernels (libartemis.so / libartemis-v6.so, per ArtemisLibraryIdentity) import none of
// these, verified by an ELF dynsym scan of all four bundled kernels, so supported kernels
// keep single-context mode. The other two bundled kernels never reach this gate
// (verifyLibrary refuses them): libartemis-compatible.so imports none of these but misses
// mandatory hooks, and the clean-room libartemis-clean.so imports eight of the unhooked
// uniform forms below — if it ever gains identity support, this deny set refuses its
// single-context mode (producer-path fallback) as designed.
// - Mapped CPU writes into GPU stores: glMapBufferRange / glFlushMappedBufferRange /
//   glUnmapBuffer (glUnmapBuffer completes the map cycle; glMapBuffer is the
//   OES_mapbuffer counterpart of the same hazard class).
// - GPU-GPU copies: glCopyBufferSubData, glCopyImageSubData (EXT_copy_image, core in ES 3.2).
// - Unhooked 2D content write: glCompressedTexSubImage2D.
// - Per-attachment clears: glClearBufferfv / glClearBufferiv / glClearBufferuiv / glClearBufferfi.
// - Non-2D content/attachment class (backstop for the documented non-2D mutation gap):
//   glTexImage3D / glTexSubImage3D / glCompressedTexImage3D / glCompressedTexSubImage3D /
//   glCopyTexSubImage3D / glTexStorage3D / glTexStorage2DMultisample / glTexStorage3DMultisample
//   (ES 3.2 entry recorded verbatim; same multisample immutable-storage class as the
//   already-denied glTexStorage2DMultisample — a target the tracked 2D mutation surface
//   never observes) / glTexImage2DMultisample / glTexImage3DMultisample /
//   glFramebufferTextureLayer / glFramebufferTexture (ES 3.2) / glTexBuffer / glTexBufferRange
//   (ES 3.2).
// - GPU-driven writes outside the tracked command model: glDispatchCompute with its
//   glMemoryBarrier ordering companion, the transform-feedback family
//   glBeginTransformFeedback / glEndTransformFeedback / glPauseTransformFeedback /
//   glResumeTransformFeedback (same class as glDispatchCompute: the GL drives vertex/
//   geometry output into bound buffers outside any tracked write/draw/clear command,
//   so imported-but-untracked use would be invisible to the command-stream hash), and
//   indirect draws whose parameter counts live in buffer contents: glDrawArraysIndirect /
//   glDrawElementsIndirect, as well as unhooked GLES 3.0 instanced/range draw entry points
//   glDrawArraysInstanced / glDrawElementsInstanced / glDrawRangeElements.
// - Unhooked uniform upload entry points (uniform values are render-affecting state the
//   hash cannot observe without a hook; the eight hooked forms glUniform1f / glUniform1fv /
//   glUniform1i / glUniform2f / glUniform3f / glUniform4f / glUniform4fv /
//   glUniformMatrix4fv stay hooked instead of denied): the remaining 25 GLES 3.x value
//   setters glUniform1iv / glUniform2i / glUniform3i / glUniform4i / glUniform2fv /
//   glUniform3fv / glUniform2iv / glUniform3iv / glUniform4iv / glUniform1ui..4ui /
//   glUniform1uiv..4uiv / glUniformMatrix2fv / glUniformMatrix3fv /
//   glUniformMatrix2x3fv..4x3fv.
// - GLES 3.1 named-program uniform uploads, the full glProgramUniform* family (all 33
//   signed/unsigned/float/matrix forms): they address a program object directly instead of
//   the current program, so the tracker's (currentProgram, loc) uniform snapshot can never
//   observe the write. The unsigned forms mirror the glUniform1ui..4uiv denials above, and
//   Matrix4fv mirrors the hooked glUniformMatrix4fv (the hook does not transfer: this entry
//   point bypasses the current program entirely).
// - GLES 3.1 program-pipeline objects: a bound pipeline replaces the current-program uniform
//   model entirely (glUseProgramStages binds per-stage programs; glActiveShaderProgram
//   selects the program glUniform* applies to while a pipeline is bound). The lifecycle
//   entries are denied as usage markers of that class — an import-based gate cannot see
//   object use, only symbol presence — deliberately overriding the generic object-lifecycle
//   carve-out below for this family.
// - Multi-render-target draw-target selection: glDrawBuffers (which attachments a draw
//   writes is part of the rendered result).
// Known-harmless carve-outs (never denied): glGet*/glIs*/query objects, glReadPixels,
// sync primitives, object lifecycle (glGen*/glCreate*/glDelete*/glAttach*/glLink*/... —
// except the program-pipeline lifecycle above), debug/markers; renderbuffer allocation
// (glRenderbufferStorage / glRenderbufferStorageMultisample — allocation only, content is
// produced by tracked draws and renderbuffers are never sampled);
// render-state setters outside the hook table remain the documented FrameDirtyTracker residual posture
// (see SPEC §12), not a content-write class; glBindVertexArray / glBindVertexArrayOES are tracked in
// optionalHooks (invalidating elementBufferSynced so host VAO switches re-read GL_ELEMENT_ARRAY_BUFFER_BINDING),
// and therefore belong to optionalHooks rather than this deny table.
static const char* const kDeniedContentChangeImports[] = {
    "glMapBufferRange", "glFlushMappedBufferRange", "glUnmapBuffer", "glMapBuffer",
    "glCopyBufferSubData", "glCopyImageSubData",
    "glCompressedTexSubImage2D",
    "glClearBufferfv", "glClearBufferiv", "glClearBufferuiv", "glClearBufferfi",
    "glTexImage3D", "glTexSubImage3D", "glCompressedTexImage3D", "glCompressedTexSubImage3D",
    "glCopyTexSubImage3D", "glTexStorage3D", "glTexStorage2DMultisample",
    "glTexStorage3DMultisample", "glTexImage2DMultisample", "glTexImage3DMultisample",
    "glFramebufferTextureLayer", "glFramebufferTexture", "glTexBuffer", "glTexBufferRange",
    "glDispatchCompute", "glMemoryBarrier",
    "glBeginTransformFeedback", "glEndTransformFeedback",
    "glPauseTransformFeedback", "glResumeTransformFeedback",
    "glDrawArraysIndirect", "glDrawElementsIndirect",
    "glDrawArraysInstanced", "glDrawElementsInstanced", "glDrawRangeElements",
    "glUniform1iv", "glUniform2i", "glUniform3i", "glUniform4i",
    "glUniform2fv", "glUniform3fv", "glUniform2iv", "glUniform3iv", "glUniform4iv",
    "glUniform1ui", "glUniform2ui", "glUniform3ui", "glUniform4ui",
    "glUniform1uiv", "glUniform2uiv", "glUniform3uiv", "glUniform4uiv",
    "glUniformMatrix2fv", "glUniformMatrix3fv",
    "glUniformMatrix2x3fv", "glUniformMatrix3x2fv",
    "glUniformMatrix2x4fv", "glUniformMatrix4x2fv",
    "glUniformMatrix3x4fv", "glUniformMatrix4x3fv",
    // GLES 3.1 named-program uniform uploads: full glProgramUniform* family (writes address
    // a program object directly, invisible to the current-program uniform snapshot).
    "glProgramUniform1i", "glProgramUniform2i", "glProgramUniform3i", "glProgramUniform4i",
    "glProgramUniform1iv", "glProgramUniform2iv", "glProgramUniform3iv", "glProgramUniform4iv",
    "glProgramUniform1ui", "glProgramUniform2ui", "glProgramUniform3ui", "glProgramUniform4ui",
    "glProgramUniform1uiv", "glProgramUniform2uiv", "glProgramUniform3uiv", "glProgramUniform4uiv",
    "glProgramUniform1f", "glProgramUniform2f", "glProgramUniform3f", "glProgramUniform4f",
    "glProgramUniform1fv", "glProgramUniform2fv", "glProgramUniform3fv", "glProgramUniform4fv",
    "glProgramUniformMatrix2fv", "glProgramUniformMatrix3fv", "glProgramUniformMatrix4fv",
    "glProgramUniformMatrix2x3fv", "glProgramUniformMatrix3x2fv",
    "glProgramUniformMatrix2x4fv", "glProgramUniformMatrix4x2fv",
    "glProgramUniformMatrix3x4fv", "glProgramUniformMatrix4x3fv",
    // GLES 3.1 program-pipeline lifecycle (usage markers of the pipeline class; a bound
    // pipeline replaces the current-program uniform model entirely).
    "glGenProgramPipelines", "glDeleteProgramPipelines", "glBindProgramPipeline",
    "glUseProgramStages", "glActiveShaderProgram", "glValidateProgramPipeline",
    "glGetProgramPipelineiv", "glProgramParameteri",
    "glDrawBuffers",
    // Unhooked render-state setters (zero imports in libartemis.so / libartemis-v6.so;
    // fail closed to the producer path for future kernels instead of risking mis-clean reuse).
    "glBlendFunc", "glBlendEquation", "glBlendColor",
    "glDepthMask", "glDepthRangef", "glPolygonOffset",
    "glTexParameterf", "glTexParameterfv", "glTexParameteriv",
    "glStencilFuncSeparate", "glStencilOpSeparate", "glStencilMaskSeparate",
    "glSampleCoverage", "glLineWidth",
};

bool installArtemisOfficialGraphics(void* handle,ANativeActivity* activity,const std::string& path) {
    // The already-registered PLT contains our wrappers, not the original targets.
    // A second installation must not revalidate those slots or overwrite status.
    if (allowed.load()) return true;
    if (installing.test_and_set()) return false;
    struct InstallScope { ~InstallScope() { installing.clear(); } } installation;
    if (allowed.load()) return true;
    if (!activity || !activity->env || !handle) return false;
    JNIEnv* env=activity->env;
    jclass cls=loadArtemisHostClass(activity,"com.core.gl.ArtemisOfficialBridge");
    if(!cls){
        __android_log_print(ANDROID_LOG_ERROR,"ArtemisOfficial","Host bridge unavailable from Activity class loader");
        return false;
    }
    // Resolve the reporting entry first and clear each lookup exception before
    // making another JNI call. CheckJNI must not abort a recoverable fallback.
    auto method = [&](const char* name, const char* signature) -> jmethodID {
        jmethodID result = env->GetStaticMethodID(cls, name, signature);
        if (env->ExceptionCheck()) { env->ExceptionClear(); return nullptr; }
        return result;
    };
    statusMethod = method("nativeStatus", "(I)V");
    directRestoredMethod = method("directRestored", "()V");
    auto failed = [&]() {
        if (env->ExceptionCheck()) env->ExceptionClear();
        if (statusMethod) {
            env->CallStaticVoidMethod(cls, statusMethod, 1);
            if (env->ExceptionCheck()) env->ExceptionClear();
        }
        env->DeleteLocalRef(cls);
        return false;
    };
    if (!statusMethod || !directRestoredMethod) return failed();
    stageDisplay = reinterpret_cast<StageDisplay>(dlsym(handle, kDisplay));
    Dl_info info{};
    if (!stageDisplay || !dladdr(reinterpret_cast<void*>(stageDisplay), &info)) return failed();
    Image image;
    image.base = reinterpret_cast<uintptr_t>(info.dli_fbase);
    dl_iterate_phdr(findImage, &image);
    std::string identity;
    try { identity = loadedBuildId(image); }
    catch (const std::bad_alloc&) { return failed(); }
    if (identity.empty()) return failed();
    jmethodID verify = method("verifyLibrary", "(Ljava/lang/String;Ljava/lang/String;)Z");
    if (!verify) return failed();
    jstring file = env->NewStringUTF(path.c_str());
    if (env->ExceptionCheck() || !file) return failed();
    jstring buildId = env->NewStringUTF(identity.c_str());
    if (env->ExceptionCheck() || !buildId) { env->DeleteLocalRef(file); return failed(); }
    bool verified = env->CallStaticBooleanMethod(cls, verify, file, buildId);
    env->DeleteLocalRef(file);
    env->DeleteLocalRef(buildId);
    if (env->ExceptionCheck() || !verified) return failed();
    connectMethod = method("connect", "(Landroid/view/Surface;IIII)Landroid/view/Surface;");
    submittedMethod = method("submitted", "(JIIZ)V");
    healthyMethod = method("healthy", "()Z");
    outputSizeMethod = method("outputSize", "()J");
    disconnectMethod = method("disconnect", "()I");
    resizeMethod = method("resizeProducer", "(II)Z");
    renderStageMethod = method("renderStage", "(IIIIIZZJ)I");
    contextDestroyedMethod = method("contextDestroyed", "()V");
    if (!connectMethod || !submittedMethod || !healthyMethod || !outputSizeMethod || !disconnectMethod
            || !resizeMethod || !renderStageMethod || !contextDestroyedMethod)
        return failed();
    const Elf64_Dyn* dynamic=nullptr;size_t count=0;
    for(size_t i=0;i<image.count;++i)if(image.phdr[i].p_type==PT_DYNAMIC){dynamic=reinterpret_cast<const Elf64_Dyn*>(image.base+image.phdr[i].p_vaddr);count=image.phdr[i].p_memsz/sizeof(Elf64_Dyn);}
    const Elf64_Sym* symbols=nullptr;const char* strings=nullptr;const Elf64_Rela* relocs=nullptr;size_t bytes=0;bool rela=false;
    for(size_t i=0;dynamic && i<count && dynamic[i].d_tag!=DT_NULL;++i){auto& d=dynamic[i];
        if(d.d_tag==DT_SYMTAB)symbols=reinterpret_cast<const Elf64_Sym*>(address(image.base,d.d_un.d_ptr));
        if(d.d_tag==DT_STRTAB)strings=reinterpret_cast<const char*>(address(image.base,d.d_un.d_ptr));
        if(d.d_tag==DT_JMPREL)relocs=reinterpret_cast<const Elf64_Rela*>(address(image.base,d.d_un.d_ptr));
        if(d.d_tag==DT_PLTRELSZ)bytes=d.d_un.d_val;if(d.d_tag==DT_PLTREL)rela=d.d_un.d_val==DT_RELA;
    }
    std::vector<Change> changes;
    bool ok=symbols && strings && relocs && rela && bytes%sizeof(Elf64_Rela)==0;
    try {
        const std::map<std::string,void*> hooks={
            {kDisplay,reinterpret_cast<void*>(displayStage)},
            {"eglChooseConfig",reinterpret_cast<void*>(hookEglChooseConfig)},
            {"eglCreateContext",reinterpret_cast<void*>(hookEglCreateContext)},
            {"eglDestroyContext",reinterpret_cast<void*>(hookEglDestroyContext)},
            {"eglCreateWindowSurface",reinterpret_cast<void*>(createSurface)},
            {"eglDestroySurface",reinterpret_cast<void*>(destroySurface)},
            {"eglQuerySurface",reinterpret_cast<void*>(querySurface)},
            {"eglMakeCurrent",reinterpret_cast<void*>(makeCurrent)},
            {"eglSwapBuffers",reinterpret_cast<void*>(swap)},
            {"AMotionEvent_getX",reinterpret_cast<void*>(inputX)},
            {"AMotionEvent_getY",reinterpret_cast<void*>(inputY)},
            {"AInputQueue_getEvent",reinterpret_cast<void*>(inputEvent)},
            {"glBindFramebuffer",reinterpret_cast<void*>(hookGlBindFramebuffer)},
            {"glGetIntegerv",reinterpret_cast<void*>(hookGlGetIntegerv)},
            {"glFramebufferTexture2D",reinterpret_cast<void*>(hookGlFramebufferTexture2D)},
            {"glActiveTexture",reinterpret_cast<void*>(hookGlActiveTexture)},
            {"glBindTexture",reinterpret_cast<void*>(hookGlBindTexture)},
            {"glDeleteTextures",reinterpret_cast<void*>(hookGlDeleteTextures)},
            {"glTexImage2D",reinterpret_cast<void*>(hookGlTexImage2D)},
            {"glCompressedTexImage2D",reinterpret_cast<void*>(hookGlCompressedTexImage2D)},
            {"glCopyTexImage2D",reinterpret_cast<void*>(hookGlCopyTexImage2D)},
            {"glTexParameteri",reinterpret_cast<void*>(hookGlTexParameteri)},
            {"glBindBuffer",reinterpret_cast<void*>(hookGlBindBuffer)},
            {"glEnableVertexAttribArray",reinterpret_cast<void*>(hookGlEnableVertexAttribArray)},
            {"glDisableVertexAttribArray",reinterpret_cast<void*>(hookGlDisableVertexAttribArray)},
            {"glVertexAttribPointer",reinterpret_cast<void*>(hookGlVertexAttribPointer)},
            {"glUseProgram",reinterpret_cast<void*>(hookGlUseProgram)},
            {"glViewport",reinterpret_cast<void*>(hookGlViewport)},
            {"glScissor",reinterpret_cast<void*>(hookGlScissor)},
            {"glEnable",reinterpret_cast<void*>(hookGlEnable)},
            {"glDisable",reinterpret_cast<void*>(hookGlDisable)},
            {"glColorMask",reinterpret_cast<void*>(hookGlColorMask)},
            {"glClearColor",reinterpret_cast<void*>(hookGlClearColor)},
            {"glClear",reinterpret_cast<void*>(hookGlClear)},
            {"glBlendFuncSeparate",reinterpret_cast<void*>(hookGlBlendFuncSeparate)},
            {"glBlendEquationSeparate",reinterpret_cast<void*>(hookGlBlendEquationSeparate)},
            {"glStencilFunc",reinterpret_cast<void*>(hookGlStencilFunc)},
            {"glStencilOp",reinterpret_cast<void*>(hookGlStencilOp)},
            {"glStencilMask",reinterpret_cast<void*>(hookGlStencilMask)},
            {"glDepthFunc",reinterpret_cast<void*>(hookGlDepthFunc)},
            {"glUniform1f",reinterpret_cast<void*>(hookGlUniform1f)},
            {"glUniform1fv",reinterpret_cast<void*>(hookGlUniform1fv)},
            {"glUniform1i",reinterpret_cast<void*>(hookGlUniform1i)},
            {"glUniform2f",reinterpret_cast<void*>(hookGlUniform2f)},
            {"glUniform3f",reinterpret_cast<void*>(hookGlUniform3f)},
            {"glUniform4f",reinterpret_cast<void*>(hookGlUniform4f)},
            {"glUniform4fv",reinterpret_cast<void*>(hookGlUniform4fv)},
            {"glUniformMatrix4fv",reinterpret_cast<void*>(hookGlUniformMatrix4fv)},
            {"glDrawElements",reinterpret_cast<void*>(hookGlDrawElements)}
        };
        const std::map<std::string,void*> optionalHooks={
            {"glDrawArrays",reinterpret_cast<void*>(hookGlDrawArrays)},
            {"glTexSubImage2D",reinterpret_cast<void*>(hookGlTexSubImage2D)},
            {"glBufferData",reinterpret_cast<void*>(hookGlBufferData)},
            {"glBufferSubData",reinterpret_cast<void*>(hookGlBufferSubData)},
            {"glClearStencil",reinterpret_cast<void*>(hookGlClearStencil)},
            {"glClearDepthf",reinterpret_cast<void*>(hookGlClearDepthf)},
            {"glCopyTexSubImage2D",reinterpret_cast<void*>(hookGlCopyTexSubImage2D)},
            {"glBlitFramebuffer",reinterpret_cast<void*>(hookGlBlitFramebuffer)},
            {"glGenerateMipmap",reinterpret_cast<void*>(hookGlGenerateMipmap)},
            {"glTexStorage2D",reinterpret_cast<void*>(hookGlTexStorage2D)},
            {"glDeleteFramebuffers",reinterpret_cast<void*>(hookGlDeleteFramebuffers)},
            {"glDeleteBuffers",reinterpret_cast<void*>(hookGlDeleteBuffers)},
            {"glCullFace",reinterpret_cast<void*>(hookGlCullFace)},
            {"glFrontFace",reinterpret_cast<void*>(hookGlFrontFace)},
            {"glBindVertexArray",reinterpret_cast<void*>(hookGlBindVertexArray)},
            {"glBindVertexArrayOES",reinterpret_cast<void*>(hookGlBindVertexArrayOES)},
            {"glFramebufferRenderbuffer",reinterpret_cast<void*>(hookGlFramebufferRenderbuffer)},
            {"glDeleteProgram",reinterpret_cast<void*>(hookGlDeleteProgram)},
            {"glLinkProgram",reinterpret_cast<void*>(hookGlLinkProgram)}
        };
        std::map<std::string,int> found;
        std::vector<std::string> deniedImports;
        const std::set<std::string> deniedContentChangeImports(
                std::begin(kDeniedContentChangeImports), std::end(kDeniedContentChangeImports));
        if(ok)for(size_t i=0;i<bytes/sizeof(Elf64_Rela);++i) {
            if(ELF64_R_TYPE(relocs[i].r_info)!=R_AARCH64_JUMP_SLOT)continue;
            const char* name=strings+symbols[ELF64_R_SYM(relocs[i].r_info)].st_name;
            // Fail-closed gate: an imported content-change entry point outside the
            // verified hook surface refuses single-context mode (producer path fallback)
            // instead of silently leaving the command-stream hash blind to it.
            if(deniedContentChangeImports.count(name)) {
                if(std::find(deniedImports.begin(),deniedImports.end(),name)==deniedImports.end())
                    deniedImports.push_back(name);
                continue;
            }
            auto hook=hooks.find(name);
            if(hook==hooks.end()) {
                hook=optionalHooks.find(name);
                if(hook==optionalHooks.end())continue;
            }
            void* before=dlsym(handle,name);
            if(std::strcmp(name,"glBindVertexArrayOES")==0 && before) {
                realBindVertexArrayOES.store(reinterpret_cast<BindVertexArrayProc>(before), std::memory_order_release);
            }
            ok=collect(image,changes,reinterpret_cast<void**>(image.base+relocs[i].r_offset),before,hook->second)&&ok;++found[name];
        }
        std::string missingHooks;
        for(auto& hook:hooks) {
            if(!found[hook.first]) {
                ok=false;
                if(!missingHooks.empty())missingHooks+=',';
                missingHooks+=hook.first;
            }
        }
        if(!missingHooks.empty()) {
            __android_log_print(ANDROID_LOG_WARN,"ArtemisOfficial",
                    "Mandatory PLT hooks missing: %s",missingHooks.c_str());
        }
        if(!deniedImports.empty()) {
            std::string list;
            for(auto& d:deniedImports){if(!list.empty())list+=',';list+=d;}
            singleContextDenied=true;
            __android_log_print(ANDROID_LOG_WARN,"ArtemisOfficial",
                    "Single-context mode denied; unhooked GLES3 content-change imports: %s",list.c_str());
        }
    } catch (const std::bad_alloc&) {
        // All metadata allocations precede both global registration and PLT writes.
        return failed();
    }
    if (!ok) return failed();
    vm = activity->vm;
    jclass registered = static_cast<jclass>(env->NewGlobalRef(cls));
    if (env->ExceptionCheck() || !registered) {
        if (registered) env->DeleteGlobalRef(registered);
        return failed();
    }
    bridge = registered;
    env->DeleteLocalRef(cls);
    for(size_t i=0;i<changes.size();++i)if(!change(changes[i],false)) {
        bool restored=true;size_t applied=i+1;while(applied)restored=change(changes[--applied],true)&&restored;
        status(3);
        env->DeleteGlobalRef(bridge);
        bridge = nullptr;
        __android_log_print(ANDROID_LOG_ERROR,"ArtemisOfficial","Registration failed; rollback=%d",restored);return false;
    }
    allowed=true;status(5);
    __android_log_print(ANDROID_LOG_INFO,"ArtemisOfficial","Official stage hooks registered");
    return true;
}
